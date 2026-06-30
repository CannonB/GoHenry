package com.gohenry.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tiny process-wide event bus so the always-running [GoHenryFcmService] (a
 * separate Android Service, with no ViewModel) can nudge a live [GoHenryViewModel]
 * the moment a push lands. When the app is in the foreground this lets the
 * carousel card and the capture history refresh immediately so what's on screen
 * stays in sync with the alert that just arrived; when it's backgrounded there's
 * simply no collector and the ON_RESUME refresh covers it instead.
 *
 * The payload is the affected VIN (or null if the push didn't carry one).
 */
object AppEvents {
    private val _pushReceived = MutableSharedFlow<String?>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pushReceived: SharedFlow<String?> = _pushReceived.asSharedFlow()

    /** Signals that an FCM alert for [vin] (nullable) was just received. */
    fun notifyPushReceived(vin: String?) {
        _pushReceived.tryEmit(vin)
    }
}
