using Azure;
using Azure.Data.Tables;

namespace GoHenry.Storage;

/// <summary>
/// The Vehicles table row — the single source of truth for one VIN. PartitionKey
/// is the owning <c>userId</c>; RowKey is the <c>vin</c>. It carries vehicle
/// metadata (Meta*), the current telemetry snapshot the poller keeps warm
/// (Snap*), per-VIN poll/activity state, and the per-VIN notification toggles.
/// One row powers the entire FleetFoot-style read surface with no joins, no SQL.
/// </summary>
public sealed class VinEntity : ITableEntity
{
    public string PartitionKey { get; set; } = "";   // userId
    public string RowKey { get; set; } = "";          // vin
    public DateTimeOffset? Timestamp { get; set; }
    public ETag ETag { get; set; }

    // --- Metadata ---
    public string? Nickname { get; set; }
    public string? Model { get; set; }
    public int? ModelYear { get; set; }
    public string? DisplayColor { get; set; }
    public string EngineType { get; set; } = "BEV";
    public string FordSlug { get; set; } = "primary";

    // --- Current snapshot (Snap*) ---
    public double? SnapSocPct { get; set; }
    public double? SnapFuelLevelPct { get; set; }
    public double? SnapRangeKm { get; set; }
    public double? SnapOdometerKm { get; set; }
    public string? SnapChargingStatus { get; set; }
    public bool? SnapPluggedIn { get; set; }
    public double? SnapLatitude { get; set; }
    public double? SnapLongitude { get; set; }
    public string? SnapIgnition { get; set; }
    public string? SnapGearLever { get; set; }
    public string? SnapDoorLocks { get; set; }
    public string? SnapAlarmStatus { get; set; }
    public string? SnapTirePressureStatus { get; set; }
    public double? SnapOilLifePct { get; set; }
    public double? SnapOutsideTempC { get; set; }
    public double? SnapInteriorTempC { get; set; }
    public DateTimeOffset? CapturedAt { get; set; }

    // --- Full raw-telemetry projection (curated per RawFieldMap), JSON-encoded ---
    // One compact JSON array (~3-5 KB) of { name, value, unit, engine } entries —
    // the complete Ford datafeed the Raw Data screen surfaces. Additive; the Snap*
    // columns above are unchanged. Well under Table Storage's 64 KB property limit.
    public string? RawFieldsJson { get; set; }

    // --- Poll / activity state ---
    public DateTimeOffset? LastPolledAt { get; set; }
    public bool LastWasActive { get; set; }
    /// <summary>
    /// The charge phase recorded on the last successful poll (the <see cref="GoHenry.Core.Models.ChargePhase"/>
    /// enum name), so the detector can fire a charge notification only when the
    /// phase changes. Null/empty is treated as <c>Other</c>.
    /// </summary>
    public string? ChargePhase { get; set; }
    public bool HasOpenTrip { get; set; }
    public DateTimeOffset? LastSeenActiveUtc { get; set; }
    /// <summary>
    /// Position captured at the last detected <c>trip.started</c> (ignition-on edge),
    /// denormalized onto the VIN row so the next <c>trip.ended</c> can report the
    /// straight-line distance travelled without any extra query or any reliance on the
    /// phone's local notification history. Null until the first start with a known
    /// position; an end with no captured start simply carries no distance.
    /// </summary>
    public double? TripStartLatitude { get; set; }
    public double? TripStartLongitude { get; set; }
    /// <summary>
    /// "Signal lost" state: the Ford feed is reachable but the vehicle's own data is
    /// frozen — ignition ON yet neither odometer nor the Ford telemetry timestamp has
    /// changed for <c>LostSignalPolls</c> consecutive reads. Drives the hero "Signal"
    /// label and the <c>signal.lost</c> push. Re-armed when the feed un-freezes.
    /// </summary>
    public bool LostSignalRaised { get; set; }
    /// <summary>
    /// "Telemetry feed lost" state: Ford polls have been failing long enough that the
    /// last successful read (<see cref="CapturedAt"/>) is stale beyond the threshold.
    /// Drives the hero "Telemetry" label and the <c>telemetryfeed.lost</c> push.
    /// </summary>
    public bool TelemetryFeedLostRaised { get; set; }
    /// <summary>
    /// Consecutive successful polls during which the feed looked frozen (ignition ON,
    /// odometer + Ford telemetry timestamp both unchanged). <c>signal.lost</c> fires
    /// once this reaches the configured <c>LostSignalPolls</c>. Reset to 0 whenever the
    /// feed advances or the ignition turns off.
    /// </summary>
    public int FrozenFeedPollCount { get; set; }
    /// <summary>The Ford telemetry timestamp (<c>$.updateTime</c>) seen on the previous
    /// successful poll, compared against the next read to detect a frozen feed.</summary>
    public string? SnapFordTelemetryTimestamp { get; set; }
    public bool TireWarnRaised { get; set; }
    public bool AlarmRaised { get; set; }
    public bool TrackingPaused { get; set; }

