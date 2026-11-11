package foxy

import (
	"context"
	"errors"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func mustURL(t *testing.T, raw string) *url.URL {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatalf("parse URL %q: %v", raw, err)
	}
	return u
}

func TestRelayPolicyAllowlistAndGuardianRewrite(t *testing.T) {
	allowed := []string{
		"https://vpn.mozilla.org/api/v1/fpn/token",
		"https://api.accounts.firefox.com/v1/account/login",
		"https://oauth.accounts.firefox.com:443/v1/token",
		"https://firefox.settings.services.mozilla.com/v1/buckets",
		"https://raw.githubusercontent.com/omidrx00/rx-update-manifests/main/vpn-client/updates.json",
		"https://raw.githubusercontent.com/omidrx00/rx-update-manifests/main/vpn-client/updates.json",
	}
	for _, s := range allowed {
		if !IsRelayTargetAllowed(mustURL(t, s)) {
			t.Errorf("expected target %q to be allowed", s)
		}
	}

	rejected := []string{
		"http://vpn.mozilla.org/api/v1/fpn/token",            // non-HTTPS
		"https://vpn.mozilla.org:8443/api/v1/fpn/token",      // non-443 port
		"https://user:pass@vpn.mozilla.org/api/v1/fpn/token", // userinfo
		"https://vpn.mozilla.org/api/v1/fpn/token#frag",      // fragment
		"https://evilmozilla.org/api/v1/fpn/token",           // suffix spoofing
		"https://127.0.0.1/admin",                            // loopback SSRF
		"https://169.254.169.254/latest/meta-data/",          // metadata SSRF
	}
	for _, s := range rejected {
		if IsRelayTargetAllowed(mustURL(t, s)) {
			t.Errorf("expected target %q to be rejected", s)
		}
	}

	if !IsGuardianRequest(mustURL(t, "https://vpn.mozilla.org/api/v1/fpn/token")) {
		t.Fatal("expected Guardian token endpoint to match IsGuardianRequest")
	}
	if IsGuardianRequest(mustURL(t, "https://api.accounts.firefox.com/v1/account/login")) {
		t.Fatal("FxA endpoint must NOT match IsGuardianRequest")
	}

	rewritten := DoubledLeadingSlash(mustURL(t, "https://vpn.mozilla.org/api/v1/fpn/status?x=1"))
	if rewritten == nil || rewritten.String() != "https://vpn.mozilla.org//api/v1/fpn/status?x=1" {
		t.Fatalf("unexpected DoubledLeadingSlash result: %v", rewritten)
	}
	if DoubledLeadingSlash(rewritten) != nil {
		t.Fatal("DoubledLeadingSlash must return nil when path already begins with //")
	}
}

func TestValidatePersonalRelayURLAndRedaction(t *testing.T) {
	u, err := ValidatePersonalRelayURL("  https://Relay.Example.Workers.Dev?secret=my_sec_123&region=eu  ")
	if err != nil {
		t.Fatalf("valid personal relay rejected: %v", err)
	}
	if u.Host != "relay.example.workers.dev" || u.Path != "/" {
		t.Fatalf("unexpected normalized URL: %s", u.String())
	}

	redacted := RedactRelayURL(u.String())
	if strings.Contains(redacted, "my_sec_123") {
		t.Fatalf("RedactRelayURL leaked secret: %s", redacted)
	}

	cases := []struct {
		raw  string
		kind PersonalURLError
	}{
		{"http://relay.example.com/?secret=abc", PersonalURLNotHTTPS},
		{"https://relay.example.com/", PersonalURLNoSecret},
		{"https://relay.example.com/?secret=", PersonalURLNoSecret},
		{"https://relay.example.com/?secret=abc&url=https://mozilla.org", PersonalURLInvalid},
		{"https://relay.example.com/?secret=abc&health=1", PersonalURLInvalid},
		{"https://user@relay.example.com/?secret=abc", PersonalURLInvalid},
		{"https://relay.example.com/?secret=abc#frag", PersonalURLInvalid},
		{"https://relay.example.com/?secret=" + strings.Repeat("a", 2050), PersonalURLInvalid},
	}
	for _, tc := range cases {
		_, vErr := ValidatePersonalRelayURL(tc.raw)
		if vErr == nil {
			t.Fatalf("expected error for %q", tc.raw)
		}
		var pve *PersonalURLValidationError
		if !errors.As(vErr, &pve) || pve.Kind != tc.kind {
			t.Fatalf("for %q got %v, want kind %q", tc.raw, vErr, tc.kind)
		}
	}
}

