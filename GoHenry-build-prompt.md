# Build Prompt — “GoHenry” Vehicle Tracker (Android + SQL‑free Azure backend)

> **How to use this document.** This is a single, self‑contained brief for an AI
> coding agent (or a developer) to build **GoHenry** from scratch. Read it top to
> bottom, then build it in the order given. Everything you need is here; where a
> design choice is open, the recommended default is stated. Do **not** modify or
> depend on any existing app or Azure resource — GoHenry is brand new and fully
> standalone. Build it well, document it clearly, and keep it small.

---

## 0) One‑paragraph mission

Build **GoHenry**, a friendly, small‑scale hobby app that quietly tracks a handful
of Ford vehicles and shows their live status, trips, and charging on an Android
phone — with push notifications when a trip starts/stops, charging changes, or a
car loses signal. It is modeled closely on an existing app called **FleetFoot**
(same carousel, same FCM notifications, same on‑device notification history,
same click‑down‑to‑detail flow, same icon‑intensive look), but GoHenry is **wholly
independent**: its own Android package, its own Azure backend, its own Firebase/FCM
project, and — critically — **no SQL database anywhere**. All server state lives in
**Azure Table Storage**. The only thing GoHenry reuses from the existing world is
the **Ford developer credentials** (client id / secret / app slug) so you don’t
have to register a new Ford developer application.

---

## 1) Hard requirements & guardrails (read first)

1. **Scale is intentionally tiny.** Support **up to 5 vehicles** total and **up to
   5 phones/installs**. Do not build for multi‑tenant scale, sharding, or high
   throughput. Favor simplicity and clarity over cleverness.
2. **No SQL. At all.** No Azure SQL, no Postgres, no MySQL, no SQLite‑on‑server, no
   Cosmos. The **single server datastore is Azure Table Storage** (plus Azure Blob
   only if you genuinely need it for raw JSON, which you should avoid). On the
   phone, persistence is **SharedPreferences only** (no Room, no on‑device DB).
3. **Wholly independent.** GoHenry shares **no** Azure resources, **no** Firebase
   project, and **no** code module with the existing apps (FleetFoot / SashaSync /
   QoastQurrent). The only reuse is the **Ford developer app credentials** and the
   *patterns* (not the live resources) described here.
4. **Secrets never touch the device or source control.** Ford refresh tokens live
   in **Azure Key Vault** (Key Vault is not a database — this keeps the “no SQL”
   rule while keeping tokens safe). The phone holds only a backend base URL, a
   per‑install `x-user-id` GUID, and a Function host key.
5. **Robust, intuitive, well‑designed, well‑documented.** Clear empty states,
   friendly error messages, and beginner‑readable docs are part of “done”.
6. **Material 3 Expressive.** Use the latest Material 3 **Expressive** design
   (rich shape, motion, and color) while preserving FleetFoot’s **icon‑intensive**
   visual language (Material Symbols for engine type, status, charge, etc.).
7. **Android package / namespace:** `com.gohenry.app`.
8. **Branding:** product name **GoHenry**; keep a calm, friendly tone in copy.

---

## 2) What FleetFoot is (the model to follow)

FleetFoot is a deliberately small Jetpack Compose app — a *read‑through, cache‑only*
“current telemetry” viewer for a few Ford EV VINs, backed by a tiny .NET 8 Azure
Functions backend. Replicate its **functionality and feel**, not its resources.
Mirror these behaviors in GoHenry:

### 2.1 Screens & interactions
- **Select‑a‑car carousel.** A `HorizontalPager` carousel with page‑indicator dots.
  Each card shows: nickname/title, engine‑type icon, full VIN, current‑telemetry
  tiles (state of charge or fuel, range, odometer, charging/plugged‑in), and a
  tap‑to‑Maps location row. Tapping the **status icon** at the bottom of the card
  opens **Vehicle Status**; **double‑tapping the engine icon** (top‑left) opens the
  **Detailed data** (raw cached fields) screen. When a car has notification history
  enabled, a bare **bell** icon appears inline in that card’s header; tapping it
  opens that car’s alert history.
