# Prompt — Validate & Surface All Native Ford Telemetry in GoHenry

> Paste the section below to an agent/engineer working in the GoHenry repo
> (`C:\Users\terrenca\OneDrive - Microsoft\FY26\GHCLI\GoHenry`). It drives an
> end‑to‑end validation of the Ford telemetry feed, makes the full raw payload
> available in the Azure data tables, and (only with explicit approval) exposes
> the approved fields on the app's existing **Raw Data** screen.
>
> **Hard constraints:** No SQL (Azure Table Storage / Blob only). Do **not**
> change vehicle/auth/poller behavior. Do **not** implement any app or mapping
> change without express written consent. Do **not** commit to git.

---

## Prompt

You are working on **GoHenry**, a SQL‑free Azure Functions + Android (Jetpack
Compose) vehicle tracker that reads Ford telemetry through the FordConnect Query
API. Your job is to **validate and pull every available field from the native
Ford telemetry feed, guarantee it lands in the Azure data tables, and prepare
(but not implement) a field‑to‑UI mapping** for review.

### 0. Ground rules
- **No SQL.** Persist only to Azure **Table Storage** (and **Blob** for large raw
  JSON if needed). The design intentionally has no relational dependency.
- **Non‑destructive.** Do not alter Ford OAuth, the garage call, the poller
  cadence, or any existing `Snap*` columns/DTO fields. Add alongside; never
  rename or repurpose.
- **Consent gate.** Produce findings + the mapping workbook and **stop**. Apply
  app/back‑end mapping changes only after the user approves the workbook. The
  **only** sanctioned app change is exposing approved raw fields on the existing
  **Raw Data** screen (`CachedFieldsScreen`).
- **No commits.** Leave all changes uncommitted for review.

### 1. Validate Ford authentication & live pull
1. Confirm a linked, ACTIVE Ford account exists (`GET /api/ford/account/status`).
   If `NEEDS_REAUTH`, stop and report — do not attempt re‑link silently.
2. For each linked VIN, call the live telemetry endpoint the backend already
   uses: `GET {ApiBaseUrl}/v1/telemetry` with `Authorization: Bearer <token>`
   and `Application-Id: <ClientId>` (see `FordApiClient.GetVehicleStatusJsonAsync`).
   Capture the **complete raw JSON response** verbatim, per VIN.
3. Record HTTP status, payload size, and `updateTime`. Save each raw payload to
   `docs/samples/ford-telemetry-<vin>-<timestampUtc>.json` for evidence.

### 2. Enumerate ALL available fields
4. Detect the envelope shape: modern **`metrics`** object vs. legacy
   **`vehiclestatus`** (see `QoastQurrent.Core.Normalization.FordPayloadNormalizer`
   in the FleetFoot repo at `..\SashaSync` for the authoritative field set —
   GoHenry must reach parity with it).
5. **Flatten** every leaf to a dotted JSON path (e.g.
   `metrics.xevBatteryStateOfCharge.value`, `metrics.position.value.location.lat`,
   `metrics.tirePressureStatus[FRONT_LEFT].value`). Emit, per field: path, JSON
   type, sample value, unit (if a `{value,unit}` leaf), per‑leaf `updateTime`,
   and presence frequency across the samples.
6. **Diff** that live inventory against what GoHenry captures today
   (`GoHenry.Core.Normalization.FordTelemetryNormalizer`). Classify each field:
   - **Captured** — current normalizer key matches the live key and it is stored.
   - **Missed** — Ford sends it but the normalizer's candidate keys do not match
     the live `metrics` key, so it is dropped before Azure.
   - **Mismapped** — a similarly‑named key is captured but routed to the wrong
     DTO field (e.g. `ambientTemp` → OutsideTemp).
   > Expected finding: GoHenry was seeded with flat keys (`soc`, `odometer`,
   > `latitude`). The live feed is the `xev*` / nested `metrics` envelope, so most
   > energy/charge/tire/position‑detail fields are currently **Missed**, and the
   > **raw JSON is never persisted** (only ~16 `Snap*` columns are).

