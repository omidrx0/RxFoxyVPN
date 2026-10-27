// Package desktop is the native shell around the panel: a WebView2 window with the panel's own
// title bar, the tray icon next to the Windows clock and the Edge/Chrome app-window fallback.
package desktop

import "errors"

// ErrNoWebView means the native window cannot be created (not Windows, or the WebView2
// runtime is missing). The caller falls back to a browser app window.
var ErrNoWebView = errors.New("the native panel window is not available")

// Options configure the native window.
type Options struct {
	// NativeFrame uses the normal Windows title bar instead of the panel's own one.
	NativeFrame bool
}

const (
	// window size (logical pixels) and the smallest size the panel layout supports
	defaultWidth, defaultHeight = 1180, 760
	minWidth, minHeight         = 880, 600
)
