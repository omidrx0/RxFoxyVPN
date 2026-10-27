package app

import (
	"context"
	"io"
	"os"
	"strings"

	"rxvpn/internal/apierr"
	"rxvpn/internal/config"
	"rxvpn/internal/locale"
	"rxvpn/internal/platform"
	"rxvpn/internal/tun"
)

// GetSettings returns the user settings, the factory defaults and whether autostart works here.
func (a *App) GetSettings(context.Context, Body) (any, error) {
	a.mu.RLock()
	s := a.settings
	a.mu.RUnlock()
	return map[string]any{
		"settings":            s,
		"defaults":            config.DefaultSettings(),
		"autostart_available": platform.IsWindows,
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
