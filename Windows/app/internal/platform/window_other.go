//go:build !windows

package platform

import (
	"fmt"
	"os"
)

const (
	MBInfo  = 0x40
	MBError = 0x10
)

func MessageBox(title, text string, _ uint32) { fmt.Fprintf(os.Stderr, "%s: %s\n", title, text) }

func AlreadyRunning() bool { return false }

func SetAppUserModelID(string) {}
