package com.openardf.serialslinger.transport

import com.fazecast.jSerialComm.SerialPort

enum class DesktopSerialStopBits(internal val serialCommValue: Int) {
    ONE(SerialPort.ONE_STOP_BIT),
    TWO(SerialPort.TWO_STOP_BITS),
}

internal fun arduconStopBitsForBaudRate(appBaud: Int): (Int) -> DesktopSerialStopBits {
    return { baudRate ->
        if (baudRate == appBaud) {
            DesktopSerialStopBits.TWO
        } else {
            DesktopSerialStopBits.ONE
        }
    }
}
