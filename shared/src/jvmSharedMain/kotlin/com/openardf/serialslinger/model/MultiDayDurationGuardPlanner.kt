package com.openardf.serialslinger.model

import java.time.Duration

enum class MultiDayDurationGuardChoice {
    SET_DAYS_TO_ONE,
    SHORTEN_DURATION,
}

data class MultiDayDurationGuardOption(
    val choice: MultiDayDurationGuardChoice,
    val label: String,
    val resultingDaysToRun: Int,
    val resultingDuration: Duration,
)

object MultiDayDurationGuardPlanner {
    private const val minimumMultiDayDurationMinutes = 24 * 60
    private const val maximumAllowedMultiDayDurationMinutes = 23 * 60
    private val maximumAllowedMultiDayDuration = Duration.ofMinutes(maximumAllowedMultiDayDurationMinutes.toLong())

    fun maximumAllowedDuration(): Duration = maximumAllowedMultiDayDuration

    fun requiresPrompt(
        resultingDaysToRun: Int,
        resultingDuration: Duration?,
    ): Boolean {
        return resultingDaysToRun > 1 &&
            resultingDuration != null &&
            resultingDuration.toMinutes() >= minimumMultiDayDurationMinutes.toLong()
    }

    fun plan(
        resultingDaysToRun: Int,
        resultingDuration: Duration?,
    ): List<MultiDayDurationGuardOption> {
        if (!requiresPrompt(resultingDaysToRun, resultingDuration)) {
            return emptyList()
        }

        val duration = resultingDuration ?: return emptyList()
        return listOf(
            MultiDayDurationGuardOption(
                choice = MultiDayDurationGuardChoice.SET_DAYS_TO_ONE,
                label = "Set Days To Run to 1",
                resultingDaysToRun = 1,
                resultingDuration = duration,
            ),
            MultiDayDurationGuardOption(
                choice = MultiDayDurationGuardChoice.SHORTEN_DURATION,
                label = "Shorten duration to ${JvmTimeSupport.formatDurationHoursMinutesCompact(maximumAllowedMultiDayDuration)}",
                resultingDaysToRun = resultingDaysToRun,
                resultingDuration = maximumAllowedMultiDayDuration,
            ),
        )
    }
}
