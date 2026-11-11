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

// ControlPlane is the HTTP client for Firefox Accounts, Guardian and Remote Settings.
// It integrates the Fastly "Client Challenge" solver (automatic PoW/PAT/clientmetrics +
// interactive image CAPTCHA) and the HTTP 451 / unreachable RelayFallbackCoordinator.
type ControlPlane struct {
	client *http.Client

	jarMu   sync.RWMutex
	jar     http.CookieJar
	relay   *RelayFallbackCoordinator
	captcha *CaptchaBroker

	challengeMu sync.Mutex
}

// dynamicCookieJar delegates to ControlPlane's current CookieJar so ClearCookies() can
// atomically swap in an empty jar when signing out or switching accounts.
type dynamicCookieJar struct {
	cp *ControlPlane
}

func (d dynamicCookieJar) SetCookies(u *url.URL, cookies []*http.Cookie) {
	d.cp.jarMu.RLock()
	j := d.cp.jar
	d.cp.jarMu.RUnlock()
	if j != nil {
		j.SetCookies(u, cookies)
	}
}

func (d dynamicCookieJar) Cookies(u *url.URL) []*http.Cookie {
	d.cp.jarMu.RLock()
	j := d.cp.jar
	d.cp.jarMu.RUnlock()
	if j == nil {
		return nil
	}
	return j.Cookies(u)
}

// NewControlPlane builds the client. apiProxy (optional) is used ONLY for these control
// requests; without it the environment / Windows system proxy is followed.
func NewControlPlane(apiProxy string) (*ControlPlane, error) {
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
	cp := &ControlPlane{
		jar:     jar,
		captcha: NewCaptchaBroker(),
	}
	cp.client = &http.Client{
		Transport: tr,
		Timeout:   30 * time.Second,
		Jar:       dynamicCookieJar{cp: cp},
	}
	return cp, nil
}

// SetRelayCoordinator attaches the HTTP 451 / unreachable relay fallback coordinator.
// When nil, all requests run strictly direct.
func (c *ControlPlane) SetRelayCoordinator(fc *RelayFallbackCoordinator) {
	c.jarMu.Lock()
	c.relay = fc
	c.jarMu.Unlock()
}

// RelayCoordinator returns the attached RelayFallbackCoordinator (may be nil).
func (c *ControlPlane) RelayCoordinator() *RelayFallbackCoordinator {
	c.jarMu.RLock()
	defer c.jarMu.RUnlock()
	return c.relay
}

// CaptchaBroker returns the interactive CAPTCHA broker for UI bindings.
func (c *ControlPlane) CaptchaBroker() *CaptchaBroker {
	return c.captcha
}

// ClearCookies discards all stored Fastly and FxA cookies (used on sign-out and account switch).
func (c *ControlPlane) ClearCookies() {
	if jar, err := cookiejar.New(nil); err == nil {
		c.jarMu.Lock()
		c.jar = jar
		c.jarMu.Unlock()
	}
}

func readResponse(resp *http.Response) (*Response, error) {
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	if err != nil {
		return nil, err
	}
	return &Response{Status: resp.StatusCode, Header: resp.Header, Body: data}, nil
}

func (c *ControlPlane) buildBaseHeaders(headers map[string]string) http.Header {
	h := make(http.Header, len(headers)+3)
	h.Set("User-Agent", UserAgent)
	h.Set("Accept", "*/*")
	h.Set("Accept-Encoding", "identity")
	for k, v := range headers {
		h.Set(k, v)
	}
	return h
}

func (c *ControlPlane) doDirectURL(ctx context.Context, method string, targetURL *url.URL, reqHeaders http.Header, body []byte) (*Response, error) {
	var rdr io.Reader
	if body != nil {
		rdr = bytes.NewReader(body)
	}
	uCopy := *targetURL
	req, err := http.NewRequestWithContext(ctx, method, uCopy.String(), rdr)
	if err != nil {
		return nil, err
	}
	// Preserve doubled leading slash (//api/v1/fpn/...) verbatim on the wire.
	req.URL = &uCopy
	req.Header = reqHeaders.Clone()
	resp, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	return readResponse(resp)
}

