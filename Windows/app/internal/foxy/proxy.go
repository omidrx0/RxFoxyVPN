package foxy

import (
	"bufio"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

// Stream is one tunnelled connection (an HTTP/2 CONNECT stream).
type Stream interface {
	io.ReadWriter
	CloseWrite()
	Close()
}

// Opener opens tunnel streams; *Upstream is the real one, tests use a fake.
type Opener interface {
	Open(ctx context.Context, host string, port int) (Stream, error)
}

// Stats are the live traffic counters shown in the panel.
type Stats struct {
	Active     atomic.Int64 // connections open right now
	Total      atomic.Int64 // connections served since start
	Uploaded   atomic.Int64 // bytes
	Downloaded atomic.Int64 // bytes
}

// ResetTraffic zeroes the byte counters (done when a new connection session starts).
func (s *Stats) ResetTraffic() {
	s.Uploaded.Store(0)
	s.Downloaded.Store(0)
}

const (
	pipeBuf        = 64 * 1024
	upstreamLinger = 30 * time.Second // how long the upload side may finish after the download ended
	maxHeadBytes   = 64 * 1024
)

// pipe copies bytes both ways between the local client and the tunnel stream, then closes both.
// client is the reader of the local connection (it may hold bytes buffered while parsing).
func pipe(client io.Reader, conn net.Conn, s Stream, initial []byte, stats *Stats) {
	stats.Active.Add(1)
	stats.Total.Add(1)
	defer stats.Active.Add(-1)

	upDone := make(chan struct{})
	go func() {
		defer close(upDone)
		defer s.CloseWrite()
		if len(initial) > 0 {
			if _, err := s.Write(initial); err != nil {
				return
			}
			stats.Uploaded.Add(int64(len(initial)))
		}
		buf := make([]byte, pipeBuf)
		for {
			n, err := client.Read(buf)
			if n > 0 {
				if _, werr := s.Write(buf[:n]); werr != nil {
					return
				}
				stats.Uploaded.Add(int64(n))
			}
			if err != nil {
				return
			}
		}
	}()

	buf := make([]byte, pipeBuf)
	for {
		n, err := s.Read(buf)
		if n > 0 {
			if _, werr := conn.Write(buf[:n]); werr != nil {
				break
			}
			stats.Downloaded.Add(int64(n))
		}
		if err != nil {
			break
		}
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.CloseWrite()
	}
	select {
	case <-upDone:
	case <-time.After(upstreamLinger):
	}
	s.Close()
	_ = conn.Close()
}

// Serve accepts connections until the listener is closed and runs handler for each one.
func Serve(ln net.Listener, handler func(net.Conn)) {
	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			time.Sleep(100 * time.Millisecond)
			continue
		}
		go handler(c)
	}
}

// ---------------------------------------------------------------- SOCKS5

