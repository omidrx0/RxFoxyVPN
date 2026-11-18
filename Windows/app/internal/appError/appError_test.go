package appError

import (
	"context"
	"crypto/x509"
	"errors"
	"testing"

	"rxvpn/internal/foxy"
)

func TestAllCodesUniqueAndRetryableTableComplete(t *testing.T) {
	if len(Codes()) != 31 {
		t.Fatalf("31 codes required, got %d", len(Codes()))
	}
	seen := map[string]bool{}
	for _, c := range Codes() {
		if seen[c] {
			t.Fatalf("duplicate code %s", c)
		}
		seen[c] = true
		_ = IsRetryable(c)
	}
}

func TestRetryableSpotChecks(t *testing.T) {
	want := map[string]bool{
		CodeQuotaExhausted:   false,
		CodeNoActiveAccount:  false,
		CodeNoServerSelected: false,
		CodeBackupEmpty:      false,
		CodeNoUsableAccount:  false,
		CodeAccountReserved:  true,
		CodeRateLimited:      true,
		CodeServerListFailed: true,
		CodeStorageError:     true,
		CodeBackupFailed:     true,
		CodeImportFailed:     true,
		CodeExportFailed:     true,
	}
	for code, w := range want {
		if got := IsRetryable(code); got != w {
			t.Errorf("IsRetryable(%s) = %v, want %v", code, got, w)
		}
	}
}

func TestClassifyNetErrors(t *testing.T) {
	if got := Classify(context.DeadlineExceeded, SecConnect, "connect"); got.Code != CodeConnectionTimeout {
		t.Errorf("deadline → %s", got.Code)
	}
	cert := x509.UnknownAuthorityError{}
	if got := Classify(cert, SecConnect, "connect"); got.Code != CodeTLSHandshakeFailed {
		t.Errorf("x509 unknown CA → %s", got.Code)
	}
}

func TestClassifyTyped(t *testing.T) {
	cases := []struct {
		err  error
		want string
	}{
		{&foxy.QuotaExceededError{}, CodeQuotaExhausted},
		{&foxy.RegionBlockedError{}, CodeRegionBlocked},
		{&foxy.NeedLoginError{}, CodeNoActiveAccount},
		{&foxy.CaptchaCancelledError{}, CodeCaptchaRequired},
		{&foxy.FastlyCaptchaRequiredError{}, CodeCaptchaRequired},
		{&foxy.FastlyChallengeError{}, CodeCaptchaBlocked},
		{&foxy.UpstreamHTTPError{Status: 403}, CodeNotEntitled},
		{&foxy.UpstreamHTTPError{Status: 429}, CodeQuotaExhausted},
		{&foxy.UpstreamHTTPError{Status: 451}, CodeRegionBlocked},
		{&foxy.UpstreamHTTPError{Status: 500}, CodeServerOverloaded},
		{&foxy.UpstreamHTTPError{Status: 401}, CodeAuthFailed},
		{&foxy.FxaAPIError{Errno: 114, Status: 400}, CodeRateLimited},
		{&foxy.FxaAPIError{Errno: 108, Status: 400}, CodeAccountExpired},
		{&foxy.FxaAPIError{Errno: 107, Status: 400}, CodeAuthFailed},
		{&foxy.FxaAPIError{Errno: 201, Status: 500}, CodeServerOverloaded},
		{GuardianTrulyNotEntitled, CodeNotEntitled},
	}
	for i, tc := range cases {
		if got := Classify(tc.err, SecConnect, "connect"); got.Code != tc.want {
			t.Errorf("case %d: %v → %s, want %s", i, tc.err, got.Code, tc.want)
		}
	}
}

func TestClassifyMessageFallback(t *testing.T) {
	for msg, want := range map[string]string{
		"no usable account found": CodeNoUsableAccount,
		"no server selected":      CodeNoServerSelected,
		"connection refused":      CodeServerUnreachable,
		"not signed in":           CodeNoActiveAccount,
	} {
		if got := Classify(errors.New(msg), SecConnect, "connect"); got.Code != want {
			t.Errorf("%q → %s, want %s", msg, got.Code, want)
		}
	}
}

func TestCodedPassesThrough(t *testing.T) {
	fe := New(CodeBackupEmpty, SecBackup, "inspect", "")
	if got := Classify(fe, SecBackup, "inspect"); got.Code != CodeBackupEmpty {
		t.Fatalf("Coded error must keep its code, got %s", got.Code)
	}
	if fFe := New(CodeBackupEmpty, SecBackup, "inspect", ""); fFe.Retryable {
		t.Fatalf("BACKUP_EMPTY is not retryable")
	}
}

func TestUserMessage(t *testing.T) {
	tr := func(k string) string {
		switch k {
		case "errors.QUOTA_EXHAUSTED.title":
			return "سهمیه تمام شد"
		case "errors.QUOTA_EXHAUSTED.hint":
			return "اول ماه بعد دوباره تلاش کنید"
		case "errors.OTHER.title":
			return "Other"
		}
		return k
	}
	if got := UserMessage(New(CodeQuotaExhausted, SecConnect, "op", ""), tr); got != "سهمیه تمام شد — اول ماه بعد دوباره تلاش کنید" {
		t.Fatalf("got %q", got)
	}
	// hint missing → title only
	tr2 := func(k string) string {
		if k == "errors.ACCOUNT_RESERVED.title" {
			return "Account reserved"
		}
		return k
	}
	if got := UserMessage(New(CodeAccountReserved, SecConnect, "op", ""), tr2); got != "Account reserved" {
		t.Fatalf("missing hint must degrade to title, got %q", got)
	}
}

func TestNewFallsBackOnUnknownCode(t *testing.T) {
	got := New("NOT_A_REAL_CODE", SecConnect, "op", "d")
	if got.Code != CodeUnknownError {
		t.Fatalf("unknown code must become UNKNOWN_ERROR, got %s", got.Code)
	}
}