func TestManifestParsingStickinessCooldownAndAnnouncements(t *testing.T) {
	manifestJSON := `{
		"version": 1,
		"generated_at": "2026-11-11T00:00:00Z",
		"relays": {
			"enabled": true,
			"urls": [
				"https://r1.example.workers.dev/",
				"https://r2.example.workers.dev",
				"http://insecure.example.com/",
				"https://r1.example.workers.dev"
			]
		},
		"announcements": [
			{"id": "low", "enabled": true, "title": "Low", "message": "Msg 1", "priority": 1},
			{"id": "high", "enabled": true, "title": "High", "message": "Msg 2", "priority": 10},
			{"id": "off", "enabled": false, "title": "Off", "message": "Hidden", "priority": 99}
		]
	}`

	var primaryCalls, fallbackCalls atomic.Int32
	now := time.Date(2026, 11, 11, 12, 0, 0, 0, time.UTC)

	mgr, err := NewSharedRelayManager(TLSOptions{}, func() RelayConfig {
		return RelayConfig{
			SharedEnabled:            true,
			ManifestPrimaryOverride:  "https://primary.test/updates.json",
			ManifestFallbackOverride: "https://mirror.test/updates.json",
		}
	})
	if err != nil {
		t.Fatal(err)
	}
	mgr.now = func() time.Time { return now }
	mgr.client = &http.Client{
		Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
			if r.URL.Host == "primary.test" {
				primaryCalls.Add(1)
				return fakeResponse(r, 503, "unavailable"), nil
			}
			fallbackCalls.Add(1)
			return fakeResponse(r, 200, manifestJSON), nil
		}),
	}

	// 1. Primary fails -> falls back to mirror seamlessly.
	cands := mgr.CandidatesForAttempt(context.Background(), "SH_SEC")
	if primaryCalls.Load() != 1 || fallbackCalls.Load() != 1 {
		t.Fatalf("expected 1 primary and 1 fallback call, got %d / %d", primaryCalls.Load(), fallbackCalls.Load())
	}
	if len(cands) != 2 ||
		cands[0] != "https://r1.example.workers.dev/?secret=SH_SEC" ||
		cands[1] != "https://r2.example.workers.dev/?secret=SH_SEC" {
		t.Fatalf("unexpected candidates: %v", cands)
	}

	// 2. MarkGood sticks r2 first for 10m; MarkBad cools down r2 for 60s.
	mgr.MarkGood(cands[1])
	gotSticky := mgr.CandidatesForAttempt(context.Background(), "SH_SEC")
	if gotSticky[0] != "https://r2.example.workers.dev/?secret=SH_SEC" {
		t.Fatalf("expected r2 sticky first, got %v", gotSticky)
	}

	mgr.MarkBad(cands[1])
	gotAfterBad := mgr.CandidatesForAttempt(context.Background(), "SH_SEC")
	if gotAfterBad[0] != "https://r1.example.workers.dev/?secret=SH_SEC" {
		t.Fatalf("expected healthy r1 ahead of cooling-down r2, got %v", gotAfterBad)
	}

	// Advance 61s -> r2 cooldown expires.
	now = now.Add(61 * time.Second)
	mgr.MarkGood(cands[1])
	if mgr.CandidatesForAttempt(context.Background(), "SH_SEC")[0] != "https://r2.example.workers.dev/?secret=SH_SEC" {
		t.Fatal("expected r2 healthy again after 61s")
	}

	// 3. PendingAnnouncements preserves exact Manifest order (matching Android AnnouncementManager), excluding disabled & acknowledged IDs.
	pending := mgr.PendingAnnouncements(context.Background(), map[string]bool{}, false)
	if len(pending) != 2 || pending[0].ID != "low" || pending[1].ID != "high" {
		t.Fatalf("unexpected announcements order: %+v", pending)
	}
	pendingAcked := mgr.PendingAnnouncements(context.Background(), map[string]bool{"low": true}, false)
	if len(pendingAcked) != 1 || pendingAcked[0].ID != "high" {
		t.Fatalf("acknowledged announcement was not filtered: %+v", pendingAcked)
	}
}

