package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FirmwareUpdateTimeSyncPolicyTest {
    @Test
    fun syncsAfterFirmwareUpdateInAutomaticAndSemiAutomaticModes() {
        val snapshot = DeviceSnapshot(
            capabilities = DeviceCapabilities(supportsScheduling = true),
        )

        assertEquals(
            FirmwareUpdateTimeSyncDecision.SYNC,
            FirmwareUpdateTimeSyncPolicy.decide(manualTimeUpdatingEnabled = false, snapshot),
        )
    }

    @Test
    fun manualModeIsTheUserControlledException() {
        val snapshot = DeviceSnapshot(
            capabilities = DeviceCapabilities(supportsScheduling = true),
        )

        assertEquals(
            FirmwareUpdateTimeSyncDecision.SKIP_MANUAL_MODE,
            FirmwareUpdateTimeSyncPolicy.decide(manualTimeUpdatingEnabled = true, snapshot),
        )
    }

    @Test
    fun unsupportedFirmwareDoesNotReceiveAClockCommand() {
        assertEquals(
            FirmwareUpdateTimeSyncDecision.SKIP_UNSUPPORTED,
            FirmwareUpdateTimeSyncPolicy.decide(
                manualTimeUpdatingEnabled = false,
                snapshot = DeviceSnapshot(),
            ),
        )
    }
}
