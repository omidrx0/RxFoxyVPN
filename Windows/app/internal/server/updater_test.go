package server

import "testing"

func TestVersionIsNewer(t *testing.T) {
	tests := []struct {
		latest, current string
		want            bool
	}{
		{"1.0.1", "1.0.0", true},
		{"1.10.0", "1.9.0", true},
		{"1.0.0", "1.0.0", false},
		{"1.0.0", "1.0.1", false},
		{"v1.2.0", "1.1.9", true},
		{"1.0.0", "dev", false},
		{"1.0.0", "", false},
	}
	for _, tt := range tests {
		if got := versionIsNewer(tt.latest, tt.current); got != tt.want {
			t.Errorf("versionIsNewer(%q, %q) = %v, want %v", tt.latest, tt.current, got, tt.want)
		}
	}
}
