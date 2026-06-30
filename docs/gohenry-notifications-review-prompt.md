# Prompt — End‑to‑End Review of GoHenry Notifications + README Write‑Up

> Paste the **Prompt** section below to an agent/engineer working in the GoHenry
> repo (`C:\Users\terrenca\OneDrive - Microsoft\FY26\GHCLI\GoHenry`). It drives a
> full review of how alerts are triggered, delivered, tracked, and stored; adds a
> detailed **Notifications** section to the README; and proposes (but does not
> build) additional alert types.
>
> **Hard constraints:** Keep GoHenry's light, **SQL‑free** design (Azure **Table
> Storage** + **Key Vault** only; on‑device **SharedPreferences** only). Do **not**
> change auth, poller, or telemetry behavior. **Implement no new notifications**
> without explicit written consent — the only deliverables are the review findings,
> the README section, and the suggestions list. Do **not** commit to git.

---

## Prompt

You are working on **GoHenry**, a SQL‑free **Azure Functions (.NET 8 isolated) +
Android (Jetpack Compose, Material 3)** hobby vehicle tracker for ≤5 cars / 5
phones. It reads Ford telemetry, detects trip/charge/signal transitions, and
pushes data‑only FCM alerts the app renders locally and can optionally keep in an
on‑device review history. Your job has five parts: **(1)** keep the design light
and SQL‑free, **(2)** perform an end‑to‑end review of the notification
functionality and validate exactly what triggers each alert and how it is tracked
and stored locally, **(3)** add a detailed **Notifications** write‑up to the
README, **(4)** suggest additional notifications worth adding, and **(5)** build a
**Notification diagnostics** panel on the Settings screen. Parts 1–4 are
review/proposal only; part 5 (§6) is a sanctioned implementation. Implement nothing
else without approval.

### 0. Ground rules
- **No SQL, stay light.** Server state lives only in **Azure Table Storage**; Ford
  refresh tokens live in **Key Vault**. The phone's alert history lives only in
  **SharedPreferences** (no Room/DB/DI). Do not introduce a database, a new hub, a
  new queue, or a new dependency.
- **Non‑destructive review.** Do not alter Ford OAuth, the garage call, the
  2‑minute poll cadence, the `ActivityDetector` logic, the `Snap*` columns, or the
  FCM payload contract. This task is read‑and‑document only (plus the README).
- **Consent gate.** Deliver the review (§1–3), the README section (§4), and the
  suggestions list (§5), then **stop** on those. Building new notification *types*
  requires explicit approval. The **Notification diagnostics** Settings panel (§6)
  **is** sanctioned — build it (read‑only/local‑only; it adds no new alert types
  and no backend/SQL).
- **No commits.** Leave all changes uncommitted for review.

### 1. Trace the trigger pipeline (backend) and validate it
Walk and confirm the full path, citing files/lines. Validate each claim against
the code — correct the write‑up (not the code) if reality differs.

1. **Poll dispatch & worker** — `backend/src/GoHenry.Api/Functions/PollerFunctions.cs`.
   - Timer `Poll_Dispatcher` runs `TimerTrigger("0 */2 * * * *")` (every 2 min),
     fans each non‑`TrackingPaused` VIN onto the `gohenry-poll` queue.
   - `Poll_Worker` mints/uses a cached Ford token, fetches + normalizes status,
     calls `ActivityDetector.Evaluate(prior, snap, now)`, persists snapshot +
     activity state to the VIN's single Table row, then for each detected event:
     appends history (trip/charge) and sends FCM **only if** `IsEnabled(entity, ev)`.
2. **Transition detection** — `backend/src/GoHenry.Core/Normalization/ActivityDetector.cs`
   (pure, unit‑tested). Validate each trigger's exact condition:
   - `trip.started` — `IsActive && !prior.WasActive` (ignition rising edge).
   - `trip.ended` — `!IsActive && prior.WasActive` **and** an open trip existed.
   - `signal.lost` — open trip, not already raised, and `now − lastSeenActive ≥
     LostSignalThreshold` (**20 minutes**). Fires once per trip (`LostSignalAlreadyRaised`).
   - `charge.in_progress` — `IsCharging && !prior.WasCharging`.
   - `charge.complete` — charging stopped (`!IsCharging && prior.WasCharging`) and
     **not** a fault.
   - `charge.error` — charging stopped **with** fault, **or** still charging while
     `IsChargeFault` (fault wins over a clean completion).
   - Confirm how `IsActive` / `IsCharging` / `IsChargeFault` are derived in
     `VehicleSnapshot` / `FordTelemetryNormalizer` and note any engine‑type nuances
     (HEV vs BEV charging).
