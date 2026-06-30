using GoHenry.Api.Notifications;
using GoHenry.Core.Geo;
using GoHenry.Core.Models;
using GoHenry.Core.Normalization;
using GoHenry.FordClient;
using GoHenry.Storage;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Logging;

namespace GoHenry.Api.Functions;

/// <summary>
/// Two-stage poller. The timer <b>dispatcher</b> fans each registered VIN onto a
/// storage queue; the queue <b>worker</b> mints/uses a cached Ford access token,
/// fetches + normalizes the vehicle status, detects trip/charge/lost-signal
/// transitions, persists the snapshot + activity state to the VIN's Table row, and
/// fires FCM for any enabled event. All state is in Table Storage — no SQL.
/// </summary>
public sealed class PollerFunctions(
    IGoHenryStore store,
    FordTokenService tokens,
    IFordApiClientFactory fordFactory,
    INotificationPublisher publisher,
    ILogger<PollerFunctions> log)
{
    public const string Queue = "gohenry-poll";

    /// <summary>Wire format for a queued poll request.</summary>
    public sealed record PollMessage(string UserId, string Vin, string Slug);

    [Function("Poll_Dispatcher")]
    [QueueOutput(Queue, Connection = "AzureWebJobsStorage")]
    public async Task<IReadOnlyList<string>> Dispatch(
        [TimerTrigger("0 */1 * * * *")] TimerInfo timer, CancellationToken ct)
    {
        // The timer ticks every minute (the minimum cadence) but we only fan out a
        // poll once the user-configured cadence has elapsed. The cadence + last-run
        // stamp live on a single Table row, so changing it from the app takes effect
        // immediately with no redeploy — and there is still no SQL.
        var meta = await store.GetOrCreateMetaAsync("pollSettings", ct);
        var cadence = PollSettingsDto.Clamp(meta.PollCadenceMinutes <= 0 ? PollSettingsDto.DefaultMinutes : meta.PollCadenceMinutes);
        var now = DateTimeOffset.UtcNow;

        // Subtract a 30s skew so e.g. a 2-min cadence reliably fires on the 2nd tick
        // rather than slipping to every 3rd because ticks land a few ms early/late.
        if (meta.LastDispatchedAt is { } last && now - last < TimeSpan.FromMinutes(cadence) - TimeSpan.FromSeconds(30))
        {
            log.LogInformation("Poll skipped: {Elapsed:n0}s of {Cadence}m cadence elapsed", (now - last).TotalSeconds, cadence);
            return Array.Empty<string>();
        }

        var vehicles = await store.ListAllVehiclesAsync(ct);
        var messages = vehicles
            .Where(v => !v.TrackingPaused)
            .Select(v => System.Text.Json.JsonSerializer.Serialize(new PollMessage(v.PartitionKey, v.RowKey, v.FordSlug)))
            .ToList();

        meta.LastDispatchedAt = now;
        meta.RegistryRefreshedAt = now;
        await store.SaveMetaAsync(meta, ct);

        log.LogInformation("Dispatched {Count} VIN poll(s) (cadence {Cadence}m)", messages.Count, cadence);
        return messages;
    }

    [Function("Poll_Worker")]
    public async Task Work(
        [QueueTrigger(Queue, Connection = "AzureWebJobsStorage")] string message, CancellationToken ct)
    {
        var msg = System.Text.Json.JsonSerializer.Deserialize<PollMessage>(message);
        if (msg is null) { log.LogWarning("Unparseable poll message"); return; }

        var entity = await store.GetVehicleAsync(msg.UserId, msg.Vin, ct);
        if (entity is null) { log.LogWarning("Poll for unknown VIN {Vin}", msg.Vin); return; }

        string accessToken;
        try
        {
            accessToken = await tokens.GetAccessTokenAsync(msg.UserId, msg.Slug, ct);
        }
        catch (FordReauthRequiredException)
        {
            log.LogInformation("Skipping {Vin}: Ford account needs re-auth", msg.Vin);
            await HandlePollFailureAsync(entity, ct);
            return;
        }

        string json;
        try { json = await fordFactory.Create(msg.Slug).GetVehicleStatusJsonAsync(accessToken, msg.Vin, ct); }
        catch (FordApiException ex)
        {
            log.LogWarning(ex, "Ford status failed for {Vin}", msg.Vin);
            await HandlePollFailureAsync(entity, ct);
            return;
        }

        var now = DateTimeOffset.UtcNow;
        var engine = EngineTypes.Parse(entity.EngineType);
        var snap = FordTelemetryNormalizer.Normalize(msg.Vin, engine, json, now);

        // Curated full datafeed (resolves indexed tire/door arrays). Extracted up
        // front so the detector can see per-wheel tire status when deciding whether
        // to raise a tire pressure warning.
        var rawFields = FordRawFieldExtractor.Extract(json);
        var rawByName = rawFields.ToDictionary(f => f.Name, f => f.Value, StringComparer.OrdinalIgnoreCase);
        snap.TireFrontLeftStatus = rawByName.GetValueOrDefault("TireFrontLeftStatus");
        snap.TireFrontRightStatus = rawByName.GetValueOrDefault("TireFrontRightStatus");
        snap.TireRearLeftStatus = rawByName.GetValueOrDefault("TireRearLeftStatus");
        snap.TireRearRightStatus = rawByName.GetValueOrDefault("TireRearRightStatus");
        // Charge-display status (Ford xevBatteryChargeDisplayStatus) drives the charge
        // notifications via its phase transitions; pull it from the curated raw fields.
        snap.ChargeDisplayStatus = rawByName.GetValueOrDefault("SoCChargeDisplayStatus");

        // signal.lost (frozen-feed) inputs: compare this read's odometer + Ford
        // telemetry timestamp against the previous successful read. Captured BEFORE
        // ApplySnapshot overwrites the entity's Snap* columns.
        var priorOdometerKm = entity.SnapOdometerKm;
        var priorTelemetryTs = entity.SnapFordTelemetryTimestamp;
        var newTelemetryTs = rawByName.GetValueOrDefault("FordTelemtryTimeStamp");

        var prior = new PriorActivityState
        {
            WasActive = entity.LastWasActive,
            PriorChargePhase = ParseChargePhase(entity.ChargePhase),
            HasOpenTrip = entity.HasOpenTrip,
            LastSeenActiveUtc = entity.LastSeenActiveUtc,
            LostSignalAlreadyRaised = entity.LostSignalRaised,
            TireWarnAlreadyRaised = entity.TireWarnRaised,
            AlarmAlreadyRaised = entity.AlarmRaised,
        };
        var result = ActivityDetector.Evaluate(prior, snap, now);

        // --- Persist snapshot + activity state on the one Table row ---
        ApplySnapshot(entity, snap);
        // Additive: persist the FULL curated Ford datafeed for the Raw Data screen.
        // The 16 Snap* writes above are unchanged; this is a separate JSON column.
        entity.RawFieldsJson = System.Text.Json.JsonSerializer.Serialize(rawFields);
        entity.SnapFordTelemetryTimestamp = newTelemetryTs;
        entity.LastWasActive = result.Next.WasActive;
        entity.ChargePhase = result.Next.ChargePhase.ToString();
        entity.HasOpenTrip = result.Next.HasOpenTrip;
        entity.LastSeenActiveUtc = result.Next.LastSeenActiveUtc;
        // A successful read means the Ford telemetry feed is reachable right now, so
        // clear any prior "telemetry feed lost" state (re-arms the failure-path check).
        // The separate signal.lost (frozen-feed) state is governed below, since a
        // reachable-but-frozen feed is still a lost signal.
        entity.TelemetryFeedLostRaised = false;
        entity.TireWarnRaised = result.Next.TireWarnAlreadyRaised;
        entity.AlarmRaised = result.Next.AlarmAlreadyRaised;

        // Phase-2 road-trip automation settings live on the same shared meta row the
        // dispatcher reads — no extra table, no SQL.
        var meta = await store.GetOrCreateMetaAsync("pollSettings", ct);

        // --- signal.lost: frozen-feed detection on a SUCCESSFUL read ---
        // The Ford feed is reachable, but if the ignition is ON and neither the
        // odometer nor the Ford telemetry timestamp has advanced for LostSignalPolls
        // reads in a row, the car's own data is stuck — treat it as a lost signal.
        // Uses the SAME LostSignalPolls setting as telemetryfeed.lost.
        var lostSignalPolls = PollSettingsDto.ClampLostSignalPolls(
            meta.LostSignalPolls <= 0 ? PollSettingsDto.DefaultLostSignalPolls : meta.LostSignalPolls);
        var feedFrozen = ActivityDetector.IsFeedFrozen(
            snap.IsActive, priorOdometerKm, snap.OdometerKm, priorTelemetryTs, newTelemetryTs);
        var raiseSignalLost = false;
        if (feedFrozen)
        {
            entity.FrozenFeedPollCount++;
            if (entity.FrozenFeedPollCount >= lostSignalPolls && !entity.LostSignalRaised)
            {
                entity.LostSignalRaised = true;
                raiseSignalLost = true;
            }
        }
        else
        {
            // Data advanced (or ignition off) — the feed is live; clear + re-arm.
            entity.FrozenFeedPollCount = 0;
            entity.LostSignalRaised = false;
        }

        // Auto-start: when enabled, a fresh trip.started with no open road trip opens
        // one so a whole driving session is captured hands-free. Must run BEFORE the
        // VIN upsert + AppendToRoadTrip so the just-opened trip captures this event.
        if (meta.RoadTripAutoStart &&
            string.IsNullOrEmpty(entity.ActiveRoadTripId) &&
            result.Events.Contains(NotificationEvents.TripStarted))
        {
            await AutoStartRoadTrip(entity, snap, now, ct);
        }

        // Capture the trip's start position the moment ignition turns on, so the
        // eventual trip.ended push can report the straight-line distance travelled
        // (computed server-side — independent of the phone's local history). Stored on
        // the VIN row below; consumed by BuildMessage on the later trip.ended poll.
        if (result.Events.Contains(NotificationEvents.TripStarted))
        {
            entity.TripStartLatitude = snap.Latitude;
            entity.TripStartLongitude = snap.Longitude;
        }

        // Keep the idle-timer fresh while a trip is live and something happened.
        if (!string.IsNullOrEmpty(entity.ActiveRoadTripId) && result.Events.Count > 0)
            entity.ActiveRoadTripLastEventAt = now;

        await store.UpsertVehicleAsync(entity, ct);

        // --- History + push for each detected event ---
        foreach (var ev in result.Events)
        {
            await MaybeAppendHistory(entity, snap, ev, now, ct);
            if (IsEnabled(entity, ev))
            {
                // A "stop" while a road trip is still open (and not auto-ended on stop)
                // becomes a call-to-action: tell the user the trip is still running and
                // deep-link them into the road-trip tray so they can end it.
                var promptEndTrip = ev == NotificationEvents.TripEnded
                    && !string.IsNullOrEmpty(entity.ActiveRoadTripId)
                    && !meta.RoadTripEndOnStop;
                await publisher.SendAsync(BuildMessage(entity, snap, ev, now, promptEndTrip), ct);
            }
        }

        // signal.lost (frozen feed) is detected on a successful read, so it lives
        // outside the result.Events loop. Fire once when newly raised and enabled.
        if (raiseSignalLost && entity.NotifyLostSignal)
        {
            log.LogInformation("{Vin}: signal lost (feed frozen for >= {Polls} polls, ignition ON)", entity.RowKey, lostSignalPolls);
            await publisher.SendAsync(BuildMessage(entity, snap, NotificationEvents.SignalLost, now), ct);
        }

        // --- Road trip association: stamp each event onto the open trip (if any) ---
        // The VIN row carries the active trip's id + start, so this needs no query
        // to discover the trip — just a direct read-modify-write of its row.
        if (!string.IsNullOrEmpty(entity.ActiveRoadTripId) &&
            entity.ActiveRoadTripStartedAt is { } tripStart && result.Events.Count > 0)
        {
            await AppendToRoadTrip(entity, tripStart, snap, result.Events, now, ct);
        }

        // --- Auto-end on stop: when enabled, a detected trip.ended (the car's
        // ignition-off) closes any open road trip immediately (EndReason="stop"),
        // rather than waiting for the idle timer. Runs after AppendToRoadTrip so the
        // stop event is already stamped on the trip's timeline. ---
        if (meta.RoadTripEndOnStop &&
            !string.IsNullOrEmpty(entity.ActiveRoadTripId) &&
            result.Events.Contains(NotificationEvents.TripEnded))
        {
            await CloseRoadTrip(entity, snap, now, "stop", ct);
        }

        // --- Auto-close safety net: end a forgotten open trip that has gone idle or
        // run past the hard age cap. Runs every tick (even with no events) so the
        // idle timer reliably fires. ---
        if (!string.IsNullOrEmpty(entity.ActiveRoadTripId))
            await MaybeAutoCloseRoadTrip(entity, meta, snap, now, ct);

        if (result.Events.Count > 0)
            log.LogInformation("{Vin}: {Events}", msg.Vin, string.Join(",", result.Events));
    }

    private static void ApplySnapshot(VinEntity e, VehicleSnapshot s)
    {
        e.SnapSocPct = s.SocPct;
        e.SnapFuelLevelPct = s.FuelLevelPct;
        e.SnapRangeKm = s.RangeKm;
        e.SnapOdometerKm = s.OdometerKm;
        e.SnapChargingStatus = s.ChargingStatus;
        e.SnapPluggedIn = s.PluggedIn;
        e.SnapLatitude = s.Latitude;
        e.SnapLongitude = s.Longitude;
        e.SnapIgnition = s.Ignition;
        e.SnapGearLever = s.GearLever;
        e.SnapDoorLocks = s.DoorLocks;
        e.SnapAlarmStatus = s.AlarmStatus;
        e.SnapTirePressureStatus = s.TirePressureStatus;
        e.SnapOilLifePct = s.OilLifePct;
        e.SnapOutsideTempC = s.OutsideTempC;
        e.SnapInteriorTempC = s.InteriorTempC;
        e.CapturedAt = s.CapturedAt;
        e.LastPolledAt = s.CapturedAt;
    }

    /// <summary>
    /// Parses the persisted charge-phase string back into the enum, tolerating
    /// null/empty/unknown values (treated as <see cref="ChargePhase.Other"/>).
    /// </summary>
    private static ChargePhase ParseChargePhase(string? raw) =>
        Enum.TryParse<ChargePhase>(raw, ignoreCase: true, out var p) ? p : ChargePhase.Other;

    /// <summary>
    /// Runs after a poll that produced no fresh telemetry (Ford API error or a
    /// Ford account needing re-auth). Because <see cref="VinEntity.CapturedAt"/> is
    /// only advanced on success, a long-enough gap means the Ford telemetry feed is
    /// unreachable: raise it exactly once (driving the hero-card "Telemetry" label via
    /// <see cref="VinEntity.TelemetryFeedLostRaised"/>) and push a single notification
    /// if the user enabled "Telemetry feed lost". Re-arms on the next successful read.
    /// </summary>
    private async Task HandlePollFailureAsync(VinEntity entity, CancellationToken ct)
    {
        var now = DateTimeOffset.UtcNow;
        if (entity.TelemetryFeedLostRaised) return; // already flagged — don't re-notify

        // Threshold scales with the user's settings: N missed polls × the cadence.
        var meta = await store.GetOrCreateMetaAsync("pollSettings", ct);
        var cadence = PollSettingsDto.Clamp(meta.PollCadenceMinutes <= 0 ? PollSettingsDto.DefaultMinutes : meta.PollCadenceMinutes);
        var polls = PollSettingsDto.ClampLostSignalPolls(meta.LostSignalPolls <= 0 ? PollSettingsDto.DefaultLostSignalPolls : meta.LostSignalPolls);
        var threshold = TimeSpan.FromMinutes((double)cadence * polls);
        if (!ActivityDetector.IsSignalStale(entity.CapturedAt, now, threshold)) return;

        entity.TelemetryFeedLostRaised = true;
        await store.UpsertVehicleAsync(entity, ct);

        if (entity.NotifyTelemetryFeedLost)
        {
            // No fresh snapshot on a failed poll — surface the last-known position
            // so the alert still carries where the car was when it went quiet.
            var lastKnown = new VehicleSnapshot
            {
                Vin = entity.RowKey,
                Latitude = entity.SnapLatitude,
                Longitude = entity.SnapLongitude,
                OutsideTempC = entity.SnapOutsideTempC,
            };
            await publisher.SendAsync(BuildMessage(entity, lastKnown, NotificationEvents.TelemetryFeedLost, now), ct);
        }

        log.LogInformation("{Vin}: telemetry feed lost (no read for >= {Polls} polls / {Mins}m)", entity.RowKey, polls, (int)threshold.TotalMinutes);
    }

    private async Task MaybeAppendHistory(VinEntity e, VehicleSnapshot s, string ev, DateTimeOffset now, CancellationToken ct)
    {
        switch (ev)
        {
            case NotificationEvents.TripEnded:
                await store.AppendTripAsync(new TripHistoryEntity
                {
                    PartitionKey = e.RowKey,
                    RowKey = TableGoHenryStore.ReverseTicksRowKey(now),
                    StartedAt = e.LastSeenActiveUtc ?? now,
                    EndedAt = now,
                    EndOdometerKm = s.OdometerKm,
                    EndLatitude = s.Latitude,
                    EndLongitude = s.Longitude,
                }, ct);
                break;
            case NotificationEvents.ChargeComplete:
            case NotificationEvents.ChargeError:
                await store.AppendChargeAsync(new ChargeHistoryEntity
                {
                    PartitionKey = e.RowKey,
                    RowKey = TableGoHenryStore.ReverseTicksRowKey(now),
                    StartedAt = now,
                    EndedAt = now,
                    EndSocPct = s.SocPct,
                    Outcome = ev == NotificationEvents.ChargeError ? "error" : "complete",
                }, ct);
                break;
        }
    }

    private static bool IsEnabled(VinEntity e, string ev) => ev switch
    {
        NotificationEvents.TripStarted => e.NotifyStart,
        NotificationEvents.TripEnded => e.NotifyStop,
        NotificationEvents.ChargeInProgress => e.NotifyChargeInProgress,
        NotificationEvents.ChargeComplete => e.NotifyChargeComplete,
        NotificationEvents.ChargeError => e.NotifyChargeError,
        NotificationEvents.SignalLost => e.NotifyLostSignal,
        NotificationEvents.TelemetryFeedLost => e.NotifyTelemetryFeedLost,
        NotificationEvents.TirePressureWarn => e.NotifyTirePressure,
        NotificationEvents.AlarmTriggered => e.NotifyAlarm,
        _ => false,
    };

    private static NotificationMessage BuildMessage(VinEntity e, VehicleSnapshot s, string ev, DateTimeOffset now, bool roadTripActive = false)
    {
        var nickname = e.Nickname ?? e.Model ?? e.RowKey;
        // The "detail" of the trigger is sent verbatim in the data payload so the
        // app can render a consistent body of: nickname • detail • local-time • lat,long.
        var (title, detail) = ev switch
        {
            NotificationEvents.TripStarted => ("Start — Ignition on", EventDetail(ev, s)),
            NotificationEvents.TripEnded => roadTripActive
                ? ("Stop — Active road trip", "Active Roadtrip - end it?")
                : ("Stop — Ignition off", EventDetail(ev, s)),
            NotificationEvents.ChargeInProgress => ("Charge in progress", EventDetail(ev, s)),
            NotificationEvents.ChargeComplete => ("Charge complete", EventDetail(ev, s)),
            NotificationEvents.ChargeError => ("Charge error", EventDetail(ev, s)),
            NotificationEvents.SignalLost => ("Car lost signal", EventDetail(ev, s)),
            NotificationEvents.TelemetryFeedLost => ("Telemetry feed lost", EventDetail(ev, s)),
            NotificationEvents.TirePressureWarn => ("Tire pressure warning", EventDetail(ev, s)),
            NotificationEvents.AlarmTriggered => ("Alarm triggered", EventDetail(ev, s)),
            _ => ("GoHenry", nickname),
        };
        var body = $"{nickname} • {detail}";
        var data = new Dictionary<string, string>
        {
            ["event"] = ev,
            ["vin"] = e.RowKey,
            ["nickname"] = nickname,
            ["detail"] = detail,
            ["timestampUtc"] = now.ToString("o"),
        };
        // On an end-trip alert, attach how far the car travelled since its last
        // start-trip: the great-circle gap from the start position captured
        // server-side on the ignition-on poll. Computed here (not on the phone) so it
        // is correct even if the device missed the start push or reinstalled the app.
        // Omitted when no start position is known, so a stop with no matching start
        // carries no distance.
        if (ev == NotificationEvents.TripEnded &&
            GeoMath.TripDistanceKm(e.TripStartLatitude, e.TripStartLongitude, s.Latitude, s.Longitude) is { } tripKm)
        {
            data["tripDistanceKm"] = tripKm.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
            body += $" • Trip {tripKm.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture)} km";
        }
        if (s.Latitude is { } lat) data["latitude"] = lat.ToString(System.Globalization.CultureInfo.InvariantCulture);
        if (s.Longitude is { } lon) data["longitude"] = lon.ToString(System.Globalization.CultureInfo.InvariantCulture);
        if (s.AltitudeM is { } alt) data["altitude"] = alt.ToString(System.Globalization.CultureInfo.InvariantCulture);
        if (s.OutsideTempC is { } otc) data["outsideTempC"] = otc.ToString(System.Globalization.CultureInfo.InvariantCulture);
        // Flag a still-open trip so the app can deep-link the stop alert into the
        // road-trip tray (tap → "end it?").
        if (roadTripActive)
        {
            data["roadTripActive"] = "true";
            if (!string.IsNullOrEmpty(e.ActiveRoadTripId)) data["roadTripId"] = e.ActiveRoadTripId;
        }

        return new NotificationMessage
        {
            UserId = e.PartitionKey, Vin = e.RowKey, Event = ev, Title = title, Body = body, Data = data,
        };
    }

    /// <summary>Human-readable detail text for an event, shared by FCM and the road-trip timeline.</summary>
    private static string EventDetail(string ev, VehicleSnapshot s) => ev switch
    {
        NotificationEvents.TripStarted => "Started moving",
        NotificationEvents.TripEnded => "Parked",
        NotificationEvents.ChargeInProgress => "Charging",
        NotificationEvents.ChargeComplete => "Charge complete",
        NotificationEvents.ChargeError => "Charging problem",
        NotificationEvents.SignalLost => "Stopped reporting movement",
        NotificationEvents.TelemetryFeedLost => "No telemetry from the car",
        NotificationEvents.TirePressureWarn =>
            $"Tire warning: {(s.AbnormalTires.Count > 0 ? string.Join(", ", s.AbnormalTires) : "a tire")}",
        NotificationEvents.AlarmTriggered => "Alarm triggered",
        _ => ev,
    };

    /// <summary>
    /// Appends each detected event to the VIN's open road trip and rolls up its
    /// stats. Single read-modify-write of the trip row (addressed via the start
    /// time the VIN row already carries); caps the embedded timeline so the JSON
    /// stays under Table Storage's 64 KB property limit while EventCount keeps
    /// counting.
    /// </summary>
    private async Task AppendToRoadTrip(VinEntity e, DateTimeOffset startedAt, VehicleSnapshot s,
        IReadOnlyList<string> events, DateTimeOffset now, CancellationToken ct)
    {
        var rowKey = TableGoHenryStore.ReverseTicksRowKey(startedAt);
        var trip = await store.GetRoadTripAsync(e.RowKey, rowKey, ct);
        if (trip is null || trip.Status != "active") return;

        var timeline = StoreMappings.ParseTimeline(trip.TimelineJson);
        foreach (var ev in events)
        {
            if (ev == NotificationEvents.TripStarted) trip.SegmentCount++;
            if (ev is NotificationEvents.ChargeComplete or NotificationEvents.ChargeError) trip.ChargeStops++;
            trip.EventCount++;
            if (timeline.Count < RoadTripDto.MaxTimelineEvents)
            {
                timeline.Add(new RoadTripEventDto
                {
                    Ts = now.ToString("o"),
                    Event = ev,
                    Detail = EventDetail(ev, s),
                    Latitude = s.Latitude,
                    Longitude = s.Longitude,
                    AltitudeM = s.AltitudeM,
                    OutsideTempC = s.OutsideTempC,
                });
            }
        }
        // Rolling distance estimate from the odometer delta since the trip started.
        if (trip.StartOdometerKm is { } start && s.OdometerKm is { } cur && cur >= start)
            trip.DistanceKm = cur - start;
        trip.TimelineJson = StoreMappings.SerializeTimeline(timeline);
        await store.UpsertRoadTripAsync(trip, ct);
    }

    /// <summary>
    /// Opens a road trip automatically for a just-detected trip.started. Mirrors the
    /// manual Start path (same reverse-ticks row key + VIN pointer denormalization)
    /// but stamps StartMethod="auto" so the UI can label it. Mutates the in-memory
    /// VIN entity; the caller upserts it. Optionally fires a roadtrip.started push.
    /// </summary>
    private async Task AutoStartRoadTrip(VinEntity e, VehicleSnapshot s, DateTimeOffset now, CancellationToken ct)
    {
        var name = $"Road trip · {now.UtcDateTime:MMM d}";
        var trip = new RoadTripEntity
        {
            PartitionKey = e.RowKey,
            RowKey = TableGoHenryStore.ReverseTicksRowKey(now),
            Id = Guid.NewGuid().ToString("n"),
            UserId = e.PartitionKey,
            Name = name,
            Status = "active",
            StartedAt = now,
            StartLatitude = s.Latitude,
            StartLongitude = s.Longitude,
            StartOdometerKm = s.OdometerKm,
            StartMethod = "auto",
        };
        await store.UpsertRoadTripAsync(trip, ct);

        e.ActiveRoadTripId = trip.Id;
        e.ActiveRoadTripName = trip.Name;
        e.ActiveRoadTripStartedAt = now;
        e.ActiveRoadTripLastEventAt = now;

        log.LogInformation("Road trip auto-started for {Vin}: {Id}", e.RowKey, trip.Id);
        if (e.NotifyRoadTripStart)
            await PublishRoadTripPush(e, s, NotificationEvents.RoadTripStarted, name, now, ct);
    }

    /// <summary>
    /// Ends an open road trip that has gone idle (no events for IdleHours) or has run
    /// past the MaxDays hard cap, stamping EndReason="auto".
    /// </summary>
    private async Task MaybeAutoCloseRoadTrip(VinEntity e, MetaEntity meta, VehicleSnapshot s, DateTimeOffset now, CancellationToken ct)
    {
        if (e.ActiveRoadTripStartedAt is not { } startedAt) return;

        var idleHours = RoadTripSettingsDto.ClampIdleHours(meta.RoadTripIdleHours <= 0 ? RoadTripSettingsDto.DefaultIdleHours : meta.RoadTripIdleHours);
        var maxDays = RoadTripSettingsDto.ClampMaxDays(meta.RoadTripMaxDays <= 0 ? RoadTripSettingsDto.DefaultMaxDays : meta.RoadTripMaxDays);

        var lastEvent = e.ActiveRoadTripLastEventAt ?? startedAt;
        var idle = now - lastEvent >= TimeSpan.FromHours(idleHours);
        var tooOld = now - startedAt >= TimeSpan.FromDays(maxDays);
        if (!idle && !tooOld) return;

        await CloseRoadTrip(e, s, now, "auto", ct);
        log.LogInformation("Road trip auto-closed for {Vin} ({Reason})", e.RowKey, idle ? "idle" : "max-age");
    }

    /// <summary>
    /// Closes the VIN's open road trip with the given <paramref name="endReason"/>
    /// ("auto" for the idle/max-age net, "stop" for end-on-stop), stamping end
    /// position/odometer + distance. Clears the VIN pointer and upserts it;
    /// optionally fires a roadtrip.ended push.
    /// </summary>
    private async Task CloseRoadTrip(VinEntity e, VehicleSnapshot s, DateTimeOffset now, string endReason, CancellationToken ct)
    {
        if (e.ActiveRoadTripStartedAt is not { } startedAt) return;

        var rowKey = TableGoHenryStore.ReverseTicksRowKey(startedAt);
        var trip = await store.GetRoadTripAsync(e.RowKey, rowKey, ct);
        if (trip is not null && trip.Status == "active")
        {
            trip.Status = "ended";
            // End at the last real activity (last timeline event), not the stop/idle moment.
            trip.EndedAt = StoreMappings.LastTimelineTimeUtc(trip.TimelineJson) ?? now;
            trip.EndLatitude = s.Latitude;
            trip.EndLongitude = s.Longitude;
            trip.EndOdometerKm = s.OdometerKm;
            if (trip.StartOdometerKm is { } start && s.OdometerKm is { } end && end >= start)
                trip.DistanceKm = end - start;
            trip.EndReason = endReason;
            await store.UpsertRoadTripAsync(trip, ct);
        }

        var name = e.ActiveRoadTripName;
        // Empty strings (not null) so the VIN row's Merge upsert actually clears them.
        e.ActiveRoadTripId = "";
        e.ActiveRoadTripName = "";
        await store.UpsertVehicleAsync(e, ct);

        if (e.NotifyRoadTripEnd)
            await PublishRoadTripPush(e, s, NotificationEvents.RoadTripEnded, name, now, ct);
    }

    /// <summary>Publishes a roadtrip.started/ended FCM push with the same payload shape as BuildMessage.</summary>
    private async Task PublishRoadTripPush(VinEntity e, VehicleSnapshot s, string ev, string tripName, DateTimeOffset now, CancellationToken ct)
    {
        var nickname = e.Nickname ?? e.Model ?? e.RowKey;
        var label = string.IsNullOrWhiteSpace(tripName) ? "road trip" : $"\u201c{tripName}\u201d";
        var (title, detail) = ev == NotificationEvents.RoadTripStarted
            ? ("Road trip started", $"Started {label}")
            : ("Road trip ended", $"Ended {label}");
        var body = $"{nickname} • {detail}";
        var data = new Dictionary<string, string>
        {
            ["event"] = ev,
            ["vin"] = e.RowKey,
            ["nickname"] = nickname,
            ["detail"] = detail,
            ["timestampUtc"] = now.ToString("o"),
        };
        if (s.Latitude is { } lat) data["latitude"] = lat.ToString(System.Globalization.CultureInfo.InvariantCulture);
        if (s.Longitude is { } lon) data["longitude"] = lon.ToString(System.Globalization.CultureInfo.InvariantCulture);
        if (s.AltitudeM is { } alt) data["altitude"] = alt.ToString(System.Globalization.CultureInfo.InvariantCulture);
        if (s.OutsideTempC is { } otc) data["outsideTempC"] = otc.ToString(System.Globalization.CultureInfo.InvariantCulture);

        await publisher.SendAsync(new NotificationMessage
        {
            UserId = e.PartitionKey, Vin = e.RowKey, Event = ev, Title = title, Body = body, Data = data,
        }, ct);
    }
}
