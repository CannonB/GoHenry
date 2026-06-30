package com.gohenry.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which top-level screen is showing. */
enum class Screen { CarSelect, CachedFields, Notifications, NotificationHistory, VehicleStatus, CombinedHistory, RoadTripHistory, Settings, RoadTripDetail }

data class GoHenryUiState(
    val screen: Screen = Screen.CarSelect,
    val loading: Boolean = false,
    val vehicles: List<Vehicle> = emptyList(),
    val selectedIndex: Int = 0,
    val telemetry: Telemetry? = null,
    val telemetryLoading: Boolean = false,
    val error: String? = null,
    val cachedFields: List<RawField> = emptyList(),
    val cachedFieldsLoading: Boolean = false,
    val cachedFieldsError: String? = null,
    // Per-VIN start/stop push toggles for the notification-setup screen.
    val prefs: Map<String, NotifyPrefs> = emptyMap(),
    val prefsLoading: Boolean = false,
    val prefsError: String? = null,
    // Account-level Ford re-authorization (one row per configured app slug).
    // Not scoped to the selected vehicle — it refreshes the Ford OAuth grant.
    val fordAccounts: List<FordAccountStatus> = emptyList(),
    val fordLoading: Boolean = false,
    val fordError: String? = null,
    val fordReauthingSlug: String? = null,
    val fordReauthUrl: String? = null,
    // Local, review-only FCM notification capture (this device), opted into PER
    // VEHICLE. notificationTrackingVins is the set of tracked VINs; the carousel
    // shows a bell on each tracked car. notificationHistory/notificationHistoryVin
    // back the review screen for whichever vehicle was opened.
    val notificationTrackingVins: Set<String> = emptySet(),
    // VINs with at least one unread captured notification (newer than the last
    // time the user opened that car's history). Drives the carousel unread dot.
    val notificationUnreadVins: Set<String> = emptySet(),
    val notificationHistory: List<StoredNotification> = emptyList(),
    val notificationHistoryVin: String? = null,
    // Combined, cross-vehicle review history (every tracked VIN's captured
    // notifications, newest first) backing the combined trip-history screen.
    val combinedHistory: List<StoredNotification> = emptyList(),
    // Which vehicle the combined-history pager is currently filtered to
    // (null = "All tracked vehicles"); read by the top-bar CSV share action so
    // the export matches exactly what's on screen.
    val combinedHistoryFilterVin: String? = null,
    // Device-wide retention window (days) for captured notifications.
    val notificationTrackingDays: Int = NotificationStore.DEFAULT_DAYS,
    // Epoch millis of the last successful telemetry fetch (network), so the
    // carousel can show a "last updated" cue. Null until the first fetch.
    val lastRefreshedAt: Long? = null,
    // Per-Ford-slug carousel/notification card color (packed ARGB) chosen on the
    // Settings screen. Missing slugs fall back to the dark-grey default.
    val slugCardColors: Map<String, Int> = emptyMap(),
    // App-wide Ford telemetry poll cadence in minutes (1..10, default 2), surfaced
    // on the Settings screen. Server-backed (Table Storage) — no SQL.
    val pollCadenceMinutes: Int = 2,
    // Consecutive missed/stale polls before "lost signal" fires (5..20). The dwell
    // time the icon + notification use is this × pollCadenceMinutes.
    val lostSignalPolls: Int = 10,
    val pollCadenceLoading: Boolean = false,
    // Road trips (server-authoritative, survives reinstall). roadTripsVin is the
    // car whose trips list is showing; selectedRoadTrip backs the detail screen.
    // roadTripBusy guards the Start/Stop buttons during an in-flight write.
    val roadTrips: List<RoadTrip> = emptyList(),
    val roadTripsVin: String? = null,
    val roadTripsLoading: Boolean = false,
    val roadTripsError: String? = null,
    // True while the home-screen road-trip tray (bottom sheet) is open.
    val roadTripTrayOpen: Boolean = false,
    val selectedRoadTrip: RoadTrip? = null,
    val roadTripDetailLoading: Boolean = false,
    val roadTripBusy: Boolean = false,
    // True when the road-trip detail screen was opened from the Road-trip history
    // screen (vs. the home tray) so Back/Delete return to the right place.
    val roadTripDetailFromHistory: Boolean = false,
    // Road-trip HISTORY screen (swipe to filter by car, like notification history).
    // Per-VIN trip lists (no timelines — those are fetched on demand for CSV/detail).
    val roadTripHistory: Map<String, List<RoadTrip>> = emptyMap(),
    val roadTripHistoryLoading: Boolean = false,
    val roadTripHistoryError: String? = null,
    // Which vehicle the history pager is filtered to (null = all cars merged);
    // read by the top-bar CSV share so the export matches what's on screen.
    val roadTripHistoryFilterVin: String? = null,
    // True while the share action is fetching trip timelines + writing the CSV.
    val roadTripHistoryExporting: Boolean = false,
    // App-wide road-trip automation (auto-start + auto-close), Settings screen.
    // Server-backed on the shared meta row — no SQL.
    val roadTripSettings: RoadTripSettings = RoadTripSettings(),
    val roadTripSettingsLoading: Boolean = false,
) {
    val selectedVehicle: Vehicle? get() = vehicles.getOrNull(selectedIndex)

    /** Card color (packed ARGB) for a slug, applying the dark-grey default. */
    fun cardColorForSlug(slug: String?): Int =
        slugCardColors[slug?.trim()?.lowercase()] ?: CardColorStore.DEFAULT_CARD_COLOR

    /** Card color for a VIN by resolving its vehicle's Ford slug. */
    fun cardColorForVin(vin: String?): Int =
        cardColorForSlug(vehicles.firstOrNull { it.vin == vin }?.appSlug)

    /**
     * Road trips for the history screen under the given filter: a single car's
     * trips when [vin] is set, otherwise every car's trips merged. Always sorted
     * active-first then newest-started-first.
     */
    fun roadTripHistoryFor(vin: String?): List<RoadTrip> {
        val trips = if (vin != null) roadTripHistory[vin].orEmpty()
        else roadTripHistory.values.flatten()
        return trips.sortedWith(
            compareByDescending<RoadTrip> { it.isActive }.thenByDescending { it.startedAt ?: "" },
        )
    }
}

