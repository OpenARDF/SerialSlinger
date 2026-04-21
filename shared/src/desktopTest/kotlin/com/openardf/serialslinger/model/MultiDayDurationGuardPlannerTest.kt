package com.openardf.serialslinger.model

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiDayDurationGuardPlannerTest {
    @Test
    fun `does not require prompt for single day events`() {
        assertFalse(
            MultiDayDurationGuardPlanner.requiresPrompt(
                resultingDaysToRun = 1,
                resultingDuration = Duration.ofHours(30),
            ),
        )
    }

    @Test
    fun `requires prompt for multi day events lasting 24 hours or longer`() {
        assertTrue(
            MultiDayDurationGuardPlanner.requiresPrompt(
                resultingDaysToRun = 3,
                resultingDuration = Duration.ofHours(24),
            ),
        )
    }

    @Test
    fun `offers reset and shorten options when prompt is required`() {
        val options =
            MultiDayDurationGuardPlanner.plan(
                resultingDaysToRun = 3,
                resultingDuration = Duration.ofHours(25),
            )

        assertEquals(
            listOf(
                MultiDayDurationGuardOption(
                    choice = MultiDayDurationGuardChoice.SET_DAYS_TO_ONE,
                    label = "Set Days To Run to 1",
                    resultingDaysToRun = 1,
                    resultingDuration = Duration.ofHours(25),
                ),
                MultiDayDurationGuardOption(
                    choice = MultiDayDurationGuardChoice.SHORTEN_DURATION,
                    label = "Shorten duration to 23h 00m",
                    resultingDaysToRun = 3,
                    resultingDuration = Duration.ofHours(23),
                ),
            ),
            options,
        )
    }
}
