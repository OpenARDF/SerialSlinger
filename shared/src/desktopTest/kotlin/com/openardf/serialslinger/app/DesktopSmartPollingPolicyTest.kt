package com.openardf.serialslinger.app

import com.openardf.serialslinger.transport.DesktopSerialPortInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSmartPollingPolicyTest {
    @Test
    fun canonicalizePortsPrefersCuAliasOverTtyAlias() {
        val cuPort = DesktopSerialPortInfo("cu.usbserial-ABSCDL93", "/dev/cu.usbserial-ABSCDL93", "A", "A")
        val ttyPort = DesktopSerialPortInfo("tty.usbserial-ABSCDL93", "/dev/tty.usbserial-ABSCDL93", "A", "A")

        val canonical = DesktopSmartPollingPolicy.canonicalizePorts(listOf(ttyPort, cuPort))

        assertEquals(listOf("/dev/cu.usbserial-ABSCDL93"), canonical.map { it.systemPortPath })
    }

    @Test
    fun preferredPortPathSwitchesTtyAliasToCuAliasWhenAvailable() {
        val cuPort = DesktopSerialPortInfo("cu.usbserial-ABSCDL93", "/dev/cu.usbserial-ABSCDL93", "A", "A")
        val ttyPort = DesktopSerialPortInfo("tty.usbserial-ABSCDL93", "/dev/tty.usbserial-ABSCDL93", "A", "A")

        val preferred = DesktopSmartPollingPolicy.preferredPortPath(
            availablePorts = listOf(ttyPort, cuPort),
            requestedPath = "/dev/tty.usbserial-ABSCDL93",
        )

        assertEquals("/dev/cu.usbserial-ABSCDL93", preferred)
    }

    @Test
    fun canonicalizePortsLeavesLinuxAndWindowsPortsUnchanged() {
        val linuxPort = DesktopSerialPortInfo("ttyUSB0", "/dev/ttyUSB0", "USB", "USB")
        val windowsPort = DesktopSerialPortInfo("COM3", "COM3", "COM3", "COM3")

        val canonical = DesktopSmartPollingPolicy.canonicalizePorts(listOf(linuxPort, windowsPort))

        assertEquals(listOf("/dev/ttyUSB0", "COM3"), canonical.map { it.systemPortPath })
    }

    @Test
    fun preferredPortPathDoesNotRewriteLinuxOrWindowsPorts() {
        val linuxPort = DesktopSerialPortInfo("ttyUSB0", "/dev/ttyUSB0", "USB", "USB")
        val windowsPort = DesktopSerialPortInfo("COM3", "COM3", "COM3", "COM3")

        assertEquals(
            "/dev/ttyUSB0",
            DesktopSmartPollingPolicy.preferredPortPath(listOf(linuxPort, windowsPort), "/dev/ttyUSB0"),
        )
        assertEquals(
            "COM3",
            DesktopSmartPollingPolicy.preferredPortPath(listOf(linuxPort, windowsPort), "COM3"),
        )
    }

    @Test
    fun probesNewPortsBeforeRecheckingCachedPorts() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")
        val portB = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")

        val candidate = DesktopSmartPollingPolicy.nextProbeCandidate(
            availablePorts = listOf(portA, portB),
            knownProbes = mapOf(
                "/dev/tty.usbA" to SignalSlingerPortProbe(
                    portInfo = portA,
                    state = PortProbeState.NOT_DETECTED,
                    summary = "No recognizable reply",
                    lastProbedAtMs = 10_000L,
                ),
            ),
            connectedPortPath = null,
            nowMs = 20_000L,
        )

        assertEquals("/dev/tty.usbB", candidate?.systemPortPath)
    }

    @Test
    fun rechecksDetectedPortAfterItsIntervalExpires() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")
        val portB = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")

        val candidate = DesktopSmartPollingPolicy.nextProbeCandidate(
            availablePorts = listOf(portA, portB),
            knownProbes = mapOf(
                "/dev/tty.usbA" to SignalSlingerPortProbe(
                    portInfo = portA,
                    state = PortProbeState.DETECTED,
                    summary = "SignalSlinger detected",
                    lastProbedAtMs = 0L,
                ),
                "/dev/tty.usbB" to SignalSlingerPortProbe(
                    portInfo = portB,
                    state = PortProbeState.NOT_DETECTED,
                    summary = "No recognizable reply",
                    lastProbedAtMs = 30_000L,
                ),
            ),
            connectedPortPath = null,
            nowMs = 21_000L,
        )

        assertEquals("/dev/tty.usbA", candidate?.systemPortPath)
    }

    @Test
    fun waitsUntilAProbeIsDue() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")

        val candidate = DesktopSmartPollingPolicy.nextProbeCandidate(
            availablePorts = listOf(portA),
            knownProbes = mapOf(
                "/dev/tty.usbA" to SignalSlingerPortProbe(
                    portInfo = portA,
                    state = PortProbeState.NOT_DETECTED,
                    summary = "No recognizable reply",
                    lastProbedAtMs = 10_000L,
                ),
            ),
            connectedPortPath = null,
            nowMs = 999_999L,
        )

        assertNull(candidate)
    }

    @Test
    fun aliasGroupKeyTreatsCuAndTtyAsTheSameAdapter() {
        assertEquals(
            "usbserial-ABSCDL93",
            DesktopSmartPollingPolicy.aliasGroupKey("/dev/cu.usbserial-ABSCDL93"),
        )
        assertEquals(
            "usbserial-ABSCDL93",
            DesktopSmartPollingPolicy.aliasGroupKey("/dev/tty.usbserial-ABSCDL93"),
        )
    }

    @Test
    fun aliasGroupKeyIgnoresNonMacosPortNames() {
        assertNull(DesktopSmartPollingPolicy.aliasGroupKey("/dev/ttyUSB0"))
        assertNull(DesktopSmartPollingPolicy.aliasGroupKey("COM3"))
    }
}
