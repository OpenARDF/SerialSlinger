package com.openardf.serialslinger.model

import kotlin.test.Test
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
}
