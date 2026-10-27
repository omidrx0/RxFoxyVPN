//go:build windows

package desktop

import (
	"bytes"
	_ "embed"
	"encoding/binary"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"

	"rxvpn/internal/locale"
)

//go:embed tray_on.png
var trayOnPNG []byte

//go:embed tray_off.png
var trayOffPNG []byte

const (
	nimAdd    = 0
	nimModify = 1
	nimDelete = 2

	nifMessage = 0x1
	nifIcon    = 0x2
	nifTip     = 0x4
	nifInfo    = 0x10

	niifInfo = 0x1

	mfString    = 0x0
	mfGrayed    = 0x1
	mfPopup     = 0x10
	mfSeparator = 0x800

	tpmRightButton = 0x0002
	tpmBottomAlign = 0x0020

	trayCallbackMsg = wmApp + 1
	trayIconID      = 1

	idOpen   = 1
	idToggle = 2
	idQuit   = 99
	idModeBase = 10 // + index in trayModes
	idLangBase = 20 // + index in languageNames
)

var trayModes = []string{"tun", "system", "manual"}

type notifyIconData struct {
	CbSize           uint32
	HWnd             uintptr
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            uintptr
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UTimeout         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         windows.GUID
	HBalloonIcon     uintptr
}

func copyUTF16(dst []uint16, s string) {
	src, _ := windows.UTF16FromString(s)
	n := copy(dst, src)
	if n == len(dst) {
		dst[n-1] = 0
	}
}

// icoFromPNG wraps a PNG image in a one-image ICO container (Windows Vista and later read
// PNG-compressed icons), so it can be loaded with LoadImage.
func icoFromPNG(png []byte, size int) []byte {
	var b bytes.Buffer
	binary.Write(&b, binary.LittleEndian, [3]uint16{0, 1, 1})
	dim := byte(size)
	if size >= 256 {
		dim = 0
	}
	b.Write([]byte{dim, dim, 0, 0})
	binary.Write(&b, binary.LittleEndian, [2]uint16{1, 32})
	binary.Write(&b, binary.LittleEndian, [2]uint32{uint32(len(png)), 22})
	b.Write(png)
	return b.Bytes()
}

// loadIconBytes writes ICO data to a temp file and loads it as an HICON.
func loadIconBytes(name string, ico []byte, w, h int) uintptr {
	path := filepath.Join(os.TempDir(), name)
	if err := os.WriteFile(path, ico, 0o600); err != nil {
		return 0
	}
	return loadIconFile(path, w, h)
}

// trayActions are what the tray menu can make the shell do.
type trayActions struct {
	open        func()
	toggle      func()
	setMode     func(mode string)
	setLang     func(lang string)
	quit        func()
	isConnected func() bool
	statusText  func() string
	mode        func() string
}

// tray is the notification-area icon plus its right-click menu.
type tray struct {
	act trayActions

	mu        sync.Mutex
	hwnd      uintptr
	iconOn    uintptr
	iconOff   uintptr
	connected bool
	taskbarCreated uint32

	ready chan error
	done  chan struct{}
}

// startTray creates the tray icon on its own OS thread (with its own message loop).
func startTray(act trayActions) (*tray, error) {
	t := &tray{act: act, ready: make(chan error, 1), done: make(chan struct{})}
	t.iconOn = loadIconBytes("rxvpn_tray_on.ico", icoFromPNG(trayOnPNG, 64), 0, 0)
	t.iconOff = loadIconBytes("rxvpn_tray_off.ico", icoFromPNG(trayOffPNG, 64), 0, 0)
	go t.loop()
	if err := <-t.ready; err != nil {
		return nil, err
	}
	return t, nil
}

