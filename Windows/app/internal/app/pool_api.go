package app

import (
	"context"
	"fmt"
	"strings"
	"time"

	"rxvpn/internal/accounts"
	"rxvpn/internal/appError"
	"rxvpn/internal/backup"
	"rxvpn/internal/foxy"
)

// PoolAccountView is the panel's row model for one enabled/disabled account (never any secret).
type PoolAccountView struct {
	ID           string `json:"id"`
	Email        string `json:"email"`
	AddedAt      string `json:"added_at,omitempty"`
	Status       string `json:"status"`
	ReasonCode   string `json:"reason_code,omitempty"`
	ReasonDetail string `json:"reason_detail,omitempty"`
	RetryAt      string `json:"retry_at,omitempty"`
	Enabled      bool   `json:"enabled"`
	Active       bool   `json:"active"`
	Usage        any    `json:"usage,omitempty"`
}

// PoolAccounts returns the enabled pool (priority order) plus the disabled ones at the end.
func (a *App) PoolAccounts(_ context.Context, _ Body) (any, error) {
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return map[string]any{"available": false, "accounts": []any{}, "threshold_percent": 95}, nil
	}
	now := time.Now()
	activeEmail := ""
	if active, ok := a.pool.mgr.EffectiveActive(); ok {
		activeEmail = active.Email
	}
	enabled := map[string]bool{}
	out := []PoolAccountView{}
	for _, acc := range a.pool.mgr.List() {
		enabled[acc.ID] = true
		out = append(out, a.poolView(acc, true, activeEmail))
	}
	all, err := a.pool.store.List()
	if err != nil {
		return nil, err
	}
	for _, acc := range all {
		if enabled[acc.ID] {
			continue
		}
		out = append(out, a.poolView(acc, false, activeEmail))
	}
	return map[string]any{
		"available":         true,
		"accounts":          out,
		"threshold_percent": a.pool.mgr.Policy().ThresholdPercent,
		"active_email":      activeEmail,
		"now":               now.UTC().Format(time.RFC3339Nano),
	}, nil
}

func (a *App) poolView(acc accounts.Account, enabled bool, activeEmail string) PoolAccountView {
	v := PoolAccountView{
		ID:           acc.ID,
		Email:        acc.Email,
		AddedAt:      isoOrEmptyLocal(acc.AddedAt),
		Status:       string(acc.Status),
		ReasonCode:   acc.ReasonCode,
		ReasonDetail: acc.ReasonDetail,
		RetryAt:      isoOrEmptyLocal(acc.RetryAt),
		Enabled:      enabled,
		Active:       acc.Email == activeEmail && acc.Email != "",
	}
	if u := acc.Usage; u != nil && u.Known() {
		switch {
		case u.Unlimited:
			v.Usage = map[string]any{"unlimited": true, "checked_at": isoOrEmptyLocal(u.CheckedAt)}
		default:
			used := u.LimitBytes - u.RemainingBytes
			if used < 0 {
				used = 0
			}
			v.Usage = map[string]any{
				"unlimited":       false,
				"limit_bytes":     u.LimitBytes,
				"remaining_bytes": u.RemainingBytes,
				"used_bytes":      used,
				"reset_at":        isoOrEmptyLocal(u.ResetAt),
				"checked_at":      isoOrEmptyLocal(u.CheckedAt),
			}
		}
	}
	return v
}

func isoOrEmptyLocal(t time.Time) string {
	if t.IsZero() {
		return ""
	}
	return t.UTC().Format(time.RFC3339Nano)
}

// PoolActivate marks one account as the one the next connection will use.
func (a *App) PoolActivate(_ context.Context, body Body) (any, error) {
	id := strings.TrimSpace(str(body["id"]))
	if id == "" {
		return nil, appError.New(appError.CodeInvalidConfiguration, appError.SecAccounts, "activate", "missing account id")
	}
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return nil, appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "activate", "account pool unavailable")
	}
	if err := a.pool.mgr.Activate(id); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

