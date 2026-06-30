package com.gohenry.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A vehicle in the fleet, as returned by GET /fleet/vehicles. */
data class Vehicle(
    val vin: String,
    val model: String?,
    val nickname: String?,
    val modelYear: Int?,
    val displayColor: String?,
    val engineType: String?,
    // Ford app slug that unlocks this VIN (default "primary"). Keys per-slug UI
    // such as the carousel/notification card color to the right car.
    val appSlug: String = "primary",
) {
    val title: String get() = nickname?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(modelYear?.toString(), model).joinToString(" ").ifBlank { vin }
}

/** Current telemetry snapshot, as returned by GET /fleet/telemetry/{vin}. */
data class Telemetry(
    val vin: String,
    val capturedAt: String?,
    val socPct: Double?,
    val rangeValue: Double?,
    val rangeUnit: String?,
    val odometerValue: Double?,
    val odometerUnit: String?,
    val chargingStatus: String?,
    val pluggedIn: Boolean?,
    val latitude: Double?,
    val longitude: Double?,
    val lastStatus: String?,
    val lastPolledAt: String?,
    val lastOdometerKm: Double?,
    val lastWasActive: Boolean?,
    val lastTripLostSignal: Boolean?,
    val telemetryFeedLost: Boolean?,
    val lastHasOpenActivity: Boolean?,
    // Additive read-only metrics from the cached Ford telemetry read.
    val ignition: String?,
    val gearLever: String?,
    val oilLifePct: Double?,
    val outsideTempValue: Double?,
    val outsideTempUnit: String?,
    val alarmStatus: String?,
    val doorLocks: String?,
    val tirePressureStatus: String?,
    val fuelLevelValue: Double?,
    val fuelLevelUnit: String?,
    val interiorTempValue: Double?,
    val interiorTempUnit: String?,
    // Full curated Ford raw-telemetry datafeed for the Raw Data screen (additive).
    val rawFields: List<RawField> = emptyList(),
    // Active road trip pointer (denormalized so the Start/Stop card + reinstall
    // rehydrate need no extra call — telemetry is always fetched on launch/select).
    val activeRoadTripId: String? = null,
    val activeRoadTripName: String? = null,
    val activeRoadTripStartedAt: String? = null,
) {
    /** True when this car currently has an open road trip. */
    val hasActiveRoadTrip: Boolean get() = !activeRoadTripId.isNullOrBlank()
}

/**
 * A road trip — a named, durable journey grouping multiple individual trips and
 * the notification events within its window. Returned by the fleet/roadtrips
 * endpoints. [timeline] is populated only by the detail call.
 */
data class RoadTrip(
    val id: String,
    val vin: String,
    val name: String,
    val status: String,            // active | ended
    val startedAt: String?,
    val endedAt: String?,
    val distanceKm: Double?,
    val segmentCount: Int,
    val chargeStops: Int,
    val eventCount: Int,
    val startLatitude: Double?,
    val startLongitude: Double?,
    val endLatitude: Double?,
    val endLongitude: Double?,
    val startMethod: String,
    val endReason: String?,
    val timeline: List<RoadTripEvent> = emptyList(),
) {
    val isActive: Boolean get() = status.equals("active", ignoreCase = true)
}

/** One event stamped onto a road trip's timeline. */
data class RoadTripEvent(
    val ts: String,
    val event: String,
    val detail: String?,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeM: Double? = null,
    val outsideTempC: Double? = null,
)

/** One raw Ford field as surfaced on the Raw Data screen. */
data class RawField(
    val name: String,
    val value: String,
    val unit: String?,
    val engine: String?,
    /** Raw Ford telemetry field path (e.g. metrics.xevBatteryChargeDisplayStatus.value). */
    val path: String? = null,
) {
    /** Display value with its original Ford unit appended (as-is, no conversion). */
    val display: String get() = if (!unit.isNullOrBlank()) "$value $unit" else value
}

/** Per-VIN push toggles, as returned by GET /fleet/notifications/{vin}. */
data class NotifyPrefs(
    val start: Boolean,
    val stop: Boolean,
    val chargeInProgress: Boolean = false,
    val chargeComplete: Boolean = false,
    val chargeError: Boolean = false,
    val lostSignal: Boolean = false,
    val telemetryFeedLost: Boolean = false,
    val tirePressure: Boolean = false,
    val alarm: Boolean = false,
    val roadTripStart: Boolean = false,
    val roadTripEnd: Boolean = false,
)

