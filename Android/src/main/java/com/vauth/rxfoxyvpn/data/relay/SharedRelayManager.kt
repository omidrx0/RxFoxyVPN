package com.vauth.rxfoxyvpn.data.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class SharedRelayManager(
    private val manifestClient: OkHttpClient,
    private val healthChecker: RelayHealthChecker,
    private val transport: RelayTransport,
    private val manifestUrls: List<String> = DEFAULT_MANIFEST_URLS,
    private val now: () -> Long = System::currentTimeMillis,
    private val shuffle: (MutableList<String>) -> Unit = { it.shuffle() },
    private val log: RelayLogger = RelayLogger {},
) {
    private val lock = Any()
    private var urls: List<String> = emptyList()
    private var refreshAt = 0L
    private var active: String? = null
    private var activeUntil = 0L
    private val badUntil = HashMap<String, Long>()

    /**
     * Tries the shared relays for [original] (a Guardian request that was refused with 451). Returns the
     * forwarded upstream response, or null when no relay produced a usable answer.
     */
    fun recover(original: Request, isCanceled: () -> Boolean, deadlineMs: Long): Response? {
        if (!RelayPolicy.isGuardianRequest(original.url) || !RelayPolicy.isReplayable(original)) return null
        return synchronized(lock) {
            load()
            val order = ArrayList(urls).also { shuffle(it) }
            active?.takeIf { now() < activeUntil }?.let { order.add(0, it) }
            val idempotent = RelayPolicy.isIdempotent(original.method)
            val tried = HashSet<String>()
            for (raw in order) {
                if (isCanceled() || now() >= deadlineMs) break
                if (!tried.add(raw) || now() < (badUntil[raw] ?: 0L)) continue
                val relay = RelayPolicy.parseSharedRelayUrl(raw) ?: continue

                val health = healthChecker.check(relay)
                if (!health.ready) {
                    log.log("shared relay ${RelayPolicy.safeName(relay)} unhealthy: ${health.failure} ${health.detail}")
                    badUntil[raw] = now() + BAD_FOR_MS
                    continue
                }
                when (val outcome = transport.send(relay, original, requireMarker = true, isCanceled = isCanceled)) {
                    is RelayOutcome.Forwarded -> {
                        val response = outcome.response
                        log.log("shared relay ${RelayPolicy.safeName(relay)} forwarded HTTP ${response.code}")
                        if (outcome.marked && usableGuardianResponse(original, response)) {
                            active = raw
                            activeUntil = now() + ACTIVE_FOR_MS
                            return@synchronized response
                        }
                        val code = response.code
                        response.close()
                        badUntil[raw] = now() + BAD_FOR_MS
                        if (!idempotent && code != 451) break
                    }
                    is RelayOutcome.Failed -> {
                        log.log("shared relay ${RelayPolicy.safeName(relay)} failed: ${outcome.kind} ${outcome.detail}")
                        if (outcome.kind == RelayFailure.CANCELLED) break
                        badUntil[raw] = now() + BAD_FOR_MS
                        if (!idempotent) break
                    }
                }
            }
            null
        }
    }

    /** Test hook / diagnostics: the candidate count after the last manifest read. Never exposes the addresses. */
    fun candidateCount(): Int = synchronized(lock) { urls.size }

    private fun load() {
        if (now() < refreshAt) return
        refreshAt = now() + FAILED_REFRESH_MS
        for (source in manifestUrls) {
            val request = runCatching { Request.Builder().url(source).header("Accept", "application/json").get().build() }
                .getOrNull() ?: continue
            val text = try {
                manifestClient.newCall(request).execute().use { response ->
                    if (response.code != 200) return@use null
                    val body = response.peekBody(MAX_MANIFEST_BYTES + 1L).string()
                    if (body.length > MAX_MANIFEST_BYTES) null else body
                }
            } catch (_: Exception) {
                null
            } ?: continue
            val root = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val candidates = ArrayList<String>()
            val relays = root.optJSONObject("relays")
            if (relays != null && relays.optBoolean("enabled", false)) {
                val list = relays.optJSONArray("urls")
                if (list != null) {
                    for (i in 0 until list.length()) {
                        val url = RelayPolicy.parseSharedRelayUrl(list.optString(i, "")) ?: continue
                        val normalized = url.toString()
                        if (normalized !in candidates) candidates.add(normalized)
                        if (candidates.size >= MAX_CANDIDATES) break
                    }
                }
            }
            urls = candidates
            badUntil.keys.retainAll(candidates.toSet())
            if (active !in candidates) {
                active = null
                activeUntil = 0L
            }
            refreshAt = now() + REFRESH_MS
            log.log("shared relay list loaded candidates=${candidates.size}")
            return
        }
        // A failed refresh neither invents another source nor erases a list fetched earlier.
    }

    companion object {
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/omidrx00/rx-update-manifests/main/vpn-client/updates.json"
        const val MANIFEST_MIRROR_URL =
            "https://cdn.jsdelivr.net/gh/omidrx00/rx-update-manifests@main/vpn-client/updates.json"
        val DEFAULT_MANIFEST_URLS = listOf(MANIFEST_URL, MANIFEST_MIRROR_URL)

        const val MAX_MANIFEST_BYTES = 512 * 1024
        const val MAX_CANDIDATES = 100
        const val BAD_FOR_MS = 60_000L
        const val ACTIVE_FOR_MS = 10 * 60_000L
        const val REFRESH_MS = 10 * 60_000L
        const val FAILED_REFRESH_MS = 60_000L

        /** Same acceptance test as the reference's `usableGuardianRelayResponse`. */
        fun usableGuardianResponse(request: Request, response: Response): Boolean {
            if (response.header(RelayPolicy.FORWARDED_HEADER) != "1") return false
            if (request.method == "HEAD" && response.code == 200) return true
            return when (response.code) {
                200, 401, 403, 429 ->
                    response.header("Content-Type")?.contains("application/json", ignoreCase = true) == true
                else -> false
            }
        }
    }
}
