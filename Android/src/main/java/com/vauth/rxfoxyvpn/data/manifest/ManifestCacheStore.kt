package com.vauth.rxfoxyvpn.data.manifest

import android.content.Context

/**
 * Persistence abstraction for caching the last validated remote `updates.json` payload.
 * Never cleared or overwritten when a subsequent network fetch fails.
 */
interface ManifestCacheBackend {
    fun readCachedJson(): String?
    fun readSavedAtMs(): Long
    fun saveValidManifest(rawJson: String, savedAtMs: Long)
}

class SharedPrefsManifestCacheBackend(context: Context) : ManifestCacheBackend {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readCachedJson(): String? =
        prefs.getString(KEY_RAW_JSON, null)?.takeIf { it.isNotBlank() }

    override fun readSavedAtMs(): Long =
        prefs.getLong(KEY_SAVED_AT_MS, 0L)

    override fun saveValidManifest(rawJson: String, savedAtMs: Long) {
        if (rawJson.isBlank()) return
        prefs.edit()
            .putString(KEY_RAW_JSON, rawJson)
            .putLong(KEY_SAVED_AT_MS, savedAtMs)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "foxyvpn_remote_manifest_cache"
        private const val KEY_RAW_JSON = "manifest_raw_json_v1"
        private const val KEY_SAVED_AT_MS = "manifest_saved_at_ms_v1"
    }
}

class InMemoryManifestCacheBackend(
    initialJson: String? = null,
    initialSavedAtMs: Long = 0L,
) : ManifestCacheBackend {
    @Volatile
    private var cachedJson: String? = initialJson

    @Volatile
    private var savedAtMs: Long = initialSavedAtMs

    override fun readCachedJson(): String? = cachedJson

    override fun readSavedAtMs(): Long = savedAtMs

    override fun saveValidManifest(rawJson: String, savedAtMs: Long) {
        if (rawJson.isBlank()) return
        this.cachedJson = rawJson
        this.savedAtMs = savedAtMs
    }
}
