package com.openardf.serialslinger.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopStartupActionsTest {
    @Test
    fun startupChecksForUpdatesThenAutomaticallySearchesForAnAttachedDevice() {
        val actions = mutableListOf<String>()

        DesktopStartupActions.run(
            checkForAppUpdates = { actions += "check for updates" },
            logAutomaticDeviceDetection = { actions += "log automatic device detection" },
            findAttachedDevice = { actions += "find attached device" },
        )

        assertEquals(
            listOf(
                "check for updates",
                "log automatic device detection",
                "find attached device",
            ),
            actions,
        )
    }
}
