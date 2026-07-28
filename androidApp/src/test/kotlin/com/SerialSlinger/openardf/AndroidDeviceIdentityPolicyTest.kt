@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDeviceIdentityPolicyTest {
    @Test
    fun unchangedFtdiPathStillRequiresSignalSlingerIdentityVerification() {
        assertEquals(
            true,
            androidTargetsShareSignalSlingerSerialLink(
                expected = AndroidConnectionTarget.Usb("/dev/bus/usb/001", baudRate = 9_600),
                observed = AndroidConnectionTarget.Usb("/dev/bus/usb/001", baudRate = 9_600),
                signalSlingerBaudRate = 9_600,
            ),
        )
        assertEquals(
            false,
            androidTargetsShareSignalSlingerSerialLink(
                expected = AndroidConnectionTarget.Usb("/dev/bus/usb/001", baudRate = 9_600),
                observed = AndroidConnectionTarget.Usb("/dev/bus/usb/002", baudRate = 9_600),
                signalSlingerBaudRate = 9_600,
            ),
        )
    }
}
