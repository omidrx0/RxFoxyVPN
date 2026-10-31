// Package app holds the connection logic of the panel: sign-in, server list, ping, connect /
// disconnect, settings. The HTTP layer (internal/server) is a thin JSON wrapper around it.
package app

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"rxvpn/internal/config"
	"rxvpn/internal/foxy"
	"rxvpn/internal/platform"
	"rxvpn/internal/sysproxy"
	"rxvpn/internal/tun"
)

// Loopback is the only address the local proxies listen on.
const Loopback = "127.0.0.1"

// Body is a decoded JSON request body (numbers are json.Number).
type Body map[string]any

// Options are the command line options that influence the app.
type Options struct {
	SocksPort int
	HTTPPort  int
	APIProxy  string
	TLS       foxy.TLSOptions
}

// App is the whole backend of the panel. All methods are safe for concurrent use.
type App struct {
	opts     Options
	cp       *foxy.ControlPlane
	account  *foxy.Account
	accounts *config.AccountStore
	stats    *foxy.Stats
	runtime  tun.Paths
	tunProc  tun.Process

	ctx    context.Context
	cancel context.CancelFunc

	opMu sync.Mutex // one connect / disconnect at a time

	mu                        sync.RWMutex // protects the fields below (short critical sections only)
	connSettings              config.Connection
	settings                  config.Settings
	connected                 bool
	started                   time.Time
	upstream                  *foxy.Upstream
	listeners                 []net.Listener
	pingIfIndex               int // 0 = unknown
	pendingSession            string
	pendingEmail              string
	pendingVerificationMethod string
	quotaLimit                *int
	quotaRemaining            *int
	connectionSaveMu          sync.Mutex
	settingsMu                sync.Mutex

	listMu    sync.Mutex
	countries map[string]*foxy.Country

	quotaRefreshMu sync.Mutex
	quotaWake      chan struct{}
}

// New creates the app, restores a system proxy left behind by a crash and starts the
// background refreshers.
func New(opts Options) (*App, error) {
	cp, err := foxy.NewControlPlane(opts.APIProxy)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	a := &App{
		opts:         opts,
		cp:           cp,
		account:      foxy.NewAccount(cp, ""),
		accounts:     config.NewAccountStore(config.SavedAccountsPath(), platform.DPAPI{}),
		stats:        &foxy.Stats{},
		runtime:      tun.ResolveRuntime(),
		ctx:          ctx,
		cancel:       cancel,
		connSettings: config.LoadConnection(),
		settings:     config.LoadSettings(),
		countries:    map[string]*foxy.Country{},
		quotaWake:    make(chan struct{}, 1),
	}
	if limit, remaining := a.account.Quota(); limit != nil && remaining != nil {
		a.quotaLimit, a.quotaRemaining = limit, remaining
	}
	if err := sysproxy.Restore(config.ProxyBackupPath()); err != nil {
		slog.Error("could not restore a previous Windows proxy setting", "err", err)
	}
	go a.serverListRefresher()
	go a.quotaRefresher()
	return a, nil
}

// Close disconnects and stops the background work.
func (a *App) Close() {
	a.opMu.Lock()
	a.doDisconnect()
	a.opMu.Unlock()
	a.cancel()
}

// Account exposes the account (the tray needs HasSession / LastCountry).
func (a *App) Account() *foxy.Account { return a.account }

// Connected reports whether a connection is up.
func (a *App) Connected() bool {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.connected
}

// ConnectionMode returns tun | system | manual.
func (a *App) ConnectionMode() string {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.connSettings.Mode
}

// ---------------------------------------------------------------- helpers

func (a *App) setUpstream(u *foxy.Upstream) {
	a.mu.Lock()
	a.upstream = u
	a.mu.Unlock()
}

func (a *App) getCountry(code string) *foxy.Country {
	a.listMu.Lock()
	defer a.listMu.Unlock()
	return a.countries[code]
}

// CountriesLoaded reports whether the server list has been downloaded.
func (a *App) CountriesLoaded() bool {
	a.listMu.Lock()
	defer a.listMu.Unlock()
	return len(a.countries) > 0
}

// Country returns one country by code (nil when unknown).
func (a *App) Country(code string) *foxy.Country { return a.getCountry(code) }

