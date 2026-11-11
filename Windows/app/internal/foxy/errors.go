package foxy

import (
	"errors"
	"fmt"
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
type QuotaExceededError struct{ Msg string }

func (e *QuotaExceededError) Error() string { return e.Msg }

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
