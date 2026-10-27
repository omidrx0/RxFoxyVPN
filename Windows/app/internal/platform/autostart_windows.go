//go:build windows

package platform

import (
	"errors"
	"os"

	"golang.org/x/sys/windows/registry"
)

const (
	autostartKey  = `Software\Microsoft\Windows\CurrentVersion\Run`
	autostartName = "RXVPN"
)

// SetAutostart adds or removes the per-user Run entry that starts the panel at sign-in.
func SetAutostart(enabled bool) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, autostartKey, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	if enabled {
		exe, err := os.Executable()
		if err != nil {
			return err
		}
		return k.SetStringValue(autostartName, `"`+exe+`"`)
	}
	if err := k.DeleteValue(autostartName); err != nil && !errors.Is(err, registry.ErrNotExist) {
		return err
	}
	return nil
}
