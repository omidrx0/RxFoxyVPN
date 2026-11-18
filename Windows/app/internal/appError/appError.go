// Package appError is the single error model of the panel (W-01). Every path that can
// fail — connect, login, backup, routing, servers, accounts — funnels through Classify,
// producing a FoxyError with a stable machine-readable Code from the 31 codes table.
// The UI only ever sees t("errors."+code+".title") (see UserMessage); raw Go errors never
// reach a label.
package appError

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"net"
	"strings"

	"rxvpn/internal/backup"
	"rxvpn/internal/foxy"
)

// Section names the part of the app the error came from.
type Section string

const (
	SecConnect  Section = "connect"
	SecLogin    Section = "login"
	SecBackup   Section = "backup"
	SecRouting  Section = "routing"
	SecServers  Section = "servers"
	SecAccounts Section = "accounts"
)

// FoxyError is the classified error. Cause is kept for the log only and is always scrubbed.
type FoxyError struct {
	Code      string
	Section   Section
	Operation string
	Cause     error
	Detail    string
	Retryable bool
}

func (e FoxyError) Error() string {
	msg := e.Code
	if e.Detail != "" {
		msg += ": " + e.Detail
	}
	return msg
}

func (e FoxyError) Unwrap() error { return e.Cause }

// Coded is implemented by sentinel/typed errors that already know their Code.
type Coded interface{ Code() string }

// The 31 stable codes (retryable flag in parens).
const (
	CodeConnectionFailed     = "CONNECTION_FAILED"     // true
	CodeServerUnreachable    = "SERVER_UNREACHABLE"    // true
	CodeAuthFailed           = "AUTH_FAILED"           // false
	CodeAccountExpired       = "ACCOUNT_EXPIRED"       // false
	CodeNoActiveAccount      = "NO_ACTIVE_ACCOUNT"     // false
	CodeNoServerSelected     = "NO_SERVER_SELECTED"    // false
	CodeNetworkUnavailable   = "NETWORK_UNAVAILABLE"   // true
	CodeDNSFailed            = "DNS_FAILED"            // true
	CodeConnectionTimeout    = "CONNECTION_TIMEOUT"    // true
	CodeTLSHandshakeFailed   = "TLS_HANDSHAKE_FAILED"  // true
	CodeInvalidConfiguration = "INVALID_CONFIGURATION" // false
	CodeCaptchaRequired      = "CAPTCHA_REQUIRED"      // true
	CodeServerOverloaded     = "SERVER_OVERLOADED"     // true
	CodeVPNPermissionDenied  = "VPN_PERMISSION_DENIED" // false
	CodeUnknownError         = "UNKNOWN_ERROR"         // true
	CodeQuotaExhausted       = "QUOTA_EXHAUSTED"       // false
	CodeNotEntitled          = "NOT_ENTITLED"          // false
	CodeTokenInvalid         = "TOKEN_INVALID"         // false
	CodeReauthRequired       = "REAUTH_REQUIRED"       // false
	CodeNoUsableAccount      = "NO_USABLE_ACCOUNT"     // false
	CodeAccountReserved      = "ACCOUNT_RESERVED"      // true
	CodeRegionBlocked        = "REGION_BLOCKED"        // false
	CodeRateLimited          = "RATE_LIMITED"          // true
	CodeCaptchaBlocked       = "CAPTCHA_BLOCKED"       // true
	CodeBackupInvalid        = "BACKUP_INVALID"        // false
	CodeBackupEmpty          = "BACKUP_EMPTY"          // false
	CodeBackupFailed         = "BACKUP_FAILED"         // true
	CodeImportFailed         = "IMPORT_FAILED"         // true
	CodeExportFailed         = "EXPORT_FAILED"         // true
	CodeServerListFailed     = "SERVER_LIST_FAILED"    // true
	CodeStorageError         = "STORAGE_ERROR"         // true
)

