"""Settings, validation and everything persisted on disk.

* resource paths (source tree and PyInstaller bundle)
* panel language (fa / en) and the tr() helper
* user settings (General / Routing / DNS / Inbound) with validation
* connection mode settings, Windows autostart
* saved Firefox accounts (passwords protected with Windows DPAPI)
"""
from __future__ import annotations

import base64
import ipaddress
import json
import logging
import os
import re
import sys
import threading

from . import foxy_bridge as fb

log = logging.getLogger("rxvpn")
LOOPBACK = "127.0.0.1"

# --------------------------------------------------------------------------- resource paths
PACKAGE_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(PACKAGE_DIR)


def bundle_dir() -> str:
    """Project root when running from source, the PyInstaller temp folder inside the .exe."""
    return getattr(sys, "_MEIPASS", ROOT_DIR)


WEB_DIR = os.path.join(bundle_dir(), "rxvpn", "web")
INDEX_PATH = os.path.join(WEB_DIR, "index.html")
RUNTIME_DIR = os.path.join(bundle_dir(), "runtime")
SING_BOX_PATH = os.path.join(RUNTIME_DIR, "sing-box.exe")
WINTUN_PATH = os.path.join(RUNTIME_DIR, "wintun.dll")
ICON_PATH = os.path.join(bundle_dir(), "packaging", "icon.ico")


class ApiError(Exception):
    """An error with an HTTP status that the panel shows to the user."""

    def __init__(self, message: str, status: int = 400, **extra):
        super().__init__(message)
        self.status, self.extra = status, extra


# --------------------------------------------------------------------------- user settings
# One flat dict, grouped in the panel as General / Routing / DNS / Inbound.
SETTINGS_DEFAULTS = {
    # general
    "auto_connect": False,
    "autostart": False,
    "log_level": "info",
    # routing (TUN)
    "bypass_lan": True,
    "ipv6": True,
    "udp_policy": "block",
    "direct_apps": [],
    "direct_domains": [],
    "direct_cidrs": [],
    # dns (TUN)
    "dns_type": "https",
    "dns_server": "1.1.1.1",
    "dns_path": "/dns-query",
    "dns_strategy": "prefer_ipv4",
    "direct_dns": "1.1.1.1",
    # inbound (TUN)
    "tun_stack": "auto",
    "tun_mtu": 0,
    "strict_route": True,
}
SETTINGS_CHOICES = {
    "log_level": ("trace", "debug", "info", "warn", "error"),
    "udp_policy": ("block", "direct"),
    "dns_type": ("https", "tls", "tcp"),
    "dns_strategy": ("prefer_ipv4", "ipv4_only", "prefer_ipv6", "ipv6_only"),
    "tun_stack": ("auto", "mixed", "system", "gvisor"),
}
# settings that only change what the panel does, not the sing-box config
SETTINGS_NON_ENGINE = ("auto_connect", "autostart")
_LIST_LIMIT = 100
_APP_RE = re.compile(r"^[A-Za-z0-9_.()+\- ]{1,80}$")
_LABEL_RE = re.compile(r"^[a-z0-9_]([a-z0-9_\-]{0,61}[a-z0-9_])?$")
_PATH_RE = re.compile(r"^/[A-Za-z0-9._~%/\-]{0,120}$")


class SettingError(ValueError):
    """Invalid setting value; carries a Persian and an English message."""

    def __init__(self, fa: str, en: str):
        super().__init__(en)
        self.fa, self.en = fa, en


def _split_items(value) -> list:
    if isinstance(value, str):
        value = re.split(r"[\r\n,;]+", value)
    if not isinstance(value, (list, tuple)):
        raise SettingError("مقدار باید فهرست باشد.", "The value must be a list.")
    items = []
    for raw in value:
        item = str(raw).strip()
        if item and item not in items:
            items.append(item)
    if len(items) > _LIST_LIMIT:
        raise SettingError(f"حداکثر {_LIST_LIMIT} مورد مجاز است.", f"At most {_LIST_LIMIT} entries are allowed.")
    return items


