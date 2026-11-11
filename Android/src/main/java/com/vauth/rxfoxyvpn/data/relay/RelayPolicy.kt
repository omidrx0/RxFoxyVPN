package com.vauth.rxfoxyvpn.data.relay

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Rules shared by every control-plane relay component.
 *
 * Ported from the reference relay (docs/relay/cloudflare/worker.js and docs/relay/php/relay.php of
 * RxVpN client): the same destination allowlist, the same HTTPS-only / no-credentials /
 * no-fragment / port-443 rule, the same response markers.
 *
 * Nothing in this file touches Android, so it is unit-testable on the JVM.
 */
object RelayPolicy {

    /** Exact allowlist of the reference Worker. A host matches itself or any subdomain of an entry. */
    val ALLOWED_DOMAINS: List<String> = listOf(
        "mozilla.org", "mozilla.com", "firefox.com", // accounts, Guardian, server list
        "github.com", "githubusercontent.com", "jsdelivr.net", // geo files, flags
    )

    const val GUARDIAN_HOST = "vpn.mozilla.org"
    const val GUARDIAN_PATH_PREFIX = "/api/v1/fpn/"

    /** Set by the Worker on a response that really came from the target. Not set on relay/Cloudflare errors. */
    const val FORWARDED_HEADER = "X-RxVpN-Relay"

    /** Set by the Worker and the PHP relay when the relay itself refuses (wrong secret, host not allowed, ...). */
    const val RELAY_ERROR_HEADER = "X-Relay-Error"

    /** Added by this app to a response that a recovery path produced (never sent anywhere). */
    const val RECOVERY_HEADER = "X-RxFoxy-Recovery"
    const val RECOVERY_EXHAUSTED_HEADER = "X-RxFoxy-Recovery-Exhausted"

    /** Headers that describe one hop and must not be forwarded (reference Worker's DROP list + PHP's HOP list). */
    val HOP_HEADERS: Set<String> = setOf(
        "host", "connection", "content-length", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade", "forwarded", "x-forwarded-for", "x-forwarded-proto",
        "x-forwarded-host", "x-real-ip", "true-client-ip",
    )

    const val MAX_PERSONAL_URL_LENGTH = 2048
    const val MAX_SHARED_CANDIDATES = 100

    /** Host equals an allowed domain or is a true subdomain of one (".mozilla.org" boundary, never a suffix match). */
    fun isHostAllowed(host: String): Boolean {
        val h = host.lowercase()
        return ALLOWED_DOMAINS.any { domain -> h == domain || h.endsWith(".$domain") }
    }

    /**
     * Whether [url] may be sent through a relay at all: HTTPS, no credentials, no fragment, port 443,
     * allowlisted host. Mirrors the Worker's checks exactly.
     */
    fun isTargetAllowed(url: HttpUrl): Boolean =
        url.isHttps &&
            url.username.isEmpty() && url.password.isEmpty() &&
            url.fragment == null &&
            url.port == 443 &&
            isHostAllowed(url.host)

    fun isGuardianRequest(url: HttpUrl): Boolean =
        url.isHttps && url.host == GUARDIAN_HOST && url.port == 443 &&
            url.encodedPath.startsWith(GUARDIAN_PATH_PREFIX)

    /** A request whose body can be sent a second time. One-shot / duplex bodies cannot be replayed. */
    fun isReplayable(request: Request): Boolean {
        val body = request.body ?: return true
        return !body.isOneShot() && !body.isDuplex()
    }

    fun isIdempotent(method: String): Boolean = method == "GET" || method == "HEAD"

    /**
     * The Guardian "doubled leading slash" request URL, or null when the path is already doubled.
     *
     * Reference behaviour (core/internal/guardian/region_recovery.go, doc/OPERATOR.md): after an HTTP 451
     * from Guardian, the identical request is retried once with ONE extra leading slash on the path,
     * `//api/v1/fpn/...`. Mozilla's CDN edge applies its region rule to the literal path and does not
     * match the doubled form, while the origin collapses `//` to the same route. Method, headers, body and
     * query are unchanged. No other character of the path is touched.
     */
    fun doubledLeadingSlash(url: HttpUrl): HttpUrl? {
        val path = url.encodedPath
        if (!path.startsWith("/") || path.startsWith("//")) return null
        val out = StringBuilder()
        out.append(url.scheme).append("://").append(url.host)
        if (url.port != HttpUrl.defaultPort(url.scheme)) out.append(':').append(url.port)
        out.append('/').append(path)
        url.encodedQuery?.let { out.append('?').append(it) }
        return out.toString().toHttpUrlOrNull()
    }

    enum class PersonalUrlError { EMPTY, TOO_LONG, INVALID, NOT_HTTPS, CREDENTIALS, FRAGMENT, RESERVED_PARAMETER, NO_SECRET }

    class PersonalUrlException(val error: PersonalUrlError) : Exception(error.name)

    /**
     * Validates a user-entered personal relay URL such as `https://worker.example.workers.dev/?secret=XYZ`.
     * The query (including `secret`) is preserved untouched. `url` and `health` are reserved by the relay
     * protocol, so a URL that already carries them is rejected.
     */
    fun validatePersonalRelayUrl(raw: String): Result<HttpUrl> {
        val text = raw.trim()
        fun fail(e: PersonalUrlError) = Result.failure<HttpUrl>(PersonalUrlException(e))
        if (text.isEmpty()) return fail(PersonalUrlError.EMPTY)
        if (text.length > MAX_PERSONAL_URL_LENGTH) return fail(PersonalUrlError.TOO_LONG)
        if (text.startsWith("http://", ignoreCase = true)) return fail(PersonalUrlError.NOT_HTTPS)
        val url = text.toHttpUrlOrNull() ?: return fail(PersonalUrlError.INVALID)
        if (!url.isHttps) return fail(PersonalUrlError.NOT_HTTPS)
        if (url.host.isEmpty()) return fail(PersonalUrlError.INVALID)
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return fail(PersonalUrlError.CREDENTIALS)
        if (url.fragment != null) return fail(PersonalUrlError.FRAGMENT)
        if (url.queryParameter("url") != null || url.queryParameter("health") != null) {
            return fail(PersonalUrlError.RESERVED_PARAMETER)
        }
        if (url.queryParameter("secret").isNullOrEmpty()) return fail(PersonalUrlError.NO_SECRET)
        return Result.success(url)
    }

    /** Candidate shared-relay URL from the manifest: HTTPS, host, no credentials/fragment, no reserved params. */
    fun parseSharedRelayUrl(raw: String, allowLoopback: Boolean = false): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        val isHttps = url.scheme == "https"
        val isLoopbackHttp = allowLoopback && url.scheme == "http" &&
            (url.host == "127.0.0.1" || url.host == "localhost")
        if (!isHttps && !isLoopbackHttp || url.host.isEmpty()) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return null
        if (url.queryParameter("url") != null || url.queryParameter("health") != null) return null
        return url
    }

    /** Safe text for logs: scheme and host only. Never the path or query (which carries the secret). */
    fun safeName(url: HttpUrl): String = url.host
}
