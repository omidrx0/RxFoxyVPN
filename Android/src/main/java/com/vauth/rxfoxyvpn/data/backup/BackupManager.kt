package com.vauth.rxfoxyvpn.data.backup

import com.vauth.rxfoxyvpn.data.AppLogger
import com.vauth.rxfoxyvpn.data.MultiAccountStore
import com.vauth.rxfoxyvpn.data.model.RuntimeAuth
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.security.SecureRandom

/**
 * Arman-compatible backup. A backup is a ZIP of the accounts' records, in the
 * exact layout the reference client uses, so a file moves between the two apps:
 *
 * ```
 * backup.json                     {"app","format":1,"version","created","system","accounts"}
 * accounts/secret.key             hex-encoded 32-byte key (password encryption)
 * accounts/<id>/account.json      non-secret record (usage, status, retry…)
 * accounts/<id>/credentials.json  tokens (+ AES-256-GCM encrypted password)
 * ```
 *
 * Security properties:
 * - Entries are matched against a strict allow-list pattern; anything else is ignored.
 * - Nothing is written to disk from the ZIP (in-memory parse) → ZIP-Slip is impossible by construction.
 * - Size caps: 16 MiB compressed, 1 MiB per account entry, 2000 entries per file.
 * - The import is all-or-nothing: accounts are merged into an in-memory working list
 *   and only persisted on full success, so a failed import destroys nothing.
 * - Passwords from the backup are re-encrypted under this device's secret key; the
 *   backup's key never replaces ours (existing passwords stay decryptable).
 */
class BackupManager(private val store: MultiAccountStore) {

    data class BackupAccountPreview(
        val email: String,
        val hasPassword: Boolean,
        /** This device already has the account (same email, case-insensitive): import updates its sign-in. */
        val exists: Boolean,
        /** False when the entry is unreadable/unusable; it will be skipped on import. */
        val usable: Boolean,
    )

    data class BackupPreview(
        val app: String?,
        val format: Int?,
        val createdAtEpochSeconds: Long?,
        val accounts: List<BackupAccountPreview>,
    ) {
        val usableCount: Int get() = accounts.count { it.usable }
    }