// retryable mirrors the table exactly.
var retryable = map[string]bool{
	CodeConnectionFailed:     true,
	CodeServerUnreachable:    true,
	CodeAuthFailed:           false,
	CodeAccountExpired:       false,
	CodeNoActiveAccount:      false,
	CodeNoServerSelected:     false,
	CodeNetworkUnavailable:   true,
	CodeDNSFailed:            true,
	CodeConnectionTimeout:    true,
	CodeTLSHandshakeFailed:   true,
	CodeInvalidConfiguration: false,
	CodeCaptchaRequired:      true,
	CodeServerOverloaded:     true,
	CodeVPNPermissionDenied:  false,
	CodeUnknownError:         true,
	CodeQuotaExhausted:       false,
	CodeNotEntitled:          false,
	CodeTokenInvalid:         false,
	CodeReauthRequired:       false,
	CodeNoUsableAccount:      false,
	CodeAccountReserved:      true,
	CodeRegionBlocked:        false,
	CodeRateLimited:          true,
	CodeCaptchaBlocked:       true,
	CodeBackupInvalid:        false,
	CodeBackupEmpty:          false,
	CodeBackupFailed:         true,
	CodeImportFailed:         true,
	CodeExportFailed:         true,
	CodeServerListFailed:     true,
	CodeStorageError:         true,
}

// Codes lists every code, for the i18n parity test and docs.
func Codes() []string {
	out := make([]string, 0, len(retryable))
	for code := range retryable {
		out = append(out, code)
	}
	return out
}

// IsRetryable reports the retryable flag of a code.
func IsRetryable(code string) bool { return retryable[code] }

func makeErr(code string, section Section, op string, cause error, detail string) FoxyError {
	if detail == "" {
		detail = lastCauseMessage(cause)
	}
	return FoxyError{Code: code, Section: section, Operation: op, Cause: cause, Detail: detail, Retryable: retryable[code]}
}

func lastCauseMessage(cause error) string {
	if cause == nil {
		return ""
	}
	return cause.Error()
}

// New builds a FoxyError for a code that carries no extra classification.
func New(code string, section Section, op string, detail string) FoxyError {
	if _, ok := retryable[code]; !ok {
		code = CodeUnknownError
	}
	return makeErr(code, section, op, nil, detail)
}

// GuardianTrulyNotEntitled marks "Guardian answered 403 with no entitlement"; the wiring
// marks the account unavailable and reports NOT_ENTITLED (W-09).
var GuardianTrulyNotEntitled = errors.New("guardian answered 403: the account has no VPN entitlement")

// Classify maps any error to a FoxyError. The mapping table (guide پیوست D) is normative.
func Classify(err error, section Section, op string) FoxyError {
	if err == nil {
		return makeErr(CodeUnknownError, section, op, nil, "")
	}
	// 0. errors that already know their code
	var coded Coded
	if errors.As(err, &coded) {
		return makeErr(coded.Code(), section, op, err, "")
	}
	var fe FoxyError
	if errors.As(err, &fe) {
		// a FoxyError that travels through another layer keeps its code
		return makeErr(fe.Code, section, op, err, fe.Detail)
	}
	// 1. sentinels of our own packages and well-known net/x509 errors
	if errors.Is(err, GuardianTrulyNotEntitled) {
		return makeErr(CodeNotEntitled, section, op, err, "")
	}
	if errors.Is(err, backup.ErrBackupEmpty) {
		return makeErr(CodeBackupEmpty, section, op, err, "")
	}
	if errors.Is(err, backup.ErrBackupInvalid) {
		return makeErr(CodeBackupInvalid, section, op, err, "")
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return makeErr(CodeConnectionTimeout, section, op, err, "")
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return makeErr(CodeConnectionTimeout, section, op, err, "")
	}
	var dnsErr *net.DNSError
	if errors.As(err, &dnsErr) {
		return makeErr(CodeDNSFailed, section, op, err, "")
	}
	var certErr *tls.RecordHeaderError
	if errors.As(err, &certErr) {
		return makeErr(CodeTLSHandshakeFailed, section, op, err, "")
	}
	var unknownCA x509.UnknownAuthorityError
	var certInvalid x509.CertificateInvalidError
	if errors.As(err, &unknownCA) || errors.As(err, &certInvalid) {
		return makeErr(CodeTLSHandshakeFailed, section, op, err, "")
	}
	var opErr *net.OpError
	if errors.As(err, &opErr) && opErr.Op == "dial" {
		return makeErr(CodeServerUnreachable, section, op, err, "")
	}
	var fxaErr *foxy.FxaAPIError
	if errors.As(err, &fxaErr) {
		return classifyFxa(fxaErr, section, op)
	}
	var regionErr *foxy.RegionBlockedError
	if errors.As(err, &regionErr) {
		return makeErr(CodeRegionBlocked, section, op, err, "")
	}
	var quotaErr *foxy.QuotaExceededError
	if errors.As(err, &quotaErr) {
		return makeErr(CodeQuotaExhausted, section, op, err, "")
	}
	var captchaErr *foxy.CaptchaCancelledError
	if errors.As(err, &captchaErr) {
		return makeErr(CodeCaptchaRequired, section, op, err, "")
	}
	var captchaReqErr *foxy.FastlyCaptchaRequiredError
	if errors.As(err, &captchaReqErr) {
		return makeErr(CodeCaptchaRequired, section, op, err, "")
	}
	var captchaBlockErr *foxy.FastlyChallengeError
	if errors.As(err, &captchaBlockErr) {
		return makeErr(CodeCaptchaBlocked, section, op, err, "")
	}
	var httpErr *foxy.UpstreamHTTPError
	if errors.As(err, &httpErr) {
		return classifyGuardianStatus(httpErr.Status, section, op, err)
	}
	var needLogin *foxy.NeedLoginError
	if errors.As(err, &needLogin) {
		return makeErr(CodeNoActiveAccount, section, op, err, "")
	}
	var upErr *foxy.UpstreamError
	if errors.As(err, &upErr) || errors.As(err, new(*foxy.ConnectionLostError)) {
		return makeErr(CodeConnectionFailed, section, op, err, "")
	}
	// 2. message fallbacks (guide: known messages)
	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "no usable account"):
		return makeErr(CodeNoUsableAccount, section, op, err, "")
	case strings.Contains(msg, "no server selected"):
		return makeErr(CodeNoServerSelected, section, op, err, "")
	case strings.Contains(msg, "not signed in"):
		return makeErr(CodeNoActiveAccount, section, op, err, "")
	case strings.Contains(msg, "connection refused"), strings.Contains(msg, "no route to host"):
		return makeErr(CodeServerUnreachable, section, op, err, "")
	}
	return makeErr(CodeUnknownError, section, op, err, "")
}

