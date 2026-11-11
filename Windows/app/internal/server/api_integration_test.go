package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"rxvpn/internal/app"
	"rxvpn/internal/foxy"
)

func TestServerSecurityAndNewEndpointsIntegration(t *testing.T) {
	t.Setenv("APPDATA", t.TempDir())
	t.Setenv("LOCALAPPDATA", t.TempDir())
	t.Setenv("HOME", t.TempDir())

	a, err := app.New(app.Options{SocksPort: 0, HTTPPort: 0})
	if err != nil {
		t.Fatalf("app.New failed: %v", err)
	}
	defer a.Close()

	srv := New(a, "secret_panel_tok", 18787)

	doGET := func(path string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodGet, "http://127.0.0.1:18787/api/"+path, nil)
		req.Host = "127.0.0.1:18787"
		req.Header.Set("Cookie", "rx=secret_panel_tok")
		rr := httptest.NewRecorder()
		srv.ServeHTTP(rr, req)
		return rr
	}
	doPOST := func(path string, payload map[string]any) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(payload)
		req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:18787/api/"+path, bytes.NewReader(raw))
		req.Host = "127.0.0.1:18787"
		req.Header.Set("Cookie", "rx=secret_panel_tok")
		req.Header.Set("X-RXVPN", "1")
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		srv.ServeHTTP(rr, req)
		return rr
	}

	// 1. CSRF / DNS-rebinding guards
	badReq := httptest.NewRequest(http.MethodPost, "http://evil.com:18787/api/logout", nil)
	badReq.Host = "evil.com:18787"
	badRR := httptest.NewRecorder()
	srv.ServeHTTP(badRR, badReq)
	if badRR.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for evil host, got %d", badRR.Code)
	}

	// 2. Status includes 4-state quota & captcha fields
	stRR := doGET("status")
	if stRR.Code != http.StatusOK {
		t.Fatalf("GET /api/status failed: %d %s", stRR.Code, stRR.Body.String())
	}
	var stMap map[string]any
	if err := json.Unmarshal(stRR.Body.Bytes(), &stMap); err != nil {
		t.Fatal(err)
	}
	if _, ok := stMap["quota_state"]; !ok {
		t.Fatalf("status response missing quota_state: %v", stMap)
	}

	// 3. Relay settings validation + secret redaction + retention when disabled
	badRelay := doPOST("set_relay_settings", map[string]any{
		"personal_url": "https://my-worker.example.dev/", // missing ?secret=
	})
	if badRelay.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 when personal_url lacks secret, got %d", badRelay.Code)
	}

	goodRelay := doPOST("set_relay_settings", map[string]any{
		"personal_url":     "https://my-worker.example.dev/?secret=ULTRA_SECRET_TOKEN",
		"personal_enabled": true,
		"personal_only":    true,
	})
	if goodRelay.Code != http.StatusOK {
		t.Fatalf("set_relay_settings failed: %d %s", goodRelay.Code, goodRelay.Body.String())
	}
	if strings.Contains(goodRelay.Body.String(), "ULTRA_SECRET_TOKEN") {
		t.Fatalf("API leaked personal relay secret: %s", goodRelay.Body.String())
	}
	var relayResp map[string]any
	_ = json.Unmarshal(goodRelay.Body.Bytes(), &relayResp)
	if relayResp["personal_host"] != "my-worker.example.dev" || relayResp["personal_only"] != true {
		t.Fatalf("unexpected relay response: %v", relayResp)
	}

	// Disable personal_enabled -> host remains saved!
	offRelay := doPOST("set_relay_settings", map[string]any{"personal_enabled": false})
	_ = json.Unmarshal(offRelay.Body.Bytes(), &relayResp)
	if relayResp["personal_enabled"] != false || relayResp["has_personal_url"] != true || relayResp["personal_only"] != false {
		t.Fatalf("disabling personal_enabled should keep saved URL and turn off personal_only: %v", relayResp)
	}

	// Clear personal relay -> removes host!
	clrRelay := doPOST("clear_personal_relay", map[string]any{})
	_ = json.Unmarshal(clrRelay.Body.Bytes(), &relayResp)
	if relayResp["has_personal_url"] != false || relayResp["personal_host"] != "" {
		t.Fatalf("clear_personal_relay failed: %v", relayResp)
	}

	// 4. Announcement acknowledgement endpoint
	ackRR := doPOST("ack_announcement", map[string]any{"id": "notice-42"})
	if ackRR.Code != http.StatusOK {
		t.Fatalf("ack_announcement failed: %d %s", ackRR.Code, ackRR.Body.String())
	}

	// 5. FriendlyError mappings for HTTP 451 RegionBlockedError, errno 160 TOTP, and CaptchaCancelledError
	fe451 := FriendlyError(&foxy.RegionBlockedError{Exhausted: "all-failed"})
	if fe451.Status != 451 || fe451.Extra["region_blocked"] != true {
		t.Fatalf("unexpected FriendlyError for 451: %+v", fe451)
	}
	feTotp := FriendlyError(&foxy.FxaAPIError{Errno: 160, Status: 400})
	if feTotp.Status != 400 || feTotp.Extra["errno"] != 160 {
		t.Fatalf("unexpected FriendlyError for errno 160: %+v", feTotp)
	}
	feCap := FriendlyError(foxy.CaptchaCancelledError{})
	if feCap.Status != 499 || feCap.Extra["captcha_cancelled"] != true {
		t.Fatalf("unexpected FriendlyError for CaptchaCancelledError: %+v", feCap)
	}
}
