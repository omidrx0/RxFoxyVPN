//go:build windows

package desktop

import "testing"

func TestWindowCloseHidesToTrayWithoutQuitting(t *testing.T) {
	s := &shell{}
	s.win = &windowCtl{}
	s.trayOK.Store(true)

	if s.onClosing() {
		t.Fatal("closing the window should be intercepted while the tray is available")
	}
	if s.quit.Load() {
		t.Fatal("hiding the window must leave the app running")
	}
}

func TestWindowCloseQuitsWhenTrayIsUnavailable(t *testing.T) {
	s := &shell{}

	if !s.onClosing() {
		t.Fatal("closing the window should be allowed when no tray is available")
	}
	if !s.quit.Load() {
		t.Fatal("closing without a tray must mark the shell for shutdown")
	}
}
