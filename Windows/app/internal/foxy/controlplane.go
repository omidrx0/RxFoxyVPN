package foxy

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"

	utls "github.com/refraction-networking/utls"

	"rxvpn/internal/sysproxy"
)

const maxBody = 16 << 20

// Response is a fully read HTTP answer.
type Response struct {
	Status int
	Header http.Header
	Body   []byte
}

// Text returns the body as a string.
func (r *Response) Text() string { return string(r.Body) }

// OK mirrors requests.Response.ok (status below 400).
func (r *Response) OK() bool { return r.Status < 400 }

// ControlPlane is the HTTP client for Firefox Accounts, Guardian and Remote Settings. It also
// contains the Fastly "Client Challenge" solver.
type ControlPlane struct {
	client *http.Client

	jar         http.CookieJar
	challengeMu sync.Mutex
}

// NewControlPlane builds the client. apiProxy (optional) is used ONLY for these control
// requests; without it the environment / Windows system proxy is followed.
func NewControlPlane(apiProxy string) (*ControlPlane, error) {
	// Mozilla's servers sit behind Fastly, which tells clients apart by their TLS hello and may
	// answer HTTP 406 to one that is not a browser's, such as Go's own. Direct connections
	// therefore start with desktop Firefox's hello (HTTP/1.1, as the reference client does).
	// A configured proxy (system or --api-proxy) makes net/http perform the TLS itself; that
	// path keeps Go's hello.
	sessions := utls.NewLRUClientSessionCache(64)
	dialer := &net.Dialer{Timeout: 20 * time.Second, KeepAlive: 30 * time.Second}
	tr := &http.Transport{
		Proxy:       sysproxy.ProxyFunc,
		DialContext: dialer.DialContext,
		DialTLSContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			host, _, err := net.SplitHostPort(address)
			if err != nil {
				return nil, err
			}
			raw, err := dialer.DialContext(ctx, network, address)
			if err != nil {
				return nil, err
			}
			shakeCtx, cancel := context.WithTimeout(ctx, 20*time.Second)
			defer cancel()
			conn, err := firefoxHandshake(shakeCtx, raw, host, nil, sessions, "http/1.1")
			if err != nil {
				raw.Close()
				return nil, err
			}
			return conn, nil
		},
		TLSHandshakeTimeout:   20 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		IdleConnTimeout:       60 * time.Second,
		MaxIdleConnsPerHost:   4,
		ForceAttemptHTTP2:     false,
	}
	if apiProxy != "" {
		u, err := url.Parse(apiProxy)
		if err != nil || u.Host == "" {
			return nil, fmt.Errorf("invalid --api-proxy %q", apiProxy)
		}
		tr.Proxy = http.ProxyURL(u)
	}
	jar, err := cookiejar.New(nil)
	if err != nil {
		return nil, fmt.Errorf("create HTTP cookie jar: %w", err)
	}
	client := &http.Client{Transport: tr, Timeout: 30 * time.Second, Jar: jar}
	return &ControlPlane{client: client, jar: jar}, nil
}

func readResponse(resp *http.Response) (*Response, error) {
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	if err != nil {
		return nil, err
	}
	return &Response{Status: resp.StatusCode, Header: resp.Header, Body: data}, nil
}

// Request sends one request. A nil body means "no body"; an empty non-nil body is sent as such.
func (c *ControlPlane) Request(ctx context.Context, method, rawURL string, headers map[string]string, body []byte) (*Response, error) {
	var rdr io.Reader
	if body != nil {
		rdr = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, rawURL, rdr)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", UserAgent)
	req.Header.Set("Accept", "*/*")
	// Without this net/http adds "Accept-Encoding: gzip", which the reference client found
	// associated with HTTP 406 from the account servers.
	req.Header.Set("Accept-Encoding", "identity")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	return readResponse(resp)
}

// ---------------------------------------------------------------- Fastly "Client Challenge" solver

