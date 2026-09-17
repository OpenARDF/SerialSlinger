package com.openardf.serialslinger.session

import com.openardf.serialslinger.platform.platformCurrentTimeMillis
import com.openardf.serialslinger.transport.DeviceTransport

object RunningEventReadSupport {
    /** Dismissing a read notice must not authorize a write to the transmitter. */
    fun stopIfRequested(
        state: DeviceSessionState,
        transport: DeviceTransport,
        stopRequested: Boolean,
    ): DeviceLoadInterventionResult? {
        if (!stopRequested) return null
        val sentAt = platformCurrentTimeMillis()
        transport.sendCommands(listOf("GO 0"))
        val lines = transport.readAvailableLines()
        val receivedAt = platformCurrentTimeMillis()
        return DeviceLoadInterventionResult(
            state = DeviceSessionWorkflow.ingestReportLines(state, lines),
            commandsSent = listOf("GO 0"),
            linesReceived = lines,
            traceEntries = listOf(SerialTraceEntry(sentAt, SerialTraceDirection.TX, "GO 0")) +
                lines.map { SerialTraceEntry(receivedAt, SerialTraceDirection.RX, it) },
        )
    }
}
