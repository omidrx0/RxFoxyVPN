package foxy

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func fakeResponse(r *http.Request, status int, body string) *http.Response {
	return &http.Response{StatusCode: status, Header: make(http.Header),
		Body: io.NopCloser(strings.NewReader(body)), Request: r}
}

func readJSONBody(t *testing.T, r *http.Request) map[string]any {
	t.Helper()
	var body map[string]any
	raw, _ := io.ReadAll(r.Body)
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatalf("request body is not JSON: %v", err)
	}
	return body
}

func TestParseChallengeInitPicksMatchingPrefixAndSurvivesBracketsInValues(t *testing.T) {
	script := `function init(a, b) {} ` +
		`init([{"ty":"pow","data":{"base":"x]y"}}], "tok1", "/_fs-ch-AAA"); ` +
		`init([{"ty":"clientmetrics","data":{}}], "tok2", "/_fs-ch-BBB");`
	challenges, token, err := parseChallengeInit(script, "/_fs-ch-AAA")
	if err != nil {
		t.Fatal(err)
	}
	if token != "tok1" || len(challenges) != 1 || challenges[0]["ty"] != "pow" {
		t.Fatalf("got token %q challenges %v", token, challenges)
	}
	if _, _, err := parseChallengeInit(script, "/_fs-ch-CCC"); err == nil {
		t.Fatal("a call for another prefix must not be accepted")
	}
}

func TestOAuthTokenUsesAuthorizationCodeAfter406(t *testing.T) {
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	var calls []string
	var challenge string
	cp.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		calls = append(calls, r.URL.String())
		if r.Header.Get("Accept-Encoding") != "identity" ||
			r.Header.Get("Content-Type") != "application/json; charset=utf-8" ||
			r.Header.Get("Sec-Fetch-Site") != "none" || r.Header.Get("User-Agent") != UserAgent {
			t.Errorf("OAuth request to %s lacks the Firefox OAuth profile", r.URL)
		}
		body := readJSONBody(t, r)
		switch r.URL.String() {
		case fxaAuthServer + "/oauth/token":
			if len(calls) != 1 || body["grant_type"] != "fxa-credentials" ||
				!strings.HasPrefix(r.Header.Get("Authorization"), "Hawk ") {
				t.Error("expected the original session exchange first")
			}
			return fakeResponse(r, 406, ""), nil
		case fxaAuthServer + "/oauth/authorization":
			state, _ := body["state"].(string)
			challenge, _ = body["code_challenge"].(string)
			if len(calls) != 2 || state == "" || len(challenge) != 43 || body["code_challenge_method"] != "S256" ||
				body["access_type"] != "offline" || !strings.HasPrefix(r.Header.Get("Authorization"), "Hawk ") {
				t.Error("expected a session-authenticated offline authorization with PKCE")
			}
			return fakeResponse(r, 200, `{"code":"fixture-code","state":"`+state+`"}`), nil
		case fxaOAuthTokenURL:
			verifier, _ := body["code_verifier"].(string)
			digest := sha256.Sum256([]byte(verifier))
			if len(calls) != 3 || body["grant_type"] != "authorization_code" || body["code"] != "fixture-code" ||
				r.Header.Get("Authorization") != "" || base64.RawURLEncoding.EncodeToString(digest[:]) != challenge {
				t.Error("expected the issued code with its matching verifier and no session credential")
			}
			return fakeResponse(r, 200, `{"access_token":"fixture-access","refresh_token":"fixture-refresh"}`), nil
		}
		t.Errorf("unexpected target %s", r.URL)
		return fakeResponse(r, 500, ""), nil
	})
	f := &Fxa{cp: cp}
	tok, err := f.OAuthFromSession(context.Background(), strings.Repeat("00", 32))
	if err != nil || tok.AccessToken != "fixture-access" || tok.RefreshToken != "fixture-refresh" || len(calls) != 3 {
		t.Fatalf("sign-in via authorization code failed: %v (calls=%v)", err, calls)
	}
}

func TestLoginSolvesChallengeAndCookieReachesEveryFxaHost(t *testing.T) {
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	const prefix = "/_fs-ch-T1"
	solves := 0
	cp.client.Transport = roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.Header.Get("Accept-Encoding") != "identity" || r.Header.Get("User-Agent") != UserAgent {
			t.Errorf("request to %s has Accept-Encoding %q, User-Agent %q", r.URL,
				r.Header.Get("Accept-Encoding"), r.Header.Get("User-Agent"))
		}
		_, cookieErr := r.Cookie("fx")
		hasCookie := cookieErr == nil
		switch r.URL.Path {
		case "/v1/account/login":
			if hasCookie {
				return fakeResponse(r, 200, `{"sessionToken":"aa","verified":true}`), nil
			}
			return fakeResponse(r, 406, ""), nil
		case "/":
			if hasCookie {
				return fakeResponse(r, 200, "normal page"), nil
			}
			return fakeResponse(r, 200, `Client Challenge <script src="`+prefix+`/script.js"></script>`), nil
		case prefix + "/script.js":
			return fakeResponse(r, 200, `init([{"ty":"clientmetrics","data":{}}], "tok", "`+prefix+`")`), nil
		case prefix + "/fst-post-back":
			solves++
			resp := fakeResponse(r, 200, `{"status":"success"}`)
			resp.Header.Set("Set-Cookie", "fx=cleared; Path=/; Secure")
			return resp, nil
		}
		t.Errorf("unexpected request to %s", r.URL)
		return fakeResponse(r, 500, ""), nil
	})
	f := &Fxa{cp: cp}
	out, err := f.Login(context.Background(), "user@example.com", "password")
	if err != nil || out.SessionToken != "aa" || solves != 1 {
		t.Fatalf("login after 406 failed: %v (solves=%d)", err, solves)
	}
	for _, target := range []string{"https://oauth.accounts.firefox.com/", "https://accounts.firefox.com/"} {
		req, _ := http.NewRequest("GET", target, nil)
		if cookies := cp.jar.Cookies(req.URL); len(cookies) == 0 {
			t.Errorf("challenge cookie did not reach %s", target)
		}
	}
}