3. **Per‑VIN enablement** — `PollerFunctions.IsEnabled` maps each event to the
   Table flags `NotifyStart`, `NotifyStop`, `NotifyChargeInProgress`,
   `NotifyChargeComplete`, `NotifyChargeError`, `NotifyLostSignal`. Confirm where
   these are set (`/api/fleet/notifications/{vin}` in `FleetFunctions.cs`,
   surfaced on the app's Notification‑setup screen) and defaults.
4. **Message build & event vocabulary** — `PollerFunctions.BuildMessage` +
   `backend/src/GoHenry.Core/Models/NotificationEvents.cs`. The canonical event
   strings are `trip.started | trip.ended | charge.in_progress | charge.complete |
   charge.error | signal.lost`. Data payload always carries `event`, `vin`,
   `nickname`, `timestampUtc` (ISO‑8601), plus `latitude`/`longitude` when known.
5. **Delivery** — `backend/src/GoHenry.Api/Notifications/NotificationHubPublisher.cs`.
   Azure Notification Hubs → **FCM v1 data‑only**, targeted by tag
   `user:{userId}` (installs tagged `user:{userId}` + `app:gohenry`). Confirm the
   local‑dev **no‑op** path when no hub is configured, and that `title`/`body` are
   injected into `data` so the app renders everything client‑side.
6. **Registration** — `NotificationsRegisterFunction.cs` + app `PushRegistrar.kt`:
   confirm token‑rotation upsert of the NH installation.

### 2. Trace the receive / track / store pipeline (app) and validate it
1. **Receive** — `app/.../GoHenryFcmService.kt#onMessageReceived`: switches on
   `data["event"]`, builds localized title/body (timestamp rendered in the
   **phone's** local zone), posts a high‑importance banner on channel
   `gohenry_alerts`, then (a) calls `maybeStoreForReview(...)` and (b)
   `AppEvents.notifyPushReceived(vin)` to refresh any foreground card.
2. **Local capture (review‑only)** — `app/.../NotificationStore.kt`, backed by
   **SharedPreferences** file `notif_history` (no DB). Validate and document:
   - Capture is **OFF by default**, opted in **per VIN** (`tracking_vins`).
   - Retention is a **device‑wide window** of `MIN_DAYS=1..MAX_DAYS=7`, default
     `DEFAULT_DAYS=3`; entries older than the window are pruned on read/write.
   - `HARD_CAP_PER_VIN=200` is a safety net only.
   - Flipping a VIN's toggle **either way wipes that VIN's history** and leaves
     other vehicles untouched.
   - `record()` is a no‑op for untracked VINs and is `synchronized` (re‑checks
     enablement inside the lock) so an FCM‑thread write can't race the UI read.
   - The **full** `data` payload is stored per `StoredNotification`.
3. **Surface** — where history is read (`GoHenryViewModel`: `forVin` /
   `allRecent`), the single‑vehicle and combined history screens, and the CSV
   share (`HistoryCsvExport.kt`). Confirm there is **no** server‑side history of
   delivered pushes (only trip/charge **history** Tables exist) — local store is
   the only record of the *notifications themselves*.
4. **Engine‑type UI gating** — confirm the Notification‑setup screen hides the
   charge toggles for non‑charging engines (`usesBatteryGauge`).

### 3. Cross‑checks & gaps to report
- **Trigger ↔ enable ↔ render parity:** every event in `NotificationEvents` has a
  matching `IsEnabled` flag, a `BuildMessage` arm, an `onMessageReceived` arm, and
  a per‑VIN toggle. Flag any mismatch.
- **Timing reality:** with a 2‑min poll, note minimum detection latency and that a
  trip shorter than one poll interval may be missed; the 20‑min lost‑signal
  threshold; "fires once per trip" semantics.
- **Failure modes:** Ford re‑auth required (poll skips VIN), Ford API error (poll
  returns), NH not configured (no‑op), push permission denied on device, app in
  background (no `AppEvents` refresh; banner + store still happen).
- **Privacy/footprint:** lat/long stored on device in cleartext prefs; retention
  bounds; hard cap. Confirm nothing PII leaves the phone except via user‑initiated
  CSV share.

### 4. README write‑up (the one code change you MAY make)
Add a detailed **## Notifications** section to the **root `README.md`** (and a
short cross‑link from `app/README.md` and `backend/README.md`). It must include:
- **What triggers each alert** — a table of the 6 events with the exact condition,
  the source field(s), the per‑VIN enable flag, and the default title/body.
- **End‑to‑end flow diagram** (ASCII, matching the existing README style): Ford →
  poll worker → `ActivityDetector` → per‑VIN enable → NH (FCM v1 data‑only) → app
  banner → optional local capture.
- **How alerts are tracked & stored locally** — the SharedPreferences store, the
  per‑VIN opt‑in, retention window (1–7 days, default 3), the toggle‑wipes‑history
  rule, the 200/VIN cap, and that it's review‑only (no server copy).
- **Timing & limitations** — 2‑min cadence, 20‑min lost‑signal, once‑per‑trip,
  background behavior, re‑auth skip.
- **Privacy** — on‑device only; CSV export is user‑initiated.
- Keep the friendly, emoji‑light house tone. Keep the **No SQL** ethos explicit.

### 5. Suggest additional notifications (propose only — do NOT build)
Recommend new alert types that fit the light, SQL‑free, poll‑driven model and are
derivable from data the snapshot **already** captures (see `VehicleSnapshot` /
`ApplySnapshot` in `PollerFunctions.cs`: `SocPct`, `FuelLevelPct`, `RangeKm`,
`ChargingStatus`, `PluggedIn`, `DoorLocks`, `AlarmStatus`, `TirePressureStatus`,
`OilLifePct`, `OutsideTempC`, `Ignition`, `Latitude`/`Longitude`, …). For each
suggestion give: the event name (e.g. `battery.low`), the trigger condition + a
sensible default threshold, edge/debounce rules (fire‑once‑per‑crossing, like
lost‑signal), which `Snap*`/raw field powers it, engine‑type applicability, and
the new per‑VIN flag + app toggle needed. Seed candidates to evaluate and expand:
- `battery.low` — BEV SoC crosses below a user threshold (e.g. 20%), once per
  discharge cycle.
- `fuel.low` — HEV fuel level below threshold (e.g. 15%), once per crossing.
- `charge.target_reached` — SoC reaches a configured target while charging.
- `plugged_not_charging` — `PluggedIn` true but `ChargingStatus` not charging for
  N polls (faulty plug / paused session).
- `tire.pressure_warn` — `TirePressureStatus` transitions to a warning state.
- `alarm.triggered` — `AlarmStatus` transitions to triggered.
- `left_unlocked` — `DoorLocks` unlocked while `Ignition` off for N minutes.
- `oil_life.low` — `OilLifePct` below threshold (HEV/gas).
- `geofence.enter_exit` — lat/long crosses a small user‑defined home radius
  (note: needs a stored center point — keep it in the VIN Table row, still no SQL).
- `temp.extreme` — `OutsideTempC` beyond hot/cold bounds (battery‑care reminder).
Rank by value‑vs‑effort and call out which need new stored state vs. which are
pure snapshot deltas.

### 6. Notification diagnostics panel — Settings (BUILD THIS)
Add a **"Notification diagnostics"** section to the Settings screen
(`MainActivity.kt#SettingsScreen`, ~line 938) so a user (or you, during a review)
can self‑verify the whole alert chain on‑device. It is **local/read‑only**: no new
backend route, no SQL, no new dependency, no new alert types.

**UI standards (match the existing Settings sections exactly):**
- Render it as a `CollapsibleSection(title = "Notification diagnostics", icon =
  Icons.Default.Notifications)`, placed next to the existing **Diagnostics**
  section (do **not** remove or merge that one). Use the same
  `CollapsibleSection` / `KeyValue` / `ToggleRow` / `Divider` / `Button`
  primitives, the same `Arrangement.spacedBy(14.dp)` rhythm, monospace for raw
  values (e.g. the FCM token, like `configSummary()`), and a primary full‑width
  `Button` for actions — identical to the "Run connectivity check" button.
- Group rows with `Divider()` sub‑headers (Bold `titleMedium`) the same way the
  Diagnostics card groups "Backend configuration" / "Recent errors".
- Give it a `trailing` refresh `IconButton` (like Ford authorization) that
  re‑reads the live diagnostic values.

**Diagnostic rows to surface (all from existing local state — no new server calls):**
- **Push permission** — `POST_NOTIFICATIONS` granted? (reuse the runtime‑permission
  check already in `MainActivity`, ~line 226). Offer a button to open app
  notification settings if denied.
- **System notifications enabled** — `NotificationManagerCompat.from(ctx)
  .areNotificationsEnabled()`.
- **Alerts channel** — that the `gohenry_alerts` channel exists and its importance
  is not `IMPORTANCE_NONE` (via `GoHenryFcmService.ensureChannel` + a
  `getNotificationChannel` read). Offer a button to open channel settings.
- **FCM registration** — token present? show a truncated, **copyable** token; last
  rotation/registration time and last registration outcome. Source from
  `PushRegistrar` (extend it minimally to persist `lastTokenPrefix`,
  `lastRegisteredAtMillis`, `lastRegisterOk` to its existing SharedPreferences —
  no new store).
- **Last push received** — wall‑clock time of the most recent push, via a tiny
  addition to `AppEvents`/`NotificationStore` (persist `lastPushReceivedMillis`).
- **Local capture (per VIN)** — for each vehicle: tracking on/off, retention days,
  and stored‑entry count (`NotificationStore.enabledVins()`, `getTrackingDays()`,
  `forVin(vin).size`). Read‑only mirror of the per‑vehicle toggle.
- **Backend alert toggles (per VIN)** — show the current `NotifyStart/Stop/
  ChargeInProgress/ChargeComplete/ChargeError/LostSignal` values already loaded
  into `state.prefs` (no new fetch); label clearly as "server‑side".

**Actions (local‑only):**
- **Send test notification** — the headline capability. Build a synthetic payload
  and run it through the **same render path** the app uses for real pushes
  (`GoHenryFcmService.showNotification(...)`, channel `gohenry_alerts`), tagged
  unmistakably as a test (e.g. title "GoHenry — test alert"). This verifies
  permission + channel + rendering end‑to‑end **without** the backend or FCM. Do
  **not** write it into `NotificationStore` (keep test noise out of review
  history) unless the user explicitly opts in via a `ToggleRow` "also capture test
  to history".
- **Copy / share diagnostics** — a `share`/copy action that emits a plain‑text
  snapshot of the rows above, reusing the existing FileProvider share pattern
  (`HistoryCsvExport`/`FieldsCsvExport`) so support triage is one tap.
- **Re‑check** — recompute permission/channel/token rows on demand (the `trailing`
  refresh).

**Constraints/notes:** keep all additions in the existing files and stores —
extend `PushRegistrar`/`AppEvents`/`NotificationStore` SharedPreferences rather
than adding any new persistence; no Room, no DI, no backend endpoint, no SQL.
Build with `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` then
`.\gradlew.bat :app:assembleDebug` from `GoHenry\app` and confirm BUILD
SUCCESSFUL. Do not commit.

### 7. Deliverables
1. A concise **review report** (validated trigger/track/store findings + the
   cross‑checks and gaps from §3), citing files/lines.
2. The **README `## Notifications`** section (root) + cross‑links.
3. The **suggested‑notifications** list (§5) with thresholds, debounce, fields,
   flags, and a value/effort ranking.
4. The **Notification diagnostics** Settings panel (§6), built and verified with a
   successful `:app:assembleDebug`, matching the existing Settings UI standards.
Do not implement new alert *types*, do not change triggers, do not commit.

### 8. Acceptance criteria & self‑test checklist
The task is done only when all of the following hold — verify each explicitly:
- **Event parity (review):** all 6 `NotificationEvents` constants have a matching
  `IsEnabled` flag, a `BuildMessage` arm, a `GoHenryFcmService.onMessageReceived`
  arm, and a per‑VIN toggle; any mismatch is listed in the report.
- **Trigger conditions documented verbatim** from `ActivityDetector` (incl. the
  20‑min lost‑signal threshold and once‑per‑trip semantics) and cited to lines.
- **Storage model documented:** off‑by‑default, per‑VIN opt‑in, 1–7 day window
  (default 3), toggle‑wipes‑history, 200/VIN cap, review‑only, full payload kept.
- **README** root has a `## Notifications` section with the filled trigger table
  (§9), the ASCII flow diagram, the local‑storage rules, timing/limits, and
  privacy; `app/` and `backend/` READMEs cross‑link it.
- **Diagnostics panel (build):** the new `CollapsibleSection` appears on Settings,
  matches the existing UI primitives/spacing, and renders every §6 row from live
  local state without any new network/SQL.
- **Test notification works:** tapping "Send test notification" produces a banner
  via the real `gohenry_alerts` channel render path, with permission/channel
  preconditions reflected correctly in the rows; it does **not** pollute review
  history unless the opt‑in toggle is on.
- **Build green:** `:app:assembleDebug` is BUILD SUCCESSFUL; `dotnet test`/the
  existing `ActivityDetectorTests` + `NotificationStoreTest` still pass (you added
  no logic that breaks them).
- **No regressions / constraints honored:** no SQL, no new dependency/endpoint, no
  changes to triggers or the FCM payload contract, nothing committed.

### 9. README `## Notifications` — trigger table template (fill from code)
Populate this table from `ActivityDetector` (condition), `VehicleSnapshot` /
`FordTelemetryNormalizer` (source field), `PollerFunctions.IsEnabled` (flag), and
`PollerFunctions.BuildMessage` / `GoHenryFcmService` (default copy). Keep the
exact event strings.

| Event (`data.event`) | Fires when | Source signal | Per‑VIN enable flag | Default title → body |
|---|---|---|---|---|
| `trip.started` | ignition rising edge (`IsActive && !WasActive`) | `VehicleSnapshot.IsActive` (ignition) | `NotifyStart` | "Start — Ignition on" → "{nickname} started moving" |
| `trip.ended` | ignition falling edge with an open trip | `IsActive` + `HasOpenTrip` | `NotifyStop` | "Stop — Ignition off" → "{nickname} parked" |
| `signal.lost` | open trip silent ≥ 20 min (once) | `LastSeenActiveUtc` vs now | `NotifyLostSignal` | "Car lost signal" → "{nickname} stopped reporting movement" |
| `charge.in_progress` | charging rising edge | `VehicleSnapshot.IsCharging` | `NotifyChargeInProgress` | "Charge in progress" → "{nickname} is charging" |
| `charge.complete` | charging stopped, no fault | `IsCharging` / `IsChargeFault` | `NotifyChargeComplete` | "Charge complete" → "{nickname} finished charging" |
| `charge.error` | charging stopped with fault, or charging + fault | `IsChargeFault` | `NotifyChargeError` | "Charge error" → "{nickname} had a charging problem" |

Note in the README: cadence = poll every **2 min** (`Poll_Dispatcher`); pushes are
**FCM v1 data‑only** via Notification Hubs (tag `user:{userId}`); timestamps are
rendered in the **phone's** local time zone; charge events are hidden on
non‑charging engine types in the app's notification‑setup UI.


- Backend triggers: `Functions/PollerFunctions.cs`, `Normalization/ActivityDetector.cs`,
  `Models/NotificationEvents.cs`, `Models/VehicleSnapshot.cs`,
  `Normalization/FordTelemetryNormalizer.cs`.
- Delivery/registration: `Notifications/NotificationHubPublisher.cs`,
  `Functions/NotificationsRegisterFunction.cs`, `Functions/FleetFunctions.cs`
  (per‑VIN `/api/fleet/notifications/{vin}`).
- App receive/store/surface: `GoHenryFcmService.kt`, `NotificationStore.kt`
  (+ `NotificationStoreTest.kt`), `PushRegistrar.kt`, `AppEvents.kt`,
  `HistoryCsvExport.kt`, `GoHenryViewModel.kt`, `MainActivity.kt`
  (history + notification‑setup screens).
