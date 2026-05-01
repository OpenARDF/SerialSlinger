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
    private val wakeSettleMs: Long = 80,
    private val wakeAfterIdleMs: Long = 1_500,
    private val readTimeoutMs: Long = 1_000,
    private val quietPeriodMs: Long = 120,
    private val pollIntervalMs: Long = 20,
) : DeviceTransport {
    private var serialPort: SerialPort? = null
    private val lineBuffer = DesktopSerialLineBuffer()
    private var lastWriteAtMs: Long? = null

    override fun connect() {
        if (serialPort?.isOpen == true) {
            return
        }

        lineBuffer.clear()

        val port = SerialPort.getCommPort(portDescriptor)
        port.setComPortParameters(
            baudRate,
            8,
            SerialPort.ONE_STOP_BIT,
            SerialPort.NO_PARITY,
        )
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)

        val opened = openPortWithRetry(port)
        require(opened) {
            "Failed to open serial port `$portDescriptor` (error code ${port.lastErrorCode})."
        }

        serialPort = port
        lastWriteAtMs = null
    }

    override fun disconnect() {
        serialPort?.closePort()
        serialPort = null
        lastWriteAtMs = null
        lineBuffer.clear()
    }

    override fun sendCommands(commands: List<String>) {
        val port = requireNotNull(serialPort) {
            "Serial port `$portDescriptor` is not connected."
        }

        val now = System.currentTimeMillis()
        if (shouldSendWakePreamble(lastWriteAtMs, now)) {
            writePayload(port, wakePreamble)
            Thread.sleep(wakeSettleMs)
        }

        commands.map(::ensureLineTerminated).forEach { payload ->
            writePayload(port, payload)
        }
        lastWriteAtMs = System.currentTimeMillis()
    }

    override fun readAvailableLines(): List<String> {
        return readAvailableLinesFor(readTimeoutMs)
    }

    fun readAvailableLinesBriefly(maxDurationMs: Long = 120): List<String> {
        return readAvailableLinesFor(maxDurationMs)
    }

    private fun readAvailableLinesFor(maxDurationMs: Long): List<String> {
        val port = serialPort ?: return emptyList()
        val deadline = System.currentTimeMillis() + maxDurationMs
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

    private fun writePayload(port: SerialPort, payload: String) {
        val bytes = payload.encodeToByteArray()
        val bytesWritten = port.writeBytes(bytes, bytes.size)
        require(bytesWritten == bytes.size) {
            "Failed to write complete payload to `$portDescriptor`."
        }
    }

    private fun openPortWithRetry(port: SerialPort): Boolean {
        repeat(connectOpenRetryCount) { attempt ->
            if (port.openPort()) {
                return true
            }
            if (!shouldRetryOpenPort(port.lastErrorCode, attempt, connectOpenRetryCount)) {
                return false
            }
            Thread.sleep(connectOpenRetryDelayMs)
        }
        return false
    }

    companion object {
        private const val connectOpenRetryCount = 4
        private const val connectOpenRetryDelayMs = 150L

        internal fun shouldSendWakePreamble(
            lastWriteAtMs: Long?,
            nowMs: Long,
            wakeAfterIdleMs: Long = 1_500,
        ): Boolean {
            return lastWriteAtMs == null || (nowMs - lastWriteAtMs) >= wakeAfterIdleMs
        }

        internal fun shouldRetryOpenPort(
            errorCode: Int,
            attemptIndex: Int,
            maxAttempts: Int = connectOpenRetryCount,
        ): Boolean {
            val hasAttemptsRemaining = attemptIndex < maxAttempts - 1
            return hasAttemptsRemaining && errorCode == 2
        }

        internal fun payloadsForSend(
            commands: List<String>,
            lineTerminator: String = "\n",
            wakePreamble: String = "**\r",
            shouldWake: Boolean = true,
        ): List<String> {
            if (commands.isEmpty()) {
                return emptyList()
            }

            val payloads = mutableListOf<String>()
            if (shouldWake) {
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
