package com.vauth.rxfoxyvpn.data.error

import com.vauth.rxfoxyvpn.data.AppLogger
import com.vauth.rxfoxyvpn.data.GuardianHttpError
import com.vauth.rxfoxyvpn.data.GuardianRegionBlockedError
import com.vauth.rxfoxyvpn.data.FxaApiError
import com.vauth.rxfoxyvpn.data.FxaRefreshFailure
import com.vauth.rxfoxyvpn.data.LanguageManager
import com.vauth.rxfoxyvpn.data.QuotaExceededError
import com.vauth.rxfoxyvpn.data.TokenInvalidError
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLException

private const val TAG = "FoxyError"

/** Which part of the app produced the error. Logged and shown (section label), never a stack trace. */
enum class ErrorSection {
    VPN,
    SERVER,
    ROUTING,
    DNS,
    ACCOUNTS,
    LOGIN,
    BACKUP,
    STORAGE,
    SYSTEM,
    UNKNOWN,
}

/**
 * Stable, user-facing error codes. Every visible error in the app maps to one of these;
 * the raw exception stays in the log. Codes are part of the translation keys:
 * `errors.<code>.title`, `errors.<code>.desc` and (optional) `errors.<code>.hint`.
 *
 * The enum carries [retryable] so the UI can offer "Try again" only when it makes sense.
 */
enum class ErrorCode(val retryable: Boolean) {
    CONNECTION_FAILED(true),
    SERVER_UNREACHABLE(true),
    AUTH_FAILED(false),
    ACCOUNT_EXPIRED(false),
    NO_ACTIVE_ACCOUNT(false),
    NO_SERVER_SELECTED(false),
    NETWORK_UNAVAILABLE(true),
    DNS_FAILED(true),
    CONNECTION_TIMEOUT(true),
    TLS_HANDSHAKE_FAILED(true),
    INVALID_CONFIGURATION(false),
    CAPTCHA_REQUIRED(true),
    SERVER_OVERLOADED(true),
    VPN_PERMISSION_DENIED(false),
    UNKNOWN_ERROR(true),

    // Extended codes used by the account / Guardian / backup flows.
    QUOTA_EXHAUSTED(false),
    NOT_ENTITLED(false),
    TOKEN_INVALID(false),
    REAUTH_REQUIRED(false),
    NO_USABLE_ACCOUNT(false),
    ACCOUNT_RESERVED(true),
    REGION_BLOCKED(false),
    RATE_LIMITED(true),
    CAPTCHA_BLOCKED(true),
    BACKUP_INVALID(false),
    BACKUP_EMPTY(false),
    BACKUP_FAILED(true),
    IMPORT_FAILED(true),
    EXPORT_FAILED(true),
    SERVER_LIST_FAILED(true),
    STORAGE_ERROR(true),
    ;

    val key: String get() = name.lowercase()
}

/**
 * One classified failure: a stable code for the UI plus everything the technical log needs.
 * [cause] and [detail] must never reach the UI — they go to [FoxyLogger] only.
 */
