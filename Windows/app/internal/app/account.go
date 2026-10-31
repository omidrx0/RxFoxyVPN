package app

import (
	"context"
	"log/slog"
	"regexp"
	"strings"

	"rxvpn/internal/apierr"
	"rxvpn/internal/config"
	"rxvpn/internal/locale"
)

var whitespaceRe = regexp.MustCompile(`\s+`)

// Login signs in with e-mail and password. The answer is {"need_code": true, ...} when Firefox
// sent an e-mail verification code, otherwise {"ok": true, ...}.
func (a *App) Login(ctx context.Context, body Body) (any, error) {
	email := strings.TrimSpace(str(body["email"]))
	password := str(body["password"])
	remember := body["remember"] == true
	if email == "" || password == "" {
		return nil, apierr.New(locale.Tr("ایمیل و رمز عبور را وارد کنید.", "Enter your email and password."))
	}
	data, err := a.account.Fxa.Login(ctx, email, password)
	if err != nil {
		return nil, err
	}
	accountSaved := false
	if remember {
		if err := a.accounts.Save(email, password); err != nil {
			slog.Warn("could not save account credentials", "err", err)
		} else {
			accountSaved = true
		}
	}
	if !data.Verified {
		a.mu.Lock()
		a.pendingSession, a.pendingEmail = data.SessionToken, email
		a.pendingVerificationMethod = data.VerificationMethod
		a.mu.Unlock()
		return map[string]any{"need_code": true, "account_saved": accountSaved}, nil
	}
	if err := a.finishLogin(ctx, data.SessionToken, email); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "account_saved": accountSaved}, nil
}

// finishLogin trades the session for OAuth tokens and resets the quota display.
func (a *App) finishLogin(ctx context.Context, sessionToken, email string) error {
	tokens, err := a.account.Fxa.OAuthFromSession(ctx, sessionToken)
	if err != nil {
		return err
	}
	if err := a.account.StoreTokens(tokens); err != nil {
		return err
	}
	if err := a.account.Fxa.DestroySession(ctx, sessionToken); err != nil {
		slog.Info("temporary FxA session cleanup failed", "err", err)
	}
	a.quotaRefreshMu.Lock()
	a.clearQuota()
	a.quotaRefreshMu.Unlock()
	a.scheduleQuotaRefresh()
	if email != "" {
		a.account.SetEmail(email)
	}
	return nil
}

// SavedAccounts lists the "remember me" e-mail addresses.
func (a *App) SavedAccounts(context.Context, Body) (any, error) {
	return map[string]any{"accounts": a.accounts.List()}, nil
}

// LoginSaved signs in with stored credentials.
func (a *App) LoginSaved(ctx context.Context, body Body) (any, error) {
	email := strings.TrimSpace(str(body["email"]))
	password, err := a.accounts.Password(email)
	if err != nil {
		return nil, apierr.New(locale.Tr("اطلاعات ذخیره‌شده‌ی این حساب در دسترس نیست.",
			"Saved credentials for this account are unavailable."), 404)
	}
	return a.Login(ctx, Body{"email": email, "password": password})
}

// DeleteSavedAccount forgets one saved account.
func (a *App) DeleteSavedAccount(_ context.Context, body Body) (any, error) {
	email := strings.TrimSpace(str(body["email"]))
	if email == "" {
		return nil, apierr.New(locale.Tr("حسابی انتخاب نشده است.", "No account is selected."))
	}
	list, err := a.accounts.Remove(email)
	if err != nil {
		return nil, err
	}
	return map[string]any{"accounts": list}, nil
}

// Verify sends the e-mail code of a pending sign-in.
func (a *App) Verify(ctx context.Context, body Body) (any, error) {
	code := whitespaceRe.ReplaceAllString(str(body["code"]), "")
	a.mu.RLock()
	session, email, verificationMethod := a.pendingSession, a.pendingEmail, a.pendingVerificationMethod
	a.mu.RUnlock()
	if session == "" {
		return nil, apierr.New(locale.Tr("ابتدا با ایمیل و رمز عبور وارد شوید.",
			"Sign in with your email and password first."), 409)
	}
	if code == "" {
		return nil, apierr.New(locale.Tr("کد تأیید را وارد کنید.", "Enter the verification code."))
	}
	if err := a.account.Fxa.VerifyCode(ctx, session, code, verificationMethod); err != nil {
		return nil, err
	}
	if err := a.finishLogin(ctx, session, email); err != nil {
		return nil, err
	}
	a.mu.Lock()
	a.pendingSession, a.pendingEmail, a.pendingVerificationMethod = "", "", ""
	a.mu.Unlock()
	return map[string]any{"ok": true}, nil
}

// Logout disconnects and forgets the session.
func (a *App) Logout(context.Context, Body) (any, error) {
	if a.Connected() {
		a.opMu.Lock()
		a.doDisconnect()
		a.opMu.Unlock()
	}
	a.quotaRefreshMu.Lock()
	a.mu.Lock()
	a.pendingSession, a.pendingEmail, a.pendingVerificationMethod = "", "", ""
	a.mu.Unlock()
	a.account.Clear()
	a.clearQuotaFields()
	a.quotaRefreshMu.Unlock()
	return map[string]any{"ok": true}, nil
}

func (a *App) clearQuotaFields() {
	a.mu.Lock()
	a.quotaLimit, a.quotaRemaining = nil, nil
	a.mu.Unlock()
}

// SetLang stores the panel language.
func (a *App) SetLang(_ context.Context, body Body) (any, error) {
	lang := str(body["lang"])
	if !locale.Valid(lang) {
		return nil, apierr.New("bad language")
	}
	locale.Set(lang)
	if err := config.SaveLang(); err != nil {
		slog.Warn("could not save the language setting", "err", err)
	}
	return map[string]any{"ok": true, "lang": lang}, nil
}
