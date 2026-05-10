package com.openardf.serialslinger.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemperatureResetSupportTest {
    @Test
    fun detectsSupportedProbeResponse() {
        assertTrue(TemperatureResetSupport.isSupportedProbeResponse(listOf("TMP R")))
    }

    @Test
    fun detectsSupportedProbeResponseWithDevicePrefix() {
        assertTrue(TemperatureResetSupport.isSupportedProbeResponse(listOf("* TMP R")))
    }

    @Test
    fun rejectsUnsupportedProbeResponse() {
        assertFalse(
            TemperatureResetSupport.isSupportedProbeResponse(
                listOf(
                    "* Err: valid commands are VER, ID, EVT, PAT, TMP",
                ),
            ),
        )
    }
}
