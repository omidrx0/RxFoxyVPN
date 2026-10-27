package server

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"net/url"

	"rxvpn/internal/apierr"
	"rxvpn/internal/foxy"
	"rxvpn/internal/locale"
	"rxvpn/internal/sysproxy"
)

// fxaMessages are the friendly texts (Persian, English) for known Firefox Accounts error numbers.
var fxaMessages = map[int][2]string{
	102: {"حسابی با این ایمیل پیدا نشد.", "No account was found with this email."},
	103: {"رمز عبور اشتباه است.", "The password is incorrect."},
	114: {"تعداد تلاش‌ها زیاد بود. چند دقیقه صبر کنید و دوباره امتحان کنید.",
		"Too many attempts. Wait a few minutes and try again."},
	125: {"Firefox این درخواست را به دلایل امنیتی مسدود کرد. از یک شبکه یا آی‌پی دیگر امتحان کنید.",
		"Firefox blocked this request for security reasons. Try another network or IP."},
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
	var fe *foxy.FxaAPIError
	if errors.As(err, &fe) {
		if known, ok := fxaMessages[fe.Errno]; ok {
			return apierr.New(locale.Tr(known[0], known[1]), 400)
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
	var fc *foxy.FastlyChallengeError
	if errors.As(err, &fc) {
		return apierr.New(locale.Tr("چالش ضدربات Fastly اجازه نداد. شبکه را عوض کنید یا --api-proxy بدهید.",
			"The Fastly anti-bot challenge blocked the request. Change network or use --api-proxy."), 502)
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
		return apierr.New(locale.Tr("اتصال به سرورهای Firefox برقرار نشد. اینترنت را بررسی کنید "+
			"(اگر مسدود است از --api-proxy استفاده کنید).",
			"Could not reach Firefox servers. Check your internet "+
				"(use --api-proxy if it is blocked)."), 502)
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return apierr.New(locale.Tr("زمان انتظار تمام شد.", "The operation timed out."), 504)
	}
	slog.Error("unexpected error", "err", err)
	return apierr.New(locale.Tr("خطای غیرمنتظره: "+err.Error(), "Unexpected error: "+err.Error()), 500)
}