// PoolToggle enables/disables an account in the rotation (files and tokens are kept).
func (a *App) PoolToggle(_ context.Context, body Body) (any, error) {
	id := strings.TrimSpace(str(body["id"]))
	enabled, ok := boolOf(body["enabled"])
	if id == "" || !ok {
		return nil, appError.New(appError.CodeInvalidConfiguration, appError.SecAccounts, "toggle", "missing id or enabled")
	}
	a.opMu.Lock()
	defer a.opMu.Unlock()
	a.poolMu.Lock()
	if a.pool == nil {
		a.poolMu.Unlock()
		return nil, appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "toggle", "account pool unavailable")
	}
	pool := a.pool
	if err := pool.mgr.SetEnabled(id, enabled); err != nil {
		a.poolMu.Unlock()
		return nil, err
	}
	noActiveAccounts := !enabled && pool.mgr.Len() == 0
	a.poolMu.Unlock()
	if noActiveAccounts && a.Connected() {
		a.doDisconnect()
	}
	return map[string]any{"ok": true}, nil
}

// PoolMove shifts an account's rotation priority by delta (-1 higher, +1 lower).
func (a *App) PoolMove(_ context.Context, body Body) (any, error) {
	id := strings.TrimSpace(str(body["id"]))
	delta, ok := intOf(body["delta"])
	if id == "" || !ok || (delta != -1 && delta != 1) {
		return nil, appError.New(appError.CodeInvalidConfiguration, appError.SecAccounts, "move", "missing id or delta")
	}
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return nil, appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "move", "account pool unavailable")
	}
	if err := a.pool.mgr.Move(id, delta); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

// PoolRemove deletes the account completely (tokens and password files).
func (a *App) PoolRemove(_ context.Context, body Body) (any, error) {
	id := strings.TrimSpace(str(body["id"]))
	if id == "" {
		return nil, appError.New(appError.CodeInvalidConfiguration, appError.SecAccounts, "remove", "missing account id")
	}
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return nil, appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "remove", "account pool unavailable")
	}
	if err := a.pool.mgr.Remove(id); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

// PoolRefresh fetches the Guardian quota for one account, including disabled accounts.
func (a *App) PoolRefresh(ctx context.Context, body Body) (any, error) {
	id := strings.TrimSpace(str(body["id"]))
	if id == "" {
		return nil, appError.New(appError.CodeInvalidConfiguration, appError.SecAccounts, "refresh", "missing account id")
	}
	a.poolMu.Lock()
	pool := a.pool
	a.poolMu.Unlock()
	if pool == nil {
		return nil, appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "refresh", "account pool unavailable")
	}
	if _, err := pool.store.Get(id); err != nil {
		return nil, err
	}
	if err := a.refreshPoolAccountQuota(ctx, id); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

// PoolRefreshAll fetches the Guardian quota of every enabled account and applies the rotation
// rules (a heavy call — one Guardian round-trip per account; used by the «Refresh all» button).
func (a *App) PoolRefreshAll(ctx context.Context, _ Body) (any, error) {
	a.poolMu.Lock()
	pool := a.pool
	var ids []string
	if pool != nil {
		for _, acc := range pool.mgr.List() {
			ids = append(ids, acc.ID)
		}
	}
	a.poolMu.Unlock()
	if pool == nil {
		return nil, appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "refresh all", "account pool unavailable")
	}
	refreshed, failed := 0, 0
	for _, id := range ids {
		select {
		case <-ctx.Done():
			return map[string]any{"ok": true, "refreshed": refreshed, "failed": failed, "interrupted": true}, nil
		default:
		}
		if err := a.refreshPoolAccountQuota(ctx, id); err != nil {
			failed++
			continue
		}
		refreshed++
	}
	return map[string]any{"ok": true, "refreshed": refreshed, "failed": failed}, nil
}

