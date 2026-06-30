namespace GoHenry.FordClient;

/// <summary>
/// Ford developer-app credentials and endpoints. GoHenry reuses the EXISTING Ford
/// developer application's client id / secret / slug, but runs its own OAuth
/// handshake. Bound from app settings / Key Vault references — never hard-coded.
/// </summary>
public sealed class FordSettings
{
    /// <summary>App slug identifying this Ford developer app (e.g. "primary").</summary>
    public string Slug { get; set; } = "primary";
    public string ClientId { get; set; } = "";
    public string ClientSecret { get; set; } = "";

    /// <summary>Typically https://api.vehicle.ford.com/ (B2C authorize + token).</summary>
    public string OAuthBaseUrl { get; set; } = "https://api.vehicle.ford.com/";

    /// <summary>Vehicle data base URL (garage + telemetry). FordConnect Query API
    /// lives under the fcon-query host, e.g. https://api.vehicle.ford.com/fcon-query/.</summary>
    public string ApiBaseUrl { get; set; } = "https://api.vehicle.ford.com/fcon-query/";

    /// <summary>The backend's own OAuth callback (registered in the Ford portal).</summary>
    public string RedirectUri { get; set; } = "";

    public string Scope { get; set; } = "access";
}

/// <summary>Tokens returned by an authorization-code or refresh-token exchange.</summary>
public sealed record TokenResult(string AccessToken, string RefreshToken, int ExpiresInSeconds);

/// <summary>A VIN discovered from the Ford garage.</summary>
public sealed record FordVehicle(string Vin, string? Model, int? ModelYear, string? Color, string? EngineType, string? NickName = null);
