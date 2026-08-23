package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThermalShutdownSupportTest {
    @Test
    fun validatesAllowedCelsiusRange() {
        assertEquals(30, ThermalShutdownSupport.validateCelsius(30))
        assertEquals(85, ThermalShutdownSupport.validateCelsius(85))
        assertEquals(60, ThermalShutdownSupport.validateCelsius(60, productName = "Arducon"))
        assertFailsWith<IllegalArgumentException> {
            ThermalShutdownSupport.validateCelsius(29)
        }
        assertFailsWith<IllegalArgumentException> {
            ThermalShutdownSupport.validateCelsius(86)
        }
        assertFailsWith<IllegalArgumentException> {
            ThermalShutdownSupport.validateCelsius(61, productName = "Arducon")
        }
    }

    @Test
    fun convertsFahrenheitThresholdsToNearestCelsius() {
        assertEquals(30, ThermalShutdownSupport.fahrenheitToCelsius(86))
        assertEquals(85, ThermalShutdownSupport.fahrenheitToCelsius(185))
    }

    @Test
    fun buildsThermalThresholdCommand() {
        assertEquals("TMP H 50", ThermalShutdownSupport.commandForCelsius(50))
        assertEquals("UTI H 50", ThermalShutdownSupport.commandForCelsius(50, productName = "Arducon"))
        assertEquals("TMP H ON", ThermalShutdownSupport.commandForEnabled(true))
        assertEquals("TMP H OFF", ThermalShutdownSupport.commandForEnabled(false))
        assertFailsWith<IllegalArgumentException> {
            ThermalShutdownSupport.commandForEnabled(true, productName = "Arducon")
        }
    }
}
