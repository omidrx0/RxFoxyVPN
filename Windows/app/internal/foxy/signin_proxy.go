package foxy

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	xproxy "golang.org/x/net/proxy"
)

const (
	signInProxyRequestTimeout = 15 * time.Second
	signInProxyDialTimeout    = 30 * time.Second
	signInProxyDNSWait        = 5 * time.Second
)

type browserSignInProxy struct {
	listener net.Listener
	dial     func(context.Context, string, string) (net.Conn, error)
	ctx      context.Context
	cancel   context.CancelFunc

	mu     sync.Mutex
	closed bool
	conns  map[net.Conn]struct{}
	wg     sync.WaitGroup

	closeOnce sync.Once
	closeErr  error
}

func newBrowserSignInProxy(cp *ControlPlane) (*browserSignInProxy, error) {
	if cp == nil {
		return nil, errors.New("Firefox sign-in proxy requires a control-plane client")
	}
	return newBrowserSignInProxyWithDial(cp.dialBrowserSignIn)
}

func newBrowserSignInProxyWithDial(dial func(context.Context, string, string) (net.Conn, error)) (*browserSignInProxy, error) {
	if dial == nil {
		return nil, errors.New("Firefox sign-in proxy requires a dialer")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("listen for Firefox sign-in proxy: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	proxy := &browserSignInProxy{
		listener: listener,
		dial:     dial,
		ctx:      ctx,
		cancel:   cancel,
		conns:    make(map[net.Conn]struct{}),
	}
	proxy.wg.Add(1)
	go proxy.serve()
	return proxy, nil
}

func (p *browserSignInProxy) Addr() string { return p.listener.Addr().String() }

func (p *browserSignInProxy) Close() error {
	p.closeOnce.Do(func() {
		p.cancel()
		p.mu.Lock()
		p.closed = true
		p.closeErr = p.listener.Close()
		for conn := range p.conns {
			_ = conn.Close()
		}
		p.mu.Unlock()

		p.wg.Wait()
		if errors.Is(p.closeErr, net.ErrClosed) {
			p.closeErr = nil
		}
	})
	return p.closeErr
}

func (p *browserSignInProxy) serve() {
	defer p.wg.Done()
	for {
		conn, err := p.listener.Accept()
		if err != nil {
			p.mu.Lock()
			closed := p.closed
			p.mu.Unlock()
			if closed {
				return
			}
			time.Sleep(50 * time.Millisecond)
			continue
		}
		if !p.track(conn) {
			_ = conn.Close()
			continue
		}
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			defer p.untrack(conn)
			defer conn.Close()
			p.handle(conn)
		}()
	}
}

func (p *browserSignInProxy) track(conn net.Conn) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return false
	}
	p.conns[conn] = struct{}{}
	return true
}

func (p *browserSignInProxy) untrack(conn net.Conn) {
	p.mu.Lock()
	delete(p.conns, conn)
	p.mu.Unlock()
}

func (p *browserSignInProxy) handle(client net.Conn) {
	_ = client.SetReadDeadline(time.Now().Add(signInProxyRequestTimeout))
	reader := bufio.NewReader(client)
	req, err := http.ReadRequest(reader)
	if err != nil {
		return
	}
	defer req.Body.Close()
	if req.Method != http.MethodConnect {
		writeSignInProxyResponse(client, http.StatusMethodNotAllowed)
		return
	}

	host, port, err := net.SplitHostPort(req.Host)
	if err != nil || port != "443" || !IsBrowserSignInNavigationAllowed("https://"+net.JoinHostPort(host, port)) {
		writeSignInProxyResponse(client, http.StatusForbidden)
		return
	}

	ctx, cancel := context.WithTimeout(p.ctx, signInProxyDialTimeout)
	upstream, err := p.dial(ctx, "tcp", net.JoinHostPort(host, port))
	cancel()
	if err != nil {
		slog.Warn("browser sign-in proxy could not connect", "host", host, "err", err)
		writeSignInProxyResponse(client, http.StatusBadGateway)
		return
	}
	defer upstream.Close()
	if !p.track(upstream) {
		return
	}
	defer p.untrack(upstream)

	_ = client.SetReadDeadline(time.Time{})
	if _, err := io.WriteString(client, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}

	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, reader)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, upstream)
		done <- struct{}{}
	}()
	<-done
}

func writeSignInProxyResponse(conn net.Conn, status int) {
	_, _ = fmt.Fprintf(conn, "HTTP/1.1 %d %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", status, http.StatusText(status))
}

