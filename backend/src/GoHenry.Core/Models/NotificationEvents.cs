namespace GoHenry.Core.Models;

/// <summary>
/// The activity event types the backend emits to the phone via FCM. The string
/// values are the exact <c>event</c> data field the GoHenry app switches on in
/// <c>GoHenryFcmService.onMessageReceived</c>.
/// </summary>
public static class NotificationEvents
{
    public const string TripStarted = "trip.started";
    public const string TripEnded = "trip.ended";
    public const string ChargeInProgress = "charge.in_progress";
    public const string ChargeComplete = "charge.complete";
    public const string ChargeError = "charge.error";
    public const string SignalLost = "signal.lost";
    /// <summary>The Ford telemetry feed itself is unreachable (polls failing / stale).</summary>
    public const string TelemetryFeedLost = "telemetryfeed.lost";
    public const string TirePressureWarn = "tire.pressure_warn";
    public const string AlarmTriggered = "alarm.triggered";
    // Road-trip lifecycle pushes (Phase 2). Optional, gated per-VIN like the rest;
    // fire when a road trip opens/closes (manual or automatic).
    public const string RoadTripStarted = "roadtrip.started";
    public const string RoadTripEnded = "roadtrip.ended";
}

/// <summary>
/// A push the backend wants to deliver. Rendered as a data-only FCM message so
/// the app can localise the timestamp. The <see cref="Data"/> dictionary is sent
/// verbatim and always contains <c>event</c>, <c>vin</c>, <c>nickname</c>,
/// <c>timestampUtc</c> and (when known) <c>latitude</c>/<c>longitude</c>.
/// </summary>
public sealed class NotificationMessage
{
    public required string UserId { get; init; }
    public required string Vin { get; init; }
    public required string Event { get; init; }
    public required string Title { get; init; }
    public required string Body { get; init; }
    public IReadOnlyDictionary<string, string> Data { get; init; } = new Dictionary<string, string>();
}
