package com.vauth.rxfoxyvpn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vauth.rxfoxyvpn.data.model.RuntimeAuth

private const val TAG = "TokenStore"

class TokenStore(context: Context) {

    private val prefs: SharedPreferences = initPrefs(context)

    private fun initPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "foxyvpn_tokens",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { firstError ->
            AppLogger.w(TAG, "EncryptedSharedPreferences failed, attempting reset", firstError)
            runCatching {
                context.deleteSharedPreferences("foxyvpn_tokens")
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    "foxyvpn_tokens",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse { secondError ->
                AppLogger.e(TAG, "EncryptedSharedPreferences reset failed, using standard SharedPreferences fallback", secondError)
                context.getSharedPreferences("foxyvpn_tokens_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    fun saveAuth(auth: RuntimeAuth) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, auth.accessToken)
            .putString(KEY_REFRESH_TOKEN, auth.refreshToken)
            .putLong(KEY_EXPIRES_AT, auth.expiresAtEpochSeconds)
            .apply()
    }

    fun loadAuth(): RuntimeAuth? = runCatching {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        RuntimeAuth(access, refresh, expiresAt)
    }.onFailure {
        AppLogger.w(TAG, "could not read the stored session", it)
    }.getOrNull()

    fun hasValidAccessToken(): Boolean {
        val auth = loadAuth() ?: return false
        if (auth.expiresAtEpochSeconds <= 0L) return false
        val nowSeconds = System.currentTimeMillis() / 1000
        return auth.expiresAtEpochSeconds - nowSeconds > CLOCK_SKEW_TOLERANCE_SECONDS
    }

    fun hasStoredSession(): Boolean = loadAuth() != null

    fun hasRefreshToken(): Boolean = loadAuth()?.refreshToken != null

    @Deprecated(
        "Checks only access-token freshness; prefer hasValidAccessToken() or FxaAuthRepository.restoreSession().",
        ReplaceWith("hasValidAccessToken()"),
    )
    fun hasValidSession(): Boolean = hasValidAccessToken()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        private const val CLOCK_SKEW_TOLERANCE_SECONDS = 60L
    }
}
