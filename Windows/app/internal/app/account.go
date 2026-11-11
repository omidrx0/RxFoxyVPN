package app

import (
	"context"
	"errors"
	"log/slog"
	"strings"

	"rxvpn/internal/apierr"
	"rxvpn/internal/foxy"
	"rxvpn/internal/locale"
	"rxvpn/internal/platform"
)

// Login signs in with e-mail and password (plus optional unblock_code).
// Possible responses:
//   - {"need_unblock": true, "email": ...} when Mozilla requires an emailed security unblock code (errno 125)
//   - {"need_code": true, "verification_method": "email-2fa"|"totp-2fa", "email": ...} when 2FA/verification is required
//   - {"ok": true, ...} when login succeeded and tokens were stored via the unified post-login path.
func (a *App) Login(ctx context.Context, body Body) (any, error) {
	email := foxy.NormalizeEmail(str(body["email"]))
	password := str(body["password"])
	unblockCode := foxy.NormalizeCode(str(body["unblock_code"]))
	remember := body["remember"] == true

	if email == "" || !strings.Contains(email, "@") || password == "" {
		return nil, apierr.New(locale.Tr("ایمیل معتبر و رمز عبور را وارد کنید.", "Enter a valid email and password."))
	}

	// Isolate cookies and pending session state when a different email signs in on the same device
	// (matching Android FxaAuthRepository.startLogin).
	a.mu.Lock()
	switchedEmail := a.lastInteractiveEmail != "" && !strings.EqualFold(a.lastInteractiveEmail, email)
	if switchedEmail {
		a.pendingSession = ""
		a.pendingEmail = ""
		a.pendingVerificationMethod = ""
		a.pendingUnblockEmail = ""
		a.pendingPassword = ""
	}
	a.lastInteractiveEmail = email
	a.mu.Unlock()
	if switchedEmail && a.cp != nil {
		a.cp.ClearCookies()
	}

	ctx = foxy.WithInteractiveCaptcha(ctx)
	data, err := a.account.Fxa.LoginWithUnblock(ctx, email, password, unblockCode)
	if err != nil {
		var apiErr *foxy.FxaAPIError
		if errors.As(err, &apiErr) && apiErr.Errno == 125 && unblockCode == "" {
			// Mozilla blocked the request for security reasons (errno 125): request an unblock code!
			if sendErr := a.account.Fxa.SendUnblockCode(ctx, email); sendErr == nil {
				a.mu.Lock()
				a.pendingUnblockEmail = email
				a.pendingPassword = password
				a.pendingSession = ""
				a.pendingVerificationMethod = ""
				a.mu.Unlock()
				return map[string]any{
					"need_unblock": true,
					"email":        email,
				}, nil
			}
		}
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
		method := strings.TrimSpace(data.VerificationMethod)
		if method == "" {
			method = "email-2fa"
		}
		a.mu.Lock()
		a.pendingSession = data.SessionToken
		a.pendingEmail = email
		a.pendingVerificationMethod = method
		a.pendingUnblockEmail = ""
		a.pendingPassword = ""
		a.mu.Unlock()
		return map[string]any{
			"need_code":           true,
			"verification_method": method,
			"email":               email,
			"account_saved":       accountSaved,
		}, nil
	}

	if err := a.finishSessionLogin(ctx, data.SessionToken, email); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "account_saved": accountSaved}, nil
}

// finishSessionLogin trades the verified FxA sessionToken for OAuth tokens, destroys the temporary
// sessionToken, and runs the unified post-login registration hook.
func (a *App) finishSessionLogin(ctx context.Context, sessionToken, email string) error {
	tokens, err := a.account.Fxa.OAuthFromSession(ctx, sessionToken)
	if err != nil {
		return err
	}
	if err := a.account.StoreTokens(tokens); err != nil {
		return err
	}
	resolvedEmail := foxy.NormalizeEmail(email)
	if resolvedEmail == "" {
		resolvedEmail = a.account.Fxa.FetchProfileEmail(ctx, tokens.AccessToken)
	}
	if err := a.account.Fxa.DestroySession(ctx, sessionToken); err != nil {
		slog.Info("temporary FxA session cleanup failed", "err", err)
	}
	a.onPostLoginComplete(resolvedEmail)
	return nil
}

