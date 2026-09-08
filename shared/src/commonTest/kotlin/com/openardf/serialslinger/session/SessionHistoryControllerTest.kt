package com.openardf.serialslinger.session

import com.openardf.serialslinger.model.SessionReport
import com.openardf.serialslinger.transport.FakeDeviceTransport
import kotlin.test.*

class SessionHistoryControllerTest {
    private val uid = "42348279800068200107012700000000"
    private val otherUid = "42348279800037200105014100000000"
    private val record = "* Session record: v=1 seq=42 base=1788512400 start=1788512400 finish=1788544800 at=1788512600 action=2 reason=2 flags=7 temp=651 limit=65"
    private val event = listOf(
        "* Event:Classic", "* In progress",
        "* Session state: v=1 action=2 reason=2 remaining=3 blocked=1",
        "* Session history: v=1 count=1 capacity=7", record,
    )
    private fun identity(value: String) = listOf(
        "* INF product=SignalSlinger update=UPD",
        "* INF sw=2.0.4 hw=3.4 app=0x2000 baud=115200",
        "* INF uid=$value",
    )
    private fun responses(value: String = uid, evt: List<String> = event) = mapOf(
        "VER" to listOf("* SW Ver: 2.0.4 HW Build: 3.4"),
        "EVT" to evt,
        "CLK" to listOf("* Time:Fri 04-sep-2026 09:03:20", "* Start:Fri 04-sep-2026 09:00:00",
            "* Finish:Fri 04-sep-2026 18:00:00", "* Days to run: 3"),
        "INF" to identity(value),
    )

    @Test fun initialLoadAndSameDeviceRefreshRetainOutcomeAndHistory() {
        val transport = FakeDeviceTransport(responses())
        val loaded = DeviceSessionController.connectAndLoad(transport)
        val status = assertNotNull(loaded.state.snapshot).status
        assertEquals(SessionReport(2, 2, 3, true), status.sessionReport)
        assertEquals(42L, status.sessionHistory.single().sequence)
        assertEquals(uid, status.sessionHistoryDeviceUniqueId)
        assertEquals("EVT", loaded.commandsSent.last())
        val refreshed = DeviceSessionController.refreshFromDevice(loaded.state, transport)
        assertEquals(status.sessionReport, refreshed.state.snapshot?.status?.sessionReport)
        assertEquals(status.sessionHistory, refreshed.state.snapshot?.status?.sessionHistory)
        assertEquals(1, refreshed.commandsSent.count { it == "EVT" })
    }

    @Test fun changedIdentityWithSameShortPrefixKeepsOnlyNewDeviceRecords() {
        val loaded = DeviceSessionController.connectAndLoad(FakeDeviceTransport(responses())).state
        val otherEvent = event.map { it.replace("seq=42", "seq=99") }
        val refreshed = DeviceSessionController.refreshFromDevice(loaded, FakeDeviceTransport(responses(otherUid, otherEvent)))
        val status = assertNotNull(refreshed.state.snapshot).status
        assertEquals(otherUid, status.sessionHistoryDeviceUniqueId)
        assertEquals(99L, status.sessionHistory.single().sequence)
        assertEquals(SessionReport(2, 2, 3, true), status.sessionReport)
    }

    @Test fun emptyDeviceJournalClearsPreviouslyLoadedRecords() {
        val loaded = DeviceSessionController.connectAndLoad(FakeDeviceTransport(responses())).state
        val emptyEvent = listOf("* Event:Classic", "* Session state: v=1 action=7 reason=0 remaining=0 blocked=0",
            "* Session history: v=1 count=0 capacity=7")
        val refreshed = DeviceSessionController.refreshFromDevice(loaded, FakeDeviceTransport(responses(evt = emptyEvent)))
        assertTrue(assertNotNull(refreshed.state.snapshot).status.sessionHistory.isEmpty())
        assertEquals(7, refreshed.state.snapshot.status.sessionReport?.action)
    }

    @Test fun failedFinalOutcomeReadDoesNotReviveInvalidatedCompletion() {
        val transport = FakeDeviceTransport(responses(), scriptedResponseSequences = mapOf("EVT" to listOf(event, emptyList())))
        val loaded = DeviceSessionController.connectAndLoad(transport)
        assertNull(loaded.state.snapshot?.status?.sessionReport)
        assertEquals(42L, loaded.state.snapshot?.status?.sessionHistory?.single()?.sequence)
    }

    @Test fun identityProbeWithoutUidCannotRetainAnotherDevicesHistory() {
        val loaded = DeviceSessionController.connectAndLoad(FakeDeviceTransport(responses())).state
        val unidentified = responses(evt = listOf("* Event:Classic")) + ("INF" to emptyList())
        val refreshed = DeviceSessionController.refreshFromDevice(loaded, FakeDeviceTransport(unidentified))
        val status = assertNotNull(refreshed.state.snapshot).status
        assertNull(status.sessionReport)
        assertNull(status.sessionHistoryDeviceUniqueId)
        assertTrue(status.sessionHistory.isEmpty())
    }

    @Test fun olderFirmwareDoesNotRequireVersionedHistoryOrAnExtraEventQuery() {
        val loaded = DeviceSessionController.connectAndLoad(FakeDeviceTransport(responses(evt = listOf("* Event:Classic", "* Event interrupted!"))))
        assertNull(loaded.state.snapshot?.status?.sessionReport)
        assertTrue(assertNotNull(loaded.state.snapshot).status.sessionHistory.isEmpty())
        assertEquals(1, loaded.commandsSent.count { it == "EVT" })
    }
}
