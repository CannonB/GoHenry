# GoHenry — Implement the Full Ford Telemetry Datafeed → Raw Data Screen (Step 1)

> **Use this prompt verbatim as the task for the implementing session.** It is grounded
> in the actual GoHenry codebase and in the user's completed field-mapping workbook
> (`docs/GoHenry-Ford-Telemetry-Field-Mapping.xlsx`, tab **Field Mapping**). Follow it
> exactly. Where it says **CONSENT REQUIRED**, do **not** proceed without an explicit
> "yes" from the user.

---

## 1. Mission

Wire the **complete** Ford telemetry feed end-to-end through the GoHenry backend and
**expose all mapped fields on the existing Raw Data screen** (`CachedFieldsScreen`),
using the **app-field names the user chose** in the Field Mapping tab. This must be
**fully wired across every vehicle** and leave the raw data available for later UI use.

**This is Step 1 and it is purely additive.** The working hero/carousel/detail UI and
its gauge must look and behave **exactly the same** afterward. The only visible change
is that the Raw Data screen now lists the full field set instead of ~16 fields.

### Hard guardrails (do not violate)
- **No SQL.** Persist everything in Azure **Table Storage** (+ Blob only if explicitly
  needed). The whole backend is SQL-free by design — keep it that way.
- **Independent** of FleetFoot / SashaSync / QoastQurrent and their Azure backend.
  `FordPayloadNormalizer.cs` in SashaSync is a **read-only parity reference**, never a
  dependency.
- **Do not change any of the 16 existing `TelemetryDto` fields** or their `Snap*`
  sources. They keep the app functional. Changing them is gated — see §10.
- **Do not commit to git.** Build, deploy, and verify, but leave the working tree
  uncommitted.
- **Values are shown as-is** with their original Ford units (no km→mi / °C→°F / kPa→psi
  conversion).
- Hobby scope: ≤5 cars / 5 phones. Keep it simple, robust, and well-documented.

---

## 2. Locked design decisions (already confirmed with the user)

| Decision | Choice |
|---|---|
| How raw fields reach the app | **Embed a `rawFields` array inside the existing** `GET /api/fleet/telemetry/{vin}` **DTO** — no new route. |
| Demo / un-linked VINs | **Do not seed.** Raw Data is populated only from real Ford polls; un-linked VINs show an empty/sparse list gracefully. |
| Essential-field remaps | **Deferred behind explicit consent** (§10). Not part of Step 1. |
| Value display | **Raw values with original Ford units, as-is.** No conversion. |

---

## 3. Current pipeline (verified — know this before you touch anything)

```
Poll_Dispatcher (timer, every 2 min)
  └─ fans every non-paused VIN onto the gohenry-poll queue
Poll_Worker (queue)
  ├─ FordApiClient.GetVehicleStatusJsonAsync(token, vin)  → RAW Ford JSON (the "metrics" envelope)
  ├─ FordTelemetryNormalizer.Normalize(...)               → 16-field VehicleSnapshot
  ├─ ApplySnapshot(entity, snap)                          → writes 16 Snap* columns on VinEntity
  └─ RAW JSON IS DISCARDED  ← the gap we are closing
Fleet_Telemetry (GET /api/fleet/telemetry/{vin})
  └─ returns VinEntity.ToTelemetryDto()   (the 16 fields only)
App: GoHenryApi.getCachedTelemetryFields(vin)
  └─ re-fetches that DTO, flattens ~16 top-level keys → CachedFieldsScreen ("Raw Data")
```

