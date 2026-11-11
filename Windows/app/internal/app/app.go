// Package app holds the connection logic of the panel: sign-in, server list, ping, connect /
// disconnect, relay, announcements, and settings. The HTTP layer (internal/server) is a thin JSON
// wrapper around it.
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
	SocksPort        int
	HTTPPort         int
	APIProxy         string
	TLS              foxy.TLSOptions
	LaunchFxaWebView func(ipcURL, ipcSecret, email string) (func(), error)
}

// App is the whole backend of the panel. All methods are safe for concurrent use.
type App struct {
	opts          Options
	cp            *foxy.ControlPlane
	account       *foxy.Account
	accounts      *config.AccountStore
	relayStore    *config.RelayStore
	annStore      *config.AnnouncementStore
	sharedRelays  *foxy.SharedRelayManager
	healthChecker *foxy.RelayHealthChecker
	captchaBroker *foxy.CaptchaBroker
	browserAuth   *foxy.BrowserAuthCoordinator
	stats         *foxy.Stats
	runtime       tun.Paths
	tunProc       tun.Process

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
	pendingUnblockEmail       string
	pendingPassword           string
	lastInteractiveEmail      string
	quotaInfo                 foxy.QuotaInfo
	connectionSaveMu          sync.Mutex
	settingsMu                sync.Mutex

	listMu    sync.Mutex
	countries map[string]*foxy.Country

	quotaRefreshMu sync.Mutex
	quotaWake      chan struct{}
	manifestWake   chan struct{}
}

func toFoxyRelayConfig(rc config.RelayConfig) foxy.RelayConfig {
	return foxy.RelayConfig{
		SharedEnabled:            rc.SharedEnabled,
		PersonalEnabled:          rc.PersonalEnabled,
		PersonalURL:              rc.PersonalURL,
		PersonalOnly:             rc.PersonalOnly,
		SharedSecret:             rc.SharedSecret,
		ManifestPrimaryOverride:  rc.ManifestPrimaryOverride,
		ManifestFallbackOverride: rc.ManifestFallbackOverride,
	}
}

// New creates the app, restores a system proxy left behind by a crash and starts the
// background refreshers.
func New(opts Options) (*App, error) {
	cp, err := foxy.NewControlPlane(opts.APIProxy)
	if err != nil {
		return nil, err
	}
	relayStore := config.NewRelayStore(config.RelayConfigPath(), platform.DPAPI{})
	annStore := config.NewAnnouncementStore(config.AnnouncementsPath())

	cfgSource := func() foxy.RelayConfig {
		return toFoxyRelayConfig(relayStore.Load())
	}
	sharedMgr, err := foxy.NewSharedRelayManager(opts.TLS, cfgSource)
	if err != nil {
		return nil, err
	}
	relayTr, err := foxy.NewRelayTransport(opts.TLS)
	if err != nil {
		return nil, err
	}
	healthChecker, err := foxy.NewRelayHealthChecker(opts.TLS)
	if err != nil {
		return nil, err
	}
	cp.SetRelayCoordinator(foxy.NewRelayFallbackCoordinator(cfgSource, relayTr, sharedMgr))

	ctx, cancel := context.WithCancel(context.Background())
	account := foxy.NewAccount(cp, "")
	a := &App{
		opts:          opts,
		cp:            cp,
		account:       account,
		accounts:      config.NewAccountStore(config.SavedAccountsPath(), platform.DPAPI{}),
		relayStore:    relayStore,
		annStore:      annStore,
		sharedRelays:  sharedMgr,
		healthChecker: healthChecker,
		captchaBroker: cp.CaptchaBroker(),
		stats:         &foxy.Stats{},
		runtime:       tun.ResolveRuntime(),
		ctx:           ctx,
		cancel:        cancel,
		connSettings:  config.LoadConnection(),
		settings:      config.LoadSettings(),
		quotaInfo:     account.CachedQuotaInfo(),
		countries:     map[string]*foxy.Country{},
		quotaWake:     make(chan struct{}, 1),
		manifestWake:  make(chan struct{}, 1),
	}
	a.browserAuth = foxy.NewBrowserAuthCoordinator(
		account,
		foxy.BrowserAuthStarter{
			LaunchWebViewHelper: opts.LaunchFxaWebView,
			OpenExternalURL:     platform.OpenURL,
		},
		func(_ context.Context, email string) {
			a.onPostLoginComplete(email)
		},
	)

	if err := sysproxy.Restore(config.ProxyBackupPath()); err != nil {
		slog.Error("could not restore a previous Windows proxy setting", "err", err)
	}
	go a.serverListRefresher()
	go a.quotaRefresher()
	go a.manifestRefresher()
	return a, nil
}

