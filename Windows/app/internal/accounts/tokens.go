package accounts

import "time"

// SavedTokens is the OAuth triple plus its issuance context. obtained_at is stored in the
// token's issuer wall-clock (RFC3339Nano UTC); expires_in and expires_at are seconds.
type SavedTokens struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	Scope        string `json:"scope,omitempty"`
	ObtainedAt   string `json:"obtained_at"`
	ExpiresIn    int64  `json:"expires_in,omitempty"`
	ExpiresAt    int64  `json:"expires_at,omitempty"`
	ClientID     string `json:"client_id,omitempty"`
	TokenType    string `json:"token_type,omitempty"`
}

// TokensFromLogin builds SavedTokens for a fresh login response (expires_in seconds from now).
func TokensFromLogin(access, refresh, clientID, scope, tokenType string, expiresIn int64, now time.Time) *SavedTokens {
	if now.IsZero() {
		now = time.Now()
	}
	if expiresIn <= 0 {
		expiresIn = 3600
	}
	return &SavedTokens{
		AccessToken:  access,
		RefreshToken: refresh,
		Scope:        scope,
		ClientID:     clientID,
		TokenType:    tokenType,
		ObtainedAt:   now.UTC().Format(time.RFC3339Nano),
		ExpiresIn:    expiresIn,
		ExpiresAt:    now.UTC().Unix() + expiresIn,
	}
}

// UsableForConnect reports whether the tokens can attempt a Guardian call at all
// (a refresh token must exist; access token may be expired — the runtime refreshes it).
func (t *SavedTokens) UsableForConnect() bool {
	return t != nil && t.RefreshToken != ""
}

// AccessFresh reports whether the access token is safe to use without a refresh.
func (t *SavedTokens) AccessFresh(now time.Time) bool {
	if t.AccessToken == "" || t.ExpiresAt == 0 {
		return false
	}
	return now.Unix()+60 < t.ExpiresAt
}

