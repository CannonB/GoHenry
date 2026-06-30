# GoHenry — Road Trip Feature Design (v1 Proposal)

> **Status:** Design proposal for owner review. **No code has been written or
> changed; nothing is committed.** This document fulfils the deliverables in
> `docs/gohenry-roadtrip-design-prompt.md`. It ends with **recommendations** and
> **open clarifying questions** — please answer §9 before any implementation.
>
> **Constraints honoured:** SQL-free (Azure **Table Storage** + **Key Vault** +
> **Notification Hub/FCM** only; phone uses **SharedPreferences** only). A road
> trip may **span many events**. History is **server-durable** and **survives app
> reinstall/clear**. **Ease of use is the priority.**

---

## 1. Confirmed architecture review (with citations)

Verified against the current code. Findings the design relies on:

**Single-row source of truth.** Each car is one Azure Table row,
`VinEntity` (PartitionKey = `userId`, RowKey = `vin`) —
`backend/src/GoHenry.Storage/Entities.cs:13-73`. It already carries the
**open-trip lifecycle** the road-trip layer sits above:
`HasOpenTrip`, `LastWasActive`, `LastSeenActiveUtc`, `WasCharging`,
`TrackingPaused`, plus the warm `Snap*` snapshot and the 8 notify toggles
(`Entities.cs:53-72`).

**Poll → detect → persist → push.**
`Poll_Dispatcher` fires every minute, cadence-gated to 1–10 min, enqueueing one
`PollMessage` per non-paused VIN (`PollerFunctions.cs:30-63`). `Poll_Worker`
mints/uses a cached Ford token, normalizes status, calls the **pure**
`ActivityDetector.Evaluate(prior, snap, now)`, writes the snapshot + next activity
state back to the **one** VIN row, and per detected event appends history and
sends FCM **only if** the toggle is enabled (`PollerFunctions.cs:65-140`).

**Events** (`backend/src/GoHenry.Core/Models/NotificationEvents.cs:10-17`):
`trip.started`, `trip.ended`, `signal.lost`, `charge.in_progress`,
`charge.complete`, `charge.error`, `tire.pressure_warn`, `alarm.triggered`.
A single trip = one ignition-on→off span (`ActivityDetector.cs:58-72`). **A road
trip is a higher-level grouping above these single trips/events.**

**FCM payload is data-only** — `event`, `vin`, `nickname`, `detail`,
`timestampUtc`, and `latitude`/`longitude` when present
(`PollerFunctions.cs:208-241`).

**Orphaned history tables (key finding).** `TripHistoryEntity` and
`ChargeHistoryEntity` (PartitionKey = `vin`, RowKey = `ReverseTicksRowKey(now)`,
newest-first) are **written** by `Poll_Worker` (`PollerFunctions.cs:164-193`) but
**no endpoint reads them** — confirmed: `FleetFunctions.cs` exposes only
`vehicles`, `telemetry`, `telemetry/cache`, `notifications`, `pollsettings`. They
are effectively write-only today. **Road trips will give trip data its first read
path and reuse this exact partitioning pattern.**

**Reinstall gap (the problem to close).** The app's "trip history" is built
**entirely from local captures** in `NotificationStore`
(`app/.../NotificationStore.kt`) — SharedPreferences only, opt-in **per VIN**,
**1–7 day** retention (`NotificationStore.kt:215-217`), wiped when a VIN's toggle
flips (`:72-82`) and **wiped entirely on reinstall/clear-data**. There is **no
server fetch that rebuilds it**. So today, durable history does not survive a
reinstall. The design makes the **server authoritative** and the phone a cache.

**Ownership/auth pattern.** Every read is `req.UserId()`-gated; ownership of a VIN
is proven via `GetVehicleAsync(userId, vin)` before returning data
(`FleetFunctions.cs:31-46`). Responses use `OkObjectResult` with
`JsonSerializerDefaults.Web` (camelCase).

---

## 2. Road-trip definition & start/stop model

**Definition (proposed).** A **Road Trip** is a *named, durable journey* that
groups **one or more individual trips (ignition cycles) and every notification
event that occurs within its open window** into a single record with rolled-up
stats and an event timeline. A weekend away (several legs + a charge stop over two
days) is **one** road trip containing multiple segments; the daily commute is
**not** auto-promoted to a road trip.

**Start/stop model — recommended: HYBRID, manual-first.**
- **Manual** is the v1 default and primary path: a one-tap **"Start road trip"** on
  the carousel/detail; **"Stop"** ends it. Optional name (defaults to a
  date/place). This is the most predictable and the lowest-risk for ease of use.
