// Package sysproxy switches the Windows system proxy (WinINet registry values) to the local
// panel proxy and puts the previous values back afterwards. A backup file makes the restore
// survive a crash: the next start restores it before anything else.
package sysproxy

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
)

// LANBypass is the ProxyOverride list: local and private addresses never use the proxy.
var LANBypass = []string{
	"<local>", "localhost", "127.*", "10.*", "172.16.*", "172.17.*", "172.18.*",
	"172.19.*", "172.20.*", "172.21.*", "172.22.*", "172.23.*", "172.24.*",
	"172.25.*", "172.26.*", "172.27.*", "172.28.*", "172.29.*", "172.30.*",
	"172.31.*", "192.168.*",
}

// ModeError is a problem with the chosen connection mode (not Windows, not admin, ...).
type ModeError struct{ Msg string }

func (e *ModeError) Error() string { return e.Msg }

// NewModeError builds a ModeError.
func NewModeError(msg string) *ModeError { return &ModeError{Msg: msg} }

// registry value types (REG_SZ, REG_DWORD)
const (
	regSZ    = 1
	regDWORD = 4
)

// value is one registry value as stored in the backup file ({"value": ..., "type": ...}),
// the same format the Python version wrote.
type value struct {
	Value any    `json:"value"`
	Type  uint32 `json:"type"`
}

type snapshot map[string]*value

var proxyValueNames = []string{"ProxyEnable", "ProxyServer", "ProxyOverride"}

// Apply points the Windows system proxy at 127.0.0.1:port, after saving the old values.
func Apply(port int, backupPath string) error {
	if !supported() {
		return NewModeError("Windows system proxy is only available on Windows")
	}
	if _, err := os.Stat(backupPath); err == nil {
		return NewModeError("A previous system proxy backup exists; restore it before connecting")
	}
	snap, err := capture()
	if err != nil {
		return err
	}
	raw, err := json.Marshal(snap)
	if err != nil {
		return err
	}
	tmp := backupPath + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, backupPath); err != nil {
		return err
	}
	server := fmt.Sprintf("http=127.0.0.1:%d;https=127.0.0.1:%d", port, port)
	err = write(snapshot{
		"ProxyEnable":   {Value: uint32(1), Type: regDWORD},
		"ProxyServer":   {Value: server, Type: regSZ},
		"ProxyOverride": {Value: strings.Join(LANBypass, ";"), Type: regSZ},
	})
	if err != nil {
		_ = Restore(backupPath)
		return err
	}
	return nil
}

// Restore puts the saved values back and deletes the backup. It does nothing without a backup.
func Restore(backupPath string) error {
	raw, err := os.ReadFile(backupPath)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	var snap snapshot
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.UseNumber()
	if err := dec.Decode(&snap); err != nil {
		return err
	}
	if err := write(snap); err != nil {
		return err
	}
	return os.Remove(backupPath)
}
