using System.Collections.Concurrent;

namespace GoHenry.FordClient;

/// <summary>
/// Caches short-lived Ford access tokens in-process, keyed by (slug, userId), so
/// the poll worker does not call the token endpoint on every dequeue. Tokens live
/// ~20 minutes; we treat them as expired a minute early for safety.
/// </summary>
public interface IFordAccessTokenCache
{
    bool TryGet(string slug, string userId, out string accessToken);
    void Set(string slug, string userId, string accessToken, int expiresInSeconds);
    void Invalidate(string slug, string userId);
}

public sealed class FordAccessTokenCache : IFordAccessTokenCache
{
    private readonly ConcurrentDictionary<string, (string Token, DateTimeOffset ExpiresAt)> _cache = new();
    private static readonly TimeSpan Skew = TimeSpan.FromMinutes(1);

    private static string Key(string slug, string userId) => $"{slug}::{userId}";

    public bool TryGet(string slug, string userId, out string accessToken)
    {
        accessToken = "";
        if (_cache.TryGetValue(Key(slug, userId), out var entry) && entry.ExpiresAt > DateTimeOffset.UtcNow)
        {
            accessToken = entry.Token;
            return true;
        }
        return false;
    }

    public void Set(string slug, string userId, string accessToken, int expiresInSeconds)
    {
        var expires = DateTimeOffset.UtcNow.AddSeconds(Math.Max(60, expiresInSeconds)) - Skew;
        _cache[Key(slug, userId)] = (accessToken, expires);
    }

    public void Invalidate(string slug, string userId) => _cache.TryRemove(Key(slug, userId), out _);
}
