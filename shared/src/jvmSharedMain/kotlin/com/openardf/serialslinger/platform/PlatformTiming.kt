package com.openardf.serialslinger.platform

import java.time.Instant
import java.time.ZoneId

internal actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun platformSleep(milliseconds: Long) {
    Thread.sleep(milliseconds)
}

internal actual fun platformLocalDateTimeFields(epochSeconds: Long): PlatformDateTimeFields? {
    val local = Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    return PlatformDateTimeFields(
        year = local.year,
        month = local.monthValue,
        day = local.dayOfMonth,
        hour = local.hour,
        minute = local.minute,
        second = local.second,
    )
}