/** App-wide road-trip automation settings, from GET /fleet/roadtripsettings. */
data class RoadTripSettings(
    val autoStart: Boolean = false,
    val idleHours: Int = 12,   // 2..12
    val maxDays: Int = 7,      // 1..7
    val endOnStop: Boolean = false,
)

/**
 * App-wide poll settings, from GET /fleet/pollsettings: how often the backend
 * fetches Ford telemetry and how many consecutive missed polls count as "lost
 * signal" (the dwell time is lostSignalPolls × cadenceMinutes).
 */
data class PollSettings(
    val cadenceMinutes: Int = 2,    // 1..10
    val lostSignalPolls: Int = 10,  // 5..20
)

/**
 * One row in the per-user Ford account status payload (one entry per CONFIGURED
 * app slug — `isLinked=false` covers slugs the user has never linked). Mirrors
 * the JSON returned by GET /ford/account/status, which the QoastQurrent app
 * also consumes. Ford re-auth is an account-level operation, so it is NOT
 * scoped to any single VIN.
 */
data class FordAccountStatus(
    val appSlug: String,
    val isPrimary: Boolean,
    val isLinked: Boolean,
    val status: String,           // ACTIVE | NEEDS_REAUTH | UNLINKED
    val needsReauth: Boolean,
    val lastRefreshAt: String?,
    /** Whole days until the next re-auth is expected; negative when overdue. */
    val daysUntilReauth: Int?,
)

/**
 * Raised for non-2xx responses (and network failures) so the UI can show a
 * friendly, diagnosable message. [requestUrl] is the URL that was attempted with
 * the function key redacted — safe to surface in the UI. [status] is the HTTP
 * status, or -1 for a network/connection failure that never reached the backend.
 */
class GoHenryApiException(
    val status: Int,
    val errorCode: String?,
    val requestUrl: String? = null,
    message: String? = null,
) : Exception(message ?: "Backend returned $status${errorCode?.let { " ($it)" } ?: ""}")

/**
 * Minimal read-through client for the GoHenry backend. Uses HttpURLConnection
 * + org.json (both in the Android platform) to keep dependencies tiny. All calls
 * are blocking and MUST be invoked from a background dispatcher.
 */
