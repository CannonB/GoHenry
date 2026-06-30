using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace GoHenry.FordClient;

/// <summary>
/// Typed HTTP client for the Ford developer API: builds the authorize URL, swaps
/// codes/refresh-tokens for access tokens, lists the garage, and fetches a
/// vehicle's current status. Transient-fault handling (retry) is layered on by
/// the Polly handler registered in <c>Program.cs</c> via <c>AddHttpClient</c>.
/// </summary>
public interface IFordApiClient
{
    string Slug { get; }
    string BuildAuthorizeUrl(string state);
    Task<TokenResult> ExchangeCodeAsync(string code, CancellationToken ct);
    Task<TokenResult> RefreshAsync(string refreshToken, CancellationToken ct);
    Task<IReadOnlyList<FordVehicle>> ListVehiclesAsync(string accessToken, CancellationToken ct);
    Task<string> GetVehicleStatusJsonAsync(string accessToken, string vin, CancellationToken ct);
}

public sealed class FordApiClient : IFordApiClient
{
    // Ford's B2C tenant + FordConnect path fragments. These MUST match what the
    // FleetFoot backend uses (QoastQurrent.FordClient.FordApiClient) — Ford does
    // NOT expose generic /authorize or /token endpoints.
    //   AUTH:  {OAuthBaseUrl}/fcon-public/v1/auth/init
    //   TOKEN: {OAuthBaseUrl}/dah2vb2cprod.onmicrosoft.com/oauth2/v2.0/token?p=B2C_1A_FCON_AUTHORIZE
    //   DATA:  {ApiBaseUrl}/v1/garage  and  {ApiBaseUrl}/v1/telemetry  (ApiBaseUrl = .../fcon-query/)
    private const string PolicyName = "B2C_1A_FCON_AUTHORIZE";
    private const string AuthorizePath = "fcon-public/v1/auth/init";
    private const string TokenPath = "dah2vb2cprod.onmicrosoft.com/oauth2/v2.0/token";

    private readonly HttpClient _http;
    private readonly FordSettings _settings;
    private readonly ILogger<FordApiClient> _log;

    public FordApiClient(HttpClient http, FordSettings settings, ILogger<FordApiClient> log)
    {
        _http = http;
        _settings = settings;
        _log = log;
    }

    public string Slug => _settings.Slug;

    public string BuildAuthorizeUrl(string state)
    {
        // Ford's /fcon-public/v1/auth/init expects a SIMPLE query string with the
        // redirect_uri RAW (un-encoded). URL-encoding it returns 404, and Ford
        // rejects requests that supply response_type / scope / p=... (its B2C
        // wrapper injects those server-side). Mirror FleetFoot exactly.
        var b = _settings.OAuthBaseUrl.TrimEnd('/');
        return $"{b}/{AuthorizePath}" +
               $"?client_id={_settings.ClientId}" +
               $"&state={state}" +
               $"&redirect_uri={_settings.RedirectUri}";
    }

    public async Task<TokenResult> ExchangeCodeAsync(string code, CancellationToken ct)
    {
        var form = new Dictionary<string, string>
        {
            ["grant_type"] = "authorization_code",
            ["client_id"] = _settings.ClientId,
            ["client_secret"] = _settings.ClientSecret,
            ["code"] = code,
            ["redirect_uri"] = _settings.RedirectUri,
        };
        return await PostTokenAsync(form, ct);
    }

    public async Task<TokenResult> RefreshAsync(string refreshToken, CancellationToken ct)
    {
        var form = new Dictionary<string, string>
        {
            ["grant_type"] = "refresh_token",
            ["client_id"] = _settings.ClientId,
            ["client_secret"] = _settings.ClientSecret,
            ["refresh_token"] = refreshToken,
        };
        return await PostTokenAsync(form, ct);
    }

    private async Task<TokenResult> PostTokenAsync(Dictionary<string, string> form, CancellationToken ct)
    {
        // Ford's token endpoint is the B2C policy URL, NOT a generic /token.
        var url = $"{_settings.OAuthBaseUrl.TrimEnd('/')}/{TokenPath}?p={PolicyName}";
        using var resp = await _http.PostAsync(url, new FormUrlEncodedContent(form), ct);
        var body = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
        {
            _log.LogWarning("Ford token exchange failed: {Status}", (int)resp.StatusCode);
            throw new FordApiException((int)resp.StatusCode, "token_exchange_failed", body);
        }
        using var doc = JsonDocument.Parse(body);
        var root = doc.RootElement;
        var access = root.TryGetProperty("access_token", out var a) ? a.GetString() ?? "" : "";
        var refresh = root.TryGetProperty("refresh_token", out var r) ? r.GetString() ?? "" : "";
        var expires = root.TryGetProperty("expires_in", out var e) && e.TryGetInt32(out var ei) ? ei : 1200;
        return new TokenResult(access, refresh, expires);
    }

