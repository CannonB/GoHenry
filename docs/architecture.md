# GoHenry architecture notes

This note explains the two design decisions that matter most: **how server state is
modeled without any SQL**, and **how activity detection works**.

## 1) No-SQL data model (Azure Table Storage)

FleetFoot's hot path was already a no-SQL read-through cache; its companion app kept a
*canonical* registry, trip/charge history, and activity rows in **SQL Server**.
GoHenry folds **all** of that onto **Azure Table Storage** so there is no relational
database to provision, pay for, patch, or back up.

Table Storage gives us a fast key/value store with two keys: a **PartitionKey**
(co-locates related rows, transaction boundary) and a **RowKey** (uniquely identifies
a row within a partition, sorted ascending). We choose them so every screen the app
renders is a **single point-read or a single partition scan** — never a join.

| Table | PartitionKey | RowKey | Why |
| --- | --- | --- | --- |
| `Vehicles` | `userId` | `vin` | One user's cars are one partition → "list my vehicles" is one cheap scan. A single row carries metadata **+** the warm telemetry snapshot **+** poll/activity state **+** notification toggles, so the carousel, status, detail and notification screens all read **one row**. |
| `FordAccounts` | `userId` | `slug` | Per-user, per Ford-app link status. The **token value is NOT here** — only the Key Vault secret name. |
| `OAuthStates` | `"state"` | state GUID | A single hot partition of short-lived nonces; the callback does a point-read + delete. |
| `Installs` | `userId` | installationId | FCM registrations for Notification Hub installs. |
| `TripHistory` | `vin` | reverse-ticks | Per-car history; reverse-ticks row key makes a top-N read return **newest-first** with no sorting. |
| `ChargeHistory` | `vin` | reverse-ticks | Same pattern, for charge sessions. |
| `RoadTrips` | `vin` | reverse-ticks | Named, durable journeys grouping multiple trips + their events. The event **timeline is embedded as one JSON array** (≤ 800 events, like `Vehicles.RawFieldsJson`) so a detail read is a single point-GET. The **active** trip is also pointed to by `Vehicles.ActiveRoadTripId/Name/StartedAt/LastEventAt` (empty-string sentinel, Merge-safe) so telemetry surfaces it with no extra read and history rehydrates after a reinstall. `StartMethod` (`manual`/`auto`) and `EndReason` (`manual`/`auto`) record how each trip began/ended. |
| `GoHenryMeta` | `"_meta"` | e.g. `pollSettings` | A tiny singleton partition for housekeeping stamps, the poll cadence, and road-trip automation settings (auto-start, idle hours, max days). |

**Reverse-ticks row key.** History rows use `String.Format("{0:D19}", long.MaxValue -
timestamp.UtcTicks)`. Because Table Storage sorts RowKeys ascending as strings, the
most recent entry sorts first — so "latest N trips" is a single forward scan.

**Why one fat `Vehicles` row instead of several tables?** At hobby scale (≤5 cars, ≤5
phones) the snapshot, toggles and activity state are always read and written together
by the poller and the app. Keeping them on one row means **zero joins**, atomic
updates per VIN, and the cheapest possible reads. Trip/charge **history** is split out
because it grows unbounded and is read on its own screen.

**Secrets are not in the data model.** Ford refresh tokens live in **Key Vault** as
`ford-refresh-{userId}-{slug}`. Key Vault is a secret store, not a database — so the
"no SQL / no token on device" guarantees both hold.

## 2) Activity detection

`GoHenry.Core.Normalization.ActivityDetector.Evaluate(prior, snapshot, nowUtc)` is a
**pure, deterministic** function (no I/O, exhaustively unit-tested). The poller loads
`prior` from the VIN row, calls `Evaluate`, fires FCM for the returned events that the
user enabled, then persists `result.Next` back onto the same row.

Inputs come from the **normalized snapshot** (`FordTelemetryNormalizer` recursively
searches the raw Ford JSON, case-insensitively, for the fields we care about and maps
them to a stable `VehicleSnapshot`). The snapshot exposes the booleans the detector
keys off:

