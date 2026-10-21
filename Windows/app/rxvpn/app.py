"""App: the connection logic (login, server list, ping, connect / disconnect, settings API)."""
from __future__ import annotations

import asyncio
import logging
import os
import random
import re
import socket
import sys
import threading
import time
import webbrowser
from concurrent.futures import ThreadPoolExecutor

import requests

from . import foxy_bridge as fb
from .settings import (
    LOOPBACK, SETTINGS_DEFAULTS, SETTINGS_NON_ENGINE, SING_BOX_PATH, UI, WINTUN_PATH, ApiError,
    SettingError, apply_autostart, list_saved_accounts, load_connection_settings,
    load_saved_account_password, load_settings, remove_saved_account, sanitize_settings,
    save_connection_settings, save_saved_account, save_settings, save_ui, tr, tun_log_path,
)
from .sysproxy import ConnectionModeError, apply_system_proxy, is_admin, restore_system_proxy
from .tun import TunProcess

log = logging.getLogger("rxvpn")

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


def default_ipv4_interface_index() -> int | None:
    if os.name != "nt":
        return None
    try:
        import ctypes

        get_best_interface = ctypes.windll.iphlpapi.GetBestInterface
        get_best_interface.argtypes = [ctypes.c_uint32, ctypes.POINTER(ctypes.c_uint32)]
        get_best_interface.restype = ctypes.c_uint32
        destination = ctypes.c_uint32(int.from_bytes(socket.inet_aton("1.1.1.1"), sys.byteorder))
        interface_index = ctypes.c_uint32()
        if get_best_interface(destination, ctypes.byref(interface_index)) == 0:
            return interface_index.value
    except (AttributeError, OSError):
        pass
    return None


def tcp_ms(host: str, port: int, timeout: float = 2.5, interface_index: int | None = None):
    """One TCP connect time in ms (DNS is resolved first and NOT counted)."""
    try:
        addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    except OSError:
        return None
    for family, socktype, proto, _canonname, address in addresses:
        try:
            with socket.socket(family, socktype, proto) as s:
                s.settimeout(timeout)
                if interface_index is not None:
                    if family == socket.AF_INET:
                        s.setsockopt(socket.IPPROTO_IP, 31, socket.htonl(interface_index))
                    elif family == socket.AF_INET6:
                        s.setsockopt(socket.IPPROTO_IPV6, 31, socket.htonl(interface_index))
                t0 = time.perf_counter()
                s.connect(address)
                return (time.perf_counter() - t0) * 1000
        except OSError:
            continue
    return None


def ping_country(c: "fb.Country", tries: int = 2, max_targets: int = 3,
                 interface_index: int | None = None) -> dict:
    targets = random.sample(c.targets, min(max_targets, len(c.targets)))
    best, ok = None, 0
    for host, port in targets:
        vals = [v for v in (tcp_ms(host, port, interface_index=interface_index) for _ in range(tries))
                if v is not None]
        if vals:
            ok += 1
            best = min(vals) if best is None else min(best, min(vals))
    return {"ms": None if best is None else max(1, round(best)), "ok": ok, "total": len(targets)}


# --------------------------------------------------------------------------- app state

