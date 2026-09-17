package com.openardf.serialslinger.model

import kotlin.test.*

class SessionHistoryPresentationTest {
    private fun record(seq: Long, action: Int, reason: Int = 0, at: String? = "260916071057", start: Long = 1789516800, flags: Int = 129) =
        SessionHistoryRecord(seq, 1789542600, start, 1789543257, at, action, reason, flags, 27.2, 65)

    @Test fun manualRestartUsesRecordedStartRatherThanMidnightAndGroupsItsCompletion() {
        val text = SessionHistoryPresentation.summary(listOf(record(21, 1), record(22, 4, 1, "260916072057")))
        assertTrue(text.contains("Latest: Manual run or test"))
        assertTrue(text.contains("Started 2026-09-16 07:10:57"))
        assertTrue(text.contains("Completed at 2026-09-16 07:20:57"))
        assertFalse(text.contains("00:00:00"))
        assertFalse(text.contains("°C"))
        assertFalse(text.contains("Earlier:"))
    }

    @Test fun distinctRunsAndRepeatedStartsAreNotCollapsed() {
        val text = SessionHistoryPresentation.summary(listOf(
            record(1, 1, at = "260916071000"), record(2, 6, 3, "260916071018"),
            record(3, 1), record(4, 4, 1, "260916072057"),
        ))
        assertTrue(text.indexOf("07:20:57") < text.indexOf("07:10:18"))
        assertTrue(text.contains("stop command received"))
        assertFalse(text.contains("stopped by user"))
    }

    @Test fun truncatedAndActiveHistoryDoesNotInventAStartOrCompletion() {
        val partial = SessionHistoryPresentation.summary(listOf(record(2, 6, 5, null)))
        assertTrue(partial.contains("Start is not in the retained history"))
        assertTrue(partial.contains("time unavailable"))
        assertTrue(partial.contains("power loss or reset"))
        assertTrue(partial.contains("Earlier history is unavailable"))
        val active = SessionHistoryPresentation.summary(listOf(record(1, 1)))
        assertTrue(active.contains("no end recorded"))
        assertFalse(active.contains("Completed"))
    }

    @Test fun thermalPauseIsExplainedAndDetailsRetainAllMeasurements() {
        val paused = record(2, 2, 2).copy(temperatureC = 65.1)
        assertTrue(SessionHistoryPresentation.summary(listOf(record(1, 1), paused)).contains("65.1°C"))
        val history = listOf(record(1, 1), paused, record(3, 3), record(4, 5, 1))
        assertTrue(SessionHistoryPresentation.summary(history).contains("includes a pause"))
        val details = SessionHistoryPresentation.details(history)
        assertTrue(details.contains("temperature 27.2°C"))
        assertTrue(details.contains("Record 2; schedule=1789542600"))
    }

    @Test fun logSummariesUseOnlyTheCurrentReadbackAndKeepAnEmptyHistoryExplicit() {
        assertNull(SessionHistoryPresentation.logSummary(listOf("* Time:Wed 16-Sep-2026 07:42:05")))
        val text = assertNotNull(SessionHistoryPresentation.logSummary(listOf(
            "* Session history: v=1 count=1 capacity=7",
            "* Session record: v=1 seq=22 base=1789542600 start=1789516800 finish=1789543257 at=1789543257 action=4 reason=1 flags=129 temp=272 limit=65",
        )))
        assertTrue(text.contains("Completed at 2026-09-16 07:20:57"))
        val empty = assertNotNull(SessionHistoryPresentation.logSummary(listOf("* Session history: v=1 count=0 capacity=7")))
        assertTrue(empty.contains("No run history"))
        assertFalse(empty.contains("Completed"))
    }

    @Test fun unreadableOrIncompleteReadbackIsNotReportedAsAnEmptyHistory() {
        val unreadable = assertNotNull(SessionHistoryPresentation.logSummary(listOf("* Session history: v=2 count=1 capacity=7")))
        assertTrue(unreadable.contains("could not be interpreted"))
        assertFalse(unreadable.contains("No run history"))
        val partial = assertNotNull(SessionHistoryPresentation.logSummary(listOf(
            "* Session history: v=1 count=1 capacity=7",
            "* Session record: truncated",
        )))
        assertTrue(partial.contains("Incomplete history readback: received 0 of 1 records"))
        assertFalse(partial.contains("No run history"))
    }
}
