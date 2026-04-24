package com.openardf.serialslinger.transport

internal class DesktopSerialLineBuffer {
    private val pendingText = StringBuilder()
    private val completeLines = mutableListOf<String>()
    private var lastByteWasCarriageReturn = false

    fun appendAscii(bytes: ByteArray) {
        for (byte in bytes) {
            val ch = byte.toInt().toChar()
            when (ch) {
                '\r' -> {
                    flushPendingLine()
                    lastByteWasCarriageReturn = true
                }
                '\n' -> {
                    if (!lastByteWasCarriageReturn) {
                        flushPendingLine()
                    }
                    lastByteWasCarriageReturn = false
                }
                else -> {
                    pendingText.append(ch)
                    lastByteWasCarriageReturn = false
                }
            }
        }
    }

    fun drainCompletedLines(): List<String> {
        val lines = completeLines.toList()
        completeLines.clear()
        return lines
    }

    fun clear() {
        pendingText.clear()
        completeLines.clear()
        lastByteWasCarriageReturn = false
    }

    private fun flushPendingLine() {
        completeLines += pendingText.toString()
        pendingText.clear()
    }
}
