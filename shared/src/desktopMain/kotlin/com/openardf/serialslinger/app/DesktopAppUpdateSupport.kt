package com.openardf.serialslinger.app

internal data class DesktopAppUpdateStatus(
    val currentVersion: String,
    val launchedByJdeploy: Boolean,
    val jdeployUpdatesAvailable: Boolean?,
    val jdeployAppVersion: String?,
    val jdeployAppSource: String?,
)

internal object DesktopAppUpdateSupport {
    const val updatePageUrl = "https://www.jdeploy.com/gh/OpenARDF/SerialSlinger"
    const val aboutUpdateCheckHref = "serialslinger-check-updates"

    private const val jdeployUpdatesAvailableProperty = "jdeploy.updatesAvailable"
    private const val jdeployAppVersionProperty = "jdeploy.app.version"
    private const val jdeployAppSourceProperty = "jdeploy.app.source"

    fun status(
        currentVersion: String,
        propertyValue: (String) -> String? = System::getProperty,
    ): DesktopAppUpdateStatus {
        val updatesAvailableRaw = propertyValue(jdeployUpdatesAvailableProperty)
        val appVersion = propertyValue(jdeployAppVersionProperty)?.trim()?.takeIf(String::isNotBlank)
        val appSource = propertyValue(jdeployAppSourceProperty)?.trim()?.takeIf(String::isNotBlank)
        return DesktopAppUpdateStatus(
            currentVersion = currentVersion,
            launchedByJdeploy = updatesAvailableRaw != null || appVersion != null || appSource != null,
            jdeployUpdatesAvailable = parseFlexibleBoolean(updatesAvailableRaw),
            jdeployAppVersion = appVersion,
            jdeployAppSource = appSource,
        )
    }

    fun shouldShowAutomaticNotice(status: DesktopAppUpdateStatus): Boolean {
        return status.jdeployUpdatesAvailable == true
    }

    fun dialogMessage(status: DesktopAppUpdateStatus): String {
        return buildString {
            when (status.jdeployUpdatesAvailable) {
                true -> append("An updated version of SerialSlinger is available.")
                false -> append("jDeploy did not report a SerialSlinger update at launch.")
                null -> {
                    if (status.launchedByJdeploy) {
                        append("SerialSlinger was launched by jDeploy, but update status was not reported.")
                    } else {
                        append("SerialSlinger was not launched by jDeploy, so jDeploy update status is not available in this run.")
                    }
                }
            }
            append("\n\nCurrent version: ${status.currentVersion}")
            if (!status.jdeployAppVersion.isNullOrBlank() && status.jdeployAppVersion != status.currentVersion) {
                append("\njDeploy package version: ${status.jdeployAppVersion}")
            }
            if (!status.jdeployAppSource.isNullOrBlank()) {
                append("\njDeploy source: ${status.jdeployAppSource}")
            }
            append("\n\nUpdate page:\n$updatePageUrl")
            append("\n\njDeploy-installed copies normally check for updates automatically each time they launch.")
        }
    }

    private fun parseFlexibleBoolean(value: String?): Boolean? {
        return when (value?.trim()?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }
}
