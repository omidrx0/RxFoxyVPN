"""sing-box TUN mode: builds the config file and runs / stops the sing-box process."""
from __future__ import annotations

import ctypes
import json
import os
import re
import signal
import subprocess
import time
from typing import Optional

from .settings import SETTINGS_DEFAULTS
from .sysproxy import ConnectionModeError, is_admin

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]|\[[0-9;]*m")

TUN_EXCLUDED_CIDRS = [
    "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
    "::1/128", "fc00::/7", "fe80::/10",
]
TUN_LOOPBACK_CIDRS = ["127.0.0.0/8", "::1/128"]


class _IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_ulonglong) for name in (
        "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
        "ReadTransferCount", "WriteTransferCount", "OtherTransferCount",
    )]


class _JobBasicLimitInformation(ctypes.Structure):
    _fields_ = [
        ("PerProcessUserTimeLimit", ctypes.c_longlong),
        ("PerJobUserTimeLimit", ctypes.c_longlong),
        ("LimitFlags", ctypes.c_ulong),
        ("MinimumWorkingSetSize", ctypes.c_size_t),
        ("MaximumWorkingSetSize", ctypes.c_size_t),
        ("ActiveProcessLimit", ctypes.c_ulong),
        ("Affinity", ctypes.c_size_t),
        ("PriorityClass", ctypes.c_ulong),
        ("SchedulingClass", ctypes.c_ulong),
    ]


class _JobExtendedLimitInformation(ctypes.Structure):
    _fields_ = [
        ("BasicLimitInformation", _JobBasicLimitInformation),
        ("IoInfo", _IoCounters),
        ("ProcessMemoryLimit", ctypes.c_size_t),
        ("JobMemoryLimit", ctypes.c_size_t),
        ("PeakProcessMemoryUsed", ctypes.c_size_t),
        ("PeakJobMemoryUsed", ctypes.c_size_t),
    ]


