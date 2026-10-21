#!/usr/bin/env python3
"""
Launcher: native app window (pywebview) + tray icon next to the Windows clock, or an Edge/Chrome app window.

Run:   python -m rxvpn                 (app window + tray icon)
       pythonw -m rxvpn                (no terminal window)
       python -m rxvpn --browser       (no pywebview/pystray; Edge/Chrome app window)
       python -m rxvpn --port 8787 --socks-port 10808 --http-port 10809
       python -m rxvpn --api-proxy socks5h://127.0.0.1:1080    (if Firefox Accounts is blocked)
"""
from __future__ import annotations

import argparse
import logging
import os
import shutil
import subprocess
import sys
import threading
import time
import webbrowser

from . import foxy_bridge as fb
from .app import App
from .server import friendly_error, start_server
from .settings import ICON_PATH, INDEX_PATH, UI, load_ui, save_ui, tr
from .sysproxy import is_admin

log = logging.getLogger("rxvpn")


def open_app_window(url: str) -> None:
    """Open the panel as a standalone app window (Edge/Chrome --app) or fall back to the default browser."""
    cands: list = []
    if sys.platform.startswith("win"):
        for var in ("PROGRAMFILES", "PROGRAMFILES(X86)", "LOCALAPPDATA"):
            base = os.environ.get(var)
            if base:
                cands += [os.path.join(base, "Microsoft", "Edge", "Application", "msedge.exe"),
                          os.path.join(base, "Google", "Chrome", "Application", "chrome.exe")]
    elif sys.platform == "darwin":
        cands = ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                 "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"]
    else:
        cands = [shutil.which(n) for n in ("google-chrome", "chromium", "chromium-browser", "microsoft-edge")]
    for exe in cands:
        if exe and os.path.exists(exe):
            try:
                subprocess.Popen([exe, f"--app={url}", "--window-size=1180,760"])
                return
            except OSError:
                continue
    webbrowser.open(url)





def make_icon_fallback(connected: bool):
    """Tray icon that matches the panel logo: gradient hexagon with an X (blue -> violet -> pink)."""
    from PIL import Image, ImageDraw
    S = 256
    stops = ((56, 217, 255), (139, 92, 246), (255, 95, 176)) if connected else \
            ((126, 134, 176), (98, 102, 146), (150, 116, 168))
    grad = Image.new("RGB", (S, S))
    px = grad.load()
    for y in range(S):
        for x in range(S):
            t = (x + y) / (2 * S - 2) * 2
            i = min(int(t), 1)
            f = t - i
            px[x, y] = tuple(round(stops[i][k] + (stops[i + 1][k] - stops[i][k]) * f) for k in range(3))
    pts = [(128, 16), (228, 72), (228, 184), (128, 240), (28, 184), (28, 72)]
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).line(pts + [pts[0]], fill=255, width=22, joint="curve")
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.polygon(pts, fill=(11, 13, 36, 255))
    im.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(im)
    white = (240, 242, 255, 255)
    d.line((88, 88, 168, 168), fill=white, width=24)
    d.line((168, 88, 88, 168), fill=white, width=24)
    dot = (52, 224, 161, 255) if connected else (120, 128, 170, 255)
    d.ellipse((165, 165, 245, 245), fill=(11, 13, 36, 255))
    d.ellipse((177, 177, 233, 233), fill=dot)
    return im.resize((64, 64), Image.LANCZOS)


def make_icon(connected: bool):
    """Tray icon = the RXVPN fox (icon.ico) + a small status dot. Offline: dimmed and grey."""
    try:
        from PIL import Image, ImageDraw, ImageEnhance
        ico = Image.open(ICON_PATH)
        try:
            base = ico.ico.getimage((256, 256)).convert("RGBA")
        except Exception:  # noqa: BLE001
            base = ico.convert("RGBA")
        base = base.resize((64, 64), Image.LANCZOS)
        if not connected:
            base = ImageEnhance.Brightness(ImageEnhance.Color(base).enhance(0.0)).enhance(0.7)
        d = ImageDraw.Draw(base)
        d.ellipse((37, 37, 64, 64), fill=(11, 13, 36, 255))
        d.ellipse((42, 42, 59, 59), fill=(52, 224, 161, 255) if connected else (120, 128, 170, 255))
        return base
    except Exception:  # noqa: BLE001
        log.warning("could not load %s, using the drawn icon", ICON_PATH)
        return make_icon_fallback(connected)


