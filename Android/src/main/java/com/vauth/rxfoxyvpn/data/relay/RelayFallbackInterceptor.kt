package com.vauth.rxfoxyvpn.data.relay

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Control-plane recovery, installed once on [com.vauth.rxfoxyvpn.data.ControlPlaneHttp.client] as an OkHttp
 * *application* interceptor. Both `execute()` and `enqueue()` calls pass through it, so the synchronous Guardian /
 * server-list code and the coroutine-based FxA code share one behaviour.
 *
 * Why an application interceptor (and not a network interceptor or a call wrapper):
 *  - it sees the request before OkHttp's own retry/redirect/cookie handling and may call `proceed` several times;
 *  - it runs once per logical call, so redirects and OkHttp's silent connection retries are not counted as failures;
 *  - request bodies built from byte arrays are replayable; one-shot / duplex bodies are never touched
 *    ([RelayPolicy.isReplayable]) and simply pass through.
 *
 * Order for a request whose URL passes [RelayPolicy.isTargetAllowed] (anything else passes through untouched):
 *  1. Direct request.
 *  2. Direct HTTP 451 on a Guardian request → the same request once with a doubled leading slash (`//api/v1/fpn/...`).
 *  3. Still 451 (any allowed host), or a direct failure before the request reached Mozilla:
 *     personal relay (if enabled and valid), then — Guardian only, and not in personal-only mode — a shared relay.
 *  4. Nothing worked: the ORIGINAL 451 response is returned (readable body) with a marker header, so the caller
 *     can tell "refused and no relay helped" from "refused".
 *
 * Not handled here on purpose: HTTP 406 (Fastly's automatic challenge, solved by FastlyChallengeSolver), 401/403
 * (credentials), 429 (quota), 5xx. They are returned as they are.
 *
 * Recursion: the relay, health and manifest requests are issued by clients that do not contain this interceptor,
 * and a relay URL is never an allowlisted host, so a relayed request cannot re-enter this code.
 *
 * Budget: the whole recovery is bounded by [RECOVERY_BUDGET_MS] and, inside it, by each relay client's own
 * connect/read/call timeouts and by the number of candidates. OkHttp's call timeout (30 s on the shared client)
 * still applies to the outer call; if it fires, the caller gets an IOException, never a hang.
 */
class RelayFallbackInterceptor(
    private val config: RelayConfigSource,
    private val transport: RelayTransport,
    private val shared: SharedRelayManager,
    private val log: RelayLogger = RelayLogger {},
    private val now: () -> Long = System::currentTimeMillis,
) : Interceptor {

    @Volatile
    private var personalFirstUntil = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = request.url
        if (!RelayPolicy.isTargetAllowed(target) || !RelayPolicy.isReplayable(request)) return chain.proceed(request)

        val cfg = config.current()
        val personal = cfg.personalRelay
        val guardian = RelayPolicy.isGuardianRequest(target)
        val deadline = now() + RECOVERY_BUDGET_MS
        val canceled = { chain.call().isCanceled() }

        // After a recent direct connection failure the personal relay goes first, so a filtered network does not
        // cost a full connect timeout per request. Falls back to the direct path if the relay does not answer.
        if (personal != null && now() < personalFirstUntil) {
            viaPersonal(personal, request, canceled)?.let { return it }
        }

        val first = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (canceled() || personal == null || !RelayTransport.isPreConnectionFailure(e, request.method)) throw e
            log.log("direct request to ${target.host} failed (${RelayTransport.describe(e)}); trying personal relay")
            val relayed = viaPersonal(personal, request, canceled)
            if (relayed != null) {
                personalFirstUntil = now() + PERSONAL_FIRST_MS
                return relayed
            }
            throw e
        }
        if (first.code != 451) return first

        // HTTP 451: keep the original answer readable in case nothing recovers it.
        val original = first.newBuilder().body(first.peekBody(MAX_KEPT_BODY)).build()
        first.close()
        log.log("HTTP 451 from ${target.host}")

        if (guardian && !canceled()) {
            RelayPolicy.doubledLeadingSlash(target)?.let { doubled ->
                try {
                    val retry = chain.proceed(request.newBuilder().url(doubled).build())
                    if (retry.code != 451) {
                        log.log("Guardian HTTP 451 recovered by the doubled-leading-slash retry (HTTP ${retry.code})")
                        return retry.newBuilder().request(request).header(RelayPolicy.RECOVERY_HEADER, "double-slash").build()
                    }
                    retry.close()
                } catch (e: IOException) {
                    if (canceled()) throw e
                    log.log("doubled-leading-slash retry failed: ${RelayTransport.describe(e)}")
                }
            }
        }

        val attempted = ArrayList<String>(2)
        if (personal != null && now() < deadline && !canceled()) {
            attempted.add("personal")
            viaPersonal(personal, request, canceled)?.let { return it }
        }
        if (guardian && cfg.sharedAllowed && now() < deadline && !canceled()) {
            attempted.add("shared")
            shared.recover(request, canceled, deadline)?.let {
                return it.newBuilder().request(request).header(RelayPolicy.RECOVERY_HEADER, "shared-relay").build()
            }
        }
        if (canceled()) {
            original.close()
            throw IOException("Canceled")
        }
        log.log("no relay recovered the HTTP 451 (tried: ${attempted.ifEmpty { listOf("none") }.joinToString(",")})")
        return original.newBuilder()
            .header(RelayPolicy.RECOVERY_EXHAUSTED_HEADER, attempted.joinToString(",").ifEmpty { "none" })
            .build()
    }

    /** One attempt through the personal relay. A forwarded 451 means the relay's exit is refused too: not recovered. */
    private fun viaPersonal(relay: HttpUrl, request: Request, canceled: () -> Boolean): Response? {
        return when (val outcome = transport.send(relay, request, requireMarker = false, isCanceled = canceled)) {
            is RelayOutcome.Forwarded -> {
                val response = outcome.response
                log.log("personal relay ${RelayPolicy.safeName(relay)} forwarded HTTP ${response.code}")
                if (response.code == 451) {
                    response.close()
                    null
                } else {
                    response.newBuilder().header(RelayPolicy.RECOVERY_HEADER, "personal-relay").build()
                }
            }
            is RelayOutcome.Failed -> {
                log.log("personal relay ${RelayPolicy.safeName(relay)} failed: ${outcome.kind} ${outcome.detail}")
                null
            }
        }
    }

    companion object {
        const val RECOVERY_BUDGET_MS = 24_000L
        const val PERSONAL_FIRST_MS = 10 * 60_000L
        const val MAX_KEPT_BODY = 64L * 1024L
    }
}
