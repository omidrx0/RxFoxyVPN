// Package tun builds the sing-box configuration for full-device TUN mode and runs / stops the
// sing-box process (kept inside a Windows job object so it can never outlive the panel).
package tun

import (
	"regexp"
	"strings"

	"rxvpn/internal/config"
)

var ansiEscapePattern = regexp.MustCompile(`\x1b\[[0-?]*[ -/]*[@-~]`)

// StripANSI removes ANSI terminal escape sequences from log text.
func StripANSI(s string) string {
	return ansiEscapePattern.ReplaceAllString(s, "")
}

var excludedCIDRs = []string{
	"127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
	"::1/128", "fc00::/7", "fe80::/10",
}

var loopbackCIDRs = []string{"127.0.0.0/8", "::1/128"}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

// MakeConfig builds the sing-box config that sends all traffic into the local SOCKS5 proxy.
func MakeConfig(socksPort int, s config.Settings) map[string]any {
	ipv6 := s.IPv6
	keep := func(cidrs []string) []string {
		out := []string{}
		for _, c := range cidrs {
			if ipv6 || !strings.Contains(c, ":") {
				out = append(out, c)
			}
		}
		return out
	}

	excluded := keep(loopbackCIDRs)
	if s.BypassLAN {
		var lan []string
		for _, c := range excludedCIDRs {
			if !contains(loopbackCIDRs, c) {
				lan = append(lan, c)
			}
		}
		excluded = append(excluded, keep(lan)...)
	}
	var extra []string
	for _, c := range keep(s.DirectCIDRs) {
		if !contains(excluded, c) {
			extra = append(extra, c)
		}
	}
	excluded = append(excluded, extra...)

	address := []string{"172.19.0.1/30"}
	if ipv6 {
		address = append(address, "fdfe:dcba:9876::1/126")
	}
	tunIn := map[string]any{
		"type":                  "tun",
		"tag":                   "rxvpn-tun",
		"interface_name":        "RXVPN",
		"address":               address,
		"auto_route":            true,
		"strict_route":          s.StrictRoute,
		"route_exclude_address": excluded,
	}
	if s.TunStack != "auto" {
		tunIn["stack"] = s.TunStack
	}
	if s.TunMTU != 0 {
		tunIn["mtu"] = s.TunMTU
	}

	remoteDNS := map[string]any{
		"type":   s.DNSType,
		"tag":    "dns-over-vpn",
		"server": s.DNSServer,
		"detour": "firefox-socks",
	}
	if s.DNSType == "https" {
		remoteDNS["path"] = s.DNSPath
	}
	strategy := "ipv4_only"
	if ipv6 {
		strategy = s.DNSStrategy
	}
	dns := map[string]any{
		"servers":  []any{remoteDNS},
		"final":    "dns-over-vpn",
		"strategy": strategy,
	}

	apps, domains := s.DirectApps, s.DirectDomains
	udpDirect := s.UDPPolicy == "direct"
	rules := []any{map[string]any{"network": "udp", "port": 53, "action": "hijack-dns"}}
	if len(domains) > 0 {
		// TUN only sees IP addresses; sniffing recovers the domain name so domain rules can match
		rules = append(rules, map[string]any{"action": "sniff"})
		dns["rules"] = []any{map[string]any{"domain_suffix": domains, "action": "route", "server": "dns-direct"}}
	}
	if len(apps) > 0 {
		rules = append(rules, map[string]any{"process_name": apps, "action": "route", "outbound": "direct"})
	}
	if len(domains) > 0 {
		rules = append(rules, map[string]any{"domain_suffix": domains, "action": "route", "outbound": "direct"})
	}
	if udpDirect {
		rules = append(rules, map[string]any{"network": "udp", "action": "route", "outbound": "direct"})
	} else {
		rules = append(rules, map[string]any{"network": "udp", "action": "reject", "method": "default"})
	}

	outbounds := []any{
		map[string]any{
			"type": "socks", "tag": "firefox-socks", "server": "127.0.0.1",
			"server_port": socksPort, "version": "5", "network": "tcp",
		},
		map[string]any{"type": "block", "tag": "block"},
	}
	needsDirect := len(apps) > 0 || len(domains) > 0 || udpDirect
	if needsDirect {
		outbounds = append(outbounds, map[string]any{"type": "direct", "tag": "direct"})
		// the direct outbound resolves domain names itself, so sing-box >= 1.12 requires a default
		// domain resolver that does NOT go through the VPN
		dns["servers"] = append(dns["servers"].([]any),
			map[string]any{"type": "udp", "tag": "dns-direct", "server": s.DirectDNS})
	}

	route := map[string]any{
		"auto_detect_interface": true,
		"rules":                 rules,
		"final":                 "firefox-socks",
	}
	if needsDirect {
		route["default_domain_resolver"] = "dns-direct"
	}

	return map[string]any{
		"log":       map[string]any{"level": s.LogLevel, "timestamp": true},
		"dns":       dns,
		"inbounds":  []any{tunIn},
		"outbounds": outbounds,
		"route":     route,
	}
}
