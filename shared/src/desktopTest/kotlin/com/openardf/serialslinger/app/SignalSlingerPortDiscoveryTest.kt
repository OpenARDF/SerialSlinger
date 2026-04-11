package com.openardf.serialslinger.app

import com.openardf.serialslinger.transport.DesktopSerialPortInfo
import com.openardf.serialslinger.transport.DeviceTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalSlingerPortDiscoveryTest {
    @Test
    fun classifiesRecognizedProtocolRepliesAsDetected() {
        val state = SignalSlingerPortDiscovery.classifyProbeLines(
            listOf("* Event:Foxoring", """* Fox:Foxoring "Low Freq" Fox"""),
        )

        assertEquals(PortProbeState.DETECTED, state)
    }

    @Test
    fun choosesFirstDetectedCandidate() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")
        val portB = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")
        val best = SignalSlingerPortDiscovery.selectBestCandidate(
            listOf(
                SignalSlingerPortProbe(portA, PortProbeState.NOT_DETECTED, "No recognizable reply"),
                SignalSlingerPortProbe(portB, PortProbeState.DETECTED, "SignalSlinger detected"),
            ),
        )

        assertEquals("/dev/tty.usbB", best?.portInfo?.systemPortPath)
    }

    @Test
    fun displayLabelProminentlyMarksDetectedSignalSlingers() {
        val port = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")

        val detected = SignalSlingerPortProbe(
            portInfo = port,
            state = PortProbeState.DETECTED,
            summary = "SignalSlinger detected",
        )

        assertEquals("[SignalSlinger] /dev/tty.usbB - SignalSlinger detected", detected.displayLabel)
    }

    @Test
    fun stopsProbingAfterTheFirstDetectedPort() {
        val portA = DesktopSerialPortInfo("tty.usbA", "/dev/tty.usbA", "A", "A")
        val portB = DesktopSerialPortInfo("tty.usbB", "/dev/tty.usbB", "B", "B")
        val portC = DesktopSerialPortInfo("tty.usbC", "/dev/tty.usbC", "C", "C")
        val probedPorts = mutableListOf<String>()

        val scanResult = SignalSlingerPortDiscovery.findFirstDetectedPort(
            ports = listOf(portA, portB, portC),
            transportFactory = { portInfo ->
                probedPorts += portInfo.systemPortPath
                FakeProbeTransport(
                    linesByCommand = when (portInfo.systemPortPath) {
                        "/dev/tty.usbB" -> mapOf("EVT" to listOf("* Event:Foxoring"))
                        else -> emptyMap()
                    },
                )
            },
        )

        assertEquals(listOf("/dev/tty.usbA", "/dev/tty.usbB"), probedPorts)
        assertEquals("/dev/tty.usbB", scanResult.detected?.portInfo?.systemPortPath)
        assertTrue(scanResult.probes.last().state == PortProbeState.DETECTED)
    }

    private class FakeProbeTransport(
        private val linesByCommand: Map<String, List<String>>,
    ) : DeviceTransport {
        private var queuedLines: List<String> = emptyList()

        override fun connect() {
        }

        override fun disconnect() {
        }

        override fun sendCommands(commands: List<String>) {
            val command = commands.single()
            queuedLines = linesByCommand[command].orEmpty()
        }

        override fun readAvailableLines(): List<String> {
            val lines = queuedLines
            queuedLines = emptyList()
            return lines
        }
    }
}
