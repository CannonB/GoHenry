# GoHenry Notifications — End‑to‑End Review

> Review deliverable for `docs/gohenry-notifications-review-prompt.md` (§1–§3, §5).
> Findings are validated against the code and cited to files/lines. **No SQL.**
> Server state is Azure **Table Storage**; the phone's alert history is
> **SharedPreferences** only.

## 1. Trigger pipeline (backend) — validated

**Poll cadence & fan‑out** — `backend/src/GoHenry.Api/Functions/PollerFunctions.cs`
- `Poll_Dispatcher` runs `TimerTrigger("0 */1 * * * *")` → fires **every minute** but
  only fans out once the **configured cadence (1–10 min, default 2)** has elapsed
  since `MetaEntity.LastDispatchedAt` (gated in `PollerFunctions.Dispatch`; cadence is
  read from the `pollSettings` meta row, set via `GET|POST /api/fleet/pollsettings`).
  It enqueues one `PollMessage` per **non‑`TrackingPaused`**
  VIN onto the `gohenry-poll` storage queue.
- `Poll_Worker` (queue trigger) gets a cached Ford token, fetches + normalizes
  status, evaluates transitions, persists snapshot + activity state to the VIN's
  **single Table row**, then per detected event appends history and sends FCM
  **only if enabled** (PollerFunctions.cs:49‑111).

**Transition detection** — `backend/src/GoHenry.Core/Normalization/ActivityDetector.cs`
(pure, deterministic, unit‑tested). Exact conditions:

| Event | Condition (verbatim) | Cite |
|---|---|---|
| `trip.started` | `isActive && !prior.WasActive` (ignition rising edge) | ActivityDetector.cs:55‑60 |
| `trip.ended` | `!isActive && prior.WasActive` **and** `hasOpenTrip` | ActivityDetector.cs:61‑66 |
| `signal.lost` | `hasOpenTrip && !lostRaised && now − lastSeenActive ≥ 20 min` (fires once, sets `LostSignalAlreadyRaised`) | ActivityDetector.cs:43, 71‑76 |
| `charge.in_progress` | `isCharging && !prior.WasCharging` | ActivityDetector.cs:80‑83 |
| `charge.complete` | charging stopped (`!isCharging && prior.WasCharging`) **and not** `IsChargeFault` | ActivityDetector.cs:84‑88 |
| `charge.error` | charging stopped **with** fault, **or** still charging while `IsChargeFault` (fault wins) | ActivityDetector.cs:84‑92 |
| `tire.pressure_warn` | `IsTireWarning` rising edge — any wheel non‑normal (fires once, re‑arms when all wheels normal) | ActivityDetector.cs (tire block); VehicleSnapshot.IsTireWarning |
| `alarm.triggered` | `IsAlarmTriggered` rising edge — alarm sounding (fires once, re‑arms when cleared) | ActivityDetector.cs (alarm block); VehicleSnapshot.IsAlarmTriggered |

`LostSignalThreshold = TimeSpan.FromMinutes(20)` (ActivityDetector.cs:43). `lastSeenActive`
is set to `now` on every active poll (ActivityDetector.cs:68). The detector returns the
events plus the `NextActivityState` the worker persists.

**Snapshot signals** — `IsActive` / `IsCharging` / `IsChargeFault` come from
`VehicleSnapshot` as produced by `FordTelemetryNormalizer.Normalize(...)`
(PollerFunctions.cs:74‑76). HEV/gas cars never charge, so charge events are inert
for them (and the app hides the charge toggles — see §2.4).

**Per‑VIN enablement** — `PollerFunctions.IsEnabled` maps each event to a Table
flag (PollerFunctions.cs:166‑175): `NotifyStart`, `NotifyStop`,
`NotifyChargeInProgress`, `NotifyChargeComplete`, `NotifyChargeError`,
`NotifyLostSignal`. These are set from the app via `POST /api/fleet/notifications/{vin}`
(`FleetFunctions.cs`) and surfaced on the app's notification‑setup screen.

**Event vocabulary & payload** — `backend/src/GoHenry.Core/Models/NotificationEvents.cs`
defines the six canonical strings. `BuildMessage` (PollerFunctions.cs:177‑204) builds
the `data` map: always `event`, `vin`, `nickname`, `timestampUtc` (ISO‑8601 `o`),
plus `latitude`/`longitude` when present.

