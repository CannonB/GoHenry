# GoHenry 🚗

**GoHenry** is a small, friendly **hobby vehicle tracker** for up to **5 cars across
5 phones**. It shows each car's live status (state of charge / fuel, range, location,
ignition, charging), pushes you a notification when a car **starts a trip, parks,
starts/finishes charging, hits a charge fault, or loses signal mid-trip**, and keeps a
tidy on-device history you can scroll back through.

It is modeled on the **FleetFoot** app and is **wholly independent** of FleetFoot /
SashaSync / QoastQurrent — it shares **no** Azure resources, **no** Firebase project,
and **no** database. The only thing reused is the **Ford developer credentials**.

> **No SQL. Anywhere.** Every byte of server state lives in **Azure Table Storage**.
> Ford refresh tokens live in **Azure Key Vault** (a secret store, not a database).
> This keeps the backend tiny and cheap — roughly **$1–3 / month** at hobby scale.

---

## Architecture

```
   ┌─────────────────┐        HTTPS (x-user-id + function key)
   │  GoHenry app     │  ─────────────────────────────────────────┐
   │  (Android,       │                                            │
   │  Material 3      │  ◀── FCM data-only push ──┐                ▼
   │  Expressive)     │                           │      ┌──────────────────────┐
   └─────────────────┘                           │      │  Azure Functions      │
                                                  │      │  (.NET 8 isolated)     │
                                                  │      │                        │
                        ┌─────────────────────┐   │      │  • /api/fleet/*        │
                        │ Azure Notification   │◀──┴──────│  • /api/oauth/*        │
                        │ Hubs → FCM v1        │   push   │  • poller (timer+queue)│
                        └─────────────────────┘          │  • /notifications/...  │
                                                          └───────┬───────┬───────┘
                                                                  │       │
                                            Ford refresh token    │       │  ALL state
                                            (per user, per slug)   ▼       ▼  (no SQL)
                                                        ┌───────────────┐ ┌────────────────┐
                                                        │  Key Vault     │ │ Table Storage   │
                                                        │  ford-refresh-*│ │ Vehicles, ...    │
                                                        └───────────────┘ └────────────────┘
                                                                  ▲
                                                                  │ OAuth code → token
                                                        ┌──────────────────────┐
                                                        │  Ford developer API   │
                                                        └──────────────────────┘
```

- **App → Functions**: the app reads the fleet/telemetry and writes notification
  toggles over plain HTTPS, authenticating with an `x-user-id` header + a Functions
  host key.
- **Poller**: a timer fans each VIN onto a storage **queue**; a queue worker mints a
  Ford access token (from the Key Vault refresh token), fetches + normalizes the
  status, detects trip/charge/lost-signal transitions, persists the snapshot, and
  fires FCM for whichever events you enabled.
- **Push**: Azure **Notification Hubs → FCM v1**, **data-only** messages the app
  renders locally (so it can localize the timestamp) and optionally keeps in history.
- **Ford auth**: a fully self-contained OAuth handshake; the refresh token is written
  straight to **Key Vault** and never touches the phone or Table Storage.

---

## Notifications

