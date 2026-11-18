// Package accounts — loose parsing helpers shared by the backup codec and the store.
package accounts

import (
	"bytes"
	"encoding/json"
	"strconv"
	"strings"
	"time"
)

// ParseTimeLoose reads RFC3339(Nano) or a numeric epoch (seconds or millis) from any of the
// fields that may carry timestamps (added_at, retry_at, reset_at, checked_at, obtained_at).
func ParseTimeLoose(raw string) time.Time {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return time.Time{}
	}
	if at, err := time.Parse(time.RFC3339Nano, raw); err == nil {
		return at
	}
	// numeric epoch (seconds or millis) — the WHOLE string must parse
	if n, err := strconv.ParseFloat(raw, 64); err == nil && n > 0 {
		if n > 1e12 {
			n = float64(int64(n) / 1000)
		}
		return time.Unix(int64(n), 0).UTC()
	}
	return time.Time{}
}

// JSONNumberLoose handles a JSON value that may be a number or a quoted number.
func JSONNumberLoose(raw json.RawMessage) (int64, bool) {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 {
		return 0, false
	}
	var n int64
	if err := json.Unmarshal(raw, &n); err == nil {
		return n, true
	}
	var f float64
	if err := json.Unmarshal(raw, &f); err == nil {
		return int64(f), true
	}
	var s string
	if err := json.Unmarshal(raw, &s); err == nil {
		if n2, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64); err == nil {
			return n2, true
		}
	}
	return 0, false
}

// ExpiryOfTokensJSON resolves the token expiry for a credentials blob following the
// Arman/Android rule: expires_at if present, else obtained_at + expires_in, else a bare
// "expiresAt" number.
func ExpiryOfTokensJSON(data []byte) int64 {
	var t struct {
		ExpiresAt  int64  `json:"expires_at"`
		ObtainedAt string `json:"obtained_at"`
		ExpiresIn  int64  `json:"expires_in"`
	}
	_ = json.Unmarshal(data, &t)
	if t.ExpiresAt > 0 {
		return t.ExpiresAt
	}
	if t.ObtainedAt != "" && t.ExpiresIn > 0 {
		if at := ParseTimeLoose(t.ObtainedAt); !at.IsZero() {
			return at.Unix() + t.ExpiresIn
		}
	}
	var probe struct {
		ExpiresAt json.RawMessage `json:"expiresAt"`
	}
	if err := json.Unmarshal(data, &probe); err == nil && len(probe.ExpiresAt) > 0 {
		if epoch, ok := JSONNumberLoose(probe.ExpiresAt); ok && epoch > 0 {
			return epoch
		}
	}
	return 0
}