data class FoxyError(
    val code: ErrorCode,
    val section: ErrorSection,
    val operation: String,
    val cause: Throwable? = null,
    /** Technical detail for the log only (never shown). Callers must not put secrets in it. */
    val detail: String? = null,
) {
    val retryable: Boolean get() = code.retryable

    fun title(): String = LanguageManager.t(
        "errors.${code.key}.title",
        fallbackTitles[code] ?: "Something went wrong",
    )

    fun description(): String = LanguageManager.t(
        "errors.${code.key}.desc",
        fallbackDescriptions[code] ?: "The operation could not be completed.",
    )

    /** A short "what you can do about it" line, or null when the UI should not offer one. */
    fun hint(): String? = fallbackHints[code]?.let { fallback ->
        LanguageManager.t("errors.${code.key}.hint", fallback)
    }

    /** One-line user-facing text (title + suggestion separator). */
    fun userMessage(): String {
        val h = hint()
        return if (h.isNullOrBlank()) title() else "${title()} — $h"
    }

    companion object {
        private val fallbackTitles: Map<ErrorCode, String> = mapOf(
            ErrorCode.CONNECTION_FAILED to "Connection failed",
            ErrorCode.SERVER_UNREACHABLE to "Server unreachable",
            ErrorCode.AUTH_FAILED to "Authentication failed",
            ErrorCode.ACCOUNT_EXPIRED to "Account expired",
            ErrorCode.NO_ACTIVE_ACCOUNT to "No active account",
            ErrorCode.NO_SERVER_SELECTED to "No server selected",
            ErrorCode.NETWORK_UNAVAILABLE to "Network unavailable",
            ErrorCode.DNS_FAILED to "DNS failed",
            ErrorCode.CONNECTION_TIMEOUT to "Connection timed out",
            ErrorCode.TLS_HANDSHAKE_FAILED to "Secure connection failed",
            ErrorCode.INVALID_CONFIGURATION to "Invalid configuration",
            ErrorCode.CAPTCHA_REQUIRED to "CAPTCHA required",
            ErrorCode.SERVER_OVERLOADED to "Server unavailable",
            ErrorCode.VPN_PERMISSION_DENIED to "VPN permission denied",
            ErrorCode.UNKNOWN_ERROR to "Unexpected error",
            ErrorCode.QUOTA_EXHAUSTED to "Monthly quota exhausted",
            ErrorCode.NOT_ENTITLED to "Account not eligible",
            ErrorCode.TOKEN_INVALID to "Session rejected",
            ErrorCode.REAUTH_REQUIRED to "Sign-in required",
            ErrorCode.NO_USABLE_ACCOUNT to "No account available",
            ErrorCode.ACCOUNT_RESERVED to "Account resting until quota reset",
            ErrorCode.REGION_BLOCKED to "Region blocked",
            ErrorCode.RATE_LIMITED to "Too many attempts",
            ErrorCode.CAPTCHA_BLOCKED to "Security check failed",
            ErrorCode.BACKUP_INVALID to "Invalid backup file",
            ErrorCode.BACKUP_EMPTY to "No accounts in backup",
            ErrorCode.BACKUP_FAILED to "Backup failed",
            ErrorCode.IMPORT_FAILED to "Import failed",
            ErrorCode.EXPORT_FAILED to "Export failed",
            ErrorCode.SERVER_LIST_FAILED to "Could not load servers",
            ErrorCode.STORAGE_ERROR to "Storage error",
        )
        private val fallbackDescriptions: Map<ErrorCode, String> = mapOf(
            ErrorCode.CONNECTION_FAILED to "The VPN tunnel could not be established.",
            ErrorCode.SERVER_UNREACHABLE to "The VPN server did not answer.",
            ErrorCode.AUTH_FAILED to "The account credentials were not accepted.",
            ErrorCode.ACCOUNT_EXPIRED to "Your Firefox session expired. Sign in again.",
            ErrorCode.NO_ACTIVE_ACCOUNT to "Add a Firefox account to connect.",
            ErrorCode.NO_SERVER_SELECTED to "Choose a server before connecting.",
            ErrorCode.NETWORK_UNAVAILABLE to "Your device has no usable internet connection.",
            ErrorCode.DNS_FAILED to "The DNS resolver did not answer.",
            ErrorCode.CONNECTION_TIMEOUT to "The operation timed out.",
            ErrorCode.TLS_HANDSHAKE_FAILED to "The secure handshake with the server failed.",
            ErrorCode.INVALID_CONFIGURATION to "A setting is invalid and the connection cannot start.",
            ErrorCode.CAPTCHA_REQUIRED to "Mozilla asked for a security check.",
            ErrorCode.SERVER_OVERLOADED to "The server is overloaded right now.",
            ErrorCode.VPN_PERMISSION_DENIED to "Android did not grant the VPN permission.",
            ErrorCode.UNKNOWN_ERROR to "Something went wrong. Try again.",
            ErrorCode.QUOTA_EXHAUSTED to "The account used its monthly Firefox quota.",
            ErrorCode.NOT_ENTITLED to "Guardian refused this account.",
            ErrorCode.TOKEN_INVALID to "The server rejected the account token.",
            ErrorCode.REAUTH_REQUIRED to "This account must be signed in again.",
            ErrorCode.NO_USABLE_ACCOUNT to "Every account is waiting for quota reset or sign-in.",
            ErrorCode.ACCOUNT_RESERVED to "The account will be back after its quota resets.",
            ErrorCode.REGION_BLOCKED to "Mozilla refused this region (HTTP 451).",
            ErrorCode.RATE_LIMITED to "Too many requests. Wait a moment.",
            ErrorCode.CAPTCHA_BLOCKED to "The security check could not be completed.",
            ErrorCode.BACKUP_INVALID to "This file is not a valid RxFoxyVPN backup.",
            ErrorCode.BACKUP_EMPTY to "The backup file contains no accounts.",
            ErrorCode.BACKUP_FAILED to "The backup could not be created.",
            ErrorCode.IMPORT_FAILED to "The backup could not be restored.",
            ErrorCode.EXPORT_FAILED to "The backup could not be saved.",
            ErrorCode.SERVER_LIST_FAILED to "The server list could not be loaded.",
            ErrorCode.STORAGE_ERROR to "Secure storage could not be read or written.",
        )
        private val fallbackHints: Map<ErrorCode, String> = mapOf(
            ErrorCode.CONNECTION_FAILED to "Try again.",
            ErrorCode.SERVER_UNREACHABLE to "Try another server.",
            ErrorCode.AUTH_FAILED to "Check the email and password.",
            ErrorCode.ACCOUNT_EXPIRED to "Sign in again.",
            ErrorCode.NO_ACTIVE_ACCOUNT to "Open Accounts and add one.",
            ErrorCode.NO_SERVER_SELECTED to "Open Servers and pick one.",
            ErrorCode.NETWORK_UNAVAILABLE to "Check Wi-Fi or mobile data.",
            ErrorCode.DNS_FAILED to "Check the DNS settings.",
            ErrorCode.CONNECTION_TIMEOUT to "Try again later.",
            ErrorCode.TLS_HANDSHAKE_FAILED to "Check the date and time, then retry.",
            ErrorCode.INVALID_CONFIGURATION to "Check the app settings.",
            ErrorCode.CAPTCHA_REQUIRED to "Complete the security check.",
            ErrorCode.SERVER_OVERLOADED to "Try again in a few minutes.",
            ErrorCode.VPN_PERMISSION_DENIED to "Allow VPN permission.",
            ErrorCode.UNKNOWN_ERROR to "Try again.",
            ErrorCode.QUOTA_EXHAUSTED to "Use another account or wait for reset.",
            ErrorCode.NOT_ENTITLED to "Check the account in Accounts.",
            ErrorCode.TOKEN_INVALID to "Sign in again.",
            ErrorCode.REAUTH_REQUIRED to "Open Accounts and sign in.",
            ErrorCode.NO_USABLE_ACCOUNT to "Add another account.",
            ErrorCode.ACCOUNT_RESERVED to "It recovers automatically.",
            ErrorCode.REGION_BLOCKED to "Enable the shared relay in Settings.",
            ErrorCode.RATE_LIMITED to "Wait a minute and retry.",
            ErrorCode.CAPTCHA_BLOCKED to "Try again.",
            ErrorCode.BACKUP_INVALID to "Pick a backup .zip file.",
            ErrorCode.BACKUP_EMPTY to "Pick another backup.",
            ErrorCode.BACKUP_FAILED to "Try again.",
            ErrorCode.IMPORT_FAILED to "Try again.",
            ErrorCode.EXPORT_FAILED to "Try again.",
            ErrorCode.SERVER_LIST_FAILED to "Check the internet and retry.",
            ErrorCode.STORAGE_ERROR to "Restart the app.",
        )
    }
}

