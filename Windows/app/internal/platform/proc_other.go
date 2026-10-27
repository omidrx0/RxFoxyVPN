//go:build !windows

package platform

import (
	"errors"
	"os/exec"
)

func HideWindow(*exec.Cmd, bool) {}

func SendCtrlBreak(int) error { return errors.New("not supported on this system") }
