// Package backup implements the Arman-compatible account backup (W-10…W-12).
//
// A backup is an in-memory ZIP with exactly this layout:
//
//	backup.json                     {"app":"RxFoxyVPN","format":1,"version":"…","created":"<RFC3339Nano>","system":"windows","accounts":N}
//	accounts/secret.key             64-char lower hex + "\n" (the EXPORTING device's AES-256-GCM key)
//	accounts/<id>/account.json      the non-secret record
//	accounts/<id>/credentials.json  tokens + the password encrypted under the exporting device's key
//
// Security properties:
//   - entries are matched against a strict allow-list regex; anything else is ignored;
//   - nothing is ever written to disk from the ZIP (pure in-memory parse) — ZIP-Slip is impossible;
//   - caps: 16 MiB file, 1 MiB per entry, 32 MiB uncompressed total, 2000 entries;
//   - import is all-or-nothing: the accounts directory is snapshotted first and fully
//     restored on any failure, so a failed import destroys nothing;
//   - passwords are re-encrypted under THIS device's key; the backup's key is used only
//     to decrypt the export's passwords and is NEVER adopted or persisted.
package backup

import (
	"archive/zip"
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"rxvpn/internal/accounts"
)

const (
	// MaxBackupSize is the hard cap on a backup file (W-10).
	MaxBackupSize = 16 << 20
	maxEntrySize  = 1 << 20
	maxTotalRaw   = 32 << 20
	maxEntries    = 2000
	backupFormat  = 1
	backupInfoFile = "backup.json"
	systemName    = "windows"
)

// Sentinel errors — mapped to codes by appError.Classify.
var (
	ErrBackupEmpty   = errors.New("backup_empty")
	ErrBackupInvalid = errors.New("backup_invalid")
)

var entryAllowList = regexp.MustCompile(`(?:^|/)(?:(backup\.json)|accounts/(secret\.key|[A-Za-z0-9_-]{1,64}/(?:account|credentials)\.json))$`)

var validHexKey = regexp.MustCompile(`^[0-9a-fA-F]{64}$`)

// BackupFileName returns the suggested file name for an export (W-10).
func BackupFileName(now time.Time) string {
	return "rxfoxyvpn-backup-" + now.Format("2006-01-02_15-04-05") + ".zip"
}

// Preview is the inspect output (W-11): what the file holds, without changing anything.
type Preview struct {
	App                   string
	Format                int
	CreatedAtEpochSeconds int64
	Accounts              []PreviewAccount
}

// PreviewAccount is one account inside the backup.
type PreviewAccount struct {
	Email       string
	HasPassword bool
	Exists      bool
	Usable      bool
}

// UsableCount is how many accounts the import will actually apply.
func (p *Preview) UsableCount() int {
	n := 0
	for _, a := range p.Accounts {
		if a.Usable {
			n++
		}
	}
	return n
}

// ImportResult reports what the import did.
type ImportResult struct {
	Added            int
	Updated          int
	SkippedPasswords int
	SkippedAccounts  int
	CreatedAtEpoch   int64
}

// ---------------------------------------------------------------------------
// Export
// ---------------------------------------------------------------------------

