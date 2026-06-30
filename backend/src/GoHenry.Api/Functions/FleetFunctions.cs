using System.Text.Json;
using GoHenry.Core.Models;
using GoHenry.Storage;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Logging;

namespace GoHenry.Api.Functions;

/// <summary>
/// The "fleet" read surface the GoHenry app renders its carousel, vehicle-status,
/// detail and notification-setup screens from — served entirely from the Vehicles
/// Table (no SQL, no joins). Routes mirror the FleetFoot contract so the Android
/// client maps cleanly:
///   GET  /api/fleet/vehicles
///   GET  /api/fleet/telemetry/{vin}
///   GET  /api/fleet/telemetry/{vin}/cache
///   GET  /api/fleet/notifications/{vin}
///   POST /api/fleet/notifications/{vin}
/// </summary>
public sealed class FleetFunctions(IGoHenryStore store, ILogger<FleetFunctions> log)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    [Function("Fleet_Vehicles")]
    public async Task<IActionResult> GetVehicles(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "fleet/vehicles")] HttpRequest req,
        CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();
        var vehicles = await store.ListVehiclesAsync(userId, ct);
        return new OkObjectResult(vehicles.Select(v => v.ToVehicleDto()).ToList());
    }

    [Function("Fleet_Telemetry")]
    public async Task<IActionResult> GetTelemetry(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "fleet/telemetry/{vin}")] HttpRequest req,
        string vin, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();
        var v = await store.GetVehicleAsync(userId, vin, ct);
        return v is null ? HttpHelpers.NotFound() : new OkObjectResult(v.ToTelemetryDto());
    }

    // The detail screen reuses the same data; provided for contract completeness.
    [Function("Fleet_TelemetryCache")]
    public async Task<IActionResult> GetTelemetryCache(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "fleet/telemetry/{vin}/cache")] HttpRequest req,
        string vin, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();
        var v = await store.GetVehicleAsync(userId, vin, ct);
        return v is null ? HttpHelpers.NotFound() : new OkObjectResult(v.ToTelemetryDto());
    }

    [Function("Fleet_GetNotifications")]
    public async Task<IActionResult> GetNotifications(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "fleet/notifications/{vin}")] HttpRequest req,
        string vin, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();
        var v = await store.GetVehicleAsync(userId, vin, ct);
        return v is null ? HttpHelpers.NotFound() : new OkObjectResult(v.ToNotifyPrefsDto());
    }

    [Function("Fleet_SetNotifications")]
    public async Task<IActionResult> SetNotifications(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "fleet/notifications/{vin}")] HttpRequest req,
        string vin, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        var existing = await store.GetVehicleAsync(userId, vin, ct);
        if (existing is null) return HttpHelpers.NotFound();

        NotifyPrefsDto? prefs;
        try
        {
            prefs = await JsonSerializer.DeserializeAsync<NotifyPrefsDto>(req.Body, Json, ct);
        }
        catch (JsonException)
        {
            return HttpHelpers.BadRequest("invalid_json");
        }
        prefs ??= new NotifyPrefsDto();

        await store.SaveNotifyPrefsAsync(userId, vin, prefs, ct);
        log.LogInformation("Updated notify prefs for {User}/{Vin}", userId, vin);
        return new OkObjectResult(prefs);
    }

    [Function("Fleet_GetPollSettings")]
    public async Task<IActionResult> GetPollSettings(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "fleet/pollsettings")] HttpRequest req,
        CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();
        var meta = await store.GetOrCreateMetaAsync("pollSettings", ct);
        var cadence = PollSettingsDto.Clamp(meta.PollCadenceMinutes <= 0 ? PollSettingsDto.DefaultMinutes : meta.PollCadenceMinutes);
        var lostPolls = PollSettingsDto.ClampLostSignalPolls(meta.LostSignalPolls <= 0 ? PollSettingsDto.DefaultLostSignalPolls : meta.LostSignalPolls);
        return new OkObjectResult(new PollSettingsDto { CadenceMinutes = cadence, LostSignalPolls = lostPolls });
    }

    [Function("Fleet_SetPollSettings")]
    public async Task<IActionResult> SetPollSettings(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "fleet/pollsettings")] HttpRequest req,
        CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        PollSettingsDto? dto;
        try { dto = await JsonSerializer.DeserializeAsync<PollSettingsDto>(req.Body, Json, ct); }
        catch (JsonException) { return HttpHelpers.BadRequest("invalid_json"); }
        dto ??= new PollSettingsDto();

        var cadence = PollSettingsDto.Clamp(dto.CadenceMinutes);
        var lostPolls = PollSettingsDto.ClampLostSignalPolls(dto.LostSignalPolls);
        var meta = await store.GetOrCreateMetaAsync("pollSettings", ct);
        meta.PollCadenceMinutes = cadence;
        meta.LostSignalPolls = lostPolls;
        await store.SaveMetaAsync(meta, ct);
        log.LogInformation("Updated poll cadence to {Cadence}m, lost-signal to {Polls} polls by {User}", cadence, lostPolls, userId);
        return new OkObjectResult(new PollSettingsDto { CadenceMinutes = cadence, LostSignalPolls = lostPolls });
    }

    [Function("Fleet_GetRoadTripSettings")]
    public async Task<IActionResult> GetRoadTripSettings(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "fleet/roadtripsettings")] HttpRequest req,
        CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();
        var meta = await store.GetOrCreateMetaAsync("pollSettings", ct);
        return new OkObjectResult(RoadTripSettingsFromMeta(meta));
    }

    [Function("Fleet_SetRoadTripSettings")]
    public async Task<IActionResult> SetRoadTripSettings(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "fleet/roadtripsettings")] HttpRequest req,
        CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        RoadTripSettingsDto? dto;
        try { dto = await JsonSerializer.DeserializeAsync<RoadTripSettingsDto>(req.Body, Json, ct); }
        catch (JsonException) { return HttpHelpers.BadRequest("invalid_json"); }
        dto ??= new RoadTripSettingsDto();

        var meta = await store.GetOrCreateMetaAsync("pollSettings", ct);
        meta.RoadTripAutoStart = dto.AutoStart;
        meta.RoadTripIdleHours = RoadTripSettingsDto.ClampIdleHours(dto.IdleHours);
        meta.RoadTripMaxDays = RoadTripSettingsDto.ClampMaxDays(dto.MaxDays);
        meta.RoadTripEndOnStop = dto.EndOnStop;
        await store.SaveMetaAsync(meta, ct);
        log.LogInformation("Updated road-trip settings (autoStart={Auto}, idle={Idle}h, max={Max}d, endOnStop={Stop}) by {User}",
            meta.RoadTripAutoStart, meta.RoadTripIdleHours, meta.RoadTripMaxDays, meta.RoadTripEndOnStop, userId);
        return new OkObjectResult(RoadTripSettingsFromMeta(meta));
    }

    private static RoadTripSettingsDto RoadTripSettingsFromMeta(MetaEntity meta) => new()
    {
        AutoStart = meta.RoadTripAutoStart,
        IdleHours = RoadTripSettingsDto.ClampIdleHours(meta.RoadTripIdleHours <= 0 ? RoadTripSettingsDto.DefaultIdleHours : meta.RoadTripIdleHours),
        MaxDays = RoadTripSettingsDto.ClampMaxDays(meta.RoadTripMaxDays <= 0 ? RoadTripSettingsDto.DefaultMaxDays : meta.RoadTripMaxDays),
        EndOnStop = meta.RoadTripEndOnStop,
    };
}
