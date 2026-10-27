package foxy

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand"
	"net"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/http2"
)

const (
	connectTimeout    = 15 * time.Second
	openStreamTimeout = 20 * time.Second
	keepaliveIdle     = 15 * time.Second // ping when nothing was received for this long ...
	keepaliveTimeout  = 10 * time.Second // ... and drop the tunnel if the ping is not answered
	passRenewMargin   = 120               // seconds before expiry when a new pass is fetched
)

// TLSOptions controls how the tunnel servers are verified.
type TLSOptions struct {
	CAFile   string // extra CA bundle (PEM) to trust besides the system store
	Insecure bool   // do NOT verify the certificate (unsafe)
}

type tlsSettings struct {
	pool     *x509.CertPool
	insecure bool
}

func newTLSSettings(o TLSOptions) (*tlsSettings, error) {
	s := &tlsSettings{insecure: o.Insecure}
	if o.CAFile != "" {
		pool, err := x509.SystemCertPool()
		if err != nil || pool == nil {
			pool = x509.NewCertPool()
		}
		pem, err := os.ReadFile(o.CAFile)
		if err != nil {
			return nil, err
		}
		if !pool.AppendCertsFromPEM(pem) {
			return nil, fmt.Errorf("no certificates found in %s", o.CAFile)
		}
		s.pool = pool
	}
	return s, nil
}

func (s *tlsSettings) config(host string) *tls.Config {
	return &tls.Config{
		ServerName:         host,
		NextProtos:         []string{"h2"},
		MinVersion:         tls.VersionTLS12,
		RootCAs:            s.pool,
		InsecureSkipVerify: s.insecure, //nolint:gosec // only with the explicit --insecure flag
	}
}

func describeTLSError(host string, port int, err error) error {
	var cve *tls.CertificateVerificationError
	if !errors.As(err, &cve) {
		return err
	}
	info := ""
	if len(cve.UnverifiedCertificates) > 0 {
		cert := cve.UnverifiedCertificates[0]
		info = fmt.Sprintf("certificate presented by %s:%d\n    issuer : %s\n    subject: %s\n    sha256 : %x",
			host, port, cert.Issuer, cert.Subject, sha256.Sum256(cert.Raw))
	}
	return &UpstreamError{Msg: "TLS certificate not trusted - something on your network/PC is replacing the server " +
		"certificate (antivirus HTTPS scanning, ISP/DPI, corporate proxy, or another VPN).\n  " + info +
		"\n  Fix: turn off HTTPS scanning in your antivirus, or try another network, or export that " +
		"issuer's root certificate and pass it with --ca file.pem"}
}

// ---------------------------------------------------------------- one HTTP/2 connection

// h2Conn is one TLS + HTTP/2 connection to a Fastly edge, multiplexing many CONNECT streams.
type h2Conn struct {
	host string
	port int
	cc   *http2.ClientConn
	tc   *tls.Conn
}

func dialH2(ctx context.Context, host string, port int, ts *tlsSettings) (*h2Conn, error) {
	dctx, cancel := context.WithTimeout(ctx, connectTimeout)
	defer cancel()
	var d net.Dialer
	raw, err := d.DialContext(dctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, err
	}
	tc := tls.Client(raw, ts.config(host))
	if err := tc.HandshakeContext(dctx); err != nil {
		raw.Close()
		return nil, describeTLSError(host, port, err)
	}
	if tc.ConnectionState().NegotiatedProtocol != "h2" {
		tc.Close()
		return nil, upstreamf("%s did not negotiate HTTP/2 (ALPN)", host)
	}
	tr := &http2.Transport{
		DisableCompression: true,
		ReadIdleTimeout:    keepaliveIdle,
		PingTimeout:        keepaliveTimeout,
	}
	cc, err := tr.NewClientConn(tc)
	if err != nil {
		tc.Close()
		return nil, err
	}
	return &h2Conn{host: host, port: port, cc: cc, tc: tc}, nil
}

// alive: usable for new streams (not closed, no GOAWAY received).
func (c *h2Conn) alive() bool {
	st := c.cc.State()
	return !st.Closed && !st.Closing
}

func (c *h2Conn) close() {
	_ = c.cc.Close()
	_ = c.tc.Close()
}

// h2Stream is one CONNECT stream = one proxied TCP connection.
type h2Stream struct {
	body   io.ReadCloser
	w      *io.PipeWriter
	cancel context.CancelFunc
	once   sync.Once
}

func (s *h2Stream) Read(p []byte) (int, error)  { return s.body.Read(p) }
func (s *h2Stream) Write(p []byte) (int, error) { return s.w.Write(p) }

// CloseWrite half-closes the stream (END_STREAM): nothing more will be sent.
func (s *h2Stream) CloseWrite() { _ = s.w.Close() }