**Delivery** — `backend/src/GoHenry.Api/Notifications/NotificationHubPublisher.cs`:
Azure Notification Hubs → **FCM v1 data‑only**. Installs are tagged
`user:{userId}` + `app:gohenry` (NotificationHubPublisher.cs:50); sends target tag
`user:{userId}` (NotificationHubPublisher.cs:69). `title`/`body` are injected into
`data` so the app renders/locales everything client‑side
(NotificationHubPublisher.cs:59‑66). When no hub is configured (local dev) it is a
**logging no‑op** (NotificationHubPublisher.cs:34‑38, 57).

**Registration** — `NotificationsRegisterFunction.cs` (backend) ↔ app
`PushRegistrar.kt`: a stable per‑install UUID upserts one NH installation, so token
rotations patch the same record (PushRegistrar.kt:34‑39, 53‑64).

## 2. Receive / track / store pipeline (app) — validated

**2.1 Receive** — `GoHenryFcmService.kt#onMessageReceived` (GoHenryFcmService.kt:38‑71):
switches on `data["event"]`, builds a localized title/body (timestamp rendered in
the **phone's** local zone, GoHenryFcmService.kt:122‑131), posts a high‑importance
banner on channel `gohenry_alerts` (GoHenryFcmService.kt:139‑161, 164), then
`maybeStoreForReview(...)` and `AppEvents.notifyPushReceived(vin)`.

**2.2 Local capture (review‑only)** — `NotificationStore.kt`, backed by
SharedPreferences file `notif_history` (no Room/DB/DI):
- Capture **OFF by default**, opted in **per VIN** (`tracking_vins`,
  NotificationStore.kt:52‑65).
- Device‑wide retention window **1–7 days, default 3** (`MIN_DAYS`/`MAX_DAYS`/
  `DEFAULT_DAYS`, NotificationStore.kt:201‑204); entries outside the window pruned
  on read/write (NotificationStore.kt:117‑150).
- `HARD_CAP_PER_VIN = 200` newest as a safety net (NotificationStore.kt:210, 148).
- Toggling a VIN **either way wipes that VIN's history**, leaving others untouched
  (NotificationStore.kt:72‑82).
- `record()` is a no‑op for untracked VINs and is `synchronized`, re‑checking
  enablement inside the lock to avoid a write/clear race (NotificationStore.kt:104‑114).
- The **full** `data` payload is stored per `StoredNotification`
  (GoHenryFcmService.kt:95; NotificationStore.kt:16‑24, 168‑198).

**2.3 Surface** — `GoHenryViewModel` reads `forVin` / `allRecent`; the single‑vehicle
and combined history screens render them; CSV export via `HistoryCsvExport.kt`
(FileProvider `ACTION_SEND`). There is **no server‑side record of delivered pushes** —
the backend keeps trip/charge **history** Tables only, so the on‑device store is the
sole record of the notifications themselves.

**2.4 Engine‑type gating** — the notification‑setup screen hides the charge toggles
for non‑charging engines via `usesBatteryGauge` (MainActivity.kt:833).

## 3. Cross‑checks & gaps

**Event parity (all four layers present for every event):**

| Event | `NotificationEvents` | `IsEnabled` flag | `BuildMessage` arm | App `onMessageReceived` arm | Per‑VIN toggle |
|---|:--:|:--:|:--:|:--:|:--:|
| trip.started | ✔ | ✔ NotifyStart | ✔ | ✔ | ✔ |
| trip.ended | ✔ | ✔ NotifyStop | ✔ | ✔ | ✔ |
| signal.lost | ✔ | ✔ NotifyLostSignal | ✔ | ✔ | ✔ |
| charge.in_progress | ✔ | ✔ NotifyChargeInProgress | ✔ | ✔ | ✔ |
| charge.complete | ✔ | ✔ NotifyChargeComplete | ✔ | ✔ | ✔ |
| charge.error | ✔ | ✔ NotifyChargeError | ✔ | ✔ | ✔ |
| tire.pressure_warn | ✔ | ✔ NotifyTirePressure | ✔ | ✔ | ✔ |
| alarm.triggered | ✔ | ✔ NotifyAlarm | ✔ | ✔ | ✔ |

No mismatches. Every event has a trigger, an enable flag, a message builder, a
render arm, and a user‑facing toggle.

**Timing reality:** 2‑min poll ⇒ minimum detection latency ~0–2 min; a trip shorter
than one poll interval can be missed entirely; lost‑signal is a **20‑min** dwell and
fires **once per trip**; charge fault detection is per‑poll edge.

**Failure modes:** Ford re‑auth required ⇒ the VIN's poll is **skipped**
(PollerFunctions.cs:64‑68); Ford API error ⇒ poll returns, no events
(PollerFunctions.cs:72); NH unconfigured ⇒ push no‑op; device push permission denied
⇒ no banner (capture/`AppEvents` still run if reached); app backgrounded ⇒ no
`AppEvents` collector (banner + store still happen).

**Privacy/footprint:** lat/long are stored on device in cleartext SharedPreferences
(within the retention window + 200/VIN cap). Nothing leaves the phone except via the
**user‑initiated** CSV share. Bounded by `MAX_DAYS = 7`.

**Minor observations (no action without consent):**
- `signal.lost` depends on polls continuing; if the *backend* stops polling (e.g.
  all tokens expired) no lost‑signal fires — it detects vehicle silence, not
  backend silence.
- Test/observability: there is no in‑app way to confirm permission/channel/token
  health → addressed by the new **Notification diagnostics** Settings panel (§6 of
  the prompt).

## 5. Suggested additional notifications (proposal only — not built)

All derivable from data the snapshot **already** captures (`VehicleSnapshot` /
`ApplySnapshot`, PollerFunctions.cs:113‑133). Each needs: a new event constant, an
`IsEnabled` flag + Table column, a per‑VIN app toggle, an `onMessageReceived` arm,
and (for thresholds) a fire‑once‑per‑crossing guard mirroring `LostSignalAlreadyRaised`.

| Suggestion (event) | Trigger + default threshold | Debounce | Field | Engine | New state? | Value/Effort |
|---|---|---|---|---|---|---|
| `battery.low` | BEV SoC crosses below 20% | once per discharge cycle (reset on charge) | `SnapSocPct` | BEV | flag bit | **High / Low** |
| `fuel.low` | HEV fuel below 15% | once per crossing (reset on refuel jump) | `SnapFuelLevelPct` | HEV | flag bit | **High / Low** |
| `charge.target_reached` | SoC ≥ user target (e.g. 80%) while charging | once per charge session | `SnapSocPct`+`ChargingStatus` | BEV | target + flag | High / Med |
| `plugged_not_charging` | `PluggedIn` true but not charging for N polls | N‑poll dwell | `SnapPluggedIn`/`SnapChargingStatus` | BEV | counter | High / Med |
| `tire.pressure_warn` | ✅ **BUILT** — any wheel non‑normal, fires once on rising edge | per‑wheel `tirePressureStatus[]` | `SnapTirePressureStatus` | BOTH | `TireWarnRaised` flag | Shipped |
| `alarm.triggered` | ✅ **BUILT** — alarm sounding, fires once on rising edge | `AlarmStatus` | `SnapAlarmStatus` | BOTH | `AlarmRaised` flag | Shipped |
| `left_unlocked` | `DoorLocks` unlocked while `Ignition` off ≥ N min | dwell, once | `SnapDoorLocks`+`SnapIgnition` | BOTH | timer+flag | Med / Med |
| `oil_life.low` | `OilLifePct` below 15% | once per crossing | `SnapOilLifePct` | HEV/Gas | flag | Med / Low |
| `geofence.enter_exit` | lat/long crosses a home radius | edge per crossing | `SnapLatitude`/`SnapLongitude` | BOTH | **stored center+radius** (Table row, still no SQL) | Med / High |
| `temp.extreme` | `OutsideTempC` beyond hot/cold bounds | once per crossing | `SnapOutsideTempC` | BOTH | flag | Low / Low |

> **Update:** `tire.pressure_warn` and `alarm.triggered` from the original first‑wave
> recommendation are now **implemented** (per‑VIN flags `NotifyTirePressure` /
> `NotifyAlarm`, app toggles under *Safety alerts*, once‑per‑edge guards
> `TireWarnRaised` / `AlarmRaised`). `battery.low` and `fuel.low` remain proposals.

**Recommended first wave (highest value, lowest effort, pure edge/threshold,
no new center‑point state):** `battery.low`, `fuel.low`, `tire.pressure_warn`,
`alarm.triggered`. These reuse the existing once‑per‑crossing pattern and a single
extra Table flag each.

**Implementation notes (when approved):** add constants to `NotificationEvents`,
emit them from `ActivityDetector.Evaluate` (extend `Prior/NextActivityState` with the
crossing flags), wire `IsEnabled` + `BuildMessage` + new `VinEntity` columns + the
`/fleet/notifications/{vin}` contract, add app toggles + `onMessageReceived` arms,
and extend `ActivityDetectorTests`. Keep everything in Table Storage — no SQL.
