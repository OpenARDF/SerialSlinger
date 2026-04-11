package com.openardf.serialslinger.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
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
        return appendSection(
            title = title,
            entries = lines.map { line ->
                DesktopLogEntry(line, DesktopLogCategory.APP)
            },
        )
    }

    fun renderSection(title: String, entries: List<DesktopLogEntry>): String {
        val timestamp = LocalTime.now(clock).format(timeFormatter)
        return buildString {
            append("[$timestamp] == ")
            append(title)
            append(" ==\n")
            if (entries.isEmpty()) {
                append("[$timestamp] [")
                append(DesktopLogCategory.APP.label)
                append("] <no lines>\n")
            } else {
                entries.forEach { entry ->
                    append("[$timestamp] [")
                    append(entry.category.label)
                    append("] ")
                    append(entry.message)
                    append('\n')
                }
            }
            append('\n')
        }
    }

    companion object {
        fun defaultLogDirectory(): Path {
            val userHome = Path.of(System.getProperty("user.home"))
            return userHome.resolve("Documents").resolve("SerialSlinger").resolve("logs")
        }
    }
}
