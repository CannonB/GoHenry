# GoHenry backend

A single **.NET 8 isolated Azure Functions** app. Its **only** datastore is **Azure
Table Storage** — there is no SQL, no Entity Framework, no Dapper. Ford refresh
tokens live in **Azure Key Vault**; push goes out via **Azure Notification Hubs (FCM
v1)**.

> 🔔 **Notifications:** the trigger rules (trip/charge/lost-signal), per-VIN enable
> flags, and the FCM payload contract are documented end-to-end in the root
> [README ▸ Notifications](../README.md#notifications). Trigger logic:
> `GoHenry.Core/Normalization/ActivityDetector.cs`; delivery: `GoHenry.Api/Notifications/`.

## Projects

| Project | What it holds |
| --- | --- |
| `GoHenry.Core` | DTOs/models, the Ford telemetry **normalizer**, and the pure **activity detector** (trip/charge/lost-signal). No I/O — fully unit-tested. |
| `GoHenry.Storage` | Table Storage entities + `IGoHenryStore` repository. **The datastore.** |
| `GoHenry.FordClient` | Typed Ford HTTP client (authorize URL, code/refresh exchange, garage, status) + in-process access-token cache. |
| `GoHenry.Api` | The Functions host: REST surface, OAuth handshake, the poller, and FCM publish. |
| `GoHenry.Tests` | xUnit + FluentAssertions. |

## Build & test

```bash
cd backend
dotnet build           # builds all projects (net8.0)
dotnet test            # runs the unit tests
```

> The repo was verified to build with the .NET SDK targeting **net8.0**. The
> Functions host uses the **ASP.NET Core integration** worker model, so HTTP
> functions take `HttpRequest` and return `IActionResult`.

## Run locally

You need the [Azure Functions Core Tools v4](https://learn.microsoft.com/azure/azure-functions/functions-run-local)
(`func`) and a storage emulator ([Azurite](https://learn.microsoft.com/azure/storage/common/storage-use-azurite)).

```bash
# 1) Start Azurite (Table + Queue + Blob) in another terminal
azurite --silent

# 2) Run the Functions host
cd backend/src/GoHenry.Api
func start
```

The host listens on `http://localhost:7071`. The Android emulator reaches it at
`http://10.0.2.2:7071/api/` (the app's default `backend.baseUrl`).

### `local.settings.json`

`GoHenry.Api/local.settings.json` holds local config. Key settings (note: config
keys use `:` in code, but **Azure app settings** use `__` — the script handles that):

| Local key (`local.settings.json`) | Azure app setting | Meaning |
| --- | --- | --- |
| `AzureWebJobsStorage` | `AzureWebJobsStorage` | Storage connection (use `UseDevelopmentStorage=true` for Azurite) |
| `KeyVaultUri` | `KeyVaultUri` | Key Vault base URI (optional locally; without it, token mint is disabled) |
| `Ford:Slug` | `Ford__Slug` | App slug (default `primary`) |
| `Ford:ClientId` / `Ford:ClientSecret` | `Ford__ClientId` / `Ford__ClientSecret` | **Reused** Ford developer app credentials |
| `Ford:OAuthBaseUrl` / `Ford:ApiBaseUrl` | `Ford__OAuthBaseUrl` / `Ford__ApiBaseUrl` | Ford endpoints |
| `Ford:RedirectUri` | `Ford__RedirectUri` | The OAuth callback URL (your Function App's `/api/oauth/callback`) |
| `Ford:Scope` | `Ford__Scope` | OAuth scope (default `access`) |
| `NotificationHub:ConnectionString` | `NotificationHub__ConnectionString` | Notification Hub `DefaultFullSharedAccessSignature` (optional locally → push is a no-op) |
| `NotificationHub:Name` | `NotificationHub__Name` | Hub name (default `gohenry`) |

When Key Vault or the Notification Hub aren't configured (typical for local dev),
the backend **degrades gracefully**: token minting throws a friendly "needs re-auth"
and push becomes a logged no-op, so you can still exercise the REST surface.

## REST surface

All routes are under `/api`. Every route requires an `x-user-id` header and the
Functions host key (`?code=...`), **except** the anonymous OAuth callback.

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/api/fleet/vehicles` | List the caller's vehicles |
| GET | `/api/fleet/telemetry/{vin}` | Current normalized telemetry snapshot |
| GET | `/api/fleet/telemetry/{vin}/cache` | Same snapshot (detail screen; contract parity) |
| GET | `/api/fleet/notifications/{vin}` | Per-VIN push toggles |
| POST | `/api/fleet/notifications/{vin}` | Set per-VIN push toggles |
| GET | `/api/fleet/pollsettings` | App-wide poll cadence (minutes, 1–10) + lost-signal threshold (missed polls, 5–20) |
| POST | `/api/fleet/pollsettings` | Set the poll cadence (1–10, default 2) and/or lost-signal polls (5–20, default 10) |
| GET | `/api/fleet/roadtripsettings` | App-wide road-trip automation (auto-start + auto-close tuning) |
| POST | `/api/fleet/roadtripsettings` | Set auto-start + idle (2–12 h, default 12) + max age (1–7 d, default 7) + end-on-stop |
| GET | `/api/fleet/roadtrips/{vin}` | List a car's road trips (newest first; `?take=` 1–200) |
| POST | `/api/fleet/roadtrips/{vin}/start` | Start a road trip (optional `{ "name": "…" }`); 409 if one is already open |
| POST | `/api/fleet/roadtrips/{vin}/stop` | Stop the active road trip; 404 if none open |
| GET | `/api/fleet/roadtrips/{vin}/{id}` | Road-trip detail incl. the full event timeline |
| POST | `/api/fleet/roadtrips/{vin}/{id}/rename` | Rename a road trip (`{ "name": "…" }`) |
| DELETE | `/api/fleet/roadtrips/{vin}/{id}` | Delete a road trip and its timeline (clears the active pointer if it was open) |
| POST | `/api/notifications/register` | Upsert this install's FCM token |
| POST | `/api/oauth/start?app={slug}` | Begin the Ford OAuth handshake → returns authorize URL |
| GET\|POST | `/api/oauth/callback` | **Anonymous** Ford redirect handler (stores refresh token in Key Vault, discovers VINs) |
| GET | `/api/ford/account/status` | Per-slug Ford link status |

## Table Storage data model (the whole datastore)

| Table | PartitionKey | RowKey | Holds |
| --- | --- | --- | --- |
| `Vehicles` | `userId` | `vin` | Metadata + the warm telemetry snapshot (`Snap*`) + poll/activity state + the per-VIN notification toggles. **One row powers the entire read surface — no joins.** |
| `FordAccounts` | `userId` | `slug` | Link status, `IsPrimary`, `KvSecretName`, `LastRefreshAt`. (Token value lives in **Key Vault**, never here.) |
| `OAuthStates` | `state` | state GUID | Short-lived OAuth nonces. |
| `Installs` | `userId` | installationId | FCM token registrations (for Notification Hub installs). |
| `TripHistory` | `vin` | reverse-ticks | Finished trips (newest first). |
| `ChargeHistory` | `vin` | reverse-ticks | Finished charge sessions (newest first). |
| `RoadTrips` | `vin` | reverse-ticks | Named, durable journeys grouping multiple trips + their notification events. Timeline embedded as one JSON array (≤ 800 events). The active trip is also pointed to by `Vehicles.ActiveRoadTripId/Name/StartedAt/LastEventAt` so telemetry exposes it with no extra read. `StartMethod` is `manual`/`auto`; `EndReason` is `manual`/`auto`. |
| `GoHenryMeta` | `_meta` | e.g. `pollSettings` | Poll cadence + lost-signal threshold (missed polls) + road-trip automation settings (auto-start, idle hours, max days) + housekeeping stamps. |

Row keys for history use **reverse ticks** (`long.MaxValue - ticks`, zero-padded) so a
plain top-N query returns newest-first without a sort.

## Activity detection

`GoHenry.Core.Normalization.ActivityDetector` is a pure function: given the prior
state (from the VIN row) and the new snapshot, it returns the events to fire and the
next state to persist. Rules:

- **Trip started** when ignition goes ON; **trip ended** when it goes OFF (if a trip
  was open).
- **Telemetry feed lost** when telemetry goes stale — no **successful** Ford read for a configurable number of **missed polls** (5–20, default 10; effective window = polls × cadence, e.g. 10 × 2 min ≈ 20 min). The Ford feed itself is unreachable / needs re-auth. Set in the app's *Settings ▸ Lost signal* section (stored on the `pollSettings` meta row). Fires once; re-arms on the next good read.
- **Lost signal** when the feed is **reachable but frozen** — ignition `ON` while **both** the odometer and the Ford telemetry timestamp stay unchanged for the same poll count (evaluated per successful poll via `ActivityDetector.IsFeedFrozen`). Fires once; re-arms when data advances or the ignition turns off. Both "offline" alerts share the one *Settings ▸ Lost signal* threshold and drive the hero-card wifi icon (which captions which loss is active: *Telemetry* / *Signal* / *Both*).
- **Charge in progress** when charging begins; on stop, **charge complete** unless the
  charger reports a fault → **charge error**.

See [`../docs/architecture.md`](../docs/architecture.md) for the full rationale.

## Deploy

Use the provisioning script (recommended):

```bash
cd backend/scripts
./gohenry.sh full      # infra + settings + deploy
```

Or deploy code only into an existing Function App:

```bash
cd backend/src/GoHenry.Api
func azure functionapp publish <your-func-app> --dotnet-isolated
```

See [`scripts/README.md`](scripts/README.md) for the full lifecycle.
