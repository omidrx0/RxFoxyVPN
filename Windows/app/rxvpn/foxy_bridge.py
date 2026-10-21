#!/usr/bin/env python3
"""
FoxyBridge - local SOCKS5 + HTTP proxy on top of the Firefox VPN (Mozilla / Fastly) proxy.

A Python port of the logic found in the FoxyVPN Android project:
  * sign in with a Firefox account (FxA "picl" login + OAuth, 2FA by e-mail code)
  * get a short-lived proxy pass from Mozilla Guardian (vpn.mozilla.org)
  * download the live server list from Mozilla Remote Settings (every start + every 6 h)
  * let you pick a country
  * open an HTTP/2 tunnel (TLS, ALPN h2, CONNECT + "proxy-authorization: Bearer <pass>")
  * expose it locally as   SOCKS5 -> 127.0.0.1:10808   and   HTTP(S) proxy -> 127.0.0.1:10809

Install:   pip install requests h2
Run:       python foxy_bridge.py
           python foxy_bridge.py --country DE
           python foxy_bridge.py --list

Unofficial. Uses your own Firefox account and its free monthly traffic allowance.
"""
from __future__ import annotations

import argparse
import asyncio
import base64
import getpass
import hashlib
import hmac
import json
import logging
import os
import random
import re
import secrets
import socket
import ssl
import sys
import threading
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple
from urllib.parse import quote, urlsplit

import requests

try:  # the tunnel needs h2; everything else works without it (used by the self-tests)
    import h2.config
    import h2.connection
    import h2.errors
    import h2.events
    import h2.exceptions
    import h2.settings

    HAVE_H2 = True
    _PING_ACK = getattr(h2.events, "PingAckReceived", None) or getattr(h2.events, "PingAcknowledged")
except ImportError:  # pragma: no cover
    HAVE_H2 = False
    _PING_ACK = ()

log = logging.getLogger("foxybridge")

# --------------------------------------------------------------------------- constants

FXA_AUTH_SERVER = "https://api.accounts.firefox.com/v1"
FIREFOX_CLIENT_ID = "5882386c6d801776"
OAUTH_SCOPE = "profile https://identity.mozilla.com/apps/vpn"
PROTOCOL_VERSION = "identity.mozilla.com/picl/v1/"
GUARDIAN = "https://vpn.mozilla.org"
SERVERLIST_URL = (
    "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
)
USER_AGENT = "MozillaVPN/2.35.0 (sys:linux; iap:true)"
SOLVER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0 Safari/537.36"
)
POW_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
EXCLUDED_COUNTRY_NAMES = {"catchall anycast"}
DEFAULT_TOKEN_TTL = 24 * 3600

CONNECT_TIMEOUT = 15.0
OPEN_STREAM_TIMEOUT = 20.0
STREAM_WINDOW = 4 * 1024 * 1024
CONN_WINDOW = 16 * 1024 * 1024
SERVERLIST_REFRESH_SECONDS = 6 * 3600


# --------------------------------------------------------------------------- crypto helpers

def _hmac(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def hkdf(ikm: bytes, info, length: int, salt: bytes = b"\x00" * 32) -> bytes:
    """RFC 5869 HKDF-SHA256 (same as the app's implementation)."""
    info_b = info if isinstance(info, bytes) else info.encode()
    prk = _hmac(salt, ikm)
    out, prev, counter = b"", b"", 1
    while len(out) < length:
        prev = _hmac(prk, prev + info_b + bytes([counter]))
        out += prev
        counter += 1
    return out[:length]


def derive_auth_pw(email: str, password: str) -> str:
    salt = (PROTOCOL_VERSION + "quickStretch:" + email).encode()
    quick = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 1000, 32)
    return hkdf(quick, PROTOCOL_VERSION + "authPW", 32).hex()


def derive_hawk_credentials(session_token_hex: str) -> Tuple[str, bytes]:
    expanded = hkdf(bytes.fromhex(session_token_hex), PROTOCOL_VERSION + "sessionToken", 64)
    return expanded[:32].hex(), expanded[32:]


def hawk_header(method: str, url: str, token_id: str, key: bytes, body: bytes) -> str:
    u = urlsplit(url)
    ts = str(int(time.time()))
    nonce = base64.urlsafe_b64encode(secrets.token_bytes(6)).decode().rstrip("=")
    path = u.path + (("?" + u.query) if u.query else "")
    port = u.port or (443 if u.scheme == "https" else 80)
    payload_hash = ""
    if body:
        h = hashlib.sha256()
        h.update(b"hawk.1.payload\napplication/json\n")
        h.update(body)
        h.update(b"\n")
        payload_hash = base64.b64encode(h.digest()).decode()
    normalized = (
        f"hawk.1.header\n{ts}\n{nonce}\n{method.upper()}\n{path}\n{u.hostname}\n{port}\n{payload_hash}\n\n"
    )
    mac = base64.b64encode(_hmac(key, normalized.encode())).decode()
    header = f'Hawk id="{token_id}", ts="{ts}", nonce="{nonce}", mac="{mac}"'
    if payload_hash:
        header += f', hash="{payload_hash}"'
    return header


def jwt_expiry(token: str) -> Optional[int]:
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        exp = int(json.loads(base64.urlsafe_b64decode(payload)).get("exp", 0))
        return exp or None
    except Exception:
        return None


# --------------------------------------------------------------------------- errors

class FxaApiError(Exception):
    def __init__(self, message, errno=None, status=None, body=None):
        super().__init__(message)
        self.errno, self.status, self.body = errno, status, body or {}


class NeedLogin(Exception):
    pass


class TokenInvalid(Exception):
    pass


class QuotaExceeded(Exception):
    pass


class FastlyChallengeError(Exception):
    pass


class UpstreamError(Exception):
    pass


class UpstreamHTTPError(UpstreamError):
    def __init__(self, status: int):
        super().__init__(f"upstream answered CONNECT with HTTP {status}")
        self.status = status


class ConnectionLost(UpstreamError):
    pass


# --------------------------------------------------------------------------- control plane (blocking, requests)

