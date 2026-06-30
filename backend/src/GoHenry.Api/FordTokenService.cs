using Azure.Security.KeyVault.Secrets;
using GoHenry.FordClient;
using GoHenry.Storage;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace GoHenry.Api;

/// <summary>
/// Resolves a usable Ford access token for a (userId, slug), minting one from the
/// Key Vault-stored refresh token when the in-process cache misses. Marks the
/// account NEEDS_REAUTH if Ford rejects the refresh token. The refresh token
/// never leaves Key Vault → no token on the device, none in Table Storage.
/// </summary>
public sealed class FordTokenService(
    IFordApiClientFactory fordFactory,
    IFordAccessTokenCache cache,
    IGoHenryStore store,
    ILogger<FordTokenService> log,
    IServiceProvider sp)
{
    // Key Vault is optional locally; resolve it lazily so the service still
    // constructs (and the no-Ford paths still work) when KV isn't configured.
    private SecretClient? Secrets => sp.GetService<SecretClient>();

    public string Slug => fordFactory.PrimarySlug;

    /// <summary>Secret name convention for a user's per-slug Ford refresh token.</summary>
    public static string SecretName(string userId, string slug) => $"ford-refresh-{userId}-{slug}";

    public async Task<string> GetAccessTokenAsync(string userId, string slug, CancellationToken ct)
    {
        if (cache.TryGet(slug, userId, out var cached)) return cached;

        var secrets = Secrets;
        if (secrets is null)
            throw new InvalidOperationException("Key Vault is not configured; cannot mint a Ford access token.");

        var account = await store.GetAccountAsync(userId, slug, ct)
            ?? throw new FordReauthRequiredException(slug);

        var secretName = account.KvSecretName ?? SecretName(userId, slug);
        string refreshToken;
        try
        {
            refreshToken = (await secrets.GetSecretAsync(secretName, cancellationToken: ct)).Value.Value;
        }
        catch (Exception ex)
        {
            log.LogWarning(ex, "Missing refresh token secret {Secret}", secretName);
            throw new FordReauthRequiredException(slug);
        }

        try
        {
            var tokens = await fordFactory.Create(slug).RefreshAsync(refreshToken, ct);
            cache.Set(slug, userId, tokens.AccessToken, tokens.ExpiresInSeconds);

            // Ford rotates refresh tokens — persist the new one and stamp the account.
            if (!string.IsNullOrWhiteSpace(tokens.RefreshToken) && tokens.RefreshToken != refreshToken)
                await secrets.SetSecretAsync(secretName, tokens.RefreshToken, ct);

            account.Status = "ACTIVE";
            account.LastRefreshAt = DateTimeOffset.UtcNow;
            await store.UpsertAccountAsync(account, ct);

            return tokens.AccessToken;
        }
        catch (FordApiException ex) when (ex.Status is 400 or 401)
        {
            account.Status = "NEEDS_REAUTH";
            await store.UpsertAccountAsync(account, ct);
            cache.Invalidate(slug, userId);
            throw new FordReauthRequiredException(slug);
        }
    }
}

/// <summary>Thrown when a Ford grant is missing or rejected and the user must re-link.</summary>
public sealed class FordReauthRequiredException(string slug)
    : Exception($"Ford account '{slug}' needs re-authentication.")
{
    public string Slug { get; } = slug;
}
