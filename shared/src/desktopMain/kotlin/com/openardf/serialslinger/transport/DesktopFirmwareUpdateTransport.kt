package com.openardf.serialslinger.transport

import com.fazecast.jSerialComm.SerialPort
import com.openardf.serialslinger.protocol.SignalSlingerFirmwareUpdateTransport

class DesktopFirmwareUpdateTransport(
    private val portDescriptor: String,
    private val readQuietPeriodMs: Long = 120,
    private val pollIntervalMs: Long = 20,
) : SignalSlingerFirmwareUpdateTransport {
    private var serialPort: SerialPort? = null
    private val lineBuffer = DesktopSerialLineBuffer()

    override fun connect(baudRate: Int) {
        disconnect()
        lineBuffer.clear()
        val port = SerialPort.getCommPort(portDescriptor)
        configurePort(port, baudRate)
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)
        require(port.openPort()) {
            "Failed to open serial port `$portDescriptor` for update (error code ${port.lastErrorCode})."
        }
        serialPort = port
    }

    override fun reconfigureBaudRate(baudRate: Int): Boolean {
        val port = serialPort ?: return false
        lineBuffer.clear()
        return configurePort(port, baudRate)
    }

    override fun disconnect() {
        serialPort?.closePort()
        serialPort = null
        lineBuffer.clear()
    }

    override fun writeAscii(text: String) {
        writeBytes(text.encodeToByteArray())
    }

    override fun writeBytes(bytes: ByteArray) {
        val port = requireNotNull(serialPort) {
            "Serial port `$portDescriptor` is not connected for update."
        }
        val written = port.writeBytes(bytes, bytes.size)
        require(written == bytes.size) {
            "Failed to write complete update payload to `$portDescriptor`."
        }
    }

    override fun readLines(timeoutMs: Long): List<String> {
        val port = serialPort ?: return emptyList()
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastDataAt: Long? = null

        while (System.currentTimeMillis() <= deadline) {
            val availableBytes = port.bytesAvailable()
            if (availableBytes > 0) {
                val buffer = ByteArray(availableBytes)
                val bytesRead = port.readBytes(buffer, buffer.size)
                if (bytesRead > 0) {
                    lineBuffer.appendAscii(buffer.copyOf(bytesRead))
                    lastDataAt = System.currentTimeMillis()
                }
            } else if (
                lastDataAt != null &&
                (System.currentTimeMillis() - lastDataAt) >= readQuietPeriodMs &&
                lineBuffer.hasCompletedLines()
            ) {
                break
            } else {
                Thread.sleep(pollIntervalMs)
            }
        }

        return lineBuffer.drainCompletedLines()
    }

    private fun configurePort(
        port: SerialPort,
        baudRate: Int,
    ): Boolean =
        port.setComPortParameters(
            baudRate,
            8,
            SerialPort.ONE_STOP_BIT,
            SerialPort.NO_PARITY,
        )
}
