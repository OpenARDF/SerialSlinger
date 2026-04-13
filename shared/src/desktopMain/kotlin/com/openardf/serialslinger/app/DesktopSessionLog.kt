package com.openardf.serialslinger.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class DesktopLogCategory(
    val label: String,
) {
    APP("APP"),
    SERIAL("SERIAL"),
    DEVICE("DEVICE"),
}

data class DesktopLogEntry(
    val message: String,
    val category: DesktopLogCategory = DesktopLogCategory.APP,
    val timestampMs: Long = System.currentTimeMillis(),
)

class DesktopSessionLog(
    private val rootDirectory: Path = defaultLogDirectory(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun logDirectory(): Path {
        Files.createDirectories(rootDirectory)
        return rootDirectory
    }

    fun currentLogFile(): Path {
        val date = LocalDate.now(clock).format(dateFormatter)
        return logDirectory().resolve("serialslinger-$date.log")
    }

    fun loadCurrentLogText(): String {
        val file = currentLogFile()
        if (!Files.exists(file)) {
            return ""
        }

        return Files.readString(file)
    }

    fun appendSection(title: String, entries: List<DesktopLogEntry>): String {
        val rendered = renderSection(title, entries)
        Files.writeString(
            currentLogFile(),
            rendered,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        return rendered
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
                .filter { Files.isRegularFile(it) && it.fileName.toString().startsWith("serialslinger-") && it.fileName.toString().endsWith(".log") }
                .toList()

            logFiles.forEach(Files::deleteIfExists)
            return logFiles.size
        }
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

    private fun formatTime(timestampMs: Long): String {
        return Instant.ofEpochMilli(timestampMs)
            .atZone(clock.zone)
            .toLocalTime()
            .format(timeFormatter)
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
    }
}
