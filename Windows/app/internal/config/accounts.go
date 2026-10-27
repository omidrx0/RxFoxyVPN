package config

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"sort"
	"strings"
	"sync"
)

// Cipher protects saved passwords. On Windows it is DPAPI (bound to the current user).
type Cipher interface {
	Protect(plain []byte) ([]byte, error)
	Unprotect(sealed []byte) ([]byte, error)
}

// ErrNoSavedAccount is returned when the requested e-mail has no saved credentials.
var ErrNoSavedAccount = errors.New("no saved credentials for this account")

type savedEntry struct {
	Email    string `json:"email"`
	Password string `json:"password"` // base64 of the protected bytes
}

// AccountStore keeps the "remember me" accounts in saved_accounts.json.
type AccountStore struct {
	path   string
	cipher Cipher
	mu     sync.Mutex
}

// NewAccountStore opens (lazily) the store at path.
func NewAccountStore(path string, cipher Cipher) *AccountStore {
	return &AccountStore{path: path, cipher: cipher}
}

func key(email string) string { return strings.ToLower(strings.TrimSpace(email)) }

func (s *AccountStore) read() map[string]savedEntry {
	out := map[string]savedEntry{}
	raw, err := os.ReadFile(s.path)
	if err != nil {
		return out
	}
	var m map[string]savedEntry
	if json.Unmarshal(raw, &m) != nil {
		return out
	}
	for k, v := range m {
		out[k] = v
	}
	return out
}

func (s *AccountStore) write(accounts map[string]savedEntry) error {
	data, err := MarshalJSON(accounts, false)
	if err != nil {
		return err
	}
	return WriteFileAtomic(s.path, data, 0o600)
}

// Save stores (or replaces) the credentials of one account.
func (s *AccountStore) Save(email, password string) error {
	email = strings.TrimSpace(email)
	if email == "" || password == "" {
		return errors.New("an email and password are required")
	}
	sealed, err := s.cipher.Protect([]byte(password))
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	accounts := s.read()
	accounts[key(email)] = savedEntry{Email: email, Password: base64.StdEncoding.EncodeToString(sealed)}
	return s.write(accounts)
}

// Password returns the decrypted password of a saved account.
func (s *AccountStore) Password(email string) (string, error) {
	s.mu.Lock()
	entry, ok := s.read()[key(email)]
	s.mu.Unlock()
	if !ok || entry.Password == "" {
		return "", ErrNoSavedAccount
	}
	sealed, err := base64.StdEncoding.DecodeString(entry.Password)
	if err != nil {
		return "", err
	}
	plain, err := s.cipher.Unprotect(sealed)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

// List returns the saved e-mail addresses, sorted case-insensitively.
func (s *AccountStore) List() []string {
	s.mu.Lock()
	accounts := s.read()
	s.mu.Unlock()
	return listEmails(accounts)
}

func listEmails(accounts map[string]savedEntry) []string {
	out := []string{}
	for _, e := range accounts {
		if e.Email != "" {
			out = append(out, e.Email)
		}
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i]) < strings.ToLower(out[j]) })
	return out
}

// Remove forgets one account and returns the remaining list.
func (s *AccountStore) Remove(email string) ([]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	accounts := s.read()
	delete(accounts, key(email))
	if err := s.write(accounts); err != nil {
		return nil, err
	}
	return listEmails(accounts), nil
}
