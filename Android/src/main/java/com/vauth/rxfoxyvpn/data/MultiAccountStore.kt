package com.vauth.rxfoxyvpn.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.vauth.rxfoxyvpn.data.model.RuntimeAuth
import com.vauth.rxfoxyvpn.data.model.SubscriptionQuotaState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Account availability. Mirrors the semantics of the reference client:
 * `READY` may supply a ProxyPass; `RESERVED` waits for the quota reset;
 * `REAUTH_REQUIRED` needs a fresh Mozilla sign-in; `UNAVAILABLE` was refused
 * (e.g. not entitled) and is retried at `retryAtEpochSeconds`.
 */
enum class AccountStatus { READY, RESERVED, REAUTH_REQUIRED, UNAVAILABLE }

/** Reason codes (stored on the account; the UI translates them). */
object AccountReason {
    const val NONE = ""
    const val QUOTA_THRESHOLD = "quota_threshold"
    const val QUOTA_EXHAUSTED = "quota_exhausted"
    const val SIGNIN_REQUIRED = "signin_required"
    const val NOT_ENTITLED = "not_entitled"
    const val SIGNIN_RETRY = "signin_retry"
}

/** How long an account whose quota reset time is unknown stays reserved before re-check. */
private const val UNKNOWN_RESET_RETRY_SECONDS = 3600L

/** The nominal 50 GiB Firefox quota, used to migrate v1 fraction-only quota data. */
private const val NOMINAL_QUOTA_BYTES = 50L * 1024 * 1024 * 1024

/**
 * Multi-account store (Arman-style behavior on the Android architecture):
 * ordered list, per-account quota state, enable/disable, active selection,
 * rotation threshold, encrypted passwords, structured backup/import.
 *
 * Threat model / guarantees:
 * - Account records and passwords live in AES-256-GCM encrypted SharedPreferences.
 * - Passwords are stored as `v1:<base64(aes-256-gcm(nonce || ciphertext || tag))>`
 *   under a random 32-byte key held only in this encrypted store ([KEY_SECRET_KEY]),
 *   and that key travels inside backups so a restored install can reuse them.
 * - A corrupt store never crashes the app: the raw payload is preserved under a
 *   separate key and the list starts empty.
 * - The store is the single source of truth for account state. [TokenStore] only
 *   mirrors the active account for the rest of the app.
 */
