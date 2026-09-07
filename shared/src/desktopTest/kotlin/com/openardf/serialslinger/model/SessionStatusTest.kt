package com.openardf.serialslinger.model

import kotlin.test.*

class SessionStatusTest {
    private fun status(report: SessionReport? = null, summary: String? = null, enabled: Boolean? = true): String =
        JvmTimeSupport.describeEventStatus(enabled, summary, "260905130000", "260904090000", "260904180000", null, 3, report)

    @Test fun deviceOutcomeOverridesCalendarAndLegacyText() {
        assertEquals("Paused: overheating", status(SessionReport(2, 2, 2, true), "In progress"))
        assertEquals("Interrupted: stopped by user", status(SessionReport(6, 3, 2, false), "In progress"))
        assertEquals("Last session finished with interruptions", status(SessionReport(5, 1, 0, false)))
        assertEquals("Last session completed (device confirmed)", status(SessionReport(4, 1, 0, false)))
        assertEquals("Schedule expired (completion unconfirmed)", status(SessionReport(7, 0, 0, false)))
        assertEquals("Interrupted (stop time unavailable)", status(summary = "Event interrupted!", enabled = false))
        assertEquals("Disabled", status(enabled = false))
        assertTrue(status(enabled = null).startsWith("Scheduled Day 2"))
    }

    @Test fun historyShowsUnknownTimeAndMissingOlderRecords() {
        val record = SessionHistoryRecord(42, 1788526800, 1788526800, 1788559200, null, 6, 5, 134, null, 65)
        val text = JvmTimeSupport.describeSessionHistory(listOf(record))
        assertTrue(text.contains("time unavailable"))
        assertTrue(text.contains("power loss or reset"))
        assertTrue(text.contains("Earlier history is unavailable"))
    }
}
