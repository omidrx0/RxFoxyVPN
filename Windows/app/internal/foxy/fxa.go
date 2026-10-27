package foxy

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
)

// Fxa is the Firefox Accounts auth-server client.
type Fxa struct{ cp *ControlPlane }

// LoginResponse is the interesting part of POST /account/login.
type LoginResponse struct {
	SessionToken string `json:"sessionToken"`
	Verified     bool   `json:"verified"`
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

// do sends one FxA request, signed with HAWK when a session token is given. A 406 answer means
// the Fastly challenge is in the way: it is solved and the request repeated (up to 5 tries).
func (f *Fxa) do(ctx context.Context, method, path string, body any, sessionToken string) ([]byte, error) {
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
	attempt := func() (*Response, error) {
		headers := map[string]string{"Content-Type": "application/json"}
		if sessionToken != "" {
			auth, err := HawkHeader(method, rawURL, hawkID, hawkKey, payload)
			if err != nil {
				return nil, err
			}
			headers["Authorization"] = auth
		}
		return f.cp.Request(ctx, method, rawURL, headers, payload)
	}

	r, err := attempt()
	if err != nil {
		return nil, err
	}
	for tries := 1; r.Status == 406 && tries < 5; tries++ {
		if err := f.cp.SolveChallenge(ctx); err != nil {
			return nil, err
		}
		if r, err = attempt(); err != nil {
			return nil, err
		}
	}
	text := r.Text()
	if r.Status >= 400 {
		var e struct {
			Message string `json:"message"`
			Errno   int    `json:"errno"`
		}
		var raw map[string]any
		_ = json.Unmarshal(r.Body, &e)
		_ = json.Unmarshal(r.Body, &raw)
		msg := e.Message
		if msg == "" {
			msg = text
		}
		if msg == "" {
			msg = fmt.Sprintf("HTTP %d", r.Status)
		}
		return nil, &FxaAPIError{Message: msg, Errno: e.Errno, Status: r.Status, Body: raw}
	}
	return r.Body, nil
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
	return f.login(ctx, email, password, true, true)
}

func (f *Fxa) login(ctx context.Context, email, password string, withVerification, caseRetry bool) (*LoginResponse, error) {
	body := map[string]any{"email": email, "authPW": DeriveAuthPW(email, password)}
	if withVerification {
		body["verificationMethod"] = "email-2fa"
	}
	raw, err := f.do(ctx, "POST", "/account/login", body, "")
	if err != nil {
		if e, ok := err.(*FxaAPIError); ok {
			if e.Errno == 107 && withVerification {
				return f.login(ctx, email, password, false, caseRetry)
			}
			if e.Errno == 120 && caseRetry {
				if corrected, _ := e.Body["email"].(string); corrected != "" { // wrong e-mail letter case
					return f.login(ctx, corrected, password, withVerification, false)
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
func (f *Fxa) VerifyCode(ctx context.Context, sessionToken, code string) error {
	_, err := f.do(ctx, "POST", "/session/verify_code", map[string]any{"code": code}, sessionToken)
	return err
}

// OAuthFromSession exchanges a session token for OAuth tokens with the VPN scope.
func (f *Fxa) OAuthFromSession(ctx context.Context, sessionToken string) (*TokenResponse, error) {
	raw, err := f.do(ctx, "POST", "/oauth/token", map[string]any{
		"client_id": firefoxClientID, "grant_type": "fxa-credentials",
		"scope": oauthScope, "access_type": "offline"}, sessionToken)
	if err != nil {
		return nil, err
	}
	var out TokenResponse
	if err := decode(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// Refresh gets a new access token from a refresh token.
func (f *Fxa) Refresh(ctx context.Context, refreshToken string) (*TokenResponse, error) {
	raw, err := f.do(ctx, "POST", "/oauth/token", map[string]any{
		"client_id": firefoxClientID, "grant_type": "refresh_token",
		"refresh_token": refreshToken, "scope": oauthScope}, "")
	if err != nil {
		return nil, err
	}
	var out TokenResponse
	if err := decode(raw, &out); err != nil {
		return nil, err
	}
	return &out, nil
}
