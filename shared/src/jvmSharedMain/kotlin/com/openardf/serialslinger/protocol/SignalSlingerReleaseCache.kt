package com.openardf.serialslinger.protocol

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.streams.asSequence

data class SignalSlingerResidentRelease(
    val manifestFile: File,
    val release: SignalSlingerReleaseInfo,
)

enum class SignalSlingerReleaseSelectionSource {
    DOWNLOADED,
    RESIDENT,
}

data class SignalSlingerReleaseSelection(
    val residentRelease: SignalSlingerResidentRelease,
    val source: SignalSlingerReleaseSelectionSource,
    val message: String,
    val downloadFailure: String? = null,
) {
    val manifestFile: File
        get() = residentRelease.manifestFile

    val release: SignalSlingerReleaseInfo
        get() = residentRelease.release
}

class SignalSlingerReleaseCache(
    private val rootDirectory: File,
    private val repositoryApiUrl: URI = URI("https://api.github.com/repos/OpenARDF/SignalSlinger/releases/latest"),
    private val repositoryReleasesApiUrl: URI = URI("https://api.github.com/repos/OpenARDF/SignalSlinger/releases?per_page=20"),
) {
    fun selectLatestForUpdate(
        hardwareBuild: String,
        currentFirmwareVersion: String?,
        allowResidentFallback: Boolean = true,
        overrideBoard: String? = null,
    ): SignalSlingerReleaseSelection {
        val requestedHardware = overrideBoard?.substringAfter("HW-", overrideBoard)?.substringAfter("Board-", overrideBoard) ?: hardwareBuild
        require(requestedHardware.isNotBlank()) {
            "Load the attached SignalSlinger before checking updates so SerialSlinger can choose the correct hardware package."
        }
        val resident = latestResidentForHardware(requestedHardware)
        val residentIsNewer =
            resident != null &&
                SignalSlingerFirmwareUpdate.compareVersionStrings(resident.release.version, currentFirmwareVersion) > 0

        val latest =
            try {
                downloadLatestForHardware(requestedHardware)
            } catch (error: Exception) {
                if (allowResidentFallback && residentIsNewer) {
                    return SignalSlingerReleaseSelection(
                        residentRelease = resident,
                        source = SignalSlingerReleaseSelectionSource.RESIDENT,
                        message = "Using resident SignalSlinger ${resident.release.version} update for ${resident.release.board}.",
                        downloadFailure = error.message ?: error.toString(),
                    )
                }
                throw IllegalStateException(
                    buildString {
                        append("SerialSlinger could not download a SignalSlinger update")
                        if (resident == null) {
                            append(", and no resident update is available")
                        } else {
                            append(", and the resident update is not newer than the connected firmware")
                        }
                        append(".")
                    },
                    error,
                )
            }

        if (SignalSlingerFirmwareUpdate.compareVersionStrings(latest.release.version, currentFirmwareVersion) <= 0) {
            if (residentIsNewer) {
                return SignalSlingerReleaseSelection(
                    residentRelease = resident,
                    source = SignalSlingerReleaseSelectionSource.RESIDENT,
                    message = "Using resident SignalSlinger ${resident.release.version} update for ${resident.release.board}.",
                )
            }
            throw IllegalStateException("The connected SignalSlinger already has firmware ${currentFirmwareVersion.orEmpty().ifBlank { latest.release.version }}.")
        }

        return SignalSlingerReleaseSelection(
            residentRelease = latest,
            source = SignalSlingerReleaseSelectionSource.DOWNLOADED,
            message = "Downloaded SignalSlinger ${latest.release.version} update for ${latest.release.board}.",
        )
    }

    fun availableGitHubReleaseVersions(): List<String> {
        val releasesJson = readHttpsText(repositoryReleasesApiUrl)
        return Regex(""""tag_name"\s*:\s*"v?([^"]+)"""")
            .findAll(releasesJson)
            .map { it.groupValues[1] }
            .filter { version -> SignalSlingerFirmwareUpdate.compareVersionStrings(version, "0.0.0") > 0 }
            .distinct()
            .sortedWith { first, second -> -SignalSlingerFirmwareUpdate.compareVersionStrings(first, second) }
            .toList()
    }

    fun selectGitHubReleaseForUpdate(
        hardwareBuild: String,
        releaseVersion: String,
        overrideBoard: String? = null,
    ): SignalSlingerReleaseSelection {
        val requestedHardware = overrideBoard?.substringAfter("HW-", overrideBoard)?.substringAfter("Board-", overrideBoard) ?: hardwareBuild
        require(requestedHardware.isNotBlank()) {
            "Load the attached SignalSlinger before checking updates so SerialSlinger can choose the correct hardware package."
        }
        val selected = downloadReleaseForHardware(
            hardwareBuild = requestedHardware,
            releaseVersion = releaseVersion,
            replaceBoardCache = false,
        )
        return SignalSlingerReleaseSelection(
            residentRelease = selected,
            source = SignalSlingerReleaseSelectionSource.DOWNLOADED,
            message = "Downloaded SignalSlinger ${selected.release.version} update for ${selected.release.board}.",
        )
    }

    fun availableResidentBoards(): List<String> {
        if (!rootDirectory.isDirectory) {
            return emptyList()
        }
        return Files.walk(rootDirectory.toPath()).use { paths ->
            paths
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { path -> path.fileName.toString().startsWith("SignalSlinger-Release-Info-") }
                .filter { path -> path.fileName.toString().endsWith(".json") }
                .mapNotNull { path ->
                    runCatching {
                        SignalSlingerFirmwareUpdate.parseReleaseInfo(Files.readString(path)).board
                    }.getOrNull()
                }
                .distinct()
                .sorted()
                .toList()
        }
    }

    fun latestResidentForHardware(hardwareBuild: String): SignalSlingerResidentRelease? {
        if (!rootDirectory.isDirectory) {
            return null
        }
        return Files.walk(rootDirectory.toPath()).use { paths ->
            paths
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { path -> path.fileName.toString().startsWith("SignalSlinger-Release-Info-") }
                .filter { path -> path.fileName.toString().endsWith(".json") }
                .mapNotNull { path ->
                    runCatching {
                        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(Files.readString(path))
                        if (SignalSlingerFirmwareUpdate.hardwareMatchesBoard(hardwareBuild, release.board)) {
                            SignalSlingerResidentRelease(path.toFile(), release)
                        } else {
                            null
                        }
                    }.getOrNull()
                }
                .maxWithOrNull { first, second ->
                    SignalSlingerFirmwareUpdate.compareVersionStrings(first.release.version, second.release.version)
                }
        }
    }

    private fun downloadLatestForHardware(hardwareBuild: String): SignalSlingerResidentRelease {
        return downloadReleaseForHardware(
            hardwareBuild = hardwareBuild,
            releaseVersion = null,
            replaceBoardCache = true,
        )
    }

    private fun downloadReleaseForHardware(
        hardwareBuild: String,
        releaseVersion: String?,
        replaceBoardCache: Boolean,
    ): SignalSlingerResidentRelease {
        val releaseApiUrl =
            releaseVersion
                ?.trim()
                ?.removePrefix("v")
                ?.takeIf(String::isNotBlank)
                ?.let { version -> URI("https://api.github.com/repos/OpenARDF/SignalSlinger/releases/tags/v$version") }
                ?: repositoryApiUrl
        val releaseJson = readHttpsText(releaseApiUrl)
        val assetUrls = Regex(""""browser_download_url"\s*:\s*"([^"]+)"""")
            .findAll(releaseJson)
            .map { it.groupValues[1] }
            .filter { it.startsWith("https://github.com/OpenARDF/SignalSlinger/releases/download/") }
            .toList()
        val hardwareToken = hardwareBuild.filter { it.isLetterOrDigit() }
        val zipUrl = assetUrls.firstOrNull { url ->
            url.endsWith(".zip") &&
                url.contains("Release-Files") &&
                url.filter { it.isLetterOrDigit() }.contains(hardwareToken, ignoreCase = true)
        } ?: error("No SignalSlinger release package was found on GitHub for hardware $hardwareBuild.")

        val uri = URI(zipUrl)
        require(uri.scheme == "https" && uri.host == "github.com") {
            "SignalSlinger release asset URL is not trusted."
        }
        val zipBytes = readHttpsBytes(uri)
        rootDirectory.parentFile?.mkdirs()
        val stagingDir = Files.createTempDirectory(rootDirectory.toPath().parent ?: rootDirectory.toPath(), "serialslinger-update-")
        extractZipSafely(zipBytes, stagingDir.toFile())
        val manifestFile = findReleaseManifest(stagingDir.toFile())
            ?: error("Downloaded SignalSlinger package did not contain release info.")
        val release = SignalSlingerFirmwareUpdate.parseReleaseInfo(manifestFile.readText())
        require(SignalSlingerFirmwareUpdate.hardwareMatchesBoard(hardwareBuild, release.board)) {
            "Downloaded update package ${release.board} does not match hardware $hardwareBuild."
        }

        val boardDir = rootDirectory.resolve(release.board)
        if (replaceBoardCache && boardDir.exists()) {
            boardDir.deleteRecursively()
        }
        boardDir.mkdirs()
        val targetDir = boardDir.resolve(release.version)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        if (!stagingDir.toFile().renameTo(targetDir)) {
            targetDir.mkdirs()
            stagingDir.toFile().copyRecursively(targetDir, overwrite = true)
            stagingDir.toFile().deleteRecursively()
        }
        return SignalSlingerResidentRelease(
            manifestFile = requireNotNull(findReleaseManifest(targetDir)) {
                "Cached SignalSlinger package did not contain release info."
            },
            release = release,
        )
    }

    private fun findReleaseManifest(directory: File): File? {
        if (!directory.isDirectory) {
            return null
        }
        return Files.walk(directory.toPath()).use { paths ->
            paths
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().startsWith("SignalSlinger-Release-Info-") }
                .filter { it.fileName.toString().endsWith(".json") }
                .firstOrNull()
                ?.toFile()
        }
    }

    private fun extractZipSafely(
        zipBytes: ByteArray,
        destination: File,
    ) {
        destination.mkdirs()
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name
                require(!entryName.startsWith("/") && !entryName.contains("..")) {
                    "SignalSlinger release package contains an unsafe file path."
                }
                if (!entry.isDirectory) {
                    val output = destination.resolve(entryName).normalize()
                    require(output.toPath().startsWith(destination.toPath())) {
                        "SignalSlinger release package contains an unsafe file path."
                    }
                    output.parentFile?.mkdirs()
                    output.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun readHttpsText(uri: URI): String = readHttpsBytes(uri).decodeToString()

    private fun readHttpsBytes(uri: URI): ByteArray {
        require(uri.scheme == "https") { "Only HTTPS downloads are supported." }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        connection.setRequestProperty("User-Agent", "SerialSlinger")
        return try {
            require(connection.responseCode in 200..299) {
                "Download failed with HTTP ${connection.responseCode}."
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
