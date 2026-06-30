using FluentAssertions;
using GoHenry.Core.Normalization;
using Xunit;

namespace GoHenry.Tests;

public class FordRawFieldExtractorTests
{
    private static string LoadRealSample()
    {
        // Walk up from the test bin dir until we find the repo's docs/samples folder.
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var candidate = Path.Combine(dir.FullName, "docs", "samples",
                "ford-telemetry-1FT6W5L77SWG23727-2026-05-01T211407Z.json");
            if (File.Exists(candidate)) return File.ReadAllText(candidate);
            dir = dir.Parent;
        }
        throw new FileNotFoundException("Real Ford telemetry sample not found above " + AppContext.BaseDirectory);
    }

    private static Dictionary<string, string> Extract()
        => FordRawFieldExtractor.Extract(LoadRealSample())
            .ToDictionary(e => e.Name, e => e.Value);

    [Fact]
    public void Extract_returns_values_as_is_for_known_fields()
    {
        var f = Extract();

        f["SoCCharge%"].Should().Be("77.0");
        f["V12batteryStateOfCharge"].Should().Be("90.0");
        f["Odometer"].Should().Be("8612.0");
        f["ChargerStatus"].Should().Be("CONNECTED");
        f["SoCChargeDisplayStatus"].Should().Be("IN_PROGRESS");
        f["OutsideTemp"].Should().Be("22.75");
        f["Latitude"].Should().Be("47.639606");
    }

    [Fact]
    public void Extract_resolves_tire_array_by_wheel_discriminator()
    {
        var f = Extract();

        f["TireFrontLeftStatus"].Should().Be("NORMAL");
        f["TirePressureFrontRight"].Should().Be("318.0");
        f["TirePressureRearLeft"].Should().Be("309.0");
    }

    [Fact]
    public void Extract_resolves_unindexed_door_lock_to_all_doors_first()
    {
        var f = Extract();
        f["DoorLocked"].Should().Be("LOCKED");
    }

    [Fact]
    public void Extract_omits_fields_absent_for_this_engine()
    {
        var f = Extract();
        // BEV sample has no liquid-fuel level metric; the field must simply be absent.
        f.Should().NotContainKey("FuelLevel");
    }

    [Fact]
    public void Extract_surfaces_raw_ford_telemetry_path()
    {
        var byName = FordRawFieldExtractor.Extract(LoadRealSample())
            .ToDictionary(e => e.Name, e => e.Path);

        byName["SoCChargeDisplayStatus"].Should().Be("metrics.xevBatteryChargeDisplayStatus.value");
        byName["Odometer"].Should().Be("metrics.odometer.value");
    }

    [Fact]
    public void Extract_does_not_emit_metadata_only_fields()
    {
        var f = Extract();
        // Metadata fields (vin/nickName/etc.) come from VinEntity, never from the feed.
        f.Should().NotContainKey("Nickname");
        f.Should().NotContainKey("Model");
    }
}
