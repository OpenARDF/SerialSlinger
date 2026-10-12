package com.openardf.serialslinger.app

internal object DesktopStartupActions {
    fun run(
        checkForAppUpdates: () -> Unit,
        logAutomaticDeviceDetection: () -> Unit,
        findAttachedDevice: () -> Unit,
    ) {
        checkForAppUpdates()
        logAutomaticDeviceDetection()
        findAttachedDevice()
    }
}
