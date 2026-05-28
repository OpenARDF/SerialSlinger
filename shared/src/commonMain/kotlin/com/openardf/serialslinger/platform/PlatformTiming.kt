package com.openardf.serialslinger.platform

internal expect fun platformCurrentTimeMillis(): Long

internal expect fun platformSleep(milliseconds: Long)

internal data class PlatformDateTimeFields(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

internal expect fun platformLocalDateTimeFields(epochSeconds: Long): PlatformDateTimeFields?
