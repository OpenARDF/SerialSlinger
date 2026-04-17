package com.openardf.serialslinger.transport

internal class AndroidSerialLineBuffer {
    private val pendingText = StringBuilder()
    private val completeLines = mutableListOf<String>()

    fun appendAscii(bytes: ByteArray, length: Int) {
        for (index in 0 until length) {
            val ch = bytes[index].toInt().toChar()
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
