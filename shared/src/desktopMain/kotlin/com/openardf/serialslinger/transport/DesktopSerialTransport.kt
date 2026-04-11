package com.openardf.serialslinger.transport

import com.fazecast.jSerialComm.SerialPort

data class DesktopSerialPortInfo(
    val systemPortName: String,
    val systemPortPath: String,
    val descriptivePortName: String,
    val portDescription: String,
)

class DesktopSerialTransport(
    private val portDescriptor: String,
    private val baudRate: Int = 9_600,
    private val lineTerminator: String = "\n",
    private val wakePreamble: String = "**\r",
    private val readTimeoutMs: Long = 1_000,
    private val quietPeriodMs: Long = 120,
    private val pollIntervalMs: Long = 20,
) : DeviceTransport {
    private var serialPort: SerialPort? = null
    private val lineBuffer = DesktopSerialLineBuffer()
    private var wakePreambleSent: Boolean = false

    override fun connect() {
        if (serialPort?.isOpen == true) {
            return
        }

        val port = SerialPort.getCommPort(portDescriptor)
        port.setComPortParameters(
            baudRate,
            8,
            SerialPort.ONE_STOP_BIT,
            SerialPort.NO_PARITY,
        )
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)

        val opened = port.openPort()
        require(opened) {
            "Failed to open serial port `$portDescriptor` (error code ${port.lastErrorCode})."
        }

        serialPort = port
        wakePreambleSent = false
    }

    override fun disconnect() {
        serialPort?.closePort()
        serialPort = null
        wakePreambleSent = false
    }

    override fun sendCommands(commands: List<String>) {
        val port = requireNotNull(serialPort) {
            "Serial port `$portDescriptor` is not connected."
        }

        buildPayloads(commands).forEach { payload ->
            writePayload(port, payload)
        }
    }

    override fun readAvailableLines(): List<String> {
        val port = serialPort ?: return emptyList()
        val deadline = System.currentTimeMillis() + readTimeoutMs
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
            } else if (lastDataAt != null && (System.currentTimeMillis() - lastDataAt) >= quietPeriodMs) {
                break
            } else {
                Thread.sleep(pollIntervalMs)
            }
        }

        return lineBuffer.drainCompletedLines()
    }

    private fun ensureLineTerminated(command: String): String {
        return if (command.endsWith(lineTerminator)) {
            command
        } else {
            command + lineTerminator
        }
    }

    private fun buildPayloads(commands: List<String>): List<String> {
        if (commands.isEmpty()) {
            return emptyList()
        }

        val payloads = mutableListOf<String>()
        if (!wakePreambleSent) {
            payloads += wakePreamble
            wakePreambleSent = true
        }

        payloads += commands.map(::ensureLineTerminated)
        return payloads
    }

    private fun writePayload(port: SerialPort, payload: String) {
        val bytes = payload.encodeToByteArray()
        val bytesWritten = port.writeBytes(bytes, bytes.size)
        require(bytesWritten == bytes.size) {
            "Failed to write complete payload to `$portDescriptor`."
        }
    }

    companion object {
        internal fun payloadsForSend(
            commands: List<String>,
            wakePreambleSent: Boolean,
            lineTerminator: String = "\n",
            wakePreamble: String = "**\r",
        ): List<String> {
            if (commands.isEmpty()) {
                return emptyList()
            }

            val payloads = mutableListOf<String>()
            if (!wakePreambleSent) {
                payloads += wakePreamble
            }
            payloads += commands.map { command ->
                if (command.endsWith(lineTerminator)) {
                    command
                } else {
                    command + lineTerminator
                }
            }
            return payloads
        }
        fun listAvailablePorts(): List<DesktopSerialPortInfo> {
            return SerialPort.getCommPorts().map { port ->
                DesktopSerialPortInfo(
                    systemPortName = port.systemPortName,
                    systemPortPath = port.systemPortPath,
                    descriptivePortName = port.descriptivePortName.orEmpty(),
                    portDescription = port.portDescription.orEmpty(),
                )
            }
        }
    }
}
