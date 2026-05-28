package com.openardf.serialslinger.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.FtdiSerialDriver.FtdiSerialPort
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.openardf.serialslinger.protocol.SignalSlingerFirmwareUpdateTransport

class AndroidFirmwareUpdateTransport(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
    private val readQuietPeriodMs: Long = 120,
    private val pollIntervalMs: Long = 20,
    private val postWriteReadDelayMs: Long = 80,
    private val binaryWriteChunkBytes: Int = 64,
    private val ftdiLatencyTimerMs: Int = 8,
) : SignalSlingerFirmwareUpdateTransport {
    private var connection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null
    private var connectedBaudRate: Int? = null
    private val lineBuffer = AndroidSerialLineBuffer()
    private var lastWriteAtMs: Long? = null
    private val requestedDeviceName = usbDevice.deviceName

    override fun connect(baudRate: Int) {
        disconnect()
        val currentDevice = resolveCurrentDevice()
        require(usbManager.hasPermission(currentDevice)) {
            "USB permission has not been granted for ${currentDevice.deviceName}."
        }
        val driver = requireNotNull(
            UsbSerialProber.getDefaultProber().probeDevice(currentDevice),
        ) {
            "No supported Android USB serial driver was found for ${currentDevice.deviceName}."
        }
        val usbConnection = requireNotNull(usbManager.openDevice(driver.device)) {
            "Android could not open USB device ${currentDevice.deviceName}. Permission may have been revoked."
        }
        val port = requireNotNull(driver.ports.firstOrNull()) {
            "USB device ${currentDevice.deviceName} does not expose a serial port."
        }
        try {
            port.open(usbConnection)
            port.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            configureFtdiLatencyTimer(port)
            connection = usbConnection
            serialPort = port
            connectedBaudRate = baudRate
            lineBuffer.clear()
            lastWriteAtMs = null
        } catch (error: Throwable) {
            runCatching { port.close() }
            usbConnection.close()
            throw error
        }
    }

    override fun disconnect() {
        runCatching { serialPort?.close() }
        connection?.close()
        serialPort = null
        connection = null
        lineBuffer.clear()
        connectedBaudRate = null
        lastWriteAtMs = null
    }

    override fun reconfigureBaudRate(baudRate: Int): Boolean {
        val port = serialPort ?: return false
        return try {
            port.setParameters(
                baudRate,
                8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            connectedBaudRate = baudRate
            lineBuffer.clear()
            lastWriteAtMs = null
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun writeAscii(text: String) {
        writeBytes(text.encodeToByteArray())
    }

    override fun writeBytes(bytes: ByteArray) {
        val allowReconnectRetry = bytes.size <= SmallCommandRetryMaxBytes
        try {
            writeConnectedBytes(bytes)
        } catch (failure: Throwable) {
            val baudRate = connectedBaudRate
            disconnect()
            if (!allowReconnectRetry) {
                throw usbInterruptedFailure(failure)
            }
            if (baudRate == null) {
                throw usbInterruptedFailure(failure)
            }
            Thread.sleep(UsbReconnectSettleMs)
            try {
                connect(baudRate)
                writeConnectedBytes(bytes)
            } catch (retryFailure: Throwable) {
                disconnect()
                throw usbInterruptedFailure(retryFailure)
            }
        }
    }

    private fun writeConnectedBytes(bytes: ByteArray) {
        val port = requireNotNull(serialPort) {
            "USB serial device $requestedDeviceName is not connected for update."
        }
        var offset = 0
        while (offset < bytes.size) {
            val nextOffset = (offset + binaryWriteChunkBytes).coerceAtMost(bytes.size)
            port.write(bytes.copyOfRange(offset, nextOffset), 2_000)
            offset = nextOffset
        }
        lastWriteAtMs = System.currentTimeMillis()
    }

    override fun readLines(timeoutMs: Long): List<String> {
        val port = serialPort ?: return emptyList()
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastDataAt: Long? = null
        val buffer = ByteArray(256)
        sleepAfterRecentWrite()

        while (System.currentTimeMillis() <= deadline) {
            val bytesRead = port.read(buffer, pollIntervalMs.toInt())
            if (bytesRead > 0) {
                lineBuffer.appendAscii(buffer, bytesRead)
                lastDataAt = System.currentTimeMillis()
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

    override fun readBytes(timeoutMs: Long): ByteArray {
        val port = serialPort ?: return ByteArray(0)
        val deadline = System.currentTimeMillis() + timeoutMs
        val out = mutableListOf<Byte>()
        val buffer = ByteArray(256)
        sleepAfterRecentWrite()

        while (System.currentTimeMillis() <= deadline) {
            val bytesRead = port.read(buffer, pollIntervalMs.toInt())
            if (bytesRead > 0) {
                out += buffer.copyOf(bytesRead).toList()
            } else if (out.isNotEmpty()) {
                break
            } else {
                Thread.sleep(pollIntervalMs)
            }
        }

        return out.toByteArray()
    }

    private fun resolveCurrentDevice(): UsbDevice {
        val devices = usbManager.deviceList.values.toList()
        return devices.firstOrNull { it.deviceName == requestedDeviceName }
            ?: devices.firstOrNull {
                it.vendorId == usbDevice.vendorId &&
                    it.productId == usbDevice.productId &&
                    UsbSerialProber.getDefaultProber().probeDevice(it) != null
            }
            ?: error("SignalSlinger USB serial adapter is no longer connected.")
    }

    private fun usbInterruptedFailure(cause: Throwable): IllegalStateException {
        val detail = cause.message?.takeIf { it.isNotBlank() } ?: cause.toString()
        return IllegalStateException(
            "USB connection was interrupted while updating SignalSlinger. Reconnect SignalSlinger and try Recovery Update. $detail",
            cause,
        )
    }

    private fun sleepAfterRecentWrite() {
        val lastWrite = lastWriteAtMs ?: return
        val elapsed = System.currentTimeMillis() - lastWrite
        if (elapsed in 0 until postWriteReadDelayMs) {
            Thread.sleep(postWriteReadDelayMs - elapsed)
        }
    }

    private fun configureFtdiLatencyTimer(port: UsbSerialPort) {
        if (port is FtdiSerialPort) {
            runCatching { port.setLatencyTimer(ftdiLatencyTimerMs) }
        }
    }

    private companion object {
        private const val SmallCommandRetryMaxBytes = 8
        private const val UsbReconnectSettleMs = 250L
    }
}
