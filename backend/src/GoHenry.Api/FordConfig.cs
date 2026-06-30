using GoHenry.FordClient;
using Microsoft.Extensions.Configuration;

namespace GoHenry.Api;

/// <summary>
/// Reads the configured Ford developer apps from configuration. The primary app
/// comes from the legacy <c>Ford:*</c> keys; additional apps (one per extra
/// vehicle) come from <c>Ford:Apps:{slug}:*</c> (env: <c>Ford__Apps__{slug}__*</c>).
/// Each extra app needs at least a ClientId + ClientSecret; the OAuth/API base
/// URLs, redirect URI and scope inherit from the primary app unless overridden
/// (every Ford app shares the same callback — the slug is recovered from state).
/// </summary>
public static class FordConfig
{
    public static List<FordSettings> ReadApps(IConfiguration cfg)
    {
        var primary = new FordSettings
        {
            Slug = (cfg["Ford:Slug"] ?? "primary").Trim().ToLowerInvariant(),
            ClientId = cfg["Ford:ClientId"] ?? "",
            ClientSecret = cfg["Ford:ClientSecret"] ?? "",
            OAuthBaseUrl = cfg["Ford:OAuthBaseUrl"] ?? "https://api.vehicle.ford.com/",
            ApiBaseUrl = cfg["Ford:ApiBaseUrl"] ?? "https://api.vehicle.ford.com/fcon-query/",
            RedirectUri = cfg["Ford:RedirectUri"] ?? "",
            Scope = cfg["Ford:Scope"] ?? "access",
        };

        var apps = new List<FordSettings> { primary };

        foreach (var child in cfg.GetSection("Ford:Apps").GetChildren())
        {
            var slug = child.Key.Trim().ToLowerInvariant();
            if (string.IsNullOrWhiteSpace(slug) || slug == primary.Slug) continue;

            var clientId = child["ClientId"];
            var clientSecret = child["ClientSecret"];
            // An incomplete slot is silently skipped so half-filled config never
            // breaks the primary app.
            if (string.IsNullOrWhiteSpace(clientId) || string.IsNullOrWhiteSpace(clientSecret)) continue;

            apps.Add(new FordSettings
            {
                Slug = slug,
                ClientId = clientId!,
                ClientSecret = clientSecret!,
                OAuthBaseUrl = child["OAuthBaseUrl"] ?? primary.OAuthBaseUrl,
                ApiBaseUrl = child["ApiBaseUrl"] ?? primary.ApiBaseUrl,
                RedirectUri = child["RedirectUri"] ?? primary.RedirectUri,
                Scope = child["Scope"] ?? primary.Scope,
            });
        }

        return apps;
    }
}
