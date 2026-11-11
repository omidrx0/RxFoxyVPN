package foxy

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestEmailAndCodeNormalization(t *testing.T) {
	// NormalizeEmail strips spaces & invisible Unicode Cf chars (ZWNJ/BOM) while preserving case for PBKDF2 quickStretch.
	if got := NormalizeEmail("  \u200CAlice.Test@Example.COM\uFEFF \n"); got != "Alice.Test@Example.COM" {
		t.Fatalf("NormalizeEmail got %q", got)
	}
	// NormalizeCode converts Persian/Arabic-Indic digits and strips spaces/hyphens.
	if got := NormalizeCode(" ۱۲۳-۴۵۶\t۷۸ "); got != "12345678" {
		t.Fatalf("NormalizeCode got %q", got)
	}
}

func TestDecodeCaptchaDataURISecurityAndFormats(t *testing.T) {
	// 1x1 valid PNG bytes
	pngRaw := []byte{
		0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
		0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
	}
	b64PNG := base64.StdEncoding.EncodeToString(pngRaw)
	img, err := DecodeCaptchaDataURI("data:image/png;base64," + b64PNG)
	if err != nil || img.MimeType != "image/png" || !strings.HasPrefix(img.DataURI, "data:image/png;base64,") {
		t.Fatalf("valid PNG failed: img=%+v err=%v", img, err)
	}

	// Safe SVG passes
	safeSVG := base64.StdEncoding.EncodeToString([]byte(`<svg xmlns="http://www.w3.org/2000/svg"><text>AB12</text></svg>`))
	svgImg, err := DecodeCaptchaDataURI(safeSVG)
	if err != nil || svgImg.MimeType != "image/svg+xml" {
		t.Fatalf("safe SVG failed: %v", err)
	}

	// Malicious SVGs with <script>, onload=, or javascript: MUST be rejected!
	badSVGs := []string{
		`<svg><script>alert(1)</script></svg>`,
		`<svg onload="alert(1)"><rect width="10" height="10"/></svg>`,
		`<svg><a href="javascript:evil()"><text>x</text></a></svg>`,
		`<svg><foreignObject><div>html</div></foreignObject></svg>`,
	}
	for _, s := range badSVGs {
		if _, err := DecodeCaptchaDataURI(base64.StdEncoding.EncodeToString([]byte(s))); err == nil {
			t.Fatalf("malicious SVG should have been rejected: %s", s)
		}
	}
}

func TestInteractiveFastlyCaptchaEndToEndOnDirectLogin(t *testing.T) {
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}

	pngB64 := base64.StdEncoding.EncodeToString([]byte{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4})
	var captchaSolved atomic.Bool

	cp.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		switch {
		case r.URL.Path == "/v1/account/login" && !captchaSolved.Load():
			return fakeResponse(r, 406, `{"errno":999}`), nil

		case r.URL.Path == "/" && !captchaSolved.Load():
			html := `<html><head><title>Client Challenge</title>` +
				`<script src="/_fs-ch-1T16220780793700/script.js?reload=true"></script></head></html>`
			resp := fakeResponse(r, 200, html)
			resp.Header.Add("Set-Cookie", "_fs_ch_st=init1; Path=/")
			return resp, nil

		case r.URL.Path == "/" && captchaSolved.Load():
			return fakeResponse(r, 200, `<html>ok</html>`), nil

		case strings.HasPrefix(r.URL.Path, "/_fs-ch-1T16220780793700/script.js"):
			js := fmt.Sprintf(`function init(a,b){} init([{"ty":"captcha","data":{"image_b64":%q}}], "cap-tok-1", "/_fs-ch-1T16220780793700");`, pngB64)
			return fakeResponse(r, 200, js), nil

		case strings.HasPrefix(r.URL.Path, "/_fs-ch-1T16220780793700/") && r.Method == http.MethodPost:
			body := readJSONBody(t, r)
			sols, _ := body["data"].([]any)
			if len(sols) != 1 {
				t.Fatalf("expected 1 captcha solution, got %v", body)
			}
			sol, _ := sols[0].(map[string]any)
			if sol["answer"] != "ZX89" {
				return fakeResponse(r, 403, `{"error":"wrong"}`), nil
			}
			captchaSolved.Store(true)
			resp := fakeResponse(r, 200, `{"status":"success"}`)
			resp.Header.Add("Set-Cookie", "_fs_ch_cp=cleared1; Path=/")
			return resp, nil

		case r.URL.Path == "/v1/account/login" && captchaSolved.Load():
			if !strings.Contains(r.Header.Get("Cookie"), "_fs_ch_cp=cleared1") {
				t.Fatalf("clearance cookie missing on retried login: %q", r.Header.Get("Cookie"))
			}
			return fakeResponse(r, 200, `{"uid":"u1","sessionToken":"`+strings.Repeat("ab", 32)+`","verified":true}`), nil

		default:
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.String())
			return nil, nil
		}
	})

	// Drive user answer asynchronously once CaptchaBroker publishes the prompt!
	go func() {
		deadline := time.Now().Add(3 * time.Second)
		for time.Now().Before(deadline) {
			if p := cp.CaptchaBroker().Current(); p != nil && p.ImageDataURI != "" {
				_ = cp.CaptchaBroker().Submit("ZX89")
				return
			}
			time.Sleep(15 * time.Millisecond)
		}
	}()

	fxa := &Fxa{cp: cp}
	ctx := WithInteractiveCaptcha(context.Background())
	res, err := fxa.Login(ctx, "user@example.com", "secret-pass")
	if err != nil {
		t.Fatalf("Login with interactive CAPTCHA failed: %v", err)
	}
	if !res.Verified || !captchaSolved.Load() {
		t.Fatalf("expected verified login after solved CAPTCHA, got %+v", res)
	}
}

