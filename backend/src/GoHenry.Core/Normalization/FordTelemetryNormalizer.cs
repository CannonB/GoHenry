using System.Text.Json;
using GoHenry.Core.Models;

namespace GoHenry.Core.Normalization;

/// <summary>
/// Converts a raw Ford vehicle-status JSON document into the canonical
/// <see cref="VehicleSnapshot"/>. Ford's real payloads nest values under varying
/// shapes, so this normalizer searches case-insensitively for a set of candidate
/// keys at any depth and pulls the first scalar (or nested <c>value</c>) it finds.
/// That keeps it tolerant of schema drift and makes it trivially unit-testable
/// with a flat JSON object.
/// </summary>
public static class FordTelemetryNormalizer
{
    public static VehicleSnapshot Normalize(string vin, EngineType engineType, string fordJson, DateTimeOffset capturedAt)
    {
        using var doc = JsonDocument.Parse(string.IsNullOrWhiteSpace(fordJson) ? "{}" : fordJson);
        var root = doc.RootElement;

        var snap = new VehicleSnapshot
        {
            Vin = vin,
            EngineType = engineType,
            CapturedAt = capturedAt,
            SocPct = FindNumber(root, "soc", "socPct", "stateOfCharge", "batteryStateOfCharge", "chargeLevel"),
            FuelLevelPct = FindNumber(root, "fuelLevel", "fuelLevelPct", "fuel"),
            RangeKm = FindNumber(root, "rangeKm", "range", "distanceToEmpty", "elVehDTE", "gasDTE"),
            OdometerKm = FindNumber(root, "odometerKm", "odometer", "mileage"),
            ChargingStatus = FindString(root, "chargingStatus", "chargeStatus", "plugStatus", "charging"),
            PluggedIn = FindBool(root, "pluggedIn", "plugged", "isPlugged", "plugConnected"),
            Latitude = FindNumber(root, "latitude", "lat"),
            Longitude = FindNumber(root, "longitude", "lon", "lng"),
            Ignition = FindString(root, "ignition", "ignitionStatus", "engineStatus"),
            GearLever = FindString(root, "gearLever", "gear", "gearLeverPosition"),
            DoorLocks = FindString(root, "doorLocks", "lockStatus", "doorLockStatus"),
            AlarmStatus = FindString(root, "alarmStatus", "alarm"),
            TirePressureStatus = FindString(root, "tirePressureStatus", "tirePressure", "tpms"),
            OilLifePct = FindNumber(root, "oilLifePct", "oilLife"),
            OutsideTempC = FindNumber(root, "outsideTempC", "outsideTemp", "ambientTemp"),
            InteriorTempC = FindNumber(root, "interiorTempC", "interiorTemp", "cabinTemp"),
            AltitudeM = FindNumber(root, "altitude", "altitudeM", "alt", "elevation"),
        };
        return snap;
    }

    /// <summary>Projects a normalized snapshot onto the wire DTO the app consumes.</summary>
    public static TelemetryDto ToDto(VehicleSnapshot s) => new()
    {
        Vin = s.Vin,
        CapturedAt = s.CapturedAt.ToString("o"),
        SocPct = s.SocPct,
        RangeValue = s.RangeKm,
        RangeUnit = s.RangeKm is null ? null : "km",
        OdometerValue = s.OdometerKm,
        OdometerUnit = s.OdometerKm is null ? null : "km",
        ChargingStatus = s.ChargingStatus,
        PluggedIn = s.PluggedIn,
        Latitude = s.Latitude,
        Longitude = s.Longitude,
        Ignition = s.Ignition,
        GearLever = s.GearLever,
        DoorLocks = s.DoorLocks,
        AlarmStatus = s.AlarmStatus,
        TirePressureStatus = s.TirePressureStatus,
        OilLifePct = s.OilLifePct,
        OutsideTempValue = s.OutsideTempC,
        OutsideTempUnit = s.OutsideTempC is null ? null : "C",
        InteriorTempValue = s.InteriorTempC,
        InteriorTempUnit = s.InteriorTempC is null ? null : "C",
        // HEV uses the fuel hero; surface fuel only when it makes sense.
        FuelLevelValue = s.FuelLevelPct,
        FuelLevelUnit = s.FuelLevelPct is null ? null : "%",
        LastPolledAt = s.CapturedAt.ToString("o"),
        LastOdometerKm = s.OdometerKm,
        LastWasActive = s.IsActive,
        LastStatus = s.Ignition,
    };

    private static double? FindNumber(JsonElement root, params string[] keys)
    {
        var el = FindFirst(root, keys);
        if (el is null) return null;
        var v = el.Value;
        switch (v.ValueKind)
        {
            case JsonValueKind.Number when v.TryGetDouble(out var d): return d;
            case JsonValueKind.String when double.TryParse(v.GetString(), out var ds): return ds;
            case JsonValueKind.Object:
                // Ford often nests a scalar under "value".
                if (v.TryGetProperty("value", out var inner) && inner.ValueKind == JsonValueKind.Number && inner.TryGetDouble(out var nd))
                    return nd;
                return null;
            default: return null;
        }
    }

    private static string? FindString(JsonElement root, params string[] keys)
    {
        var el = FindFirst(root, keys);
        if (el is null) return null;
        var v = el.Value;
        return v.ValueKind switch
        {
            JsonValueKind.String => v.GetString(),
            JsonValueKind.Number => v.ToString(),
            JsonValueKind.True => "true",
            JsonValueKind.False => "false",
            JsonValueKind.Object when v.TryGetProperty("value", out var inner) => inner.ToString(),
            _ => null,
        };
    }

    private static bool? FindBool(JsonElement root, params string[] keys)
    {
        var el = FindFirst(root, keys);
        if (el is null) return null;
        var v = el.Value;
        return v.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.String when bool.TryParse(v.GetString(), out var b) => b,
            JsonValueKind.Number => v.TryGetDouble(out var d) && d != 0,
            _ => null,
        };
    }

    /// <summary>Depth-first, case-insensitive search for the first matching key.</summary>
    private static JsonElement? FindFirst(JsonElement element, string[] keys)
    {
        switch (element.ValueKind)
        {
            case JsonValueKind.Object:
                foreach (var prop in element.EnumerateObject())
                {
                    foreach (var key in keys)
                        if (string.Equals(prop.Name, key, StringComparison.OrdinalIgnoreCase))
                            return prop.Value;
                }
                // Recurse only after checking this level (shallow keys win).
                foreach (var prop in element.EnumerateObject())
                {
                    var hit = FindFirst(prop.Value, keys);
                    if (hit is not null) return hit;
                }
                return null;
            case JsonValueKind.Array:
                foreach (var item in element.EnumerateArray())
                {
                    var hit = FindFirst(item, keys);
                    if (hit is not null) return hit;
                }
                return null;
            default:
                return null;
        }
    }
}
