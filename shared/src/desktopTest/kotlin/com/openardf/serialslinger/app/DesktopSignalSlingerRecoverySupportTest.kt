package com.openardf.serialslinger.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSignalSlingerRecoverySupportTest {
    @Test
    fun doesNotGuessWhenApplicationCannotReportHardware() {
        assertNull(DesktopSignalSlingerRecoverySupport.preferredBoard(null))
    }

    @Test
    fun usesReportedHardwareWhenItIsAvailable() {
        assertEquals("HW-3.5", DesktopSignalSlingerRecoverySupport.preferredBoard("3.5"))
    }

    @Test
    fun rejectsRecoveryPackageForDifferentPhysicalBoard() {
        assertTrue(DesktopSignalSlingerRecoverySupport.packageMatchesBoard("HW-3.4", "HW-3.4"))
        assertFalse(DesktopSignalSlingerRecoverySupport.packageMatchesBoard("HW-3.4", "HW-3.5"))
    }
}
