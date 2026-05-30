@file:Suppress("PackageName")

package com.SerialSlinger.openardf

val emulatorDirectSerialPaths: List<String> =
    listOf(
        "/dev/ttyS0",
        "/dev/ttyS1",
        "/dev/ttyS2",
        "/dev/ttyAMA0",
        "/dev/ttyAMA1",
        "/dev/ttyAMA2",
        "/dev/hvc0",
    )

fun emulatorDirectSerialTargets(): List<AndroidConnectionTarget.DirectSerial> =
    emulatorDirectSerialPaths.map(AndroidConnectionTarget::DirectSerial)

fun isEmulatorDirectSerialTarget(target: AndroidConnectionTarget?): Boolean =
    (target as? AndroidConnectionTarget.DirectSerial)?.path in emulatorDirectSerialPaths

sealed interface AndroidConnectionTarget {
    val label: String

    data class Usb(
        val deviceName: String,
        val baudRate: Int = 9_600,
    ) : AndroidConnectionTarget {
        override val label: String
            get() = if (baudRate == 9_600) deviceName else "$deviceName at $baudRate baud"
    }

    data class DirectSerial(
        val path: String,
    ) : AndroidConnectionTarget {
        override val label: String
            get() = "Emulator serial $path"
    }
}
