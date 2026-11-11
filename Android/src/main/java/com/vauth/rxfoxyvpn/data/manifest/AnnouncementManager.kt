package com.vauth.rxfoxyvpn.data.manifest

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Persistence backend for storing announcement IDs that the user has explicitly
 * acknowledged by tapping «متوجه شدم».
 */
interface AcknowledgedAnnouncementBackend {
    fun loadAcknowledgedIds(): Set<String>
    fun persistAcknowledgedIds(ids: Set<String>)
}

class SharedPrefsAnnouncementBackend(context: Context) : AcknowledgedAnnouncementBackend {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadAcknowledgedIds(): Set<String> {
        val result = LinkedHashSet<String>()
        prefs.getStringSet(KEY_ACK_SET, null)?.forEach { id ->
            val trimmed = id.trim()
            if (trimmed.isNotEmpty()) result.add(trimmed)
        }
        val rawJson = prefs.getString(KEY_ACK_JSON, null)
        if (!rawJson.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i).trim()
                    if (id.isNotEmpty()) result.add(id)
                }
            }
        }
        return result
    }

    override fun persistAcknowledgedIds(ids: Set<String>) {
        val cleanCopy = HashSet<String>(ids.size)
        val jsonArr = JSONArray()
        for (id in ids) {
            val trimmed = id.trim()
            if (trimmed.isNotEmpty()) {
                cleanCopy.add(trimmed)
                jsonArr.put(trimmed)
            }
        }
        // commit() guarantees durable persistence immediately upon tapping «متوجه شدم»,
        // even if the user closes the app milliseconds later.
        prefs.edit()
            .putStringSet(KEY_ACK_SET, cleanCopy)
            .putString(KEY_ACK_JSON, jsonArr.toString())
            .commit()
    }

    companion object {
        private const val PREFS_NAME = "foxyvpn_acknowledged_announcements"
        private const val KEY_ACK_SET = "acknowledged_ids_v1"
        private const val KEY_ACK_JSON = "acknowledged_ids_json_v1"
    }
}

class InMemoryAnnouncementBackend(initialIds: Set<String> = emptySet()) : AcknowledgedAnnouncementBackend {
    private val lock = Any()
    private val stored = LinkedHashSet<String>(initialIds)

    override fun loadAcknowledgedIds(): Set<String> = synchronized(lock) { LinkedHashSet(stored) }

    override fun persistAcknowledgedIds(ids: Set<String>) {
        synchronized(lock) {
            stored.clear()
            stored.addAll(ids)
        }
    }
}

/**
 * Coordinates unread active Announcements from [RemoteManifest].
 *
 * Rules enforced:
 *  - Only announcements with `enabled == true` and valid `id`, `title`, and `message`
 *    whose `id` has NOT yet been acknowledged are returned.
 *  - Multiple active unread announcements are returned in exact Manifest order so the UI
 *    presents them sequentially one by one without dropping any item.
 *  - An announcement's `id` is persisted ONLY when [acknowledge] is invoked (strictly on
 *    the user's «متوجه شدم» button click — never upon display or dismissal).
 */
class AnnouncementManager(
    private val backend: AcknowledgedAnnouncementBackend,
) {
    private val lock = Any()
    private val acknowledgedSet = LinkedHashSet<String>(runCatching { backend.loadAcknowledgedIds() }.getOrDefault(emptySet()))

    private val _acknowledgedIds = MutableStateFlow<Set<String>>(acknowledgedSet.toSet())
    val acknowledgedIds: StateFlow<Set<String>> = _acknowledgedIds.asStateFlow()

    fun isAcknowledged(id: String): Boolean {
        val clean = id.trim()
        if (clean.isEmpty()) return true
        return synchronized(lock) { acknowledgedSet.contains(clean) }
    }

    /**
     * Returns all unread, active (`enabled == true`), well-formed announcements in
     * their exact Manifest order.
     */
    fun pendingAnnouncements(
        manifest: RemoteManifest?,
        acknowledged: Set<String> = _acknowledgedIds.value,
    ): List<ManifestAnnouncement> {
        val items = manifest?.announcements ?: return emptyList()
        if (items.isEmpty()) return emptyList()
        val seenIds = HashSet<String>()
        val pending = ArrayList<ManifestAnnouncement>()
        for (item in items) {
            val id = item.id.trim()
            val title = item.title.trim()
            val message = item.message.trim()
            if (!item.enabled || id.isEmpty() || title.isEmpty() || message.isEmpty()) continue
            if (acknowledged.contains(id)) continue
            if (seenIds.add(id)) {
                pending.add(item)
            }
        }
        return pending
    }

    /**
     * Marks [announcementId] as read and persists it immediately.
     * MUST be called strictly when the user clicks «متوجه شدم».
     */
    fun acknowledge(announcementId: String) {
        val clean = announcementId.trim()
        if (clean.isEmpty()) return
        val snapshot: Set<String> = synchronized(lock) {
            if (!acknowledgedSet.add(clean)) return
            LinkedHashSet(acknowledgedSet)
        }
        runCatching { backend.persistAcknowledgedIds(snapshot) }
        _acknowledgedIds.value = snapshot
    }
}