    // --- Active road trip pointer (Phase-1 road trips) ---
    // Denormalized onto the VIN row so the poll worker can stamp events onto the
    // open trip with NO extra query, and so the telemetry read can tell the app a
    // trip is in progress (survives app reinstall — the server is the source of
    // truth). Empty string = no open trip; we use "" rather than null because the
    // VIN row is written with Merge upserts, which don't clear properties set null.
    public string ActiveRoadTripId { get; set; } = "";
    public string ActiveRoadTripName { get; set; } = "";
    public DateTimeOffset? ActiveRoadTripStartedAt { get; set; }
    // Last time the open trip recorded an event — used by the idle auto-close net
    // so the worker can decide to auto-end a forgotten trip without reading its row.
    public DateTimeOffset? ActiveRoadTripLastEventAt { get; set; }

    // --- Notification toggles ---
    public bool NotifyStart { get; set; }
    public bool NotifyStop { get; set; }
    public bool NotifyChargeInProgress { get; set; }
    public bool NotifyChargeComplete { get; set; }
    public bool NotifyChargeError { get; set; }
    public bool NotifyLostSignal { get; set; }
    public bool NotifyTelemetryFeedLost { get; set; }
    public bool NotifyTirePressure { get; set; }
    public bool NotifyAlarm { get; set; }
    public bool NotifyRoadTripStart { get; set; }
    public bool NotifyRoadTripEnd { get; set; }
}

/// <summary>A linked Ford account (one per user+slug). Token value lives in Key Vault.</summary>
public sealed class FordAccountEntity : ITableEntity
{
    public string PartitionKey { get; set; } = "";   // userId
    public string RowKey { get; set; } = "";          // slug
    public DateTimeOffset? Timestamp { get; set; }
    public ETag ETag { get; set; }

    public string Status { get; set; } = "UNLINKED";  // ACTIVE | NEEDS_REAUTH | UNLINKED
    public bool IsPrimary { get; set; }
    public string? KvSecretName { get; set; }
    public DateTimeOffset? LastRefreshAt { get; set; }
}

/// <summary>A short-lived OAuth state nonce. Partition is constant; RowKey is the GUID.</summary>
public sealed class OAuthStateEntity : ITableEntity
{
    public string PartitionKey { get; set; } = "state";
    public string RowKey { get; set; } = "";          // state guid
    public DateTimeOffset? Timestamp { get; set; }
    public ETag ETag { get; set; }

    public string UserId { get; set; } = "";
    public string Slug { get; set; } = "primary";
    public DateTimeOffset CreatedAt { get; set; }
}

/// <summary>One app install (FCM token registration). PartitionKey user, RowKey install id.</summary>
public sealed class InstallEntity : ITableEntity
{
    public string PartitionKey { get; set; } = "";   // userId
    public string RowKey { get; set; } = "";          // installationId
    public DateTimeOffset? Timestamp { get; set; }
    public ETag ETag { get; set; }

    public string FcmToken { get; set; } = "";
    public string App { get; set; } = "gohenry";
    public DateTimeOffset UpdatedAt { get; set; }
}

/// <summary>A finished trip. PartitionKey is the VIN; RowKey sorts newest-first.</summary>
public sealed class TripHistoryEntity : ITableEntity
{
    public string PartitionKey { get; set; } = "";   // vin
    public string RowKey { get; set; } = "";          // reverse-ticks
    public DateTimeOffset? Timestamp { get; set; }
    public ETag ETag { get; set; }

    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset EndedAt { get; set; }
    public double? StartOdometerKm { get; set; }
    public double? EndOdometerKm { get; set; }
    public double? DistanceKm { get; set; }
    public double? EndLatitude { get; set; }
    public double? EndLongitude { get; set; }
}

