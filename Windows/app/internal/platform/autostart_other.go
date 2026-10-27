//go:build !windows

package platform

import "errors"

// SetAutostart is only supported on Windows.
func SetAutostart(bool) error { return errors.New("start with Windows is only supported on Windows") }