- **Auto-close safety net** (always on): an open trip auto-ends after a
  configurable **idle timeout** (no `trip.*`/activity for N hours, recommend 12h)
  or a **max duration** (recommend 7 days), so a forgotten trip can't grow
  unbounded. Auto-closed trips are marked `endReason = "auto"`.
- **Auto-start** (later phase, opt-in): heuristic promotion of an ordinary trip to
  a road trip — see §10. Deferred so v1 stays simple and explainable.

**Scope — recommended: per-VIN for v1.** One road trip belongs to one car.
Multi-car journeys (one trip spanning two cars on the account) are noted as a
future option but add real complexity (cross-row aggregation) for little hobby
benefit.

**Aggregates:** segment count (ignition cycles), total distance, total/active
duration, charge stops, start/end location + time, and the **chronological
timeline** of member events.

---

## 3. Data model design (Table Storage, no SQL)

### 3.1 New `RoadTrips` table

Mirrors the existing `TripHistory` pattern (`Entities.cs:115-130`,
`TableGoHenryStore.cs:18,184-186`).

- **Table:** `RoadTrips`
- **PartitionKey:** `vin` — all of a car's road trips co-locate, so listing is a
  single cheap partition scan. (Ownership is enforced at the API by first calling
  `GetVehicleAsync(userId, vin)`, exactly as today's reads do — the same reason
  `TripHistory` can safely use `vin` alone.)
- **RowKey:** `ReverseTicksRowKey(StartedAt)` — reuse the existing helper so trips
  sort **newest-first** natively (`TableGoHenryStore.cs:184-186`).

**Entity fields (conceptual):**

| Field | Type | Notes |
|---|---|---|
| `PartitionKey` | string | `vin` |
| `RowKey` | string | reverse-ticks of `StartedAt` |
| `Id` | string | stable GUID — durable handle for stamping events & deep links |
| `Name` | string | user or auto ("Jun 27 – Coast trip") |
| `Status` | string | `active` / `ended` |
| `StartedAt` / `EndedAt?` | DateTimeOffset | open window |
| `StartLat/Lng`, `EndLat/Lng` | double? | from `Snap*` at boundaries |
| `SegmentCount`, `ChargeStops` | int | rolled up |
| `DistanceKm`, `ActiveMinutes` | double? | rolled up (odometer delta / active span) |
| `EventCount` | int | timeline length (cheap list rendering) |
| `TimelineJson` | string | **embedded** compact JSON array of events (see §3.3) |
| `EndReason` | string | `manual` / `auto` |
| `StartMethod` | string | `manual` / `auto` |

### 3.2 `VinEntity` active-trip pointer (additive, design-only)

Add two properties to the existing single row so `Poll_Worker` can associate
events to the open trip **with no extra query**:

- `ActiveRoadTripId : string?` — the open trip's `Id` (null = none open).
- `ActiveRoadTripStartedAt : DateTimeOffset?` — so the worker can compute the
  RowKey to update without a lookup.

Because the worker already loads and upserts the VIN row every poll
(`PollerFunctions.cs:72,128`), reading the pointer is **free** and writing the
trip update is one extra `UpsertEntity` on a different table.

### 3.3 Embedded timeline vs row-per-event — recommended: EMBEDDED