func (t *tray) loop() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	defer close(t.done)

	hinst, _, _ := pGetModuleHandle.Call(0)
	className := utf16("RXVPNTrayWindow")
	wndProc := windows.NewCallback(t.wndProc)
	wc := wndClassEx{
		CbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
		LpfnWndProc:   wndProc,
		HInstance:     hinst,
		LpszClassName: className,
	}
	if r, _, err := pRegisterClassEx.Call(uintptr(unsafe.Pointer(&wc))); r == 0 {
		t.ready <- fmt.Errorf("RegisterClassEx: %w", err)
		return
	}
	title := utf16("RXFOXYVPN tray")
	hwnd, _, err := pCreateWindowEx.Call(0, uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(title)),
		0, 0, 0, 0, 0, 0, 0, hinst, 0)
	runtime.KeepAlive(className)
	runtime.KeepAlive(title)
	if hwnd == 0 {
		t.ready <- fmt.Errorf("CreateWindowEx: %w", err)
		return
	}
	t.mu.Lock()
	t.hwnd = hwnd
	t.mu.Unlock()
	tbName := utf16("TaskbarCreated")
	tc, _, _ := pRegisterWindowMessage.Call(uintptr(unsafe.Pointer(tbName)))
	runtime.KeepAlive(tbName)
	t.taskbarCreated = uint32(tc)
	if !t.notify(nimAdd, nifMessage|nifIcon|nifTip, t.iconOff, "RXFOXYVPN", "", "") {
		t.ready <- fmt.Errorf("Shell_NotifyIcon failed")
		pDestroyWindow.Call(hwnd)
		return
	}
	t.ready <- nil

	var m winMsg
	for {
		r, _, _ := pGetMessage.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 { // WM_QUIT (0) or an error (-1)
			break
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		pDispatchMessage.Call(uintptr(unsafe.Pointer(&m)))
	}
}

// notify adds / modifies / removes the icon; balloonTitle/balloonText show a balloon notification.
func (t *tray) notify(op uintptr, flags uint32, icon uintptr, tip, balloonTitle, balloonText string) bool {
	t.mu.Lock()
	hwnd := t.hwnd
	t.mu.Unlock()
	nid := notifyIconData{
		HWnd: hwnd, UID: trayIconID, UFlags: flags,
		UCallbackMessage: trayCallbackMsg, HIcon: icon,
	}
	nid.CbSize = uint32(unsafe.Sizeof(nid))
	copyUTF16(nid.SzTip[:], tip)
	if flags&nifInfo != 0 {
		copyUTF16(nid.SzInfoTitle[:], balloonTitle)
		copyUTF16(nid.SzInfo[:], balloonText)
		nid.DwInfoFlags = niifInfo
	}
	r, _, _ := pShellNotifyIcon.Call(op, uintptr(unsafe.Pointer(&nid)))
	return r != 0
}

// Update changes the icon colour and tooltip to match the connection state.
func (t *tray) Update(connected bool) {
	t.mu.Lock()
	t.connected = connected
	t.mu.Unlock()
	icon, state := t.iconOff, tt("st_off")
	if connected {
		icon, state = t.iconOn, tt("st_on")
	}
	t.notify(nimModify, nifIcon|nifTip, icon, "RXFOXYVPN - "+state, "", "")
}

// Notify shows a balloon notification.
func (t *tray) Notify(text string) {
	icon := t.iconOff
	t.mu.Lock()
	if t.connected {
		icon = t.iconOn
	}
	t.mu.Unlock()
	t.notify(nimModify, nifInfo, icon, "RXFOXYVPN", "RXFOXYVPN", text)
}

// Stop removes the icon and ends the tray thread.
func (t *tray) Stop() {
	t.mu.Lock()
	hwnd := t.hwnd
	t.mu.Unlock()
	if hwnd == 0 {
		return
	}
	t.notify(nimDelete, 0, 0, "", "", "")
	postMessage(hwnd, wmClose, 0, 0) // DefWindowProc destroys the window on its own thread -> WM_DESTROY -> quit
	select {
	case <-t.done:
	case <-time.After(3 * time.Second):
	}
	for _, h := range []uintptr{t.iconOn, t.iconOff} {
		if h != 0 {
			pDestroyIcon.Call(h)
		}
	}
}

