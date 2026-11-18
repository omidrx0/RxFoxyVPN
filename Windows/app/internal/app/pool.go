package app

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"time"

	"rxvpn/internal/appError"

	"rxvpn/internal/accounts"
	"rxvpn/internal/config"
	"rxvpn/internal/foxy"
	"rxvpn/internal/logx"
	"rxvpn/internal/platform"
)

// poolState groups the multi-account store and its rotation manager.
type poolState struct {
	store *accounts.Store
	mgr   *accounts.Manager
}

// openPool builds the account pool on startup: it migrates a legacy single-account session
// into the pool (W-04) once, opens the rotation manager and resumes due reservations.
// Failure to build the pool is degraded, never fatal: it logs and the app keeps working
// against the legacy session until the next launch.
func (a *App) openPool() {
	store, err := accounts.OpenStore(config.DataDir())
	if err != nil {
		slog.Warn("account pool unavailable", "err", err)
		return
	}
	now := time.Now()
	migrated, err := accounts.MigrateLegacyIfNeeded(config.DataDir(), config.StatePath(), config.SavedAccountsPath(), platform.DPAPI{}, now)
	if err != nil {
		slog.Warn("legacy account migration failed", "err", err)
	}
	mgr, err := accounts.NewManager(store, accounts.Policy{ThresholdPercent: 95}, config.PoolStatePath(), config.PoolOrderPath())
	if err != nil {
		slog.Warn("account pool manager unavailable", "err", err)
		return
	}
	if err := mgr.ResumeAll(); err != nil {
		slog.Warn("could not resume due accounts", "err", err)
	}
	accounts.Warn = func(msg string) { slog.Warn("account store", "msg", msg) }
	a.poolMu.Lock()
	a.pool = &poolState{store: store, mgr: mgr}
	a.poolMu.Unlock()
	if migrated {
		// the migrated account is the only one; make it active
		if list, err := store.List(); err == nil && len(list) == 1 {
			_ = mgr.Activate(list[0].ID)
		}
		slog.Info("legacy account migrated into the pool")
	}
}

// applyPoolPolicy pushes the configured rotation threshold (W-32: Settings → «Switch threshold»)
// into the pool manager. Out-of-range values fall back to the factory 95.
func (a *App) applyPoolPolicy(pct int) {
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return
	}
	a.pool.mgr.SetPolicy(accounts.Policy{ThresholdPercent: float64(pct)})
}

// poolActive returns the pool's effective active account.
func (a *App) poolActive() (accounts.Account, bool) {
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return accounts.Account{}, false
	}
	return a.pool.mgr.EffectiveActive()
}

// PoolSummary feeds the panel (/api/status) with a secret-free snapshot of the pool.
type PoolSummary struct {
	Available      bool   `json:"available"`
	ActiveAccount  string `json:"active_account,omitempty"`
	Total          int    `json:"total"`
	Ready          int    `json:"ready"`
	Reserved       int    `json:"reserved"`
	ReauthRequired int    `json:"reauth_required"`
	Unavailable    int    `json:"unavailable"`
}

// PoolSummaryOf reports the pool state without leaking any secret.
func (a *App) PoolSummaryOf() PoolSummary {
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return PoolSummary{}
	}
	s := PoolSummary{Available: true}
	if active, ok := a.pool.mgr.EffectiveActive(); ok {
		s.ActiveAccount = active.Email
	}
	list, err := a.pool.store.List()
	if err != nil {
		return s
	}
	s.Total = len(list)
	for _, acc := range list {
		if acc.Disabled {
			continue
		}
		switch acc.Status {
		case accounts.StatusReady:
			s.Ready++
		case accounts.StatusReserved:
			s.Reserved++
		case accounts.StatusReauthRequired:
			s.ReauthRequired++
		case accounts.StatusUnavailable:
			s.Unavailable++
		}
	}
	return s
}

