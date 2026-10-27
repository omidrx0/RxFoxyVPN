//go:build windows

package desktop

import (
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")
	shell32  = windows.NewLazySystemDLL("shell32.dll")
	dwmapi   = windows.NewLazySystemDLL("dwmapi.dll")

	pGetWindowLongPtr      = user32.NewProc("GetWindowLongPtrW")
	pSetWindowLongPtr      = user32.NewProc("SetWindowLongPtrW")
	pCallWindowProc        = user32.NewProc("CallWindowProcW")
	pDefWindowProc         = user32.NewProc("DefWindowProcW")
	pSetWindowPos          = user32.NewProc("SetWindowPos")
	pGetWindowRect         = user32.NewProc("GetWindowRect")
	pGetCursorPos          = user32.NewProc("GetCursorPos")
	pGetAsyncKeyState      = user32.NewProc("GetAsyncKeyState")
	pGetSystemMetrics      = user32.NewProc("GetSystemMetrics")
	pShowWindow            = user32.NewProc("ShowWindow")
	pSetForegroundWindow   = user32.NewProc("SetForegroundWindow")
	pMonitorFromPoint      = user32.NewProc("MonitorFromPoint")
	pMonitorFromWindow     = user32.NewProc("MonitorFromWindow")
	pGetMonitorInfo        = user32.NewProc("GetMonitorInfoW")
	pGetDpiForWindow       = user32.NewProc("GetDpiForWindow")
	pSendMessage           = user32.NewProc("SendMessageW")
	pPostMessage           = user32.NewProc("PostMessageW")
	pLoadImage             = user32.NewProc("LoadImageW")
	pRegisterClassEx       = user32.NewProc("RegisterClassExW")
	pCreateWindowEx        = user32.NewProc("CreateWindowExW")
	pDestroyWindow         = user32.NewProc("DestroyWindow")
	pGetMessage            = user32.NewProc("GetMessageW")
	pTranslateMessage      = user32.NewProc("TranslateMessage")
	pDispatchMessage       = user32.NewProc("DispatchMessageW")
	pPostQuitMessage       = user32.NewProc("PostQuitMessage")
	pRegisterWindowMessage = user32.NewProc("RegisterWindowMessageW")
	pCreatePopupMenu       = user32.NewProc("CreatePopupMenu")
	pAppendMenu            = user32.NewProc("AppendMenuW")
	pTrackPopupMenu        = user32.NewProc("TrackPopupMenu")
	pDestroyMenu           = user32.NewProc("DestroyMenu")
	pCheckMenuRadioItem    = user32.NewProc("CheckMenuRadioItem")
	pSetMenuDefaultItem    = user32.NewProc("SetMenuDefaultItem")
	pDestroyIcon           = user32.NewProc("DestroyIcon")
	pGetModuleHandle       = kernel32.NewProc("GetModuleHandleW")
	pShellNotifyIcon       = shell32.NewProc("Shell_NotifyIconW")
	pDwmExtendFrame        = dwmapi.NewProc("DwmExtendFrameIntoClientArea")
	pDwmSetWindowAttribute = dwmapi.NewProc("DwmSetWindowAttribute")
)

// window styles / messages / flags
const (
	gwlStyle   = ^uintptr(15) // GWL_STYLE (-16)
	gwlWndProc = ^uintptr(3)  // GWLP_WNDPROC (-4)

	wsCaption    = 0x00C00000
	wsThickFrame = 0x00040000
	wsMaximize   = 0x00010000

	swpNoSize       = 0x0001
	swpNoMove       = 0x0002
	swpNoZOrder     = 0x0004
	swpNoActivate   = 0x0010
	swpFrameChanged = 0x0020

	swHide     = 0
	swShow     = 5
	swMinimize = 6
	swRestore  = 9

	wmNull      = 0x0000
	wmDestroy   = 0x0002
	wmClose     = 0x0010
	wmSetIcon   = 0x0080
	wmCommand   = 0x0111
	wmLButtonUp = 0x0202
	wmLDblClk   = 0x0203
	wmRButtonUp = 0x0205
	wmApp       = 0x8000

	imageIcon       = 1
	lrLoadFromFile  = 0x0010
	lrDefaultSize   = 0x0040
	monitorNearest  = 2
	smSwapButton    = 23
	vkLButton       = 0x01
	vkRButton       = 0x02
	asyncKeyDownBit = 0x8000
)

type point struct{ X, Y int32 }

type rect struct{ Left, Top, Right, Bottom int32 }

func (r rect) w() int { return int(r.Right - r.Left) }
func (r rect) h() int { return int(r.Bottom - r.Top) }

type monitorInfo struct {
	CbSize    uint32
	RcMonitor rect
	RcWork    rect
	DwFlags   uint32
}

type wndClassEx struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     uintptr
	HIcon         uintptr
	HCursor       uintptr
	HbrBackground uintptr
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       uintptr
}

type winMsg struct {
	HWnd    uintptr
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      point
}

func utf16(s string) *uint16 {
	p, _ := windows.UTF16PtrFromString(s)
	return p
}

// packPoint passes a POINT by value (it fits one 64-bit register on 64-bit Windows).
func packPoint(x, y int) uintptr {
	return uintptr(uint64(uint32(int32(x))) | uint64(uint32(int32(y)))<<32)
}

func getWindowLongPtr(hwnd, index uintptr) uintptr {
	r, _, _ := pGetWindowLongPtr.Call(hwnd, index)
	return r
}

func setWindowLongPtr(hwnd, index, value uintptr) uintptr {
	r, _, _ := pSetWindowLongPtr.Call(hwnd, index, value)
	return r
}

func getWindowRect(hwnd uintptr) rect {
	var r rect
	pGetWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&r)))
	return r
}

func cursorPos() (int, int) {
	var p point
	pGetCursorPos.Call(uintptr(unsafe.Pointer(&p)))
	return int(p.X), int(p.Y)
}

// primaryPressed reports whether the primary mouse button is down (respecting swapped buttons).
func primaryPressed() bool {
	vk := uintptr(vkLButton)
	if r, _, _ := pGetSystemMetrics.Call(smSwapButton); r != 0 {
		vk = vkRButton
	}
	r, _, _ := pGetAsyncKeyState.Call(vk)
	return r&asyncKeyDownBit != 0
}

func setWindowPos(hwnd uintptr, x, y, w, h int, flags uintptr) {
	pSetWindowPos.Call(hwnd, 0, uintptr(int32(x)), uintptr(int32(y)), uintptr(int32(w)), uintptr(int32(h)), flags)
}

func showWindow(hwnd uintptr, cmd int) { pShowWindow.Call(hwnd, uintptr(cmd)) }

func postMessage(hwnd uintptr, m uint32, wp, lp uintptr) { pPostMessage.Call(hwnd, uintptr(m), wp, lp) }

// loadIconFile loads an .ico file at the default (system) icon size.
func loadIconFile(path string, w, h int) uintptr {
	flags := uintptr(lrLoadFromFile)
	if w == 0 && h == 0 {
		flags |= lrDefaultSize
	}
	p := utf16(path)
	r, _, _ := pLoadImage.Call(0, uintptr(unsafe.Pointer(p)), imageIcon, uintptr(w), uintptr(h), flags)
	runtime.KeepAlive(p)
	return r
}
