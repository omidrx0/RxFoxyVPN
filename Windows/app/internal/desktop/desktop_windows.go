//go:build windows

package desktop

import (
	"context"
	"fmt"
	"log/slog"
	"sync/atomic"
	"time"

	webview2 "github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"

	"rxvpn/internal/app"
	"rxvpn/internal/config"
	"rxvpn/internal/locale"
	"rxvpn/internal/platform"
	"rxvpn/internal/server"
	"rxvpn/packaging"
)

// shell ties the window, the tray icon and the app together.
type shell struct {
	app  *app.App
	view webview2.WebView
	win  *windowCtl
	tray *tray

	quit   atomic.Bool
	trayOK atomic.Bool
}

// Run opens the native panel window and the tray icon and blocks until the app is closed.
// It returns ErrNoWebView when the WebView2 runtime is missing.
func Run(a *app.App, pageURL string, opts Options) error {
	platform.SetAppUserModelID("RXVPN.Panel")
	// per-monitor DPI awareness (-4 = PER_MONITOR_AWARE_V2); the manifest asks for it too
	user32.NewProc("SetProcessDpiAwarenessContext").Call(^uintptr(3))

	page := pageURL
	if !opts.NativeFrame { // our own title bar (drawn by the page) instead of the Windows one
		page += "&tb=1"
	}

	view := webview2.NewWithOptions(webview2.WebViewOptions{
		AutoFocus: true,
		DataPath:  config.WebViewDataDir(),
		WindowOptions: webview2.WindowOptions{
			Title: "RXFOXYVPN", Width: defaultWidth, Height: defaultHeight, Center: true,
		},
	})
	if view == nil {
		return ErrNoWebView
	}
	defer view.Destroy()

	hwnd := uintptr(view.Window())
	s := &shell{app: a, view: view}
	s.win = newWindowCtl(hwnd, func(js string) { view.Dispatch(func() { view.Eval(js) }) })

	// size and place the window in logical pixels, then set the minimum size
	scale := s.win.dpiScale()
	view.SetSize(int(minWidth*scale), int(minHeight*scale), webview2.HintMin)
	_, work := s.win.monitorAt(0, 0, true)
	w, h := int(defaultWidth*scale), int(defaultHeight*scale)
	s.win.setRect(int(work.Left)+(work.w()-w)/2, int(work.Top)+(work.h()-h)/2, w, h, false)

	if !opts.NativeFrame {
		s.win.makeFrameless()
		s.bindTitleBar()
	}
	s.setWindowIcons()
	s.interceptClose(hwnd)
	s.startTray()

	view.Navigate(page)
	view.Run() // blocks until the window is really destroyed

	s.quit.Store(true)
	if s.tray != nil {
		s.tray.Stop()
	}
	return nil
}

// bindTitleBar exposes the window actions the page's custom title bar calls (web/app.js).
func (s *shell) bindTitleBar() {
	bind := func(name string, f any) {
		if err := s.view.Bind(name, f); err != nil {
			slog.Error("could not bind a window function", "name", name, "err", err)
		}
	}
	bind("rxwin_minimize", func() { s.win.minimize() })
	bind("rxwin_close", func() { s.closeWindow() })
	bind("rxwin_toggle_maximize", func() bool { return s.win.toggleMaximize() })
	bind("rxwin_start_drag", func() bool { return s.win.startDrag() })
	bind("rxwin_begin_resize", func(edge string) bool { return s.win.beginResize(edge) })
}

func (s *shell) setWindowIcons() {
	small := loadIconBytes("rxvpn_app.ico", packaging.IconICO, 16, 16)
	big := loadIconBytes("rxvpn_app.ico", packaging.IconICO, 32, 32)
	if small != 0 {
		pSendMessage.Call(s.win.hwnd, wmSetIcon, 0, small)
	}
	if big != 0 {
		pSendMessage.Call(s.win.hwnd, wmSetIcon, 1, big)
	}
}

