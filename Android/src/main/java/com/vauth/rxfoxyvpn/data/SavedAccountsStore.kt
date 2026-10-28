package com.vauth.rxfoxyvpn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SavedAccountsStore"

data class SavedAccount(val email: String, val pass: String)

class SavedAccountsStore(context: Context) {

    private val prefs: SharedPreferences = initPrefs(context)

    private fun initPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "foxyvpn_saved_accounts",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse { firstError ->
            AppLogger.w(TAG, "EncryptedSharedPreferences failed, attempting reset", firstError)
            runCatching {
                context.deleteSharedPreferences("foxyvpn_saved_accounts")
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context,
                    "foxyvpn_saved_accounts",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.getOrElse { secondError ->
                AppLogger.e(TAG, "EncryptedSharedPreferences reset failed, using standard SharedPreferences fallback", secondError)
                context.getSharedPreferences("foxyvpn_saved_accounts_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    fun getSavedAccounts(): List<SavedAccount> {
        val jsonStr = runCatching { prefs.getString("accounts_list", "[]") }.getOrNull() ?: "[]"
        val list = mutableListOf<SavedAccount>()
        runCatching {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val email = obj.optString("email", "")
                val pass = obj.optString("pass", "")
                if (email.isNotBlank()) {
                    list.add(SavedAccount(email, pass))
                }
            }
        }
        return list
    }

    fun saveAccount(email: String, pass: String) {
        val current = getSavedAccounts().toMutableList()
        current.removeAll { it.email.equals(email, ignoreCase = true) }
        current.add(0, SavedAccount(email, pass))
        val arr = JSONArray()
        for (acc in current) {
            arr.put(JSONObject().put("email", acc.email).put("pass", acc.pass))
        }
        prefs.edit().putString("accounts_list", arr.toString()).apply()
    }

    fun removeAccount(email: String) {
        val current = getSavedAccounts().toMutableList()
        current.removeAll { it.email.equals(email, ignoreCase = true) }
        val arr = JSONArray()
        for (acc in current) {
            arr.put(JSONObject().put("email", acc.email).put("pass", acc.pass))
        }
        prefs.edit().putString("accounts_list", arr.toString()).apply()
    }
}
