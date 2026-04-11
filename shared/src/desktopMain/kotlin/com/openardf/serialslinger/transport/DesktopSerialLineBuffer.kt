package com.openardf.serialslinger.transport

internal class DesktopSerialLineBuffer {
    private val pendingText = StringBuilder()
    private val completeLines = mutableListOf<String>()

    fun appendAscii(bytes: ByteArray) {
        for (byte in bytes) {
            val ch = byte.toInt().toChar()
            when (ch) {
                '\n' -> {
                    completeLines += pendingText.toString().trimEnd('\r')
                    pendingText.clear()
                }
                else -> pendingText.append(ch)
            }
        }
    }

    fun drainCompletedLines(): List<String> {
        val lines = completeLines.toList()
        completeLines.clear()
        return lines
    }
}
