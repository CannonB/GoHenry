namespace GoHenry.Core.Models;

/// <summary>
/// Current telemetry snapshot returned by <c>GET /api/fleet/telemetry/{vin}</c>.
/// All fields are nullable — the app degrades gracefully on partial data. Field
/// names are byte-compatible with the FleetFoot client's <c>Telemetry</c> DTO.
/// </summary>
public sealed class TelemetryDto
{
    public string Vin { get; set; } = "";
    public string? CapturedAt { get; set; }

    // Energy / range
    public double? SocPct { get; set; }
    public double? RangeValue { get; set; }
    public string? RangeUnit { get; set; }
    public double? OdometerValue { get; set; }
    public string? OdometerUnit { get; set; }
    public string? ChargingStatus { get; set; }
    public bool? PluggedIn { get; set; }

    // Location
    public double? Latitude { get; set; }
    public double? Longitude { get; set; }

    // Poll/activity bookkeeping (read-only, surfaced for the detail screen)
    public string? LastStatus { get; set; }
    public string? LastPolledAt { get; set; }
    public double? LastOdometerKm { get; set; }
    public bool? LastWasActive { get; set; }
    public bool? LastTripLostSignal { get; set; }
    /// <summary>True when the Ford telemetry feed itself is unreachable (polls stale).</summary>
    public bool? TelemetryFeedLost { get; set; }
    public bool? LastHasOpenActivity { get; set; }

    // Additive read-only metrics from the cached Ford read
    public string? Ignition { get; set; }
    public string? GearLever { get; set; }
    public double? OilLifePct { get; set; }
    public double? OutsideTempValue { get; set; }
    public string? OutsideTempUnit { get; set; }
    public string? AlarmStatus { get; set; }
    public string? DoorLocks { get; set; }
    public string? TirePressureStatus { get; set; }
    public double? FuelLevelValue { get; set; }
    public string? FuelLevelUnit { get; set; }
    public double? InteriorTempValue { get; set; }
    public string? InteriorTempUnit { get; set; }

    // Full curated Ford raw-telemetry datafeed for the Raw Data screen (additive,
    // read-only). Null when no projection/metadata is available. The 16 fields above
    // are unchanged and keep the hero/carousel/detail UI working.
    public List<RawFieldDto>? RawFields { get; set; }

    // Active road trip (Phase-1). Surfaced on every telemetry read so the app can
    // render the Start/Stop control and rehydrate an in-progress trip after a
    // reinstall with no extra call. Null when no trip is open.
    public string? ActiveRoadTripId { get; set; }
    public string? ActiveRoadTripName { get; set; }
    public string? ActiveRoadTripStartedAt { get; set; }
}

/// <summary>One raw Ford field as surfaced on the app's Raw Data screen.</summary>
public sealed class RawFieldDto
{
    public string Name { get; set; } = "";
    public string Value { get; set; } = "";
    public string? Unit { get; set; }
    public string? Engine { get; set; } // BEV | HEV | BOTH
    /// <summary>Raw Ford telemetry field path (or metadata source key) behind this field.</summary>
    public string? Path { get; set; }
}

/// <summary>Per-VIN push toggles (<c>GET|POST /api/fleet/notifications/{vin}</c>).</summary>
public sealed class NotifyPrefsDto
{
    public bool Start { get; set; }
    public bool Stop { get; set; }
    public bool ChargeInProgress { get; set; }
    public bool ChargeComplete { get; set; }
    public bool ChargeError { get; set; }
    public bool LostSignal { get; set; }
    public bool TelemetryFeedLost { get; set; }
    public bool TirePressure { get; set; }
    public bool Alarm { get; set; }
    // Road-trip lifecycle pushes (Phase 2), off by default.
    public bool RoadTripStart { get; set; }
    public bool RoadTripEnd { get; set; }
}

/// <summary>
/// App-wide Ford poll cadence (<c>GET|POST /api/fleet/pollsettings</c>). One value
/// for the whole hobby fleet — the timer dispatcher fires at the minimum cadence
/// and only fans out a poll once <see cref="CadenceMinutes"/> has elapsed, so the
/// cadence changes take effect with no redeploy and stay entirely in Table Storage.
/// </summary>
public sealed class PollSettingsDto
{
    /// <summary>Smallest allowed cadence, in minutes (also the timer tick rate).</summary>
    public const int MinMinutes = 1;
    /// <summary>Largest allowed cadence, in minutes.</summary>
    public const int MaxMinutes = 10;
    /// <summary>Default cadence when nothing has been set.</summary>
    public const int DefaultMinutes = 2;

    /// <summary>Fewest consecutive missed polls before "lost signal" is raised.</summary>
    public const int MinLostSignalPolls = 5;
    /// <summary>Most consecutive missed polls before "lost signal" is raised.</summary>
    public const int MaxLostSignalPolls = 20;
    /// <summary>Default missed-poll count for "lost signal" when nothing is set.</summary>
    public const int DefaultLostSignalPolls = 10;

