using Azure;
using Azure.Data.Tables;
using GoHenry.Core.Models;

namespace GoHenry.Storage;

/// <summary>
/// Azure Table Storage implementation of <see cref="IGoHenryStore"/>. A single
/// Storage account holds a handful of tables; this type owns the table clients
/// and the (de)serialization between entities and domain DTOs. No SQL anywhere.
/// </summary>
public sealed class TableGoHenryStore : IGoHenryStore
{
    public const string VehiclesTable = "Vehicles";
    public const string AccountsTable = "FordAccounts";
    public const string StatesTable = "OAuthStates";
    public const string InstallsTable = "Installs";
    public const string TripsTable = "TripHistory";
    public const string ChargesTable = "ChargeHistory";
    public const string RoadTripsTable = "RoadTrips";
    public const string MetaTable = "GoHenryMeta";

    private readonly TableServiceClient _service;
    private readonly TableClient _vehicles;
    private readonly TableClient _accounts;
    private readonly TableClient _states;
    private readonly TableClient _installs;
    private readonly TableClient _trips;
    private readonly TableClient _charges;
    private readonly TableClient _roadtrips;
    private readonly TableClient _meta;

    public TableGoHenryStore(TableServiceClient service)
    {
        _service = service;
        _vehicles = service.GetTableClient(VehiclesTable);
        _accounts = service.GetTableClient(AccountsTable);
        _states = service.GetTableClient(StatesTable);
        _installs = service.GetTableClient(InstallsTable);
        _trips = service.GetTableClient(TripsTable);
        _charges = service.GetTableClient(ChargesTable);
        _roadtrips = service.GetTableClient(RoadTripsTable);
        _meta = service.GetTableClient(MetaTable);
    }

    public async Task InitializeAsync(CancellationToken ct = default)
    {
        foreach (var t in new[] { _vehicles, _accounts, _states, _installs, _trips, _charges, _roadtrips, _meta })
            await t.CreateIfNotExistsAsync(ct);
    }

    // ---- Vehicles ----
    public async Task<IReadOnlyList<VinEntity>> ListVehiclesAsync(string userId, CancellationToken ct = default)
    {
        var list = new List<VinEntity>();
        await foreach (var e in _vehicles.QueryAsync<VinEntity>(x => x.PartitionKey == userId, cancellationToken: ct))
            list.Add(e);
        return list;
    }

    public async Task<IReadOnlyList<VinEntity>> ListAllVehiclesAsync(CancellationToken ct = default)
    {
        var list = new List<VinEntity>();
        await foreach (var e in _vehicles.QueryAsync<VinEntity>(cancellationToken: ct))
            list.Add(e);
        return list;
    }

    public async Task<VinEntity?> GetVehicleAsync(string userId, string vin, CancellationToken ct = default)
    {
        try
        {
            var r = await _vehicles.GetEntityAsync<VinEntity>(userId, vin, cancellationToken: ct);
            return r.Value;
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            return null;
        }
    }

    public Task UpsertVehicleAsync(VinEntity entity, CancellationToken ct = default) =>
        _vehicles.UpsertEntityAsync(entity, TableUpdateMode.Merge, ct);

    public async Task UpdateSnapshotAsync(string userId, string vin, VehicleSnapshot snap, CancellationToken ct = default)
    {
        var e = await GetVehicleAsync(userId, vin, ct) ?? new VinEntity { PartitionKey = userId, RowKey = vin };
        e.EngineType = snap.EngineType.ToString();
        e.SnapSocPct = snap.SocPct;
        e.SnapFuelLevelPct = snap.FuelLevelPct;
        e.SnapRangeKm = snap.RangeKm;
        e.SnapOdometerKm = snap.OdometerKm;
        e.SnapChargingStatus = snap.ChargingStatus;
        e.SnapPluggedIn = snap.PluggedIn;
        e.SnapLatitude = snap.Latitude;
        e.SnapLongitude = snap.Longitude;
        e.SnapIgnition = snap.Ignition;
        e.SnapGearLever = snap.GearLever;
        e.SnapDoorLocks = snap.DoorLocks;
        e.SnapAlarmStatus = snap.AlarmStatus;
        e.SnapTirePressureStatus = snap.TirePressureStatus;
        e.SnapOilLifePct = snap.OilLifePct;
        e.SnapOutsideTempC = snap.OutsideTempC;
        e.SnapInteriorTempC = snap.InteriorTempC;
        e.CapturedAt = snap.CapturedAt;
        e.LastPolledAt = snap.CapturedAt;
        e.LastWasActive = snap.IsActive;
        await _vehicles.UpsertEntityAsync(e, TableUpdateMode.Merge, ct);
    }

    public async Task SaveNotifyPrefsAsync(string userId, string vin, NotifyPrefsDto p, CancellationToken ct = default)
    {
        var e = await GetVehicleAsync(userId, vin, ct) ?? new VinEntity { PartitionKey = userId, RowKey = vin };
        e.NotifyStart = p.Start;
        e.NotifyStop = p.Stop;
        e.NotifyChargeInProgress = p.ChargeInProgress;
        e.NotifyChargeComplete = p.ChargeComplete;
        e.NotifyChargeError = p.ChargeError;
        e.NotifyLostSignal = p.LostSignal;
        e.NotifyTelemetryFeedLost = p.TelemetryFeedLost;
        e.NotifyTirePressure = p.TirePressure;
        e.NotifyAlarm = p.Alarm;
        e.NotifyRoadTripStart = p.RoadTripStart;
        e.NotifyRoadTripEnd = p.RoadTripEnd;
        await _vehicles.UpsertEntityAsync(e, TableUpdateMode.Merge, ct);
    }

