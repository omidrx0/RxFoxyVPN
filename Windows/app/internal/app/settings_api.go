package app

import (
	"context"
	"errors"
	"io"
	"net/url"
	"os"
	"strings"
	"time"

	"rxvpn/internal/apierr"
	"rxvpn/internal/config"
	"rxvpn/internal/foxy"
	"rxvpn/internal/locale"
	"rxvpn/internal/platform"
	"rxvpn/internal/tun"
)

func (a *App) saveLangSafe() error {
	return config.SaveLang()
}

// GetSettings returns the user settings, the factory defaults and whether autostart works here.
func (a *App) GetSettings(context.Context, Body) (any, error) {
	a.mu.RLock()
	s := a.settings
	a.mu.RUnlock()
	return map[string]any{
		"settings":            s,
		"defaults":            config.DefaultSettings(),
		"autostart_available": platform.IsWindows,
		"relay":               a.relayPublicPayload(),
	}, nil
}

func (a *App) applyAutostart(enabled bool) error {
	if !platform.IsWindows {
		return apierr.New(locale.Tr("اجرای خودکار فقط در ویندوز پشتیبانی می‌شود.",
			"Start with Windows is only supported on Windows."), 501)
	}
	return platform.SetAutostart(enabled)
}

// SetSettings validates and stores a set of changed settings. The reply tells the panel whether
// the TUN connection has to be restarted for them to take effect.
func (a *App) SetSettings(_ context.Context, body Body) (any, error) {
	changes := map[string]any(body)
	if inner, ok := body["settings"].(map[string]any); ok {
		changes = inner
	}
	a.settingsMu.Lock()
	defer a.settingsMu.Unlock()

	a.mu.RLock()
	current := a.settings
	a.mu.RUnlock()

	updated, err := config.Sanitize(changes, current)
	if err != nil {
		if se, ok := err.(*config.SettingError); ok {
			return nil, apierr.New(locale.Tr(se.FA, se.EN))
		}
		return nil, err
	}
	if updated.Autostart != current.Autostart {
		if err := a.applyAutostart(updated.Autostart); err != nil {
			return nil, err
		}
	}
	engineChanged := config.EngineChanged(updated, current)
	if err := config.SaveSettings(updated); err != nil {
		return nil, err
	}
	a.mu.Lock()
	a.settings = updated
	connected, mode := a.connected, a.connSettings.Mode
	a.mu.Unlock()
	return map[string]any{
		"settings":       updated,
		"restart_needed": engineChanged && connected && mode == "tun",
	}, nil
}

// ResetSettings restores the factory defaults.
func (a *App) ResetSettings(context.Context, Body) (any, error) {
	a.settingsMu.Lock()
	defer a.settingsMu.Unlock()
	defaults := config.DefaultSettings()
	a.mu.RLock()
	hadAutostart := a.settings.Autostart
	a.mu.RUnlock()
	if hadAutostart {
		if err := a.applyAutostart(false); err != nil {
			return nil, err
		}
	}
	if err := config.SaveSettings(defaults); err != nil {
		return nil, err
	}
	a.mu.Lock()
	a.settings = defaults
	connected, mode := a.connected, a.connSettings.Mode
	a.mu.Unlock()
	return map[string]any{
		"settings":       defaults,
		"restart_needed": connected && mode == "tun",
	}, nil
}

// ---------------------------------------------------------------- Relay Settings & Health Check

func (a *App) relayPublicPayload() map[string]any {
	rc := a.relayStore.Load()
	pub := config.PublicRelayView(rc)
	candidates := 0
	if a.sharedRelays != nil {
		candidates = a.sharedRelays.CandidateCount()
	}
	return map[string]any{
		"shared_enabled":        pub.SharedEnabled,
		"personal_enabled":      pub.PersonalEnabled,
		"personal_only":         pub.PersonalOnly,
		"has_personal_url":      pub.PersonalURLSet,
		"personal_host":         pub.PersonalHost,
		"has_shared_secret":     strings.TrimSpace(rc.SharedSecret) != "",
		"shared_candidate_pool": candidates,
	}
}

func localizedPersonalURLError(err error) *apierr.Error {
	var pve *foxy.PersonalURLValidationError
	if errors.As(err, &pve) {
		switch pve.Kind {
		case foxy.PersonalURLNotHTTPS:
			return apierr.New(locale.Tr("نشانی رله شخصی باید با https:// آغاز شود.",
				"Personal relay URL must start with https://."))
		case foxy.PersonalURLNoSecret:
			return apierr.New(locale.Tr("نشانی رله شخصی باید شامل پارامتر ?secret=... غیرخالی باشد.",
				"Personal relay URL must include a non-empty ?secret=... query parameter."))
		}
	}
	return apierr.New(locale.Tr(
		"نشانی رله شخصی نامعتبر است (باید https، بدون # و بدون پارامترهای رزروشده url/health باشد).",
		"Invalid personal relay URL (must be https without # or reserved url/health query parameters).",
	))
}