const (
	fxaAPIHost = "https://api.accounts.firefox.com"
	fxaSiteURL = "https://accounts.firefox.com/"

	// fastlySolveTimeout bounds one full challenge-solving attempt (page, script, answers,
	// post-back, probe are several sequential requests).
	fastlySolveTimeout = 60 * time.Second

	// maxPostBackRounds caps follow-up challenge rounds returned by fst-post-back.
	maxPostBackRounds = 8
)

var (
	challengePrefixRe     = regexp.MustCompile(`/_fs-ch-[A-Za-z0-9]+`)
	challengeInitStartRe  = regexp.MustCompile(`\binit\s*\(\s*\[`)
	challengeInitLegacyRe = regexp.MustCompile(`^init\((\[[^\]]*\]),\s*"([^"]+)",\s*"([^"]+)"`)
)

// SolveChallenge handles the Fastly challenge flow and installs the earned anti-bot cookies in
// the jar the API requests use. Solving runs on a throw-away client with a fresh jar: a client
// that already holds a valid challenge cookie is served the real page instead of the challenge,
// so it could never solve again (the API can answer 406 again after a first solve).
func (c *ControlPlane) SolveChallenge(ctx context.Context) error {
	c.challengeMu.Lock()
	defer c.challengeMu.Unlock()
	if err := ctx.Err(); err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, fastlySolveTimeout)
	defer cancel()

	var last error
	for _, base := range []string{fxaAPIHost, strings.TrimRight(fxaSiteURL, "/")} {
		if err := ctx.Err(); err != nil {
			return err
		}
		err := c.solveOnHost(ctx, base)
		if err == nil {
			return nil
		}
		if errors.Is(err, errNoChallengePage) {
			continue
		}
		var fe *FastlyChallengeError
		if errors.As(err, &fe) {
			last = err
			continue
		}
		return err // network problems propagate
	}
	if last != nil {
		return last
	}
	return &FastlyChallengeError{Msg: "host did not serve a Fastly challenge page"}
}

func solvePoW(base, targetHex string) (string, bool) {
	target, err := hex.DecodeString(targetHex)
	if err != nil || len(target) != 32 {
		return "", false
	}
	for _, a := range powAlphabet {
		for _, b := range powAlphabet {
			sum := sha256.Sum256([]byte(base + string(a) + string(b)))
			if bytes.Equal(sum[:], target) {
				return string(a) + string(b), true
			}
		}
	}
	return "", false
}

func fastlyErr(format string, args ...any) error {
	return &FastlyChallengeError{Msg: fmt.Sprintf(format, args...)}
}

// installChallengeCookies copies the cookies the solver earned into the API jar for the sign-in
// site and both FxA API origins (as host-only cookies, so a cookie Fastly scoped to one host
// still reaches the API host that answered 406).
func (c *ControlPlane) installChallengeCookies(solverJar http.CookieJar, pageURL string) error {
	page, err := url.Parse(pageURL)
	if err != nil {
		return err
	}
	cookies := solverJar.Cookies(page)
	if len(cookies) == 0 {
		return fastlyErr("challenge completed but no applicable cookies were issued")
	}
	for _, target := range []string{
		fxaAuthServer + "/",
		"https://api.accounts.firefox.com/",
		"https://oauth.accounts.firefox.com/",
		fxaSiteURL,
	} {
		u, err := url.Parse(target)
		if err != nil {
			return err
		}
		c.jar.SetCookies(u, cookies)
	}
	return nil
}

