package com.openardf.serialslinger.session

import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareFaultHistoryTest {
    @Test
    fun parsesFaultHistoryAndClearsItOnEachHeader() {
        var state = DeviceSessionWorkflow.connected()
        state = DeviceSessionWorkflow.ingestReportLines(
            state,
            listOf(
                "* HWF v=1 count=2 capacity=8",
                "* HWF v=1 code=1 at=0 flags=0",
                "* HWF v=1 code=2 at=1788527000 flags=1",
            ),
        )
        val faults = assertNotNull(state.snapshot).status.hardwareFaults
        assertEquals(listOf(1, 2), faults.map { it.code })
        assertNull(faults.first().detectedAtCompact)
        assertEquals("260904130320", faults.last().detectedAtCompact)
        assertEquals(
            "Clock hardware did not respond — first detected time unavailable\n" +
                "Transmit hardware did not respond — first detected 2026-09-04 13:03:20",
            com.openardf.serialslinger.model.HardwareFaultPresentation.summary(faults),
        )

        state = DeviceSessionWorkflow.ingestReportLines(state, listOf("* HWF v=1 count=0 capacity=8"))
        assertTrue(assertNotNull(state.snapshot).status.hardwareFaults.isEmpty())
    }

    @Test
    fun rejectsMalformedFaultReports() {
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* HWF v=2 count=0 capacity=8"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* HWF v=1 count=9 capacity=8"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* HWF v=1 code=0 at=0 flags=0"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* HWF v=1 code=9 at=0 flags=0"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* HWF v=1 code=2 at=-1 flags=1"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* HWF v=1 code=2 at=1788527000 flags=2"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* HWF v=1 code=2 at=0 flags=1"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("* HWF v=1 code=2 at=1788527000 flags=0"))
    }

    @Test
    fun faultHistoryFollowsTheReportingDeviceIdentity() {
        val firstUid = "42348279800077200110014200000000"
        val secondUid = "42348279800068200107012700000000"
        var state = DeviceSessionWorkflow.connected()
        state = DeviceSessionWorkflow.ingestReportLines(
            state,
            listOf(
                "* INF uid=$firstUid",
                "* HWF v=1 count=1 capacity=8",
                "* HWF v=1 code=2 at=1788527000 flags=1",
            ),
        )
        assertEquals(firstUid, assertNotNull(state.snapshot).status.hardwareFaultDeviceUniqueId)
        assertEquals(1, assertNotNull(state.snapshot).status.hardwareFaults.size)

        state = DeviceSessionWorkflow.ingestReportLines(state, listOf("* INF uid=$firstUid"))
        assertEquals(1, assertNotNull(state.snapshot).status.hardwareFaults.size)

        state = DeviceSessionWorkflow.ingestReportLines(state, listOf("* INF uid=$secondUid"))
        val switchedStatus = assertNotNull(state.snapshot).status
        assertTrue(switchedStatus.hardwareFaults.isEmpty())
        assertEquals(secondUid, switchedStatus.hardwareFaultDeviceUniqueId)
        assertFalse(switchedStatus.sessionHistoryDeviceUniqueId == firstUid)
    }
}
