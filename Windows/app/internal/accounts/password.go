package accounts

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Passwords are stored so an account whose tokens were revoked can sign in again without the
// user typing them. They are encrypted with AES-256-GCM under a random key stored next to the
// accounts (secret.key), and transferred between devices only inside a backup zip — the backup's
// copy can only be decrypted with the backup's own key, and it NEVER replaces the device key.
const (
	secretKeyFileName = "secret.key"
	passwordPrefix    = "v1:"
)

// ErrNoPassword reports an account without a stored password.
var ErrNoPassword = errors.New("no stored password")

// secretKey returns the encryption key, creating it on first use.
func (s *Store) SecretKey() ([]byte, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.secretKeyLocked()
}

func (s *Store) secretKeyLocked() ([]byte, error) {
	path := filepath.Join(s.dir, secretKeyFileName)
	data, err := ReadCapped(path, 4096)
	if err == nil {
		key, err := hex.DecodeString(strings.TrimSpace(string(data)))
		if err != nil || len(key) != 32 {
			return nil, fmt.Errorf("%s is damaged; sign in again to recreate the account", secretKeyFileName)
		}
		return key, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, err
	}
	if err := WritePrivate(path, []byte(hex.EncodeToString(key)+"\n")); err != nil {
		return nil, err
	}
	return key, nil
}

// SecretKeyPath is the path of the secret.key file (used by the backup exporter).
func (s *Store) SecretKeyPath() string { return filepath.Join(s.dir, secretKeyFileName) }

// EncryptWithKey encrypts plain under key in the v1 format (base64(nonce‖ciphertext‖tag)).
func EncryptWithKey(key []byte, plain string) (string, error) {
	aead, err := newAEAD(key)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	sealed := aead.Seal(nonce, nonce, []byte(plain), nil)
	return passwordPrefix + base64.StdEncoding.EncodeToString(sealed), nil
}

// DecryptWithKey reverses EncryptWithKey (used when importing a backup with the BACKUP's key).
func DecryptWithKey(key []byte, blob string) (string, error) {
	if !strings.HasPrefix(blob, passwordPrefix) {
		return "", errors.New("unknown password format")
	}
	sealed, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(blob, passwordPrefix))
	if err != nil {
		return "", err
	}
	aead, err := newAEAD(key)
	if err != nil {
		return "", err
	}
	if len(sealed) < aead.NonceSize() {
		return "", errors.New("stored password is damaged")
	}
	plain, err := aead.Open(nil, sealed[:aead.NonceSize()], sealed[aead.NonceSize():], nil)
	if err != nil {
		return "", errors.New("stored password cannot be decrypted")
	}
	return string(plain), nil
}

func newAEAD(key []byte) (cipher.AEAD, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// RawPasswordBlob returns the stored v1:… password blob verbatim ("" when none).
// Used by the backup exporter — the blob is already encrypted under this device's key.
func (s *Store) RawPasswordBlob(id string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return "", errBadID
	}
	var file credentialsFile
	data, err := ReadCapped(filepath.Join(s.dir, id, credentialsName), MaxSecretFile)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", nil
		}
		return "", err
	}
	if err := json.Unmarshal(data, &file); err != nil {
		return "", err
	}
	return file.Password, nil
}

// SetRawPasswordBlob overwrites the stored password blob verbatim (used by the backup
// importer with a blob already encrypted under this device's key).
func (s *Store) SetRawPasswordBlob(id string, blob string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !validID(id) {
		return errBadID
	}
	var file credentialsFile
	data, err := ReadCapped(filepath.Join(s.dir, id, credentialsName), MaxSecretFile)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			return err
		}
	} else if err := json.Unmarshal(data, &file); err != nil {
		return err
	}
	file.Password = blob
	out, err := json.MarshalIndent(file, "", "  ")
	if err != nil {
		return err
	}
	return WritePrivate(filepath.Join(s.dir, id, credentialsName), append(out, '\n'))
}
