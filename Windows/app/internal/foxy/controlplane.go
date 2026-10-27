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
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

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

	mu        sync.Mutex
	fxCookies map[string]string
}

// NewControlPlane builds the client. apiProxy (optional) is used ONLY for these control
// requests; without it the environment / Windows system proxy is followed.
func NewControlPlane(apiProxy string) (*ControlPlane, error) {
	tr := &http.Transport{
		Proxy:                 sysproxy.ProxyFunc,
		TLSHandshakeTimeout:   20 * time.Second,
		ResponseHeaderTimeout: 30 * time.Second,
		IdleConnTimeout:       60 * time.Second,
		MaxIdleConnsPerHost:   4,
		ForceAttemptHTTP2:     true,
	}
	if apiProxy != "" {
		u, err := url.Parse(apiProxy)
		if err != nil || u.Host == "" {
			return nil, fmt.Errorf("invalid --api-proxy %q", apiProxy)
		}
		tr.Proxy = http.ProxyURL(u)
	}
	return &ControlPlane{
		client:    &http.Client{Transport: tr, Timeout: 30 * time.Second},
		fxCookies: map[string]string{},
	}, nil
}

func (c *ControlPlane) cookieHeader(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil {
		return ""
	}
	host := u.Hostname()
	if host != "firefox.com" && !strings.HasSuffix(host, ".firefox.com") {
		return ""
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	parts := make([]string, 0, len(c.fxCookies))
	for k, v := range c.fxCookies {
		parts = append(parts, k+"="+v)
	}
	return strings.Join(parts, "; ")
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
	req.Header.Set("Accept", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	if cookie := c.cookieHeader(rawURL); cookie != "" {
		req.Header.Set("Cookie", cookie)
	}
	resp, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	return readResponse(resp)
}

// ---------------------------------------------------------------- Fastly "Client Challenge" solver

var (
	challengePrefixRe = regexp.MustCompile(`/_fs-ch-[A-Za-z0-9]+`)
	challengeInitRe   = regexp.MustCompile(`init\((\[[^\]]*\]),\s*"([^"]+)",\s*"([^"]+)"`)
)

// SolveChallenge passes the Fastly anti-bot challenge (proof of work + client metrics) and
// remembers the cookies it earns for the firefox.com hosts.
func (c *ControlPlane) SolveChallenge(ctx context.Context) error {
	var last error
	for _, base := range []string{"https://api.accounts.firefox.com", "https://accounts.firefox.com"} {
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

func (c *ControlPlane) solveOnHost(ctx context.Context, base string) error {
	jar, err := cookiejar.New(nil)
	if err != nil {
		return err
	}
	hc := &http.Client{Transport: c.client.Transport, Jar: jar, Timeout: 60 * time.Second}
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
		req.Header.Set("User-Agent", solverUA)
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
	matches := challengeInitRe.FindAllStringSubmatch(script, -1)
	if len(matches) == 0 {
		return fastlyErr("challenge init() call not found in script")
	}
	lastMatch := matches[len(matches)-1]
	var challenges []map[string]any
	if err := json.Unmarshal([]byte(lastMatch[1]), &challenges); err != nil {
		return fastlyErr("could not parse challenge list: %v", err)
	}
	token := lastMatch[2]
	if len(challenges) == 0 {
		return fastlyErr("empty challenge list")
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

	for round := 0; round < 3; round++ {
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
			return fastlyErr("unexpected post-back response: %s", r.Text())
		}
		if reply.Status == "success" {
			_, still, err := fetchPage()
			if err != nil {
				return err
			}
			if still {
				return fastlyErr("challenge cookie not accepted on this exit IP")
			}
			baseURL, _ := url.Parse(base)
			cookies := map[string]string{}
			for _, ck := range jar.Cookies(baseURL) {
				cookies[ck.Name] = ck.Value
			}
			if len(cookies) == 0 {
				return fastlyErr("challenge completed but no cookies were issued")
			}
			c.mu.Lock()
			for k, v := range cookies {
				c.fxCookies[k] = v
			}
			c.mu.Unlock()
			slog.Info("Fastly challenge solved")
			return nil
		}
		if len(reply.Ch) == 0 || reply.Tok == "" {
			return fastlyErr("unexpected post-back response: %s", r.Text())
		}
		challenges, token = reply.Ch, reply.Tok
	}
	return fastlyErr("Fastly challenge did not complete within 3 rounds")
}