def set_window_icon(window) -> None:
    """Windows: give the app window (title bar + taskbar) the fox icon."""
    if not sys.platform.startswith("win") or not os.path.exists(ICON_PATH):
        return
    try:
        import ctypes
        user32 = ctypes.windll.user32
        hwnd = int(window.native.Handle.ToInt32())
        for size, which in ((16, 0), (32, 1)):               # ICON_SMALL, ICON_BIG
            h = user32.LoadImageW(None, ICON_PATH, 1, size, size, 0x10)   # IMAGE_ICON, LR_LOADFROMFILE
            if h:
                user32.SendMessageW(hwnd, 0x80, which, h)    # WM_SETICON
    except Exception:  # noqa: BLE001
        log.exception("could not set the window icon")


def copy_text(text: str) -> None:
    try:
        subprocess.run(["clip"], input=text.encode("utf-16le"), check=False,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except OSError:
        pass


def already_running(allow_existing: bool = False) -> bool:
    """Windows: one RXVPN at a time (a second launch just tells the user where the first one is)."""
    if not sys.platform.startswith("win"):
        return False
    import ctypes
    ctypes.windll.kernel32.CreateMutexW(None, False, "Local\\RXVPN_single_instance")
    if ctypes.windll.kernel32.GetLastError() == 183 and not allow_existing:  # ERROR_ALREADY_EXISTS
        ctypes.windll.user32.MessageBoxW(0, tr("برنامه در حال اجراست.\nآیکونش را کنار ساعت ویندوز پیدا کن.",
                                               "RXFOXYVPN is already running.\nFind its icon next to the Windows clock."), "RXFOXYVPN", 0x40)
        return True
    return False


# Tray menu labels: same wording as the panel buttons, in the panel's current language.
TRAY = {
    "open":       ("باز کردن RXFOXYVPN", "Open RXFOXYVPN"),
    "connect":    ("اتصال", "Connect"),
    "disconnect": ("قطع اتصال", "Disconnect"),
    "servers":    ("سرورها", "Servers"),
    "connection_mode": ("حالت اتصال", "Connection mode"),
    "mode_tun": ("TUN سراسری", "Full-device TUN"),
    "mode_system": ("پروکسی سیستم", "System proxy"),
    "mode_manual": ("پروکسی دستی", "Manual proxy"),
    "ping":       ("پینگ بگیر", "Test ping"),
    "copy_socks": ("کپی SOCKS5", "Copy SOCKS5"),
    "copy_http":  ("کپی HTTP", "Copy HTTP"),
    "language":   ("زبان", "Language"),
    "logout":     ("خروج از حساب", "Sign out"),
    "quit":       ("خروج", "Exit"),
    "st_on":      ("متصل", "Connected"),
    "st_off":     ("قطع", "Offline"),
    "loading":    ("در حال دریافت…", "Loading…"),
    "running":    ("برنامه در حال اجراست", "App is running"),
    "no_reply":   ("پاسخ نداد", "No reply"),
    "need_login": ("اول در پنل وارد حساب Firefox شو.", "Sign in to your Firefox account in the panel first."),
    "connected":  ("وصل شدی.", "Connected."),
    "disconnected": ("اتصال قطع شد.", "Disconnected."),
    "signed_out": ("از حساب خارج شدی.", "Signed out."),
}


TRAY_I18N = {
    "fa": {
        "open":"باز کردن RXFOXYVPN", "connect":"اتصال", "disconnect":"قطع اتصال",
        "connection_mode":"حالت اتصال", "mode_tun":"تونل سراسری (TUN)",
        "mode_system":"پروکسی سیستم", "mode_manual":"پروکسی دستی",
        "language":"زبان", "quit":"خروج", "st_on":"متصل", "st_off":"قطع اتصال",
        "running":"برنامه در حال اجراست", "loading":"در حال دریافت…",
        "need_login":"ابتدا در برنامه وارد حساب خود شوید.", "connected":"اتصال برقرار شد.",
        "disconnected":"اتصال قطع شد.", "no_reply":"پاسخی دریافت نشد", "signed_out":"از حساب خارج شدید.",
    },
    "en": {
        "open":"Open RXFOXYVPN", "connect":"Connect", "disconnect":"Disconnect",
        "connection_mode":"Connection mode", "mode_tun":"Full-device TUN",
        "mode_system":"System proxy", "mode_manual":"Manual proxy",
        "language":"Language", "quit":"Exit", "st_on":"Connected", "st_off":"Disconnected",
        "running":"RXFOXYVPN is running", "loading":"Loading…",
        "need_login":"Please sign in to your account in the app first.", "connected":"Connected.",
        "disconnected":"Disconnected.", "no_reply":"No reply", "signed_out":"Signed out.",
    },
    "ar": {
        "open":"فتح RXFOXYVPN", "connect":"اتصال", "disconnect":"قطع الاتصال",
        "connection_mode":"وضع الاتصال", "mode_tun":"نفق الجهاز بالكامل (TUN)",
        "mode_system":"وكيل النظام", "mode_manual":"وكيل يدوي",
        "language":"اللغة", "quit":"خروج", "st_on":"متصل", "st_off":"غير متصل",
        "running":"تطبيق RXFOXYVPN قيد التشغيل", "loading":"جارٍ التحميل…",
        "need_login":"يرجى تسجيل الدخول إلى حسابك في التطبيق أولاً.", "connected":"تم الاتصال.",
        "disconnected":"تم قطع الاتصال.", "no_reply":"لا توجد استجابة", "signed_out":"تم تسجيل الخروج.",
    },
    "ru": {
        "open":"Открыть RXFOXYVPN", "connect":"Подключиться", "disconnect":"Отключиться",
        "connection_mode":"Режим подключения", "mode_tun":"Полный туннель (TUN)",
        "mode_system":"Системный прокси", "mode_manual":"Ручной прокси",
        "language":"Язык", "quit":"Выход", "st_on":"Подключено", "st_off":"Не подключено",
        "running":"RXFOXYVPN работает", "loading":"Загрузка…",
        "need_login":"Сначала войдите в свою учётную запись в приложении.", "connected":"Подключено.",
        "disconnected":"Соединение отключено.", "no_reply":"Нет ответа", "signed_out":"Вы вышли из аккаунта.",
    },
    "ko": {
        "open":"RXFOXYVPN 열기", "connect":"연결", "disconnect":"연결 해제",
        "connection_mode":"연결 모드", "mode_tun":"전체 기기 TUN",
        "mode_system":"시스템 프록시", "mode_manual":"수동 프록시",
        "language":"언어", "quit":"종료", "st_on":"연결됨", "st_off":"연결 끊김",
        "running":"RXFOXYVPN 실행 중", "loading":"불러오는 중…",
        "need_login":"먼저 앱에서 계정에 로그인하세요.", "connected":"연결되었습니다.",
        "disconnected":"연결이 해제되었습니다.", "no_reply":"응답 없음", "signed_out":"로그아웃되었습니다.",
    },
    "zh": {
        "open":"打开 RXFOXYVPN", "connect":"连接", "disconnect":"断开连接",
        "connection_mode":"连接模式", "mode_tun":"全设备隧道 (TUN)",
        "mode_system":"系统代理", "mode_manual":"手动代理",
        "language":"语言", "quit":"退出", "st_on":"已连接", "st_off":"未连接",
        "running":"RXFOXYVPN 正在运行", "loading":"正在加载…",
        "need_login":"请先在应用中登录您的账户。", "connected":"连接成功。",
        "disconnected":"已断开连接。", "no_reply":"无响应", "signed_out":"已退出登录。",
    },
}


def tt(key: str) -> str:
    lang = UI.get("lang", "en")
    if lang not in TRAY_I18N:
        lang = "en"
    return TRAY_I18N[lang].get(key, TRAY.get(key, (key, key))[1])


MIN_W, MIN_H = 880, 600


class WindowApi:
    """What the custom title bar (web/app.js) can ask the window to do, via window.pywebview.api.

    Moving and resizing are done here, in a small python thread that follows the mouse with plain
    Win32 calls (GetCursorPos / SetWindowPos). That is what keeps the drag smooth: nothing goes
    back and forth through the JavaScript bridge while the mouse moves.
    """

    SWP_NOSIZE, SWP_NOZORDER, SWP_NOACTIVATE = 0x0001, 0x0004, 0x0010

    def __init__(self, on_close):
        self._window = None            # underscore: pywebview must not expose this to JavaScript
        self._on_close = on_close
        self._maximized = False
        self._restore_rect = (0, 0, MIN_W, MIN_H)
        self._busy = threading.Lock()  # one drag / resize at a time

    # ------------------------------------------------------------------ plain window actions
    def minimize(self):
        self._window.minimize()

    def close(self):
        self._on_close()

    def toggle_maximize(self) -> bool:
        w = self._window
        try:
            if sys.platform.startswith("win"):
                if not self._busy.acquire(blocking=False):          # a drag / animation is running
                    return self._maximized
                try:
                    if self._maximized:
                        self._animate_rect(self._restore_rect)
                        self._maximized = False
                    else:
                        self._maximize_to_workarea()
                finally:
                    self._busy.release()
            elif self._maximized:
                w.restore()
                self._maximized = False
            else:
                w.maximize()
                self._maximized = True
        except Exception:  # noqa: BLE001
            log.exception("maximize / restore failed")
        return self._maximized

    # ------------------------------------------------------------------ Win32 helpers
    def _hwnd(self):
        import ctypes
        from ctypes import wintypes
        return wintypes.HWND(int(self._window.native.Handle.ToInt64())), ctypes.windll.user32

    def _get_rect(self):
        import ctypes
        from ctypes import wintypes
        hwnd, user32 = self._hwnd()
        r = wintypes.RECT()
        user32.GetWindowRect(hwnd, ctypes.byref(r))
        return r.left, r.top, r.right - r.left, r.bottom - r.top

    def _animate_rect(self, target, seconds: float = 0.2) -> None:
        """Glide the window from where it is to `target` (x, y, w, h) with an ease-out curve."""
        x0, y0, w0, h0 = self._get_rect()
        x1, y1, w1, h1 = target
        start = time.perf_counter()
        while True:
            t = (time.perf_counter() - start) / seconds
            if t >= 1:
                break
            e = 1 - (1 - t) ** 3                                    # ease-out cubic
            self._set_rect(x0 + (x1 - x0) * e, y0 + (y1 - y0) * e, w0 + (w1 - w0) * e, h0 + (h1 - h0) * e)
            time.sleep(0.008)
        self._set_rect(x1, y1, w1, h1)

    def _set_rect(self, x, y, w, h, move_only=False):
        hwnd, user32 = self._hwnd()
        flags = self.SWP_NOZORDER | self.SWP_NOACTIVATE | (self.SWP_NOSIZE if move_only else 0)
        user32.SetWindowPos(hwnd, None, int(x), int(y), int(w), int(h), flags)

    @staticmethod
    def _cursor():
        import ctypes
        from ctypes import wintypes
        pt = wintypes.POINT()
        ctypes.windll.user32.GetCursorPos(ctypes.byref(pt))
        return pt.x, pt.y

    @staticmethod
    def _pressed() -> bool:
        import ctypes
        user32 = ctypes.windll.user32
        vk = 0x02 if user32.GetSystemMetrics(23) else 0x01      # primary button (SM_SWAPBUTTON)
        return bool(user32.GetAsyncKeyState(vk) & 0x8000)

    def _scale(self) -> float:
        try:
            hwnd, user32 = self._hwnd()
            return max(1.0, user32.GetDpiForWindow(hwnd) / 96.0)
        except Exception:  # noqa: BLE001
            return 1.0

    def _monitor_at(self, point=None):
        """(rcMonitor, rcWork) of the monitor under `point` (x, y), or the one the window is on."""
        import ctypes
        from ctypes import wintypes
        hwnd, user32 = self._hwnd()

        class MonitorInfo(ctypes.Structure):
            _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT),
                        ("rcWork", wintypes.RECT), ("dwFlags", wintypes.DWORD)]

        user32.MonitorFromWindow.restype = wintypes.HANDLE
        user32.MonitorFromPoint.restype = wintypes.HANDLE
        if point is None:
            monitor = user32.MonitorFromWindow(hwnd, 2)              # MONITOR_DEFAULTTONEAREST
        else:
            monitor = user32.MonitorFromPoint(wintypes.POINT(int(point[0]), int(point[1])), 2)
        info = MonitorInfo()
        info.cbSize = ctypes.sizeof(MonitorInfo)
        if not user32.GetMonitorInfoW(monitor, ctypes.byref(info)):
            raise ctypes.WinError()
        return info.rcMonitor, info.rcWork

    def _maximize_to_workarea(self, point=None) -> None:
        """Maximize to the monitor's work area, so the taskbar stays visible (a frameless window
        maximized the normal way would cover the whole screen)."""
        self._restore_rect = self._get_rect()
        _, work = self._monitor_at(point)
        self._animate_rect((work.left, work.top, work.right - work.left, work.bottom - work.top))
        self._maximized = True

    def _page_maximized(self) -> None:
        try:
            self._window.evaluate_js("document.body.classList.add('tb-maxed')")
        except Exception:  # noqa: BLE001
            pass

    def _page_not_maximized(self) -> None:
        try:
            self._window.evaluate_js("document.body.classList.remove('tb-maxed')")
        except Exception:  # noqa: BLE001
            pass

    # ------------------------------------------------------------------ move (title bar)
    def start_drag(self) -> bool:
        if not sys.platform.startswith("win"):
            return False                  # the page falls back to pywebview's own drag region
        threading.Thread(target=self._drag_loop, daemon=True, name="rxvpn-drag").start()
        return True

    def _drag_loop(self) -> None:
        if not self._busy.acquire(blocking=False):
            return
        try:
            sx, sy = self._cursor()
            x0, y0, w0, h0 = self._get_rect()
            ox, oy = sx - x0, sy - y0                           # where in the window we grabbed it
            started = False
            while self._pressed():
                cx, cy = self._cursor()
                if not started:
                    if abs(cx - sx) < 4 and abs(cy - sy) < 4:   # a click, not a drag yet
                        time.sleep(0.01)
                        continue
                    started = True
                    if self._maximized:                         # dragging a maximized window restores it
                        rw, rh = self._restore_rect[2], self._restore_rect[3]
                        ox, oy = int(rw * (sx - x0) / max(w0, 1)), min(oy, 20)
                        self._set_rect(cx - ox, cy - oy, rw, rh)
                        self._maximized = False
                        self._page_not_maximized()
                        continue
                pos = (cx - ox, cy - oy)
                if pos != getattr(self, "_last_pos", None) or not started:
                    self._set_rect(pos[0], pos[1], 0, 0, move_only=True)
                    self._last_pos = pos
                time.sleep(0.006)
            if started and not self._maximized:                 # dropped on the top edge: snap = maximize
                cx, cy = self._cursor()
                monitor, _ = self._monitor_at((cx, cy))
                if cy <= monitor.top + 4:
                    self._maximize_to_workarea((cx, cy))
                    self._page_maximized()
        except Exception:  # noqa: BLE001
            log.exception("window drag failed")
        finally:
            self._last_pos = None
            self._busy.release()

    # ------------------------------------------------------------------ resize (edges)
    def begin_resize(self, edge: str) -> bool:
        if not sys.platform.startswith("win") or self._maximized:
            return False
        threading.Thread(target=self._resize_loop, args=(str(edge),), daemon=True, name="rxvpn-resize").start()
        return True

    def _resize_loop(self, edge: str) -> None:
        if not self._busy.acquire(blocking=False):
            return
        try:
            sx, sy = self._cursor()
            x0, y0, w0, h0 = self._get_rect()
            min_w, min_h = int(MIN_W * self._scale()), int(MIN_H * self._scale())
            last = None
            while self._pressed():
                cx, cy = self._cursor()
                dx, dy = cx - sx, cy - sy
                w = w0 + dx if "e" in edge else w0 - dx if "w" in edge else w0
                h = h0 + dy if "s" in edge else h0 - dy if "n" in edge else h0
                w, h = max(min_w, w), max(min_h, h)
                x = x0 + (w0 - w) if "w" in edge else x0       # keep the opposite edge in place
                y = y0 + (h0 - h) if "n" in edge else y0
                if (x, y, w, h) != last:
                    self._set_rect(x, y, w, h)
                    last = (x, y, w, h)
                time.sleep(0.008)
        except Exception:  # noqa: BLE001
            log.exception("window resize failed")
        finally:
            self._busy.release()


