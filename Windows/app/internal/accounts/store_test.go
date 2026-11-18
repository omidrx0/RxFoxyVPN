package accounts

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func nowT() time.Time { return time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC) }

func addTest(t *testing.T, st *Store, email string) Account {
	t.Helper()
	creds := TokensFromLogin("at-"+email, "rt-"+email, "", "profile", "bearer", 3600, nowT())
	acc, created, err := st.Add(email, creds, nowT())
	if err != nil {
		t.Fatal(err)
	}
	if !created {
		t.Fatalf("first add must be created=true")
	}
	return acc
}

func TestAddListGetRemove(t *testing.T) {
	st, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	a1 := addTest(t, st, "one@example.com")
	addTest(t, st, "two@example.com")

	list, err := st.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 {
		t.Fatalf("len = %d", len(list))
	}
	if list[0].AddedAt.After(list[1].AddedAt) {
		t.Fatal("list must be added-order")
	}
	got, err := st.Get(a1.ID)
	if err != nil || got.Email != "one@example.com" {
		t.Fatalf("Get: %+v %v", got, err)
	}
	creds, err := st.Credentials(a1.ID)
	if err != nil || creds.RefreshToken != "rt-one@example.com" {
		t.Fatalf("Credentials: %+v %v", creds, err)
	}
	// re-adding same email updates tokens in place (created=false, same id)
	creds2 := TokensFromLogin("at2", "rt2", "", "profile", "bearer", 3600, nowT())
	acc2, created, err := st.Add("ONE@example.com", creds2, nowT())
	if err != nil {
		t.Fatal(err)
	}
	if created || acc2.ID != a1.ID {
		t.Fatalf("re-add must upsert: created=%v id=%s", created, acc2.ID)
	}
	creds3, _ := st.Credentials(a1.ID)
	if creds3.RefreshToken != "rt2" {
		t.Fatal("tokens were not replaced")
	}
	if err := st.Remove(a1.ID); err != nil {
		t.Fatal(err)
	}
	list, _ = st.List()
	if len(list) != 1 {
		t.Fatal("remove failed")
	}
}

func TestDamagedFolderIsSkipped(t *testing.T) {
	dir := t.TempDir()
	st, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	addTest(t, st, "good@example.com")
	// plant a damaged folder
	bad := filepath.Join(dir, "accounts", "deadbeefdeadbeef")
	if err := os.MkdirAll(bad, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(bad, "account.json"), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	origWarn := Warn
	defer func() { Warn = origWarn }()
	saw := ""
	Warn = func(msg string) { saw += msg + "\n" }
	list, err := st.List()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 || list[0].Email != "good@example.com" {
		t.Fatalf("damaged folder must be skipped: %+v", list)
	}
	if !strings.Contains(saw, "deadbeefdeadbeef") {
		t.Fatalf("a skip warning must be emitted, got %q", saw)
	}
}

func TestPasswordRoundTripAndWrongKey(t *testing.T) {
	st, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	acc := addTest(t, st, "pw@example.com")
	if err := st.SetPassword(acc.ID, "hunter2-secret"); err != nil {
		t.Fatal(err)
	}
	has, err := st.HasPassword(acc.ID)
	if err != nil || !has {
		t.Fatalf("HasPassword = %v %v", has, err)
	}
	pw, err := st.Password(acc.ID)
	if err != nil || pw != "hunter2-secret" {
		t.Fatalf("Password = %q %v", pw, err)
	}
	// no plaintext anywhere in the file
	raw, err := os.ReadFile(filepath.Join(st.Dir(), acc.ID, "credentials.json"))
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(raw), "hunter2-secret") {
		t.Fatal("password stored in cleartext")
	}
	// corrupt the blob → Password returns ErrNoPassword, never the plaintext/error text
	file := map[string]any{}
	_ = json.Unmarshal(raw, &file)
	file["password"] = "v1:!!!not-base64!!!"
	mut, _ := json.Marshal(file)
	if err := os.WriteFile(filepath.Join(st.Dir(), acc.ID, "credentials.json"), mut, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := st.Password(acc.ID); !errors.Is(err, ErrNoPassword) {
		t.Fatalf("corrupt blob must surface ErrNoPassword, got %v", err)
	}
}

func TestSecretKeyStableAcrossOpens(t *testing.T) {
	dir := t.TempDir()
	st1, _ := OpenStore(dir)
	k1, err := st1.SecretKey()
	if err != nil {
		t.Fatal(err)
	}
	st2, _ := OpenStore(dir)
	k2, err := st2.SecretKey()
	if err != nil {
		t.Fatal(err)
	}
	if string(k1) != string(k2) {
		t.Fatal("secret key must be generated once and reused")
	}
	// file content is 64-char hex + \n
	raw, _ := os.ReadFile(filepath.Join(dir, "accounts", "secret.key"))
	if len(strings.TrimSpace(string(raw))) != 64 {
		t.Fatalf("secret.key must be 32 bytes of hex, got %q", raw)
	}
}

func TestRemoveIDValidation(t *testing.T) {
	st, _ := OpenStore(t.TempDir())
	addTest(t, st, "x@example.com")
	if err := st.Remove("../x"); err == nil {
		t.Fatal("path escape must be refused")
	}
	list, _ := st.List()
	if len(list) != 1 {
		t.Fatal("remove must not touch anything on invalid id")
	}
}

func TestMaxStoredAccounts(t *testing.T) {
	st, _ := OpenStore(t.TempDir())
	for i := 0; i < maxStoredAccounts; i++ {
		addTest(t, st, fmt.Sprintf("user%02d@example.com", i))
	}
	creds := TokensFromLogin("at", "rt", "", "profile", "bearer", 3600, nowT())
	if _, _, err := st.Add("one-too-many@example.com", creds, nowT()); err == nil {
		t.Fatal("the cap must be enforced")
	}
}