func TestRelayHealthCheckerContract(t *testing.T) {
	checker := &RelayHealthChecker{}
	u, _ := ValidatePersonalRelayURL("https://relay.test/?secret=abc")

	cases := []struct {
		name     string
		status   int
		hdr      map[string]string
		body     string
		wantOK   bool
		wantFail RelayFailure
	}{
		{
			name:   "healthy v1 worker",
			status: 200,
			body:   `{"relay":"rxvpn","version":1,"ready":true}`,
			wantOK: true,
		},
		{
			name:     "200 with X-Relay-Error rejected",
			status:   200,
			hdr:      map[string]string{RelayErrorHeader: "bad"},
			body:     `{"relay":"rxvpn","version":1,"ready":true}`,
			wantOK:   false,
			wantFail: RelayFailureRefused,
		},
		{
			name:     "403 wrong secret",
			status:   403,
			body:     `{"error":"wrong secret"}`,
			wantOK:   false,
			wantFail: RelayFailureWrongSecret,
		},
		{
			name:     "503 missing SECRET env",
			status:   503,
			body:     `{"error":"SECRET not configured"}`,
			wantOK:   false,
			wantFail: RelayFailureNotSetUp,
		},
		{
			name:     "502 upstream unreachable",
			status:   502,
			body:     `bad gateway`,
			wantOK:   false,
			wantFail: RelayFailureUpstreamUnreachable,
		},
		{
			name:     "200 unrelated HTML page",
			status:   200,
			body:     `<html>hello</html>`,
			wantOK:   false,
			wantFail: RelayFailureUnrecognised,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			checker.client = &http.Client{
				Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
					if r.URL.Query().Get("health") != "1" || r.URL.Query().Get("secret") != "abc" {
						t.Fatalf("unexpected health probe URL: %s", r.URL.String())
					}
					resp := fakeResponse(r, tc.status, tc.body)
					for k, v := range tc.hdr {
						resp.Header.Set(k, v)
					}
					return resp, nil
				}),
			}
			res := checker.Check(context.Background(), u)
			if res.Ready != tc.wantOK || (!tc.wantOK && res.Failure != tc.wantFail) {
				t.Fatalf("got %+v, want ok=%v fail=%q", res, tc.wantOK, tc.wantFail)
			}
		})
	}
}

