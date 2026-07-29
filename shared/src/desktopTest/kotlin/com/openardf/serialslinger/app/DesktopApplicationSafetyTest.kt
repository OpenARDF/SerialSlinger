package com.openardf.serialslinger.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopApplicationSafetyTest {
    @Test
    fun allowsExitWhenNoProtectedOperationIsRunning() {
        val decision = DesktopExitProtection.decision(null)

        assertTrue(decision.mayExit)
        assertNull(decision.message)
    }

    @Test
    fun blocksExitWhileFirmwareUpdateIsRunning() {
        val decision = DesktopExitProtection.decision("Updating SignalSlinger firmware")

        assertFalse(decision.mayExit)
        assertTrue(decision.message.orEmpty().contains("Updating SignalSlinger firmware is still in progress."))
        assertTrue(decision.message.orEmpty().contains("must remain open"))
    }
}
