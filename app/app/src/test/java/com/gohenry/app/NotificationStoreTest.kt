package com.gohenry.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationStoreTest {
    private lateinit var context: Context
    private lateinit var store: NotificationStore

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("notif_history", Context.MODE_PRIVATE).edit().clear().commit()
        store = NotificationStore(context)
    }

    @Test fun prunesEntriesOutsideRetentionWindow() {
        val vin = "VIN1"
        store.setTrackingEnabled(vin, true)
        val now = System.currentTimeMillis()
        store.record(notification(vin, now - 4L * 24 * 60 * 60 * 1000, "old"))
        store.record(notification(vin, now - 1L * 24 * 60 * 60 * 1000, "recent"))
        store.setTrackingDays(3)
        val rows = store.forVin(vin)
        assertEquals(1, rows.size)
        assertEquals("recent", rows.single().event)
    }

    @Test fun togglingVinClearsOnlyThatVinHistory() {
        store.setTrackingEnabled("VIN1", true)
        store.setTrackingEnabled("VIN2", true)
        store.record(notification("VIN1", System.currentTimeMillis(), "one"))
        store.record(notification("VIN2", System.currentTimeMillis(), "two"))
        assertEquals(1, store.forVin("VIN1").size)

        store.setTrackingEnabled("VIN1", true)
        assertTrue(store.isTrackingEnabled("VIN1"))
        assertEquals(0, store.forVin("VIN1").size)
        assertEquals(1, store.forVin("VIN2").size)

        store.setTrackingEnabled("VIN2", false)
        assertFalse(store.isTrackingEnabled("VIN2"))
        assertEquals(0, store.forVin("VIN2").size)
    }

    private fun notification(vin: String, at: Long, event: String) = StoredNotification(
        vin = vin,
        event = event,
        title = "Title $event",
        body = "Body $event",
        timestampUtc = null,
        receivedAtMillis = at,
        data = mapOf("vin" to vin, "event" to event),
    )
}
