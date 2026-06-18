package com.openardf.serialslinger.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArduconFirmwareUpdateTest {
    @Test
    fun parsesReleaseManifest() {
        val manifest = ArduconFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray()))

        assertEquals("arducon-release-info-v1", manifest.format)
        assertEquals("Arducon", manifest.product)
        assertEquals("2.0.0", manifest.version)
        assertEquals("ATmega328P", manifest.board)
        assertEquals("Arducon-Update-v2.0.0-ATmega328P.hex", manifest.update.fileName)
        assertEquals(0x0000, manifest.update.startAddress)
        assertEquals(4, manifest.update.bytesInImage)
        assertEquals(57_600, manifest.firmwareUpdate.appBaud)
        assertEquals(115_200, manifest.firmwareUpdate.updateBaud)
        assertEquals("INF", manifest.firmwareUpdate.appInfoCommand)
        assertEquals("UPD", manifest.firmwareUpdate.appUpdateCommand)
        assertEquals(128, manifest.firmwareUpdate.pageBytes)
        assertEquals("stk500v1", manifest.firmwareUpdate.bootloaderProtocol)
        assertEquals(0x0000, manifest.firmwareUpdate.appStartAddress)
        assertEquals(0x7E00, manifest.firmwareUpdate.appLimitAddress)
        assertEquals(32_768, manifest.firmwareUpdate.flashBytes)
        assertEquals("Arducon-Update-v2.0.0-ATmega328P.hex", manifest.updateFile().fileName)
        ArduconFirmwareUpdate.validateReleaseManifest(manifest)
    }

    @Test
    fun rejectsHexDataInBootloaderArea() {
        val hex = intelHex(
            0x0000 to byteArrayOf(0x01, 0x02),
            0x7E00 to byteArrayOf(0x03),
        )
        val manifest = ArduconFirmwareUpdate.parseReleaseInfo(
            sampleManifest(
                updateBytes = hex.encodeToByteArray(),
                bytesInImage = 3,
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ArduconFirmwareUpdate.performUpdate(
                transport = FakeStk500Transport(),
                release = manifest,
                hexText = hex,
            )
        }

        assertTrue(error.message.orEmpty().contains("outside app flash"))
        assertTrue(error.message.orEmpty().contains("0x00007E00"))
    }

    @Test
    fun performsStk500v1UpdateAndVerifiesReadback() {
        val hex = sampleHex()
        val manifest = ArduconFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))
        val transport = FakeStk500Transport()

        ArduconFirmwareUpdate.performUpdate(
            transport = transport,
            release = manifest,
            hexText = hex,
        )

        assertEquals(listOf(57_600, 57_600), transport.connectedBauds)
        assertEquals(listOf(115_200), transport.reconfiguredBauds)
        assertEquals(1, transport.resetPulseCount)
        assertEquals(listOf("SYN 0\r", "INF\r", "UPD\r", "INF\r"), transport.asciiWrites)
        assertTrue(transport.binaryWrites.any { it.first().toInt() == 0x30 })
        assertTrue(transport.binaryWrites.any { it.first().toInt() == 0x50 })
        assertTrue(transport.binaryWrites.any { it.first().toInt() == 0x55 })
        assertTrue(transport.binaryWrites.any { it.first().toInt() == 0x64 })
        assertTrue(transport.binaryWrites.any { it.first().toInt() == 0x74 })
        assertTrue(transport.binaryWrites.any { it.first().toInt() == 0x51 })
    }

    @Test
    fun retriesWhenFirstArduconInfoReadIsIncomplete() {
        val hex = sampleHex()
        val manifest = ArduconFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))
        val transport = FakeStk500Transport(partialFirstInfo = true)

        ArduconFirmwareUpdate.performUpdate(
            transport = transport,
            release = manifest,
            hexText = hex,
        )

        assertEquals(listOf("SYN 0\r", "INF\r", "SYN 0\r", "INF\r", "UPD\r"), transport.asciiWrites.take(5))
        assertTrue(transport.binaryWrites.any { it.first().toInt() == 0x64 })
    }

    @Test
    fun programsResetVectorPageLast() {
        val hex = intelHex(
            0x0000 to byteArrayOf(0x01, 0x02),
            0x0080 to byteArrayOf(0x03, 0x04),
            0x0100 to byteArrayOf(0x05, 0x06),
        )
        val manifest = ArduconFirmwareUpdate.parseReleaseInfo(
            sampleManifest(
                updateBytes = hex.encodeToByteArray(),
                bytesInImage = 6,
            ),
        )
        val transport = FakeStk500Transport()

        ArduconFirmwareUpdate.performUpdate(
            transport = transport,
            release = manifest,
            hexText = hex,
            verifyReadback = false,
        )

        val programmedAddresses = transport.binaryWrites
            .filter { bytes -> (bytes.first().toInt() and 0xFF) == 0x55 }
            .map { bytes ->
                val wordAddress = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
                wordAddress * 2
            }
        assertEquals(listOf(0x0080, 0x0100, 0x0000), programmedAddresses)
    }

    @Test
    fun refusesToDowngradeArduconAcrossBootloaderBoundary() {
        val hex = sampleHex()
        val manifest = ArduconFirmwareUpdate.parseReleaseInfo(
            sampleManifest(
                updateBytes = hex.encodeToByteArray(),
                version = "2.0.9",
            ),
        )
        val transport = FakeStk500Transport(appVersion = "2.1.0")

        val error = assertFailsWith<IllegalArgumentException> {
            ArduconFirmwareUpdate.performUpdate(
                transport = transport,
                release = manifest,
                hexText = hex,
            )
        }

        assertTrue(error.message.orEmpty().contains("cannot be downgraded to 2.0.9"))
        assertTrue(error.message.orEmpty().contains("bootloader"))
        assertEquals(listOf("SYN 0\r", "INF\r"), transport.asciiWrites)
        assertEquals(emptyList(), transport.reconfiguredBauds)
        assertEquals(emptyList(), transport.binaryWrites)
        assertEquals(0, transport.resetPulseCount)
    }

    @Test
    fun reportsWhenAppKeepsRunningAfterBootloaderEntryCommand() {
        val hex = sampleHex()
        val manifest = ArduconFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))

        val error = assertFailsWith<IllegalStateException> {
            ArduconFirmwareUpdate.performUpdate(
                transport = FakeArduconAppOnlyTransport(),
                release = manifest,
                hexText = hex,
            )
        }

        assertTrue(error.message.orEmpty().contains("app is still running"))
        assertTrue(error.message.orEmpty().contains("STK500v1/Optiboot"))
    }

    @Test
    fun reportsWhenBootloaderStillRespondsAfterLeaveProgramming() {
        val hex = sampleHex()
        val manifest = ArduconFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))

        val error = assertFailsWith<IllegalStateException> {
            ArduconFirmwareUpdate.performUpdate(
                transport = FakeStk500Transport(confirmRestart = false),
                release = manifest,
                hexText = hex,
            )
        }

        assertTrue(error.message.orEmpty().contains("Arducon update was sent and verified"))
        assertTrue(error.message.orEmpty().contains("Manually restart the Arducon by cycling power"))
    }

    private fun sampleManifest(
        updateBytes: ByteArray,
        bytesInImage: Int = 4,
        product: String = "Arducon",
        version: String = "2.0.0",
    ): String {
        return """
            {
              "format": "arducon-release-info-v1",
              "product": "$product",
              "version": "$version",
              "board": "ATmega328P",
              "update": {
                "fileName": "Arducon-Update-v$version-ATmega328P.hex",
                "startAddress": "0x0000",
                "bytesInImage": $bytesInImage
              },
              "bootloader": {
                "fileName": "Arducon-Bootloader-Optiboot-ATmega328P.hex",
                "sourceArchiveFileName": "Arducon-Bootloader-Optiboot-ATmega328P-Source.zip",
                "protocol": "stk500v1",
                "baud": 115200,
                "startAddress": "0x7E00",
                "endAddress": "0x7FFF",
                "bytesInImage": 502
              },
              "firmwareUpdate": {
                "appBaud": 57600,
                "updateBaud": 115200,
                "appInfoCommand": "INF",
                "appUpdateCommand": "UPD",
                "bootloaderEntryCommand": "",
                "pageBytes": 128,
                "protocolVersion": 1,
                "bootloaderProtocol": "stk500v1",
                "bootloaderVersion": "unknown",
                "appStartAddress": "0x0000",
                "flashBytes": 32768,
                "appLimitAddress": "0x7E00"
              },
              "files": [
                {
                  "fileName": "Arducon-Update-v$version-ATmega328P.hex",
                  "kind": "update",
                  "purpose": "Application HEX for normal bootloader updates.",
                  "sizeBytes": ${updateBytes.size},
                  "sha256": "${Sha256.digestHex(updateBytes)}"
                }
              ]
            }
        """.trimIndent()
    }

    private fun sampleHex(): String = intelHex(0x0000 to byteArrayOf(0x01, 0x02, 0x03, 0x04))

    private fun intelHex(vararg chunks: Pair<Int, ByteArray>): String {
        val lines = mutableListOf<String>()
        var currentUpper: Int? = null
        chunks.sortedBy { it.first }.forEach { (address, bytes) ->
            val upper = address ushr 16
            if (upper != currentUpper) {
                currentUpper = upper
                lines += record(0x0000, 0x04, byteArrayOf(((upper ushr 8) and 0xFF).toByte(), (upper and 0xFF).toByte()))
            }
            lines += record(address and 0xFFFF, 0x00, bytes)
        }
        lines += ":00000001FF"
        return lines.joinToString("\n")
    }

    private fun record(
        offset: Int,
        type: Int,
        data: ByteArray,
    ): String {
        val values = mutableListOf(data.size, (offset ushr 8) and 0xFF, offset and 0xFF, type)
        values += data.map { it.toInt() and 0xFF }
        val checksum = ((values.sum().inv() + 1) and 0xFF)
        return ":" + (values + checksum).joinToString("") { it.toString(16).uppercase().padStart(2, '0') }
    }

    private class FakeStk500Transport(
        private val confirmRestart: Boolean = true,
        private val appVersion: String = "2.0.0",
        private val partialFirstInfo: Boolean = false,
    ) : SignalSlingerFirmwareUpdateTransport {
        val connectedBauds = mutableListOf<Int>()
        val reconfiguredBauds = mutableListOf<Int>()
        val asciiWrites = mutableListOf<String>()
        val binaryWrites = mutableListOf<ByteArray>()
        var resetPulseCount = 0
            private set
        private var connectedBaud: Int? = null
        private val programmedPages = mutableMapOf<Int, ByteArray>()
        private var loadedAddress = 0
        private var pendingBytes = ByteArray(0)
        private var finalInfo = false
        private var appInfoReadCount = 0

        override fun connect(baudRate: Int) {
            connectedBaud = baudRate
            connectedBauds += baudRate
            if (baudRate == 57_600 && asciiWrites.any { it == "UPD\r" }) {
                finalInfo = true
            }
        }

        override fun disconnect() {
            connectedBaud = null
        }

        override fun reconfigureBaudRate(baudRate: Int): Boolean {
            connectedBaud = baudRate
            reconfiguredBauds += baudRate
            return true
        }

        override fun pulseTargetReset(): Boolean {
            resetPulseCount++
            return true
        }

        override fun writeAscii(text: String) {
            asciiWrites += text
        }

        override fun writeBytes(bytes: ByteArray) {
            binaryWrites += bytes
            require(connectedBaud == 115_200)
            require(bytes.last().toInt() == 0x20)
            when (bytes.first().toInt() and 0xFF) {
                0x30, 0x50, 0x51 -> pendingBytes = stkOk()
                0x55 -> {
                    val wordAddress = (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
                    loadedAddress = wordAddress * 2
                    pendingBytes = stkOk()
                }
                0x64 -> {
                    val length = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
                    programmedPages[loadedAddress] = bytes.copyOfRange(4, 4 + length)
                    pendingBytes = stkOk()
                }
                0x74 -> {
                    val page = programmedPages.getValue(loadedAddress)
                    pendingBytes = byteArrayOf(0x14) + page + byteArrayOf(0x10)
                }
                else -> error("Unexpected STK command ${bytes.first().toInt() and 0xFF}")
            }
        }

        override fun readBytes(timeoutMs: Long): ByteArray {
            val result = pendingBytes
            pendingBytes = ByteArray(0)
            return result
        }

        override fun readLines(timeoutMs: Long): List<String> {
            if (asciiWrites.lastOrNull() != "INF\r") {
                return emptyList()
            }
            appInfoReadCount++
            if (partialFirstInfo && appInfoReadCount == 1) {
                return listOf(
                    "> baud=115200",
                    "* INF bl=unknown",
                    "* INF proto=stk500v1",
                )
            }
            if (finalInfo && !confirmRestart) {
                return emptyList()
            }
            return listOf(
                "* INF product=Arducon update=UPD",
                "* INF sw=$appVersion hw=ATmega328P-16 app=0x0000 appbaud=57600 baud=115200 bl=unknown proto=stk500v1",
            )
        }

        private fun stkOk(): ByteArray = byteArrayOf(0x14, 0x10)
    }

    private class FakeArduconAppOnlyTransport : SignalSlingerFirmwareUpdateTransport {
        private var connectedBaud: Int? = null
        private var lastAsciiWrite: String? = null

        override fun connect(baudRate: Int) {
            connectedBaud = baudRate
        }

        override fun disconnect() {
            connectedBaud = null
        }

        override fun reconfigureBaudRate(baudRate: Int): Boolean {
            connectedBaud = baudRate
            return true
        }

        override fun writeAscii(text: String) {
            lastAsciiWrite = text
        }

        override fun writeBytes(bytes: ByteArray) {
            require(connectedBaud == 115_200)
        }

        override fun readBytes(timeoutMs: Long): ByteArray = ByteArray(0)

        override fun readLines(timeoutMs: Long): List<String> {
            if (connectedBaud != 57_600 || lastAsciiWrite != "INF\r") {
                return emptyList()
            }
            return listOf(
                "* INF product=Arducon update=UPD",
                "* INF sw=1.0.1 hw=ATmega328P-16 app=0x0000 appbaud=57600 baud=115200 bl=unknown proto=stk500v1",
            )
        }
    }
}