// poolSupplier yields a Guardian-usable access token for one pool account,
// refreshing through FxA when the stored token is stale and persisting the result.
func (a *App) poolSupplier(id string) foxy.AccessTokenSupplier {
	return func(ctx context.Context, force bool) (string, error) {
		a.poolMu.Lock()
		pool := a.pool
		a.poolMu.Unlock()
		if pool == nil {
			return "", errors.New("no usable account in the pool")
		}
		creds, err := pool.store.Credentials(id)
		if err != nil {
			return "", err
		}
		if !force && creds.AccessFresh(time.Now()) {
			return creds.AccessToken, nil
		}
		if creds.RefreshToken == "" {
			return "", &foxy.NeedLoginError{Msg: "no refresh token for this pool account"}
		}
		data, err := a.account.Fxa.RefreshWithClient(ctx, creds.RefreshToken, creds.ClientID)
		if err != nil {
			var fe *foxy.FxaAPIError
			if errors.As(err, &fe) && (fe.Status == 400 || fe.Status == 401 || fe.Status == 403) {
				// the pool account's session is dead: flag it once and let the caller reauth/fail over
				_ = pool.mgr.MarkReauthRequired(id, "fxa rejected the refresh token")
				return "", &foxy.NeedLoginError{Msg: "Firefox rejected the stored session"}
			}
			return "", err
		}
		merged := *creds
		merged.AccessToken = data.AccessToken
		if data.RefreshToken != "" {
			merged.RefreshToken = data.RefreshToken
		}
		if data.ClientID != "" {
			merged.ClientID = data.ClientID
		}
		merged.ObtainedAt = time.Now().UTC().Format(time.RFC3339Nano)
		merged.ExpiresIn = int64(data.ExpiresIn)
		merged.ExpiresAt = time.Now().Unix() + int64(data.ExpiresIn)
		if err := pool.store.UpdateTokens(id, &merged); err != nil {
			slog.Warn("could not persist the refreshed pool token", "err", err)
		}
		return merged.AccessToken, nil
	}
}

// mintFailover is consulted once per failed pass mint. It marks the failing pool account and
// switches the connection to the next candidate exactly once (the caller then re-mints).
// W-08 (429 → reserve+next) and W-09 (401 → reauth+next; 403 → not entitled, +1h, next).
func (a *App) mintFailover(ts *foxy.TokenSource) func(ctx context.Context, err error) bool {
	return func(_ context.Context, err error) bool {
		a.poolMu.Lock()
		defer a.poolMu.Unlock()
		if a.pool == nil {
			return false
		}
		active, ok := a.pool.mgr.EffectiveActive()
		if !ok {
			return false
		}
		now := time.Now()
		switch {
		case func() bool {
			var qe *foxy.QuotaExceededError
			return errors.As(err, &qe)
		}():
			var qe *foxy.QuotaExceededError
			_ = errors.As(err, &qe)
			retryAt := qe.RetryAt()
			if retryAt.IsZero() {
				retryAt = now.Add(time.Hour)
			}
			if err := a.pool.mgr.Reserve(active.ID, accounts.ReasonQuotaExhausted, retryAt); err != nil {
				slog.Warn("pool reserve failed", "err", err)
			}
			slog.Info("pool account quota exhausted; rotating", "account", logx.ID(active.ID))
		case func() bool {
			var tie *foxy.TokenInvalidError
			return errors.As(err, &tie)
		}():
			var tie *foxy.TokenInvalidError
			_ = errors.As(err, &tie)
			if tie.Status == 403 {
				_ = a.pool.mgr.MarkUnavailable(active.ID, accounts.ReasonNotEntitled, "guardian 403", now.Add(time.Hour))
			} else {
				_ = a.pool.mgr.MarkReauthRequired(active.ID, "guardian rejected the token")
			}
			slog.Info("pool account rejected by guardian; rotating", "account", logx.ID(active.ID), "status", tie.Status)
		default:
			var needLogin *foxy.NeedLoginError
			if errors.As(err, &needLogin) {
				_ = a.pool.mgr.MarkReauthRequired(active.ID, "the session is no longer valid")
				slog.Info("pool account session dead; rotating", "account", logx.ID(active.ID))
			} else {
				return false
			}
		}
		cand, err := a.pool.mgr.Candidate(map[string]bool{active.ID: true})
		if err != nil {
			slog.Warn("no usable pool account left after failover", "err", err)
			return false
		}
		if err := a.pool.mgr.Activate(cand.ID); err != nil {
			return false
		}
		if ts != nil {
			ts.SetAccessTokenSupplier(a.poolSupplier(cand.ID))
		}
		return true
	}
}

// newTokenSource builds the token source for a new Upstream: pool mode when the pool has an
// active account, legacy state.json otherwise. The W-07 gate lives in doConnect; this only wires.
func (a *App) newTokenSource() *foxy.TokenSource {
	ts := foxy.NewTokenSource(a.account)
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return ts
	}
	active, ok := a.pool.mgr.EffectiveActive()
	if !ok {
		return ts
	}
	if err := a.pool.mgr.Activate(active.ID); err != nil {
		slog.Warn("could not persist the active pool account", "err", err)
	}
	ts.SetAccessTokenSupplier(a.poolSupplier(active.ID))
	ts.SetMintFailover(a.mintFailover(ts))
	return ts
}

