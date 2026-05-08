package com.openardf.serialslinger.protocol

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SignalSlingerReleaseCacheTest {
    @Test
    fun findsNewestResidentReleaseForHardware() {
        val root = Files.createTempDirectory("signalslinger-release-cache-test").toFile()
        try {
            root.resolve("HW-3.5/2.0.0").apply {
                mkdirs()
                resolve("SignalSlinger-Release-Info-v2.0.0-HW-3.5.json").writeText(manifest(version = "2.0.0", board = "HW-3.5"))
            }
            root.resolve("HW-3.5/2.1.0").apply {
                mkdirs()
                resolve("SignalSlinger-Release-Info-v2.1.0-HW-3.5.json").writeText(manifest(version = "2.1.0", board = "HW-3.5"))
            }
            root.resolve("HW-3.4/3.0.0").apply {
                mkdirs()
                resolve("SignalSlinger-Release-Info-v3.0.0-HW-3.4.json").writeText(manifest(version = "3.0.0", board = "HW-3.4"))
            }

            val resident = assertNotNull(SignalSlingerReleaseCache(root).latestResidentForHardware("3.5"))

            assertEquals("2.1.0", resident.release.version)
            assertEquals("HW-3.5", resident.release.board)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifest(
        version: String,
        board: String,
    ): String {
        return """
            {
              "format": "signalslinger-release-info-v1",
              "product": "SignalSlinger",
              "version": "$version",
              "board": "$board",
              "update": {
                "fileName": "SignalSlinger-Update-v$version-$board.hex",
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
                "bootloaderVersion": "BL0.11",
                "appStartAddress": "0x4000",
                "flashBytes": 131072
              },
              "files": [
                {
                  "fileName": "SignalSlinger-Update-v$version-$board.hex",
                  "kind": "update",
                  "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
                }
              ]
            }
        """.trimIndent()
    }
}
