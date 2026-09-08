package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulePresentationTest {
    @Test
    fun `final schedule lists all three September dates`() {
        val lines = SchedulePresentation.scheduleLines(DeviceSettings.empty().copy(
            startTimeCompact = "260904090000", finishTimeCompact = "260904180000", daysToRun = 3,
        ))
        assertEquals(4, lines.size)
        assertEquals("Schedule: 3 days", lines.first())
        for (day in 4..6) {
            assertTrue(lines[day - 3].contains("2026-09-0$day 09:00"))
            assertTrue(lines[day - 3].contains("2026-09-0$day 18:00"))
        }
    }

    @Test
    fun `expired dates cannot assert completion and stale remaining count cannot revive them`() {
        assertEquals("Expired — completion not confirmed", describe(null))
        assertEquals("Interrupted — schedule expired", describe("Event interrupted!"))
        assertEquals("Expired — completion not confirmed", describe("Session expired."))
        assertEquals("(Expired; 0 scheduled windows remaining)", JvmTimeSupport.formatDaysToRunRemainingSummary(
            3, 3, "260907140000", "260904090000", "260904180000",
        ))
    }

    @Test
    fun `disabled transmitter during a window is not labeled running`() {
        val text = JvmTimeSupport.describeEventStatus(false, "Not scheduled", "260905100000",
            "260904090000", "260904180000", null, 3)
        assertEquals("Disabled", text)
    }

    private fun describe(summary: String?) = JvmTimeSupport.describeEventStatus(
        false, summary, "260907140000", "260904090000", "260904180000", null, 3,
    )
}
