package com.openardf.serialslinger.session

import com.openardf.serialslinger.transport.FakeDeviceTransport
import kotlin.test.*

class RunningEventReadSupportTest {
    private val running = DeviceSessionWorkflow.ingestReportLines(
        DeviceSessionWorkflow.connected(),
        listOf("* In progress", "* Session state: v=1 action=1 reason=0 remaining=1 blocked=0"),
    )

    @Test fun keepRunningOrDismissalDoesNotSendCommands() {
        val transport = FakeDeviceTransport()
        assertNull(RunningEventReadSupport.stopIfRequested(running, transport, false))
        assertTrue(transport.sentCommands.isEmpty())
        assertEquals(1, running.snapshot?.status?.sessionReport?.action)
    }

    @Test fun explicitStopRetainsExistingCommandReadbackAndTrace() {
        val reply = listOf("* GO 0:Classic; Stopped")
        val transport = FakeDeviceTransport(scriptedResponses = mapOf("GO 0" to reply))
        transport.connect()
        val result = assertNotNull(RunningEventReadSupport.stopIfRequested(running, transport, true))
        assertEquals(listOf("GO 0"), transport.sentCommands)
        assertEquals(listOf("GO 0"), result.commandsSent)
        assertEquals(reply, result.linesReceived)
        assertEquals(false, result.state.snapshot?.status?.eventEnabled)
        assertEquals(SerialTraceDirection.TX, result.traceEntries.first().direction)
        assertEquals(SerialTraceDirection.RX, result.traceEntries.last().direction)
    }
}
