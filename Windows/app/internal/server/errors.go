package server

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/url"
	"strings"

	"rxvpn/internal/apierr"
	"rxvpn/internal/foxy"
	"rxvpn/internal/locale"
	"rxvpn/internal/sysproxy"
)

// fxaMessages are the friendly texts (Persian, English) for known Firefox Accounts error numbers.
var fxaMessages = map[int][2]string{
	102: {"حسابی با این ایمیل پیدا نشد.", "No account was found with this email."},
	103: {"رمز عبور اشتباه است.", "The password is incorrect."},
	104: {"حساب کاربری شما هنوز تأیید نشده است.", "Your Firefox account has not been verified yet."},
	106: {"قالب درخواست نامعتبر بود.", "Invalid request payload."},
	107: {"مقدار واردشده نامعتبر است.", "Invalid parameter in request."},
	110: {"نشست ورود نامعتبر یا منقضی شده است. دوباره تلاش کنید.", "Invalid or expired sign-in token. Please try again."},
	114: {"تعداد تلاش‌ها زیاد بود. چند دقیقه صبر کنید و دوباره امتحان کنید.",
		"Too many attempts. Wait a few minutes and try again."},
	125: {"Firefox این درخواست را به دلایل امنیتی مسدود کرد. کد رفع مسدودیت ارسال‌شده به ایمیل را وارد کنید یا از ورود با مرورگر استفاده کنید.",
		"Firefox blocked this request for security reasons. Enter the emailed unblock code or use Browser Sign-In."},
	126: {"حساب نیاز به بازنشانی امنیتی دارد.", "This account must complete a security reset on Mozilla."},
	127: {"کد رفع مسدودیت اشتباه یا منقضی شده است.", "Invalid or expired unblock code."},
	138: {"نشست هنوز تأیید نشده است. کد تأیید را کامل کنید.", "Session is not verified yet. Complete verification first."},
	142: {"ورود با این ایمیل موقتاً مسدود شده است.", "Sign-in with this email is temporarily blocked."},
	151: {"ارسال ایمیل کد تأیید ناموفق بود. کمی بعد دوباره امتحان کنید.", "Failed to send verification email. Try again shortly."},
	152: {"کد تأیید ایمیل اشتباه یا منقضی شده است.", "Invalid or expired email verification code."},
	160: {"کد تأیید دو مرحله‌ای (Authenticator) اشتباه است.", "Invalid two-step authentication (TOTP) code."},
}

// FriendlyError turns any error from the backend into a message the panel can show.
func FriendlyError(err error) *apierr.Error {
	var ae *apierr.Error
	if errors.As(err, &ae) {
		return ae
	}
	var me *sysproxy.ModeError
	if errors.As(err, &me) {
		return apierr.New(me.Error(), 409)
	}
	var rb *foxy.RegionBlockedError
	if errors.As(err, &rb) {
		return apierr.New(locale.Tr(
			"دسترسی از منطقه شما توسط سرور محدود شده است (HTTP 451). از بخش تنظیمات، «سرور رله» (رله اشتراکی یا رله شخصی) را فعال یا بررسی کنید.",
			"Access from your region is restricted (HTTP 451). Check or enable Relay Server (Shared or Personal Relay) in Settings.",
		), 451).WithExtra("region_blocked", true)
	}
	var fe *foxy.FxaAPIError
	if errors.As(err, &fe) {
		if known, ok := fxaMessages[fe.Errno]; ok {
			return apierr.New(locale.Tr(known[0], known[1]), 400).WithExtra("errno", fe.Errno)
		}
		msg := fe.Error()
		if msg == "" {
			msg = locale.Tr("ورود ناموفق بود.", "Sign-in failed.")
		}
		return apierr.New(msg, 400)
	}
	var nl *foxy.NeedLoginError
	if errors.As(err, &nl) {
		return apierr.New(locale.Tr("نشست Firefox منقضی شده. دوباره وارد شوید.",
			"Your Firefox session expired. Please sign in again."), 401).WithExtra("need_login", true)
	}
	var qe *foxy.QuotaExceededError
	if errors.As(err, &qe) {
		return apierr.New(locale.Tr("سهمیه ماهانه‌ی حساب Firefox تمام شده است.",
			"The monthly quota of your Firefox account is used up."), 429)
	}
	var cc foxy.CaptchaCancelledError
	if errors.As(err, &cc) {
		return apierr.New(locale.Tr("تأیید امنیتی لغو شد.",
			"Security verification was cancelled."), 499).WithExtra("captcha_cancelled", true)
	}
	var fc *foxy.FastlyChallengeError
	if errors.As(err, &fc) {
		if strings.Contains(strings.ToLower(fc.Msg), "cancelled") {
			return apierr.New(locale.Tr("تأیید امنیتی لغو شد.",
				"Security verification was cancelled."), 499).WithExtra("captcha_cancelled", true)
		}
		return apierr.New(locale.Tr(
			"تأیید امنیتی Fastly تکمیل نشد یا منقضی شد. دوباره تلاش کنید یا از روش «ورود با مرورگر» استفاده کنید.",
			"Fastly security verification was not completed or expired. Try again or use Browser Sign-In.",
		), 502)
	}
	if foxy.IsUpstream(err) {
		return apierr.New(err.Error(), 502)
	}
	var ue *url.Error
	var ne net.Error
	if errors.As(err, &ue) || errors.As(err, &ne) {
		if errors.Is(err, context.DeadlineExceeded) {
			return apierr.New(locale.Tr("زمان انتظار تمام شد.", "The operation timed out."), 504)
		}
		return apierr.New(locale.Tr("اتصال به سرورهای Firefox برقرار نشد. اینترنت یا تنظیمات «سرور رله» را بررسی کنید.",
			"Could not reach Firefox servers. Check your internet or Relay Server settings."), 502)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return apierr.New(locale.Tr("زمان انتظار تمام شد.", "The operation timed out."), 504)
	}
	if errors.Is(err, context.Canceled) {
		return apierr.New(locale.Tr("عملیات لغو شد.", "Operation was cancelled."), 499)
	}
	slog.Error("unexpected error", "err", err)
	return apierr.New(locale.Tr("خطای غیرمنتظره: "+err.Error(), "Unexpected error: "+err.Error()), 500)
}
