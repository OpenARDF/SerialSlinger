@file:Suppress("PackageName")

package com.SerialSlinger.openardf

internal fun androidTargetsShareSignalSlingerSerialLink(
    expected: AndroidConnectionTarget,
    observed: AndroidConnectionTarget,
    signalSlingerBaudRate: Int,
): Boolean {
    return when {
        expected is AndroidConnectionTarget.Usb && observed is AndroidConnectionTarget.Usb ->
            expected.deviceName == observed.deviceName &&
                observed.baudRate == signalSlingerBaudRate
        expected is AndroidConnectionTarget.DirectSerial && observed is AndroidConnectionTarget.DirectSerial ->
            expected.path == observed.path
        else -> false
    }
}
