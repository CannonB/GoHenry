using System.Text.Json;
using GoHenry.Core.Models;
using GoHenry.Storage;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Logging;

namespace GoHenry.Api.Functions;

/// <summary>
/// Road-trip read/write surface (Phase 1). A road trip is a named, durable journey
/// that groups multiple individual trips and the notification events within its
/// open window. State lives entirely in the <c>RoadTrips</c> Table plus an
/// active-trip pointer denormalized onto the VIN row — no SQL. Because the server
/// is authoritative, the app rebuilds full history (and any in-progress trip) after
/// a reinstall.
///   POST /api/fleet/roadtrips/{vin}/start
///   POST /api/fleet/roadtrips/{vin}/stop
///   GET  /api/fleet/roadtrips/{vin}
///   GET  /api/fleet/roadtrips/{vin}/{id}
///   POST /api/fleet/roadtrips/{vin}/{id}/rename
///   DELETE /api/fleet/roadtrips/{vin}/{id}
/// </summary>
public sealed class RoadTripFunctions(IGoHenryStore store, ILogger<RoadTripFunctions> log)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    [Function("RoadTrip_Start")]
    public async Task<IActionResult> Start(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "fleet/roadtrips/{vin}/start")] HttpRequest req,
        string vin, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        var vehicle = await store.GetVehicleAsync(userId, vin, ct);
        if (vehicle is null) return HttpHelpers.NotFound();
        if (!string.IsNullOrEmpty(vehicle.ActiveRoadTripId)) return HttpHelpers.Conflict("road_trip_already_active");

        RoadTripStartRequest? body = null;
        try { body = await JsonSerializer.DeserializeAsync<RoadTripStartRequest>(req.Body, Json, ct); }
        catch (JsonException) { /* name is optional — ignore a bad/empty body */ }

        var now = DateTimeOffset.UtcNow;
        var name = string.IsNullOrWhiteSpace(body?.Name)
            ? $"Road trip · {now.UtcDateTime:MMM d}"
            : body!.Name!.Trim();

        var trip = new RoadTripEntity
        {
            PartitionKey = vin,
            RowKey = TableGoHenryStore.ReverseTicksRowKey(now),
            Id = Guid.NewGuid().ToString("n"),
            UserId = userId,
            Name = name,
            Status = "active",
            StartedAt = now,
            StartLatitude = vehicle.SnapLatitude,
            StartLongitude = vehicle.SnapLongitude,
            StartOdometerKm = vehicle.SnapOdometerKm,
            StartMethod = "manual",
        };
        await store.UpsertRoadTripAsync(trip, ct);

        vehicle.ActiveRoadTripId = trip.Id;
        vehicle.ActiveRoadTripName = trip.Name;
        vehicle.ActiveRoadTripStartedAt = now;
        vehicle.ActiveRoadTripLastEventAt = now;
        await store.UpsertVehicleAsync(vehicle, ct);

        log.LogInformation("Road trip started for {Vin}: {Id}", vin, trip.Id);
        return new OkObjectResult(trip.ToRoadTripDto());
    }

    [Function("RoadTrip_Stop")]
    public async Task<IActionResult> Stop(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "fleet/roadtrips/{vin}/stop")] HttpRequest req,
        string vin, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        var vehicle = await store.GetVehicleAsync(userId, vin, ct);
        if (vehicle is null) return HttpHelpers.NotFound();
        if (string.IsNullOrEmpty(vehicle.ActiveRoadTripId)) return HttpHelpers.NotFound("no_active_road_trip");

        var trip = await store.GetRoadTripByIdAsync(vin, vehicle.ActiveRoadTripId, ct);
        if (trip is null)
        {
            // Dangling pointer — clear it so the car isn't stuck "on a trip".
            ClearActiveTrip(vehicle);
            await store.UpsertVehicleAsync(vehicle, ct);
            return HttpHelpers.NotFound("no_active_road_trip");
        }

        var now = DateTimeOffset.UtcNow;
        trip.Status = "ended";
        // End the trip at the last real activity (last timeline event) rather than the
        // moment "stop" was pressed, which may be long after the car actually parked.
        trip.EndedAt = StoreMappings.LastTimelineTimeUtc(trip.TimelineJson) ?? now;
        trip.EndLatitude = vehicle.SnapLatitude;
        trip.EndLongitude = vehicle.SnapLongitude;
        trip.EndOdometerKm = vehicle.SnapOdometerKm;
        if (trip.StartOdometerKm is { } start && vehicle.SnapOdometerKm is { } end && end >= start)
            trip.DistanceKm = end - start;
        trip.EndReason = "manual";
        await store.UpsertRoadTripAsync(trip, ct);

        ClearActiveTrip(vehicle);
        await store.UpsertVehicleAsync(vehicle, ct);

        log.LogInformation("Road trip stopped for {Vin}: {Id}", vin, trip.Id);
        return new OkObjectResult(trip.ToRoadTripDto());
    }

    [Function("RoadTrip_List")]
    public async Task<IActionResult> List(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "fleet/roadtrips/{vin}")] HttpRequest req,
        string vin, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();
        // Ownership: confirm the caller owns this VIN before exposing its trips.
        if (await store.GetVehicleAsync(userId, vin, ct) is null) return HttpHelpers.NotFound();

        var take = 50;
        if (int.TryParse(req.Query["take"], out var t)) take = Math.Clamp(t, 1, 200);

        var trips = await store.ListRoadTripsAsync(vin, take, ct);
        return new OkObjectResult(trips.Select(x => x.ToRoadTripDto()).ToList());
    }

    [Function("RoadTrip_Detail")]
    public async Task<IActionResult> Detail(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "fleet/roadtrips/{vin}/{id}")] HttpRequest req,
        string vin, string id, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();
        if (await store.GetVehicleAsync(userId, vin, ct) is null) return HttpHelpers.NotFound();

        var trip = await store.GetRoadTripByIdAsync(vin, id, ct);
        return trip is null ? HttpHelpers.NotFound() : new OkObjectResult(trip.ToRoadTripDto(includeTimeline: true));
    }

    [Function("RoadTrip_Rename")]
    public async Task<IActionResult> Rename(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "fleet/roadtrips/{vin}/{id}/rename")] HttpRequest req,
        string vin, string id, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        var vehicle = await store.GetVehicleAsync(userId, vin, ct);
        if (vehicle is null) return HttpHelpers.NotFound();

        RoadTripStartRequest? body;
        try { body = await JsonSerializer.DeserializeAsync<RoadTripStartRequest>(req.Body, Json, ct); }
        catch (JsonException) { return HttpHelpers.BadRequest("invalid_json"); }
        var name = body?.Name?.Trim();
        if (string.IsNullOrWhiteSpace(name)) return HttpHelpers.BadRequest("name_required");

        var trip = await store.GetRoadTripByIdAsync(vin, id, ct);
        if (trip is null) return HttpHelpers.NotFound();

        trip.Name = name;
        await store.UpsertRoadTripAsync(trip, ct);

        // Keep the denormalized VIN pointer in sync when renaming the open trip.
        if (vehicle.ActiveRoadTripId == trip.Id)
        {
            vehicle.ActiveRoadTripName = name;
            await store.UpsertVehicleAsync(vehicle, ct);
        }

        log.LogInformation("Road trip renamed for {Vin}: {Id} -> {Name}", vin, trip.Id, name);
        return new OkObjectResult(trip.ToRoadTripDto());
    }

    [Function("RoadTrip_Delete")]
    public async Task<IActionResult> Delete(
        [HttpTrigger(AuthorizationLevel.Function, "delete", Route = "fleet/roadtrips/{vin}/{id}")] HttpRequest req,
        string vin, string id, CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        var vehicle = await store.GetVehicleAsync(userId, vin, ct);
        if (vehicle is null) return HttpHelpers.NotFound();

        var trip = await store.GetRoadTripByIdAsync(vin, id, ct);
        if (trip is null) return HttpHelpers.NotFound();

        await store.DeleteRoadTripAsync(vin, trip.RowKey, ct);

        // If the deleted trip was the open one, clear the denormalized VIN pointer so
        // the car isn't left "on a trip" that no longer exists.
        if (vehicle.ActiveRoadTripId == trip.Id)
        {
            ClearActiveTrip(vehicle);
            await store.UpsertVehicleAsync(vehicle, ct);
        }

        log.LogInformation("Road trip deleted for {Vin}: {Id}", vin, trip.Id);
        return new OkResult();
    }

    private static void ClearActiveTrip(VinEntity vehicle)
    {
        // Empty strings (not null) so the VIN row's Merge upsert actually clears them.
        vehicle.ActiveRoadTripId = "";
        vehicle.ActiveRoadTripName = "";
    }
}
