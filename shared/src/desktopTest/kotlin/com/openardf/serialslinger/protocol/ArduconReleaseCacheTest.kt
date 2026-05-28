package com.openardf.serialslinger.protocol

import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArduconReleaseCacheTest {
    @Test
    fun downloadsLatestArduconReleasePackage() {
        val root = Files.createTempDirectory("arducon-release-cache-test").toFile()
        val latestApi = URI("https://api.github.test/releases/latest")
        val zipUrl = "https://github.com/OpenARDF/Arducon/releases/download/v2.0.0/Arducon-v2.0.0-ATmega328P-Release-Files.zip"
        try {
            val selection = ArduconReleaseCache(
                rootDirectory = root,
                repositoryApiUrl = latestApi,
                downloadBytes = { uri ->
                    when (uri) {
                        latestApi -> """{"tag_name":"v2.0.0","browser_download_url":"$zipUrl"}""".encodeToByteArray()
                        URI(zipUrl) -> releaseZip(version = "2.0.0")
                        else -> error("unexpected URI $uri")
                    }
                },
            ).selectLatestForUpdate(currentFirmwareVersion = "1.9.9")

            assertEquals(ArduconReleaseSelectionSource.DOWNLOADED, selection.source)
            assertEquals("2.0.0", selection.release.version)
            assertEquals("ATmega328P", selection.release.board)
            assertTrue(root.resolve("ATmega328P/2.0.0/Arducon-Release-Info-v2.0.0-ATmega328P.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsAlreadyCurrentWhenLatestMatchesConnectedFirmware() {
        val root = Files.createTempDirectory("arducon-release-cache-test").toFile()
        val latestApi = URI("https://api.github.test/releases/latest")
        val zipUrl = "https://github.com/OpenARDF/Arducon/releases/download/v2.0.0/Arducon-v2.0.0-ATmega328P-Release-Files.zip"
        try {
            val error = assertFailsWith<ArduconAlreadyCurrentException> {
                ArduconReleaseCache(
                    rootDirectory = root,
                    repositoryApiUrl = latestApi,
                    downloadBytes = { uri ->
                        when (uri) {
                            latestApi -> """{"tag_name":"v2.0.0","browser_download_url":"$zipUrl"}""".encodeToByteArray()
                            URI(zipUrl) -> releaseZip(version = "2.0.0")
                            else -> error("unexpected URI $uri")
                        }
                    },
                ).selectLatestForUpdate(currentFirmwareVersion = "2.0.0")
            }

            assertEquals("The connected Arducon already has firmware 2.0.0.", error.message)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importsLocalReleasePackageIntoResidentCache() {
        val root = Files.createTempDirectory("arducon-release-cache-test").toFile()
        val packageFile = Files.createTempFile("arducon-release-package", ".zip").toFile()
        try {
            packageFile.writeBytes(releaseZip(version = "2.0.0"))

            val resident = ArduconReleaseCache(root).importReleasePackage(packageFile)

            assertEquals("2.0.0", resident.release.version)
            assertEquals("ATmega328P", resident.release.board)
            assertTrue(root.resolve("ATmega328P/2.0.0/Arducon-Release-Info-v2.0.0-ATmega328P.json").isFile)
            assertNotNull(ArduconReleaseCache(root).latestResident())
        } finally {
            packageFile.delete()
            root.deleteRecursively()
        }
    }

    private fun releaseZip(version: String): ByteArray {
        val hex = sampleHex()
        val manifest = manifest(version = version, updateBytes = hex.encodeToByteArray())
        return zip(
            "Arducon-Release-Info-v$version-ATmega328P.json" to manifest.encodeToByteArray(),
            "Arducon-Update-v$version-ATmega328P.hex" to hex.encodeToByteArray(),
        )
    }

    private fun manifest(
        version: String,
        updateBytes: ByteArray,
    ): String {
        return """
            {
              "format": "arducon-release-info-v1",
              "product": "Arducon",
              "version": "$version",
              "board": "ATmega328P",
              "update": {
                "fileName": "Arducon-Update-v$version-ATmega328P.hex",
                "startAddress": "0x0000",
                "bytesInImage": 4
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

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        return java.io.ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
    }
}
