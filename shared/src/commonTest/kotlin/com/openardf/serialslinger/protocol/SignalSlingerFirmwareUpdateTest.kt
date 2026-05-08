package com.openardf.serialslinger.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignalSlingerFirmwareUpdateTest {
    @Test
    fun parsesReleaseManifest() {
        val manifest = SignalSlingerFirmwareUpdate.parseReleaseInfo("\uFEFF" + sampleManifest(sampleHex().encodeToByteArray()))

        assertEquals("signalslinger-release-info-v1", manifest.format)
        assertEquals("SignalSlinger", manifest.product)
        assertEquals("2.0.0", manifest.version)
        assertEquals("HW-3.5", manifest.board)
        assertEquals("SignalSlinger-Update-v2.0.0-HW-3.5.hex", manifest.update.fileName)
        assertEquals(0x4000, manifest.update.startAddress)
        assertEquals(4, manifest.update.bytesInImage)
        assertEquals(9_600, manifest.serialSlinger.appBaud)
        assertEquals(115_200, manifest.serialSlinger.updateBaud)
        assertEquals("INF", manifest.serialSlinger.appInfoCommand)
        assertEquals("UPD", manifest.serialSlinger.appUpdateCommand)
        assertEquals("U", manifest.serialSlinger.bootloaderEntryCommand)
        assertEquals(512, manifest.serialSlinger.pageBytes)
        assertEquals(1, manifest.serialSlinger.protocolVersion)
        assertEquals("BL0.10", manifest.serialSlinger.bootloaderVersion)
        assertEquals(0x4000, manifest.serialSlinger.appStartAddress)
        assertEquals(131_072, manifest.serialSlinger.flashBytes)
        assertEquals(manifest.update.fileName, manifest.updateFile().fileName)
    }

    @Test
    fun gatesBootloaderUpdateSupportAtFirmwareTwoDotZeroDotZero() {
        assertEquals(false, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate(null))
        assertEquals(false, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate(""))
        assertEquals(false, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("1.2.2"))
        assertEquals(false, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("1.99.99"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("2.0.0"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("2.0"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("2.0.1"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("2.1.0-beta"))
        assertEquals(true, SignalSlingerFirmwareUpdate.supportsBootloaderUpdate("3.0.0"))
    }

    @Test
    fun validatesUpdateFileSha256() {
        val bytes = "abc".encodeToByteArray()
        val releaseFile = SignalSlingerReleaseFile(
            fileName = "update.hex",
            kind = "update",
            purpose = null,
            sizeBytes = bytes.size,
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )

        SignalSlingerFirmwareUpdate.verifyReleaseFileHash(releaseFile, bytes)

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.verifyReleaseFileHash(releaseFile, "abd".encodeToByteArray())
        }
        assertTrue(error.message.orEmpty().contains("hash mismatch"))
    }

    @Test
    fun parsesAppInfoLines() {
        val info = SignalSlingerFirmwareUpdate.parseAppInfo(
            listOf(
                "startup text",
                "SignalSlINF* INF product=SignalSlinger update=UPD",
                "* INF sw=2.0.0 hw=3.5 app=0x4000 baud=115200",
            ),
        )

        assertNotNull(info)
        assertEquals("SignalSlinger", info.product)
        assertEquals("2.0.0", info.softwareVersion)
        assertEquals("3.5", info.hardwareBuild)
        assertEquals(0x4000, info.appStartAddress)
        assertEquals(115_200, info.updateBaud)
        assertEquals("UPD", info.appUpdateCommand)
        info.validateForRelease(SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray())))
    }

    @Test
    fun rejectsAppInfoForWrongHardware() {
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray()))
        val info = SignalSlingerAppInfo(
            product = "SignalSlinger",
            softwareVersion = "2.0.0",
            hardwareBuild = "3.4",
            appStartAddress = 0x4000,
            updateBaud = 115_200,
            appUpdateCommand = "UPD",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            info.validateForRelease(release)
        }
        assertTrue(error.message.orEmpty().contains("does not match package board"))
    }

    @Test
    fun fallsBackToVerInfoForUnsupportedOlderFirmware() {
        val info = SignalSlingerFirmwareUpdate.parseAppInfo(
            listOf("* SW Ver: 1.2.2 HW Build: 3.5"),
        )

        assertNotNull(info)
        assertEquals("1.2.2", info.softwareVersion)
        assertEquals("3.5", info.hardwareBuild)
        val error = assertFailsWith<IllegalArgumentException> {
            info.validateForRelease(SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray())))
        }
        assertTrue(error.message.orEmpty().contains("does not support firmware updates"))
    }

    @Test
    fun rejectsIntelHexDataBelowAppStart() {
        val hex = intelHex(
            0x0000 to byteArrayOf(0x12, 0x34),
            0x4000 to byteArrayOf(0x56),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.parseIntelHexPages(hex)
        }
        assertTrue(error.message.orEmpty().contains("outside app flash"))
        assertTrue(error.message.orEmpty().contains("0x00000000"))
    }

    @Test
    fun rejectsIntelHexDataAtOrAboveFlashEnd() {
        val hex = intelHex(
            0x4000 to byteArrayOf(0x56),
            0x20000 to byteArrayOf(0x12),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            SignalSlingerFirmwareUpdate.parseIntelHexPages(hex)
        }
        assertTrue(error.message.orEmpty().contains("0x00020000"))
    }

    @Test
    fun parsesExtendedSegmentAddressRecords() {
        val hex = listOf(
            record(0x4000, 0x00, byteArrayOf(0x01)),
            record(0x0000, 0x02, byteArrayOf(0x10, 0x00)),
            record(0x0000, 0x00, byteArrayOf(0x02)),
            ":00000001FF",
        ).joinToString("\n")

        val pages = SignalSlingerFirmwareUpdate.parseIntelHexPages(hex)

        assertEquals(listOf(0x4000, 0x10000), pages.map { it.address })
        assertEquals(0x02, pages.last().bytes.first().toInt() and 0xFF)
    }

    @Test
    fun buildsPagePlanWithResetVectorPageWrittenLast() {
        val pages = SignalSlingerFirmwareUpdate.parseIntelHexPages(
            intelHex(
                0x4000 to byteArrayOf(0x01, 0x02),
                0x4200 to byteArrayOf(0x03),
                0x4400 to byteArrayOf(0x04),
            ),
        )

        val plan = SignalSlingerFirmwareUpdate.buildUpdatePlan(pages)

        assertEquals(listOf(0x4000, 0x4200, 0x4400), pages.map { it.address })
        assertEquals(
            listOf(
                SignalSlingerFirmwareOperationKind.ERASE to 0x4000,
                SignalSlingerFirmwareOperationKind.ERASE to 0x4200,
                SignalSlingerFirmwareOperationKind.WRITE to 0x4200,
                SignalSlingerFirmwareOperationKind.CRC to 0x4200,
                SignalSlingerFirmwareOperationKind.ERASE to 0x4400,
                SignalSlingerFirmwareOperationKind.WRITE to 0x4400,
                SignalSlingerFirmwareOperationKind.CRC to 0x4400,
                SignalSlingerFirmwareOperationKind.WRITE to 0x4000,
                SignalSlingerFirmwareOperationKind.CRC to 0x4000,
                SignalSlingerFirmwareOperationKind.RUN to null,
            ),
            plan.operations.map { it.kind to it.address },
        )
    }

    @Test
    fun constructsCrcProtectedFramesWithLittleEndianFields() {
        assertEquals(
            "45 00 40 00 00 9E 3E",
            SignalSlingerFirmwareUpdate.eraseFrame(0x4000).toHex(),
        )
        assertEquals(
            "43 00 40 00 00 1B F3",
            SignalSlingerFirmwareUpdate.pageCrcFrame(0x4000).toHex(),
        )

        val write = SignalSlingerFirmwareUpdate.writeFrame(0x4000, ByteArray(512) { 0xFF.toByte() })
        assertEquals(519, write.size)
        assertEquals("57 00 40 00 00", write.take(5).toByteArray().toHex())
        assertEquals("FF FF FF FF AF F3", write.takeLast(6).toByteArray().toHex())
    }

    @Test
    fun parsesOkCrcResponse() {
        val response = assertNotNull(SignalSlingerFirmwareUpdate.parsePageCrcResponse("OK crc 0x00004000 6995"))

        assertEquals(0x4000, response.address)
        assertEquals(0x6995, response.crc)
        assertEquals(null, SignalSlingerFirmwareUpdate.parsePageCrcResponse("ERR crc"))
    }

    @Test
    fun parsesAndValidatesBootloaderIdentity() {
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(sampleHex().encodeToByteArray()))
        val identity = assertNotNull(
            SignalSlingerFirmwareUpdate.parseIdentityLine(
                "SignalSlinger BL0.10 proto=1 app=0x4000 page=512 flash=131072 baud=115200 boot=32 cmds=U,R,?,E,W,C",
            ),
        )

        assertEquals("SignalSlinger", identity.product)
        assertEquals("BL0.10", identity.bootloaderVersion)
        assertEquals(1, identity.protocolVersion)
        assertEquals(0x4000, identity.appStartAddress)
        assertEquals(512, identity.pageBytes)
        assertEquals(131_072, identity.flashBytes)
        assertEquals(115_200, identity.baud)
        assertEquals(setOf('U', 'R', '?', 'E', 'W', 'C'), identity.commands)
        identity.validateForRelease(release)
    }

    @Test
    fun ignoresNormalAppBannerAsBootloaderIdentity() {
        assertEquals(
            null,
            SignalSlingerFirmwareUpdate.parseIdentityLine("SignalSlinger 80m Radio Orienteering Transmitter"),
        )
    }

    @Test
    fun crc16CcittFalseMatchesKnownVector() {
        assertEquals(0x29B1, SignalSlingerFirmwareUpdate.crc16CcittFalse("123456789".encodeToByteArray()))
    }

    @Test
    fun fallsBackToResetEntryWhenUpdDoesNotAcknowledge() {
        val hex = sampleHex()
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(sampleManifest(hex.encodeToByteArray()))
        val transport = FakeUpdateTransport(hex)

        SignalSlingerFirmwareUpdate.performUpdate(
            transport = transport,
            release = release,
            hexText = hex,
        )

        assertEquals(listOf(9_600, 115_200, 9_600, 115_200, 9_600), transport.connectedBauds)
        assertEquals(listOf("**\r", "INF\r", "UPD\r"), transport.asciiWrites.take(3))
        assertTrue("RST\n" in transport.asciiWrites)
        assertTrue("R" in transport.asciiWrites)
        assertEquals("INF\r", transport.asciiWrites.last())
        assertEquals(listOf('E', 'W', 'C'), transport.binaryWrites.map { it[0].toInt().toChar() })
    }

    private fun sampleManifest(updateBytes: ByteArray): String {
        return """
            {
              "format": "signalslinger-release-info-v1",
              "product": "SignalSlinger",
              "version": "2.0.0",
              "board": "HW-3.5",
              "generatedUtc": "2026-05-08T00:00:00Z",
              "gitCommit": "abcdef",
              "update": {
                "fileName": "SignalSlinger-Update-v2.0.0-HW-3.5.hex",
                "startAddress": "0x4000",
                "bytesInImage": 4
              },
              "serialSlinger": {
                "appBaud": 9600,
                "updateBaud": 115200,
                "appInfoCommand": "INF",
                "appUpdateCommand": "UPD",
                "bootloaderEntryCommand": "U",
                "pageBytes": 512,
                "protocolVersion": 1,
                "bootloaderVersion": "BL0.10",
                "appStartAddress": "0x4000",
                "flashBytes": 131072
              },
              "files": [
                {
                  "fileName": "SignalSlinger-Update-v2.0.0-HW-3.5.hex",
                  "kind": "update",
                  "purpose": "Update an existing SignalSlinger",
                  "sizeBytes": ${updateBytes.size},
                  "sha256": "${sha256ForSampleHex()}"
                }
              ]
            }
        """.trimIndent()
    }

    private fun sampleHex(): String = intelHex(0x4000 to byteArrayOf(0x01, 0x02, 0x03, 0x04))

    private fun sha256ForSampleHex(): String {
        return "1d9878e560beae019c8f407190e576b5c61c7ad9ab2a55a38ee5f1d97e1bfef6"
    }

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

    private fun ByteArray.toHex(): String {
        return joinToString(" ") { byte -> (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
    }

    private class FakeUpdateTransport(
        hex: String,
    ) : SignalSlingerFirmwareUpdateTransport {
        val connectedBauds = mutableListOf<Int>()
        val asciiWrites = mutableListOf<String>()
        val binaryWrites = mutableListOf<ByteArray>()
        private val pages = SignalSlingerFirmwareUpdate.parseIntelHexPages(hex)
        private var pendingLines = emptyList<String>()
        private var resetSent = false

        override fun connect(baudRate: Int) {
            connectedBauds += baudRate
        }

        override fun disconnect() {
        }

        override fun writeAscii(text: String) {
            asciiWrites += text
            pendingLines =
                when (text) {
                    "INF\r" -> listOf(
                        "* INF product=SignalSlinger update=UPD",
                        "* INF sw=2.0.0 hw=3.5 app=0x4000 baud=115200",
                    )
                    "RST\n" -> {
                        resetSent = true
                        emptyList()
                    }
                    "U" -> if (resetSent) listOf("BOOT") else emptyList()
                    "?" -> listOf("SignalSlinger BL0.10 proto=1 app=0x4000 page=512 flash=131072 baud=115200 boot=32 cmds=U,R,?,E,W,C")
                    else -> emptyList()
                }
        }

        override fun writeBytes(bytes: ByteArray) {
            binaryWrites += bytes
            pendingLines =
                when (bytes[0].toInt().toChar()) {
                    'E' -> listOf("OK erase")
                    'W' -> listOf("OK write")
                    'C' -> {
                        val address = (bytes[1].toInt() and 0xFF) or
                            ((bytes[2].toInt() and 0xFF) shl 8) or
                            ((bytes[3].toInt() and 0xFF) shl 16) or
                            ((bytes[4].toInt() and 0xFF) shl 24)
                        val page = pages.first { it.address == address }
                        val crc = SignalSlingerFirmwareUpdate.crc16CcittFalse(page.bytes)
                        listOf("OK crc 0x${address.toString(16).uppercase().padStart(8, '0')} ${crc.toString(16).uppercase().padStart(4, '0')}")
                    }
                    else -> emptyList()
                }
        }

        override fun readLines(timeoutMs: Long): List<String> {
            val lines = pendingLines
            pendingLines = emptyList()
            return lines
        }
    }
}