/** An exception that already carries a [FoxyError]; thrown across boundaries, classified once. */
class FoxyException(val error: FoxyError) : Exception(error.detail ?: error.title())

/**
 * Throwable → [FoxyError]. One place knows the whole app; every section classifies here
 * so messages stay consistent and raw exceptions never reach the UI.
 */
object ErrorClassifier {

    fun classify(
        throwable: Throwable,
        section: ErrorSection = ErrorSection.UNKNOWN,
        operation: String = "unknown",
    ): FoxyError {
        if (throwable is FoxyException) return throwable.error
        val code = when (throwable) {
            is CancellationException -> ErrorCode.UNKNOWN_ERROR // caller decides to surface or drop
            is TimeoutCancellationException -> ErrorCode.CONNECTION_TIMEOUT
            is SocketTimeoutException -> ErrorCode.CONNECTION_TIMEOUT
            is UnknownHostException -> ErrorCode.NETWORK_UNAVAILABLE
            is NoRouteToHostException -> ErrorCode.NETWORK_UNAVAILABLE
            is ConnectException -> ErrorCode.SERVER_UNREACHABLE
            is SSLException -> ErrorCode.TLS_HANDSHAKE_FAILED
            is GeneralSecurityException -> ErrorCode.TLS_HANDSHAKE_FAILED
            is GuardianRegionBlockedError -> ErrorCode.REGION_BLOCKED
            is GuardianHttpError -> when (throwable.statusCode) {
                400, 401, 403 -> ErrorCode.AUTH_FAILED
                404 -> ErrorCode.SERVER_UNREACHABLE
                429 -> ErrorCode.QUOTA_EXHAUSTED
                451 -> ErrorCode.REGION_BLOCKED
                in 500..599 -> ErrorCode.SERVER_OVERLOADED
                else -> ErrorCode.CONNECTION_FAILED
            }
            is QuotaExceededError -> ErrorCode.QUOTA_EXHAUSTED
            is TokenInvalidError -> ErrorCode.TOKEN_INVALID
            is FxaRefreshFailure -> if (throwable.permanent) ErrorCode.ACCOUNT_EXPIRED else ErrorCode.NETWORK_UNAVAILABLE
            is FxaApiError -> classifyFxa(throwable)
            is com.vauth.rxfoxyvpn.data.FastlyChallengeError -> ErrorCode.CAPTCHA_BLOCKED
            is com.vauth.rxfoxyvpn.data.CaptchaCancelledException -> ErrorCode.CAPTCHA_REQUIRED
            is JSONException -> ErrorCode.INVALID_CONFIGURATION
            is IllegalArgumentException -> ErrorCode.INVALID_CONFIGURATION
            is IllegalStateException -> when {
                throwable.message?.contains("no usable account", ignoreCase = true) == true ->
                    ErrorCode.NO_USABLE_ACCOUNT
                throwable.message?.contains("no server selected", ignoreCase = true) == true ->
                    ErrorCode.NO_SERVER_SELECTED
                throwable.message?.contains("not signed in", ignoreCase = true) == true ->
                    ErrorCode.NO_ACTIVE_ACCOUNT
                else -> ErrorCode.UNKNOWN_ERROR
            }
            is IOException -> ErrorCode.NETWORK_UNAVAILABLE
            is SecurityException -> ErrorCode.VPN_PERMISSION_DENIED
            else -> ErrorCode.UNKNOWN_ERROR
        }
        return FoxyError(
            code = code,
            section = section,
            operation = operation,
            cause = throwable,
            detail = throwable.message,
        )
    }