class ControlPlane:
    """HTTP client for FxA / Guardian / Remote Settings, incl. the Fastly challenge solver."""

    def __init__(self, api_proxy: Optional[str] = None, timeout: float = 30.0):
        self.timeout = timeout
        self.proxies = {"http": api_proxy, "https": api_proxy} if api_proxy else None
        self.session = requests.Session()
        self.fx_cookies: Dict[str, str] = {}
        self._lock = threading.Lock()

    def _cookie_header(self, url: str) -> Optional[str]:
        host = urlsplit(url).hostname or ""
        if self.fx_cookies and (host == "firefox.com" or host.endswith(".firefox.com")):
            return "; ".join(f"{k}={v}" for k, v in self.fx_cookies.items())
        return None

    def request(self, method: str, url: str, headers: Optional[dict] = None, data: Optional[bytes] = None):
        h = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        h.update(headers or {})
        cookie = self._cookie_header(url)
        if cookie:
            h["Cookie"] = cookie
        return self.session.request(
            method, url, headers=h, data=data, timeout=self.timeout, proxies=self.proxies
        )

    # ---- Fastly "Client Challenge" solver (port of FastlyChallengeSolver.kt)

    def solve_challenge(self) -> None:
        last: Optional[Exception] = None
        for base in ("https://api.accounts.firefox.com", "https://accounts.firefox.com"):
            try:
                self._solve_on_host(base)
                return
            except _NoChallengePage:
                continue
            except FastlyChallengeError as e:
                last = e
        raise last or FastlyChallengeError("host did not serve a Fastly challenge page")

    @staticmethod
    def _solve_pow(base: str, target_hex: str) -> Optional[str]:
        try:
            target = bytes.fromhex(target_hex)
        except ValueError:
            return None
        if len(target) != 32:
            return None
        for a in POW_ALPHABET:
            for b in POW_ALPHABET:
                if hashlib.sha256((base + a + b).encode()).digest() == target:
                    return a + b
        return None

    def _solve_on_host(self, base: str) -> None:
        s = requests.Session()
        kw = dict(timeout=60, proxies=self.proxies)
        page_url = base + "/"

        def get_text(url, accept="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"):
            r = s.get(url, headers={"User-Agent": SOLVER_UA, "Accept": accept}, **kw)
            if not r.ok:
                raise FastlyChallengeError(f"GET {url} returned HTTP {r.status_code}")
            return r.text

        def fetch_page():
            body = get_text(page_url)
            return body, ("/_fs-ch-" in body and "Client Challenge" in body)

        page, is_challenge = fetch_page()
        if not is_challenge:
            raise _NoChallengePage()
        m = re.search(r"/_fs-ch-[A-Za-z0-9]+", page)
        if not m:
            raise FastlyChallengeError(f"challenge asset prefix not found on {base}")
        prefix = base + m.group(0)
        origin = prefix[: prefix.index("/_fs-ch-")]

        script = get_text(f"{prefix}/script.js?reload=true")
        matches = list(re.finditer(r'init\((\[[^\]]*\]),\s*"([^"]+)",\s*"([^"]+)"', script))
        if not matches:
            raise FastlyChallengeError("challenge init() call not found in script")
        try:
            challenges = json.loads(matches[-1].group(1))
        except ValueError as e:
            raise FastlyChallengeError(f"could not parse challenge list: {e}")
        token = matches[-1].group(2)
        if not challenges:
            raise FastlyChallengeError("empty challenge list")

        def answer(ch: dict) -> dict:
            ty = ch.get("ty")
            data = ch.get("data") or {}
            if ty == "pow":
                b = data.get("base", "")
                ans = self._solve_pow(b, data.get("hash", ""))
                if ans is None:
                    raise FastlyChallengeError(f"no proof-of-work solution found for base {b}")
                return {"ty": "pow", "base": b, "answer": ans,
                        "hmac": data.get("hmac", ""), "expires": data.get("expires", "")}
            if ty == "pat":
                r = s.post(
                    f"{prefix}/pat?token={quote(token, safe='')}",
                    headers={"Accept": "text/plain", "Content-Type": "application/json",
                             "User-Agent": SOLVER_UA, "Origin": origin},
                    data=b"", **kw)
                if r.status_code in (400, 401):
                    return {"ty": "pat", "auth": ""}
                if not r.ok:
                    raise FastlyChallengeError(f"PAT request returned HTTP {r.status_code}")
                auth = r.json().get("auth", "")
                if not auth:
                    raise FastlyChallengeError("empty PAT auth token")
                return {"ty": "pat", "auth": auth}
            if ty == "clientmetrics":
                return {"ty": "clientmetrics", "webdriver": False,
                        "bot_detection_result": {"bot_detected": False, "bot_kind": None},
                        "browser_metrics": {"client_data": "{}", "error_trace": None},
                        "detector_results": {}, "v": 2}
            raise FastlyChallengeError(f"unsupported Fastly challenge type '{ty}' (captcha cannot be solved automatically)")

        for _ in range(3):
            answers = [answer(c) for c in challenges]
            r = s.post(
                f"{prefix}/fst-post-back",
                headers={"Content-Type": "application/json", "Accept": "application/json",
                         "User-Agent": SOLVER_UA, "Origin": origin},
                data=json.dumps({"token": token, "data": answers}).encode(), **kw)
            if not r.ok:
                raise FastlyChallengeError(f"challenge post-back returned HTTP {r.status_code}")
            body = r.json()
            if body.get("status") == "success":
                _, still = fetch_page()
                if still:
                    raise FastlyChallengeError("challenge cookie not accepted on this exit IP")
                cookies = {c.name: c.value for c in s.cookies}
                if not cookies:
                    raise FastlyChallengeError("challenge completed but no cookies were issued")
                with self._lock:
                    self.fx_cookies.update(cookies)
                log.info("Fastly challenge solved")
                return
            nxt, ntok = body.get("ch"), body.get("tok", "")
            if not nxt or not ntok:
                raise FastlyChallengeError(f"unexpected post-back response: {body}")
            challenges, token = nxt, ntok
        raise FastlyChallengeError("Fastly challenge did not complete within 3 rounds")


class _NoChallengePage(Exception):
    pass


# --------------------------------------------------------------------------- Firefox account