- **Vehicle Status (detail).** Read‑only, built entirely from the telemetry fields.
  An engine‑aware **hero gauge** — **Fuel level** for pure hybrids (HEV), **State of
  charge** for everything else (BEV/PHEV/gas) — a 2×3 grid of stat tiles, and an
  accordion of collapsible cards: **Location** (with *Open in Maps*), **Drive
  Train**, **Battery** (hidden for HEV), **Vehicle State**, and **Motion**. Cards
  default collapsed for a calm landing.
- **Detailed data.** A flat, sorted list of every raw cached field/value for the
  VIN (debug‑friendly), reusing the same telemetry endpoint as the carousel.
- **Notification setup.** Per‑VIN push toggles, grouped:
  - **Trips:** Trip start, Trip stop, Lost signal (car stopped reporting mid‑trip).
  - **Charge** (BEV/PHEV only): Charge in progress, Charge complete, Charge error.
  - A **Background activity** card at top that warns when GoHenry is
    battery‑optimized and offers a one‑tap *Allow background activity* button
    (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, with graceful
    fallbacks), flipping to a green “Unrestricted” state on resume.
  - A per‑vehicle **Save recent alerts** toggle (history; see below) with a
    **retention slider** (1–7 days, default 3).
  - A collapsible **Ford Re‑Authorization** section at the bottom — **account‑level**
    (not per‑VIN) — listing one row per configured Ford app slug with status
    (linked / needs‑reauth / days‑until‑reauth) and a button to re‑link via a
    browser OAuth round trip. Re‑auth must preserve all telemetry and vehicle data.
- **Notification history (review‑only, on‑device).** Per‑vehicle local capture of
  incoming FCM pushes. When enabled for a car, a bell shows in its carousel header;
  tapping opens that VIN’s alerts **newest → oldest**, grouped into **collapsible
  day sections** (each header shows that day’s count). **Today** is always shown and
  expanded; other days start collapsed. Each entry shows the event icon, time in the
  **phone’s local timezone**, and a tappable lat/long that opens Google Maps. The
  list is strictly **review‑only** (no edit/delete/manage). Toggling a car’s setting
  either way **clears that car’s history** (and hides its bell when off); other
  vehicles are untouched. Retention prunes entries older than the window. Backed by
  **SharedPreferences only** — no database.

### 2.2 Notifications (FCM)
- Push uses **Azure Notification Hubs → FCM v1**. Messages are **data‑only**,
  rendered locally by the app’s FCM service so the time is shown in the phone’s
  local timezone (parse a `timestampUtc` data field).
- The backend emits these event types in the message `data`:
  `trip.started`, `trip.ended`, `charge.in_progress`, `charge.complete`,
  `charge.error`, `signal.lost`. The `data` also includes `nickname`,
  `latitude`, `longitude`, `vin`, and `timestampUtc`. The app maps each event to a
  title/body and an icon (e.g., key / key‑off for trip start/stop,
  `signal_wifi_bad` for lost signal, charge icons for charge events).
- Because messages are data‑only, they render only while the OS keeps the app
  wakeable — hence the **Background activity** prompt above.

### 2.3 Networking & state (app side)
- Keep dependencies tiny: a minimal HTTP client using `HttpURLConnection` +
  `org.json` (no Retrofit/OkHttp needed). All calls are blocking and run off the
  main thread.
- A single `ViewModel` holds UI state plus a small **in‑memory, per‑VIN cache** of
  the last telemetry snapshot so paging back to an already‑loaded car is instant.
  Tapping a card pull‑to‑refreshes (re‑fetches and updates the cache).
- The **only** thing written to disk is the optional review‑only notification
  history (SharedPreferences).