// interceptClose makes Alt+F4 / the taskbar's Close behave like the X button: with a tray icon
// the window only hides and the app keeps running.
func (s *shell) interceptClose(hwnd uintptr) {
	var oldProc uintptr
	proc := windows.NewCallback(func(h, m, wp, lp uintptr) uintptr {
		if uint32(m) == wmClose && !s.onClosing() {
			return 0
		}
		r, _, _ := pCallWindowProc.Call(oldProc, h, m, wp, lp)
		return r
	})
	oldProc = setWindowLongPtr(hwnd, gwlWndProc, proc)
}

// onClosing decides what a close request means: true = really close, false = it was hidden.
func (s *shell) onClosing() bool {
	if s.quit.Load() || !s.trayOK.Load() { // no tray icon -> closing really quits (never a hidden zombie)
		return true
	}
	s.win.hide()
	s.notify(tt("running"))
	return false
}

// closeWindow is the title bar's X button.
func (s *shell) closeWindow() {
	if s.onClosing() {
		s.terminate()
	}
}

func (s *shell) terminate() {
	s.view.Dispatch(func() { s.view.Terminate() })
}

func (s *shell) notify(text string) {
	if s.tray != nil && s.trayOK.Load() {
		s.tray.Notify(text)
	}
}

func (s *shell) notifyError(err error) { s.notify(server.FriendlyError(err).Message) }

func (s *shell) quitAll() {
	s.quit.Store(true)
	if s.app.Connected() {
		_, _ = s.app.Disconnect(context.Background(), nil)
	}
	s.terminate()
}

func (s *shell) toggleConnection() {
	go func() {
		ctx := context.Background()
		if s.app.Connected() {
			if _, err := s.app.Disconnect(ctx, nil); err != nil {
				s.notifyError(err)
				return
			}
			s.notify(tt("disconnected"))
			return
		}
		if !s.app.Account().HasSession() {
			s.win.show()
			s.notify(tt("need_login"))
			return
		}
		code := s.app.Account().LastCountry()
		if code == "" {
			code = "REC"
		}
		if _, err := s.app.Connect(ctx, app.Body{"code": code}); err != nil {
			s.notifyError(err)
			return
		}
		s.notify(tt("connected"))
	}()
}

func (s *shell) setMode(mode string) {
	go func() {
		if _, err := s.app.SetConnection(context.Background(), app.Body{"mode": mode}); err != nil {
			s.notifyError(err)
			return
		}
		key := map[string]string{"tun": "mode_tun", "system": "mode_system", "manual": "mode_manual"}[mode]
		s.notify(tt(key))
	}()
}

func (s *shell) setLanguage(lang string) {
	if _, err := s.app.SetLang(context.Background(), app.Body{"lang": lang}); err != nil {
		s.notifyError(err)
		return
	}
	// switch the open panel instantly
	js := fmt.Sprintf("window.__setLang && window.__setLang('%s')", lang)
	s.view.Dispatch(func() { s.view.Eval(js) })
	if s.tray != nil {
		s.tray.Update(s.app.Connected())
	}
}

func (s *shell) statusText() string {
	st := s.app.StatusNow()
	if st.State == "connected" && st.Country != nil {
		return tt("st_on") + " · " + st.Country.Name
	}
	return tt("st_off")
}

func (s *shell) startTray() {
	tr, err := startTray(trayActions{
		open:        s.win.show,
		toggle:      s.toggleConnection,
		setMode:     s.setMode,
		setLang:     s.setLanguage,
		quit:        s.quitAll,
		isConnected: s.app.Connected,
		statusText:  s.statusText,
		mode:        s.app.ConnectionMode,
	})
	if err != nil {
		slog.Error("could not create the tray icon", "err", err)
		return
	}
	s.tray = tr
	s.trayOK.Store(true)

	// the icon colour and tooltip follow the connection state and the language
	go func() {
		type snapshot struct {
			connected bool
			lang      string
		}
		var last snapshot
		first := true
		for !s.quit.Load() {
			cur := snapshot{s.app.Connected(), locale.Get()}
			if first || cur != last {
				tr.Update(cur.connected)
				last, first = cur, false
			}
			time.Sleep(1500 * time.Millisecond)
		}
	}()
}
