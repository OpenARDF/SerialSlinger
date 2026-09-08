package com.openardf.serialslinger.session

import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import kotlin.test.*

class SessionHistoryTest {
    private val record = "* Session record: v=1 seq=42 base=1788526800 start=1788526800 finish=1788559200 at=1788527000 action=2 reason=2 flags=7 temp=651 limit=65"

    @Test fun parsesDeviceHistoryAndDeduplicatesRefreshes() {
        var state = DeviceSessionWorkflow.connected()
        state = DeviceSessionWorkflow.ingestReportLines(state, listOf(
            "* Session state: v=1 action=2 reason=2 remaining=3 blocked=1",
            "* Session history: v=1 count=1 capacity=7", record, record,
        ))
        val status = assertNotNull(state.snapshot).status
        assertEquals(2, status.sessionReport?.action)
        assertEquals(3, status.daysRemaining)
        assertEquals(true, status.sessionReport?.blocked)
        assertEquals(1, status.sessionHistory.size)
        assertEquals(65.1, status.sessionHistory.single().temperatureC)
        assertNotNull(status.sessionHistory.single().timestampCompact)
        state = DeviceSessionWorkflow.ingestReportLines(state, listOf("* Session history: v=1 count=0 capacity=7"))
        assertTrue(assertNotNull(state.snapshot).status.sessionHistory.isEmpty())
    }

    @Test fun resetHasUnknownStopTimeAndIdentityChangeClearsHistory() {
        var state = DeviceSessionWorkflow.ingestReportLines(DeviceSessionWorkflow.connected(), listOf(
            "* INF uid=42348279800068200107012700000000",
            "* Session state: v=1 action=6 reason=5 remaining=2 blocked=0",
            record.replace("at=1788527000", "at=0").replace("flags=7", "flags=6").replace("reason=2", "reason=5"),
        ))
        assertNull(assertNotNull(state.snapshot).status.sessionHistory.single().timestampCompact)
        state = DeviceSessionWorkflow.ingestReportLines(state, listOf("* INF uid=314A323536384E171D00321700000000"))
        assertTrue(assertNotNull(state.snapshot).status.sessionHistory.isEmpty())
        assertNull(assertNotNull(state.snapshot).status.sessionReport)
    }

    @Test fun newScheduleDoesNotInheritPreviousCompletion() {
        var state = DeviceSessionWorkflow.ingestReportLines(DeviceSessionWorkflow.connected(), listOf(
            "* Session state: v=1 action=4 reason=1 remaining=0 blocked=0", record,
        ))
        state = DeviceSessionWorkflow.ingestReportLines(state, listOf("* Start:Fri 10-apr-2026 15:00:00"))
        assertNull(assertNotNull(state.snapshot).status.sessionReport)
        assertEquals(1, assertNotNull(state.snapshot).status.sessionHistory.size)
    }

    @Test fun legacyInterruptionSurvivesGenericIdleReportWithoutInventingStopTime() {
        val loaded = DeviceSessionWorkflow.ingestReportLines(DeviceSessionWorkflow.connected(),
            listOf("* Event interrupted!", "* Not scheduled"))
        val status = assertNotNull(loaded.snapshot).status
        assertEquals("Event interrupted!", status.eventStateSummary)
        assertNull(status.sessionReport)
        assertTrue(status.sessionHistory.isEmpty())
        val resumed = DeviceSessionWorkflow.ingestReportLines(loaded, listOf("* In progress"))
        assertEquals("In progress", resumed.snapshot?.status?.eventStateSummary)
    }

    @Test fun rejectsMalformedAndUnknownVersionReports() {
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* Session state: v=2 action=4 reason=1 remaining=0 blocked=0"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* Session state: v=1 action=99 reason=1 remaining=0 blocked=0"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine(record.replace("seq=42", "seq=-1")))
        assertNull(SignalSlingerProtocolCodec.parseReportLine(record.replace("limit=65", "limit=999")))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* Session history: v=1 count=8 capacity=7"))
    }
}
