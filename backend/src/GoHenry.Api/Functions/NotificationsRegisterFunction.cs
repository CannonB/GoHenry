using System.Text.Json;
using GoHenry.Api.Notifications;
using GoHenry.Storage;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Logging;

namespace GoHenry.Api.Functions;

/// <summary>
/// <c>POST /api/notifications/register</c> — upserts this install's FCM token as a
/// Notification Hub installation tagged <c>user:{userId}</c>, and records it in the
/// Installs table. Body: <c>{ "installationId", "fcmToken", "app" }</c>.
/// </summary>
public sealed class NotificationsRegisterFunction(
    IGoHenryStore store, INotificationPublisher publisher, ILogger<NotificationsRegisterFunction> log)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private sealed class RegisterBody
    {
        public string? InstallationId { get; set; }
        public string? FcmToken { get; set; }
        public string? App { get; set; }
    }

    [Function("Notifications_Register")]
    public async Task<IActionResult> Register(
        [HttpTrigger(AuthorizationLevel.Function, "post", Route = "notifications/register")] HttpRequest req,
        CancellationToken ct)
    {
        var userId = req.UserId();
        if (userId is null) return HttpHelpers.Unauthorized();

        RegisterBody? body;
        try { body = await JsonSerializer.DeserializeAsync<RegisterBody>(req.Body, Json, ct); }
        catch (JsonException) { return HttpHelpers.BadRequest("invalid_json"); }

        if (body is null || string.IsNullOrWhiteSpace(body.InstallationId) || string.IsNullOrWhiteSpace(body.FcmToken))
            return HttpHelpers.BadRequest("installationId_and_fcmToken_required");

        await store.UpsertInstallAsync(new InstallEntity
        {
            PartitionKey = userId,
            RowKey = body.InstallationId!,
            FcmToken = body.FcmToken!,
            App = string.IsNullOrWhiteSpace(body.App) ? "gohenry" : body.App!,
            UpdatedAt = DateTimeOffset.UtcNow,
        }, ct);

        await publisher.RegisterAsync(userId, body.InstallationId!, body.FcmToken!, ct);
        log.LogInformation("Registered install {Install} for {User}", body.InstallationId, userId);
        return new OkObjectResult(new { ok = true });
    }
}