    // ---- Ford accounts ----
    public async Task<IReadOnlyList<FordAccountEntity>> ListAccountsAsync(string userId, CancellationToken ct = default)
    {
        var list = new List<FordAccountEntity>();
        await foreach (var e in _accounts.QueryAsync<FordAccountEntity>(x => x.PartitionKey == userId, cancellationToken: ct))
            list.Add(e);
        return list;
    }

    public async Task<FordAccountEntity?> GetAccountAsync(string userId, string slug, CancellationToken ct = default)
    {
        try
        {
            var r = await _accounts.GetEntityAsync<FordAccountEntity>(userId, slug, cancellationToken: ct);
            return r.Value;
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            return null;
        }
    }

    public Task UpsertAccountAsync(FordAccountEntity entity, CancellationToken ct = default) =>
        _accounts.UpsertEntityAsync(entity, TableUpdateMode.Merge, ct);

    // ---- OAuth state ----
    public Task PutStateAsync(OAuthStateEntity entity, CancellationToken ct = default) =>
        _states.UpsertEntityAsync(entity, TableUpdateMode.Replace, ct);

    public async Task<OAuthStateEntity?> TakeStateAsync(string state, CancellationToken ct = default)
    {
        try
        {
            var r = await _states.GetEntityAsync<OAuthStateEntity>("state", state, cancellationToken: ct);
            await _states.DeleteEntityAsync("state", state, ETag.All, ct); // single-use
            return r.Value;
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            return null;
        }
    }

    // ---- Installs ----
    public Task UpsertInstallAsync(InstallEntity entity, CancellationToken ct = default) =>
        _installs.UpsertEntityAsync(entity, TableUpdateMode.Replace, ct);

    public async Task<IReadOnlyList<InstallEntity>> ListInstallsAsync(string userId, CancellationToken ct = default)
    {
        var list = new List<InstallEntity>();
        await foreach (var e in _installs.QueryAsync<InstallEntity>(x => x.PartitionKey == userId, cancellationToken: ct))
            list.Add(e);
        return list;
    }

    // ---- History ----
    public Task AppendTripAsync(TripHistoryEntity entity, CancellationToken ct = default) =>
        _trips.UpsertEntityAsync(entity, TableUpdateMode.Replace, ct);

    public Task AppendChargeAsync(ChargeHistoryEntity entity, CancellationToken ct = default) =>
        _charges.UpsertEntityAsync(entity, TableUpdateMode.Replace, ct);

    /// <summary>A RowKey that sorts most-recent-first (Table Storage sorts ascending).</summary>
    public static string ReverseTicksRowKey(DateTimeOffset at) =>
        (DateTimeOffset.MaxValue.Ticks - at.UtcTicks).ToString("D19");

    // ---- Road trips ----
    public Task UpsertRoadTripAsync(RoadTripEntity entity, CancellationToken ct = default) =>
        _roadtrips.UpsertEntityAsync(entity, TableUpdateMode.Replace, ct);

    public async Task<IReadOnlyList<RoadTripEntity>> ListRoadTripsAsync(string vin, int take = 50, CancellationToken ct = default)
    {
        var list = new List<RoadTripEntity>();
        // RowKey is reverse-ticks, so partition order (ascending) is already newest-first.
        await foreach (var e in _roadtrips.QueryAsync<RoadTripEntity>(x => x.PartitionKey == vin, cancellationToken: ct))
        {
            list.Add(e);
            if (list.Count >= take) break;
        }
        return list;
    }

    public async Task<RoadTripEntity?> GetRoadTripAsync(string vin, string rowKey, CancellationToken ct = default)
    {
        try
        {
            var r = await _roadtrips.GetEntityAsync<RoadTripEntity>(vin, rowKey, cancellationToken: ct);
            return r.Value;
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            return null;
        }
    }

    public async Task<RoadTripEntity?> GetRoadTripByIdAsync(string vin, string id, CancellationToken ct = default)
    {
        await foreach (var e in _roadtrips.QueryAsync<RoadTripEntity>(x => x.PartitionKey == vin && x.Id == id, cancellationToken: ct))
            return e;
        return null;
    }

    public async Task DeleteRoadTripAsync(string vin, string rowKey, CancellationToken ct = default)
    {
        // Deleting the single row removes the trip and its embedded timeline (no SQL).
        try { await _roadtrips.DeleteEntityAsync(vin, rowKey, ETag.All, ct); }
        catch (RequestFailedException ex) when (ex.Status == 404) { /* already gone */ }
    }

    // ---- Meta ----
    public async Task<MetaEntity> GetOrCreateMetaAsync(string rowKey, CancellationToken ct = default)
    {
        try
        {
            var r = await _meta.GetEntityAsync<MetaEntity>("_meta", rowKey, cancellationToken: ct);
            return r.Value;
        }
        catch (RequestFailedException ex) when (ex.Status == 404)
        {
            var fresh = new MetaEntity { PartitionKey = "_meta", RowKey = rowKey };
            await _meta.UpsertEntityAsync(fresh, TableUpdateMode.Merge, ct);
            return fresh;
        }
    }

    public Task SaveMetaAsync(MetaEntity entity, CancellationToken ct = default) =>
        _meta.UpsertEntityAsync(entity, TableUpdateMode.Merge, ct);
}
