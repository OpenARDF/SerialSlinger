package com.openardf.serialslinger.transport

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.io.RandomAccessFile

class AndroidDirectSerialTransport(
    private val path: String,
    private val lineTerminator: String = "\n",
    private val wakePreamble: String = "**\r",
    private val wakeSettleMs: Long = 80,
    private val wakeAfterIdleMs: Long = 1_500,
    private val readTimeoutMs: Int = 1_000,
    private val quietPeriodMs: Long = 120,
    private val pollIntervalMs: Long = 20,
) : DeviceTransport {
    private var serialFile: RandomAccessFile? = null
    private var serialFd: FileDescriptor? = null
    private val lineBuffer = AndroidSerialLineBuffer()
    private var lastWriteAtMs: Long? = null

    override fun connect() {
        if (serialFd != null) {
            return
        }

        try {
            val file = RandomAccessFile(path, "rw")
            serialFile = file
            serialFd = file.fd
            Os.fcntlInt(requireNotNull(serialFd), OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            lastWriteAtMs = null
        } catch (error: Throwable) {
            disconnect()
            val message = error.message.orEmpty()
            if ("EACCES" in message || "Permission denied" in message) {
                throw IllegalStateException(
                    "Android denied access to $path. This emulator image likely blocks app access to serial_device; use a rootable Google APIs image or adjusted SELinux policy.",
                    error,
                )
            }
            throw error
        }
    }

    override fun disconnect() {
        runCatching { serialFile?.close() }
        serialFile = null
        serialFd = null
        lineBuffer.clear()
        lastWriteAtMs = null
    }

    override fun sendCommands(commands: List<String>) {
        val fd = requireNotNull(serialFd) {
            "Direct serial device $path is not connected."
        }
        val now = System.currentTimeMillis()
        if (shouldSendWakePreamble(lastWriteAtMs, now)) {
            writePayload(fd, wakePreamble)
            Thread.sleep(wakeSettleMs)
        }

        commands.map(::ensureLineTerminated).forEach { payload ->
            writePayload(fd, payload)
        }
        lastWriteAtMs = System.currentTimeMillis()
    }

    override fun readAvailableLines(): List<String> {
        val fd = serialFd ?: return emptyList()
        val deadline = System.currentTimeMillis() + readTimeoutMs
        var lastDataAt: Long? = null
        val buffer = ByteArray(256)

        while (System.currentTimeMillis() <= deadline) {
            val bytesRead = readAvailable(fd, buffer)
            if (bytesRead > 0) {
                lineBuffer.appendAscii(buffer, bytesRead)
                lastDataAt = System.currentTimeMillis()
            } else if (
                lastDataAt != null &&
                (System.currentTimeMillis() - lastDataAt) >= quietPeriodMs &&
                lineBuffer.hasCompletedLines()
            ) {
                break
            } else {
                Thread.sleep(pollIntervalMs)
            }
        }

        return lineBuffer.drainCompletedLines()
    }

    private fun readAvailable(
        fd: FileDescriptor,
        buffer: ByteArray,
    ): Int {
        return try {
            Os.read(fd, buffer, 0, buffer.size)
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.EAGAIN) {
                0
            } else {
                throw error
            }
        }
    }

    private fun writePayload(
        fd: FileDescriptor,
        payload: String,
    ) {
        val bytes = payload.encodeToByteArray()
        var offset = 0
        while (offset < bytes.size) {
            offset += Os.write(fd, bytes, offset, bytes.size - offset)
        }
    }

    companion object {
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
    }
}