// SortedCountries returns the loaded countries in display order.
func (a *App) SortedCountries() []*foxy.Country {
	a.listMu.Lock()
	defer a.listMu.Unlock()
	return foxy.SortedCountries(a.countries)
}

// EnsureCountries downloads the server list when it is empty (or force is set).
func (a *App) EnsureCountries(ctx context.Context, force bool) (map[string]*foxy.Country, error) {
	a.listMu.Lock()
	defer a.listMu.Unlock()
	if force || len(a.countries) == 0 {
		fresh, err := foxy.FetchCountries(ctx, a.cp)
		if err != nil {
			return nil, err
		}
		a.countries = fresh
	}
	return a.countries, nil
}

func (a *App) serverListRefresher() {
	t := time.NewTicker(foxy.ServerListRefresh)
	defer t.Stop()
	for {
		select {
		case <-a.ctx.Done():
			return
		case <-t.C:
		}
		ctx, cancel := context.WithTimeout(a.ctx, 60*time.Second)
		fresh, err := foxy.FetchCountries(ctx, a.cp)
		cancel()
		if err != nil {
			slog.Warn("server list refresh failed", "err", err)
			continue
		}
		a.listMu.Lock()
		a.countries = fresh
		a.listMu.Unlock()
		a.mu.RLock()
		up := a.upstream
		a.mu.RUnlock()
		if up != nil {
			if cur := up.Country(); cur != nil {
				if c, ok := fresh[cur.Code]; ok {
					up.UpdateTargets(c)
				}
			}
		}
	}
}

// ---------------------------------------------------------------- quota

func (a *App) wakeQuota() {
	select {
	case a.quotaWake <- struct{}{}:
	default:
	}
}

func (a *App) quotaRefresher() {
	for {
		a.refreshQuota()
		wait := 120 * time.Second
		if a.Connected() {
			wait = 60 * time.Second
		}
		select {
		case <-a.ctx.Done():
			return
		case <-a.quotaWake:
		case <-time.After(wait):
		}
	}
}

func (a *App) refreshQuota() {
	if !a.quotaRefreshMu.TryLock() {
		return
	}
	defer a.quotaRefreshMu.Unlock()
	if !a.account.HasSession() {
		return
	}
	ctx, cancel := context.WithTimeout(a.ctx, 45*time.Second)
	defer cancel()
	usage, err := a.account.FetchQuota(ctx)
	if err != nil {
		slog.Info("quota refresh failed", "err", err)
		return
	}
	if usage != nil {
		a.rememberQuota(usage.Limit, usage.Remaining)
	}
}

func (a *App) scheduleQuotaRefresh() { go a.refreshQuota() }

// rememberQuota stores a plausible allowance and persists it when it changed.
func (a *App) rememberQuota(limit, remaining int) {
	if limit <= 0 || remaining < 0 || remaining > limit {
		return
	}
	a.mu.Lock()
	if a.quotaLimit != nil && a.quotaRemaining != nil && *a.quotaLimit == limit && *a.quotaRemaining == remaining {
		a.mu.Unlock()
		return
	}
	l, r := limit, remaining
	a.quotaLimit, a.quotaRemaining = &l, &r
	a.mu.Unlock()
	a.account.SetQuota(&l, &r)
}

func (a *App) rememberPass(p *foxy.ProxyPass) {
	if p != nil && p.QuotaLimit != nil && p.QuotaRemaining != nil {
		a.rememberQuota(*p.QuotaLimit, *p.QuotaRemaining)
	}
}

func (a *App) clearQuota() {
	a.mu.Lock()
	a.quotaLimit, a.quotaRemaining = nil, nil
	a.mu.Unlock()
	a.account.SetQuota(nil, nil)
}

// ---------------------------------------------------------------- body helpers

func str(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return x
	default:
		return fmt.Sprint(x)
	}
}

// intOf reads a JSON integer (and nothing else: no floats, no strings, no booleans).
func intOf(v any) (int, bool) {
	switch x := v.(type) {
	case int:
		return x, true
	case float64:
		if x == float64(int(x)) {
			return int(x), true
		}
	case interface{ String() string }: // json.Number
		n, err := strconv.Atoi(strings.TrimSpace(x.String()))
		if err == nil {
			return n, true
		}
	}
	return 0, false
}

func ptr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