func TestRelayFallbackOrderStatusIsolationAndHeaderSanitization(t *testing.T) {
	var cfg RelayConfig
	var relayHits []string
	var sawTransferredHopHeader atomic.Bool

	tr := &RelayTransport{
		client: &http.Client{
			Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
				relayHits = append(relayHits, r.URL.Host)
				if r.Header.Get("CF-Connecting-IP") != "" || r.Header.Get("Connection") != "" {
					sawTransferredHopHeader.Store(true)
				}
				switch r.URL.Host {
				case "personal.test":
					resp := fakeResponse(r, 200, `{"subscribed":true}`)
					resp.Header.Set("Content-Type", "application/json")
					// Personal relay without X-RxVpN-Relay (non-Cloudflare) is accepted per RelayTransport.kt!
					return resp, nil
				case "shared1.test":
					// Shared relay WITHOUT X-RxVpN-Relay must be rejected and marked bad!
					resp := fakeResponse(r, 200, `{"subscribed":true}`)
					resp.Header.Set("Content-Type", "application/json")
					return resp, nil
				case "shared2.test":
					resp := fakeResponse(r, 200, `{"subscribed":true}`)
					resp.Header.Set("Content-Type", "application/json")
					resp.Header.Set(RelayForwardedHeader, "1")
					return resp, nil
				default:
					return fakeResponse(r, 502, "err"), nil
				}
			}),
		},
	}

	sharedMgr := &SharedRelayManager{
		now:           time.Now,
		hasManifest:   true,
		nextRefreshAt: time.Now().Add(time.Hour),
		candidates:    []string{"https://shared1.test/", "https://shared2.test/"},
		badUntil:      map[string]time.Time{},
	}

	coord := NewRelayFallbackCoordinator(func() RelayConfig { return cfg }, tr, sharedMgr)

	// 1. Direct non-451 statuses (401, 403, 406, 429, 500) NEVER trigger relay fallback!
	for _, code := range []int{200, 401, 403, 406, 429, 500} {
		relayHits = nil
		cfg = RelayConfig{
			SharedEnabled:   true,
			PersonalEnabled: true,
			PersonalURL:     "https://personal.test/?secret=sec",
		}
		resp, err := coord.Execute(
			context.Background(),
			http.MethodGet,
			mustURL(t, "https://vpn.mozilla.org/api/v1/fpn/status"),
			http.Header{},
			nil,
			func(_ context.Context, _ *url.URL) (*Response, error) {
				return &Response{Status: code, Header: http.Header{}, Body: []byte(`{}`)}, nil
			},
		)
		if err != nil || resp.Status != code || len(relayHits) != 0 {
			t.Fatalf("status %d must return untouched without hitting relays; hits=%v err=%v", code, relayHits, err)
		}
	}

	// 2. Guardian HTTP 451 -> tries "//" direct first, then Shared1 (rejected: missing X-RxVpN-Relay), then Shared2 (succeeds)!
	var directPaths []string
	relayHits = nil
	cfg = RelayConfig{SharedEnabled: true}
	reqHdr := http.Header{
		"Authorization":    []string{"Bearer tok"},
		"CF-Connecting-IP": []string{"1.2.3.4"},
		"Connection":       []string{"keep-alive"},
	}
	resp, err := coord.Execute(
		context.Background(),
		http.MethodGet,
		mustURL(t, "https://vpn.mozilla.org/api/v1/fpn/status"),
		reqHdr,
		nil,
		func(_ context.Context, u *url.URL) (*Response, error) {
			directPaths = append(directPaths, u.Path)
			return &Response{Status: 451, Header: http.Header{}, Body: []byte("blocked")}, nil
		},
	)
	if err != nil {
		t.Fatalf("expected shared relay recovery to succeed: %v", err)
	}
	if sawTransferredHopHeader.Load() {
		t.Fatal("RelayTransport failed to strip CF-* or hop-by-hop headers")
	}
	if len(directPaths) != 2 || directPaths[0] != "/api/v1/fpn/status" || directPaths[1] != "//api/v1/fpn/status" {
		t.Fatalf("expected direct + doubled-slash Guardian retry before relays, got %v", directPaths)
	}
	if len(relayHits) != 2 || relayHits[0] != "shared1.test" || relayHits[1] != "shared2.test" {
		t.Fatalf("unexpected shared relay progression: %v", relayHits)
	}
	if resp.Header.Get(RelayRecoveryHeader) != "shared" {
		t.Fatalf("expected X-RxFoxy-Recovery=shared, got %q", resp.Header.Get(RelayRecoveryHeader))
	}

	// 3. PersonalOnly=true when Personal fails -> MUST NOT touch Shared relays!
	relayHits = nil
	cfg = RelayConfig{
		SharedEnabled:   true,
		PersonalEnabled: true,
		PersonalOnly:    true,
		PersonalURL:     "https://dead-personal.test/?secret=sec",
	}
	resp451, err := coord.Execute(
		context.Background(),
		http.MethodGet,
		mustURL(t, "https://vpn.mozilla.org/api/v1/fpn/status"),
		http.Header{},
		nil,
		func(_ context.Context, _ *url.URL) (*Response, error) {
			return &Response{Status: 451, Header: http.Header{}, Body: []byte("blocked")}, nil
		},
	)
	if err != nil || resp451.Status != 451 {
		t.Fatalf("expected buffered 451 when PersonalOnly fails, got resp=%v err=%v", resp451, err)
	}
	if len(relayHits) != 1 || relayHits[0] != "dead-personal.test" {
		t.Fatalf("PersonalOnly leaked to shared relays: %v", relayHits)
	}
	if resp451.Header.Get(RelayRecoveryExhaustedHeader) != "personal" {
		t.Fatalf("expected exhausted=personal, got %q", resp451.Header.Get(RelayRecoveryExhaustedHeader))
	}

	// 4. Non-Guardian FxA 451 with SharedEnabled=true and PersonalEnabled=false -> MUST NOT send FxA auth to Shared relays!
	relayHits = nil
	cfg = RelayConfig{SharedEnabled: true, PersonalEnabled: false}
	fxa451, err := coord.Execute(
		context.Background(),
		http.MethodPost,
		mustURL(t, "https://api.accounts.firefox.com/v1/account/login"),
		http.Header{},
		[]byte(`{}`),
		func(_ context.Context, _ *url.URL) (*Response, error) {
			return &Response{Status: 451, Header: http.Header{}, Body: []byte("blocked")}, nil
		},
	)
	if err != nil || fxa451.Status != 451 || len(relayHits) != 0 {
		t.Fatalf("FxA request must never be sent to shared relays; hits=%v", relayHits)
	}
}
