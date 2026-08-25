package com.openardf.serialslinger.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAutomaticWorkPolicyTest {
    @Test
    fun messageDialogDefersAllAutomaticSerialWork() {
        assertTrue(
            DesktopAutomaticWorkPolicy.shouldDeferIdentityProbe(
                backgroundWorkInProgress = false,
                identityReloadInProgress = false,
                firmwarePromptVisible = false,
                appMessageDialogVisible = true,
                deviceDataSampleInProgress = false,
            ),
        )
        assertTrue(
            DesktopAutomaticWorkPolicy.shouldDeferFirmwareOffer(
                firmwareCheckInProgress = false,
                firmwarePromptVisible = false,
                backgroundWorkInProgress = false,
                appMessageDialogVisible = true,
            ),
        )
        assertTrue(
            DesktopAutomaticWorkPolicy.shouldDeferDeviceDataSample(
                backgroundWorkInProgress = false,
                appMessageDialogVisible = true,
                identityProbeInProgress = false,
            ),
        )
    }

    @Test
    fun idleDesktopAllowsAutomaticSerialWork() {
        assertFalse(
            DesktopAutomaticWorkPolicy.shouldDeferIdentityProbe(
                backgroundWorkInProgress = false,
                identityReloadInProgress = false,
                firmwarePromptVisible = false,
                appMessageDialogVisible = false,
                deviceDataSampleInProgress = false,
            ),
        )
        assertFalse(
            DesktopAutomaticWorkPolicy.shouldDeferFirmwareOffer(
                firmwareCheckInProgress = false,
                firmwarePromptVisible = false,
                backgroundWorkInProgress = false,
                appMessageDialogVisible = false,
            ),
        )
        assertFalse(
            DesktopAutomaticWorkPolicy.shouldDeferDeviceDataSample(
                backgroundWorkInProgress = false,
                appMessageDialogVisible = false,
                identityProbeInProgress = false,
            ),
        )
    }

    @Test
    fun automaticTimeSyncRunsOnlyUntilAConnectionAttemptFails() {
        assertTrue(
            DesktopAutomaticWorkPolicy.shouldScheduleAutomaticTimeSync(
                automaticMode = true,
                transportAvailable = true,
                connected = true,
                schedulingSupported = true,
                syncNeeded = true,
                failureSuppressed = false,
            ),
        )
        assertFalse(
            DesktopAutomaticWorkPolicy.shouldScheduleAutomaticTimeSync(
                automaticMode = true,
                transportAvailable = true,
                connected = true,
                schedulingSupported = true,
                syncNeeded = true,
                failureSuppressed = true,
            ),
        )
    }
}
