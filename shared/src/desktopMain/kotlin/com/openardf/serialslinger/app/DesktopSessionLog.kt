package com.openardf.serialslinger.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DesktopLogCategory(
    val label: String,
) {
    APP("APP"),
    SERIAL("SERIAL"),
    DEVICE("DEVICE"),
    USER("USER"),
}

data class DesktopLogEntry(
    val message: String,
    val category: DesktopLogCategory = DesktopLogCategory.APP,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class DesktopTemperatureLogFile(
    val path: Path,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

class DesktopSessionLog(
    private val rootDirectory: Path = defaultLogDirectory(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val appVersion: String = SerialSlingerVersion.displayVersion,
    private val platformLabel: String = defaultPlatformLabel(),
) {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var temperatureLogFile: Path? = null

    fun logDirectory(): Path {
        Files.createDirectories(rootDirectory)
        return rootDirectory
    }

    fun currentLogFile(): Path {
        temperatureLogFile?.let { return it }
        val date = LocalDate.now(clock).format(dateFormatter)
        return logDirectory().resolve("serialslinger-$date.log")
    }

    fun beginTemperatureLog(): Path {
        archiveCurrentLog()
        val file = logDirectory().resolve("serialslinger-temperature-${LocalDateTime.now(clock).format(dateTimeFormatter)}.csv")
        Files.writeString(file, "timestamp,temperature_c,external_battery_v,internal_battery_v\n", StandardOpenOption.CREATE_NEW)
        temperatureLogFile = file
        return file
    }

    fun endTemperatureLog(): Path {
        temperatureLogFile = null
        archiveCurrentLog()
        return ensureCurrentLogFile()
    }

    fun appendTemperatureSample(
        timestamp: LocalDateTime,
        temperatureC: Double?,
        externalBatteryVolts: Double?,
        internalBatteryVolts: Double?,
    ) {
        val file = temperatureLogFile ?: return
        val line = "${timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}," +
            "${temperatureC?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()}," +
            "${externalBatteryVolts?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()}," +
            "${internalBatteryVolts?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()}\n"
        Files.writeString(file, line, StandardOpenOption.APPEND)
    }

    fun loadCurrentLogText(): String {
        val file = currentLogFile()
        if (!Files.exists(file)) {
            return ""
        }

        return Files.readString(file)
    }

    fun ensureCurrentLogFile(): Path {
        val file = currentLogFile()
        if (!Files.exists(file)) {
            Files.writeString(file, renderHeader(), StandardOpenOption.CREATE_NEW)
        } else {
            ensureHeaderAtTop(file)
        }
        return file
    }

    fun appendSection(title: String, entries: List<DesktopLogEntry>): String {
        if (temperatureLogFile != null) {
            return ""
        }
        val file = currentLogFile()
        val header = headerTextIfNeeded(file)
        val rendered = renderSection(title, entries)
        val written = header + rendered
        Files.writeString(
            file,
            written,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return written
    }

    fun appendPlainSection(title: String, lines: List<String>): String {
        val timestampMs = clock.millis()
        return appendSection(
            title = title,
            entries = lines.map { line ->
                DesktopLogEntry(line, DesktopLogCategory.APP, timestampMs)
            },
        )
    }

    fun archiveCurrentLog(): Path? {
        val file = currentLogFile()
        if (!Files.exists(file)) {
            return null
        }

        val archivedFile = nextArchiveFile(file)
        Files.move(file, archivedFile, StandardCopyOption.REPLACE_EXISTING)
        return archivedFile
    }

    fun deleteAllLogs(): Int {
        val directory = logDirectory()
        if (!Files.exists(directory)) {
            return 0
        }

        Files.list(directory).use { paths ->
            val logFiles = paths
                .filter {
                    val name = it.fileName.toString()
                    Files.isRegularFile(it) &&
                        name.startsWith("serialslinger-") &&
                        (name.endsWith(".log") || name.endsWith(".csv"))
                }
                .toList()

            logFiles.forEach(Files::deleteIfExists)
            return logFiles.size
        }
    }

    fun listTemperatureLogFiles(): List<DesktopTemperatureLogFile> {
        return temperatureLogFiles()
            .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
            .map { file ->
                DesktopTemperatureLogFile(
                    path = file,
                    name = file.fileName.toString(),
                    sizeBytes = Files.size(file),
                    lastModifiedMs = Files.getLastModifiedTime(file).toMillis(),
                )
            }
    }

    fun deleteTemperatureLog(path: Path): Boolean {
        val file = path.normalize()
        if (!isTemperatureLogFile(file) || file.parent != logDirectory()) {
            return false
        }
        if (temperatureLogFile?.normalize() == file) {
            return false
        }
        return Files.deleteIfExists(file)
    }

    fun deleteAllTemperatureLogs(): Int {
        return temperatureLogFiles()
            .filter { file -> temperatureLogFile?.normalize() != file.normalize() }
            .count { file -> Files.deleteIfExists(file) }
    }

    fun renderSection(title: String, entries: List<DesktopLogEntry>): String {
        val titleTimestampMs = entries.firstOrNull()?.timestampMs ?: clock.millis()
        return buildString {
            append("[${formatTime(titleTimestampMs)}] == ")
            append(title)
            append(" ==\n")
            if (entries.isEmpty()) {
                append("[${formatTime(titleTimestampMs)}] [")
                append(DesktopLogCategory.APP.label)
                append("] <no lines>\n")
            } else {
                entries.forEach { entry ->
                    append("[${formatTime(entry.timestampMs)}] [")
                    append(entry.category.label)
                    append("] ")
                    append(entry.message)
                    append('\n')
                }
            }
            append('\n')
        }
    }

    private fun headerTextIfNeeded(file: Path): String {
        if (!Files.exists(file) || Files.size(file) == 0L) {
            return renderHeader()
        }
        ensureHeaderAtTop(file)
        return ""
    }

    private fun ensureHeaderAtTop(file: Path) {
        if (!Files.exists(file) || Files.size(file) == 0L) {
            return
        }
        val text = Files.readString(file)
        if (text.startsWith("SerialSlinger ")) {
            return
        }
        Files.writeString(file, renderHeader() + text)
    }

    private fun renderHeader(): String {
        return buildString {
            appendLine("SerialSlinger $appVersion")
            appendLine("Platform: $platformLabel")
            appendLine()
        }
    }

    private fun formatTime(timestampMs: Long): String {
        return Instant.ofEpochMilli(timestampMs)
            .atZone(clock.zone)
            .toLocalTime()
            .format(timeFormatter)
    }

    private fun temperatureLogFiles(): List<Path> {
        val directory = logDirectory()
        if (!Files.exists(directory)) {
            return emptyList()
        }
        Files.list(directory).use { paths ->
            return paths
                .filter(::isTemperatureLogFile)
                .toList()
        }
    }

    private fun isTemperatureLogFile(file: Path): Boolean {
        val name = file.fileName.toString()
        return Files.isRegularFile(file) &&
            name.startsWith("serialslinger-temperature-") &&
            name.endsWith(".csv")
    }

    private fun nextArchiveFile(currentFile: Path): Path {
        val fileName = currentFile.fileName.toString()
        val stem = fileName.removeSuffix(".log")
        var index = 1
        while (true) {
            val candidate = currentFile.parent.resolve("$stem-$index.log")
            if (!Files.exists(candidate)) {
                return candidate
            }
            index += 1
        }
    }

    companion object {
        fun defaultLogDirectory(): Path {
            val userHome = Path.of(System.getProperty("user.home"))
            return userHome.resolve("Documents").resolve("SerialSlinger").resolve("logs")
        }

        private fun defaultPlatformLabel(): String {
            val name = System.getProperty("os.name").orEmpty().ifBlank { "Unknown OS" }
            val version = System.getProperty("os.version").orEmpty()
            val arch = System.getProperty("os.arch").orEmpty()
            return listOf(name, version, arch)
                .filter(String::isNotBlank)
                .joinToString(" ")
        }
    }
}
