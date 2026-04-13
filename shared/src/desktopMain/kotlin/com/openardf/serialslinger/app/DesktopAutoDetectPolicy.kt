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
        val connectedAliasGroup = DesktopSmartPollingPolicy.aliasGroupKey(preferredConnectedPath ?: connectedPortPath.orEmpty())
        val rememberedAliasGroup = DesktopSmartPollingPolicy.aliasGroupKey(preferredLastWorkingPath ?: lastWorkingPortPath.orEmpty())

        return availablePorts
            .filterNot { portInfo ->
                portInfo.systemPortPath == preferredConnectedPath ||
                    (
                        connectedAliasGroup != null &&
                            DesktopSmartPollingPolicy.aliasGroupKey(portInfo.systemPortPath) == connectedAliasGroup
                        )
            }
            .sortedWith(
                compareBy<DesktopSerialPortInfo>(
                    { rememberPriority(portInfo = it, preferredPath = preferredLastWorkingPath, aliasGroup = rememberedAliasGroup) },
                    { aliasPriority(it.systemPortPath) },
                    { it.systemPortPath },
                ),
            )
    }

    private fun rememberPriority(
        portInfo: DesktopSerialPortInfo,
        preferredPath: String?,
        aliasGroup: String?,
    ): Int {
        return when {
            preferredPath != null && portInfo.systemPortPath == preferredPath -> 0
            aliasGroup != null && DesktopSmartPollingPolicy.aliasGroupKey(portInfo.systemPortPath) == aliasGroup -> 1
            else -> 2
        }
    }

    private fun aliasPriority(systemPortPath: String): Int {
        return when {
            systemPortPath.startsWith("/dev/cu.") -> 0
            systemPortPath.startsWith("/dev/tty.") -> 1
            else -> 0
        }
    }
}
