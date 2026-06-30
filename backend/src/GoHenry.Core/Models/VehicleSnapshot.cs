namespace GoHenry.Core.Models;

/// <summary>
/// Coarse charge phase derived from Ford's charge-display status. The detector
/// raises a notification whenever this value *changes* between polls.
/// </summary>
public enum ChargePhase
{
    /// <summary>Any value (or absence) that is not one of the three notifying states.</summary>
    Other,
    InProgress,
    Complete,
    Error,
}

/// <summary>
/// Normalized, engine-aware telemetry snapshot — the canonical shape the poller
/// writes to Table Storage and the API serves. Produced from a raw Ford payload
/// by <see cref="GoHenry.Core.Normalization.FordTelemetryNormalizer"/>.
/// </summary>
public sealed class VehicleSnapshot
{
    public string Vin { get; set; } = "";
    public EngineType EngineType { get; set; } = EngineType.BEV;
    public DateTimeOffset CapturedAt { get; set; } = DateTimeOffset.UtcNow;

    public double? SocPct { get; set; }
    public double? FuelLevelPct { get; set; }
    public double? RangeKm { get; set; }
    public double? OdometerKm { get; set; }

    public string? ChargingStatus { get; set; }
    public bool? PluggedIn { get; set; }

    /// <summary>
    /// Ford's charge <em>display</em> status (<c>metrics.xevBatteryChargeDisplayStatus</c>),
    /// e.g. <c>IN_PROGRESS</c> / <c>COMPLETED</c> / <c>NOT_READY</c> / <c>FAULT</c>.
    /// This — not the looser <see cref="ChargingStatus"/> — drives the charge
    /// notifications and the hero charge glyph. Populated by the poller from the
    /// extracted raw field <c>"SoCChargeDisplayStatus"</c>.
    /// </summary>
    public string? ChargeDisplayStatus { get; set; }

    public double? Latitude { get; set; }
    public double? Longitude { get; set; }

    public string? Ignition { get; set; }
    public string? GearLever { get; set; }
    public string? DoorLocks { get; set; }
    public string? AlarmStatus { get; set; }
    public string? TirePressureStatus { get; set; }

    // Per-wheel tire pressure status (resolved from the Ford tirePressureStatus[]
    // array). Populated by the poller from the extracted raw fields so the
    // detector can name which wheel is no longer "normal". Null when unknown.
    public string? TireFrontLeftStatus { get; set; }
    public string? TireFrontRightStatus { get; set; }
    public string? TireRearLeftStatus { get; set; }
    public string? TireRearRightStatus { get; set; }

    public double? OilLifePct { get; set; }
    public double? OutsideTempC { get; set; }
    public double? InteriorTempC { get; set; }

    /// <summary>Altitude / elevation in metres, when the Ford payload reports it.</summary>
    public double? AltitudeM { get; set; }

    /// <summary>True when the vehicle is moving / ignition on (drives trip detection).</summary>
    public bool IsActive =>
        string.Equals(Ignition, "ON", StringComparison.OrdinalIgnoreCase) ||
        string.Equals(Ignition, "RUN", StringComparison.OrdinalIgnoreCase) ||
        string.Equals(Ignition, "START", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Coarse charge phase from <see cref="ChargeDisplayStatus"/>, used by the
    /// detector to fire a notification when the phase changes:
    /// <c>IN_PROGRESS</c> → <c>charge.in_progress</c>, <c>COMPLETED</c> →
    /// <c>charge.complete</c>, an <c>ERROR</c>/<c>FAULT</c> state → <c>charge.error</c>.
    /// Any other or absent value is <see cref="Models.ChargePhase.Other"/> and
    /// raises nothing.
    /// </summary>
    public ChargePhase ChargePhase
    {
        get
        {
            var s = ChargeDisplayStatus?.Trim();
            if (string.IsNullOrEmpty(s)) return ChargePhase.Other;
            if (s.Contains("IN_PROGRESS", StringComparison.OrdinalIgnoreCase) ||
                s.Contains("INPROGRESS", StringComparison.OrdinalIgnoreCase))
                return ChargePhase.InProgress;
            if (s.Contains("COMPLETED", StringComparison.OrdinalIgnoreCase) ||
                s.Contains("TARGET_REACHED", StringComparison.OrdinalIgnoreCase))
                return ChargePhase.Complete;
            if (s.Contains("ERROR", StringComparison.OrdinalIgnoreCase) ||
                s.Contains("FAULT", StringComparison.OrdinalIgnoreCase))
                return ChargePhase.Error;
            return ChargePhase.Other;
        }
    }

    /// <summary>Tire status values that are considered healthy (no warning).</summary>
    private static readonly string[] HealthyTireStatuses =
        { "NORMAL", "OK", "SYSTEM_OK", "NORMAL_OPERATION", "STATUS_NORMAL", "GOOD" };

    private static bool TireIsAbnormal(string? status) =>
        !string.IsNullOrWhiteSpace(status) &&
        !HealthyTireStatuses.Any(h => string.Equals(h, status, StringComparison.OrdinalIgnoreCase));

    /// <summary>
    /// Human-readable labels of every wheel currently reporting a non-normal tire
    /// pressure status (e.g. "Front Left"). Empty when all wheels are healthy.
    /// </summary>
    public IReadOnlyList<string> AbnormalTires
    {
        get
        {
            var bad = new List<string>(4);
            if (TireIsAbnormal(TireFrontLeftStatus)) bad.Add("Front Left");
            if (TireIsAbnormal(TireFrontRightStatus)) bad.Add("Front Right");
            if (TireIsAbnormal(TireRearLeftStatus)) bad.Add("Rear Left");
            if (TireIsAbnormal(TireRearRightStatus)) bad.Add("Rear Right");
            return bad;
        }
    }

    /// <summary>True when any wheel reports a non-normal tire pressure status.</summary>
    public bool IsTireWarning => AbnormalTires.Count > 0;

    /// <summary>
    /// True when the security alarm is actively sounding. Ford reports armed /
    /// disarmed / set states that are NOT a trigger; only an explicit triggered /
    /// alarm / sounding state counts.
    /// </summary>
    public bool IsAlarmTriggered
    {
        get
        {
            if (string.IsNullOrWhiteSpace(AlarmStatus)) return false;
            var s = AlarmStatus.Trim();
            return s.Contains("trigger", StringComparison.OrdinalIgnoreCase) ||
                   s.Contains("sound", StringComparison.OrdinalIgnoreCase) ||
                   s.Contains("active", StringComparison.OrdinalIgnoreCase) ||
                   s.Equals("ALARM", StringComparison.OrdinalIgnoreCase);
        }
    }
}
