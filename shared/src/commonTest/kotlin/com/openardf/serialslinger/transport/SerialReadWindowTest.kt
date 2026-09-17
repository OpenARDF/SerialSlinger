package com.openardf.serialslinger.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerialReadWindowTest {
    @Test
    fun longHistoryReplyCanFinishBeforeTheNextCommand() {
        val window = SerialReadWindow(0, 1_000, 120)
        var lastDataAt: Long? = null
        // A full history takes more than one second at 9600 baud.
        for (now in 0L..1_500L step 20) {
            assertTrue(window.shouldRead(now, lastDataAt), "Read ended during reply at $now ms")
            lastDataAt = now
        }
        assertTrue(window.shouldRead(1_600, lastDataAt))
        assertFalse(window.shouldRead(1_621, lastDataAt))
    }

    @Test
    fun silenceAndShortRepliesKeepTheirExistingDeadline() {
        val window = SerialReadWindow(0, 1_000, 120)
        assertTrue(window.shouldRead(1_000, null))
        assertFalse(window.shouldRead(1_001, null))
        assertFalse(window.shouldRead(1_001, 100))
    }

    @Test
    fun continuousUnsolicitedOutputStillHasAHardLimit() {
        val window = SerialReadWindow(0, 1_000, 120)
        assertTrue(window.shouldRead(5_000, 4_990))
        assertFalse(window.shouldRead(5_001, 5_000))
    }

    @Test
    fun briefPollingDoesNotExtendItsBudget() {
        val window = SerialReadWindow(0, 120, 20, completionGraceMs = 0)
        assertTrue(window.shouldRead(120, 119))
        assertFalse(window.shouldRead(121, 120))
    }
}