Store member events as one compact JSON array on `TimelineJson`, exactly the
proven technique used for `RawFieldsJson` (`Entities.cs:47-51`, a ~3-5 KB JSON
column well under Table Storage's 64 KB property limit). Each entry:
`{ ts, event, detail, lat, lng }`.

- **Why embedded:** a road-trip detail screen is then **one** `GetEntity` call —
  no fan-out, no join, trivially SQL-free, and naturally atomic per trip.
- **64 KB guard:** at ~80 bytes/event that's ~800 events per trip. Combined with
  the §2 auto-close caps this is ample for a hobby trip. **Overflow rule:** if a
  trip approaches the cap, stop appending full entries and just increment
  `EventCount` (the rolled-up stats remain correct). Document this limit.
- **Alternative (noted):** one row per event under PartitionKey = `roadTripId` for
  unbounded trips. More rows, more reads, more code — rejected for v1.

### 3.4 Why this needs no SQL

Every access pattern is a point or single-partition Table operation:
- *List a car's road trips, newest-first* → partition scan on `RoadTrips` where PK
  = `vin` (RowKey already sorts).
- *Open trip detail + full timeline* → one `GetEntity(vin, rowKey)` (timeline
  embedded).
- *Associate an event* → read pointer off the already-loaded VIN row; one
  `UpsertEntity` on the trip row.
- *Start/stop* → one upsert + one merge on the VIN row.
No joins, no relational queries, no new datastore.

---

## 4. Endpoint contracts (Function-auth, `req.UserId()`-gated, camelCase)

All under the existing `fleet/...` prefix, modelled on
`Fleet_GetNotifications`/`Fleet_SetNotifications` and the new `pollsettings` pair.
Each first verifies ownership via `GetVehicleAsync(userId, vin)`.

| Method | Route | Purpose |
|---|---|---|
| POST | `/api/fleet/roadtrips/{vin}/start` | Open a trip. Body `{ "name"?: string }`. 409 if one is already active. Returns the new `RoadTripDto`. |
| POST | `/api/fleet/roadtrips/{vin}/stop` | Close the active trip (sets `EndedAt`, finalises rollups, clears the VIN pointer). Returns the ended `RoadTripDto`. Idempotent if none open. |
| GET | `/api/fleet/roadtrips/{vin}` | List trips, newest-first (summary, no timeline). Supports `?take=`. |
| GET | `/api/fleet/roadtrips/{vin}/{id}` | One trip incl. full event timeline. |
| PATCH | `/api/fleet/roadtrips/{vin}/{id}` *(optional)* | Rename. Body `{ "name": string }`. |

**`RoadTripDto` (camelCase):** `id, vin, name, status, startedAt, endedAt,
distanceKm, activeMinutes, segmentCount, chargeStops, eventCount, startLat,
startLng, endLat, endLng, startMethod, endReason` — plus `timeline: [{ ts, event,
detail, lat, lng }]` on the **detail** response only.

This also **closes the orphaned-history gap**: trip data finally has a read path.

---

## 5. App UX (Compose, Material 3 — match existing components)

Reuse `CollapsibleSection`, the carousel/detail layout, per-slug card colors, and
the notification-row visual language already in `MainActivity.kt`.

- **Carousel + detail control:** when no trip is open, a compact **"Start road
  trip"** button/chip on the selected car; when open, an **"On a road trip"** chip
  (with elapsed time) + a **"Stop"** action. One tap to start, one to stop.
- **Road Trips history screen:** a list (newest-first) of trips per car —
  name, date range, distance, segment/charge counts — reached from the detail
  screen and/or the existing history entry point. Tapping a trip opens **detail**:
  rolled-up stats header + the **event timeline** rendered with the same row style
  as captured notifications.
- **Naming:** optional on start (prefilled with a date/place default); editable
  later if PATCH is adopted.
- **State:** add `roadTrips`, `activeRoadTrip`, and loading flags to
  `GoHenryUiState`; mutate via `_state.value.copy(...)`; all network on
  `Dispatchers.IO` in `viewModelScope` — same pattern as `loadFordAccounts` /
  `setPollCadence`.

### Rehydrate-on-launch (reinstall fix)

On home load / login for the signed-in `x-user-id`, call
`GET /api/fleet/roadtrips/{vin}` for each vehicle and populate state; if any trip
has `status = active`, show the **Stop** affordance immediately. A SharedPreferences
copy is kept **only** as an offline render cache — the **server is truth**, so a
fresh install restores the full road-trip history and any in-progress trip.

---

## 6. Reinstall / data-clear story

- **Durable record:** `RoadTrips` rows live in Table Storage, scoped to the
  owner's `userId`/`vin`. Nothing trip-critical lives only on the phone.
- **Fresh install / cleared data:** the app re-fetches all trips on first
  authenticated load → full history reappears; no local seed needed.
- **In-progress trip:** `Status = active` on the server is rehydrated → the Stop
  button returns; the user can close a trip they started on a now-wiped install.
- **Same VIN re-added after removal:** history reattaches automatically because it
  is keyed by `vin` under the same `userId` (consistent with how `TripHistory`
  already partitions). If a VIN can ever belong to a different user, key the
  partition `userId|vin` instead — flagged as a clarifying question (§9).
- **Local retention does not apply** to road trips — the 1–7 day SharedPreferences
  window governs only the legacy local notification cache, not server trips.

---

## 7. Event-association flow (no new queries, detector stays pure)

`ActivityDetector` is unchanged (still pure). The **worker** does association:

1. `Poll_Worker` loads the VIN row (already happens — `PollerFunctions.cs:72`),
   which now includes `ActiveRoadTripId` / `ActiveRoadTripStartedAt`.
2. It runs the detector and gets `result.Events` (unchanged).
3. **If a trip is open** (`ActiveRoadTripId != null`), for each detected event it:
   - appends `{ ts, event, detail, lat, lng }` to that trip's `TimelineJson`
     (read-modify-write of the single `RoadTrips` row via its known RowKey — no
     query), and
   - updates rollups (`SegmentCount` on `trip.started`, `ChargeStops` on
     `charge.complete`/`charge.error`, distance from odometer deltas, etc.).
4. Existing behaviour is untouched: it still writes `TripHistory`/`ChargeHistory`
   and sends FCM per enabled toggle.
5. **Start** sets the VIN pointer + creates the `RoadTrips` row; **Stop** finalises
   the row and clears the pointer. Both are app-driven endpoints (§4); the worker
   only *appends to* an already-open trip.

Concurrency: the worker updates the trip row with the standard Table
merge/ETag-retry approach already used for the VIN row; appends are last-writer-
wins on a single small property, acceptable at hobby poll rates.

---

## 8. Edge cases & limits

- **Unbounded trips:** bounded by the §2 idle/max-duration auto-close and the §3.3
  timeline cap (then stats-only).
- **64 KB property limit:** embedded timeline sized for it; overflow → increment
  `EventCount` without appending.
- **Lost signal mid-trip:** `signal.lost` simply becomes a timeline entry; the
  trip stays open until Stop/auto-close, so a tunnel/long quiet period doesn't
  fragment the journey.
- **Paused tracking (`TrackingPaused`):** no polls → no new segments; an open trip
  persists and resumes appending when unpaused.
- **Multi-phone race:** two phones on one account starting/stopping — server is
  authoritative; a second `start` returns 409, and both phones converge on the
  next `GET`. (Real-time mirroring across phones is a clarifying question, §9.)
- **VIN re-add / removal:** history reattaches by key (§6).
- **Charge-only legs / no GPS:** lat/lng are nullable throughout (matches today's
  payload), so trips without location still record time + events.

---

## 9. Recommendations & open clarifying questions

**Recommended v1 defaults:** hybrid start (manual-first) with idle/max auto-close;
per-VIN scope; embedded JSON timeline; all event types as members; server-
authoritative with launch rehydrate; **no** new `roadtrip.*` FCM pushes in v1
(in-app only). 

**Please confirm/answer before implementation:**
1. **Start model:** manual-only for v1, or hybrid with the auto-close net (rec)?
   If/when auto-start: what promotes a trip (min distance? crosses a day? leaves a
   home geofence)?
2. **Auto-close boundary:** idle hours (rec 12h) and max duration (rec 7d) — OK?
3. **Scope:** per-car only for v1 (rec), or must a trip span multiple cars?
4. **Naming:** auto-named default + optional user name + later rename (PATCH)?
5. **Member events:** include *all* event types (rec), or only trip/charge
   (exclude tire/alarm)?
6. **Retention:** keep road trips indefinitely (rec), or cap/age them?
7. **FCM:** add `roadtrip.started`/`roadtrip.ended` toggles, or silent (rec)?
8. **Live vs summary:** live, growing timeline while active (rec), or summary only
   once stopped?
9. **Map/route:** out of scope for v1 (rec), or needed?
10. **Multi-phone:** is real-time mirroring across phones on the same account
    required for v1, or is converge-on-next-fetch acceptable (rec)?
11. **Partition key:** `vin` (rec, matches `TripHistory`) or `userId|vin` (needed
    only if a VIN can change owners)?

---

## 10. Phased plan & test plan

**Phase 1 (v1) — ✅ DONE:** `RoadTrips` table + `VinEntity` pointer; start/stop/list/detail
endpoints + `RoadTripDto`; worker association; app start/stop control, history +
detail screens; launch rehydrate. Closes the reinstall gap and gives trip data its
first read path.

**Phase 2 — ✅ DONE:** auto-start heuristic + auto-close tuning (idle hours 2–12 /
max days 1–7 on the `pollSettings` meta row); an **End the trip on stop** toggle
(closes any open trip when a `trip.ended` stop push fires, `endReason="stop"`);
optional `roadtrip.started`/`roadtrip.ended` FCM toggles; rename and **delete**.
App surfaces it via a **Road trips tray** (bottom sheet) opened by a wide button
between the home screen's bottom corner buttons, rename/delete actions on the
detail screen, and a **Settings ▸ Road-trip automation** section.

**Phase 3:** route/map view; multi-car trips; CSV export of a trip (reuse
`HistoryCsvExport` pattern).

**Test plan (when code is approved):**
- Backend: `dotnet test backend\tests\GoHenry.Tests\GoHenry.Tests.csproj` —
  add contract tests for `RoadTripDto` (camelCase) and association/rollup logic
  (segment/charge counting, timeline cap, auto-close), alongside existing
  `ContractTests`/detector tests.
- App: `:app:assembleDebug` + `:app:testDebugUnitTest`.
- Manual: start → drive (sim several `trip.*`) → charge → stop → reinstall →
  confirm full history + any active trip rehydrate from the server.

---

**No code was written or modified, and nothing was committed. Awaiting answers to
§9 before implementation.**
