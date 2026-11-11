package config

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type xorTestCipher struct {
	key byte
}

func (c xorTestCipher) Protect(plain []byte) ([]byte, error) {
	out := make([]byte, len(plain)+4)
	copy(out, "SEAL")
	for i, b := range plain {
		out[i+4] = b ^ c.key
	}
	return out, nil
}

func (c xorTestCipher) Unprotect(sealed []byte) ([]byte, error) {
	if len(sealed) < 4 || !bytes.Equal(sealed[:4], []byte("SEAL")) {
		return nil, os.ErrInvalid
	}
	out := make([]byte, len(sealed)-4)
	for i, b := range sealed[4:] {
		out[i] = b ^ c.key
	}
	return out, nil
}

func TestRelayStoreDefaultsEncryptionRetentionAndRedaction(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "relay.json")
	store := NewRelayStore(path, xorTestCipher{key: 0x5A})

	// 1. Defaults match Android SettingsStore: SharedEnabled=true, PersonalEnabled=false, PersonalOnly=false.
	initial := store.Load()
	if !initial.SharedEnabled || initial.PersonalEnabled || initial.PersonalOnly || initial.PersonalURL != "" {
		t.Fatalf("unexpected defaults: %+v", initial)
	}

	// 2. Save personal relay URL + shared secret -> encrypted on disk, zero plaintext leak.
	secretURL := "https://my-relay.example.workers.dev/?secret=TOP_SECRET_VALUE_999"
	_, err := store.Update(func(c *RelayConfig) error {
		c.PersonalURL = secretURL
		c.PersonalEnabled = true
		c.PersonalOnly = true
		c.SharedSecret = "SHARED_SECRET_777"
		return nil
	})
	if err != nil {
		t.Fatalf("Update failed: %v", err)
	}

	rawDisk, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read relay.json: %v", err)
	}
	if strings.Contains(string(rawDisk), "TOP_SECRET_VALUE_999") || strings.Contains(string(rawDisk), "SHARED_SECRET_777") {
		t.Fatalf("secrets leaked in plaintext on disk: %s", string(rawDisk))
	}

	loaded := store.Load()
	if loaded.PersonalURL != secretURL || !loaded.PersonalEnabled || !loaded.PersonalOnly || loaded.SharedSecret != "SHARED_SECRET_777" {
		t.Fatalf("unsealed config mismatch: %+v", loaded)
	}

	// 3. PublicRelayView exposes ONLY host, never secret.
	pub := PublicRelayView(loaded)
	if !pub.PersonalURLSet || pub.PersonalHost != "my-relay.example.workers.dev" {
		t.Fatalf("unexpected PublicRelayView: %+v", pub)
	}

	// 4. Toggling PersonalEnabled=false retains saved PersonalURL (matching Android SettingsStore).
	_, err = store.Update(func(c *RelayConfig) error {
		c.PersonalEnabled = false
		c.PersonalOnly = false
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	afterDisable := store.Load()
	if afterDisable.PersonalEnabled || afterDisable.PersonalOnly || afterDisable.PersonalURL != secretURL {
		t.Fatalf("PersonalURL was lost when disabling PersonalEnabled: %+v", afterDisable)
	}
}

func TestAnnouncementStorePersistenceAndDeduplication(t *testing.T) {
	path := filepath.Join(t.TempDir(), "announcements.json")
	store := NewAnnouncementStore(path)

	if len(store.AcknowledgedSet()) != 0 {
		t.Fatal("expected empty acknowledged set initially")
	}
	if err := store.Acknowledge("   "); err != nil {
		t.Fatal(err)
	}
	if len(store.AcknowledgedSet()) != 0 {
		t.Fatal("blank ID must be ignored")
	}

	if err := store.Acknowledge("ann-2026-01"); err != nil {
		t.Fatal(err)
	}
	if err := store.Acknowledge("ann-2026-01"); err != nil {
		t.Fatal(err)
	}
	if err := store.Acknowledge("ann-2026-02"); err != nil {
		t.Fatal(err)
	}

	reloaded := NewAnnouncementStore(path)
	ack := reloaded.AcknowledgedSet()
	if len(ack) != 2 || !ack["ann-2026-01"] || !ack["ann-2026-02"] {
		t.Fatalf("unexpected acknowledged set after reload: %v", ack)
	}
}
