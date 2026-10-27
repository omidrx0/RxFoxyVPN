package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"time"

	"rxvpn/internal/apierr"
	"rxvpn/internal/config"
	"rxvpn/internal/foxy"
	"rxvpn/internal/locale"
	"rxvpn/internal/platform"
	"rxvpn/internal/sysproxy"
)

const connectTimeout = 120 * time.Second

// PortError means the local proxy port could not be opened.
type PortError struct{ Err error }

func (e *PortError) Error() string { return e.Err.Error() }
func (e *PortError) Unwrap() error { return e.Err }

// Connect connects to the country with the given code (or switches to it when connected).
func (a *App) Connect(ctx context.Context, body Body) (any, error) {
	code := strings.ToUpper(str(body["code"]))
	if !a.opMu.TryLock() {
		return nil, apierr.New(locale.Tr("عملیات قبلی هنوز تمام نشده.", "The previous operation is still running."), 409)
	}
	defer a.opMu.Unlock()

	ctx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()
	if _, err := a.EnsureCountries(ctx, false); err != nil {
		return nil, err
	}
	if err := a.doConnect(ctx, code); err != nil {
		return nil, err
	}
	return a.Status(ctx, nil)
}

// Disconnect closes the tunnel and undoes the connection mode (system proxy, TUN).
func (a *App) Disconnect(ctx context.Context, _ Body) (any, error) {
	a.opMu.Lock()
	a.doDisconnect()
	a.opMu.Unlock()
	return a.Status(ctx, nil)
}

func (a *App) doConnect(ctx context.Context, code string) error {
	a.mu.RLock()
	cs, settings, connected, up := a.connSettings, a.settings, a.connected, a.upstream
	a.mu.RUnlock()

	switch cs.Mode {
	case "system":
		if !platform.IsWindows {
			return sysproxy.NewModeError("Windows system proxy is only available on Windows")
		}
	case "tun":
		if !platform.IsWindows {
			return sysproxy.NewModeError("TUN mode is only available on Windows")
		}
		if !platform.IsAdmin() {
			return sysproxy.NewModeError("Run RXFOXYVPN as Administrator to start TUN mode")
		}
		if !a.runtime.Exists() {
			return sysproxy.NewModeError("TUN runtime is missing; rebuild RXFOXYVPN to install sing-box and Wintun")
		}
	}
	country := a.getCountry(code)
	if country == nil {
		return apierr.New(locale.Tr("این کشور در فهرست سرورها نیست.", "This country is not in the server list."), 404)
	}

	if up == nil {
		var err error
		up, err = foxy.NewUpstream(foxy.NewTokenSource(a.account), a.opts.TLS)
		if err != nil {
			return err
		}
		a.setUpstream(up)
	}
	var prev *foxy.Country
	if connected {
		prev = up.Country()
	}
	// rollback: stay on the old server like `use` in the CLI, or drop the half-open upstream
	rollback := func() {
		if prev != nil {
			up.SetCountry(prev)
		} else {
			up.Close()
			a.setUpstream(nil)
		}
	}

	up.SetCountry(country)
	if err := up.Preflight(ctx); err != nil {
		rollback()
		return err
	}
	a.account.SetLastCountry(code)
	if !connected {
		a.stats.ResetTraffic()
	}

	a.mu.RLock()
	haveListener := len(a.listeners) > 0
	a.mu.RUnlock()
	if !haveListener {
		if err := a.startConnectionMode(up, cs, settings); err != nil {
			a.stopConnectionMode()
			rollback()
			var pe *PortError
			if errors.As(err, &pe) {
				return apierr.New(locale.Tr("پورت پراکسی باز نشد: "+pe.Error(),
					"Could not open the proxy port: "+pe.Error()), 409)
			}
			return err
		}
	}

	a.mu.Lock()
	if !a.connected {
		a.started = time.Now()
	}
	a.connected = true
	a.mu.Unlock()
	if prev == nil {
		a.wakeQuota()
	}
	return nil
}

