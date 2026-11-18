package server

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const latestReleaseAPI = "https://api.github.com/repos/omidrx0/RxFoxyVPN/releases/latest"

type githubRelease struct {
	TagName    string `json:"tag_name"`
	Name       string `json:"name"`
	Body       string `json:"body"`
	HTMLURL    string `json:"html_url"`
	Draft      bool   `json:"draft"`
	Prerelease bool   `json:"prerelease"`
}

type updateCheckResult struct {
	CurrentVersion  string `json:"currentVersion"`
	LatestVersion   string `json:"latestVersion"`
	UpdateAvailable bool   `json:"updateAvailable"`
	ReleaseNotes    string `json:"releaseNotes"`
	ReleaseURL      string `json:"releaseUrl"`
}

func checkLatestRelease(ctx context.Context, current string) (*updateCheckResult, error) {
	ctx, cancel := context.WithTimeout(ctx, 12*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, latestReleaseAPI, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "RXFoxyVPN-Updater")

	client := &http.Client{Timeout: 12 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("could not reach GitHub: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("GitHub API returned HTTP %d", resp.StatusCode)
	}

	var rel githubRelease
	if err := json.NewDecoder(resp.Body).Decode(&rel); err != nil {
		return nil, fmt.Errorf("invalid GitHub release response: %w", err)
	}
	if rel.Draft || rel.Prerelease || strings.TrimSpace(rel.TagName) == "" {
		return nil, fmt.Errorf("latest GitHub release is not a stable release")
	}

	latest := strings.TrimPrefix(strings.TrimSpace(rel.TagName), "v")
	current = strings.TrimPrefix(strings.TrimSpace(current), "v")
	result := &updateCheckResult{
		CurrentVersion: current, LatestVersion: latest,
		UpdateAvailable: versionIsNewer(latest, current),
		ReleaseNotes:    rel.Body, ReleaseURL: rel.HTMLURL,
	}
	return result, nil
}

func versionIsNewer(latest, current string) bool {
	a, okA := numericVersion(latest)
	b, okB := numericVersion(current)
	if !okA || !okB {
		// Never advertise an update when the running build has no reliable version.
		// Release builds must inject the real version with -X main.version=... .
		return false
	}
	for i := 0; i < len(a) || i < len(b); i++ {
		var av, bv int64
		if i < len(a) {
			av = a[i]
		}
		if i < len(b) {
			bv = b[i]
		}
		if av != bv {
			return av > bv
		}
	}
	return false
}

func numericVersion(v string) ([]int64, bool) {
	v = strings.TrimSpace(v)
	v = strings.TrimPrefix(strings.TrimPrefix(v, "v"), "V")
	parts := strings.Split(v, ".")
	if len(parts) < 2 || len(parts) > 4 {
		return nil, false
	}
	out := make([]int64, len(parts))
	for i, part := range parts {
		if part == "" {
			return nil, false
		}
		n, err := strconv.ParseInt(part, 10, 32)
		if err != nil || n < 0 {
			return nil, false
		}
		out[i] = n
	}
	return out, true
}