// SubmitUnblock submits Mozilla's 8-character unblock code for a pending errno-125 login.
func (a *App) SubmitUnblock(ctx context.Context, body Body) (any, error) {
	code := foxy.NormalizeCode(str(body["code"]))
	if code == "" {
		code = foxy.NormalizeCode(str(body["unblock_code"]))
	}
	if code == "" {
		return nil, apierr.New(locale.Tr("کد رفع مسدودیت ارسال‌شده به ایمیل را وارد کنید.",
			"Enter the unblock code sent to your email."))
	}

	a.mu.RLock()
	email := a.pendingUnblockEmail
	password := a.pendingPassword
	a.mu.RUnlock()

	if e := foxy.NormalizeEmail(str(body["email"])); e != "" {
		email = e
	}
	if p := str(body["password"]); p != "" {
		password = p
	}
	if email == "" || password == "" {
		return nil, apierr.New(locale.Tr("نشست ورود منقضی شده؛ دوباره ایمیل و رمز عبور را وارد کنید.",
			"Sign-in session expired; enter your email and password again."), 409)
	}
	return a.Login(ctx, Body{
		"email":        email,
		"password":     password,
		"unblock_code": code,
		"remember":     body["remember"] == true,
	})
}

// ResendCode resends either the pending 2FA email verification code or the security unblock code.
func (a *App) ResendCode(ctx context.Context, body Body) (any, error) {
	ctx = foxy.WithInteractiveCaptcha(ctx)
	mode := strings.ToLower(strings.TrimSpace(str(body["mode"])))

	a.mu.RLock()
	session := a.pendingSession
	method := a.pendingVerificationMethod
	unblockEmail := a.pendingUnblockEmail
	a.mu.RUnlock()

	if mode == "unblock" || (session == "" && unblockEmail != "") {
		email := unblockEmail
		if e := foxy.NormalizeEmail(str(body["email"])); e != "" {
			email = e
		}
		if email == "" {
			return nil, apierr.New(locale.Tr("ایمیلی برای ارسال مجدد کد ثبت نشده است.",
				"No email is pending an unblock code."), 409)
		}
		if err := a.account.Fxa.SendUnblockCode(ctx, email); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true, "mode": "unblock"}, nil
	}

	if session == "" {
		return nil, apierr.New(locale.Tr("نشست تأیید فعالی وجود ندارد.",
			"There is no active verification session."), 409)
	}
	if method == "totp-2fa" {
		return nil, apierr.New(locale.Tr("کد دو مرحله‌ای از برنامه Authenticator دریافت می‌شود و قابل ارسال مجدد با ایمیل نیست.",
			"Authenticator (TOTP) codes are generated on your device and cannot be emailed."), 400)
	}
	if err := a.account.Fxa.ResendVerificationCode(ctx, session); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "mode": "verify"}, nil
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

// Verify verifies either an email verification code or a 6-digit TOTP authenticator code.
func (a *App) Verify(ctx context.Context, body Body) (any, error) {
	code := foxy.NormalizeCode(str(body["code"]))
	a.mu.RLock()
	session, email, verificationMethod := a.pendingSession, a.pendingEmail, a.pendingVerificationMethod
	a.mu.RUnlock()

	if override := strings.TrimSpace(str(body["method"])); override != "" {
		verificationMethod = override
	}
	if session == "" {
		return nil, apierr.New(locale.Tr("ابتدا با ایمیل و رمز عبور وارد شوید.",
			"Sign in with your email and password first."), 409)
	}
	if code == "" {
		return nil, apierr.New(locale.Tr("کد تأیید را وارد کنید.", "Enter the verification code."))
	}

	ctx = foxy.WithInteractiveCaptcha(ctx)
	if err := a.account.Fxa.VerifyCode(ctx, session, code, verificationMethod); err != nil {
		return nil, err
	}
	if err := a.finishSessionLogin(ctx, session, email); err != nil {
		return nil, err
	}
	return map[string]any{"ok": true}, nil
}

// CancelPendingAuth cancels any pending 2FA, unblock, CAPTCHA, or browser sign-in flow cleanly.
func (a *App) CancelPendingAuth(context.Context, Body) (any, error) {
	a.mu.Lock()
	a.pendingSession = ""
	a.pendingEmail = ""
	a.pendingVerificationMethod = ""
	a.pendingUnblockEmail = ""
	a.pendingPassword = ""
	a.mu.Unlock()

	if a.captchaBroker != nil {
		a.captchaBroker.Cancel()
	}
	if a.browserAuth != nil {
		a.browserAuth.Cancel()
	}
	return map[string]any{"ok": true}, nil
}

// ---------------------------------------------------------------- Interactive Fastly CAPTCHA API

// CaptchaState returns any active interactive Fastly CAPTCHA challenge awaiting user input.
func (a *App) CaptchaState(context.Context, Body) (any, error) {
	if a.captchaBroker == nil {
		return map[string]any{"active": false}, nil
	}
	cur := a.captchaBroker.Current()
	if cur == nil {
		return map[string]any{"active": false}, nil
	}
	return map[string]any{
		"active":    true,
		"challenge": cur,
	}, nil
}

