package foxy

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	// DefaultManifestPrimaryURL is the authoritative updates.json URL from SharedRelayManager.kt / ManifestModels.kt.
	DefaultManifestPrimaryURL = "https://raw.githubusercontent.com/omidrx00/rx-update-manifests/main/vpn-client/updates.json"
	// DefaultManifestFallbackURL is the jsDelivr mirror URL from SharedRelayManager.kt / ManifestModels.kt.
	DefaultManifestFallbackURL = "https://cdn.jsdelivr.net/gh/omidrx00/rx-update-manifests@main/vpn-client/updates.json"

	maxManifestBytes              = 512 * 1024
	maxSharedRelayCandidates      = 100
	maxManifestAnnouncements      = 20
	sharedRelayBadFor             = 60 * time.Second
	sharedRelayActiveFor          = 10 * time.Minute
	manifestRefreshInterval       = 10 * time.Minute
	manifestFailedRefreshInterval = 60 * time.Second

	manifestDialTimeout = 6 * time.Second
	manifestReadTimeout = 8 * time.Second
	manifestCallTimeout = 10 * time.Second
)

// ManifestAnnouncement represents a user-facing announcement from updates.json.
type ManifestAnnouncement struct {
	ID       string `json:"id"`
	Enabled  bool   `json:"enabled"`
	Title    string `json:"title"`
	Message  string `json:"message"`
	Priority int    `json:"priority"`
}

// RemoteManifest holds the parsed relays and announcements sections of updates.json.
type RemoteManifest struct {
	Version       int                    `json:"version"`
	GeneratedAt   string                 `json:"generated_at,omitempty"`
	RelaysEnabled bool                   `json:"relays_enabled"`
	RelayURLs     []string               `json:"relay_urls"`
	Announcements []ManifestAnnouncement `json:"announcements"`
}

// ParseRemoteManifest parses updates.json matching SharedRelayManager.parseCandidates and ManifestParser.parse.
func ParseRemoteManifest(raw []byte) (RemoteManifest, error) {
	if len(raw) > maxManifestBytes {
		return RemoteManifest{}, fmt.Errorf("manifest exceeds %d bytes", maxManifestBytes)
	}
	var root map[string]any
	dec := json.NewDecoder(bytes.NewReader(raw))
	if err := dec.Decode(&root); err != nil {
		return RemoteManifest{}, err
	}

	out := RemoteManifest{
		Version:       1,
		RelaysEnabled: false,
		RelayURLs:     []string{},
		Announcements: []ManifestAnnouncement{},
	}
	if v, ok := root["version"].(float64); ok && int(v) > 0 {
		out.Version = int(v)
	}
	if g, ok := root["generated_at"].(string); ok {
		out.GeneratedAt = strings.TrimSpace(g)
	}

	// Parse relays section (matches SharedRelayManager.parseCandidates in relay.zip).
	if relaysObj, ok := root["relays"].(map[string]any); ok {
		if enabledVal, exists := relaysObj["enabled"]; exists {
			if b, ok := enabledVal.(bool); ok {
				out.RelaysEnabled = b
			}
		}
		if out.RelaysEnabled {
			if arr, ok := relaysObj["urls"].([]any); ok {
				seen := make(map[string]struct{}, len(arr))
				for _, item := range arr {
					s, ok := item.(string)
					if !ok {
						continue
					}
					norm, valid := NormalizeSharedRelayCandidate(s)
					if !valid {
						continue
					}
					if _, dup := seen[norm]; dup {
						continue
					}
					seen[norm] = struct{}{}
					out.RelayURLs = append(out.RelayURLs, norm)
					if len(out.RelayURLs) >= maxSharedRelayCandidates {
						break
					}
				}
			}
		}
	}

	// Parse announcements section (matches ManifestParser.parseAnnouncements + AnnouncementManager).
	if annArr, ok := root["announcements"].([]any); ok {
		seenIDs := make(map[string]struct{}, len(annArr))
		for _, item := range annArr {
			m, ok := item.(map[string]any)
			if !ok {
				continue
			}
			id, _ := m["id"].(string)
			title, _ := m["title"].(string)
			message, _ := m["message"].(string)
			id = strings.TrimSpace(id)
			title = strings.TrimSpace(title)
			message = strings.TrimSpace(message)
			if id == "" || len(id) > 128 || title == "" || message == "" {
				continue
			}
			enabled := true
			if rawEnabled, hasEnabled := m["enabled"]; hasEnabled && rawEnabled != nil {
				b, ok := rawEnabled.(bool)
				if !ok || !b {
					continue
				}
				enabled = b
			}
			if !enabled {
				continue
			}
			if _, dup := seenIDs[id]; dup {
				continue
			}
			seenIDs[id] = struct{}{}
			prio := 0
			if p, ok := m["priority"].(float64); ok {
				prio = int(p)
			}
			out.Announcements = append(out.Announcements, ManifestAnnouncement{
				ID:       id,
				Enabled:  true,
				Title:    title,
				Message:  message,
				Priority: prio,
			})
			if len(out.Announcements) >= maxManifestAnnouncements {
				break
			}
		}
	}

	return out, nil
}