// Export builds the backup zip entirely in memory and returns (bytes, fileName, error).
// version is recorded as backup.json's "version" (pass the app build version).
func Export(store *accounts.Store, version string, now time.Time) ([]byte, string, error) {
	list, err := store.List()
	if err != nil {
		return nil, "", err
	}
	if now.IsZero() {
		now = time.Now()
	}
	if strings.TrimSpace(version) == "" {
		version = "dev"
	}
	var out bytes.Buffer
	zw := zip.NewWriter(&out)

	writeEntry := func(name string, data []byte) error {
		w, err := zw.Create(name)
		if err != nil {
			return err
		}
		_, err = w.Write(data)
		return err
	}

	info := map[string]any{
		"app":      "RxFoxyVPN",
		"format":   backupFormat,
		"version":  version,
		"created":  now.UTC().Format(time.RFC3339Nano),
		"system":   systemName,
		"accounts": len(list),
	}
	infoRaw, err := json.MarshalIndent(info, "", "  ")
	if err != nil {
		return nil, "", err
	}
	if err := writeEntry(backupInfoFile, append(infoRaw, '\n')); err != nil {
		return nil, "", err
	}
	if keyRaw, err := os.ReadFile(filepath.Join(store.Dir(), "secret.key")); err == nil {
		if data := bytes.TrimSpace(keyRaw); len(data) > 0 {
			if err := writeEntry("accounts/secret.key", append(data, '\n')); err != nil {
				return nil, "", err
			}
		}
	} else if !os.IsNotExist(err) {
		return nil, "", err
	}
	for i := range list {
		acc := &list[i]
		id := sanitizeID(acc.ID)
		if id == "" {
			return nil, "", fmt.Errorf("account %s cannot be exported (bad id)", acc.Email)
		}
		creds, err := store.Credentials(acc.ID)
		if err != nil {
			return nil, "", fmt.Errorf("reading credentials of %s: %w", acc.Email, err)
		}
		accountRaw, err := exportAccountJSON(acc, now)
		if err != nil {
			return nil, "", err
		}
		// the stored password blob is already encrypted under THIS device's key,
		// which is exactly the key the export carries in secret.key — pass it through
		passwordBlob, err := store.RawPasswordBlob(acc.ID)
		if err != nil {
			return nil, "", err
		}
		credsRaw, err := exportCredentialsJSON(creds, passwordBlob, acc.Email)
		if err != nil {
			return nil, "", err
		}
		if err := writeEntry("accounts/"+id+"/account.json", accountRaw); err != nil {
			return nil, "", err
		}
		if err := writeEntry("accounts/"+id+"/credentials.json", credsRaw); err != nil {
			return nil, "", err
		}
	}
	if err := zw.Close(); err != nil {
		return nil, "", err
	}
	if out.Len() > MaxBackupSize {
		return nil, "", fmt.Errorf("%w: export exceeded %d bytes", ErrBackupInvalid, MaxBackupSize)
	}
	return out.Bytes(), BackupFileName(now), nil
}

func exportAccountJSON(acc *accounts.Account, now time.Time) ([]byte, error) {
	added := acc.AddedAt
	if added.IsZero() {
		added = now
	}
	w := map[string]any{
		"id":       sanitizeID(acc.ID),
		"email":    acc.Email,
		"added_at": added.UTC().Format(time.RFC3339Nano),
		"status":   string(acc.Status),
	}
	if w["status"] == "" {
		w["status"] = "ready"
	}
	if acc.ReasonCode != "" {
		w["reason_code"] = acc.ReasonCode
	}
	if acc.ReasonDetail != "" {
		w["reason_detail"] = acc.ReasonDetail
	}
	if !acc.RetryAt.IsZero() {
		w["retry_at"] = acc.RetryAt.UTC().Format(time.RFC3339Nano)
	}
	if u := acc.Usage; u != nil && u.Known() {
		usage := map[string]any{}
		switch {
		case u.Unlimited:
			usage["unlimited"] = true
		default:
			usage["limit_bytes"] = u.LimitBytes
			usage["remaining_bytes"] = u.RemainingBytes
			if u.ResetAt.After(now) {
				usage["reset_at"] = u.ResetAt.UTC().Format(time.RFC3339Nano)
			}
			if !u.CheckedAt.IsZero() {
				usage["checked_at"] = u.CheckedAt.UTC().Format(time.RFC3339Nano)
			}
		}
		w["usage"] = usage
	}
	if acc.DeviceID != "" {
		w["device_id"] = acc.DeviceID
	}
	if acc.DeviceToken != "" {
		w["device_token"] = acc.DeviceToken
	}
	data, err := json.MarshalIndent(w, "", "  ")
	return append(data, '\n'), err
}

func exportCredentialsJSON(creds *accounts.SavedTokens, passwordBlob, email string) ([]byte, error) {
	obtained := strings.TrimSpace(creds.ObtainedAt)
	if obtained == "" {
		obtained = time.Now().UTC().Format(time.RFC3339Nano)
	}
	w := map[string]any{
		"access_token":  creds.AccessToken,
		"refresh_token": creds.RefreshToken,
		"scope":         creds.Scope,
		"obtained_at":   obtained,
		"expires_in":    creds.ExpiresIn,
		"expiresAt":     creds.ExpiresAt,
		"email":         email,
	}
	if passwordBlob != "" {
		w["password"] = passwordBlob
	}
	data, err := json.MarshalIndent(w, "", "  ")
	return append(data, '\n'), err
}

