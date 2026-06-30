using GoHenry.Core.Models;

namespace GoHenry.Storage;

/// <summary>
/// The one and only datastore abstraction for GoHenry. Everything the backend
/// persists lives in Azure Table Storage behind this interface — there is no SQL.
/// </summary>
public interface IGoHenryStore
{
    Task InitializeAsync(CancellationToken ct = default);

    // Vehicles
    Task<IReadOnlyList<VinEntity>> ListVehiclesAsync(string userId, CancellationToken ct = default);
    Task<IReadOnlyList<VinEntity>> ListAllVehiclesAsync(CancellationToken ct = default);
    Task<VinEntity?> GetVehicleAsync(string userId, string vin, CancellationToken ct = default);
    Task UpsertVehicleAsync(VinEntity entity, CancellationToken ct = default);
    Task UpdateSnapshotAsync(string userId, string vin, VehicleSnapshot snap, CancellationToken ct = default);
    Task SaveNotifyPrefsAsync(string userId, string vin, NotifyPrefsDto prefs, CancellationToken ct = default);

    // Ford accounts
    Task<IReadOnlyList<FordAccountEntity>> ListAccountsAsync(string userId, CancellationToken ct = default);
    Task<FordAccountEntity?> GetAccountAsync(string userId, string slug, CancellationToken ct = default);
    Task UpsertAccountAsync(FordAccountEntity entity, CancellationToken ct = default);

    // OAuth state
    Task PutStateAsync(OAuthStateEntity entity, CancellationToken ct = default);
    Task<OAuthStateEntity?> TakeStateAsync(string state, CancellationToken ct = default);

    // Installs
    Task UpsertInstallAsync(InstallEntity entity, CancellationToken ct = default);
    Task<IReadOnlyList<InstallEntity>> ListInstallsAsync(string userId, CancellationToken ct = default);

    // History
    Task AppendTripAsync(TripHistoryEntity entity, CancellationToken ct = default);
    Task AppendChargeAsync(ChargeHistoryEntity entity, CancellationToken ct = default);

    // Road trips
    Task UpsertRoadTripAsync(RoadTripEntity entity, CancellationToken ct = default);
    Task<IReadOnlyList<RoadTripEntity>> ListRoadTripsAsync(string vin, int take = 50, CancellationToken ct = default);
    Task<RoadTripEntity?> GetRoadTripAsync(string vin, string rowKey, CancellationToken ct = default);
    Task<RoadTripEntity?> GetRoadTripByIdAsync(string vin, string id, CancellationToken ct = default);
    Task DeleteRoadTripAsync(string vin, string rowKey, CancellationToken ct = default);

    // Meta
    Task<MetaEntity> GetOrCreateMetaAsync(string rowKey, CancellationToken ct = default);
    Task SaveMetaAsync(MetaEntity entity, CancellationToken ct = default);
}
