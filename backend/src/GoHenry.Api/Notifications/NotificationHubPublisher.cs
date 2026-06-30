using System.Text.Json;
using GoHenry.Core.Models;
using Microsoft.Azure.NotificationHubs;
using Microsoft.Extensions.Logging;

namespace GoHenry.Api.Notifications;

/// <summary>Delivers FCM pushes and registers device installs. Backed by Azure Notification Hubs.</summary>
public interface INotificationPublisher
{
    Task RegisterAsync(string userId, string installationId, string fcmToken, CancellationToken ct);
    Task SendAsync(NotificationMessage message, CancellationToken ct);
}

/// <summary>
/// Azure Notification Hubs → FCM v1 publisher. Registers each phone as an NH
/// installation tagged <c>user:{userId}</c> + <c>app:gohenry</c>, and sends
/// data-only FCM v1 messages so the app renders them locally (and can localise
/// the timestamp). When no hub is configured (local dev) a logging no-op is used.
/// </summary>
public sealed class NotificationHubPublisher : INotificationPublisher
{
    private readonly NotificationHubClient? _client;
    private readonly ILogger<NotificationHubPublisher> _log;

    private NotificationHubPublisher(NotificationHubClient? client, ILogger<NotificationHubPublisher> log)
    {
        _client = client;
        _log = log;
    }

    public static NotificationHubPublisher Create(string? connectionString, string? hubName, ILogger<NotificationHubPublisher> log)
    {
        if (string.IsNullOrWhiteSpace(connectionString) || string.IsNullOrWhiteSpace(hubName))
        {
            log.LogWarning("Notification Hub not configured — push is a local no-op.");
            return new NotificationHubPublisher(null, log);
        }
        return new NotificationHubPublisher(NotificationHubClient.CreateClientFromConnectionString(connectionString, hubName), log);
    }

    public async Task RegisterAsync(string userId, string installationId, string fcmToken, CancellationToken ct)
    {
        if (_client is null) { _log.LogInformation("(no-op) register install {Install} for {User}", installationId, userId); return; }
        var installation = new Installation
        {
            InstallationId = installationId,
            Platform = NotificationPlatform.FcmV1,
            PushChannel = fcmToken,
            Tags = new List<string> { $"user:{userId}", "app:gohenry" },
        };
        await _client.CreateOrUpdateInstallationAsync(installation, ct);
    }

    public async Task SendAsync(NotificationMessage message, CancellationToken ct)
    {
        if (_client is null) { _log.LogInformation("(no-op) push {Event} → {User}/{Vin}", message.Event, message.UserId, message.Vin); return; }

        var data = new Dictionary<string, string>(message.Data)
        {
            ["event"] = message.Event,
            ["vin"] = message.Vin,
            ["title"] = message.Title,
            ["body"] = message.Body,
        };
        var payload = JsonSerializer.Serialize(new { message = new { data } });
        try
        {
            await _client.SendFcmV1NativeNotificationAsync(payload, $"user:{message.UserId}", ct);
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "FCM send failed for {User}/{Vin} ({Event})", message.UserId, message.Vin, message.Event);
        }
    }
}
