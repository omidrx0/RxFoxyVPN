// Package server serves the panel files from the embedded web folder and the JSON API
// (/api/...) that web/app.js talks to. It only listens on 127.0.0.1 and refuses requests that
// do not come from the panel window itself (secret token cookie, Host check, custom header).
package server

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"path"
	"strings"
	"time"

	"rxvpn/internal/apierr"
	"rxvpn/internal/app"
	"rxvpn/internal/config"
	"rxvpn/internal/foxy"
	"rxvpn/internal/locale"
	"rxvpn/packaging"
	"rxvpn/web"
)

type apiFunc func(context.Context, app.Body) (any, error)

// staticTypes are the only file types ever served from the web folder.
var staticTypes = map[string]string{
	".css":   "text/css; charset=utf-8",
	".js":    "text/javascript; charset=utf-8",
	".png":   "image/png",
	".webp":  "image/webp",
	".svg":   "image/svg+xml",
	".ico":   "image/x-icon",
	".woff2": "font/woff2",
	".woff":  "font/woff",
}

const (
	maxBodyBytes = 1 << 20
	htmlTag      = `<html lang="en" dir="ltr" class="i18n-loading">`
)

// Server is the panel's HTTP server.
type Server struct {
	app          *app.App
	version      string
	token        string
	allowedHosts map[string]bool
	get          map[string]apiFunc
	post         map[string]apiFunc
	webFS        fs.FS
	http         *http.Server
}

// Start listens on 127.0.0.1:port (0 = any free port) and returns the server and the URL the
// panel window must open (it carries the one-time token).
func Start(a *app.App, port int) (*Server, string, error) {
	return StartWithVersion(a, port, "dev")
}

// StartWithVersion starts the local panel and exposes the build version to the UI.
func StartWithVersion(a *app.App, port int, version string) (*Server, string, error) {
	ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", app.Loopback, port))
	if err != nil {
		return nil, "", err
	}
	realPort := ln.Addr().(*net.TCPAddr).Port
	raw := make([]byte, 18)
	if _, err := rand.Read(raw); err != nil {
		ln.Close()
		return nil, "", err
	}
	s := New(a, base64.RawURLEncoding.EncodeToString(raw), realPort)
	s.version = version
	s.http = &http.Server{Handler: s, ReadHeaderTimeout: 30 * time.Second}
	go func() {
		if err := s.http.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("panel server stopped", "err", err)
		}
	}()
	slog.Info("panel listening", "port", realPort)
	return s, fmt.Sprintf("http://%s:%d/?k=%s", app.Loopback, realPort, s.token), nil
}

// New builds the handler (used by Start and by the tests).
func New(a *app.App, token string, port int) *Server {
	s := &Server{
		app:   a,
		token: token,
		allowedHosts: map[string]bool{
			fmt.Sprintf("127.0.0.1:%d", port): true,
			fmt.Sprintf("localhost:%d", port): true,
		},
		webFS: web.FS,
	}
	s.get = map[string]apiFunc{
		"status": a.Status, "servers": a.Servers, "saved_accounts": a.SavedAccounts,
		"settings": a.GetSettings, "tun_log": a.TunLog,
		"update_check": func(ctx context.Context, _ app.Body) (any, error) {
			return checkLatestRelease(ctx, s.version)
		},
		"update_progress": func(ctx context.Context, _ app.Body) (any, error) { return getUpdateProgress(), nil },
	}
	s.post = map[string]apiFunc{
		"install_update": func(ctx context.Context, _ app.Body) (any, error) {
			return downloadAndLaunchInstaller(ctx, s.version)
		},
		"login": a.Login, "verify": a.Verify, "logout": a.Logout, "connect": a.Connect,
		"disconnect": a.Disconnect, "ping": a.Ping, "ping_all": a.PingAll,
		"refresh_servers": a.RefreshServers, "open_url": a.OpenURL, "set_lang": a.SetLang,
		"set_connection": a.SetConnection, "login_saved": a.LoginSaved,
		"delete_saved_account": a.DeleteSavedAccount,
		"set_settings":         a.SetSettings, "reset_settings": a.ResetSettings,
	}
	return s
}