### 3. Make the raw feed available in the Azure data tables (no SQL)
7. Add a **non‑destructive raw‑capture path** so the app can read the full feed:
   - **Option A (preferred):** add a `RawTelemetryJson` (string) + `RawCapturedAt`
     to the existing `VinEntity` row, written by the poller alongside `Snap*`.
     If the JSON can exceed Azure Table's 32 KB property limit, store it in a
     **Blob** (`raw-telemetry/<vin>/<utc>.json`) and keep only the blob URI +
     a flattened **key/value** projection in a new `RawFieldEntity`
     (PartitionKey = vin, RowKey = field path).
   - Keep it append‑only and bounded (latest snapshot + optional short history).
8. Expose a **read** surface for the app without breaking the current contract:
   either extend `GET /api/fleet/telemetry/{vin}` with an opt‑in
   `?include=raw` block, or add `GET /api/fleet/telemetry/{vin}/raw` returning the
   flattened `{ path: value }` map. The current `getCachedTelemetryFields()` in
   the app reads the **DTO**, not the true raw feed — this step is what makes the
   Raw Data screen show the *actual* Ford payload.
9. **Validate round‑trip:** poll → raw persisted to Table/Blob → read endpoint
   returns the same flattened fields. Prove with the saved sample(s) that a known
   field (e.g. `xevBatteryStateOfCharge`) survives end‑to‑end.

### 4. Produce the mapping workbook (for human review)
10. Generate / update `docs/GoHenry-Ford-Telemetry-Field-Mapping.xlsx` with one
    row per discovered field. Pre‑fill the reference columns (raw key, JSON path,
    type, unit, Captured/In‑DTO, **GoHenry Live‑Feed Status**) and leave the
    yellow user columns blank: **Map to App Field**, **Target Screen/Section**,
    **Show on Raw Data screen?**, **Priority**, **Keep raw value?**, **Notes**.
    (A seeded version already exists — reconcile new fields into it, don't
    overwrite the user's decisions.)
11. Write a short `docs/ford-telemetry-validation-report.md`: per‑VIN pull
    status, total fields found, Captured/Missed/Mismapped counts, and the exact
    normalizer key changes that would be required to close each gap.

### 5. App change — ONLY the Raw Data screen, ONLY on approval
12. After the user approves the workbook, the **sole** app change is to point
    `CachedFieldsScreen` at the new raw endpoint so it lists every approved field
    (path + value), sorted, with the existing "Reload fields" affordance. Do
    **not** add fields to the hero card, detail tiles, or notifications unless the
    workbook explicitly assigns them there in a separate, separately‑approved
    pass. Build (`:app:assembleDebug`), run unit tests, and verify on device.

### 6. Deliverables
- Saved raw sample payload(s) under `docs/samples/`.
- Updated `docs/GoHenry-Ford-Telemetry-Field-Mapping.xlsx` (reconciled).
- `docs/ford-telemetry-validation-report.md` with the gap analysis.
- A written list of the exact, **proposed** (not applied) normalizer + storage
  changes needed for each field the user selects.
- Confirmation that nothing was committed and no behavior changed without consent.

### Key source references
- `backend/src/GoHenry.FordClient/FordApiClient.cs` — auth + `/v1/telemetry` pull.
- `backend/src/GoHenry.Core/Normalization/FordTelemetryNormalizer.cs` — current
  (flat‑key) extraction; the source of the Missed/Mismapped gaps.
- `backend/src/GoHenry.Storage/Entities.cs` — `VinEntity` (`Snap*` columns; raw
  JSON is **not** stored today).
- `backend/src/GoHenry.Core/Models/TelemetryDto.cs` — app wire contract.
- `app/app/src/main/java/com/gohenry/app/GoHenryApi.kt` —
  `getCachedTelemetryFields()` (reads the DTO today).
- `app/app/src/main/java/com/gohenry/app/MainActivity.kt` — `CachedFieldsScreen`
  (the Raw Data screen to extend).
- FleetFoot parity reference:
  `..\SashaSync\backend\src\QoastQurrent.Core\Normalization\FordPayloadNormalizer.cs`.