func (t *tray) wndProc(hwnd, message, wparam, lparam uintptr) uintptr {
	switch {
	case uint32(message) == trayCallbackMsg:
		switch uint32(lparam) & 0xFFFF {
		case wmLButtonUp, wmLDblClk:
			go t.act.open()
		case wmRButtonUp:
			t.showMenu(hwnd)
		}
		return 0
	case uint32(message) == wmCommand:
		go t.command(int(wparam & 0xFFFF))
		return 0
	case t.taskbarCreated != 0 && uint32(message) == t.taskbarCreated: // Explorer restarted
		t.mu.Lock()
		connected := t.connected
		t.mu.Unlock()
		icon := t.iconOff
		if connected {
			icon = t.iconOn
		}
		t.notify(nimAdd, nifMessage|nifIcon|nifTip, icon, "RXFOXYVPN", "", "")
		return 0
	case uint32(message) == wmDestroy:
		pPostQuitMessage.Call(0)
		return 0
	}
	r, _, _ := pDefWindowProc.Call(hwnd, message, wparam, lparam)
	return r
}

func (t *tray) command(id int) {
	switch {
	case id == idOpen:
		t.act.open()
	case id == idToggle:
		t.act.toggle()
	case id == idQuit:
		t.act.quit()
	case id >= idModeBase && id < idModeBase+len(trayModes):
		t.act.setMode(trayModes[id-idModeBase])
	case id >= idLangBase && id < idLangBase+len(languageNames):
		t.act.setLang(languageNames[id-idLangBase].Code)
	}
}

func appendMenu(menu uintptr, flags uint32, id uintptr, text string) {
	p := utf16(text)
	pAppendMenu.Call(menu, uintptr(flags), id, uintptr(unsafe.Pointer(p)))
	runtime.KeepAlive(p)
}

// showMenu builds the menu fresh every time, so labels, checks and language are always current.
func (t *tray) showMenu(hwnd uintptr) {
	menu, _, _ := pCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	defer pDestroyMenu.Call(menu)

	toggleText := tt("connect")
	if t.act.isConnected() {
		toggleText = tt("disconnect")
	}
	appendMenu(menu, mfString, idOpen, tt("open"))
	appendMenu(menu, mfString|mfGrayed, 0, t.act.statusText())
	appendMenu(menu, mfSeparator, 0, "")
	appendMenu(menu, mfString, idToggle, toggleText)

	modeMenu, _, _ := pCreatePopupMenu.Call()
	modeLabels := []string{tt("mode_tun"), tt("mode_system"), tt("mode_manual")}
	for i, label := range modeLabels {
		appendMenu(modeMenu, mfString, uintptr(idModeBase+i), label)
	}
	current := t.act.mode()
	for i, m := range trayModes {
		if m == current {
			pCheckMenuRadioItem.Call(modeMenu, idModeBase, uintptr(idModeBase+len(trayModes)-1), uintptr(idModeBase+i), 0)
		}
	}
	appendMenu(menu, mfPopup, modeMenu, tt("connection_mode"))

	langMenu, _, _ := pCreatePopupMenu.Call()
	for i, l := range languageNames {
		appendMenu(langMenu, mfString, uintptr(idLangBase+i), l.Name)
	}
	for i, l := range languageNames {
		if l.Code == locale.Get() {
			pCheckMenuRadioItem.Call(langMenu, idLangBase, uintptr(idLangBase+len(languageNames)-1), uintptr(idLangBase+i), 0)
		}
	}
	appendMenu(menu, mfPopup, langMenu, tt("language"))

	appendMenu(menu, mfSeparator, 0, "")
	appendMenu(menu, mfString, idQuit, tt("quit"))
	pSetMenuDefaultItem.Call(menu, idOpen, 0)

	x, y := cursorPos()
	pSetForegroundWindow.Call(hwnd) // required, or the menu does not close when clicking elsewhere
	pTrackPopupMenu.Call(menu, tpmRightButton|tpmBottomAlign, uintptr(x), uintptr(y), 0, hwnd, 0)
	postMessage(hwnd, wmNull, 0, 0)
	slog.Debug("tray menu closed")
}