// HandleSOCKS serves one SOCKS5 client (CONNECT only, no authentication).
func HandleSOCKS(ctx context.Context, conn net.Conn, up Opener, stats *Stats) {
	defer conn.Close()
	br := bufio.NewReader(conn)
	reply := func(code byte) { _, _ = conn.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0}) }

	head := make([]byte, 2)
	if _, err := io.ReadFull(br, head); err != nil || head[0] != 5 {
		return
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	if !bytes.Contains(methods, []byte{0}) {
		_, _ = conn.Write([]byte{5, 0xff})
		return
	}
	_, _ = conn.Write([]byte{5, 0})

	req := make([]byte, 4)
	if _, err := io.ReadFull(br, req); err != nil {
		return
	}
	cmd, atyp := req[1], req[3]
	var host string
	switch atyp {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 3:
		l, err := br.ReadByte()
		if err != nil {
			return
		}
		b := make([]byte, l)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		host = string(b)
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		host = net.IP(b).String()
	default:
		reply(8)
		return
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(br, pb); err != nil {
		return
	}
	port := int(binary.BigEndian.Uint16(pb))
	if cmd != 1 { // only CONNECT
		reply(7)
		return
	}
	stream, err := up.Open(ctx, host, port)
	if err != nil {
		slog.Info("SOCKS connect failed", "target", net.JoinHostPort(host, strconv.Itoa(port)), "err", err)
		reply(5)
		return
	}
	reply(0)
	slog.Debug("SOCKS open", "target", net.JoinHostPort(host, strconv.Itoa(port)))
	pipe(br, conn, stream, nil, stats)
}

// ---------------------------------------------------------------- HTTP proxy

func httpError(conn net.Conn, code int, text string) {
	_, _ = fmt.Fprintf(conn, "HTTP/1.1 %d %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", code, text)
	_ = conn.Close()
}

var errHeadTooLarge = errors.New("request header too large")

// readHead reads up to and including the blank line; it returns the head without the final CRLFCRLF.
func readHead(br *bufio.Reader) (string, error) {
	var sb strings.Builder
	for {
		line, err := br.ReadSlice('\n')
		if errors.Is(err, bufio.ErrBufferFull) {
			return "", errHeadTooLarge
		}
		if err != nil {
			return "", err
		}
		if sb.Len()+len(line) > maxHeadBytes {
			return "", errHeadTooLarge
		}
		if bytes.Equal(line, []byte("\r\n")) {
			return strings.TrimSuffix(sb.String(), "\r\n"), nil
		}
		sb.Write(line)
	}
}

// rewriteHTTPRequest turns a proxy-style request (absolute URI) into an origin-style one.
// It returns the target host and port and the rewritten head, or ok=false when the target is
// not an absolute http:// URL.
func rewriteHTTPRequest(method, target, version string, headerLines []string) (host string, port int, raw []byte, ok bool) {
	u, err := url.Parse(target)
	if err != nil || u.Scheme != "http" || u.Hostname() == "" {
		return "", 0, nil, false
	}
	out := []string{method + " " + u.RequestURI() + " " + version}
	hasHost := false
	for _, line := range headerLines {
		name := strings.ToLower(strings.TrimSpace(strings.SplitN(line, ":", 2)[0]))
		switch name {
		case "proxy-connection", "proxy-authorization", "connection", "keep-alive":
			continue
		case "host":
			hasHost = true
		}
		out = append(out, line)
	}
	if !hasHost {
		out = append(out, "Host: "+u.Host)
	}
	out = append(out, "Connection: close")
	port = 80
	if p := u.Port(); p != "" {
		if n, err := strconv.Atoi(p); err == nil {
			port = n
		}
	}
	return u.Hostname(), port, []byte(strings.Join(out, "\r\n") + "\r\n\r\n"), true
}

// HandleHTTP serves one HTTP proxy client: CONNECT tunnels and plain http:// requests.
func HandleHTTP(ctx context.Context, conn net.Conn, up Opener, stats *Stats) {
	br := bufio.NewReaderSize(conn, maxHeadBytes)
	head, err := readHead(br)
	if err != nil {
		if errors.Is(err, errHeadTooLarge) {
			httpError(conn, 431, "Request Header Fields Too Large")
			return
		}
		_ = conn.Close()
		return
	}
	lines := strings.Split(head, "\r\n")
	parts := strings.SplitN(lines[0], " ", 3)
	if len(parts) != 3 {
		httpError(conn, 400, "Bad Request")
		return
	}
	method, target, version := parts[0], parts[1], parts[2]

	var host string
	var port int
	var first []byte
	isConnect := strings.EqualFold(method, "CONNECT")
	if isConnect {
		i := strings.LastIndex(target, ":")
		if i < 0 {
			httpError(conn, 400, "Bad Request")
			return
		}
		host = strings.Trim(target[:i], "[]")
		p, perr := strconv.Atoi(target[i+1:])
		if host == "" || perr != nil || p < 0 {
			httpError(conn, 400, "Bad Request")
			return
		}
		port = p
	} else {
		h, p, raw, ok := rewriteHTTPRequest(method, target, version, lines[1:])
		if !ok {
			httpError(conn, 400, "Bad Request (absolute http:// URL expected)")
			return
		}
		host, port, first = h, p, raw
	}

	stream, err := up.Open(ctx, host, port)
	if err != nil {
		slog.Info("HTTP connect failed", "method", method, "target", net.JoinHostPort(host, strconv.Itoa(port)), "err", err)
		httpError(conn, 502, "Bad Gateway")
		return
	}
	if isConnect {
		if _, err := conn.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
			stream.Close()
			_ = conn.Close()
			return
		}
	}
	slog.Debug("HTTP open", "method", method, "target", net.JoinHostPort(host, strconv.Itoa(port)))
	pipe(br, conn, stream, first, stats)
}
