//go:build windows

package desktop

import (
	"log/slog"
	"math"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"
)

// windowCtl is what the panel's own title bar (web/app.js) can ask the window to do.
//
// Moving and resizing run in a small goroutine that follows the mouse with plain Win32 calls
// (GetCursorPos / SetWindowPos). Nothing travels through the JavaScript bridge while the mouse
// moves, which is what keeps dragging smooth.
type windowCtl struct {
	hwnd uintptr

	busy        sync.Mutex // one drag / resize / animation at a time
	maximized   atomic.Bool
	restoreRect rect

	evalJS func(js string) // runs JavaScript in the page (thread-safe)
}

func newWindowCtl(hwnd uintptr, evalJS func(string)) *windowCtl {
	return &windowCtl{hwnd: hwnd, evalJS: evalJS, restoreRect: rect{0, 0, minWidth, minHeight}}
}

// makeFrameless removes the native title bar and border (the page draws its own) and asks DWM
// for the soft drop shadow.
func (c *windowCtl) makeFrameless() {
	style := getWindowLongPtr(c.hwnd, gwlStyle)
	style &^= wsCaption | wsThickFrame | wsMaximize
	setWindowLongPtr(c.hwnd, gwlStyle, style)
	setWindowPos(c.hwnd, 0, 0, 0, 0, swpNoMove|swpNoSize|swpNoZOrder|swpNoActivate|swpFrameChanged)

	const dwmwaNCRenderingPolicy, dwmncrpEnabled = 2, 2
	policy := int32(dwmncrpEnabled)
	pDwmSetWindowAttribute.Call(c.hwnd, dwmwaNCRenderingPolicy, uintptr(unsafe.Pointer(&policy)), 4)
	margins := [4]int32{1, 1, 1, 1} // MARGINS{left, right, top, bottom}
	pDwmExtendFrame.Call(c.hwnd, uintptr(unsafe.Pointer(&margins)))
}

func (c *windowCtl) dpiScale() float64 {
	dpi, _, _ := pGetDpiForWindow.Call(c.hwnd)
	if dpi == 0 {
		return 1
	}
	return math.Max(1, float64(dpi)/96)
}

func (c *windowCtl) rect() rect { return getWindowRect(c.hwnd) }

func (c *windowCtl) setRect(x, y, w, h int, moveOnly bool) {
	flags := uintptr(swpNoZOrder | swpNoActivate)
	if moveOnly {
		flags |= swpNoSize
	}
	setWindowPos(c.hwnd, x, y, w, h, flags)
}

// animate glides the window to target with an ease-out curve.
func (c *windowCtl) animate(target rect, d time.Duration) {
	from := c.rect()
	start := time.Now()
	lerp := func(a, b int32, e float64) int { return int(float64(a) + float64(b-a)*e) }
	for {
		t := float64(time.Since(start)) / float64(d)
		if t >= 1 {
			break
		}
		e := 1 - math.Pow(1-t, 3)
		c.setRect(lerp(from.Left, target.Left, e), lerp(from.Top, target.Top, e),
			lerp(int32(from.w()), int32(target.w()), e), lerp(int32(from.h()), int32(target.h()), e), false)
		time.Sleep(8 * time.Millisecond)
	}
	c.setRect(int(target.Left), int(target.Top), target.w(), target.h(), false)
}

// monitorAt returns the work area of the monitor under (x, y), or of the window's own monitor.
func (c *windowCtl) monitorAt(x, y int, useWindow bool) (full, work rect) {
	var mon uintptr
	if useWindow {
		mon, _, _ = pMonitorFromWindow.Call(c.hwnd, monitorNearest)
	} else {
		mon, _, _ = pMonitorFromPoint.Call(packPoint(x, y), monitorNearest)
	}
	info := monitorInfo{CbSize: uint32(unsafe.Sizeof(monitorInfo{}))}
	pGetMonitorInfo.Call(mon, uintptr(unsafe.Pointer(&info)))
	return info.RcMonitor, info.RcWork
}

// maximizeToWorkArea fills the monitor's work area so the taskbar stays visible (a frameless
// window maximized the normal way would cover the whole screen).
func (c *windowCtl) maximizeToWorkArea(x, y int, useWindow bool) {
	c.restoreRect = c.rect()
	_, work := c.monitorAt(x, y, useWindow)
	c.animate(work, 200*time.Millisecond)
	c.maximized.Store(true)
}

func (c *windowCtl) pageMaximized(on bool) {
	if c.evalJS == nil {
		return
	}
	if on {
		c.evalJS("document.body.classList.add('tb-maxed')")
	} else {
		c.evalJS("document.body.classList.remove('tb-maxed')")
	}
}

// ---------------------------------------------------------------- JS-callable actions

