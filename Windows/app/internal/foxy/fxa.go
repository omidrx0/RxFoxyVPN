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
	"time"
)

const (
	// maxChallengeAttempts bounds how many times one FxA call re-solves the Fastly challenge.
	maxChallengeAttempts = 5
	// fxaChallengeBudget bounds the whole recovery after the first HTTP 406 (alternate
	// requests, solving, and the final retry).
	fxaChallengeBudget = 90 * time.Second
	// fxaErrnoInvalidToken is FxA's "invalid token" errno.
	fxaErrnoInvalidToken = 108
)

// Fxa is the Firefox Accounts auth-server client.
type Fxa struct{ cp *ControlPlane }

// LoginResponse is the interesting part of POST /account/login.
type LoginResponse struct {
	SessionToken       string `json:"sessionToken"`
	Verified           bool   `json:"verified"`
	VerificationMethod string `json:"verificationMethod"`
}

// TokenResponse is an OAuth token answer.
type TokenResponse struct {
	AccessToken  string  `json:"access_token"`
	RefreshToken string  `json:"refresh_token"`
	ExpiresIn    float64 `json:"expires_in"`
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

func apiErrorFrom(r *Response) *FxaAPIError {
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

// send runs one FxA request with the HTTP 406 recovery: on 406 (Fastly's bot check, empty body)
// the alternate route is tried first, then the challenge is solved and the request repeated
// (a fresh request, a fresh HAWK nonce each time), bounded by maxChallengeAttempts and one
// overall budget. Responses are fully read, so nothing is left open between attempts.
func (f *Fxa) send(ctx context.Context, newRequest func(ctx context.Context) (*Response, error),
	alternate alternateFunc, hawkID string, hawkKey []byte) (*Response, error) {
	r, err := newRequest(ctx)
	if err != nil || r.Status != 406 {
		return r, err
	}
	slog.Info("the account server answered HTTP 406 (Fastly bot check); starting recovery")
	rctx, cancel := context.WithTimeout(ctx, fxaChallengeBudget)
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
	payload, err := jsonCompact(body)
	if err != nil {
		return nil, err
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
		headers := map[string]string{"Content-Type": "application/json"}
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
// when the direct session-to-token exchange is refused at the edge. It returns the token
// response, or nil when the edge refuses this route too.
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
		return nil, nil // let the original request use its challenge flow
	}
	return nil, oauthCodeError(tokens, "OAuth authorization code exchange")
}

// oauthCodeError keeps the errno but never the body, which may carry a code or verifier.
func oauthCodeError(r *Response, step string) error {
	var data struct {
		Errno int `json:"errno"`
	}
	_ = json.Unmarshal(r.Body, &data)
	return &FxaAPIError{Message: step + " failed", Errno: data.Errno, Status: r.Status}
}

// refreshAtOAuthEndpoint repeats a refresh grant at Mozilla's OAuth endpoint after the API host
// answered 406. Both endpoints share one refresh-token store. A nil response leaves the
// challenge path available.
func (f *Fxa) refreshAtOAuthEndpoint(ctx context.Context, payload []byte) (*Response, error) {
	r, err := f.cp.Request(ctx, "POST", fxaOAuthTokenURL, oauthHeaders(), payload)
	if err != nil {
		return nil, nil
	}
	switch r.Status {
	case 200:
		slog.Info("OAuth refresh accepted by Mozilla's OAuth endpoint after HTTP 406")
		return r, nil
	case 406:
		return nil, nil
	case 400:
		apiErr := apiErrorFrom(r)
		if apiErr.Errno == fxaErrnoInvalidToken {
			apiErr.Message = "invalid refresh token"
			return nil, apiErr
		}
	}
	return nil, fmt.Errorf("Mozilla's OAuth endpoint returned HTTP %d", r.Status)
}

func decode(raw []byte, out any) error {
	if len(bytes.TrimSpace(raw)) == 0 {
		return nil
	}
	return json.Unmarshal(raw, out)
}

// Login signs in with e-mail and password. When the server asks for e-mail 2FA the answer has
// Verified == false and the code must be sent with VerifyCode.
func (f *Fxa) Login(ctx context.Context, email, password string) (*LoginResponse, error) {
	return f.login(ctx, email, password, true, true, "")
}

func (f *Fxa) login(ctx context.Context, email, password string, withVerification, caseRetry bool, originalLoginEmail string) (*LoginResponse, error) {
	body := map[string]any{"email": email, "authPW": DeriveAuthPW(email, password)}
	if originalLoginEmail != "" {
		body["originalLoginEmail"] = originalLoginEmail
	}
	if withVerification {
		body["verificationMethod"] = "email-2fa"
	}
	raw, err := f.do(ctx, "POST", "/account/login", body, "")
	if err != nil {
		if e, ok := err.(*FxaAPIError); ok {
			if e.Errno == 107 && withVerification {
				return f.login(ctx, email, password, false, caseRetry, originalLoginEmail)
			}
			if e.Errno == 120 && caseRetry {
				if corrected, _ := e.Body["email"].(string); corrected != "" { // wrong e-mail letter case
					return f.login(ctx, corrected, password, withVerification, false, email)
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

// VerifyCode confirms the e-mail code of a pending sign-in.
func (f *Fxa) VerifyCode(ctx context.Context, sessionToken, code, verificationMethod string) error {
	path := "/session/verify_code"
	if verificationMethod == "totp-2fa" {
		path = "/session/verify/totp"
	}
	_, err := f.do(ctx, "POST", path, map[string]any{"code": code}, sessionToken)
	return err
}

// OAuthFromSession exchanges a session token for OAuth tokens with the VPN scope. When the edge
// answers 406 even after solving, the session-authenticated authorization-code route is tried.
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
	return &out, nil
}

// DestroySession closes the temporary FxA session after OAuth credentials have
// been issued. A caller may treat failure as non-fatal because the OAuth tokens
// are already independent of this temporary session.
func (f *Fxa) DestroySession(ctx context.Context, sessionToken string) error {
	_, err := f.do(ctx, "POST", "/session/destroy", map[string]any{}, sessionToken)
	return err
}

// Refresh gets a new access token from a refresh token.
func (f *Fxa) Refresh(ctx context.Context, refreshToken string) (*TokenResponse, error) {
	body := map[string]any{
		"client_id": firefoxClientID, "grant_type": "refresh_token",
		"refresh_token": refreshToken, "scope": oauthScope}
	payload, err := jsonCompact(body)
	if err != nil {
		return nil, err
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
	return &out, nil
}
