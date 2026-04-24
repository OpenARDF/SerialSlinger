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
    ) : AndroidConnectionTarget {
        override val label: String
            get() = deviceName
    }

    data class DirectSerial(
        val path: String,
    ) : AndroidConnectionTarget {
        override val label: String
            get() = "Emulator serial $path"
    }
}
