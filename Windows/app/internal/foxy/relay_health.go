package foxy

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// RelayFailure matches RelayHealth.kt in relay.zip.
type RelayFailure string

const (
	RelayFailureNone                RelayFailure = ""
	RelayFailureWrongSecret         RelayFailure = "wrong_secret"
	RelayFailureNotSetUp            RelayFailure = "not_set_up"
	RelayFailureUpstreamUnreachable RelayFailure = "upstream_unreachable"
	RelayFailureRefused             RelayFailure = "refused"
	RelayFailureUnrecognised        RelayFailure = "unrecognised"
	RelayFailureTimeout             RelayFailure = "timeout"
	RelayFailureNetwork             RelayFailure = "network"
)

// RelayHealthResult is the result of probing GET <relay>&health=1.
type RelayHealthResult struct {
	Ready     bool         `json:"ready"`
	HTTPCode  *int         `json:"http_code,omitempty"`
	ElapsedMS int64        `json:"elapsed_ms"`
	Failure   RelayFailure `json:"failure,omitempty"`
}

const (
	relayHealthDialTimeout = 4 * time.Second
	relayHealthReadTimeout = 6 * time.Second
	relayHealthCallTimeout = 8 * time.Second
	maxHealthBodyBytes     = 8 * 1024
)

// BuildRelayHealthURL appends health=1 to a validated relay URL without dropping existing query params.
func BuildRelayHealthURL(relayURL *url.URL) string {
	clone := *relayURL
	appendRawQueryParam(&clone, "health", "1")
	return clone.String()
}

// RelayHealthChecker probes personal or shared relays using an isolated HTTP/1.1 client
// (no CookieJar, no redirects, bounded 4s/6s/8s timeouts).
type RelayHealthChecker struct {
	client *http.Client
}

// NewRelayHealthChecker constructs a RelayHealthChecker using the given TLS settings.
func NewRelayHealthChecker(opts TLSOptions) (*RelayHealthChecker, error) {
	ts, err := newTLSSettings(opts)
	if err != nil {
		return nil, err
	}
	client := newIsolatedDirectHTTPClient(ts, relayHealthDialTimeout, relayHealthReadTimeout)
	return &RelayHealthChecker{client: client}, nil
}

// Check runs GET <relay>&health=1 and verifies HTTP 200 without X-Relay-Error and JSON
// {"relay":"rxvpn","version":1,"ready":true}.
func (h *RelayHealthChecker) Check(ctx context.Context, relayURL *url.URL) RelayHealthResult {
	start := time.Now()
	cctx, cancel := context.WithTimeout(ctx, relayHealthCallTimeout)
	defer cancel()

	target := BuildRelayHealthURL(relayURL)
	req, err := http.NewRequestWithContext(cctx, http.MethodGet, target, nil)
	if err != nil {
		return RelayHealthResult{
			Ready:     false,
			ElapsedMS: time.Since(start).Milliseconds(),
			Failure:   RelayFailureNetwork,
		}
	}
	req.Header.Set("User-Agent", UserAgent)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Accept-Encoding", "identity")

	resp, err := h.client.Do(req)
	elapsed := time.Since(start).Milliseconds()
	if err != nil {
		failure := RelayFailureNetwork
		if isTimeoutErr(err) || errors.Is(cctx.Err(), context.DeadlineExceeded) {
			failure = RelayFailureTimeout
		}
		return RelayHealthResult{
			Ready:     false,
			ElapsedMS: elapsed,
			Failure:   failure,
		}
	}
	defer resp.Body.Close()
	bodyBytes, _ := io.ReadAll(io.LimitReader(resp.Body, maxHealthBodyBytes))
	body := string(bodyBytes)
	code := resp.StatusCode

	if code == http.StatusOK && resp.Header.Get(RelayErrorHeader) == "" {
		if isRelayHealthPayloadReady(bodyBytes) {
			return RelayHealthResult{
				Ready:     true,
				HTTPCode:  &code,
				ElapsedMS: elapsed,
			}
		}
		return RelayHealthResult{
			Ready:     false,
			HTTPCode:  &code,
			ElapsedMS: elapsed,
			Failure:   RelayFailureUnrecognised,
		}
	}

	lower := strings.ToLower(body)
	var failure RelayFailure
	switch {
	case code == http.StatusForbidden && strings.Contains(lower, "wrong secret"):
		failure = RelayFailureWrongSecret
	case code == http.StatusServiceUnavailable && strings.Contains(lower, "secret"):
		failure = RelayFailureNotSetUp
	case code == http.StatusBadGateway:
		failure = RelayFailureUpstreamUnreachable
	default:
		failure = RelayFailureRefused
	}
	return RelayHealthResult{
		Ready:     false,
		HTTPCode:  &code,
		ElapsedMS: elapsed,
		Failure:   failure,
	}
}

func isRelayHealthPayloadReady(raw []byte) bool {
	var obj map[string]any
	if err := json.Unmarshal(raw, &obj); err != nil {
		return false
	}
	relayName, _ := obj["relay"].(string)
	if relayName != "rxvpn" {
		return false
	}
	ready, _ := obj["ready"].(bool)
	if !ready {
		return false
	}
	switch v := obj["version"].(type) {
	case float64:
		return v == 1
	case int:
		return v == 1
	default:
		return false
	}
}

func isTimeoutErr(err error) bool {
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}
