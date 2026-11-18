package accounts

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func managerFixture(t *testing.T) (*Store, string, []Account) {
	t.Helper()
	dir := t.TempDir()
	st, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC)
	creds := func(email string) *SavedTokens {
		return TokensFromLogin("at-"+email, "rt-"+email, "", "profile", "bearer", 3600, now)
	}
	var accs []Account
	for _, email := range []string{"first@example.com", "second@example.com", "third@example.com"} {
		acc, _, err := st.Add(email, creds(email), now)
		if err != nil {
			t.Fatal(err)
		}
		accs = append(accs, acc)
	}
	mkOrder(t, st, []string{accs[0].ID, accs[1].ID, accs[2].ID})
	return st, dir, accs
}

func mkOrder(t *testing.T, st *Store, order []string) {
	t.Helper()
	if err := SaveOrder(filepath.Join(st.Dir(), "order.json"), order); err != nil {
		t.Fatal(err)
	}
}

func loadManager(t *testing.T, st *Store, dir string, pct float64) *Manager {
	t.Helper()
	m, err := NewManager(st, Policy{ThresholdPercent: pct}, filepath.Join(st.Dir(), "state.json"), filepath.Join(st.Dir(), "order.json"))
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func TestReserveAndCandidateFlow(t *testing.T) {
	st, dir, accs := managerFixture(t)
	m := loadManager(t, st, dir, 50)

	cand, err := m.Candidate(nil)
	if err != nil || cand.ID != accs[0].ID {
		t.Fatalf("first candidate must be the first account: %+v %v", cand, err)
	}
	// reserve the first; candidate moves to the second
	future := time.Now().Add(2 * time.Hour)
	if err := m.Reserve(accs[0].ID, ReasonQuotaExhausted, future); err != nil {
		t.Fatal(err)
	}
	cand, err = m.Candidate(nil)
	if err != nil || cand.ID != accs[1].ID {
		t.Fatalf("after reserve, candidate must rotate: %+v %v", cand, err)
	}
	// reserving everything → ErrNoCandidate
	_ = m.Reserve(accs[1].ID, ReasonQuotaExhausted, future)
	_ = m.Reserve(accs[2].ID, ReasonQuotaExhausted, future)
	if _, err := m.Candidate(nil); !errors.Is(err, ErrNoCandidate) {
		t.Fatalf("all reserved → ErrNoCandidate, got %v", err)
	}
	// activating a reserved account is allowed (import/refresh flows need it)
	if err := m.Activate(accs[1].ID); err != nil {
		t.Fatal(err)
	}
}

func TestObserveUsageThresholdAndResume(t *testing.T) {
	st, dir, accs := managerFixture(t)
	m := loadManager(t, st, dir, 50)

	limit := int64(100 << 20)
	remaining := int64(40 << 20) // 60% used → past a 50% threshold
	updated := time.Date(2026, 11, 20, 0, 0, 0, 0, time.UTC)
	now := time.Date(2026, 12, 1, 0, 0, 0, 0, time.UTC)
	m.now = func() time.Time { return now }

	reserved, err := m.ObserveUsage(accs[0].ID, Usage{LimitBytes: limit, RemainingBytes: remaining, ResetAt: updated, CheckedAt: now})
	if err != nil {
		t.Fatal(err)
	}
	if !reserved {
		t.Fatal("account at 60% used must be reserved by a 50% policy")
	}
	got, _ := st.Get(accs[0].ID)
	if got.Status != StatusReserved || got.ReasonCode != ReasonQuotaExhausted {
		t.Fatalf("status=%s reason=%s", got.Status, got.ReasonCode)
	}
	if !got.RetryAt.Equal(updated) {
		t.Fatalf("retry_at=%s, want reset %s", got.RetryAt, updated)
	}

	// AfterReset after the reset time makes it ready again
	later := updated.Add(time.Minute)
	st2, _ := OpenStore(dir)
	m2 := loadManager(t, st2, dir, 50)
	m2.now = func() time.Time { return later }
	if err := m2.ResumeAll(); err != nil {
		t.Fatal(err)
	}
	got, _ = st2.Get(accs[0].ID)
	if got.Status != StatusReady {
		t.Fatalf("after reset the account must be ready, got %s", got.Status)
	}
}

func TestObserveUsageUnknownKeepsPreviousReading(t *testing.T) {
	st, dir, accs := managerFixture(t)
	m := loadManager(t, st, dir, 50)
	m.now = func() time.Time { return time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC) }

	known := Usage{LimitBytes: 100 << 20, RemainingBytes: 90 << 20, CheckedAt: m.now()}
	if _, err := m.ObserveUsage(accs[0].ID, known); err != nil {
		t.Fatal(err)
	}
	// an unknown reading must not erase the numbers or flip status
	if _, err := m.ObserveUsage(accs[0].ID, Usage{}); err != nil {
		t.Fatal(err)
	}
	got, _ := st.Get(accs[0].ID)
	if got.Usage == nil || got.Usage.LimitBytes != 100<<20 || got.Usage.RemainingBytes != 90<<20 {
		t.Fatalf("unknown reading overwrote the stored quota: %+v", got.Usage)
	}
}