def _clean_app(item: str) -> str:
    if not _APP_RE.match(item) or item.strip(". ") == "":
        raise SettingError(f"نام برنامه نامعتبر است: {item}", f"Invalid program name: {item}")
    return item


def _clean_domain(item: str) -> str:
    domain = item.lower().lstrip("*").lstrip(".").rstrip(".")
    labels = domain.split(".")
    if (not domain or len(domain) > 253 or
            not all(_LABEL_RE.match(label) for label in labels)):
        raise SettingError(f"دامنه نامعتبر است: {item}", f"Invalid domain: {item}")
    return domain


def _clean_cidr(item: str) -> str:
    try:
        network = ipaddress.ip_network(item, strict=False)
    except ValueError:
        raise SettingError(f"IP یا CIDR نامعتبر است: {item}", f"Invalid IP or CIDR: {item}") from None
    if network.prefixlen == 0:
        raise SettingError("مسیر پیش‌فرض (/0) مجاز نیست.", "A default route (/0) is not allowed.")
    return str(network)


def _clean_ip(value, label_fa: str, label_en: str) -> str:
    try:
        ip = ipaddress.ip_address(str(value).strip())
    except ValueError:
        raise SettingError(f"{label_fa} باید یک آدرس IP معتبر باشد.",
                           f"{label_en} must be a valid IP address.") from None
    if ip.is_unspecified or ip.is_multicast:
        raise SettingError(f"{label_fa} نامعتبر است.", f"{label_en} is not valid.")
    return str(ip)


def sanitize_setting(key: str, value):
    if key not in SETTINGS_DEFAULTS:
        raise SettingError(f"تنظیم ناشناخته: {key}", f"Unknown setting: {key}")
    default = SETTINGS_DEFAULTS[key]
    if isinstance(default, bool):
        if type(value) is not bool:
            raise SettingError("مقدار باید روشن یا خاموش باشد.", "The value must be on or off.")
        return value
    if key in SETTINGS_CHOICES:
        if value not in SETTINGS_CHOICES[key]:
            raise SettingError("گزینه نامعتبر است.", "Invalid option.")
        return value
    if key == "direct_apps":
        return [_clean_app(x) for x in _split_items(value)]
    if key == "direct_domains":
        out = []
        for x in _split_items(value):
            d = _clean_domain(x)
            if d not in out:
                out.append(d)
        return out
    if key == "direct_cidrs":
        out = []
        for x in _split_items(value):
            c = _clean_cidr(x)
            if c not in out:
                out.append(c)
        return out
    if key == "dns_server":
        return _clean_ip(value, "آدرس DNS", "The DNS address")
    if key == "direct_dns":
        return _clean_ip(value, "آدرس DNS مستقیم", "The direct DNS address")
    if key == "dns_path":
        path = str(value).strip() or "/dns-query"
        if not path.startswith("/"):
            path = "/" + path
        if not _PATH_RE.match(path):
            raise SettingError("مسیر DoH نامعتبر است.", "Invalid DoH path.")
        return path
    if key == "tun_mtu":
        try:
            mtu = int(str(value).strip() or 0)
        except ValueError:
            raise SettingError("MTU باید عدد باشد.", "MTU must be a number.") from None
        if mtu != 0 and not 1280 <= mtu <= 9000:
            raise SettingError("MTU باید 0 (خودکار) یا بین 1280 تا 9000 باشد.",
                               "MTU must be 0 (automatic) or between 1280 and 9000.")
        return mtu
    raise SettingError("تنظیم پشتیبانی نمی‌شود.", "Unsupported setting.")  # pragma: no cover


def sanitize_settings(changes: dict, base: dict | None = None) -> dict:
    """Return `base` (or the defaults) updated with the validated `changes`."""
    result = dict(SETTINGS_DEFAULTS)
    result.update(base or {})
    for key, value in (changes or {}).items():
        result[key] = sanitize_setting(key, value)
    return result