### 2.4 Reference: FleetFoot’s app file shape (mirror, renamed for GoHenry)
FleetFoot ships ~7 Kotlin files; produce GoHenry equivalents under
`com.gohenry.app`:
`MainActivity.kt`, `GoHenryViewModel.kt`, `GoHenryApi.kt` (HTTP client + DTOs),
`GoHenryFcmService.kt`, `NotificationStore.kt` (SharedPreferences history),
`PushRegistrar.kt` (FCM token + Notification Hub install), `CarVisuals.kt`
(engine/icon/color helpers). Add Compose screen files as needed (carousel,
vehicle status, detailed data, notification setup, history).

---

## 3) Backend architecture (SQL‑free, standalone)

Build a new **.NET 8 isolated‑worker Azure Functions** app named **`GoHenry.Api`**
(plus small class libraries as below). It must do everything end‑to‑end without any
SQL. The existing FleetFoot/QoastQurrent backend already keeps its *hot path* in
Azure Table Storage; GoHenry takes that further and uses **Table Storage as the
sole store**, eliminating the SQL database those apps still use for the canonical
vehicle registry and trip/charge history.

### 3.1 Projects / solution layout
```
backend/
├── GoHenry.sln
├── src/
│   ├── GoHenry.Api/          HTTP + Timer + Queue triggers (the Functions host)
│   ├── GoHenry.Core/         Models + Ford payload normalizer (engine‑type aware)
│   ├── GoHenry.Storage/      Azure Table Storage repositories (THE datastore)
│   └── GoHenry.FordClient/   Typed Ford HTTP client (Polly retry) + token cache
└── tests/
    └── GoHenry.Tests/        xUnit + FluentAssertions
```

### 3.2 Responsibilities
1. **Ford OAuth (self‑contained).** Reuse the existing **Ford developer
   credentials** (client id/secret/app slug) but run the entire handshake in
   GoHenry’s own backend:
   - `POST /api/oauth/start?app={slug}` → issue a `state` GUID (stored in Table
     Storage) and return the Ford **authorize URL** for the phone to open in a
     browser.
   - `GET|POST /api/oauth/callback` (must be `AuthorizationLevel.Anonymous` — Ford
     redirects here) → validate `state`, exchange the `code` for tokens, **store the
     refresh token in Key Vault** as `ford-refresh-{userId}-{slug}`, discover VINs
     via Ford `/v1/garage`, and upsert vehicle rows into Table Storage. Mark the
     account **ACTIVE**.
   - Ford endpoints (same as the reference app): authorize/token under
     `https://api.vehicle.ford.com/...` (B2C), grant types `authorization_code` and
     `refresh_token`; vehicle list at `/v1/garage`, per‑vehicle status under
     `/v1/vehicles/...`. Refresh tokens are good ~90 days; surface a heuristic
     “re‑auth expected by” = lastRefresh + 90d.
2. **Polling.** A **timer‑triggered dispatcher** (e.g., every 1–2 minutes) fans the
   registered VINs onto a **storage queue**; a **queue‑triggered worker** mints/uses
   a cached Ford access token (~20 min lifetime, cached in‑process per slug+user),
   fetches the vehicle status, **normalizes** it, and writes the snapshot to the
   VIN’s Table row. Skip writes when the vehicle is idle and was idle last poll
   (keeps cost/noise low). All dispatcher/poll state lives in Table Storage.
3. **Activity detection.** From the telemetry stream, detect **trip start/stop**
   (ignition transitions), **charge in‑progress/complete/error** (plug events +
   charging status), and **lost signal** (active trip that stopped reporting). Keep
   a compact per‑VIN open‑activity state on the Table row; append finished
   trips/charges to a history table (Section 4).
4. **Push.** When a watched event fires and the per‑VIN toggle is on, send a
   **data‑only** message via **Azure Notification Hubs → FCM v1** to the tag(s) for
   that user/app, including `event`, `nickname`, `vin`, `latitude`, `longitude`,
   `timestampUtc`.
