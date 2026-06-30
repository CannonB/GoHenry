package com.gohenry.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiJsonMappingTest {
    @Test fun parsesVehicleTelemetryPrefsAndFordAccounts() {
        val vehicles = GoHenryApi.parseVehicles("""
            [{"vin":"VIN1","model":"Mustang Mach-E","nickname":"Henry","modelYear":2024,"displayColor":"Blue","engineType":"BEV"}]
        """.trimIndent())
        assertEquals("VIN1", vehicles.single().vin)
        assertEquals("Henry", vehicles.single().title)

        val telemetry = GoHenryApi.parseTelemetry("""
            {"vin":"VIN1","socPct":88.5,"rangeValue":220,"rangeUnit":"mi","odometerValue":1200.4,"odometerUnit":"mi","chargingStatus":"Charging","pluggedIn":true,"latitude":47.1,"longitude":-122.2,"ignition":"ON","gearLever":"PARK","oilLifePct":73,"outsideTempValue":71,"outsideTempUnit":"F","alarmStatus":"Armed","doorLocks":"Locked","tirePressureStatus":"OK","fuelLevelValue":55,"fuelLevelUnit":"%","interiorTempValue":70,"interiorTempUnit":"F","lastPolledAt":"2026-06-24T12:00:00Z","lastWasActive":true,"lastHasOpenActivity":false,"lastTripLostSignal":false}
        """.trimIndent(), "fallback")
        assertEquals("VIN1", telemetry.vin)
        assertEquals(88.5, telemetry.socPct!!, 0.001)
        assertTrue(telemetry.pluggedIn == true)
        assertEquals("Locked", telemetry.doorLocks)

        val prefs = GoHenryApi.parseNotifyPrefs("""{"start":true,"stop":false,"chargeInProgress":true,"chargeComplete":true,"chargeError":false,"lostSignal":true}""")
        assertTrue(prefs.start)
        assertFalse(prefs.stop)
        assertTrue(prefs.chargeInProgress)
        assertTrue(prefs.lostSignal)

        val accounts = GoHenryApi.parseFordAccountStatuses("""
            {"accounts":[{"appSlug":"gohenry","isPrimary":false,"isLinked":true,"status":"NEEDS_REAUTH","needsReauth":true,"lastRefreshAt":"2026-06-20T00:00:00Z","daysUntilReauth":-1}]}
        """.trimIndent())
        assertEquals("gohenry", accounts.single().appSlug)
        assertTrue(accounts.single().needsReauth)
        assertEquals(-1, accounts.single().daysUntilReauth)
    }

    @Test fun missingOptionalTelemetryFieldsRemainNull() {
        val telemetry = GoHenryApi.parseTelemetry("{}", "VIN2")
        assertEquals("VIN2", telemetry.vin)
        assertNull(telemetry.socPct)
        assertNull(telemetry.pluggedIn)
    }
}
