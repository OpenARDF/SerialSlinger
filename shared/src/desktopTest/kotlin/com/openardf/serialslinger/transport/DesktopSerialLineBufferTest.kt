package com.openardf.serialslinger.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSerialLineBufferTest {
    @Test
    fun accumulatesCrLfDelimitedLinesAcrossChunks() {
        val buffer = DesktopSerialLineBuffer()

        buffer.appendAscii("* ID: W1".encodeToByteArray())
        assertEquals(emptyList(), buffer.drainCompletedLines())

        buffer.appendAscii("FOX\r\n* EV".encodeToByteArray())
        assertEquals(listOf("* ID: W1FOX"), buffer.drainCompletedLines())

        buffer.appendAscii("T:Classic\n".encodeToByteArray())
        assertEquals(listOf("* EVT:Classic"), buffer.drainCompletedLines())
    }

    @Test
    fun keepsPartialTrailingTextUntilNewlineArrives() {
        val buffer = DesktopSerialLineBuffer()

        buffer.appendAscii("* PAT:TEST".encodeToByteArray())
        assertEquals(emptyList(), buffer.drainCompletedLines())

        buffer.appendAscii("\n".encodeToByteArray())
        assertEquals(listOf("* PAT:TEST"), buffer.drainCompletedLines())
    }

    @Test
    fun flushesCarriageReturnDelimitedLinesWithoutAddingBlankLineForFollowingLf() {
        val buffer = DesktopSerialLineBuffer()

        buffer.appendAscii("* PAT:MOE\r".encodeToByteArray())
        assertEquals(listOf("* PAT:MOE"), buffer.drainCompletedLines())

        buffer.appendAscii("* Time:Fri 10-apr-2026 14:22:33\r\n".encodeToByteArray())
        assertEquals(listOf("* Time:Fri 10-apr-2026 14:22:33"), buffer.drainCompletedLines())
    }

    @Test
    fun ignoresNulPaddingWithinSerialChunks() {
        val buffer = DesktopSerialLineBuffer()

        buffer.appendAscii(byteArrayOf(0, 0) + "VER".encodeToByteArray() + byteArrayOf(0, 0) + "\n".encodeToByteArray())

        assertEquals(listOf("VER"), buffer.drainCompletedLines())
    }

    @Test
    fun clearDropsAnyPartialTrailingText() {
        val buffer = DesktopSerialLineBuffer()

        buffer.appendAscii("* PAT:MOE".encodeToByteArray())
        buffer.clear()

        buffer.appendAscii("* ID:W1FOX\n".encodeToByteArray())
        assertEquals(listOf("* ID:W1FOX"), buffer.drainCompletedLines())
    }
}