5. **REST surface** for the app (function‑key + `x-user-id` header on all app
   routes except the anonymous OAuth callback). Keep the routes identical in shape
   to FleetFoot’s so the app code is a clean port:

   | Method | Route | Purpose |
   | --- | --- | --- |
   | GET | `/api/fleet/vehicles` | caller’s vehicles w/ metadata (vin, model, nickname, modelYear, displayColor, engineType) |
   | GET | `/api/fleet/telemetry/{vin}` | current snapshot (soc/fuel, range, odometer, charging, pluggedIn, lat/long, ignition, temps, doorLocks, tires, alarm, …), ownership‑checked |
   | GET | `/api/fleet/notifications/{vin}` | per‑VIN toggles `{ start, stop, chargeInProgress, chargeComplete, chargeError, lostSignal }` |
   | POST | `/api/fleet/notifications/{vin}` | set those toggles |
   | POST | `/api/notifications/register` | upsert this install’s FCM token (body `{ installationId, fcmToken, app }`) |
   | GET | `/api/ford/account/status` | per‑slug Ford account status (linked, needsReauth, daysUntilReauth) |
   | POST | `/api/oauth/start?app={slug}` | begin Ford OAuth; returns `{ url }` |
   | GET\|POST | `/api/oauth/callback` | **anonymous** Ford redirect handler |

   Keep the telemetry/vehicle/notification DTO field names byte‑compatible with the
   FleetFoot contract documented in Section 2 so the Android client maps cleanly.

### 3.3 Tech stack (backend)
- .NET 8 isolated worker, Azure Functions v4.
- `Azure.Data.Tables` for storage, `Polly` for Ford retries,
  `Azure.Security.KeyVault.Secrets` for tokens, `Azure.Messaging.NotificationHubs`
  (or the Notification Hubs SDK) for push. **No Dapper, no EF, no SQL client.**
- Managed identity for Key Vault + Storage (no connection secrets in app settings
  where avoidable). xUnit + FluentAssertions for tests.

---

## 4) The SQL‑free data model (Azure Table Storage)

Use **one Storage account** with a few tables. Recommended design (tune names as
needed but keep it this simple):

### Table `Vehicles`
- **PartitionKey** = `userId` (the install/account GUID).
- **RowKey** = `vin`.
- Columns: `Nickname`, `Model`, `ModelYear`, `DisplayColor`, `EngineType`
  (`BEV`/`PHEV`/`HEV`/`GAS`), `FordSlug`, plus **current snapshot** fields written
  by the poller: `SnapSocPct`, `SnapFuelLevel`, `SnapRangeValue/Unit`,
  `SnapOdometerValue/Unit`, `SnapChargingStatus`, `SnapPluggedIn`, `SnapLatitude`,
  `SnapLongitude`, `SnapIgnition`, `SnapGearLever`, `SnapDoorLocks`,
  `SnapTirePressureStatus`, `SnapAlarmStatus`, `SnapOutsideTemp*`, `SnapInteriorTemp*`,
  `SnapOilLifePct`, `CapturedAt`. **Per‑VIN poll/activity state:** `LastPolledAt`,
  `LastWasActive`, `LastOdometerKm`, `HasOpenActivity`, `LostSignal`, `TrackingPaused`.
  **Per‑VIN notification toggles:** `NotifyStart`, `NotifyStop`,
  `NotifyChargeInProgress`, `NotifyChargeComplete`, `NotifyChargeError`,
  `NotifyLostSignal`.
- This single row powers the entire FleetFoot‑style read surface with **no joins
  and no SQL**.

### Table `FordAccounts`
- **PartitionKey** = `userId`, **RowKey** = `slug`. Columns: `Status`
  (`ACTIVE`/`NEEDS_REAUTH`/`UNLINKED`), `IsPrimary`, `KvSecretName`,
  `LastRefreshAt`. (Token value itself lives in **Key Vault**, never here.)

### Table `OAuthStates`
- **PartitionKey** = `"state"`, **RowKey** = `stateGuid`. Columns: `UserId`, `Slug`,
  `CreatedAt`. Short‑lived; prune on use or by a daily timer.

### Table `Installs`
- **PartitionKey** = `userId`, **RowKey** = `installationId`. Columns: `FcmToken`,
  `App`, `UpdatedAt`. Used to register/refresh Notification Hub installations.