// GetRelaySettings returns the secret-redacted Relay configuration view.
func (a *App) GetRelaySettings(context.Context, Body) (any, error) {
	return a.relayPublicPayload(), nil
}

// SetRelaySettings updates Shared Relay, Personal Relay, Personal-Only, Personal URL, and Shared Secret.
// Matching Android's SettingsStore:
//   - Toggling personal_enabled=false retains the encrypted personal_url on disk.
//   - Passing personal_url="" clears the stored personal_url and turns off personal_enabled & personal_only.
func (a *App) SetRelaySettings(_ context.Context, body Body) (any, error) {
	before := a.relayStore.Load()

	after, err := a.relayStore.Update(func(next *config.RelayConfig) error {
		if v, ok := body["shared_enabled"]; ok {
			if b, isBool := boolOf(v); isBool {
				next.SharedEnabled = b
			}
		}
		if v, ok := body["personal_url"]; ok {
			trimmed := strings.TrimSpace(str(v))
			if trimmed == "" {
				next.PersonalURL = ""
				next.PersonalEnabled = false
				next.PersonalOnly = false
			} else {
				parsed, vErr := foxy.ValidatePersonalRelayURL(trimmed)
				if vErr != nil {
					return localizedPersonalURLError(vErr)
				}
				next.PersonalURL = parsed.String()
			}
		}
		if v, ok := body["personal_enabled"]; ok {
			if b, isBool := boolOf(v); isBool {
				if b && strings.TrimSpace(next.PersonalURL) == "" {
					return apierr.New(locale.Tr("ابتدا نشانی رله شخصی معتبر را وارد و ذخیره کنید.",
						"Enter and save a valid personal relay URL first."))
				}
				next.PersonalEnabled = b
				if !b {
					next.PersonalOnly = false
				}
			}
		}
		if v, ok := body["personal_only"]; ok {
			if b, isBool := boolOf(v); isBool {
				if b && (!next.PersonalEnabled || strings.TrimSpace(next.PersonalURL) == "") {
					return apierr.New(locale.Tr("حالت «فقط رله شخصی» نیازمند فعال بودن رله شخصی است.",
						"Personal-only mode requires an active personal relay."))
				}
				next.PersonalOnly = b
			}
		}
		if v, ok := body["shared_secret"]; ok {
			next.SharedSecret = strings.TrimSpace(str(v))
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	if after.SharedEnabled && !before.SharedEnabled {
		a.wakeManifest()
	}
	return a.relayPublicPayload(), nil
}

// ClearPersonalRelay removes the stored personal relay URL and disables personal relay toggles.
func (a *App) ClearPersonalRelay(context.Context, Body) (any, error) {
	if _, err := a.relayStore.Update(func(next *config.RelayConfig) error {
		next.PersonalURL = ""
		next.PersonalEnabled = false
		next.PersonalOnly = false
		return nil
	}); err != nil {
		return nil, err
	}
	return a.relayPublicPayload(), nil
}

// TestPersonalRelay runs the real RelayHealth check (&health=1) against either a candidate URL
// typed in the dialog or the saved encrypted PersonalURL, never echoing the secret back.
func (a *App) TestPersonalRelay(ctx context.Context, body Body) (any, error) {
	candidateRaw := strings.TrimSpace(str(body["personal_url"]))
	var targetURL *url.URL
	var err error
	if candidateRaw != "" {
		targetURL, err = foxy.ValidatePersonalRelayURL(candidateRaw)
		if err != nil {
			return nil, localizedPersonalURLError(err)
		}
	} else {
		stored := strings.TrimSpace(a.relayStore.Load().PersonalURL)
		if stored == "" {
			return nil, apierr.New(locale.Tr("هیچ نشانی رله شخصی ذخیره نشده است.",
				"No personal relay URL is configured."))
		}
		targetURL, err = foxy.ValidatePersonalRelayURL(stored)
		if err != nil {
			return nil, localizedPersonalURLError(err)
		}
	}

	hctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	health := a.healthChecker.Check(hctx, targetURL)

	var code int
	if health.HTTPCode != nil {
		code = *health.HTTPCode
	}
	return map[string]any{
		"ok":         health.Ready,
		"failure":    health.Failure,
		"http_code":  code,
		"elapsed_ms": health.ElapsedMS,
		"message":    relayHealthMessage(health),
	}, nil
}

func relayHealthMessage(h foxy.RelayHealthResult) string {
	if h.Ready {
		return locale.Tr("رله سالم و آماده به کار است (v1).", "Relay is healthy and ready (v1).")
	}
	switch h.Failure {
	case foxy.RelayFailureWrongSecret:
		return locale.Tr("کلید رمز (secret) رله اشتباه است (HTTP 403).", "Relay secret was rejected (HTTP 403).")
	case foxy.RelayFailureNotSetUp:
		return locale.Tr("متغیر SECRET در سرور رله تنظیم نشده است (HTTP 503).", "Relay server SECRET is not configured (HTTP 503).")
	case foxy.RelayFailureUpstreamUnreachable:
		return locale.Tr("سرور رله به سرور بالادست دسترسی ندارد (HTTP 502).", "Relay could not reach upstream (HTTP 502).")
	case foxy.RelayFailureUnrecognised:
		return locale.Tr("این نشانی پاسخ معتبر رله RXFOXYVPN را برنگرداند.", "Endpoint did not return a valid RXFOXYVPN relay response.")
	case foxy.RelayFailureTimeout:
		return locale.Tr("زمان انتظار اتصال به سرور رله به پایان رسید.", "Relay health check timed out.")
	case foxy.RelayFailureNetwork:
		return locale.Tr("برقراری ارتباط با سرور رله ممکن نشد.", "Could not connect to the relay server.")
	default:
		if h.HTTPCode != nil && *h.HTTPCode > 0 {
			return locale.Tr("سرور رله درخواست را رد کرد (کد "+str(*h.HTTPCode)+").",
				"Relay server refused the check (HTTP "+str(*h.HTTPCode)+").")
		}
		return locale.Tr("بررسی سلامت رله ناموفق بود.", "Relay health check failed.")
	}
}

// ---------------------------------------------------------------- Announcements API

// GetAnnouncements returns the sequential queue of unread Home announcements from updates.json.
func (a *App) GetAnnouncements(ctx context.Context, _ Body) (any, error) {
	if a.sharedRelays == nil {
		return map[string]any{"announcements": []any{}}, nil
	}
	cctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()
	acked := a.annStore.AcknowledgedSet()
	list := a.sharedRelays.PendingAnnouncements(cctx, acked, false)
	if list == nil {
		list = []foxy.ManifestAnnouncement{}
	}
	return map[string]any{"announcements": list}, nil
}

// AckAnnouncement marks a single announcement ID as read ONLY when the user clicks «متوجه شدم».
func (a *App) AckAnnouncement(_ context.Context, body Body) (any, error) {
	id := strings.TrimSpace(str(body["id"]))
	if id == "" {
		return nil, apierr.New("missing announcement id")
	}
	if a.annStore != nil {
		if err := a.annStore.Acknowledge(id); err != nil {
			return nil, err
		}
	}
	return map[string]any{"ok": true, "id": id}, nil
}

// ---------------------------------------------------------------- TUN Log & Safe Browser URLs

// TunLog returns the tail of the sing-box log.
func (a *App) TunLog(context.Context, Body) (any, error) {
	path := config.TunLogPath()
	f, err := os.Open(path)
	if err != nil {
		return map[string]any{"text": "", "path": path, "exists": false}, nil
	}
	defer f.Close()
	if st, err := f.Stat(); err == nil && st.Size() > 65536 {
		_, _ = f.Seek(st.Size()-65536, io.SeekStart)
	}
	raw, err := io.ReadAll(f)
	if err != nil {
		return map[string]any{"text": "", "path": path, "exists": false}, nil
	}
	lines := strings.Split(strings.ReplaceAll(tun.StripANSI(strings.ToValidUTF8(string(raw), "\uFFFD")), "\r\n", "\n"), "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	if len(lines) > 300 {
		lines = lines[len(lines)-300:]
	}
	return map[string]any{"text": strings.Join(lines, "\n"), "path": path, "exists": true}, nil
}

// OpenURL opens a Firefox Accounts page in the browser (nothing else is allowed).
func (a *App) OpenURL(_ context.Context, body Body) (any, error) {
	url := str(body["url"])
	if !strings.HasPrefix(url, "https://accounts.firefox.com/") {
		return nil, apierr.New("forbidden", 403)
	}
	if err := platform.OpenURL(url); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}