// CaptchaSubmit submits the user's answer for an active Fastly CAPTCHA challenge.
func (a *App) CaptchaSubmit(_ context.Context, body Body) (any, error) {
	answer := strings.TrimSpace(str(body["answer"]))
	if answer == "" {
		return nil, apierr.New(locale.Tr("پاسخ تصویر امنیتی را وارد کنید.", "Enter the security image code."))
	}
	if a.captchaBroker == nil || a.captchaBroker.Submit(answer) != nil {
		return nil, apierr.New(locale.Tr("چالش امنیتی منقضی شده یا تغییر کرده است.",
			"The security challenge expired or changed."), 409)
	}
	return map[string]any{"ok": true}, nil
}

// CaptchaRefresh requests a fresh CAPTCHA image from Fastly.
func (a *App) CaptchaRefresh(_ context.Context, _ Body) (any, error) {
	if a.captchaBroker == nil || a.captchaBroker.Refresh() != nil {
		return nil, apierr.New(locale.Tr("چالش امنیتی فعالی برای بارگذاری مجدد وجود ندارد.",
			"No active security challenge to refresh."), 409)
	}
	return map[string]any{"ok": true}, nil
}

// CaptchaCancel aborts the active CAPTCHA challenge and returns control to the login screen.
func (a *App) CaptchaCancel(_ context.Context, _ Body) (any, error) {
	if a.captchaBroker != nil {
		a.captchaBroker.Cancel()
	}
	return map[string]any{"ok": true}, nil
}

// ---------------------------------------------------------------- Browser Login API

// BrowserLoginStart begins an independent Browser Sign-In session.
// On Windows, preferred mode defaults to "webview2" (isolated Mozilla WebChannel sign-in window,
// matching Android's BrowserSignInScreen) with automatic fallback to "external" (system browser
// OAuth 2.0 + PKCE + loopback callback listener). Callers may explicitly pass mode="external"
// to open the system default browser directly.
func (a *App) BrowserLoginStart(_ context.Context, body Body) (any, error) {
	rawMode := strings.ToLower(strings.TrimSpace(str(body["mode"])))
	mode := foxy.BrowserModeWebView2
	if rawMode == "external" || !platform.IsWindows {
		mode = foxy.BrowserModeExternal
	}
	email := foxy.NormalizeEmail(str(body["email"]))
	st, err := a.browserAuth.Start(mode, email)
	if err != nil {
		return nil, err
	}
	return st, nil
}

// BrowserLoginStatus returns the live status of the active or most recent Browser Sign-In session.
func (a *App) BrowserLoginStatus(context.Context, Body) (any, error) {
	return a.browserAuth.Status(), nil
}

// BrowserLoginComplete completes an external-browser OAuth PKCE sign-in using the pasted final
// Mozilla redirect URL (https://accounts.firefox.com/oauth/success/...?code=...&state=...).
func (a *App) BrowserLoginComplete(ctx context.Context, body Body) (any, error) {
	raw := strings.TrimSpace(str(body["redirect_url"]))
	if raw == "" {
		raw = strings.TrimSpace(str(body["code"]))
	}
	if raw == "" {
		return nil, apierr.New(locale.Tr("نشانی بازگشتی مرورگر را جای‌گذاری کنید.",
			"Paste the final redirect URL from your browser."))
	}
	ctx = foxy.WithInteractiveCaptcha(ctx)
	email, err := a.browserAuth.CompleteExternalRedirect(ctx, raw)
	if err != nil {
		return nil, err
	}
	return map[string]any{"ok": true, "email": email}, nil
}

// BrowserLoginCancel cancels an active Browser Sign-In session, closes the loopback listener,
// terminates any helper WebView2 window, and wipes partial session state.
func (a *App) BrowserLoginCancel(context.Context, Body) (any, error) {
	a.browserAuth.Cancel()
	return map[string]any{"ok": true}, nil
}

// ---------------------------------------------------------------- Logout & Language

// Logout disconnects and forgets the session.
func (a *App) Logout(context.Context, Body) (any, error) {
	if a.browserAuth != nil {
		a.browserAuth.Cancel()
	}
	if a.captchaBroker != nil {
		a.captchaBroker.Cancel()
	}
	if a.Connected() {
		a.opMu.Lock()
		a.doDisconnect()
		a.opMu.Unlock()
	}
	a.quotaRefreshMu.Lock()
	a.mu.Lock()
	a.pendingSession = ""
	a.pendingEmail = ""
	a.pendingVerificationMethod = ""
	a.pendingUnblockEmail = ""
	a.pendingPassword = ""
	a.mu.Unlock()
	a.account.Clear()
	a.clearQuota()
	a.quotaRefreshMu.Unlock()
	return map[string]any{"ok": true}, nil
}

// SetLang stores the panel language.
func (a *App) SetLang(_ context.Context, body Body) (any, error) {
	lang := str(body["lang"])
	if !locale.Valid(lang) {
		return nil, apierr.New("bad language")
	}
	locale.Set(lang)
	if err := a.saveLangSafe(); err != nil {
		slog.Warn("could not save the language setting", "err", err)
	}
	return map[string]any{"ok": true, "lang": lang}, nil
}
