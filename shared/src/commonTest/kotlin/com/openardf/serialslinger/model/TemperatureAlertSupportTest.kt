package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TemperatureAlertSupportTest {
    @Test
    fun appliesTemperatureAlertThresholdsAtRequestedBoundaries() {
        assertEquals(TemperatureAlertLevel.NORMAL, TemperatureAlertSupport.alertLevel(null))
        assertEquals(TemperatureAlertLevel.NORMAL, TemperatureAlertSupport.alertLevel(40.0))
        assertEquals(TemperatureAlertLevel.WARNING, TemperatureAlertSupport.alertLevel(40.1))
        assertEquals(TemperatureAlertLevel.WARNING, TemperatureAlertSupport.alertLevel(50.0))
        assertEquals(TemperatureAlertLevel.DANGER, TemperatureAlertSupport.alertLevel(50.1))
    }
}
