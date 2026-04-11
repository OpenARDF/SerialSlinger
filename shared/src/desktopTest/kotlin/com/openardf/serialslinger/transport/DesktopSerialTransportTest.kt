package com.openardf.serialslinger.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSerialTransportTest {
    @Test
    fun prependsWakePreambleBeforeFirstCommandBatch() {
        val payloads = DesktopSerialTransport.payloadsForSend(
            commands = listOf("FOX", "FRE 1"),
            wakePreambleSent = false,
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
    fun skipsWakePreambleAfterFirstBatch() {
        val payloads = DesktopSerialTransport.payloadsForSend(
            commands = listOf("EVT"),
            wakePreambleSent = true,
        )

        assertEquals(listOf("EVT\n"), payloads)
    }
}