GoHenry watches each linked car and pushes you a small banner when something
happens. Every alert is a **data-only FCM v1 message** the app renders itself
(so times show in *your* phone's zone), and — if you opt in — keeps in a tidy
on-device history. **No SQL anywhere:** the backend's per-car state is a single
Azure Table row; the phone's alert history is just SharedPreferences.

### What triggers each alert

The poller runs at a **configurable cadence (1–10 min, default 2 min)** — set it on
the Settings screen ("Polling cadence") — compares this poll to the last, and fires
an event the moment a transition is detected (logic lives in
`backend/src/GoHenry.Core/Normalization/ActivityDetector.cs`). An alert is only
pushed if you've enabled that event for that car.

| Event (`data.event`) | Fires when | Source signal | Per-VIN enable flag | Default title → body |
|---|---|---|---|---|
| `trip.started` | ignition turns on (`isActive && !wasActive`) | ignition (`VehicleSnapshot.IsActive`) | `NotifyStart` | **Start — Ignition on** → "{nickname} started moving" |
| `trip.ended` | ignition turns off with a trip open | ignition + open-trip state | `NotifyStop` | **Stop — Ignition off** → "{nickname} parked". If a road trip is still open (and *end-on-stop* is off) the alert becomes **Stop — Active road trip** → "Active Roadtrip - end it?" and **deep-links into the road-trip tray** so a tap can end it. |
| `signal.lost` | the Ford feed is **reachable** but the car's own data is **frozen**: ignition `ON` while **both** the odometer **and** the Ford telemetry timestamp (`FordTelemtryTimeStamp`/`$.updateTime`) stay unchanged for the configured number of **consecutive successful polls** (5–20, default 10; fires once, re-arms when data advances or the ignition turns off) | per-poll change in `OdometerKm` + Ford telemetry timestamp while `IsActive` | `NotifyLostSignal` | **Car lost signal** → "{nickname} stopped reporting movement" (carries last-known position) |
| `telemetryfeed.lost` | no **successful** Ford read for the configured number of **missed polls** (default 10 × cadence ≈ 20 min; `CapturedAt` staleness; fires once, re-arms on the next good read) — the Ford feed itself is unreachable / needs re-auth | time since last successful poll (`CapturedAt`) | `NotifyTelemetryFeedLost` | **Telemetry feed lost** → "{nickname} no telemetry from the car" (carries last-known position) |
| `charge.in_progress` | charge-display status changes **to `IN_PROGRESS`** | `SoCChargeDisplayStatus` (Ford `xevBatteryChargeDisplayStatus`) phase | `NotifyChargeInProgress` | **Charge in progress** → "{nickname} is charging" |
| `charge.complete` | charge-display status changes **to `COMPLETED`** | `SoCChargeDisplayStatus` phase | `NotifyChargeComplete` | **Charge complete** → "{nickname} finished charging" |
| `charge.error` | charge-display status changes **to an `ERROR`/`FAULT` state** (and not the above) | `SoCChargeDisplayStatus` phase | `NotifyChargeError` | **Charge error** → "Charging problem" |
| `tire.pressure_warn` | any wheel leaves "normal" status (fires once, re-arms when all wheels are normal again) | per-wheel `tirePressureStatus[]` (`VehicleSnapshot.IsTireWarning`) | `NotifyTirePressure` | **Tire pressure warning** → "Tire warning: {wheel(s)}" |
| `alarm.triggered` | the security alarm starts sounding (fires once, re-arms when it clears) | `alarmStatus` (`VehicleSnapshot.IsAlarmTriggered`) | `NotifyAlarm` | **Alarm triggered** → "Alarm triggered" |
| `roadtrip.started` | a road trip is **auto-started** on first movement (auto-start enabled) | `trip.started` with no open trip | `NotifyRoadTripStart` | **Road trip started** → "Started "{trip name}"" |
| `roadtrip.ended` | a road trip is **auto-closed** (gone idle, or past the max-age cap) | idle/max-age safety net | `NotifyRoadTripEnd` | **Road trip ended** → "Ended "{trip name}"" |

Every alert body is rendered on the phone in a single consistent shape:
**`{nickname} • {trigger detail} • {local time} • {latitude, longitude} • Alt {m} • Out {°C}`** —
the backend sends the trigger detail in `data.detail`, the position in
`data.latitude`/`data.longitude`, and (when telemetry provides them) the altitude
in `data.altitude` (metres) and outside temperature in `data.outsideTempC`;
`GoHenryFcmService` localizes the timestamp. The same body is what the in-app
notification history card shows.

The **`trip.ended`** alert additionally carries a **`Trip {x.x} km`** segment (right
after the trigger detail): the straight-line distance from the car's position when
its `trip.started` fired to where it parked. This is computed **server-side** by the
poller — the start position is captured on the ignition-on poll and stored on the
VIN row, then the great-circle gap to the parked position is stamped into the
`trip.ended` push as `tripDistanceKm`. Computing it on the backend (rather than on
the phone from its local alert history) keeps the figure correct even if the device
missed the start push or reinstalled the app. The segment is omitted when no start
position was captured for that trip.

Charge alerts only apply to cars that charge — the app hides those toggles for
HEV/gas vehicles. Tire-pressure and alarm alerts apply to **all** engine types.

### End-to-end flow

```
 Ford telemetry
      │  (poll every 1–10 min, configurable — Poll_Dispatcher → Poll_Worker)
      ▼
 FordTelemetryNormalizer ──▶ VehicleSnapshot
      │
      ▼
 ActivityDetector.Evaluate(prior, snap, now) ──▶ events[]  (+ next state persisted to the Table row)
      │
      ▼  for each event
 IsEnabled(VIN, event)?  ──no──▶ (drop)
      │ yes
      ▼
 Azure Notification Hubs ──▶ FCM v1 (data-only, tag user:{userId})
      │
      ▼
 GoHenryFcmService.onMessageReceived
      ├─▶ system banner on channel "gohenry_alerts"  (time localized to the phone)
      ├─▶ NotificationStore.record(...)   (only if this VIN's capture is ON)
      └─▶ AppEvents.notifyPushReceived(vin)  (refresh the live card if foregrounded)
```

### How alerts are tracked & stored locally

Local capture is **review-only** and lives entirely on the phone
(`NotificationStore.kt`, SharedPreferences file `notif_history` — no database):

- **Off by default**, opted in **per vehicle** (per VIN) on the notification-setup
  screen.
- A **device-wide retention window of 1–7 days (default 3)**; anything older is
  pruned automatically. A safety cap of 200 entries/VIN guards against runaway
  volume.
- **Toggling a car's capture on *or* off wipes that car's stored history** and
  leaves your other cars untouched.
- The **full** push payload is kept, so the history screen can show every field;
  you can export what's on screen to **CSV** via the share button.

There is **no server-side copy of delivered notifications** — the backend only keeps
trip/charge *history* tables. The on-device store is the sole record of the alerts
themselves.

### Timing & limitations

- Detection latency is up to one poll (**~2 min**); a trip shorter than a poll
  interval may be missed.
- The two "offline" alerts share one **Settings ▸ Lost signal** threshold (a count of
  missed/frozen polls, **5–20**, default **10**) and both drive the hero-card wifi icon,
  which shows a small caption — **"Telemetry"**, **"Signal"**, or **"Both"** — naming the
  active loss:
  - `telemetryfeed.lost` fires when telemetry goes **stale** — polls keep failing
    (vehicle offline / unreachable / Ford re-auth needed). Dwell time = threshold ×
    the polling cadence (e.g. 10 × 2 min ≈ 20 min).
  - `signal.lost` fires when the feed is **reachable but frozen**: the ignition is `ON`
    yet neither the odometer nor the Ford telemetry timestamp advances for `threshold`
    consecutive successful polls.
  - Both fire **once** and re-arm after the next good/advancing read.
- `tire.pressure_warn` and `alarm.triggered` fire **once** on the rising edge and
  re-arm only after the condition clears (all wheels normal / alarm no longer
  sounding), so a persistent fault doesn't repeat every poll.
- If the Ford token needs re-auth, that car's poll is skipped (no alerts until you
  re-link); a Ford API hiccup simply yields no events that cycle.
