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
    val productName: String? = null,
    val appBaud: Int? = null,
    val isPlaceholder: Boolean = false,
    val lastProbedAtMs: Long? = null,
) {
    val displayLabel: String
        get() = when {
            isPlaceholder -> summary
            state == PortProbeState.DETECTED -> "[${detectedProductLabel()}] ${portInfo.systemPortPath} - $summary"
            else -> "${portInfo.systemPortPath} - $summary"
        }

    override fun toString(): String = displayLabel

    fun detectedProductLabel(): String = productName?.takeIf { it.isNotBlank() } ?: "SignalSlinger"
}

object SignalSlingerPortDiscovery {
    private val probeCommands = listOf("EVT", "FOX", "FRE")
    private val arduconProbeCommands = listOf("INF")
    private const val probeRetryCount = 3
    private const val probeRetryDelayMs = 200L

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
        retryOnNoReply: Boolean = false,
        transportFactory: (DesktopSerialPortInfo) -> DeviceTransport = ::defaultTransportFor,
    ): SignalSlingerPortProbe {
        repeat(probeRetryCount) { attempt ->
            try {
                val transport = transportFactory(portInfo)
                var result: SignalSlingerPortProbe
                transport.connect()
                try {
                    result = probeWithConnectedTransport(portInfo, transport)
                    if (result.state == PortProbeState.DETECTED) {
                        return result
                    }
                } finally {
                    transport.disconnect()
                }
                val arduconResult = probeArduconPort(portInfo)
                if (arduconResult.state == PortProbeState.DETECTED) {
                    return arduconResult
                }
                if (!shouldRetryProbeResult(result, retryOnNoReply, attempt, probeRetryCount)) {
                    return result
                }
            } catch (exception: Exception) {
                if (!shouldRetryProbe(exception, attempt, probeRetryCount)) {
                    return SignalSlingerPortProbe(
                        portInfo = portInfo,
                        state = PortProbeState.ERROR,
                        summary = exception.message ?: "Probe failed",
                        lastProbedAtMs = System.currentTimeMillis(),
                    )
                }
            }
            Thread.sleep(probeRetryDelayMs)
        }

        return SignalSlingerPortProbe(
            portInfo = portInfo,
            state = PortProbeState.NOT_DETECTED,
            summary = "No recognizable reply",
            lastProbedAtMs = System.currentTimeMillis(),
        )
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
        val productName = productNameFrom(lines)
        val appBaud = appBaudFrom(lines)
        return SignalSlingerPortProbe(
            portInfo = portInfo,
            state = state,
            summary = summaryFor(state, lines),
            evidenceLines = lines,
            productName = productName,
            appBaud = appBaud,
            lastProbedAtMs = startedAtMs,
        )
    }

    fun autoDetectPorts(
        ports: List<DesktopSerialPortInfo> = DesktopSerialTransport.listAvailablePorts(),
        transportFactory: (DesktopSerialPortInfo) -> DeviceTransport = ::defaultTransportFor,
    ): List<SignalSlingerPortProbe> {
        return ports.map { portInfo -> probePort(portInfo, transportFactory = transportFactory) }
    }

    fun findFirstDetectedPort(
        ports: List<DesktopSerialPortInfo>,
        transportFactory: (DesktopSerialPortInfo) -> DeviceTransport = ::defaultTransportFor,
        onProbeComplete: ((SignalSlingerPortProbe) -> Unit)? = null,
    ): AutoDetectScanResult {
        val probes = mutableListOf<SignalSlingerPortProbe>()

        for (portInfo in ports) {
            val result = probePort(portInfo, transportFactory = transportFactory)
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
            PortProbeState.DETECTED -> "${productNameFrom(lines) ?: "SignalSlinger"} detected"
            PortProbeState.NOT_DETECTED -> if (lines.isEmpty()) "No recognizable reply" else "Non-SignalSlinger replies"
            PortProbeState.ERROR -> "Probe failed"
            PortProbeState.UNCHECKED -> "Not checked"
        }
    }

    private fun probeArduconPort(portInfo: DesktopSerialPortInfo): SignalSlingerPortProbe {
        val transport = DesktopSerialTransport(
            portDescriptor = portInfo.systemPortPath,
            baudRate = 57_600,
            lineTerminator = "\r",
            wakePreamble = "",
            readTimeoutMs = 500,
            quietPeriodMs = 80,
        )
        return try {
            transport.connect()
            try {
                Thread.sleep(500)
                transport.readAvailableLinesBriefly(700)
                probeWithCommands(
                    portInfo = portInfo,
                    transport = transport,
                    commands = arduconProbeCommands,
                )
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

    private fun probeWithCommands(
        portInfo: DesktopSerialPortInfo,
        transport: DeviceTransport,
        commands: List<String>,
    ): SignalSlingerPortProbe {
        val lines = mutableListOf<String>()
        val startedAtMs = System.currentTimeMillis()

        for (command in commands) {
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
            productName = productNameFrom(lines),
            appBaud = appBaudFrom(lines),
            lastProbedAtMs = startedAtMs,
        )
    }

    private fun productNameFrom(lines: List<String>): String? {
        return lines.asSequence()
            .mapNotNull { line -> SignalSlingerProtocolCodec.parseReportLine(line)?.deviceInfoPatch?.productName }
            .firstOrNull()
    }

    private fun appBaudFrom(lines: List<String>): Int? {
        return lines.asSequence()
            .mapNotNull { line -> SignalSlingerProtocolCodec.parseReportLine(line)?.deviceInfoPatch?.appBaud }
            .firstOrNull()
    }

    internal fun shouldRetryProbeResult(
        result: SignalSlingerPortProbe,
        retryOnNoReply: Boolean,
        attemptIndex: Int,
        maxAttempts: Int = probeRetryCount,
    ): Boolean {
        val hasAttemptsRemaining = attemptIndex < maxAttempts - 1
        return hasAttemptsRemaining &&
            retryOnNoReply &&
            result.state == PortProbeState.NOT_DETECTED &&
            result.evidenceLines.isEmpty()
    }

    internal fun shouldRetryProbe(
        exception: Exception,
        attemptIndex: Int,
        maxAttempts: Int = probeRetryCount,
    ): Boolean {
        val hasAttemptsRemaining = attemptIndex < maxAttempts - 1
        val message = exception.message.orEmpty().lowercase()
        return hasAttemptsRemaining &&
            (message.contains("error code 2") || message.contains("failed to write complete payload"))
    }

    private fun defaultTransportFor(portInfo: DesktopSerialPortInfo): DeviceTransport {
        return DesktopSerialTransport(
            portDescriptor = portInfo.systemPortPath,
            readTimeoutMs = 350,
            quietPeriodMs = 80,
        )
    }
}
