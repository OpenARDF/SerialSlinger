package com.openardf.serialslinger.app

import com.openardf.serialslinger.transport.DesktopSerialPortInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAutoDetectPolicyTest {
    @Test
    fun defaultsToLastWorkingPortWhenCurrentSelectionIsUnavailable() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")
        val portB = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")

        assertEquals(
            "/dev/tty.usbB",
            DesktopAutoDetectPolicy.defaultSelectionPath(
                availablePorts = listOf(portA, portB),
                currentSelectionPath = "/dev/tty.usbZ",
                lastWorkingPortPath = "/dev/tty.usbB",
            ),
        )
    }

    @Test
    fun keepsCurrentSelectionWhenItIsStillAvailable() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")
        val portB = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")

        assertEquals(
            "/dev/tty.usbA",
            DesktopAutoDetectPolicy.defaultSelectionPath(
                availablePorts = listOf(portA, portB),
                currentSelectionPath = "/dev/tty.usbA",
                lastWorkingPortPath = "/dev/tty.usbB",
            ),
        )
    }

    @Test
    fun detectionOrderStartsWithLastWorkingPortWhenNotConnected() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")
        val portB = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")
        val portC = DesktopSerialPortInfo("tty.usbC", "/dev/tty.usbC", "C", "C")

        assertEquals(
            listOf(portB, portA, portC).map { it.systemPortPath },
            DesktopAutoDetectPolicy.detectionOrder(
                availablePorts = listOf(portA, portB, portC),
                lastWorkingPortPath = "/dev/tty.usbB",
                connectedPortPath = null,
            ).map { it.systemPortPath },
        )
    }

    @Test
    fun detectionOrderSkipsConnectedPortAndSearchesTheRest() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")
        val portB = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")
        val portC = DesktopSerialPortInfo("tty.usbC", "/dev/tty.usbC", "C", "C")

        assertEquals(
            listOf(portC, portA).map { it.systemPortPath },
            DesktopAutoDetectPolicy.detectionOrder(
                availablePorts = listOf(portA, portB, portC),
                lastWorkingPortPath = "/dev/tty.usbC",
                connectedPortPath = "/dev/tty.usbB",
            ).map { it.systemPortPath },
        )
    }

    @Test
    fun detectionOrderPrefersCuAliasWhenBothAliasesExist() {
        val ttyPort = DesktopSerialPortInfo("tty.usbserial-ABSCDL93", "/dev/tty.usbserial-ABSCDL93", "A", "A")
        val cuPort = DesktopSerialPortInfo("cu.usbserial-ABSCDL93", "/dev/cu.usbserial-ABSCDL93", "A", "A")
        val otherPort = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")

        assertEquals(
            listOf("/dev/cu.usbserial-ABSCDL93", "/dev/tty.usbserial-ABSCDL93", "/dev/tty.usbB"),
            DesktopAutoDetectPolicy.detectionOrder(
                availablePorts = listOf(ttyPort, cuPort, otherPort),
                lastWorkingPortPath = "/dev/tty.usbserial-ABSCDL93",
                connectedPortPath = null,
            ).map { it.systemPortPath },
        )
    }

    @Test
    fun detectionOrderSkipsBothAliasesOfConnectedPort() {
        val connectedTty = DesktopSerialPortInfo("tty.usbserial-ABSCDL93", "/dev/tty.usbserial-ABSCDL93", "A", "A")
        val connectedCu = DesktopSerialPortInfo("cu.usbserial-ABSCDL93", "/dev/cu.usbserial-ABSCDL93", "A", "A")
        val otherPort = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")

        assertEquals(
            listOf("/dev/tty.usbB"),
            DesktopAutoDetectPolicy.detectionOrder(
                availablePorts = listOf(connectedTty, connectedCu, otherPort),
                lastWorkingPortPath = null,
                connectedPortPath = "/dev/cu.usbserial-ABSCDL93",
            ).map { it.systemPortPath },
        )
    }

    @Test
    fun defaultSelectionKeepsLinuxAndWindowsPathsStable() {
        val linuxPort = DesktopSerialPortInfo("ttyUSB0", "/dev/ttyUSB0", "USB", "USB")
        val windowsPort = DesktopSerialPortInfo("COM3", "COM3", "COM3", "COM3")

        assertEquals(
            "/dev/ttyUSB0",
            DesktopAutoDetectPolicy.defaultSelectionPath(
                availablePorts = listOf(linuxPort, windowsPort),
                currentSelectionPath = "/dev/ttyUSB0",
                lastWorkingPortPath = "COM3",
            ),
        )
        assertEquals(
            "COM3",
            DesktopAutoDetectPolicy.defaultSelectionPath(
                availablePorts = listOf(linuxPort, windowsPort),
                currentSelectionPath = null,
                lastWorkingPortPath = "COM3",
            ),
        )
    }
}