- Backgrounded, the live-card refresh is skipped, but the banner and local capture
  still happen.

### Privacy

Locations (lat/long) attached to alerts are stored **only on the device**, within
the retention window, and never leave the phone except through a **CSV share you
explicitly trigger**.

> **Self-check:** Settings ▸ **Notification diagnostics** shows push permission,
> the `gohenry_alerts` channel status, FCM registration, last push received, and
> per-car capture — plus a **Send test notification** button to verify the whole
> chain on-device.

---

## Road trips

A **road trip** is a named, durable journey that groups one car's individual trips
(ignition cycles) **and** the notification events that happen while it's open — so a
weekend away that spans many start/stop/charge alerts reads as **one** entry.

- **Start/Stop** from the **Road trips tray** — a bottom sheet opened by the wide
  **Road trips** button (centered between the two bottom corner buttons on the home
  screen) or the **Road trips** button on a car's status screen. The tray's pinned
  header starts/stops the journey; pull it up to scroll the trip **history**. Naming
  is optional — blank gets a dated default.
- **Rename** any trip from its **detail screen** (pencil icon → a bottom-sheet tray).
- The home hero status box shows a **Roadtrip Active** line while a journey is open.
- While a trip is open, the poller **stamps every detected event** (start, stop,
  charge, tire, alarm) onto its timeline and rolls up **trips · charges · distance ·
  events**. Distance is an odometer-delta estimate.
