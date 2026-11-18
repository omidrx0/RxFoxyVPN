package foxy

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
)

func TestBrowserSignInResolverUsesConfiguredDNS(t *testing.T) {
	listener, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = listener.Close() })

	go func() {
		buffer := make([]byte, 1500)
		for {
			n, addr, readErr := listener.ReadFrom(buffer)
			if readErr != nil {
				return
			}
			var query dnsmessage.Message
			if query.Unpack(buffer[:n]) != nil {
				continue
			}
			response := dnsmessage.Message{
				Header:    dnsmessage.Header{ID: query.Header.ID, Response: true},
				Questions: query.Questions,
			}
			for _, question := range query.Questions {
				if question.Type != dnsmessage.TypeA {
					continue
				}
				response.Answers = append(response.Answers, dnsmessage.Resource{
					Header: dnsmessage.ResourceHeader{
						Name:  question.Name,
						Type:  dnsmessage.TypeA,
						Class: dnsmessage.ClassINET,
						TTL:   60,
					},
					Body: &dnsmessage.AResource{A: [4]byte{203, 0, 113, 12}},
				})
			}
			packed, packErr := response.Pack()
			if packErr == nil {
				_, _ = listener.WriteTo(packed, addr)
			}
		}
	}()

	resolver := newBrowserSignInResolver(listener.LocalAddr().String())
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	addresses, err := resolver.LookupIPAddr(ctx, "accounts.firefox.com")
	if err != nil {
		t.Fatal(err)
	}
	for _, address := range addresses {
		if address.IP.Equal(net.IPv4(203, 0, 113, 12)) {
			return
		}
	}
	t.Fatalf("configured DNS response = %v, want 203.0.113.12", addresses)
}

func TestBrowserSignInProxyAllowsApprovedConnectAndRelaysData(t *testing.T) {
	dialed := make(chan string, 1)
	proxy, err := newBrowserSignInProxyWithDial(func(_ context.Context, network, address string) (net.Conn, error) {
		if network != "tcp" {
			t.Errorf("unexpected network: %s", network)
		}
		dialed <- address
		local, remote := net.Pipe()
		go func() {
			defer remote.Close()
			request := make([]byte, len("hello"))
			if _, err := io.ReadFull(remote, request); err != nil {
				t.Errorf("read relayed request: %v", err)
				return
			}
			if string(request) != "hello" {
				t.Errorf("relayed request = %q, want hello", request)
				return
			}
			_, _ = io.WriteString(remote, "world")
		}()
		return local, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()

	client, err := net.Dial("tcp", proxy.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := fmt.Fprint(client, "CONNECT accounts.firefox.com:443 HTTP/1.1\r\nHost: accounts.firefox.com:443\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(client)
	response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodConnect})
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("CONNECT status = %d, want 200", response.StatusCode)
	}
	if got := <-dialed; got != "accounts.firefox.com:443" {
		t.Fatalf("upstream address = %q", got)
	}
	if _, err := io.WriteString(client, "hello"); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, len("world"))
	if _, err := io.ReadFull(reader, reply); err != nil {
		t.Fatal(err)
	}
	if string(reply) != "world" {
		t.Fatalf("tunnel response = %q, want world", reply)
	}
}

func TestBrowserSignInProxyRejectsUnapprovedConnect(t *testing.T) {
	dialCalled := make(chan struct{}, 1)
	proxy, err := newBrowserSignInProxyWithDial(func(context.Context, string, string) (net.Conn, error) {
		dialCalled <- struct{}{}
		return nil, fmt.Errorf("unexpected dial")
	})
	if err != nil {
		t.Fatal(err)
	}
	defer proxy.Close()

	client, err := net.Dial("tcp", proxy.Addr())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	_ = client.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := fmt.Fprint(client, "CONNECT attacker.example:443 HTTP/1.1\r\nHost: attacker.example:443\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	response, err := http.ReadResponse(bufio.NewReader(client), &http.Request{Method: http.MethodConnect})
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusForbidden {
		t.Fatalf("CONNECT status = %d, want 403", response.StatusCode)
	}
	select {
	case <-dialCalled:
		t.Fatal("proxy dialed an unapproved destination")
	default:
	}
}

func TestBrowserAuthCoordinatorOwnsSignInProxyLifecycle(t *testing.T) {
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	acct := NewAccount(cp, "")
	var proxyAddress string
	coord := NewBrowserAuthCoordinator(acct, BrowserAuthStarter{
		LaunchWebViewHelper: func(_, _, _, addr string) (func(), error) {
			proxyAddress = addr
			return func() {}, nil
		},
	}, nil)

	status, err := coord.Start(BrowserModeWebView2, "")
	if err != nil {
		t.Fatal(err)
	}
	if status.Mode != BrowserModeWebView2 || proxyAddress == "" {
		t.Fatalf("WebView2 launch did not receive a proxy address: status=%+v address=%q", status, proxyAddress)
	}
	conn, err := net.DialTimeout("tcp", proxyAddress, time.Second)
	if err != nil {
		t.Fatalf("sign-in proxy was not listening: %v", err)
	}
	_ = conn.Close()

	coord.Cancel()
	conn, err = net.DialTimeout("tcp", proxyAddress, 200*time.Millisecond)
	if err == nil {
		_ = conn.Close()
		t.Fatal("sign-in proxy remained open after the browser session was cancelled")
	}
}

func TestBrowserAuthCoordinatorDoesNotOpenExternalBrowserOnInAppFailure(t *testing.T) {
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	externalOpened := false
	coord := NewBrowserAuthCoordinator(NewAccount(cp, ""), BrowserAuthStarter{
		LaunchWebViewHelper: func(_, _, _, _ string) (func(), error) {
			return nil, errors.New("in-app host unavailable")
		},
		OpenExternalURL: func(string) error {
			externalOpened = true
			return nil
		},
	}, nil)

	if _, err := coord.Start(BrowserModeWebView2, ""); err == nil {
		t.Fatal("expected in-app browser launch error")
	}
	if externalOpened {
		t.Fatal("in-app browser failure unexpectedly opened an external browser")
	}
	if status := coord.Status(); status.Active {
		t.Fatalf("failed in-app launch left a session active: %+v", status)
	}
}

func TestBrowserAuthCoordinatorKeepsEarlyHelperErrors(t *testing.T) {
	cp, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	coord := NewBrowserAuthCoordinator(NewAccount(cp, ""), BrowserAuthStarter{
		LaunchWebViewHelper: func(ipcURL, ipcSecret, _, _ string) (func(), error) {
			req, err := http.NewRequest(http.MethodPost, ipcURL, strings.NewReader(
				`{"event":"error","error":"WebView2 runtime is not available"}`,
			))
			if err != nil {
				return nil, err
			}
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("X-RxVPN-Auth-Token", ipcSecret)
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				return nil, err
			}
			_ = resp.Body.Close()
			if resp.StatusCode != http.StatusOK {
				return nil, fmt.Errorf("helper IPC status = %d, want %d", resp.StatusCode, http.StatusOK)
			}
			return func() {}, nil
		},
	}, nil)

	status, err := coord.Start(BrowserModeWebView2, "")
	if err != nil {
		t.Fatal(err)
	}
	if status.Active || status.Stage != BrowserStageFailed || status.Error != "WebView2 runtime is not available" {
		t.Fatalf("early helper error was lost: %+v", status)
	}
}

