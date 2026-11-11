package foxy

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
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
	QuotaRemaining *int64
	QuotaLimit     *int64
	QuotaUnlimited bool
}

// QuotaStateKind mirrors Android's 4-state SubscriptionQuotaState.
type QuotaStateKind string

const (
	QuotaStateUnlimited QuotaStateKind = "unlimited"
	QuotaStateLimited   QuotaStateKind = "limited"
	QuotaStateUnknown   QuotaStateKind = "unknown"
	QuotaStateError     QuotaStateKind = "error"
)

// QuotaInfo represents the 4-state Guardian subscription & monthly data allowance.
type QuotaInfo struct {
	State          QuotaStateKind `json:"state"`
	Subscribed     bool           `json:"subscribed"`
	LimitBytes     *int64         `json:"limit_bytes,omitempty"`
	RemainingBytes *int64         `json:"remaining_bytes,omitempty"`
	UsedBytes      *int64         `json:"used_bytes,omitempty"`
	ResetAt        string         `json:"reset_at,omitempty"`
	Error          string         `json:"error,omitempty"`
}

// QuotaUsage is preserved for compatibility with callers expecting finite int byte counts.
type QuotaUsage struct{ Limit, Remaining int }

// accountState is what state.json holds. The password is never stored here.
type accountState struct {
	AccessToken    string `json:"access_token,omitempty"`
	RefreshToken   string `json:"refresh_token,omitempty"`
	ClientID       string `json:"client_id,omitempty"`
	ExpiresAt      int64  `json:"expires_at,omitempty"`
	Email          string `json:"email,omitempty"`
	LastCountry    string `json:"last_country,omitempty"`
	QuotaLimit     *int64 `json:"quota_limit,omitempty"`
	QuotaRemaining *int64 `json:"quota_remaining,omitempty"`
	QuotaUnlimited *bool  `json:"quota_unlimited,omitempty"`
	Subscribed     *bool  `json:"subscribed,omitempty"`
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

// Clear signs out (the last chosen country is kept) and wipes control-plane cookies.
func (a *Account) Clear() {
	a.mu.Lock()
	a.clearLocked()
	a.mu.Unlock()
	if a.cp != nil {
		a.cp.ClearCookies()
	}
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
	if t.ClientID != "" {
		a.st.ClientID = t.ClientID
	} else if a.st.ClientID == "" {
		a.st.ClientID = firefoxClientID
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

// Email returns the signed-in email stored in state.json.
func (a *Account) Email() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.st.Email
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

// CachedQuotaInfo returns the persisted 4-state QuotaInfo snapshot (Unknown before the first check).
func (a *Account) CachedQuotaInfo() QuotaInfo {
	a.mu.Lock()
	defer a.mu.Unlock()
	sub := a.st.Subscribed != nil && *a.st.Subscribed
	if a.st.QuotaUnlimited != nil && *a.st.QuotaUnlimited {
		return QuotaInfo{State: QuotaStateUnlimited, Subscribed: sub}
	}
	l, r := a.st.QuotaLimit, a.st.QuotaRemaining
	if l != nil && r != nil && *l > 0 && *r >= 0 && *r <= *l {
		lim, rem := *l, *r
		used := lim - rem
		return QuotaInfo{
			State:          QuotaStateLimited,
			Subscribed:     sub,
			LimitBytes:     &lim,
			RemainingBytes: &rem,
			UsedBytes:      &used,
		}
	}
	return QuotaInfo{State: QuotaStateUnknown, Subscribed: sub}
}

// SetQuotaInfo persists a resolved QuotaInfo into state.json.
func (a *Account) SetQuotaInfo(info QuotaInfo) {
	a.mu.Lock()
	defer a.mu.Unlock()
	sub := info.Subscribed
	a.st.Subscribed = &sub
	switch info.State {
	case QuotaStateUnlimited:
		t := true
		a.st.QuotaUnlimited = &t
		a.st.QuotaLimit = nil
		a.st.QuotaRemaining = nil
	case QuotaStateLimited:
		f := false
		a.st.QuotaUnlimited = &f
		if info.LimitBytes != nil && info.RemainingBytes != nil {
			lim, rem := *info.LimitBytes, *info.RemainingBytes
			a.st.QuotaLimit = &lim
			a.st.QuotaRemaining = &rem
		}
	}
	a.save()
}

// Quota returns the saved finite allowance (nil when unlimited or unknown).
func (a *Account) Quota() (limit, remaining *int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.st.QuotaUnlimited != nil && *a.st.QuotaUnlimited {
		return nil, nil
	}
	l, r := a.st.QuotaLimit, a.st.QuotaRemaining
	if l != nil && r != nil && *l > 0 && *r >= 0 && *r <= *l {
		li, ri := int(*l), int(*r)
		return &li, &ri
	}
	return nil, nil
}

// SetQuota saves a finite allowance; (nil, nil) clears finite numbers.
func (a *Account) SetQuota(limit, remaining *int) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if limit == nil || remaining == nil {
		a.st.QuotaLimit, a.st.QuotaRemaining = nil, nil
	} else {
		l, r := int64(*limit), int64(*remaining)
		f := false
		a.st.QuotaUnlimited = &f
		a.st.QuotaLimit, a.st.QuotaRemaining = &l, &r
	}
	a.save()
}

// AccessToken returns a valid access token, refreshing it when needed.
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
	cid := a.st.ClientID
	a.mu.Unlock()
	if rt == "" {
		return "", &NeedLoginError{Msg: "no refresh token; sign in again"}
	}
	data, err := a.Fxa.RefreshWithClient(ctx, rt, cid)
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
			"Content-Type":  "application/json",
			"Authorization": "Bearer " + access,
			"Cache-Control": "no-cache",
		}, body)
	}
	r, err := do()
	if err != nil {
		return nil, err
	}
	if r.Status == 406 {
		if err := a.cp.SolveChallenge(ctx); err != nil {
			return nil, err
		}
		r, err = do()
		if err != nil {
			return nil, err
		}
	}
	if r.Status == http.StatusUnavailableForLegalReasons {
		return nil, &RegionBlockedError{Exhausted: r.Header.Get(RelayRecoveryExhaustedHeader)}
	}
	return r, nil
}