func sanitizeID(id string) string {
	var b strings.Builder
	for _, r := range id {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_':
			b.WriteRune(r)
		}
		if b.Len() >= 64 {
			break
		}
	}
	return b.String()
}

// ---------------------------------------------------------------------------
// Parsing (shared by inspect and import)
// ---------------------------------------------------------------------------

type parsedAccount struct {
	folderID     string
	email        string
	accessToken  string
	refreshToken string
	expiresAt    int64
	scope        string
	password     string // encrypted blob; "" = none
	usage        *accounts.Usage
	status       accounts.Status
	reasonCode   string
	reasonDetail string
	retryAt      time.Time
	addedAt      time.Time
	deviceID     string
	deviceToken  string
}

type parsedBackup struct {
	info         *backupInfo
	secretKey    []byte
	accounts     []*parsedAccount
	skippedAssoc int
}

type backupInfo struct {
	App      string `json:"app"`
	Format   int    `json:"format"`
	Version  string `json:"version"`
	Created  string `json:"created"`
	System   string `json:"system"`
	Accounts int    `json:"accounts"`
}

func parseBackup(data []byte) (*parsedBackup, error) {
	if len(data) == 0 {
		return nil, fmt.Errorf("%w: the file is empty", ErrBackupInvalid)
	}
	if len(data) > MaxBackupSize {
		return nil, fmt.Errorf("%w: the file exceeds %d bytes", ErrBackupInvalid, MaxBackupSize)
	}
	r, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("%w: not a zip file", ErrBackupInvalid)
	}
	if len(r.File) > maxEntries {
		return nil, fmt.Errorf("%w: more than %d entries", ErrBackupInvalid, maxEntries)
	}
	entries := map[string][]byte{}
	var total int64
	for _, f := range r.File {
		normalized := strings.ReplaceAll(f.Name, "\\", "/")
		normalized = strings.TrimPrefix(normalized, "./")
		m := entryAllowList.FindStringSubmatch(normalized)
		if m == nil {
			continue // unknown entry: ignored, never written out
		}
		rel := ""
		if m[1] != "" {
			rel = m[1]
		} else {
			rel = "accounts/" + m[2]
		}
		rc, err := f.Open()
		if err != nil {
			return nil, fmt.Errorf("%w: entry %q cannot be read", ErrBackupInvalid, f.Name)
		}
		buf, err := io.ReadAll(io.LimitReader(rc, maxEntrySize+1))
		_ = rc.Close()
		if err != nil {
			return nil, fmt.Errorf("%w: entry %q cannot be read", ErrBackupInvalid, f.Name)
		}
		if int64(len(buf)) > maxEntrySize {
			return nil, fmt.Errorf("%w: entry %q is too large", ErrBackupInvalid, f.Name)
		}
		total += int64(len(buf))
		if total > maxTotalRaw {
			return nil, fmt.Errorf("%w: the unzipped data is too large", ErrBackupInvalid)
		}
		entries[rel] = buf
	}

	pb := &parsedBackup{}
	if raw, ok := entries[backupInfoFile]; ok {
		var info backupInfo
		if err := json.Unmarshal(raw, &info); err == nil {
			pb.info = &info
		}
	}
	if raw, ok := entries["accounts/secret.key"]; ok {
		hexStr := strings.TrimSpace(string(raw))
		if validHexKey.MatchString(hexStr) {
			if key, err := hex.DecodeString(hexStr); err == nil && len(key) == 32 {
				pb.secretKey = key
			}
		}
	}

	// group account.json / credentials.json per folder id
	folders := map[string]map[string][]byte{}
	order := []string{}
	for rel, data := range entries {
		if rel == backupInfoFile || rel == "accounts/secret.key" {
			continue
		}
		parts := strings.Split(rel, "/")
		if len(parts) != 3 || parts[0] != "accounts" {
			continue
		}
		folder, kind := parts[1], parts[2]
		if kind != "account.json" && kind != "credentials.json" {
			continue
		}
		if _, ok := folders[folder]; !ok {
			folders[folder] = map[string][]byte{}
			order = append(order, folder)
		}
		folders[folder][kind] = data
	}
	for _, folder := range order {
		_ = folder
		files := folders[folder]
		credRaw := files["credentials.json"]
		if len(credRaw) == 0 {
			continue // unusable (no credentials) — skipped like Arman does
		}
		var creds struct {
			AccessToken  string          `json:"access_token"`
			RefreshToken string          `json:"refresh_token"`
			Scope        string          `json:"scope"`
			ObtainedAt   string          `json:"obtained_at"`
			ExpiresIn    int64           `json:"expires_in"`
			ExpiresAt    int64           `json:"expiresAt"`
			Password     string          `json:"password"`
			Email        string          `json:"email"`
		}
		if err := json.Unmarshal(credRaw, &creds); err != nil {
			continue
		}
		var account struct {
			Email        string          `json:"email"`
			AddedAt      json.RawMessage `json:"added_at"`
			Status       string          `json:"status"`
			ReasonCode   string          `json:"reason_code"`
			ReasonDetail string          `json:"reason_detail"`
			RetryAt      json.RawMessage `json:"retry_at"`
			Usage        *accounts.Usage `json:"usage"`
			DeviceID     string          `json:"device_id"`
			DeviceToken  string          `json:"device_token"`
		}
		if raw, ok := files["account.json"]; ok && len(raw) > 0 {
			_ = json.Unmarshal(raw, &account)
		}
		email := strings.ToLower(strings.TrimSpace(firstNonEmpty(creds.Email, account.Email)))
		refresh := strings.TrimSpace(creds.RefreshToken)
		if email == "" {
			continue
		}
		if refresh == "" {
			continue // unusable — silently skipped, counted later via skippedAssoc if desired
		}
		refreshAt := rawTimeOrZero(account.RetryAt)
		_ = refreshAt
		pa := &parsedAccount{
			folderID:     folder,
			email:        email,
			accessToken:  strings.TrimSpace(creds.AccessToken),
			refreshToken: refresh,
			expiresAt:    expiryOf(creds.ObtainedAt, creds.ExpiresIn, creds.ExpiresAt),
			scope:        strings.TrimSpace(creds.Scope),
			password:     strings.TrimSpace(creds.Password),
			usage:        account.Usage,
			status:       accounts.Status(account.Status),
			reasonCode:   account.ReasonCode,
			reasonDetail: account.ReasonDetail,
			retryAt:      rawTimeOrZero(account.RetryAt),
			addedAt:      rawTimeOrZero(account.AddedAt),
			deviceID:     account.DeviceID,
			deviceToken:  account.DeviceToken,
		}
		pb.accounts = append(pb.accounts, pa)
	}
	pb.skippedAssoc = len(order) - len(pb.accounts)
	return pb, nil
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func expiryOf(obtainedAt string, expiresIn, expiresAt int64) int64 {
	if at := parseEpochLoose(obtainedAt); at > 0 && expiresIn > 0 {
		return at + expiresIn
	}
	if expiresAt > 0 {
		if expiresAt < 1_000_000_000_000 { // seconds, not millis
			return expiresAt
		}
		return expiresAt / 1000
	}
	if at := parseEpochLoose(obtainedAt); at > 0 {
		return at
	}
	return 0
}

func parseEpochLoose(raw string) int64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}
	var n int64
	if _, err := fmt.Sscanf(raw, "%d", &n); err == nil && n > 0 {
		return n
	}
	if t, err := time.Parse(time.RFC3339Nano, raw); err == nil {
		return t.Unix()
	}
	if t, err := time.Parse(time.RFC3339, raw); err == nil {
		return t.Unix()
	}
	return 0
}

func rawTimeOrZero(raw json.RawMessage) time.Time {
	s := strings.Trim(strings.TrimSpace(string(raw)), `"`)
	if s == "" || s == "null" {
		return time.Time{}
	}
	if t, err := time.Parse(time.RFC3339Nano, s); err == nil {
		return t
	}
	if n := parseEpochLoose(s); n > 0 {
		return time.Unix(n, 0).UTC()
	}
	return time.Time{}
}

// CreatedAtEpochSeconds parses backup.json's "created" (RFC3339Nano or plain epoch).
// Exported for the server layer and tests.
func CreatedAtEpochSeconds(created string) int64 {
	return parseEpochLoose(created)
}
