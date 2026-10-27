// Package platform wraps every operating-system specific call (Windows API, registry, DPAPI,
// job objects ...). Each function has a Windows implementation and a harmless stub for other
// systems, so the rest of the code base builds and is testable everywhere.
package platform

import (
	"os/exec"
	"runtime"
)

// IsWindows is true when the app runs on Windows (the only OS with TUN / system-proxy support).
const IsWindows = runtime.GOOS == "windows"

// OpenURL opens a URL in the default browser.
func OpenURL(url string) error {
	switch runtime.GOOS {
	case "windows":
		return exec.Command("rundll32", "url.dll,FileProtocolHandler", url).Start()
	case "darwin":
		return exec.Command("open", url).Start()
	default:
		return exec.Command("xdg-open", url).Start()
	}
}
