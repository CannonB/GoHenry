using GoHenry.Core.Models;
using GoHenry.Core.Normalization;
using System.Text.Json;

namespace GoHenry.Storage;

/// <summary>Projection helpers between Table entities and the wire DTOs.</summary>
public static class StoreMappings
{
    public static VehicleDto ToVehicleDto(this VinEntity e) => new()
    {
        Vin = e.RowKey,
        Model = e.Model,
        Nickname = e.Nickname,
        ModelYear = e.ModelYear,
        DisplayColor = e.DisplayColor,
        EngineType = e.EngineType,
        AppSlug = e.FordSlug,
    };

    public static TelemetryDto ToTelemetryDto(this VinEntity e) => new()
    {
        Vin = e.RowKey,
        CapturedAt = e.CapturedAt?.ToString("o"),
        SocPct = e.SnapSocPct,
        RangeValue = e.SnapRangeKm,
        RangeUnit = e.SnapRangeKm is null ? null : "km",
        OdometerValue = e.SnapOdometerKm,
        OdometerUnit = e.SnapOdometerKm is null ? null : "km",
        ChargingStatus = e.SnapChargingStatus,
        PluggedIn = e.SnapPluggedIn,
        Latitude = e.SnapLatitude,
        Longitude = e.SnapLongitude,
        Ignition = e.SnapIgnition,
        GearLever = e.SnapGearLever,
        DoorLocks = e.SnapDoorLocks,
        AlarmStatus = e.SnapAlarmStatus,
        TirePressureStatus = e.SnapTirePressureStatus,
        OilLifePct = e.SnapOilLifePct,
        OutsideTempValue = e.SnapOutsideTempC,
        OutsideTempUnit = e.SnapOutsideTempC is null ? null : "C",
        InteriorTempValue = e.SnapInteriorTempC,
        InteriorTempUnit = e.SnapInteriorTempC is null ? null : "C",
        FuelLevelValue = e.SnapFuelLevelPct,
        FuelLevelUnit = e.SnapFuelLevelPct is null ? null : "%",
        LastStatus = e.SnapIgnition,
        LastPolledAt = e.LastPolledAt?.ToString("o"),
        LastOdometerKm = e.SnapOdometerKm,
        LastWasActive = e.LastWasActive,
        LastHasOpenActivity = e.HasOpenTrip,
        LastTripLostSignal = e.LostSignalRaised,
        TelemetryFeedLost = e.TelemetryFeedLostRaised,
        RawFields = BuildRawFields(e),
        ActiveRoadTripId = string.IsNullOrEmpty(e.ActiveRoadTripId) ? null : e.ActiveRoadTripId,
        ActiveRoadTripName = string.IsNullOrEmpty(e.ActiveRoadTripId) ? null : e.ActiveRoadTripName,
        ActiveRoadTripStartedAt = string.IsNullOrEmpty(e.ActiveRoadTripId) ? null : e.ActiveRoadTripStartedAt?.ToString("o"),
    };

    private static readonly JsonSerializerOptions RoadTripJson = new(JsonSerializerDefaults.Web);

    /// <summary>Projects a road-trip entity to its wire DTO; pass <paramref name="includeTimeline"/> for the detail read.</summary>
    public static RoadTripDto ToRoadTripDto(this RoadTripEntity e, bool includeTimeline = false)
    {
        var dto = new RoadTripDto
        {
            Id = e.Id,
            Vin = e.PartitionKey,
            Name = e.Name,
            Status = e.Status,
            StartedAt = e.StartedAt.ToString("o"),
            EndedAt = e.EndedAt?.ToString("o"),
            DistanceKm = e.DistanceKm,
            SegmentCount = e.SegmentCount,
            ChargeStops = e.ChargeStops,
            EventCount = e.EventCount,
            StartLatitude = e.StartLatitude,
            StartLongitude = e.StartLongitude,
            EndLatitude = e.EndLatitude,
            EndLongitude = e.EndLongitude,
            StartMethod = e.StartMethod,
            EndReason = e.EndReason,
        };
        if (includeTimeline) dto.Timeline = ParseTimeline(e.TimelineJson);
        return dto;
    }

    /// <summary>Deserializes a stored road-trip timeline; returns an empty list on null/garbage.</summary>
    public static List<RoadTripEventDto> ParseTimeline(string? json)
    {
        if (string.IsNullOrWhiteSpace(json)) return new List<RoadTripEventDto>();
        try { return JsonSerializer.Deserialize<List<RoadTripEventDto>>(json, RoadTripJson) ?? new(); }
        catch (JsonException) { return new List<RoadTripEventDto>(); }
    }

