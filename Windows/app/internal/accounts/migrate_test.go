package accounts

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type fakeCipher struct{}

func (fakeCipher) Unprotect(sealed []byte) ([]byte, error) {
	// our "legacy" test blob is the raw password bytes
	return sealed, nil
}

func TestMigrateLegacyEndToEnd(t *testing.T) {
	dir := t.TempDir()
	statePath := filepath.Join(dir, "state.json")
	savedPath := filepath.Join(dir, "saved_accounts.json")

	now := time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC)
	state := map[string]any{
		"access_token":  "legacy-at",
		"refresh_token": "legacy-rt",
		"client_id":     "legacy-cid",
		"expires_at":    now.Add(time.Hour).Unix(),
		"email":         "Legacy@Example.com",
		"last_country":  "ir",
	}
	writeJSON(t, statePath, state)
	writeJSON(t, savedPath, map[string]map[string]string{
		"legacy@example.com": {"email": "legacy@example.com", "password": "cGFzczEyMw=="},
	})

	// legacy cipher double: decode base64 then return raw
	if err := os.WriteFile(savedPath, []byte(`{"legacy@example.com":{"email":"legacy@example.com","password":"cGFzczEyMw=="}}`), 0o600); err != nil {
		t.Fatal(err)
	}

	migrated, err := MigrateLegacyIfNeeded(dir, statePath, savedPath, strictBase64Cipher{}, now)
	if err != nil {
		t.Fatal(err)
	}
	if !migrated {
		t.Fatal("migration must report true")
	}

	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	list, err := store.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 {
		t.Fatalf("expected 1 migrated account, got %d", len(list))
	}
	acc := list[0]
	if acc.Email != "legacy@example.com" || acc.Status != StatusReady {
		t.Fatalf("unexpected migrated account: %+v", acc)
	}
	creds, err := store.Credentials(acc.ID)
	if err != nil {
		t.Fatal(err)
	}
	if creds.RefreshToken != "legacy-rt" {
		t.Fatalf("refresh token not carried over: %+v", creds)
	}
	pw, err := store.Password(acc.ID)
	if err != nil || pw != "pass123" {
		t.Fatalf("password not carried over: %q %v", pw, err)
	}

	// legacy session emptied, country kept
	raw, _ := os.ReadFile(statePath)
	var left map[string]any
	if err := json.Unmarshal(raw, &left); err != nil {
		t.Fatal(err)
	}
	if _, ok := left["refresh_token"]; ok {
		t.Fatal("legacy refresh token must be removed")
	}
	if left["last_country"] != "ir" {
		t.Fatal("last_country must survive")
	}

	// second run is a no-op
	migrated2, err := MigrateLegacyIfNeeded(dir, statePath, savedPath, strictBase64Cipher{}, now)
	if err != nil || migrated2 {
		t.Fatalf("re-run must be a no-op: %v %v", migrated2, err)
	}
}

// strictBase64Cipher decodes the base64 blob to raw bytes, mimicking the legacy DPAPI
// semantics where the stored payload is the password itself in this test.
type strictBase64Cipher struct{}

func (strictBase64Cipher) Unprotect(sealed []byte) ([]byte, error) { return sealed, nil }

func writeJSON(t *testing.T, path string, v any) {
	t.Helper()
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, append(b, '\n'), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestMigrateLegacyAccessTokenOnly(t *testing.T) {
	dir := t.TempDir()
	statePath := filepath.Join(dir, "state.json")
	now := time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC)
	writeJSON(t, statePath, map[string]any{"access_token": "at", "refresh_token": "", "email": "x@example.com"})
	migrated, err := MigrateLegacyIfNeeded(dir, statePath, filepath.Join(dir, "none.json"), nil, now)
	if err != nil {
		t.Fatal(err)
	}
	if migrated {
		t.Fatal("access-token-only sessions cannot be pooled; migration must be a no-op")
	}
	// the file must be untouched
	raw, _ := os.ReadFile(statePath)
	if !bytes.Contains(raw, []byte("at")) {
		t.Fatal("untouched legacy file expected")
	}
}