func TestFxa2FATOTPUnblockNilBodyHawkAndRegionBlocked451(t *testing.T) {
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}

	sessionHex := strings.Repeat("cd", 32)
	var sawPaths []string
	var getHawkHeader string

	cp.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		sawPaths = append(sawPaths, r.Method+" "+r.URL.RequestURI())
		switch r.URL.Path {
		case "/v1/recovery_email/status":
			getHawkHeader = r.Header.Get("Authorization")
			return fakeResponse(r, 200, `{"email":"user@example.com","verified":true}`), nil
		case "/v1/session/verify_code":
			return fakeResponse(r, 200, `{}`), nil
		case "/v1/session/verify/totp":
			b := readJSONBody(t, r)
			if b["code"] != "654321" {
				t.Fatalf("unexpected TOTP payload: %v", b)
			}
			return fakeResponse(r, 200, `{"success":true}`), nil
		case "/v1/account/login/send_unblock_code":
			return fakeResponse(r, 200, `{}`), nil
		case "/v1/account/login":
			b := readJSONBody(t, r)
			if b["unblockCode"] == "ABCD1234" {
				return fakeResponse(r, 200, `{"uid":"u","sessionToken":"`+sessionHex+`","verified":false,"verificationMethod":"totp-2fa"}`), nil
			}
			return fakeResponse(r, 451, `blocked`), nil
		default:
			return fakeResponse(r, 404, `{}`), nil
		}
	})

	fxa := &Fxa{cp: cp}

	// 1. GET /recovery_email/status MUST omit hash="..." in HAWK Authorization header!
	st, err := fxa.RecoveryEmailStatus(context.Background(), sessionHex)
	if err != nil || !st.Verified {
		t.Fatalf("RecoveryEmailStatus failed: %v", err)
	}
	if strings.Contains(getHawkHeader, `hash="`) || !strings.HasPrefix(getHawkHeader, "Hawk id=") {
		t.Fatalf("GET HAWK header must omit payload hash, got: %s", getHawkHeader)
	}

	// 2. VerifyCode dispatches email-2fa -> /session/verify_code and totp-2fa -> /totp/verify!
	if err := fxa.VerifyCode(context.Background(), sessionHex, "112 233", "email-2fa"); err != nil {
		t.Fatalf("email-2fa verify failed: %v", err)
	}
	if err := fxa.VerifyCode(context.Background(), sessionHex, "654-321", "totp-2fa"); err != nil {
		t.Fatalf("totp-2fa verify failed: %v", err)
	}

	// 3. SendUnblockCode & LoginWithUnblock
	if err := fxa.SendUnblockCode(context.Background(), "User@Example.com"); err != nil {
		t.Fatalf("SendUnblockCode failed: %v", err)
	}
	lr, err := fxa.LoginWithUnblock(context.Background(), "user@example.com", "pw", "ABCD-1234")
	if err != nil || lr.VerificationMethod != "totp-2fa" {
		t.Fatalf("LoginWithUnblock failed: lr=%+v err=%v", lr, err)
	}

	// 4. HTTP 451 maps to *RegionBlockedError (never generic parse error)
	_, err = fxa.Login(context.Background(), "user@example.com", "pw")
	var rb *RegionBlockedError
	if !errors.As(err, &rb) {
		t.Fatalf("expected *RegionBlockedError on HTTP 451, got %T (%v)", err, err)
	}
}

