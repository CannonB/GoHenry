package com.gohenry.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only snapshot of everything that determines whether a backend alert will
 * actually reach the user on THIS phone. Collected on demand by the
 * Settings ▸ Notification diagnostics panel — it never changes any state.
 *
 * Design notes:
 *  - SQL-free / backend-free: every value comes from on-device OS state or the
 *    app's own SharedPreferences ([NotificationStore], [PushRegistrar]).
 *  - The FCM token is only ever shown truncated (see [PushRegistrar.lastTokenPrefix]).
 */
data class NotificationDiagnostics(
    val postPermissionRequired: Boolean,   // false on < Android 13 (granted implicitly)
    val postPermissionGranted: Boolean,
    val systemNotificationsEnabled: Boolean,
    val channelExists: Boolean,
    val channelBlocked: Boolean,           // channel importance == NONE
    val tokenPrefix: String?,
    val lastRegisteredAtMillis: Long,
    val lastRegisterOk: Boolean?,
    val lastPushSeenMillis: Long,
    val captureEnabledVinCount: Int,
    val captureRetentionDays: Int,
    val capturedTotal: Int,
) {
    val deliverable: Boolean
        get() = (!postPermissionRequired || postPermissionGranted) &&
            systemNotificationsEnabled && channelExists && !channelBlocked

    companion object {
        private val STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun millisToLocal(millis: Long): String =
            if (millis <= 0L) "never"
            else Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(STAMP)

        /** Gathers the current notification-readiness snapshot. Pure read. */
        fun collect(context: Context): NotificationDiagnostics {
            val nmc = NotificationManagerCompat.from(context)
            val permRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val permGranted = !permRequired || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

            var channelExists = false
            var channelBlocked = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val ch = nm?.getNotificationChannel(GoHenryFcmService.CHANNEL_ID)
                channelExists = ch != null
                channelBlocked = ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE
            } else {
                channelExists = true
            }

            val push = PushRegistrar(context)
            val store = NotificationStore(context)
            val enabledVins = store.enabledVins()

            return NotificationDiagnostics(
                postPermissionRequired = permRequired,
                postPermissionGranted = permGranted,
                systemNotificationsEnabled = nmc.areNotificationsEnabled(),
                channelExists = channelExists,
                channelBlocked = channelBlocked,
                tokenPrefix = push.lastTokenPrefix(),
                lastRegisteredAtMillis = push.lastRegisteredAtMillis(),
                lastRegisterOk = push.lastRegisterOk(),
                lastPushSeenMillis = store.lastPushSeenMillis(),
                captureEnabledVinCount = enabledVins.size,
                captureRetentionDays = store.getTrackingDays(),
                capturedTotal = enabledVins.sumOf { store.forVin(it).size },
            )
        }

        /** Plain-text report for the copy/share action. Contains no full secrets. */
        fun buildShareText(d: NotificationDiagnostics): String = buildString {
            appendLine("GoHenry — Notification diagnostics")
            appendLine("Generated: ${millisToLocal(System.currentTimeMillis())}")
            appendLine()
            appendLine("Overall: ${if (d.deliverable) "READY — alerts can reach this phone" else "BLOCKED — see items below"}")
            appendLine()
            appendLine("[Device permission]")
            appendLine("  POST_NOTIFICATIONS required: ${d.postPermissionRequired}")
            appendLine("  POST_NOTIFICATIONS granted:  ${d.postPermissionGranted}")
            appendLine("  System notifications on:     ${d.systemNotificationsEnabled}")
            appendLine("  Alert channel present:       ${d.channelExists}")
            appendLine("  Alert channel blocked:       ${d.channelBlocked}")
            appendLine()
            appendLine("[FCM registration]")
            appendLine("  Token (truncated):  ${d.tokenPrefix ?: "none"}")
            appendLine("  Last registered:    ${millisToLocal(d.lastRegisteredAtMillis)}")
            appendLine("  Last result:        ${d.lastRegisterOk?.let { if (it) "ok" else "failed" } ?: "never"}")
            appendLine("  Last push received: ${millisToLocal(d.lastPushSeenMillis)}")
            appendLine()
            appendLine("[Local capture]")
            appendLine("  Tracked VINs:    ${d.captureEnabledVinCount}")
            appendLine("  Retention days:  ${d.captureRetentionDays}")
            appendLine("  Stored entries:  ${d.capturedTotal}")
        }
    }
}
