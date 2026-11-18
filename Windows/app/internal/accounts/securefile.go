package accounts

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// MaxSecretFile caps any file we read from the account store / a backup zip.
const MaxSecretFile = 1 << 20 // 1 MiB

// ErrTooLarge is returned when a file exceeds its read cap.
var ErrTooLarge = errors.New("file is too large")

// ReadCapped reads at most limit bytes; the error wraps ErrTooLarge when the file is bigger.
func ReadCapped(path string, limit int64) ([]byte, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > limit {
		return nil, fmt.Errorf("%w: %s", ErrTooLarge, filepath.Base(path))
	}
	return data, nil
}

// WritePrivate writes data atomically with owner-only permissions (0600).
func WritePrivate(path string, data []byte) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".tmp-*.part")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	if err := tmp.Chmod(0o600); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmpName)
		return err
	}
	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmpName)
		return err
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		_ = os.Remove(tmpName)
		return err
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpName)
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		_ = os.Remove(tmpName)
		return err
	}
	return nil
}

// EnsurePrivateDir creates dir (and its parents) with owner-only permissions (0700).
func EnsurePrivateDir(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	// tighten in case the dir pre-existed with a wider mask
	return os.Chmod(dir, 0o700)
}