def load_settings_dict(saved) -> dict:
    """Merge a saved JSON object into the defaults, silently dropping anything invalid."""
    result = dict(SETTINGS_DEFAULTS)
    if isinstance(saved, dict):
        for key, value in saved.items():
            try:
                result[key] = sanitize_setting(key, value)
            except SettingError:
                pass
    return result



# --------------------------------------------------------------------------- language (fa / en)
UI = {"lang": "en"}
SAVED_ACCOUNTS_LOCK = threading.RLock()


def tr(fa: str, en: str) -> str:
    """Pick the Persian or English text according to the language chosen in the panel."""
    return fa if UI["lang"] == "fa" else en


# --------------------------------------------------------------------------- files in the data folder
def ui_path() -> str:
    return os.path.join(os.path.dirname(fb.state_path()), "rxvpn_ui.json")


def connection_settings_path() -> str:
    return os.path.join(os.path.dirname(fb.state_path()), "connection.json")


def settings_path() -> str:
    return os.path.join(os.path.dirname(fb.state_path()), "rxvpn_settings.json")


def tun_log_path() -> str:
    return os.path.join(os.path.dirname(fb.state_path()), "tun.log")


def saved_accounts_path() -> str:
    return os.path.join(os.path.dirname(fb.state_path()), "saved_accounts.json")



# --------------------------------------------------------------------------- saved accounts
def _dpapi_crypt(data: bytes, protect: bool) -> bytes:
    if os.name != "nt":
        raise OSError("Saved account passwords require Windows DPAPI")

    import ctypes
    from ctypes import wintypes

    class DataBlob(ctypes.Structure):
        _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]

    source_buffer = ctypes.create_string_buffer(data, len(data))
    source = DataBlob(len(data), ctypes.cast(source_buffer, ctypes.POINTER(ctypes.c_ubyte)))
    output = DataBlob()
    crypt32 = ctypes.WinDLL("Crypt32.dll", use_last_error=True)
    kernel32 = ctypes.WinDLL("Kernel32.dll", use_last_error=True)
    local_free = kernel32.LocalFree
    local_free.argtypes = [ctypes.c_void_p]
    local_free.restype = ctypes.c_void_p

    if protect:
        call = crypt32.CryptProtectData
        call.argtypes = [ctypes.POINTER(DataBlob), wintypes.LPCWSTR, ctypes.POINTER(DataBlob),
                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(DataBlob)]
        ok = call(ctypes.byref(source), "RXVPN saved account", None, None, None, 0x1, ctypes.byref(output))
    else:
        description = wintypes.LPWSTR()
        call = crypt32.CryptUnprotectData
        call.argtypes = [ctypes.POINTER(DataBlob), ctypes.POINTER(wintypes.LPWSTR),
                         ctypes.POINTER(DataBlob), ctypes.c_void_p, ctypes.c_void_p,
                         wintypes.DWORD, ctypes.POINTER(DataBlob)]
        ok = call(ctypes.byref(source), ctypes.byref(description), None, None, None, 0x1, ctypes.byref(output))
        if description:
            local_free(description)

    if not ok:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(output.pbData, output.cbData)
    finally:
        local_free(output.pbData)


def _read_saved_accounts(path=None) -> dict:
    target = os.fspath(path or saved_accounts_path())
    try:
        with open(target, encoding="utf-8") as f:
            value = json.load(f)
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def save_saved_account(email: str, password: str, path=None) -> None:
    email = email.strip()
    if not email or not password:
        raise ValueError("An email and password are required")
    target = os.fspath(path or saved_accounts_path())
    key = email.casefold()
    encrypted = base64.b64encode(_dpapi_crypt(password.encode("utf-8"), True)).decode("ascii")
    with SAVED_ACCOUNTS_LOCK:
        accounts = _read_saved_accounts(target)
        accounts[key] = {"email": email, "password": encrypted}
        os.makedirs(os.path.dirname(target), exist_ok=True)
        tmp = target + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(accounts, f)
        os.replace(tmp, target)