### Table `TripHistory` and `ChargeHistory` (optional but recommended)
- **PartitionKey** = `vin`, **RowKey** = reverse‑ticks timestamp (so newest sorts
  first). Columns capture start/end time, start/end odometer or SoC, distance,
  duration, and end location. These replace what the reference app stored in SQL.
  Keep retention short (e.g., 30–90 days) via a daily prune timer — this is a hobby
  app.

### Table `Meta` (singletons)
- **PartitionKey** = `"_meta"`, RowKeys like `registry`, `pollSettings`. Holds the
  poll cadence, per‑poll write toggles, last registry refresh time, last activity
  sweep time — so the dispatcher reads everything from Table Storage on each tick.

> **Why Table Storage:** it is schemaless, costs cents/month at this scale, needs no
> server to keep warm, and the reference app already proves the hot‑path access
> patterns. It satisfies “robust + minimal overhead + no SQL” cleanly.

---

## 5) Provisioning — `gohenry.sh` (one‑shot, idempotent)

Provide a single menu‑driven Bash script `backend/scripts/gohenry.sh` (model it on
the reference `qoast.sh`, but **delete all SQL Server / SQL Database steps**). It
must prompt once for inputs (`SUFFIX`, `FORD_CLIENT_ID`, `FORD_CLIENT_SECRET`,
`FORD_SLUG`) and remember them for the session, then provision and deploy in one
run from **Azure Cloud Shell**. Subcommands: `provision`, `deploy` (code‑only),
`reauth`, `teardown`, `rebuild` (soft), `full`. Resources it creates (all in one
resource group, e.g. `rg-gohenry-<suffix>`):

| Resource | SKU / Tier | Purpose |
| --- | --- | --- |
| Resource Group | — | Container for everything |
| Storage Account | Standard_LRS | Functions runtime **and** the GoHenry Tables/Queue datastore |
| Key Vault | Standard, RBAC | Per‑user Ford refresh tokens |
| Notification Hub Namespace + Hub | Free | FCM push to the app |
| Function App | Flex Consumption, .NET 8 isolated, Linux | The backend |

The script must also: wire the Function App’s **managed identity** (Key Vault
Secrets Officer + Storage Table/Queue Data Contributor), set every app setting
(Ford client id/secret/slug, OAuth callback URL, Notification Hub connection +
name, Key Vault URI, Storage connection), and **print the OAuth callback URL** to
paste into the Ford Developer Portal’s allowed redirect list. **Explicitly note**
what it does *not* do: register the callback URL in Ford’s portal, configure FCM v1
credentials on the Hub (upload the Firebase service‑account JSON), or build the APK.

Estimated cost at this scale: **~$1–3/month** (no SQL to pay for or keep warm).

---

## 6) Firebase / FCM setup (GoHenry’s own project)

- Create a **new, dedicated Firebase project** for GoHenry (do not reuse any
  existing project). Register the Android app `com.gohenry.app`, download
  `google-services.json` into `app/`, and wire the `com.google.gms.google-services`
  Gradle plugin.
- In Firebase, generate an **FCM v1 service‑account JSON** and upload it to the
  Notification Hub (Portal → the hub → Google (FCM v1)). Document this clearly — it
  is the one manual step that makes live push work.

---

## 7) Android project specifics

- Kotlin + Jetpack Compose, **Material 3 Expressive**; Navigation‑Compose for the
  screen graph; Hilt optional (a single ViewModel may not need DI). `minSdk 26`,
  `targetSdk` current. Its **own** Gradle build (independent of any other repo).
- Material Symbols (Outlined) vector drawables for engine type and status icons —
  keep the icon‑intensive look. Engine‑type aware throughout: BEV → `electric_car`,
  HEV → a fuel/recirculate icon, hide charge‑only UI for HEV.
- **Configuration** via git‑ignored `local.properties` → `BuildConfig` fields:
  ```
  backend.baseUrl=https://<your-gohenry-api>.azurewebsites.net/api/
  backend.userId=<this install's x-user-id GUID>
  backend.functionKey=<Function App default host key>
  ```
  Default base URL to the emulator loopback `http://10.0.2.2:7071/api/` for local
  `func start` development.
