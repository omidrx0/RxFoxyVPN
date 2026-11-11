// Package foxy talks to Mozilla: it signs in with a Firefox account (FxA "picl" login + OAuth,
// email/TOTP/unblock verification, and Mozilla WebChannel / external OAuth PKCE browser login),
// gets short-lived proxy passes and 4-state subscription quota from Guardian, downloads the server
// list from Remote Settings, recovers HTTP 451 via allow-listed relays, opens HTTP/2 CONNECT
// tunnels to the Fastly edge servers and exposes them as local SOCKS5 / HTTP proxies.
package foxy

const (
	fxaAuthServer   = "https://api.accounts.firefox.com/v1"
	firefoxClientID = "5882386c6d801776"
	// Public Mozilla OAuth PKCE client ("Firefox for Android") with registered HTTPS success redirect URI,
	// used when completing OAuth + PKCE sign-in in the external system browser.
	firefoxPublicOAuthClientID = "a2270f727f45f648"
	firefoxPublicOAuthRedirect = "https://accounts.firefox.com/oauth/success/a2270f727f45f648"

	oauthScope      = "profile https://identity.mozilla.com/apps/vpn"
	protocolVersion = "identity.mozilla.com/picl/v1/"
	guardianURL     = "https://vpn.mozilla.org"
	serverListURL   = "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/vpn-serverlist/records"
	fxaProfileURL   = "https://profile.accounts.firefox.com/v1/profile"

	// UserAgent matches the direct Firefox Accounts client profile used by
	// the Android RXFoxyVPN implementation.
	UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101 Firefox/158.0"

	powAlphabet     = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	defaultTokenTTL = 24 * 3600

	fxaOAuthTokenURL = "https://oauth.accounts.firefox.com/v1/token"
)