- The trip's **detail screen** header shows the trip name, the **vehicle nickname**,
  and the rolled-up stats; the trip's **end time is the timestamp of its last event**
  (not the moment Stop/auto-close ran). The timeline below shows each event in a
  compact **two-line row**: line 1 is the action with its local date/time alongside,
  line 2 is the extra detail (lat/long, altitude, outside temp). It also flags whether
  the trip was **auto-started** / **auto-closed**. A **delete** button (trash icon)
  permanently removes the trip and its timeline.
- **Road trip history** is also reachable from the home screen's bottom-left
  **history split button**: its primary area opens notification history (default),
  and its **▼** menu offers **Notification history** or **Road trip history**. The
  road-trip history screen is a swipe-to-filter carousel (all cars, then one page
  per car); its top-bar **share** button exports the visible page — every trip with
  its full event timeline — to **CSV** and opens the share sheet.

### Automation (Settings ▸ Road-trip automation)

- **Auto-start** (off by default): when a car starts moving with no open trip, a
  road trip opens automatically (`startMethod="auto"`), capturing the whole driving
  session hands-free.
- **Auto-close safety net** ends a forgotten open trip once it has been **idle**
  (no events) for *N* hours (**2–12, default 12**) **or** has run past a hard
  **max-age** cap of *N* days (**1–7, default 7**), stamping `endReason="auto"`.
- **End the trip on stop** (off by default): when a car's **stop** (`trip.ended`)
  push fires, any open road trip is closed automatically (`endReason="stop"`).
- These are **app-wide** settings stored on the shared meta row (no SQL). Optional
  `roadtrip.started` / `roadtrip.ended` pushes announce the automated transitions
  (per-VIN toggles on the notification screen).

**Server-authoritative, so it survives a reinstall.** State lives entirely in the
`RoadTrips` Azure Table plus an active-trip pointer denormalized onto the car's row;
the app's telemetry response carries `activeRoadTripId/Name/StartedAt`, so after a
fresh install the open trip and full history rehydrate with **no extra call** and
**no SQL**. One trip per car may be open at a time (a second `start` returns 409).

---

## Repository layout

```
GoHenry/
├─ README.md                     ← you are here
├─ GoHenry-build-prompt.md       ← the full build brief this repo implements
├─ backend/                      ← .NET 8 isolated Azure Functions (SQL-free)
│  ├─ GoHenry.slnx
│  ├─ src/
│  │  ├─ GoHenry.Core/           ← models, Ford telemetry normalizer, activity detector
│  │  ├─ GoHenry.Storage/        ← Azure Table Storage entities + repository (the datastore)
│  │  ├─ GoHenry.FordClient/     ← typed Ford HTTP client + token cache
│  │  └─ GoHenry.Api/            ← the Functions host (REST + OAuth + poller + FCM)
│  ├─ tests/GoHenry.Tests/       ← xUnit tests (normalizer, activity detection, contract)
│  └─ scripts/                   ← gohenry.sh one-shot provisioner (no SQL resources)
├─ app/                          ← Android app (com.gohenry.app, Material 3 Expressive)
├─ docs/                         ← architecture note (activity rules, table schema)
└─ GUIDES/                       ← beginner, browser-only Azure setup walkthrough
```

---

## Get started

