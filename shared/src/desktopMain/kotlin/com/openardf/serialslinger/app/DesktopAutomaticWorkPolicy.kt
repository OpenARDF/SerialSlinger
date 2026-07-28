package com.openardf.serialslinger.app

object DesktopAutomaticWorkPolicy {
    fun shouldDeferIdentityProbe(
        backgroundWorkInProgress: Boolean,
        identityReloadInProgress: Boolean,
        firmwarePromptVisible: Boolean,
        appMessageDialogVisible: Boolean,
        deviceDataSampleInProgress: Boolean,
    ): Boolean =
        backgroundWorkInProgress ||
            identityReloadInProgress ||
            firmwarePromptVisible ||
            appMessageDialogVisible ||
            deviceDataSampleInProgress

    fun shouldDeferFirmwareOffer(
        firmwareCheckInProgress: Boolean,
        firmwarePromptVisible: Boolean,
        backgroundWorkInProgress: Boolean,
        appMessageDialogVisible: Boolean,
    ): Boolean =
        firmwareCheckInProgress ||
            firmwarePromptVisible ||
            backgroundWorkInProgress ||
            appMessageDialogVisible

    fun shouldDeferDeviceDataSample(
        backgroundWorkInProgress: Boolean,
        appMessageDialogVisible: Boolean,
        identityProbeInProgress: Boolean,
    ): Boolean =
        backgroundWorkInProgress ||
            appMessageDialogVisible ||
            identityProbeInProgress
}
