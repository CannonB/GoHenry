using System.Text;
using System.Text.Json;
using Azure.Security.KeyVault.Secrets;
using GoHenry.Core.Models;
using GoHenry.FordClient;
using GoHenry.Storage;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace GoHenry.Api.Functions;

/// <summary>
/// Self-contained Ford OAuth handshake (reusing the existing Ford developer app
/// credentials):
///   POST /api/oauth/start?app={slug}     — issue a state + return the authorize URL
///   GET|POST /api/oauth/callback          — Ford redirect: redeem code, store token
///   GET  /api/ford/account/status         — per-slug link status for the app
///
/// The refresh token is written straight to Key Vault as
/// <c>ford-refresh-{userId}-{slug}</c> — never on the device, never in Table Storage.
/// </summary>
public sealed class OAuthFunctions(
    IFordApiClientFactory fordFactory,
    IFordAppRegistry fordRegistry,
    IGoHenryStore store,
    IServiceProvider sp,
    ILogger<OAuthFunctions> log)
{
    private const int RefreshTokenLifetimeDays = 90;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private SecretClient? Secrets => sp.GetService<SecretClient>();

    [Function("OAuth_Start")]
    public async Task<IActionResult> Start(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "oauth/start")] HttpRequest req,
        CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        var slug = FordAppSlug.Normalize(req.Query["app"].ToString());
        if (slug.Length == 0) slug = fordRegistry.PrimarySlug;
        if (!fordRegistry.Contains(slug))
            return new NotFoundObjectResult(new
            {
                error = "ford_app_not_configured",
                detail = $"No Ford developer app is configured for slug '{slug}'.",
            });

        var state = Guid.NewGuid().ToString("N");
        await store.PutStateAsync(new OAuthStateEntity
        {
            RowKey = state, UserId = userId, Slug = slug, CreatedAt = DateTimeOffset.UtcNow,
        }, ct);

        var url = fordFactory.Create(slug).BuildAuthorizeUrl(state);
        return new OkObjectResult(new { url, app = slug });
    }

    [Function("OAuth_Callback")]
    public async Task<IActionResult> Callback(
        [HttpTrigger(AuthorizationLevel.Anonymous, "get", "post", Route = "oauth/callback")] HttpRequest req,
        CancellationToken ct)
    {
        var code = req.Query["code"].ToString();
        var state = req.Query["state"].ToString();
        if (string.IsNullOrWhiteSpace(code) || string.IsNullOrWhiteSpace(state))
            return Html("Ford did not return a code. Re-start the link from the app.", false);

        var stateRow = await store.TakeStateAsync(state, ct);
        if (stateRow is null)
            return Html("This sign-in link has expired. Re-start the link from the app.", false);

        var userId = stateRow.UserId;
        var slug = stateRow.Slug;
        if (!fordRegistry.Contains(slug))
            return Html("That Ford app is no longer configured. Please try again.", false);

        var ford = fordFactory.Create(slug);

        TokenResult tokens;
        try { tokens = await ford.ExchangeCodeAsync(code, ct); }
        catch (FordApiException ex)
        {
            log.LogWarning(ex, "Ford code exchange failed");
            return Html("Ford rejected the sign-in. Please try again.", false);
        }

        var secrets = Secrets;
        var secretName = FordTokenService.SecretName(userId, slug);
        if (secrets is not null)
            await secrets.SetSecretAsync(secretName, tokens.RefreshToken, ct);
        else
            log.LogWarning("Key Vault not configured — refresh token NOT persisted (dev only).");

        await store.UpsertAccountAsync(new FordAccountEntity
        {
            PartitionKey = userId,
            RowKey = slug,
            Status = "ACTIVE",
            IsPrimary = string.Equals(slug, fordRegistry.PrimarySlug, StringComparison.OrdinalIgnoreCase),
            KvSecretName = secretName,
            LastRefreshAt = DateTimeOffset.UtcNow,
        }, ct);

        // Discover and upsert the VINs the user authorized.
        try
        {
            var vehicles = await ford.ListVehiclesAsync(tokens.AccessToken, ct);
            foreach (var v in vehicles)
            {
                var existing = await store.GetVehicleAsync(userId, v.Vin, ct) ?? new VinEntity { PartitionKey = userId, RowKey = v.Vin };
                existing.Model = v.Model ?? existing.Model;
                existing.ModelYear = v.ModelYear ?? existing.ModelYear;
                existing.DisplayColor = v.Color ?? existing.DisplayColor;
                existing.EngineType = EngineTypes.Parse(v.EngineType ?? existing.EngineType).ToString();
                existing.FordSlug = slug;
                existing.Nickname ??= v.NickName ?? v.Model;
                await store.UpsertVehicleAsync(existing, ct);
            }
            log.LogInformation("Linked {Count} vehicles for {User} (slug={Slug})", vehicles.Count, userId, slug);
        }
        catch (FordApiException ex)
        {
            log.LogWarning(ex, "Garage discovery failed (tokens still valid)");
        }

        return Html("GoHenry is linked to your Ford account. You can close this tab and return to the app.", true);
    }

    [Function("Ford_AccountStatus")]
    public async Task<IActionResult> AccountStatus(
        [HttpTrigger(AuthorizationLevel.Function, "get", Route = "ford/account/status")] HttpRequest req,
        CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        var accountsBySlug = (await store.ListAccountsAsync(userId, ct))
            .ToDictionary(a => FordAppSlug.Normalize(a.RowKey), a => a, StringComparer.OrdinalIgnoreCase);

        // One card per CONFIGURED Ford app (primary first), linked or not, so the
        // re-auth screen can offer "Link" for every car slot.
        var rows = new List<FordAccountStatusDto>();
        foreach (var slug in fordRegistry.Slugs)
        {
            var isPrimary = string.Equals(slug, fordRegistry.PrimarySlug, StringComparison.OrdinalIgnoreCase);
            if (accountsBySlug.TryGetValue(slug, out var acct))
            {
                var dto = acct.ToStatusDto(RefreshTokenLifetimeDays);
                dto.IsPrimary = isPrimary;
                rows.Add(dto);
            }
            else
            {
                rows.Add(new FordAccountStatusDto
                {
                    AppSlug = slug, IsPrimary = isPrimary, IsLinked = false,
                    Status = "UNLINKED", NeedsReauth = false,
                });
            }
        }
        return new OkObjectResult(new { accounts = rows });
    }

    private static ContentResult Html(string message, bool ok)
    {
        var color = ok ? "#1f7a3d" : "#b3261e";
        var sb = new StringBuilder()
            .Append("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>")
            .Append("<title>GoHenry</title></head><body style='font-family:system-ui;margin:0;display:flex;")
            .Append("min-height:100vh;align-items:center;justify-content:center;background:#faf7f5'>")
            .Append("<div style='max-width:28rem;padding:2rem;text-align:center'>")
            .Append($"<h1 style='color:{color};margin:0 0 .5rem'>GoHenry</h1>")
            .Append($"<p style='font-size:1.05rem;color:#333'>{System.Net.WebUtility.HtmlEncode(message)}</p>")
            .Append("</div></body></html>");
        return new ContentResult { Content = sb.ToString(), ContentType = "text/html", StatusCode = 200 };
    }
}