// Shutdown stops the server.
func (s *Server) Shutdown() {
	if s.http == nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = s.http.Shutdown(ctx)
}

// ---------------------------------------------------------------- responses

func send(w http.ResponseWriter, status int, body []byte, ctype string, extra map[string]string) {
	h := w.Header()
	h.Set("Content-Type", ctype)
	h.Set("Content-Length", fmt.Sprint(len(body)))
	h.Set("Cache-Control", "no-store")
	for k, v := range extra {
		h.Set(k, v)
	}
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func sendJSON(w http.ResponseWriter, status int, v any) {
	data, err := config.MarshalJSON(v, false)
	if err != nil {
		data, status = []byte(`{"error":"encoding failed"}`), 500
	}
	send(w, status, data, "application/json; charset=utf-8", nil)
}

func sendError(w http.ResponseWriter, e *apierr.Error) {
	body := map[string]any{"error": e.Message}
	for k, v := range e.Extra {
		body[k] = v
	}
	sendJSON(w, e.Status, body)
}

// guard blocks DNS rebinding (only our own loopback host names) and any page that does not
// carry the secret cookie. The very first load of "/" may present the token in the query.
func (s *Server) guard(w http.ResponseWriter, r *http.Request, p, query string) bool {
	if !s.allowedHosts[strings.ToLower(r.Host)] {
		sendJSON(w, 403, map[string]string{"error": "forbidden host"})
		return false
	}
	if s.token != "" {
		hasCookie := false
		for _, c := range strings.Split(r.Header.Get("Cookie"), ";") {
			if strings.TrimSpace(c) == "rx="+s.token {
				hasCookie = true
				break
			}
		}
		firstVisit := false
		if p == "/" || p == "/index.html" {
			for _, kv := range strings.Split(query, "&") {
				if kv == "k="+s.token {
					firstVisit = true
				}
			}
		}
		if !hasCookie && !firstVisit {
			sendJSON(w, 403, map[string]string{"error": "forbidden"})
			return false
		}
	}
	return true
}

// ServeHTTP implements http.Handler.
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	defer func() {
		if rec := recover(); rec != nil {
			slog.Error("panic in handler", "panic", rec)
			sendError(w, apierr.New(fmt.Sprint("Unexpected error: ", rec), 500))
		}
	}()
	switch r.Method {
	case http.MethodGet, http.MethodHead:
		s.doGET(w, r)
	case http.MethodPost:
		s.doPOST(w, r)
	default:
		sendJSON(w, 405, map[string]string{"error": "method not allowed"})
	}
}

func (s *Server) indexHTML() ([]byte, error) {
	raw, err := fs.ReadFile(s.webFS, "index.html")
	if err != nil {
		return nil, err
	}
	// Inject the persisted language into the first HTML answer, so the page never depends on
	// WebView localStorage (which can start late or use another profile between launches).
	lang := locale.Get()
	if !locale.Valid(lang) {
		lang = "en"
	}
	dir := "ltr"
	if locale.IsRTL(lang) {
		dir = "rtl"
	}
	repl := fmt.Sprintf(`<html lang="%s" dir="%s" class="i18n-loading">`, lang, dir)
	raw = bytes.Replace(raw, []byte(htmlTag), []byte(repl), 1)
	if s.version != "" && s.version != "dev" {
		raw = bytes.ReplaceAll(raw, []byte(`<small class="ver">Beta</small>`), []byte(`<small class="ver">V`+s.version+`</small>`))
	}
	return raw, nil
}

