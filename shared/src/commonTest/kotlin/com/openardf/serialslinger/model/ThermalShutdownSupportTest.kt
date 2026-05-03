package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThermalShutdownSupportTest {
    @Test
    fun validatesAllowedCelsiusRange() {
        assertEquals(30, ThermalShutdownSupport.validateCelsius(30))
        assertEquals(55, ThermalShutdownSupport.validateCelsius(55))
        assertFailsWith<IllegalArgumentException> {
            ThermalShutdownSupport.validateCelsius(29)
        }
        assertFailsWith<IllegalArgumentException> {
            ThermalShutdownSupport.validateCelsius(56)
        }
    }

    @Test
    fun convertsFahrenheitThresholdsToNearestCelsius() {
        assertEquals(30, ThermalShutdownSupport.fahrenheitToCelsius(86))
        assertEquals(55, ThermalShutdownSupport.fahrenheitToCelsius(131))
    }

    @Test
    fun buildsThermalThresholdCommand() {
        assertEquals("TMP H 50", ThermalShutdownSupport.commandForCelsius(50))
    }
}
