//go:build windows

package desktop

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/jchv/go-webview2/pkg/edge"
	"golang.org/x/sys/windows"

	"rxvpn/internal/foxy"
	"rxvpn/internal/platform"
	"rxvpn/packaging"
)

const (
	fxaWinWidth, fxaWinHeight = 500, 690
	wmAppDispatch             = wmApp + 1
	wsOverlappedWindow        = 0x00CF0000
)

var (
	pUpdateWindow      = user32.NewProc("UpdateWindow")
	pPostThreadMessage = user32.NewProc("PostThreadMessageW")
	pSetFocus          = user32.NewProc("SetFocus")
	pGetCurrentThread  = kernel32.NewProc("GetCurrentThreadId")
)

// LaunchFxaWebViewHelper starts an isolated child RXFOXYVPN process running the Mozilla WebChannel
// sign-in window with an ephemeral WebView2 profile. Secrets are passed via environment variables,
// never on the command line.
func LaunchFxaWebViewHelper(ipcURL, ipcSecret, email string) (func(), error) {
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(exe, "--fxa-webview")
	cmd.Env = append(os.Environ(),
		"RXVPN_FXA_IPC_URL="+ipcURL,
		"RXVPN_FXA_IPC_SECRET="+ipcSecret,
		"RXVPN_FXA_EMAIL="+email,
	)
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	var stopOnce sync.Once
	stop := func() {
		stopOnce.Do(func() {
			if cmd.Process != nil {
				_ = cmd.Process.Kill()
			}
		})
	}
	go func() {
		_ = cmd.Wait()
	}()
	return stop, nil
}

// webView2WebChannelHookJS forwards Mozilla FxA WebChannel events through WebView2's native
// chrome.webview.postMessage strictly when running on https://accounts.firefox.com.
const webView2WebChannelHookJS = `(function(){
  if (window.__rxFxaHooked) return;
  if (!window.location || window.location.origin !== 'https://accounts.firefox.com') return;
  if (!window.chrome || !window.chrome.webview || typeof window.chrome.webview.postMessage !== 'function') return;
  window.__rxFxaHooked = true;
  window.addEventListener('WebChannelMessageToChrome', function(e) {
    try {
      if (!window.location || window.location.origin !== 'https://accounts.firefox.com') return;
      var d = e && e.detail;
      if (d == null) return;
      window.chrome.webview.postMessage(JSON.stringify({
        origin: window.location.origin,
        detail: typeof d === 'string' ? d : JSON.stringify(d)
      }));
    } catch (_) {}
  }, true);
})();`