func TestUnlimitedIsNeverReserved(t *testing.T) {
	st, dir, accs := managerFixture(t)
	m := loadManager(t, st, dir, 95)
	m.now = func() time.Time { return time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC) }
	reserved, err := m.ObserveUsage(accs[0].ID, Usage{Unlimited: true, CheckedAt: m.now()})
	if err != nil || reserved {
		t.Fatalf("unlimited must never be reserved: %v %v", reserved, err)
	}
	got, _ := m.Candidate(nil)
	if got.ID != accs[0].ID {
		t.Fatal("unlimited account stays a candidate")
	}
}

func TestReservedWithFreshQuotaReturnsToReady(t *testing.T) {
	st, dir, accs := managerFixture(t)
	m := loadManager(t, st, dir, 50)
	now := time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC)
	m.now = func() time.Time { return now }
	_ = m.Reserve(accs[0].ID, ReasonQuotaExhausted, now.Add(time.Hour))
	// Guardian reports a fresh window (reset in future is impossible; remaining full)
	_, err := m.ObserveUsage(accs[0].ID, Usage{LimitBytes: 100, RemainingBytes: 100, ResetAt: now.Add(time.Hour), CheckedAt: now})
	if err != nil {
		t.Fatal(err)
	}
	got, _ := st.Get(accs[0].ID)
	if got.Status != StatusReady {
		t.Fatalf("fresh quota must return the account to ready, got %s", got.Status)
	}
}

func TestReauthIsSticky(t *testing.T) {
	st, dir, accs := managerFixture(t)
	m := loadManager(t, st, dir, 95)
	m.now = func() time.Time { return time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC) }
	if err := m.MarkReauthRequired(accs[0].ID, "test"); err != nil {
		t.Fatal(err)
	}
	if _, err := m.ObserveUsage(accs[0].ID, Usage{LimitBytes: 100, RemainingBytes: 100, CheckedAt: m.now()}); err != nil {
		t.Fatal(err)
	}
	got, _ := st.Get(accs[0].ID)
	if got.Status != StatusReauthRequired {
		t.Fatalf("reauth_required must be sticky, got %s", got.Status)
	}
}

func TestOrderRoundTripAndDisable(t *testing.T) {
	st, dir, accs := managerFixture(t)
	m := loadManager(t, st, dir, 95)
	if err := m.SetEnabled(accs[0].ID, false); err != nil {
		t.Fatal(err)
	}
	cand, err := m.Candidate(nil)
	if err != nil || cand.ID != accs[1].ID {
		t.Fatalf("disabled account must not be a candidate: %+v %v", cand, err)
	}
	m2 := loadManager(t, st, dir, 95)
	got, ok := m2.EffectiveActive()
	if !ok {
		t.Fatal("no active account resolved")
	}
	if got.ID == accs[0].ID {
		t.Fatal("disabled account must not be picked as active")
	}
	// a disabled id cannot be activated
	if err := m.Activate(accs[0].ID); !errors.Is(err, ErrNotFound) {
		t.Fatalf("activating a disabled account must fail, got %v", err)
	}
}

func TestOrderJsonCompatible(t *testing.T) {
	st, _, accs := managerFixture(t)
	order := []string{accs[2].ID, accs[0].ID}
	if err := SaveOrder(filepath.Join(st.Dir(), "order.json"), order); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(st.Dir(), "order.json"))
	if err != nil {
		t.Fatal(err)
	}
	var back []string
	if err := json.Unmarshal(raw, &back); err != nil {
		t.Fatal(err)
	}
	if len(back) != 2 || back[0] != accs[2].ID {
		t.Fatalf("order.json roundtrip failed: %v", back)
	}
}
