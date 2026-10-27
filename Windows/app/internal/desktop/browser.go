package desktop

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"

	"rxvpn/internal/platform"
)

// OpenAppWindow opens the panel as a standalone Edge/Chrome --app window, or in the default
// browser when none of them is installed.
func OpenAppWindow(url string) {
	var candidates []string
	switch runtime.GOOS {
	case "windows":
		for _, env := range []string{"PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA"} {
			if base := os.Getenv(env); base != "" {
				candidates = append(candidates,
					filepath.Join(base, "Microsoft", "Edge", "Application", "msedge.exe"),
					filepath.Join(base, "Google", "Chrome", "Application", "chrome.exe"))
			}
		}
	case "darwin":
		candidates = []string{
			"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
			"/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
		}
	default:
		for _, name := range []string{"google-chrome", "chromium", "chromium-browser", "microsoft-edge"} {
			if p, err := exec.LookPath(name); err == nil {
				candidates = append(candidates, p)
			}
		}
	}
	for _, exe := range candidates {
		if st, err := os.Stat(exe); err == nil && !st.IsDir() {
			if exec.Command(exe, "--app="+url, "--window-size=1180,760").Start() == nil {
				return
			}
		}
	}
	_ = platform.OpenURL(url)
}