// SharedRelayManager manages the remote updates.json manifest, shared relay candidate stickiness,
// 60-second failure cooldowns, and announcement retrieval.
type SharedRelayManager struct {
	client       *http.Client
	configSource RelayConfigSource
	now          func() time.Time

	fetchMu sync.Mutex // singleflight manifest fetch

	mu            sync.Mutex
	hasManifest   bool
	manifest      RemoteManifest
	candidates    []string
	nextRefreshAt time.Time
	activeURL     string
	activeUntil   time.Time
	badUntil      map[string]time.Time
}

// NewSharedRelayManager creates a SharedRelayManager with an isolated direct HTTP client (never relayed).
func NewSharedRelayManager(opts TLSOptions, cfgSource RelayConfigSource) (*SharedRelayManager, error) {
	ts, err := newTLSSettings(opts)
	if err != nil {
		return nil, err
	}
	client := newIsolatedDirectHTTPClient(ts, manifestDialTimeout, manifestReadTimeout)
	return &SharedRelayManager{
		client:       client,
		configSource: cfgSource,
		now:          time.Now,
		badUntil:     make(map[string]time.Time),
	}, nil
}

func (m *SharedRelayManager) manifestURLs() []string {
	primary := DefaultManifestPrimaryURL
	fallback := DefaultManifestFallbackURL
	if m.configSource != nil {
		cfg := m.configSource()
		if s := strings.TrimSpace(cfg.ManifestPrimaryOverride); s != "" {
			primary = s
		}
		if s := strings.TrimSpace(cfg.ManifestFallbackOverride); s != "" {
			fallback = s
		}
	}
	if strings.EqualFold(primary, fallback) {
		return []string{primary}
	}
	return []string{primary, fallback}
}

// Refresh fetches updates.json from Primary then Fallback mirror if the TTL expired or force is true.
// On network or parse failure, the last valid candidate list and manifest are preserved.
func (m *SharedRelayManager) Refresh(ctx context.Context, force bool) (RemoteManifest, bool) {
	now := m.now()
	m.mu.Lock()
	if !force && m.hasManifest && now.Before(m.nextRefreshAt) {
		cur := m.manifest
		m.mu.Unlock()
		return cur, true
	}
	m.mu.Unlock()

	m.fetchMu.Lock()
	defer m.fetchMu.Unlock()

	now = m.now()
	m.mu.Lock()
	if !force && m.hasManifest && now.Before(m.nextRefreshAt) {
		cur := m.manifest
		m.mu.Unlock()
		return cur, true
	}
	m.mu.Unlock()

	for _, u := range m.manifestURLs() {
		parsed, err := m.fetchOne(ctx, u)
		if err != nil {
			slog.Debug("manifest source unavailable", "url", u, "err", err)
			continue
		}
		fetchedAt := m.now()
		m.mu.Lock()
		m.manifest = parsed
		m.hasManifest = true
		if !parsed.RelaysEnabled {
			m.candidates = nil
			m.activeURL = ""
			m.activeUntil = time.Time{}
		} else {
			m.candidates = append([]string(nil), parsed.RelayURLs...)
			if m.activeURL != "" && !sliceContains(m.candidates, m.activeURL) {
				m.activeURL = ""
				m.activeUntil = time.Time{}
			}
		}
		m.nextRefreshAt = fetchedAt.Add(manifestRefreshInterval)
		m.mu.Unlock()
		return parsed, true
	}

	m.mu.Lock()
	m.nextRefreshAt = m.now().Add(manifestFailedRefreshInterval)
	cur, ok := m.manifest, m.hasManifest
	m.mu.Unlock()
	return cur, ok
}

