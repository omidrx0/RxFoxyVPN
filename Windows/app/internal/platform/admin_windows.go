//go:build windows

package platform

import (
	"sync"

	"golang.org/x/sys/windows"
)

var (
	adminOnce sync.Once
	admin     bool
)

// IsAdmin reports whether the process runs elevated (the answer cannot change while running).
func IsAdmin() bool {
	adminOnce.Do(func() { admin = windows.GetCurrentProcessToken().IsElevated() })
	return admin
}