// Request sends one control-plane request, applying RelayFallbackCoordinator on allow-listed
// Mozilla/manifest HTTPS targets when HTTP 451 or direct connection failure occurs.
func (c *ControlPlane) Request(ctx context.Context, method, rawURL string, headers map[string]string, body []byte) (*Response, error) {
	parsedURL, err := url.Parse(rawURL)
	if err != nil {
		return nil, err
	}
	reqHeaders := c.buildBaseHeaders(headers)

	c.jarMu.RLock()
	relay := c.relay
	c.jarMu.RUnlock()

	if relay != nil {
		// Snapshot matching cookies onto reqHeaders only if relay forwarding ends up needed;
		// directDo lets c.client.Jar attach/save cookies natively.
		relayHeaders := reqHeaders.Clone()
		if cookies := (dynamicCookieJar{cp: c}).Cookies(parsedURL); len(cookies) > 0 && relayHeaders.Get("Cookie") == "" {
			parts := make([]string, 0, len(cookies))
			for _, ck := range cookies {
				parts = append(parts, ck.Name+"="+ck.Value)
			}
			relayHeaders.Set("Cookie", strings.Join(parts, "; "))
		}
		return relay.Execute(ctx, method, parsedURL, relayHeaders, body, func(dctx context.Context, u *url.URL) (*Response, error) {
			return c.doDirectURL(dctx, method, u, reqHeaders, body)
		})
	}
	return c.doDirectURL(ctx, method, parsedURL, reqHeaders, body)
}

// ---------------------------------------------------------------- Fastly "Client Challenge" solver

const (
	fxaAPIHost = "https://api.accounts.firefox.com"
	fxaSiteURL = "https://accounts.firefox.com/"

	// fastlySolveTimeout bounds automatic (non-CAPTCHA) challenge solving.
	fastlySolveTimeout = 60 * time.Second

	// maxPostBackRounds caps follow-up challenge rounds returned by fst-post-back.
	maxPostBackRounds = 8
)

var (
	challengePrefixRe     = regexp.MustCompile(`/_fs-ch-[A-Za-z0-9]+`)
	challengeInitStartRe  = regexp.MustCompile(`\binit\s*\(\s*\[`)
	challengeInitLegacyRe = regexp.MustCompile(`^init\((\[[^\]]*\]),\s*"([^"]+)",\s*"([^"]+)"`)
)