func parseNonNegativeInt64(v any) (int64, bool) {
	switch x := v.(type) {
	case json.Number:
		if n, err := x.Int64(); err == nil && n >= 0 {
			return n, true
		}
		if f, err := x.Float64(); err == nil && f >= 0 {
			return int64(f), true
		}
	case float64:
		if x >= 0 {
			return int64(x), true
		}
	case string:
		s := strings.TrimSpace(x)
		if s != "" {
			if n, err := strconv.ParseInt(s, 10, 64); err == nil && n >= 0 {
				return n, true
			}
		}
	}
	return 0, false
}

func parseStrictBool(v any) (bool, bool) {
	switch x := v.(type) {
	case bool:
		return x, true
	case string:
		switch strings.ToLower(strings.TrimSpace(x)) {
		case "true":
			return true, true
		case "false":
			return false, true
		}
	}
	return false, false
}

func parseTokenQuotaHeaders(h http.Header) (unlimited bool, remaining, limit *int64, resetAt string) {
	if strings.EqualFold(strings.TrimSpace(h.Get("X-Quota-Unlimited")), "true") {
		return true, nil, nil, ""
	}
	resetAt = strings.TrimSpace(h.Get("X-Quota-Reset"))
	if n, ok := parseNonNegativeInt64(h.Get("X-Quota-Remaining")); ok {
		remaining = &n
	}
	if n, ok := parseNonNegativeInt64(h.Get("X-Quota-Limit")); ok && n > 0 {
		limit = &n
	}
	if limit != nil && remaining != nil && *remaining > *limit {
		remaining, limit = nil, nil
	}
	return false, remaining, limit, resetAt
}

type guardianStatusParsed struct {
	ok               bool
	subscribed       bool
	limitedBandwidth *bool
	unlimited        *bool
	maxBytes         *int64
	remainingBytes   *int64
	usedBytes        *int64
}