**Key files**
- `backend/src/GoHenry.Api/Functions/PollerFunctions.cs` — `Poll_Worker`, `ApplySnapshot`.
- `backend/src/GoHenry.FordClient/FordApiClient.cs` — `GetVehicleStatusJsonAsync` returns the raw body.
- `backend/src/GoHenry.Storage/Entities.cs` — `VinEntity` (Table row, `Snap*` columns).
- `backend/src/GoHenry.Storage/StoreMappings.cs` — `ToTelemetryDto`.
- `backend/src/GoHenry.Core/Models/TelemetryDto.cs` — the wire DTO.
- `backend/src/GoHenry.Core/Normalization/FordTelemetryNormalizer.cs` — **leave as-is** for Step 1.
- `backend/src/GoHenry.Api/Functions/FleetFunctions.cs` — `Fleet_Telemetry` route.
- `app/app/src/main/java/com/gohenry/app/GoHenryApi.kt` — `Telemetry`, `parseTelemetry`, `getCachedTelemetryFields`.
- `app/app/src/main/java/com/gohenry/app/MainActivity.kt` — `CachedFieldsScreen` (~line 678).
- **Parity reference:** `SashaSync/backend/src/QoastQurrent.Core/Normalization/FordPayloadNormalizer.cs`
  — has the array-discriminator helpers (`TryGetWheelString`, `TryGetDoorLockStatus`,
  `TryGetDoorStatusByDoor`) using `vehicleWheel` / `vehicleDoor` / `ALL_DOORS`.
- **Real sample for tests:** `docs/samples/ford-telemetry-1FT6W5L77SWG23727-2026-05-01T211407Z.json`.

---

## 4. Canonical field map (the user's Field Mapping tab — 60 rows)

Embed this **verbatim** as a static `RawFieldMap` in `GoHenry.Core`. Preserve the
user's app-field names **exactly as authored** (including spellings like `Logitude`,
`PostionUpdateTimestamp`, `ChargerCommunictionStatus`, `SoCCharge%`, `FordTelemtryTimeStamp`).
`Source`: `T` = from the Ford telemetry JSON, `M` = vehicle metadata already on `VinEntity`.

