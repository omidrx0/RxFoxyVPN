package foxy

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"rxvpn/internal/config"
)

// ProxyPass is a short-lived Guardian token that authorises CONNECT requests.
type ProxyPass struct {
	Token          string
	Exp            float64 // unix seconds
	QuotaRemaining *int
	QuotaLimit     *int
}

// QuotaUsage is the monthly allowance as reported by Guardian.
type QuotaUsage struct{ Limit, Remaining int }

// accountState is what state.json holds. The password is never stored here.
type accountState struct {
	AccessToken    string `json:"access_token,omitempty"`
	RefreshToken   string `json:"refresh_token,omitempty"`
	ExpiresAt      int64  `json:"expires_at,omitempty"`
	Email          string `json:"email,omitempty"`
	LastCountry    string `json:"last_country,omitempty"`
	QuotaLimit     *int   `json:"quota_limit,omitempty"`
	QuotaRemaining *int   `json:"quota_remaining,omitempty"`
}

// Account stores the OAuth tokens and mints Guardian proxy passes.
type Account struct {
	cp   *ControlPlane
	Fxa  *Fxa
	path string

	refreshMu sync.Mutex
	mu        sync.Mutex
	st        accountState
}

// NewAccount loads state.json (an unreadable file simply means "signed out").
func NewAccount(cp *ControlPlane, path string) *Account {
	if path == "" {
		path = config.StatePath()
	}
	a := &Account{cp: cp, Fxa: &Fxa{cp: cp}, path: path}
	if raw, err := os.ReadFile(path); err == nil {
		if json.Unmarshal(raw, &a.st) != nil {
			a.st = accountState{}
		}
	}
	return a
}

func (a *Account) saveLocked() error {
	data, err := json.Marshal(a.st)
	if err != nil {
		return err
	}
	return config.WriteFileAtomic(a.path, data, 0o600)
}

func (a *Account) save() {
	if err := a.saveLocked(); err != nil {
		slog.Warn("could not save the account state", "err", err)
	}
}

// Save persists the state.
func (a *Account) Save() {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.save()
}

func (a *Account) clearLocked() {
	a.st = accountState{LastCountry: a.st.LastCountry}
	a.save()
}

// Clear signs out (the last chosen country is kept).
func (a *Account) Clear() {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.clearLocked()
}

// StoreTokens saves a fresh OAuth answer.
func (a *Account) StoreTokens(t *TokenResponse) error {
	if t == nil || t.AccessToken == "" {
		return errors.New("FxA returned no access token")
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	a.st.AccessToken = t.AccessToken
	if t.RefreshToken != "" {
		a.st.RefreshToken = t.RefreshToken
	}
	ttl := int64(t.ExpiresIn)
	if ttl == 0 {
		ttl = defaultTokenTTL
	}
	a.st.ExpiresAt = time.Now().Unix() + ttl
	a.save()
	return nil
}

func (a *Account) validAccessLocked() bool {
	return a.st.AccessToken != "" && a.st.ExpiresAt > time.Now().Unix()+60
}

// HasSession reports whether the user is signed in (a refresh token or a valid access token).
func (a *Account) HasSession() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.st.RefreshToken != "" || a.validAccessLocked()
}

// LastCountry is the code of the country connected last time.
func (a *Account) LastCountry() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.st.LastCountry
}

// SetLastCountry remembers the chosen country.
func (a *Account) SetLastCountry(code string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.st.LastCountry = code
	a.save()
}

// SetEmail remembers the signed-in address.
func (a *Account) SetEmail(email string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.st.Email = email
	a.save()
}

// Quota returns the saved allowance (valid only when the stored pair is consistent).
func (a *Account) Quota() (limit, remaining *int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	l, r := a.st.QuotaLimit, a.st.QuotaRemaining
	if l != nil && r != nil && *l > 0 && *r >= 0 && *r <= *l {
		return l, r
	}
	return nil, nil
}

// SetQuota saves the allowance; (nil, nil) removes it.
func (a *Account) SetQuota(limit, remaining *int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.st.QuotaLimit, a.st.QuotaRemaining = limit, remaining
	a.save()
}

// AccessToken returns a valid access token, refreshing it when needed. Refreshes are
// serialised, but the state lock is not held during the network call.
func (a *Account) AccessToken(ctx context.Context, force bool) (string, error) {
	a.refreshMu.Lock()
	defer a.refreshMu.Unlock()
	a.mu.Lock()
	if !force && a.validAccessLocked() {
		tok := a.st.AccessToken
		a.mu.Unlock()
		return tok, nil
	}
	rt := a.st.RefreshToken
	a.mu.Unlock()
	if rt == "" {
		return "", &NeedLoginError{Msg: "no refresh token; sign in again"}
	}
	data, err := a.Fxa.Refresh(ctx, rt)
	if err != nil {
		var fe *FxaAPIError
		if errors.As(err, &fe) && (fe.Status == 400 || fe.Status == 401 || fe.Status == 403) {
			a.Clear()
			return "", &NeedLoginError{Msg: "Firefox rejected the stored session: " + fe.Error()}
		}
		return "", err
	}
	if data.AccessToken == "" {
		return "", &UpstreamError{Msg: "FxA returned no access token"}
	}
	if err := a.StoreTokens(data); err != nil {
		return "", err
	}
	return data.AccessToken, nil
}

