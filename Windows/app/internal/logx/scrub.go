// Package logx carries the log primitives every package uses: email masking, short
// account ids, and a scrubber that removes secrets before anything is written.
//
// Order of operations is deliberate: structured (quoted) secrets first, then
// key=value pairs, then bearer/basic tokens, then raw JWTs, then email addresses.
// Reversing this order lets a generic match eat the prefix of a Hawk/JWT blob and
// leave the tail in the clear.
package logx

import (
	"regexp"
	"strings"
)

var (
	// quoted JSON-ish pair: "access_token": "..."
	jsonSecretRe = regexp.MustCompile(`(?i)"(access_token|refresh_token|client_secret|client_id|password|authorization|proxy-authorization|mac|nonce|id|state|code|api[_-]?key|token|session_token|device_id)"\s*:\s*"[^"]*"`)

	// mac=..., nonce=... (Hawk artefacts wherever they appear, quoted or in prose)
	hawkRe = regexp.MustCompile(`(?i)\b(mac|nonce)="[^"]*"`)

	// key=value / key: value / "key": value in any text
	kvSecretRe = regexp.MustCompile(`(?i)(access_token|refresh_token|client_secret|password|authorization|proxy-authorization|api[_-]?key|session_token|device_id|token)\s*[:=]\s*("[^"]*"|'[^']*'|[^\s,;&]+)`)

	// bearer / basic credentials in prose
	schemeSecretRe = regexp.MustCompile(`(?i)\b(bearer|basic)\s+[A-Za-z0-9._\-+/=]{8,}`)

	// raw JWTs (they start with eyJ)
	jwtRe = regexp.MustCompile(`\beyJ[A-Za-z0-9_-]{8,}(\.[A-Za-z0-9_-]{2,}){1,2}\b`)

	// emails anywhere in the text
	emailRe = regexp.MustCompile(`[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`)
)

const redacted = "[REDACTED]"

// Scrub removes credentials and personal data from a free-form log line.
func Scrub(s string) string {
	s = jsonSecretRe.ReplaceAllStringFunc(s, func(m string) string {
		return strings.Split(m, ":")[0] + `:"` + redacted + `"`
	})
	s = hawkRe.ReplaceAllString(s, `${1}="`+redacted+`"`)
	s = kvSecretRe.ReplaceAllString(s, `${1}=`+redacted)
	s = schemeSecretRe.ReplaceAllString(s, `${1} `+redacted)
	s = jwtRe.ReplaceAllString(s, redacted)
	s = emailRe.ReplaceAllStringFunc(s, func(m string) string {
		_, domain, ok := strings.Cut(m, "@")
		if !ok {
			return redacted
		}
		return "***@" + domain
	})
	return s
}

// ScrubValue renders v into a scrubbed string for structured logging.
func ScrubValue(v any) any {
	switch t := v.(type) {
	case string:
		return Scrub(t)
	case error:
		return Scrub(t.Error())
	default:
		return v
	}
}

// Email renders an email log-safely: first letter, stars, the domain.
// "" in, "" out; anything without '@' becomes "***".
func Email(email string) string {
	email = strings.TrimSpace(email)
	if email == "" {
		return ""
	}
	local, domain, ok := strings.Cut(email, "@")
	if !ok {
		return "***"
	}
	r := []rune(local)
	if len(r) == 0 {
		return "***@" + domain
	}
	return string(r[:1]) + "***@" + domain
}

// ID renders an account id log-safely (first 8 chars, nothing more).
func ID(id string) string {
	if len(id) <= 8 {
		return id
	}
	return id[:8]
}
