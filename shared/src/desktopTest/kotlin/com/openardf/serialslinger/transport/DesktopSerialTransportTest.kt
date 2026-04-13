package com.openardf.serialslinger.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSerialTransportTest {
    @Test
    fun prependsWakePreambleBeforeFirstCommandBatch() {
        val payloads = DesktopSerialTransport.payloadsForSend(
            commands = listOf("FOX", "FRE 1"),
            shouldWake = true,
        )

        assertEquals(
            listOf(
                "**\r",
                "FOX\n",
                "FRE 1\n",
            ),
            payloads,
        )
    }

    @Test
    fun prependsWakePreambleForLaterCommandBatchesToo() {
        val payloads = DesktopSerialTransport.payloadsForSend(
            commands = listOf("EVT"),
            shouldWake = true,
        )

        assertEquals(listOf("**\r", "EVT\n"), payloads)
    }

    @Test
    fun skipsWakePreambleWhenRecentTrafficShouldKeepDeviceAwake() {
        val payloads = DesktopSerialTransport.payloadsForSend(
            commands = listOf("BAT"),
            shouldWake = false,
        )

        assertEquals(listOf("BAT\n"), payloads)
    }

    @Test
    fun requestsWakePreambleAfterLongIdleGap() {
        assertEquals(true, DesktopSerialTransport.shouldSendWakePreamble(lastWriteAtMs = null, nowMs = 10_000))
        assertEquals(true, DesktopSerialTransport.shouldSendWakePreamble(lastWriteAtMs = 1_000, nowMs = 3_000, wakeAfterIdleMs = 1_500))
        assertEquals(false, DesktopSerialTransport.shouldSendWakePreamble(lastWriteAtMs = 1_000, nowMs = 2_000, wakeAfterIdleMs = 1_500))
    }

    @Test
    fun retriesTransientOpenPortErrorCodeTwoOnlyWhileAttemptsRemain() {
        assertTrue(DesktopSerialTransport.shouldRetryOpenPort(errorCode = 2, attemptIndex = 0, maxAttempts = 4))
        assertTrue(DesktopSerialTransport.shouldRetryOpenPort(errorCode = 2, attemptIndex = 2, maxAttempts = 4))
        assertFalse(DesktopSerialTransport.shouldRetryOpenPort(errorCode = 2, attemptIndex = 3, maxAttempts = 4))
        assertFalse(DesktopSerialTransport.shouldRetryOpenPort(errorCode = 5, attemptIndex = 0, maxAttempts = 4))
    }
}
