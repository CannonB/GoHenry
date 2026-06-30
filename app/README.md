# GoHenry — Android app

A small hobby vehicle tracker, modeled on FleetFoot and **upgraded to Material 3
Expressive**. Package `com.gohenry.app`. It talks only to the GoHenry backend (its
own, SQL-free Azure Functions app) and uses its **own** Firebase project for push —
nothing is shared with FleetFoot / SashaSync / QoastQurrent.

> 🔔 **Notifications:** how alerts are received, rendered, captured, and reviewed on
> the device is documented in the root [README ▸ Notifications](../README.md#notifications).
> In-app self-checks live under **Settings ▸ Notification diagnostics**.

## Screens

- **Car carousel** — swipe between up to 5 cars; engine-aware hero gauge (State of
  Charge for BEV/PHEV, fuel for HEV/GAS), a bell badge on cars with on-device
  notification capture enabled. Tap a card → vehicle status. When a journey is
  open, the hero status box shows a **Roadtrip Active** line. The two bottom corner
  buttons — the **history split button** (left) and the **Settings** button (right) —
  share the same width; a wide **Road trips** button sits centered between them (it
  turns solid and reads **Trip active** while a trip is open) and launches the
  road-trip tray.
- **Vehicle status** — hero gauge + telemetry tiles (range, odometer, charging/plug,
  ignition/gear, temps, locks/alarm/tires/oil) plus a **Raw fields** button.
- **Cached fields** — the raw key/value telemetry snapshot. Each row shows the raw
  Ford telemetry field path (e.g. `metrics.xevBatteryChargeDisplayStatus.value`) in
  small text under its friendly name; the CSV export adds a **Ford Field** column.
- **Road trips** — a **bottom-sheet tray** (opened from the home screen's wide
  **Road trips** button) with a pinned header to **start a named journey** (or
  **stop** the open one) and a
  pull-up **history list** of past trips. Tapping a trip opens its detail screen with
  the full event timeline (start, stop, charge, tire, alarm) with local times and
  coordinates, which flags **auto-started/auto-closed** trips and lets you **rename**
  a trip via a bottom-sheet tray, or **delete** it (trash icon, with confirmation).
  Server-authoritative, so trips survive a reinstall.
- **Alerts & re-auth** — per-car push toggles (start, stop, charge in progress,
  charge complete, charge error, lost signal, telemetry feed lost, tire pressure,
  alarm, road trip started, road trip ended), on-device capture toggle + retention
  (1–7 days), and the **Ford Re-Authorization** section (opens the OAuth link in a browser).
  When a **stop** arrives while a road trip is still open (and *end-on-stop* is
  off), the alert reads **"Active Roadtrip - end it?"** and tapping it deep-links
  straight into that car's road-trip tray so you can end the journey.
- **Settings ▸ Road-trip automation** — app-wide **auto-start** (open a trip on first
  movement), **auto-close** tuning (idle 2–12 h, max age 1–7 days), and an **End the
  trip on stop** toggle (close any open trip when a stop push fires). Also hosts the
  Ford poll cadence (1–10 min).
- **Settings ▸ Lost signal** — sets how many consecutive **missed/frozen polls** (5–20,
  default 10) mark a car offline; shows the effective dwell time (polls × cadence).
  This one threshold drives **both** the `telemetryfeed.lost` alert (Ford feed
  unreachable) and the `signal.lost` alert (feed reachable but car data frozen with
  the ignition on), plus the hero-card wifi icon — which captions which loss is active
  (**Telemetry** / **Signal** / **Both**).
- **Notification history** (per car) and **All recent alerts** (combined) — review-only
  history of received pushes.
- **History split button** — the home screen's bottom-left button is a Material 3
  split button: its primary (history-icon) area opens **notification history** (the
  default), while the **▼** area opens a menu — rendered **above** the button — to
  pick **Notification history** or **Road trip history**.
- **Road trip history** — a swipe-to-filter carousel of past trips (page 1 = all
  cars, then one page per car), mirroring the alert-history screen. Each trip row is
  tinted with its car's home-screen card color. Tap a trip to open its detail; the
  top-bar **share** button exports the visible page (every trip plus its full event
  timeline) to **CSV** and opens the Android share sheet.

## Prerequisites

- **Android Studio** (latest). The build uses its bundled JDK (JBR 17).
- An Android device or emulator on **API 26+** (minSdk 26).
- The **GoHenry backend** running — either locally (`func start` + Azurite, see
  [`../backend/README.md`](../backend/README.md)) or deployed in Azure (see
  [`../GUIDES/azure-setup-guide.md`](../GUIDES/azure-setup-guide.md)).

## 1) Configure `local.properties`

Copy the example and fill it in (this is git-ignored — no secrets in source):

```bash
cp local.properties.example local.properties
```

| Key | Meaning |
| --- | --- |
| `sdk.dir` | Your Android SDK path (Android Studio sets this automatically when you open the project). |
| `backend.baseUrl` | The backend base URL. Local emulator → `http://10.0.2.2:7071/api/`. Azure → `https://<func-app>.azurewebsites.net/api/`. |
| `backend.userId` | Your chosen `x-user-id` (any stable string, e.g. `henry`). **All phones that should see the same cars use the same value.** |
| `backend.functionKey` | The Function App **default host key** (Azure Portal → Function App → App keys). Leave blank for a local `func start` with no key. |

These are compiled into `BuildConfig.BACKEND_BASE_URL` / `USER_ID` / `FUNCTION_KEY`.

## 2) Add your Firebase `google-services.json`

The repo ships a **placeholder** `app/google-services.json` so the project compiles.
For real push you must replace it:

1. In the [Firebase console](https://console.firebase.google.com), create your own
   project and add an **Android app** with package name **`com.gohenry.app`**.
2. Download that project's **`google-services.json`** and drop it in over
   `app/google-services.json`.
3. Generate an **FCM v1 service-account key** (Firebase → Project settings → Service
   accounts) and upload it to your **Azure Notification Hub** (Portal → your hub →
   *Google (FCM v1)*). See the Azure guide for the exact clicks.

## 3) Build & run

From this folder (`GoHenry/app`):

```bash
# Windows: point JAVA_HOME at Android Studio's bundled JDK first
#   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"

./gradlew :app:assembleDebug        # builds app-debug.apk
./gradlew :app:testDebugUnitTest    # runs the unit tests
```

> The build output is redirected to `C:/temp/gradle-builds/GoHenry/…` to avoid
> OneDrive file-locking — the APK lands at
> `C:/temp/gradle-builds/GoHenry/app/outputs/apk/debug/app-debug.apk`.

Or just open the `GoHenry/app` folder in Android Studio and press **Run**.

## 4) Pair a phone

1. Install the APK on the phone (Android Studio **Run**, or `adb install` the APK).
2. Make sure `local.properties` has the right `backend.baseUrl`, your `backend.userId`,
   and `backend.functionKey`, then rebuild so they're baked in.
3. Launch the app. On first run it asks for **notification permission** (Android 13+).
4. Open **Alerts & re-auth → Ford Re-Authorization → link**, complete the Ford sign-in
   in the browser, and return to the app.
5. Within a couple of minutes the poller discovers your cars and the carousel fills in
   with live status. Toggle the alerts you want per car.

To put a **second phone** on the **same cars**, install the app with the **same
`backend.userId`**.

## Tests

`app/src/test/java/com/gohenry/app/`:

- `ApiJsonMappingTest` — backend JSON → DTO parsing.
- `CarVisualsTest` — engine-type → visual mapping.
- `NotificationStoreTest` — capture, retention pruning, clear-on-toggle (Robolectric).

```bash
./gradlew :app:testDebugUnitTest
```

## Notes

- Errors are surfaced with a friendly, multi-line message that includes a **redacted**
  request URL and a config summary (base URL, user id, whether a key is set) so a
  misconfigured `local.properties` is obvious without a debugger.
- `local.properties` and `google-services.json` are intentionally **not** meant to be
  committed.
