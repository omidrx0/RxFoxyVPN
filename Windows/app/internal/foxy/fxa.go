package foxy

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"
	"unicode"
)

const (
	// maxChallengeAttempts bounds how many times one FxA call re-solves the Fastly challenge.
	maxChallengeAttempts = 5
	// fxaChallengeBudget bounds automatic recovery after the first HTTP 406.
	fxaChallengeBudget = 90 * time.Second

	// FxA API error numbers from Mozilla's ecosystem-platform specification.
	fxaErrnoUnverifiedAccount  = 104
	fxaErrnoInvalidParameter   = 107
	fxaErrnoInvalidToken       = 108
	fxaErrnoThrottled          = 114
	fxaErrnoIncorrectEmailCase = 120
	fxaErrnoRequestBlocked     = 125
	fxaErrnoUnverifiedSession  = 138
	fxaErrnoServiceUnavailable = 201
)

// NormalizeEmail strips whitespace and invisible Unicode format characters (ZWNJ/ZWJ/bidi marks/BOM).
func NormalizeEmail(raw string) string {
	var b strings.Builder
	b.Grow(len(raw))
	for _, r := range strings.TrimSpace(raw) {
		if unicode.IsSpace(r) || unicode.In(r, unicode.Cf) {
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}

// NormalizeCode converts Persian (۰-۹) and Arabic-Indic (٠-٩) digits to ASCII 0-9
// and strips spaces, hyphens, and invisible Unicode format characters.
func NormalizeCode(raw string) string {
	var b strings.Builder
	b.Grow(len(raw))
	for _, r := range strings.TrimSpace(raw) {
		switch {
		case r >= '\u06F0' && r <= '\u06F9':
			b.WriteByte(byte('0' + (r - '\u06F0')))
		case r >= '\u0660' && r <= '\u0669':
			b.WriteByte(byte('0' + (r - '\u0660')))
		case r == '-' || unicode.IsSpace(r) || unicode.In(r, unicode.Cf):
			continue
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

// Fxa is the Firefox Accounts auth-server client.
type Fxa struct{ cp *ControlPlane }

// LoginResponse is the interesting part of POST /account/login.
type LoginResponse struct {
	SessionToken       string `json:"sessionToken"`
	Verified           bool   `json:"verified"`
	VerificationMethod string `json:"verificationMethod"`
	VerificationReason string `json:"verificationReason"`
}

// TokenResponse is an OAuth token answer.
type TokenResponse struct {
	AccessToken  string  `json:"access_token"`
	RefreshToken string  `json:"refresh_token"`
	ExpiresIn    float64 `json:"expires_in"`
	ClientID     string  `json:"client_id,omitempty"`
}

// RecoveryEmailStatusResponse is the response of GET /recovery_email/status.
type RecoveryEmailStatusResponse struct {
	Email    string `json:"email"`
	Verified bool   `json:"verified"`
}

// jsonCompact encodes v like Python json.dumps(separators=(",", ":")) (no HTML escaping).
func jsonCompact(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}

// alternateFunc can answer a request that drew HTTP 406 by another route. solved is the number of
// challenges solved so far. A nil response and nil error leave the challenge path available.
type alternateFunc func(ctx context.Context, solved int, hawkID string, hawkKey []byte) (*Response, error)

// oauthHeaders is the native Firefox OAuth request profile, sent on every OAuth grant.
func oauthHeaders() map[string]string {
	return map[string]string{
		"Accept":          "application/json;q=0.9,*/*;q=0.2",
		"Accept-Language": "en-US, en",
		"Content-Type":    "application/json; charset=utf-8",
		"Cache-Control":   "no-cache",
		"Pragma":          "no-cache",
		"Sec-Fetch-Dest":  "empty",
		"Sec-Fetch-Mode":  "no-cors",
		"Sec-Fetch-Site":  "none",
		"Priority":        "u=4",
	}
}

func apiErrorFrom(r *Response) error {
	if r.Status == http.StatusUnavailableForLegalReasons {
		return &RegionBlockedError{Exhausted: r.Header.Get(RelayRecoveryExhaustedHeader)}
	}
	var e struct {
		Message string `json:"message"`
		Errno   int    `json:"errno"`
	}
	var raw map[string]any
	_ = json.Unmarshal(r.Body, &e)
	_ = json.Unmarshal(r.Body, &raw)
	msg := e.Message
	if msg == "" {
		msg = r.Text()
	}
	if msg == "" {
		msg = fmt.Sprintf("HTTP %d", r.Status)
	}
	return &FxaAPIError{Message: msg, Errno: e.Errno, Status: r.Status, Body: raw}
}

// send runs one FxA request with bounded HTTP 406 recovery.
func (f *Fxa) send(ctx context.Context, newRequest func(ctx context.Context) (*Response, error),
	alternate alternateFunc, hawkID string, hawkKey []byte) (*Response, error) {
	r, err := newRequest(ctx)
	if err != nil || r.Status != 406 {
		return r, err
	}
	slog.Info("the account server answered HTTP 406 (Fastly bot check); starting recovery")
	budget := fxaChallengeBudget
	if allowsInteractiveCaptcha(ctx) {
		budget = interactiveCaptchaTimeout + 60*time.Second
	}
	rctx, cancel := context.WithTimeout(ctx, budget)
	defer cancel()
	for solved := 0; ; solved++ {
		if alternate != nil {
			if alt, err := alternate(rctx, solved, hawkID, hawkKey); alt != nil || err != nil {
				return alt, err
			}
		}
		if solved == maxChallengeAttempts {
			return nil, &FastlyChallengeError{Msg: fmt.Sprintf(
				"Mozilla's security check still answers HTTP 406 after %d solved challenges", solved)}
		}
		if err := f.cp.SolveChallenge(rctx); err != nil {
			var cancelErr CaptchaCancelledError
			if errors.As(err, &cancelErr) {
				return nil, err
			}
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			if rctx.Err() != nil {
				return nil, &FastlyChallengeError{Msg: "Mozilla's security check recovery timed out"}
			}
			return nil, err
		}
		if r, err = newRequest(rctx); err != nil {
			if ctx.Err() == nil && rctx.Err() != nil {
				return nil, &FastlyChallengeError{Msg: "Mozilla's security check recovery timed out"}
			}
			return nil, err
		}
		slog.Info("request retried after challenge solve", "solved", solved+1, "status", r.Status)
		if r.Status != 406 {
			return r, nil
		}
	}
}

// do sends one FxA request, signed with HAWK when a session token is given, and returns the
// body of a successful answer or the FxA error.
func (f *Fxa) do(ctx context.Context, method, path string, body any, sessionToken string) ([]byte, error) {
	return f.doCall(ctx, method, path, body, sessionToken, false, nil)
}

func (f *Fxa) doCall(ctx context.Context, method, path string, body any, sessionToken string,
	oauth bool, alternate alternateFunc) ([]byte, error) {
	rawURL := fxaAuthServer + path
	var payload []byte
	var err error
	if body != nil && !strings.EqualFold(method, http.MethodGet) && !strings.EqualFold(method, http.MethodHead) {
		payload, err = jsonCompact(body)
		if err != nil {
			return nil, err
		}
	}
	var hawkID string
	var hawkKey []byte
	if sessionToken != "" {
		hawkID, hawkKey, err = DeriveHawkCredentials(sessionToken)
		if err != nil {
			return nil, err
		}
	}
	newRequest := func(ctx context.Context) (*Response, error) {
		headers := map[string]string{"Accept": "application/json"}
		if payload != nil {
			headers["Content-Type"] = "application/json"
		}
		if oauth {
			headers = oauthHeaders()
		}
		if sessionToken != "" {
			auth, err := HawkHeader(method, rawURL, hawkID, hawkKey, payload)
			if err != nil {
				return nil, err
			}
			headers["Authorization"] = auth
		}
		return f.cp.Request(ctx, method, rawURL, headers, payload)
	}
	r, err := f.send(ctx, newRequest, alternate, hawkID, hawkKey)
	if err != nil {
		return nil, err
	}
	if r.Status >= 400 {
		return nil, apiErrorFrom(r)
	}
	return r.Body, nil
}

// exchangeSessionWithOAuthCode uses FxA's session-authenticated authorization-code grant (PKCE)
// when the direct session-to-token exchange is refused at the edge.
func (f *Fxa) exchangeSessionWithOAuthCode(ctx context.Context, hawkID string, hawkKey []byte) (*Response, error) {
	var secret [32]byte
	if _, err := rand.Read(secret[:]); err != nil {
		return nil, fmt.Errorf("generating OAuth code verifier: %w", err)
	}
	var stateBytes [16]byte
	if _, err := rand.Read(stateBytes[:]); err != nil {
		return nil, fmt.Errorf("generating OAuth state: %w", err)
	}
	verifier := base64.RawURLEncoding.EncodeToString(secret[:])
	challenge := sha256.Sum256([]byte(verifier))
	state := base64.RawURLEncoding.EncodeToString(stateBytes[:])

	authorizeURL := fxaAuthServer + "/oauth/authorization"
	authorizeBody, err := jsonCompact(map[string]string{
		"client_id":             firefoxClientID,
		"response_type":         "code",
		"scope":                 oauthScope,
		"state":                 state,
		"access_type":           "offline",
		"code_challenge":        base64.RawURLEncoding.EncodeToString(challenge[:]),
		"code_challenge_method": "S256",
	})
	if err != nil {
		return nil, err
	}
	auth, err := HawkHeader("POST", authorizeURL, hawkID, hawkKey, authorizeBody)
	if err != nil {
		return nil, fmt.Errorf("signing OAuth authorization request: %w", err)
	}
	headers := oauthHeaders()
	headers["Authorization"] = auth
	r, err := f.cp.Request(ctx, "POST", authorizeURL, headers, authorizeBody)
	if err != nil {
		return nil, fmt.Errorf("requesting OAuth authorization code: %w", err)
	}
	if r.Status == 406 {
		slog.Info("OAuth authorization returned HTTP 406")
		return nil, nil
	}
	if r.Status != 200 {
		return nil, oauthCodeError(r, "OAuth authorization")
	}
	var authorization struct {
		Code  string `json:"code"`
		State string `json:"state"`
	}
	if err := json.Unmarshal(r.Body, &authorization); err != nil || authorization.Code == "" || authorization.State != state {
		return nil, errors.New("invalid OAuth authorization response")
	}

	tokenBody, err := jsonCompact(map[string]string{
		"client_id":     firefoxClientID,
		"grant_type":    "authorization_code",
		"code":          authorization.Code,
		"code_verifier": verifier,
	})
	if err != nil {
		return nil, err
	}
	tokens, err := f.cp.Request(ctx, "POST", fxaOAuthTokenURL, oauthHeaders(), tokenBody)
	if err != nil {
		return nil, fmt.Errorf("exchanging OAuth authorization code: %w", err)
	}
	switch tokens.Status {
	case 200:
		slog.Info("OAuth authorization-code exchange accepted after HTTP 406")
		return tokens, nil
	case 406:
		return nil, nil
	}
	return nil, oauthCodeError(tokens, "OAuth authorization code exchange")
}

// oauthCodeError keeps the errno but never the body, which may carry a code or verifier.
func oauthCodeError(r *Response, step string) error {
	if r.Status == http.StatusUnavailableForLegalReasons {
		return &RegionBlockedError{Exhausted: r.Header.Get(RelayRecoveryExhaustedHeader)}
	}
	var data struct {
		Errno int `json:"errno"`
	}
	_ = json.Unmarshal(r.Body, &data)
	return &FxaAPIError{Message: step + " failed", Errno: data.Errno, Status: r.Status}
}

// refreshAtOAuthEndpoint repeats a refresh grant at Mozilla's OAuth endpoint.
func (f *Fxa) refreshAtOAuthEndpoint(ctx context.Context, payload []byte) (*Response, error) {
	r, err := f.cp.Request(ctx, "POST", fxaOAuthTokenURL, oauthHeaders(), payload)
	if err != nil {
		return nil, nil
	}
	switch r.Status {
	case 200:
		slog.Info("OAuth refresh accepted by Mozilla's OAuth endpoint")
		return r, nil
	case 406:
		return nil, nil
	case 451:
		return nil, &RegionBlockedError{Exhausted: r.Header.Get(RelayRecoveryExhaustedHeader)}
	case 400, 401, 403:
		errObj := apiErrorFrom(r)
		if apiErr, ok := errObj.(*FxaAPIError); ok && apiErr.Errno == fxaErrnoInvalidToken {
			apiErr.Message = "invalid refresh token"
			return nil, apiErr
		}
		return nil, errObj
	}
	return nil, fmt.Errorf("Mozilla's OAuth endpoint returned HTTP %d", r.Status)
}

func decode(raw []byte, out any) error {
	if len(bytes.TrimSpace(raw)) == 0 {
		return nil
	}
	return json.Unmarshal(raw, out)
}

// Login signs in with e-mail and password.
func (f *Fxa) Login(ctx context.Context, email, password string) (*LoginResponse, error) {
	return f.LoginWithUnblock(ctx, email, password, "")
}

// LoginWithUnblock signs in with e-mail, password, and an optional 8-character FxA unblockCode (errno 125).
func (f *Fxa) LoginWithUnblock(ctx context.Context, email, password, unblockCode string) (*LoginResponse, error) {
	cleanEmail := NormalizeEmail(email)
	cleanUnblock := strings.ToUpper(NormalizeCode(unblockCode))
	return f.login(ctx, cleanEmail, password, true, true, "", cleanUnblock)
}

func (f *Fxa) login(ctx context.Context, email, password string, withVerification, caseRetry bool, originalLoginEmail, unblockCode string) (*LoginResponse, error) {
	body := map[string]any{
		"email":  email,
		"authPW": DeriveAuthPW(email, password),
	}
	if originalLoginEmail != "" {
		body["originalLoginEmail"] = originalLoginEmail
	}
	if withVerification {
		body["verificationMethod"] = "email-2fa"
	}
	if unblockCode != "" {
		body["unblockCode"] = unblockCode
	}
	raw, err := f.do(ctx, "POST", "/account/login", body, "")
	if err != nil {
		if e, ok := err.(*FxaAPIError); ok {
			if e.Errno == fxaErrnoInvalidParameter && withVerification {
				return f.login(ctx, email, password, false, caseRetry, originalLoginEmail, unblockCode)
			}
			if e.Errno == fxaErrnoIncorrectEmailCase && caseRetry {
				if corrected, _ := e.Body["email"].(string); corrected != "" {
					return f.login(ctx, NormalizeEmail(corrected), password, withVerification, false, email, unblockCode)
				}
			}
		}
		return nil, err
	}
	var out LoginResponse
	if err := decode(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// SendUnblockCode asks FxA to email an 8-character security unblock code (errno 125 recovery).
func (f *Fxa) SendUnblockCode(ctx context.Context, email string) error {
	cleanEmail := NormalizeEmail(email)
	_, err := f.do(ctx, "POST", "/account/login/send_unblock_code", map[string]any{"email": cleanEmail}, "")
	return err
}

// ResendVerificationCode asks FxA to resend the session confirmation email code.
func (f *Fxa) ResendVerificationCode(ctx context.Context, sessionToken string) error {
	_, err := f.do(ctx, "POST", "/session/resend_code", map[string]any{}, sessionToken)
	return err
}

// VerifyCode confirms the e-mail or TOTP 2FA code of a pending sign-in.
func (f *Fxa) VerifyCode(ctx context.Context, sessionToken, code, verificationMethod string) error {
	cleanCode := NormalizeCode(code)
	path := "/session/verify_code"
	if verificationMethod == "totp-2fa" {
		path = "/session/verify/totp"
	}
	_, err := f.do(ctx, "POST", path, map[string]any{"code": cleanCode}, sessionToken)
	return err
}

// RecoveryEmailStatus queries GET /recovery_email/status for a sessionToken (used by Browser Sign-in).
func (f *Fxa) RecoveryEmailStatus(ctx context.Context, sessionToken string) (*RecoveryEmailStatusResponse, error) {
	raw, err := f.do(ctx, "GET", "/recovery_email/status", nil, sessionToken)
	if err != nil {
		return nil, err
	}
	var out RecoveryEmailStatusResponse
	if err := decode(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// OAuthFromSession exchanges a session token for OAuth tokens with the VPN scope.
func (f *Fxa) OAuthFromSession(ctx context.Context, sessionToken string) (*TokenResponse, error) {
	raw, err := f.doCall(ctx, "POST", "/oauth/token", map[string]any{
		"client_id": firefoxClientID, "grant_type": "fxa-credentials",
		"scope": oauthScope, "access_type": "offline"}, sessionToken, true,
		func(ctx context.Context, _ int, hawkID string, hawkKey []byte) (*Response, error) {
			return f.exchangeSessionWithOAuthCode(ctx, hawkID, hawkKey)
		})
	if err != nil {
		return nil, err
	}
	var out TokenResponse
	if err := decode(raw, &out); err != nil {
		return nil, err
	}
	out.ClientID = firefoxClientID
	return &out, nil
}

// ExchangeOAuthAuthorizationCode exchanges a public OAuth + PKCE authorization code for tokens.
func (f *Fxa) ExchangeOAuthAuthorizationCode(ctx context.Context, clientID, code, verifier string) (*TokenResponse, error) {
	if clientID == "" {
		clientID = firefoxPublicOAuthClientID
	}
	payload, err := jsonCompact(map[string]string{
		"client_id":     clientID,
		"grant_type":    "authorization_code",
		"code":          code,
		"code_verifier": verifier,
	})
	if err != nil {
		return nil, err
	}
	r, err := f.send(ctx, func(c context.Context) (*Response, error) {
		return f.cp.Request(c, "POST", fxaOAuthTokenURL, oauthHeaders(), payload)
	}, nil, "", nil)
	if err != nil {
		return nil, err
	}
	if r.Status >= 400 {
		return nil, oauthCodeError(r, "OAuth code exchange")
	}
	var out TokenResponse
	if err := decode(r.Body, &out); err != nil {
		return nil, err
	}
	out.ClientID = clientID
	return &out, nil
}

// FetchProfileEmail queries Mozilla's profile endpoint for the account email using an OAuth access token.
func (f *Fxa) FetchProfileEmail(ctx context.Context, accessToken string) string {
	if strings.TrimSpace(accessToken) == "" {
		return ""
	}
	r, err := f.cp.Request(ctx, "GET", fxaProfileURL, map[string]string{
		"Accept":        "application/json",
		"Authorization": "Bearer " + accessToken,
	}, nil)
	if err != nil || r.Status != 200 {
		return ""
	}
	var prof struct {
		Email string `json:"email"`
	}
	if json.Unmarshal(r.Body, &prof) != nil {
		return ""
	}
	return NormalizeEmail(prof.Email)
}

// DestroySession closes the temporary FxA session after OAuth credentials have been issued.
func (f *Fxa) DestroySession(ctx context.Context, sessionToken string) error {
	_, err := f.do(ctx, "POST", "/session/destroy", map[string]any{}, sessionToken)
	return err
}

// Refresh gets a new access token from a refresh token using the default Firefox Desktop client ID.
func (f *Fxa) Refresh(ctx context.Context, refreshToken string) (*TokenResponse, error) {
	return f.RefreshWithClient(ctx, refreshToken, firefoxClientID)
}

// RefreshWithClient gets a new access token using the OAuth client ID that issued refreshToken.
func (f *Fxa) RefreshWithClient(ctx context.Context, refreshToken, clientID string) (*TokenResponse, error) {
	if strings.TrimSpace(clientID) == "" {
		clientID = firefoxClientID
	}
	body := map[string]any{
		"client_id":     clientID,
		"grant_type":    "refresh_token",
		"refresh_token": refreshToken,
	}
	if clientID == firefoxClientID {
		body["scope"] = oauthScope
	}
	payload, err := jsonCompact(body)
	if err != nil {
		return nil, err
	}
	if clientID != firefoxClientID {
		// Non-desktop public OAuth clients refresh directly against Mozilla's OAuth server.
		r, err := f.send(ctx, func(c context.Context) (*Response, error) {
			return f.cp.Request(c, "POST", fxaOAuthTokenURL, oauthHeaders(), payload)
		}, nil, "", nil)
		if err != nil {
			return nil, err
		}
		if r.Status >= 400 {
			return nil, apiErrorFrom(r)
		}
		var out TokenResponse
		if err := decode(r.Body, &out); err != nil {
			return nil, err
		}
		out.ClientID = clientID
		return &out, nil
	}

	raw, err := f.doCall(ctx, "POST", "/oauth/token", body, "", true,
		func(ctx context.Context, solved int, _ string, _ []byte) (*Response, error) {
			if solved != 0 {
				return nil, nil
			}
			return f.refreshAtOAuthEndpoint(ctx, payload)
		})
	if err != nil {
		return nil, err
	}
	var out TokenResponse
	if err := decode(raw, &out); err != nil {
		return nil, err
	}
	out.ClientID = clientID
	return &out, nil
}
