//go:build windows

package desktop

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/jchv/go-webview2/pkg/edge"
	"golang.org/x/sys/windows"

	"rxvpn/internal/foxy"
	"rxvpn/internal/platform"
)

const (
	wmAppDispatch = wmApp + 1
)

var (
	pUpdateWindow      = user32.NewProc("UpdateWindow")
	pPostThreadMessage = user32.NewProc("PostThreadMessageW")
	pIsWindow          = user32.NewProc("IsWindow")
	pSetFocus          = user32.NewProc("SetFocus")
	pGetCurrentThread  = kernel32.NewProc("GetCurrentThreadId")

	fxaBrowserParent atomic.Uintptr
	fxaTitlebarInset atomic.Int64
)

func setFxaBrowserParent(hwnd uintptr, titlebarInset int) {
	fxaBrowserParent.Store(hwnd)
	fxaTitlebarInset.Store(int64(titlebarInset))
}

// LaunchFxaWebViewHelper starts the WebView2 child surface inside the app's main window.
// Secrets and the parent window handle are passed via environment variables, never on the command line.
func LaunchFxaWebViewHelper(ipcURL, ipcSecret, email, proxyAddress string) (func(), error) {
	parent := fxaBrowserParent.Load()
	if parent == 0 {
		return nil, errors.New("the app window is unavailable for in-app browser sign-in")
	}
	exe, err := os.Executable()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(exe, "--fxa-webview")
	cmd.Env = append(os.Environ(),
		"RXVPN_FXA_IPC_URL="+ipcURL,
		"RXVPN_FXA_IPC_SECRET="+ipcSecret,
		"RXVPN_FXA_EMAIL="+email,
		"RXVPN_FXA_PROXY="+proxyAddress,
		"RXVPN_FXA_PARENT_HWND="+strconv.FormatUint(uint64(parent), 10),
		"RXVPN_FXA_TITLEBAR_INSET="+strconv.FormatInt(fxaTitlebarInset.Load(), 10),
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

// RunFxaSignInWindow runs the in-app Mozilla sign-in WebView2 child surface (--fxa-webview).
func RunFxaSignInWindow() error {
	runtime.LockOSThread()
	platform.SetAppUserModelID("RXVPN.Panel.SignIn")
	user32.NewProc("SetProcessDpiAwarenessContext").Call(^uintptr(3))

	ipcURL := os.Getenv("RXVPN_FXA_IPC_URL")
	ipcSecret := os.Getenv("RXVPN_FXA_IPC_SECRET")
	email := os.Getenv("RXVPN_FXA_EMAIL")
	proxyAddress := os.Getenv("RXVPN_FXA_PROXY")
	parentRaw := os.Getenv("RXVPN_FXA_PARENT_HWND")
	titlebarInsetRaw := os.Getenv("RXVPN_FXA_TITLEBAR_INSET")
	_ = os.Unsetenv("RXVPN_FXA_IPC_SECRET")
	_ = os.Unsetenv("RXVPN_FXA_PROXY")
	_ = os.Unsetenv("RXVPN_FXA_PARENT_HWND")
	_ = os.Unsetenv("RXVPN_FXA_TITLEBAR_INSET")
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

	parentValue, parentErr := strconv.ParseUint(parentRaw, 10, strconv.IntSize)
	titlebarInset, insetErr := strconv.Atoi(titlebarInsetRaw)
	if parentErr != nil || parentValue == 0 || insetErr != nil || titlebarInset < 0 || titlebarInset > 200 {
		err := errors.New("invalid in-app browser parent window")
		_ = postIPC(map[string]string{"event": "error", "error": "The in-app browser is unavailable"})
		return err
	}
	parent := uintptr(parentValue)
	if isWindow, _, _ := pIsWindow.Call(parent); isWindow == 0 {
		err := errors.New("in-app browser parent window is no longer available")
		_ = postIPC(map[string]string{"event": "error", "error": "The in-app browser is unavailable"})
		return err
	}

	host, port, proxyErr := net.SplitHostPort(proxyAddress)
	portNumber, portErr := strconv.Atoi(port)
	if proxyErr != nil || host != "127.0.0.1" || portErr != nil || portNumber < 1 || portNumber > 65535 {
		err := errors.New("invalid browser sign-in proxy address")
		_ = postIPC(map[string]string{"event": "error", "error": "The browser sign-in proxy is unavailable"})
		return err
	}
	browserArgs := strings.TrimSpace(os.Getenv("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS"))
	if browserArgs != "" {
		browserArgs += " "
	}
	browserArgs += "--proxy-server=http://" + proxyAddress
	if err := os.Setenv("WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS", browserArgs); err != nil {
		_ = postIPC(map[string]string{"event": "error", "error": "Could not configure the browser sign-in proxy"})
		return fmt.Errorf("configure WebView2 sign-in proxy: %w", err)
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

	titlePtr := utf16("RXFOXYVPN — Sign in with browser")
	parentBounds := getClientRect(parent)
	childTop := scaledTitlebarInset(parent, titlebarInset)
	childHeight := max(0, parentBounds.h()-childTop)

	hwnd, _, _ = pCreateWindowEx.Call(
		0,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(titlePtr)),
		wsChild|wsVisible,
		0,
		uintptr(childTop),
		uintptr(max(1, parentBounds.w())),
		uintptr(max(1, childHeight)),
		parent, 0, hinstance, 0,
	)
	if hwnd == 0 {
		_ = postIPC(map[string]string{"event": "error", "error": "Could not open the in-app sign-in page"})
		return errors.New("CreateWindowExW failed")
	}

	showWindow(hwnd, swShow)
	pUpdateWindow.Call(hwnd)
	pSetFocus.Call(hwnd)

	stopBoundsSync := make(chan struct{})
	defer close(stopBoundsSync)
	go syncFxaChildBounds(hwnd, parent, titlebarInset, &closed, stopBoundsSync)

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
	parentBounds = getClientRect(parent)
	childTop = scaledTitlebarInset(parent, titlebarInset)
	setWindowPos(hwnd, 0, childTop, parentBounds.w(), parentBounds.h()-childTop, swpNoActivate)

	if settings, sErr := chromium.GetSettings(); sErr == nil && settings != nil {
		_ = settings.PutUserAgent(foxy.BrowserSignInUserAgent)
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

	chromium.Init(foxy.WebChannelHookJS)
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

func scaledTitlebarInset(parent uintptr, logicalInset int) int {
	dpi, _, _ := pGetDpiForWindow.Call(parent)
	if dpi == 0 {
		return logicalInset
	}
	return (logicalInset*int(dpi) + 48) / 96
}

func syncFxaChildBounds(child, parent uintptr, logicalInset int, closed *atomic.Bool, stop <-chan struct{}) {
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	initial := getClientRect(parent)
	last := rect{
		Top:    int32(scaledTitlebarInset(parent, logicalInset)),
		Right:  int32(initial.w()),
		Bottom: int32(initial.h()),
	}
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			if closed.Load() {
				return
			}
			if isWindow, _, _ := pIsWindow.Call(parent); isWindow == 0 {
				postMessage(child, wmClose, 0, 0)
				return
			}
			bounds := getClientRect(parent)
			top := scaledTitlebarInset(parent, logicalInset)
			if bounds.w() <= 0 || bounds.h() <= top {
				continue
			}
			next := rect{Top: int32(top), Right: int32(bounds.w()), Bottom: int32(bounds.h())}
			if next == last {
				continue
			}
			setWindowPos(child, 0, top, bounds.w(), bounds.h()-top, swpNoActivate)
			last = next
		}
	}
}
