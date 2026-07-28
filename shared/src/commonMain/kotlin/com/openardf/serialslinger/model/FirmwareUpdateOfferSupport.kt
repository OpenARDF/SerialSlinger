package com.openardf.serialslinger.model

object FirmwareUpdateOfferSupport {
    fun snapshotKey(snapshot: DeviceSnapshot): String =
        listOf(
            snapshot.info.productName.orEmpty().trim(),
            snapshot.info.identityReportReceived.toString(),
            snapshot.info.deviceUniqueId ?: "legacy",
            snapshot.info.hardwareBuild.orEmpty().trim(),
            snapshot.info.softwareVersion.orEmpty().trim(),
        ).joinToString("|")
}
