package com.vauth.rxfoxyvpn.data.relay

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a relay could not give a usable answer. Distinguishes relay/infrastructure failure from upstream answers. */
enum class RelayFailure {
    /** Relay did not answer in time. */
    TIMEOUT,

    /** DNS / TCP / TLS to the relay failed. */
    CONNECTION,

    /** Relay answered 403 "wrong secret". */
    WRONG_SECRET,

    /** Relay answered 503 "no secret is set". */
    NOT_SET_UP,

    /** Relay refused the request itself (400/403/405 with X-Relay-Error). */
    REFUSED,

    /** Relay is fine but its own upstream fetch failed (502 with X-Relay-Error). */
    UPSTREAM_UNREACHABLE,

    /** A Cloudflare / hosting error page: no relay marker (quota, outage, challenge, ...). */
    RELAY_INFRASTRUCTURE,

    /** The destination is not allowed by [RelayPolicy]; nothing was sent. */
    TARGET_NOT_ALLOWED,

    /** The relay returned more redirects than allowed. */
    TOO_MANY_REDIRECTS,

    /** The request body cannot be replayed. */
    NOT_REPLAYABLE,

    /** The call was cancelled by the caller. */
    CANCELLED,

    /** The address answered, but not with the relay's health document (wrong URL, or an old Worker/PHP file). */
    UNRECOGNISED,
}

sealed class RelayOutcome {
    /**
     * A genuine answer from the destination, forwarded by the relay (including upstream errors such as 401, 403,
     * 429 or even 451 from Mozilla itself). [marked] is true when the Worker's `X-RxVpN-Relay: 1` marker was present.
     */
    class Forwarded(val response: Response, val marked: Boolean) : RelayOutcome()

    /** The relay (or the path to it) failed; no upstream answer exists. [detail] never contains a secret. */
    class Failed(val kind: RelayFailure, val detail: String, val httpStatus: Int? = null) : RelayOutcome()
}

/**
 * Sends one request through one relay using the reference protocol:
 * `GET|POST|... <relay>?secret=S&url=<percent-encoded https target>` with the original method, headers and body.
 * Redirects are NOT followed by the relay (it answers with the 3xx), so this class follows them itself, one hop at a
 * time, through the relay, re-validating every hop against [RelayPolicy].
 *
 * [client] must not have a [RelayFallbackInterceptor] and must not follow redirects. This is what prevents
 * recursive fallback.
 */
class RelayTransport(private val client: OkHttpClient) {