    /** FxA (Firefox Accounts) errno/HTTP mapping — same semantics as the reference client. */
    private fun classifyFxa(error: FxaApiError): ErrorCode = when (error.errno) {
        107 -> ErrorCode.AUTH_FAILED          // invalid parameter (server-side)
        104, 138 -> ErrorCode.AUTH_FAILED     // unverified account/session (surfaced only when not handled internally)
        114 -> ErrorCode.RATE_LIMITED         // throttled
        108 -> ErrorCode.ACCOUNT_EXPIRED      // invalid refresh token
        109 -> ErrorCode.ACCOUNT_EXPIRED      // invalid token
        120 -> ErrorCode.AUTH_FAILED          // account not found (handled by the case-insensitive retry)
        125 -> ErrorCode.AUTH_FAILED          // login blocked — needs unblock code
        201 -> ErrorCode.SERVER_OVERLOADED    // service unavailable
        else -> when (error.statusCode) {
            400, 401, 403 -> ErrorCode.AUTH_FAILED
            404 -> ErrorCode.AUTH_FAILED
            429 -> ErrorCode.RATE_LIMITED
            451 -> ErrorCode.REGION_BLOCKED
            in 500..599 -> ErrorCode.SERVER_OVERLOADED
            null -> ErrorCode.NETWORK_UNAVAILABLE
            else -> ErrorCode.UNKNOWN_ERROR
        }
    }