| # | App Field (display name & key) | Ford JSON Path | Type | Unit | Engine | Source |
|---|---|---|---|---|---|---|
| 1 | SoCCharge% | metrics.xevBatteryStateOfCharge.value | number | % | BEV | T |
| 2 | SoCChargeDisplayStatus | metrics.xevBatteryChargeDisplayStatus.value | string |  | BEV | T |
| 3 | ChargerStatus | metrics.xevPlugChargerStatus.value | string |  | BEV | T |
| 4 | BatteryTemp | metrics.xevBatteryTemperature.value | number | C | BEV | T |
| 5 | BatteryCurrent | metrics.xevBatteryIoCurrent.value | number | A | BEV | T |
| 6 | BatteryVoltage | metrics.xevBatteryVoltage.value | number | V | BEV | T |
| 7 | BatteryCapacity | metrics.xevBatteryCapacity.value | number | kWh | BEV | T |
| 8 | BatteryPrefStatus | metrics.xevBatteryPerformanceStatus.value | string |  | BEV | T |
| 9 | BatteryTime2Full | metrics.xevBatteryTimeToFullCharge.value | number | min | BEV | T |
| 10 | ChargerType | metrics.xevChargeStationPowerType.value | string |  | BEV | T |
| 11 | ChargerCommunictionStatus | metrics.xevChargeStationCommunicationStatus.value | string |  | BEV | T |
| 12 | BatteryTotalDistance | metrics.tripXevBatteryDistanceAccumulated.value | number | km | BEV | T |
| 13 | BatteryLoad | metrics.batteryLoadStatus.value | string |  | BEV | T |
| 14 | FuelLevel | metrics.fuelLevel.value | number | % | HEV | T |
| 15 | FuelRange | metrics.fuelRange.value | number | km | HEV | T |
| 16 | FuelTripEconomy | metrics.tripFuelEconomy.value | number | L/100km | HEV | T |
| 17 | Odometer | metrics.odometer.value | number | km | BOTH | T |
| 18 | EngineSpeed | metrics.engineSpeed.value | number | rpm | HEV | T |
| 19 | CoolantTemp | metrics.engineCoolantTemp.value | number | C | HEV | T |
| 20 | AccelerationX | metrics.acceleration.value.x | number | g | BOTH | T |
| 21 | AccelerationY | metrics.acceleration.value.y | number | g | BOTH | T |
| 22 | AccelerationZ | metrics.acceleration.value.z | number | g | BOTH | T |
| 23 | Latitude | metrics.position.value.location.lat | number | deg | BOTH | T |
| 24 | Logitude | metrics.position.value.location.lon | number | deg | BOTH | T |
| 25 | Altitude | metrics.position.value.location.alt | number | m | BOTH | T |
| 26 | PostionUpdateTimestamp | metrics.position.updateTime | datetime | ISO8601 | BOTH | T |
| 27 | Heading | metrics.heading.value | number | deg | BOTH | T |
| 28 | Compass | metrics.compassDirection.value | string |  | BOTH | T |
| 29 | IgnitionStatus | metrics.ignitionStatus.value | string |  | BOTH | T |
| 30 | GearLever | metrics.gearLeverPosition.value | string |  | BOTH | T |
| 31 | HybridStatus | metrics.hybridVehicleModeStatus.value | string |  | HEV | T |
| 32 | VehicleLifecycle | metrics.vehicleLifeCycleMode.value | string |  | BOTH | T |
| 33 | BrakePedalStatus | metrics.brakePedalStatus.value | string |  | BOTH | T |
| 34 | DoorLocked | metrics.doorLockStatus[].value | string |  | BOTH | T |
| 35 | Alarm | metrics.alarmStatus.value | string |  | BOTH | T |
| 36 | HoodStatus | metrics.hoodStatus.value | string |  | BOTH | T |
| 37 | TailGateStatus | metrics.doorStatus[TAILGATE].value | string |  | BOTH | T |
| 38 | InnerTailGateStatus | metrics.doorStatus[INNER_TAILGATE].value | string |  | BOTH | T |
| 39 | SeatOccupancy | metrics.seatBeltStatus[].value (a.k.a. seatOccupancy) | string |  | BOTH | T |
| 40 | TirePressureSystemStatus | metrics.tirePressureSystemStatus.value | string |  | BOTH | T |
| 41 | TireFrontLeftStatus | metrics.tirePressureStatus[FRONT_LEFT].value | string |  | BOTH | T |
| 42 | TireFrontRightStatus | metrics.tirePressureStatus[FRONT_RIGHT].value | string |  | BOTH | T |
| 43 | TireRearLeftStatus | metrics.tirePressureStatus[REAR_LEFT].value | string |  | BOTH | T |
| 44 | TireRearRightStatus | metrics.tirePressureStatus[REAR_RIGHT].value | string |  | BOTH | T |
| 45 | TirePressureFrontLeft | metrics.tirePressure[FRONT_LEFT].value | number | kPa | BOTH | T |
| 46 | TirePressureFrontRight | metrics.tirePressure[FRONT_RIGHT].value | number | kPa | BOTH | T |
| 47 | TirePressureRearLeft | metrics.tirePressure[REAR_LEFT].value | number | kPa | BOTH | T |
| 48 | TirePressureRearRight | metrics.tirePressure[REAR_RIGHT].value | number | kPa | BOTH | T |
| 49 | OutsideTemp | metrics.outsideTemperature.value | number | C | BOTH | T |
| 50 | InteriorTemp | metrics.ambientTemp.value | number | C | BOTH | T |
| 51 | OilLifeLeft% | metrics.oilLifeRemaining.value | number | % | HEV | T |
| 52 | SystemofMeasure | metrics.displaySystemOfMeasure.value | string |  | BOTH | T |
| 53 | FordTelemtryTimeStamp | $.updateTime | datetime | ISO8601 | BOTH | T |
| 54 | Key | vin | string |  | BOTH | M |
| 55 | Nickname | nickName | string |  | BOTH | M |
| 56 | Model | modelName | string |  | BOTH | M |
| 57 | modelyear | modelYear | number |  | BOTH | M |
| 58 | Color | color | string |  | BOTH | M |
| 59 | enginetype | engineType | string |  | BOTH | M |
| 60 | V12batteryStateOfCharge | metrics.batteryStateOfCharge.value | string |  | BOTH | T |

