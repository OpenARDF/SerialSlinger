package com.openardf.serialslinger.transport

internal class AndroidSerialLineBuffer {
    private val pendingText = StringBuilder()
    private val completeLines = mutableListOf<String>()
    private var lastByteWasCarriageReturn = false

    fun appendAscii(bytes: ByteArray, length: Int) {
        for (index in 0 until length) {
            val ch = bytes[index].toInt().toChar()
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

    fun pendingFragment(): String = pendingText.toString()

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