// Close ends the stream; if the remote side is not finished it is reset (CANCEL).
func (s *h2Stream) Close() {
	s.once.Do(func() {
		_ = s.w.CloseWithError(io.ErrClosedPipe)
		_ = s.body.Close()
		s.cancel()
	})
}

// openStream sends CONNECT authority with the proxy pass. parent bounds the life of the stream.
func (c *h2Conn) openStream(parent context.Context, authority, token string) (*h2Stream, error) {
	ctx, cancel := context.WithCancel(parent)
	var timedOut atomic.Bool
	timer := time.AfterFunc(openStreamTimeout, func() { timedOut.Store(true); cancel() })

	pr, pw := io.Pipe()
	req := (&http.Request{
		Method: "CONNECT",
		URL:    &url.URL{Scheme: "https", Host: authority},
		Host:   authority,
		Header: http.Header{
			"Proxy-Authorization": {"Bearer " + token},
			"User-Agent":          {""}, // the Python client sent none
		},
		Body:          pr,
		ContentLength: -1,
	}).WithContext(ctx)

	resp, err := c.cc.RoundTrip(req)
	if err != nil {
		timer.Stop()
		cancel()
		_ = pr.Close()
		_ = pw.Close()
		switch {
		case timedOut.Load():
			return nil, upstreamf("timed out waiting for CONNECT %s", authority)
		case !c.alive():
			return nil, &ConnectionLostError{Msg: err.Error()}
		}
		var se http2.StreamError
		if errors.As(err, &se) {
			return nil, upstreamf("stream reset (code %v)", se.Code)
		}
		return nil, &UpstreamError{Msg: err.Error()}
	}
	if !timer.Stop() { // the timeout fired right after the answer arrived
		_ = resp.Body.Close()
		_ = pw.Close()
		cancel()
		return nil, upstreamf("timed out waiting for CONNECT %s", authority)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		_ = resp.Body.Close()
		_ = pw.Close()
		cancel()
		return nil, &UpstreamHTTPError{Status: resp.StatusCode}
	}
	return &h2Stream{body: resp.Body, w: pw, cancel: cancel}, nil
}

// ---------------------------------------------------------------- proxy passes

// TokenSource keeps a valid Guardian proxy pass at hand.
type TokenSource struct {
	acc   *Account
	getMu sync.Mutex
	curMu sync.RWMutex
	cur   *ProxyPass
}

// NewTokenSource wraps an account.
func NewTokenSource(a *Account) *TokenSource { return &TokenSource{acc: a} }

// Current returns the pass in use (nil before the first one), for quota display.
func (t *TokenSource) Current() *ProxyPass {
	t.curMu.RLock()
	defer t.curMu.RUnlock()
	return t.cur
}

// Get returns a token valid for at least two more minutes, fetching a new pass when needed.
func (t *TokenSource) Get(ctx context.Context) (string, error) {
	t.getMu.Lock()
	defer t.getMu.Unlock()
	if p := t.Current(); p != nil && p.Exp-float64(time.Now().Unix()) > passRenewMargin {
		return p.Token, nil
	}
	pass, err := t.acc.MintPass(ctx)
	if err != nil {
		return "", err
	}
	t.curMu.Lock()
	t.cur = pass
	t.curMu.Unlock()
	if pass.QuotaRemaining != nil {
		slog.Info("got a proxy pass", "valid_s", int(pass.Exp-float64(time.Now().Unix())), "quota_left", *pass.QuotaRemaining)
	} else {
		slog.Info("got a proxy pass", "valid_s", int(pass.Exp-float64(time.Now().Unix())))
	}
	return pass.Token, nil
}

// Invalidate forgets the pass so the next Get fetches a new one.
func (t *TokenSource) Invalidate() {
	t.curMu.Lock()
	t.cur = nil
	t.curMu.Unlock()
}

// ---------------------------------------------------------------- upstream with failover

// Upstream owns the (single) HTTP/2 connection and fails over between the servers of a country.
type Upstream struct {
	tokens *TokenSource
	tls    *tlsSettings
	ctx    context.Context
	cancel context.CancelFunc

	dialMu sync.Mutex // one dial at a time

	mu         sync.Mutex // protects everything below
	gen        int        // bumped when the country changes
	candidates []Target
	idx        int
	conn       *h2Conn
	current    *Target
	country    *Country
}

// NewUpstream creates an upstream without a country; call SetCountry before Open.
func NewUpstream(tokens *TokenSource, opts TLSOptions) (*Upstream, error) {
	ts, err := newTLSSettings(opts)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Upstream{tokens: tokens, tls: ts, ctx: ctx, cancel: cancel}, nil
}

// Tokens returns the pass source (for quota display).
func (u *Upstream) Tokens() *TokenSource { return u.tokens }

// Country is the selected country (nil before SetCountry).
func (u *Upstream) Country() *Country {
	u.mu.Lock()
	defer u.mu.Unlock()
	return u.country
}

// Current is the server the tunnel is connected to.
func (u *Upstream) Current() (Target, bool) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.current == nil {
		return Target{}, false
	}
	return *u.current, true
}