class Fxa:
    def __init__(self, cp: ControlPlane):
        self.cp = cp

    def _do(self, method: str, path: str, body: dict, session_token: Optional[str] = None) -> dict:
        url = FXA_AUTH_SERVER + path
        body_bytes = json.dumps(body, separators=(",", ":")).encode()
        cred = derive_hawk_credentials(session_token) if session_token else None

        def attempt():
            headers = {"Content-Type": "application/json"}
            if cred:
                headers["Authorization"] = hawk_header(method, url, cred[0], cred[1], body_bytes)
            return self.cp.request(method, url, headers, body_bytes)

        r = attempt()
        tries = 1
        while r.status_code == 406 and tries < 5:
            self.cp.solve_challenge()
            r = attempt()
            tries += 1
        text = r.text
        if r.status_code >= 400:
            try:
                err = json.loads(text)
            except ValueError:
                err = {}
            raise FxaApiError(err.get("message") or text or f"HTTP {r.status_code}",
                              err.get("errno"), r.status_code, err)
        return json.loads(text) if text else {}

    def login(self, email: str, password: str, with_verification_method: bool = True,
              _case_retry: bool = True) -> dict:
        body = {"email": email, "authPW": derive_auth_pw(email, password)}
        if with_verification_method:
            body["verificationMethod"] = "email-2fa"
        try:
            return self._do("POST", "/account/login", body)
        except FxaApiError as e:
            if e.errno == 107 and with_verification_method:
                return self.login(email, password, False, _case_retry)
            if e.errno == 120 and _case_retry and e.body.get("email"):  # wrong e-mail letter case
                return self.login(e.body["email"], password, with_verification_method, False)
            raise

    def verify_code(self, session_token: str, code: str) -> None:
        self._do("POST", "/session/verify_code", {"code": code}, session_token)

    def oauth_from_session(self, session_token: str) -> dict:
        return self._do("POST", "/oauth/token", {
            "client_id": FIREFOX_CLIENT_ID, "grant_type": "fxa-credentials",
            "scope": OAUTH_SCOPE, "access_type": "offline"}, session_token)

    def refresh(self, refresh_token: str) -> dict:
        return self._do("POST", "/oauth/token", {
            "client_id": FIREFOX_CLIENT_ID, "grant_type": "refresh_token",
            "refresh_token": refresh_token, "scope": OAUTH_SCOPE})


@dataclass
class ProxyPass:
    token: str
    exp: float
    quota_remaining: Optional[int] = None
    quota_limit: Optional[int] = None


@dataclass(frozen=True)
class QuotaUsage:
    quota_limit: int
    quota_remaining: int


def state_path() -> str:
    base = os.environ.get("APPDATA") or os.path.join(os.path.expanduser("~"), ".config")
    d = os.path.join(base, "FoxyBridge")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "state.json")


class Account:
    """Stores the OAuth tokens (NOT the password) and mints Guardian proxy passes."""

    def __init__(self, cp: ControlPlane, path: Optional[str] = None):
        self.cp, self.fxa = cp, Fxa(cp)
        self.path = path or state_path()
        self.lock = threading.RLock()
        self.st: dict = {}
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                self.st = json.load(f)
        except (OSError, ValueError):
            self.st = {}

    def save(self) -> None:
        with self.lock:
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.st, f)
            try:
                os.chmod(tmp, 0o600)
            except OSError:
                pass
            os.replace(tmp, self.path)

    def clear(self) -> None:
        with self.lock:
            keep = {k: v for k, v in self.st.items() if k == "last_country"}
            self.st = keep
            self.save()

    def _store_tokens(self, data: dict) -> None:
        with self.lock:
            self.st["access_token"] = data["access_token"]
            rt = data.get("refresh_token")
            if rt:
                self.st["refresh_token"] = rt
            self.st["expires_at"] = int(time.time()) + int(data.get("expires_in") or DEFAULT_TOKEN_TTL)
            self.save()

    def valid_access(self) -> bool:
        return bool(self.st.get("access_token")) and self.st.get("expires_at", 0) > time.time() + 60

    def has_session(self) -> bool:
        return bool(self.st.get("refresh_token")) or self.valid_access()

    def login_interactive(self) -> None:
        email = os.environ.get("FOXY_EMAIL") or input("Firefox account e-mail: ").strip()
        password = os.environ.get("FOXY_PASSWORD") or getpass.getpass("Password (hidden): ")
        data = self.fxa.login(email, password)
        session_token = data["sessionToken"]
        if not data.get("verified", False):
            for _ in range(3):
                code = input("Firefox sent a verification code to your e-mail. Enter it: ").strip()
                try:
                    self.fxa.verify_code(session_token, code)
                    break
                except FxaApiError as e:
                    print(f"  code rejected: {e}")
            else:
                raise NeedLogin("verification failed")
        self._store_tokens(self.fxa.oauth_from_session(session_token))

    def access_token(self, force: bool = False) -> str:
        with self.lock:
            if not force and self.valid_access():
                return self.st["access_token"]
            rt = self.st.get("refresh_token")
            if not rt:
                raise NeedLogin("no refresh token; sign in again")
            try:
                data = self.fxa.refresh(rt)
            except FxaApiError as e:
                if e.status in (400, 401, 403):
                    self.clear()
                    raise NeedLogin(f"Firefox rejected the stored session: {e}")
                raise
            if not data.get("access_token"):
                raise UpstreamError("FxA returned no access token")
            self._store_tokens(data)
            return self.st["access_token"]

    # ---- Guardian

    def _guardian(self, method: str, path: str, access: str, _solved=False):
        r = self.cp.request(method, GUARDIAN + path,
                            {"Content-Type": "application/json", "Authorization": f"Bearer {access}",
                             "Cache-Control": "no-cache"},
                            b"" if method == "POST" else None)
        if r.status_code == 406 and not _solved:
            self.cp.solve_challenge()
            return self._guardian(method, path, access, True)
        return r

    def _fetch_pass(self, access: str) -> ProxyPass:
        r = self._guardian("GET", "/api/v1/fpn/token", access)
        if r.status_code in (401, 403):
            raise TokenInvalid("access token was rejected by Guardian")
        if r.status_code == 429:
            raise QuotaExceeded("your Firefox VPN monthly quota is used up")
        if r.status_code != 200:
            raise UpstreamError(f"failed to fetch proxy pass: HTTP {r.status_code}: {r.text[:300]}")
        body = r.json()
        token = body.get("token", "")
        if not token:
            raise UpstreamError("proxy pass response did not contain a token")
        exp = body.get("expires_at") or jwt_expiry(token) or (time.time() + 600)
        rem = r.headers.get("X-Quota-Remaining")
        limit = r.headers.get("X-Quota-Limit")
        quota_remaining = int(rem) if rem and rem.isdigit() else None
        quota_limit = int(limit) if limit and limit.isdigit() else None
        if quota_limit is not None and quota_remaining is not None and quota_remaining > quota_limit:
            quota_remaining = quota_limit = None
        return ProxyPass(token, float(exp), quota_remaining, quota_limit)

    def fetch_quota(self) -> Optional[QuotaUsage]:
        r = self._guardian("HEAD", "/api/v1/fpn/token", self.access_token())
        rem = r.headers.get("X-Quota-Remaining")
        limit = r.headers.get("X-Quota-Limit")
        if not rem or not rem.isdigit() or not limit or not limit.isdigit():
            return None
        quota_remaining, quota_limit = int(rem), int(limit)
        if quota_limit <= 0 or quota_remaining > quota_limit:
            return None
        return QuotaUsage(quota_limit, quota_remaining)

    def mint_pass(self) -> ProxyPass:
        access = self.access_token()
        try:
            return self._fetch_pass(access)
        except TokenInvalid:
            log.info("pass rejected; activating the Guardian entitlement and retrying")
            r = self._guardian("POST", "/api/v1/fpn/activate", access)
            if r.status_code != 200:
                log.warning("activate returned HTTP %s: %s", r.status_code, r.text[:200])
            try:
                return self._fetch_pass(access)
            except TokenInvalid:
                return self._fetch_pass(self.access_token(force=True))


