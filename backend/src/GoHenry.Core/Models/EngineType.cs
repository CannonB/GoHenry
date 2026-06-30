namespace GoHenry.Core.Models;

/// <summary>
/// Engine family for a vehicle. Drives UI affordances (which gauge to show, and
/// whether charging UI is relevant) on both the backend and the GoHenry app.
/// </summary>
public enum EngineType
{
    /// <summary>Battery electric — show State of Charge, charging UI enabled.</summary>
    BEV,
    /// <summary>Plug-in hybrid — show State of Charge, charging UI enabled.</summary>
    PHEV,
    /// <summary>Conventional hybrid — show Fuel level, charging UI hidden.</summary>
    HEV,
    /// <summary>Gas / diesel — show Fuel level, charging UI hidden.</summary>
    GAS,
}

public static class EngineTypes
{
    /// <summary>Charging UI (plug / kWh / charge alerts) only applies to BEV + PHEV.</summary>
    public static bool SupportsCharging(this EngineType e) => e is EngineType.BEV or EngineType.PHEV;

    /// <summary>HEV uses a fuel-level hero gauge; everything else uses SoC where present.</summary>
    public static bool UsesFuelHero(this EngineType e) => e is EngineType.HEV;

    public static EngineType Parse(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return EngineType.BEV;
        return raw.Trim().ToUpperInvariant() switch
        {
            "BEV" or "EV" or "ELECTRIC" or "BATTERY" => EngineType.BEV,
            "PHEV" or "PLUGIN" or "PLUG-IN" => EngineType.PHEV,
            "HEV" or "HYBRID" => EngineType.HEV,
            "GAS" or "ICE" or "DIESEL" or "PETROL" => EngineType.GAS,
            _ => EngineType.BEV,
        };
    }
}