class MultiAccountStore(
    context: Context,
    private val tokenStore: TokenStore,
) {
    data class Account(
        val id: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtEpochSeconds: Long,
        val scope: String? = null,
        val enabled: Boolean = true,
        val status: AccountStatus = AccountStatus.READY,
        val reasonCode: String = AccountReason.NONE,
        val reasonDetail: String = "",
        val retryAtEpochSeconds: Long = 0L,
        /** 0 when the quota is unknown (never treated as exhausted). */
        val limitBytes: Long = 0L,
        val remainingBytes: Long = 0L,
        val resetAtEpochSeconds: Long = 0L,
        val quotaCheckedAtEpochSeconds: Long = 0L,
        val unlimited: Boolean = false,
        val createdAtEpochSeconds: Long = 0L,
        val deviceId: String? = null,
        val deviceToken: String? = null,
    ) {
        val usedBytes: Long get() = if (unlimited || limitBytes <= 0) 0L else (limitBytes - remainingBytes).coerceAtLeast(0L)
        val usedFraction: Float
            get() = if (unlimited || limitBytes <= 0) 0f else (usedBytes.toDouble() / limitBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        val usedPercent: Int get() = (usedFraction * 100).toInt()

        /** After the recorded quota reset has passed, the month it measured is over. */
        fun afterResetSnapshot(nowEpochSeconds: Long): Account {
            if (unlimited || limitBytes <= 0 || resetAtEpochSeconds == 0L || nowEpochSeconds < resetAtEpochSeconds) return this
            if (status == AccountStatus.RESERVED && nowEpochSeconds < retryAtEpochSeconds) return this
            val promoted = copy(remainingBytes = limitBytes, resetAtEpochSeconds = 0L)
            return if (status == AccountStatus.RESERVED) {
                promoted.copy(status = AccountStatus.READY, reasonCode = AccountReason.NONE, reasonDetail = "", retryAtEpochSeconds = 0L)
            } else {
                promoted
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = initPrefs(appContext)

    private val _accounts = MutableStateFlow(loadAll())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _activeId = MutableStateFlow(prefs.getString(KEY_ACTIVE_ID, null))
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    var rotationThresholdPercent: Int
        get() = prefs.getInt(KEY_ROTATION_PCT, 95).coerceIn(50, 100)
        set(value) {
            prefs.edit().putInt(KEY_ROTATION_PCT, value.coerceIn(50, 100)).apply()
        }

    init {
        migrateLegacySavedPasswords()
        val promoted = promoteDueAccounts()
        if (_accounts.value.isEmpty()) {
            // Migrate the legacy single TokenStore session into the list once.
            val legacy = tokenStore.loadAuth()
            if (legacy != null && legacy.accessToken.isNotBlank()) {
                val id = UUID.randomUUID().toString()
                val account = Account(
                    id = id,
                    email = prefs.getString(KEY_LEGACY_EMAIL, null) ?: "account",
                    accessToken = legacy.accessToken,
                    refreshToken = legacy.refreshToken,
                    expiresAtEpochSeconds = legacy.expiresAtEpochSeconds,
                    enabled = true,
                    createdAtEpochSeconds = nowSeconds(),
                )
                persistAll(listOf(account), id)
                applyActiveToTokenStore(account)
            }
        } else if (promoted || _accounts.value.none { it.id == _activeId.value }) {
            val target = _accounts.value.firstOrNull { it.id == _activeId.value }
                ?: _accounts.value.firstOrNull { usableNow(it, nowSeconds()) }
                ?: _accounts.value.first()
            ensureActive(target.id)
        } else {
            _accounts.value.firstOrNull { it.id == _activeId.value }?.let { applyActiveToTokenStore(it) }
        }
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    fun hasAnyAccount(): Boolean = _accounts.value.isNotEmpty()

    fun activeAccount(): Account? = _accounts.value.firstOrNull { it.id == _activeId.value }?.afterResetSnapshot(nowSeconds())

    fun isUsable(account: Account): Boolean = usableNow(account, nowSeconds())

    fun usableCount(): Int = _accounts.value.count { usableNow(it, nowSeconds()) }

    /** The next account to try, as the rotation policy sees it now. `tried` lists ids already attempted this operation. */
    fun candidate(tried: Set<String> = emptySet()): Account? {
        promoteDueAccounts()
        val now = nowSeconds()
        val snapshot = _accounts.value
        fun eligible(a: Account) = a.enabled && a.id !in tried && usableNow(a, now)
        snapshot.firstOrNull { it.id == _activeId.value && eligible(it) }?.let { return it }
        snapshot.firstOrNull { eligible(it) }?.let { return it }
        // No account is fully ready: the last share of one set aside at the switch
        // threshold, so no remaining quota is left unused (Arman semantics).
        snapshot.firstOrNull { a ->
            a.enabled && a.id !in tried && a.status == AccountStatus.RESERVED &&
                a.reasonCode == AccountReason.QUOTA_THRESHOLD && a.remainingBytes > 0 && !a.unlimited
        }?.let { return it }
        return null
    }

    /** Earliest time any enabled account can be used again (0 when none is scheduled). */
    fun earliestRetryAt(): Long {
        val now = nowSeconds()
        return _accounts.value
            .filter { it.enabled && !usableNow(it, now) }
            .mapNotNull { it.retryAtEpochSeconds.takeIf { t -> t > now } }
            .minOrNull() ?: 0L
    }

    /** True when an update happened during the call (used internally and by callers to reload). */
    fun promoteDueAccounts(): Boolean {
        val now = nowSeconds()
        var changed = false
        val promoted = _accounts.value.map { raw ->
            val a = raw.afterResetSnapshot(now)
            if (a !== raw && a != raw) {
                changed = true
                a
            } else if ((a.status == AccountStatus.RESERVED || a.status == AccountStatus.UNAVAILABLE) &&
                a.retryAtEpochSeconds > 0 && now >= a.retryAtEpochSeconds
            ) {
                changed = true
                a.copy(status = AccountStatus.READY, reasonCode = AccountReason.NONE, reasonDetail = "", retryAtEpochSeconds = 0L)
            } else {
                a
            }
        }
        if (changed) persistAll(promoted, _activeId.value)
        return changed
    }

    // ------------------------------------------------------------------
    // Mutations (tokens / login)
    // ------------------------------------------------------------------

    /**
     * Adds or refreshes the account for [email] and makes it active. A fresh sign-in
     * always makes the account usable again (status reset, reasons cleared). When a
     * non-blank [password] is supplied it is stored encrypted for automatic re-sign-in.
     */
    fun upsertFromLogin(email: String, auth: RuntimeAuth, password: String? = null, deviceId: String? = null, deviceToken: String? = null): Account {
        val normalized = email.trim().ifBlank { "account" }
        val list = _accounts.value.toMutableList()
        val existingIndex = list.indexOfFirst { it.email.equals(normalized, ignoreCase = true) }
        val existing = existingIndex.takeIf { it >= 0 }?.let { list[it] }
        val id = existing?.id ?: UUID.randomUUID().toString()
        // RuntimeAuth carries no scope yet; keep whatever the account had (backups carry it).
        val scope = existing?.scope
        val effectivePassword = password?.takeIf { it.isNotBlank() } ?: existing?.let { decryptPasswordFor(it.id) } ?: orphanPassword(normalized)
        if (password?.isNotBlank() == true) storeEncryptedPassword(id, password)
        else if (password == null && effectivePassword != null && existing == null) {
            // Claim the orphan (migrated) password so it is no longer floating around email-keyed.
            storeEncryptedPassword(id, effectivePassword)
            removeOrphanPassword(normalized)
        }
        val updated = Account(
            id = id,
            email = existing?.email ?: normalized,
            accessToken = auth.accessToken,
            refreshToken = auth.refreshToken ?: existing?.refreshToken,
            expiresAtEpochSeconds = auth.expiresAtEpochSeconds,
            scope = scope ?: existing?.scope,
            enabled = existing?.enabled ?: true,
            status = AccountStatus.READY,
            reasonCode = AccountReason.NONE,
            reasonDetail = "",
            retryAtEpochSeconds = 0L,
            limitBytes = existing?.limitBytes ?: 0L,
            remainingBytes = existing?.remainingBytes ?: 0L,
            resetAtEpochSeconds = existing?.resetAtEpochSeconds ?: 0L,
            quotaCheckedAtEpochSeconds = existing?.quotaCheckedAtEpochSeconds ?: 0L,
            unlimited = existing?.unlimited ?: false,
            createdAtEpochSeconds = existing?.createdAtEpochSeconds ?: nowSeconds(),
            deviceId = deviceId ?: existing?.deviceId,
            deviceToken = deviceToken ?: existing?.deviceToken,
        )
        if (existingIndex >= 0) list[existingIndex] = updated else list += updated
        persistAll(list, id)
        applyActiveToTokenStore(updated)
        return updated
    }

    /** Records that the refresh token stream produced new tokens for [id] (keeps the active TokenStore mirror in sync). */
    fun updateTokens(id: String, auth: RuntimeAuth) {
        val list = _accounts.value.map { if (it.id == id) it.copy(accessToken = auth.accessToken, refreshToken = auth.refreshToken ?: it.refreshToken, expiresAtEpochSeconds = auth.expiresAtEpochSeconds) else it }
        persistAll(list, _activeId.value)
        if (_activeId.value == id) list.firstOrNull { it.id == id }?.let { applyActiveToTokenStore(it) }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val list = _accounts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persistAll(list, _activeId.value)
        if (!enabled && _activeId.value == id) {
            list.firstOrNull { it.enabled && usableNow(it, nowSeconds()) }?.let { setActive(it.id) }
        }
    }

    fun setActive(id: String): Boolean {
        val acc = _accounts.value.firstOrNull { it.id == id } ?: return false
        if (!acc.enabled) return false
        // Manual activation brings the account back into the rotation.
        val reset = if (acc.status != AccountStatus.READY) {
            acc.copy(status = AccountStatus.READY, reasonCode = AccountReason.NONE, reasonDetail = "", retryAtEpochSeconds = 0L)
        } else acc
        persistAll(_accounts.value.map { if (it.id == id) reset else it }, id)
        applyActiveToTokenStore(reset)
        return true
    }

    fun remove(id: String) {
        val storedPasswordRemoved = runCatching { removeEncryptedPassword(id) }.isSuccess
        val list = _accounts.value.filterNot { it.id == id }
        val newActive = when {
            _activeId.value != id -> _activeId.value
            else -> list.firstOrNull { it.enabled && usableNow(it, nowSeconds()) }?.id ?: list.firstOrNull { it.enabled }?.id
        }
        persistAll(list, newActive)
        if (newActive != null) {
            list.firstOrNull { it.id == newActive }?.let { applyActiveToTokenStore(it) }
        } else {
            tokenStore.clear()
        }
        if (!storedPasswordRemoved) AppLogger.w(TAG, "could not remove the stored password of a removed account")
    }

    /** Moves an account up (-1) or down (+1) in the rotation order. */
    fun move(id: String, delta: Int) {
        val list = _accounts.value.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return
        val item = list.removeAt(from)
        list.add(to, item)
        persistAll(list, _activeId.value)
    }

    // ------------------------------------------------------------------
    // Quota observation (the heart of automatic switching)
    // ------------------------------------------------------------------

    /**
     * Records a quota reading for [id] (from Guardian `/api/v1/fpn/token` headers or `/status`).
     * Returns true when the account left (or must leave) the active slot because of its quota.
     *
     * Semantics (Arman):
     * - Remaining == 0 → reserved until the reported reset; returns true.
     * - Usage ≥ switch threshold AND another enabled account is usable → reserved until reset; returns true.
     * - Otherwise the account keeps going; with no alternative it runs to the end of its quota.
     * - An account that was reserved and now reads below threshold becomes ready again.
     */
    fun observeQuota(id: String, unlimited: Boolean, limitBytes: Long, remainingBytes: Long, resetAtEpochSeconds: Long): Boolean {
        return observeUsageInternal(id, unlimited, limitBytes, remainingBytes, resetAtEpochSeconds)
    }

    /** Maps a ProxyPass (token fetch headers) onto the stored account. */
    fun observePassQuota(id: String, pass: ProxyPass): Boolean {
        if (pass.quotaUnlimited) {
            return observeUsageInternal(id, unlimited = true, 0, 0, 0)
        }
        val limit = pass.quotaMax ?: return false     // no quota headers: nothing to learn, keep stored state
        val remaining = pass.quotaRemaining ?: return false
        return observeUsageInternal(id, unlimited = false, limit, remaining, pass.quotaReset ?: 0L)
    }

    /** Maps Guardian `/api/v1/fpn/status` output onto the stored account. */
    fun observeEntitlement(id: String, entitlement: com.vauth.rxfoxyvpn.data.model.Entitlement): Boolean {
        return when (val state = entitlement.quotaState) {
            is SubscriptionQuotaState.Unlimited -> observeUsageInternal(id, true, 0, 0, 0)
            is SubscriptionQuotaState.Limited -> observeUsageInternal(
                id,
                unlimited = false,
                limitBytes = state.limitBytes,
                remainingBytes = state.remainingBytes,
                resetAtEpochSeconds = GuardianClient.parseQuotaResetEpochSeconds(state.resetRaw) ?: 0L,
            )
            is SubscriptionQuotaState.Unknown, is SubscriptionQuotaState.Error -> false
        }
    }

    /** Reserves [id] because Mozilla answered 429. [resetAtEpochSeconds] 0 means unknown (re-check in an hour). */
    fun reserveForQuotaExhausted(id: String, resetAtEpochSeconds: Long = 0L, detail: String? = null) {
        val retryAt = normalizedRetryAt(resetAtEpochSeconds)
        mark(id, AccountStatus.RESERVED, AccountReason.QUOTA_EXHAUSTED, detail.orEmpty(), retryAt)
    }

    /** Reserves [id] because it reached the switch threshold. */
    fun reserveForThreshold(id: String, resetAtEpochSeconds: Long, detail: String? = null) {
        mark(id, AccountStatus.RESERVED, AccountReason.QUOTA_THRESHOLD, detail.orEmpty(), normalizedRetryAt(resetAtEpochSeconds))
    }

    /** Takes [id] out of the rotation until it is signed in again. */
    fun markReauthRequired(id: String, detail: String? = null) {
        mark(id, AccountStatus.REAUTH_REQUIRED, AccountReason.SIGNIN_REQUIRED, detail.orEmpty(), 0L)
    }

    /** Takes [id] out of the rotation until [retryAtEpochSeconds] (e.g. not entitled). */
    fun markUnavailable(id: String, retryAtEpochSeconds: Long, reasonCode: String = AccountReason.NOT_ENTITLED, detail: String? = null) {
        mark(id, AccountStatus.UNAVAILABLE, reasonCode, detail.orEmpty(), normalizedRetryAt(retryAtEpochSeconds))
    }

    /**
     * Rotation used before connect and during long connections.
     * Returns the account that should serve, or null when nobody can.
     * When the active account crossed its threshold (and a successor exists) it is reserved
     * here and the successor becomes active. With no successor it stays on the active account.
     */
    fun rotateIfNeeded(): Account? {
        promoteDueAccounts()
        val now = nowSeconds()
        val active = activeAccount() ?: return candidate()
        // Another account became active already, or the active one is unusable: hand over if possible.
        if (!usableNow(active, now) || !active.enabled) {
            return candidate(setOf(active.id)) ?: candidate()
        }
        val threshold = rotationThresholdPercent / 100f
        if (active.unlimited || active.limitBytes <= 0 || active.usedFraction < threshold) return active
        val successor = candidate(setOf(active.id))
        if (successor == null) return active
        reserveForThreshold(active.id, active.resetAtEpochSeconds, "used ${active.usedPercent}% of quota")
        setActive(successor.id)
        return successor
    }

    /** True when the active account crossed the threshold and a successor exists (UI hint only). */
    fun isRotationPending(): Boolean {
        val active = activeAccount() ?: return false
        if (active.unlimited || active.limitBytes <= 0) return false
        val threshold = rotationThresholdPercent / 100f
        return active.usedFraction >= threshold && candidate(setOf(active.id)) != null
    }

    // ------------------------------------------------------------------
    // Passwords (encrypted, Arman-compatible `v1:` format)
    // ------------------------------------------------------------------

    fun hasStoredPassword(id: String): Boolean = passwordMap().containsKey(id)

    fun storedPassword(id: String): String? = decryptPasswordFor(id)

    fun storeEncryptedPassword(id: String, password: String) {
        val map = passwordMap().toMutableMap()
        map[id] = encryptPassword(secretKey(), password)
        prefs.edit().putString(KEY_PASSWORDS, JSONObject(map.mapValues { it.value }).toString()).apply()
    }

    fun removeEncryptedPassword(id: String) {
        val map = passwordMap().toMutableMap()
        if (map.remove(id) != null) {
            prefs.edit().putString(KEY_PASSWORDS, JSONObject(map).toString()).apply()
        }
    }

    internal fun secretKeyHex(): String = secretKey().joinToString("") { "%02x".format(it) }

    internal fun secretKeyFromHex(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length != 64) return null
        return runCatching { ByteArray(32) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    /** Installs a backup's secret key so its encrypted passwords decrypt here. No-op when unchanged. */
    internal fun installSecretKey(bytes: ByteArray) {
        require(bytes.size == 32) { "secret.key must hold 32 bytes" }
        prefs.edit().putString(KEY_SECRET_KEY, bytes.joinToString("") { "%02x".format(it) }).apply()
    }

    internal fun encryptWithCurrentKey(plain: String): String = encryptPassword(secretKey(), plain)

    /**
     * Replaces the whole account list (backup import). Runs only after the merged
     * list was built successfully in memory; a failure before this call persists nothing.
     */
    fun replaceAllForImport(list: List<Account>, activeId: String?) {
        persistAll(list, activeId)
        activeId?.let { id -> list.firstOrNull { it.id == id }?.let { applyActiveToTokenStore(it) } }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun observeUsageInternal(id: String, unlimited: Boolean, limitBytes: Long, remainingBytes: Long, resetAtEpochSeconds: Long): Boolean {
        val now = nowSeconds()
        val list = _accounts.value.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        var acc = list[index].copy(
            unlimited = unlimited,
            limitBytes = if (unlimited) 0L else limitBytes.coerceAtLeast(0L),
            remainingBytes = if (unlimited) 0L else remainingBytes.coerceIn(0L, if (limitBytes > 0) limitBytes else Long.MAX_VALUE),
            resetAtEpochSeconds = if (unlimited) 0L else resetAtEpochSeconds,
            quotaCheckedAtEpochSeconds = now,
        )
        var needsRotation = false
        if (!unlimited && limitBytes > 0) {
            val exhausted = remainingBytes <= 0
            val threshold = rotationThresholdPercent / 100.0
            val used = (limitBytes - remainingBytes.coerceAtLeast(0)).coerceAtLeast(0)
            val thresholdReached = limitBytes > 0 && used >= (limitBytes * threshold).toLong()
            when {
                exhausted -> {
                    acc = acc.copy(
                        status = AccountStatus.RESERVED,
                        reasonCode = AccountReason.QUOTA_EXHAUSTED,
                        reasonDetail = "monthly quota exhausted",
                        retryAtEpochSeconds = normalizedRetryAt(resetAtEpochSeconds),
                    )
                    needsRotation = true
                }
                thresholdReached && otherEnabledUsableExists(list, id, now) -> {
                    acc = acc.copy(
                        status = AccountStatus.RESERVED,
                        reasonCode = AccountReason.QUOTA_THRESHOLD,
                        reasonDetail = "used ${used * 100 / limitBytes}% of quota (switch threshold ${rotationThresholdPercent}%)",
                        retryAtEpochSeconds = normalizedRetryAt(resetAtEpochSeconds),
                    )
                    needsRotation = true
                }
                acc.status == AccountStatus.RESERVED && !thresholdReached -> {
                    acc = acc.copy(status = AccountStatus.READY, reasonCode = AccountReason.NONE, reasonDetail = "", retryAtEpochSeconds = 0L)
                }
            }
        } else if (unlimited && acc.status == AccountStatus.RESERVED && acc.reasonCode == AccountReason.QUOTA_THRESHOLD) {
            acc = acc.copy(status = AccountStatus.READY, reasonCode = AccountReason.NONE, reasonDetail = "", retryAtEpochSeconds = 0L)
        }
        list[index] = acc
        persistAll(list, _activeId.value)
        return needsRotation
    }

    private fun otherEnabledUsableExists(list: List<Account>, excludeId: String, now: Long): Boolean =
        list.any { it.id != excludeId && it.enabled && usableNow(it, now) }

    private fun usableNow(a: Account, now: Long): Boolean = when (a.status) {
        AccountStatus.READY -> true
        AccountStatus.RESERVED, AccountStatus.UNAVAILABLE -> a.retryAtEpochSeconds > 0L && now >= a.retryAtEpochSeconds
        AccountStatus.REAUTH_REQUIRED -> false
    }

    private fun normalizedRetryAt(resetAtEpochSeconds: Long): Long {
        val now = nowSeconds()
        return if (resetAtEpochSeconds > now) resetAtEpochSeconds else now + UNKNOWN_RESET_RETRY_SECONDS
    }

    private fun mark(id: String, status: AccountStatus, reasonCode: String, detail: String, retryAtEpochSeconds: Long) {
        val list = _accounts.value.map { if (it.id == id) it.copy(status = status, reasonCode = reasonCode, reasonDetail = detail, retryAtEpochSeconds = retryAtEpochSeconds) else it }
        persistAll(list, _activeId.value)
    }

    private fun ensureActive(id: String) {
        persistAll(_accounts.value, id)
        _accounts.value.firstOrNull { it.id == id }?.let { applyActiveToTokenStore(it) }
    }

    private fun applyActiveToTokenStore(acc: Account) {
        tokenStore.saveAuth(
            RuntimeAuth(
                accessToken = acc.accessToken,
                refreshToken = acc.refreshToken,
                expiresAtEpochSeconds = acc.expiresAtEpochSeconds,
            ),
        )
    }

    private fun persistAll(list: List<Account>, activeId: String?) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("email", a.email)
                    .put("accessToken", a.accessToken)
                    .put("refreshToken", a.refreshToken ?: "")
                    .put("expiresAt", a.expiresAtEpochSeconds)
                    .put("scope", a.scope ?: "")
                    .put("enabled", a.enabled)
                    .put("status", a.status.name)
                    .put("reasonCode", a.reasonCode)
                    .put("reasonDetail", a.reasonDetail)
                    .put("retryAt", a.retryAtEpochSeconds)
                    .put("limitBytes", a.limitBytes)
                    .put("remainingBytes", a.remainingBytes)
                    .put("resetAt", a.resetAtEpochSeconds)
                    .put("quotaCheckedAt", a.quotaCheckedAtEpochSeconds)
                    .put("unlimited", a.unlimited)
                    .put("createdAt", a.createdAtEpochSeconds)
                    .put("deviceId", a.deviceId ?: "")
                    .put("deviceToken", a.deviceToken ?: ""),
            )
        }
        prefs.edit()
            .putInt(KEY_STORE_VERSION, STORE_VERSION)
            .putString(KEY_ACCOUNTS, arr.toString())
            .putString(KEY_ACTIVE_ID, activeId)
            .apply()
        _accounts.value = list
        _activeId.value = activeId
    }

    private fun loadAll(): List<Account> {
        val version = prefs.getInt(KEY_STORE_VERSION, 1)
        val raw = prefs.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val parsed = runCatching { parseAccountsJson(raw, version) }
        return parsed.getOrElse { failure ->
            // Corruption protection: keep the raw payload for support/recovery, start clean instead of crashing.
            AppLogger.e(TAG, "the account store is unreadable; starting with an empty list (raw data preserved)", failure)
            prefs.edit().putString(KEY_ACCOUNTS_CORRUPT_BACKUP, raw).apply()
            emptyList()
        }
    }

    private fun parseAccountsJson(raw: String, fromVersion: Int): List<Account> {
        val arr = JSONArray(raw)
        val now = nowSeconds()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (fromVersion < 2) {
                    // v1 → v2: usedFraction/unlimited only. Map onto byte accounting with the nominal quota.
                    val unlimited = o.optBoolean("unlimited", false)
                    val fraction = o.optDouble("usedFraction", 0.0).coerceIn(0.0, 1.0)
                    val limit = if (unlimited) 0L else NOMINAL_QUOTA_BYTES
                    val remaining = if (unlimited) 0L else ((1.0 - fraction) * NOMINAL_QUOTA_BYTES).toLong()
                    add(
                        Account(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            email = o.optString("email").ifBlank { "account" },
                            accessToken = o.optString("accessToken"),
                            refreshToken = o.optString("refreshToken").ifBlank { null },
                            expiresAtEpochSeconds = o.optLong("expiresAt"),
                            enabled = o.optBoolean("enabled", true),
                            status = AccountStatus.READY,
                            limitBytes = limit,
                            remainingBytes = remaining,
                            unlimited = unlimited,
                            createdAtEpochSeconds = now,
                        ),
                    )
                } else {
                    add(
                        Account(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            email = o.optString("email"),
                            accessToken = o.optString("accessToken"),
                            refreshToken = o.optString("refreshToken").ifBlank { null },
                            expiresAtEpochSeconds = o.optLong("expiresAt"),
                            scope = o.optString("scope").ifBlank { null },
                            enabled = o.optBoolean("enabled", true),
                            status = runCatching { AccountStatus.valueOf(o.optString("status", "READY")) }.getOrDefault(AccountStatus.READY),
                            reasonCode = o.optString("reasonCode"),
                            reasonDetail = o.optString("reasonDetail"),
                            retryAtEpochSeconds = o.optLong("retryAt"),
                            limitBytes = o.optLong("limitBytes"),
                            remainingBytes = o.optLong("remainingBytes"),
                            resetAtEpochSeconds = o.optLong("resetAt"),
                            quotaCheckedAtEpochSeconds = o.optLong("quotaCheckedAt"),
                            unlimited = o.optBoolean("unlimited", false),
                            createdAtEpochSeconds = o.optLong("createdAt", now),
                            deviceId = o.optString("deviceId").ifBlank { null },
                            deviceToken = o.optString("deviceToken").ifBlank { null },
                        ),
                    )
                }
            }
        }
    }

    // --- encrypted password plumbing ---

    private fun secretKey(): ByteArray {
        val existing = prefs.getString(KEY_SECRET_KEY, null)
        if (existing != null) {
            secretKeyFromHex(existing)?.let { return it }
            AppLogger.w(TAG, "the stored secret.key is damaged; generating a new one (stored passwords will not decrypt)")
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SECRET_KEY, fresh.joinToString("") { "%02x".format(it) }).apply()
        return fresh
    }

    private fun passwordMap(): Map<String, String> {
        val raw = runCatching { prefs.getString(KEY_PASSWORDS, null) }.getOrNull() ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { key -> put(key, obj.optString(key)) } }
        }.getOrElse { emptyMap() }
    }

    private fun orphanPasswords(): Map<String, String> {
        val raw = runCatching { prefs.getString(KEY_ORPHAN_PASSWORDS, null) }.getOrNull() ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { key -> put(key, obj.optString(key)) } }
        }.getOrElse { emptyMap() }
    }

    private fun orphanPassword(email: String): String? = orphanPasswords()[email.lowercase()]

    private fun removeOrphanPassword(email: String) {
        val map = orphanPasswords().toMutableMap()
        if (map.remove(email.lowercase()) != null) {
            prefs.edit().putString(KEY_ORPHAN_PASSWORDS, JSONObject(map).toString()).apply()
        }
    }

    private fun decryptPasswordFor(id: String): String? {
        val blob = passwordMap()[id] ?: return null
        return runCatching { decryptPassword(secretKey(), blob) }
            .onFailure { AppLogger.w(TAG, "a stored password could not be decrypted", it) }
            .getOrNull()
    }

    /**
     * One-time migration: folds the legacy `foxyvpn_saved_accounts` (plaintext-in-ESP)
     * email+password list into this store, then wipes the plaintext list. Passwords for
     * emails that already have an account are attached to that account; the rest are kept
     * as orphans keyed by email and claimed on the next sign-in for that email.
     */
    private fun migrateLegacySavedPasswords() {
        if (prefs.getBoolean(KEY_SAVED_PASSWORDS_MIGRATED, false)) return
        val legacy = appContext.getSharedPreferences("foxyvpn_saved_accounts", Context.MODE_PRIVATE)
        val raw = runCatching { legacy.getString("accounts_list", null) }.getOrNull()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                val current = _accounts.value
                val orphans = orphanPasswords().toMutableMap()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val email = o.optString("email").trim()
                    val pass = o.optString("pass")
                    if (email.isBlank() || pass.isBlank()) continue
                    val match = current.firstOrNull { it.email.equals(email, ignoreCase = true) }
                    if (match != null) {
                        storeEncryptedPassword(match.id, pass)
                    } else {
                        orphans[email.lowercase()] = encryptPassword(secretKey(), pass)
                    }
                }
                prefs.edit().putString(KEY_ORPHAN_PASSWORDS, JSONObject(orphans).toString()).apply()
            }.onFailure {
                AppLogger.w(TAG, "legacy saved-account passwords could not be migrated; leaving them in place", it)
                return // do not mark migrated; retry next launch
            }
            legacy.edit().remove("accounts_list").apply()
            AppLogger.i(TAG, "legacy saved-account passwords were migrated into the encrypted multi-account store")
        }
        prefs.edit().putBoolean(KEY_SAVED_PASSWORDS_MIGRATED, true).apply()
    }

    private fun initPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "foxyvpn_multi_accounts",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            AppLogger.e(TAG, "EncryptedSharedPreferences is unavailable; using the private fallback store", it)
            context.getSharedPreferences("foxyvpn_multi_accounts_fallback", Context.MODE_PRIVATE)
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun encryptPassword(key: ByteArray, password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val blob = nonce + sealed
        return PASSWORD_PREFIX + android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP)
    }

    private fun decryptPassword(key: ByteArray, blob: String): String {
        require(blob.startsWith(PASSWORD_PREFIX)) { "unknown password format" }
        val raw = android.util.Base64.decode(blob.substring(PASSWORD_PREFIX.length), android.util.Base64.NO_WRAP)
        require(raw.size > 12) { "stored password is damaged" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return try {
            cipher.doFinal(raw.copyOfRange(12, raw.size)).toString(Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw IllegalStateException("stored password cannot be decrypted (was secret.key replaced?)", e)
        }
    }

    companion object {
        private const val TAG = "MultiAccountStore"
        internal const val STORE_VERSION = 2
        internal const val KEY_ACCOUNTS = "accounts_json"
        internal const val KEY_ACTIVE_ID = "active_id"
        internal const val KEY_ROTATION_PCT = "rotation_pct"
        internal const val KEY_LEGACY_EMAIL = "legacy_email"
        internal const val KEY_STORE_VERSION = "store_version"
        internal const val KEY_ACCOUNTS_CORRUPT_BACKUP = "accounts_json_corrupt_backup"
        internal const val KEY_SECRET_KEY = "secret_key_hex"
        internal const val KEY_PASSWORDS = "passwords_json"
        internal const val KEY_ORPHAN_PASSWORDS = "orphan_passwords_json"
        internal const val KEY_SAVED_PASSWORDS_MIGRATED = "saved_passwords_migrated"
        internal const val PASSWORD_PREFIX = "v1:"
    }
}
