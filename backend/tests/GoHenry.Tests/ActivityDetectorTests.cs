using FluentAssertions;
using GoHenry.Core.Models;
using GoHenry.Core.Normalization;
using Xunit;

namespace GoHenry.Tests;

public class ActivityDetectorTests
{
    private static VehicleSnapshot Snap(string ignition = "OFF", string? chargeDisplay = null, bool? plugged = null,
        string? tireFrontLeft = null, string? alarm = null) => new()
    {
        Vin = "VIN1",
        EngineType = EngineType.BEV,
        Ignition = ignition,
        ChargeDisplayStatus = chargeDisplay,
        PluggedIn = plugged,
        TireFrontLeftStatus = tireFrontLeft,
        AlarmStatus = alarm,
    };

    private static PriorActivityState Prior(bool active = false, ChargePhase chargePhase = ChargePhase.Other, bool openTrip = false,
        DateTimeOffset? lastActive = null, bool lostRaised = false, bool tireRaised = false, bool alarmRaised = false) => new()
    {
        WasActive = active,
        PriorChargePhase = chargePhase,
        HasOpenTrip = openTrip,
        LastSeenActiveUtc = lastActive,
        LostSignalAlreadyRaised = lostRaised,
        TireWarnAlreadyRaised = tireRaised,
        AlarmAlreadyRaised = alarmRaised,
    };

    [Fact]
    public void Ignition_on_raises_trip_started_and_opens_trip()
    {
        var r = ActivityDetector.Evaluate(Prior(active: false), Snap(ignition: "ON"), DateTimeOffset.UtcNow);
        r.Events.Should().ContainSingle().Which.Should().Be(NotificationEvents.TripStarted);
        r.Next.HasOpenTrip.Should().BeTrue();
        r.Next.WasActive.Should().BeTrue();
    }

    [Fact]
    public void Ignition_off_after_trip_raises_trip_ended_and_closes_trip()
    {
        var r = ActivityDetector.Evaluate(Prior(active: true, openTrip: true), Snap(ignition: "OFF"), DateTimeOffset.UtcNow);
        r.Events.Should().Contain(NotificationEvents.TripEnded);
        r.Next.HasOpenTrip.Should().BeFalse();
    }

    [Fact]
    public void No_event_when_state_unchanged()
    {
        var r = ActivityDetector.Evaluate(Prior(active: false), Snap(ignition: "OFF"), DateTimeOffset.UtcNow);
        r.Events.Should().BeEmpty();
    }

    [Fact]
    public void Charge_display_in_progress_then_complete_emits_progress_then_complete()
    {
        var now = DateTimeOffset.UtcNow;
        // Phase changes Other → IN_PROGRESS → charge.in_progress.
        var start = ActivityDetector.Evaluate(Prior(chargePhase: ChargePhase.Other), Snap(chargeDisplay: "IN_PROGRESS", plugged: true), now);
        start.Events.Should().Contain(NotificationEvents.ChargeInProgress);
        start.Next.ChargePhase.Should().Be(ChargePhase.InProgress);

        // Phase changes IN_PROGRESS → COMPLETED → charge.complete.
        var done = ActivityDetector.Evaluate(Prior(chargePhase: ChargePhase.InProgress), Snap(chargeDisplay: "COMPLETED", plugged: true), now);
        done.Events.Should().Contain(NotificationEvents.ChargeComplete);
        done.Events.Should().NotContain(NotificationEvents.ChargeError);
    }

    [Fact]
    public void Charge_display_error_emits_error_not_complete()
    {
        var fault = ActivityDetector.Evaluate(Prior(chargePhase: ChargePhase.InProgress), Snap(chargeDisplay: "EVSE_ERROR"), DateTimeOffset.UtcNow);
        fault.Events.Should().Contain(NotificationEvents.ChargeError);
        fault.Events.Should().NotContain(NotificationEvents.ChargeComplete);
    }

    [Fact]
    public void Charge_display_unchanged_does_not_re_emit()
    {
        // Same phase across two polls → no repeat notification.
        var r = ActivityDetector.Evaluate(Prior(chargePhase: ChargePhase.InProgress), Snap(chargeDisplay: "IN_PROGRESS"), DateTimeOffset.UtcNow);
        r.Events.Should().NotContain(NotificationEvents.ChargeInProgress);
    }

    [Fact]
    public void Charge_display_other_value_emits_nothing()
    {
        // Non-notifying values (e.g. plugged-but-idle) raise no charge event.
        var r = ActivityDetector.Evaluate(Prior(chargePhase: ChargePhase.Other), Snap(chargeDisplay: "NOT_READY"), DateTimeOffset.UtcNow);
        r.Events.Should().NotContain(NotificationEvents.ChargeInProgress);
        r.Events.Should().NotContain(NotificationEvents.ChargeComplete);
        r.Events.Should().NotContain(NotificationEvents.ChargeError);
        r.Next.ChargePhase.Should().Be(ChargePhase.Other);
    }

