using Azure.Data.Tables;
using Azure.Identity;
using Azure.Security.KeyVault.Secrets;
using GoHenry.Api;
using GoHenry.Api.Notifications;
using GoHenry.FordClient;
using GoHenry.Storage;
using Microsoft.Azure.Functions.Worker;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Polly;
using Polly.Extensions.Http;

var host = new HostBuilder()
    .ConfigureFunctionsWebApplication()
    .ConfigureServices((context, services) =>
    {
    var cfg = context.Configuration;

services.AddApplicationInsightsTelemetryWorkerService();
services.ConfigureFunctionsApplicationInsights();

// --- Table Storage: the one and only datastore (no SQL) ---
var storageConn = cfg["AzureWebJobsStorage"] ?? cfg["Storage:ConnectionString"] ?? "UseDevelopmentStorage=true";
services.AddSingleton(_ => new TableServiceClient(storageConn));
services.AddSingleton<IGoHenryStore, TableGoHenryStore>();

// --- Key Vault for Ford refresh tokens (optional locally) ---
var kvUri = cfg["KeyVaultUri"];
if (!string.IsNullOrWhiteSpace(kvUri))
    services.AddSingleton(_ => new SecretClient(new Uri(kvUri), new DefaultAzureCredential()));

// --- Ford developer apps (one slug per app per vehicle; REUSED Ford credentials) ---
var fordApps = FordConfig.ReadApps(cfg);
var primaryFordSlug = fordApps[0].Slug;
services.AddSingleton<IFordAppRegistry>(new FordAppRegistry(primaryFordSlug, fordApps));

// One named HttpClient per app. The client builds absolute URLs from settings,
// so the named client only needs the shared transient-fault policy.
foreach (var app in fordApps)
{
    services.AddHttpClient($"ford:{app.Slug}")
        .AddPolicyHandler(HttpPolicyExtensions
            .HandleTransientHttpError()
            .WaitAndRetryAsync(3, attempt => TimeSpan.FromMilliseconds(250 * Math.Pow(2, attempt))));
}

services.AddSingleton<IFordApiClientFactory, FordApiClientFactory>();
// Default IFordApiClient (primary app) for any single-app consumer.
services.AddSingleton<IFordApiClient>(sp => sp.GetRequiredService<IFordApiClientFactory>().Create(null));

services.AddSingleton<IFordAccessTokenCache, FordAccessTokenCache>();
services.AddSingleton<FordTokenService>();

// --- FCM push via Azure Notification Hubs (no-op locally when unset) ---
services.AddSingleton<INotificationPublisher>(sp =>
{
    var conn = cfg["NotificationHub:ConnectionString"];
    var name = cfg["NotificationHub:Name"];
    return NotificationHubPublisher.Create(conn, name,
        sp.GetRequiredService<Microsoft.Extensions.Logging.ILogger<NotificationHubPublisher>>());
});

    })
    .Build();

// Ensure tables exist on cold start.
using (var scope = host.Services.CreateScope())
{
    var store = scope.ServiceProvider.GetRequiredService<IGoHenryStore>();
    try { await store.InitializeAsync(); } catch { /* best-effort; emulator may be offline locally */ }
}

await host.RunAsync();

namespace GoHenry.Api
{
    /// <summary>Marker for test discovery / assembly identity.</summary>
    public static class AssemblyMarker { }
}