func (m *SharedRelayManager) fetchOne(ctx context.Context, rawURL string) (RemoteManifest, error) {
	cctx, cancel := context.WithTimeout(ctx, manifestCallTimeout)
	defer cancel()

	req, err := http.NewRequestWithContext(cctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return RemoteManifest{}, err
	}
	req.Header.Set("User-Agent", UserAgent)
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Accept-Encoding", "identity")

	resp, err := m.client.Do(req)
	if err != nil {
		return RemoteManifest{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return RemoteManifest{}, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	limited := io.LimitReader(resp.Body, maxManifestBytes+1)
	body, err := io.ReadAll(limited)
	if err != nil {
		return RemoteManifest{}, err
	}
	if len(body) > maxManifestBytes {
		return RemoteManifest{}, fmt.Errorf("manifest exceeds %d bytes", maxManifestBytes)
	}
	return ParseRemoteManifest(body)
}

// CandidatesForAttempt returns ordered shared relay URLs with sharedSecret attached:
// 1. Active sticky winner (if still healthy and within 10m TTL)
// 2. Remaining healthy candidates in manifest order
// 3. Cooling-down candidates (so a transient outage across all relays does not prevent retrying)
func (m *SharedRelayManager) CandidatesForAttempt(ctx context.Context, sharedSecret string) []string {
	m.Refresh(ctx, false)

	now := m.now()
	m.mu.Lock()
	list := append([]string(nil), m.candidates...)
	if len(list) == 0 {
		m.mu.Unlock()
		return nil
	}
	for u, until := range m.badUntil {
		if !until.After(now) {
			delete(m.badUntil, u)
		}
	}
	isHealthy := func(u string) bool {
		until, bad := m.badUntil[u]
		return !bad || !until.After(now)
	}

	sticky := ""
	if m.activeURL != "" && now.Before(m.activeUntil) && sliceContains(list, m.activeURL) && isHealthy(m.activeURL) {
		sticky = m.activeURL
	}

	ordered := make([]string, 0, len(list))
	seen := make(map[string]struct{}, len(list))
	add := func(u string) {
		if _, ok := seen[u]; ok {
			return
		}
		seen[u] = struct{}{}
		ordered = append(ordered, AttachRelaySecret(u, sharedSecret))
	}
	if sticky != "" {
		add(sticky)
	}
	for _, u := range list {
		if isHealthy(u) {
			add(u)
		}
	}
	for _, u := range list {
		if !isHealthy(u) {
			add(u)
		}
	}
	m.mu.Unlock()
	return ordered
}

// MarkGood records url as the sticky active shared relay for 10 minutes.
func (m *SharedRelayManager) MarkGood(rawURL string) {
	base := WithoutRelaySecret(rawURL)
	now := m.now()
	m.mu.Lock()
	m.activeURL = base
	m.activeUntil = now.Add(sharedRelayActiveFor)
	delete(m.badUntil, base)
	m.mu.Unlock()
}

// MarkBad cools down url for 60 seconds and clears it if it was the sticky relay.
func (m *SharedRelayManager) MarkBad(rawURL string) {
	base := WithoutRelaySecret(rawURL)
	now := m.now()
	m.mu.Lock()
	m.badUntil[base] = now.Add(sharedRelayBadFor)
	if m.activeURL == base {
		m.activeURL = ""
		m.activeUntil = time.Time{}
	}
	m.mu.Unlock()
}

// PendingAnnouncements returns enabled announcements not present in acknowledgedSet.
func (m *SharedRelayManager) PendingAnnouncements(ctx context.Context, acknowledgedSet map[string]bool, force bool) []ManifestAnnouncement {
	manifest, ok := m.Refresh(ctx, force)
	if !ok || len(manifest.Announcements) == 0 {
		return []ManifestAnnouncement{}
	}
	out := make([]ManifestAnnouncement, 0, len(manifest.Announcements))
	for _, a := range manifest.Announcements {
		if !a.Enabled || acknowledgedSet[a.ID] {
			continue
		}
		out = append(out, a)
	}
	return out
}

// CandidateCount returns the number of currently loaded shared relay candidates.
func (m *SharedRelayManager) CandidateCount() int {
	if m == nil {
		return 0
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.candidates)
}

func sliceContains(items []string, target string) bool {
	for _, item := range items {
		if item == target {
			return true
		}
	}
	return false
}
