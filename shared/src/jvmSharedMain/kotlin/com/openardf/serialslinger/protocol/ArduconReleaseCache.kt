package com.openardf.serialslinger.protocol

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.streams.asSequence

data class ArduconResidentRelease(
    val manifestFile: File,
    val release: ArduconReleaseInfo,
)

enum class ArduconReleaseSelectionSource {
    DOWNLOADED,
    RESIDENT,
}

data class ArduconReleaseSelection(
    val residentRelease: ArduconResidentRelease,
    val source: ArduconReleaseSelectionSource,
    val message: String,
    val downloadFailure: String? = null,
) {
    val manifestFile: File
        get() = residentRelease.manifestFile

    val release: ArduconReleaseInfo
        get() = residentRelease.release
}

class ArduconAlreadyCurrentException(
    version: String,
) : IllegalStateException("The connected Arducon already has firmware $version.")

class ArduconReleaseCache(
    private val rootDirectory: File,
    private val repositoryApiUrl: URI = URI("https://api.github.com/repos/OpenARDF/Arducon/releases/latest"),
    private val repositoryReleasesApiUrl: URI = URI("https://api.github.com/repos/OpenARDF/Arducon/releases?per_page=20"),
    private val downloadBytes: (URI) -> ByteArray = ::downloadHttpsBytes,
) {
    fun selectLatestForUpdate(
        currentFirmwareVersion: String?,
        allowResidentFallback: Boolean = true,
        preferResidentWhenLatestMatches: Boolean = true,
    ): ArduconReleaseSelection {
        val resident = latestResident()
        val residentIsNewer =
            resident != null &&
                SignalSlingerFirmwareUpdate.compareVersionStrings(resident.release.version, currentFirmwareVersion) > 0

        val latestVersion =
            try {
                latestReleaseVersion()
            } catch (_: Exception) {
                null
            }
        if (
            preferResidentWhenLatestMatches &&
            residentIsNewer &&
            latestVersion != null &&
            SignalSlingerFirmwareUpdate.compareVersionStrings(resident.release.version, latestVersion) >= 0
        ) {
            return ArduconReleaseSelection(
                residentRelease = resident,
                source = ArduconReleaseSelectionSource.RESIDENT,
                message = "Using resident Arducon ${resident.release.version} update for ${resident.release.board}.",
            )
        }

        val latest =
            try {
                downloadLatest(replaceCache = true)
            } catch (error: Exception) {
                if (allowResidentFallback && residentIsNewer) {
                    return ArduconReleaseSelection(
                        residentRelease = resident,
                        source = ArduconReleaseSelectionSource.RESIDENT,
                        message = "Using resident Arducon ${resident.release.version} update for ${resident.release.board}.",
                        downloadFailure = error.message ?: error.toString(),
                    )
                }
                throw IllegalStateException(
                    buildString {
                        append("SerialSlinger could not download an Arducon update")
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

        val latestVersionComparison = SignalSlingerFirmwareUpdate.compareVersionStrings(latest.release.version, currentFirmwareVersion)
        if (latestVersionComparison <= 0) {
            if (residentIsNewer) {
                return ArduconReleaseSelection(
                    residentRelease = resident,
                    source = ArduconReleaseSelectionSource.RESIDENT,
                    message = "Using resident Arducon ${resident.release.version} update for ${resident.release.board}.",
                )
            }
            throw ArduconAlreadyCurrentException(currentFirmwareVersion.orEmpty().ifBlank { latest.release.version })
        }

        return ArduconReleaseSelection(
            residentRelease = latest,
            source = ArduconReleaseSelectionSource.DOWNLOADED,
            message = "Downloaded Arducon ${latest.release.version} update for ${latest.release.board}.",
        )
    }

    fun selectGitHubReleaseForUpdate(releaseVersion: String): ArduconReleaseSelection {
        val selected = downloadRelease(
            releaseVersion = releaseVersion,
            replaceCache = true,
        )
        return ArduconReleaseSelection(
            residentRelease = selected,
            source = ArduconReleaseSelectionSource.DOWNLOADED,
            message = "Downloaded Arducon ${selected.release.version} update for ${selected.release.board}.",
        )
    }

    fun importReleasePackage(packageFile: File): ArduconResidentRelease {
        require(packageFile.isFile) {
            "Arducon release package `${packageFile.path}` was not found."
        }
        require(packageFile.extension.equals("zip", ignoreCase = true)) {
            "Arducon release package must be a ZIP file."
        }
        rootDirectory.parentFile?.mkdirs()
        val stagingDir = Files.createTempDirectory(rootDirectory.toPath().parent ?: rootDirectory.toPath(), "arducon-import-")
        try {
            extractZipSafely(packageFile.readBytes(), stagingDir.toFile())
            val manifestFile = findReleaseManifest(stagingDir.toFile())
                ?: error("Arducon package did not contain release info.")
            val release = ArduconFirmwareUpdate.parseReleaseInfo(manifestFile.readText())
            ArduconFirmwareUpdate.validateReleaseManifest(release)
            val targetDir = rootDirectory.resolve(release.board).resolve(release.version)
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.parentFile?.mkdirs()
            if (!stagingDir.toFile().renameTo(targetDir)) {
                targetDir.mkdirs()
                stagingDir.toFile().copyRecursively(targetDir, overwrite = true)
                stagingDir.toFile().deleteRecursively()
            }
            return ArduconResidentRelease(
                manifestFile = requireNotNull(findReleaseManifest(targetDir)) {
                    "Imported Arducon package did not contain release info."
                },
                release = release,
            )
        } catch (exception: Exception) {
            stagingDir.toFile().deleteRecursively()
            throw exception
        }
    }

    fun latestResident(): ArduconResidentRelease? {
        if (!rootDirectory.isDirectory) {
            return null
        }
        return Files.walk(rootDirectory.toPath()).use { paths ->
            paths
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .filter { path -> path.fileName.toString().startsWith("Arducon-Release-Info-") }
                .filter { path -> path.fileName.toString().endsWith(".json") }
                .mapNotNull { path ->
                    runCatching {
                        val release = ArduconFirmwareUpdate.parseReleaseInfo(Files.readString(path))
                        ArduconFirmwareUpdate.validateReleaseManifest(release)
                        ArduconResidentRelease(path.toFile(), release)
                    }.getOrNull()
                }
                .maxWithOrNull { first, second ->
                    SignalSlingerFirmwareUpdate.compareVersionStrings(first.release.version, second.release.version)
                }
        }
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

    private fun latestReleaseVersion(): String? {
        val releaseJson = readHttpsText(repositoryApiUrl)
        return Regex(""""tag_name"\s*:\s*"v?([^"]+)"""")
            .find(releaseJson)
            ?.groupValues
            ?.get(1)
    }

    private fun downloadLatest(replaceCache: Boolean): ArduconResidentRelease {
        return downloadRelease(
            releaseVersion = null,
            replaceCache = replaceCache,
        )
    }

    private fun downloadRelease(
        releaseVersion: String?,
        replaceCache: Boolean,
    ): ArduconResidentRelease {
        val releaseApiUrl =
            releaseVersion
                ?.trim()
                ?.removePrefix("v")
                ?.takeIf(String::isNotBlank)
                ?.let { version -> URI("https://api.github.com/repos/OpenARDF/Arducon/releases/tags/v$version") }
                ?: repositoryApiUrl
        val releaseJson = readHttpsText(releaseApiUrl)
        val assetUrls = Regex(""""browser_download_url"\s*:\s*"([^"]+)"""")
            .findAll(releaseJson)
            .map { it.groupValues[1] }
            .filter { it.startsWith("https://github.com/OpenARDF/Arducon/releases/download/") }
            .toList()
        val zipUrl = assetUrls.firstOrNull { url ->
            url.endsWith(".zip") &&
                url.contains("Arducon") &&
                url.contains("ATmega328P") &&
                url.contains("Release-Files")
        } ?: error("No Arducon release package was found on GitHub.")

        val uri = URI(zipUrl)
        require(uri.scheme == "https" && uri.host == "github.com") {
            "Arducon release asset URL is not trusted."
        }
        val zipBytes = readBytes(uri)
        rootDirectory.parentFile?.mkdirs()
        val stagingDir = Files.createTempDirectory(rootDirectory.toPath().parent ?: rootDirectory.toPath(), "arducon-update-")
        extractZipSafely(zipBytes, stagingDir.toFile())
        val manifestFile = findReleaseManifest(stagingDir.toFile())
            ?: error("Downloaded Arducon package did not contain release info.")
        val release = ArduconFirmwareUpdate.parseReleaseInfo(manifestFile.readText())
        ArduconFirmwareUpdate.validateReleaseManifest(release)

        val boardDir = rootDirectory.resolve(release.board)
        if (replaceCache && boardDir.exists()) {
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
        return ArduconResidentRelease(
            manifestFile = requireNotNull(findReleaseManifest(targetDir)) {
                "Cached Arducon package did not contain release info."
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
                .filter { it.fileName.toString().startsWith("Arducon-Release-Info-") }
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
                    "Arducon release package contains an unsafe file path."
                }
                if (!entry.isDirectory) {
                    val output = destination.resolve(entryName).normalize()
                    require(output.toPath().startsWith(destination.toPath())) {
                        "Arducon release package contains an unsafe file path."
                    }
                    output.parentFile?.mkdirs()
                    output.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun readHttpsText(uri: URI): String = readBytes(uri).decodeToString()

    private fun readBytes(uri: URI): ByteArray {
        require(uri.scheme == "https") { "Only HTTPS downloads are supported." }
        return downloadBytes(uri)
    }
}

private fun downloadHttpsBytes(uri: URI): ByteArray {
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
