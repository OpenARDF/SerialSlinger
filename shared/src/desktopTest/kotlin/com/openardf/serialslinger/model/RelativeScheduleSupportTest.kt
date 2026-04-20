package com.openardf.serialslinger.model

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeScheduleSupportTest {
    @Test
    fun `derive selection rounds to nearest five minutes and uses toth at zero`() {
        val selection = RelativeScheduleSupport.deriveSelection(
            baseCompact = "260420100000",
            targetCompact = "260420120030",
        )

        assertEquals(
            RelativeScheduleSelection(hours = 2, minutes = 0, useTopOfHour = true),
            selection,
        )
    }

    @Test
    fun `derive selection rounds up to next hour when minutes roll over`() {
        val selection = RelativeScheduleSupport.deriveSelection(
            baseCompact = "260420100000",
            targetCompact = "260420115800",
        )

        assertEquals(
            RelativeScheduleSelection(hours = 2, minutes = 0, useTopOfHour = true),
            selection,
        )
    }

    @Test
    fun `format default event length matches expected hours and minutes`() {
        assertEquals("6h 00m", RelativeScheduleSupport.formatDefaultEventLength(360))
        assertEquals("15m", RelativeScheduleSupport.formatDefaultEventLength(15))
    }

    @Test
    fun `selection for duration rounds positive seconds up to next minute`() {
        val selection = RelativeScheduleSupport.selectionForDuration(Duration.ofSeconds(3661))

        assertEquals(
            RelativeScheduleSelection(hours = 1, minutes = 2, useTopOfHour = false),
            selection,
        )
    }
}