    /// <summary>Minutes between Ford telemetry polls (clamped to 1..10).</summary>
    public int CadenceMinutes { get; set; } = DefaultMinutes;

    /// <summary>
    /// Number of consecutive failed/stale polls before a vehicle is treated as
    /// having lost signal (clamped to 5..20). The actual dwell time is this count
    /// multiplied by <see cref="CadenceMinutes"/>, so it scales with the cadence.
    /// </summary>
    public int LostSignalPolls { get; set; } = DefaultLostSignalPolls;

    /// <summary>Clamp any value into the supported 1..10 range.</summary>
    public static int Clamp(int minutes) => Math.Clamp(minutes, MinMinutes, MaxMinutes);

    /// <summary>Clamp any value into the supported 5..20 missed-poll range.</summary>
    public static int ClampLostSignalPolls(int polls) => Math.Clamp(polls, MinLostSignalPolls, MaxLostSignalPolls);
}

/// <summary>
/// App-wide road-trip automation settings (<c>GET|POST /api/fleet/roadtripsettings</c>).
/// Like <see cref="PollSettingsDto"/> these live on the <c>pollSettings</c> meta row
/// (no SQL) and apply to the whole hobby fleet. <see cref="AutoStart"/> is opt-in;
/// the idle/max auto-close net is always on (it just bounds a forgotten trip).
/// </summary>
public sealed class RoadTripSettingsDto
{
    public const int MinIdleHours = 2;
    public const int MaxIdleHours = 12;
    public const int DefaultIdleHours = 12;
    public const int MinMaxDays = 1;
    public const int MaxMaxDays = 7;
    public const int DefaultMaxDays = 7;

    /// <summary>When on, a <c>trip.started</c> with no open road trip auto-opens one.</summary>
    public bool AutoStart { get; set; }
    /// <summary>Auto-close an open trip after this many idle hours (no events).</summary>
    public int IdleHours { get; set; } = DefaultIdleHours;
    /// <summary>Hard cap: auto-close a trip once it is this many days old.</summary>
    public int MaxDays { get; set; } = DefaultMaxDays;
    /// <summary>When on, a <c>trip.ended</c> (stop) auto-closes any open road trip.</summary>
    public bool EndOnStop { get; set; }

    public static int ClampIdleHours(int h) => Math.Clamp(h, MinIdleHours, MaxIdleHours);
    public static int ClampMaxDays(int d) => Math.Clamp(d, MinMaxDays, MaxMaxDays);
}

/// <summary>One member event on a road-trip timeline.</summary>
public sealed class RoadTripEventDto
{
    public string Ts { get; set; } = "";              // ISO-8601 UTC
    public string Event { get; set; } = "";           // NotificationEvents.* key
    public string? Detail { get; set; }
    public double? Latitude { get; set; }
    public double? Longitude { get; set; }
    public double? AltitudeM { get; set; }            // metres, when known
    public double? OutsideTempC { get; set; }         // °C, when known
}

/// <summary>
/// A road trip (<c>GET|POST /api/fleet/roadtrips/...</c>). Summary form omits
/// <see cref="Timeline"/>; the detail endpoint includes it. All durable in Table
/// Storage so history (and an in-progress trip) survive an app reinstall.
/// </summary>
public sealed class RoadTripDto
{
    /// <summary>Cap on stored timeline entries (keeps the JSON well under 64 KB).</summary>
    public const int MaxTimelineEvents = 800;

    public string Id { get; set; } = "";
    public string Vin { get; set; } = "";
    public string Name { get; set; } = "";
    public string Status { get; set; } = "active";    // active | ended
    public string? StartedAt { get; set; }
    public string? EndedAt { get; set; }
    public double? DistanceKm { get; set; }
    public int SegmentCount { get; set; }
    public int ChargeStops { get; set; }
    public int EventCount { get; set; }
    public double? StartLatitude { get; set; }
    public double? StartLongitude { get; set; }
    public double? EndLatitude { get; set; }
    public double? EndLongitude { get; set; }
    public string StartMethod { get; set; } = "manual";
    public string? EndReason { get; set; }
    public List<RoadTripEventDto>? Timeline { get; set; } // detail response only
}

/// <summary>Optional body for <c>POST /api/fleet/roadtrips/{vin}/start</c>.</summary>
public sealed class RoadTripStartRequest
{
    public string? Name { get; set; }
}

/// <summary>One row of <c>GET /api/ford/account/status</c> (one per configured slug).</summary>
public sealed class FordAccountStatusDto
{
    public string AppSlug { get; set; } = "primary";
    public bool IsPrimary { get; set; }
    public bool IsLinked { get; set; }
    public string Status { get; set; } = "UNLINKED"; // ACTIVE | NEEDS_REAUTH | UNLINKED
    public bool NeedsReauth { get; set; }
    public string? LastRefreshAt { get; set; }
    public int? DaysUntilReauth { get; set; }
}
