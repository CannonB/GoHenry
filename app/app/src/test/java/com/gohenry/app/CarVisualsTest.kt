package com.gohenry.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarVisualsTest {
    @Test fun mapsEverySupportedEngineType() {
        assertEquals(EngineVisualKind.BatteryElectric, engineVisualKind("BEV"))
        assertEquals(EngineVisualKind.PlugInHybrid, engineVisualKind("PHEV"))
        assertEquals(EngineVisualKind.Hybrid, engineVisualKind("HEV"))
        assertEquals(EngineVisualKind.Gas, engineVisualKind("GAS"))
        assertTrue(usesBatteryGauge("BEV"))
        assertTrue(usesBatteryGauge("PHEV"))
        assertFalse(usesBatteryGauge("HEV"))
        assertFalse(usesBatteryGauge("GAS"))
    }

    @Test fun mapsKnownModelsToDistinctThemes() {
        assertEquals(CardMotif.None, vehicleVisualTheme("Mustang Mach-E", "BEV", 0).motif)
        assertEquals(CardMotif.None, vehicleVisualTheme("Escape", "PHEV", 1).motif)
        assertEquals(CardMotif.None, vehicleVisualTheme("F-150", "GAS", 2).motif)
    }
}