    data class ImportResult(
        val added: Int,
        val updated: Int,
        val skippedPasswords: Int,
        val skippedAccounts: Int,
    )

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    fun export(): Result<ByteArray> = runCatching {
        val accounts = store.accounts.value
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            writeEntry(zip, BACKUP_INFO, backupInfoJson(accounts.size).toByteArray(Charsets.UTF_8))
            writeEntry(zip, "accounts/secret.key", (store.secretKeyHex() + "\n").toByteArray(Charsets.UTF_8))
            val now = Instant.now().epochSecond
            for (a in accounts) {
                val id = sanitizeId(a.id)
                writeEntry(zip, "accounts/$id/account.json", accountJson(a, now).toByteArray(Charsets.UTF_8))
                writeEntry(zip, "accounts/$id/credentials.json", credentialsJson(a).toByteArray(Charsets.UTF_8))
            }
        }
        out.toByteArray()
    }.onFailure { AppLogger.e(TAG, "backup export failed", it) }

    // ------------------------------------------------------------------
    // Inspect / Import
    // ------------------------------------------------------------------

    fun inspect(zipBytes: ByteArray): Result<BackupPreview> = runCatching {
        val parsed = parseZip(zipBytes)
        require(parsed.accounts.isNotEmpty()) { "no accounts in this file" }
        val have = store.accounts.value
        BackupPreview(
            app = parsed.info?.optString("app")?.takeIf { it.isNotBlank() },
            format = parsed.info?.optInt("format", -1)?.takeIf { it > 0 },
            createdAtEpochSeconds = parsed.info?.let { info ->
                runCatching { java.time.OffsetDateTime.parse(info.optString("created")).toEpochSecond() }.getOrNull()
            },
            accounts = parsed.accounts.map { entry ->
                BackupAccountPreview(
                    email = entry.email,
                    hasPassword = entry.password != null,
                    exists = have.any { it.email.equals(entry.email, ignoreCase = true) },
                    usable = entry.refreshToken.isNotBlank(),
                )
            },
        )
    }.onFailure { AppLogger.w(TAG, "backup inspect failed (${it::class.java.simpleName}): ${it.message}") }

    fun import(zipBytes: ByteArray): Result<ImportResult> = runCatching {
        val parsed = parseZip(zipBytes)
        val usable = parsed.accounts.filter { it.refreshToken.isNotBlank() }
        require(usable.isNotEmpty()) { "no accounts in this file" }

        // Passwords are re-encrypted under THIS device's key; the backup's key never replaces ours.
        val backupKey = parsed.secretKey
        val reEncrypted = HashMap<String, String>()
        var skippedPasswords = 0
        for (entry in usable) {
            val cipher = entry.password ?: continue
            val plain = backupKey?.let { key -> runCatching { decryptAesGcm(key, cipher) }.getOrNull() }
            if (plain != null) {
                reEncrypted[entry.folderId] = plain
            } else {
                skippedPasswords++
            }
        }

        var added = 0
        var updated = 0
        // Compute the merged list first; persist only on full success.
        val working = store.accounts.value.map { it }.toMutableList()
        val now = Instant.now().epochSecond
        for (entry in usable) {
            val existingIndex = working.indexOfFirst { it.email.equals(entry.email, ignoreCase = true) }
            val auth = RuntimeAuth(
                accessToken = entry.accessToken,
                refreshToken = entry.refreshToken,
                expiresAtEpochSeconds = entry.expiresAtEpochSeconds,
            )
            if (existingIndex >= 0) {
                val existing = working[existingIndex]
                working[existingIndex] = existing.copy(
                    accessToken = auth.accessToken,
                    refreshToken = auth.refreshToken,
                    expiresAtEpochSeconds = auth.expiresAtEpochSeconds,
                    scope = entry.scope ?: existing.scope,
                    status = com.vauth.rxfoxyvpn.data.AccountStatus.READY,
                    reasonCode = com.vauth.rxfoxyvpn.data.AccountReason.NONE,
                    reasonDetail = "",
                    retryAtEpochSeconds = 0L,
                    unlimited = entry.unlimited,
                    limitBytes = entry.limitBytes,
                    remainingBytes = entry.remainingBytes,
                    resetAtEpochSeconds = entry.resetAtEpochSeconds,
                    quotaCheckedAtEpochSeconds = entry.quotaCheckedAtEpochSeconds,
                    deviceId = entry.deviceId ?: existing.deviceId,
                    deviceToken = entry.deviceToken ?: existing.deviceToken,
                )
                updated++
            } else {
                working += MultiAccountStore.Account(
                    id = sanitizeId(entry.folderId).ifBlank { UUID.randomUUID().toString() },
                    email = entry.email,
                    accessToken = auth.accessToken,
                    refreshToken = auth.refreshToken,
                    expiresAtEpochSeconds = auth.expiresAtEpochSeconds,
                    scope = entry.scope,
                    enabled = true,
                    status = com.vauth.rxfoxyvpn.data.AccountStatus.READY,
                    unlimited = entry.unlimited,
                    limitBytes = entry.limitBytes,
                    remainingBytes = entry.remainingBytes,
                    resetAtEpochSeconds = entry.resetAtEpochSeconds,
                    quotaCheckedAtEpochSeconds = entry.quotaCheckedAtEpochSeconds,
                    createdAtEpochSeconds = entry.createdAtEpochSeconds.takeIf { it > 0 } ?: now,
                    deviceId = entry.deviceId,
                    deviceToken = entry.deviceToken,
                )
                added++
            }
        }
        // Activate the first newly added account when none was active.
        val activeId = store.activeId.value ?: working.firstOrNull()?.id
        store.replaceAllForImport(working, activeId)
        for (entry in usable) {
            val plain = reEncrypted[entry.folderId] ?: continue
            val target = store.accounts.value.firstOrNull { it.email.equals(entry.email, ignoreCase = true) } ?: continue
            store.storeEncryptedPassword(target.id, plain)
        }
        store.promoteDueAccounts()
        AppLogger.i(TAG, "backup imported: $added added, $updated updated")
        ImportResult(added = added, updated = updated, skippedPasswords = skippedPasswords, skippedAccounts = parsed.accounts.size - usable.size)
    }.onFailure { AppLogger.e(TAG, "backup import failed", it) }

    // ------------------------------------------------------------------
    // Parsing & validation
    // ------------------------------------------------------------------

    private class ParsedAccount(
        val folderId: String,
        val email: String,
        val accessToken: String,
        val refreshToken: String,
        val expiresAtEpochSeconds: Long,
        val scope: String?,
        val password: String?,
        val unlimited: Boolean,
        val limitBytes: Long,
        val remainingBytes: Long,
        val resetAtEpochSeconds: Long,
        val quotaCheckedAtEpochSeconds: Long,
        val createdAtEpochSeconds: Long,
        val deviceId: String?,
        val deviceToken: String?,
    )

    private class ParsedBackup(
        val info: JSONObject?,
        val secretKey: ByteArray?,
        val accounts: List<ParsedAccount>,
    )

    private fun readEntryFully(zip: java.util.zip.ZipInputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (total < limit) {
            val want = minOf(chunk.size, limit - total)
            val n = zip.read(chunk, 0, want)
            if (n <= 0) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun parseZip(zipBytes: ByteArray): ParsedBackup {
        require(zipBytes.isNotEmpty()) { "empty file" }
        require(zipBytes.size <= MAX_BACKUP_BYTES) { "file too large (max 16 MB)" }

        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var count = 0
            var uncompressedTotal = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ENTRIES) { "too many files in the zip" }
                if (entry.isDirectory) { zip.closeEntry(); continue }
                val name = entry.name
                val normalized = name.removePrefix("./")
                // Read the entry FIRST (with a cap), then close it — reading after
                // closeEntry() does not yield the entry's bytes.
                // NOTE: do not use InputStream.readNBytes here — it does not exist below API 33
                // and this code path runs on any minSdk. Read with a manual cap instead.
                val buffer = readEntryFully(zip, MAX_ENTRY_BYTES + 1)
                zip.closeEntry()
                val match = ENTRY_PATTERN.find(normalized) ?: continue
                val rel = (match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                    ?: match.groupValues.getOrNull(2)?.let { sub -> "accounts/$sub" }) ?: continue
                require(buffer.size <= MAX_ENTRY_BYTES) { "an account file inside the zip is too large" }
                uncompressedTotal += buffer.size
                require(uncompressedTotal <= MAX_UNCOMPRESSED_BYTES) { "the unzipped data is too large" }
                entries[rel] = buffer
            }
        }

        val info = entries["backup.json"]?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() }
        val secretKey = entries["accounts/secret.key"]?.let { raw ->
            runCatching {
                val hex = raw.decodeToString().trim()
                require(hex.length == 64) { "secret.key must be 32 bytes of hex" }
                ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
        }

        // Group account.json / credentials.json by folder id. rel values look like
        // "accounts/<id>/account.json" (what the exporter writes and Arman's
        // backupAccountFile captures).
        val folders = LinkedHashMap<String, MutableMap<String, ByteArray>>()
        for ((rel, data) in entries) {
            if (rel == "accounts/secret.key" || rel == "backup.json") continue
            val parts = rel.split('/')
            if (parts.size != 3 || parts[0] != "accounts") continue
            val (_, folder, kind) = parts
            if (kind != "account.json" && kind != "credentials.json") continue
            folders.getOrPut(folder) { LinkedHashMap() }[kind] = data
        }

        val accounts = folders.mapNotNull { (folderId, files) ->
            val accountRaw = files["account.json"]?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() }
            val credentials = files["credentials.json"]?.let { runCatching { JSONObject(it.decodeToString()) }.getOrNull() } ?: return@mapNotNull null
            val email = credentials.optString("email").ifBlank { accountRaw?.optString("email").orEmpty() }.trim()
            val refreshToken = credentials.optString("refresh_token").trim()
            // Arman skips credential-less / refresh-token-less entries too.
            if (email.isBlank() || refreshToken.isBlank()) return@mapNotNull null
            val usage = accountRaw?.optJSONObject("usage")
            val status = accountRaw?.optString("status").orEmpty()
            val unlimited = usage?.optBoolean("unlimited", false) == true
            val limit = usage?.optLong("limit_bytes", 0L) ?: 0L
            val remaining = usage?.optLong("remaining_bytes", limit) ?: limit
            ParsedAccount(
                folderId = folderId,
                email = email,
                accessToken = credentials.optString("access_token").trim(),
                refreshToken = refreshToken,
                expiresAtEpochSeconds = expiryOf(credentials),
                scope = credentials.optString("scope").ifBlank { null },
                password = credentials.optString("password").ifBlank { null },
                unlimited = unlimited,
                limitBytes = if (unlimited) 0L else limit,
                remainingBytes = if (unlimited) 0L else remaining.coerceIn(0L, if (limit > 0) limit else Long.MAX_VALUE),
                resetAtEpochSeconds = parseInstant(usage?.optString("reset_at")),
                quotaCheckedAtEpochSeconds = parseInstant(usage?.optString("checked_at")),
                createdAtEpochSeconds = parseInstant(accountRaw?.optString("added_at")),
                deviceId = accountRaw?.optString("device_id")?.ifBlank { null },
                deviceToken = accountRaw?.optString("device_token")?.ifBlank { null },
            ).also { if (status == "unavailable") { /* imported as ready; the user retries from the UI */ } }
        }
        return ParsedBackup(info, secretKey, accounts)
    }

    private fun expiryOf(credentials: JSONObject): Long {
        // Arman stores expires_in + obtained_at; both are optional in foreign backups.
        val obtained = parseInstant(credentials.optString("obtained_at"))
        val expiresIn = credentials.optLong("expires_in", 0L)
        if (obtained > 0 && expiresIn > 0) return obtained + expiresIn
        credentials.optLong("expiresAt").takeIf { it > 0 }?.let { return it } // tolerate my1-native backups
        return 0L
    }

    private fun parseInstant(value: String?): Long {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return 0L
        raw.toLongOrNull()?.let { return it }
        return runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond() }.getOrElse {
            runCatching { java.time.Instant.parse(raw).epochSecond }.getOrDefault(0L)
        }
    }

    private fun formatInstant(epochSeconds: Long): String =
        if (epochSeconds <= 0) "" else Instant.ofEpochSecond(epochSeconds).toString()

    private fun sanitizeId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { UUID.randomUUID().toString() }

    private fun backupInfoJson(accountCount: Int): String {
        return JSONObject()
            .put("app", "RxFoxyVPN")
            .put("format", BACKUP_FORMAT)
            .put("version", APP_VERSION)
            .put("created", Instant.now().toString())
            .put("system", "android")
            .put("accounts", accountCount)
            .toString(2)
    }

    private fun accountJson(a: MultiAccountStore.Account, now: Long): String {
        val usage = JSONObject()
        if (a.unlimited) {
            usage.put("unlimited", true)
        } else if (a.limitBytes > 0) {
            usage.put("limit_bytes", a.limitBytes)
            usage.put("remaining_bytes", a.remainingBytes)
            if (a.resetAtEpochSeconds > now) usage.put("reset_at", formatInstant(a.resetAtEpochSeconds))
            if (a.quotaCheckedAtEpochSeconds > 0) usage.put("checked_at", formatInstant(a.quotaCheckedAtEpochSeconds))
        }
        val json = JSONObject()
            .put("id", sanitizeId(a.id))
            .put("email", a.email)
            .put("added_at", formatInstant(a.createdAtEpochSeconds.takeIf { it > 0 } ?: now))
            .put("status", when (a.status) {
                com.vauth.rxfoxyvpn.data.AccountStatus.READY -> "ready"
                com.vauth.rxfoxyvpn.data.AccountStatus.RESERVED -> "reserved"
                com.vauth.rxfoxyvpn.data.AccountStatus.REAUTH_REQUIRED -> "reauth_required"
                com.vauth.rxfoxyvpn.data.AccountStatus.UNAVAILABLE -> "unavailable"
            })
        if (a.reasonCode.isNotBlank()) json.put("reason_code", a.reasonCode)
        if (a.reasonDetail.isNotBlank()) json.put("reason_detail", a.reasonDetail)
        if (a.retryAtEpochSeconds > 0) json.put("retry_at", formatInstant(a.retryAtEpochSeconds))
        if (usage.length() > 0) json.put("usage", usage)
        a.deviceId?.let { json.put("device_id", it) }
        a.deviceToken?.let { json.put("device_token", it) }
        return json.toString(2)
    }

    private fun credentialsJson(a: MultiAccountStore.Account): String {
        val json = JSONObject()
            .put("access_token", a.accessToken)
            .put("refresh_token", a.refreshToken ?: "")
            .put("scope", a.scope ?: "")
            .put("obtained_at", Instant.now().toString())
            .put("expires_in", (a.expiresAtEpochSeconds - Instant.now().epochSecond).coerceAtLeast(0L))
            .put("expiresAt", a.expiresAtEpochSeconds)
        store.storedPassword(a.id)?.let { plain ->
            // Re-encrypt with the current key so the file is self-contained.
            json.put("password", store.encryptWithCurrentKey(plain))
        }
        return json.toString(2)
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    /** AES-256-GCM open with the `v1:` layout shared with the account store. */
    private fun decryptAesGcm(key: ByteArray, blob: String): String {
        require(blob.startsWith("v1:")) { "unsupported password format" }
        val raw = android.util.Base64.decode(blob.removePrefix("v1:"), android.util.Base64.NO_WRAP)
        require(raw.size > 12) { "stored password is damaged" }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return cipher.doFinal(raw.copyOfRange(12, raw.size)).toString(Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_FORMAT = 1
        private const val APP_VERSION = "1.0.0"
        internal const val BACKUP_INFO = "backup.json"
        private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 1 * 1024 * 1024
        private const val MAX_UNCOMPRESSED_BYTES = 32L * 1024 * 1024
        private const val MAX_ENTRIES = 2000

        /**
         * Allow-list: `backup.json`, `accounts/secret.key`, `accounts/<id>/(account|credentials).json`,
         * optionally nested below one or more folders (hand-zipped data directories). Anything else
         * (logs, settings, executables…) is ignored.
         */
        /**
         * Captures either the bare `backup.json` (if nested under folders) or the
         * path **below** `accounts/` so the caller always works with a uniform
         * relative layout: `backup.json` or `<id>/<file>`.
         */
        private val ENTRY_PATTERN = Regex("""(?:^|/)(?:(backup\.json)|accounts/(secret\.key|[A-Za-z0-9_-]{1,64}/(?:account|credentials)\.json))$""")
    }
}
