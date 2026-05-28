package com.openardf.serialslinger.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval
import platform.posix.usleep

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformCurrentTimeMillis(): Long =
    memScoped {
        val currentTime = alloc<timeval>()
        gettimeofday(currentTime.ptr, null)
        currentTime.tv_sec * 1000L + currentTime.tv_usec / 1000L
    }

internal actual fun platformSleep(milliseconds: Long) {
    usleep((milliseconds.coerceAtLeast(0L) * 1000L).toUInt())
}

internal actual fun platformLocalDateTimeFields(epochSeconds: Long): PlatformDateTimeFields? =
    utcDateTimeFields(epochSeconds)

private fun utcDateTimeFields(epochSeconds: Long): PlatformDateTimeFields? {
    if (epochSeconds < 0L) {
        return null
    }
    val days = epochSeconds / 86_400L
    val secondsOfDay = (epochSeconds % 86_400L).toInt()
    val (year, month, day) = civilFromDays(days)
    return PlatformDateTimeFields(
        year = year,
        month = month,
        day = day,
        hour = secondsOfDay / 3_600,
        minute = (secondsOfDay % 3_600) / 60,
        second = secondsOfDay % 60,
    )
}

private fun civilFromDays(daysSinceEpoch: Long): Triple<Int, Int, Int> {
    val z = daysSinceEpoch + 719_468L
    val era = if (z >= 0) z / 146_097L else (z - 146_096L) / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    val year = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = dayOfYear - (153L * monthPrime + 2L) / 5L + 1L
    val month = monthPrime + if (monthPrime < 10) 3L else -9L
    val adjustedYear = year + if (month <= 2) 1L else 0L
    return Triple(adjustedYear.toInt(), month.toInt(), day.toInt())
}
