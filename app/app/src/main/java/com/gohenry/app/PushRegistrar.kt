package com.gohenry.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Coordinates FCM ↔ backend registration for GoHenry, mirroring the SashaSync
 * app's PushRegistrar but without Hilt (GoHenry has no DI graph).
 *
 * Both the cold-start path (called from [MainActivity.onCreate]) and the token-
 * rotation path (called from [GoHenryFcmService.onNewToken]) funnel through here so
 * the install-id persistence lives in one place. The install id is a stable
 * per-install UUID stashed in SharedPreferences so updates patch the same
 * Notification Hub record instead of leaking duplicates.
 */
class PushRegistrar(
    context: Context,
    private val api: GoHenryApi = GoHenryApi(),
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stable per-install UUID; created on first call, persisted forever. */
    val installationId: String
        get() = prefs.getString(KEY_INSTALL_ID, null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_INSTALL_ID, it).apply()
            }

    /** Fetch the current FCM token (if any) and sync it to the backend. */
    fun registerIfPossible() {
        scope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                pushToBackend(token)
            } catch (t: Throwable) {
                Log.w(TAG, "registerIfPossible: failed to fetch FCM token: ${t.message}", t)
            }
        }
    }

    fun onTokenRotated(newToken: String) {
        scope.launch { pushToBackend(newToken) }
    }

    private suspend fun pushToBackend(token: String) {
        prefs.edit().putString(KEY_TOKEN_PREFIX, tokenPrefix(token)).apply()
        try {
            withContext(Dispatchers.IO) { api.registerForPush(installationId, token) }
            prefs.edit()
                .putLong(KEY_LAST_REG_AT, System.currentTimeMillis())
                .putInt(KEY_LAST_REG_OK, 1)
                .apply()
            Log.i(TAG, "push registration synced (installId=$installationId)")
        } catch (t: Throwable) {
            prefs.edit()
                .putLong(KEY_LAST_REG_AT, System.currentTimeMillis())
                .putInt(KEY_LAST_REG_OK, 0)
                .apply()
            Log.w(TAG, "push registration failed: ${t.message}", t)
        }
    }

    /** A safe, truncated view of the FCM token for diagnostics (never the full token). */
    private fun tokenPrefix(token: String): String =
        if (token.length <= 12) token else token.take(12) + "…"

    // ---- Diagnostics read-only accessors (used by the Settings panel) ----
    /** Truncated FCM token last synced, or null if push has never registered. */
    fun lastTokenPrefix(): String? = prefs.getString(KEY_TOKEN_PREFIX, null)

    /** Epoch millis of the last registration attempt (0 = never). */
    fun lastRegisteredAtMillis(): Long = prefs.getLong(KEY_LAST_REG_AT, 0L)

    /** Outcome of the last registration attempt: true=ok, false=failed, null=never tried. */
    fun lastRegisterOk(): Boolean? = when (prefs.getInt(KEY_LAST_REG_OK, -1)) {
        1 -> true
        0 -> false
        else -> null
    }

    private companion object {
        const val PREFS = "fcm_prefs"
        const val KEY_INSTALL_ID = "install_id"
        const val KEY_TOKEN_PREFIX = "token_prefix"
        const val KEY_LAST_REG_AT = "last_reg_at"
        const val KEY_LAST_REG_OK = "last_reg_ok"
        const val TAG = "PushRegistrar"
    }
}
