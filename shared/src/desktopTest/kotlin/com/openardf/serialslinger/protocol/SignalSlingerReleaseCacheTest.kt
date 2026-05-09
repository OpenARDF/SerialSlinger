package com.openardf.serialslinger.protocol

import java.nio.file.Files
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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

    @Test
    fun fallsBackToNewerResidentReleaseWhenDownloadFails() {
        val root = Files.createTempDirectory("signalslinger-release-cache-test").toFile()
        try {
            root.resolve("HW-3.4/2.0.2").apply {
                mkdirs()
                resolve("SignalSlinger-Release-Info-v2.0.2-HW-3.4.json").writeText(manifest(version = "2.0.2", board = "HW-3.4"))
            }

            val selection = SignalSlingerReleaseCache(
                rootDirectory = root,
                downloadBytes = { error("network unavailable") },
            ).selectLatestForUpdate(
                hardwareBuild = "3.4",
                currentFirmwareVersion = "2.0.1",
            )

            assertEquals(SignalSlingerReleaseSelectionSource.RESIDENT, selection.source)
            assertEquals("2.0.2", selection.release.version)
            assertNotNull(selection.downloadFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun doesNotOfferResidentReleaseThatIsNotNewer() {
        val root = Files.createTempDirectory("signalslinger-release-cache-test").toFile()
        try {
            root.resolve("HW-3.4/2.0.2").apply {
                mkdirs()
                resolve("SignalSlinger-Release-Info-v2.0.2-HW-3.4.json").writeText(manifest(version = "2.0.2", board = "HW-3.4"))
            }

            val error = assertFailsWith<IllegalStateException> {
                SignalSlingerReleaseCache(
                    rootDirectory = root,
                    downloadBytes = { error("network unavailable") },
                ).selectLatestForUpdate(
                    hardwareBuild = "3.4",
                    currentFirmwareVersion = "2.0.2",
                )
            }

            assertTrue(error.message.orEmpty().contains("not newer"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsAlreadyCurrentAsTypedNoOpWhenLatestMatchesConnectedFirmware() {
        val root = Files.createTempDirectory("signalslinger-release-cache-test").toFile()
        val latestApi = URI("https://api.github.test/releases/latest")
        val zipUrl = "https://github.com/OpenARDF/SignalSlinger/releases/download/v2.0.2/SignalSlinger-Release-Files-v2.0.2-HW-3.4.zip"
        try {
            val error = assertFailsWith<SignalSlingerAlreadyCurrentException> {
                SignalSlingerReleaseCache(
                    rootDirectory = root,
                    repositoryApiUrl = latestApi,
                    downloadBytes = { uri ->
                        when (uri) {
                            latestApi -> """{"browser_download_url":"$zipUrl"}""".encodeToByteArray()
                            URI(zipUrl) -> releaseZip(version = "2.0.2", board = "HW-3.4")
                            else -> error("unexpected URI $uri")
                        }
                    },
                ).selectLatestForUpdate(
                    hardwareBuild = "3.4",
                    currentFirmwareVersion = "2.0.2",
                )
            }

            assertEquals("The connected SignalSlinger already has firmware 2.0.2.", error.message)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun overrideAllowsSameVersionResidentReleaseWhenDownloadFails() {
        val root = Files.createTempDirectory("signalslinger-release-cache-test").toFile()
        try {
            root.resolve("HW-3.4/2.0.2").apply {
                mkdirs()
                resolve("SignalSlinger-Release-Info-v2.0.2-HW-3.4.json").writeText(manifest(version = "2.0.2", board = "HW-3.4"))
            }

            val selection = SignalSlingerReleaseCache(
                rootDirectory = root,
                downloadBytes = { error("network unavailable") },
            ).selectLatestForUpdate(
                hardwareBuild = "3.5",
                currentFirmwareVersion = "2.0.2",
                overrideBoard = "HW-3.4",
            )

            assertEquals(SignalSlingerReleaseSelectionSource.RESIDENT, selection.source)
            assertEquals("2.0.2", selection.release.version)
            assertEquals("HW-3.4", selection.release.board)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun overrideAllowsSameVersionDownloadedRelease() {
        val root = Files.createTempDirectory("signalslinger-release-cache-test").toFile()
        val latestApi = URI("https://api.github.test/releases/latest")
        val zipUrl = "https://github.com/OpenARDF/SignalSlinger/releases/download/v2.0.2/SignalSlinger-Release-Files-v2.0.2-HW-3.4.zip"
        try {
            val selection = SignalSlingerReleaseCache(
                rootDirectory = root,
                repositoryApiUrl = latestApi,
                downloadBytes = { uri ->
                    when (uri) {
                        latestApi -> """{"browser_download_url":"$zipUrl"}""".encodeToByteArray()
                        URI(zipUrl) -> releaseZip(version = "2.0.2", board = "HW-3.4")
                        else -> error("unexpected URI $uri")
                    }
                },
            ).selectLatestForUpdate(
                hardwareBuild = "3.5",
                currentFirmwareVersion = "2.0.2",
                overrideBoard = "HW-3.4",
            )

            assertEquals(SignalSlingerReleaseSelectionSource.DOWNLOADED, selection.source)
            assertEquals("2.0.2", selection.release.version)
            assertEquals("HW-3.4", selection.release.board)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun latestDownloadReplacesOlderResidentForSameBoardOnly() {
        val root = Files.createTempDirectory("signalslinger-release-cache-test").toFile()
        val latestApi = URI("https://api.github.test/releases/latest")
        val zipUrl = "https://github.com/OpenARDF/SignalSlinger/releases/download/v2.0.2/SignalSlinger-Release-Files-v2.0.2-HW-3.4.zip"
        try {
            root.resolve("HW-3.4/2.0.1").apply {
                mkdirs()
                resolve("SignalSlinger-Release-Info-v2.0.1-HW-3.4.json").writeText(manifest(version = "2.0.1", board = "HW-3.4"))
            }
            root.resolve("HW-3.5/2.0.1").apply {
                mkdirs()
                resolve("SignalSlinger-Release-Info-v2.0.1-HW-3.5.json").writeText(manifest(version = "2.0.1", board = "HW-3.5"))
            }

            val selection = SignalSlingerReleaseCache(
                rootDirectory = root,
                repositoryApiUrl = latestApi,
                downloadBytes = { uri ->
                    when (uri) {
                        latestApi -> """{"browser_download_url":"$zipUrl"}""".encodeToByteArray()
                        URI(zipUrl) -> releaseZip(version = "2.0.2", board = "HW-3.4")
                        else -> error("unexpected URI $uri")
                    }
                },
            ).selectLatestForUpdate(
                hardwareBuild = "3.4",
                currentFirmwareVersion = "2.0.1",
            )

            assertEquals(SignalSlingerReleaseSelectionSource.DOWNLOADED, selection.source)
            assertEquals("2.0.2", selection.release.version)
            assertNull(root.resolve("HW-3.4/2.0.1/SignalSlinger-Release-Info-v2.0.1-HW-3.4.json").takeIf { it.exists() })
            assertTrue(root.resolve("HW-3.4/2.0.2/SignalSlinger-Release-Info-v2.0.2-HW-3.4.json").isFile)
            assertTrue(root.resolve("HW-3.5/2.0.1/SignalSlinger-Release-Info-v2.0.1-HW-3.5.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun importsLocalReleasePackageIntoResidentCache() {
        val root = Files.createTempDirectory("signalslinger-release-cache-test").toFile()
        val packageFile = Files.createTempFile("signalslinger-release-package", ".zip").toFile()
        try {
            packageFile.writeBytes(releaseZip(version = "2.0.2", board = "HW-3.5"))

            val resident = SignalSlingerReleaseCache(root).importReleasePackage(packageFile)

            assertEquals("2.0.2", resident.release.version)
            assertEquals("HW-3.5", resident.release.board)
            assertTrue(root.resolve("HW-3.5/2.0.2/SignalSlinger-Release-Info-v2.0.2-HW-3.5.json").isFile)
        } finally {
            packageFile.delete()
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

    private fun releaseZip(
        version: String,
        board: String,
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("SignalSlinger-Release-Info-v$version-$board.json"))
            zip.write(manifest(version = version, board = board).encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
