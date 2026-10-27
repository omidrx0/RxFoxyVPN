//go:build windows

package platform

import (
	"errors"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

// MessageBox flags used by the app.
const (
	MBInfo  = 0x40
	MBError = 0x10
)

// MessageBox shows a simple modal message box.
func MessageBox(title, text string, flags uint32) {
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString(title)
	_, _ = windows.MessageBox(0, t, c, flags)
}

// AlreadyRunning creates the single-instance mutex and reports whether another copy owns it.
// The handle is deliberately kept open until the process exits.
func AlreadyRunning() bool {
	name, _ := windows.UTF16PtrFromString(`Local\RXVPN_single_instance`)
	_, err := windows.CreateMutex(nil, false, name)
	return errors.Is(err, windows.ERROR_ALREADY_EXISTS)
}

var procSetAppID = windows.NewLazySystemDLL("shell32.dll").NewProc("SetCurrentProcessExplicitAppUserModelID")

// SetAppUserModelID groups the taskbar button / tray balloons under one identity.
func SetAppUserModelID(id string) {
	p, err := windows.UTF16PtrFromString(id)
	if err == nil {
		_, _, _ = procSetAppID.Call(uintptr(unsafe.Pointer(p)))
		runtime.KeepAlive(p)
	}
}