// RunFxaSignInWindow runs the isolated Mozilla Sign-in WebView2 helper mode (--fxa-webview).
func RunFxaSignInWindow() error {
	runtime.LockOSThread()
	platform.SetAppUserModelID("RXVPN.Panel.SignIn")
	user32.NewProc("SetProcessDpiAwarenessContext").Call(^uintptr(3))

	ipcURL := os.Getenv("RXVPN_FXA_IPC_URL")
	ipcSecret := os.Getenv("RXVPN_FXA_IPC_SECRET")
	email := os.Getenv("RXVPN_FXA_EMAIL")
	_ = os.Unsetenv("RXVPN_FXA_IPC_SECRET")
	if ipcURL == "" || ipcSecret == "" {
		return errors.New("missing FxA IPC configuration")
	}

	postIPC := func(payload map[string]string) error {
		raw, err := json.Marshal(payload)
		if err != nil {
			return err
		}
		req, err := http.NewRequest(http.MethodPost, ipcURL, bytes.NewReader(raw))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-RxVPN-Auth-Token", ipcSecret)
		client := &http.Client{Timeout: 4 * time.Second}
		resp, err := client.Do(req)
		if err != nil {
			return err
		}
		_ = resp.Body.Close()
		return nil
	}

	tempDataDir, err := os.MkdirTemp("", "rxvpn-fxa-wv2-*")
	if err != nil {
		_ = postIPC(map[string]string{"event": "error", "error": "Could not create temporary sign-in profile"})
		return err
	}
	defer func() {
		for i := 0; i < 6; i++ {
			if os.RemoveAll(tempDataDir) == nil {
				break
			}
			time.Sleep(80 * time.Millisecond)
		}
	}()

	hinstance, _, _ := pGetModuleHandle.Call(0)

	var (
		chromium   *edge.Chromium
		hwnd       uintptr
		mainThread uintptr
		mu         sync.Mutex
		queue      []func()
		sessionGot atomic.Bool
		closed     atomic.Bool
	)
	mainThread, _, _ = pGetCurrentThread.Call()

	dispatch := func(fn func()) {
		mu.Lock()
		queue = append(queue, fn)
		mu.Unlock()
		pPostThreadMessage.Call(mainThread, wmAppDispatch, 0, 0)
	}

	wndProc := windows.NewCallback(func(h, msg, wp, lp uintptr) uintptr {
		switch uint32(msg) {
		case 0x0005: // WM_SIZE
			if chromium != nil {
				chromium.Resize()
			}
			return 0
		case 0x0003, 0x0216: // WM_MOVE, WM_MOVING
			if chromium != nil {
				_ = chromium.NotifyParentWindowPositionChanged()
			}
		case 0x0006: // WM_ACTIVATE
			if wp != 0 && chromium != nil {
				chromium.Focus()
			}
			return 0
		case wmClose:
			pDestroyWindow.Call(h)
			return 0
		case wmDestroy:
			closed.Store(true)
			pPostQuitMessage.Call(0)
			return 0
		}
		r, _, _ := pDefWindowProc.Call(h, msg, wp, lp)
		return r
	})

	className := utf16("rxvpn_fxa_signin")
	wc := wndClassEx{
		CbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
		HInstance:     hinstance,
		LpfnWndProc:   wndProc,
		LpszClassName: className,
	}
	pRegisterClassEx.Call(uintptr(unsafe.Pointer(&wc)))

	sw, _, _ := pGetSystemMetrics.Call(0) // SM_CXSCREEN
	sh, _, _ := pGetSystemMetrics.Call(1) // SM_CYSCREEN
	posX := (int(sw) - fxaWinWidth) / 2
	posY := (int(sh) - fxaWinHeight) / 2
	titlePtr := utf16("Mozilla Sign-in — RXFOXYVPN")

	hwnd, _, _ = pCreateWindowEx.Call(
		0,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(titlePtr)),
		wsOverlappedWindow,
		uintptr(max(0, posX)),
		uintptr(max(0, posY)),
		uintptr(fxaWinWidth),
		uintptr(fxaWinHeight),
		0, 0, hinstance, 0,
	)
	if hwnd == 0 {
		_ = postIPC(map[string]string{"event": "error", "error": "Could not create sign-in window"})
		return errors.New("CreateWindowExW failed")
	}

	if small := loadIconBytes("rxvpn_app.ico", packaging.IconICO, 16, 16); small != 0 {
		pSendMessage.Call(hwnd, wmSetIcon, 0, small)
	}
	if big := loadIconBytes("rxvpn_app.ico", packaging.IconICO, 32, 32); big != 0 {
		pSendMessage.Call(hwnd, wmSetIcon, 1, big)
	}

	showWindow(hwnd, swShow)
	pUpdateWindow.Call(hwnd)
	pSetFocus.Call(hwnd)

	chromium = edge.NewChromium()
	chromium.DataPath = tempDataDir
	chromium.MessageCallback = func(raw string) {
		var env struct {
			Origin string `json:"origin"`
			Detail string `json:"detail"`
		}
		if json.Unmarshal([]byte(raw), &env) != nil {
			return
		}
		if !foxy.IsTrustedBridgeOrigin(env.Origin) {
			return
		}
		res, err := foxy.HandleWebChannelMessage(env.Detail)
		if err != nil {
			return
		}
		if res.ReplyJS != "" {
			reply := res.ReplyJS
			dispatch(func() {
				if chromium != nil && !closed.Load() {
					chromium.Eval(reply)
				}
			})
		}
		if res.SessionToken != "" && sessionGot.CompareAndSwap(false, true) {
			tok, em := res.SessionToken, res.Email
			go func() {
				_ = postIPC(map[string]string{
					"event":         "session",
					"session_token": tok,
					"email":         em,
				})
				probe := &http.Client{Timeout: 1500 * time.Millisecond}
				for !closed.Load() {
					time.Sleep(900 * time.Millisecond)
					req, _ := http.NewRequest(http.MethodGet, ipcURL, nil)
					if _, err := probe.Do(req); err != nil {
						postMessage(hwnd, wmClose, 0, 0)
						return
					}
				}
			}()
		}
	}

	if !chromium.Embed(hwnd) {
		pDestroyWindow.Call(hwnd)
		_ = postIPC(map[string]string{"event": "error", "error": "WebView2 runtime is not available"})
		return ErrNoWebView
	}
	chromium.Resize()

	if settings, sErr := chromium.GetSettings(); sErr == nil && settings != nil {
		_ = settings.PutUserAgent(foxy.UserAgent)
		_ = settings.PutAreDefaultContextMenusEnabled(false)
		_ = settings.PutAreDevToolsEnabled(false)
	}

	chromium.AddWebResourceRequestedFilter("*", edge.COREWEBVIEW2_WEB_RESOURCE_CONTEXT_DOCUMENT)
	chromium.WebResourceRequestedCallback = func(req *edge.ICoreWebView2WebResourceRequest, args *edge.ICoreWebView2WebResourceRequestedEventArgs) {
		uri, err := req.GetUri()
		if err != nil || !foxy.IsBrowserSignInNavigationAllowed(uri) {
			if env := chromium.Environment(); env != nil {
				if resp, rErr := env.CreateWebResourceResponse(
					[]byte("Navigation outside Mozilla sign-in was blocked by RXFOXYVPN."),
					403, "Forbidden", "Content-Type: text/plain; charset=utf-8\r\n",
				); rErr == nil && resp != nil {
					_ = args.PutResponse(resp)
				}
			}
		}
	}

	chromium.Init(webView2WebChannelHookJS)
	chromium.Navigate(foxy.WebChannelLoginURL(email))

	var msg winMsg
	for {
		r, _, _ := pGetMessage.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		if msg.Message == wmAppDispatch {
			mu.Lock()
			tasks := append([]func(){}, queue...)
			queue = nil
			mu.Unlock()
			for _, t := range tasks {
				t()
			}
			continue
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		pDispatchMessage.Call(uintptr(unsafe.Pointer(&msg)))
	}

	if !sessionGot.Load() {
		_ = postIPC(map[string]string{"event": "closed"})
	}
	return nil
}