# --------------------------------------------------------------------------- server list

@dataclass
class Country:
    code: str
    name: str
    targets: List[Tuple[str, int]] = field(default_factory=list)


def parse_serverlist(records: list) -> Dict[str, Country]:
    out: Dict[str, Country] = {}
    for rec in records:
        c = rec.get("country") or rec
        code, name = c.get("code", ""), c.get("name", "")
        if not code or name.lower() in EXCLUDED_COUNTRY_NAMES:
            continue
        targets: List[Tuple[str, int]] = []
        for city in c.get("cities", []):
            for srv in city.get("servers", []):
                if srv.get("quarantined"):
                    continue
                protos = srv.get("protocols") or []
                if not protos:
                    host, port = srv.get("hostname", ""), srv.get("port", 0)
                else:
                    p = next((p for p in protos if p.get("name") == "connect"), None)
                    if p is None:
                        continue
                    host = p.get("host") or srv.get("hostname", "")
                    port = p.get("port") or srv.get("port", 0)
                if host:
                    targets.append((host, int(port) or 443))
        if not targets:
            continue
        key = code.upper()
        if key in out:
            out[key].targets.extend(t for t in targets if t not in out[key].targets)
        else:
            out[key] = Country(key, name, targets)
    return out


def fetch_countries(cp: ControlPlane) -> Dict[str, Country]:
    r = cp.request("GET", SERVERLIST_URL)
    if not r.ok:
        raise UpstreamError(f"Remote Settings fetch failed: HTTP {r.status_code}")
    countries = parse_serverlist(r.json().get("data", []))
    log.info("server list: %d countries", len(countries))
    return countries


def display_name(c: Country) -> str:
    return "Recommended (auto)" if c.code == "REC" else c.name


def sorted_countries(countries: Dict[str, Country]) -> List[Country]:
    return sorted(countries.values(), key=lambda c: (c.code != "REC", display_name(c).lower()))


# --------------------------------------------------------------------------- HTTP/2 tunnel

TLS = {"cafile": None, "insecure": False, "kind": "system"}


def make_ssl_context(kind: str) -> ssl.SSLContext:
    """kind: 'system' (Windows store) or 'certifi' (Mozilla's bundle, same roots Firefox trusts)."""
    if TLS["insecure"]:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
    elif TLS["cafile"]:
        ctx = ssl.create_default_context(cafile=TLS["cafile"])
    elif kind == "certifi":
        import certifi
        ctx = ssl.create_default_context(cafile=certifi.where())
    else:
        ctx = ssl.create_default_context()
    ctx.set_alpn_protocols(["h2"])
    return ctx


def describe_untrusted_cert(host: str, port: int) -> str:
    """Handshake WITHOUT verification just to show who issued the certificate we were given."""
    try:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        with socket.create_connection((host, port), 10) as raw, ctx.wrap_socket(raw, server_hostname=host) as t:
            der = t.getpeercert(binary_form=True)
        fp = hashlib.sha256(der).hexdigest()
        issuer = subject = "?"
        try:
            import tempfile
            with tempfile.NamedTemporaryFile("w", suffix=".pem", delete=False) as f:
                f.write(ssl.DER_cert_to_PEM_cert(der))
            d = ssl._ssl._test_decode_cert(f.name)  # private but stable for years
            os.unlink(f.name)
            flat = lambda name: " / ".join(f"{k}={v}" for rdn in d.get(name, ()) for k, v in rdn)
            issuer, subject = flat("issuer"), flat("subject")
        except Exception:  # noqa: BLE001
            pass
        return f"certificate presented by {host}:{port}\n    issuer : {issuer}\n    subject: {subject}\n    sha256 : {fp}"
    except Exception as e:  # noqa: BLE001
        return f"(could not inspect the certificate: {e})"


class H2Stream:
    """One CONNECT stream = one proxied TCP connection."""

    def __init__(self, conn: "H2Conn", sid: int):
        self.c, self.sid = conn, sid
        self.status_fut: asyncio.Future = conn.loop.create_future()
        self.status_fut.add_done_callback(lambda f: f.cancelled() or f.exception())
        self.inq: asyncio.Queue = asyncio.Queue()
        self.local_closed = False
        self.remote_closed = False
        self.eof = False

    async def read(self) -> bytes:
        if self.eof:
            return b""
        item = await self.inq.get()
        if item is None:
            self.eof = True
            return b""
        try:
            self.c.conn.acknowledge_received_data(len(item), self.sid)
        except h2.exceptions.H2Error:
            pass
        await self.c.flush()
        return item

    async def write(self, data: bytes) -> None:
        mv = memoryview(data)
        while len(mv):
            if self.local_closed or self.c.dead:
                raise ConnectionLost("tunnel closed")
            try:
                win = min(self.c.conn.local_flow_control_window(self.sid), self.c.conn.max_outbound_frame_size)
            except h2.exceptions.H2Error as e:
                raise ConnectionLost(str(e))
            if win <= 0:
                self.c.window_event.clear()
                await self.c.window_event.wait()
                continue
            chunk = bytes(mv[:win])
            mv = mv[win:]
            try:
                self.c.conn.send_data(self.sid, chunk)
            except h2.exceptions.H2Error as e:
                raise ConnectionLost(str(e))
            await self.c.flush()

    async def close_write(self) -> None:
        if self.local_closed:
            return
        self.local_closed = True
        try:
            self.c.conn.end_stream(self.sid)
            await self.c.flush()
        except (h2.exceptions.H2Error, ConnectionLost, OSError):
            pass
        self._maybe_release()

    async def close(self) -> None:
        if not (self.local_closed and self.remote_closed) and not self.c.dead:
            try:
                self.c.conn.reset_stream(self.sid, h2.errors.ErrorCodes.CANCEL)
                await self.c.flush()
            except (h2.exceptions.H2Error, ConnectionLost, OSError):
                pass
        self.local_closed = self.remote_closed = True
        self._maybe_release()

    def _maybe_release(self) -> None:
        if self.local_closed and self.remote_closed:
            self.c.streams.pop(self.sid, None)