def run_desktop(app: App, url: str, native_frame: bool = False) -> None:
    """Native app window (pywebview / WebView2) + tray icon. Closing the window only hides it."""
    import webview

    state = {"quit": False, "tray": False}
    icon = None
    api = WindowApi(lambda: close_window())
    options = dict(width=1180, height=760, min_size=(MIN_W, MIN_H), background_color="#060714")
    page = url
    if not native_frame:                  # our own title bar (drawn by the page) instead of the Windows one
        options.update(frameless=True, easy_drag=False, shadow=True, js_api=api)
        page = url + "&tb=1"
    try:
        window = webview.create_window("RXFOXYVPN", page, **options)
    except TypeError:                     # older pywebview without the `shadow` option
        options.pop("shadow", None)
        window = webview.create_window("RXFOXYVPN", page, **options)
    api._window = window

    def notify(msg: str) -> None:
        try:
            if icon is not None and state["tray"]:
                icon.notify(msg, "RXFOXYVPN")
        except Exception:  # noqa: BLE001
            log.exception("notify failed")

    def show(*_):
        try:
            window.show()
            window.restore()
        except Exception:  # noqa: BLE001
            log.exception("show window failed")

    def on_closing():
        if state["quit"] or not state["tray"]:          # no tray icon -> closing really quits (never a hidden zombie)
            return True
        window.hide()
        notify(tt("running"))                           # short and simple: just "App is running"
        return False
    window.events.closing += on_closing

    def close_window():                                 # the title bar's X button
        if on_closing():
            window.destroy()
    window.events.shown += lambda: set_window_icon(window)
    if sys.platform.startswith("win"):
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("RXVPN.Panel")
        except Exception:  # noqa: BLE001
            pass

    def in_thread(fn):
        threading.Thread(target=fn, daemon=True).start()

    def current_code():
        st = app.status()
        return (st["country"] or {}).get("code") if st["state"] == "connected" else None

    def select_connection_mode(mode: str):
        try:
            app.set_connection({"mode": mode})
            icon.update_menu()
            notify(tt({"tun": "mode_tun", "system": "mode_system", "manual": "mode_manual"}[mode]))
        except Exception as e:  # noqa: BLE001
            notify(str(friendly_error(e)))

    # ---- actions (same actions as the panel buttons)
    def toggle(*_):
        def work():
            try:
                if app.connected:
                    app.disconnect()
                    notify(tt("disconnected"))
                    return
                if not app.account.has_session():
                    show()
                    notify(tt("need_login"))
                    return
                app.connect({"code": app.account.st.get("last_country") or "REC"})
                notify(tt("connected"))
            except Exception as e:  # noqa: BLE001
                notify(str(friendly_error(e)))
        in_thread(work)

    def pick_server(code: str):
        def work():
            try:
                if not app.account.has_session():
                    show()
                    notify(tt("need_login"))
                    return
                app.connect({"code": code})
                notify(tt("connected"))
            except Exception as e:  # noqa: BLE001
                notify(str(friendly_error(e)))
        in_thread(work)

    def test_ping(*_):
        def work():
            try:
                code = current_code() or app.account.st.get("last_country") or "REC"
                res = app.ping({"code": code})
                c = app.countries.get(code)
                name = fb.display_name(c) if c else code
                notify(f"{name}: {res['ms']} ms" if res["ms"] is not None else f"{name}: {tt('no_reply')}")
            except Exception as e:  # noqa: BLE001
                notify(str(friendly_error(e)))
        in_thread(work)

    def do_logout(*_):
        def work():
            try:
                app.logout()
                notify(tt("signed_out"))
            except Exception as e:  # noqa: BLE001
                notify(str(friendly_error(e)))
        in_thread(work)

    def set_language(lang: str):
        UI["lang"] = lang
        save_ui()
        try:
            icon.update_menu()
        except Exception:  # noqa: BLE001
            pass
        try:                                              # switch the open panel instantly
            window.evaluate_js(f"window.__setLang && window.__setLang('{lang}')")
        except Exception:  # noqa: BLE001
            pass

    def quit_all(*_):
        state["quit"] = True
        try:
            if app.connected:
                app.disconnect()
        except Exception:  # noqa: BLE001
            pass
        try:
            icon.stop()
        except Exception:  # noqa: BLE001
            pass
        window.destroy()

    def status_text(_item):
        st = app.status()
        if st["state"] == "connected" and st["country"]:
            return f"{tt('st_on')} · {st['country']['name']}"
        return tt("st_off")

    try:
        import pystray

        img_off, img_on = make_icon(False), make_icon(True)

        def mk_checked_server(code):
            return lambda item: current_code() == code

        def mk_pick(code):
            return lambda *_: pick_server(code)

        def server_items():
            countries = app.countries
            if not countries:                              # list not loaded yet: fetch in the background
                in_thread(lambda: app.ensure_countries())
                yield pystray.MenuItem(tt("loading"), None, enabled=False)
                return
            for c in fb.sorted_countries(countries):
                yield pystray.MenuItem(fb.display_name(c), mk_pick(c.code),
                                       checked=mk_checked_server(c.code), radio=True)

        LANG_NAMES = {"fa": "فارسی", "en": "English", "ar": "العربية", "ru": "Русский", "zh": "中文", "ko": "한국어"}

        def mk_lang(lang):
            return pystray.MenuItem(
                LANG_NAMES[lang],
                lambda *_: set_language(lang),
                checked=lambda item: UI.get("lang", "en") == lang, radio=True)

        # Compact tray menu: open app, live connection status/toggle, connection mode, language, exit.
        menu = pystray.Menu(
            pystray.MenuItem(lambda _i: tt("open"), show, default=True),
            pystray.MenuItem(status_text, None, enabled=False),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(lambda _i: tt("disconnect") if app.connected else tt("connect"), toggle),
            pystray.MenuItem(lambda _i: tt("connection_mode"), pystray.Menu(
                pystray.MenuItem(lambda _i: tt("mode_tun"), lambda *_: select_connection_mode("tun"),
                                 checked=lambda _i: app.connection_settings.get("mode", "system") == "tun", radio=True),
                pystray.MenuItem(lambda _i: tt("mode_system"), lambda *_: select_connection_mode("system"),
                                 checked=lambda _i: app.connection_settings.get("mode", "system") == "system", radio=True),
                pystray.MenuItem(lambda _i: tt("mode_manual"), lambda *_: select_connection_mode("manual"),
                                 checked=lambda _i: app.connection_settings.get("mode", "system") == "manual", radio=True),
            )),
            pystray.MenuItem(lambda _i: tt("language"), pystray.Menu(
                mk_lang("en"), mk_lang("fa"), mk_lang("ar"), mk_lang("ru"), mk_lang("zh"), mk_lang("ko")
            )),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem(lambda _i: tt("quit"), quit_all),
        )
        icon = pystray.Icon("RXVPN", img_off, "RXFOXYVPN", menu)

        def tray_main():
            try:
                icon.run()                                # own thread + own message loop
            except Exception:  # noqa: BLE001
                state["tray"] = False
                log.exception("tray icon stopped")
        state["tray"] = True
        threading.Thread(target=tray_main, daemon=True, name="rxvpn-tray").start()

        def watcher():                                    # icon colour/tooltip follow connection state and language
            last = None
            while not state["quit"]:
                cur = (app.connected, UI["lang"])
                if cur != last:
                    icon.icon = img_on if cur[0] else img_off
                    icon.title = f"RXFOXYVPN - {tt('st_on') if cur[0] else tt('st_off')}"
                    try:
                        icon.update_menu()
                    except Exception:  # noqa: BLE001
                        pass
                    last = cur
                time.sleep(1.5)
        threading.Thread(target=watcher, daemon=True).start()
    except Exception:  # noqa: BLE001
        state["tray"] = False
        log.exception("could not create the tray icon")

    try:
        webview.start()                 # blocks until the window is really destroyed
    finally:
        state["quit"] = True
        try:
            if icon:
                icon.stop()
        except Exception:  # noqa: BLE001
            pass