### Tire numeric rows 45–48 — finalized names
In the user's sheet the four `tirePressure` numeric rows reused only **two** names
(`TirePressureLeftStatus`, `TirePressureRightStatus`), which would collide as map keys.
They are **finalized** (per user approval) to unique per-corner names:
`TirePressureFrontLeft / TirePressureFrontRight / TirePressureRearLeft / TirePressureRearRight`.
All other names are kept exactly as authored, typos included.

---

## 5. Path-resolution engine (Core, new) — `FordRawFieldExtractor`

Create `backend/src/GoHenry.Core/Normalization/FordRawFieldExtractor.cs`. It parses the
raw Ford JSON once (`JsonDocument`) and resolves every `T`-source path in `RawFieldMap`
to a display string. Supported path grammar:

| Path form | Meaning |
|---|---|
| `$.updateTime` | root-level property |
| `metrics.X.value` | metric leaf — Ford metrics are `{updateTime, oemCorrelationId, value, …}` |
| `metrics.X.value.y` | nested scalar under a metric's `value` object (e.g. acceleration x/y/z) |
| `metrics.position.value.location.lat` | deep nested object chain |
| `metrics.position.updateTime` | a metric's own metadata field |
| `metrics.ARR[KEY].value` | **array discriminator** — pick the element whose discriminator field equals `KEY`, then read `.value` |
| `metrics.doorLockStatus[].value` | unindexed array — prefer the `ALL_DOORS` element, else the first element's `.value` |

**Array discriminator rules** (mirror `FordPayloadNormalizer.cs`):
- `tirePressureStatus[...]` and `tirePressure[...]` → match on `vehicleWheel`
  (`FRONT_LEFT`, `FRONT_RIGHT`, `REAR_LEFT`, `REAR_RIGHT`).
- `doorStatus[...]` → match on `vehicleDoor` (`TAILGATE`, `INNER_TAILGATE`).
- `doorLockStatus[]` → prefer `vehicleDoor == "ALL_DOORS"`, else first.
- `seatBeltStatus[]` → first element's `.value` (row 39).

**Value stringification (as-is):**
- number → invariant `ToString()` (no rounding, no unit math).
- string / bool → as-is.
- object with `value` → stringify the inner `value`.
- missing / null / wrong-shape → **omit the field** (do not emit `"null"`); the screen
  simply won't list absent fields. (Engine-irrelevant fields are naturally absent.)

Return `IReadOnlyList<RawFieldEntry>` where
`RawFieldEntry { string Name; string Value; string? Unit; string EngineScope; }`,
**only** for fields that resolved to a present value.

---

## 6. Persist the projection (SQL-free) — `VinEntity.RawFieldsJson`

In `Entities.cs`, add **one** nullable string column to `VinEntity`:

```csharp
// --- Full raw-telemetry projection (curated per RawFieldMap), JSON-encoded ---
public string? RawFieldsJson { get; set; }   // e.g. [{"name":"SoCCharge%","value":"77","unit":"%","engine":"BEV"}, ...]
```

- This is a compact JSON array (~3–5 KB for a full BEV poll) — far under Table Storage's
  64 KB-per-property / 1 MB-per-entity limits. **No 60-column explosion. No SQL.**
