package com.openardf.serialslinger.app

import com.openardf.serialslinger.protocol.Sha256
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopLocalReleaseSelectionSupportTest {
    @Test
    fun resolvesSelectedArduconManifestFile() {
        val root = Files.createTempDirectory("arducon-local-selection-test").toFile()
        try {
            val manifestFile = writeArduconManifest(root, version = "2.0.0")

            val resolved = DesktopLocalReleaseSelectionSupport.resolveArduconUpdateManifestSelection(manifestFile)

            assertEquals(manifestFile.canonicalFile, resolved.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolvesLatestArduconManifestFromSelectedBoardFolder() {
        val root = Files.createTempDirectory("arducon-local-selection-test").toFile()
        try {
            val boardDirectory = root.resolve("ATmega328P")
            writeArduconManifest(boardDirectory.resolve("2.0.0"), version = "2.0.0")
            val latestManifest = writeArduconManifest(boardDirectory.resolve("2.0.2"), version = "2.0.2")

            val resolved = DesktopLocalReleaseSelectionSupport.resolveArduconUpdateManifestSelection(boardDirectory)

            assertEquals(latestManifest.canonicalFile, resolved.canonicalFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsFolderWithoutArduconReleaseInfo() {
        val root = Files.createTempDirectory("arducon-local-selection-test").toFile()
        try {
            val error = assertFailsWith<IllegalArgumentException> {
                DesktopLocalReleaseSelectionSupport.resolveArduconUpdateManifestSelection(root)
            }

            assertEquals(
                "Selected folder `${root.path}` does not contain a valid Arducon release info JSON.",
                error.message,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeArduconManifest(
        directory: java.io.File,
        version: String,
    ): java.io.File {
        directory.mkdirs()
        val hexBytes = ":00000001FF\n".encodeToByteArray()
        val manifest = """
            {
              "format": "arducon-release-info-v1",
              "product": "Arducon",
              "version": "$version",
              "board": "ATmega328P",
              "update": {
                "fileName": "Arducon-Update-v$version-ATmega328P.hex",
                "startAddress": "0x0000",
                "bytesInImage": 0
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
                  "sizeBytes": ${hexBytes.size},
                  "sha256": "${Sha256.digestHex(hexBytes)}"
                }
              ]
            }
        """.trimIndent()
        val manifestFile = directory.resolve("Arducon-Release-Info-v$version-ATmega328P.json")
        manifestFile.writeText(manifest)
        return manifestFile
    }
}
