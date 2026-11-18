package accounts

import (
	"encoding/json"
	"errors"
	"os"
	"sort"
	"time"
)

// Policy is the rotation threshold policy. When an account's used fraction reaches
// ThresholdPercent of its monthly quota, it is set aside until the quota resets.
type Policy struct {
	ThresholdPercent float64
}

const unknownResetRetry = time.Hour

// ErrNoCandidate is returned when no enabled account can currently take over.
var ErrNoCandidate = errors.New("no usable account")

// ErrNotFound is returned when an id is not in the enabled pool (disabled or unknown).
var ErrNotFound = errors.New("account is not in the enabled pool")

// ErrTooEarly is returned when an account is asked to activate before its retry time.
var ErrTooEarly = errors.New("account is not ready yet")

// Manager keeps the in-memory view of the ENABLED accounts, their order, and the policy.
// The one and only pool manager of the process (W-26). All persistence is via the Store.
type Manager struct {
	store     *Store
	accounts  map[string]*Account
	order     []string
	policy    Policy
	statePath string
	orderPath string
	now       func() time.Time
}

// NewManager loads the enabled pool (order file + per-account disabled flags) and the persisted active id.
// orderPath may be empty in tests — then the order is simply "all enabled accounts, added order".
func NewManager(store *Store, policy Policy, statePath, orderPath string) (*Manager, error) {
	if policy.ThresholdPercent <= 0 || policy.ThresholdPercent > 100 {
		policy.ThresholdPercent = 95
	}
	m := &Manager{store: store, accounts: map[string]*Account{}, order: nil, policy: policy, statePath: statePath, orderPath: orderPath, now: time.Now}
	order, err := LoadOrder(store, orderPath)
	if err != nil {
		return nil, err
	}
	if err := m.Reload(order); err != nil {
		return nil, err
	}
	return m, nil
}

// Policy returns the active policy.
func (m *Manager) Policy() Policy { return m.policy }

// SetPolicy swaps the threshold policy (existing reservations are kept).
func (m *Manager) SetPolicy(p Policy) {
	if p.ThresholdPercent <= 0 || p.ThresholdPercent > 100 {
		p.ThresholdPercent = 95
	}
	m.policy = p
}

// Len reports how many enabled accounts the pool holds.
func (m *Manager) Len() int { return len(m.accounts) }

// List returns the enabled accounts in their current priority order.
func (m *Manager) List() []Account {
	out := make([]Account, 0, len(m.order))
	for _, id := range m.order {
		if acc, ok := m.accounts[id]; ok {
			out = append(out, *acc)
		}
	}
	return out
}

// LoadOrder computes the enabled pool: every account with Disabled==false, in saved order,
// with accounts that have no saved position appended by AddedAt. Disabled accounts are never
// in the result — their position is forgotten until they are re-enabled.
func LoadOrder(store *Store, path string) ([]string, error) {
	list, err := store.List()
	if err != nil {
		return nil, err
	}
	enabled := []Account{}
	have := map[string]Account{}
	for _, acc := range list {
		if acc.Disabled {
			continue
		}
		enabled = append(enabled, acc)
		have[acc.ID] = acc
	}
	order := []string{}
	if path != "" {
		if raw, err := os.ReadFile(path); err == nil {
			_ = json.Unmarshal(raw, &order)
		} else if !os.IsNotExist(err) {
			return nil, err
		}
	}
	seen := map[string]bool{}
	clean := []string{}
	for _, id := range order {
		if _, ok := have[id]; ok && !seen[id] {
			seen[id] = true
			clean = append(clean, id)
		}
	}
	rest := []Account{}
	for _, acc := range enabled {
		if !seen[acc.ID] {
			rest = append(rest, acc)
		}
	}
	sort.Slice(rest, func(i, j int) bool {
		if rest[i].AddedAt.Equal(rest[j].AddedAt) {
			return rest[i].ID < rest[j].ID
		}
		return rest[i].AddedAt.Before(rest[j].AddedAt)
	})
	for _, acc := range rest {
		clean = append(clean, acc.ID)
	}
	return clean, nil
}

// SaveOrder persists an enable-list.
func SaveOrder(path string, order []string) error {
	data, err := json.MarshalIndent(order, "", "  ")
	if err != nil {
		return err
	}
	return WritePrivate(path, append(data, '\n'))
}

// Reload (re)builds the pool from an enable-list (already filtered to enabled accounts).
func (m *Manager) Reload(order []string) error {
	list, err := m.store.List()
	if err != nil {
		return err
	}
	have := map[string]Account{}
	for _, acc := range list {
		have[acc.ID] = acc
	}
	m.accounts = map[string]*Account{}
	m.order = []string{}
	for _, id := range order {
		acc, ok := have[id]
		if !ok || acc.Disabled {
			continue
		}
		cpy := acc
		m.accounts[id] = &cpy
		m.order = append(m.order, id)
	}
	return nil
}

