package com.vauth.rxfoxyvpn.data.manifest

import com.vauth.rxfoxyvpn.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "RemoteManifestRepo"

/**
 * Thread-safe repository for fetching, validating, and caching `updates.json`
 * (exclusively for Relay configuration and User Announcements).
 *
 * Guarantees:
 *  - Primary URL is queried first; Fallback CDN URL is queried ONLY if Primary fails
 *    or returns an invalid payload.
 *  - Valid responses are persisted in [cacheBackend] so offline startups still have
 *    the last valid Relays and Announcements.
 *  - Failed refreshes NEVER wipe or corrupt the previously cached valid Manifest.
 *  - Concurrent callers (e.g. HomeScreen announcement check + HTTP 451 relay recovery)
 *    coalesce onto a single in-flight fetch via single-flight coordination.
 *  - Suspending calls always execute on [Dispatchers.IO], never on the Main thread.
 */
class RemoteManifestRepository(
    private val httpClient: () -> OkHttpClient,
    private val cacheBackend: ManifestCacheBackend,
    private val urlProvider: () -> List<String> = { ManifestConfig.resolveUrls() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val refreshTtlMs: Long = ManifestConfig.REFRESH_TTL_MS,
    private val retryCooldownMs: Long = ManifestConfig.RETRY_COOLDOWN_MS,
    private val allowLoopbackHttpRelays: Boolean = false,
) {
    private val stateLock = Any()

    @Volatile
    private var cachedManifest: RemoteManifest? = loadInitialCache()

    @Volatile
    private var nextRefreshAllowedAtMs: Long = 0L

    private var inFlightLatch: CountDownLatch? = null

    private val _manifestFlow = MutableStateFlow(cachedManifest)
    val manifestFlow: StateFlow<RemoteManifest?> = _manifestFlow.asStateFlow()

    private fun loadInitialCache(): RemoteManifest? {
        val raw = runCatching { cacheBackend.readCachedJson() }.getOrNull() ?: return null
        val savedAt = runCatching { cacheBackend.readSavedAtMs() }.getOrDefault(0L)
        return ManifestParser.parse(raw, savedAt, allowLoopbackHttpRelays).getOrNull()
    }

    /** Returns the latest known valid Manifest (from network or disk cache), or [RemoteManifest.EMPTY]. */
    fun currentOrEmpty(): RemoteManifest = cachedManifest ?: RemoteManifest.EMPTY

    /** Returns the latest known valid Manifest, or `null` if none has ever been loaded. */
    fun currentOrNull(): RemoteManifest? = cachedManifest

    /**
     * Refreshes the Manifest on [Dispatchers.IO] unless the in-memory copy is still within TTL
     * (unless [force] is `true`). Never throws; returns the latest valid Manifest or [RemoteManifest.EMPTY].
     */
    suspend fun refresh(force: Boolean = false): RemoteManifest = withContext(Dispatchers.IO) {
        getOrRefreshBlocking(force)
    }

    /**
     * Blocking variant strictly for background worker threads (e.g. OkHttp [SharedRelayManager]).
     */
    fun getOrRefreshBlocking(force: Boolean = false): RemoteManifest {
        var waitLatch: CountDownLatch? = null
        var isLeader = false

        synchronized(stateLock) {
            val current = cachedManifest
            if (!force && current != null && now() < nextRefreshAllowedAtMs) {
                return current
            }
            if (!force && current == null && now() < nextRefreshAllowedAtMs) {
                return RemoteManifest.EMPTY
            }
            val existingLatch = inFlightLatch
            if (existingLatch != null) {
                waitLatch = existingLatch
            } else {
                val created = CountDownLatch(1)
                inFlightLatch = created
                isLeader = true
            }
        }

        if (!isLeader) {
            runCatching {
                waitLatch?.await(ManifestConfig.CALL_TIMEOUT_SECONDS * 2 + 2, TimeUnit.SECONDS)
            }
            return cachedManifest ?: RemoteManifest.EMPTY
        }

        try {
            val urls = runCatching { urlProvider() }.getOrDefault(emptyList())
            val client = runCatching { httpClient() }.getOrNull()

            if (client != null && urls.isNotEmpty()) {
                for ((index, sourceUrl) in urls.withIndex()) {
                    val sourceLabel = if (index == 0) "primary" else "fallback-$index"
                    val fetched = fetchAndValidateSingleSource(client, sourceUrl, sourceLabel)
                    if (fetched != null) {
                        val (rawJson, parsed) = fetched
                        val timestamp = now()
                        runCatching { cacheBackend.saveValidManifest(rawJson, timestamp) }
                        synchronized(stateLock) {
                            cachedManifest = parsed
                            nextRefreshAllowedAtMs = timestamp + refreshTtlMs
                            _manifestFlow.value = parsed
                        }
                        AppLogger.i(
                            TAG,
                            "manifest loaded source=$sourceLabel relaysEnabled=${parsed.relays.enabled} " +
                                "relayCount=${parsed.relays.urls.size} announcements=${parsed.announcements.size}"
                        )
                        return parsed
                    }
                }
            }

            // Both Primary and Fallback failed: preserve last valid cache untouched.
            synchronized(stateLock) {
                nextRefreshAllowedAtMs = now() + retryCooldownMs
            }
            AppLogger.w(TAG, "manifest refresh failed on all sources; retaining cached=${cachedManifest != null}")
            return cachedManifest ?: RemoteManifest.EMPTY
        } finally {
            synchronized(stateLock) {
                inFlightLatch?.countDown()
                inFlightLatch = null
            }
        }
    }

    private fun fetchAndValidateSingleSource(
        client: OkHttpClient,
        sourceUrl: String,
        sourceLabel: String,
    ): Pair<String, RemoteManifest>? {
        val req = try {
            Request.Builder()
                .url(sourceUrl)
                .get()
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()
        } catch (_: Exception) {
            return null
        }

        val rawBody = try {
            client.newCall(req).execute().use { resp ->
                if (resp.code != 200) {
                    AppLogger.w(TAG, "manifest $sourceLabel returned HTTP ${resp.code}")
                    return null
                }
                val stream = resp.body?.byteStream() ?: return null
                val bytes = stream.readNBytes(ManifestConfig.MAX_MANIFEST_BYTES + 1)
                if (bytes.isEmpty() || bytes.size > ManifestConfig.MAX_MANIFEST_BYTES) {
                    AppLogger.w(TAG, "manifest $sourceLabel rejected due to payload size=${bytes.size}")
                    return null
                }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "manifest $sourceLabel transport failure: ${e.javaClass.simpleName}")
            return null
        }

        val parsed = ManifestParser.parse(rawBody, now(), allowLoopbackHttpRelays).getOrElse { err ->
            AppLogger.w(TAG, "manifest $sourceLabel parse failure: ${err.message}")
            return null
        }
        return rawBody to parsed
    }
}
