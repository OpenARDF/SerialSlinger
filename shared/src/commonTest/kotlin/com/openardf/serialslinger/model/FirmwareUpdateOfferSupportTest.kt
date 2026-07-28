package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertNotEquals

class FirmwareUpdateOfferSupportTest {
    @Test
    fun keyChangesForUidPresenceDeviceUidAndFirmwareVersion() {
        val legacy =
            DeviceSnapshot(
                info =
                    DeviceInfo(
                        productName = "SignalSlinger",
                        identityReportReceived = true,
                        softwareVersion = "2.0.2",
                        hardwareBuild = "3.5",
                    ),
            )
        val updatedLegacy =
            legacy.copy(
                info =
                    legacy.info.copy(
                        softwareVersion = "2.0.3",
                    ),
            )
        val uidA =
            updatedLegacy.copy(
                info =
                    updatedLegacy.info.copy(
                        deviceUniqueId = "314A323536384E171D00321700000000",
                    ),
            )
        val uidB =
            uidA.copy(
                info =
                    uidA.info.copy(
                        deviceUniqueId = "314A323536384E171D00321700000001",
                    ),
            )

        assertNotEquals(
            FirmwareUpdateOfferSupport.snapshotKey(legacy),
            FirmwareUpdateOfferSupport.snapshotKey(updatedLegacy),
        )
        assertNotEquals(
            FirmwareUpdateOfferSupport.snapshotKey(updatedLegacy),
            FirmwareUpdateOfferSupport.snapshotKey(uidA),
        )
        assertNotEquals(
            FirmwareUpdateOfferSupport.snapshotKey(uidA),
            FirmwareUpdateOfferSupport.snapshotKey(uidB),
        )
    }
}
