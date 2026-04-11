package com.openardf.serialslinger.app

import com.openardf.serialslinger.transport.DesktopSerialPortInfo

object DesktopAutoDetectPolicy {
    fun defaultSelectionPath(
        availablePorts: List<DesktopSerialPortInfo>,
        currentSelectionPath: String?,
        lastWorkingPortPath: String?,
    ): String? {
        val canonicalPorts = DesktopSmartPollingPolicy.canonicalizePorts(availablePorts)
        return when {
            currentSelectionPath != null && availablePorts.any { it.systemPortPath == currentSelectionPath } -> {
                DesktopSmartPollingPolicy.preferredPortPath(canonicalPorts, currentSelectionPath)
            }
            lastWorkingPortPath != null && availablePorts.any { it.systemPortPath == lastWorkingPortPath } -> {
                DesktopSmartPollingPolicy.preferredPortPath(canonicalPorts, lastWorkingPortPath)
            }
            else -> canonicalPorts.firstOrNull()?.systemPortPath
        }
    }

    fun detectionOrder(
        availablePorts: List<DesktopSerialPortInfo>,
        lastWorkingPortPath: String?,
        connectedPortPath: String?,
    ): List<DesktopSerialPortInfo> {
        val canonicalPorts = DesktopSmartPollingPolicy.canonicalizePorts(availablePorts)
        val preferredConnectedPath = DesktopSmartPollingPolicy.preferredPortPath(canonicalPorts, connectedPortPath)
        val preferredLastWorkingPath = DesktopSmartPollingPolicy.preferredPortPath(canonicalPorts, lastWorkingPortPath)
        val portsToSearch = canonicalPorts.filterNot { it.systemPortPath == preferredConnectedPath }
        val rememberedPort = portsToSearch.firstOrNull { it.systemPortPath == preferredLastWorkingPath }
        val others = portsToSearch.filterNot { it.systemPortPath == preferredLastWorkingPath }

        return buildList {
            if (rememberedPort != null) {
                add(rememberedPort)
            }
            addAll(others)
        }
    }
}