// Close disconnects and stops the background work.
func (a *App) Close() {
	if a.browserAuth != nil {
		a.browserAuth.Cancel()
	}
	if a.captchaBroker != nil {
		a.captchaBroker.Cancel()
	}
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

// ---------------------------------------------------------------- manifest & announcements

func (a *App) wakeManifest() {
	select {
	case a.manifestWake <- struct{}{}:
	default:
	}
}

func (a *App) manifestRefresher() {
	for {
		rc := a.relayStore.Load()
		if rc.SharedEnabled || a.account.HasSession() {
			ctx, cancel := context.WithTimeout(a.ctx, 25*time.Second)
			a.sharedRelays.Refresh(ctx, false)
			cancel()
		}
		select {
		case <-a.ctx.Done():
			return
		case <-a.manifestWake:
		case <-time.After(10 * time.Minute):
		}
	}
}

// ---------------------------------------------------------------- quota (4-state Guardian model)

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
	info, err := a.account.FetchQuotaInfo(ctx)
	if err != nil {
		slog.Info("quota refresh failed", "err", err)
		a.mu.Lock()
		if a.quotaInfo.State == "" || a.quotaInfo.State == foxy.QuotaStateUnknown {
			a.quotaInfo = foxy.QuotaInfo{State: foxy.QuotaStateError}
		}
		a.mu.Unlock()
		return
	}
	a.setQuotaInfo(info)
}

func (a *App) scheduleQuotaRefresh() { go a.refreshQuota() }

func (a *App) setQuotaInfo(info foxy.QuotaInfo) {
	a.mu.Lock()
	a.quotaInfo = info
	a.mu.Unlock()
	a.account.SetQuotaInfo(info)
}

func (a *App) rememberPass(p *foxy.ProxyPass) {
	if p == nil {
		return
	}
	if p.QuotaUnlimited {
		a.mu.RLock()
		sub := a.quotaInfo.Subscribed
		a.mu.RUnlock()
		a.setQuotaInfo(foxy.QuotaInfo{
			State:      foxy.QuotaStateUnlimited,
			Subscribed: sub,
		})
		return
	}
	if p.QuotaLimit != nil && p.QuotaRemaining != nil && *p.QuotaLimit > 0 && *p.QuotaRemaining >= 0 {
		limit, rem := *p.QuotaLimit, *p.QuotaRemaining
		used := max(int64(0), limit-rem)
		a.mu.RLock()
		cur := a.quotaInfo
		a.mu.RUnlock()
		// Never overwrite an already-established Unlimited state with a nominal byte header.
		if cur.State == foxy.QuotaStateUnlimited {
			return
		}
		a.setQuotaInfo(foxy.QuotaInfo{
			State:          foxy.QuotaStateLimited,
			Subscribed:     cur.Subscribed,
			LimitBytes:     &limit,
			RemainingBytes: &rem,
			UsedBytes:      &used,
			ResetAt:        cur.ResetAt,
		})
	}
}

func (a *App) clearQuota() {
	empty := foxy.QuotaInfo{State: foxy.QuotaStateUnknown}
	a.mu.Lock()
	a.quotaInfo = empty
	a.mu.Unlock()
	a.account.SetQuotaInfo(empty)
}

// onPostLoginComplete is the single unified convergence point shared by Direct Login and
// Browser Login (both isolated WebView2 WebChannel and External Browser OAuth PKCE).
func (a *App) onPostLoginComplete(email string) {
	a.mu.Lock()
	a.pendingSession = ""
	a.pendingEmail = ""
	a.pendingVerificationMethod = ""
	a.pendingUnblockEmail = ""
	a.pendingPassword = ""
	a.mu.Unlock()

	if clean := foxy.NormalizeEmail(email); clean != "" {
		a.account.SetEmail(clean)
	}
	a.quotaRefreshMu.Lock()
	a.clearQuota()
	a.quotaRefreshMu.Unlock()
	a.scheduleQuotaRefresh()
	a.wakeManifest()
	go func() {
		ctx, cancel := context.WithTimeout(a.ctx, 45*time.Second)
		defer cancel()
		_, _ = a.EnsureCountries(ctx, false)
	}()
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

func boolOf(v any) (bool, bool) {
	b, ok := v.(bool)
	return b, ok
}

func ptr(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}