func (c *ControlPlane) solveOnHost(ctx context.Context, base string) error {
	jar, err := cookiejar.New(nil)
	if err != nil {
		return err
	}
	hc := &http.Client{Transport: c.client.Transport, Jar: jar, Timeout: fastlySolveTimeout}
	pageURL := base + "/"

	do := func(method, rawURL string, headers map[string]string, body []byte) (*Response, error) {
		var rdr io.Reader
		if body != nil {
			rdr = bytes.NewReader(body)
		}
		req, err := http.NewRequestWithContext(ctx, method, rawURL, rdr)
		if err != nil {
			return nil, err
		}
		// The client that earns the cookie must be the one that presents it: same Firefox
		// User-Agent and the same identity encoding as the API requests.
		req.Header.Set("User-Agent", UserAgent)
		req.Header.Set("Accept-Encoding", "identity")
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		resp, err := hc.Do(req)
		if err != nil {
			return nil, err
		}
		return readResponse(resp)
	}
	getText := func(rawURL string) (string, error) {
		r, err := do("GET", rawURL, map[string]string{
			"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"}, nil)
		if err != nil {
			return "", err
		}
		if !r.OK() {
			return "", fastlyErr("GET %s returned HTTP %d", rawURL, r.Status)
		}
		return r.Text(), nil
	}
	fetchPage := func() (string, bool, error) {
		body, err := getText(pageURL)
		if err != nil {
			return "", false, err
		}
		return body, strings.Contains(body, "/_fs-ch-") && strings.Contains(body, "Client Challenge"), nil
	}

	page, isChallenge, err := fetchPage()
	if err != nil {
		return err
	}
	if !isChallenge {
		return errNoChallengePage
	}
	m := challengePrefixRe.FindString(page)
	if m == "" {
		return fastlyErr("challenge asset prefix not found on %s", base)
	}
	prefix := base + m
	origin := prefix[:strings.Index(prefix, "/_fs-ch-")]

	script, err := getText(prefix + "/script.js?reload=true")
	if err != nil {
		return err
	}
	challenges, token, err := parseChallengeInit(script, m)
	if err != nil {
		return fastlyErr("%v", err)
	}

	answer := func(ch map[string]any) (map[string]any, error) {
		ty, _ := ch["ty"].(string)
		data, _ := ch["data"].(map[string]any)
		str := func(k string) string { s, _ := data[k].(string); return s }
		switch ty {
		case "pow":
			b := str("base")
			ans, ok := solvePoW(b, str("hash"))
			if !ok {
				return nil, fastlyErr("no proof-of-work solution found for base %s", b)
			}
			var expires any = ""
			if v, ok := data["expires"]; ok {
				expires = v
			}
			return map[string]any{"ty": "pow", "base": b, "answer": ans, "hmac": str("hmac"), "expires": expires}, nil
		case "pat":
			r, err := do("POST", prefix+"/pat?token="+url.QueryEscape(token), map[string]string{
				"Accept": "text/plain", "Content-Type": "application/json", "Origin": origin}, []byte{})
			if err != nil {
				return nil, err
			}
			if r.Status == 400 || r.Status == 401 {
				return map[string]any{"ty": "pat", "auth": ""}, nil
			}
			if !r.OK() {
				return nil, fastlyErr("PAT request returned HTTP %d", r.Status)
			}
			var pat struct {
				Auth string `json:"auth"`
			}
			_ = json.Unmarshal(r.Body, &pat)
			if pat.Auth == "" {
				return nil, fastlyErr("empty PAT auth token")
			}
			return map[string]any{"ty": "pat", "auth": pat.Auth}, nil
		case "clientmetrics":
			return map[string]any{
				"ty": "clientmetrics", "webdriver": false,
				"bot_detection_result": map[string]any{"bot_detected": false, "bot_kind": nil},
				"browser_metrics":      map[string]any{"client_data": "{}", "error_trace": nil},
				"detector_results":     map[string]any{}, "v": 2,
			}, nil
		}
		return nil, fastlyErr("unsupported Fastly challenge type \x27%s\x27 (captcha cannot be solved automatically)", ty)
	}

	for round := 0; round < maxPostBackRounds; round++ {
		answers := make([]map[string]any, 0, len(challenges))
		for _, ch := range challenges {
			a, err := answer(ch)
			if err != nil {
				return err
			}
			answers = append(answers, a)
		}
		payload, err := json.Marshal(map[string]any{"token": token, "data": answers})
		if err != nil {
			return err
		}
		r, err := do("POST", prefix+"/fst-post-back", map[string]string{
			"Content-Type": "application/json", "Accept": "application/json", "Origin": origin}, payload)
		if err != nil {
			return err
		}
		if !r.OK() {
			return fastlyErr("challenge post-back returned HTTP %d", r.Status)
		}
		var reply struct {
			Status string           `json:"status"`
			Ch     []map[string]any `json:"ch"`
			Tok    string           `json:"tok"`
		}
		if err := json.Unmarshal(r.Body, &reply); err != nil {
			return fastlyErr("unexpected post-back response")
		}
		if reply.Status == "success" {
			// The challenge page must clear before the cookies are installed; the caller then
			// verifies them by replaying the original request.
			_, still, err := fetchPage()
			if err != nil {
				return err
			}
			if still {
				return fastlyErr("challenge cookie not accepted on this exit IP")
			}
			if err := c.installChallengeCookies(jar, pageURL); err != nil {
				return err
			}
			slog.Info("Fastly challenge solved", "host", strings.TrimPrefix(base, "https://"), "rounds", round+1)
			return nil
		}
		if len(reply.Ch) == 0 || reply.Tok == "" {
			return fastlyErr("challenge post-back is missing the next round")
		}
		challenges, token = reply.Ch, reply.Tok
	}
	return fastlyErr("Fastly challenge did not complete within %d rounds", maxPostBackRounds)
}