func (c *windowCtl) minimize() { showWindow(c.hwnd, swMinimize) }

// toggleMaximize flips maximized / normal and returns the state the window is heading to.
func (c *windowCtl) toggleMaximize() bool {
	if !c.busy.TryLock() { // a drag or animation is running
		return c.maximized.Load()
	}
	target := !c.maximized.Load()
	go func() {
		defer c.busy.Unlock()
		defer func() {
			if r := recover(); r != nil {
				slog.Error("maximize / restore failed", "panic", r)
			}
		}()
		if target {
			c.maximizeToWorkArea(0, 0, true)
		} else {
			c.animate(c.restoreRect, 200*time.Millisecond)
			c.maximized.Store(false)
		}
	}()
	return target
}

// startDrag begins following the mouse (the page calls it on pointer-down in the title bar).
func (c *windowCtl) startDrag() bool {
	go c.dragLoop()
	return true
}

func (c *windowCtl) dragLoop() {
	if !c.busy.TryLock() {
		return
	}
	defer c.busy.Unlock()
	defer func() {
		if r := recover(); r != nil {
			slog.Error("window drag failed", "panic", r)
		}
	}()

	sx, sy := cursorPos()
	r0 := c.rect()
	ox, oy := sx-int(r0.Left), sy-int(r0.Top) // where in the window it was grabbed
	started := false
	lastX, lastY := math.MinInt, math.MinInt
	for primaryPressed() {
		cx, cy := cursorPos()
		if !started {
			if abs(cx-sx) < 4 && abs(cy-sy) < 4 { // a click, not a drag yet
				time.Sleep(10 * time.Millisecond)
				continue
			}
			started = true
			if c.maximized.Load() { // dragging a maximized window restores it
				rw, rh := c.restoreRect.w(), c.restoreRect.h()
				ox = rw * (sx - int(r0.Left)) / max(r0.w(), 1)
				oy = min(oy, 20)
				c.setRect(cx-ox, cy-oy, rw, rh, false)
				c.maximized.Store(false)
				c.pageMaximized(false)
				continue
			}
		}
		x, y := cx-ox, cy-oy
		if x != lastX || y != lastY {
			c.setRect(x, y, 0, 0, true)
			lastX, lastY = x, y
		}
		time.Sleep(6 * time.Millisecond)
	}
	if started && !c.maximized.Load() { // dropped on the top edge: snap = maximize
		cx, cy := cursorPos()
		full, _ := c.monitorAt(cx, cy, false)
		if cy <= int(full.Top)+4 {
			c.maximizeToWorkArea(cx, cy, false)
			c.pageMaximized(true)
		}
	}
}

// beginResize follows the mouse while an edge or corner of the window is dragged.
// edge is a combination of n, s, e, w.
func (c *windowCtl) beginResize(edge string) bool {
	if c.maximized.Load() {
		return false
	}
	go c.resizeLoop(edge)
	return true
}

func (c *windowCtl) resizeLoop(edge string) {
	if !c.busy.TryLock() {
		return
	}
	defer c.busy.Unlock()
	defer func() {
		if r := recover(); r != nil {
			slog.Error("window resize failed", "panic", r)
		}
	}()

	has := func(ch byte) bool {
		for i := 0; i < len(edge); i++ {
			if edge[i] == ch {
				return true
			}
		}
		return false
	}
	sx, sy := cursorPos()
	r0 := c.rect()
	x0, y0, w0, h0 := int(r0.Left), int(r0.Top), r0.w(), r0.h()
	scale := c.dpiScale()
	minW, minH := int(minWidth*scale), int(minHeight*scale)
	var last [4]int
	haveLast := false
	for primaryPressed() {
		cx, cy := cursorPos()
		dx, dy := cx-sx, cy-sy
		w, h := w0, h0
		switch {
		case has('e'):
			w = w0 + dx
		case has('w'):
			w = w0 - dx
		}
		switch {
		case has('s'):
			h = h0 + dy
		case has('n'):
			h = h0 - dy
		}
		w, h = max(minW, w), max(minH, h)
		x, y := x0, y0
		if has('w') { // keep the opposite edge in place
			x = x0 + (w0 - w)
		}
		if has('n') {
			y = y0 + (h0 - h)
		}
		if cur := [4]int{x, y, w, h}; !haveLast || cur != last {
			c.setRect(x, y, w, h, false)
			last, haveLast = cur, true
		}
		time.Sleep(8 * time.Millisecond)
	}
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// ---------------------------------------------------------------- show / hide

func (c *windowCtl) show() {
	showWindow(c.hwnd, swShow)
	showWindow(c.hwnd, swRestore)
	pSetForegroundWindow.Call(c.hwnd)
}

func (c *windowCtl) hide() { showWindow(c.hwnd, swHide) }
