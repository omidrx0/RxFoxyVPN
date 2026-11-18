//go:build windows

package desktop

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"time"
	"unsafe"

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
	s.bindBackupExport()

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
	titlebarInset := 0
	if !opts.NativeFrame {
		titlebarInset = 40
	}
	setFxaBrowserParent(hwnd, titlebarInset)
	defer setFxaBrowserParent(0, 0)
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

// bindTitleBar exposes the window actions the page's custom title bar calls (ui/app.js).
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

func (s *shell) bindBackupExport() {
	if err := s.view.Bind("rxbackup_save", func(title, filter string) map[string]any {
		return s.exportBackup(title, filter)
	}); err != nil {
		slog.Error("could not bind the backup export function", "err", err)
	}
}

func (s *shell) exportBackup(title, filter string) map[string]any {
	if s == nil || s.app == nil {
		return map[string]any{"ok": false, "canceled": false, "error": "app unavailable"}
	}
	defaultName := "rxfoxyvpn-backup-" + time.Now().Format("2006-01-02_15-04-05") + ".zip"
	out, canceled, err := promptSavePath(s.win.hwnd, title, filter, defaultName)
	if canceled {
		return map[string]any{"ok": false, "canceled": true}
	}
	if err != nil {
		return map[string]any{"ok": false, "canceled": false, "error": err.Error()}
	}
	data, _, err := s.app.BackupExport()
	if err != nil {
		return map[string]any{"ok": false, "canceled": false, "error": err.Error()}
	}
	if err := writeAtomicFile(out, data); err != nil {
		return map[string]any{"ok": false, "canceled": false, "error": err.Error()}
	}
	return map[string]any{"ok": true, "path": out, "canceled": false}
}

func writeAtomicFile(path string, data []byte) error {
	dir := filepath.Dir(path)
	if dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
	}
	tmp, err := os.CreateTemp(dir, ".rxbackup-*.zip")
	if err != nil {
		return err
	}
	tmpPath := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmpPath)
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	from, err := windows.UTF16FromString(tmpPath)
	if err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	to, err := windows.UTF16FromString(path)
	if err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	if err := windows.MoveFileEx(&from[0], &to[0], windows.MOVEFILE_REPLACE_EXISTING|windows.MOVEFILE_WRITE_THROUGH); err != nil {
		_ = os.Remove(tmpPath)
		return err
	}
	return nil
}

var (
	comdlg32                 = windows.NewLazySystemDLL("comdlg32.dll")
	procGetSaveFileNameW     = comdlg32.NewProc("GetSaveFileNameW")
	procCommDlgExtendedError = comdlg32.NewProc("CommDlgExtendedError")
)

type openFileNameW struct {
	lStructSize       uint32
	hwndOwner         uintptr
	hInstance         uintptr
	lpstrFilter       *uint16
	lpstrCustomFilter *uint16
	nMaxCustFilter    uint32
	nFilterIndex      uint32
	lpstrFile         *uint16
	nMaxFile          uint32
	lpstrFileTitle    *uint16
	nMaxFileTitle     uint32
	lpstrInitialDir   *uint16
	lpstrTitle        *uint16
	Flags             uint32
	nFileOffset       uint16
	nFileExtension    uint16
	lpstrDefExt       *uint16
	lCustData         uintptr
	lpfnHook          uintptr
	lpTemplateName    *uint16
	pvReserved        uintptr
	dwReserved        uint32
	FlagsEx           uint32
}

func multiStringUTF16(parts ...string) ([]uint16, error) {
	result := make([]uint16, 0)
	for _, part := range parts {
		encoded, err := windows.UTF16FromString(part)
		if err != nil {
			return nil, err
		}
		result = append(result, encoded...)
	}
	return append(result, 0), nil
}

func promptSavePath(owner uintptr, title, filter, defaultName string) (string, bool, error) {
	if title == "" {
		title = "Save backup"
	}
	if filter == "" {
		filter = "RXFOXYVPN backup"
	}
	filterSpec, err := multiStringUTF16(filter+" (*.zip)", "*.zip", "All files (*.*)", "*.*")
	if err != nil {
		return "", false, fmt.Errorf("invalid backup file filter: %w", err)
	}
	titleUTF16, err := windows.UTF16FromString(title)
	if err != nil {
		return "", false, fmt.Errorf("invalid save dialog title: %w", err)
	}
	defaultUTF16, err := windows.UTF16FromString(defaultName)
	if err != nil {
		return "", false, fmt.Errorf("invalid default backup name: %w", err)
	}
	extensionUTF16, err := windows.UTF16FromString("zip")
	if err != nil {
		return "", false, err
	}
	buf := make([]uint16, 32768)
	copy(buf, defaultUTF16)
	ofn := openFileNameW{
		lStructSize:     uint32(unsafe.Sizeof(openFileNameW{})),
		hwndOwner:       owner,
		hInstance:       0,
		lpstrFilter:     &filterSpec[0],
		lpstrFile:       &buf[0],
		nMaxFile:        uint32(len(buf)),
		lpstrTitle:      &titleUTF16[0],
		Flags:           0x00000002 | 0x00000008 | 0x00000800 | 0x00080000,
		lpstrDefExt:     &extensionUTF16[0],
		lpstrInitialDir: nil,
	}
	ret, _, _ := procGetSaveFileNameW.Call(uintptr(unsafe.Pointer(&ofn)))
	runtime.KeepAlive(filterSpec)
	runtime.KeepAlive(titleUTF16)
	runtime.KeepAlive(defaultUTF16)
	runtime.KeepAlive(extensionUTF16)
	runtime.KeepAlive(buf)
	runtime.KeepAlive(&ofn)
	if ret == 0 {
		if code, _, _ := procCommDlgExtendedError.Call(); code != 0 {
			return "", false, fmt.Errorf("save dialog failed (Windows error 0x%X)", code)
		}
		return "", true, nil
	}
	path := windows.UTF16ToString(buf)
	if strings.TrimSpace(path) == "" {
		return "", false, fmt.Errorf("save dialog returned an empty path")
	}
	return filepath.Clean(path), false, nil
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

// interceptClose hides the window to the tray when available; without a tray it lets Windows close it.
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

// onClosing hides the panel when the tray is available, otherwise allows a full close.
func (s *shell) onClosing() bool {
	if s.trayOK.Load() && s.win != nil {
		s.win.hide()
		return false
	}
	s.quit.Store(true)
	return true
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