// listenerSpec returns the protocol and port of the local proxy for the current mode.
func (a *App) listenerSpec(cs config.Connection) (string, int, error) {
	switch cs.Mode {
	case "manual":
		return cs.Protocol, cs.Port, nil
	case "system":
		if !platform.IsWindows {
			return "", 0, sysproxy.NewModeError("Windows system proxy is only available on Windows")
		}
		return "http", a.opts.HTTPPort, nil
	}
	return "socks5", a.opts.SocksPort, nil
}

// startConnectionMode opens the local listener and applies the mode (system proxy / TUN).
func (a *App) startConnectionMode(up *foxy.Upstream, cs config.Connection, settings config.Settings) error {
	protocol, port, err := a.listenerSpec(cs)
	if err != nil {
		return err
	}
	ln, err := net.Listen("tcp", net.JoinHostPort(Loopback, strconv.Itoa(port)))
	if err != nil {
		return &PortError{Err: err}
	}
	handler := foxy.HandleHTTP
	if protocol == "socks5" {
		handler = foxy.HandleSOCKS
	}
	a.mu.Lock()
	a.listeners = append(a.listeners, ln)
	a.mu.Unlock()
	go foxy.Serve(ln, func(c net.Conn) { handler(a.ctx, c, up, a.stats) })

	switch cs.Mode {
	case "system":
		return sysproxy.Apply(port, config.ProxyBackupPath())
	case "tun":
		a.mu.RLock()
		idx := a.pingIfIndex
		a.mu.RUnlock()
		if idx == 0 {
			if i, ok := platform.DefaultIPv4InterfaceIndex(); ok {
				idx = i
				a.mu.Lock()
				a.pingIfIndex = i
				a.mu.Unlock()
			} else {
				slog.Warn("could not determine physical interface for direct ping probes")
			}
		}
		return a.tunProc.Start(a.runtime, config.TunConfigPath(), config.TunLogPath(), port, settings)
	}
	return nil
}

func (a *App) doDisconnect() {
	a.stopConnectionMode()
	a.mu.Lock()
	a.pingIfIndex = 0
	up := a.upstream
	a.mu.Unlock()
	if up != nil {
		a.rememberPass(up.Tokens().Current())
		up.Close()
	}
	a.mu.Lock()
	a.upstream = nil
	a.connected = false
	a.mu.Unlock()
	a.wakeQuota()
}

// stopConnectionMode stops TUN, restores the system proxy and closes the local listeners.
func (a *App) stopConnectionMode() {
	a.tunProc.Stop()
	if err := sysproxy.Restore(config.ProxyBackupPath()); err != nil {
		slog.Error("could not restore the Windows proxy setting", "err", err)
	}
	a.mu.Lock()
	lns := a.listeners
	a.listeners = nil
	a.mu.Unlock()
	for _, l := range lns {
		_ = l.Close() // open client connections end together with the upstream
	}
}

// ---------------------------------------------------------------- status

// CountryInfo is a country as the panel shows it.
type CountryInfo struct {
	Code string `json:"code"`
	Name string `json:"name"`
}

// Status is the answer of /api/status (polled by the panel every second or so).
type Status struct {
	LoggedIn             bool              `json:"logged_in"`
	PendingCode          bool              `json:"pending_code"`
	State                string            `json:"state"`
	Country              *CountryInfo      `json:"country"`
	Host                 *string           `json:"host"`
	Socks                *string           `json:"socks"`
	HTTP                 *string           `json:"http"`
	Active               int64             `json:"active"`
	Total                int64             `json:"total"`
	UploadedBytes        int64             `json:"uploaded_bytes"`
	DownloadedBytes      int64             `json:"downloaded_bytes"`
	Uptime               int64             `json:"uptime"`
	LastCountry          *string           `json:"last_country"`
	QuotaLimit           *int              `json:"quota_limit"`
	QuotaRemaining       *int              `json:"quota_remaining"`
	Connection           config.Connection `json:"connection"`
	SystemProxyAvailable bool              `json:"system_proxy_available"`
	TunAvailable         bool              `json:"tun_available"`
	TunAdmin             bool              `json:"tun_admin"`
	Lang                 string            `json:"lang"`
}

// Status returns the current state of the app.
func (a *App) Status(_ context.Context, _ Body) (any, error) {
	return a.StatusNow(), nil
}

