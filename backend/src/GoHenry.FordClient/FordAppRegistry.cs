namespace GoHenry.FordClient;

/// <summary>
/// In-memory registry of the configured Ford developer apps, keyed by slug.
/// Enumerating <see cref="Slugs"/> drives the re-auth screen so that every
/// configured app shows a card — even before it has been linked.
/// </summary>
public interface IFordAppRegistry
{
    /// <summary>The slug of the primary (legacy <c>Ford:*</c>) app.</summary>
    string PrimarySlug { get; }

    /// <summary>All configured slugs, primary first, then the rest A→Z.</summary>
    IReadOnlyList<string> Slugs { get; }

    bool Contains(string? slug);

    /// <summary>Settings for a slug. Blank/null resolves to the primary app.
    /// Throws <see cref="KeyNotFoundException"/> when the slug isn't configured.</summary>
    FordSettings Get(string? slug);

    bool TryGet(string? slug, out FordSettings settings);
}

/// <inheritdoc />
public sealed class FordAppRegistry : IFordAppRegistry
{
    private readonly Dictionary<string, FordSettings> _bySlug;

    public string PrimarySlug { get; }

    public FordAppRegistry(string primarySlug, IEnumerable<FordSettings> apps)
    {
        var p = FordAppSlug.Normalize(primarySlug);
        PrimarySlug = p.Length > 0 ? p : "primary";
        _bySlug = new Dictionary<string, FordSettings>(StringComparer.OrdinalIgnoreCase);
        foreach (var a in apps)
        {
            var key = FordAppSlug.Normalize(a.Slug);
            if (key.Length > 0) _bySlug[key] = a;
        }
    }

    public IReadOnlyList<string> Slugs =>
        _bySlug.Keys
            .OrderBy(s => string.Equals(s, PrimarySlug, StringComparison.OrdinalIgnoreCase) ? 0 : 1)
            .ThenBy(s => s, StringComparer.OrdinalIgnoreCase)
            .ToList();

    private string Resolve(string? slug)
    {
        var k = FordAppSlug.Normalize(slug);
        return k.Length == 0 ? PrimarySlug : k;
    }

    public bool Contains(string? slug) => _bySlug.ContainsKey(Resolve(slug));

    public FordSettings Get(string? slug) =>
        _bySlug.TryGetValue(Resolve(slug), out var s)
            ? s
            : throw new KeyNotFoundException($"No Ford app is configured for slug '{slug}'.");

    public bool TryGet(string? slug, out FordSettings settings) =>
        _bySlug.TryGetValue(Resolve(slug), out settings!);
}
