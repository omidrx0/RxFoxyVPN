// Package config validates and persists everything the app keeps on disk: user settings,
// connection mode, panel language and saved accounts. File names and formats are the same
// as the Python version, so an existing installation keeps working after the switch.
package config

import (
	"os"
	"path/filepath"
)

// DataDir is %APPDATA%\FoxyBridge (or ~/.config/FoxyBridge elsewhere). It is created on demand.
func DataDir() string {
	base := os.Getenv("APPDATA")
	if base == "" {
		home, _ := os.UserHomeDir()
		base = filepath.Join(home, ".config")
	}
	dir := filepath.Join(base, "FoxyBridge")
	_ = os.MkdirAll(dir, 0o755)
	return dir
}

func StatePath() string           { return filepath.Join(DataDir(), "state.json") }
func UIPath() string              { return filepath.Join(DataDir(), "rxvpn_ui.json") }
func ConnectionPath() string      { return filepath.Join(DataDir(), "connection.json") }
func SettingsPath() string        { return filepath.Join(DataDir(), "rxvpn_settings.json") }
func SavedAccountsPath() string   { return filepath.Join(DataDir(), "saved_accounts.json") }
func TunLogPath() string          { return filepath.Join(DataDir(), "tun.log") }
func AppLogPath() string          { return filepath.Join(DataDir(), "rxvpn.log") }
func ProxyBackupPath() string     { return filepath.Join(DataDir(), "system_proxy_backup.json") }
func FlagDir() string             { return filepath.Join(DataDir(), "flags") }
func TunConfigPath() string       { return filepath.Join(DataDir(), "tun", "config.json") }
func WebViewDataDir() string      { return filepath.Join(DataDir(), "webview2") }
func ExtractedRuntimeDir() string { return filepath.Join(DataDir(), "runtime") }
