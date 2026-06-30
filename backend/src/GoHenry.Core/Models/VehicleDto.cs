namespace GoHenry.Core.Models;

/// <summary>
/// Vehicle metadata as returned by <c>GET /api/fleet/vehicles</c>. Mirrors the
/// FleetFoot contract field-for-field so the GoHenry Android client maps cleanly.
/// </summary>
public sealed class VehicleDto
{
    public string Vin { get; set; } = "";
    public string? Model { get; set; }
    public string? Nickname { get; set; }
    public int? ModelYear { get; set; }
    public string? DisplayColor { get; set; }
    /// <summary>One of BEV / PHEV / HEV / GAS.</summary>
    public string? EngineType { get; set; }
    /// <summary>
    /// The Ford app slug that unlocks this VIN (default "primary"). Lets the
    /// client key per-slug UI (e.g. the carousel card color) to the right car.
    /// </summary>
    public string AppSlug { get; set; } = "primary";
}
