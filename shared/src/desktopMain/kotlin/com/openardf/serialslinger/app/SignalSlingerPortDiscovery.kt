package com.openardf.serialslinger.app

import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import com.openardf.serialslinger.transport.DesktopSerialPortInfo
import com.openardf.serialslinger.transport.DesktopSerialTransport
import com.openardf.serialslinger.transport.DeviceTransport

enum class PortProbeState {
    UNCHECKED,
    DETECTED,
    NOT_DETECTED,
    ERROR,
}

data class SignalSlingerPortProbe(
    val portInfo: DesktopSerialPortInfo,
    val state: PortProbeState,
    val summary: String,
    val evidenceLines: List<String> = emptyList(),
    val isPlaceholder: Boolean = false,
    val lastProbedAtMs: Long? = null,
) {
    val displayLabel: String
        get() = when {
            isPlaceholder -> summary
            state == PortProbeState.DETECTED -> "[SignalSlinger] ${portInfo.systemPortPath} - $summary"
            else -> "${portInfo.systemPortPath} - $summary"
        }

    override fun toString(): String = displayLabel
}

object SignalSlingerPortDiscovery {
    private val probeCommands = listOf("EVT", "FOX", "FRE")

    data class AutoDetectScanResult(
        val probes: List<SignalSlingerPortProbe>,
        val detected: SignalSlingerPortProbe?,
    )

    fun listAvailablePorts(): List<SignalSlingerPortProbe> {
        return DesktopSerialTransport.listAvailablePorts().map { portInfo ->
            SignalSlingerPortProbe(
                portInfo = portInfo,
                state = PortProbeState.UNCHECKED,
                summary = "Port available",
                lastProbedAtMs = null,
            )
        }
    }

    fun probePort(
        portInfo: DesktopSerialPortInfo,
        transportFactory: (DesktopSerialPortInfo) -> DeviceTransport = ::defaultTransportFor,
    ): SignalSlingerPortProbe {
        return try {
            val transport = transportFactory(portInfo)
            transport.connect()
            try {
                probeWithConnectedTransport(portInfo, transport)
            } finally {
                transport.disconnect()
            }
        } catch (exception: Exception) {
            SignalSlingerPortProbe(
                portInfo = portInfo,
                state = PortProbeState.ERROR,
                summary = exception.message ?: "Probe failed",
                lastProbedAtMs = System.currentTimeMillis(),
            )
        }
    }

    fun probeWithConnectedTransport(
        portInfo: DesktopSerialPortInfo,
        transport: DeviceTransport,
    ): SignalSlingerPortProbe {
        val lines = mutableListOf<String>()
        val startedAtMs = System.currentTimeMillis()

        for (command in probeCommands) {
            transport.sendCommands(listOf(command))
            lines += transport.readAvailableLines()
            if (classifyProbeLines(lines) == PortProbeState.DETECTED) {
                break
            }
        }

        val state = classifyProbeLines(lines)
        return SignalSlingerPortProbe(
            portInfo = portInfo,
            state = state,
            summary = summaryFor(state, lines),
            evidenceLines = lines,
            lastProbedAtMs = startedAtMs,
        )
    }

    fun autoDetectPorts(
        ports: List<DesktopSerialPortInfo> = DesktopSerialTransport.listAvailablePorts(),
        transportFactory: (DesktopSerialPortInfo) -> DeviceTransport = ::defaultTransportFor,
    ): List<SignalSlingerPortProbe> {
        return ports.map { portInfo -> probePort(portInfo, transportFactory) }
    }

    fun findFirstDetectedPort(
        ports: List<DesktopSerialPortInfo>,
        transportFactory: (DesktopSerialPortInfo) -> DeviceTransport = ::defaultTransportFor,
        onProbeComplete: ((SignalSlingerPortProbe) -> Unit)? = null,
    ): AutoDetectScanResult {
        val probes = mutableListOf<SignalSlingerPortProbe>()

        for (portInfo in ports) {
            val result = probePort(portInfo, transportFactory)
            probes += result
            onProbeComplete?.invoke(result)
            if (result.state == PortProbeState.DETECTED) {
                return AutoDetectScanResult(
                    probes = probes,
                    detected = result,
                )
            }
        }

        return AutoDetectScanResult(
            probes = probes,
            detected = null,
        )
    }

    fun selectBestCandidate(results: List<SignalSlingerPortProbe>): SignalSlingerPortProbe? {
        return results.firstOrNull { it.state == PortProbeState.DETECTED }
    }

    internal fun classifyProbeLines(lines: List<String>): PortProbeState {
        if (lines.any { SignalSlingerProtocolCodec.parseReportLine(it) != null }) {
            return PortProbeState.DETECTED
        }
        if (lines.any { it.contains("SignalSlinger", ignoreCase = true) }) {
            return PortProbeState.DETECTED
        }

        return PortProbeState.NOT_DETECTED
    }

    private fun summaryFor(state: PortProbeState, lines: List<String>): String {
        return when (state) {
            PortProbeState.DETECTED -> "SignalSlinger detected"
            PortProbeState.NOT_DETECTED -> if (lines.isEmpty()) "No recognizable reply" else "Non-SignalSlinger replies"
            PortProbeState.ERROR -> "Probe failed"
            PortProbeState.UNCHECKED -> "Not checked"
        }
    }

    private fun defaultTransportFor(portInfo: DesktopSerialPortInfo): DeviceTransport {
        return DesktopSerialTransport(
            portDescriptor = portInfo.systemPortPath,
            readTimeoutMs = 350,
            quietPeriodMs = 80,
        )
    }
}