func (s *Server) doGET(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	if !s.guard(w, r, p, r.URL.RawQuery) {
		return
	}
	switch {
	case p == "/" || p == "/index.html":
		html, err := s.indexHTML()
		if err != nil {
			send(w, 500, []byte("resource missing"), "text/plain", nil)
			return
		}
		extra := map[string]string{}
		if s.token != "" {
			extra["Set-Cookie"] = "rx=" + s.token + "; Path=/; HttpOnly; SameSite=Strict"
		}
		send(w, 200, html, "text/html; charset=utf-8", extra)
	case p == "/favicon.ico":
		send(w, 200, packaging.IconICO, "image/x-icon", map[string]string{"Cache-Control": "public, max-age=86400"})
	case strings.HasPrefix(p, "/flag/") && strings.HasSuffix(p, ".png"):
		data := s.app.Flag(r.Context(), strings.ToLower(p[len("/flag/"):len(p)-len(".png")]))
		if data == nil {
			send(w, 404, nil, "text/plain", nil)
			return
		}
		send(w, 200, data, "image/png", map[string]string{"Cache-Control": "public, max-age=2592000"})
	case strings.HasPrefix(p, "/api/") && s.get[p[len("/api/"):]] != nil:
		s.call(w, r, s.get[p[len("/api/"):]], nil, false)
	default:
		s.serveStatic(w, p)
	}
}

// serveStatic serves one allowed file from the embedded web folder.
func (s *Server) serveStatic(w http.ResponseWriter, urlPath string) {
	rel, err := url.PathUnescape(urlPath)
	rel = strings.TrimPrefix(rel, "/")
	if err != nil || rel == "" || strings.ContainsAny(rel, "\\\x00") || !fs.ValidPath(rel) {
		send(w, 404, []byte("not found"), "text/plain", nil)
		return
	}
	ctype, ok := staticTypes[strings.ToLower(path.Ext(rel))]
	if !ok {
		send(w, 404, []byte("not found"), "text/plain", nil)
		return
	}
	data, err := fs.ReadFile(s.webFS, rel)
	if err != nil {
		send(w, 404, []byte("not found"), "text/plain", nil)
		return
	}
	var extra map[string]string
	if strings.HasPrefix(ctype, "image/") || strings.HasPrefix(ctype, "font/") {
		extra = map[string]string{"Cache-Control": "public, max-age=3600"}
	}
	send(w, 200, data, ctype, extra)
}

func (s *Server) doPOST(w http.ResponseWriter, r *http.Request) {
	p := r.URL.Path
	if !s.guard(w, r, p, "") {
		return
	}
	if r.Header.Get("X-RXVPN") != "1" { // a cross-site page cannot set this header
		sendJSON(w, 403, map[string]string{"error": "forbidden"})
		return
	}
	var fn apiFunc
	if strings.HasPrefix(p, "/api/") {
		fn = s.post[p[len("/api/"):]]
	}
	if fn == nil {
		sendJSON(w, 404, map[string]string{"error": "not found"})
		return
	}
	raw, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytes))
	if err != nil {
		sendJSON(w, 400, map[string]string{"error": "bad request"})
		return
	}
	body := app.Body{}
	if len(bytes.TrimSpace(raw)) > 0 {
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		var v any
		if err := dec.Decode(&v); err != nil {
			sendJSON(w, 400, map[string]string{"error": "bad json"})
			return
		}
		m, ok := v.(map[string]any)
		if !ok {
			sendError(w, apierr.New("bad request"))
			return
		}
		body = app.Body(m)
	}
	s.call(w, r, fn, body, true)
}

func (s *Server) call(w http.ResponseWriter, r *http.Request, fn apiFunc, body app.Body, isPost bool) {
	result, err := fn(r.Context(), body)
	if err != nil {
		var nl *foxy.NeedLoginError
		if isPost && errors.As(err, &nl) {
			s.app.Account().Clear()
		}
		sendError(w, FriendlyError(err))
		return
	}
	sendJSON(w, 200, result)
}