def _create_kill_on_close_job():
    if os.name != "nt":
        raise OSError("Windows Job Objects are only available on Windows")
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_job = kernel32.CreateJobObjectW
    create_job.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
    create_job.restype = ctypes.c_void_p
    job = create_job(None, None)
    if not job:
        raise ctypes.WinError(ctypes.get_last_error())

    limits = _JobExtendedLimitInformation()
    limits.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    set_info = kernel32.SetInformationJobObject
    set_info.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
    set_info.restype = wintypes.BOOL
    if not set_info(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
        error = ctypes.WinError(ctypes.get_last_error())
        _close_job_handle(job)
        raise error
    return job


def _assign_process_to_job(job, process) -> None:
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    assign = kernel32.AssignProcessToJobObject
    assign.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    assign.restype = wintypes.BOOL
    if not assign(job, int(process._handle)):
        raise ctypes.WinError(ctypes.get_last_error())


def _close_job_handle(job) -> None:
    if not job:
        return
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = [ctypes.c_void_p]
    close_handle.restype = wintypes.BOOL
    if not close_handle(job):
        raise ctypes.WinError(ctypes.get_last_error())


def make_tun_config(socks_port: int, settings: dict | None = None) -> dict:
    s = dict(SETTINGS_DEFAULTS)
    s.update(settings or {})
    ipv6 = bool(s["ipv6"])

    def keep(cidrs):
        return [c for c in cidrs if ipv6 or ":" not in c]

    excluded = keep(TUN_LOOPBACK_CIDRS)
    if s["bypass_lan"]:
        excluded += keep(c for c in TUN_EXCLUDED_CIDRS if c not in TUN_LOOPBACK_CIDRS)
    excluded += [c for c in keep(s["direct_cidrs"]) if c not in excluded]

    tun = {
        "type": "tun",
        "tag": "rxvpn-tun",
        "interface_name": "RXVPN",
        "address": ["172.19.0.1/30"] + (["fdfe:dcba:9876::1/126"] if ipv6 else []),
        "auto_route": True,
        "strict_route": bool(s["strict_route"]),
        "dns_mode": "hijack",
        "dns_address": ["172.19.0.2"] + (["fdfe:dcba:9876::2"] if ipv6 else []),
        "route_exclude_address": excluded,
    }
    if s["tun_stack"] != "auto":
        tun["stack"] = s["tun_stack"]
    if s["tun_mtu"]:
        tun["mtu"] = int(s["tun_mtu"])

    remote_dns = {
        "type": s["dns_type"],
        "tag": "dns-over-vpn",
        "server": s["dns_server"],
        "detour": "firefox-socks",
    }
    if s["dns_type"] == "https":
        remote_dns["path"] = s["dns_path"]
    dns = {
        "servers": [remote_dns],
        "final": "dns-over-vpn",
        "strategy": s["dns_strategy"] if ipv6 else "ipv4_only",
    }

    apps, domains = list(s["direct_apps"]), list(s["direct_domains"])
    udp_direct = s["udp_policy"] == "direct"
    rules = [{"network": "udp", "port": 53, "action": "hijack-dns"}]
    if domains:
        # TUN only sees IP addresses; sniffing recovers the domain name so domain rules can match
        rules.append({"action": "sniff"})
        dns["rules"] = [{"domain_suffix": domains, "action": "route", "server": "dns-direct"}]
    if apps:
        rules.append({"process_name": apps, "action": "route", "outbound": "direct"})
    if domains:
        rules.append({"domain_suffix": domains, "action": "route", "outbound": "direct"})
    if udp_direct:
        rules.append({"network": "udp", "action": "route", "outbound": "direct"})
    else:
        rules.append({"network": "udp", "action": "reject", "method": "default"})

    outbounds = [
        {
            "type": "socks",
            "tag": "firefox-socks",
            "server": "127.0.0.1",
            "server_port": socks_port,
            "version": "5",
            "network": "tcp",
        },
        {"type": "block", "tag": "block"},
    ]
    needs_direct = bool(apps or domains or udp_direct)
    if needs_direct:
        outbounds.append({"type": "direct", "tag": "direct"})
        # the direct outbound resolves domain names itself, so sing-box >= 1.12 requires a default
        # domain resolver that does NOT go through the VPN (otherwise: "missing default_domain_resolver")
        dns["servers"].append({"type": "udp", "tag": "dns-direct", "server": s["direct_dns"]})

    route = {
        "auto_detect_interface": True,
        "rules": rules,
        "final": "firefox-socks",
    }
    if needs_direct:
        route["default_domain_resolver"] = "dns-direct"

    return {
        "log": {"level": s["log_level"], "timestamp": True},
        "dns": dns,
        "inbounds": [tun],
        "outbounds": outbounds,
        "route": route,
    }


class TunProcess:
    def __init__(self):
        self.process: Optional[subprocess.Popen] = None
        self.job_handle = None
        self.log_file = None

    def start(self, executable: str, wintun_dll: str, config_path: str, log_path: str, socks_port: int,
              settings: dict | None = None) -> None:
        if os.name != "nt":
            raise ConnectionModeError("TUN mode is only available on Windows")
        if not is_admin():
            raise ConnectionModeError("Run RXFOXYVPN as Administrator to start TUN mode")
        if not os.path.isfile(executable) or not os.path.isfile(wintun_dll):
            raise ConnectionModeError("TUN runtime is missing; rebuild RXFOXYVPN to install sing-box and Wintun")

        config_tmp = config_path + ".tmp"
        with open(config_tmp, "w", encoding="utf-8") as f:
            json.dump(make_tun_config(socks_port, settings), f, indent=2)
        os.replace(config_tmp, config_path)

        check = subprocess.run(
            [executable, "check", "-c", config_path],
            capture_output=True,
            text=True,
            timeout=15,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        if check.returncode:
            raise ConnectionModeError(_ANSI_RE.sub("", check.stderr or check.stdout or "Invalid TUN configuration").strip())

        self.log_file = open(log_path, "a", encoding="utf-8")
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0)
        try:
            self.job_handle = _create_kill_on_close_job()
            self.process = subprocess.Popen(
                [executable, "run", "-c", config_path],
                cwd=os.path.dirname(executable),
                stdout=self.log_file,
                stderr=subprocess.STDOUT,
                creationflags=flags,
            )
            _assign_process_to_job(self.job_handle, self.process)
            time.sleep(1)
            if self.process.poll() is not None:
                raise ConnectionModeError(f"sing-box exited during startup (code {self.process.returncode})")
        except Exception:
            self.stop()
            raise

    def stop(self) -> None:
        proc = self.process
        if proc is not None:
            try:
                if proc.poll() is None:
                    try:
                        proc.send_signal(signal.CTRL_BREAK_EVENT)
                        proc.wait(timeout=5)
                    except (OSError, subprocess.TimeoutExpired, ValueError):
                        try:
                            proc.terminate()
                            proc.wait(timeout=5)
                        except (OSError, subprocess.TimeoutExpired, ValueError):
                            try:
                                proc.kill()
                                proc.wait(timeout=5)
                            except Exception:  # noqa: BLE001
                                pass
            except Exception:  # noqa: BLE001
                pass
        self.process = None
        job, self.job_handle = self.job_handle, None
        if job:
            try:
                _close_job_handle(job)
            except Exception:  # noqa: BLE001
                pass
        if self.log_file:
            self.log_file.close()