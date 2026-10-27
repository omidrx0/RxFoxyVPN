//go:build !windows

package platform

// IsAdmin is always false outside Windows.
func IsAdmin() bool { return false }
