package foxy

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// FxaAPIError is an error answer of the Firefox Accounts API.
type FxaAPIError struct {
	Message string
	Errno   int // 0 when the server sent none
	Status  int
	Body    map[string]any
}

func (e *FxaAPIError) Error() string { return e.Message }

// NeedLoginError means the stored session is gone or was rejected: the user must sign in again.
type NeedLoginError struct{ Msg string }

func (e *NeedLoginError) Error() string { return e.Msg }

// QuotaExceededError means the monthly Firefox VPN allowance is used up.
// ResetAt is the raw X-Quota-Reset header (RFC3339 or unix seconds), used by the pool failover.
type QuotaExceededError struct {
	Msg     string
	ResetAt string
}

func (e *QuotaExceededError) Error() string { return e.Msg }

// RetryAt parses the reset hint (RFC3339 or unix seconds). Zero time when absent/unparsable.
func (e *QuotaExceededError) RetryAt() time.Time {
	raw := strings.TrimSpace(e.ResetAt)
	if raw == "" {
		return time.Time{}
	}
	var n int64
	if _, err := fmt.Sscanf(raw, "%d", &n); err == nil && n > 0 {
		if n > 1_000_000_000_000 { // millis
			return time.UnixMilli(n)
		}
		return time.Unix(n, 0).UTC()
	}
	if t, err := time.Parse(time.RFC3339Nano, raw); err == nil {
		return t
	}
	if t, err := time.Parse(time.RFC3339, raw); err == nil {
		return t
	}
	return time.Time{}
}

// FastlyChallengeError means the Fastly anti-bot challenge could not be solved.
type FastlyChallengeError struct{ Msg string }

func (e *FastlyChallengeError) Error() string { return e.Msg }

// UpstreamError is a problem with the tunnel servers or the Mozilla back end.
type UpstreamError struct{ Msg string }

func (e *UpstreamError) Error() string { return e.Msg }

// UpstreamHTTPError: the edge server answered CONNECT with a non-2xx status.
type UpstreamHTTPError struct{ Status int }

func (e *UpstreamHTTPError) Error() string {
	return fmt.Sprintf("upstream answered CONNECT with HTTP %d", e.Status)
}

// ConnectionLostError: the HTTP/2 connection died.
type ConnectionLostError struct{ Msg string }

func (e *ConnectionLostError) Error() string { return e.Msg }

// RegionBlockedError indicates HTTP 451 (Unavailable For Legal Reasons) after relay recovery.
type RegionBlockedError struct {
	Exhausted string // "none", "personal", "shared", or "all-failed"
}

func (e *RegionBlockedError) Error() string {
	if e.Exhausted == "" || e.Exhausted == "none" {
		return "Mozilla refused this region (HTTP 451)"
	}
	return fmt.Sprintf("Mozilla refused this region (HTTP 451; relay recovery %s)", e.Exhausted)
}

// CaptchaCancelledError indicates the user cancelled an interactive CAPTCHA prompt.
type CaptchaCancelledError struct{}

func (CaptchaCancelledError) Error() string { return "CAPTCHA cancelled by user" }

// FastlyCaptchaRequiredError indicates Fastly requested a manual image CAPTCHA on a non-interactive call.
type FastlyCaptchaRequiredError struct{ Msg string }

func (e *FastlyCaptchaRequiredError) Error() string { return e.Msg }

var (
	errTokenInvalid    = errors.New("access token was rejected by Guardian")
	errNoChallengePage = errors.New("host did not serve a Fastly challenge page")
)

// ErrTokenInvalid is the exported sentinel matched by the pool failover logic.
// It is satisfied by TokenInvalidError as well as by errTokenInvalid-wrapping chains.
var ErrTokenInvalid = errTokenInvalid

// TokenInvalidError carries the Guardian HTTP status that rejected the access token.
// 401 → the session is dead (re-auth required); 403 → the account is not entitled (
// retry at a later time). Both unwrap to ErrTokenInvalid so legacy callers keep working.
type TokenInvalidError struct {
	Status int
	Msg    string
}

func (e *TokenInvalidError) Error() string {
	if e.Msg != "" {
		return e.Msg
	}
	return fmt.Sprintf("guardian rejected the access token (HTTP %d)", e.Status)
}

func (e *TokenInvalidError) Unwrap() error { return ErrTokenInvalid }

func upstreamf(format string, args ...any) error {
	return &UpstreamError{Msg: fmt.Sprintf(format, args...)}
}

// IsUpstream reports whether err is one of the tunnel / back-end errors (UpstreamError,
// UpstreamHTTPError, ConnectionLostError).
func IsUpstream(err error) bool {
	var a *UpstreamError
	var b *UpstreamHTTPError
	var c *ConnectionLostError
	return errors.As(err, &a) || errors.As(err, &b) || errors.As(err, &c)
}
