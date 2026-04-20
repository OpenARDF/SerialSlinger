package com.openardf.serialslinger.model

import java.time.Duration

data class RelativeScheduleSelection(
    val hours: Int,
    val minutes: Int,
    val useTopOfHour: Boolean = false,
)

object RelativeScheduleSupport {
    fun deriveSelection(
        baseCompact: String?,
        targetCompact: String?,
    ): RelativeScheduleSelection {
        val base = baseCompact?.let(JvmTimeSupport::normalizeCurrentTimeCompactForDisplay)?.let(JvmTimeSupport::parseCompactTimestamp)
        val target = targetCompact?.let(JvmTimeSupport::normalizeCurrentTimeCompactForDisplay)?.let(JvmTimeSupport::parseCompactTimestamp)
        if (base == null || target == null || !target.isAfter(base)) {
            return RelativeScheduleSelection(hours = 0, minutes = 0, useTopOfHour = true)
        }

        val totalMinutes = Duration.between(base, target).toMinutes().coerceAtLeast(0)
        val hours = (totalMinutes / 60).toInt().coerceIn(0, 480)
        val minutes = (totalMinutes % 60).toInt()
        val roundedMinutes = ((minutes + 2) / 5) * 5
        return if (roundedMinutes >= 60) {
            RelativeScheduleSelection(hours = (hours + 1).coerceIn(0, 480), minutes = 0, useTopOfHour = true)
        } else if (roundedMinutes == 0) {
            RelativeScheduleSelection(hours = hours, minutes = 0, useTopOfHour = true)
        } else {
            RelativeScheduleSelection(hours = hours, minutes = roundedMinutes, useTopOfHour = false)
        }
    }

    fun formatSelection(selection: RelativeScheduleSelection): String {
        return if (selection.useTopOfHour) {
            "+${selection.hours} TOTH"
        } else {
            "+${selection.hours}:${selection.minutes}"
        }
    }

    fun formatCommand(selection: RelativeScheduleSelection): String {
        return if (selection.useTopOfHour) {
            "+${selection.hours}"
        } else {
            "+${selection.hours}:${selection.minutes}"
        }
    }

    fun validateDefaultEventLengthMinutes(minutes: Int): Int {
        require(minutes in 10..(24 * 60)) {
            "Default Event Length must be between 10 minutes and 24 hours."
        }
        return minutes
    }

    fun formatDefaultEventLength(minutes: Int): String {
        val validatedMinutes = validateDefaultEventLengthMinutes(minutes)
        val hoursPart = validatedMinutes / 60
        val minutesPart = validatedMinutes % 60
        return if (hoursPart > 0) {
            "${hoursPart}h ${minutesPart.toString().padStart(2, '0')}m"
        } else {
            "${minutesPart}m"
        }
    }

    fun selectionForDuration(minutes: Int): RelativeScheduleSelection {
        val validatedMinutes = validateDefaultEventLengthMinutes(minutes)
        return RelativeScheduleSelection(
            hours = validatedMinutes / 60,
            minutes = validatedMinutes % 60,
            useTopOfHour = false,
        )
    }

    fun selectionForDuration(duration: Duration): RelativeScheduleSelection {
        require(!duration.isNegative && !duration.isZero) {
            "Duration must be positive."
        }
        val totalSeconds = duration.seconds.coerceAtLeast(0)
        val totalMinutes = (totalSeconds / 60) + if (totalSeconds % 60 == 0L) 0 else 1
        return RelativeScheduleSelection(
            hours = (totalMinutes / 60).toInt(),
            minutes = (totalMinutes % 60).toInt(),
            useTopOfHour = false,
        )
    }
}
