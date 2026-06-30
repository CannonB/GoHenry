package com.gohenry.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Bridges Firebase Cloud Messaging into GoHenry using the SAME backend push
 * pipeline as the SashaSync app (Azure Notification Hubs → FCM v1):
 *  - onNewToken → hand the rotated token to [PushRegistrar], which upserts the
 *    NH installation via POST /api/notifications/register.
 *  - onMessageReceived → post a user-visible trip/charge banner built from
 *    the backend payload.
 *
 * The notifications carry these `data` fields (added by the backend
 * ActivityIngestionService): `event` (trip.started|trip.ended|charge.in_progress|
 * charge.complete|charge.error|signal.lost), `nickname`, `latitude`, `longitude`, and
 * `timestampUtc` (ISO-8601). The time is rendered in the PHONE's local
 * timezone so it matches the device clock.
 */
class GoHenryFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated")
        PushRegistrar(this).onTokenRotated(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val event = data["event"]
        val nickname = data["nickname"]?.takeIf { it.isNotBlank() } ?: "Car"

        // Diagnostics: record that a push arrived (independent of per-VIN capture).
        NotificationStore(this).recordPushSeen()

        val (title, fallbackTitle) = when (event) {
            "trip.started" -> "Start — Ignition on" to (message.notification?.title ?: "Trip started")
            "trip.ended" -> (if (data["roadTripActive"] == "true") "Stop — Active road trip" else "Stop — Ignition off") to (message.notification?.title ?: "Trip ended")
            "charge.in_progress" -> "Charge in progress" to (message.notification?.title ?: "Charge in progress")
            "charge.complete" -> "Charge complete" to (message.notification?.title ?: "Charge complete")
            "charge.error" -> "Charge error" to (message.notification?.title ?: "Charge error")
            "signal.lost" -> "Car lost signal" to (message.notification?.title ?: "Car lost signal")
            "telemetryfeed.lost" -> "Telemetry feed lost" to (message.notification?.title ?: "Telemetry feed lost")
            "tire.pressure_warn" -> "Tire pressure warning" to (message.notification?.title ?: "Tire pressure warning")
            "alarm.triggered" -> "Alarm triggered" to (message.notification?.title ?: "Alarm triggered")
            else -> (message.notification?.title ?: "GoHenry") to "GoHenry"
        }

        // Every alert renders the same body shape so the user always sees the
        // nickname, the trigger detail, the local time, and the lat/long position.
        val body = buildAlertBody(nickname, data, event)

        val resolvedTitle = title.ifBlank { fallbackTitle }
        // A "stop" alert that still has an open road trip deep-links straight into
        // the road-trip tray so the user can end the trip with one tap.
        val roadTripVin = if (event == "trip.ended" && data["roadTripActive"] == "true") {
            data["vin"]?.takeIf { it.isNotBlank() }
        } else null
        showNotification(resolvedTitle, body, event ?: "generic", roadTripVin)

        // Review-only local capture, gated by the in-app toggle. Stored per VIN
        // (newest 10) so the carousel bell can list this vehicle's recent pushes.
        // The FULL data payload is kept so the review screen can show everything.
        maybeStoreForReview(data, event, resolvedTitle, body)

        // Nudge any live, foreground ViewModel to refresh the affected card and
        // capture history so the UI stays in sync with the alert that just landed.
        AppEvents.notifyPushReceived(data["vin"]?.takeIf { it.isNotBlank() })
    }

    /**
     * Persists this push into the local [NotificationStore] for in-app review,
     * but only when the user has enabled capture and the payload identifies a
     * VIN (so it can be filed under the right vehicle). Silent no-op otherwise.
     */
    private fun maybeStoreForReview(
        data: Map<String, String>,
        event: String?,
        title: String,
        body: String,
    ) {
        val vin = data["vin"]?.takeIf { it.isNotBlank() } ?: return
        val store = NotificationStore(this)
        if (!store.isTrackingEnabled(vin)) return
        store.record(
            StoredNotification(
                vin = vin,
                event = event,
                title = title,
                body = body,
                timestampUtc = data["timestampUtc"]?.takeIf { it.isNotBlank() },
                receivedAtMillis = System.currentTimeMillis(),
                data = HashMap(data),
            ),
        )
    }

    /**
     * Unified body for every alert: "{nickname} • {detail} • {local time} • {lat,long}".
     * The backend sends the trigger detail in `data["detail"]`; older payloads fall
     * back to a sensible per-event phrase. Any missing part is simply dropped, but
     * trip/charge/tire/alarm payloads always carry nickname, time and position.
     */
    private fun buildAlertBody(nickname: String, data: Map<String, String>, event: String?): String {
        val parts = mutableListOf(nickname)
        (data["detail"]?.takeIf { it.isNotBlank() } ?: defaultDetail(event))?.let { parts.add(it) }
        // On an end-trip alert, append how far the car travelled since its last
        // start-trip. The distance is computed server-side and delivered in
        // data["tripDistanceKm"], so it no longer depends on the phone's local
        // notification history (correct even after a missed start push or reinstall).
        if (event == "trip.ended") tripDistanceDetail(data)?.let { parts.add(it) }
        localTime(data["timestampUtc"])?.let { parts.add(it) }
        location(data["latitude"], data["longitude"])?.let { parts.add(it) }
        altitude(data["altitude"])?.let { parts.add(it) }
        outsideTemp(data["outsideTempC"])?.let { parts.add(it) }
        return parts.joinToString(" • ")
    }

    /**
     * For an end-trip alert: the straight-line distance the backend reports for the
     * trip (in `data["tripDistanceKm"]`), formatted "Trip X.X km". Returns null (so
     * the body omits it) when the payload carries no distance — e.g. an older backend
     * or a stop with no captured start position.
     */
    private fun tripDistanceDetail(data: Map<String, String>): String? {
        val km = data["tripDistanceKm"]?.toDoubleOrNull() ?: return null
        return "Trip %.1f km".format(km)
    }

    /** Fallback trigger detail when the backend payload omits `detail` (older builds). */
    private fun defaultDetail(event: String?): String? = when (event) {
        "trip.started" -> "Started moving"
        "trip.ended" -> "Parked"
        "charge.in_progress" -> "Charging"
        "charge.complete" -> "Charge complete"
        "charge.error" -> "Charging problem"
        "signal.lost" -> "Stopped reporting movement"
        "telemetryfeed.lost" -> "No telemetry from the car"
        "tire.pressure_warn" -> "Tire pressure warning"
        "alarm.triggered" -> "Alarm triggered"
        else -> null
    }

    private fun localTime(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val local = Instant.parse(iso).atZone(ZoneId.systemDefault())
            local.format(TIME_FMT)
        } catch (t: Throwable) {
            Log.w(TAG, "could not parse timestampUtc='$iso': ${t.message}")
            null
        }
    }

    private fun location(lat: String?, lon: String?): String? {
        val la = lat?.toDoubleOrNull() ?: return null
        val lo = lon?.toDoubleOrNull() ?: return null
        return "%.5f, %.5f".format(la, lo)
    }

    private fun altitude(alt: String?): String? {
        val a = alt?.toDoubleOrNull() ?: return null
        return "Alt %.0f m".format(a)
    }

    private fun outsideTemp(tempC: String?): String? {
        val t = tempC?.toDoubleOrNull() ?: return null
        return "Out %.0f°C".format(t)
    }

    private fun showNotification(title: String, body: String, tag: String, roadTripVin: String? = null) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (roadTripVin != null) {
                putExtra(MainActivity.EXTRA_DEEPLINK, MainActivity.DEEPLINK_ROADTRIP)
                putExtra(MainActivity.EXTRA_VIN, roadTripVin)
            }
        }
        val pi = PendingIntent.getActivity(
            this, tag.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(tag.hashCode(), notif)
    }

    companion object {
        const val CHANNEL_ID = "gohenry_alerts"
        private const val TAG = "GoHenryFcmService"
        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /** Idempotently registers the GoHenry alerts channel with the OS. */
        fun ensureChannel(nm: NotificationManager) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "GoHenry alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Trip and charge alerts from the backend."
                }
            )
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannel(nm)
        }

        /**
         * Posts a local **test** banner through the SAME channel + builder the real
         * push render path uses, so the Settings ▸ Notification diagnostics panel can
         * verify permission + channel + rendering end-to-end without the backend or
         * FCM. Never written to [NotificationStore] (no review-history pollution).
         */
        fun postTestNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannel(nm)
            val tag = "diagnostics-test"
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context, tag.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val now = Instant.now().atZone(ZoneId.systemDefault()).format(TIME_FMT)
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("GoHenry — test alert")
                .setContentText("Notifications are working • $now")
                .setStyle(NotificationCompat.BigTextStyle().bigText("This is a local test from Settings ▸ Notification diagnostics. If you can see it, your alert channel and permission are working. • $now"))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(tag.hashCode(), notif)
        }
    }
}