    public async Task<IReadOnlyList<FordVehicle>> ListVehiclesAsync(string accessToken, CancellationToken ct)
    {
        var url = $"{_settings.ApiBaseUrl.TrimEnd('/')}/v1/garage";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.Authorization = new("Bearer", accessToken);
        req.Headers.TryAddWithoutValidation("Application-Id", _settings.ClientId);
        using var resp = await _http.SendAsync(req, ct);
        var body = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
            throw new FordApiException((int)resp.StatusCode, "garage_failed", body);
        return ParseGarage(body);
    }

    public async Task<string> GetVehicleStatusJsonAsync(string accessToken, string vin, CancellationToken ct)
    {
        // FordConnect Query API exposes a single per-token telemetry endpoint
        // (matches FleetFoot). The vin is retained for the poller's per-VIN
        // dispatch/logging but is not part of the URL.
        var url = $"{_settings.ApiBaseUrl.TrimEnd('/')}/v1/telemetry";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.Authorization = new("Bearer", accessToken);
        req.Headers.TryAddWithoutValidation("Application-Id", _settings.ClientId);
        using var resp = await _http.SendAsync(req, ct);
        var body = await resp.Content.ReadAsStringAsync(ct);
        if (!resp.IsSuccessStatusCode)
            throw new FordApiException((int)resp.StatusCode, "status_failed", body);
        return body;
    }

    /// <summary>
    /// Tolerant garage-envelope parser, ported from FleetFoot. Recursively walks
    /// the JSON looking for any object that has a "vin" (or legacy "vehicleId").
    /// FordConnect Query has shipped several shapes — bare array, {vehicles:[…]},
    /// {vehicleList:[…]}, {data:{vehicles:[…]}}, and a SINGLE root object
    /// {vin:…,nickName:…} (what the live tenant returns today). Handling all of
    /// them means an envelope rename doesn't cost us the whole link.
    /// </summary>
    public static IReadOnlyList<FordVehicle> ParseGarage(string json)
    {
        if (string.IsNullOrWhiteSpace(json)) return Array.Empty<FordVehicle>();
        JsonDocument doc;
        try { doc = JsonDocument.Parse(json); }
        catch (JsonException) { return Array.Empty<FordVehicle>(); }

        using (doc)
        {
            var found = new List<FordVehicle>();
            CollectVehicles(doc.RootElement, found, depth: 0);
            return found
                .GroupBy(v => v.Vin, StringComparer.OrdinalIgnoreCase)
                .Select(g => g.First())
                .ToList();
        }
    }

    private static void CollectVehicles(JsonElement el, List<FordVehicle> sink, int depth)
    {
        if (depth > 6) return; // guard against pathological nesting
        switch (el.ValueKind)
        {
            case JsonValueKind.Array:
                foreach (var item in el.EnumerateArray())
                    CollectVehicles(item, sink, depth + 1);
                break;
            case JsonValueKind.Object:
                if (TryReadVehicle(el, out var v)) { sink.Add(v); return; }
                foreach (var prop in el.EnumerateObject())
                    CollectVehicles(prop.Value, sink, depth + 1);
                break;
        }
    }

    private static bool TryReadVehicle(JsonElement obj, out FordVehicle vehicle)
    {
        vehicle = default!;
        var vin = Str(obj, "vin", "vehicleId", "VIN");
        if (string.IsNullOrWhiteSpace(vin)) return false;

        var model = Str(obj, "modelName", "model");
        var nick = Str(obj, "nickName", "nickname", "vehicleNickName");
        var year = Num(obj, "modelyear", "modelYear", "year");
        var color = Str(obj, "color", "displayColor", "vehicleColor", "exteriorColor");
        var engine = Str(obj, "engineType", "engineTypeCode", "fuelType",
                         "powerTrain", "powertrain", "powertrainType", "propulsionType");
        vehicle = new FordVehicle(vin.Trim(), model, year, color, engine?.Trim().ToUpperInvariant(), nick);
        return true;
    }

    private static string? Str(JsonElement e, params string[] names)
    {
        foreach (var n in names)
            if (e.TryGetProperty(n, out var p) && p.ValueKind == JsonValueKind.String) return p.GetString();
        return null;
    }

    private static int? Num(JsonElement e, params string[] names)
    {
        foreach (var n in names)
            if (e.TryGetProperty(n, out var p))
            {
                if (p.ValueKind == JsonValueKind.Number && p.TryGetInt32(out var i)) return i;
                if (p.ValueKind == JsonValueKind.String && int.TryParse(p.GetString(), out var s)) return s;
            }
        return null;
    }
}

/// <summary>Raised for non-2xx Ford responses so callers can map to friendly errors.</summary>
public sealed class FordApiException(int status, string code, string? body = null)
    : Exception($"Ford API error {status} ({code})")
{
    public int Status { get; } = status;
    public string Code { get; } = code;
    public string? Body { get; } = body;
}