class GoHenryApi(
    private val baseUrl: String = BuildConfig.BACKEND_BASE_URL,
    private val userId: String = BuildConfig.USER_ID,
    private val functionKey: String = BuildConfig.FUNCTION_KEY,
) {
    fun getVehicles(): List<Vehicle> = parseVehicles(get("fleet/vehicles"))

    fun getTelemetry(vin: String): Telemetry = parseTelemetry(get("fleet/telemetry/$vin"), vin)

    /**
     * All fields the backend exposes for this VIN's cached telemetry snapshot,
     * flattened to display name/value pairs for the detail screen. This reuses
     * the deployed `fleet/telemetry/{vin}` endpoint (the same one the carousel
     * reads) rather than a separate `/cache` route, so the detail screen never
     * 404s against backends that don't expose that extra route.
     */
    fun getCachedTelemetryFields(vin: String): List<RawField> {
        val o = JSONObject(get("fleet/telemetry/$vin"))
        // Prefer the full curated Ford datafeed when the backend supplies it.
        val raw = parseRawFields(o.optJSONArray("rawFields"))
        if (raw.isNotEmpty()) return raw
        // Fallback for backends without rawFields: flatten the DTO's top-level keys.
        return o.keys().asSequence()
            .filter { it != "rawFields" }
            .sorted()
            .map { key ->
                val v = o.opt(key)
                val text = when {
                    v == null || v == JSONObject.NULL -> "null"
                    v is String -> v
                    v is Number || v is Boolean -> v.toString()
                    else -> v.toString()
                }
                RawField(key, text, null, null)
            }
            .toList()
    }

    /** GET /fleet/notifications/{vin} — current per-VIN start/stop push toggles. */
    fun getNotifyPrefs(vin: String): NotifyPrefs = parseNotifyPrefs(get("fleet/notifications/$vin"))

    /** GET /fleet/pollsettings — app-wide Ford poll cadence (1..10 min) + lost-signal poll count (5..20). */
    fun getPollSettings(): PollSettings {
        val o = JSONObject(get("fleet/pollsettings"))
        return PollSettings(
            cadenceMinutes = o.optInt("cadenceMinutes", 2).coerceIn(1, 10),
            lostSignalPolls = o.optInt("lostSignalPolls", 10).coerceIn(5, 20),
        )
    }

    /** POST /fleet/pollsettings — set cadence + lost-signal poll count; returns the clamped values. */
    fun setPollSettings(cadenceMinutes: Int, lostSignalPolls: Int): PollSettings {
        val body = JSONObject()
            .put("cadenceMinutes", cadenceMinutes.coerceIn(1, 10))
            .put("lostSignalPolls", lostSignalPolls.coerceIn(5, 20))
            .toString()
        val o = JSONObject(send("POST", "fleet/pollsettings", body))
        return PollSettings(
            cadenceMinutes = o.optInt("cadenceMinutes", cadenceMinutes).coerceIn(1, 10),
            lostSignalPolls = o.optInt("lostSignalPolls", lostSignalPolls).coerceIn(5, 20),
        )
    }

    /** POST /fleet/notifications/{vin} — set the per-VIN start/stop + charge push toggles. */
    fun setNotifyPrefs(
        vin: String,
        start: Boolean,
        stop: Boolean,
        chargeInProgress: Boolean = false,
        chargeComplete: Boolean = false,
        chargeError: Boolean = false,
        lostSignal: Boolean = false,
        telemetryFeedLost: Boolean = false,
        tirePressure: Boolean = false,
        alarm: Boolean = false,
        roadTripStart: Boolean = false,
        roadTripEnd: Boolean = false,
    ): NotifyPrefs {
        val body = JSONObject()
            .put("start", start)
            .put("stop", stop)
            .put("chargeInProgress", chargeInProgress)
            .put("chargeComplete", chargeComplete)
            .put("chargeError", chargeError)
            .put("lostSignal", lostSignal)
            .put("telemetryFeedLost", telemetryFeedLost)
            .put("tirePressure", tirePressure)
            .put("alarm", alarm)
            .put("roadTripStart", roadTripStart)
            .put("roadTripEnd", roadTripEnd)
            .toString()
        return parseNotifyPrefs(send("POST", "fleet/notifications/$vin", body), NotifyPrefs(start, stop, chargeInProgress, chargeComplete, chargeError, lostSignal, telemetryFeedLost, tirePressure, alarm, roadTripStart, roadTripEnd))
    }

    /**
     * POST /notifications/register — upsert this install's FCM token under the
     * caller's `user:{id}` tag. Same backend contract the SashaSync app uses.
     */
    fun registerForPush(installationId: String, fcmToken: String) {
        val body = JSONObject()
            .put("installationId", installationId)
            .put("fcmToken", fcmToken)
            // App slug so the backend can deliver/gate GoHenry's trip + charge
            // notifications independently of the QoastQurrent app (tag app:gohenry).
            .put("app", "gohenry")
            .toString()
        send("POST", "notifications/register", body)
    }

    /**
     * GET /ford/account/status — one row per configured Ford developer app
     * slug for the signed-in user. Account-level (not per-VIN). Returns an
     * empty list if the backend reports no configured apps.
     */
    fun getFordAccountStatuses(): List<FordAccountStatus> = parseFordAccountStatuses(get("ford/account/status"))

    /** GET /fleet/roadtrips/{vin} — a car's road trips, newest first (no timeline). */
    fun listRoadTrips(vin: String): List<RoadTrip> = parseRoadTrips(get("fleet/roadtrips/$vin"))

    /** GET /fleet/roadtrips/{vin}/{id} — a single road trip including its event timeline. */
    fun getRoadTrip(vin: String, id: String): RoadTrip = parseRoadTrip(JSONObject(get("fleet/roadtrips/$vin/$id")))

    /**
     * POST /fleet/roadtrips/{vin}/start — open a new road trip. [name] is optional;
     * the backend supplies a dated default when blank. Returns the created trip.
     */
    fun startRoadTrip(vin: String, name: String? = null): RoadTrip {
        val body = JSONObject().apply { if (!name.isNullOrBlank()) put("name", name.trim()) }.toString()
        return parseRoadTrip(JSONObject(send("POST", "fleet/roadtrips/$vin/start", body)))
    }

    /** POST /fleet/roadtrips/{vin}/stop — close the active road trip; returns it ended. */
    fun stopRoadTrip(vin: String): RoadTrip =
        parseRoadTrip(JSONObject(send("POST", "fleet/roadtrips/$vin/stop", "{}")))

    /** POST /fleet/roadtrips/{vin}/{id}/rename — rename a road trip; returns it updated. */
    fun renameRoadTrip(vin: String, id: String, name: String): RoadTrip {
        val body = JSONObject().put("name", name.trim()).toString()
        return parseRoadTrip(JSONObject(send("POST", "fleet/roadtrips/$vin/$id/rename", body)))
    }

    /** GET /fleet/roadtripsettings — app-wide auto-start + auto-close tuning. */
    fun getRoadTripSettings(): RoadTripSettings = parseRoadTripSettings(get("fleet/roadtripsettings"))

    /** POST /fleet/roadtripsettings — update auto-start + idle/max + end-on-stop; returns the clamped values. */
    fun setRoadTripSettings(autoStart: Boolean, idleHours: Int, maxDays: Int, endOnStop: Boolean): RoadTripSettings {
        val body = JSONObject()
            .put("autoStart", autoStart)
            .put("idleHours", idleHours.coerceIn(2, 12))
            .put("maxDays", maxDays.coerceIn(1, 7))
            .put("endOnStop", endOnStop)
            .toString()
        return parseRoadTripSettings(send("POST", "fleet/roadtripsettings", body))
    }

    /** DELETE /fleet/roadtrips/{vin}/{id} — permanently removes a road trip and its timeline. */
    fun deleteRoadTrip(vin: String, id: String) {
        send("DELETE", "fleet/roadtrips/$vin/$id", null)
    }

    /**
     * POST /oauth/start?app={slug} — kicks off the Ford OAuth handshake and
     * returns the Ford authorize URL. The caller opens it in a browser; the
     * backend's OAuth_Callback completes the handshake and flips the account
     * back to ACTIVE. Telemetry / vehicle data is preserved.
     */
    fun startFordOAuth(appSlug: String): String {
        val body = send("POST", "oauth/start", "{}", listOf("app" to appSlug.trim().lowercase()))
        return JSONObject(body).optString("url").takeIf { it.isNotBlank() }
            ?: throw GoHenryApiException(-1, "missing_url", null, "oauth/start response had no 'url' field.")
    }


    companion object {
        fun parseVehicles(json: String): List<Vehicle> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Vehicle(
                    vin = o.getString("vin"),
                    model = o.optStringOrNull("model"),
                    nickname = o.optStringOrNull("nickname"),
                    modelYear = o.optIntOrNull("modelYear"),
                    displayColor = o.optStringOrNull("displayColor"),
                    engineType = o.optStringOrNull("engineType"),
                    appSlug = o.optString("appSlug", "primary").ifBlank { "primary" },
                )
            }
        }

        fun parseTelemetry(json: String, fallbackVin: String): Telemetry {
            val o = JSONObject(json)
            return Telemetry(
                vin = o.optString("vin", fallbackVin),
                capturedAt = o.optStringOrNull("capturedAt"),
                socPct = o.optDoubleOrNull("socPct"),
                rangeValue = o.optDoubleOrNull("rangeValue"),
                rangeUnit = o.optStringOrNull("rangeUnit"),
                odometerValue = o.optDoubleOrNull("odometerValue"),
                odometerUnit = o.optStringOrNull("odometerUnit"),
                chargingStatus = o.optStringOrNull("chargingStatus"),
                pluggedIn = if (o.isNull("pluggedIn")) null else o.optBoolean("pluggedIn"),
                latitude = o.optDoubleOrNull("latitude"),
                longitude = o.optDoubleOrNull("longitude"),
                lastStatus = o.optStringOrNull("lastStatus"),
                lastPolledAt = o.optStringOrNull("lastPolledAt"),
                lastOdometerKm = o.optDoubleOrNull("lastOdometerKm"),
                lastWasActive = if (o.isNull("lastWasActive")) null else o.optBoolean("lastWasActive"),
                lastTripLostSignal = if (o.isNull("lastTripLostSignal")) null else o.optBoolean("lastTripLostSignal"),
                telemetryFeedLost = if (o.isNull("telemetryFeedLost")) null else o.optBoolean("telemetryFeedLost"),
                lastHasOpenActivity = if (o.isNull("lastHasOpenActivity")) null else o.optBoolean("lastHasOpenActivity"),
                ignition = o.optStringOrNull("ignition"),
                gearLever = o.optStringOrNull("gearLever"),
                oilLifePct = o.optDoubleOrNull("oilLifePct"),
                outsideTempValue = o.optDoubleOrNull("outsideTempValue"),
                outsideTempUnit = o.optStringOrNull("outsideTempUnit"),
                alarmStatus = o.optStringOrNull("alarmStatus"),
                doorLocks = o.optStringOrNull("doorLocks"),
                tirePressureStatus = o.optStringOrNull("tirePressureStatus"),
                fuelLevelValue = o.optDoubleOrNull("fuelLevelValue"),
                fuelLevelUnit = o.optStringOrNull("fuelLevelUnit"),
                interiorTempValue = o.optDoubleOrNull("interiorTempValue"),
                interiorTempUnit = o.optStringOrNull("interiorTempUnit"),
                rawFields = parseRawFields(o.optJSONArray("rawFields")),
                activeRoadTripId = o.optStringOrNull("activeRoadTripId"),
                activeRoadTripName = o.optStringOrNull("activeRoadTripName"),
                activeRoadTripStartedAt = o.optStringOrNull("activeRoadTripStartedAt"),
            )
        }

        fun parseRawFields(arr: JSONArray?): List<RawField> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = r.optStringOrNull("name") ?: return@mapNotNull null
                RawField(
                    name = name,
                    value = r.optString("value"),
                    unit = r.optStringOrNull("unit"),
                    engine = r.optStringOrNull("engine"),
                    path = r.optStringOrNull("path"),
                )
            }
        }

        fun parseNotifyPrefs(json: String, fallback: NotifyPrefs = NotifyPrefs(start = false, stop = false)): NotifyPrefs {
            val o = JSONObject(json)
            return NotifyPrefs(
                start = o.optBoolean("start", fallback.start),
                stop = o.optBoolean("stop", fallback.stop),
                chargeInProgress = o.optBoolean("chargeInProgress", fallback.chargeInProgress),
                chargeComplete = o.optBoolean("chargeComplete", fallback.chargeComplete),
                chargeError = o.optBoolean("chargeError", fallback.chargeError),
                lostSignal = o.optBoolean("lostSignal", fallback.lostSignal),
                telemetryFeedLost = o.optBoolean("telemetryFeedLost", fallback.telemetryFeedLost),
                tirePressure = o.optBoolean("tirePressure", fallback.tirePressure),
                alarm = o.optBoolean("alarm", fallback.alarm),
                roadTripStart = o.optBoolean("roadTripStart", fallback.roadTripStart),
                roadTripEnd = o.optBoolean("roadTripEnd", fallback.roadTripEnd),
            )
        }

        fun parseRoadTripSettings(json: String): RoadTripSettings {
            val o = JSONObject(json)
            return RoadTripSettings(
                autoStart = o.optBoolean("autoStart", false),
                idleHours = o.optInt("idleHours", 12).coerceIn(2, 12),
                maxDays = o.optInt("maxDays", 7).coerceIn(1, 7),
                endOnStop = o.optBoolean("endOnStop", false),
            )
        }

        fun parseFordAccountStatuses(body: String): List<FordAccountStatus> {
            if (body.isBlank()) return emptyList()
            val arr = JSONObject(body.trim()).optJSONArray("accounts") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FordAccountStatus(
                    appSlug = o.optString("appSlug", "primary"),
                    isPrimary = o.optBoolean("isPrimary", false),
                    isLinked = o.optBoolean("isLinked", false),
                    status = o.optString("status", "UNLINKED"),
                    needsReauth = o.optBoolean("needsReauth", false),
                    lastRefreshAt = o.optStringOrNull("lastRefreshAt"),
                    daysUntilReauth = if (o.has("daysUntilReauth") && !o.isNull("daysUntilReauth")) o.optInt("daysUntilReauth") else null,
                )
            }
        }

        fun parseRoadTrips(json: String): List<RoadTrip> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i -> parseRoadTrip(arr.getJSONObject(i)) }
        }

        fun parseRoadTrip(o: JSONObject): RoadTrip = RoadTrip(
            id = o.optString("id"),
            vin = o.optString("vin"),
            name = o.optString("name"),
            status = o.optString("status", "ended"),
            startedAt = o.optStringOrNull("startedAt"),
            endedAt = o.optStringOrNull("endedAt"),
            distanceKm = o.optDoubleOrNull("distanceKm"),
            segmentCount = o.optInt("segmentCount", 0),
            chargeStops = o.optInt("chargeStops", 0),
            eventCount = o.optInt("eventCount", 0),
            startLatitude = o.optDoubleOrNull("startLatitude"),
            startLongitude = o.optDoubleOrNull("startLongitude"),
            endLatitude = o.optDoubleOrNull("endLatitude"),
            endLongitude = o.optDoubleOrNull("endLongitude"),
            startMethod = o.optString("startMethod", "manual"),
            endReason = o.optStringOrNull("endReason"),
            timeline = parseRoadTripEvents(o.optJSONArray("timeline")),
        )

        fun parseRoadTripEvents(arr: JSONArray?): List<RoadTripEvent> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                RoadTripEvent(
                    ts = e.optString("ts"),
                    event = e.optString("event"),
                    detail = e.optStringOrNull("detail"),
                    latitude = e.optDoubleOrNull("latitude"),
                    longitude = e.optDoubleOrNull("longitude"),
                    altitudeM = e.optDoubleOrNull("altitudeM"),
                    outsideTempC = e.optDoubleOrNull("outsideTempC"),
                )
            }
        }
    }

    private fun get(path: String): String = send("GET", path, null)

    private fun send(
        method: String,
        path: String,
        jsonBody: String?,
        extraParams: List<Pair<String, String>> = emptyList(),
    ): String {
        val sep = if (baseUrl.endsWith("/")) "" else "/"
        // code (function key) first, then any extra params (URL-encoded). The
        // redacted variant masks only the key so it's safe to show in errors.
        val realParts = buildList {
            if (functionKey.isNotBlank()) add("code=$functionKey")
            extraParams.forEach { (k, v) -> add("$k=" + URLEncoder.encode(v, "UTF-8")) }
        }
        val maskedParts = buildList {
            if (functionKey.isNotBlank()) add("code=***")
            extraParams.forEach { (k, v) -> add("$k=" + URLEncoder.encode(v, "UTF-8")) }
        }
        val query = if (realParts.isEmpty()) "" else "?" + realParts.joinToString("&")
        val maskedQuery = if (maskedParts.isEmpty()) "" else "?" + maskedParts.joinToString("&")
        val fullUrl = "$baseUrl$sep$path$query"
        val redactedUrl = "$baseUrl$sep$path$maskedQuery"

        val conn = try {
            URL(fullUrl).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw GoHenryApiException(-1, "bad_url", redactedUrl, "Bad backend URL: $method $redactedUrl — ${e.message}")
        }
        conn.apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("x-user-id", userId)
            setRequestProperty("Accept", "application/json")
            if (jsonBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (jsonBody != null) {
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
            }
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                // Never reached the backend (DNS, TLS, timeout, no network, …).
                throw GoHenryApiException(-1, "network_error", redactedUrl, "Could not reach backend: $method $redactedUrl — ${e.message}")
            }
            if (status in 200..299) {
                return conn.inputStream?.bufferedReader()?.use { it.readText() } ?: "{}"
            }
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }
            throw GoHenryApiException(
                status,
                errorBody?.let { runCatching { JSONObject(it).optStringOrNull("error") }.getOrNull() },
                redactedUrl,
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * A masked, human-readable summary of the backend wiring this client is
     * using (base URL, user id, and whether a function key is configured). Safe
     * to show in the UI: the function key value is NEVER revealed, only its
     * presence and length. Surfaced alongside errors so the most common cause —
     * an unset/wrong `local.properties` value — is obvious without a debugger.
     */
    fun configSummary(): String {
        val baseState = baseUrl.ifBlank { "(empty — set backend.baseUrl)" }
        val userState = if (userId.isBlank()) "MISSING (set backend.userId)" else userId
        val keyState = if (functionKey.isBlank())
            "MISSING (set backend.functionKey)"
        else
            "set (length ${functionKey.length})"
        return buildString {
            append("baseUrl = ").append(baseState).append('\n')
            append("userId  = ").append(userState).append('\n')
            append("functionKey = ").append(keyState)
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }
