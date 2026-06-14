package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.ConnectionState
import com.openardf.serialslinger.session.DeviceLoadResult
import com.openardf.serialslinger.session.DeviceSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLoadValidationSupportTest {
    @Test
    fun rejectsEmptyLoad() {
        val result = loadResult(emptyList())

        assertEquals(
            "No response from device on `/dev/cu.test` while loading settings.",
            DesktopLoadValidationSupport.validate("/dev/cu.test", result),
        )
    }

    @Test
    fun rejectsIdentityOnlyArduconLoad() {
        val result = loadResult(
            listOf(
                "* INF product=Arducon",
                "* INF sw=2.0.6",
                "* INF appbaud=57600",
            ),
        )

        assertEquals(
            "No settings readback from device on `/dev/cu.test` while loading settings.",
            DesktopLoadValidationSupport.validate("/dev/cu.test", result),
        )
    }

    @Test
    fun acceptsLoadWithSettingsReadback() {
        val result = loadResult(
            listOf(
                "* INF product=Arducon",
                "ID: NZ0I",
            ),
        )

        assertNull(DesktopLoadValidationSupport.validate("/dev/cu.test", result))
        assertEquals(1, DesktopLoadValidationSupport.settingsOrStatusResponseCount(result))
    }

    private fun loadResult(lines: List<String>): DeviceLoadResult {
        return DeviceLoadResult(
            state = DeviceSessionState(connectionState = ConnectionState.CONNECTED),
            commandsSent = emptyList(),
            linesReceived = lines,
            traceEntries = emptyList(),
        )
    }
}