func classifyGuardianStatus(status int, section Section, op string, err error) FoxyError {
	switch {
	case status == 400 || status == 401:
		return makeErr(CodeAuthFailed, section, op, err, "")
	case status == 403:
		// W-09: Guardian answered 403 — the account is valid but NOT ENTITLED
		return makeErr(CodeNotEntitled, section, op, err, "")
	case status == 404:
		return makeErr(CodeServerUnreachable, section, op, err, "")
	case status == 429:
		return makeErr(CodeQuotaExhausted, section, op, err, "")
	case status == 451:
		return makeErr(CodeRegionBlocked, section, op, err, "")
	case status >= 500 && status <= 599:
		return makeErr(CodeServerOverloaded, section, op, err, "")
	default:
		return makeErr(CodeConnectionFailed, section, op, err, "")
	}
}

func classifyFxa(e *foxy.FxaAPIError, section Section, op string) FoxyError {
	switch e.Errno {
	case 107, 104, 138, 120, 125:
		return makeErr(CodeAuthFailed, section, op, e, e.Message)
	case 114:
		return makeErr(CodeRateLimited, section, op, e, e.Message)
	case 108, 109:
		return makeErr(CodeAccountExpired, section, op, e, e.Message)
	case 201:
		return makeErr(CodeServerOverloaded, section, op, e, e.Message)
	}
	if e.Status == 0 {
		// no HTTP status at all: the answer itself was not there
		return makeErr(CodeNetworkUnavailable, section, op, e, e.Message)
	}
	switch {
	case e.Status == 429:
		return makeErr(CodeRateLimited, section, op, e, e.Message)
	case e.Status == 451:
		return makeErr(CodeRegionBlocked, section, op, e, e.Message)
	case e.Status >= 500 && e.Status <= 599:
		return makeErr(CodeServerOverloaded, section, op, e, e.Message)
	default:
		return makeErr(CodeAuthFailed, section, op, e, e.Message)
	}
}

// UserMessage renders the localized UI message: title (+ " — " + hint when present).
// t is the UI's translator (web t() equivalent); missing keys must degrade to the key itself.
func UserMessage(e FoxyError, t func(string) string) string {
	msg := t("errors." + e.Code + ".title")
	hint := t("errors." + e.Code + ".hint")
	if hint != "" && hint != "errors."+e.Code+".hint" {
		return msg + " — " + hint
	}
	return msg
}
