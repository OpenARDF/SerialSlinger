package com.openardf.serialslinger.model

import com.openardf.serialslinger.session.DeviceSessionWorkflow
import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import java.time.format.DateTimeFormatter

/** Presents retained transitions without inventing missing starts or successful transmissions. */
object SessionHistoryPresentation {
    private fun time(record: SessionHistoryRecord): String = record.timestampCompact
        ?.let(JvmTimeSupport::parseCompactTimestamp)
        ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) ?: "time unavailable"

    private fun reason(record: SessionHistoryRecord): String = when (record.reason) {
        1 -> "finish time reached"
        3 -> "stop command received"
        else -> SessionHistorySupport.reason(record.reason)
    }

    private fun runs(history: List<SessionHistoryRecord>): List<List<SessionHistoryRecord>> {
        val result = mutableListOf<MutableList<SessionHistoryRecord>>()
        history.forEach { record ->
            val previous = result.lastOrNull()?.lastOrNull()
            if (previous == null || record.action == 1 ||
                previous.scheduleEpoch != record.scheduleEpoch || previous.startEpoch != record.startEpoch ||
                previous.finishEpoch != record.finishEpoch || previous.action in listOf(4, 5, 6, 7)) {
                result.add(mutableListOf(record))
            } else result.last().add(record)
        }
        return result
    }

    fun summary(history: List<SessionHistoryRecord>): String {
        if (history.isEmpty()) return "No run history was returned by this transmitter."
        return buildString {
            runs(history).asReversed().forEachIndexed { index, run ->
                val first = run.first()
                val last = run.last()
                val kind = if (first.flags and 4 != 0) "Scheduled run" else "Manual run or test"
                appendLine("${if (index == 0) "Latest" else "Earlier"}: $kind")
                appendLine(if (first.action == 1) "Started ${time(first)}" else "Start is not in the retained history.")
                val outcome = when (last.action) {
                    1, 3 -> "Last recorded state: ${SessionHistorySupport.action(last.action).lowercase()}; no end recorded"
                    else -> SessionHistorySupport.action(last.action)
                }
                append("$outcome at ${time(last)}")
                if (last.reason != 0) append(" — ${reason(last)}")
                if (last.reason == 2) last.temperatureC?.let {
                    append(" ($it°C; shutdown threshold ${last.thresholdC}°C)")
                }
                if (run.any { it.action == 2 } && last.action in listOf(4, 5)) append("; includes a pause")
                appendLine(".")
                appendLine()
            }
            if (history.any { it.flags and 128 != 0 }) {
                appendLine("Earlier history is unavailable; older records have been replaced or were not retained.")
            }
            append("Times use the transmitter clock. A stop command may come from SerialSlinger or another controller.")
        }.trim()
    }

    fun details(history: List<SessionHistoryRecord>): String = buildString {
        appendLine(summary(history))
        if (history.isNotEmpty()) {
            appendLine("\nRecorded transitions (oldest first):")
            history.forEach { record ->
                append("${time(record)}: ${SessionHistorySupport.action(record.action)}")
                if (record.reason != 0) append(" — ${reason(record)}")
                record.temperatureC?.let { append("; temperature $it°C; shutdown threshold ${record.thresholdC}°C") }
                appendLine()
                appendLine("  Record ${record.sequence}; schedule=${record.scheduleEpoch}; start=${record.startEpoch}; finish=${record.finishEpoch}; flags=${record.flags}")
            }
        }
    }.trim()

    /** Derive summaries only from this log section's readback, never another device's cached state. */
    fun logSummary(receivedLines: List<String>): String? {
        val lines = receivedLines.map(String::trim)
        val headerIndex = lines.indexOfLast { it.startsWith("* Session history:") }
        if (headerIndex < 0) return null
        val prefix = "Device run history (captured readback):\n"
        val header = lines[headerIndex]
        if (SignalSlingerProtocolCodec.parseReportLine(header)?.deviceStatusPatch?.clearSessionHistory != true) {
            return prefix + "History format could not be interpreted. Original records are retained above."
        }
        val state = DeviceSessionWorkflow.ingestReportLines(DeviceSessionWorkflow.connected(), lines.drop(headerIndex))
        val history = state.snapshot?.status?.sessionHistory.orEmpty()
        val expectedCount = Regex("\\bcount=(\\d+)").find(header)?.groupValues?.get(1)?.toIntOrNull()
        if (expectedCount != null && history.size != expectedCount) {
            return prefix + "Incomplete history readback: received ${history.size} of $expectedCount records.\n" +
                if (history.isEmpty()) "No readable transitions were captured. Original records are retained above." else summary(history)
        }
        return prefix + summary(history)
    }
}
