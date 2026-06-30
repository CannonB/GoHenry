# GoHenry — Ford Telemetry Validation Report

**Subject:** Validate the native Ford telemetry feed and confirm which fields reach the
Azure data tables for the GoHenry app to consume.
**Status:** Evidence-based analysis only — **no code or backend changes were made**, and
nothing was committed to git.
**Generated:** 2026-05-01
**Companion artifacts:**
`docs/GoHenry-Ford-Telemetry-Field-Mapping.xlsx` ·
`docs/ford-telemetry-data-validation-prompt.md` ·
`docs/samples/ford-telemetry-1FT6W5L77SWG23727-2026-05-01T211407Z.json`

---

## 1. Pull status — what could and could not be retrieved live

| Question | Finding |
|---|---|
| Can we pull a **live raw** payload from the deployed backend? | **No.** The deployed function `https://func-gohenry-doit.azurewebsites.net/api/` exposes `fleet/telemetry/{vin}` at `AuthorizationLevel.Function` (needs a Function key + a non-null `userId` header) **and it only returns the normalized `TelemetryDto`** — never the raw Ford JSON. |
| Is the **raw Ford JSON persisted** anywhere in Azure? | **No.** `VinEntity` in `GoHenry.Storage/Entities.cs` stores ~16 `Snap*` columns only. The raw payload is parsed in-memory by `FordTelemetryNormalizer` and discarded. There is **no raw field in any Azure Table**. |
| Do we have an **authoritative real sample** to validate against? | **Yes.** A genuine captured FordConnect Query "metrics" envelope exists for VIN `1FT6W5L77SWG23727` (a BEV). It is copied verbatim into `docs/samples/…` and is the per-VIN raw sample for this report. |

> **Why no live raw pull:** the live raw feed is only visible inside the backend poller at
> the moment it calls Ford. To capture it per-VIN going forward, the backend must persist
> raw payloads (see §6) — a change that requires your express consent and is **not** made here.

### Per-VIN coverage
Only **one** VIN has a captured real payload (`1FT6W5L77SWG23727`). The other vehicles
currently in the app are seed/demo entries with synthetic VINs and have **no** captured
raw feed. Persisting raw payloads (§6) is the prerequisite for a per-VIN sample on every
car; this report validates the one authentic feed we have.

---

## 2. The sample at a glance

| Property | Value |
|---|---|
| VIN | `1FT6W5L77SWG23727` |
| `vehicleId` | `9d665443-24f6-4a3b-815b-93a430222bc1` |
| `updateTime` | `2026-05-01T21:14:07Z` |
| Powertrain | BEV (Ford F-150 Lightning–class `xev*` metrics present) |
| Envelope shape | `{ updateTime, vehicleId, vin, metrics{…} }` |
| **Distinct metrics under `metrics{}`** | **64** |
| Total leaf fields (incl. arrays + per-metric `updateTime`/`oemCorrelationId` metadata) | 458 |
| Fields GoHenry's DTO consumes | 16 |
| **DTO fields correct on this real feed** | **6 of 16** |

Each metric is an object of the form
`{ updateTime, oemCorrelationId, value, …context }` (arrays for multi-instance metrics
such as per-wheel tire pressure and per-door locks).

---

## 3. Field-by-field validation (GoHenry normalizer run against the real sample)

These values were produced by running a **faithful port of
`FordTelemetryNormalizer.FindFirst/FindNumber/FindString/FindBool`** over the sample —
i.e. exactly what GoHenry would store today.