class H2Conn:
    """One TLS+HTTP/2 connection to a Fastly masque edge, multiplexing many CONNECT streams."""

    def __init__(self, host: str, port: int):
        self.host, self.port = host, port
        self.loop = asyncio.get_running_loop()
        self.reader = self.writer = None
        self.conn = None
        self.streams: Dict[int, H2Stream] = {}
        self.dead = False
        self.goaway = False
        self.error: Optional[Exception] = None
        self.window_event = asyncio.Event()
        self._wlock = asyncio.Lock()
        self._tasks: List[asyncio.Task] = []
        self.last_rx = time.monotonic()
        self.awaiting_ping = False
        self.ping_sent = 0.0

    @property
    def alive(self) -> bool:
        return not self.dead and not self.goaway

    async def connect(self) -> None:
        if not HAVE_H2:
            raise UpstreamError("the 'h2' package is missing: pip install h2")
        kinds = [TLS["kind"]] + [k for k in ("system", "certifi") if k != TLS["kind"]]
        if TLS["insecure"] or TLS["cafile"]:
            kinds = ["system"]
        for i, kind in enumerate(kinds):
            try:
                self.reader, self.writer = await asyncio.open_connection(
                    self.host, self.port, ssl=make_ssl_context(kind), server_hostname=self.host)
                TLS["kind"] = kind
                break
            except ssl.SSLCertVerificationError as e:
                log.warning("TLS verification failed with the %s trust store: %s", kind, e.verify_message)
                if i + 1 < len(kinds):
                    continue
                info = await self.loop.run_in_executor(None, describe_untrusted_cert, self.host, self.port)
                raise UpstreamError(
                    "TLS certificate not trusted - something on your network/PC is replacing the server "
                    "certificate (antivirus HTTPS scanning, ISP/DPI, corporate proxy, or another VPN).\n  "
                    + info +
                    "\n  Fix: turn off HTTPS scanning in your antivirus, or try another network, or export that "
                    "issuer's root certificate and pass it with --ca file.pem")
            except ImportError:
                continue
        sslobj = self.writer.get_extra_info("ssl_object")
        if not sslobj or sslobj.selected_alpn_protocol() != "h2":
            raise UpstreamError(f"{self.host} did not negotiate HTTP/2 (ALPN)")
        sock = self.writer.get_extra_info("socket")
        if sock is not None:
            try:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            except OSError:
                pass
        cfg = h2.config.H2Configuration(client_side=True, header_encoding="utf-8",
                                        validate_outbound_headers=False)
        self.conn = h2.connection.H2Connection(config=cfg)
        self.conn.initiate_connection()
        self.conn.update_settings({h2.settings.SettingCodes.INITIAL_WINDOW_SIZE: STREAM_WINDOW})
        self.conn.increment_flow_control_window(CONN_WINDOW - 65535)
        await self.flush()
        self.last_rx = time.monotonic()
        self._tasks = [self.loop.create_task(self._read_loop()), self.loop.create_task(self._keepalive())]

    async def flush(self) -> None:
        if self.dead or self.conn is None:
            raise ConnectionLost("connection is closed")
        async with self._wlock:
            data = self.conn.data_to_send()
            if data:
                try:
                    self.writer.write(data)
                    await self.writer.drain()
                except (OSError, RuntimeError) as e:
                    self._fail(ConnectionLost(f"write failed: {e}"))
                    raise ConnectionLost(str(e))

    async def _read_loop(self) -> None:
        err: Optional[Exception] = None
        try:
            while True:
                data = await self.reader.read(65536)
                if not data:
                    err = ConnectionLost("connection closed by the server")
                    break
                self.last_rx = time.monotonic()
                for ev in self.conn.receive_data(data):
                    self._dispatch(ev)
                await self.flush()
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001
            err = ConnectionLost(f"{type(e).__name__}: {e}")
        finally:
            self._fail(err or ConnectionLost("closed"))

    def _dispatch(self, ev) -> None:
        E = h2.events
        sid = getattr(ev, "stream_id", None)
        st = self.streams.get(sid) if sid else None
        if isinstance(ev, E.ResponseReceived):
            if st and not st.status_fut.done():
                try:
                    status = int(dict(ev.headers).get(":status", "0"))
                except ValueError:
                    status = 0
                st.status_fut.set_result(status)
        elif isinstance(ev, E.DataReceived):
            if st:
                st.inq.put_nowait(ev.data)
            else:
                try:
                    self.conn.acknowledge_received_data(ev.flow_controlled_length, ev.stream_id)
                except h2.exceptions.H2Error:
                    pass
        elif isinstance(ev, E.StreamEnded):
            if st:
                st.remote_closed = True
                st.inq.put_nowait(None)
                st._maybe_release()
        elif isinstance(ev, E.StreamReset):
            if st:
                st.remote_closed = st.local_closed = True
                if not st.status_fut.done():
                    st.status_fut.set_exception(UpstreamError(f"stream reset (code {ev.error_code})"))
                st.inq.put_nowait(None)
                st._maybe_release()
        elif isinstance(ev, (E.WindowUpdated, E.RemoteSettingsChanged)):
            self.window_event.set()
        elif isinstance(ev, _PING_ACK):
            self.awaiting_ping = False
        elif isinstance(ev, E.ConnectionTerminated):
            log.warning("server sent GOAWAY (code %s); will reconnect for new connections", ev.error_code)
            self.goaway = True

    async def _keepalive(self) -> None:
        try:
            while not self.dead:
                await asyncio.sleep(3)
                now = time.monotonic()
                if self.awaiting_ping:
                    if now - self.ping_sent > 10:
                        log.warning("keepalive PING unanswered; dropping the tunnel")
                        self._fail(ConnectionLost("keepalive timeout"))
                        return
                elif now - self.last_rx > 15:
                    self.awaiting_ping, self.ping_sent = True, now
                    self.conn.ping(b"FoxyBrdg")
                    await self.flush()
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001
            pass

    def _fail(self, err: Exception) -> None:
        if self.dead:
            return
        self.dead, self.error = True, err
        for st in list(self.streams.values()):
            st.remote_closed = st.local_closed = True
            if not st.status_fut.done():
                st.status_fut.set_exception(err)
            st.inq.put_nowait(None)
        self.streams.clear()
        self.window_event.set()
        if self.writer is not None:
            try:
                self.writer.close()
            except Exception:  # noqa: BLE001
                pass

    async def close(self) -> None:
        self._fail(ConnectionLost("closed locally"))
        for t in self._tasks:
            if t is not asyncio.current_task():
                t.cancel()

    async def open_stream(self, authority: str, token: str) -> H2Stream:
        if not self.alive:
            raise ConnectionLost("connection is not usable")
        while len(self.streams) >= min(self.conn.remote_settings.max_concurrent_streams, 1000):
            await asyncio.sleep(0.02)
            if not self.alive:
                raise ConnectionLost("connection is not usable")
        sid = self.conn.get_next_available_stream_id()
        st = H2Stream(self, sid)
        self.streams[sid] = st
        try:
            self.conn.send_headers(sid, [
                (":method", "CONNECT"),
                (":authority", authority),
                ("proxy-authorization", "Bearer " + token),
            ])
            await self.flush()
            status = await asyncio.wait_for(st.status_fut, OPEN_STREAM_TIMEOUT)
        except asyncio.TimeoutError:
            await st.close()
            raise UpstreamError(f"timed out waiting for CONNECT {authority}")
        except h2.exceptions.H2Error as e:
            self.streams.pop(sid, None)
            raise UpstreamError(str(e))
        except Exception:
            self.streams.pop(sid, None)
            raise
        if not 200 <= status < 300:
            await st.close()
            raise UpstreamHTTPError(status)
        return st


