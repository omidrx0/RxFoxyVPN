package app

import (
	"context"
	"path/filepath"
	"testing"
	"time"

	"rxvpn/internal/accounts"
	"rxvpn/internal/config"
	"rxvpn/internal/foxy"
)

func testPool(t *testing.T, dataDir string) *poolState {
	t.Helper()
	store, err := accounts.OpenStore(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	mgr, err := accounts.NewManager(
		store,
		accounts.Policy{ThresholdPercent: 95},
		filepath.Join(store.Dir(), "state.json"),
		filepath.Join(store.Dir(), "order.json"),
	)
	if err != nil {
		t.Fatal(err)
	}
	return &poolState{store: store, mgr: mgr}
}

func testAccount(t *testing.T, dataDir string) *foxy.Account {
	t.Helper()
	cp, err := foxy.NewControlPlane("")
	if err != nil {
		t.Fatal(err)
	}
	account := foxy.NewAccount(cp, filepath.Join(dataDir, "legacy.json"))
	if err := account.StoreTokens(&foxy.TokenResponse{
		AccessToken:  "legacy-access",
		RefreshToken: "legacy-refresh",
		ExpiresIn:    3600,
	}); err != nil {
		t.Fatal(err)
	}
	account.SetEmail("user@example.com")
	return account
}

func TestSyncPoolAccountAfterLoginEnablesAndActivatesAccount(t *testing.T) {
	dir := t.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	a := &App{
		account:  testAccount(t, dir),
		accounts: config.NewAccountStore(filepath.Join(dir, "saved_accounts.json"), nil),
		pool:     testPool(t, dir),
		ctx:      ctx,
	}

	a.syncPoolAccountAfterLogin("user@example.com")

	got := a.pool.mgr.List()
	if len(got) != 1 {
		t.Fatalf("got %d enabled accounts, want 1", len(got))
	}
	if got[0].Disabled {
		t.Fatal("newly signed-in account must be enabled")
	}
	active, ok := a.pool.mgr.EffectiveActive()
	if !ok || active.ID != got[0].ID {
		t.Fatalf("newly signed-in account was not activated: %+v, %v", active, ok)
	}
}

func TestPoolToggleLastAccountBlocksLegacySessionFallback(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", filepath.Join(dir, "appdata"))
	pool := testPool(t, dir)
	acc, _, err := pool.store.Add(
		"pool@example.com",
		accounts.TokensFromLogin("access", "refresh", "", "profile", "bearer", 3600, time.Now()),
		time.Now(),
	)
	if err != nil {
		t.Fatal(err)
	}
	a := &App{account: testAccount(t, dir), pool: pool}

	if _, err := a.PoolToggle(context.Background(), Body{"id": acc.ID, "enabled": false}); err != nil {
		t.Fatal(err)
	}
	if err := a.w07Gate("US"); err == nil {
		t.Fatal("connecting must be rejected when all pool accounts are disabled, even with a legacy session")
	}
}

func TestPoolToggleDisconnectsWhenLastAccountIsDisabled(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("APPDATA", filepath.Join(dir, "appdata"))
	pool := testPool(t, dir)
	acc, _, err := pool.store.Add(
		"pool@example.com",
		accounts.TokensFromLogin("access", "refresh", "", "profile", "bearer", 3600, time.Now()),
		time.Now(),
	)
	if err != nil {
		t.Fatal(err)
	}
	a := &App{pool: pool, connected: true}

	if _, err := a.PoolToggle(context.Background(), Body{"id": acc.ID, "enabled": false}); err != nil {
		t.Fatal(err)
	}
	if a.Connected() {
		t.Fatal("connection should stop when the final enabled account is disabled")
	}
}

func TestW07GateAllowsLegacySessionWhenPoolHasNoAccounts(t *testing.T) {
	dir := t.TempDir()
	a := &App{account: testAccount(t, dir), pool: testPool(t, dir)}
	if err := a.w07Gate("US"); err != nil {
		t.Fatalf("empty pool should retain legacy-session fallback: %v", err)
	}
}
