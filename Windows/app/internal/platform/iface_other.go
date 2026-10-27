//go:build !windows

package platform

import "syscall"

func DefaultIPv4InterfaceIndex() (int, bool) { return 0, false }

func DialControl(int) func(network, address string, c syscall.RawConn) error { return nil }
