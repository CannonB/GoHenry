using Microsoft.Extensions.Logging;

namespace GoHenry.FordClient;

/// <summary>
/// Builds a <see cref="IFordApiClient"/> bound to a specific Ford app slug, using
/// that slug's credentials and its named <c>ford:{slug}</c> HttpClient. The OAuth
/// handshake, token heartbeat and poller all resolve the right client per row so
/// each linked vehicle refreshes against its own Ford developer app.
/// </summary>
public interface IFordApiClientFactory
{
    string PrimarySlug { get; }
    IReadOnlyList<string> Slugs { get; }
    bool Contains(string? slug);
    FordSettings GetSettings(string? slug);

    /// <summary>Create a client for a slug. Blank/null resolves to the primary app.</summary>
    IFordApiClient Create(string? slug);
}

/// <inheritdoc />
public sealed class FordApiClientFactory(
    IHttpClientFactory httpFactory,
    IFordAppRegistry registry,
    ILoggerFactory logFactory) : IFordApiClientFactory
{
    public string PrimarySlug => registry.PrimarySlug;
    public IReadOnlyList<string> Slugs => registry.Slugs;
    public bool Contains(string? slug) => registry.Contains(slug);
    public FordSettings GetSettings(string? slug) => registry.Get(slug);

    public IFordApiClient Create(string? slug)
    {
        var settings = registry.Get(slug);
        var http = httpFactory.CreateClient($"ford:{FordAppSlug.Normalize(settings.Slug)}");
        return new FordApiClient(http, settings, logFactory.CreateLogger<FordApiClient>());
    }
}
