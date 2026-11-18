// Package accounts is the multi-account store of the app: the one place OAuth tokens,
// quota observations and per-account status live. The backup, the pool manager and the
// panel all go through it — nothing reads state.json or a "saved accounts" file directly.
package accounts

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	accountsDirName   = "accounts"
	accountFileName   = "account.json"
	credentialsName   = "credentials.json"
	accountIDLen      = 16
	maxStoredAccounts = 50
)

// Status is the rotation state of an account.
type Status string

const (
	StatusReady          Status = "ready"
	StatusReserved       Status = "reserved"
	StatusUnavailable    Status = "unavailable"
	StatusReauthRequired Status = "reauth_required"
)

// Reason codes stored next to the status.
const (
	ReasonNone           = "none"
	ReasonSignInRequired = "sign_in_required"
	ReasonNotEntitled    = "not_entitled"
	ReasonQuotaExhausted = "quota_exhausted"
	ReasonTokenInvalid   = "token_invalid"
)

var validID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`).MatchString

// Usage is the last quota reading. Zero-limit + !Unlimited means "unknown" — the runtime
// keeps the previous reading rather than erasing it (the pool uses this distinction).
type Usage struct {
	Unlimited      bool
	LimitBytes     int64
	RemainingBytes int64
	ResetAt        time.Time
	CheckedAt      time.Time
}

// Account is the non-secret record of one account.
type Account struct {
	ID           string
	Email        string
	AddedAt      time.Time
	Status       Status
	ReasonCode   string
	ReasonDetail string
	RetryAt      time.Time
	Usage        *Usage
	DeviceID     string
	DeviceToken  string
	// Disabled accounts stay on disk (tokens and password intact) but never rotate.
	Disabled bool `json:"disabled,omitempty"`
}

// Known reports whether the reading is real (unlimited, or a positive limit).
func (u *Usage) Known() bool {
	if u == nil {
		return false
	}
	return u.Unlimited || u.LimitBytes > 0
}

// ---- JSON wire (byte-compatible with the Android account.json) ---------------

func isoT(t time.Time) string {
	if t.IsZero() {
		return ""
	}
	return t.UTC().Format(time.RFC3339Nano)
}

func (u Usage) MarshalJSON() ([]byte, error) {
	w := struct {
		Unlimited      bool   `json:"unlimited"`
		LimitBytes     int64  `json:"limit_bytes,omitempty"`
		RemainingBytes int64  `json:"remaining_bytes,omitempty"`
		ResetAt        string `json:"reset_at,omitempty"`
		CheckedAt      string `json:"checked_at,omitempty"`
	}{u.Unlimited, u.LimitBytes, u.RemainingBytes, isoT(u.ResetAt), isoT(u.CheckedAt)}
	return json.Marshal(w)
}

func (u *Usage) UnmarshalJSON(data []byte) error {
	var w struct {
		Unlimited      bool            `json:"unlimited"`
		LimitBytes     int64           `json:"limit_bytes"`
		RemainingBytes int64           `json:"remaining_bytes"`
		ResetAt        json.RawMessage `json:"reset_at"`
		CheckedAt      json.RawMessage `json:"checked_at"`
	}
	if err := json.Unmarshal(data, &w); err != nil {
		return err
	}
	u.Unlimited = w.Unlimited
	u.LimitBytes = w.LimitBytes
	u.RemainingBytes = w.RemainingBytes
	u.ResetAt = rawTime(w.ResetAt)
	u.CheckedAt = rawTime(w.CheckedAt)
	// consistency: remaining must not exceed the limit
	if u.LimitBytes > 0 && u.RemainingBytes > u.LimitBytes {
		u.RemainingBytes = u.LimitBytes
	}
	if u.LimitBytes < 0 {
		u.LimitBytes = 0
	}
	if u.RemainingBytes < 0 {
		u.RemainingBytes = 0
	}
	// unlimited accounts carry no limit numbers
	if u.Unlimited {
		u.LimitBytes = 0
		u.RemainingBytes = 0
	}
	return nil
}

func rawTime(raw json.RawMessage) time.Time {
	raw = trimQuotes(raw)
	if len(raw) == 0 {
		return time.Time{}
	}
	if at, err := time.Parse(time.RFC3339Nano, string(raw)); err == nil {
		return at
	}
	if n, ok := JSONNumberLoose([]byte(raw)); ok && n > 0 {
		return time.Unix(n, 0).UTC()
	}
	return time.Time{}
}

func trimQuotes(raw []byte) []byte {
	raw = []byte(strings.TrimSpace(string(raw)))
	if len(raw) >= 2 && raw[0] == '"' && raw[len(raw)-1] == '"' {
		return raw[1 : len(raw)-1]
	}
	return raw
}

func (a Account) MarshalJSON() ([]byte, error) {
	w := struct {
		ID           string `json:"id"`
		Email        string `json:"email"`
		AddedAt      string `json:"added_at"`
		Status       Status `json:"status"`
		ReasonCode   string `json:"reason_code,omitempty"`
		ReasonDetail string `json:"reason_detail,omitempty"`
		RetryAt      string `json:"retry_at,omitempty"`
		Usage        *Usage `json:"usage,omitempty"`
		DeviceID     string `json:"device_id,omitempty"`
		DeviceToken  string `json:"device_token,omitempty"`
		Disabled bool   `json:"disabled,omitempty"`
	}{a.ID, a.Email, isoT(a.AddedAt), a.Status, a.ReasonCode, a.ReasonDetail, isoT(a.RetryAt), a.Usage, a.DeviceID, a.DeviceToken, a.Disabled}
	return json.Marshal(w)
}

func (a *Account) UnmarshalJSON(data []byte) error {
	var w struct {
		ID           string          `json:"id"`
		Email        string          `json:"email"`
		AddedAt      json.RawMessage `json:"added_at"`
		Status       Status          `json:"status"`
		ReasonCode   string          `json:"reason_code"`
		ReasonDetail string          `json:"reason_detail"`
		RetryAt      json.RawMessage `json:"retry_at"`
		Usage        *Usage          `json:"usage"`
		DeviceID     string          `json:"device_id"`
		DeviceToken  string          `json:"device_token"`
		Disabled     bool            `json:"disabled"`
	}
	if err := json.Unmarshal(data, &w); err != nil {
		return err
	}
	a.ID = strings.TrimSpace(w.ID)
	a.Email = strings.TrimSpace(w.Email)
	a.AddedAt = rawTime(w.AddedAt)
	a.Status = w.Status
	if a.Status == "" {
		a.Status = StatusReady
	}
	a.ReasonCode = w.ReasonCode
	a.ReasonDetail = w.ReasonDetail
	a.RetryAt = rawTime(w.RetryAt)
	a.Usage = w.Usage
	a.DeviceID = w.DeviceID
	a.DeviceToken = w.DeviceToken
	a.Disabled = w.Disabled
	return nil
}

// credentialsFile is credentials.json: tokens flattened at the top level plus the encrypted password.
type credentialsFile struct {
	SavedTokens
	Password string `json:"password,omitempty"`
	Email    string `json:"email,omitempty"`
}

// Store is the on-disk account pool rooted at dataDir/accounts.
type Store struct {
	dir string
	mu  sync.Mutex
}

// Warn receives notices about folders that are skipped. The app sends them to the log.
var Warn func(msg string)

func warnf(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	if Warn != nil {
		Warn(msg)
		return
	}
	slog.Warn(msg)
}

// OpenStore opens (and creates) the account store inside dataDir.
func OpenStore(dataDir string) (*Store, error) {
	dir := filepath.Join(dataDir, accountsDirName)
	if err := EnsurePrivateDir(dir); err != nil {
		return nil, fmt.Errorf("creating account store: %w", err)
	}
	return &Store{dir: dir}, nil
}

// Dir returns the accounts directory (used by the backup exporter).
func (s *Store) Dir() string { return s.dir }

// normalizeEmail lowercases and trims; empty stays empty.
func normalizeEmail(email string) string { return strings.ToLower(strings.TrimSpace(email)) }

func readAccount(dir string) (Account, error) {
	var acc Account
	data, err := ReadCapped(filepath.Join(dir, accountFileName), MaxSecretFile)
	if err != nil {
		return acc, err
	}
	if err := json.Unmarshal(data, &acc); err != nil {
		return acc, err
	}
	if acc.ID == "" || acc.Email == "" {
		return acc, errors.New("account record is missing id or email")
	}
	return acc, nil
}

func (s *Store) readAccountLocked(id string) (Account, error) {
	return readAccount(filepath.Join(s.dir, id))
}

// List returns every readable account (AddedAt order). Damaged folders are skipped with a warning.
func (s *Store) List() ([]Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.listLocked()
}

func (s *Store) listLocked() ([]Account, error) {
	entries, err := os.ReadDir(s.dir)
	if err != nil {
		return nil, err
	}
	out := []Account{}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		acc, err := readAccount(filepath.Join(s.dir, e.Name()))
		if err != nil {
			warnf("skipping unreadable account folder %q", e.Name())
			continue
		}
		// make sure the id inside the file matches the folder
		acc.ID = e.Name()
		out = append(out, acc)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].AddedAt.Equal(out[j].AddedAt) {
			return out[i].ID < out[j].ID
		}
		return out[i].AddedAt.Before(out[j].AddedAt)
	})
	return out, nil
}

// Get returns one account.
func (s *Store) Get(id string) (*Account, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return nil, errBadID
	}
	acc, err := s.readAccountLocked(id)
	if err != nil {
		return nil, err
	}
	acc.ID = id
	return &acc, nil
}

// GetByEmail finds an account by email (case-insensitive).
func (s *Store) GetByEmail(email string) (*Account, error) {
	email = normalizeEmail(email)
	s.mu.Lock()
	defer s.mu.Unlock()
	list, err := s.listLocked()
	if err != nil {
		return nil, err
	}
	for _, acc := range list {
		if normalizeEmail(acc.Email) == email {
			a := acc
			return &a, nil
		}
	}
	return nil, nil
}

// Credentials returns the tokens (and any encrypted password marker) of one account.
func (s *Store) Credentials(id string) (*SavedTokens, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return nil, errBadID
	}
	var creds credentialsFile
	data, err := ReadCapped(filepath.Join(s.dir, id, credentialsName), MaxSecretFile)
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &creds); err != nil {
		return nil, fmt.Errorf("parsing credentials of account %s: %w", id, err)
	}
	return &creds.SavedTokens, nil
}

var errBadID = errors.New("invalid account id")

func newAccountID() (string, error) {
	b := make([]byte, accountIDLen/2)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// Add upserts an account by email (case-insensitive): an existing account gets its tokens
// replaced and comes back usable (status ready); a new one is created. created reports which.
func (s *Store) Add(email string, creds *SavedTokens, now time.Time) (Account, bool, error) {
	email = normalizeEmail(email)
	if email == "" {
		return Account{}, false, errors.New("email is required")
	}
	if creds == nil || creds.RefreshToken == "" {
		return Account{}, false, errors.New("credentials with a refresh token are required")
	}
	if now.IsZero() {
		now = time.Now()
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	list, err := s.listLocked()
	if err != nil {
		return Account{}, false, err
	}
	for _, acc := range list {
		if normalizeEmail(acc.Email) != email {
			continue
		}
		acc.Status = StatusReady
		acc.ReasonCode = ""
		acc.ReasonDetail = ""
		acc.RetryAt = time.Time{}
		if err := s.writeCredentialsLocked(acc.ID, creds, email, ""); err != nil {
			return Account{}, false, err
		}
		// a re-login proves the account is reachable; clear any stale usage gate
		if err := s.writeAccountLocked(acc); err != nil {
			return Account{}, false, err
		}
		return acc, false, nil
	}
	if len(list) >= maxStoredAccounts {
		return Account{}, false, fmt.Errorf("at most %d accounts can be stored", maxStoredAccounts)
	}
	id, err := newAccountID()
	if err != nil {
		return Account{}, false, err
	}
	dir := filepath.Join(s.dir, id)
	if err := EnsurePrivateDir(dir); err != nil {
		return Account{}, false, err
	}
	acc := Account{
		ID:      id,
		Email:   email,
		AddedAt: now.UTC(),
		Status:  StatusReady,
	}
	if err := s.writeCredentialsLocked(id, creds, email, ""); err != nil {
		_ = os.RemoveAll(dir)
		return Account{}, false, err
	}
	if err := s.writeAccountLocked(acc); err != nil {
		_ = os.RemoveAll(dir)
		return Account{}, false, err
	}
	return acc, true, nil
}

// writeCredentialsLocked writes credentials.json, preserving the stored password unless passwordOverride is set.
func (s *Store) writeCredentialsLocked(id string, creds *SavedTokens, email, passwordOverride string) error {
	var file credentialsFile
	if raw, err := ReadCapped(filepath.Join(s.dir, id, credentialsName), MaxSecretFile); err == nil {
		_ = json.Unmarshal(raw, &file)
	}
	file.SavedTokens = *creds
	file.Email = email
	if passwordOverride != "" {
		file.Password = passwordOverride
	}
	data, err := json.MarshalIndent(file, "", "  ")
	if err != nil {
		return err
	}
	return WritePrivate(filepath.Join(s.dir, id, credentialsName), append(data, '\n'))
}

func (s *Store) writeAccountLocked(acc Account) error {
	data, err := json.MarshalIndent(acc, "", "  ")
	if err != nil {
		return err
	}
	return WritePrivate(filepath.Join(s.dir, acc.ID, accountFileName), append(data, '\n'))
}

// SaveState replaces the account record wholesale (status/reason/retry/usage).
func (s *Store) SaveState(acc Account) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(acc.ID) {
		return errBadID
	}
	return s.writeAccountLocked(acc)
}

// SaveUsage stores a fresh quota reading on the account.
func (s *Store) SaveUsage(id string, u Usage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return errBadID
	}
	acc, err := s.readAccountLocked(id)
	if err != nil {
		return err
	}
	acc.ID = id
	acc.Usage = &u
	return s.writeAccountLocked(acc)
}

// SaveDevice stores the device pairing strings (round-tripped for backup compatibility).
func (s *Store) SaveDevice(id, deviceID, deviceToken string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return errBadID
	}
	acc, err := s.readAccountLocked(id)
	if err != nil {
		return err
	}
	acc.ID = id
	acc.DeviceID = deviceID
	acc.DeviceToken = deviceToken
	return s.writeAccountLocked(acc)
}

// SetPassword encrypts and stores the account's password (empty removes it).
func (s *Store) SetPassword(id, password string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return errBadID
	}
	var file credentialsFile
	if raw, err := ReadCapped(filepath.Join(s.dir, id, credentialsName), MaxSecretFile); err == nil {
		_ = json.Unmarshal(raw, &file)
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if password == "" {
		file.Password = ""
	} else {
		key, err := s.secretKeyLocked()
		if err != nil {
			return err
		}
		blob, err := EncryptWithKey(key, password)
		if err != nil {
			return err
		}
		file.Password = blob
	}
	data, err := json.MarshalIndent(file, "", "  ")
	if err != nil {
		return err
	}
	return WritePrivate(filepath.Join(s.dir, id, credentialsName), append(data, '\n'))
}

// Password decrypts the stored password (ErrNoPassword when absent or undecryptable).
func (s *Store) Password(id string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return "", errBadID
	}
	var file credentialsFile
	data, err := ReadCapped(filepath.Join(s.dir, id, credentialsName), MaxSecretFile)
	if err != nil {
		return "", err
	}
	if err := json.Unmarshal(data, &file); err != nil {
		return "", err
	}
	if file.Password == "" {
		return "", ErrNoPassword
	}
	key, err := s.secretKeyLocked()
	if err != nil {
		return "", err
	}
	plain, err := DecryptWithKey(key, file.Password)
	if err != nil {
		return "", ErrNoPassword
	}
	return plain, nil
}

// HasPassword reports whether the account has a stored password.
func (s *Store) HasPassword(id string) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return false, errBadID
	}
	var file credentialsFile
	data, err := ReadCapped(filepath.Join(s.dir, id, credentialsName), MaxSecretFile)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return false, nil
		}
		return false, err
	}
	if err := json.Unmarshal(data, &file); err != nil {
		return false, err
	}
	return file.Password != "", nil
}

// Remove deletes the account completely (files and all).
func (s *Store) Remove(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return errBadID
	}
	return os.RemoveAll(filepath.Join(s.dir, id))
}

// UpdateTokens replaces the stored tokens of an account (pool refresh path). Unlike Add it
// never touches status, reason, retry_at or usage — a token refresh must not revive a reserved
// account or clear a reauth flag.
func (s *Store) UpdateTokens(id string, creds *SavedTokens) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return errBadID
	}
	if creds == nil || creds.RefreshToken == "" {
		return errors.New("credentials with a refresh token are required")
	}
	acc, err := s.readAccountLocked(id)
	if err != nil {
		return err
	}
	acc.ID = id
	if err := s.writeCredentialsLocked(id, creds, acc.Email, ""); err != nil {
		return err
	}
	return s.writeAccountLocked(acc)
}
