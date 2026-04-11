package com.openardf.serialslinger.app

import com.openardf.serialslinger.transport.DesktopSerialPortInfo

object DesktopSmartPollingPolicy {
    private const val detectedRecheckMs = 20_000L

    fun canonicalizePorts(availablePorts: List<DesktopSerialPortInfo>): List<DesktopSerialPortInfo> {
        val grouped = linkedMapOf<String, MutableList<DesktopSerialPortInfo>>()
        availablePorts.forEach { portInfo ->
            val key = aliasGroupKey(portInfo.systemPortPath) ?: portInfo.systemPortPath
            grouped.getOrPut(key) { mutableListOf() }.add(portInfo)
        }

        return grouped.values.map { group ->
            group.firstOrNull { it.systemPortPath.startsWith("/dev/cu.") } ?: group.first()
        }
    }

    fun preferredPortPath(
        availablePorts: List<DesktopSerialPortInfo>,
        requestedPath: String?,
    ): String? {
        requestedPath ?: return null
        val requested = availablePorts.firstOrNull { it.systemPortPath == requestedPath }
        if (requested != null && requested.systemPortPath.startsWith("/dev/cu.")) {
            return requested.systemPortPath
        }

        val aliasGroup = aliasGroupKey(requestedPath)
        if (aliasGroup != null) {
            availablePorts.firstOrNull {
                aliasGroupKey(it.systemPortPath) == aliasGroup && it.systemPortPath.startsWith("/dev/cu.")
            }?.let { return it.systemPortPath }
        }

        return requested?.systemPortPath ?: requestedPath.takeIf { path ->
            availablePorts.any { it.systemPortPath == path }
        }
    }

    fun nextProbeCandidate(
        availablePorts: List<DesktopSerialPortInfo>,
        knownProbes: Map<String, SignalSlingerPortProbe>,
        connectedPortPath: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): DesktopSerialPortInfo? {
        return availablePorts
            .map { portInfo ->
                Candidate(
                    portInfo = portInfo,
                    priority = priorityFor(
                        probe = knownProbes[portInfo.systemPortPath],
                        isConnectedPort = portInfo.systemPortPath == connectedPortPath,
                    ),
                    dueAtMs = dueAtMs(
                        probe = knownProbes[portInfo.systemPortPath],
                        isConnectedPort = portInfo.systemPortPath == connectedPortPath,
                    ),
                )
            }
            .filter { it.dueAtMs <= nowMs }
            .minWithOrNull(compareBy<Candidate>({ it.priority }, { it.dueAtMs }, { it.portInfo.systemPortPath }))
            ?.portInfo
    }

    private fun priorityFor(
        probe: SignalSlingerPortProbe?,
        isConnectedPort: Boolean,
    ): Int {
        return when {
            probe == null || probe.lastProbedAtMs == null -> 0
            probe.state == PortProbeState.UNCHECKED -> 0
            probe.state == PortProbeState.DETECTED && isConnectedPort -> 1
            probe.state == PortProbeState.DETECTED -> 2
            else -> 3
        }
    }

    private fun dueAtMs(
        probe: SignalSlingerPortProbe?,
        isConnectedPort: Boolean,
    ): Long {
        val lastProbedAtMs = probe?.lastProbedAtMs ?: return Long.MIN_VALUE

        return when {
            probe.state == PortProbeState.UNCHECKED -> 0L
            probe.state == PortProbeState.DETECTED -> lastProbedAtMs + detectedRecheckMs
            else -> Long.MAX_VALUE
        }
    }

    fun aliasGroupKey(systemPortPath: String): String? {
        val devicePrefix = "/dev/"
        if (!systemPortPath.startsWith(devicePrefix)) {
            return null
        }

        val deviceName = systemPortPath.removePrefix(devicePrefix)
        return when {
            deviceName.startsWith("cu.") -> deviceName.removePrefix("cu.")
            deviceName.startsWith("tty.") -> deviceName.removePrefix("tty.")
            else -> null
        }
    }

    private data class Candidate(
        val portInfo: DesktopSerialPortInfo,
        val priority: Int,
        val dueAtMs: Long,
    )
}
