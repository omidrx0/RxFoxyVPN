package foxy

import (
	"context"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"
)

const (
	// WebChannelTrustedOrigin is the sole origin allowed to speak over the FxA WebChannel bridge.
	WebChannelTrustedOrigin = "https://accounts.firefox.com"
	// WebChannelID is Firefox Desktop Sync's WebChannel ID expected by accounts.firefox.com.
	WebChannelID = "account_updates"

	browserConfirmTimeout  = 5 * time.Minute
	browserPollInterval    = 2 * time.Second
	browserExchangeRetries = 5
)

var browserSignInAllowedDomains = []string{
	"firefox.com",
	"mozilla.com",
	"mozilla.org",
	"firefoxusercontent.com",
	"fastly.net",
}

// IsTrustedBridgeOrigin reports whether origin equals https://accounts.firefox.com (scheme+host+default port).
func IsTrustedBridgeOrigin(rawOrigin string) bool {
	trimmed := strings.TrimSuffix(strings.TrimSpace(rawOrigin), "/")
	u, err := url.Parse(trimmed)
	if err != nil {
		return false
	}
	if !strings.EqualFold(u.Scheme, "https") || u.User != nil {
		return false
	}
	if p := u.Port(); p != "" && p != "443" {
		return false
	}
	return strings.EqualFold(u.Hostname(), "accounts.firefox.com")
}

// IsBrowserSignInNavigationAllowed mirrors Android's BrowserSignInPolicy.isNavigationAllowed:
// HTTPS on port 443, no userinfo, and host within Mozilla's sign-in / CDN domains (plus about:blank).
func IsBrowserSignInNavigationAllowed(rawURL string) bool {
	trimmed := strings.TrimSpace(rawURL)
	if strings.EqualFold(trimmed, "about:blank") {
		return true
	}
	u, err := url.Parse(trimmed)
	if err != nil || !isEffectiveHTTPS443(u) || u.User != nil {
		return false
	}
	host := strings.ToLower(strings.TrimSuffix(u.Hostname(), "."))
	if host == "" {
		return false
	}
	for _, d := range browserSignInAllowedDomains {
		if host == d || strings.HasSuffix(host, "."+d) {
			return true
		}
	}
	return false
}

// IsValidSessionTokenHex checks whether token is a 64-character hexadecimal FxA sessionToken.
func IsValidSessionTokenHex(token string) bool {
	if len(token) != 64 {
		return false
	}
	_, err := hex.DecodeString(token)
	return err == nil
}

// WebChannelLoginURL builds the Mozilla FxA WebChannel sign-in URL matching Android's FxaWebChannel.loginUrl.
func WebChannelLoginURL(email string) string {
	u, _ := url.Parse(WebChannelTrustedOrigin + "/")
	q := url.Values{}
	q.Set("context", "fx_desktop_v3")
	q.Set("service", "sync")
	q.Set("action", "email")
	if clean := NormalizeEmail(email); clean != "" {
		q.Set("email", clean)
	}
	u.RawQuery = q.Encode()
	return u.String()
}

// WebChannelHookJS is injected at document creation in the isolated WebView2 sign-in window.
// It activates strictly on https://accounts.firefox.com and forwards WebChannelMessageToChrome
// events to the bound Go handler __rxFxaBridge(rawDetailString).
const WebChannelHookJS = `(function(){
  if (window.__rxFxaHooked) return;
  if (!window.location || window.location.origin !== 'https://accounts.firefox.com') return;
  window.__rxFxaHooked = true;
  window.addEventListener('WebChannelMessageToChrome', function(e) {
    try {
      var d = e && e.detail;
      if (d == null || typeof window.__rxFxaBridge !== 'function') return;
      window.__rxFxaBridge(typeof d === 'string' ? d : JSON.stringify(d));
    } catch (_) {}
  }, true);
})();`

// WebChannelReplyJS builds the JavaScript snippet that dispatches a WebChannelMessageToContent
// response strictly when the page is still on https://accounts.firefox.com.
func WebChannelReplyJS(command, messageID string, data map[string]any) (string, error) {
	detail := map[string]any{
		"id": WebChannelID,
		"message": map[string]any{
			"command":   command,
			"messageId": messageID,
			"data":      data,
		},
	}
	encoded, err := json.Marshal(detail)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf(
		`(function(){if(window.location&&window.location.origin==='https://accounts.firefox.com'){window.dispatchEvent(new CustomEvent('WebChannelMessageToContent',{detail:%s}));}})();`,
		string(encoded),
	), nil
}

// WebChannelResult describes what HandleWebChannelMessage extracted from a page event.
type WebChannelResult struct {
	ReplyJS      string
	SessionToken string
	Email        string
}

