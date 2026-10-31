package foxy

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/url"
	"strings"
	"time"
)

func hmacSHA256(key, data []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(data)
	return m.Sum(nil)
}

// HKDF is RFC 5869 HKDF-SHA256. A nil salt means 32 zero bytes (same as the app).
func HKDF(ikm, info []byte, length int, salt []byte) []byte {
	if salt == nil {
		salt = make([]byte, 32)
	}
	prk := hmacSHA256(salt, ikm)
	var out, prev []byte
	for counter := 1; len(out) < length; counter++ {
		block := append(append(append([]byte{}, prev...), info...), byte(counter))
		prev = hmacSHA256(prk, block)
		out = append(out, prev...)
	}
	return out[:length]
}

// pbkdf2SHA256 is PBKDF2-HMAC-SHA256 (RFC 8018), kept local to avoid a dependency.
func pbkdf2SHA256(password, salt []byte, iterations, keyLen int) []byte {
	prf := hmac.New(sha256.New, password)
	hashLen := prf.Size()
	blocks := (keyLen + hashLen - 1) / hashLen
	var idx [4]byte
	dk := make([]byte, 0, blocks*hashLen)
	u := make([]byte, hashLen)
	for block := 1; block <= blocks; block++ {
		prf.Reset()
		prf.Write(salt)
		idx[0], idx[1], idx[2], idx[3] = byte(block>>24), byte(block>>16), byte(block>>8), byte(block)
		prf.Write(idx[:])
		dk = prf.Sum(dk)
		t := dk[len(dk)-hashLen:]
		copy(u, t)
		for n := 2; n <= iterations; n++ {
			prf.Reset()
			prf.Write(u)
			u = prf.Sum(u[:0])
			for x := range u {
				t[x] ^= u[x]
			}
		}
	}
	return dk[:keyLen]
}

// DeriveAuthPW computes the authPW the FxA login endpoint expects (hex string).
func DeriveAuthPW(email, password string) string {
	salt := []byte(protocolVersion + "quickStretch:" + email)
	quick := pbkdf2SHA256([]byte(password), salt, 1000, 32)
	return hex.EncodeToString(HKDF(quick, []byte(protocolVersion+"authPW"), 32, nil))
}

// DeriveHawkCredentials turns a session token into the HAWK id and key.
func DeriveHawkCredentials(sessionTokenHex string) (id string, key []byte, err error) {
	tok, err := hex.DecodeString(sessionTokenHex)
	if err != nil {
		return "", nil, err
	}
	expanded := HKDF(tok, []byte(protocolVersion+"sessionToken"), 64, nil)
	return hex.EncodeToString(expanded[:32]), expanded[32:], nil
}

// HawkHeader builds the Authorization header for one request.
func HawkHeader(method, rawURL, tokenID string, key, body []byte) (string, error) {
	nonce := make([]byte, 6)
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	return HawkHeaderAt(method, rawURL, tokenID, key, body, time.Now().Unix(),
		hex.EncodeToString(nonce))
}

// HawkHeaderAt is HawkHeader with an explicit timestamp and nonce (used by the tests).
func HawkHeaderAt(method, rawURL, tokenID string, key, body []byte, ts int64, nonce string) (string, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return "", err
	}
	path := u.EscapedPath()
	if u.RawQuery != "" {
		path += "?" + u.RawQuery
	}
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}
	payloadHash := ""
	if len(body) > 0 {
		h := sha256.New()
		h.Write([]byte("hawk.1.payload\napplication/json\n"))
		h.Write(body)
		h.Write([]byte("\n"))
		payloadHash = hex.EncodeToString(h.Sum(nil))
	}
	normalized := fmt.Sprintf("hawk.1.header\n%d\n%s\n%s\n%s\n%s\n%s\n%s\n\n",
		ts, nonce, strings.ToUpper(method), path, strings.ToLower(u.Hostname()), port, payloadHash)
	mac := hex.EncodeToString(hmacSHA256(key, []byte(normalized)))
	header := fmt.Sprintf(`Hawk id="%s", ts="%d", nonce="%s", mac="%s"`, tokenID, ts, nonce, mac)
	if payloadHash != "" {
		header += fmt.Sprintf(`, hash="%s"`, payloadHash)
	}
	return header, nil
}

// JWTExpiry reads the "exp" claim of a JWT without verifying it.
func JWTExpiry(token string) (int64, bool) {
	parts := strings.Split(token, ".")
	if len(parts) < 2 {
		return 0, false
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(parts[1], "="))
	if err != nil {
		return 0, false
	}
	var claims struct {
		Exp float64 `json:"exp"`
	}
	if json.Unmarshal(raw, &claims) != nil || claims.Exp == 0 {
		return 0, false
	}
	return int64(claims.Exp), true
}
