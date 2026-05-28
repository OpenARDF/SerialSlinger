package com.openardf.serialslinger.platform

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

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

internal actual fun platformUtcDateTimeFields(epochSeconds: Long): PlatformDateTimeFields? {
    val utc = Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneOffset.UTC)
        .toLocalDateTime()
    return PlatformDateTimeFields(
        year = utc.year,
        month = utc.monthValue,
        day = utc.dayOfMonth,
        hour = utc.hour,
        minute = utc.minute,
        second = utc.second,
    )
}

internal actual fun platformEpochSecondsFromLocalDateTimeFields(fields: PlatformDateTimeFields): Long? {
    return try {
        LocalDateTime.of(
            fields.year,
            fields.month,
            fields.day,
            fields.hour,
            fields.minute,
            fields.second,
        ).atZone(ZoneId.systemDefault()).toEpochSecond()
    } catch (_: Exception) {
        null
    }
}