- `IsActive` — ignition is ON / RUN / START.
- `ChargePhase` — coarse charge state parsed from `ChargeDisplayStatus` (Ford
  `xevBatteryChargeDisplayStatus`, surfaced as the raw field `SoCChargeDisplayStatus`):
  `IN_PROGRESS` → `InProgress`, `COMPLETED`/`TARGET_REACHED` → `Complete`,
  `ERROR`/`FAULT` → `Error`, anything else → `Other`.

### Rules

| Transition | Condition | Event |
| --- | --- | --- |
| Trip start | `IsActive` and not previously active | `trip.started` (opens a trip) |
| Trip end | previously active, now not active, a trip was open | `trip.ended` (appends to `TripHistory`) |
| Telemetry feed lost | no **successful** Ford read for a configurable number of missed polls (5–20, default 10; window = polls × cadence, from the `pollSettings` meta row); `CapturedAt` staleness raised in `PollerFunctions.HandlePollFailureAsync`, not the detector. Re-arms on the next good read | `telemetryfeed.lost` |
| Lost signal | feed reachable but **frozen**: ignition `ON` and **both** `OdometerKm` and the Ford telemetry timestamp (`FordTelemtryTimeStamp`) unchanged for the same poll count; evaluated on each **successful** poll in `PollerFunctions` via `ActivityDetector.IsFeedFrozen`, counted in `VinEntity.FrozenFeedPollCount`. Re-arms when data advances or ignition turns off | `signal.lost` |
| Charge begins | `ChargePhase` changes **to `InProgress`** | `charge.in_progress` |
| Charge completes | `ChargePhase` changes **to `Complete`** | `charge.complete` (appends to `ChargeHistory`) |
| Charge error | `ChargePhase` changes **to `Error`** | `charge.error` |

Charge events fire on a *change* of phase, so a steady `IN_PROGRESS` (or any
unchanged value) never re-notifies, and non-charging values (`NOT_READY`,
`DISCONNECTED`, …) raise nothing.

The `Next` state mirrors the inputs (`WasActive`, `ChargePhase`, `HasOpenTrip`,
`LastSeenActiveUtc`, `LostSignalAlreadyRaised`) so the next poll has everything it
needs — again, all on the one `Vehicles` row.

### Poller shape

A **timer** (`Poll_Dispatcher`, fires every 1 min, gated to a configurable 1–10 min
cadence stored in the `pollSettings` meta row) scans all `Vehicles` rows and enqueues
one `gohenry-poll` message per non-paused VIN. A **queue worker** (`Poll_Worker`)
processes each message independently: get an access token (cached, else minted from
the Key Vault refresh token), fetch + normalize the Ford status, run the detector,
persist, append history, and publish FCM. Fanning out through a queue keeps each
vehicle's work isolated and naturally retried.

If the car has an **open road trip** (`Vehicles.ActiveRoadTripId` set), the worker
also stamps each detected event onto that trip's embedded timeline and rolls up its
trip/charge/distance/event counters — a single read-modify-write of the `RoadTrips`
row, addressed directly via the start time the `Vehicles` row already carries (no
query to discover the trip).

Road-trip **automation** also runs in the worker (settings on the `pollSettings`
meta row, so still no SQL): with **auto-start** on, a `trip.started` with no open
trip auto-opens one (`startMethod="auto"`); an **auto-close safety net** ends a
forgotten trip once it has been idle past the configured hours or older than the
max-age cap (`endReason="auto"`). The idle check uses `Vehicles.ActiveRoadTripLastEventAt`
(denormalized so the worker needs no extra read), and runs every tick — even when a
poll produced no events — so a parked car still gets closed out. Optional
`roadtrip.started` / `roadtrip.ended` pushes (per-VIN toggles) announce the
automated transitions.

## Event → FCM contract

Events are delivered as **data-only** FCM v1 messages. The `data` map always contains
`event`, `vin`, `nickname`, `timestampUtc`, `title`, `body`, and (when known)
`latitude` / `longitude`. The app's `GoHenryFcmService` switches on `event` to render
a local notification and optionally store it in the on-device review history.
