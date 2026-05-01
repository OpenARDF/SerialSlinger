package com.openardf.serialslinger.transport

import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

data class AndroidUsbDeviceDescriptor(
    val deviceName: String,
    val productName: String?,
    val manufacturerName: String?,
    val vendorId: Int,
    val productId: Int,
    val interfaceCount: Int,
    val likelySerial: Boolean,
    val supportedSerialDriver: Boolean,
    val hasPermission: Boolean,
)

/**
 * First Android transport slice: discover host-visible USB devices and establish the shape of
 * the transport boundary. Serial I/O will be implemented in a later step.
 */
class AndroidUsbTransport(
    private val usbManager: UsbManager,
    private val usbDevice: UsbDevice,
    private val baudRate: Int = 9_600,
    private val lineTerminator: String = "\n",
    private val wakePreamble: String = "**\r",
    private val wakeSettleMs: Long = 80,
    private val wakeAfterIdleMs: Long = 1_500,
    private val readTimeoutMs: Int = 1_000,
    private val quietPeriodMs: Long = 120,
    private val pollIntervalMs: Long = 20,
) : DeviceTransport {
    private var connection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null
    private val lineBuffer = AndroidSerialLineBuffer()
    private var lastWriteAtMs: Long? = null

    override fun connect() {
        if (serialPort != null) {
            return
        }

        require(usbManager.hasPermission(usbDevice)) {
            "USB permission has not been granted for ${usbDevice.deviceName}."
        }

        val driver = requireNotNull(findDriver(usbManager, usbDevice)) {
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
            serialPort = port
            connection = usbConnection
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

    override fun sendCommands(commands: List<String>) {
        val port = requireNotNull(serialPort) {
            "USB serial device ${usbDevice.deviceName} is not connected."
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
        val port = serialPort ?: return emptyList()
        val deadline = System.currentTimeMillis() + readTimeoutMs
        var lastDataAt: Long? = null
        val buffer = ByteArray(256)

        while (System.currentTimeMillis() <= deadline) {
            val bytesRead =
                try {
                    port.read(buffer, pollIntervalMs.toInt())
                } catch (error: Throwable) {
                    Log.w(logTag, "USB serial read failed for ${usbDevice.deviceName}.", error)
                    0
                }

            if (bytesRead > 0) {
                lineBuffer.appendAscii(buffer, bytesRead)
                lastDataAt = System.currentTimeMillis()
            } else if (lastDataAt != null && (System.currentTimeMillis() - lastDataAt) >= quietPeriodMs) {
                break
            } else {
                Thread.sleep(pollIntervalMs)
            }
        }

        return lineBuffer.drainCompletedLines()
    }

    companion object {
        private const val logTag = "SerialSlingerUsb"

        fun requestPermission(
            context: Context,
            usbManager: UsbManager,
            usbDevice: UsbDevice,
            action: String,
        ) {
            val intent = android.content.Intent(action).setPackage(context.packageName)
            val flags =
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
            val pendingIntent = PendingIntent.getBroadcast(context, usbDevice.deviceId, intent, flags)
            usbManager.requestPermission(usbDevice, pendingIntent)
        }

        fun connectedDevices(usbManager: UsbManager): List<AndroidUsbDeviceDescriptor> {
            return usbManager.deviceList.values
                .sortedBy { it.deviceName }
                .map { device ->
                    AndroidUsbDeviceDescriptor(
                        deviceName = device.deviceName,
                        productName = device.productName,
                        manufacturerName = device.manufacturerName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        interfaceCount = device.interfaceCount,
                        likelySerial = looksLikeSerialDevice(device),
                        supportedSerialDriver = findDriver(usbManager, device) != null,
                        hasPermission = usbManager.hasPermission(device),
                    )
                }
        }

        fun hasSupportedDriver(usbManager: UsbManager, usbDevice: UsbDevice): Boolean {
            return findDriver(usbManager, usbDevice) != null
        }

        private fun findDriver(usbManager: UsbManager, usbDevice: UsbDevice): UsbSerialDriver? {
            return UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
                .firstOrNull { it.device.deviceId == usbDevice.deviceId }
        }

        private fun shouldSendWakePreamble(
            lastWriteAtMs: Long?,
            nowMs: Long,
            wakeAfterIdleMs: Long = 1_500,
        ): Boolean {
            return lastWriteAtMs == null || (nowMs - lastWriteAtMs) >= wakeAfterIdleMs
        }

        private fun ensureLineTerminated(command: String, lineTerminator: String = "\n"): String {
            return if (command.endsWith(lineTerminator)) command else command + lineTerminator
        }

        private fun writePayload(port: UsbSerialPort, payload: String) {
            val bytes = payload.encodeToByteArray()
            port.write(bytes, 1_000)
        }

        private fun looksLikeSerialDevice(device: UsbDevice): Boolean {
            if (device.interfaceCount == 0) {
                return false
            }

            return (0 until device.interfaceCount).any { index ->
                val usbInterface = device.getInterface(index)
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                    usbInterface.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                    usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
            }
        }
    }
}
