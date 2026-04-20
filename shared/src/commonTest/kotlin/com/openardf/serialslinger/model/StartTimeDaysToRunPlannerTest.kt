package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StartTimeDaysToRunPlannerTest {
    @Test
    fun `days to run of one skips prompt and resets`() {
        val plan = StartTimeDaysToRunPlanner.plan(currentDaysToRun = 1)

        assertEquals(StartTimeDaysToRunChoice.RESET, plan.autoChoice)
        assertEquals(emptyList(), plan.options)
    }

    @Test
    fun `days to run above one offers preserve then reset`() {
        val plan = StartTimeDaysToRunPlanner.plan(currentDaysToRun = 3)

        assertNull(plan.autoChoice)
        assertEquals(
            listOf(
                StartTimeDaysToRunOption(
                    choice = StartTimeDaysToRunChoice.PRESERVE,
                    label = "Preserve current Days To Run (3)",
                ),
                StartTimeDaysToRunOption(
                    choice = StartTimeDaysToRunChoice.RESET,
                    label = "Reset Days To Run to 1",
                ),
            ),
            plan.options,
        )
    }
}