/**
 * Drives the GoHenry UI: the car-select carousel (loads the fleet, then current
 * telemetry for whichever VIN is selected) and the notification-setup screen
 * (per-VIN Start/Stop push toggles). No local database for cloud data — every
 * read/write goes to the backend, which is itself a no-SQL read-through cache.
 *
 * The ONE local-only concern is the review-only notification history
 * ([NotificationStore]): incoming FCM pushes are optionally captured on-device so
 * the user can review the last few per vehicle. That needs a [Context], which is
 * why this is an [AndroidViewModel].
 */
class GoHenryViewModel(app: Application) : AndroidViewModel(app) {

    private val api: GoHenryApi = GoHenryApi()
    private val notificationStore = NotificationStore(app)
    private val cardColorStore = CardColorStore(app)

    private val _state = MutableStateFlow(
        GoHenryUiState(
            notificationTrackingVins = notificationStore.enabledVins(),
            notificationUnreadVins = notificationStore.unreadVins(),
            notificationTrackingDays = notificationStore.getTrackingDays(),
            slugCardColors = cardColorStore.all(),
        ),
    )
    val state: StateFlow<GoHenryUiState> = _state.asStateFlow()

    // In-memory, per-VIN caches so paging back to an already-loaded car — or
    // reopening its detail screen — is instant and costs no extra network call.
    // Reads/writes only ever happen on the Main dispatcher (the viewModelScope
    // continuation that resumes after the IO-bound fetch), so a plain map is safe.
    private val telemetryCache = mutableMapOf<String, Telemetry>()
    private val cachedFieldsCache = mutableMapOf<String, List<RawField>>()

    // A road-trip notification deep-link that arrived before the fleet finished
    // loading (cold start from the alert); applied once [loadFleet] completes.
    private var pendingRoadTripDeepLink = false
    private var pendingRoadTripDeepLinkVin: String? = null

    init {
        // When a push lands while the app is in the foreground, refresh the live
        // capture lists and silently re-pull the affected card's telemetry so the
        // UI stays in sync with the alert. Backgrounded? No collector runs and the
        // ON_RESUME refresh handles it instead.
        viewModelScope.launch {
            AppEvents.pushReceived.collect { vin ->
                refreshCombinedHistory()
                refreshNotificationHistory()
                refreshUnreadBadges()
                if (vin != null) {
                    telemetryCache.remove(vin)
                    val idx = _state.value.vehicles.indexOfFirst { it.vin == vin }
                    if (idx >= 0 && idx == _state.value.selectedIndex) {
                        loadTelemetry(idx, force = true, silent = true)
                    }
                }
            }
        }
    }

