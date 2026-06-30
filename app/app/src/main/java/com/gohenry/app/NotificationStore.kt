package com.gohenry.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One locally-captured FCM notification, kept for in-app review ONLY. Holds the
 * rendered banner [title]/[body], the originating [event] and [vin], and the
 * COMPLETE [data] payload the backend sent so the review screen can surface
 * every associated field. [receivedAtMillis] is the device wall-clock time the
 * push arrived (used to order newest → oldest and to age entries out of the
 * retention window); [timestampUtc] is the server's ISO-8601 event time when
 * present.
 */
data class StoredNotification(
    val vin: String,
    val event: String?,
    val title: String,
    val body: String,
    val timestampUtc: String?,
    val receivedAtMillis: Long,
    val data: Map<String, String>,
)

/**
 * Local, review-only store for recently-received FCM notifications, backed by
 * SharedPreferences (GoHenry has no Room/DI, mirroring [PushRegistrar]).
 *
 * Behaviour is dictated by the per-vehicle "Save recent alerts" toggle on the
 * Notification-setup screen plus a device-wide retention window:
 *  - Tracking is OFF by default, and is opted into PER VEHICLE (by VIN).
 *  - For a tracked vehicle, every notification received within the last
 *    [getTrackingDays] days is kept ([MIN_DAYS]..[MAX_DAYS], default
 *    [DEFAULT_DAYS]); older entries are pruned. [HARD_CAP_PER_VIN] is only a
 *    safety net against pathological volume.
 *  - The user never deletes or manages entries — this is review only.
 *  - Flipping a vehicle's toggle EITHER way wipes that vehicle's history (see
 *    [setTrackingEnabled]) while leaving other vehicles untouched.
 *
 * The same prefs file is opened from both the FCM service (writer) and the
 * ViewModel (reader); Android caches one SharedPreferences instance per name
 * per process, so reads observe writes. [record] is additionally synchronized
 * on a process-wide lock so a push arriving on the FCM thread can't race the
 * read-modify-write.
 */
class NotificationStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** VINs the user has opted into capturing — independent per vehicle. */
    fun enabledVins(): Set<String> {
        val raw = prefs.getString(KEY_ENABLED_VINS, null) ?: return emptySet()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                .toSet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    fun isTrackingEnabled(vin: String): Boolean = enabledVins().contains(vin)

    /**
     * Enables/disables capture for a SINGLE vehicle. ALWAYS clears that
     * vehicle's stored history — toggling a car on OR off starts it clean while
     * leaving every other vehicle's tracking and history untouched.
     */
    fun setTrackingEnabled(vin: String, enabled: Boolean) {
        if (vin.isBlank()) return
        synchronized(LOCK) {
            val vins = enabledVins().toMutableSet()
            if (enabled) vins.add(vin) else vins.remove(vin)
            prefs.edit()
                .putString(KEY_ENABLED_VINS, JSONArray(vins.toList()).toString())
                .apply()
            writeAll(readAll().filter { it.vin != vin })
            // Start clean: a toggled vehicle has no history, so drop its read-marker.
            writeLastReadMap(lastReadMap().apply { remove(vin) })
        }
    }

    /** Device-wide retention window in days, clamped to [MIN_DAYS]..[MAX_DAYS]. */
    fun getTrackingDays(): Int =
        prefs.getInt(KEY_TRACKING_DAYS, DEFAULT_DAYS).coerceIn(MIN_DAYS, MAX_DAYS)

    /**
     * Sets the retention window (days) and immediately prunes any stored entry
     * that now falls outside it. Applies to every tracked vehicle.
     */
    fun setTrackingDays(days: Int) {
        val clamped = days.coerceIn(MIN_DAYS, MAX_DAYS)
        synchronized(LOCK) {
            prefs.edit().putInt(KEY_TRACKING_DAYS, clamped).apply()
            writeAll(pruneOld(readAll(), clamped))
        }
    }

    /**
     * Records [n] when its vehicle is tracked, dropping anything older than the
     * current retention window. No-op when that VIN isn't tracked.
     */
    fun record(n: StoredNotification) {
        if (!isTrackingEnabled(n.vin)) return
        synchronized(LOCK) {
            // Re-check inside the lock: a concurrent setTrackingEnabled() may have
            // turned this car off (and cleared its history) since the check above.
            if (!isTrackingEnabled(n.vin)) return
            val all = readAll().toMutableList()
            all.add(n)
            writeAll(pruneOld(all, getTrackingDays()))
        }
    }

    /**
     * Records the wall-clock time a push was received, regardless of whether its
     * VIN is tracked. Used by the Settings ▸ Notification diagnostics panel to show
     * "last push received". Kept here so the single `notif_history` prefs file owns
     * all notification metadata (no new store).
     */
    fun recordPushSeen(atMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_PUSH_SEEN, atMillis).apply()
    }

    /** Epoch millis of the most recent push received (0 = none observed). */
    fun lastPushSeenMillis(): Long = prefs.getLong(KEY_LAST_PUSH_SEEN, 0L)

    /**
     * Marks every currently-stored notification for [vin] as read by advancing
     * that vehicle's read-marker to its newest captured entry. Called when the
     * user opens the vehicle's notification history, so the carousel "unread"
     * dot for that car clears. The marker only ever moves forward.
     */
    fun markRead(vin: String) {
        if (vin.isBlank()) return
        synchronized(LOCK) {
            val newest = readAll().filter { it.vin == vin }.maxOfOrNull { it.receivedAtMillis } ?: 0L
            val map = lastReadMap()
            val marker = maxOf(newest, map[vin] ?: 0L)
            map[vin] = marker
            writeLastReadMap(map)
        }
    }

    /**
     * VINs with at least one captured notification newer than that vehicle's
     * read-marker (i.e. unread). Drives the carousel's per-vehicle unread dot.
     * Only entries within the retention window count (older ones are pruned).
     */
    fun unreadVins(): Set<String> {
        val read = lastReadMap()
        return allRecent()
            .filter { it.receivedAtMillis > (read[it.vin] ?: 0L) }
            .map { it.vin }
            .toSet()
    }

    /** This VIN's captured notifications within the window, newest first. */
    fun forVin(vin: String): List<StoredNotification> {
        val cutoff = System.currentTimeMillis() - getTrackingDays().toLong() * MILLIS_PER_DAY
        return readAll()
            .filter { it.vin == vin && it.receivedAtMillis >= cutoff }
            .sortedByDescending { it.receivedAtMillis }
            .take(HARD_CAP_PER_VIN)
    }

    /**
     * Every captured notification across ALL tracked vehicles within the
     * retention window, newest first. Backs the combined cross-vehicle history
     * screen. Entries are only ever stored for tracked VINs, so no extra VIN
     * filtering is needed here.
     */
    fun allRecent(): List<StoredNotification> {
        val cutoff = System.currentTimeMillis() - getTrackingDays().toLong() * MILLIS_PER_DAY
        return readAll()
            .filter { it.receivedAtMillis >= cutoff }
            .sortedByDescending { it.receivedAtMillis }
    }

    /**
     * Drops entries older than [days] and caps each VIN at [HARD_CAP_PER_VIN]
     * (newest kept) as a safety net.
     */
    private fun pruneOld(entries: List<StoredNotification>, days: Int): List<StoredNotification> {
        val cutoff = System.currentTimeMillis() - days.toLong() * MILLIS_PER_DAY
        return entries
            .filter { it.receivedAtMillis >= cutoff }
            .groupBy { it.vin }
            .flatMap { (_, list) ->
                list.sortedByDescending { it.receivedAtMillis }.take(HARD_CAP_PER_VIN)
            }
    }

    private fun readAll(): List<StoredNotification> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.optJSONObject(i)) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun writeAll(entries: List<StoredNotification>) {
        val arr = JSONArray()
        entries.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    /** Per-VIN read-marker map (vin → newest-read receivedAtMillis). */
    private fun lastReadMap(): MutableMap<String, Long> {
        val raw = prefs.getString(KEY_LAST_READ, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, Long>()
            o.keys().forEach { k -> m[k] = o.optLong(k) }
            m
        } catch (t: Throwable) {
            mutableMapOf()
        }
    }

    private fun writeLastReadMap(map: Map<String, Long>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(KEY_LAST_READ, o.toString()).apply()
    }

    private fun toJson(n: StoredNotification): JSONObject {
        val data = JSONObject()
        n.data.forEach { (k, v) -> data.put(k, v) }
        val o = JSONObject()
            .put("vin", n.vin)
            .put("title", n.title)
            .put("body", n.body)
            .put("receivedAtMillis", n.receivedAtMillis)
            .put("data", data)
        if (n.event != null) o.put("event", n.event)
        if (n.timestampUtc != null) o.put("timestampUtc", n.timestampUtc)
        return o
    }

    private fun fromJson(o: JSONObject?): StoredNotification? {
        if (o == null) return null
        val vin = o.optString("vin").takeIf { it.isNotBlank() } ?: return null
        val data = mutableMapOf<String, String>()
        o.optJSONObject("data")?.let { d ->
            d.keys().forEach { k -> data[k] = d.optString(k) }
        }
        return StoredNotification(
            vin = vin,
            event = o.optString("event").takeIf { it.isNotBlank() },
            title = o.optString("title"),
            body = o.optString("body"),
            timestampUtc = o.optString("timestampUtc").takeIf { it.isNotBlank() },
            receivedAtMillis = o.optLong("receivedAtMillis"),
            data = data,
        )
    }

    companion object {
        /** Retention-window bounds (days), surfaced to the settings slider. */
        const val MIN_DAYS = 1
        const val MAX_DAYS = 7
        const val DEFAULT_DAYS = 3

        private const val PREFS = "notif_history"
        private const val KEY_ENABLED_VINS = "tracking_vins"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_TRACKING_DAYS = "tracking_days"
        private const val KEY_LAST_PUSH_SEEN = "last_push_seen"
        private const val KEY_LAST_READ = "last_read"
        private const val HARD_CAP_PER_VIN = 200
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        private val LOCK = Any()
    }
}