// SetEnabled toggles an account in/out of the rotation (the files stay; tokens survive).
// It persists the per-account flag, then re-derives and saves the order and reloads the pool.
func (m *Manager) SetEnabled(id string, enabled bool) error {
	acc, err := m.store.Get(id)
	if err != nil {
		return err
	}
	acc.Disabled = !enabled
	if err := m.store.SaveState(*acc); err != nil {
		return err
	}
	return m.reloadFromDisk()
}

// Move shifts an account's rotation priority by delta (-1 higher, +1 lower).
func (m *Manager) Move(id string, delta int) error {
	order, err := LoadOrder(m.store, m.orderPath)
	if err != nil {
		return err
	}
	idx := -1
	for i, v := range order {
		if v == id {
			idx = i
			break
		}
	}
	if idx < 0 {
		return ErrNotFound
	}
	j := idx + delta
	if j < 0 || j >= len(order) {
		return nil // already at the edge: not an error
	}
	order[idx], order[j] = order[j], order[idx]
	if err := SaveOrder(m.orderPath, order); err != nil {
		return err
	}
	return m.Reload(order)
}

// ReloadFromDisk re-derives the enabled pool from the store and the order file.
// Exported for the backup import / account removal paths, which change the store on disk.
func (m *Manager) ReloadFromDisk() error {
	return m.reloadFromDisk()
}

// Remove deletes the account from disk and reloads the pool.
func (m *Manager) Remove(id string) error {
	if err := m.store.Remove(id); err != nil {
		return err
	}
	return m.reloadFromDisk()
}

// reloadFromDisk re-derives the order from the store+order file and reloads the pool.
func (m *Manager) reloadFromDisk() error {
	order, err := LoadOrder(m.store, m.orderPath)
	if err != nil {
		return err
	}
	if m.orderPath != "" {
		if err := SaveOrder(m.orderPath, order); err != nil {
			return err
		}
	}
	return m.Reload(order)
}

// readState loads the persisted active id (empty when unset/corrupt).
func (m *Manager) readState() string {
	raw, err := os.ReadFile(m.statePath)
	if err != nil {
		return ""
	}
	var st struct {
		ActiveAccount string `json:"active_account"`
	}
	if err := json.Unmarshal(raw, &st); err != nil {
		return ""
	}
	return st.ActiveAccount
}

func (m *Manager) writeState(id string) error {
	data, err := json.MarshalIndent(map[string]any{"active_account": id}, "", "  ")
	if err != nil {
		return err
	}
	return WritePrivate(m.statePath, append(data, '\n'))
}

// EffectiveActive resolves the persisted active id: stored if still enabled, else first ready, else first enabled.
func (m *Manager) EffectiveActive() (Account, bool) {
	id := m.readState()
	if id != "" {
		if acc, ok := m.accounts[id]; ok {
			return *acc, true
		}
	}
	for _, id := range m.order {
		if acc := m.accounts[id]; acc != nil && acc.Status == StatusReady && !ThresholdReached(acc.Usage, m.policy) {
			return *acc, true
		}
	}
	for _, id := range m.order {
		if acc := m.accounts[id]; acc != nil {
			return *acc, true
		}
	}
	return Account{}, false
}

// Active returns the persisted active account if it is still in the enabled pool.
func (m *Manager) Active() (Account, bool) {
	id := m.readState()
	if id == "" {
		return Account{}, false
	}
	acc, ok := m.accounts[id]
	if !ok {
		return Account{}, false
	}
	return *acc, true
}

// Activate persists the active account id (empty clears it).
func (m *Manager) Activate(id string) error {
	if id != "" {
		if _, ok := m.accounts[id]; !ok {
			return ErrNotFound
		}
	}
	return m.writeState(id)
}

// ThresholdReached reports whether the reading is at/past the rotation threshold.
func (m *Manager) ThresholdReached(u *Usage) bool {
	return ThresholdReached(u, m.policy)
}

// ThresholdReached reports whether u is at/past the policy threshold.
func ThresholdReached(u *Usage, policy Policy) bool {
	if u == nil || !u.Known() || u.Unlimited {
		return false
	}
	used := u.LimitBytes - u.RemainingBytes
	if used < 0 {
		used = 0
	}
	return float64(used)*100 >= float64(u.LimitBytes)*policy.ThresholdPercent
}

// AfterReset promotes a reserved account whose retry time has passed.
func (m *Manager) AfterReset(acc Account, now time.Time) (Account, bool) {
	if acc.Status == StatusReserved && !acc.RetryAt.IsZero() && !now.Before(acc.RetryAt) {
		acc.Status = StatusReady
		acc.ReasonCode = ""
		acc.ReasonDetail = ""
		acc.RetryAt = time.Time{}
		return acc, true
	}
	return acc, false
}

