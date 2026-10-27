//go:build !windows

package platform

import "errors"

// DPAPI is not available outside Windows.
type DPAPI struct{}

var errNoDPAPI = errors.New("saved account passwords require Windows DPAPI")

func (DPAPI) Protect([]byte) ([]byte, error)   { return nil, errNoDPAPI }
func (DPAPI) Unprotect([]byte) ([]byte, error) { return nil, errNoDPAPI }
