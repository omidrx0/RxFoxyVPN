"""HTTP server: serves the panel files from web/ and the JSON API (/api/...) used by app.js."""
from __future__ import annotations

import json
import logging
import os
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote

import requests

from . import foxy_bridge as fb
from .app import App
from .settings import ICON_PATH, INDEX_PATH, LOOPBACK, WEB_DIR, UI, ApiError, tr
from .sysproxy import ConnectionModeError

log = logging.getLogger("rxvpn")

# Only these file types are ever served from web/ (css, js and images of the panel).
STATIC_TYPES = {
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".png": "image/png",
    ".webp": "image/webp",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2",
    ".woff": "font/woff",
}


def resolve_static(url_path: str):
    """Map a URL path to (file, content-type) inside web/, or None. Blocks ../ and unknown types."""
    rel = unquote(url_path).lstrip("/")
    if not rel or "\\" in rel or "\x00" in rel:
        return None
    root = os.path.realpath(WEB_DIR)
    full = os.path.realpath(os.path.join(root, rel))
    try:
        if os.path.commonpath([root, full]) != root:
            return None
    except ValueError:                      # e.g. another drive on Windows
        return None
    ctype = STATIC_TYPES.get(os.path.splitext(full)[1].lower())
    if ctype is None or not os.path.isfile(full):
        return None
    return full, ctype


FXA_MESSAGES = {
    102: ("حسابی با این ایمیل پیدا نشد.", "No account was found with this email."),
    103: ("رمز عبور اشتباه است.", "The password is incorrect."),
    114: ("تعداد تلاش‌ها زیاد بود. چند دقیقه صبر کنید و دوباره امتحان کنید.",
          "Too many attempts. Wait a few minutes and try again."),
    125: ("Firefox این درخواست را به دلایل امنیتی مسدود کرد. از یک شبکه یا آی‌پی دیگر امتحان کنید.",
          "Firefox blocked this request for security reasons. Try another network or IP."),
}




def friendly_error(e: Exception) -> ApiError:
    """Turn any exception from foxy_bridge into a message the panel can show."""
    if isinstance(e, ApiError):
        return e
    if isinstance(e, ConnectionModeError):
        return ApiError(str(e), 409)
    if isinstance(e, fb.FxaApiError):
        known = FXA_MESSAGES.get(e.errno)
        return ApiError(tr(*known) if known else (str(e) or tr("ورود ناموفق بود.", "Sign-in failed.")), 400)
    if isinstance(e, fb.NeedLogin):
        return ApiError(tr("نشست Firefox منقضی شده. دوباره وارد شوید.", "Your Firefox session expired. Please sign in again."), 401, need_login=True)
    if isinstance(e, fb.QuotaExceeded):
        return ApiError(tr("سهمیه ماهانه‌ی حساب Firefox تمام شده است.", "The monthly quota of your Firefox account is used up."), 429)
    if isinstance(e, fb.FastlyChallengeError):
        return ApiError(tr("چالش ضدربات Fastly اجازه نداد. شبکه را عوض کنید یا --api-proxy بدهید.",
                        "The Fastly anti-bot challenge blocked the request. Change network or use --api-proxy."), 502)
    if isinstance(e, requests.RequestException):
        return ApiError(tr("اتصال به سرورهای Firefox برقرار نشد. اینترنت را بررسی کنید "
                           "(اگر مسدود است از --api-proxy استفاده کنید).",
                           "Could not reach Firefox servers. Check your internet "
                           "(use --api-proxy if it is blocked)."), 502)
    if isinstance(e, fb.UpstreamError):
        return ApiError(str(e), 502)
    log.exception("unexpected error")
    return ApiError(tr(f"خطای غیرمنتظره: {e}", f"Unexpected error: {e}"), 500)




GET_ROUTES = {"status": "status", "servers": "servers", "saved_accounts": "saved_accounts",
              "settings": "get_settings", "tun_log": "tun_log"}
POST_ROUTES = {"login": "login", "verify": "verify", "logout": "logout", "connect": "connect",
               "disconnect": "disconnect", "ping": "ping", "ping_all": "ping_all",
               "refresh_servers": "refresh_servers", "open_url": "open_url", "set_lang": "set_lang",
               "set_connection": "set_connection", "login_saved": "login_saved",
               "delete_saved_account": "delete_saved_account",
               "set_settings": "set_settings", "reset_settings": "reset_settings"}


