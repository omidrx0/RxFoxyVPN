// Package foxy talks to Mozilla: it signs in with a Firefox account (FxA "picl" login + OAuth,
// two-factor by e-mail code), gets short-lived proxy passes from Guardian, downloads the server
// list from Remote Settings, opens HTTP/2 CONNECT tunnels to the Fastly edge servers and
// exposes them as local SOCKS5 / HTTP proxies.
//
// It is the Go port of foxy_bridge.py (the console front end of that script is not part of
// the desktop app and is not ported).
package foxy

const (
	fxaAuthServer   = "https://api.accounts.firefox.com/v1"
	firefoxClientID = "5882386c6d801776"
	oauthScope      = "profile https://identity.mozilla.com/apps/vpn"
	protocolVersion = "identity.mozilla.com/picl/v1/"
	guardianURL     = "https://vpn.mozilla.org"
	serverListURL   = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"

	// UserAgent is what the official client sends.
	UserAgent = "MozillaVPN/2.35.0 (sys:linux; iap:true)"
	// SolverUA is the browser-like agent used while solving the Fastly challenge.
	solverUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
		"Chrome/126.0 Safari/537.36"

	powAlphabet     = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	defaultTokenTTL = 24 * 3600
)