    fun loadFleet() {
        // A fresh fleet pull invalidates everything we cached for the old list.
        telemetryCache.clear()
        cachedFieldsCache.clear()
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val vehicles = withContext(Dispatchers.IO) { api.getVehicles() }
                _state.value = _state.value.copy(loading = false, vehicles = vehicles, selectedIndex = 0)
                if (vehicles.isNotEmpty()) loadTelemetry(0)
                // No cars yet? Pull Ford link status so the empty-state can tell the
                // user whether to LINK Ford (unlinked) vs. fix backend config.
                else loadFordAccounts()
                // Apply a notification deep-link that arrived during cold start.
                if (pendingRoadTripDeepLink && vehicles.isNotEmpty()) {
                    pendingRoadTripDeepLink = false
                    val v = pendingRoadTripDeepLinkVin
                    pendingRoadTripDeepLinkVin = null
                    applyRoadTripDeepLink(v)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = friendly(e))
            }
        }
    }

    fun select(index: Int) {
        val vehicles = _state.value.vehicles
        if (index < 0 || index >= vehicles.size) return
        val vin = vehicles[index].vin
        // Already showing this VIN's telemetry — nothing to do.
        if (index == _state.value.selectedIndex && _state.value.telemetry?.vin == vin) return
        // Serve the cached snapshot immediately (no null flash / spinner) when we
        // already have it; otherwise clear and fetch.
        val cached = telemetryCache[vin]
        _state.value = _state.value.copy(
            selectedIndex = index,
            telemetry = cached,
            telemetryLoading = cached == null,
            error = null,
        )
        // Always re-pull from the network on landing so a card that's been sitting
        // in the carousel cache reflects the latest telemetry. When we already have
        // a cached snapshot the refresh is silent (no spinner / no value flash); a
        // cold card shows the normal loading state.
        loadTelemetry(index, force = true, silent = cached != null)
    }

    fun refresh() {
        // An explicit refresh must hit the network and also drop any cached detail
        // fields for this VIN so the two views can't disagree.
        val vin = _state.value.selectedVehicle?.vin
        if (vin != null) cachedFieldsCache.remove(vin)
        loadTelemetry(_state.value.selectedIndex, force = true)
    }

    fun showCarSelect() {
        _state.value = _state.value.copy(screen = Screen.CarSelect, cachedFields = emptyList(), cachedFieldsError = null)
    }

    fun showCachedFields(vin: String) {
        if (vin.isBlank()) return
        val cached = cachedFieldsCache[vin]
        _state.value = _state.value.copy(
            screen = Screen.CachedFields,
            cachedFields = cached ?: emptyList(),
            cachedFieldsLoading = cached == null,
            cachedFieldsError = null,
        )
        if (cached == null) loadCachedFields(vin)
    }

    fun showNotifications() {
        _state.value = _state.value.copy(screen = Screen.Notifications)
        loadSelectedPrefs()
        loadFordAccounts()
    }

    /**
     * Opens the account-level Settings screen, which owns Ford authorization
     * (link / re-link / add another vehicle) and a self-diagnostics panel. Pulls
     * the latest per-slug Ford account status so the cards reflect reality.
     */
    fun showSettings() {
        _state.value = _state.value.copy(screen = Screen.Settings)
        loadFordAccounts()
        loadPollCadence()
        loadRoadTripSettings()
    }

    /**
     * Loads the app-wide poll settings (cadence minutes + lost-signal poll count)
     * from the backend so the Settings sliders reflect the values actually driving
     * the poller. Falls back to current state on error — these are non-critical UI.
     */
    fun loadPollCadence() {
        _state.value = _state.value.copy(pollCadenceLoading = true)
        viewModelScope.launch {
            try {
                val ps = withContext(Dispatchers.IO) { api.getPollSettings() }
                _state.value = _state.value.copy(
                    pollCadenceLoading = false,
                    pollCadenceMinutes = ps.cadenceMinutes,
                    lostSignalPolls = ps.lostSignalPolls,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(pollCadenceLoading = false)
            }
        }
    }

    /**
     * Persists a new app-wide poll cadence (clamped 1..10 minutes), keeping the
     * current lost-signal poll count. Optimistic, then reconciles with the echo.
     */
    fun setPollCadence(minutes: Int) {
        val clamped = minutes.coerceIn(1, 10)
        _state.value = _state.value.copy(pollCadenceMinutes = clamped)
        viewModelScope.launch {
            try {
                val applied = withContext(Dispatchers.IO) { api.setPollSettings(clamped, _state.value.lostSignalPolls) }
                _state.value = _state.value.copy(pollCadenceMinutes = applied.cadenceMinutes, lostSignalPolls = applied.lostSignalPolls)
            } catch (e: Exception) {
                // Leave the optimistic value; a later loadPollCadence() will reconcile.
            }
        }
    }

    /**
     * Persists the "lost signal" threshold as a consecutive-missed-poll count
     * (clamped 5..20), keeping the current cadence. The effective dwell time is
     * this × cadence; both the backend notification and the hero-card wifi icon
     * use it. Optimistic, then reconciles with the server echo.
     */
    fun setLostSignalPolls(polls: Int) {
        val clamped = polls.coerceIn(5, 20)
        _state.value = _state.value.copy(lostSignalPolls = clamped)
        viewModelScope.launch {
            try {
                val applied = withContext(Dispatchers.IO) { api.setPollSettings(_state.value.pollCadenceMinutes, clamped) }
                _state.value = _state.value.copy(pollCadenceMinutes = applied.cadenceMinutes, lostSignalPolls = applied.lostSignalPolls)
            } catch (e: Exception) {
                // Leave the optimistic value; a later loadPollCadence() will reconcile.
            }
        }
    }

    /**
     * Human-readable summary of the backend config the app is actually using
     * (base URL, user id, masked function-key presence). Surfaced on the Settings
     * diagnostics panel so a misconfigured local.properties is obvious in-app.
     */
    fun configSummary(): String = api.configSummary()

    // ----- Road trips ---------------------------------------------------------

    /**
     * Opens the road-trip tray (bottom sheet) for [vin] (the selected car by
     * default) and pulls its trips newest-first. The active-trip Start/Stop state
     * comes from the already-loaded telemetry, so this only needs the history
     * list. The tray replaces the old full-screen road-trips list, so the screen
     * stays on Home behind the sheet.
     */
    fun openRoadTripTray(vin: String? = null) {
        val target = vin ?: _state.value.selectedVehicle?.vin ?: return
        _state.value = _state.value.copy(
            roadTripTrayOpen = true,
            roadTripsVin = target,
            roadTrips = if (_state.value.roadTripsVin == target) _state.value.roadTrips else emptyList(),
        )
        loadRoadTrips(target)
    }

    /** Closes the road-trip tray (bottom sheet). */
    fun closeRoadTripTray() {
        _state.value = _state.value.copy(roadTripTrayOpen = false)
    }

    /**
     * Handles the "stop while a trip is open" notification deep-link: select the
     * car the alert is for and open its road-trip tray so the user can end the
     * trip with one tap. If the fleet hasn't loaded yet (cold start from the
     * notification), the request is held and applied once [loadFleet] finishes.
     */
    fun requestRoadTripDeepLink(vin: String?) {
        if (_state.value.vehicles.isEmpty()) {
            pendingRoadTripDeepLink = true
            pendingRoadTripDeepLinkVin = vin
            return
        }
        applyRoadTripDeepLink(vin)
    }

    private fun applyRoadTripDeepLink(vin: String?) {
        val vehicles = _state.value.vehicles
        if (vehicles.isEmpty()) return
        val idx = vehicles.indexOfFirst { it.vin == vin }.takeIf { it >= 0 } ?: _state.value.selectedIndex
        if (_state.value.screen != Screen.CarSelect) showCarSelect()
        select(idx)
        openRoadTripTray(vehicles.getOrNull(idx)?.vin)
    }

    /** Backs out of the road-trip detail screen to wherever it was opened from. */
    fun backFromRoadTripDetail() {
        if (_state.value.roadTripDetailFromHistory) {
            _state.value = _state.value.copy(screen = Screen.RoadTripHistory, roadTripDetailFromHistory = false)
        } else {
            _state.value = _state.value.copy(screen = Screen.CarSelect, roadTripTrayOpen = true)
        }
    }

    /** Re-pulls the road-trips list for [vin] from the backend (newest first). */
    fun loadRoadTrips(vin: String) {
        _state.value = _state.value.copy(roadTripsLoading = true, roadTripsError = null)
        viewModelScope.launch {
            try {
                val trips = withContext(Dispatchers.IO) { api.listRoadTrips(vin) }
                if (_state.value.roadTripsVin == vin) {
                    _state.value = _state.value.copy(roadTripsLoading = false, roadTrips = trips)
                }
            } catch (e: Exception) {
                if (_state.value.roadTripsVin == vin) {
                    _state.value = _state.value.copy(roadTripsLoading = false, roadTripsError = friendly(e))
                }
            }
        }
    }

    /**
     * Opens the detail screen for one road trip (full event timeline). Shows any
     * cached summary immediately, then loads the timeline.
     */
    fun openRoadTrip(id: String) {
        val vin = _state.value.roadTripsVin ?: _state.value.selectedVehicle?.vin ?: return
        val summary = _state.value.roadTrips.firstOrNull { it.id == id }
        _state.value = _state.value.copy(
            screen = Screen.RoadTripDetail,
            roadTripTrayOpen = false,
            roadTripDetailFromHistory = false,
            selectedRoadTrip = summary,
            roadTripDetailLoading = true,
        )
        viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) { api.getRoadTrip(vin, id) }
                if (_state.value.selectedRoadTrip?.id == id || _state.value.screen == Screen.RoadTripDetail) {
                    _state.value = _state.value.copy(roadTripDetailLoading = false, selectedRoadTrip = full)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(roadTripDetailLoading = false, roadTripsError = friendly(e))
            }
        }
    }

    /**
     * Starts a road trip on the selected car. On success the car's telemetry is
     * force-refreshed so the active-trip card flips to "Stop", and the list (if
     * showing) is reloaded.
     */
    fun startRoadTrip(name: String? = null) {
        val vin = _state.value.selectedVehicle?.vin ?: return
        if (_state.value.roadTripBusy) return
        _state.value = _state.value.copy(roadTripBusy = true, roadTripsError = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.startRoadTrip(vin, name) }
                afterRoadTripChange(vin)
            } catch (e: Exception) {
                _state.value = _state.value.copy(roadTripBusy = false, roadTripsError = friendly(e))
            }
        }
    }

    /** Stops the selected car's active road trip, then refreshes telemetry + list. */
    fun stopRoadTrip() {
        val vin = _state.value.selectedVehicle?.vin ?: return
        if (_state.value.roadTripBusy) return
        _state.value = _state.value.copy(roadTripBusy = true, roadTripsError = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.stopRoadTrip(vin) }
                afterRoadTripChange(vin)
            } catch (e: Exception) {
                _state.value = _state.value.copy(roadTripBusy = false, roadTripsError = friendly(e))
            }
        }
    }

    /** Shared tail for start/stop: drop the telemetry cache, re-pull it (so the
     *  active-trip pointer is current), and refresh the trips list if visible. */
    private suspend fun afterRoadTripChange(vin: String) {
        telemetryCache.remove(vin)
        try {
            val t = withContext(Dispatchers.IO) { api.getTelemetry(vin) }
            telemetryCache[vin] = t
            if (_state.value.selectedVehicle?.vin == vin) {
                _state.value = _state.value.copy(telemetry = t, lastRefreshedAt = System.currentTimeMillis())
            }
        } catch (_: Exception) { /* non-fatal — the card will reconcile on next load */ }
        _state.value = _state.value.copy(roadTripBusy = false)
        if (_state.value.roadTripTrayOpen && _state.value.roadTripsVin == vin) loadRoadTrips(vin)
    }

    /**
     * Renames a road trip (open or ended). Refreshes the detail (if showing this
     * trip) and the list so the new name appears everywhere immediately.
     */
    fun renameRoadTrip(id: String, name: String) {
        val vin = _state.value.roadTripsVin ?: _state.value.selectedVehicle?.vin ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank() || _state.value.roadTripBusy) return
        _state.value = _state.value.copy(roadTripBusy = true, roadTripsError = null)
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { api.renameRoadTrip(vin, id, trimmed) }
                val sel = _state.value.selectedRoadTrip
                _state.value = _state.value.copy(
                    roadTripBusy = false,
                    selectedRoadTrip = if (sel?.id == id) sel.copy(name = updated.name) else sel,
                    roadTrips = _state.value.roadTrips.map { if (it.id == id) it.copy(name = updated.name) else it },
                )
                afterRoadTripChange(vin)
            } catch (e: Exception) {
                _state.value = _state.value.copy(roadTripBusy = false, roadTripsError = friendly(e))
            }
        }
    }

    /**
     * Permanently deletes a road trip (and its timeline). On success it leaves the
     * detail screen (back to the tray on Home), drops it from the in-memory list,
     * and refreshes telemetry if it was the open trip.
     */
    fun deleteRoadTrip(id: String) {
        val vin = _state.value.roadTripsVin ?: _state.value.selectedVehicle?.vin ?: return
        if (_state.value.roadTripBusy) return
        _state.value = _state.value.copy(roadTripBusy = true, roadTripsError = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { api.deleteRoadTrip(vin, id) }
                val fromHistory = _state.value.roadTripDetailFromHistory && _state.value.screen == Screen.RoadTripDetail
                val onDetail = _state.value.screen == Screen.RoadTripDetail
                _state.value = _state.value.copy(
                    roadTripBusy = false,
                    screen = when {
                        fromHistory -> Screen.RoadTripHistory
                        onDetail -> Screen.CarSelect
                        else -> _state.value.screen
                    },
                    roadTripTrayOpen = onDetail && !fromHistory,
                    roadTripDetailFromHistory = false,
                    selectedRoadTrip = if (_state.value.selectedRoadTrip?.id == id) null else _state.value.selectedRoadTrip,
                    roadTrips = _state.value.roadTrips.filterNot { it.id == id },
                    // Drop it from the history map too so the history list updates.
                    roadTripHistory = _state.value.roadTripHistory.mapValues { (_, v) -> v.filterNot { it.id == id } },
                )
                afterRoadTripChange(vin)
            } catch (e: Exception) {
                _state.value = _state.value.copy(roadTripBusy = false, roadTripsError = friendly(e))
            }
        }
    }

    /** Loads app-wide road-trip automation settings for the Settings screen. */
    fun loadRoadTripSettings() {
        _state.value = _state.value.copy(roadTripSettingsLoading = true)
        viewModelScope.launch {
            try {
                val s = withContext(Dispatchers.IO) { api.getRoadTripSettings() }
                _state.value = _state.value.copy(roadTripSettingsLoading = false, roadTripSettings = s)
            } catch (e: Exception) {
                _state.value = _state.value.copy(roadTripSettingsLoading = false)
            }
        }
    }

    /**
     * Persists road-trip automation settings (auto-start + idle/max + end-on-stop),
     * clamped. Optimistically updates state, then reconciles with the server's echo.
     */
    fun setRoadTripSettings(autoStart: Boolean, idleHours: Int, maxDays: Int, endOnStop: Boolean) {
        val optimistic = RoadTripSettings(autoStart, idleHours.coerceIn(2, 12), maxDays.coerceIn(1, 7), endOnStop)
        _state.value = _state.value.copy(roadTripSettings = optimistic)
        viewModelScope.launch {
            try {
                val applied = withContext(Dispatchers.IO) {
                    api.setRoadTripSettings(optimistic.autoStart, optimistic.idleHours, optimistic.maxDays, optimistic.endOnStop)
                }
                _state.value = _state.value.copy(roadTripSettings = applied)
            } catch (e: Exception) {
                // Leave the optimistic value; a later loadRoadTripSettings() reconciles.
            }
        }
    }

    /**
     * Opens the per-vehicle notification review screen for [vin], loading its
     * latest captured notifications from the local store (newest first). The
     * carousel bell only routes here for VINs with capture enabled.
     */
    fun showNotificationHistory(vin: String) {
        if (vin.isBlank()) return
        // Opening the history is what "reads" these alerts — advance the marker and
        // recompute so this car's carousel unread dot clears immediately.
        notificationStore.markRead(vin)
        _state.value = _state.value.copy(
            screen = Screen.NotificationHistory,
            notificationHistoryVin = vin,
            notificationHistory = notificationStore.forVin(vin),
            notificationUnreadVins = notificationStore.unreadVins(),
        )
    }

    /**
     * Recomputes the per-vehicle unread set from the local store. Cheap; called
     * on ON_RESUME and after a foreground push so the carousel dots stay accurate
     * regardless of which screen is showing.
     */
    fun refreshUnreadBadges() {
        val unread = notificationStore.unreadVins()
        if (unread != _state.value.notificationUnreadVins) {
            _state.value = _state.value.copy(notificationUnreadVins = unread)
        }
    }

    /**
     * Re-reads the reviewed vehicle's captured notifications from the store —
     * e.g. on ON_RESUME — so pushes that arrived while the review screen was
     * open (the FCM service writes from a separate process context) show up.
     */
    fun refreshNotificationHistory() {
        if (_state.value.screen != Screen.NotificationHistory) return
        val vin = _state.value.notificationHistoryVin ?: return
        _state.value = _state.value.copy(notificationHistory = notificationStore.forVin(vin))
    }

    /**
     * Opens the combined, cross-vehicle history screen, loading every tracked
     * vehicle's captured notifications (newest first) from the local store. No
     * new data is fetched or saved — it reuses what the per-vehicle review
     * already persists.
     */
    fun showCombinedHistory() {
        _state.value = _state.value.copy(
            screen = Screen.CombinedHistory,
            combinedHistory = notificationStore.allRecent(),
            combinedHistoryFilterVin = null,
        )
    }

    /** Tracks the combined-history pager's current vehicle filter (null = all). */
    fun setCombinedHistoryFilter(vin: String?) {
        if (_state.value.combinedHistoryFilterVin != vin) {
            _state.value = _state.value.copy(combinedHistoryFilterVin = vin)
        }
    }

    /**
     * Persists the user's chosen carousel/notification card color for [slug] and
     * updates state so every card keyed to that slug recolors immediately.
     */
    fun setCardColor(slug: String, color: Int) {
        val key = slug.trim().lowercase()
        if (key.isBlank()) return
        cardColorStore.setColor(key, color)
        _state.value = _state.value.copy(slugCardColors = _state.value.slugCardColors + (key to color))
    }

    /**
     * Re-reads the combined history from the store — e.g. on ON_RESUME — so
     * pushes that arrived while the screen was open (the FCM service writes from
     * a separate process context) show up.
     */
    fun refreshCombinedHistory() {
        if (_state.value.screen != Screen.CombinedHistory) return
        _state.value = _state.value.copy(combinedHistory = notificationStore.allRecent())
    }

    /**
     * Opens the road-trip HISTORY screen — a swipe-to-filter pager over the fleet,
     * modeled on the notification history screen. Resets the filter to "all cars"
     * and pulls every vehicle's trips (server-authoritative) from the backend.
     */
    fun showRoadTripHistory() {
        _state.value = _state.value.copy(
            screen = Screen.RoadTripHistory,
            roadTripHistoryFilterVin = null,
            roadTripHistoryError = null,
        )
        loadAllRoadTripHistory()
    }

    /** Pulls each vehicle's road-trip list (newest first) into the history map. */
    fun loadAllRoadTripHistory() {
        val vins = _state.value.vehicles.map { it.vin }
        if (vins.isEmpty()) {
            _state.value = _state.value.copy(roadTripHistory = emptyMap(), roadTripHistoryLoading = false)
            return
        }
        _state.value = _state.value.copy(roadTripHistoryLoading = true, roadTripHistoryError = null)
        viewModelScope.launch {
            try {
                val byVin = withContext(Dispatchers.IO) {
                    vins.associateWith { vin -> runCatching { api.listRoadTrips(vin) }.getOrDefault(emptyList()) }
                }
                if (_state.value.screen == Screen.RoadTripHistory) {
                    _state.value = _state.value.copy(roadTripHistoryLoading = false, roadTripHistory = byVin)
                }
            } catch (e: Exception) {
                if (_state.value.screen == Screen.RoadTripHistory) {
                    _state.value = _state.value.copy(roadTripHistoryLoading = false, roadTripHistoryError = friendly(e))
                }
            }
        }
    }

    /** Tracks the road-trip history pager's current vehicle filter (null = all). */
    fun setRoadTripHistoryFilter(vin: String?) {
        if (_state.value.roadTripHistoryFilterVin != vin) {
            _state.value = _state.value.copy(roadTripHistoryFilterVin = vin)
        }
    }

    /**
     * Opens a trip's detail screen from the history pager. Unlike [openRoadTrip]
     * (home tray), this pins the trip's own VIN and flags the origin so Back and
     * Delete return to the history screen.
     */
    fun openRoadTripFromHistory(vin: String, id: String) {
        val summary = _state.value.roadTripHistory[vin]?.firstOrNull { it.id == id }
        _state.value = _state.value.copy(
            screen = Screen.RoadTripDetail,
            roadTripsVin = vin,
            roadTripDetailFromHistory = true,
            roadTripTrayOpen = false,
            selectedRoadTrip = summary,
            roadTripDetailLoading = true,
        )
        viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) { api.getRoadTrip(vin, id) }
                if (_state.value.selectedRoadTrip?.id == id || _state.value.screen == Screen.RoadTripDetail) {
                    _state.value = _state.value.copy(roadTripDetailLoading = false, selectedRoadTrip = full)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(roadTripDetailLoading = false, roadTripsError = friendly(e))
            }
        }
    }

    /**
     * Exports the road-trip history currently on screen (the filtered car, or all
     * cars merged) to CSV — fetching each trip's full event timeline first — then
     * opens the share sheet. Network-bound, so it flips [roadTripHistoryExporting]
     * for the duration. [onShare] receives the fully-populated trips on the main
     * thread so the caller can hand them to [RoadTripCsvExport] with a Context.
     */
    fun exportRoadTripHistory(onShare: (List<RoadTrip>) -> Unit) {
        if (_state.value.roadTripHistoryExporting) return
        val trips = _state.value.roadTripHistoryFor(_state.value.roadTripHistoryFilterVin)
        if (trips.isEmpty()) return
        _state.value = _state.value.copy(roadTripHistoryExporting = true, roadTripHistoryError = null)
        viewModelScope.launch {
            try {
                val full = withContext(Dispatchers.IO) {
                    trips.map { t -> runCatching { api.getRoadTrip(t.vin, t.id) }.getOrDefault(t) }
                }
                _state.value = _state.value.copy(roadTripHistoryExporting = false)
                onShare(full)
            } catch (e: Exception) {
                _state.value = _state.value.copy(roadTripHistoryExporting = false, roadTripHistoryError = friendly(e))
            }
        }
    }

    /**
     * Turns local, review-only capture on/off for a SINGLE vehicle. Per the
     * requirement, flipping a car's toggle EITHER way wipes that car's history;
     * other vehicles are untouched. The carousel bell for [vin] appears/
     * disappears immediately.
     */
    fun setNotificationTracking(vin: String, enabled: Boolean) {
        if (vin.isBlank()) return
        notificationStore.setTrackingEnabled(vin, enabled)
        val vins = _state.value.notificationTrackingVins.toMutableSet().apply {
            if (enabled) add(vin) else remove(vin)
        }
        _state.value = _state.value.copy(
            notificationTrackingVins = vins,
            notificationUnreadVins = notificationStore.unreadVins(),
            notificationHistory =
                if (_state.value.notificationHistoryVin == vin) emptyList()
                else _state.value.notificationHistory,
        )
    }

    /**
     * Sets the device-wide retention window (days) for captured notifications
     * and prunes anything now outside it. If the review screen is open, its list
     * is refreshed so entries that just aged out disappear immediately.
     */
    fun setNotificationTrackingDays(days: Int) {
        notificationStore.setTrackingDays(days)
        val applied = notificationStore.getTrackingDays()
        val vin = _state.value.notificationHistoryVin
        _state.value = _state.value.copy(
            notificationTrackingDays = applied,
            notificationUnreadVins = notificationStore.unreadVins(),
            notificationHistory =
                if (_state.value.screen == Screen.NotificationHistory && vin != null)
                    notificationStore.forVin(vin)
                else _state.value.notificationHistory,
        )
    }

    /**
     * Loads the per-slug Ford account status for the signed-in user. Account-
     * level, so it does not depend on the selected vehicle. Backs the Ford
     * Re-Authorization section on the notification-setup screen.
     */
    fun loadFordAccounts() {
        _state.value = _state.value.copy(fordLoading = true, fordError = null)
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { api.getFordAccountStatuses() }
                _state.value = _state.value.copy(fordLoading = false, fordAccounts = rows)
            } catch (e: Exception) {
                _state.value = _state.value.copy(fordLoading = false, fordError = friendly(e))
            }
        }
    }

    /**
     * Starts the Ford OAuth handshake for [appSlug] and surfaces the authorize
     * URL into state so the screen can open it in a browser. On return the
     * server-side callback flips the account to ACTIVE; the next
     * [loadFordAccounts] picks up the change.
     */
    fun startFordReauth(appSlug: String) {
        _state.value = _state.value.copy(fordReauthingSlug = appSlug, fordError = null)
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) { api.startFordOAuth(appSlug) }
                _state.value = _state.value.copy(fordReauthingSlug = null, fordReauthUrl = url)
            } catch (e: Exception) {
                _state.value = _state.value.copy(fordReauthingSlug = null, fordError = friendly(e))
            }
        }
    }

    /** Clears the pending OAuth URL after the screen launches the browser. */
    fun consumeFordReauthUrl() {
        if (_state.value.fordReauthUrl != null) {
            _state.value = _state.value.copy(fordReauthUrl = null)
        }
    }

    /**
     * Opens the full Vehicle Status screen for the currently-selected car. It
     * reads the already-loaded [GoHenryUiState.telemetry] snapshot, so no extra
     * network call is needed (the carousel has already fetched it).
     */
    fun showVehicleStatus() {
        if (_state.value.selectedVehicle == null) return
        _state.value = _state.value.copy(screen = Screen.VehicleStatus)
    }

    private fun loadTelemetry(index: Int, force: Boolean = false, silent: Boolean = false) {
        val vin = _state.value.vehicles.getOrNull(index)?.vin ?: return
        val cached = telemetryCache[vin]
        if (cached != null && !force) {
            // Cache hit — show it without a network round-trip or spinner.
            if (_state.value.selectedVehicle?.vin == vin) {
                _state.value = _state.value.copy(telemetryLoading = false, telemetry = cached)
            }
            return
        }
        // A silent refresh (carousel landing / push) keeps the current value on
        // screen and shows no spinner; only a cold/explicit load flips loading on.
        if (!(silent && cached != null)) {
            _state.value = _state.value.copy(telemetryLoading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val t = withContext(Dispatchers.IO) { api.getTelemetry(vin) }
                telemetryCache[vin] = t
                // Ignore if the user paged away while this was in flight.
                if (_state.value.selectedVehicle?.vin == vin) {
                    _state.value = _state.value.copy(telemetryLoading = false, telemetry = t, lastRefreshedAt = System.currentTimeMillis())
                }
            } catch (e: Exception) {
                // Only surface the error if we're still on the card that failed —
                // and never for a silent background refresh, which must not pop an
                // error banner over an otherwise-good cached card.
                if (!silent && _state.value.selectedVehicle?.vin == vin) {
                    _state.value = _state.value.copy(telemetryLoading = false, error = friendly(e))
                } else if (silent && _state.value.selectedVehicle?.vin == vin) {
                    _state.value = _state.value.copy(telemetryLoading = false)
                }
            }
        }
    }

    private fun loadCachedFields(vin: String) {
        viewModelScope.launch {
            try {
                val fields = withContext(Dispatchers.IO) { api.getCachedTelemetryFields(vin) }
                cachedFieldsCache[vin] = fields
                _state.value = _state.value.copy(cachedFieldsLoading = false, cachedFields = fields, cachedFieldsError = null)
            } catch (e: Exception) {
                // Scope failures to the detail screen so they never leak onto the carousel.
                _state.value = _state.value.copy(cachedFieldsLoading = false, cachedFieldsError = friendly(e))
            }
        }
    }

    private fun loadSelectedPrefs() {
        val vin = _state.value.selectedVehicle?.vin ?: return
        _state.value = _state.value.copy(prefsLoading = true, prefsError = null)
        viewModelScope.launch {
            try {
                val prefs = withContext(Dispatchers.IO) { api.getNotifyPrefs(vin) }
                _state.value = _state.value.copy(
                    prefsLoading = false,
                    prefs = _state.value.prefs + (vin to prefs),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(prefsLoading = false, prefsError = friendly(e))
            }
        }
    }

    fun setStart(vin: String, enabled: Boolean) = updatePref(vin, start = enabled)
    fun setStop(vin: String, enabled: Boolean) = updatePref(vin, stop = enabled)
    fun setChargeInProgress(vin: String, enabled: Boolean) = updatePref(vin, chargeInProgress = enabled)
    fun setChargeComplete(vin: String, enabled: Boolean) = updatePref(vin, chargeComplete = enabled)
    fun setChargeError(vin: String, enabled: Boolean) = updatePref(vin, chargeError = enabled)
    fun setLostSignal(vin: String, enabled: Boolean) = updatePref(vin, lostSignal = enabled)
    fun setTelemetryFeedLost(vin: String, enabled: Boolean) = updatePref(vin, telemetryFeedLost = enabled)
    fun setTirePressure(vin: String, enabled: Boolean) = updatePref(vin, tirePressure = enabled)
    fun setAlarm(vin: String, enabled: Boolean) = updatePref(vin, alarm = enabled)
    fun setRoadTripStart(vin: String, enabled: Boolean) = updatePref(vin, roadTripStart = enabled)
    fun setRoadTripEnd(vin: String, enabled: Boolean) = updatePref(vin, roadTripEnd = enabled)

    private fun updatePref(
        vin: String,
        start: Boolean? = null,
        stop: Boolean? = null,
        chargeInProgress: Boolean? = null,
        chargeComplete: Boolean? = null,
        chargeError: Boolean? = null,
        lostSignal: Boolean? = null,
        telemetryFeedLost: Boolean? = null,
        tirePressure: Boolean? = null,
        alarm: Boolean? = null,
        roadTripStart: Boolean? = null,
        roadTripEnd: Boolean? = null,
    ) {
        val current = _state.value.prefs[vin] ?: NotifyPrefs(start = false, stop = false)
        val desired = NotifyPrefs(
            start = start ?: current.start,
            stop = stop ?: current.stop,
            chargeInProgress = chargeInProgress ?: current.chargeInProgress,
            chargeComplete = chargeComplete ?: current.chargeComplete,
            chargeError = chargeError ?: current.chargeError,
            lostSignal = lostSignal ?: current.lostSignal,
            telemetryFeedLost = telemetryFeedLost ?: current.telemetryFeedLost,
            tirePressure = tirePressure ?: current.tirePressure,
            alarm = alarm ?: current.alarm,
            roadTripStart = roadTripStart ?: current.roadTripStart,
            roadTripEnd = roadTripEnd ?: current.roadTripEnd,
        )
        // Optimistic update so the toggle moves immediately.
        _state.value = _state.value.copy(
            prefs = _state.value.prefs + (vin to desired),
            prefsError = null,
        )
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    api.setNotifyPrefs(
                        vin,
                        desired.start,
                        desired.stop,
                        desired.chargeInProgress,
                        desired.chargeComplete,
                        desired.chargeError,
                        desired.lostSignal,
                        desired.telemetryFeedLost,
                        desired.tirePressure,
                        desired.alarm,
                        desired.roadTripStart,
                        desired.roadTripEnd,
                    )
                }
                _state.value = _state.value.copy(prefs = _state.value.prefs + (vin to saved))
            } catch (e: Exception) {
                // Roll back to the server's last-known value on failure.
                _state.value = _state.value.copy(
                    prefs = _state.value.prefs + (vin to current),
                    prefsError = friendly(e),
                )
            }
        }
    }

    private fun friendly(e: Exception): String {
        val headline = when {
            e is GoHenryApiException && e.status == 401 -> "Not signed in (401). The backend rejected x-user-id."
            e is GoHenryApiException && e.errorCode == "needs_reauth" -> "Ford account needs re-authentication (409)."
            e is GoHenryApiException && e.status == 429 -> "Ford is rate-limiting (429). Try again shortly."
            e is GoHenryApiException && e.status == 404 -> "Vehicle not found for this user (404)."
            e is GoHenryApiException && e.status == -1 -> e.message ?: "Could not reach the backend."
            e is GoHenryApiException -> e.message ?: "Backend error (${e.status})."
            else -> e.message ?: "Something went wrong."
        }
        // Always append what the app is actually using, so a misconfigured
        // local.properties (wrong base URL / missing user id or function key)
        // is diagnosable straight from the screen. The function key is masked.
        val request = (e as? GoHenryApiException)?.requestUrl?.let { "\n\nRequest: $it" } ?: ""
        return "$headline$request\n\nUsing:\n${api.configSummary()}"
    }
}