| DTO field | GoHenry value **today** | Key it matched | Correct value | Correct Ford key | Verdict |
|---|---|---|---|---|---|
| `SocPct` | **90.0** | `batteryStateOfCharge` (12 V aux battery) | **77.0** | `xevBatteryStateOfCharge` | 🟧 **Mismapped** |
| `RangeKm` | `null` | — | **330.3 km** | `xevBatteryRange` (BEV) / `fuelRange` (ICE) | 🟥 **Missed** |
| `ChargingStatus` | `null` | — | **IN_PROGRESS** | `xevBatteryChargeDisplayStatus` (+ `xevPlugChargerStatus`) | 🟥 **Missed** |
| `PluggedIn` | `null` | — | **true** | `xevPlugChargerStatus` = `CONNECTED` | 🟥 **Missed** |
| `DoorLocks` | `null` | `doorLockStatus` (matched, but value is an **array** → dropped) | **LOCKED** | `doorLockStatus[].value` | 🟥 **Missed** |
| `TirePressureStatus` | `null` | `tirePressureStatus` (matched, but **array** → dropped) | **NORMAL** (all wheels) | `tirePressureStatus[].value` / `tirePressureSystemStatus` | 🟥 **Missed** |
| `OilLifePct` | `null` | — | **0.0** (N/A on BEV) | `oilLifeRemaining` | 🟥 **Missed** |
| `OutsideTempC` | **0.0** | `ambientTemp` (aux sensor reading 0) | **22.75 °C** | `outsideTemperature` | 🟧 **Mismapped** |
| `InteriorTempC` | `null` | — | (no cabin-temp metric in this feed) | — | ⬜ Missed (N/A) |
| `FuelLevelPct` | `null` | — | 0.0 (N/A on BEV; ICE/HEV only) | `fuelLevel` (verify on HEV feed) | ⬜ Missed (N/A) |
| `OdometerKm` | **8612.0** | `odometer` | 8612.0 | `odometer` | ✅ **Captured** |
| `Latitude` | **47.639606** | `lat` (`position.value.location.lat`) | 47.639606 | same | ✅ **Captured** |
| `Longitude` | **-122.167889** | `lon` | -122.167889 | same | ✅ **Captured** |
| `Ignition` | **OFF** | `ignitionStatus` | OFF | `ignitionStatus` | ✅ **Captured** |
| `GearLever` | **PARK** | `gearLeverPosition` | PARK | `gearLeverPosition` | ✅ **Captured** |
| `AlarmStatus` | **ARMED** | `alarmStatus` | ARMED | `alarmStatus` | ✅ **Captured** |

### Tally
- ✅ **Captured correctly: 6** — Odometer, Latitude, Longitude, Ignition, GearLever, AlarmStatus
- 🟧 **Mismapped (wrong value shown to the user): 2** — `SocPct`, `OutsideTempC`
- 🟥 **Missed (data exists in feed but dropped): 6** — Range, ChargingStatus, PluggedIn, DoorLocks, TirePressureStatus, OilLifePct
- ⬜ **Missed but N/A on this BEV: 2** — InteriorTempC, FuelLevelPct

> **Headline:** on a real BEV feed, **only 6 of GoHenry's 16 mapped fields are correct.**
> The hero gauge would show **90 % charge instead of 77 %**, **no range** despite 330 km
> being present, **"unplugged"** while actually charging, and **0 °C outside** instead of
> 22.75 °C.

---

## 4. Root causes

1. **Candidate-key collisions (mismaps).** `FindFirst` returns the *first* property name
   (in JSON order) that matches *any* candidate. On the real feed:
   - `SocPct` lists `batteryStateOfCharge` as a candidate; that key is the **12 V auxiliary
     battery** (90 %) and appears *before* the traction-battery key `xevBatteryStateOfCharge`
     (77 %), which is **not** a candidate at all.
   - `OutsideTempC` lists `ambientTemp` (an aux sensor reading `0.0`) as a candidate but
     **not** `outsideTemperature` (22.75 °C), so it locks onto the wrong sensor.
2. **`xev*` BEV keys are absent from every candidate list.** The normalizer was written for
   flat/legacy keys (`range`, `chargingStatus`, `pluggedIn`, `distanceToEmpty`). The real
   FordConnect Query feed uses `xevBatteryRange`, `xevBatteryChargeDisplayStatus`,
   `xevPlugChargerStatus`, etc., so EV range/charge/plug fields silently resolve to `null`.
3. **Array-valued metrics are dropped.** `doorLockStatus` and `tirePressureStatus` are
   **arrays** (one entry per door/wheel). `FindFirst` returns the array, but `FindString`
   has no `JsonValueKind.Array` case, so it returns `null` even though the key matched.
4. **Raw payload is never persisted.** Even fields that *are* in the feed (the other 48
   metrics — speed, doors, windows, battery temps, charger current/voltage, trip regen,
   configurations/charge schedules, etc.) are unreachable because nothing stores the raw
   JSON. The "Raw fields" screen can only show the 16 DTO fields.

