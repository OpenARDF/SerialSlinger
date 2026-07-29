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
import java.util.concurrent.atomic.AtomicBoolean

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

class DesktopStreamingLogSection internal constructor(
    val initialRenderedText: String,
    private val appendEntry: (DesktopLogEntry) -> String,
    private val finishSection: () -> String,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun append(entry: DesktopLogEntry): String {
        check(!closed.get()) { "Cannot append to a closed log section." }
        return appendEntry(entry)
    }

    fun finish(): String {
        if (!closed.compareAndSet(false, true)) {
            return ""
        }
        return finishSection()
    }

    override fun close() {
        finish()
    }
}

class DesktopStreamingLogEntries(
    private val section: DesktopStreamingLogSection,
    private val renderedTextSink: (String) -> Unit,
) : AbstractMutableList<DesktopLogEntry>(), AutoCloseable {
    private val storedEntries = mutableListOf<DesktopLogEntry>()

    init {
        renderedTextSink(section.initialRenderedText)
    }

    override val size: Int
        get() = storedEntries.size

    override fun get(index: Int): DesktopLogEntry = storedEntries[index]

    override fun add(index: Int, element: DesktopLogEntry) {
        require(index == storedEntries.size) {
            "Streaming log entries can only be appended."
        }
        storedEntries.add(element)
        renderedTextSink(section.append(element))
    }

    override fun set(index: Int, element: DesktopLogEntry): DesktopLogEntry {
        throw UnsupportedOperationException("Streaming log entries cannot be replaced.")
    }

    override fun removeAt(index: Int): DesktopLogEntry {
        throw UnsupportedOperationException("Streaming log entries cannot be removed.")
    }

    fun finish() {
        renderedTextSink(section.finish())
    }

    override fun close() {
        finish()
    }
}

class DesktopSessionLog(
    private val rootDirectory: Path = defaultLogDirectory(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val appVersion: String = SerialSlingerVersion.displayVersion,
    private val platformLabel: String = defaultPlatformLabel(),
) {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val writeLock = Any()
    private var temperatureLogFile: Path? = null

    fun logDirectory(): Path {
        Files.createDirectories(rootDirectory)
        return rootDirectory
    }

    fun currentLogFile(): Path {
        val date = LocalDate.now(clock).format(dateFormatter)
        return logDirectory().resolve("serialslinger-$date.log")
    }

    fun beginTemperatureLog(deviceType: String): Path {
        val deviceSlug = deviceType.lowercase(Locale.US).replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        val file = logDirectory().resolve(
            "serialslinger-temperature-${deviceSlug.ifBlank { "device" }}-${LocalDateTime.now(clock).format(dateTimeFormatter)}.csv",
        )
        Files.writeString(
            file,
            "device_type,timestamp,temperature_c,external_battery_v,internal_battery_v\n",
            StandardOpenOption.CREATE_NEW,
        )
        temperatureLogFile = file
        return file
    }

    fun endTemperatureLog(): Path {
        temperatureLogFile = null
        return ensureCurrentLogFile()
    }

    fun appendTemperatureSample(
        deviceType: String,
        timestamp: LocalDateTime,
        temperatureC: Double?,
        externalBatteryVolts: Double?,
        internalBatteryVolts: Double?,
    ) {
        val file = temperatureLogFile ?: return
        val line = "${csvEscape(deviceType)}," +
            "${timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}," +
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
        synchronized(writeLock) {
            val file = currentLogFile()
            val header = headerTextIfNeeded(file)
            val rendered = renderSection(title, entries)
            val written = header + rendered
            appendToFile(file, written)
            return written
        }
    }

    fun beginStreamingSection(
        title: String,
        timestampMs: Long = clock.millis(),
    ): DesktopStreamingLogSection {
        val file = currentLogFile()
        val initialText =
            synchronized(writeLock) {
                val written =
                    headerTextIfNeeded(file) +
                        "[${formatTime(timestampMs)}] == $title ==\n"
                appendToFile(file, written)
                written
            }
        return DesktopStreamingLogSection(
            initialRenderedText = initialText,
            appendEntry = { entry ->
                synchronized(writeLock) {
                    val rendered = renderEntry(entry)
                    appendToFile(file, rendered)
                    rendered
                }
            },
            finishSection = {
                synchronized(writeLock) {
                    appendToFile(file, "\n")
                    "\n"
                }
            },
        )
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
                append(
                    renderEntry(
                        DesktopLogEntry(
                            message = "<no lines>",
                            category = DesktopLogCategory.APP,
                            timestampMs = titleTimestampMs,
                        ),
                    ),
                )
            } else {
                entries.forEach { entry ->
                    append(renderEntry(entry))
                }
            }
            append('\n')
        }
    }

    private fun renderEntry(entry: DesktopLogEntry): String {
        return buildString {
            append("[${formatTime(entry.timestampMs)}] [")
            append(entry.category.label)
            append("] ")
            append(entry.message)
            append('\n')
        }
    }

    private fun appendToFile(file: Path, text: String) {
        Files.writeString(
            file,
            text,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
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

    private fun csvEscape(value: String): String {
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
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