class TokenSource:
    """Async wrapper that keeps a valid Guardian proxy pass at hand."""

    def __init__(self, account: Account):
        self.acc = account
        self.cur: Optional[ProxyPass] = None
        self.lock = asyncio.Lock()

    async def get(self) -> str:
        async with self.lock:
            if self.cur and self.cur.exp - time.time() > 120:
                return self.cur.token
            self.cur = await asyncio.get_running_loop().run_in_executor(None, self.acc.mint_pass)
            left = int(self.cur.exp - time.time())
            log.info("got a proxy pass (valid ~%ds%s)", left,
                     f", quota left {self.cur.quota_remaining}" if self.cur.quota_remaining is not None else "")
            return self.cur.token

    def invalidate(self) -> None:
        self.cur = None


class Upstream:
    """Owns the (single) HTTP/2 connection, fails over between the servers of a country."""

    def __init__(self, tokens: TokenSource):
        self.tokens = tokens
        self.candidates: List[Tuple[str, int]] = []
        self.idx = 0
        self.conn: Optional[H2Conn] = None
        self.current: Optional[Tuple[str, int]] = None
        self.country: Optional[Country] = None
        self.lock = asyncio.Lock()

    async def set_country(self, country: Country) -> None:
        self.country = country
        self.candidates = list(country.targets)
        random.shuffle(self.candidates)
        self.idx = 0
        old, self.conn = self.conn, None
        if old:
            await old.close()

    def update_targets(self, country: Country) -> None:
        """Refresh the server list without dropping the live connection."""
        if self.country and country.code == self.country.code and country.targets:
            self.country = country
            self.candidates = list(country.targets)
            random.shuffle(self.candidates)
            self.idx = 0

    async def _ensure_conn(self) -> H2Conn:
        async with self.lock:
            if self.conn and self.conn.alive:
                return self.conn
            if self.conn:
                await self.conn.close()
                self.conn = None
            if not self.candidates:
                raise UpstreamError("no servers available for this country")
            n, last = len(self.candidates), None
            for k in range(n):
                host, port = self.candidates[(self.idx + k) % n]
                c = H2Conn(host, port)
                try:
                    await asyncio.wait_for(c.connect(), CONNECT_TIMEOUT)
                except Exception as e:  # noqa: BLE001
                    last = e
                    log.warning("server %s:%s failed: %s", host, port, e or type(e).__name__)
                    await c.close()
                    continue
                self.idx = (self.idx + k) % n
                self.conn, self.current = c, (host, port)
                log.info("tunnel up via %s:%s", host, port)
                return c
            self.idx = (self.idx + 1) % n
            raise UpstreamError(f"all servers failed (last error: {last or type(last).__name__})")

    async def preflight(self) -> None:
        await self.tokens.get()
        await self._ensure_conn()

    async def open(self, host: str, port: int):
        authority = f"[{host}]:{port}" if ":" in host and not host.startswith("[") else f"{host}:{port}"
        last: Optional[Exception] = None
        for attempt in (1, 2, 3):
            try:
                conn = await self._ensure_conn()
                token = await self.tokens.get()
                return await conn.open_stream(authority, token)
            except UpstreamHTTPError as e:
                last = e
                if e.status in (401, 403, 407) and attempt < 3:
                    self.tokens.invalidate()
                    continue
                raise
            except ConnectionLost as e:
                last = e
                continue
        raise last or UpstreamError("could not open a tunnel stream")

    async def close(self) -> None:
        if self.conn:
            await self.conn.close()


# --------------------------------------------------------------------------- local proxies

ACTIVE = {"n": 0, "total": 0, "uploaded": 0, "downloaded": 0}


def reset_traffic_stats() -> None:
    ACTIVE["uploaded"] = 0
    ACTIVE["downloaded"] = 0


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, stream, initial: bytes = b"") -> None:
    async def up():
        try:
            if initial:
                await stream.write(initial)
                ACTIVE["uploaded"] += len(initial)
            while True:
                d = await reader.read(65536)
                if not d:
                    break
                await stream.write(d)
                ACTIVE["uploaded"] += len(d)
        except Exception:  # noqa: BLE001
            pass
        finally:
            await stream.close_write()

    async def down():
        try:
            while True:
                d = await stream.read()
                if not d:
                    break
                writer.write(d)
                await writer.drain()
                ACTIVE["downloaded"] += len(d)
        except Exception:  # noqa: BLE001
            pass
        finally:
            try:
                if writer.can_write_eof():
                    writer.write_eof()
            except Exception:  # noqa: BLE001
                pass

    ACTIVE["n"] += 1
    ACTIVE["total"] += 1
    t_up, t_down = asyncio.ensure_future(up()), asyncio.ensure_future(down())
    try:
        await t_down
        try:
            await asyncio.wait_for(asyncio.shield(t_up), 30)
        except (asyncio.TimeoutError, Exception):  # noqa: BLE001
            pass
    finally:
        ACTIVE["n"] -= 1
        for t in (t_up, t_down):
            t.cancel()
        await stream.close()
        try:
            writer.close()
        except Exception:  # noqa: BLE001
            pass


