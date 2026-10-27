package server

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const latestReleaseAPI = "https://api.github.com/repos/omidrx0/RxFoxyVPN/releases/latest"

type downloadProgressState struct {
	Active     bool  `json:"active"`
	Downloaded int64 `json:"downloaded"`
	Total      int64 `json:"total"`
	Percent    int   `json:"percent"`
}

var updateProgressMu sync.RWMutex
var updateProgress downloadProgressState

func setUpdateProgress(p downloadProgressState) {
	updateProgressMu.Lock()
	updateProgress = p
	updateProgressMu.Unlock()
}
func getUpdateProgress() downloadProgressState {
	updateProgressMu.RLock()
	defer updateProgressMu.RUnlock()
	return updateProgress
}

type githubAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
	Size               int64  `json:"size"`
}

type githubRelease struct {
	TagName    string        `json:"tag_name"`
	Name       string        `json:"name"`
	Body       string        `json:"body"`
	HTMLURL    string        `json:"html_url"`
	Draft      bool          `json:"draft"`
	Prerelease bool          `json:"prerelease"`
	Assets     []githubAsset `json:"assets"`
}

type updateCheckResult struct {
	CurrentVersion  string `json:"currentVersion"`
	LatestVersion   string `json:"latestVersion"`
	UpdateAvailable bool   `json:"updateAvailable"`
	ReleaseNotes    string `json:"releaseNotes"`
	ReleaseURL      string `json:"releaseUrl"`
	InstallerURL    string `json:"installerUrl,omitempty"`
	PortableURL     string `json:"portableUrl,omitempty"`
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
	for _, asset := range rel.Assets {
		if !strings.HasPrefix(asset.BrowserDownloadURL, "https://github.com/") {
			continue
		}
		if strings.EqualFold(asset.Name, "RXFoxyVPN-Setup.exe") {
			result.InstallerURL = asset.BrowserDownloadURL
		}
		if strings.EqualFold(asset.Name, "RXFOXYVPN-Windows.zip") {
			result.PortableURL = asset.BrowserDownloadURL
		}
	}
	// Installer always has priority. If it exists, hide/ignore the portable option
	// even when the release also contains a portable ZIP.
	if result.InstallerURL != "" {
		result.PortableURL = ""
	} else if result.PortableURL == "" {
		// If no installer and no release ZIP exist, offer the known public v1.0.0 package.
		result.PortableURL = "https://github.com/omidrx0/RxFoxyVPN/releases/download/v1.0.0/RXFOXYVPN-Windows.zip"
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

// downloadAndLaunchInstaller downloads the exact installer asset for the latest stable
// release to a temporary file, then starts it. The caller must only invoke this after
// explicit user confirmation in the UI.
func downloadAndLaunchInstaller(ctx context.Context, current string) (map[string]string, error) {
	if runtime.GOOS != "windows" {
		return nil, fmt.Errorf("Windows installer updates are only supported on Windows")
	}
	release, err := checkLatestRelease(ctx, current)
	if err != nil {
		return nil, err
	}
	if !release.UpdateAvailable {
		return nil, fmt.Errorf("RXFoxyVPN is already up to date")
	}
	// Prefer the installer whenever available; otherwise download the portable ZIP.
	downloadURL := release.InstallerURL
	isPortable := false
	if downloadURL == "" {
		downloadURL = release.PortableURL
		isPortable = true
	}
	if downloadURL == "" {
		return nil, fmt.Errorf("this release does not contain a Windows installer or portable ZIP")
	}

	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, downloadURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "RXFoxyVPN-Updater")
	resp, err := (&http.Client{Timeout: 5 * time.Minute}).Do(req)
	if err != nil {
		return nil, fmt.Errorf("update download failed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("update download returned HTTP %d", resp.StatusCode)
	}
	const maxInstallerSize = int64(500 << 20)
	if resp.ContentLength > maxInstallerSize {
		return nil, fmt.Errorf("update file is unexpectedly large")
	}

	tempDir := filepath.Join(os.TempDir(), "RXFoxyVPN-Update")
	if err := os.MkdirAll(tempDir, 0700); err != nil {
		return nil, err
	}
	pattern := "RXFoxyVPN-Setup-*.exe"
	if isPortable {
		pattern = "RXFOXYVPN-Windows-*.zip"
	}
	file, err := os.CreateTemp(tempDir, pattern)
	if err != nil {
		return nil, err
	}
	filePath := file.Name()
	ok := false
	defer func() {
		_ = file.Close()
		if !ok {
			_ = os.Remove(filePath)
		}
	}()
	setUpdateProgress(downloadProgressState{Active: true, Total: resp.ContentLength})
	var written int64
	buf := make([]byte, 64*1024)
	for {
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if written+int64(n) > maxInstallerSize {
				setUpdateProgress(downloadProgressState{})
				return nil, fmt.Errorf("downloaded installer is unexpectedly large")
			}
			if _, err := file.Write(buf[:n]); err != nil {
				setUpdateProgress(downloadProgressState{})
				return nil, err
			}
			written += int64(n)
			p := downloadProgressState{Active: true, Downloaded: written, Total: resp.ContentLength}
			if p.Total > 0 {
				p.Percent = int((written * 100) / p.Total)
				if p.Percent > 100 {
					p.Percent = 100
				}
			}
			setUpdateProgress(p)
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			setUpdateProgress(downloadProgressState{})
			return nil, readErr
		}
	}
	if written == 0 || written > maxInstallerSize {
		return nil, fmt.Errorf("downloaded installer size is invalid")
	}
	setUpdateProgress(downloadProgressState{Active: true, Downloaded: written, Total: written, Percent: 100})
	if err := file.Sync(); err != nil {
		return nil, err
	}
	if err := file.Close(); err != nil {
		return nil, err
	}

	var cmd *exec.Cmd
	if isPortable {
		// Open the downloaded ZIP in Windows Explorer; never open a browser tab.
		cmd = exec.Command("explorer.exe", filePath)
	} else {
		cmd = exec.Command(filePath)
	}
	if err := cmd.Start(); err != nil {
		setUpdateProgress(downloadProgressState{})
		return nil, fmt.Errorf("could not open downloaded update: %w", err)
	}
	setUpdateProgress(downloadProgressState{})
	ok = true
	status, message := "started", "Installer started. Follow the installer prompts to complete the update."
	if isPortable {
		status, message = "opened", "Portable ZIP opened in Windows Explorer. Extract it to use the portable version."
	}
	return map[string]string{
		"status":  status,
		"version": release.LatestVersion,
		"message": message,
		"type":    map[bool]string{true: "portable", false: "installer"}[isPortable],
	}, nil
}