// ---------------------------------------------------------------- Guardian

func (a *Account) guardian(ctx context.Context, method, path, access string) (*Response, error) {
	var body []byte
	if method == "POST" {
		body = []byte{}
	}
	do := func() (*Response, error) {
		return a.cp.Request(ctx, method, guardianURL+path, map[string]string{
			"Content-Type": "application/json", "Authorization": "Bearer " + access,
			"Cache-Control": "no-cache"}, body)
	}
	r, err := do()
	if err != nil {
		return nil, err
	}
	if r.Status == 406 { // Fastly challenge: solve once and retry
		if err := a.cp.SolveChallenge(ctx); err != nil {
			return nil, err
		}
		return do()
	}
	return r, nil
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func quotaHeaders(h map[string][]string) (remaining, limit *int) {
	get := func(k string) string {
		for name, v := range h {
			if strings.EqualFold(name, k) && len(v) > 0 {
				return v[0]
			}
		}
		return ""
	}
	if s := get("X-Quota-Remaining"); isDigits(s) {
		n, _ := strconv.Atoi(s)
		remaining = &n
	}
	if s := get("X-Quota-Limit"); isDigits(s) {
		n, _ := strconv.Atoi(s)
		limit = &n
	}
	return
}

func (a *Account) fetchPass(ctx context.Context, access string) (*ProxyPass, error) {
	r, err := a.guardian(ctx, "GET", "/api/v1/fpn/token", access)
	if err != nil {
		return nil, err
	}
	switch {
	case r.Status == 401 || r.Status == 403:
		return nil, errTokenInvalid
	case r.Status == 429:
		return nil, &QuotaExceededError{Msg: "your Firefox VPN monthly quota is used up"}
	case r.Status != 200:
		text := r.Text()
		if len(text) > 300 {
			text = text[:300]
		}
		return nil, upstreamf("failed to fetch proxy pass: HTTP %d: %s", r.Status, text)
	}
	var body struct {
		Token     string `json:"token"`
		ExpiresAt any    `json:"expires_at"`
	}
	if err := json.Unmarshal(r.Body, &body); err != nil || body.Token == "" {
		return nil, &UpstreamError{Msg: "proxy pass response did not contain a token"}
	}
	exp := 0.0
	switch v := body.ExpiresAt.(type) {
	case float64:
		exp = v
	case string:
		exp, _ = strconv.ParseFloat(v, 64)
	}
	if exp == 0 {
		if e, ok := JWTExpiry(body.Token); ok {
			exp = float64(e)
		} else {
			exp = float64(time.Now().Unix() + 600)
		}
	}
	rem, limit := quotaHeaders(r.Header)
	if limit != nil && rem != nil && *rem > *limit {
		rem, limit = nil, nil
	}
	return &ProxyPass{Token: body.Token, Exp: exp, QuotaRemaining: rem, QuotaLimit: limit}, nil
}

// FetchQuota asks Guardian (HEAD) for the monthly allowance. It returns nil when the answer
// carries no usable numbers.
func (a *Account) FetchQuota(ctx context.Context) (*QuotaUsage, error) {
	access, err := a.AccessToken(ctx, false)
	if err != nil {
		return nil, err
	}
	r, err := a.guardian(ctx, "HEAD", "/api/v1/fpn/token", access)
	if err != nil {
		return nil, err
	}
	rem, limit := quotaHeaders(r.Header)
	if rem == nil || limit == nil || *limit <= 0 || *rem > *limit {
		return nil, nil
	}
	return &QuotaUsage{Limit: *limit, Remaining: *rem}, nil
}

// MintPass gets a new proxy pass, activating the Guardian entitlement when it is rejected.
func (a *Account) MintPass(ctx context.Context) (*ProxyPass, error) {
	access, err := a.AccessToken(ctx, false)
	if err != nil {
		return nil, err
	}
	pass, err := a.fetchPass(ctx, access)
	if !errors.Is(err, errTokenInvalid) {
		return pass, err
	}
	slog.Info("pass rejected; activating the Guardian entitlement and retrying")
	r, err := a.guardian(ctx, "POST", "/api/v1/fpn/activate", access)
	if err != nil {
		return nil, err
	}
	if r.Status != 200 {
		text := r.Text()
		if len(text) > 200 {
			text = text[:200]
		}
		slog.Warn("activate failed", "status", r.Status, "body", text)
	}
	pass, err = a.fetchPass(ctx, access)
	if !errors.Is(err, errTokenInvalid) {
		return pass, err
	}
	access, err = a.AccessToken(ctx, true)
	if err != nil {
		return nil, err
	}
	return a.fetchPass(ctx, access)
}
