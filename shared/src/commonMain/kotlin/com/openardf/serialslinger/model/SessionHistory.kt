package com.openardf.serialslinger.model

/** Values are the version 1 SignalSlinger EVT protocol, independent of firmware version. */
data class SessionReport(val action: Int, val reason: Int, val remaining: Int, val blocked: Boolean)

/** Epoch fields encode the transmitter's wall clock, not UTC instants. */
data class SessionHistoryRecord(
    val sequence: Long,
    val scheduleEpoch: Long,
    val startEpoch: Long,
    val finishEpoch: Long,
    val timestampCompact: String?,
    val action: Int,
    val reason: Int,
    val flags: Int,
    val temperatureC: Double?,
    val thresholdC: Int,
)

object SessionHistorySupport {
    fun reason(reason: Int): String = when (reason) {
        1 -> "scheduled finish"
        2 -> "overheating"
        3 -> "stopped by user"
        4 -> "settings changed"
        5 -> "power loss or reset"
        6 -> "clock changed"
        7 -> "temperature reading unavailable"
        8 -> "transmissions disabled"
        9 -> "test timer finished"
        else -> "reason unavailable"
    }
    fun action(action: Int): String = when (action) {
        1 -> "Started"
        2 -> "Paused"
        3 -> "Resumed"
        4 -> "Completed"
        5 -> "Finished with interruptions"
        6 -> "Interrupted"
        7 -> "Expired"
        else -> "Waiting"
    }
}
