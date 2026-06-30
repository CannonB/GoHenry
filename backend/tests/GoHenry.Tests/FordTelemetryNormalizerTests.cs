using FluentAssertions;
using GoHenry.Core.Models;
using GoHenry.Core.Normalization;
using Xunit;

namespace GoHenry.Tests;

public class FordTelemetryNormalizerTests
{
    [Fact]
    public void Normalize_reads_flat_fields()
    {
        const string json = """
        {
          "soc": 72.5, "rangeKm": 300, "odometerKm": 12345.6,
          "chargingStatus": "NotCharging", "pluggedIn": false,
          "latitude": 47.61, "longitude": -122.33,
          "ignition": "OFF", "doorLocks": "LOCKED", "oilLifePct": 88
        }
        """;
        var s = FordTelemetryNormalizer.Normalize("VIN1", EngineType.BEV, json, DateTimeOffset.UnixEpoch);

        s.SocPct.Should().Be(72.5);
        s.RangeKm.Should().Be(300);
        s.OdometerKm.Should().BeApproximately(12345.6, 0.001);
        s.PluggedIn.Should().BeFalse();
        s.Latitude.Should().Be(47.61);
        s.Ignition.Should().Be("OFF");
        s.IsActive.Should().BeFalse();
    }

    [Fact]
    public void Normalize_finds_nested_value_objects()
    {
        const string json = """
        { "vehicleStatus": { "battery": { "soc": { "value": 55 } }, "ignition": "ON" } }
        """;
        var s = FordTelemetryNormalizer.Normalize("VIN1", EngineType.BEV, json, DateTimeOffset.UtcNow);
        s.SocPct.Should().Be(55);
        s.IsActive.Should().BeTrue();
    }

    [Fact]
    public void Normalize_tolerates_empty_payload()
    {
        var s = FordTelemetryNormalizer.Normalize("VIN1", EngineType.HEV, "", DateTimeOffset.UtcNow);
        s.SocPct.Should().BeNull();
        s.EngineType.Should().Be(EngineType.HEV);
    }

    [Fact]
    public void ToDto_sets_units_only_when_value_present()
    {
        var s = FordTelemetryNormalizer.Normalize("VIN1", EngineType.BEV,
            """{ "rangeKm": 100 }""", DateTimeOffset.UtcNow);
        var dto = FordTelemetryNormalizer.ToDto(s);
        dto.RangeUnit.Should().Be("km");
        dto.OdometerUnit.Should().BeNull();
        dto.Vin.Should().Be("VIN1");
    }
}
