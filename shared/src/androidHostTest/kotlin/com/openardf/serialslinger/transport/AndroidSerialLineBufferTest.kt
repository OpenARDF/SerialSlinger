package com.openardf.serialslinger.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidSerialLineBufferTest {
    @Test
    fun accumulatesCrLfDelimitedLinesAcrossChunks() {
        val buffer = AndroidSerialLineBuffer()

        buffer.appendAscii("* ID: W1".encodeToByteArray(), "* ID: W1".length)
        assertEquals(emptyList(), buffer.drainCompletedLines())

        buffer.appendAscii("FOX\r\n* EV".encodeToByteArray(), "FOX\r\n* EV".length)
        assertEquals(listOf("* ID: W1FOX"), buffer.drainCompletedLines())

        buffer.appendAscii("T:Classic\n".encodeToByteArray(), "T:Classic\n".length)
        assertEquals(listOf("* EVT:Classic"), buffer.drainCompletedLines())
    }

    @Test
    fun flushesCarriageReturnDelimitedLinesWithoutAddingBlankLineForFollowingLf() {
        val buffer = AndroidSerialLineBuffer()

        buffer.appendAscii("* PAT:MOE\r".encodeToByteArray(), "* PAT:MOE\r".length)
        assertEquals(listOf("* PAT:MOE"), buffer.drainCompletedLines())

        val timeLine = "* Time:Fri 10-apr-2026 14:22:33\r\n"
        buffer.appendAscii(timeLine.encodeToByteArray(), timeLine.length)
        assertEquals(listOf("* Time:Fri 10-apr-2026 14:22:33"), buffer.drainCompletedLines())
    }

    @Test
    fun clearDropsAnyPartialTrailingText() {
        val buffer = AndroidSerialLineBuffer()

        val partial = "* PAT:MOE"
        buffer.appendAscii(partial.encodeToByteArray(), partial.length)
        buffer.clear()

        val nextLine = "* ID:W1FOX\n"
        buffer.appendAscii(nextLine.encodeToByteArray(), nextLine.length)
        assertEquals(listOf("* ID:W1FOX"), buffer.drainCompletedLines())
    }
}
