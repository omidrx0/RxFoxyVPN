package foxy

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"time"

	utls "github.com/refraction-networking/utls"

	"rxvpn/internal/sysproxy"
)

const (
	relayAttemptDialTimeout = 8 * time.Second
	relayAttemptReadTimeout = 15 * time.Second
	relayAttemptCallTimeout = 20 * time.Second
	relayMaxRedirects       = 5
	relayMaxReplayBodyBytes = 1024 * 1024 // 1 MiB
	relayMaxResponseBytes   = 8 << 20     // 8 MiB
	relayRecoveryBudget     = 24 * time.Second
	personalFirstWindow     = 10 * time.Minute
)

var relayHopHeaders = map[string]struct{}{
	"host":                {},
	"connection":          {},
	"keep-alive":          {},
	"proxy-authenticate":  {},
	"proxy-authorization": {},
	"te":                  {},
	"trailer":             {},
	"transfer-encoding":   {},
	"upgrade":             {},
	"content-length":      {},
}

func newIsolatedDirectHTTPClient(ts *tlsSettings, dialTimeout, respHeaderTimeout time.Duration) *http.Client {
	sessions := utls.NewLRUClientSessionCache(32)
	dialer := &net.Dialer{Timeout: dialTimeout, KeepAlive: 30 * time.Second}
	var rootPool = ts.pool
	if ts.insecure {
		// Insecure mode is only used by local unit/integration TLS test servers or explicit --insecure.
		tr := &http.Transport{
			Proxy:                 sysproxy.ProxyFunc,
			DialContext:           dialer.DialContext,
			TLSClientConfig:       &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: rootPool, InsecureSkipVerify: true}, //nolint:gosec
			ForceAttemptHTTP2:     false,
			TLSNextProto:          map[string]func(string, *tls.Conn) http.RoundTripper{},
			DisableCompression:    true,
			MaxIdleConns:          16,
			IdleConnTimeout:       60 * time.Second,
			ResponseHeaderTimeout: respHeaderTimeout,
		}
		return &http.Client{
			Transport: tr,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		}
	}
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
			shakeCtx, cancel := context.WithTimeout(ctx, dialTimeout)
			defer cancel()
			conn, err := firefoxHandshake(shakeCtx, raw, host, rootPool, sessions, "http/1.1")
			if err != nil {
				_ = raw.Close()
				return nil, err
			}
			return conn, nil
		},
		ForceAttemptHTTP2:     false,
		TLSNextProto:          map[string]func(string, *tls.Conn) http.RoundTripper{},
		DisableCompression:    true,
		MaxIdleConns:          16,
		IdleConnTimeout:       60 * time.Second,
		ResponseHeaderTimeout: respHeaderTimeout,
	}
	return &http.Client{
		Transport: tr,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

// RelayTransport forwards an allow-listed control-plane HTTP request through a Worker or PHP relay
// (<method> <relayURL>&url=<encodedTarget>), following up to 5 validated redirects without leaking
// cross-origin Authorization or Cookie headers.
type RelayTransport struct {
	client *http.Client
}

// NewRelayTransport creates an isolated RelayTransport (no CookieJar, no auto-redirects).
func NewRelayTransport(opts TLSOptions) (*RelayTransport, error) {
	ts, err := newTLSSettings(opts)
	if err != nil {
		return nil, err
	}
	return &RelayTransport{
		client: newIsolatedDirectHTTPClient(ts, relayAttemptDialTimeout, relayAttemptReadTimeout),
	}, nil
}

func isRedirectStatus(code int) bool {
	switch code {
	case http.StatusMovedPermanently, http.StatusFound, http.StatusSeeOther,
		http.StatusTemporaryRedirect, http.StatusPermanentRedirect:
		return true
	default:
		return false
	}
}

func sanitizeForwardedHeaders(src http.Header) http.Header {
	dst := make(http.Header, len(src))
	for name, vals := range src {
		lower := strings.ToLower(name)
		if _, hop := relayHopHeaders[lower]; hop {
			continue
		}
		if strings.HasPrefix(lower, "cf-") {
			continue
		}
		copied := make([]string, len(vals))
		copy(copied, vals)
		dst[name] = copied
	}
	return dst
}

// Forward executes one relay forwarding attempt (with up to 5 validated redirects).
// It matches RelayTransport.forward in relay.zip.
func (rt *RelayTransport) Forward(
	ctx context.Context,
	relayURL string,
	method string,
	targetURL *url.URL,
	headers http.Header,
	body []byte,
	requireRelayHeader bool,
) (*Response, error) {
	if !IsRelayTargetAllowed(targetURL) {
		return nil, fmt.Errorf("target not allowed for relay: %s", targetURL.Hostname())
	}
	parsedRelay, err := url.Parse(relayURL)
	if err != nil || !strings.EqualFold(parsedRelay.Scheme, "https") || parsedRelay.Hostname() == "" {
		return nil, errors.New("invalid relay URL")
	}

	curMethod := method
	curTarget := *targetURL
	curHeaders := sanitizeForwardedHeaders(headers)
	curBody := body

	for hop := 0; hop <= relayMaxRedirects; hop++ {
		attemptCtx, cancel := context.WithTimeout(ctx, relayAttemptCallTimeout)
		wireRelay := *parsedRelay
		appendRawQueryParam(&wireRelay, "url", curTarget.String())

		var bodyReader io.Reader
		if curBody != nil && curMethod != http.MethodGet && curMethod != http.MethodHead {
			bodyReader = bytes.NewReader(curBody)
		}
		req, err := http.NewRequestWithContext(attemptCtx, curMethod, wireRelay.String(), bodyReader)
		if err != nil {
			cancel()
			return nil, err
		}
		req.Header = curHeaders.Clone()
		if curBody != nil && curMethod != http.MethodGet && curMethod != http.MethodHead {
			req.ContentLength = int64(len(curBody))
		}

		resp, err := rt.client.Do(req)
		if err != nil {
			cancel()
			return nil, err
		}
		respBody, readErr := io.ReadAll(io.LimitReader(resp.Body, relayMaxResponseBytes))
		_ = resp.Body.Close()
		cancel()
		if readErr != nil {
			return nil, readErr
		}

		// Reject relay-generated errors (never confuse a relay error with Mozilla's answer).
		if resp.Header.Get(RelayErrorHeader) != "" {
			return nil, fmt.Errorf("relay returned %s (HTTP %d)", resp.Header.Get(RelayErrorHeader), resp.StatusCode)
		}

		forwardedMark := resp.Header.Get(RelayForwardedHeader) == "1"
		if requireRelayHeader {
			if !forwardedMark {
				return nil, fmt.Errorf("relay response missing %s header (HTTP %d)", RelayForwardedHeader, resp.StatusCode)
			}
		} else if !forwardedMark {
			// Personal relay without X-RxVpN-Relay: reject Cloudflare edge challenge/error pages
			// while still accepting older custom personal Workers that omit the header.
			if resp.Header.Get("CF-Ray") != "" || strings.Contains(strings.ToLower(resp.Header.Get("Server")), "cloudflare") {
				return nil, fmt.Errorf("unmarked cloudflare edge response from personal relay (HTTP %d)", resp.StatusCode)
			}
		}

		if isRedirectStatus(resp.StatusCode) && hop < relayMaxRedirects {
			loc := strings.TrimSpace(resp.Header.Get("Location"))
			if loc != "" {
				locURL, parseErr := curTarget.Parse(loc)
				if parseErr != nil || !IsRelayTargetAllowed(locURL) {
					return nil, fmt.Errorf("relay redirect to disallowed target")
				}
				if !strings.EqualFold(locURL.Hostname(), curTarget.Hostname()) {
					curHeaders.Del("Authorization")
					curHeaders.Del("Cookie")
				}
				if resp.StatusCode == http.StatusSeeOther ||
					((resp.StatusCode == http.StatusMovedPermanently || resp.StatusCode == http.StatusFound) &&
						curMethod != http.MethodGet && curMethod != http.MethodHead) {
					curMethod = http.MethodGet
					curBody = nil
					curHeaders.Del("Content-Type")
				}
				curTarget = *locURL
				continue
			}
		}

		return &Response{
			Status: resp.StatusCode,
			Header: resp.Header.Clone(),
			Body:   respBody,
		}, nil
	}

	return nil, errors.New("relay exceeded redirect limit")
}

// UsableGuardianResponse matches RelayFallbackInterceptor.usableGuardianResponse in relay.zip:
// HEAD -> 200; other methods -> 200/401/403/429 with JSON Content-Type (or JSON body).
func UsableGuardianResponse(method string, r *Response) bool {
	if r == nil {
		return false
	}
	if strings.EqualFold(method, http.MethodHead) {
		return r.Status == http.StatusOK
	}
	switch r.Status {
	case http.StatusOK, http.StatusUnauthorized, http.StatusForbidden, http.StatusTooManyRequests:
	default:
		return false
	}
	ct := strings.ToLower(r.Header.Get("Content-Type"))
	if strings.Contains(ct, "json") {
		return true
	}
	trimmed := bytes.TrimSpace(r.Body)
	return len(trimmed) > 0 && (trimmed[0] == '{' || trimmed[0] == '[')
}

// RelayFallbackCoordinator implements the exact HTTP 451 & unreachable recovery order
// from RelayFallbackInterceptor.kt in relay.zip.
type RelayFallbackCoordinator struct {
	configSource RelayConfigSource
	transport    *RelayTransport
	shared       *SharedRelayManager
	now          func() time.Time

	lastDirectFailureUnixNano atomic.Int64
}

// NewRelayFallbackCoordinator wires RelayTransport and SharedRelayManager together.
func NewRelayFallbackCoordinator(
	cfgSource RelayConfigSource,
	transport *RelayTransport,
	shared *SharedRelayManager,
) *RelayFallbackCoordinator {
	return &RelayFallbackCoordinator{
		configSource: cfgSource,
		transport:    transport,
		shared:       shared,
		now:          time.Now,
	}
}

func (fc *RelayFallbackCoordinator) currentConfig() RelayConfig {
	if fc == nil || fc.configSource == nil {
		return RelayConfig{SharedEnabled: true}
	}
	return fc.configSource()
}

func (fc *RelayFallbackCoordinator) inPersonalFirstWindow() bool {
	ts := fc.lastDirectFailureUnixNano.Load()
	if ts == 0 {
		return false
	}
	elapsed := fc.now().Sub(time.Unix(0, ts))
	return elapsed >= 0 && elapsed < personalFirstWindow
}

// Execute runs a single control-plane HTTP round-trip with RelayFallbackInterceptor semantics.
// directDo executes the direct uTLS request (with the FxA CookieJar when applicable).
func (fc *RelayFallbackCoordinator) Execute(
	ctx context.Context,
	method string,
	targetURL *url.URL,
	reqHeaders http.Header,
	body []byte,
	directDo func(ctx context.Context, u *url.URL) (*Response, error),
) (*Response, error) {
	if fc == nil || fc.transport == nil || !IsRelayTargetAllowed(targetURL) || len(body) > relayMaxReplayBodyBytes {
		return directDo(ctx, targetURL)
	}

	cfg := fc.currentConfig()
	var personalURL *url.URL
	if cfg.PersonalEnabled && strings.TrimSpace(cfg.PersonalURL) != "" {
		if u, err := ValidatePersonalRelayURL(cfg.PersonalURL); err == nil {
			personalURL = u
		}
	}

	// 1. Personal-First window (10m after a direct pre-connection failure when Personal Relay is valid).
	if personalURL != nil && fc.inPersonalFirstWindow() {
		if r, err := fc.tryPersonal(ctx, personalURL.String(), method, targetURL, reqHeaders, body); err == nil && r != nil {
			r.Header.Set(RelayRecoveryHeader, "personal-first")
			return r, nil
		}
	}

	// 2. Direct attempt.
	directResp, directErr := directDo(ctx, targetURL)
	if directErr != nil {
		if ctx.Err() != nil {
			return nil, directErr
		}
		fc.lastDirectFailureUnixNano.Store(fc.now().UnixNano())
		if personalURL != nil {
			if r, err := fc.tryPersonal(ctx, personalURL.String(), method, targetURL, reqHeaders, body); err == nil && r != nil {
				r.Header.Set(RelayRecoveryHeader, "personal-on-unreachable")
				return r, nil
			}
		}
		return nil, directErr
	}

	// Direct reached the server: clear the Personal-First unreachable marker.
	fc.lastDirectFailureUnixNano.Store(0)

	// Non-451 statuses (including 200, 400, 401, 403, 406 Fastly challenge, 429, 5xx) return untouched!
	if directResp.Status != http.StatusUnavailableForLegalReasons {
		return directResp, nil
	}

	// 3. Bounded HTTP 451 recovery (24-second total budget).
	recCtx, cancel := context.WithTimeout(ctx, relayRecoveryBudget)
	defer cancel()

	guardian := IsGuardianRequest(targetURL)
	personalTried := false
	sharedTried := false

	// Step 3a (Guardian only): doubled leading slash direct retry (//api/v1/fpn/...).
	if guardian && recCtx.Err() == nil {
		if slashURL := DoubledLeadingSlash(targetURL); slashURL != nil {
			if r, err := directDo(recCtx, slashURL); err == nil && r != nil && r.Status != http.StatusUnavailableForLegalReasons {
				r.Header.Set(RelayRecoveryHeader, "slash")
				slog.Info("HTTP 451 recovered via doubled leading slash", "host", targetURL.Hostname())
				return r, nil
			}
		}
	}

	// Step 3b: Personal relay (if enabled and valid).
	if personalURL != nil && recCtx.Err() == nil {
		personalTried = true
		if r, err := fc.tryPersonal(recCtx, personalURL.String(), method, targetURL, reqHeaders, body); err == nil && r != nil {
			r.Header.Set(RelayRecoveryHeader, "personal")
			slog.Info("HTTP 451 recovered via personal relay", "relay", RedactRelayURL(personalURL.String()))
			return r, nil
		}
	}

	// Step 3c: Shared relay (Guardian requests only; skipped when PersonalEnabled && PersonalOnly).
	allowShared := guardian && cfg.SharedEnabled && !(cfg.PersonalEnabled && cfg.PersonalOnly)
	if allowShared && fc.shared != nil && recCtx.Err() == nil {
		candidates := fc.shared.CandidatesForAttempt(recCtx, cfg.SharedSecret)
		if len(candidates) > 0 {
			sharedTried = true
		}
		for _, cand := range candidates {
			if recCtx.Err() != nil {
				break
			}
			r, err := fc.transport.Forward(recCtx, cand, method, targetURL, reqHeaders, body, true)
			if err != nil {
				fc.shared.MarkBad(cand)
				continue
			}
			if !UsableGuardianResponse(method, r) {
				fc.shared.MarkBad(cand)
				continue
			}
			fc.shared.MarkGood(cand)
			r.Header.Set(RelayRecoveryHeader, "shared")
			slog.Info("HTTP 451 recovered via shared relay", "relay", RedactRelayURL(cand))
			return r, nil
		}
	}

	// Step 3d: Recovery exhausted — preserve buffered 451 response and tag exhaustion reason.
	exhausted := "none"
	switch {
	case personalTried && sharedTried:
		exhausted = "all-failed"
	case personalTried:
		exhausted = "personal"
	case sharedTried:
		exhausted = "shared"
	}
	if directResp.Header == nil {
		directResp.Header = make(http.Header)
	}
	directResp.Header.Set(RelayRecoveryExhaustedHeader, exhausted)
	return directResp, nil
}

func (fc *RelayFallbackCoordinator) tryPersonal(
	ctx context.Context,
	personalRelayURL string,
	method string,
	targetURL *url.URL,
	reqHeaders http.Header,
	body []byte,
) (*Response, error) {
	r, err := fc.transport.Forward(ctx, personalRelayURL, method, targetURL, reqHeaders, body, false)
	if err != nil || r == nil {
		return nil, err
	}
	if IsGuardianRequest(targetURL) {
		if !UsableGuardianResponse(method, r) {
			return nil, errors.New("unusable guardian response from personal relay")
		}
		return r, nil
	}
	if r.Status == http.StatusUnavailableForLegalReasons {
		return nil, errors.New("personal relay also returned HTTP 451")
	}
	return r, nil
}