    fun send(
        relay: HttpUrl,
        original: Request,
        requireMarker: Boolean,
        isCanceled: () -> Boolean = { false },
    ): RelayOutcome {
        var target = original.url
        var method = original.method
        var body: RequestBody? = original.body
        var headers = original.headers
        var hops = 0
        while (true) {
            if (isCanceled()) return RelayOutcome.Failed(RelayFailure.CANCELLED, "cancelled")
            if (!RelayPolicy.isTargetAllowed(target)) {
                return RelayOutcome.Failed(RelayFailure.TARGET_NOT_ALLOWED, "destination ${target.host} is not allowed")
            }
            if (body != null && (body.isOneShot() || body.isDuplex())) {
                return RelayOutcome.Failed(RelayFailure.NOT_REPLAYABLE, "request body cannot be replayed")
            }
            val relayUrl = relay.newBuilder()
                .removeAllQueryParameters("url")
                .addEncodedQueryParameter("url", URLEncoder.encode(target.toString(), "UTF-8"))
                .build()
            val builder = Request.Builder().url(relayUrl)
            for (i in 0 until headers.size) {
                val name = headers.name(i)
                val lower = name.lowercase()
                if (lower in RelayPolicy.HOP_HEADERS || lower.startsWith("cf-")) continue
                builder.addHeader(name, headers.value(i))
            }
            val sendBody = when {
                method == "GET" || method == "HEAD" -> null
                body != null -> body
                else -> ByteArray(0).toRequestBody(null)
            }
            builder.method(method, sendBody)

            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                return if (isCanceled()) {
                    RelayOutcome.Failed(RelayFailure.CANCELLED, "cancelled")
                } else {
                    RelayOutcome.Failed(failureFor(e), describe(e))
                }
            }

            val classified = classify(response, requireMarker)
            if (classified != null) {
                response.close()
                return classified
            }
            val marked = response.header(RelayPolicy.FORWARDED_HEADER) == "1"

            if (response.code in REDIRECT_CODES) {
                val location = response.header("Location")
                val next = location?.let { target.resolve(it) }
                if (next == null) return RelayOutcome.Forwarded(response.rebase(original), marked)
                if (++hops > MAX_REDIRECTS) {
                    response.close()
                    return RelayOutcome.Failed(RelayFailure.TOO_MANY_REDIRECTS, "too many redirects")
                }
                response.close()
                // Same rules as OkHttp / browsers: 303 and (301, 302 on POST) become GET without a body;
                // 307 and 308 keep method and body. Credentials never follow a redirect to another host.
                val changeToGet = response.code == 303 ||
                    ((response.code == 301 || response.code == 302) && method == "POST")
                if (changeToGet && method != "GET" && method != "HEAD") {
                    method = "GET"
                    body = null
                    headers = headers.newBuilder()
                        .removeAll("Content-Type").removeAll("Content-Length").build()
                }
                if (next.host != target.host) {
                    headers = headers.newBuilder().removeAll("Authorization").removeAll("Cookie").build()
                }
                target = next
                continue
            }
            return RelayOutcome.Forwarded(response.rebase(original), marked)
        }
    }

    /**
     * Relay-generated failure or hosting-provider error → [RelayOutcome.Failed]; null means "this is an upstream answer".
     * The Worker marks forwarded answers with `X-RxVpN-Relay: 1` and relay refusals with `X-Relay-Error`. The PHP relay
     * sets only `X-Relay-Error`, never the marker, so for a personal relay ([requireMarker] false) a missing marker is
     * accepted unless the answer carries Cloudflare's `cf-ray` (then it is a Cloudflare-generated page, not Mozilla's).
     */
    internal fun classify(response: Response, requireMarker: Boolean): RelayOutcome.Failed? {
        val reason = response.header(RelayPolicy.RELAY_ERROR_HEADER)
        if (reason != null) {
            val status = response.code
            val kind = when {
                status == 403 && reason.contains("wrong secret", ignoreCase = true) -> RelayFailure.WRONG_SECRET
                status == 503 && reason.contains("secret", ignoreCase = true) -> RelayFailure.NOT_SET_UP
                status == 502 -> RelayFailure.UPSTREAM_UNREACHABLE
                else -> RelayFailure.REFUSED
            }
            // The reason text is the relay's own fixed vocabulary, never a secret. It is kept short for logs.
            return RelayOutcome.Failed(kind, "relay refused: HTTP $status ${reason.take(80)}", status)
        }
        if (response.header(RelayPolicy.FORWARDED_HEADER) == "1") return null
        val cloudflareGenerated = response.header("cf-ray") != null || response.header("Server").equals("cloudflare", true)
        if (requireMarker || cloudflareGenerated) {
            return RelayOutcome.Failed(
                RelayFailure.RELAY_INFRASTRUCTURE,
                "answer did not come from the relay (HTTP ${response.code}, no relay marker)",
                response.code,
            )
        }
        return null
    }

    private fun Response.rebase(original: Request): Response = newBuilder().request(original).build()

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5

        fun failureFor(e: IOException): RelayFailure = when (e) {
            is SocketTimeoutException, is InterruptedIOException -> RelayFailure.TIMEOUT
            else -> RelayFailure.CONNECTION
        }

        /** Exception text without the URL (OkHttp messages may include the query string, which holds the secret). */
        fun describe(e: IOException): String = when (e) {
            is UnknownHostException -> "DNS lookup failed"
            is SocketTimeoutException, is InterruptedIOException -> "timed out"
            is ConnectException, is NoRouteToHostException -> "connection failed"
            is SSLException -> "TLS handshake failed"
            else -> e::class.java.simpleName
        }

        /**
         * Whether a direct-request failure happened before any byte of the request reached Mozilla, so that
         * re-sending through a relay cannot duplicate a side effect. GET/HEAD are always safe to resend.
         */
        fun isPreConnectionFailure(e: IOException, method: String): Boolean {
            if (RelayPolicy.isIdempotent(method)) return true
            return when (e) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SSLException -> true
                is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
                else -> false
            }
        }
    }
}