---

## 5. Exact normalizer changes to close each gap

Surgical edits to the candidate lists in `FordTelemetryNormalizer.Normalize` (no schema
change required for these; **do not apply without consent**):

| DTO field | Change |
|---|---|
| `SocPct` | Put `"xevBatteryStateOfCharge"` **first**; **remove** `"batteryStateOfCharge"` (12 V aux) to stop the mismap → `"xevBatteryStateOfCharge","soc","socPct","stateOfCharge","chargeLevel"`. |
| `RangeKm` | Add `"xevBatteryRange"` (first) and `"fuelRange"` → `"xevBatteryRange","fuelRange","rangeKm","range","distanceToEmpty","elVehDTE","gasDTE"`. |
| `ChargingStatus` | Add `"xevBatteryChargeDisplayStatus"` (first), `"xevPlugChargerStatus"`. |
| `PluggedIn` | No boolean plug key exists; derive in code: `pluggedIn = xevPlugChargerStatus == "CONNECTED"` (add string→bool mapping for this key, or compute it in `Normalize`). |
| `OutsideTempC` | Add `"outsideTemperature"` (first); **remove** `"ambientTemp"` → `"outsideTempC","outsideTemp","outsideTemperature"`. |
| `OilLifePct` | Add `"oilLifeRemaining"`. |
| `DoorLocks` / `TirePressureStatus` | Add a `JsonValueKind.Array` case to `FindString` that aggregates `[].value` (e.g. first/most-severe), **or** map to a scalar summary key. |
| `FuelLevelPct` | Confirm the real HEV key on a hybrid feed (the BEV sample has none); likely `fuelLevel`. |

After any such change, re-run this validation against the sample to confirm the tally
moves to 14/16 correct (the two remaining are genuinely N/A on a BEV).

---

## 6. The bigger opportunity — persist the raw feed (SQL-free)

The feed carries **64 metrics / hundreds of leaves**; GoHenry surfaces 16. To make the full
set available to the app's **Raw Data screen** without taking on SQL:

- **Option A (simplest):** add a `RawTelemetryJson` string column to `VinEntity` (Azure
  Table Storage; keep under the ~64 KB property limit — this sample is 15 KB). One write per
  poll, zero new infrastructure.
- **Option B (scales/audits better):** write each raw payload to **Blob Storage**
  (`raw/{vin}/{timestamp}.json`) and keep a tiny `RawFieldEntity` index in Table Storage for
  fast field lookups. Still **no SQL**.

Then expose the stored raw fields on the existing **Raw Data screen** (read-only). Per the
standing constraint, **this is a recommendation only** — no implementation here without your
express consent.

---

## 7. Notable fields present but completely unused (sample of the 48 extra metrics)

`speed`, `doorStatus`, `windowStatus[]`, `hoodStatus`, `seatBeltStatus[]`,
`parkingBrakeStatus`, `brakePedalStatus`, `engineCoolantTemp`, `batteryVoltage` (12 V),
`xevBatteryActualStateOfCharge` (71.93), `xevBatteryEnergyRemaining`,
`xevBatteryMaximumRange` (467.8), `xevBatteryTemperature`,
`xevBatteryChargerCurrentOutput` / `…VoltageOutput`, `xevTractionMotorCurrent` / `…Voltage`,
`xevChargeStationCommunicationStatus` (STATION_READY), `xevBatteryTimeToFullCharge`,
`tripXevBatteryRangeRegenerated`, `tripXevBatteryChargeRegenerated`, `tripFuelEconomy`,
`compassDirection`, `heading`, `yawRate`, `acceleration`, `acceleratorPedalPosition`,
`remoteStartCountdownTimer`, `panicAlarmStatus`, `doorPresenceStatus[]`,
`vehicleLifeCycleMode`, `displaySystemOfMeasure` (IMPERIAL),
`configurations{}` (departure/charge schedules).

These are the rows flagged for your mapping decisions in
`GoHenry-Ford-Telemetry-Field-Mapping.xlsx` (yellow input columns).

---

*No application or backend code was modified to produce this report; no commits were made.
The sole new artifacts are this report and the per-VIN sample JSON under `docs/samples/`.*
