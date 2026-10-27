package foxy

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeStream plays the tunnel side: it records what was sent and answers with a fixed reply.
type fakeStream struct {
	mu      sync.Mutex
	got     bytes.Buffer
	reply   io.Reader
	closeWr sync.Once
}

func (f *fakeStream) Read(p []byte) (int, error) { return f.reply.Read(p) }
func (f *fakeStream) Write(p []byte) (int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.got.Write(p)
}
func (f *fakeStream) CloseWrite() { f.closeWr.Do(func() {}) }
func (f *fakeStream) Close()      {}
func (f *fakeStream) sent() string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.got.String()
}

type fakeOpener struct {
	host   string
	port   int
	stream *fakeStream
	err    error
}

func (o *fakeOpener) Open(_ context.Context, host string, port int) (Stream, error) {
	o.host, o.port = host, port
	if o.err != nil {
		return nil, o.err
	}
	return o.stream, nil
}

func runHandler(t *testing.T, h func(context.Context, net.Conn, Opener, *Stats), op Opener, stats *Stats) (client net.Conn, done chan struct{}) {
	t.Helper()
	c1, c2 := net.Pipe()
	done = make(chan struct{})
	go func() {
		defer close(done)
		h(context.Background(), c2, op, stats)
	}()
	t.Cleanup(func() { c1.Close() })
	return c1, done
}

func waitDone(t *testing.T, done chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("handler did not finish")
	}
}

func TestSOCKS5Connect(t *testing.T) {
	op := &fakeOpener{stream: &fakeStream{reply: strings.NewReader("from-upstream")}}
	stats := &Stats{}
	c, done := runHandler(t, HandleSOCKS, op, stats)

	mustWrite(t, c, []byte{5, 1, 0})
	if got := mustRead(t, c, 2); !bytes.Equal(got, []byte{5, 0}) {
		t.Fatalf("method selection = %v", got)
	}
	req := append([]byte{5, 1, 0, 3, byte(len("example.com"))}, "example.com"...)
	req = append(req, 0x01, 0xbb) // port 443
	mustWrite(t, c, req)
	if got := mustRead(t, c, 10); got[0] != 5 || got[1] != 0 {
		t.Fatalf("connect reply = %v", got)
	}
	mustWrite(t, c, []byte("ping"))
	if got := string(mustRead(t, c, len("from-upstream"))); got != "from-upstream" {
		t.Fatalf("download = %q", got)
	}
	c.Close()
	waitDone(t, done)

	if op.host != "example.com" || op.port != 443 {
		t.Errorf("opened %s:%d", op.host, op.port)
	}
	if got := op.stream.sent(); got != "ping" {
		t.Errorf("tunnel received %q", got)
	}
	if stats.Total.Load() != 1 || stats.Active.Load() != 0 {
		t.Errorf("stats total=%d active=%d", stats.Total.Load(), stats.Active.Load())
	}
	if stats.Uploaded.Load() != 4 || stats.Downloaded.Load() != int64(len("from-upstream")) {
		t.Errorf("stats up=%d down=%d", stats.Uploaded.Load(), stats.Downloaded.Load())
	}
}

func TestSOCKS5UpstreamFailureReplies5(t *testing.T) {
	op := &fakeOpener{err: errors.New("boom")}
	c, done := runHandler(t, HandleSOCKS, op, &Stats{})
	mustWrite(t, c, []byte{5, 1, 0})
	mustRead(t, c, 2)
	mustWrite(t, c, []byte{5, 1, 0, 1, 1, 2, 3, 4, 0, 80})
	if got := mustRead(t, c, 10); got[1] != 5 {
		t.Errorf("reply code = %d, want 5", got[1])
	}
	waitDone(t, done)
	if op.host != "1.2.3.4" || op.port != 80 {
		t.Errorf("opened %s:%d", op.host, op.port)
	}
}

func TestSOCKS5RejectsUDPAssociate(t *testing.T) {
	c, done := runHandler(t, HandleSOCKS, &fakeOpener{}, &Stats{})
	mustWrite(t, c, []byte{5, 1, 0})
	mustRead(t, c, 2)
	mustWrite(t, c, []byte{5, 3, 0, 1, 0, 0, 0, 0, 0, 0})
	if got := mustRead(t, c, 10); got[1] != 7 {
		t.Errorf("reply code = %d, want 7 (command not supported)", got[1])
	}
	waitDone(t, done)
}

