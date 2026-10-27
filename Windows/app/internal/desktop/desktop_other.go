//go:build !windows

package desktop

import "rxvpn/internal/app"

// Run is not supported outside Windows; main then opens a browser window instead.
func Run(*app.App, string, Options) error { return ErrNoWebView }