func TestWebChannelAndBrowserPKCELoopbackSecurity(t *testing.T) {
	// 1. Origin & navigation allowlist checks
	if !IsTrustedBridgeOrigin("https://accounts.firefox.com") ||
		IsTrustedBridgeOrigin("https://evil-firefox.com") ||
		IsTrustedBridgeOrigin("http://accounts.firefox.com") {
		t.Fatal("IsTrustedBridgeOrigin check failed")
	}
	if !IsBrowserSignInNavigationAllowed("https://accounts.firefox.com/?context=fx_desktop_v3") ||
		IsBrowserSignInNavigationAllowed("https://attacker.example.com/phish") ||
		IsBrowserSignInNavigationAllowed("javascript:alert(1)") {
		t.Fatal("IsBrowserSignInNavigationAllowed check failed")
	}

	// 2. WebChannel login message extraction
	validTok := strings.Repeat("ef", 32)
	msgJSON := fmt.Sprintf(`{"id":"account_updates","message":{"command":"fxaccounts:login","messageId":"m1","data":{"email":"Alice@Example.com","sessionToken":%q}}}`, validTok)
	wcRes, err := HandleWebChannelMessage(msgJSON)
	if err != nil || wcRes.SessionToken != validTok || wcRes.Email != "Alice@Example.com" || wcRes.ReplyJS == "" {
		t.Fatalf("HandleWebChannelMessage failed: %+v err=%v", wcRes, err)
	}

	// 3. External Browser OAuth 2.0 + PKCE loopback anti-forgery & anti-duplicate tests
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	var tokenExchanges atomic.Int32
	var gotVerifier string

	cp.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Host {
		case "oauth.accounts.firefox.com":
			tokenExchanges.Add(1)
			b := readJSONBody(t, r)
			gotVerifier, _ = b["code_verifier"].(string)
			if b["client_id"] != firefoxPublicOAuthClientID || b["code"] != "AUTH_CODE_1" {
				t.Fatalf("unexpected OAuth PKCE body: %v", b)
			}
			return fakeResponse(r, 200, `{"access_token":"acc_pkce","refresh_token":"ref_pkce","expires_in":3600}`), nil
		case "profile.accounts.firefox.com":
			return fakeResponse(r, 200, `{"email":"pkce-user@example.com"}`), nil
		default:
			return fakeResponse(r, 404, `{}`), nil
		}
	})

	statePath := filepath.Join(t.TempDir(), "state.json")
	acct := NewAccount(cp, statePath)

	var openedURL string
	completedCh := make(chan string, 1)
	coord := NewBrowserAuthCoordinator(acct, BrowserAuthStarter{
		OpenExternalURL: func(u string) error {
			openedURL = u
			return nil
		},
	}, func(_ context.Context, email string) {
		completedCh <- email
	})
	defer coord.Cancel()

	st, err := coord.Start(BrowserModeExternal, "pkce-user@example.com")
	if err != nil || !st.Active || st.Mode != BrowserModeExternal || st.CallbackPort <= 0 {
		t.Fatalf("BrowserAuthCoordinator.Start failed: %+v err=%v", st, err)
	}

	parsedAuth, err := url.Parse(openedURL)
	if err != nil {
		t.Fatal(err)
	}
	q := parsedAuth.Query()
	stateParam := q.Get("state")
	challengeParam := q.Get("code_challenge")
	if q.Get("code_challenge_method") != "S256" || len(challengeParam) < 43 {
		t.Fatalf("missing S256 PKCE parameters in %s", openedURL)
	}

	// Grab internal callbackToken via current session for direct loopback HTTP anti-forgery test!
	coord.mu.Lock()
	cbTok := coord.cur.callbackToken
	coord.mu.Unlock()

	baseLoopback := fmt.Sprintf("http://127.0.0.1:%d/oauth/callback/", st.CallbackPort)

	// (a) Forged token -> HTTP 400, listener MUST stay alive!
	resp1, err := http.Get(baseLoopback + "wrong-token?code=AUTH_CODE_1&state=" + stateParam)
	if err != nil || resp1.StatusCode != http.StatusBadRequest {
		t.Fatalf("forged callback token should return 400, got %v err=%v", resp1, err)
	}
	_ = resp1.Body.Close()

	// (b) Forged state -> HTTP 400, listener MUST stay alive!
	resp2, err := http.Get(baseLoopback + cbTok + "?code=AUTH_CODE_1&state=forged_state_value")
	if err != nil || resp2.StatusCode != http.StatusBadRequest {
		t.Fatalf("forged OAuth state should return 400, got %v err=%v", resp2, err)
	}
	_ = resp2.Body.Close()

	// (c) Valid callback + duplicate second callback -> ONLY 1 token exchange!
	validCallbackURL := baseLoopback + cbTok + "?code=AUTH_CODE_1&state=" + url.QueryEscape(stateParam)
	resp3, err := http.Get(validCallbackURL)
	if err != nil || resp3.StatusCode != http.StatusOK {
		t.Fatalf("valid callback failed: %v err=%v", resp3, err)
	}
	_ = resp3.Body.Close()

	resp4, err := http.Get(validCallbackURL)
	if err == nil {
		_ = resp4.Body.Close()
	}

	select {
	case email := <-completedCh:
		if email != "pkce-user@example.com" {
			t.Fatalf("unexpected completed email: %q", email)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for PKCE completion")
	}

	if tokenExchanges.Load() != 1 {
		t.Fatalf("expected exactly 1 PKCE token exchange, got %d", tokenExchanges.Load())
	}
	// Verify RFC 7636 S256: SHA256(gotVerifier) == challengeParam!
	sum := sha256.Sum256([]byte(gotVerifier))
	if base64.RawURLEncoding.EncodeToString(sum[:]) != challengeParam {
		t.Fatal("PKCE code_verifier did not match code_challenge")
	}
	if !acct.HasSession() || acct.Email() != "pkce-user@example.com" {
		t.Fatal("account session was not stored via unified post-login path")
	}
}