func TestControlPlaneBrowserSignInDialUsesConfiguredHTTPProxy(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()
	proxyURL, err := url.Parse("http://user:pass@proxy.example:8080")
	if err != nil {
		t.Fatal(err)
	}
	transport := &http.Transport{
		DialContext: func(_ context.Context, network, address string) (net.Conn, error) {
			if network != "tcp" || address != "proxy.example:8080" {
				return nil, fmt.Errorf("unexpected proxy dial %s %s", network, address)
			}
			return client, nil
		},
	}
	cp := &ControlPlane{client: &http.Client{Transport: transport}, apiProxy: proxyURL}

	requestErr := make(chan error, 1)
	go func() {
		reader := bufio.NewReader(server)
		req, err := http.ReadRequest(reader)
		if err != nil {
			requestErr <- err
			return
		}
		if req.Method != http.MethodConnect || req.Host != "accounts.firefox.com:443" {
			requestErr <- fmt.Errorf("unexpected CONNECT request: %s %s", req.Method, req.Host)
			return
		}
		if got := req.Header.Get("Proxy-Authorization"); got != "Basic dXNlcjpwYXNz" {
			requestErr <- fmt.Errorf("proxy authorization = %q", got)
			return
		}
		_, err = io.WriteString(server, "HTTP/1.1 200 Connection Established\r\n\r\npayload")
		requestErr <- err
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	conn, err := cp.dialBrowserSignIn(ctx, "tcp", "accounts.firefox.com:443")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	payload := make([]byte, len("payload"))
	if _, err := io.ReadFull(conn, payload); err != nil {
		t.Fatal(err)
	}
	if string(payload) != "payload" {
		t.Fatalf("tunneled payload = %q, want payload", payload)
	}
	if err := <-requestErr; err != nil {
		t.Fatal(err)
	}
}

func TestControlPlaneBrowserSignInDialUsesDirectTransport(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()
	systemProxyURL, err := url.Parse("http://stopped-proxy.example:8080")
	if err != nil {
		t.Fatal(err)
	}
	transport := &http.Transport{
		Proxy: func(*http.Request) (*url.URL, error) {
			return systemProxyURL, nil
		},
		DialContext: func(_ context.Context, network, address string) (net.Conn, error) {
			if network != "tcp" || address != "accounts.firefox.com:443" {
				return nil, fmt.Errorf("unexpected direct dial %s %s", network, address)
			}
			return client, nil
		},
	}
	cp := &ControlPlane{client: &http.Client{Transport: transport}}

	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := cp.dialBrowserSignIn(ctx, "tcp", "accounts.firefox.com:443")
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.Close()
}

func TestNewControlPlaneUsesOnlyExplicitAPIProxy(t *testing.T) {
	direct, err := NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	directTransport, ok := direct.client.Transport.(*http.Transport)
	if !ok {
		t.Fatal("control-plane transport is not an HTTP transport")
	}
	if directTransport.Proxy != nil {
		t.Fatal("default control-plane unexpectedly uses an ambient proxy")
	}

	configured, err := NewControlPlane("http://proxy.example:8080")
	if err != nil {
		t.Fatal(err)
	}
	configuredTransport, ok := configured.client.Transport.(*http.Transport)
	if !ok {
		t.Fatal("configured control-plane transport is not an HTTP transport")
	}
	proxyURL, err := configuredTransport.Proxy(&http.Request{
		URL: &url.URL{Scheme: "https", Host: "accounts.firefox.com"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if proxyURL == nil || proxyURL.String() != "http://proxy.example:8080" {
		t.Fatalf("explicit API proxy = %v", proxyURL)
	}
}
