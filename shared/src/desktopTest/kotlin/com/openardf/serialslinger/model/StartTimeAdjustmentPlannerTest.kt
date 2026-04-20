package com.openardf.serialslinger.model

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class StartTimeAdjustmentPlannerTest {
    @Test
    fun plan_returnsDefaultOnlyWhenExistingDurationIsInvalid() {
        val options = StartTimeAdjustmentPlanner.plan(
            currentStartTimeCompact = null,
            currentFinishTimeCompact = "260420180000",
            proposedStartTimeCompact = "260420120000",
            defaultEventLengthMinutes = 360,
        )

        assertEquals(
            listOf(
                StartTimeAdjustmentOption(
                    kind = StartTimeAdjustmentOptionKind.ADJUST_FOR_DEFAULT_DURATION,
                    duration = Duration.ofHours(6),
                ),
            ),
            options,
        )
    }

    @Test
    fun plan_omitsKeepWhenExistingMatchesDefaultAndIncludesLeaveWhenDistinct() {
        val options = StartTimeAdjustmentPlanner.plan(
            currentStartTimeCompact = "260420120000",
            currentFinishTimeCompact = "260420180000",
            proposedStartTimeCompact = "260420121000",
            defaultEventLengthMinutes = 360,
        )

        assertEquals(
            listOf(
                StartTimeAdjustmentOption(
                    kind = StartTimeAdjustmentOptionKind.ADJUST_FOR_DEFAULT_DURATION,
                    duration = Duration.ofHours(6),
                ),
                StartTimeAdjustmentOption(
                    kind = StartTimeAdjustmentOptionKind.LEAVE_FINISH_UNCHANGED,
                    duration = Duration.ofHours(5).plusMinutes(50),
                ),
            ),
            options,
        )
    }

    @Test
    fun plan_usesDisableEventWhenProposedStartEqualsCurrentFinish() {
        val options = StartTimeAdjustmentPlanner.plan(
            currentStartTimeCompact = "260420120000",
            currentFinishTimeCompact = "260420180000",
            proposedStartTimeCompact = "260420180000",
            defaultEventLengthMinutes = 360,
        )

        assertEquals(
            listOf(
                StartTimeAdjustmentOption(
                    kind = StartTimeAdjustmentOptionKind.ADJUST_FOR_DEFAULT_DURATION,
                    duration = Duration.ofHours(6),
                ),
                StartTimeAdjustmentOption(kind = StartTimeAdjustmentOptionKind.DISABLE_EVENT),
            ),
            options,
        )
    }
}