// ResumeAll promotes reserved accounts whose retry passed (called at startup and post-import).
func (m *Manager) ResumeAll() error {
	now := m.now()
	for id, acc := range m.accounts {
		if next, ok := m.AfterReset(*acc, now); ok {
			if err := m.store.SaveState(next); err != nil {
				return err
			}
			cpy := next
			m.accounts[id] = &cpy
		}
	}
	return nil
}

// Candidate returns the first enabled account that is ready and below threshold.
func (m *Manager) Candidate(exclude map[string]bool) (Account, error) {
	for _, id := range m.order {
		if exclude != nil && exclude[id] {
			continue
		}
		acc, ok := m.accounts[id]
		if !ok {
			continue
		}
		if acc.Status == StatusReady && !ThresholdReached(acc.Usage, m.policy) {
			return *acc, nil
		}
	}
	return Account{}, ErrNoCandidate
}

// ObserveUsage stores a fresh reading and applies the threshold rules.
// It reports whether this call moved the account INTO reserved.
// An unknown reading (not unlimited, no limit) updates only CheckedAt and never flips status.
func (m *Manager) ObserveUsage(id string, u Usage) (bool, error) {
	acc, ok := m.accounts[id]
	if !ok {
		// disabled account: just persist the reading
		return false, m.store.SaveUsage(id, u)
	}
	now := m.now()
	u.CheckedAt = now
	if !u.Known() {
		// keep the previous numbers; only the check time moves (guide: unknown never overwrites)
		cpy := *acc
		if cpy.Usage != nil {
			prev := *cpy.Usage
			prev.CheckedAt = now
			cpy.Usage = &prev
		}
		if err := m.store.SaveState(cpy); err != nil {
			return false, err
		}
		m.accounts[id] = &cpy
		return false, nil
	}
	cpy := *acc
	cpy.Usage = &u
	reserved := false
	if cpy.Status == StatusReserved {
		if !ThresholdReached(cpy.Usage, m.policy) {
			// server reports fresh quota (reset passed or limit raised) → ready again
			cpy.Status = StatusReady
			cpy.ReasonCode = ""
			cpy.ReasonDetail = ""
			cpy.RetryAt = time.Time{}
		}
	} else if ThresholdReached(cpy.Usage, m.policy) && cpy.Status != StatusReauthRequired {
		cpy.Status = StatusReserved
		cpy.ReasonCode = ReasonQuotaExhausted
		cpy.ReasonDetail = ""
		if u.ResetAt.IsZero() {
			cpy.RetryAt = now.Add(unknownResetRetry)
		} else {
			cpy.RetryAt = u.ResetAt
		}
		reserved = true
	}
	if err := m.store.SaveState(cpy); err != nil {
		return false, err
	}
	m.accounts[id] = &cpy
	return reserved, nil
}

// Reserve parks an account until its retry time with a reason (quota exhausted / 429).
func (m *Manager) Reserve(id string, reasonCode string, retryAt time.Time) error {
	cpy, err := m.enabled(id)
	if err != nil {
		return err
	}
	if retryAt.IsZero() {
		retryAt = m.now().Add(unknownResetRetry)
	}
	cpy.Status = StatusReserved
	cpy.ReasonCode = reasonCode
	cpy.RetryAt = retryAt
	data := cpy
	if err := m.store.SaveState(data); err != nil {
		return err
	}
	cp := data
	m.accounts[id] = &cp
	return nil
}

// MarkReauthRequired flags an account whose tokens were rejected (401). It never
// comes back on its own — only a fresh login revives it (W-09).
func (m *Manager) MarkReauthRequired(id string, reasonDetail string) error {
	return m.setStatus(id, StatusReauthRequired, ReasonSignInRequired, reasonDetail, time.Time{})
}

// MarkUnavailable flags an account that cannot be used right now (403 not entitled / 451).
func (m *Manager) MarkUnavailable(id string, reasonCode string, reasonDetail string, retryAt time.Time) error {
	return m.setStatus(id, StatusUnavailable, reasonCode, reasonDetail, retryAt)
}

func (m *Manager) enabled(id string) (Account, error) {
	if acc, ok := m.accounts[id]; ok {
		return *acc, nil
	}
	// allow operating on a disabled account too (import path needs it)
	list, err := m.store.List()
	if err != nil {
		return Account{}, err
	}
	for _, acc := range list {
		if acc.ID == id {
			return acc, nil
		}
	}
	return Account{}, ErrNotFound
}

func (m *Manager) setStatus(id string, status Status, reasonCode, reasonDetail string, retryAt time.Time) error {
	cpy, err := m.enabled(id)
	if err != nil {
		return err
	}
	cpy.Status = status
	cpy.ReasonCode = reasonCode
	cpy.ReasonDetail = reasonDetail
	cpy.RetryAt = retryAt
	if err := m.store.SaveState(cpy); err != nil {
		return err
	}
	if _, ok := m.accounts[id]; ok {
		cp := cpy
		m.accounts[id] = &cp
	}
	return nil
}
