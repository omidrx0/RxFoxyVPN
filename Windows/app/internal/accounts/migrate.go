package accounts

import (
	"encoding/base64"
	"encoding/json"
	"log/slog"
	"os"
	"strings"
	"time"
)

// LegacyMigratedWindowsState is the legacy foxy state.json shape (only the fields we need).
type legacyState struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ClientID     string `json:"client_id"`
	ExpiresAt    int64  `json:"expires_at"`
	Email        string `json:"email"`
	LastCountry  string `json:"last_country"`
}

// LegacyCipher decrypts the legacy "saved passwords" blob (DPAPI on Windows).
type LegacyCipher interface {
	Unprotect(sealed []byte) ([]byte, error)
}

// MigrateLegacyIfNeeded moves the single legacy session (state.json) and the matching
// DPAPI-saved password (saved_accounts.json) into the account pool, once (W-04).
// It is idempotent: a non-empty account store means a previous run already migrated.
// The legacy session file is rewritten with the credentials removed; `last_country`
// is kept. Orphan saved passwords are left untouched in the legacy file, which stays
// readable for the direct-login "remember me" flow.
func MigrateLegacyIfNeeded(dataDir, statePath, savedAccountsPath string, cipher LegacyCipher, now time.Time) (bool, error) {
	store, err := OpenStore(dataDir)
	if err != nil {
		return false, err
	}
	existing, err := store.List()
	if err != nil {
		return false, err
	}
	if len(existing) > 0 {
		// Already migrated (or already has manual accounts): never touch anything.
		return false, nil
	}

	raw, err := os.ReadFile(statePath)
	if err != nil {
		if os.IsNotExist(err) {
			return false, nil // fresh install: nothing to migrate
		}
		return false, err
	}
	var st legacyState
	if err := json.Unmarshal(raw, &st); err != nil {
		// an unreadable legacy file is left in place — never destroy data we cannot read
		slog.Warn("legacy state file is unreadable; skipping migration", "err", err)
		return false, nil
	}
	if strings.TrimSpace(st.RefreshToken) == "" {
		return false, nil // signed out / access-token-only: nothing the pool can use
	}
	if now.IsZero() {
		now = time.Now()
	}

	email := normalizeEmail(st.Email)
	if email == "" {
		email = "account"
	}
	expiresIn := int64(0)
	if st.ExpiresAt > now.Unix() {
		expiresIn = st.ExpiresAt - now.Unix()
	}
	creds := &SavedTokens{
		AccessToken:  strings.TrimSpace(st.AccessToken),
		RefreshToken: strings.TrimSpace(st.RefreshToken),
		ClientID:     strings.TrimSpace(st.ClientID),
		ObtainedAt:   now.UTC().Format(time.RFC3339Nano),
		ExpiresIn:    expiresIn,
		ExpiresAt:    st.ExpiresAt,
	}
	acc, _, err := store.Add(email, creds, now)
	if err != nil {
		return false, err
	}
	slog.Info("legacy single account migrated into the pool")

	// bind the matching DPAPI-saved password to the migrated account, if any
	if cipher != nil {
		if err := migrateSavedPassword(store, acc.ID, email, savedAccountsPath, cipher); err != nil {
			slog.Warn("could not migrate the saved password", "err", err)
		}
	}

	// empty the legacy session but keep the user's last country
	kept, err := json.Marshal(map[string]any{"last_country": st.LastCountry})
	if err == nil {
		if err := WritePrivate(statePath, append(kept, '\n')); err != nil {
			slog.Warn("could not clear the legacy session file", "err", err)
		}
	}
	return true, nil
}

func migrateSavedPassword(store *Store, accountID, email, savedAccountsPath string, cipher LegacyCipher) error {
	raw, err := os.ReadFile(savedAccountsPath)
	if err != nil {
		return nil // no saved passwords at all
	}
	var m map[string]struct {
		Email    string `json:"email"`
		Password string `json:"password"`
	}
	if err := json.Unmarshal(raw, &m); err != nil {
		return err // leave the file untouched
	}
	for key, entry := range m {
		if !strings.EqualFold(key, email) && !strings.EqualFold(entry.Email, email) {
			continue
		}
		blob, err := base64.StdEncoding.DecodeString(entry.Password)
		if err != nil {
			return err
		}
		plain, err := cipher.Unprotect(blob)
		if err != nil {
			return err
		}
		if len(plain) == 0 {
			return nil
		}
		err = store.SetPassword(accountID, string(plain))
		for i := range plain {
			plain[i] = 0
		}
		return err
	}
	// no saved password for this account: nothing to bind; the legacy file stays untouched
	return nil
}