    /** Convenience for call sites that hold a message-only failure: prefer classifying the cause. */
    fun classifyMessage(message: String?, section: ErrorSection, operation: String): FoxyError {
        val text = message?.lowercase().orEmpty()
        val code = when {
            "timeout" in text -> ErrorCode.CONNECTION_TIMEOUT
            "dns" in text -> ErrorCode.DNS_FAILED
            "quota" in text -> ErrorCode.QUOTA_EXHAUSTED
            "permission" in text -> ErrorCode.VPN_PERMISSION_DENIED
            "not signed in" in text || "sign in" in text -> ErrorCode.NO_ACTIVE_ACCOUNT
            else -> ErrorCode.UNKNOWN_ERROR
        }
        return FoxyError(code, section, operation, detail = message)
    }
}

/**
 * Structured error logging. Full technical detail (code, section, operation, exception and stack
 * trace) goes to the log; secrets are scrubbed on the way. Use [log] everywhere; never print
 * exceptions with raw [AppLogger] calls from UI code.
 */
object FoxyLogger {

    private val secretPatterns = listOf(
        Regex("""(Bearer\s+)[A-Za-z0-9._\-~+/]+=*"""),
        Regex("""("?(access_token|refresh_token|id_token|password|authPW|unblockCode|secret|token|authorization)"?\s*[:=]\s*"?)[^",\s}]+""", RegexOption.IGNORE_CASE),
        Regex("""(Hawk\s+id="?)[^",\s]+"""),
    )

    fun scrub(text: String): String {
        var out = text
        secretPatterns.forEach { pattern ->
            out = pattern.replace(out) { match ->
                val prefix = match.groupValues.getOrNull(1) ?: ""
                prefix + "<redacted>"
            }
        }
        return out
    }

    /** Logs a classified error with everything needed to diagnose it. */
    fun log(error: FoxyError, message: String? = null) {
        val cause = error.cause
        val rendered = buildString {
            append("code=").append(error.code.name)
            append(" section=").append(error.section.name)
            append(" op=").append(error.operation)
            append(" retryable=").append(error.retryable)
            if (!message.isNullOrBlank()) append(" msg=").append(scrub(message))
            if (!error.detail.isNullOrBlank()) append(" detail=").append(scrub(error.detail))
            cause?.message?.takeIf { it.isNotBlank() }?.let { append(" cause=").append(scrub(it)) }
        }
        if (cause != null) {
            AppLogger.e(TAG, rendered, ScrubbedThrowable(cause))
        } else {
            AppLogger.w(TAG, rendered)
        }
    }

    /** Logs a classified error at warn level (expected, handled failures). */
    fun logWarn(error: FoxyError, message: String? = null) {
        val rendered = buildString {
            append("code=").append(error.code.name)
            append(" section=").append(error.section.name)
            append(" op=").append(error.operation)
            if (!message.isNullOrBlank()) append(" msg=").append(scrub(message))
            error.cause?.message?.takeIf { it.isNotBlank() }?.let { append(" cause=").append(scrub(it)) }
        }
        AppLogger.w(TAG, rendered)
    }

    /** A throwable whose message and stack frames are scrubbed before hitting the log. */
    private class ScrubbedThrowable(cause: Throwable) : RuntimeException(scrub(cause.message ?: cause.toString()), cause.cause)
}
