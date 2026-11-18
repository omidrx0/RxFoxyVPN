package foxy

import (
	"testing"
)

func TestAllowedManifestHost(t *testing.T) {
	cases := []struct {
		url  string
		want bool
	}{
		{DefaultManifestPrimaryURL, true},
		{DefaultManifestFallbackURL, true},
		{"https://github.com/x/y/raw/main/updates.json", true},
		{"https://raw.githubusercontent.com/a/b/main/updates.json", true},
		{"https://cdn.jsdelivr.net/gh/a/b@main/updates.json", true},
		{"https://jsdelivr.net/gh/a/b@main/updates.json", true},
		{"https://evil.com/updates.json", false},
		{"https://github.com.evil.com/updates.json", false},
		{"http://raw.githubusercontent.com/a/b/main/updates.json", false},
		{"https://raw.githubusercontent.com:8443/a/b/main/updates.json", false},
		{"https://user:pass@raw.githubusercontent.com/a/b/main/updates.json", false},
		{"https://raw.githubusercontent.com/a/b/main/updates.json#frag", false},
		{"", false},
	}
	for _, tc := range cases {
		if got := allowedManifestHost(tc.url); got != tc.want {
			t.Errorf("allowedManifestHost(%q) = %v, want %v", tc.url, got, tc.want)
		}
	}
}

func TestManifestURLsRefusesUntrustedOverrides(t *testing.T) {
	m := &SharedRelayManager{}
	tests := []struct {
		name     string
		cfg      RelayConfig
		wantPri  string
		wantFall string
	}{
		{"no override", RelayConfig{}, DefaultManifestPrimaryURL, DefaultManifestFallbackURL},
		{"github override accepted", RelayConfig{ManifestPrimaryOverride: "https://github.com/x/y/raw/main/u.json"}, "https://github.com/x/y/raw/main/u.json", DefaultManifestFallbackURL},
		{"evil primary refused", RelayConfig{ManifestPrimaryOverride: "https://evil.com/u.json"}, DefaultManifestPrimaryURL, DefaultManifestFallbackURL},
		{"evil fallback refused", RelayConfig{ManifestFallbackOverride: "https://attacker.example.com/u.json"}, DefaultManifestPrimaryURL, DefaultManifestFallbackURL},
		{"http override refused", RelayConfig{ManifestPrimaryOverride: "http://raw.githubusercontent.com/x/y"}, DefaultManifestPrimaryURL, DefaultManifestFallbackURL},
		{"equal override list collapses", RelayConfig{ManifestPrimaryOverride: "https://raw.githubusercontent.com/a/b/main/u.json", ManifestFallbackOverride: "https://raw.githubusercontent.com/a/b/main/u.json"}, "https://raw.githubusercontent.com/a/b/main/u.json", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			m.configSource = func() RelayConfig { return tt.cfg }
			got := m.manifestURLs()
			if got[0] != tt.wantPri {
				t.Errorf("primary = %q, want %q", got[0], tt.wantPri)
			}
			if tt.wantFall == "" {
				if len(got) != 1 {
					t.Errorf("expected single URL, got %v", got)
				}
				return
			}
			if len(got) != 2 || got[1] != tt.wantFall {
				t.Errorf("urls = %v, want [.. %q]", got, tt.wantFall)
			}
		})
	}
}
