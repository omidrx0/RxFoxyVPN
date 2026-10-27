package sysproxy

import (
	"net"
	"net/http"
	"net/url"
	"path"
	"strings"
)

// ProxyFunc is an http.Transport.Proxy function that follows the environment variables and,
// on Windows, the system proxy (with its bypass list) — the behaviour Python requests had.
func ProxyFunc(req *http.Request) (*url.URL, error) {
	if u, err := http.ProxyFromEnvironment(req); u != nil || err != nil {
		return u, err
	}
	return systemProxyFor(req)
}

// bypassed reports whether host matches the ProxyOverride list (";"-separated, "*" wildcards,
// "<local>" = names without a dot).
func bypassed(host, override string) bool {
	host = strings.ToLower(host)
	if host == "" {
		return true
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		return true
	}
	for _, entry := range strings.Split(override, ";") {
		entry = strings.ToLower(strings.TrimSpace(entry))
		switch {
		case entry == "":
			continue
		case entry == "<local>":
			if !strings.Contains(host, ".") {
				return true
			}
		default:
			if ok, _ := path.Match(entry, host); ok {
				return true
			}
		}
	}
	return false
}
