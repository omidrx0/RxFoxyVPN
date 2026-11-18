package backup

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"time"

	"rxvpn/internal/accounts"
)

// Inspect parses the backup without changing anything (W-11).
func Inspect(store *accounts.Store, data []byte) (*Preview, error) {
	pb, err := parseBackup(data)
	if err != nil {
		slog.Warn(fmt.Sprintf("backup inspect failed (%T): %v", err, err))
		return nil, err
	}
	if len(pb.accounts) == 0 {
		// structurally fine but nothing usable inside
		return nil, fmt.Errorf("%w: no accounts in this file", ErrBackupEmpty)
	}
	p := &Preview{}
	if pb.info != nil {
		p.App = pb.info.App
		p.Format = pb.info.Format
		p.CreatedAtEpochSeconds = CreatedAtEpochSeconds(pb.info.Created)
	}
	for _, pa := range pb.accounts {
		_, err := store.GetByEmail(pa.email)
		p.Accounts = append(p.Accounts, PreviewAccount{
			Email:       pa.email,
			HasPassword: pa.password != "",
			Exists:      err == nil,
			Usable:      pa.refreshToken != "",
		})
	}
	return p, nil
}

// Import merges the backup into the store — all-or-nothing (W-12).
// The accounts directory is snapshotted first; on any failure it is restored byte-for-byte.
func Import(store *accounts.Store, data []byte) (*ImportResult, error) {
	pb, err := parseBackup(data)
	if err != nil {
		return nil, err
	}
	usable := pb.accounts // parseBackup already dropped refresh-token-less / credential-less entries
	if len(usable) == 0 {
		return nil, fmt.Errorf("%w: no accounts in this file", ErrBackupEmpty)
	}

	// Decrypt the export's passwords under the BACKUP's key (if any); re-encrypt
	// under THIS device's key during the merge. The backup key never touches disk.
	deviceKey, err := store.SecretKey()
	if err != nil {
		return nil, err
	}
	type plainPass struct{ folderID, plain string }
	var passwords []plainPass
	var skippedPasswords int
	for _, pa := range usable {
		if pa.password == "" {
			continue
		}
		if len(pb.secretKey) != 32 {
			skippedPasswords++
			continue
		}
		plain, err := accounts.DecryptWithKey(pb.secretKey, pa.password)
		if err != nil {
			skippedPasswords++
			continue
		}
		passwords = append(passwords, plainPass{pa.folderID, plain})
	}
	defer func() {
		for i := range passwords {
			p := []byte(passwords[i].plain)
			for j := range p {
				p[j] = 0
			}
			passwords[i].plain = ""
		}
	}()

	// ---- all-or-nothing write phase --------------------------------------
	snap, err := snapshotDir(store.Dir())
	if err != nil {
		return nil, fmt.Errorf("cannot snapshot the account store: %w", err)
	}
	committed := false
	defer func() {
		if !committed {
			_ = restoreDir(store.Dir(), snap)
		}
		_ = os.RemoveAll(snap)
	}()

	now := time.Now()
	res := &ImportResult{SkippedPasswords: skippedPasswords, SkippedAccounts: pb.skippedAssoc}
	if pb.info != nil {
		res.CreatedAtEpoch = CreatedAtEpochSeconds(pb.info.Created)
	}
	byFolder := map[string]string{} // folderID → device password blob after re-encryption
	for _, pp := range passwords {
		blob, err := accounts.EncryptWithKey(deviceKey, pp.plain)
		if err == nil {
			byFolder[pp.folderID] = blob
		} else {
			res.SkippedPasswords++
		}
	}

	for _, pa := range usable {
		existing, err := store.GetByEmail(pa.email)
		if err != nil {
			return nil, err
		}
		expiresIn := int64(0)
		expiresAt := pa.expiresAt
		if expiresAt > 0 && expiresAt > now.Unix() {
			expiresIn = expiresAt - now.Unix()
		}
		creds := &accounts.SavedTokens{
			AccessToken:  pa.accessToken,
			RefreshToken: pa.refreshToken,
			Scope:        pa.scope,
			ClientID:     "", // unknown for foreign accounts; filled on next sign-in
			ObtainedAt:   now.UTC().Format(time.RFC3339Nano),
			ExpiresIn:    expiresIn,
			ExpiresAt:    expiresAt,
		}
		acc, created, err := store.Add(pa.email, creds, now)
		if err != nil {
			return nil, err
		}
		if created {
			res.Added++
		} else {
			res.Updated++
			// keep the old device pairing unless the backup carries a newer one
			if pa.deviceID == "" {
				pa.deviceID = existing.DeviceID
			}
			if pa.deviceToken == "" {
				pa.deviceToken = existing.DeviceToken
			}
		}

		final := acc
		final.Status = accounts.StatusReady
		final.ReasonCode = ""
		final.ReasonDetail = ""
		final.RetryAt = time.Time{}
		if created && !pa.addedAt.IsZero() {
			final.AddedAt = pa.addedAt
		}
		// restore the backed-up quota reading only when the backup carries a real one;
		// an absent/unlimited reading must not erase known limits
		if pa.usage != nil && (pa.usage.Unlimited || pa.usage.LimitBytes > 0) {
			final.Usage = pa.usage
		} else if existing != nil {
			final.Usage = existing.Usage
		}
		final.DeviceID = pa.deviceID
		final.DeviceToken = pa.deviceToken
		if err := store.SaveState(final); err != nil {
			return nil, err
		}

		// password blob: the file now holds a stale devices-key blob; overwrite with
		// the backup-derived one, if we have it
		if blob := byFolder[pa.folderID]; blob != "" {
			if err := store.SetRawPasswordBlob(acc.ID, blob); err != nil {
				return nil, err
			}
		}
	}
	committed = true
	slog.Info(fmt.Sprintf("backup imported: %d added, %d updated, %d passwords skipped", res.Added, res.Updated, res.SkippedPasswords))
	return res, nil
}

// ---------------------------------------------------------------------------
// Snapshot / restore (rollback)
// ---------------------------------------------------------------------------

func snapshotDir(dir string) (string, error) {
	snap, err := os.MkdirTemp("", "rxfoxy-backup-snapshot-*")
	if err != nil {
		return "", err
	}
	if err := copyDir(dir, snap); err != nil {
		_ = os.RemoveAll(snap)
		return "", err
	}
	return snap, nil
}

func copyDir(src, dst string) error {
	return filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o700)
		}
		if !info.Mode().IsRegular() {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		return os.WriteFile(target, data, 0o600)
	})
}

func restoreDir(dir, snap string) error {
	// remove everything in dir, then copy the snapshot back
	entries, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	for _, e := range entries {
		if err := os.RemoveAll(filepath.Join(dir, e.Name())); err != nil {
			return err
		}
	}
	return copyDir(snap, dir)
}