class Handler(BaseHTTPRequestHandler):
    app: App = None            # set in main()
    allowed_hosts: set = set()
    token = None               # secret: only our own window knows it, so a normal browser gets 403
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the console quiet
        log.debug(fmt, *args)

    def _send(self, status: int, body: bytes, ctype: str, extra: dict | None = None):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, data: dict):
        self._send(status, json.dumps(data, ensure_ascii=False).encode(), "application/json; charset=utf-8")

    def _guard(self, path: str = "", query: str = "") -> bool:
        # blocks DNS-rebinding: only our own loopback host names are accepted
        if (self.headers.get("Host") or "").lower() not in self.allowed_hosts:
            self._json(403, {"error": "forbidden host"})
            return False
        if self.token:
            cookies = [c.strip() for c in (self.headers.get("Cookie") or "").split(";")]
            first_visit = path in ("/", "/index.html") and f"k={self.token}" in query.split("&")
            if f"rx={self.token}" not in cookies and not first_visit:
                self._json(403, {"error": "forbidden"})
                return False
        return True

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if not self._guard(path, query):
            return
        try:
            if path in ("/", "/index.html"):
                extra = {"Set-Cookie": f"rx={self.token}; Path=/; HttpOnly; SameSite=Strict"} if self.token else None
                # Inject the persisted language into the initial HTML response.
                # This avoids relying on WebView localStorage, which can initialize
                # late or use a different profile between app launches.
                lang = UI.get("lang", "en")
                if lang not in ("en", "fa", "ar", "ru", "zh", "ko"):
                    lang = "en"
                direction = "rtl" if lang in ("fa", "ar") else "ltr"
                with open(INDEX_PATH, "r", encoding="utf-8") as f:
                    html = f.read()
                html = html.replace(
                    '<html lang="en" dir="ltr" class="i18n-loading">',
                    f'<html lang="{lang}" dir="{direction}" class="i18n-loading">',
                    1,
                )
                return self._send(200, html.encode("utf-8"), "text/html; charset=utf-8", extra)
            if path == "/favicon.ico":
                if not os.path.exists(ICON_PATH):
                    return self._send(404, b"", "text/plain")
                with open(ICON_PATH, "rb") as f:
                    return self._send(200, f.read(), "image/x-icon", {"Cache-Control": "public, max-age=86400"})
            if path.startswith("/flag/") and path.endswith(".png"):
                data = self.app.flag(path[6:-4].lower())
                if data is None:
                    return self._send(404, b"", "text/plain")
                return self._send(200, data, "image/png", {"Cache-Control": "public, max-age=2592000"})
            if path.startswith("/api/") and path[5:] in GET_ROUTES:
                return self._json(200, getattr(self.app, GET_ROUTES[path[5:]])())
            static = resolve_static(path)
            if static:
                with open(static[0], "rb") as f:
                    cache = {"Cache-Control": "public, max-age=3600"} if static[1].startswith(("image/", "font/")) else None
                    return self._send(200, f.read(), static[1], cache)
            self._send(404, b"not found", "text/plain")
        except FileNotFoundError:
            self._send(500, b"resource missing", "text/plain")
        except Exception as e:  # noqa: BLE001
            err = friendly_error(e)
            self._json(err.status, {"error": str(err), **err.extra})

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if not self._guard(path):
            return
        if self.headers.get("X-RXVPN") != "1":      # a cross-site page cannot set this header
            return self._json(403, {"error": "forbidden"})
        if not path.startswith("/api/") or path[5:] not in POST_ROUTES:
            return self._json(404, {"error": "not found"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n) or b"{}") if n else {}
            if not isinstance(body, dict):
                raise ApiError("bad request")
            self._json(200, getattr(self.app, POST_ROUTES[path[5:]])(body))
        except json.JSONDecodeError:
            self._json(400, {"error": "bad json"})
        except Exception as e:  # noqa: BLE001
            err = friendly_error(e)
            if isinstance(e, fb.NeedLogin):
                self.app.account.clear()
            self._json(err.status, {"error": str(err), **err.extra})



def start_server(app: App, port: int = 0):
    """Start the panel server on 127.0.0.1. Returns (httpd, url). Raises OSError if the port is taken."""
    Handler.app = app
    Handler.token = secrets.token_urlsafe(18)
    httpd = ThreadingHTTPServer((LOOPBACK, port), Handler)
    httpd.daemon_threads = True
    real_port = httpd.server_address[1]
    Handler.allowed_hosts = {f"127.0.0.1:{real_port}", f"localhost:{real_port}"}
    threading.Thread(target=httpd.serve_forever, daemon=True, name="rxvpn-http").start()
    log.info("panel on port %s", real_port)
    return httpd, f"http://{LOOPBACK}:{real_port}/?k={Handler.token}"
