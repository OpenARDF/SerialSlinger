package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmTimeSupportTest {
    @Test
    fun `scheduling fields are editable when device time is valid`() {
        assertTrue(
            JvmTimeSupport.areSchedulingFieldsEditable(
                currentTimeCompact = "260421115500",
            ),
        )
    }

    @Test
    fun `scheduling fields are not editable when device time is invalid`() {
        assertFalse(
            JvmTimeSupport.areSchedulingFieldsEditable(
                currentTimeCompact = null,
            ),
        )
    }

    @Test
    fun `finish time is editable when start time is valid and not in the past`() {
        assertTrue(
            JvmTimeSupport.isFinishTimeEditable(
                startTimeCompact = "260421120000",
                currentTimeCompact = "260421115500",
            ),
        )
    }

    @Test
    fun `finish time is not editable when start time is earlier than current time`() {
        assertFalse(
            JvmTimeSupport.isFinishTimeEditable(
                startTimeCompact = "260421115000",
                currentTimeCompact = "260421115500",
            ),
        )
    }

    @Test
    fun `finish time is not editable when start time is invalid`() {
        assertFalse(
            JvmTimeSupport.isFinishTimeEditable(
                startTimeCompact = null,
                currentTimeCompact = "260421115500",
            ),
        )
    }

    @Test
    fun `clone schedule is eligible when start and finish are equal`() {
        assertTrue(
            JvmTimeSupport.isCloneScheduleEligible(
                startTimeCompact = "000101000000",
                finishTimeCompact = "000101000000",
                currentTimeCompact = "260430071211",
                daysToRun = 1,
                daysRemaining = null,
            ),
        )
    }

    @Test
    fun `clone schedule is eligible for past multi day window with days remaining`() {
        assertTrue(
            JvmTimeSupport.isCloneScheduleEligible(
                startTimeCompact = "260428233000",
                finishTimeCompact = "260428234500",
                currentTimeCompact = "260430071211",
                daysToRun = 7,
                daysRemaining = 5,
            ),
        )
    }

    @Test
    fun `clone schedule is not eligible for completed single day window`() {
        assertFalse(
            JvmTimeSupport.isCloneScheduleEligible(
                startTimeCompact = "260428233000",
                finishTimeCompact = "260428234500",
                currentTimeCompact = "260430071211",
                daysToRun = 1,
                daysRemaining = null,
            ),
        )
    }

    @Test
    fun `normalize start time for change snaps past selection to minimum boundary`() {
        val normalized =
            JvmTimeSupport.normalizeStartTimeForChange(
                startTimeCompact = "260421115000",
                currentTimeCompact = "260421115542",
            )

        assertEquals("260421120000", normalized.startTimeCompact)
        assertTrue(normalized.wasAdjustedToMinimum)
    }

    @Test
    fun `normalize start time for change keeps valid future selection unchanged`() {
        val normalized =
            JvmTimeSupport.normalizeStartTimeForChange(
                startTimeCompact = "260421120500",
                currentTimeCompact = "260421115542",
            )

        assertEquals("260421120500", normalized.startTimeCompact)
        assertFalse(normalized.wasAdjustedToMinimum)
    }
}
