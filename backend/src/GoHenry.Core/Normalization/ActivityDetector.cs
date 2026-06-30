using GoHenry.Core.Models;

namespace GoHenry.Core.Normalization;

/// <summary>The minimal prior state the detector needs (loaded from the VIN's Table row).</summary>
public sealed class PriorActivityState
{
    public bool WasActive { get; init; }
    /// <summary>The charge phase recorded on the previous successful poll.</summary>
    public ChargePhase PriorChargePhase { get; init; }
    public bool HasOpenTrip { get; init; }
    public DateTimeOffset? LastSeenActiveUtc { get; init; }
    public bool LostSignalAlreadyRaised { get; init; }
    public bool TireWarnAlreadyRaised { get; init; }
    public bool AlarmAlreadyRaised { get; init; }
}

/// <summary>The next state to persist after evaluating a poll (mirrors the inputs).</summary>
public sealed class NextActivityState
{
    public bool WasActive { get; init; }
    /// <summary>The charge phase observed on this poll, to compare against next time.</summary>
    public ChargePhase ChargePhase { get; init; }
    public bool HasOpenTrip { get; init; }
    public DateTimeOffset? LastSeenActiveUtc { get; init; }
    public bool LostSignalAlreadyRaised { get; init; }
    public bool TireWarnAlreadyRaised { get; init; }
    public bool AlarmAlreadyRaised { get; init; }
}

public sealed class ActivityResult
{
    public IReadOnlyList<string> Events { get; init; } = Array.Empty<string>();
    public required NextActivityState Next { get; init; }
}

/// <summary>
/// Pure, side-effect-free detection of trip / charge / lost-signal transitions
/// from one poll to the next. The Functions worker calls this, then fires FCM for
/// whichever events are enabled and persists <see cref="ActivityResult.Next"/>.
/// Kept deterministic and dependency-free so it is exhaustively unit-testable.
/// </summary>
public static class ActivityDetector
{
    /// <summary>
    /// How long telemetry may go without a fresh successful read before the
    /// vehicle is treated as having "lost signal". <see cref="PollerFunctions"/>
    /// only advances CapturedAt on a successful Ford read, so a gap this long
    /// means polling has been failing (vehicle offline / unreachable / re-auth).
    /// </summary>
    public static readonly TimeSpan LostSignalThreshold = TimeSpan.FromMinutes(20);

    /// <summary>
    /// True when there is a prior successful read but none within
    /// <paramref name="threshold"/> of <paramref name="nowUtc"/> — i.e. polling
    /// has gone quiet long enough to call it "lost signal". A VIN that has never
    /// been read (<paramref name="lastCapturedUtc"/> null) is NOT stale: there is
    /// no signal to lose yet.
    /// </summary>
    public static bool IsSignalStale(DateTimeOffset? lastCapturedUtc, DateTimeOffset nowUtc, TimeSpan threshold)
        => lastCapturedUtc is { } cap && nowUtc - cap >= threshold;

    /// <summary>
    /// True when a <em>successful</em> read looks frozen: the ignition is ON yet the
    /// vehicle's data has not advanced since the previous read — neither the odometer
    /// nor the Ford telemetry timestamp changed. The poll worker counts consecutive
    /// frozen reads and raises <c>signal.lost</c> once the count reaches the user's
    /// <c>LostSignalPolls</c> setting. Null/equal values count as "unchanged"; ignition
    /// OFF (or moving data) is never frozen.
    /// </summary>
    public static bool IsFeedFrozen(bool ignitionOn, double? priorOdometerKm, double? newOdometerKm,
        string? priorTelemetryTimestamp, string? newTelemetryTimestamp)
    {
        if (!ignitionOn) return false;
        var odometerUnchanged = Nullable.Equals(priorOdometerKm, newOdometerKm);
        var timestampUnchanged = string.Equals(
            priorTelemetryTimestamp?.Trim(), newTelemetryTimestamp?.Trim(), StringComparison.OrdinalIgnoreCase);
        return odometerUnchanged && timestampUnchanged;
    }

    public static ActivityResult Evaluate(PriorActivityState prior, VehicleSnapshot snap, DateTimeOffset nowUtc)
    {
        var events = new List<string>();

        var isActive = snap.IsActive;
        var hasOpenTrip = prior.HasOpenTrip;
        var lostRaised = prior.LostSignalAlreadyRaised;
        var lastSeenActive = prior.LastSeenActiveUtc;

        // --- Trip transitions (ignition edge) ---
        if (isActive && !prior.WasActive)
        {
            events.Add(NotificationEvents.TripStarted);
            hasOpenTrip = true;
            lostRaised = false;
        }
        else if (!isActive && prior.WasActive)
        {
            if (hasOpenTrip) events.Add(NotificationEvents.TripEnded);
            hasOpenTrip = false;
            lostRaised = false;
        }

        if (isActive) lastSeenActive = nowUtc;

        // Lost signal is no longer inferred here from an open-trip silence timer
        // (that missed parked vehicles and was defeated by stale "ignition ON"
        // reads). It is now driven by telemetry staleness in the poll worker:
        // CapturedAt only advances on a successful Ford read, so a long gap == lost
        // signal. See PollerFunctions.HandlePollFailureAsync + IsSignalStale.

        // --- Charge transitions (driven by Ford's charge-display status) ---
        // Fire a notification whenever the charge phase *changes* into one of the
        // three notifying states. Other/absent values raise nothing, and an
        // unchanged phase never re-spams.
        var chargePhase = snap.ChargePhase;
        if (chargePhase != prior.PriorChargePhase)
        {
            switch (chargePhase)
            {
                case ChargePhase.InProgress: events.Add(NotificationEvents.ChargeInProgress); break;
                case ChargePhase.Complete: events.Add(NotificationEvents.ChargeComplete); break;
                case ChargePhase.Error: events.Add(NotificationEvents.ChargeError); break;
            }
        }

        // --- Tire pressure warning: rising edge of "any wheel abnormal" ---
        // Fires once when a tire leaves "normal"; re-arms only after all wheels
        // return to normal, so a persistent warning doesn't spam every poll.
        var tireWarn = snap.IsTireWarning;
        var tireRaised = prior.TireWarnAlreadyRaised;
        if (tireWarn && !tireRaised)
        {
            events.Add(NotificationEvents.TirePressureWarn);
            tireRaised = true;
        }
        else if (!tireWarn)
        {
            tireRaised = false;
        }

        // --- Alarm triggered: rising edge of an actively-sounding alarm ---
        var alarm = snap.IsAlarmTriggered;
        var alarmRaised = prior.AlarmAlreadyRaised;
        if (alarm && !alarmRaised)
        {
            events.Add(NotificationEvents.AlarmTriggered);
            alarmRaised = true;
        }
        else if (!alarm)
        {
            alarmRaised = false;
        }

        return new ActivityResult
        {
            Events = events,
            Next = new NextActivityState
            {
                WasActive = isActive,
                ChargePhase = chargePhase,
                HasOpenTrip = hasOpenTrip,
                LastSeenActiveUtc = lastSeenActive,
                LostSignalAlreadyRaised = lostRaised,
                TireWarnAlreadyRaised = tireRaised,
                AlarmAlreadyRaised = alarmRaised,
            },
        };
    }
}
