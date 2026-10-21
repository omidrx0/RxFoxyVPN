"""Windows system proxy (WinINet registry) + the small Windows helpers shared by the other modules."""
from __future__ import annotations

import ctypes
import json
import os

REGISTRY_PATH = r"Software\Microsoft\Windows\CurrentVersion\Internet Settings"
PROXY_VALUES = ("ProxyEnable", "ProxyServer", "ProxyOverride")
LAN_BYPASS = (
    "<local>", "localhost", "127.*", "10.*", "172.16.*", "172.17.*", "172.18.*",
    "172.19.*", "172.20.*", "172.21.*", "172.22.*", "172.23.*", "172.24.*",
    "172.25.*", "172.26.*", "172.27.*", "172.28.*", "172.29.*", "172.30.*",
    "172.31.*", "192.168.*",
)


class ConnectionModeError(RuntimeError):
    pass



def is_admin() -> bool:
    if os.name != "nt":
        return False
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except (AttributeError, OSError):
        return False



def _notify_proxy_change() -> None:
    wininet = ctypes.windll.Wininet
    wininet.InternetSetOptionW(None, 39, None, 0)
    wininet.InternetSetOptionW(None, 37, None, 0)


def _proxy_key():
    import winreg

    return winreg.OpenKey(
        winreg.HKEY_CURRENT_USER,
        REGISTRY_PATH,
        0,
        winreg.KEY_QUERY_VALUE | winreg.KEY_SET_VALUE,
    )


def _capture_proxy_values() -> dict:
    import winreg

    snapshot = {}
    with _proxy_key() as key:
        for name in PROXY_VALUES:
            try:
                value, kind = winreg.QueryValueEx(key, name)
                snapshot[name] = {"value": value, "type": kind}
            except FileNotFoundError:
                snapshot[name] = None
    return snapshot


def _write_proxy_values(snapshot: dict) -> None:
    import winreg

    with _proxy_key() as key:
        for name in PROXY_VALUES:
            entry = snapshot.get(name)
            if entry is None:
                try:
                    winreg.DeleteValue(key, name)
                except FileNotFoundError:
                    pass
            else:
                winreg.SetValueEx(key, name, 0, entry["type"], entry["value"])
    _notify_proxy_change()


def apply_system_proxy(port: int, backup_path: str) -> None:
    if os.name != "nt":
        raise ConnectionModeError("Windows system proxy is only available on Windows")
    if os.path.exists(backup_path):
        raise ConnectionModeError("A previous system proxy backup exists; restore it before connecting")
    snapshot = _capture_proxy_values()
    tmp = backup_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(snapshot, f)
    os.replace(tmp, backup_path)
    try:
        import winreg

        _write_proxy_values({
            "ProxyEnable": {"value": 1, "type": winreg.REG_DWORD},
            "ProxyServer": {
                "value": f"http=127.0.0.1:{port};https=127.0.0.1:{port}",
                "type": winreg.REG_SZ,
            },
            "ProxyOverride": {"value": ";".join(LAN_BYPASS), "type": winreg.REG_SZ},
        })
    except Exception:
        restore_system_proxy(backup_path)
        raise


def restore_system_proxy(backup_path: str) -> None:
    if not os.path.exists(backup_path):
        return
    with open(backup_path, encoding="utf-8") as f:
        snapshot = json.load(f)
    _write_proxy_values(snapshot)
    os.remove(backup_path)
