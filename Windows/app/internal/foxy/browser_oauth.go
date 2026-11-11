package foxy

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	browserSessionTimeout = 6 * time.Minute
	maxLoopbackBodyBytes  = 8 * 1024
)

// BrowserAuthMode identifies whether browser login runs in the native isolated WebView2 window
// or in the user's external default browser via OAuth 2.0 + PKCE.
type BrowserAuthMode string

const (
	BrowserModeWebView2 BrowserAuthMode = "webview2"
	BrowserModeExternal BrowserAuthMode = "external"
)

// BrowserAuthStatus is returned by GET /api/login/browser/status for live UI polling.
type BrowserAuthStatus struct {
	Active       bool              `json:"active"`
	Mode         BrowserAuthMode   `json:"mode,omitempty"`
	Stage        BrowserLoginStage `json:"stage"`
	AuthURL      string            `json:"auth_url,omitempty"`
	CallbackPort int               `json:"callback_port,omitempty"`
	Email        string            `json:"email,omitempty"`
	Error        string            `json:"error,omitempty"`
}

// BrowserAuthStarter launches the native WebView2 helper process when mode == BrowserModeWebView2,
// or opens the external system browser when mode == BrowserModeExternal.
type BrowserAuthStarter struct {
	LaunchWebViewHelper func(ipcURL, ipcSecret, email string) (stop func(), err error)
	OpenExternalURL     func(authURL string) error
}

type browserSession struct {
	id            string
	mode          BrowserAuthMode
	emailHint     string
	verifier      string
	state         string
	callbackToken string
	ipcSecret     string
	authURL       string
	callbackPort  int

	ctx      context.Context
	cancel   context.CancelFunc
	server   *http.Server
	stopProc func()

	claimed atomic.Bool
}

// BrowserAuthCoordinator manages independent Browser Sign-In sessions (both isolated WebView2
// WebChannel and external system browser OAuth 2.0 + PKCE + loopback callback listener).
type BrowserAuthCoordinator struct {
	account    *Account
	starter    BrowserAuthStarter
	onComplete func(ctx context.Context, email string)

	mu      sync.Mutex
	cur     *browserSession
	stage   BrowserLoginStage
	mode    BrowserAuthMode
	authURL string
	port    int
	email   string
	lastErr string
}

// NewBrowserAuthCoordinator constructs a BrowserAuthCoordinator converging into account & onComplete.
func NewBrowserAuthCoordinator(
	account *Account,
	starter BrowserAuthStarter,
	onComplete func(ctx context.Context, email string),
) *BrowserAuthCoordinator {
	return &BrowserAuthCoordinator{
		account:    account,
		starter:    starter,
		onComplete: onComplete,
		stage:      BrowserStageIdle,
	}
}