    [Fact]
    public void Signal_staleness_is_detected_only_after_threshold_with_a_prior_read()
    {
        var now = DateTimeOffset.UtcNow;
        var stale = now - ActivityDetector.LostSignalThreshold - TimeSpan.FromMinutes(1);
        var fresh = now - TimeSpan.FromMinutes(1);

        // A read older than the window is stale; a recent read is not.
        ActivityDetector.IsSignalStale(stale, now, ActivityDetector.LostSignalThreshold).Should().BeTrue();
        ActivityDetector.IsSignalStale(fresh, now, ActivityDetector.LostSignalThreshold).Should().BeFalse();
        // Never read yet → no signal to lose, so not stale.
        ActivityDetector.IsSignalStale(null, now, ActivityDetector.LostSignalThreshold).Should().BeFalse();
    }

    [Fact]
    public void Feed_is_frozen_only_when_ignition_on_and_neither_odometer_nor_timestamp_changed()
    {
        // Ignition ON, both odometer and timestamp unchanged → frozen.
        ActivityDetector.IsFeedFrozen(true, 100.0, 100.0, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
            .Should().BeTrue();

        // Odometer advanced → not frozen.
        ActivityDetector.IsFeedFrozen(true, 100.0, 100.5, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
            .Should().BeFalse();

        // Telemetry timestamp advanced → not frozen.
        ActivityDetector.IsFeedFrozen(true, 100.0, 100.0, "2024-01-01T00:00:00Z", "2024-01-01T00:05:00Z")
            .Should().BeFalse();

        // Ignition OFF is never frozen, even if nothing changed.
        ActivityDetector.IsFeedFrozen(false, 100.0, 100.0, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z")
            .Should().BeFalse();

        // First successful read: prior timestamp null, new present → counts as changed.
        ActivityDetector.IsFeedFrozen(true, null, 100.0, null, "2024-01-01T00:00:00Z")
            .Should().BeFalse();
    }

    [Fact]
    public void Evaluate_no_longer_emits_signal_lost()
    {
        // Lost signal is now driven by poll-failure staleness, not the detector.
        var now = DateTimeOffset.UtcNow;
        var stale = now - ActivityDetector.LostSignalThreshold - TimeSpan.FromMinutes(1);
        var prior = Prior(active: false, openTrip: true, lastActive: stale, lostRaised: false);

        var r = ActivityDetector.Evaluate(prior, Snap(ignition: "OFF"), now);
        r.Events.Should().NotContain(NotificationEvents.SignalLost);
    }
    [Fact]
    public void Tire_leaving_normal_raises_warning_once_and_rearms_when_normal()
    {
        // Rising edge: a wheel goes abnormal → one warning.
        var first = ActivityDetector.Evaluate(Prior(tireRaised: false), Snap(tireFrontLeft: "LOW"), DateTimeOffset.UtcNow);
        first.Events.Should().Contain(NotificationEvents.TirePressureWarn);
        first.Next.TireWarnAlreadyRaised.Should().BeTrue();

        // Still abnormal next poll → no repeat.
        var second = ActivityDetector.Evaluate(Prior(tireRaised: true), Snap(tireFrontLeft: "LOW"), DateTimeOffset.UtcNow);
        second.Events.Should().NotContain(NotificationEvents.TirePressureWarn);

        // Back to normal re-arms the guard.
        var cleared = ActivityDetector.Evaluate(Prior(tireRaised: true), Snap(tireFrontLeft: "NORMAL"), DateTimeOffset.UtcNow);
        cleared.Next.TireWarnAlreadyRaised.Should().BeFalse();
    }

    [Fact]
    public void Alarm_triggered_raises_once_and_rearms_when_cleared()
    {
        var first = ActivityDetector.Evaluate(Prior(alarmRaised: false), Snap(alarm: "TRIGGERED"), DateTimeOffset.UtcNow);
        first.Events.Should().Contain(NotificationEvents.AlarmTriggered);
        first.Next.AlarmAlreadyRaised.Should().BeTrue();

        var second = ActivityDetector.Evaluate(Prior(alarmRaised: true), Snap(alarm: "TRIGGERED"), DateTimeOffset.UtcNow);
        second.Events.Should().NotContain(NotificationEvents.AlarmTriggered);

        var cleared = ActivityDetector.Evaluate(Prior(alarmRaised: true), Snap(alarm: "ARMED"), DateTimeOffset.UtcNow);
        cleared.Next.AlarmAlreadyRaised.Should().BeFalse();
    }
}

internal static class TestStateExtensions
{
    public static PriorActivityState ToPrior(this NextActivityState n) => new()
    {
        WasActive = n.WasActive,
        PriorChargePhase = n.ChargePhase,
        HasOpenTrip = n.HasOpenTrip,
        LastSeenActiveUtc = n.LastSeenActiveUtc,
        LostSignalAlreadyRaised = n.LostSignalAlreadyRaised,
        TireWarnAlreadyRaised = n.TireWarnAlreadyRaised,
        AlarmAlreadyRaised = n.AlarmAlreadyRaised,
    };
}