// syncPoolAccountAfterLogin upserts the freshly-signed-in account into the pool (W-15/W-16).
func (a *App) syncPoolAccountAfterLogin(email string) {
	snap := a.account.SessionSnapshot()
	if snap.RefreshToken == "" {
		return
	}
	clean := foxy.NormalizeEmail(email)
	if clean == "" {
		clean = foxy.NormalizeEmail(snap.Email)
	}
	if clean == "" {
		return
	}
	creds := &accounts.SavedTokens{
		AccessToken:  snap.AccessToken,
		RefreshToken: snap.RefreshToken,
		ClientID:     snap.ClientID,
		ObtainedAt:   time.Now().UTC().Format(time.RFC3339Nano),
		ExpiresAt:    snap.ExpiresAt,
	}
	if creds.ExpiresAt > time.Now().Unix() {
		creds.ExpiresIn = creds.ExpiresAt - time.Now().Unix()
	}
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	if a.pool == nil {
		return
	}
	acc, _, err := a.pool.store.Add(clean, creds, time.Now())
	if err != nil {
		slog.Warn("could not add the signed-in account to the pool", "err", err)
		return
	}
	if err := a.pool.mgr.SetEnabled(acc.ID, true); err != nil {
		slog.Warn("could not enable the signed-in pool account", "err", err)
		return
	}
	// re-bind a remembered password (DPAPI) into the pool store, when there is one
	if pw, err := a.accounts.Password(clean); err == nil && pw != "" {
		if err := a.pool.store.SetPassword(acc.ID, pw); err != nil {
			slog.Warn("could not bind the remembered password to the pool account", "err", err)
		}
	}
	if err := a.pool.mgr.Activate(acc.ID); err != nil {
		slog.Warn("could not activate the signed-in pool account", "err", err)
	}
	go a.observePoolQuota(acc.ID)
}

// observePoolQuota fetches the Guardian quota for a pool account and applies the rotation rules.
func (a *App) observePoolQuota(id string) {
	ctx, cancel := context.WithTimeout(a.ctx, 45*time.Second)
	defer cancel()

	token, err := a.poolSupplier(id)(ctx, false)
	if err != nil {
		slog.Info("pool quota check skipped", "err", err)
		return
	}
	info, err := a.account.FetchQuotaForToken(ctx, token, func(c context.Context, force bool) (string, error) {
		return a.poolSupplier(id)(c, force)
	}, false)
	if err != nil {
		logPoolQuotaError(id, err)
		return
	}
	u := accounts.Usage{Unlimited: info.State == foxy.QuotaStateUnlimited}
	switch info.State {
	case foxy.QuotaStateUnlimited:
		// unlimited: nothing else to store
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
		// leave the usage unknown; only CheckedAt moves
	case foxy.QuotaStateError:
		return
	}
	u.CheckedAt = time.Now()
	a.poolMu.Lock()
	if a.pool != nil {
		if _, err := a.pool.mgr.ObserveUsage(id, u); err != nil {
			slog.Warn("pool usage observation failed", "err", err)
		}
	}
	a.poolMu.Unlock()
}

func logPoolQuotaError(id string, err error) {
	slog.Info("pool quota check failed", "err", err, "account", logx.ID(id))
}

// poolAccountForOauthEmail finds the pool account bound to an FxA email (used by logout flows).
func (a *App) poolStore() *poolState {
	a.poolMu.Lock()
	defer a.poolMu.Unlock()
	return a.pool
}

// w07Gate enforces بند 9 at the front door of connect: (1) an active pool account, or a
// legacy session as a transitional fallback; (2) a chosen server. It returns a typed error
// the UI renders from the errors.* catalog — never a raw string.
func (a *App) w07Gate(code string) error {
	if strings.TrimSpace(code) == "" {
		return appError.New(appError.CodeNoServerSelected, appError.SecServers, "connect", "no server selected")
	}
	a.poolMu.Lock()
	pool := a.pool
	if pool != nil {
		if _, ok := pool.mgr.EffectiveActive(); ok {
			a.poolMu.Unlock()
			return nil
		}
		accounts, err := pool.store.List()
		a.poolMu.Unlock()
		if err != nil {
			return err
		}
		if len(accounts) > 0 {
			return appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "connect", "no active account")
		}
	} else {
		a.poolMu.Unlock()
	}
	if a.account.HasSession() {
		// transitional fallback: a legacy session with no pool (pool unavailable/migration skipped)
		return nil
	}
	return appError.New(appError.CodeNoActiveAccount, appError.SecAccounts, "connect", "no active account")
}