def load_saved_account_password(email: str, path=None) -> str:
    with SAVED_ACCOUNTS_LOCK:
        entry = _read_saved_accounts(path).get(email.strip().casefold())
    if not isinstance(entry, dict) or not isinstance(entry.get("password"), str):
        raise KeyError(email)
    encrypted = base64.b64decode(entry["password"], validate=True)
    return _dpapi_crypt(encrypted, False).decode("utf-8")


def list_saved_accounts(path=None) -> list[str]:
    with SAVED_ACCOUNTS_LOCK:
        accounts = _read_saved_accounts(path)
    return sorted(
        (entry["email"] for entry in accounts.values()
         if isinstance(entry, dict) and isinstance(entry.get("email"), str)),
        key=str.casefold,
    )


def remove_saved_account(email: str, path=None) -> list[str]:
    target = os.fspath(path or saved_accounts_path())
    with SAVED_ACCOUNTS_LOCK:
        accounts = _read_saved_accounts(target)
        accounts.pop(email.strip().casefold(), None)
        tmp = target + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(accounts, f)
        os.replace(tmp, target)
    return list_saved_accounts(target)



# --------------------------------------------------------------------------- connection / user settings
def load_connection_settings() -> dict:
    settings = {"mode": "manual", "protocol": "socks5", "port": 10808}
    try:
        with open(connection_settings_path(), encoding="utf-8") as f:
            saved = json.load(f)
        if saved.get("mode") in ("tun", "system", "manual"):
            settings["mode"] = saved["mode"]
        if saved.get("protocol") in ("socks5", "http"):
            settings["protocol"] = saved["protocol"]
        port = saved.get("port")
        if type(port) is int and 1 <= port <= 65535:
            settings["port"] = port
    except (OSError, ValueError, AttributeError):
        pass
    return settings


def save_connection_settings(settings: dict) -> None:
    path = connection_settings_path()
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(settings, f)
    os.replace(tmp, path)


def load_settings() -> dict:
    try:
        with open(settings_path(), encoding="utf-8") as f:
            return load_settings_dict(json.load(f))
    except (OSError, ValueError):
        return load_settings_dict(None)


def save_settings(settings: dict) -> None:
    path = settings_path()
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(settings, f, ensure_ascii=False, indent=2)
    os.replace(tmp, path)


# --------------------------------------------------------------------------- language file
def load_ui() -> None:
    try:
        with open(ui_path(), encoding="utf-8") as f:
            lang = json.load(f).get("lang")
        if lang in ("fa", "en", "ar", "ru", "zh", "ko"):
            UI["lang"] = lang
    except (OSError, ValueError, AttributeError):
        pass


def save_ui() -> None:
    try:
        with open(ui_path(), "w", encoding="utf-8") as f:
            json.dump({"lang": UI["lang"]}, f)
    except OSError:
        log.warning("could not save the language setting")


# --------------------------------------------------------------------------- start with Windows
AUTOSTART_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
AUTOSTART_NAME = "RXVPN"


def _autostart_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}"'
    exe = sys.executable
    if os.path.basename(exe).lower() == "python.exe":
        windowless = os.path.join(os.path.dirname(exe), "pythonw.exe")
        if os.path.exists(windowless):
            exe = windowless
    boot = (f"import sys, runpy; sys.path.insert(0, r'{ROOT_DIR}'); "
            "runpy.run_module('rxvpn', run_name='__main__', alter_sys=True)")
    return f'"{exe}" -c "{boot}"'


def apply_autostart(enabled: bool) -> None:
    """Add/remove the per-user Windows Run entry that launches the panel at sign-in."""
    if os.name != "nt":
        raise ApiError(tr("اجرای خودکار فقط در ویندوز پشتیبانی می‌شود.",
                          "Start with Windows is only supported on Windows."), 501)
    import winreg
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, AUTOSTART_KEY) as key:
        if enabled:
            winreg.SetValueEx(key, AUTOSTART_NAME, 0, winreg.REG_SZ, _autostart_command())
        else:
            try:
                winreg.DeleteValue(key, AUTOSTART_NAME)
            except FileNotFoundError:
                pass