func randomHex(nBytes int) (string, error) {
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func randomBase64URL(nBytes int) (string, error) {
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

func constantTimeEqualString(a, b string) bool {
	if len(a) == 0 || len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// BuildExternalOAuthURL builds the Mozilla OAuth 2.0 + PKCE S256 authorization URL.
func BuildExternalOAuthURL(verifier, state, emailHint string) string {
	sum := sha256.Sum256([]byte(verifier))
	challenge := base64.RawURLEncoding.EncodeToString(sum[:])

	u, _ := url.Parse("https://accounts.firefox.com/authorization")
	q := url.Values{}
	q.Set("client_id", firefoxPublicOAuthClientID)
	q.Set("redirect_uri", firefoxPublicOAuthRedirect)
	q.Set("response_type", "code")
	q.Set("scope", oauthScope)
	q.Set("state", state)
	q.Set("code_challenge_method", "S256")
	q.Set("code_challenge", challenge)
	q.Set("access_type", "offline")
	q.Set("action", "email")
	if clean := NormalizeEmail(emailHint); clean != "" {
		q.Set("email", clean)
	}
	u.RawQuery = q.Encode()
	return u.String()
}

// Start cancels any previous browser login session, starts a fresh loopback listener on 127.0.0.1:0,
// and opens either the isolated native WebView2 window (falling back to external browser if unavailable)
// or the external system browser directly.
func (c *BrowserAuthCoordinator) Start(preferredMode BrowserAuthMode, emailHint string) (BrowserAuthStatus, error) {
	c.Cancel()

	sessID, err := randomHex(16)
	if err != nil {
		return BrowserAuthStatus{}, err
	}
	verifier, err := randomBase64URL(32) // 43 chars base64url (RFC 7636)
	if err != nil {
		return BrowserAuthStatus{}, err
	}
	state, err := randomBase64URL(24)
	if err != nil {
		return BrowserAuthStatus{}, err
	}
	cbToken, err := randomHex(24)
	if err != nil {
		return BrowserAuthStatus{}, err
	}
	ipcSecret, err := randomHex(32)
	if err != nil {
		return BrowserAuthStatus{}, err
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return BrowserAuthStatus{}, fmt.Errorf("failed to bind loopback callback listener: %w", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	authURL := BuildExternalOAuthURL(verifier, state, emailHint)

	ctx, cancel := context.WithTimeout(context.Background(), browserSessionTimeout)
	sess := &browserSession{
		id:            sessID,
		mode:          preferredMode,
		emailHint:     NormalizeEmail(emailHint),
		verifier:      verifier,
		state:         state,
		callbackToken: cbToken,
		ipcSecret:     ipcSecret,
		authURL:       authURL,
		callbackPort:  port,
		ctx:           ctx,
		cancel:        cancel,
	}

	mux := http.NewServeMux()
	// 1. External OAuth loopback callback endpoint: /oauth/callback/<callbackToken>?code=...&state=...
	mux.HandleFunc("/oauth/callback/", func(w http.ResponseWriter, r *http.Request) {
		c.handleLoopbackOAuthCallback(sess, w, r)
	})
	// 2. Isolated WebView2 helper IPC endpoint: POST /internal/webchannel/<ipcSecret>
	mux.HandleFunc("/internal/webchannel/", func(w http.ResponseWriter, r *http.Request) {
		c.handleWebViewIPC(sess, w, r)
	})

	srv := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       15 * time.Second,
	}
	sess.server = srv

	go func() {
		_ = srv.Serve(ln)
	}()

	// Watchdog: close listener and helper when session times out or is cancelled.
	go func() {
		<-ctx.Done()
		c.onSessionExpiredOrCancelled(sess)
	}()

	actualMode := preferredMode
	if actualMode == "" {
		actualMode = BrowserModeWebView2
	}

	ipcURL := fmt.Sprintf("http://127.0.0.1:%d/internal/webchannel/%s", port, ipcSecret)
	if actualMode == BrowserModeWebView2 && c.starter.LaunchWebViewHelper != nil {
		stopFn, launchErr := c.starter.LaunchWebViewHelper(ipcURL, ipcSecret, sess.emailHint)
		if launchErr == nil {
			sess.stopProc = stopFn
		} else {
			// Clean automatic fallback to external browser when WebView2 is unavailable.
			actualMode = BrowserModeExternal
		}
	} else {
		actualMode = BrowserModeExternal
	}
	sess.mode = actualMode

	if actualMode == BrowserModeExternal && c.starter.OpenExternalURL != nil {
		if openErr := c.starter.OpenExternalURL(authURL); openErr != nil {
			cancel()
			_ = srv.Close()
			return BrowserAuthStatus{}, openErr
		}
	}

	// Clear stale cookies before starting a fresh sign-in session.
	if c.account != nil && c.account.cp != nil {
		c.account.cp.ClearCookies()
	}

	c.mu.Lock()
	c.cur = sess
	c.stage = BrowserStageAwaitingBrowser
	c.mode = actualMode
	c.authURL = authURL
	c.port = port
	c.email = ""
	c.lastErr = ""
	st := c.statusLocked()
	c.mu.Unlock()

	return st, nil
}

// Status returns a thread-safe snapshot of the current or most recent Browser Sign-In state.
func (c *BrowserAuthCoordinator) Status() BrowserAuthStatus {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.statusLocked()
}

func (c *BrowserAuthCoordinator) statusLocked() BrowserAuthStatus {
	active := c.cur != nil &&
		c.stage != BrowserStageSucceeded &&
		c.stage != BrowserStageFailed &&
		c.stage != BrowserStageCancelled &&
		c.stage != BrowserStageIdle
	out := BrowserAuthStatus{
		Active: active,
		Mode:   c.mode,
		Stage:  c.stage,
		Email:  c.email,
		Error:  c.lastErr,
	}
	if active && c.mode == BrowserModeExternal {
		out.AuthURL = c.authURL
		out.CallbackPort = c.port
	}
	return out
}

// Cancel stops any running Browser Sign-In session, closes the loopback listener, terminates any
// WebView2 helper process, and wipes partial in-memory verifier/state secrets.
func (c *BrowserAuthCoordinator) Cancel() {
	c.mu.Lock()
	sess := c.cur
	c.cur = nil
	if sess != nil {
		c.stage = BrowserStageCancelled
		c.lastErr = ""
		c.authURL = ""
		c.port = 0
	}
	c.mu.Unlock()

	if sess != nil {
		c.teardownSession(sess)
	}
}

func (c *BrowserAuthCoordinator) teardownSession(sess *browserSession) {
	sess.cancel()
	if sess.stopProc != nil {
		sess.stopProc()
	}
	if sess.server != nil {
		sctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_ = sess.server.Shutdown(sctx)
		cancel()
	}
}

func (c *BrowserAuthCoordinator) onSessionExpiredOrCancelled(sess *browserSession) {
	c.mu.Lock()
	isCurrent := c.cur == sess
	if isCurrent && errors.Is(sess.ctx.Err(), context.DeadlineExceeded) &&
		c.stage != BrowserStageSucceeded && c.stage != BrowserStageFailed {
		c.cur = nil
		c.stage = BrowserStageFailed
		c.lastErr = "Browser sign-in timed out. Please try again."
	}
	c.mu.Unlock()
	c.teardownSession(sess)
}

func (c *BrowserAuthCoordinator) updateStage(sess *browserSession, st BrowserLoginStage) {
	c.mu.Lock()
	if c.cur == sess {
		c.stage = st
	}
	c.mu.Unlock()
}

func (c *BrowserAuthCoordinator) finishSession(sess *browserSession, email string, err error) {
	c.mu.Lock()
	if c.cur != sess {
		c.mu.Unlock()
		return
	}
	c.cur = nil
	c.authURL = ""
	c.port = 0
	if err != nil {
		c.stage = BrowserStageFailed
		c.lastErr = err.Error()
		c.mu.Unlock()
		c.teardownSession(sess)
		return
	}
	c.stage = BrowserStageSucceeded
	c.email = email
	c.lastErr = ""
	c.mu.Unlock()

	if c.onComplete != nil {
		c.onComplete(context.Background(), email)
	}
	// Shut down loopback listener and helper slightly asynchronously so HTTP response flushes cleanly.
	go func() {
		time.Sleep(250 * time.Millisecond)
		c.teardownSession(sess)
	}()
}

func (c *BrowserAuthCoordinator) handleLoopbackOAuthCallback(sess *browserSession, w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")

	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	pathToken := strings.TrimPrefix(r.URL.Path, "/oauth/callback/")
	if !constantTimeEqualString(pathToken, sess.callbackToken) {
		// Forged/invalid path: reject WITHOUT closing the active listener!
		http.Error(w, "invalid callback token", http.StatusBadRequest)
		return
	}

	q := r.URL.Query()
	if oauthErr := strings.TrimSpace(q.Get("error")); oauthErr != "" {
		if constantTimeEqualString(q.Get("state"), sess.state) && sess.claimed.CompareAndSwap(false, true) {
			desc := strings.TrimSpace(q.Get("error_description"))
			if desc == "" {
				desc = oauthErr
			}
			c.finishSession(sess, "", fmt.Errorf("Mozilla sign-in declined: %s", desc))
		}
		writeLoopbackHTML(w, http.StatusBadRequest, false, "Sign-in was cancelled or declined.")
		return
	}

	code := strings.TrimSpace(q.Get("code"))
	gotState := strings.TrimSpace(q.Get("state"))
	if code == "" || !constantTimeEqualString(gotState, sess.state) {
		// Anti-forgery: invalid state or missing code does NOT kill the listener!
		http.Error(w, "invalid OAuth state or missing code", http.StatusBadRequest)
		return
	}

	// Anti-duplicate callback: only the first valid callback claims the PKCE exchange.
	if !sess.claimed.CompareAndSwap(false, true) {
		writeLoopbackHTML(w, http.StatusOK, true, "Sign-in is already completing in RXFOXYVPN. You can close this tab.")
		return
	}

	go c.exchangePKCECode(sess, code)
	writeLoopbackHTML(w, http.StatusOK, true, "Sign-in received! Completing authentication in RXFOXYVPN — you can close this browser tab.")
}

func (c *BrowserAuthCoordinator) handleWebViewIPC(sess *browserSession, w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	pathSecret := strings.TrimPrefix(r.URL.Path, "/internal/webchannel/")
	hdrSecret := r.Header.Get("X-RxVPN-Auth-Token")
	if !constantTimeEqualString(pathSecret, sess.ipcSecret) || !constantTimeEqualString(hdrSecret, sess.ipcSecret) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxLoopbackBodyBytes))
	if err != nil {
		http.Error(w, "bad body", http.StatusBadRequest)
		return
	}
	var payload struct {
		Event        string `json:"event"` // "session", "closed", or "error"
		SessionToken string `json:"session_token"`
		Email        string `json:"email"`
		Error        string `json:"error"`
	}
	if json.Unmarshal(body, &payload) != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}

	switch payload.Event {
	case "closed":
		// Window closed by user before session exchange started -> mark cancelled cleanly.
		if sess.claimed.CompareAndSwap(false, true) {
			c.Cancel()
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
		return

	case "error":
		if sess.claimed.CompareAndSwap(false, true) {
			msg := strings.TrimSpace(payload.Error)
			if msg == "" {
				msg = "Mozilla sign-in window could not load."
			}
			c.finishSession(sess, "", errors.New(msg))
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
		return

	case "session":
		if !IsValidSessionTokenHex(payload.SessionToken) {
			http.Error(w, "invalid session token", http.StatusBadRequest)
			return
		}
		if !sess.claimed.CompareAndSwap(false, true) {
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{"ok":true}`))
			return
		}
		go func() {
			email, err := c.account.CompleteBrowserSession(sess.ctx, payload.SessionToken, payload.Email, func(st BrowserLoginStage) {
				c.updateStage(sess, st)
			})
			c.finishSession(sess, email, err)
		}()
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
		return

	default:
		http.Error(w, "unknown event", http.StatusBadRequest)
	}
}

// CompleteExternalRedirect completes an active external-browser OAuth PKCE session when the user
// pastes the final Mozilla redirect URL (https://accounts.firefox.com/oauth/success/...?code=...&state=...).
func (c *BrowserAuthCoordinator) CompleteExternalRedirect(ctx context.Context, rawRedirect string) (string, error) {
	code, gotState, err := parseOAuthRedirectInput(rawRedirect)
	if err != nil {
		return "", err
	}

	c.mu.Lock()
	sess := c.cur
	c.mu.Unlock()
	if sess == nil {
		return "", errors.New("no active browser sign-in session; start browser sign-in first")
	}
	if !constantTimeEqualString(gotState, sess.state) {
		return "", errors.New("OAuth state mismatch (the link belongs to an older sign-in attempt)")
	}
	if !sess.claimed.CompareAndSwap(false, true) {
		return "", errors.New("this browser sign-in session is already completing")
	}

	c.updateStage(sess, BrowserStageGettingTokens)
	tokens, exchErr := c.account.Fxa.ExchangeOAuthAuthorizationCode(ctx, firefoxPublicOAuthClientID, code, sess.verifier)
	if exchErr != nil {
		c.finishSession(sess, "", exchErr)
		return "", exchErr
	}
	if storeErr := c.account.StoreTokens(tokens); storeErr != nil {
		c.finishSession(sess, "", storeErr)
		return "", storeErr
	}
	email := c.account.Fxa.FetchProfileEmail(ctx, tokens.AccessToken)
	if email == "" {
		email = sess.emailHint
	}
	if email != "" {
		c.account.SetEmail(email)
	}
	c.finishSession(sess, email, nil)
	return email, nil
}

func (c *BrowserAuthCoordinator) exchangePKCECode(sess *browserSession, code string) {
	c.updateStage(sess, BrowserStageGettingTokens)
	tokens, err := c.account.Fxa.ExchangeOAuthAuthorizationCode(sess.ctx, firefoxPublicOAuthClientID, code, sess.verifier)
	if err != nil {
		c.finishSession(sess, "", err)
		return
	}
	if err := c.account.StoreTokens(tokens); err != nil {
		c.finishSession(sess, "", err)
		return
	}
	email := c.account.Fxa.FetchProfileEmail(sess.ctx, tokens.AccessToken)
	if email == "" {
		email = sess.emailHint
	}
	if email != "" {
		c.account.SetEmail(email)
	}
	c.finishSession(sess, email, nil)
}

func parseOAuthRedirectInput(raw string) (code, state string, err error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", "", errors.New("paste the final Mozilla redirect URL containing code and state")
	}
	var q url.Values
	if strings.Contains(trimmed, "://") {
		u, uErr := url.Parse(trimmed)
		if uErr != nil {
			return "", "", errors.New("invalid redirect URL")
		}
		q = u.Query()
	} else {
		trimmed = strings.TrimPrefix(trimmed, "?")
		var qErr error
		q, qErr = url.ParseQuery(trimmed)
		if qErr != nil {
			return "", "", errors.New("invalid OAuth query parameters")
		}
	}
	code = strings.TrimSpace(q.Get("code"))
	state = strings.TrimSpace(q.Get("state"))
	if code == "" || state == "" {
		return "", "", errors.New("redirect URL must contain both code and state parameters")
	}
	return code, state, nil
}

func writeLoopbackHTML(w http.ResponseWriter, status int, ok bool, message string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	accent := "#34e0a1"
	title := "RXFOXYVPN · ورود تکمیل شد"
	if !ok {
		accent = "#ff5c7a"
		title = "RXFOXYVPN · خطا در ورود"
	}
	body := fmt.Sprintf(`<!doctype html><html lang="en"><head><meta charset="utf-8"><title>%s</title>
<style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#060714;color:#eef0ff;font-family:system-ui,-apple-system,Segoe UI,sans-serif}
.card{max-width:440px;padding:28px 30px;border-radius:20px;background:#14173c;border:1px solid rgba(150,158,255,.28);text-align:center;box-shadow:0 20px 60px rgba(0,0,0,.5)}
.dot{width:14px;height:14px;border-radius:50%%;background:%s;margin:0 auto 14px}
h1{font-size:19px;margin:0 0 10px}p{margin:0;color:#9aa1d6;line-height:1.6;font-size:14px}</style></head>
<body><div class="card"><div class="dot"></div><h1>%s</h1><p>%s</p></div></body></html>`,
		html.EscapeString(title), accent, html.EscapeString(title), html.EscapeString(message))
	_, _ = io.WriteString(w, body)
}