    /// <summary>Serializes a road-trip timeline for storage (camelCase, matches <see cref="ParseTimeline"/>).</summary>
    public static string SerializeTimeline(List<RoadTripEventDto> timeline) =>
        JsonSerializer.Serialize(timeline, RoadTripJson);

    /// <summary>
    /// The UTC timestamp of the most recent timeline event, used as a trip's "ended"
    /// time so closing reflects the last real activity (last notification) rather
    /// than the moment the user pressed stop / the idle timer fired. Null when the
    /// timeline is empty or unparseable.
    /// </summary>
    public static DateTimeOffset? LastTimelineTimeUtc(string? json)
    {
        DateTimeOffset? latest = null;
        foreach (var ev in ParseTimeline(json))
        {
            if (DateTimeOffset.TryParse(ev.Ts, System.Globalization.CultureInfo.InvariantCulture,
                    System.Globalization.DateTimeStyles.AssumeUniversal | System.Globalization.DateTimeStyles.AdjustToUniversal,
                    out var ts) && (latest is null || ts > latest))
                latest = ts;
        }
        return latest;
    }

    private static readonly JsonSerializerOptions RawJsonOpts = new() { PropertyNameCaseInsensitive = true };

    /// <summary>
    /// Builds the full Raw Data field list: the curated Ford telemetry projection
    /// captured by the poller (<see cref="VinEntity.RawFieldsJson"/>) followed by the
    /// vehicle-metadata fields (which live on the VIN row, not in the feed). Returns
    /// null when neither source yields anything, so the screen degrades gracefully.
    /// </summary>
    private static List<RawFieldDto>? BuildRawFields(VinEntity e)
    {
        var list = new List<RawFieldDto>();

        if (!string.IsNullOrWhiteSpace(e.RawFieldsJson))
        {
            try
            {
                var telemetry = JsonSerializer.Deserialize<List<RawFieldDto>>(e.RawFieldsJson, RawJsonOpts);
                if (telemetry is not null) list.AddRange(telemetry);
            }
            catch (JsonException) { /* ignore a malformed cache; metadata still shows */ }
        }

        foreach (var def in RawFieldMap.Metadata)
        {
            var value = def.Path switch
            {
                "vin"        => e.RowKey,
                "nickName"   => e.Nickname,
                "modelName"  => e.Model,
                "modelYear"  => e.ModelYear?.ToString(),
                "color"      => e.DisplayColor,
                "engineType" => e.EngineType,
                _            => null,
            };
            if (!string.IsNullOrWhiteSpace(value))
                list.Add(new RawFieldDto { Name = def.Name, Value = value, Unit = def.Unit, Engine = def.Engine, Path = def.Path });
        }

        return list.Count > 0 ? list : null;
    }

    public static NotifyPrefsDto ToNotifyPrefsDto(this VinEntity e) => new()
    {
        Start = e.NotifyStart,
        Stop = e.NotifyStop,
        ChargeInProgress = e.NotifyChargeInProgress,
        ChargeComplete = e.NotifyChargeComplete,
        ChargeError = e.NotifyChargeError,
        LostSignal = e.NotifyLostSignal,
        TelemetryFeedLost = e.NotifyTelemetryFeedLost,
        TirePressure = e.NotifyTirePressure,
        Alarm = e.NotifyAlarm,
        RoadTripStart = e.NotifyRoadTripStart,
        RoadTripEnd = e.NotifyRoadTripEnd,
    };

    public static FordAccountStatusDto ToStatusDto(this FordAccountEntity e, int refreshLifetimeDays)
    {
        int? days = null;
        if (e.LastRefreshAt is { } last)
            days = (int)Math.Floor((last.AddDays(refreshLifetimeDays) - DateTimeOffset.UtcNow).TotalDays);
        return new FordAccountStatusDto
        {
            AppSlug = e.RowKey,
            IsPrimary = e.IsPrimary,
            IsLinked = e.Status != "UNLINKED",
            Status = e.Status,
            NeedsReauth = e.Status == "NEEDS_REAUTH",
            LastRefreshAt = e.LastRefreshAt?.ToString("o"),
            DaysUntilReauth = days,
        };
    }
}
