package com.openardf.serialslinger.model

enum class FirmwareUpdateTimeSyncDecision {
    SYNC,
    SKIP_MANUAL_MODE,
    SKIP_UNSUPPORTED,
}

object FirmwareUpdateTimeSyncPolicy {
    /** Firmware updates reset the clock; only an explicit manual-time preference suppresses repair. */
    fun decide(
        manualTimeUpdatingEnabled: Boolean,
        snapshot: DeviceSnapshot,
    ): FirmwareUpdateTimeSyncDecision =
        when {
            manualTimeUpdatingEnabled -> FirmwareUpdateTimeSyncDecision.SKIP_MANUAL_MODE
            !snapshot.capabilities.supportsScheduling -> FirmwareUpdateTimeSyncDecision.SKIP_UNSUPPORTED
            else -> FirmwareUpdateTimeSyncDecision.SYNC
        }
}