// parseChallengeInit reads the first three arguments of an init call for the fetched prefix,
// newest call first. It never evaluates JavaScript, tolerates "]" inside values, and accepts only
// a call whose own prefix equals the page's.
func parseChallengeInit(script, prefix string) ([]map[string]any, string, error) {
	calls := challengeInitStartRe.FindAllStringIndex(script, -1)
	var legacyChallenges []map[string]any
	var legacyToken string
	for i := len(calls) - 1; i >= 0; i-- {
		call := calls[i]
		var challenges []map[string]any
		var token, scriptPrefix string
		rest, ok := decodeChallengeInitArgs(script[call[1]-1:], &challenges, &token, &scriptPrefix)
		if !ok || scriptPrefix != prefix || token == "" || len(challenges) == 0 ||
			slices.ContainsFunc(challenges, func(ch map[string]any) bool {
				ty, _ := ch["ty"].(string)
				return ty == ""
			}) {
			continue
		}
		if hasChallengeInitDelimiter(rest) {
			return challenges, token, nil
		}
		if legacyChallenges == nil && challengeInitLegacyRe.MatchString(script[call[0]:len(script)-len(rest)]) &&
			hasLegacyChallengeInitDelimiter(rest) {
			legacyChallenges, legacyToken = challenges, token
		}
	}
	if legacyChallenges != nil {
		return legacyChallenges, legacyToken, nil
	}
	return nil, "", errors.New("valid challenge init call not found in script")
}

func decodeChallengeInitArgs(rest string, destinations ...any) (string, bool) {
	for i, destination := range destinations {
		if i > 0 {
			var ok bool
			rest, ok = strings.CutPrefix(rest, ",")
			if !ok {
				return "", false
			}
		}
		decoder := json.NewDecoder(strings.NewReader(rest))
		if decoder.Decode(destination) != nil {
			return "", false
		}
		rest = strings.TrimSpace(rest[decoder.InputOffset():])
	}
	return rest, true
}

func hasChallengeInitDelimiter(rest string) bool {
	if strings.HasPrefix(rest, ")") {
		return true
	}
	after, ok := strings.CutPrefix(rest, ",")
	return ok && strings.TrimSpace(after) != ""
}

func hasLegacyChallengeInitDelimiter(rest string) bool {
	for strings.HasPrefix(rest, "/*") {
		_, after, ok := strings.Cut(rest[2:], "*/")
		if !ok {
			return false
		}
		rest = strings.TrimSpace(after)
	}
	return hasChallengeInitDelimiter(rest)
}
