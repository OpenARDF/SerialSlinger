package com.openardf.serialslinger.model

enum class CloneDeviceIdentityComparison {
    DIFFERENT,
    SAME,
    UNAVAILABLE,
}

object CloneDeviceIdentitySupport {
    fun compare(
        templateSourceDeviceUniqueId: String?,
        targetDeviceUniqueId: String?,
    ): CloneDeviceIdentityComparison {
        val sourceId = templateSourceDeviceUniqueId.normalizedDeviceUniqueId()
        val targetId = targetDeviceUniqueId.normalizedDeviceUniqueId()
        return when {
            sourceId == null || targetId == null -> CloneDeviceIdentityComparison.UNAVAILABLE
            sourceId == targetId -> CloneDeviceIdentityComparison.SAME
            else -> CloneDeviceIdentityComparison.DIFFERENT
        }
    }

    fun requireDifferentDevice(
        templateSourceDeviceUniqueId: String?,
        targetDeviceUniqueId: String?,
    ) {
        if (compare(templateSourceDeviceUniqueId, targetDeviceUniqueId) != CloneDeviceIdentityComparison.SAME) {
            return
        }
        val sourceId = requireNotNull(templateSourceDeviceUniqueId.normalizedDeviceUniqueId())
        error(
            "Clone cancelled because the attached SignalSlinger is the same unit that supplied " +
                "the clone template (unit ${sourceId.takeLast(8)}). Connect a different SignalSlinger and try again.",
        )
    }

    private fun String?.normalizedDeviceUniqueId(): String? =
        this
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase()
}