func TestFourStateGuardianQuotaUnlimitedVsNominal50GB(t *testing.T) {
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	acct := NewAccount(cp, filepath.Join(t.TempDir(), "state.json"))
	_ = acct.StoreTokens(&TokenResponse{AccessToken: "tok", RefreshToken: "ref", ExpiresIn: 3600})

	// Case 1: Mozilla returns nominal maxBytes="53687091200" (50 GiB) on /status AND X-Quota-Unlimited: true on /token!
	// Must resolve to QuotaStateUnlimited (NEVER 50 GB!).
	cp.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		switch r.URL.Path {
		case "/api/v1/fpn/status":
			return fakeResponse(r, 200, `{"subscribed":true,"uid":42,"maxBytes":"53687091200"}`), nil
		case "/api/v1/fpn/token":
			resp := fakeResponse(r, 200, `{"token":"pass"}`)
			resp.Header.Set("X-Quota-Unlimited", "true")
			resp.Header.Set("X-Quota-Limit", "53687091200")
			resp.Header.Set("X-Quota-Remaining", "53687091200")
			return resp, nil
		default:
			return fakeResponse(r, 404, `{}`), nil
		}
	})
	info1, err := acct.FetchQuotaInfo(context.Background())
	if err != nil {
		t.Fatalf("FetchQuotaInfo unlimited header failed: %v", err)
	}
	if info1.State != QuotaStateUnlimited || !info1.Subscribed || info1.LimitBytes != nil {
		t.Fatalf("expected Unlimited state ignoring nominal 50GB, got %+v", info1)
	}

	// Case 2: /status explicitly reports limited_bandwidth: false even if /token fails -> Unlimited!
	cp.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/api/v1/fpn/status" {
			return fakeResponse(r, 200, `{"subscribed":true,"maxBytes":"53687091200","limited_bandwidth":false}`), nil
		}
		return fakeResponse(r, 502, `gateway error`), nil
	})
	info2, err := acct.FetchQuotaInfo(context.Background())
	if err != nil || info2.State != QuotaStateUnlimited {
		t.Fatalf("expected Unlimited from limited_bandwidth=false, got %+v err=%v", info2, err)
	}

	// Case 3: Finite 50 GiB quota with 30 GiB remaining -> Limited!
	cp.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.URL.Path == "/api/v1/fpn/status" {
			return fakeResponse(r, 200, `{"subscribed":false,"maxBytes":"53687091200","limited_bandwidth":true}`), nil
		}
		resp := fakeResponse(r, 200, `{}`)
		resp.Header.Set("X-Quota-Limit", "53687091200")
		resp.Header.Set("X-Quota-Remaining", "32212254720")
		resp.Header.Set("X-Quota-Reset", "2026-12-01T00:00:00Z")
		return resp, nil
	})
	info3, err := acct.FetchQuotaInfo(context.Background())
	if err != nil || info3.State != QuotaStateLimited || info3.LimitBytes == nil || *info3.LimitBytes != 53687091200 || *info3.RemainingBytes != 32212254720 {
		t.Fatalf("expected finite Limited quota, got %+v err=%v", info3, err)
	}
}