func parseGuardianStatusJSON(raw []byte) guardianStatusParsed {
	var out guardianStatusParsed
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var m map[string]any
	if dec.Decode(&m) != nil || len(m) == 0 {
		return out
	}
	out.ok = true
	if b, ok := parseStrictBool(m["subscribed"]); ok {
		out.subscribed = b
	}
	for _, k := range []string{"limited_bandwidth", "limitedBandwidth"} {
		if b, ok := parseStrictBool(m[k]); ok {
			out.limitedBandwidth = &b
			break
		}
	}
	for _, k := range []string{"unlimited", "isUnlimited", "unlimited_bandwidth"} {
		if b, ok := parseStrictBool(m[k]); ok {
			out.unlimited = &b
			break
		}
	}
	for _, k := range []string{"maxBytes", "max_bytes", "quotaLimit", "quota_limit"} {
		if n, ok := parseNonNegativeInt64(m[k]); ok && n > 0 {
			out.maxBytes = &n
			break
		}
	}
	for _, k := range []string{"remainingBytes", "remaining_bytes", "quotaRemaining", "quota_remaining"} {
		if n, ok := parseNonNegativeInt64(m[k]); ok {
			out.remainingBytes = &n
			break
		}
	}
	for _, k := range []string{"usedBytes", "used_bytes", "bytesUsed", "bytes_used"} {
		if n, ok := parseNonNegativeInt64(m[k]); ok {
			out.usedBytes = &n
			break
		}
	}
	return out
}

// FetchQuotaInfo queries Guardian (/api/v1/fpn/status + /api/v1/fpn/token) and resolves the
// authoritative 4-state QuotaInfo (Unlimited, Limited, Unknown, Error), matching Mozilla's
// GuardianTypes.sys.mjs and Android's GuardianClient.kt.
func (a *Account) FetchQuotaInfo(ctx context.Context) (QuotaInfo, error) {
	access, err := a.AccessToken(ctx, false)
	if err != nil {
		return QuotaInfo{State: QuotaStateError}, err
	}

	// 1. GET /api/v1/fpn/status (retrying once with refreshed token on 401/403).
	statusResp, statusErr := a.guardian(ctx, "GET", "/api/v1/fpn/status", access)
	if statusErr == nil && (statusResp.Status == 401 || statusResp.Status == 403) {
		if refreshed, rErr := a.AccessToken(ctx, true); rErr == nil {
			access = refreshed
			statusResp, statusErr = a.guardian(ctx, "GET", "/api/v1/fpn/status", access)
		}
	}
	var st guardianStatusParsed
	if statusErr == nil && statusResp != nil && statusResp.Status == 200 {
		st = parseGuardianStatusJSON(statusResp.Body)
	}

	// 2. HEAD /api/v1/fpn/token (falling back to GET /api/v1/fpn/token when headers are absent).
	var (
		tokUnlimited bool
		tokRem       *int64
		tokLim       *int64
		tokReset     string
		tokStatus    int
		tokErr       error
	)
	probeToken := func(method string) {
		r, e := a.guardian(ctx, method, "/api/v1/fpn/token", access)
		if e != nil {
			tokErr = e
			return
		}
		tokErr = nil
		tokStatus = r.Status
		tokUnlimited, tokRem, tokLim, tokReset = parseTokenQuotaHeaders(r.Header)
	}
	probeToken("HEAD")
	if tokErr == nil && !tokUnlimited && (tokLim == nil || tokRem == nil) && tokStatus != 401 && tokStatus != 403 {
		probeToken("GET")
	}
	if tokErr == nil && (tokStatus == 401 || tokStatus == 403) {
		// Activate Guardian entitlement if needed (matches MintPass), then retry once.
		_, _ = a.guardian(ctx, "POST", "/api/v1/fpn/activate", access)
		probeToken("HEAD")
		if tokErr == nil && !tokUnlimited && (tokLim == nil || tokRem == nil) {
			probeToken("GET")
		}
	}

	// If both endpoints failed with transport / region-block error, propagate it so UI shows Error state.
	if !st.ok && tokErr != nil {
		return QuotaInfo{State: QuotaStateError}, tokErr
	}
	if !st.ok && (tokStatus == 401 || tokStatus == 403) {
		return QuotaInfo{State: QuotaStateError}, &NeedLoginError{Msg: "Guardian rejected the access token"}
	}

	// 3. Unlimited resolution (GuardianTypes.sys.mjs + IPPUsageHelper.sys.mjs + Android GuardianClient.kt):
	//    - X-Quota-Unlimited: true on /api/v1/fpn/token, OR
	//    - status.limited_bandwidth == false, OR
	//    - status.unlimited == true, OR
	//    - status.subscribed == true && status.limited_bandwidth != true (ignoring nominal maxBytes=53687091200).
	isUnlimited := tokUnlimited
	if !isUnlimited && st.ok {
		if (st.limitedBandwidth != nil && !*st.limitedBandwidth) ||
			(st.unlimited != nil && *st.unlimited) ||
			(st.subscribed && (st.limitedBandwidth == nil || !*st.limitedBandwidth) && tokLim == nil) {
			isUnlimited = true
		}
	}
	if isUnlimited {
		info := QuotaInfo{
			State:      QuotaStateUnlimited,
			Subscribed: st.subscribed,
		}
		a.SetQuotaInfo(info)
		return info, nil
	}

	// 4. Finite Limited resolution from token headers or status JSON.
	if tokLim != nil && tokRem != nil && *tokLim > 0 && *tokRem >= 0 && *tokRem <= *tokLim {
		lim, rem := *tokLim, *tokRem
		used := lim - rem
		info := QuotaInfo{
			State:          QuotaStateLimited,
			Subscribed:     st.subscribed,
			LimitBytes:     &lim,
			RemainingBytes: &rem,
			UsedBytes:      &used,
			ResetAt:        tokReset,
		}
		a.SetQuotaInfo(info)
		return info, nil
	}
	if tokStatus == http.StatusTooManyRequests && st.maxBytes != nil && *st.maxBytes > 0 {
		lim := *st.maxBytes
		rem := int64(0)
		used := lim
		info := QuotaInfo{
			State:          QuotaStateLimited,
			Subscribed:     st.subscribed,
			LimitBytes:     &lim,
			RemainingBytes: &rem,
			UsedBytes:      &used,
			ResetAt:        tokReset,
		}
		a.SetQuotaInfo(info)
		return info, nil
	}
	if st.ok && st.maxBytes != nil && *st.maxBytes > 0 {
		lim := *st.maxBytes
		if st.remainingBytes != nil && *st.remainingBytes <= lim {
			rem := *st.remainingBytes
			used := lim - rem
			info := QuotaInfo{
				State:          QuotaStateLimited,
				Subscribed:     st.subscribed,
				LimitBytes:     &lim,
				RemainingBytes: &rem,
				UsedBytes:      &used,
			}
			a.SetQuotaInfo(info)
			return info, nil
		}
		if st.usedBytes != nil && *st.usedBytes <= lim {
			used := *st.usedBytes
			rem := lim - used
			info := QuotaInfo{
				State:          QuotaStateLimited,
				Subscribed:     st.subscribed,
				LimitBytes:     &lim,
				RemainingBytes: &rem,
				UsedBytes:      &used,
			}
			a.SetQuotaInfo(info)
			return info, nil
		}
	}

	// 5. Neither unlimited nor verifiable finite usage: Unknown (never invent 0 / 50 GB).
	return QuotaInfo{
		State:      QuotaStateUnknown,
		Subscribed: st.subscribed,
	}, nil
}

