package com.vauth.rxfoxyvpn.data.manifest

import com.vauth.rxfoxyvpn.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Central configuration for the dedicated remote Manifest (`updates.json`).
 *
 * IMPORTANT ARCHITECTURAL BOUNDARY:
 * This Manifest is strictly used for:
 *  1. Dynamic Guardian control-plane Relay management (`relays`), and
 *  2. User-facing in-app Announcements (`announcements`).
 *
 * It MUST NOT be used for APK version checks, downloads, or self-updates.
 * The existing APK self-update flow in `AndroidUpdateDialog.kt` remains completely
 * independent and untouched.
 *
 * To change the Manifest repository URLs in the future, update `MANIFEST_PRIMARY_URL`
 * and `MANIFEST_FALLBACK_URL` in `app/build.gradle.kts` (or `DEFAULT_PRIMARY_URL` /
 * `DEFAULT_FALLBACK_URL` below), or set runtime overrides via `SettingsStore`.
 */
object ManifestConfig {
    const val DEFAULT_PRIMARY_URL: String = BuildConfig.MANIFEST_PRIMARY_URL
    const val DEFAULT_FALLBACK_URL: String = BuildConfig.MANIFEST_FALLBACK_URL

    const val SUPPORTED_SCHEMA_VERSION: Int = 1
    const val MAX_MANIFEST_BYTES: Int = 512 * 1024
    const val MAX_ANNOUNCEMENTS: Int = 50
    const val MAX_ANNOUNCEMENT_ID_LENGTH: Int = 128

    const val CONNECT_TIMEOUT_SECONDS: Long = 8L
    const val READ_TIMEOUT_SECONDS: Long = 10L
    const val CALL_TIMEOUT_SECONDS: Long = 15L

    /** Minimum interval between automatic successful manifest refreshes. */
    const val REFRESH_TTL_MS: Long = 10 * 60_000L

    /** Minimum cooldown after a failed refresh attempt before automatic retry. */
    const val RETRY_COOLDOWN_MS: Long = 60_000L

    /**
     * Resolves and validates the ordered list of Manifest URLs to try (Primary first,
     * Fallback CDN second). Production URLs must use HTTPS without embedded credentials
     * or fragments; loopback HTTP is permitted only in unit tests when explicitly enabled.
     */
    fun resolveUrls(
        primaryOverride: String? = null,
        fallbackOverride: String? = null,
        allowLoopbackHttp: Boolean = false,
    ): List<String> {
        val rawCandidates = listOf(
            primaryOverride?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PRIMARY_URL,
            fallbackOverride?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_FALLBACK_URL,
        )
        val validated = ArrayList<String>(2)
        val seen = HashSet<String>(2)
        for (candidate in rawCandidates) {
            val normalized = validateManifestSourceUrl(candidate, allowLoopbackHttp) ?: continue
            if (seen.add(normalized)) {
                validated.add(normalized)
            }
        }
        return validated
    }

    fun validateManifestSourceUrl(raw: String, allowLoopbackHttp: Boolean = false): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val url = trimmed.toHttpUrlOrNull() ?: return null
        val isHttps = url.scheme == "https"
        val isLoopbackHttp = allowLoopbackHttp && url.scheme == "http" &&
            (url.host == "127.0.0.1" || url.host == "localhost")
        if (!isHttps && !isLoopbackHttp) return null
        if (url.host.isBlank()) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.fragment != null) return null
        return url.toString()
    }
}
