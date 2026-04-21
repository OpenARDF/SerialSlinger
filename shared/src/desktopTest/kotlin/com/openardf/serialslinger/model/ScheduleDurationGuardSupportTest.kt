package com.openardf.serialslinger.model

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleDurationGuardSupportTest {
    @Test
    fun `plan for direct days change prompts only for invalid multi day duration`() {
        assertEquals(
            2,
            ScheduleDurationGuardSupport.planForDirectDaysToRunChange(
                selectedDaysToRun = 4,
                currentDuration = Duration.ofHours(24),
            ).size,
        )
        assertEquals(
            emptyList(),
            ScheduleDurationGuardSupport.planForDirectDaysToRunChange(
                selectedDaysToRun = 1,
                currentDuration = Duration.ofHours(24),
            ),
        )
    }

    @Test
    fun `plan for schedule change only prompts when preserved multi day duration is invalid`() {
        assertEquals(
            2,
            ScheduleDurationGuardSupport.planForScheduleChange(
                currentDaysToRun = 3,
                daysChoice = StartTimeDaysToRunChoice.PRESERVE,
                proposedDuration = Duration.ofHours(24),
            ).size,
        )
        assertEquals(
            emptyList(),
            ScheduleDurationGuardSupport.planForScheduleChange(
                currentDaysToRun = 3,
                daysChoice = StartTimeDaysToRunChoice.RESET,
                proposedDuration = Duration.ofHours(24),
            ),
        )
    }

    @Test
    fun `resolve schedule change keeps reset choice when no guard option selected`() {
        val resolution = ScheduleDurationGuardSupport.resolveScheduleChange(
            currentDaysToRun = 4,
            daysChoice = StartTimeDaysToRunChoice.RESET,
            proposedDuration = Duration.ofHours(6),
            selectedOption = null,
        )

        assertEquals(false, resolution.preserveDaysToRun)
        assertEquals(1, resolution.resultingDaysToRun)
        assertEquals(Duration.ofHours(6), resolution.resultingDuration)
    }

    @Test
    fun `resolve schedule change can shorten duration while preserving days`() {
        val resolution = ScheduleDurationGuardSupport.resolveScheduleChange(
            currentDaysToRun = 4,
            daysChoice = StartTimeDaysToRunChoice.PRESERVE,
            proposedDuration = Duration.ofHours(24),
            selectedOption = MultiDayDurationGuardPlanner.plan(
                resultingDaysToRun = 4,
                resultingDuration = Duration.ofHours(24),
            ).single { it.choice == MultiDayDurationGuardChoice.SHORTEN_DURATION },
        )

        assertEquals(true, resolution.preserveDaysToRun)
        assertEquals(4, resolution.resultingDaysToRun)
        assertEquals(MultiDayDurationGuardPlanner.maximumAllowedDuration(), resolution.resultingDuration)
    }

    @Test
    fun `resolve direct days change can force single day or shortened duration`() {
        val options = MultiDayDurationGuardPlanner.plan(
            resultingDaysToRun = 4,
            resultingDuration = Duration.ofHours(24),
        )

        val setDaysToOne = ScheduleDurationGuardSupport.resolveDirectDaysToRunChange(
            selectedDaysToRun = 4,
            currentDuration = Duration.ofHours(24),
            selectedOption = options.single { it.choice == MultiDayDurationGuardChoice.SET_DAYS_TO_ONE },
        )
        assertEquals(1, setDaysToOne.resultingDaysToRun)
        assertEquals(Duration.ofHours(24), setDaysToOne.resultingDuration)

        val shortenDuration = ScheduleDurationGuardSupport.resolveDirectDaysToRunChange(
            selectedDaysToRun = 4,
            currentDuration = Duration.ofHours(24),
            selectedOption = options.single { it.choice == MultiDayDurationGuardChoice.SHORTEN_DURATION },
        )
        assertEquals(4, shortenDuration.resultingDaysToRun)
        assertEquals(MultiDayDurationGuardPlanner.maximumAllowedDuration(), shortenDuration.resultingDuration)
    }
}
