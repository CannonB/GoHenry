using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace GoHenry.Api.Functions;

/// <summary>Small helpers shared by the HTTP-triggered functions.</summary>
internal static class HttpHelpers
{
    public const string UserIdHeader = "x-user-id";

    /// <summary>Reads the caller's identity from the <c>x-user-id</c> header.</summary>
    public static string? UserId(this HttpRequest req) =>
        req.Headers.TryGetValue(UserIdHeader, out var v) && !string.IsNullOrWhiteSpace(v)
            ? v.ToString().Trim()
            : null;

    public static IActionResult Unauthorized(string error = "missing_user_id") =>
        new ObjectResult(new { error }) { StatusCode = StatusCodes.Status401Unauthorized };

    public static IActionResult NotFound(string error = "not_found") =>
        new ObjectResult(new { error }) { StatusCode = StatusCodes.Status404NotFound };

    public static IActionResult BadRequest(string error) =>
        new ObjectResult(new { error }) { StatusCode = StatusCodes.Status400BadRequest };

    public static IActionResult NeedsReauth() =>
        new ObjectResult(new { error = "needs_reauth" }) { StatusCode = StatusCodes.Status409Conflict };

    public static IActionResult Conflict(string error) =>
        new ObjectResult(new { error }) { StatusCode = StatusCodes.Status409Conflict };
}
