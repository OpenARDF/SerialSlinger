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
}