| You want to… | Read |
| --- | --- |
| **Install the backend on Windows (guided, asks questions)** | [`GUIDES/install-backend.md`](GUIDES/install-backend.md) → `Install-GoHenry.ps1` |
| Stand up the cloud backend with one command (macOS/Linux) | [`backend/scripts/README.md`](backend/scripts/README.md) |
| Set everything up in the browser, no terminal | [`GUIDES/azure-setup-guide.md`](GUIDES/azure-setup-guide.md) |
| Run/develop the backend locally | [`backend/README.md`](backend/README.md) |
| Build & install the Android app | [`app/README.md`](app/README.md) |
| **Add or remove a tracked car (up to 5)** | [Adding & removing vehicles](#adding--removing-vehicles) (below) |
| **Bring the backend back after an Azure outage** | [Recovering after an Azure outage](#recovering-after-an-azure-outage) (below) |
| Understand how it works inside | [`docs/architecture.md`](docs/architecture.md) |

### TL;DR

```powershell
# Windows — interactive: provisions Azure + deploys the backend, asking you questions
cd backend\scripts
.\Install-GoHenry.ps1
```

```bash
# macOS/Linux — the Bash equivalent
cd backend/scripts
cp gohenry.env.example gohenry.env     # add your Ford client id/secret
./gohenry.sh full                      # creates RG, Storage, Key Vault, Notification Hub, Function App
```

---

## Adding & removing vehicles

A Ford OAuth sign-in unlocks **exactly one vehicle**. To track several cars you register
a **separate Ford developer app** (its own *client id* + *client secret* and a short
**slug**) for each extra car — all sharing the **same** OAuth callback URL. GoHenry
supports the **primary** app plus up to **4 extras = 5 cars total**.

Throughout this section, replace the placeholders with your own values:

| Placeholder | What it is | Where to find it |
| --- | --- | --- |
| `<suffix>` | The unique resource tag you chose at install (e.g. `henry`). Appears in every resource name. | Azure Portal resource group `rg-gohenry-<suffix>` |
| `<userId>` | The app's user id. | `backend.userId` in the app's `local.properties` (default `henry`) |
| `<slug>` | Short tag for an **extra** Ford app (e.g. `car2`). Lowercase letters/numbers only. | You choose it |
| `<VIN>` | The car's 17‑character VIN. | The car's card in the app, or the `Vehicles` table |

> The **primary** car uses the app settings `Ford__ClientId` / `Ford__ClientSecret`.
> Each **extra** car uses `Ford__Apps__<slug>__ClientId` / `Ford__Apps__<slug>__ClientSecret`.
> The steps below are for **extra** cars; to change the primary car's credentials, re-run
> the installer with new `-FordClientId` / `-FordClientSecret`.

### ➕ Add a vehicle

1. **Register a new Ford developer app** for the car (its own client id + secret). On
   that app, register the **same** callback URL as everything else:
   `https://func-gohenry-<suffix>.azurewebsites.net/api/oauth/callback`
2. **Tell the backend about it.** Passing `-ExtraFordApps` puts the installer in a
   lightweight **“manage Ford apps”** mode: it writes *only* the new
   `Ford__Apps__<slug>__*` settings on the existing Function App and **does not create
   or update any other resource**:
   ```powershell
   cd backend\scripts
   .\Install-GoHenry.ps1 -Suffix <suffix> -ExtraFordApps @(@{ Slug='<slug>'; ClientId='<id>'; ClientSecret='<secret>' })
   ```
   Leave off `ClientId`/`ClientSecret` to be prompted for just those values. Running the
   installer with **no arguments** still offers **“Add another vehicle now?”** during a
   full install.
   <details><summary>macOS/Linux (Bash) equivalent</summary>

   ```bash
   cd backend/scripts
   ./gohenry.sh add-ford-app <slug> <id> <secret>   # omit id/secret to be prompted
   ```
   </details>
   <details><summary>Prefer a raw one-liner without either script?</summary>

   ```bash
   az functionapp config appsettings set -n func-gohenry-<suffix> -g rg-gohenry-<suffix> \
     --settings Ford__Apps__<slug>__ClientId="<id>" Ford__Apps__<slug>__ClientSecret="<secret>"
   ```
   </details>
3. **Link it in the app.** Open GoHenry → tap the **cog** (Settings, top‑right) →
   **Ford authorization** → expand the new **`<slug>`** card → tap **Link** and sign in
   to that car's Ford account.
4. **Wait ~2 minutes**, then **pull down** on the carousel to refresh. The new car
   appears. No app reinstall is needed — the app shows one card per configured slug
   automatically.

> Up to **4 extra slugs** are allowed. Repeat steps 1–4 for each additional car.

### ➖ Remove a vehicle

Removal is a **backend** operation (there is intentionally no in‑app "delete car"). The
fastest path mirrors adding — a lightweight script run that touches **only** that slug's
Ford settings and Key Vault token, and **nothing else**:

```powershell
cd backend\scripts
.\Install-GoHenry.ps1 -Suffix <suffix> -RemoveFordApps <slug>
```
```bash
# macOS/Linux (Bash) equivalent
cd backend/scripts
./gohenry.sh remove-ford-app <slug>
```

Both delete `Ford__Apps__<slug>__ClientId` / `__ClientSecret` **and** the matching
`ford-refresh-<userId>-<slug>` Key Vault secret(s), then let the Function App restart.

<details><summary>Prefer to run the raw Azure CLI yourself? Here's exactly what those commands do.</summary>

1. **Remove the Ford app credentials** so the car stops being polled:
   ```bash
   az functionapp config appsettings delete -n func-gohenry-<suffix> -g rg-gohenry-<suffix> \
     --setting-names Ford__Apps__<slug>__ClientId Ford__Apps__<slug>__ClientSecret
   ```
2. **Delete the stored Ford refresh token** from Key Vault:
   ```bash
   az keyvault secret delete --vault-name kv-gohenry-<suffix> --name ford-refresh-<userId>-<slug>
   # optional, if you want it gone immediately (soft-delete is on by default):
   az keyvault secret purge --vault-name kv-gohenry-<suffix> --name ford-refresh-<userId>-<slug>
   ```
</details>

To also drop the **cached account + vehicle rows** so the car disappears immediately
(otherwise it ages out on the next refresh):

```bash
az storage entity delete --account-name stgohenry<suffix> --auth-mode login \
  --table-name FordAccounts --partition-key <userId> --row-key <slug>
az storage entity delete --account-name stgohenry<suffix> --auth-mode login \
  --table-name Vehicles      --partition-key <userId> --row-key <VIN>
```
<details><summary><code>--auth-mode login</code> fails with a permissions error?</summary>

Either grant your account the **Storage Table Data Contributor** role on the storage
account, or swap `--auth-mode login` for an account key:
```bash
KEY=$(az storage account keys list -n stgohenry<suffix> -g rg-gohenry-<suffix> --query "[0].value" -o tsv)
az storage entity delete --account-name stgohenry<suffix> --account-key "$KEY" \
  --table-name Vehicles --partition-key <userId> --row-key <VIN>
```
</details>

After removal, **refresh the app**: the `<slug>` card and the car drop off once the
Function App restarts (a few seconds) — open **Settings → Ford authorization** and tap the
refresh icon, then **pull down** on the carousel. Old `TripHistory` / `ChargeHistory` rows
are harmless; delete them the same way (by `<VIN>`) if you want a totally clean slate.

> **Nuke everything:** to retire the entire deployment (all cars, all resources), just
> delete the resource group: `az group delete -n rg-gohenry-<suffix> --yes`.

See [`GUIDES/install-backend.md`](GUIDES/install-backend.md#tracking-more-than-one-vehicle-up-to-5)
for the full installer reference (interactive prompts, non‑interactive flags, and the
Linux/macOS `gohenry.sh` `FORD_EXTRA_APPS` equivalent).

---

## Recovering after an Azure outage

GoHenry is **stateless compute over durable storage**, so an outage is almost never
data‑loss — it's just a service that needs nudging back to life. Your data (Ford tokens
in Key Vault, vehicles/trips/charges in Table Storage) survives a Function App restart,
redeploy, or regional blip. Work the list **top to bottom and stop as soon as cars load**.

> **First, is it actually Azure?** Check [Azure status](https://status.azure.com). If a
> region is genuinely down, there is nothing to fix — wait for recovery, then re‑run
> `status` below to confirm. The app keeps showing the **last cached** telemetry meanwhile.

| Placeholder | Value |
| --- | --- |
| `<suffix>` | your install tag (resource group is `rg-gohenry-<suffix>`) |

**1. Confirm what's actually up.** This lists every resource and the live endpoints
without changing anything:
```bash
cd backend/scripts
./gohenry.sh status                 # macOS/Linux
```
```powershell
# Windows (equivalent quick check)
az resource list -g rg-gohenry-<suffix> -o table
az functionapp show -n func-gohenry-<suffix> -g rg-gohenry-<suffix> --query "state" -o tsv
```

**2. Function App stopped or wedged? Start / restart it.** (Safe and non‑destructive —
settings and data are untouched.)
```bash
az functionapp start   -n func-gohenry-<suffix> -g rg-gohenry-<suffix>
az functionapp restart -n func-gohenry-<suffix> -g rg-gohenry-<suffix>
```
Give it ~60 seconds, then probe the health of the API:
```bash
curl -s -o /dev/null -w "%{http_code}\n" https://func-gohenry-<suffix>.azurewebsites.net/api/fleet/vehicles
```
A `401`/`200` means the host is alive (401 just means the function key/header is missing
from the bare curl — that's fine). Connection errors mean it's still coming up.

**3. Cars still missing after the host is healthy? Re‑apply settings, then redeploy.**
This **does not recreate infrastructure** — it just re‑pushes config and code onto the
existing resources:
```bash
./gohenry.sh settings               # re-apply app settings + RBAC
./gohenry.sh deploy                 # rebuild + publish the Functions code
# or both at once:
./gohenry.sh rebuild
```
On Windows you can re‑run the installer over the existing deployment (every step is
idempotent — it fixes config/RBAC/code without disturbing data):
```powershell
.\Install-GoHenry.ps1 -Suffix <suffix>
```

**4. Managed‑identity / RBAC errors in the logs** (e.g. the app can't read Key Vault or
Storage after a long outage)? Role assignments occasionally need re‑applying; `settings`
above does that, or re‑run `./gohenry.sh` → option **3 (settings)**. Allow ~1 minute for
role propagation, then `restart` the Function App.

**5. Re‑link Ford only if tokens were lost.** Ford refresh tokens live in Key Vault and
survive outages, so you normally **do not** re‑link. If Key Vault itself was restored from
an earlier state, or a token expired during a long outage, just open the app →
**Settings (cog) → Ford authorization → Link/Re‑link** for any car showing *not linked*.
Nothing on the backend needs rebuilding for this.

**6. Worst case — a resource was deleted.** Because there's no SQL and nothing bespoke to
restore, you can recreate any missing piece by re‑running the installer; it skips what
already exists and only creates what's gone:
```powershell
.\Install-GoHenry.ps1 -Suffix <suffix>          # Windows
```
```bash
./gohenry.sh full                                # macOS/Linux
```
- **Key Vault deleted but within retention?** It's soft‑deleted — recover it instead of
  recreating, to keep your Ford tokens:
  `az keyvault recover --name kv-gohenry-<suffix>`
- **Notification Hub gone?** Re‑running the installer recreates it, but you must
  **re‑upload your Firebase FCM v1 service‑account JSON** (Portal → `gohenry` hub →
  Google (FCM v1)) — that credential is not stored by the scripts.
- **Storage account deleted?** Vehicle/trip history is lost (it lived there), but the app
  re‑discovers vehicles from Ford on the next refresh after you re‑link.

> **Rule of thumb:** restart → re‑apply settings → redeploy → (only if needed) recreate.
> You almost never get past step 2.

---

## Scope & intent

- **5 cars, 5 phones.** GoHenry is a hobby app — intuitive, robust, and well
  documented, but deliberately small. It is not multi-tenant SaaS.
- **No SQL.** All server state is in Azure Table Storage; Ford tokens are in Key Vault.
- **Independent.** No shared Azure/Firebase resources with any other app.
- **Material 3 Expressive**, icon-intensive UI in the FleetFoot spirit.

## License / use

Personal hobby project. Ford, FordPass, and related marks belong to Ford Motor
Company; GoHenry is an independent hobby client and is not affiliated with or
endorsed by Ford.
