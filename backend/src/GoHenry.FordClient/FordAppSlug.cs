namespace GoHenry.FordClient;

/// <summary>
/// One Ford developer app == one slug == one vehicle. A FordConnect token only
/// grants access to a single vehicle, so GoHenry tracks several cars by linking
/// several Ford apps, each under its own slug (mirrors FleetFoot). The primary
/// app's slug is whatever <c>Ford:Slug</c> is set to (default "primary").
/// </summary>
public static class FordAppSlug
{
    /// <summary>Trim + lower-case a slug. Blank/null becomes an empty string,
    /// which callers treat as "use the primary slug".</summary>
    public static string Normalize(string? slug) =>
        string.IsNullOrWhiteSpace(slug) ? "" : slug.Trim().ToLowerInvariant();
}