- Do **not** add `RawTelemetryJson` (full raw blob) in Step 1 unless the user asks; the
  curated projection is what the screen needs. (If later desired, it's a second string
  column or a Blob — note it as a future option, don't build it now.)

**Poller wiring** — in `PollerFunctions.Poll_Worker`, after `json` is fetched and the
existing `Normalize`/`ApplySnapshot` runs **unchanged**, add:

```csharp
var rawFields = FordRawFieldExtractor.Extract(json, engine);     // engine-scoped telemetry fields
entity.RawFieldsJson = JsonSerializer.Serialize(rawFields);      // additive; Snap* writes untouched
```

Because `Poll_Dispatcher` already enqueues **every non-paused VIN**, this captures the
full feed **fleet-wide automatically** — no per-VIN special-casing.

---

## 7. Expose it on the existing endpoint — embed `rawFields` in the DTO

**`TelemetryDto.cs`** — add (additive, nullable; existing 16 fields untouched):

```csharp
/// <summary>Full curated Ford raw-telemetry fields for the Raw Data screen (additive).</summary>
public List<RawFieldDto>? RawFields { get; set; }

public sealed class RawFieldDto
{
    public string Name { get; set; } = "";
    public string Value { get; set; } = "";
    public string? Unit { get; set; }
    public string? Engine { get; set; }   // BEV | HEV | BOTH
}
```

**`StoreMappings.ToTelemetryDto`** — after building the existing DTO, populate
`RawFields` by:
1. Deserializing `e.RawFieldsJson` (the `T`-source fields), if present.
2. Folding in the **`M`-source metadata** fields (rows 54–59) from the `VinEntity`
   columns directly (`Key`=RowKey, `Nickname`=Nickname, `Model`=Model,
   `modelyear`=ModelYear, `Color`=DisplayColor, `enginetype`=EngineType).
3. Leaving `RawFields = null` when there's no projection and no metadata (graceful empty).

`Fleet_Telemetry` and `Fleet_TelemetryCache` need **no route changes** — they already
return `ToTelemetryDto()`.

---

## 8. App — render the full list on the Raw Data screen only

**`GoHenryApi.kt`**
- Add a `rawFields: List<RawField>` field to the `Telemetry` data class and a
  `RawField(name, value, unit, engine)` model; parse the `rawFields` array in
  `parseTelemetry` (tolerant: absent ⇒ empty list).
- Replace the body of `getCachedTelemetryFields(vin)` so that **when** the telemetry
  payload includes `rawFields`, it returns those `name → value (+unit suffix)` pairs;
  otherwise it falls back to the current flatten-the-DTO behavior (so older backends
  still work). Build the display value as `value + (unit?.let { " $it" } ?: "")`.

**`MainActivity.kt` → `CachedFieldsScreen`** (~line 678)
- Keep the screen's structure. It already renders `state.cachedFields` as
  name/value cards and shows an `EmptyState` when empty — that satisfies the
  "don't seed demo VINs" decision. **Do not change any other screen.**
- Optional, low-risk nicety (only if trivial): keep the existing `sortedBy { it.first }`
  or preserve `RawFieldMap` order — either is acceptable; do not over-engineer.

No changes to the hero card, carousel, gauge, detail, notifications, or settings.

---

## 9. Engine-scope behavior
- The extractor already drops fields that aren't present in a given vehicle's feed, so
  a BEV simply won't have `FuelLevel`/`EngineSpeed`, and an ICE won't have `xev*`.
- Use the `Engine` column only as **metadata** on each row (so a future UI can filter);
  do **not** hide rows by engine type in Step 1 — show whatever the feed actually returned.

---

## 10. CONSENT REQUIRED — essential remaps (explicitly OUT of Step 1)

These would change what the **working** gauge/hero shows, so **do not implement them**
in this task. List them to the user and wait for an explicit "yes" before any of them:

| Working DTO field | Today (kept) | Correct source (needs consent) |
|---|---|---|
| `SocPct` (gauge) | `batteryStateOfCharge` (12 V, ~90 %) | `xevBatteryStateOfCharge` (~77 %) |
| `OutsideTempValue` | `ambientTemp` (0.0) | `outsideTemperature` (22.75) |
| `InteriorTempValue` | `ambientTemp` | confirm intended cabin source |
| `RangeValue` | null | `xevBatteryRange` (BEV) / `fuelRange` (HEV) |
| `ChargingStatus` | null | `xevBatteryChargeDisplayStatus` (+ `xevPlugChargerStatus`) |
| `PluggedIn` | null | derive `xevPlugChargerStatus == "CONNECTED"` |

Until consent, the gauge keeps showing exactly what it shows today. The **correct**
values are nonetheless **visible on the Raw Data screen** (rows 1, 49, 50, etc.), which
is the whole point of Step 1.

---

## 11. Tests
Add to `backend/tests/GoHenry.Tests/`:
- **`FordRawFieldExtractorTests`** — load the real sample
  `docs/samples/ford-telemetry-1FT6W5L77SWG23727-…json` and assert key resolutions:
  `SoCCharge% == "77"`, `Odometer == "8612"`, `FuelRange`/`xevBatteryRange` →
  `BatteryTotalDistance`/`FuelRange` as mapped, `ChargerStatus == "CONNECTED"`,
  `SoCChargeDisplayStatus == "IN_PROGRESS"`, `TireFrontLeftStatus == "NORMAL"`,
  `DoorLocked == "LOCKED"`, `OutsideTemp == "22.75"`, `Latitude == "47.639606"`,
  `V12batteryStateOfCharge == "90"`. Assert engine-irrelevant fields are **absent**.
- **Contract test** — serialize `ToTelemetryDto()` for a populated `VinEntity` and assert
  (a) all 16 original fields are byte-stable/unchanged, and (b) `rawFields` round-trips.
- Keep existing `FordTelemetryNormalizerTests` green (you didn't touch the normalizer).

---

## 12. Build, deploy, verify (no git commit)

**Backend**
```powershell
cd "C:\Users\terrenca\OneDrive - Microsoft\FY26\GHCLI\GoHenry\backend"
dotnet build
dotnet test
# deploy the Function App (same target as today): func azure functionapp publish func-gohenry-doit
```

**App** (per stored build convention)
```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
cd "C:\Users\terrenca\OneDrive - Microsoft\FY26\GHCLI\GoHenry\app"
.\gradlew.bat :app:assembleDebug
# adb: $env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe -s 59090DLCQ008MB install -r app\build\... (build output redirects to C:\temp\gradle-builds\GoHenry)
```

**On-device verification**
1. Open a vehicle → **Raw Data** screen.
2. Wait for at least one poll cycle (≤2 min) after deploy so `RawFieldsJson` is written.
3. Confirm the full mapped field set renders with values + units, and that the hero
   gauge/carousel/detail are **visually unchanged**.
4. Screenshot for the record (splash holds ~3 s — wait ≥7 s before capturing; wake the
   device if `screencap` is black).

---

## 13. Definition of done
- [ ] `RawFieldMap` (60 rows, verbatim names) + `FordRawFieldExtractor` + `RawFieldsJson`
      column added; poller writes the projection for every VIN; 16 `Snap*` writes unchanged.
- [ ] `TelemetryDto.RawFields` embedded and populated (telemetry + metadata) with the 16
      original fields byte-stable.
- [ ] App parses `rawFields` and the **Raw Data screen** lists the full set; no other
      screen changed; gauge/hero identical to before.
- [ ] Backend builds + all tests pass against the real sample; app builds, installs, and
      shows the full list on device.
- [ ] Tire-row naming finalized to `TirePressureFrontLeft/FrontRight/RearLeft/RearRight` (done).
- [ ] Essential remaps (§10) **not** implemented; presented to the user for consent.
- [ ] **Nothing committed to git.**

---

## 14. Explicit DO-NOTs
- ❌ No SQL / relational store of any kind.
- ❌ No new dependency on FleetFoot / SashaSync / their backend.
- ❌ No change to `FordTelemetryNormalizer`, the 16 `Snap*` fields, or any non-Raw-Data UI.
- ❌ No unit conversion; no rounding of raw values.
- ❌ No git commit.
- ❌ No essential-field remap (§10) without an explicit user "yes".