async def handle_socks(reader, writer, upstream) -> None:
    def reply(code: int) -> bytes:
        return bytes([5, code, 0, 1, 0, 0, 0, 0, 0, 0])

    try:
        ver, n = await reader.readexactly(2)
        if ver != 5:
            writer.close()
            return
        methods = await reader.readexactly(n)
        if 0 not in methods:
            writer.write(b"\x05\xff")
            await writer.drain()
            writer.close()
            return
        writer.write(b"\x05\x00")
        _, cmd, _, atyp = await reader.readexactly(4)
        if atyp == 1:
            host = socket.inet_ntoa(await reader.readexactly(4))
        elif atyp == 3:
            ln = (await reader.readexactly(1))[0]
            host = (await reader.readexactly(ln)).decode("utf-8", "replace")
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, await reader.readexactly(16))
        else:
            writer.write(reply(8))
            await writer.drain()
            writer.close()
            return
        port = int.from_bytes(await reader.readexactly(2), "big")
        if cmd != 1:  # only CONNECT
            writer.write(reply(7))
            await writer.drain()
            writer.close()
            return
        try:
            stream = await upstream.open(host, port)
        except Exception as e:  # noqa: BLE001
            log.info("SOCKS %s:%s failed: %s", host, port, e)
            writer.write(reply(5))
            await writer.drain()
            writer.close()
            return
        writer.write(reply(0))
        await writer.drain()
        log.debug("SOCKS %s:%s open", host, port)
        await pipe(reader, writer, stream)
    except (asyncio.IncompleteReadError, ConnectionError, OSError):
        try:
            writer.close()
        except Exception:  # noqa: BLE001
            pass


async def _http_error(writer, code: int, text: str) -> None:
    try:
        writer.write(f"HTTP/1.1 {code} {text}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".encode())
        await writer.drain()
    finally:
        writer.close()


def rewrite_http_request(method: str, target: str, version: str, header_lines: List[str]):
    u = urlsplit(target)
    if u.scheme != "http" or not u.hostname:
        return None
    path = (u.path or "/") + (("?" + u.query) if u.query else "")
    out, has_host = [f"{method} {path} {version}"], False
    for line in header_lines:
        name = line.split(":", 1)[0].strip().lower()
        if name in ("proxy-connection", "proxy-authorization", "connection", "keep-alive"):
            continue
        if name == "host":
            has_host = True
        out.append(line)
    if not has_host:
        out.append("Host: " + u.netloc.rsplit("@", 1)[-1])
    out.append("Connection: close")
    return u.hostname, u.port or 80, ("\r\n".join(out) + "\r\n\r\n").encode("latin-1")


