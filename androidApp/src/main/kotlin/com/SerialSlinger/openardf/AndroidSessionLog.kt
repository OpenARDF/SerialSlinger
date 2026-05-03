@file:Suppress("PackageName")
package com.SerialSlinger.openardf

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

data class AndroidTemperatureLogFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

class AndroidSessionLog(
    private val rootDirectory: File,
    private val appVersion: String,
    private val platformLabel: String,
) {
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dateTimeFormatter = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var temperatureLogFile: File? = null

    fun logDirectory(): File {
        rootDirectory.mkdirs()
        return rootDirectory
    }

    fun currentLogFile(): File {
        temperatureLogFile?.let { return it }
        val date = dateFormatter.format(Date())
        return File(logDirectory(), "serialslinger-$date.log")
    }

    fun beginTemperatureLog(): File {
        archiveCurrentDebugLog()
        val file = File(logDirectory(), "serialslinger-temperature-${dateTimeFormatter.format(Date())}.csv")
        file.writeText("timestamp,temperature_c,external_battery_v,internal_battery_v\n")
        temperatureLogFile = file
        return file
    }

    fun endTemperatureLog(): File {
        temperatureLogFile = null
        archiveCurrentDebugLog()
        return ensureCurrentLogFile()
    }

    fun appendTemperatureSample(
        timestamp: String,
        temperatureC: Double?,
        externalBatteryVolts: Double?,
        internalBatteryVolts: Double?,
    ) {
        val file = temperatureLogFile ?: return
        file.appendText(
            "$timestamp," +
                "${temperatureC?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()}," +
                "${externalBatteryVolts?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()}," +
                "${internalBatteryVolts?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()}\n",
        )
    }

    fun ensureCurrentLogFile(): File {
        val file = currentLogFile()
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(
                renderHeader() +
                renderSection(
                    title = "Android Session Log",
                    entries = listOf(AndroidLogEntry(message = "<no log entries captured yet>")),
                ),
            )
        } else {
            ensureHeaderAtTop(file)
        }
        return file
    }

    fun appendSection(title: String, entries: List<AndroidLogEntry>): String {
        if (temperatureLogFile != null) {
            return ""
        }
        val file = currentLogFile()
        val header = headerTextIfNeeded(file)
        val rendered = renderSection(title, entries)
        val written = header + rendered
        file.appendText(written)
        return written
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

    fun listTemperatureLogFiles(): List<AndroidTemperatureLogFile> {
        return temperatureLogFiles()
            .sortedByDescending { it.lastModified() }
            .map { file ->
                AndroidTemperatureLogFile(
                    file = file,
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                )
            }
    }

    fun deleteTemperatureLog(name: String): Boolean {
        val file = temperatureLogFileByName(name) ?: return false
        if (temperatureLogFile?.canonicalPath == file.canonicalPath) {
            return false
        }
        return file.delete()
    }

    fun deleteAllTemperatureLogs(): Int {
        return temperatureLogFiles()
            .filter { file -> temperatureLogFile?.canonicalPath != file.canonicalPath }
            .count { file -> file.delete() }
    }

    fun deleteAllLogs(): Int {
        val directory = logDirectory()
        val logFiles =
            directory.listFiles { file ->
                file.isFile &&
                    file.name.startsWith("serialslinger-") &&
                    (file.name.endsWith(".log") || file.name.endsWith(".csv"))
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

    private fun headerTextIfNeeded(file: File): String {
        if (!file.exists() || file.length() == 0L) {
            return renderHeader()
        }
        ensureHeaderAtTop(file)
        return ""
    }

    private fun ensureHeaderAtTop(file: File) {
        if (!file.exists() || file.length() == 0L) {
            return
        }
        val text = file.readText()
        if (text.startsWith("SerialSlinger ")) {
            return
        }
        file.writeText(renderHeader() + text)
    }

    private fun renderHeader(): String {
        return buildString {
            appendLine("SerialSlinger $appVersion")
            appendLine("Platform: $platformLabel")
            appendLine()
        }
    }

    private fun temperatureLogFiles(): List<File> {
        return logDirectory()
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith("serialslinger-temperature-") &&
                    file.name.endsWith(".csv")
            }.orEmpty()
            .toList()
    }

    private fun temperatureLogFileByName(name: String): File? {
        if (!name.startsWith("serialslinger-temperature-") || !name.endsWith(".csv") || name.contains('/')) {
            return null
        }
        val file = File(logDirectory(), name)
        return file.takeIf { it.isFile }
    }

    private fun archiveCurrentDebugLog(): File? {
        val file = File(logDirectory(), "serialslinger-${dateFormatter.format(Date())}.log")
        if (!file.exists()) {
            return null
        }
        val archived = nextArchiveFile(file)
        file.renameTo(archived)
        return archived
    }

    private fun nextArchiveFile(currentFile: File): File {
        val stem = currentFile.name.removeSuffix(".log")
        var index = 1
        while (true) {
            val candidate = File(currentFile.parentFile, "$stem-$index.log")
            if (!candidate.exists()) {
                return candidate
            }
            index += 1
        }
    }
}