// SolveChallenge handles the Fastly challenge flow (including multi-step PoW, PAT, clientmetrics,
// and interactive user CAPTCHA when ctx carries WithInteractiveCaptcha) and installs verified
// anti-bot clearance cookies ONLY after challenge completion is confirmed.
func (c *ControlPlane) SolveChallenge(ctx context.Context) error {
	c.challengeMu.Lock()
	defer c.challengeMu.Unlock()
	if c.captcha != nil {
		defer c.captcha.Clear()
	}
	if err := ctx.Err(); err != nil {
		return err
	}

	interactive := allowsInteractiveCaptcha(ctx)
	timeout := fastlySolveTimeout
	if interactive {
		timeout = interactiveCaptchaTimeout + 60*time.Second
	}
	solveCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	var last error
	for _, base := range []string{fxaAPIHost, strings.TrimRight(fxaSiteURL, "/")} {
		if err := solveCtx.Err(); err != nil {
			if errors.Is(err, context.Canceled) {
				return CaptchaCancelledError{}
			}
			return err
		}
		err := c.solveOnHost(solveCtx, base, interactive)
		if err == nil {
			return nil
		}
		if errors.Is(err, errNoChallengePage) {
			continue
		}
		var cancelErr CaptchaCancelledError
		if errors.As(err, &cancelErr) {
			return err
		}
		var reqErr *FastlyCaptchaRequiredError
		if errors.As(err, &reqErr) {
			return err
		}
		var fe *FastlyChallengeError
		if errors.As(err, &fe) {
			last = err
			continue
		}
		return err
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

// installChallengeCookies copies earned clearance cookies as host-only cookies across all FxA origins
// (accounts.firefox.com, api.accounts.firefox.com, oauth.accounts.firefox.com, plus the challenge host).
func (c *ControlPlane) installChallengeCookies(solverJar http.CookieJar, pageURL string) error {
	page, err := url.Parse(pageURL)
	if err != nil {
		return err
	}
	cookies := solverJar.Cookies(page)
	if len(cookies) == 0 {
		return fastlyErr("challenge completed but no applicable cookies were issued")
	}
	hostOnly := make([]*http.Cookie, 0, len(cookies))
	for _, ck := range cookies {
		cp := *ck
		cp.Domain = "" // host-only when passed to CookieJar.SetCookies
		if cp.Path == "" {
			cp.Path = "/"
		}
		hostOnly = append(hostOnly, &cp)
	}
	targets := []string{
		fxaAuthServer + "/",
		"https://api.accounts.firefox.com/",
		"https://oauth.accounts.firefox.com/",
		fxaSiteURL,
		page.Scheme + "://" + page.Host + "/",
	}
	seen := map[string]bool{}
	for _, target := range targets {
		if seen[target] {
			continue
		}
		seen[target] = true
		u, err := url.Parse(target)
		if err != nil {
			return err
		}
		(dynamicCookieJar{cp: c}).SetCookies(u, hostOnly)
	}
	return nil
}

func findCaptchaEntry(challenges []map[string]any) map[string]any {
	for _, ch := range challenges {
		if ty, _ := ch["ty"].(string); ty == "captcha" {
			return ch
		}
	}
	return nil
}

func (c *ControlPlane) solveOnHost(ctx context.Context, base string, interactive bool) error {
	pageURL := base + "/"
	var pendingNotice CaptchaNoticeKind
	var lastShownImage *DecodedCaptchaImage
	autoRetriedCorruptImage := false

	for sessionRestarts := 0; sessionRestarts < maxCaptchaSessionRestarts; sessionRestarts++ {
		// Fresh isolated cookie jar per challenge session; discarded on refresh/expiry/cancel
		// and copied to the shared control-plane jar ONLY after verified clearance.
		jar, err := cookiejar.New(nil)
		if err != nil {
			return err
		}
		hc := &http.Client{Transport: c.client.Transport, Jar: jar, Timeout: fastlySolveTimeout}

		do := func(method, rawURL string, headers map[string]string, body []byte) (*Response, error) {
			var rdr io.Reader
			if body != nil {
				rdr = bytes.NewReader(body)
			}
			req, err := http.NewRequestWithContext(ctx, method, rawURL, rdr)
			if err != nil {
				return nil, err
			}
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
				"Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
			}, nil)
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

		initChallenge := func() (string, string, []map[string]any, string, error) {
			page, isChallenge, err := fetchPage()
			if err != nil {
				return "", "", nil, "", err
			}
			if !isChallenge {
				return "", "", nil, "", errNoChallengePage
			}
			m := challengePrefixRe.FindString(page)
			if m == "" {
				return "", "", nil, "", fastlyErr("challenge asset prefix not found on %s", base)
			}
			prefix := base + m
			origin := prefix[:strings.Index(prefix, "/_fs-ch-")]
			script, err := getText(prefix + "/script.js?reload=true")
			if err != nil {
				return "", "", nil, "", err
			}
			chs, tok, err := parseChallengeInit(script, m)
			if err != nil {
				return "", "", nil, "", fastlyErr("%v", err)
			}
			return prefix, origin, chs, tok, nil
		}

		prefix, origin, challenges, token, initErr := initChallenge()
		if initErr != nil {
			if errors.Is(initErr, errNoChallengePage) {
				if sessionRestarts == 0 {
					return errNoChallengePage
				}
				return c.installChallengeCookies(jar, pageURL)
			}
			if interactive && c.captcha != nil && lastShownImage != nil && sessionRestarts > 0 {
				_, waitErr := c.captcha.promptAndAwait(ctx, lastShownImage, sessionRestarts+1, CaptchaNoticeRefreshFailed)
				if waitErr != nil {
					return waitErr
				}
				continue
			}
			return initErr
		}

		solveAuto := func(ch map[string]any, curToken string) (map[string]any, error) {
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
				r, err := do("POST", prefix+"/pat?token="+url.QueryEscape(curToken), map[string]string{
					"Accept": "text/plain", "Content-Type": "application/json", "Origin": origin,
				}, []byte{})
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
					"ty":                   "clientmetrics",
					"webdriver":            false,
					"bot_detection_result": map[string]any{"bot_detected": false, "bot_kind": nil},
					"browser_metrics":      map[string]any{"client_data": "{}", "error_trace": nil},
					"detector_results":     map[string]any{},
					"v":                    2,
				}, nil
			}
			return nil, fastlyErr("unsupported Fastly challenge type '%s'", ty)
		}

		submittedCaptchaInSession := false
		shouldRestartSession := false

		for round := 0; round < maxPostBackRounds; round++ {
			captchaEntry := findCaptchaEntry(challenges)
			manualCaptchaAnswer := ""

			if captchaEntry != nil {
				if !interactive || c.captcha == nil {
					return &FastlyCaptchaRequiredError{
						Msg: "Fastly requires an image CAPTCHA; please use Direct Sign-In or Mozilla Sign-In.",
					}
				}
				dataMap, _ := captchaEntry["data"].(map[string]any)
				rawImg, _ := dataMap["image_b64"].(string)
				decodedImg, decErr := DecodeCaptchaDataURI(rawImg)
				if decErr != nil {
					if !autoRetriedCorruptImage && sessionRestarts+1 < maxCaptchaSessionRestarts {
						autoRetriedCorruptImage = true
						shouldRestartSession = true
						break
					}
					_, waitErr := c.captcha.promptAndAwait(ctx, nil, round+1, CaptchaNoticeDecodeFailed)
					if waitErr != nil {
						return waitErr
					}
					shouldRestartSession = true
					break
				}

				autoRetriedCorruptImage = false
				notice := pendingNotice
				if submittedCaptchaInSession {
					notice = CaptchaNoticeWrongAnswer
				}
				pendingNotice = CaptchaNoticeNone
				lastShownImage = decodedImg

				act, waitErr := c.captcha.promptAndAwait(ctx, decodedImg, round+1, notice)
				if waitErr != nil {
					return waitErr
				}
				if act.kind == captchaActionRefresh {
					shouldRestartSession = true
					break
				}
				manualCaptchaAnswer = strings.TrimSpace(act.answer)
				submittedCaptchaInSession = true
			}

			if shouldRestartSession {
				break
			}

			answers := make([]map[string]any, 0, len(challenges))
			for _, ch := range challenges {
				if ty, _ := ch["ty"].(string); ty == "captcha" {
					answers = append(answers, map[string]any{
						"ty":     "captcha",
						"answer": manualCaptchaAnswer,
					})
				} else {
					a, err := solveAuto(ch, token)
					if err != nil {
						return err
					}
					answers = append(answers, a)
				}
			}

			payload, err := json.Marshal(map[string]any{"token": token, "data": answers})
			if err != nil {
				return err
			}
			r, err := do("POST", prefix+"/fst-post-back", map[string]string{
				"Content-Type": "application/json",
				"Accept":       "application/json",
				"Origin":       origin,
			}, payload)
			if err != nil {
				return err
			}
			if !r.OK() {
				if submittedCaptchaInSession && expiredCaptchaPostBackCodes[r.Status] {
					pendingNotice = CaptchaNoticeExpired
					shouldRestartSession = true
					break
				}
				return fastlyErr("challenge post-back returned HTTP %d", r.Status)
			}
			var reply struct {
				Status string           `json:"status"`
				Ch     []map[string]any `json:"ch"`
				Tok    string           `json:"tok"`
			}
			if err := json.Unmarshal(r.Body, &reply); err != nil {
				if submittedCaptchaInSession {
					pendingNotice = CaptchaNoticeExpired
					shouldRestartSession = true
					break
				}
				return fastlyErr("unexpected post-back response")
			}
			if reply.Status == "success" {
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
				if submittedCaptchaInSession {
					pendingNotice = CaptchaNoticeExpired
					shouldRestartSession = true
					break
				}
				return fastlyErr("challenge post-back is missing the next round")
			}
			challenges, token = reply.Ch, reply.Tok
		}

		if shouldRestartSession {
			continue
		}
		return fastlyErr("Fastly challenge did not complete within %d rounds", maxPostBackRounds)
	}

	return fastlyErr("Fastly CAPTCHA session exceeded maximum refresh attempts")
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