async def handle_http(reader, writer, upstream) -> None:
    try:
        try:
            raw = await reader.readuntil(b"\r\n\r\n")
        except asyncio.LimitOverrunError:
            return await _http_error(writer, 431, "Request Header Fields Too Large")
        head, _, _ = raw.partition(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        try:
            method, target, version = lines[0].split(" ", 2)
        except ValueError:
            return await _http_error(writer, 400, "Bad Request")

        if method.upper() == "CONNECT":
            host, _, port_s = target.rpartition(":")
            host = host.strip("[]")
            if not host or not port_s.isdigit():
                return await _http_error(writer, 400, "Bad Request")
            hostport = (host, int(port_s))
            first = b""
        else:
            rw = rewrite_http_request(method, target, version, lines[1:])
            if rw is None:
                return await _http_error(writer, 400, "Bad Request (absolute http:// URL expected)")
            host, port, first = rw
            hostport = (host, port)

        try:
            stream = await upstream.open(*hostport)
        except Exception as e:  # noqa: BLE001
            log.info("HTTP %s %s:%s failed: %s", method, hostport[0], hostport[1], e)
            return await _http_error(writer, 502, "Bad Gateway")
        if method.upper() == "CONNECT":
            writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            await writer.drain()
        log.debug("HTTP %s %s:%s open", method, *hostport)
        await pipe(reader, writer, stream, initial=first)
    except (asyncio.IncompleteReadError, ConnectionError, OSError):
        try:
            writer.close()
        except Exception:  # noqa: BLE001
            pass


# --------------------------------------------------------------------------- console / main

class Console:
    """stdin reader running in a daemon thread so Ctrl+C always exits cleanly."""

    def __init__(self, loop: asyncio.AbstractEventLoop):
        self.q: asyncio.Queue = asyncio.Queue()

        def run():
            while True:
                try:
                    line = input()
                except EOFError:
                    loop.call_soon_threadsafe(self.q.put_nowait, None)
                    return
                except Exception:  # noqa: BLE001
                    return
                loop.call_soon_threadsafe(self.q.put_nowait, line)

        threading.Thread(target=run, daemon=True).start()

    async def ask(self, prompt: str) -> Optional[str]:
        print(prompt, end="", flush=True)
        line = await self.q.get()
        return None if line is None else line.strip()


def print_countries(countries: Dict[str, Country]) -> List[Country]:
    items = sorted_countries(countries)
    print()
    cols = 2
    rows = (len(items) + cols - 1) // cols
    for r in range(rows):
        cells = []
        for c in range(cols):
            i = r + c * rows
            if i < len(items):
                it = items[i]
                cells.append(f"{i + 1:>3}) {it.code:<4}{display_name(it):<24}")
        print("  " + "".join(cells))
    print()
    return items


def resolve_choice(text: str, items: List[Country]) -> Optional[Country]:
    t = text.strip()
    if not t:
        return None
    if t.isdigit() and 1 <= int(t) <= len(items):
        return items[int(t) - 1]
    for it in items:
        if it.code.lower() == t.lower() or display_name(it).lower() == t.lower():
            return it
    matches = [it for it in items if t.lower() in display_name(it).lower()]
    return matches[0] if len(matches) == 1 else None


async def amain(args, account: Account, cp: ControlPlane) -> int:
    loop = asyncio.get_running_loop()
    print("Downloading the server list ...")
    countries = await loop.run_in_executor(None, fetch_countries, cp)
    if not countries:
        print("The server list is empty.")
        return 1

    tokens = TokenSource(account)
    upstream = Upstream(tokens)
    console = Console(loop)
    items = sorted_countries(countries)
    last = account.st.get("last_country")

    async def connect_to(country: Country) -> bool:
        print(f"Connecting to {display_name(country)} ({len(country.targets)} server(s)) ...")
        await upstream.set_country(country)
        try:
            await upstream.preflight()
        except NeedLogin as e:
            print(f"  sign-in problem: {e}\n  run again with --logout and sign in again.")
            return False
        except Exception as e:  # noqa: BLE001
            print(f"  failed: {e}")
            return False
        account.st["last_country"] = country.code
        account.save()
        return True

    chosen: Optional[Country] = None
    if args.country:
        chosen = resolve_choice(args.country, items)
        if not chosen:
            print(f"Unknown country '{args.country}'. Use --list.")
            return 1
        if not await connect_to(chosen):
            return 1
    else:
        while True:
            print_countries(countries)
            hint = f" [Enter = {last}]" if last in countries else ""
            ans = await console.ask(f"Pick a country (number or code){hint}: ")
            if ans is None:
                return 1
            if not ans and last in countries:
                ans = last
            pick = resolve_choice(ans, items)
            if not pick:
                print("Not understood, try again.")
                continue
            if await connect_to(pick):
                chosen = pick
                break

    servers = []
    try:
        socks = await asyncio.start_server(lambda r, w: handle_socks(r, w, upstream), args.bind, args.socks_port)
        servers.append(socks)
        http = await asyncio.start_server(lambda r, w: handle_http(r, w, upstream), args.bind, args.http_port)
        servers.append(http)
    except OSError as e:
        print(f"Could not open the local proxy ports: {e}")
        for s in servers:
            s.close()
        await upstream.close()
        return 1

    def banner():
        cur = upstream.current
        print("\n" + "=" * 60)
        print(f" CONNECTED  {display_name(upstream.country)}   via {cur[0]}:{cur[1]}" if cur else " CONNECTED")
        print(f" SOCKS5 proxy : {args.bind}:{args.socks_port}")
        print(f" HTTP  proxy  : {args.bind}:{args.http_port}")
        print("=" * 60)
        print(" test:  curl -x socks5h://127.0.0.1:%d https://www.cloudflare.com/cdn-cgi/trace" % args.socks_port)
        print(" commands: list | use <code|number> | status | quit\n")

    banner()
    if args.bind not in ("127.0.0.1", "localhost", "::1"):
        print(" WARNING: the proxies listen on a non-loopback address and have NO password.\n")

    async def refresher():
        while True:
            await asyncio.sleep(SERVERLIST_REFRESH_SECONDS)
            try:
                fresh = await loop.run_in_executor(None, fetch_countries, cp)
                countries.clear()
                countries.update(fresh)
                if upstream.country and upstream.country.code in fresh:
                    upstream.update_targets(fresh[upstream.country.code])
            except Exception as e:  # noqa: BLE001
                log.warning("server list refresh failed: %s", e)

    refresh_task = asyncio.ensure_future(refresher())
    started = time.time()
    try:
        while True:
            line = await console.q.get()
            if line is None:  # stdin closed: just keep serving
                await asyncio.Event().wait()
            cmd, _, rest = line.strip().partition(" ")
            cmd = cmd.lower()
            if cmd in ("quit", "exit", "q"):
                break
            elif cmd == "list":
                print_countries(countries)
            elif cmd == "status":
                cur = upstream.current
                print(f" {display_name(upstream.country)} via {cur[0]}:{cur[1] if cur else ''}" if cur else " not connected (will reconnect on demand)")
                print(f" active connections: {ACTIVE['n']}   total served: {ACTIVE['total']}   uptime: {int(time.time() - started)}s")
            elif cmd == "use":
                pick = resolve_choice(rest, sorted_countries(countries))
                if not pick:
                    print(" unknown country (see 'list')")
                    continue
                prev = upstream.country
                if await connect_to(pick):
                    banner()
                else:
                    print(f" staying on {display_name(prev)}")
                    await upstream.set_country(prev)
            elif cmd in ("help", "?", ""):
                print(" commands: list | use <code|number> | status | quit")
            else:
                print(" unknown command")
    finally:
        refresh_task.cancel()
        for s in servers:
            s.close()
        await upstream.close()
    return 0


def ensure_login(account: Account) -> None:
    if account.has_session():
        try:
            account.access_token()
            return
        except NeedLogin as e:
            print(f"Session expired: {e}")
        except requests.RequestException as e:
            if account.valid_access():
                return
            print(f"Cannot reach Firefox Accounts: {e}\n(use --api-proxy if it is blocked on your network)")
            sys.exit(1)
        except FxaApiError as e:
            print(f"Firefox Accounts error: {e}")
            sys.exit(1)
    for _ in range(3):
        try:
            account.login_interactive()
            print("Signed in.\n")
            return
        except FxaApiError as e:
            print(f"Login failed: {e}")
        except FastlyChallengeError as e:
            print(f"Blocked by the Fastly bot challenge: {e}\nTry another network / IP, or --api-proxy.")
            sys.exit(1)
        except requests.RequestException as e:
            print(f"Cannot reach Firefox Accounts: {e}\n(use --api-proxy if it is blocked on your network)")
            sys.exit(1)
        except NeedLogin as e:
            print(f"Login failed: {e}")
        if os.environ.get("FOXY_EMAIL"):
            break
    sys.exit(1)


def main() -> int:
    ap = argparse.ArgumentParser(description="Local SOCKS5 + HTTP proxy on top of the Firefox VPN proxy.")
    ap.add_argument("--country", help="country code or name (skip the menu), e.g. DE, REC, Germany")
    ap.add_argument("--list", action="store_true", help="print the server list and exit")
    ap.add_argument("--socks-port", type=int, default=10808)
    ap.add_argument("--http-port", type=int, default=10809)
    ap.add_argument("--bind", default="127.0.0.1", help="listen address (default 127.0.0.1; 0.0.0.0 = whole LAN, no password!)")
    ap.add_argument("--api-proxy", help="proxy URL used ONLY for sign-in / token / server-list requests")
    ap.add_argument("--ca", help="extra CA bundle (PEM) to trust for the tunnel servers")
    ap.add_argument("--insecure", action="store_true",
                    help="DO NOT verify the tunnel server certificate (unsafe: whoever intercepts can steal your pass)")
    ap.add_argument("--logout", action="store_true", help="forget the stored Firefox session and exit")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    if not args.verbose:
        logging.getLogger("urllib3").setLevel(logging.WARNING)

    TLS["cafile"], TLS["insecure"] = args.ca, args.insecure
    if args.insecure:
        print("WARNING: --insecure disables certificate checks; anyone intercepting the connection can read\n"
              "         your proxy pass and the hostnames you visit.\n")
    cp = ControlPlane(args.api_proxy)
    account = Account(cp)

    if args.logout:
        account.clear()
        print("Stored session removed.")
        return 0
    if args.list:
        try:
            for c in sorted_countries(fetch_countries(cp)):
                print(f"{c.code:<4}{display_name(c):<24}" + ", ".join(f"{h}:{p}" for h, p in c.targets))
        except requests.RequestException as e:
            print(f"Cannot download the server list: {e}")
            return 1
        return 0
    if not HAVE_H2:
        print("Missing dependency. Run:  pip install requests h2")
        return 1

    ensure_login(account)
    try:
        return asyncio.run(amain(args, account, cp))
    except KeyboardInterrupt:
        print("\nBye.")
        return 0


if __name__ == "__main__":
    sys.exit(main())