/// <summary>A finished charge session. PartitionKey is the VIN; RowKey sorts newest-first.</summary>
public sealed class ChargeHistoryEntity : ITableEntity
{
    public string PartitionKey { get; set; } = "";   // vin
    public string RowKey { get; set; } = "";          // reverse-ticks
    public DateTimeOffset? Timestamp { get; set; }
    public ETag ETag { get; set; }

    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset EndedAt { get; set; }
    public double? StartSocPct { get; set; }
    public double? EndSocPct { get; set; }
    public string? Outcome { get; set; }              // complete | error
}

/// <summary>
/// A road trip — a named, durable journey that groups one or more individual
/// trips (ignition cycles) and the notification events that occurred during it.
/// PartitionKey is the VIN; RowKey is <c>ReverseTicksRowKey(StartedAt)</c> so a
/// car's trips list newest-first (same pattern as <see cref="TripHistoryEntity"/>).
/// The event timeline is embedded as one compact JSON array (well under the 64 KB
/// property limit, like <see cref="VinEntity.RawFieldsJson"/>) so a detail read is
/// a single point GET — no joins, no SQL.
/// </summary>
public sealed class RoadTripEntity : ITableEntity
{
    public string PartitionKey { get; set; } = "";   // vin
    public string RowKey { get; set; } = "";          // reverse-ticks of StartedAt
    public DateTimeOffset? Timestamp { get; set; }
    public ETag ETag { get; set; }

    public string Id { get; set; } = "";              // stable GUID (durable handle)
    public string UserId { get; set; } = "";          // owner (back-reference)
    public string Name { get; set; } = "";
    public string Status { get; set; } = "active";    // active | ended
    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset? EndedAt { get; set; }
    public double? StartLatitude { get; set; }
    public double? StartLongitude { get; set; }
    public double? EndLatitude { get; set; }
    public double? EndLongitude { get; set; }
    public double? StartOdometerKm { get; set; }
    public double? EndOdometerKm { get; set; }
    public double? DistanceKm { get; set; }
    public int SegmentCount { get; set; }             // ignition cycles within the trip
    public int ChargeStops { get; set; }
    public int EventCount { get; set; }               // timeline length (may exceed stored entries)
    public string? TimelineJson { get; set; }         // JSON array of {ts,event,detail,latitude,longitude}
    public string StartMethod { get; set; } = "manual";
    public string? EndReason { get; set; }            // manual | auto
}

/// <summary>A singleton meta row (poll settings, registry refresh stamps, etc).</summary>
public sealed class MetaEntity : ITableEntity
{
    public string PartitionKey { get; set; } = "_meta";
    public string RowKey { get; set; } = "";          // e.g. "pollSettings"
    public DateTimeOffset? Timestamp { get; set; }
    public ETag ETag { get; set; }

    public int PollCadenceMinutes { get; set; } = 2;
    // Consecutive missed/stale polls before "lost signal" fires (5..20). The dwell
    // time is this × PollCadenceMinutes, so it scales with the chosen cadence.
    public int LostSignalPolls { get; set; } = 10;
    public DateTimeOffset? RegistryRefreshedAt { get; set; }
    public DateTimeOffset? LastActivitySweepAt { get; set; }
    // When the timer last fanned out a poll. The dispatcher fires every minute but
    // only enqueues work once PollCadenceMinutes has elapsed since this stamp, so
    // the user-set cadence applies live (no redeploy) and stays in Table Storage.
    public DateTimeOffset? LastDispatchedAt { get; set; }

    // Road-trip automation (Phase 2). App-wide, on the same pollSettings row.
    public bool RoadTripAutoStart { get; set; }
    public int RoadTripIdleHours { get; set; } = 12;
    public int RoadTripMaxDays { get; set; } = 7;
    // When on, a trip.ended (stop) auto-closes any open road trip.
    public bool RoadTripEndOnStop { get; set; }
}