class App:
    def __init__(self, args):
        self.args = args
        self.cp = fb.ControlPlane(args.api_proxy)
        self.account = fb.Account(self.cp)
        self.connection_lock = threading.Lock()
        self.connection_settings = load_connection_settings()
        self.settings = load_settings()
        self.settings_lock = threading.Lock()
        self.system_proxy_backup_path = os.path.join(os.path.dirname(fb.state_path()), "system_proxy_backup.json")
        self.tun_process = TunProcess()
        self.ping_interface_index = None
        self.listener_protocol = None
        self.countries: dict = {}
        self.pending_session = None          # sessionToken waiting for the e-mail code
        self.pending_email = None
        self.upstream = None
        saved_limit = self.account.st.get("quota_limit")
        saved_quota = self.account.st.get("quota_remaining")
        valid_quota = (type(saved_limit) is int and type(saved_quota) is int and
                   saved_limit > 0 and 0 <= saved_quota <= saved_limit)
        self.quota_limit = saved_limit if valid_quota else None
        self.quota_remaining = saved_quota if valid_quota else None
        self.quota_refresh_lock = threading.Lock()
        self.quota_refresher_wakeup = threading.Event()
        self.listeners: list = []
        self.connected = False
        self.started = 0.0
        self.op_lock = threading.Lock()      # one connect/disconnect at a time
        self.list_lock = threading.Lock()
        self.flag_dir = os.path.join(os.path.dirname(fb.state_path()), "flags")
        os.makedirs(self.flag_dir, exist_ok=True)
        try:
            restore_system_proxy(self.system_proxy_backup_path)
        except Exception as e:  # noqa: BLE001
            log.error("could not restore a previous Windows proxy setting: %s", e)
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, daemon=True, name="rxvpn-loop").start()
        threading.Thread(target=self._refresher, daemon=True, name="rxvpn-refresh").start()
        threading.Thread(target=self._quota_refresher, daemon=True, name="rxvpn-quota").start()

    # ---- helpers
    def run(self, coro, timeout: float = 120):
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    def ensure_countries(self, force: bool = False) -> dict:
        with self.list_lock:
            if force or not self.countries:
                self.countries = fb.fetch_countries(self.cp)
            return self.countries

    def _refresher(self):
        while True:
            time.sleep(fb.SERVERLIST_REFRESH_SECONDS)
            try:
                fresh = fb.fetch_countries(self.cp)
                with self.list_lock:
                    self.countries = fresh
                up = self.upstream
                if up and up.country and up.country.code in fresh:
                    self.loop.call_soon_threadsafe(up.update_targets, fresh[up.country.code])
            except Exception as e:  # noqa: BLE001
                log.warning("server list refresh failed: %s", e)

    def _quota_refresher(self):
        while True:
            self._refresh_quota()
            self.quota_refresher_wakeup.wait(60 if self.connected else 120)
            self.quota_refresher_wakeup.clear()

    def _refresh_quota(self):
        if not self.quota_refresh_lock.acquire(blocking=False):
            return
        try:
            if self.account.has_session():
                self._remember_quota(self.account.fetch_quota())
        except Exception as e:  # noqa: BLE001
            log.info("quota refresh failed: %s", e)
        finally:
            self.quota_refresh_lock.release()

    def _schedule_quota_refresh(self):
        threading.Thread(target=self._refresh_quota, daemon=True, name="rxvpn-quota-fetch").start()

    def _clear_quota(self):
        self.quota_limit = self.quota_remaining = None
        self.account.st.pop("quota_limit", None)
        self.account.st.pop("quota_remaining", None)
        self.account.save()

    # ---- account
    def login(self, body: dict) -> dict:
        email = str(body.get("email", "")).strip()
        password = str(body.get("password", ""))
        remember = body.get("remember") is True
        if not email or not password:
            raise ApiError(tr("ایمیل و رمز عبور را وارد کنید.", "Enter your email and password."))
        data = self.account.fxa.login(email, password)
        account_saved = False
        if remember:
            try:
                save_saved_account(email, password)
                account_saved = True
            except Exception as e:  # noqa: BLE001
                log.warning("could not save account credentials: %s", e)
        token = data["sessionToken"]
        if not data.get("verified", False):
            self.pending_session = token
            self.pending_email = email
            return {"need_code": True, "account_saved": account_saved}
        self.account._store_tokens(self.account.fxa.oauth_from_session(token))
        with self.quota_refresh_lock:
            self._clear_quota()
        self._schedule_quota_refresh()
        self.account.st["email"] = email
        self.account.save()
        return {"ok": True, "account_saved": account_saved}

    def saved_accounts(self, _body=None) -> dict:
        return {"accounts": list_saved_accounts()}

    def login_saved(self, body: dict) -> dict:
        email = str(body.get("email", "")).strip()
        try:
            password = load_saved_account_password(email)
        except (KeyError, ValueError, OSError) as e:
            raise ApiError(tr("اطلاعات ذخیره‌شده‌ی این حساب در دسترس نیست.",
                              "Saved credentials for this account are unavailable."), 404) from e
        return self.login({"email": email, "password": password})

    def delete_saved_account(self, body: dict) -> dict:
        email = str(body.get("email", "")).strip()
        if not email:
            raise ApiError(tr("حسابی انتخاب نشده است.", "No account is selected."))
        return {"accounts": remove_saved_account(email)}

    def verify(self, body: dict) -> dict:
        code = re.sub(r"\s+", "", str(body.get("code", "")))
        if not self.pending_session:
            raise ApiError(tr("ابتدا با ایمیل و رمز عبور وارد شوید.", "Sign in with your email and password first."), 409)
        if not code:
            raise ApiError(tr("کد تأیید را وارد کنید.", "Enter the verification code."))
        self.account.fxa.verify_code(self.pending_session, code)
        self.account._store_tokens(self.account.fxa.oauth_from_session(self.pending_session))
        if self.pending_email:
            self.account.st["email"] = self.pending_email
            self.account.save()
        self.pending_session = None
        self.pending_email = None
        with self.quota_refresh_lock:
            self._clear_quota()
        self._schedule_quota_refresh()
        return {"ok": True}

    def logout(self, _body=None) -> dict:
        if self.connected:
            self.disconnect()
        with self.quota_refresh_lock:
            self.pending_session = None
            self.pending_email = None
            self.account.clear()
            self.quota_limit = None
            self.quota_remaining = None
        return {"ok": True}

    # ---- connection
    async def _connect(self, code: str):
        mode = self.connection_settings["mode"]
        if mode == "system" and os.name != "nt":
            raise ConnectionModeError("Windows system proxy is only available on Windows")
        if mode == "tun":
            if os.name != "nt":
                raise ConnectionModeError("TUN mode is only available on Windows")
            if not is_admin():
                raise ConnectionModeError("Run RXFOXYVPN as Administrator to start TUN mode")
            if not os.path.isfile(SING_BOX_PATH) or not os.path.isfile(WINTUN_PATH):
                raise ConnectionModeError("TUN runtime is missing; rebuild RXFOXYVPN to install sing-box and Wintun")
        country = self.countries.get(code)
        if not country:
            raise ApiError(tr("این کشور در فهرست سرورها نیست.", "This country is not in the server list."), 404)
        if self.upstream is None:
            self.upstream = fb.Upstream(fb.TokenSource(self.account))
        up = self.upstream
        prev = up.country if self.connected else None
        await up.set_country(country)
        try:
            await up.preflight()
        except Exception:
            if prev:                              # stay on the old server, like `use` in the CLI
                await up.set_country(prev)
            else:
                await up.close()
                self.upstream = None
            raise
        self.account.st["last_country"] = code
        self.account.save()
        if not self.connected:
            fb.reset_traffic_stats()
        if not self.listeners:
            try:
                protocol, port = self._listener_spec()
                handler = fb.handle_socks if protocol == "socks5" else fb.handle_http
                listener = await asyncio.start_server(
                    lambda r, w: handler(r, w, self.upstream), LOOPBACK, port)
                self.listeners.append(listener)
                self.listener_protocol = protocol
                if self.connection_settings["mode"] == "system":
                    apply_system_proxy(port, self.system_proxy_backup_path)
                elif self.connection_settings["mode"] == "tun":
                    if self.ping_interface_index is None:
                        self.ping_interface_index = default_ipv4_interface_index()
                        if self.ping_interface_index is None:
                            log.warning("could not determine physical interface for direct ping probes")
                    data_dir = os.path.dirname(fb.state_path())
                    os.makedirs(os.path.join(data_dir, "tun"), exist_ok=True)
                    self.tun_process.start(
                        SING_BOX_PATH, WINTUN_PATH,
                        os.path.join(data_dir, "tun", "config.json"),
                        os.path.join(data_dir, "tun.log"), port,
                        dict(self.settings),
                    )
            except Exception as e:  # noqa: BLE001
                self._stop_connection_mode()
                if prev:
                    await up.set_country(prev)
                else:
                    await up.close()
                    self.upstream = None
                if isinstance(e, (OSError, PermissionError)):
                    raise ApiError(tr(f"پورت پراکسی باز نشد: {e}", f"Could not open the proxy port: {e}"), 409)
                raise
        if not self.connected:
            self.started = time.time()
        self.connected = True
        if not prev:
            self.quota_refresher_wakeup.set()

    async def _disconnect(self):
        self._stop_connection_mode()
        self.ping_interface_index = None
        if self.upstream:
            current_pass = self.upstream.tokens.cur
            self._remember_quota(current_pass)
            await self.upstream.close()
        self.upstream = None
        self.connected = False
        self.quota_refresher_wakeup.set()

    def _stop_connection_mode(self) -> None:
        self.tun_process.stop()
        restore_system_proxy(self.system_proxy_backup_path)
        for s in self.listeners:
            s.close()                              # not awaiting wait_closed(): it blocks on open connections
        self.listeners.clear()
        self.listener_protocol = None

    def _listener_spec(self) -> tuple[str, int]:
        settings = self.connection_settings
        if settings["mode"] == "manual":
            return settings["protocol"], settings["port"]
        if settings["mode"] == "system":
            if os.name != "nt":
                raise ConnectionModeError("Windows system proxy is only available on Windows")
            return "http", self.args.http_port
        return "socks5", self.args.socks_port

    def connect(self, body: dict) -> dict:
        code = str(body.get("code", "")).upper()
        if not self.op_lock.acquire(blocking=False):
            raise ApiError(tr("عملیات قبلی هنوز تمام نشده.", "The previous operation is still running."), 409)
        try:
            self.ensure_countries()
            self.run(self._connect(code))
        finally:
            self.op_lock.release()
        return self.status()

    def disconnect(self, _body=None) -> dict:
        with self.op_lock:
            self.run(self._disconnect())
        return self.status()

    # ---- read-only endpoints
    def status(self, _body=None) -> dict:
        up = self.upstream
        cur = up.current if up and self.connected else None
        country = up.country if up and self.connected else None
        current_pass = up.tokens.cur if up else None
        self._remember_quota(current_pass)
        settings = self.connection_settings
        socks = http = None
        if self.connected and settings["mode"] == "manual":
            address = f"{LOOPBACK}:{settings['port']}"
            socks = address if settings["protocol"] == "socks5" else None
            http = address if settings["protocol"] == "http" else None
        elif self.connected and settings["mode"] == "system":
            http = f"{LOOPBACK}:{self.args.http_port}"
        return {
            "logged_in": self.account.has_session(),
            "pending_code": self.pending_session is not None,
            "state": "connected" if self.connected else "idle",
            "country": {"code": country.code, "name": fb.display_name(country)} if country else None,
            "host": f"{cur[0]}:{cur[1]}" if cur else None,
            "socks": socks,
            "http": http,
            "active": fb.ACTIVE["n"],
            "total": fb.ACTIVE["total"],
            "uploaded_bytes": fb.ACTIVE["uploaded"],
            "downloaded_bytes": fb.ACTIVE["downloaded"],
            "uptime": int(time.time() - self.started) if self.connected else 0,
            "last_country": self.account.st.get("last_country"),
            "quota_limit": self.quota_limit,
            "quota_remaining": self.quota_remaining,
            "connection": dict(self.connection_settings),
            "system_proxy_available": os.name == "nt",
            "tun_available": os.name == "nt" and os.path.exists(SING_BOX_PATH)
                             and os.path.exists(WINTUN_PATH),
            "tun_admin": is_admin(),
            "lang": UI["lang"],
        }

    def _remember_quota(self, proxy_pass) -> None:
        if not proxy_pass:
            return
        limit, remaining = proxy_pass.quota_limit, proxy_pass.quota_remaining
        if (type(limit) is not int or type(remaining) is not int or limit <= 0 or
                not 0 <= remaining <= limit):
            return
        if (limit, remaining) == (self.quota_limit, self.quota_remaining):
            return
        self.quota_limit, self.quota_remaining = limit, remaining
        self.account.st["quota_limit"] = limit
        self.account.st["quota_remaining"] = remaining
        self.account.save()

    def set_lang(self, body: dict) -> dict:
        lang = str(body.get("lang", ""))
        if lang not in ("fa", "en", "ar", "ru", "zh", "ko"):
            raise ApiError("bad language")
        UI["lang"] = lang
        save_ui()
        return {"ok": True, "lang": lang}

    def set_connection(self, body: dict) -> dict:
        mode = body.get("mode", self.connection_settings["mode"])
        protocol = body.get("protocol", self.connection_settings["protocol"])
        port = body.get("port", self.connection_settings["port"])
        if mode not in ("tun", "system", "manual"):
            raise ApiError(tr("حالت اتصال نامعتبر است.", "Invalid connection mode."))
        if protocol not in ("socks5", "http"):
            raise ApiError(tr("پروتکل پراکسی نامعتبر است.", "Invalid proxy protocol."))
        if type(port) is not int or not 1 <= port <= 65535:
            raise ApiError(tr("پورت باید عددی بین 1 تا 65535 باشد.", "Port must be between 1 and 65535."))
        if self.connected:
            raise ApiError(tr("برای تغییر حالت، ابتدا اتصال را قطع کن.", "Disconnect before changing the connection mode."), 409)
        if mode == "system" and os.name != "nt":
            raise ApiError(tr("پروکسی سیستم در این سیستم‌عامل پشتیبانی نمی‌شود.", "System proxy mode is not supported on this operating system."), 501)
        if mode == "tun" and os.name != "nt":
            raise ApiError(tr("حالت TUN در این سیستم‌عامل پشتیبانی نمی‌شود.", "TUN mode is not supported on this operating system."), 501)
        updated = {"mode": mode, "protocol": protocol, "port": port}
        with self.connection_lock:
            save_connection_settings(updated)
            self.connection_settings = updated
        return {"connection": dict(updated)}

    # ---- user settings (General / Routing / DNS / Inbound pages)
    def get_settings(self, _body=None) -> dict:
        return {"settings": dict(self.settings), "defaults": dict(SETTINGS_DEFAULTS),
                "autostart_available": os.name == "nt"}

    def set_settings(self, body: dict) -> dict:
        changes = body.get("settings") if isinstance(body.get("settings"), dict) else body
        with self.settings_lock:
            try:
                updated = sanitize_settings(changes, self.settings)
            except SettingError as e:
                raise ApiError(tr(e.fa, e.en)) from None
            if updated["autostart"] != self.settings["autostart"]:
                apply_autostart(updated["autostart"])
            engine_changed = any(updated[k] != self.settings[k] for k in updated
                                 if k not in SETTINGS_NON_ENGINE)
            save_settings(updated)
            self.settings = updated
        restart = bool(engine_changed and self.connected and self.connection_settings["mode"] == "tun")
        return {"settings": dict(updated), "restart_needed": restart}

    def reset_settings(self, _body=None) -> dict:
        with self.settings_lock:
            defaults = dict(SETTINGS_DEFAULTS)
            if self.settings["autostart"]:
                apply_autostart(False)
            save_settings(defaults)
            self.settings = defaults
        restart = bool(self.connected and self.connection_settings["mode"] == "tun")
        return {"settings": dict(defaults), "restart_needed": restart}

    def tun_log(self, _body=None) -> dict:
        path = tun_log_path()
        try:
            with open(path, "rb") as f:
                f.seek(0, os.SEEK_END)
                f.seek(max(0, f.tell() - 65536))
                raw = f.read()
        except OSError:
            return {"text": "", "path": path, "exists": False}
        text = _ANSI_RE.sub("", raw.decode("utf-8", "replace"))
        return {"text": "\n".join(text.splitlines()[-300:]), "path": path, "exists": True}

    def servers(self, _body=None) -> dict:
        countries = self.ensure_countries()
        return {"servers": [{"code": c.code, "name": fb.display_name(c), "count": len(c.targets)}
                            for c in fb.sorted_countries(countries)]}

    def refresh_servers(self, _body=None) -> dict:
        self.ensure_countries(force=True)
        return self.servers()

    def ping(self, body: dict) -> dict:
        c = self.ensure_countries().get(str(body.get("code", "")).upper())
        if not c:
            raise ApiError(tr("این کشور در فهرست سرورها نیست.", "This country is not in the server list."), 404)
        interface_index = self.ping_interface_index if self.connected and self.connection_settings["mode"] == "tun" else None
        if self.connected and self.connection_settings["mode"] == "tun" and interface_index is None:
            return {"ms": None, "ok": 0, "total": 0}
        return ping_country(c, interface_index=interface_index)

    def ping_all(self, _body=None) -> dict:
        items = list(self.ensure_countries().values())
        interface_index = self.ping_interface_index if self.connected and self.connection_settings["mode"] == "tun" else None
        if self.connected and self.connection_settings["mode"] == "tun" and interface_index is None:
            return {c.code: None for c in items}
        with ThreadPoolExecutor(max_workers=16) as pool:
            results = list(pool.map(
                lambda c: ping_country(c, tries=1, max_targets=2, interface_index=interface_index), items))
        return {c.code: r["ms"] for c, r in zip(items, results)}

    def open_url(self, body: dict) -> dict:
        url = str(body.get("url", ""))
        if not url.startswith("https://accounts.firefox.com/"):
            raise ApiError("forbidden", 403)
        webbrowser.open(url)
        return {"ok": True}

    # ---- flags (downloaded once, cached on disk, so the panel works offline afterwards)
    def flag(self, cc: str):
        if not re.fullmatch(r"[a-z]{2}", cc):
            return None
        path = os.path.join(self.flag_dir, cc + ".png")
        if not os.path.exists(path):
            try:
                r = requests.get(f"https://flagcdn.com/w80/{cc}.png", timeout=8,
                                 proxies=self.cp.proxies, headers={"User-Agent": fb.USER_AGENT})
            except requests.RequestException:
                return None
            if r.status_code != 200 or not r.headers.get("Content-Type", "").startswith("image/"):
                return None
            with open(path, "wb") as f:
                f.write(r.content)
        with open(path, "rb") as f:
            return f.read()
