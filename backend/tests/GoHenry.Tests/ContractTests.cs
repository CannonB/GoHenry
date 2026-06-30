using System.Text.Json;
using FluentAssertions;
using GoHenry.Core.Models;
using GoHenry.FordClient;
using Xunit;

namespace GoHenry.Tests;

public class ContractTests
{
    [Fact]
    public void TelemetryDto_serializes_with_camelCase_field_names()
    {
        var dto = new TelemetryDto { Vin = "VIN1", SocPct = 50, RangeValue = 100, RangeUnit = "km" };
        var json = JsonSerializer.Serialize(dto, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        // The GoHenry app maps these exact keys from FleetFoot's contract.
        json.Should().Contain("\"vin\":\"VIN1\"");
        json.Should().Contain("\"socPct\":50");
        json.Should().Contain("\"rangeValue\":100");
        json.Should().Contain("\"rangeUnit\":\"km\"");
    }

    [Fact]
    public void NotifyPrefsDto_has_all_eleven_toggles()
    {
        var dto = new NotifyPrefsDto { Start = true, Stop = true, ChargeInProgress = true, ChargeComplete = true, ChargeError = true, LostSignal = true, TelemetryFeedLost = true, TirePressure = true, Alarm = true, RoadTripStart = true, RoadTripEnd = true };
        var json = JsonSerializer.Serialize(dto, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        foreach (var key in new[] { "start", "stop", "chargeInProgress", "chargeComplete", "chargeError", "lostSignal", "telemetryFeedLost", "tirePressure", "alarm", "roadTripStart", "roadTripEnd" })
            json.Should().Contain($"\"{key}\":true");
    }

    [Fact]
    public void PollSettingsDto_serializes_cadence_as_camelCase()
    {
        var dto = new PollSettingsDto { CadenceMinutes = 5, LostSignalPolls = 8 };
        var json = JsonSerializer.Serialize(dto, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        json.Should().Contain("\"cadenceMinutes\":5");
        json.Should().Contain("\"lostSignalPolls\":8");
    }

    [Theory]
    [InlineData(0, 1)]
    [InlineData(1, 1)]
    [InlineData(2, 2)]
    [InlineData(10, 10)]
    [InlineData(25, 10)]
    [InlineData(-3, 1)]
    public void PollSettingsDto_clamps_to_one_through_ten(int input, int expected)
    {
        PollSettingsDto.Clamp(input).Should().Be(expected);
    }

    [Theory]
    [InlineData(0, 5)]
    [InlineData(5, 5)]
    [InlineData(10, 10)]
    [InlineData(20, 20)]
    [InlineData(50, 20)]
    [InlineData(-3, 5)]
    public void PollSettingsDto_clamps_lost_signal_polls_to_five_through_twenty(int input, int expected)
    {
        PollSettingsDto.ClampLostSignalPolls(input).Should().Be(expected);
    }

    [Fact]
    public void RoadTripSettingsDto_serializes_with_camelCase_field_names()
    {
        var dto = new RoadTripSettingsDto { AutoStart = true, IdleHours = 12, MaxDays = 7, EndOnStop = true };
        var json = JsonSerializer.Serialize(dto, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        json.Should().Contain("\"autoStart\":true");
        json.Should().Contain("\"idleHours\":12");
        json.Should().Contain("\"maxDays\":7");
        json.Should().Contain("\"endOnStop\":true");
    }

    [Fact]
    public void RoadTripSettingsDto_defaults_match_recommended()
    {
        var dto = new RoadTripSettingsDto();
        dto.AutoStart.Should().BeFalse();
        dto.IdleHours.Should().Be(12);
        dto.MaxDays.Should().Be(7);
        dto.EndOnStop.Should().BeFalse();
    }

    [Theory]
    [InlineData(0, 2)]
    [InlineData(1, 2)]
    [InlineData(2, 2)]
    [InlineData(8, 8)]
    [InlineData(12, 12)]
    [InlineData(72, 12)]
    [InlineData(-5, 2)]
    public void RoadTripSettingsDto_clamps_idle_hours(int input, int expected)
    {
        RoadTripSettingsDto.ClampIdleHours(input).Should().Be(expected);
    }

    [Theory]
    [InlineData(0, 1)]
    [InlineData(1, 1)]
    [InlineData(7, 7)]
    [InlineData(30, 7)]
    [InlineData(99, 7)]
    [InlineData(-2, 1)]
    public void RoadTripSettingsDto_clamps_max_days(int input, int expected)
    {
        RoadTripSettingsDto.ClampMaxDays(input).Should().Be(expected);
    }

    [Fact]
    public void RoadTripDto_serializes_with_camelCase_and_timeline()
    {
        var dto = new RoadTripDto
        {
            Id = "abc",
            Vin = "VIN1",
            Name = "Coast trip",
            Status = "active",
            SegmentCount = 3,
            ChargeStops = 1,
            EventCount = 7,
            Timeline = new() { new RoadTripEventDto { Ts = "2026-06-27T00:00:00Z", Event = "trip.started", Detail = "Started moving", Latitude = 1.5, Longitude = -2.5 } },
        };
        var json = JsonSerializer.Serialize(dto, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        json.Should().Contain("\"id\":\"abc\"");
        json.Should().Contain("\"vin\":\"VIN1\"");
        json.Should().Contain("\"status\":\"active\"");
        json.Should().Contain("\"segmentCount\":3");
        json.Should().Contain("\"chargeStops\":1");
        json.Should().Contain("\"eventCount\":7");
        json.Should().Contain("\"timeline\":[");
        json.Should().Contain("\"event\":\"trip.started\"");
        json.Should().Contain("\"latitude\":1.5");
    }

    [Fact]
    public void TelemetryDto_exposes_active_road_trip_fields()
    {
        var dto = new TelemetryDto { Vin = "VIN1", ActiveRoadTripId = "rt1", ActiveRoadTripName = "Coast trip" };
        var json = JsonSerializer.Serialize(dto, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        json.Should().Contain("\"activeRoadTripId\":\"rt1\"");
        json.Should().Contain("\"activeRoadTripName\":\"Coast trip\"");
    }

    [Fact]
    public void ParseGarage_reads_vehicles_array()
    {
        const string json = """
        { "vehicles": [ { "vin": "ABC123", "model": "Mustang Mach-E", "modelYear": 2023, "engineType": "BEV" } ] }
        """;
        var vins = FordApiClient.ParseGarage(json);
        vins.Should().ContainSingle();
        vins[0].Vin.Should().Be("ABC123");
        vins[0].Model.Should().Be("Mustang Mach-E");
        vins[0].ModelYear.Should().Be(2023);
    }

    [Fact]
    public void ParseGarage_reads_single_root_object()
    {
        // The live FordConnect tenant returns the vehicle as a bare object
        // (no array / no "vehicles" wrapper). This was the "no vehicles yet" bug.
        const string json = """
        {"vin":"1FT6W5L77SWG23727","nickName":"Sasha","vehicleType":"2025 F-150","color":"ANTIMATTER BLUE","modelName":"F-150","modelYear":"2025","make":"Ford","engineType":"BEV"}
        """;
        var vins = FordApiClient.ParseGarage(json);
        vins.Should().ContainSingle();
        vins[0].Vin.Should().Be("1FT6W5L77SWG23727");
        vins[0].Model.Should().Be("F-150");
        vins[0].ModelYear.Should().Be(2025);
        vins[0].NickName.Should().Be("Sasha");
        vins[0].EngineType.Should().Be("BEV");
    }

    [Fact]
    public void EngineType_charging_support_is_correct()
    {
        EngineType.BEV.SupportsCharging().Should().BeTrue();
        EngineType.PHEV.SupportsCharging().Should().BeTrue();
        EngineType.HEV.SupportsCharging().Should().BeFalse();
        EngineType.HEV.UsesFuelHero().Should().BeTrue();
        EngineTypes.Parse("hybrid").Should().Be(EngineType.HEV);
    }
}
