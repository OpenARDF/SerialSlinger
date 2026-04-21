package com.openardf.serialslinger.androidapp

import com.openardf.serialslinger.session.SerialTraceEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AndroidLogCategory(
    val label: String,
) {
    APP("APP"),
    SERIAL("SERIAL"),
    USER("USER"),
}

data class AndroidLogEntry(
    val message: String,
    val category: AndroidLogCategory = AndroidLogCategory.APP,
    val timestampMs: Long = System.currentTimeMillis(),
)

class AndroidSessionLog(
    private val rootDirectory: File,
) {
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun logDirectory(): File {
        rootDirectory.mkdirs()
        return rootDirectory
    }

    fun currentLogFile(): File {
        val date = dateFormatter.format(Date())
        return File(logDirectory(), "serialslinger-$date.log")
    }

    fun appendSection(title: String, entries: List<AndroidLogEntry>): String {
        val rendered = renderSection(title, entries)
        currentLogFile().appendText(rendered)
        return rendered
    }

    fun appendCommandSection(
        command: String,
        source: String,
        success: Boolean,
        summary: String,
        traceEntries: List<SerialTraceEntry>,
    ): String {
        val summaryTimestamp = traceEntries.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        val entries =
            buildList {
                add(
                    AndroidLogEntry(
                        message = "source=$source success=$success",
                        category = AndroidLogCategory.APP,
                        timestampMs = summaryTimestamp,
                    ),
                )
                summary
                    .lines()
                    .map(String::trimEnd)
                    .filter(String::isNotBlank)
                    .forEach { line ->
                        add(
                            AndroidLogEntry(
                                message = line,
                                category = AndroidLogCategory.APP,
                                timestampMs = summaryTimestamp,
                            ),
                        )
                    }
                traceEntries.forEach { trace ->
                    add(
                        AndroidLogEntry(
                            message = "${trace.direction.label} ${trace.payload}",
                            category = AndroidLogCategory.SERIAL,
                            timestampMs = trace.timestampMs,
                        ),
                    )
                }
            }
        return appendSection(title = command, entries = entries)
    }

    fun loadCurrentLogText(): String {
        val file = currentLogFile()
        if (!file.exists()) {
            return ""
        }
        return file.readText()
    }

    fun loadCurrentLogTail(maxChars: Int = 16000): String {
        val text = loadCurrentLogText()
        if (text.isBlank()) {
            return ""
        }
        return if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun deleteAllLogs(): Int {
        val directory = logDirectory()
        val logFiles =
            directory.listFiles { file ->
                file.isFile && file.name.startsWith("serialslinger-") && file.name.endsWith(".log")
            }.orEmpty()
        logFiles.forEach { file -> file.delete() }
        return logFiles.size
    }

    fun renderSection(title: String, entries: List<AndroidLogEntry>): String {
        val titleTimestampMs = entries.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        return buildString {
            append("[${formatTime(titleTimestampMs)}] == ")
            append(title)
            append(" ==\n")
            if (entries.isEmpty()) {
                append("[${formatTime(titleTimestampMs)}] [${AndroidLogCategory.APP.label}] <no lines>\n")
            } else {
                entries.forEach { entry ->
                    append("[${formatTime(entry.timestampMs)}] [${entry.category.label}] ${entry.message}\n")
                }
            }
            append('\n')
        }
    }

    private fun formatTime(timestampMs: Long): String {
        return timeFormatter.format(Date(timestampMs))
    }
}