// StatusNow is Status without the interface plumbing.
func (a *App) StatusNow() Status {
	a.mu.RLock()
	connected, up, cs := a.connected, a.upstream, a.connSettings
	started := a.started
	a.mu.RUnlock()

	if up != nil {
		a.rememberPass(up.Tokens().Current())
	}
	a.mu.RLock()
	qLimit, qRemaining := a.quotaLimit, a.quotaRemaining
	pending := a.pendingSession != ""
	a.mu.RUnlock()

	st := Status{
		LoggedIn:             a.account.HasSession(),
		PendingCode:          pending,
		State:                "idle",
		Active:               a.stats.Active.Load(),
		Total:                a.stats.Total.Load(),
		UploadedBytes:        a.stats.Uploaded.Load(),
		DownloadedBytes:      a.stats.Downloaded.Load(),
		LastCountry:          ptr(a.account.LastCountry()),
		QuotaLimit:           qLimit,
		QuotaRemaining:       qRemaining,
		Connection:           cs,
		SystemProxyAvailable: platform.IsWindows,
		TunAvailable:         platform.IsWindows && a.runtime.Exists(),
		TunAdmin:             platform.IsAdmin(),
		Lang:                 locale.Get(),
	}
	if connected && up != nil {
		st.State = "connected"
		st.Uptime = int64(time.Since(started).Seconds())
		if c := up.Country(); c != nil {
			st.Country = &CountryInfo{Code: c.Code, Name: foxy.DisplayName(c)}
		}
		if t, ok := up.Current(); ok {
			h := net.JoinHostPort(t.Host, strconv.Itoa(t.Port))
			st.Host = &h
		}
		addr := fmt.Sprintf("%s:%d", Loopback, cs.Port)
		switch cs.Mode {
		case "manual":
			if cs.Protocol == "socks5" {
				st.Socks = &addr
			} else {
				st.HTTP = &addr
			}
		case "system":
			h := fmt.Sprintf("%s:%d", Loopback, a.opts.HTTPPort)
			st.HTTP = &h
		}
	}
	return st
}

// SetConnection changes the connection mode / protocol / port (only while disconnected).
func (a *App) SetConnection(_ context.Context, body Body) (any, error) {
	a.mu.RLock()
	cur := a.connSettings
	connected := a.connected
	a.mu.RUnlock()

	mode, protocol, port := cur.Mode, cur.Protocol, cur.Port
	if v, ok := body["mode"]; ok {
		mode = str(v)
	}
	if v, ok := body["protocol"]; ok {
		protocol = str(v)
	}
	if v, ok := body["port"]; ok {
		p, isInt := intOf(v)
		if !isInt {
			p = 0
		}
		port = p
	}
	if !config.ValidMode(mode) {
		return nil, apierr.New(locale.Tr("حالت اتصال نامعتبر است.", "Invalid connection mode."))
	}
	if !config.ValidProtocol(protocol) {
		return nil, apierr.New(locale.Tr("پروتکل پراکسی نامعتبر است.", "Invalid proxy protocol."))
	}
	if port < 1 || port > 65535 {
		return nil, apierr.New(locale.Tr("پورت باید عددی بین 1 تا 65535 باشد.", "Port must be between 1 and 65535."))
	}
	if connected {
		return nil, apierr.New(locale.Tr("برای تغییر حالت، ابتدا اتصال را قطع کن.",
			"Disconnect before changing the connection mode."), 409)
	}
	if mode == "system" && !platform.IsWindows {
		return nil, apierr.New(locale.Tr("پروکسی سیستم در این سیستم‌عامل پشتیبانی نمی‌شود.",
			"System proxy mode is not supported on this operating system."), 501)
	}
	if mode == "tun" && !platform.IsWindows {
		return nil, apierr.New(locale.Tr("حالت TUN در این سیستم‌عامل پشتیبانی نمی‌شود.",
			"TUN mode is not supported on this operating system."), 501)
	}
	updated := config.Connection{Mode: mode, Protocol: protocol, Port: port}
	a.connectionSaveMu.Lock()
	err := config.SaveConnection(updated)
	a.connectionSaveMu.Unlock()
	if err != nil {
		return nil, err
	}
	a.mu.Lock()
	a.connSettings = updated
	a.mu.Unlock()
	return map[string]any{"connection": updated}, nil
}