- Provide friendly error surfacing: redact the function key in any displayed URL,
  and offer a small “config summary” (base URL, user id, key‑present length) to make
  the most common misconfiguration obvious without a debugger.

---

## 8) Documentation set (part of “done”)

Write clear, beginner‑friendly docs:
1. **Root `README.md`** — what GoHenry is, the architecture diagram (Android →
   Functions → Table Storage; Notification Hub → FCM; Ford OAuth → Key Vault),
   repo layout, and a “5 cars / 5 phones / no SQL” statement of intent.
2. **`backend/README.md`** — local `func start` + Azurite, app settings keys, the
   Table Storage data model, and how to deploy.
3. **`backend/scripts/README.md`** — `gohenry.sh` reference and lifecycle.
4. **`GUIDES/azure-setup-guide.md`** — a **no‑terminal, browser‑only** walkthrough
   for a first‑time setup (≈45–60 min), plus the one‑shot script path.
5. **`app/README.md`** — build/run, `local.properties`, Firebase/FCM steps, and how
   to pair a phone (get an `x-user-id`, link Ford in‑app, see cars appear).
6. A short **`docs/` architecture note** describing the activity‑detection rules and
   the Table schema decisions.

Keep instructions explicit and copy‑pasteable; assume a hobbyist reader.

---

## 9) Tests

- **Backend (`GoHenry.Tests`, xUnit + FluentAssertions):** the Ford payload
  normalizer (engine‑type → which fields, BEV vs HEV), activity detection
  (trip/charge/lost‑signal state transitions), the Table repository round‑trips
  (using the Azurite emulator), and DTO serialization matching the documented
  contract.
- **Android (`:app:testDebugUnitTest`):** the API DTO parsing (`GoHenryApi`),
  engine‑type visual mapping (`CarVisuals`), and the notification‑history store
  (capture, day‑grouping, retention pruning, clear‑on‑toggle).
- Build commands to document and verify:
  - Android: set `JAVA_HOME` to Android Studio’s bundled JBR, then
    `./gradlew :app:assembleDebug` and `./gradlew :app:testDebugUnitTest`.
  - Backend: `cd backend && dotnet build` and `dotnet test`.

---

## 10) Build order (suggested)

1. Scaffold the backend solution + Table Storage repositories + DTOs.
2. Implement Ford client + OAuth start/callback (reuse existing Ford creds) → verify
   you can link an account and discover VINs into the `Vehicles` table.
3. Implement the poller (dispatcher + worker) + normalizer → live snapshots land on
   the VIN row.
4. Implement activity detection + Notification Hub push.
5. Implement the REST surface (`/api/fleet/*`, `/api/notifications/register`,
   `/api/ford/account/status`).
6. Write `gohenry.sh`; provision a real environment; smoke‑test the endpoints.
7. Build the Android app screen‑by‑screen (carousel → vehicle status → detailed
   data → notification setup → history), wiring each to its endpoint.
8. Wire FCM (own Firebase project) end‑to‑end; verify a real push renders locally.
9. Write tests + the documentation set. Polish empty/error states and Material 3
   Expressive styling.

---

## 11) Definition of done

- A hobbyist can: provision the backend with `gohenry.sh`, paste the callback URL
  into the Ford portal, upload the FCM JSON, build & install the APK on up to 5
  phones, link Ford in‑app, and watch up to 5 cars appear with live status, trips,
  charging, and working push notifications + on‑device history.
- **Zero SQL** anywhere; all server state in Azure Table Storage; Ford tokens only
  in Key Vault.
- Fully independent of FleetFoot/SashaSync/QoastQurrent and any of their Azure or
  Firebase resources (Ford developer credentials reused; nothing else).
- Clean, friendly Material 3 Expressive UI with the icon‑intensive FleetFoot feel.
- Backend and Android tests pass; documentation is clear and complete.
