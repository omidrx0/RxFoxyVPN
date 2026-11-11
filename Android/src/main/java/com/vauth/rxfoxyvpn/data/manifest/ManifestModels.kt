package com.vauth.rxfoxyvpn.data.manifest

import com.vauth.rxfoxyvpn.data.relay.RelayPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Validated in-memory representation of the dedicated remote `updates.json` Manifest.
 */
data class RemoteManifest(
    val schemaVersion: Int = ManifestConfig.SUPPORTED_SCHEMA_VERSION,
    val relays: ManifestRelaysConfig = ManifestRelaysConfig(),
    val announcements: List<ManifestAnnouncement> = emptyList(),
    val fetchedAtMs: Long = 0L,
) {
    /** Active candidate relay URLs in Manifest priority order (empty when disabled). */
    val activeRelayUrls: List<String>
        get() = if (relays.enabled) relays.urls else emptyList()

    companion object {
        val EMPTY = RemoteManifest()
    }
}

data class ManifestRelaysConfig(
    val enabled: Boolean = false,
    val urls: List<String> = emptyList(),
    val shuffle: Boolean = false,
)

data class ManifestAnnouncement(
    val id: String,
    val title: String,
    val message: String,
    val enabled: Boolean = true,
)

class ManifestParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Resilient parser for `updates.json`.
 *
 * - Rejects empty/non-JSON-object documents so `RemoteManifestRepository` can fall back
 *   to the mirror URL or preserve the last valid cached Manifest.
 * - Tolerates missing optional sections (`relays`, `announcements`, `schema_version`)
 *   and isolates malformed individual array items so one bad URL or bad announcement
 *   never crashes the app or discards valid entries.
 */
object ManifestParser {

    fun parse(
        rawJson: String,
        fetchedAtMs: Long = System.currentTimeMillis(),
        allowLoopbackHttpRelays: Boolean = false,
    ): Result<RemoteManifest> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return Result.failure(ManifestParseException("Manifest body is empty or not a JSON object"))
        }

        val root = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            return Result.failure(ManifestParseException("Invalid Manifest JSON", e))
        }

        val schemaVersion = parseSchemaVersion(root)
            ?: return Result.failure(ManifestParseException("Invalid schema_version in Manifest"))

        val relaysConfig = parseRelaysSection(root, allowLoopbackHttpRelays)
        val announcements = parseAnnouncementsSection(root)

        return Result.success(
            RemoteManifest(
                schemaVersion = schemaVersion,
                relays = relaysConfig,
                announcements = announcements,
                fetchedAtMs = fetchedAtMs,
            )
        )
    }

    private fun parseSchemaVersion(root: JSONObject): Int? {
        if (!root.has("schema_version") || root.isNull("schema_version")) {
            return ManifestConfig.SUPPORTED_SCHEMA_VERSION
        }
        val raw = root.opt("schema_version")
        val version = when (raw) {
            is Number -> {
                val asDouble = raw.toDouble()
                val asInt = raw.toInt()
                if (asDouble != asInt.toDouble()) return null
                asInt
            }
            else -> return null
        }
        return version.takeIf { it >= 1 }
    }

    private fun parseRelaysSection(root: JSONObject, allowLoopbackHttpRelays: Boolean): ManifestRelaysConfig {
        if (!root.has("relays") || root.isNull("relays")) {
            return ManifestRelaysConfig()
        }
        val relaysObj = root.optJSONObject("relays") ?: return ManifestRelaysConfig()

        val enabled = (relaysObj.opt("enabled") as? Boolean) ?: false
        val shuffle = (relaysObj.opt("shuffle") as? Boolean) ?: false

        val urlsArray = relaysObj.optJSONArray("urls") ?: JSONArray()
        val validUrls = ArrayList<String>()
        val seen = LinkedHashSet<String>()

        for (i in 0 until urlsArray.length()) {
            val rawItem = urlsArray.opt(i) as? String ?: continue
            val parsedUrl = RelayPolicy.parseSharedRelayUrl(rawItem, allowLoopbackHttpRelays)?.toString() ?: continue
            if (seen.add(parsedUrl)) {
                validUrls.add(parsedUrl)
                if (validUrls.size >= RelayPolicy.MAX_SHARED_CANDIDATES) break
            }
        }

        return ManifestRelaysConfig(
            enabled = enabled,
            urls = validUrls,
            shuffle = shuffle,
        )
    }

    private fun parseAnnouncementsSection(root: JSONObject): List<ManifestAnnouncement> {
        if (!root.has("announcements") || root.isNull("announcements")) {
            return emptyList()
        }
        val arr = root.optJSONArray("announcements") ?: return emptyList()

        val result = ArrayList<ManifestAnnouncement>()
        val seenIds = HashSet<String>()

        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val parsed = parseSingleAnnouncement(item) ?: continue
            if (seenIds.add(parsed.id)) {
                result.add(parsed)
                if (result.size >= ManifestConfig.MAX_ANNOUNCEMENTS) break
            }
        }
        return result
    }

    private fun parseSingleAnnouncement(item: JSONObject): ManifestAnnouncement? {
        val id = (item.opt("id") as? String)?.trim().orEmpty()
        if (id.isEmpty() || id.length > ManifestConfig.MAX_ANNOUNCEMENT_ID_LENGTH) return null

        val title = (item.opt("title") as? String)?.trim().orEmpty()
        if (title.isEmpty()) return null

        val message = (item.opt("message") as? String)?.trim().orEmpty()
        if (message.isEmpty()) return null

        val enabled = if (item.has("enabled") && !item.isNull("enabled")) {
            item.opt("enabled") as? Boolean ?: return null
        } else {
            true
        }

        return ManifestAnnouncement(
            id = id,
            title = title,
            message = message,
            enabled = enabled,
        )
    }
}