func (c *ControlPlane) dialBrowserSignIn(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil || port != "443" || !IsBrowserSignInNavigationAllowed("https://"+net.JoinHostPort(host, port)) {
		return nil, errors.New("browser sign-in proxy refused an unapproved destination")
	}
	if c == nil || c.client == nil {
		return nil, errors.New("control-plane client is unavailable for browser sign-in")
	}
	transport, ok := c.client.Transport.(*http.Transport)
	if !ok {
		return nil, errors.New("control-plane transport cannot open browser sign-in connections")
	}
	// Match the working in-app sign-in route: ignore environment and Windows
	// system proxies, which may point to a stopped local proxy. Honor only
	// the proxy explicitly configured for the control plane.
	proxyURL := c.apiProxy
	if proxyURL == nil {
		c.jarMu.RLock()
		resolver := c.browserSignInDNSResolver
		c.jarMu.RUnlock()
		if resolver != nil {
			dialer := &net.Dialer{
				Timeout:  signInProxyDialTimeout,
				Resolver: resolver,
			}
			dnsCtx, cancel := context.WithTimeout(ctx, signInProxyDNSWait)
			conn, err := dialer.DialContext(dnsCtx, network, address)
			cancel()
			if err == nil {
				return conn, nil
			}
			slog.Debug("browser sign-in direct DNS failed; retrying with system DNS", "host", host, "err", err)
		}
		return transport.DialContext(ctx, network, address)
	}

	switch strings.ToLower(proxyURL.Scheme) {
	case "http", "https":
		return dialHTTPProxy(ctx, transport, network, proxyURL, address)
	case "socks5", "socks5h":
		dialer, err := xproxy.FromURL(proxyURL, transportContextDialer{transport: transport})
		if err != nil {
			return nil, fmt.Errorf("configure browser sign-in SOCKS proxy: %w", err)
		}
		contextDialer, ok := dialer.(xproxy.ContextDialer)
		if !ok {
			return nil, errors.New("browser sign-in SOCKS proxy does not support cancellation")
		}
		return contextDialer.DialContext(ctx, network, address)
	default:
		return nil, fmt.Errorf("unsupported browser sign-in proxy scheme %q", proxyURL.Scheme)
	}
}

func newBrowserSignInResolver(dnsAddress string) *net.Resolver {
	dialer := &net.Dialer{Timeout: signInProxyDialTimeout}
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			return dialer.DialContext(ctx, network, dnsAddress)
		},
	}
}

type transportContextDialer struct {
	transport *http.Transport
}

func (d transportContextDialer) Dial(network, address string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(context.Background(), signInProxyDialTimeout)
	defer cancel()
	return d.DialContext(ctx, network, address)
}

func (d transportContextDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return d.transport.DialContext(ctx, network, address)
}

func dialHTTPProxy(ctx context.Context, transport *http.Transport, network string, proxyURL *url.URL, target string) (net.Conn, error) {
	proxyScheme := strings.ToLower(proxyURL.Scheme)
	proxyHost := proxyURL.Hostname()
	proxyPort := proxyURL.Port()
	if proxyHost == "" {
		return nil, errors.New("browser sign-in proxy has no host")
	}
	if proxyPort == "" {
		if proxyScheme == "https" {
			proxyPort = "443"
		} else {
			proxyPort = "80"
		}
	}
	portNumber, err := strconv.Atoi(proxyPort)
	if err != nil {
		return nil, fmt.Errorf("browser sign-in proxy has an invalid port: %w", err)
	}
	if portNumber < 1 || portNumber > 65535 {
		return nil, errors.New("browser sign-in proxy has an invalid port")
	}

	conn, err := transport.DialContext(ctx, network, net.JoinHostPort(proxyHost, proxyPort))
	if err != nil {
		return nil, fmt.Errorf("connect to browser sign-in proxy: %w", err)
	}
	if proxyScheme == "https" {
		tlsConn := tls.Client(conn, &tls.Config{
			ServerName: proxyHost,
			MinVersion: tls.VersionTLS12,
		})
		if err := tlsConn.HandshakeContext(ctx); err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("secure browser sign-in proxy connection: %w", err)
		}
		conn = tlsConn
	}

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if _, err := fmt.Fprintf(conn, "CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Connection: Keep-Alive\r\n", target, target); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("request browser sign-in tunnel: %w", err)
	}
	if proxyURL.User != nil {
		password, _ := proxyURL.User.Password()
		credentials := proxyURL.User.Username() + ":" + password
		if _, err := fmt.Fprintf(conn, "Proxy-Authorization: Basic %s\r\n", base64.StdEncoding.EncodeToString([]byte(credentials))); err != nil {
			_ = conn.Close()
			return nil, fmt.Errorf("authenticate browser sign-in proxy: %w", err)
		}
	}
	if _, err := io.WriteString(conn, "\r\n"); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("finish browser sign-in tunnel request: %w", err)
	}

	reader := bufio.NewReader(conn)
	resp, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("read browser sign-in proxy response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		_ = conn.Close()
		return nil, fmt.Errorf("browser sign-in proxy rejected the tunnel: %s", resp.Status)
	}
	if err := conn.SetDeadline(time.Time{}); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("clear browser sign-in proxy deadline: %w", err)
	}
	return &bufferedConn{Conn: conn, reader: reader}, nil
}

type bufferedConn struct {
	net.Conn
	reader *bufio.Reader
}

func (c *bufferedConn) Read(p []byte) (int, error) { return c.reader.Read(p) }
