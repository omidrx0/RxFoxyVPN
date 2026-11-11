//go:build !windows

package desktop

// LaunchFxaWebViewHelper is unavailable outside Windows; callers fall back to external browser OAuth PKCE.
func LaunchFxaWebViewHelper(_, _, _ string) (func(), error) {
	return nil, ErrNoWebView
}

// RunFxaSignInWindow is unavailable outside Windows.
func RunFxaSignInWindow() error {
	return ErrNoWebView
}
