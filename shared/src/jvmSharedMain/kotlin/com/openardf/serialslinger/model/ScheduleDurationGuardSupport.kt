package com.openardf.serialslinger.model

import java.time.Duration

data class ScheduleDurationGuardResolution(
    val preserveDaysToRun: Boolean,
    val resultingDaysToRun: Int,
    val resultingDuration: Duration?,
)

data class DaysToRunGuardResolution(
    val resultingDaysToRun: Int,
    val resultingDuration: Duration?,
)

object ScheduleDurationGuardSupport {
    fun planForDirectDaysToRunChange(
        selectedDaysToRun: Int,
        currentDuration: Duration?,
    ): List<MultiDayDurationGuardOption> {
        return MultiDayDurationGuardPlanner.plan(selectedDaysToRun, currentDuration)
    }

    fun planForScheduleChange(
        currentDaysToRun: Int,
        daysChoice: StartTimeDaysToRunChoice,
        proposedDuration: Duration?,
    ): List<MultiDayDurationGuardOption> {
        val preserveDaysToRun = daysChoice == StartTimeDaysToRunChoice.PRESERVE
        val resultingDaysToRun = if (preserveDaysToRun) currentDaysToRun else 1
        return MultiDayDurationGuardPlanner.plan(resultingDaysToRun, proposedDuration)
    }

    fun resolveScheduleChange(
        currentDaysToRun: Int,
        daysChoice: StartTimeDaysToRunChoice,
        proposedDuration: Duration?,
        selectedOption: MultiDayDurationGuardOption?,
    ): ScheduleDurationGuardResolution {
        val preserveDaysToRun = daysChoice == StartTimeDaysToRunChoice.PRESERVE
        return when (selectedOption?.choice) {
            MultiDayDurationGuardChoice.SET_DAYS_TO_ONE -> ScheduleDurationGuardResolution(
                preserveDaysToRun = false,
                resultingDaysToRun = 1,
                resultingDuration = proposedDuration,
            )
            MultiDayDurationGuardChoice.SHORTEN_DURATION -> ScheduleDurationGuardResolution(
                preserveDaysToRun = true,
                resultingDaysToRun = currentDaysToRun,
                resultingDuration = selectedOption.resultingDuration,
            )
            null -> ScheduleDurationGuardResolution(
                preserveDaysToRun = preserveDaysToRun,
                resultingDaysToRun = if (preserveDaysToRun) currentDaysToRun else 1,
                resultingDuration = proposedDuration,
            )
        }
    }

    fun resolveDirectDaysToRunChange(
        selectedDaysToRun: Int,
        currentDuration: Duration?,
        selectedOption: MultiDayDurationGuardOption?,
    ): DaysToRunGuardResolution {
        return when (selectedOption?.choice) {
            MultiDayDurationGuardChoice.SET_DAYS_TO_ONE -> DaysToRunGuardResolution(
                resultingDaysToRun = 1,
                resultingDuration = currentDuration,
            )
            MultiDayDurationGuardChoice.SHORTEN_DURATION -> DaysToRunGuardResolution(
                resultingDaysToRun = selectedDaysToRun,
                resultingDuration = selectedOption.resultingDuration,
            )
            null -> DaysToRunGuardResolution(
                resultingDaysToRun = selectedDaysToRun,
                resultingDuration = currentDuration,
            )
        }
    }
}
