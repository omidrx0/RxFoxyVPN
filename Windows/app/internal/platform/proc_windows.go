//go:build windows

package platform

import (
	"os/exec"
	"syscall"

	"golang.org/x/sys/windows"
)

// HideWindow starts the child without a console window (optionally in its own process group).
func HideWindow(cmd *exec.Cmd, newProcessGroup bool) {
	flags := uint32(windows.CREATE_NO_WINDOW)
	if newProcessGroup {
		flags |= windows.CREATE_NEW_PROCESS_GROUP
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: flags}
}

// SendCtrlBreak asks a console process (in its own process group) to shut down gracefully.
func SendCtrlBreak(pid int) error {
	return windows.GenerateConsoleCtrlEvent(windows.CTRL_BREAK_EVENT, uint32(pid))
}
