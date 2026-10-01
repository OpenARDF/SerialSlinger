package com.openardf.serialslinger.session

data class SessionLogIdentity(
    val appVersion: String,
    val platformLabel: String,
) {
    val headerText: String
        get() = "SerialSlinger $appVersion\nPlatform: $platformLabel\n\n"

    val sessionMarkerMessage: String
        get() = "Session identity: SerialSlinger $appVersion; Platform: $platformLabel"
}

/** Keeps a daily log intelligible when more than one app build writes to it. */
object SessionLogIdentitySupport {
    fun needsSessionMarker(
        existingText: String,
        identity: SessionLogIdentity,
    ): Boolean {
        if (existingText.isBlank()) {
            return false
        }

        val lastMarker =
            existingText
                .lineSequence()
                .mapNotNull { line ->
                    line.substringAfter(markerPrefix, missingDelimiterValue = "")
                        .takeIf(String::isNotEmpty)
                }
                .lastOrNull()
        if (lastMarker != null) {
            return lastMarker != identity.sessionMarkerMessage.removePrefix(markerPrefix)
        }

        return !existingText.startsWith(identity.headerText)
    }

    private const val markerPrefix = "Session identity: "
}