func (a *App) refreshPoolAccountQuota(ctx context.Context, id string) error {
	rctx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()
	supplier := a.poolSupplier(id)
	token, err := supplier(rctx, false)
	if err != nil {
		return err
	}
	info, err := a.account.FetchQuotaForToken(rctx, token, supplier, false)
	if err != nil {
		return err
	}
	u := accounts.Usage{Unlimited: info.State == foxy.QuotaStateUnlimited}
	switch info.State {
	case foxy.QuotaStateUnlimited:
	case foxy.QuotaStateLimited:
		if info.LimitBytes != nil {
			u.LimitBytes = *info.LimitBytes
		}
		if info.RemainingBytes != nil {
			u.RemainingBytes = *info.RemainingBytes
		}
		if info.ResetAt != "" {
			u.ResetAt = foxy.ParseQuotaReset(info.ResetAt)
		}
	case foxy.QuotaStateUnknown:
	case foxy.QuotaStateError:
		return fmt.Errorf("guardian returned an error state for this account")
	}
	u.CheckedAt = time.Now()
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "refresh", "account pool unavailable")
	}
	_, err = a.pool.mgr.ObserveUsage(id, u)
	return err
}

// BackupExport builds the backup zip (W-10). Handled specially by the HTTP layer because the
// answer is a file download, not JSON.
func (a *App) BackupExport() ([]byte, string, error) {
	a.poolMu.Lock()
	pool := a.pool
	a.poolMu.Unlock()
	if pool == nil {
		return nil, "", appError.New(appError.CodeBackupFailed, appError.SecBackup, "export", "account pool unavailable")
	}
	return backup.Export(pool.store, a.backupVersion, time.Now())
}

// BackupInspectRaw parses an uploaded backup without changing anything (W-11).
func (a *App) BackupInspectRaw(data []byte) (any, error) {
	a.poolMu.Lock()
	pool := a.pool
	a.poolMu.Unlock()
	if pool == nil {
		return nil, appError.New(appError.CodeBackupFailed, appError.SecBackup, "inspect", "account pool unavailable")
	}
	pv, err := backup.Inspect(pool.store, data)
	if err != nil {
		return nil, err
	}
	// show at most 8 accounts in the dialog (W-11); the new/update split is computed on the full list
	newCount, updateCount := 0, 0
	for _, acc := range pv.Accounts {
		if acc.Exists {
			updateCount++
		} else {
			newCount++
		}
	}
	const maxRows = 8
	rows := []map[string]any{}
	for i, acc := range pv.Accounts {
		if i >= maxRows {
			break
		}
		rows = append(rows, map[string]any{
			"email":        acc.Email,
			"has_password": acc.HasPassword,
			"exists":       acc.Exists,
			"usable":       acc.Usable,
		})
	}
	return map[string]any{
		"ok":               true,
		"app":              pv.App,
		"format":           pv.Format,
		"created_at_epoch": pv.CreatedAtEpochSeconds,
		"accounts_count":   len(pv.Accounts),
		"usable_count":     pv.UsableCount(),
		"new_count":        newCount,
		"update_count":     updateCount,
		"accounts":         rows,
	}, nil
}

// BackupImportRaw merges an uploaded backup into the store, all-or-nothing (W-12).
func (a *App) BackupImportRaw(data []byte) (any, error) {
	a.poolMu.Lock()
	pool := a.pool
	a.poolMu.Unlock()
	if pool == nil {
		return nil, appError.New(appError.CodeBackupFailed, appError.SecBackup, "import", "account pool unavailable")
	}
	res, err := backup.Import(pool.store, data)
	if err != nil {
		return nil, err
	}
	// the store changed on disk: rebuild the rotation order (imported accounts are enabled)
	a.poolMu.Lock()
	if a.pool != nil {
		if err := a.pool.mgr.ReloadFromDisk(); err != nil {
			a.poolMu.Unlock()
			return nil, err
		}
	}
	a.poolMu.Unlock()
	// W-27: refresh every usable account's quota right after a successful import, in the
	// background; per-account failures are skipped and never reach the user.
	go func() { _, _ = a.PoolRefreshAll(a.ctx, Body{}) }()
	return map[string]any{
		"ok":                true,
		"added":             res.Added,
		"updated":           res.Updated,
		"skipped_passwords": res.SkippedPasswords,
		"skipped_accounts":  res.SkippedAccounts,
		"created_at_epoch":  res.CreatedAtEpoch,
	}, nil
}

// SetBackupVersion lets the server layer record the build version inside exports.
func (a *App) SetBackupVersion(v string) { a.backupVersion = v }
