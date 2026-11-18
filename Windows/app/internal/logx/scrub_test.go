package logx

import (
	"errors"
	"strings"
	"testing"
)

func TestScrubHawkAndKeys(t *testing.T) {
	in := `Authorization: Hawk id="abc123", ts="1711000000", nonce="n0nceValue", mac="macValue0123456789"`
	out := Scrub(in)
	if strings.Contains(out, "macValue") || strings.Contains(out, "n0nceValue") {
		t.Fatalf("hawk secret leaked: %q", out)
	}
}

func TestScrubJSONSecrets(t *testing.T) {
	in := `{"access_token":"sekrit-access","refresh_token":"sekrit-refresh","expires_in":3600,"email":"one@example.com"}`
	out := Scrub(in)
	if strings.Contains(out, "sekrit-access") || strings.Contains(out, "sekrit-refresh") {
		t.Fatalf("json secrets leaked: %q", out)
	}
	if !strings.Contains(out, "3600") {
		t.Fatalf("non-secret content must survive: %q", out)
	}
}

func TestScrubKV(t *testing.T) {
	in := "GET /x?access_token=abcDEF123&password=hunter2 other"
	out := Scrub(in)
	if strings.Contains(out, "abcDEF123") || strings.Contains(out, "hunter2") {
		t.Fatalf("kv secrets leaked: %q", out)
	}
}

func TestScrubBearerAndJWT(t *testing.T) {
	in := "calling with Bearer abc.def-ghijkl and token eyJhbGciOi9kaWN0.e30.signature123"
	out := Scrub(in)
	if strings.Contains(out, "abc.def-ghijkl") || strings.Contains(out, "signature123") {
		t.Fatalf("token leaked: %q", out)
	}
}

func TestScrubEmail(t *testing.T) {
	in := "user jane.doe@example.com logged in"
	out := Scrub(in)
	if strings.Contains(out, "jane.doe@") {
		t.Fatalf("email local part leaked: %q", out)
	}
	if !strings.Contains(out, "@example.com") {
		t.Fatalf("domain must remain visible for debugging: %q", out)
	}
}

func TestScrubOrderHawkSurvivesKV(t *testing.T) {
	// the generic key=value pattern must not half-eat a hawk blob; the full mac must vanish
	in := `nonce="zzz111111111" mac="yyy222222222"`
	out := Scrub(in)
	if strings.Contains(out, "yyy222222222") || strings.Contains(out, "zzz111111111") {
		t.Fatalf("hawk values leaked: %q", out)
	}
}

func TestScrubValueWithError(t *testing.T) {
	err := errors.New("access_token=abc123secret failed")
	if got := ScrubValue(err).(string); strings.Contains(got, "abc123secret") {
		t.Fatalf("error text leaked: %q", got)
	}
}

func TestEmailHelper(t *testing.T) {
	cases := map[string]string{
		"arman@example.com":  "a***@example.com",
		"a@b.co":             "a***@b.co",
		"":                   "",
		"not-an-email":       "***",
		"UPPER@EXAMPLE.COM":  "U***@EXAMPLE.COM",
	}
	for in, want := range cases {
		if got := Email(in); got != want {
			t.Errorf("Email(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestIDHelper(t *testing.T) {
	if got := ID("1234567890abcdef"); got != "12345678" {
		t.Fatalf("ID = %q", got)
	}
	if got := ID(" short "); got != " short " {
		t.Fatalf("ID keeps short ids, got %q", got)
	}
}
