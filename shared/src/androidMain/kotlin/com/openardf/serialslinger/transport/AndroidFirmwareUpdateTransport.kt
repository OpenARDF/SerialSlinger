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
    private val lineBuffer = AndroidSerialLineBuffer()
    private var lastWriteAtMs: Long? = null

    override fun connect(baudRate: Int) {
        disconnect()
        require(usbManager.hasPermission(usbDevice)) {
            "USB permission has not been granted for ${usbDevice.deviceName}."
        }
        val driver = requireNotNull(
            UsbSerialProber.getDefaultProber().probeDevice(usbDevice),
        ) {
            "No supported Android USB serial driver was found for ${usbDevice.deviceName}."
        }
        val usbConnection = requireNotNull(usbManager.openDevice(driver.device)) {
            "Android could not open USB device ${usbDevice.deviceName}. Permission may have been revoked."
        }
        val port = requireNotNull(driver.ports.firstOrNull()) {
            "USB device ${usbDevice.deviceName} does not expose a serial port."
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
        lastWriteAtMs = null
    }

    override fun writeAscii(text: String) {
        writeBytes(text.encodeToByteArray())
    }

    override fun writeBytes(bytes: ByteArray) {
        val port = requireNotNull(serialPort) {
            "USB serial device ${usbDevice.deviceName} is not connected for update."
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
}