// FetchQuota wraps FetchQuotaInfo for callers expecting *QuotaUsage.
func (a *Account) FetchQuota(ctx context.Context) (*QuotaUsage, error) {
	info, err := a.FetchQuotaInfo(ctx)
	if err != nil {
		return nil, err
	}
	if info.State == QuotaStateLimited && info.LimitBytes != nil && info.RemainingBytes != nil {
		return &QuotaUsage{Limit: int(*info.LimitBytes), Remaining: int(*info.RemainingBytes)}, nil
	}
	return nil, nil
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
	unl, rem, limit, _ := parseTokenQuotaHeaders(r.Header)
	if unl {
		a.SetQuotaInfo(QuotaInfo{State: QuotaStateUnlimited, Subscribed: a.CachedQuotaInfo().Subscribed})
	} else if limit != nil && rem != nil {
		used := *limit - *rem
		a.SetQuotaInfo(QuotaInfo{
			State:          QuotaStateLimited,
			Subscribed:     a.CachedQuotaInfo().Subscribed,
			LimitBytes:     limit,
			RemainingBytes: rem,
			UsedBytes:      &used,
		})
	}
	return &ProxyPass{
		Token:          body.Token,
		Exp:            exp,
		QuotaRemaining: rem,
		QuotaLimit:     limit,
		QuotaUnlimited: unl,
	}, nil
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
