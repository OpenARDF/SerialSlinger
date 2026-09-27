package com.openardf.serialslinger.model

object FirmwareUpdateOfferSupport {
    fun snapshotKey(
        snapshot: DeviceSnapshot,
        residentFirmwareVersion: String? = null,
    ): String =
        listOf(
            snapshot.info.productName.orEmpty().trim(),
            snapshot.info.identityReportReceived.toString(),
            snapshot.info.deviceUniqueId ?: "legacy",
            snapshot.info.hardwareBuild.orEmpty().trim(),
            snapshot.info.softwareVersion.orEmpty().trim(),
            // Recheck an unchanged connected device when a newer package is imported locally.
            residentFirmwareVersion.orEmpty().trim(),
        ).joinToString("|")
}