func TestHTTPConnect(t *testing.T) {
	op := &fakeOpener{stream: &fakeStream{reply: strings.NewReader("pong")}}
	c, done := runHandler(t, HandleHTTP, op, &Stats{})
	mustWrite(t, c, []byte("CONNECT example.com:8443 HTTP/1.1\r\nHost: example.com:8443\r\n\r\n"))
	want := "HTTP/1.1 200 Connection Established\r\n\r\n"
	if got := string(mustRead(t, c, len(want))); got != want {
		t.Fatalf("reply = %q", got)
	}
	if got := string(mustRead(t, c, 4)); got != "pong" {
		t.Fatalf("download = %q", got)
	}
	c.Close()
	waitDone(t, done)
	if op.host != "example.com" || op.port != 8443 {
		t.Errorf("opened %s:%d", op.host, op.port)
	}
}

func TestHTTPPlainRequestIsRewritten(t *testing.T) {
	op := &fakeOpener{stream: &fakeStream{reply: strings.NewReader("HTTP/1.1 200 OK\r\n\r\n")}}
	c, done := runHandler(t, HandleHTTP, op, &Stats{})
	mustWrite(t, c, []byte("GET http://example.com:8080/a/b?x=1 HTTP/1.1\r\nUser-Agent: t\r\nProxy-Connection: keep-alive\r\n\r\n"))
	mustRead(t, c, len("HTTP/1.1 200 OK\r\n\r\n"))
	c.Close()
	waitDone(t, done)
	if op.host != "example.com" || op.port != 8080 {
		t.Errorf("opened %s:%d", op.host, op.port)
	}
	want := "GET /a/b?x=1 HTTP/1.1\r\nUser-Agent: t\r\nHost: example.com:8080\r\nConnection: close\r\n\r\n"
	if got := op.stream.sent(); got != want {
		t.Errorf("forwarded request:\n got  %q\n want %q", got, want)
	}
}

func TestHTTPRejectsNonProxyRequest(t *testing.T) {
	c, done := runHandler(t, HandleHTTP, &fakeOpener{}, &Stats{})
	mustWrite(t, c, []byte("GET /relative HTTP/1.1\r\nHost: x\r\n\r\n"))
	line, _ := bufio.NewReader(c).ReadString('\n')
	if !strings.HasPrefix(line, "HTTP/1.1 400") {
		t.Errorf("status line = %q", line)
	}
	waitDone(t, done)
}

func TestRewriteHTTPRequestKeepsHostHeader(t *testing.T) {
	host, port, raw, ok := rewriteHTTPRequest("POST", "http://example.org/x", "HTTP/1.1",
		[]string{"Host: example.org", "Connection: keep-alive", "Proxy-Authorization: Basic zz", "Content-Length: 3"})
	if !ok || host != "example.org" || port != 80 {
		t.Fatalf("got %v %s %d", ok, host, port)
	}
	want := "POST /x HTTP/1.1\r\nHost: example.org\r\nContent-Length: 3\r\nConnection: close\r\n\r\n"
	if string(raw) != want {
		t.Errorf("got %q want %q", raw, want)
	}
	if _, _, _, ok := rewriteHTTPRequest("GET", "https://example.org/", "HTTP/1.1", nil); ok {
		t.Error("https:// absolute URLs must be refused")
	}
}

func TestReadHeadLimit(t *testing.T) {
	big := "GET / HTTP/1.1\r\nX: " + strings.Repeat("a", maxHeadBytes) + "\r\n\r\n"
	br := bufio.NewReaderSize(strings.NewReader(big), maxHeadBytes)
	if _, err := readHead(br); !errors.Is(err, errHeadTooLarge) {
		t.Errorf("err = %v, want errHeadTooLarge", err)
	}
}

func mustWrite(t *testing.T, c net.Conn, b []byte) {
	t.Helper()
	_ = c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := c.Write(b); err != nil {
		t.Fatalf("write: %v", err)
	}
}

func mustRead(t *testing.T, c net.Conn, n int) []byte {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, n)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatalf("read %d bytes: %v", n, err)
	}
	return buf
}
