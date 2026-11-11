package config

import (
	"encoding/base64"
	"encoding/json"
	"net/url"
	"os"
	"strings"
	"sync"
)

// DefaultSharedRelaySecret may be overridden at build time via:
// -ldflags "-X rxvpn/internal/config.DefaultSharedRelaySecret=..."
var DefaultSharedRelaySecret = ""

// RelayConfig holds the complete control-plane relay configuration (matching Android RelayConfig).
// Secrets (PersonalURL and SharedSecret) are never serialized directly to the public panel API.
type RelayConfig struct {
	SharedEnabled            bool
	PersonalEnabled          bool
	PersonalURL              string
	PersonalOnly             bool
	SharedSecret             string
	ManifestPrimaryOverride  string
	ManifestFallbackOverride string
}

// RelayPublicView is the safe representation sent to the WebView2 / browser panel.
// The PersonalURL secret query parameter and SharedSecret are never exposed.
type RelayPublicView struct {
	SharedEnabled   bool   `json:"shared_enabled"`
	PersonalEnabled bool   `json:"personal_enabled"`
	PersonalOnly    bool   `json:"personal_only"`
	PersonalURLSet  bool   `json:"personal_url_set"`
	PersonalHost    string `json:"personal_host"`
}

type relayDiskRecord struct {
	SharedEnabled            *bool  `json:"shared_enabled,omitempty"`
	PersonalEnabled          bool   `json:"personal_enabled"`
	PersonalOnly             bool   `json:"personal_only"`
	PersonalURLSealed        string `json:"personal_url_sealed,omitempty"`
	PersonalURLPlain         string `json:"personal_url_plain,omitempty"`
	SharedSecretSealed       string `json:"shared_secret_sealed,omitempty"`
	SharedSecretPlain        string `json:"shared_secret_plain,omitempty"`
	ManifestPrimaryOverride  string `json:"manifest_primary_override,omitempty"`
	ManifestFallbackOverride string `json:"manifest_fallback_override,omitempty"`
}

// RelayStore persists relay toggles and DPAPI-protected relay secrets on disk.
// Disabling PersonalEnabled preserves the saved PersonalURL (matching Android SettingsStore).
type RelayStore struct {
	path   string
	cipher Cipher
	mu     sync.RWMutex
}

// NewRelayStore creates a store backed by path and optional DPAPI cipher.
func NewRelayStore(path string, cipher Cipher) *RelayStore {
	return &RelayStore{path: path, cipher: cipher}
}

func (s *RelayStore) sealString(plain string) (sealedB64, fallbackPlain string) {
	if plain == "" {
		return "", ""
	}
	if s.cipher != nil {
		if b, err := s.cipher.Protect([]byte(plain)); err == nil && len(b) > 0 {
			return base64.StdEncoding.EncodeToString(b), ""
		}
	}
	return "", plain
}

func (s *RelayStore) unsealString(sealedB64, fallbackPlain string) string {
	if sealedB64 != "" && s.cipher != nil {
		if raw, err := base64.StdEncoding.DecodeString(sealedB64); err == nil {
			if plain, err := s.cipher.Unprotect(raw); err == nil {
				return string(plain)
			}
		}
	}
	return fallbackPlain
}

func (s *RelayStore) loadUnlocked() RelayConfig {
	cfg := RelayConfig{
		SharedEnabled: true, // Default in Android SettingsStore: true
		SharedSecret:  DefaultSharedRelaySecret,
	}
	raw, err := os.ReadFile(s.path)
	if err != nil {
		return cfg
	}
	var rec relayDiskRecord
	if json.Unmarshal(raw, &rec) != nil {
		return cfg
	}
	if rec.SharedEnabled != nil {
		cfg.SharedEnabled = *rec.SharedEnabled
	}
	cfg.PersonalEnabled = rec.PersonalEnabled
	cfg.PersonalOnly = rec.PersonalOnly
	cfg.PersonalURL = strings.TrimSpace(s.unsealString(rec.PersonalURLSealed, rec.PersonalURLPlain))
	if secret := strings.TrimSpace(s.unsealString(rec.SharedSecretSealed, rec.SharedSecretPlain)); secret != "" {
		cfg.SharedSecret = secret
	}
	cfg.ManifestPrimaryOverride = strings.TrimSpace(rec.ManifestPrimaryOverride)
	cfg.ManifestFallbackOverride = strings.TrimSpace(rec.ManifestFallbackOverride)
	return cfg
}

func (s *RelayStore) saveUnlocked(cfg RelayConfig) error {
	shared := cfg.SharedEnabled
	urlSealed, urlPlain := s.sealString(strings.TrimSpace(cfg.PersonalURL))
	secToStore := strings.TrimSpace(cfg.SharedSecret)
	if secToStore == DefaultSharedRelaySecret {
		secToStore = ""
	}
	secSealed, secPlain := s.sealString(secToStore)
	rec := relayDiskRecord{
		SharedEnabled:            &shared,
		PersonalEnabled:          cfg.PersonalEnabled,
		PersonalOnly:             cfg.PersonalOnly,
		PersonalURLSealed:        urlSealed,
		PersonalURLPlain:         urlPlain,
		SharedSecretSealed:       secSealed,
		SharedSecretPlain:        secPlain,
		ManifestPrimaryOverride:  strings.TrimSpace(cfg.ManifestPrimaryOverride),
		ManifestFallbackOverride: strings.TrimSpace(cfg.ManifestFallbackOverride),
	}
	data, err := MarshalJSON(rec, true)
	if err != nil {
		return err
	}
	return WriteFileAtomic(s.path, data, 0o600)
}

// Load returns the current relay configuration.
func (s *RelayStore) Load() RelayConfig {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.loadUnlocked()
}

// Update atomically mutates and persists the relay configuration.
func (s *RelayStore) Update(fn func(*RelayConfig) error) (RelayConfig, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	cfg := s.loadUnlocked()
	if err := fn(&cfg); err != nil {
		return cfg, err
	}
	if err := s.saveUnlocked(cfg); err != nil {
		return cfg, err
	}
	return cfg, nil
}

// PublicView returns a secret-redacted view for UI rendering.
func PublicRelayView(cfg RelayConfig) RelayPublicView {
	host := ""
	if cfg.PersonalURL != "" {
		if u, err := url.Parse(cfg.PersonalURL); err == nil && u.Host != "" {
			host = strings.ToLower(u.Hostname())
		}
	}
	return RelayPublicView{
		SharedEnabled:   cfg.SharedEnabled,
		PersonalEnabled: cfg.PersonalEnabled,
		PersonalOnly:    cfg.PersonalOnly,
		PersonalURLSet:  cfg.PersonalURL != "" && host != "",
		PersonalHost:    host,
	}
}