func shuffled(t []Target) []Target {
	out := append([]Target(nil), t...)
	rand.Shuffle(len(out), func(i, j int) { out[i], out[j] = out[j], out[i] })
	return out
}

// SetCountry switches to another country and drops the current connection.
func (u *Upstream) SetCountry(c *Country) {
	cands := shuffled(c.Targets)
	u.mu.Lock()
	u.gen++
	u.country, u.candidates, u.idx = c, cands, 0
	old := u.conn
	u.conn = nil
	u.mu.Unlock()
	if old != nil {
		old.close()
	}
}

// UpdateTargets refreshes the server list of the current country without dropping the connection.
func (u *Upstream) UpdateTargets(c *Country) {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.country != nil && u.country.Code == c.Code && len(c.Targets) > 0 {
		u.country = c
		u.candidates = shuffled(c.Targets)
		u.idx = 0
	}
}

func (u *Upstream) ensureConn(ctx context.Context) (*h2Conn, error) {
	u.dialMu.Lock()
	defer u.dialMu.Unlock()
	for {
		u.mu.Lock()
		conn, gen, idx := u.conn, u.gen, u.idx
		cands := append([]Target(nil), u.candidates...)
		u.mu.Unlock()

		if conn != nil && conn.alive() {
			return conn, nil
		}
		if conn != nil {
			conn.close()
			u.mu.Lock()
			if u.conn == conn {
				u.conn = nil
			}
			u.mu.Unlock()
		}
		n := len(cands)
		if n == 0 {
			return nil, &UpstreamError{Msg: "no servers available for this country"}
		}

		var got *h2Conn
		var gotIdx int
		var last error
		for k := 0; k < n; k++ {
			t := cands[(idx+k)%n]
			c, err := dialH2(ctx, t.Host, t.Port, u.tls)
			if err != nil {
				last = err
				slog.Warn("server failed", "server", net.JoinHostPort(t.Host, strconv.Itoa(t.Port)), "err", err)
				if ctx.Err() != nil {
					return nil, ctx.Err()
				}
				continue
			}
			got, gotIdx = c, (idx+k)%n
			break
		}
		if got == nil {
			u.mu.Lock()
			if u.gen == gen {
				u.idx = (idx + 1) % n
			}
			u.mu.Unlock()
			return nil, upstreamf("all servers failed (last error: %v)", last)
		}

		u.mu.Lock()
		if u.gen != gen { // the country changed while dialing: throw this connection away
			u.mu.Unlock()
			got.close()
			continue
		}
		target := cands[gotIdx]
		u.conn, u.current, u.idx = got, &target, gotIdx
		u.mu.Unlock()
		slog.Info("tunnel up", "via", net.JoinHostPort(target.Host, strconv.Itoa(target.Port)))
		return got, nil
	}
}

// life returns a context that ends when ctx ends or the upstream is closed.
func (u *Upstream) life(ctx context.Context) (context.Context, context.CancelFunc) {
	c, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(u.ctx, cancel)
	return c, func() { stop(); cancel() }
}

// Preflight fetches a pass and connects, so the first real request is fast and errors show now.
func (u *Upstream) Preflight(ctx context.Context) error {
	ctx, done := u.life(ctx)
	defer done()
	if _, err := u.tokens.Get(ctx); err != nil {
		return err
	}
	_, err := u.ensureConn(ctx)
	return err
}

// Open opens a tunnel stream to host:port (up to three attempts, re-fetching the pass on 401/403/407).
func (u *Upstream) Open(ctx context.Context, host string, port int) (Stream, error) {
	authority := net.JoinHostPort(host, strconv.Itoa(port)) // brackets IPv6 literals
	ctx, done := u.life(ctx)
	defer done()
	var last error
	for attempt := 1; attempt <= 3; attempt++ {
		conn, err := u.ensureConn(ctx)
		if err != nil {
			return nil, err
		}
		token, err := u.tokens.Get(ctx)
		if err != nil {
			return nil, err
		}
		st, err := conn.openStream(u.ctx, authority, token)
		if err == nil {
			return st, nil
		}
		last = err
		var he *UpstreamHTTPError
		var cl *ConnectionLostError
		switch {
		case errors.As(err, &he):
			if (he.Status == 401 || he.Status == 403 || he.Status == 407) && attempt < 3 {
				u.tokens.Invalidate()
				continue
			}
			return nil, err
		case errors.As(err, &cl):
			continue
		default:
			return nil, err
		}
	}
	if last == nil {
		last = &UpstreamError{Msg: "could not open a tunnel stream"}
	}
	return nil, last
}

// Close shuts the connection and every stream down; the upstream cannot be reused.
func (u *Upstream) Close() {
	u.cancel()
	u.mu.Lock()
	old := u.conn
	u.conn = nil
	u.mu.Unlock()
	if old != nil {
		old.close()
	}
}