def require_windows_admin(admin_checker=None) -> None:
    """Block non-admin launches on Windows before the app opens any UI or network objects."""
    if os.name != "nt":
        return
    checker = admin_checker or is_admin
    if checker():
        return
    try:
        import ctypes
        ctypes.windll.user32.MessageBoxW(
            0,
            "RXFOXYVPN needs Administrator privileges. Please close it and run it as Administrator.",
            "RXFOXYVPN - Administrator required",
            0x10,
        )
    except Exception:  # noqa: BLE001
        pass
    raise PermissionError("RXFOXYVPN must be run as Administrator")


def main() -> int:
    load_ui()
    ap = argparse.ArgumentParser(description="RXVPN graphical panel for foxy_bridge.py")
    ap.add_argument("--port", type=int, default=0, help="panel port (default: random free port)")
    ap.add_argument("--socks-port", type=int, default=10808)
    ap.add_argument("--http-port", type=int, default=10809)
    ap.add_argument("--api-proxy", help="proxy URL used ONLY for sign-in / token / server-list / flag downloads")
    ap.add_argument("--ca", help="extra CA bundle (PEM) to trust for the tunnel servers")
    ap.add_argument("--insecure", action="store_true", help="DO NOT verify the tunnel certificate (unsafe)")
    ap.add_argument("--no-browser", action="store_true", help="do not open any window (panel only reachable by URL)")
    ap.add_argument("--native-frame", action="store_true", help="use the normal Windows title bar instead of the custom one")
    ap.add_argument("--browser", action="store_true", help="use an Edge/Chrome app window instead of the native window + tray icon")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    handlers = [logging.FileHandler(os.path.join(os.path.dirname(fb.state_path()), "rxvpn.log"), encoding="utf-8")]
    if sys.stderr:
        handlers.append(logging.StreamHandler())
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, handlers=handlers,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    if not args.verbose:
        logging.getLogger("urllib3").setLevel(logging.WARNING)
    if not fb.HAVE_H2:
        print("Missing dependency. Run:  pip install requests h2")
        log.error("h2 missing")
        return 1
    if not os.path.exists(INDEX_PATH):
        print(f"panel resource missing ({INDEX_PATH})")
        log.error("web/index.html missing")
        return 1
    if os.name == "nt":
        try:
            require_windows_admin()
        except PermissionError as exc:
            print(str(exc))
            log.error(str(exc))
            return 1
    native = not args.browser and not args.no_browser
    if native and already_running(allow_existing=False):
        return 0

    fb.TLS["cafile"], fb.TLS["insecure"] = args.ca, args.insecure
    if args.insecure:
        print("WARNING: --insecure disables certificate checks; your proxy pass can be stolen.\n")

    app = App(args)
    try:
        httpd, url = start_server(app, args.port)
    except OSError as e:
        print(f"Could not open the panel port {args.port}: {e}  (try --port)")
        log.error("panel port: %s", e)
        return 1
    print(f"RXFOXYVPN panel: {url}   (Ctrl+C to quit)")
    try:
        done = False
        if native:
            try:
                import PIL  # noqa: F401
                import webview  # noqa: F401
                have = True
            except ImportError:
                have = False
                print("برای پنجره‌ی اپ و آیکون کنار ساعت نصب کن:  pip install pywebview pystray pillow\n"
                      "فعلاً در پنجره‌ی مرورگر باز می‌شود.")
                log.warning("pywebview/pillow missing, falling back to a browser window")
            if have:
                run_desktop(app, url, native_frame=args.native_frame)
                done = True
        if not done:
            if not args.no_browser:
                threading.Timer(0.6, open_app_window, args=(url,)).start()
            while True:
                time.sleep(3600)
    except KeyboardInterrupt:
        print("\nBye.")
    finally:
        try:
            if app.connected:
                app.disconnect()
        except Exception:  # noqa: BLE001
            pass
        httpd.shutdown()
        httpd.server_close()
    return 0
