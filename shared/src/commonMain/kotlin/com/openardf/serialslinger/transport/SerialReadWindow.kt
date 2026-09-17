package com.openardf.serialslinger.transport

/** Let an in-flight reply finish without waiting indefinitely for continuous output. */
internal class SerialReadWindow(
    startedAtMs: Long,
    timeoutMs: Long,
    private val quietPeriodMs: Long,
    completionGraceMs: Long = 4_000,
) {
    private val deadlineMs = startedAtMs + timeoutMs
    private val completionDeadlineMs = deadlineMs + completionGraceMs

    fun shouldRead(nowMs: Long, lastDataAtMs: Long?): Boolean =
        nowMs <= deadlineMs ||
            (lastDataAtMs != null && nowMs <= completionDeadlineMs &&
                nowMs - lastDataAtMs <= quietPeriodMs)
}