// HandleWebChannelMessage processes one WebChannelMessageToChrome JSON string (matching FxaWebChannel.kt).
// It never logs raw payloads, emails, or session tokens.
func HandleWebChannelMessage(raw string) (WebChannelResult, error) {
	var outer struct {
		ID      string          `json:"id"`
		Message json.RawMessage `json:"message"`
	}
	if err := json.Unmarshal([]byte(raw), &outer); err != nil {
		return WebChannelResult{}, err
	}
	if outer.ID != WebChannelID || len(outer.Message) == 0 {
		return WebChannelResult{}, nil
	}
	var msg struct {
		Command   string         `json:"command"`
		MessageID string         `json:"messageId"`
		Data      map[string]any `json:"data"`
	}
	if err := json.Unmarshal(outer.Message, &msg); err != nil {
		return WebChannelResult{}, err
	}

	switch msg.Command {
	case "fxaccounts:fxa_status":
		js, err := WebChannelReplyJS(msg.Command, msg.MessageID, map[string]any{
			"signedInUser": nil,
			"clientId":     firefoxClientID,
			"capabilities": map[string]any{
				"choose_what_to_sync": true,
				"multiService":        false,
				"pairing":             false,
				"engines":             []string{},
			},
		})
		return WebChannelResult{ReplyJS: js}, err

	case "fxaccounts:can_link_account":
		js, err := WebChannelReplyJS(msg.Command, msg.MessageID, map[string]any{"ok": true})
		return WebChannelResult{ReplyJS: js}, err

	case "fxaccounts:login", "fxaccounts:oauth_login":
		tok, _ := msg.Data["sessionToken"].(string)
		email, _ := msg.Data["email"].(string)
		var reply string
		if msg.MessageID != "" {
			reply, _ = WebChannelReplyJS(msg.Command, msg.MessageID, map[string]any{})
		}
		if IsValidSessionTokenHex(tok) {
			return WebChannelResult{
				ReplyJS:      reply,
				SessionToken: strings.ToLower(tok),
				Email:        NormalizeEmail(email),
			}, nil
		}
		return WebChannelResult{ReplyJS: reply}, nil

	default:
		if msg.MessageID != "" {
			js, err := WebChannelReplyJS(msg.Command, msg.MessageID, map[string]any{})
			return WebChannelResult{ReplyJS: js}, err
		}
		return WebChannelResult{}, nil
	}
}

// BrowserLoginStage mirrors Android's BrowserLoginStage.
type BrowserLoginStage string

const (
	BrowserStageIdle                   BrowserLoginStage = "idle"
	BrowserStageAwaitingBrowser        BrowserLoginStage = "awaiting_browser"
	BrowserStageChecking               BrowserLoginStage = "checking"
	BrowserStageWaitingForConfirmation BrowserLoginStage = "waiting_for_confirmation"
	BrowserStageGettingTokens          BrowserLoginStage = "getting_tokens"
	BrowserStageSucceeded              BrowserLoginStage = "succeeded"
	BrowserStageFailed                 BrowserLoginStage = "failed"
	BrowserStageCancelled              BrowserLoginStage = "cancelled"
)

// CompleteBrowserSession exchanges a WebChannel sessionToken into OAuth credentials after
// polling GET /recovery_email/status until verified == true (matching Android's completeBrowserLogin).
func (a *Account) CompleteBrowserSession(
	ctx context.Context,
	sessionToken string,
	hintEmail string,
	onStage func(BrowserLoginStage),
) (string, error) {
	sessionToken = strings.ToLower(strings.TrimSpace(sessionToken))
	if !IsValidSessionTokenHex(sessionToken) {
		return "", errors.New("Mozilla returned an invalid session token")
	}
	if onStage != nil {
		onStage(BrowserStageChecking)
	}

	confirmCtx, cancel := context.WithTimeout(ctx, browserConfirmTimeout)
	defer cancel()

	announcedWait := false
	resolvedEmail := NormalizeEmail(hintEmail)

	for {
		status, err := a.Fxa.RecoveryEmailStatus(confirmCtx, sessionToken)
		if err == nil && status != nil {
			if status.Email != "" {
				resolvedEmail = NormalizeEmail(status.Email)
			}
			if status.Verified {
				break
			}
		} else if err != nil {
			var apiErr *FxaAPIError
			if errors.As(err, &apiErr) {
				if apiErr.Errno != 0 && apiErr.Errno != fxaErrnoThrottled && apiErr.Errno != fxaErrnoServiceUnavailable {
					return "", err
				}
			} else if confirmCtx.Err() != nil {
				if ctx.Err() != nil {
					return "", ctx.Err()
				}
				return "", &FxaAPIError{Message: "Timed out waiting for Mozilla to confirm the sign-in", Status: 408}
			}
		}

		if !announcedWait {
			announcedWait = true
			if onStage != nil {
				onStage(BrowserStageWaitingForConfirmation)
			}
		}
		select {
		case <-confirmCtx.Done():
			if ctx.Err() != nil {
				return "", ctx.Err()
			}
			return "", &FxaAPIError{Message: "Timed out waiting for Mozilla to confirm the sign-in", Status: 408}
		case <-time.After(browserPollInterval):
		}
	}

	if onStage != nil {
		onStage(BrowserStageGettingTokens)
	}

	var tokens *TokenResponse
	var lastErr error
	for attempt := 0; attempt < browserExchangeRetries; attempt++ {
		tokens, lastErr = a.Fxa.OAuthFromSession(ctx, sessionToken)
		if lastErr == nil {
			break
		}
		var apiErr *FxaAPIError
		if errors.As(lastErr, &apiErr) &&
			(apiErr.Errno == fxaErrnoUnverifiedAccount || apiErr.Errno == fxaErrnoUnverifiedSession) &&
			attempt+1 < browserExchangeRetries {
			select {
			case <-ctx.Done():
				return "", ctx.Err()
			case <-time.After(browserPollInterval):
				continue
			}
		}
		return "", lastErr
	}
	if err := a.StoreTokens(tokens); err != nil {
		return "", err
	}
	if resolvedEmail == "" {
		resolvedEmail = a.Fxa.FetchProfileEmail(ctx, tokens.AccessToken)
	}
	if resolvedEmail != "" {
		a.SetEmail(resolvedEmail)
	}
	if dErr := a.Fxa.DestroySession(ctx, sessionToken); dErr != nil {
		// Non-fatal: OAuth tokens are already stored independently.
		_ = dErr
	}
	return resolvedEmail, nil
}
