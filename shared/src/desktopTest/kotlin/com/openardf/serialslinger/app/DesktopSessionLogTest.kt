package com.openardf.serialslinger.app

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSessionLogTest {
    @Test
    fun writesDailyLogFileWithDateInNameAndTimestampedEntries() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
            appVersion = "1.2.3-test",
            platformLabel = "TestOS 1.0",
        )

        val rendered = log.appendSection(
            title = "Auto Detect",
            entries = listOf(
                DesktopLogEntry(
                    message = "Detected /dev/tty.usbserial-1234",
                    category = DesktopLogCategory.DEVICE,
                    timestampMs = Instant.parse("2026-04-10T14:22:33Z").toEpochMilli(),
                ),
            ),
        )

        val file = log.currentLogFile()
        assertEquals("serialslinger-2026-04-10.log", file.fileName.toString())
        assertTrue(Files.exists(file))
        assertTrue(rendered.startsWith("SerialSlinger 1.2.3-test\nPlatform: TestOS 1.0\n\n"))
        assertTrue(rendered.contains("[14:22:33] == Auto Detect =="))
        assertTrue(rendered.contains("[14:22:33] [DEVICE] Detected /dev/tty.usbserial-1234"))
        assertEquals(rendered, Files.readString(file))
    }

    @Test
    fun keepsLogHeaderAtTopForCurrentDay() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
            appVersion = "1.2.3-test",
            platformLabel = "TestOS 1.0",
        )

        log.appendPlainSection("First", listOf("one"))
        log.appendPlainSection("Second", listOf("two"))

        val text = Files.readString(log.currentLogFile())
        assertTrue(text.startsWith("SerialSlinger 1.2.3-test\nPlatform: TestOS 1.0\n\n"))
        assertTrue(text.contains("[14:22:33] == First =="))
        assertTrue(text.contains("[14:22:33] == Second =="))
    }

    @Test
    fun backfillsMissingLogHeaderAtTopOfExistingCurrentDayLog() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
            appVersion = "1.2.3-test",
            platformLabel = "TestOS 1.0",
        )
        val file = log.currentLogFile()
        Files.writeString(file, "[14:00:00] == Existing ==\n[14:00:00] [APP] old\n\n")

        val rendered = log.appendPlainSection("New", listOf("new"))

        val text = Files.readString(file)
        assertEquals("[14:22:33] == New ==\n[14:22:33] [APP] new\n\n", rendered)
        assertTrue(text.startsWith("SerialSlinger 1.2.3-test\nPlatform: TestOS 1.0\n\n"))
        assertTrue(text.contains("[14:00:00] == Existing =="))
        assertTrue(text.contains("[14:22:33] == New =="))
    }

    @Test
    fun loadsExistingLogTextForCurrentDay() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC"))
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = clock,
        )
        val file = log.currentLogFile()
        Files.createDirectories(file.parent)
        Files.writeString(file, "[14:00:00] == Existing ==\n[14:00:00] hello\n\n")

        assertEquals("[14:00:00] == Existing ==\n[14:00:00] hello\n\n", log.loadCurrentLogText())
    }

    @Test
    fun rendersEachEntryWithItsOwnTimestamp() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
        )

        val rendered = log.renderSection(
            title = "Clone",
            entries = listOf(
                DesktopLogEntry(
                    message = "TX EVT S",
                    category = DesktopLogCategory.SERIAL,
                    timestampMs = Instant.parse("2026-04-10T14:22:31Z").toEpochMilli(),
                ),
                DesktopLogEntry(
                    message = "RX * Event:Sprint",
                    category = DesktopLogCategory.SERIAL,
                    timestampMs = Instant.parse("2026-04-10T14:22:32Z").toEpochMilli(),
                ),
            ),
        )

        assertTrue(rendered.contains("[14:22:31] == Clone =="))
        assertTrue(rendered.contains("[14:22:31] [SERIAL] TX EVT S"))
        assertTrue(rendered.contains("[14:22:32] [SERIAL] RX * Event:Sprint"))
    }

    @Test
    fun archivesCurrentLogToNextIndexedSuffix() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC"))
        val log = DesktopSessionLog(rootDirectory = tempDirectory, clock = clock)
        val currentFile = log.currentLogFile()
        Files.createDirectories(currentFile.parent)
        Files.writeString(currentFile, "current")
        Files.writeString(currentFile.parent.resolve("serialslinger-2026-04-10-1.log"), "older")

        val archived = log.archiveCurrentLog()

        assertEquals(currentFile.parent.resolve("serialslinger-2026-04-10-2.log"), archived)
        assertFalse(Files.exists(currentFile))
        assertEquals("current", Files.readString(archived))
    }

    @Test
    fun archiveCurrentLogReturnsNullWhenCurrentFileDoesNotExist() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
        )

        assertNull(log.archiveCurrentLog())
    }

    @Test
    fun deletesAllSerialSlingerLogFiles() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
        )
        val directory = log.logDirectory()
        Files.writeString(directory.resolve("serialslinger-2026-04-10.log"), "a")
        Files.writeString(directory.resolve("serialslinger-2026-04-10-1.log"), "b")
        Files.writeString(directory.resolve("notes.txt"), "keep")

        val deletedCount = log.deleteAllLogs()

        assertEquals(2, deletedCount)
        assertFalse(Files.exists(directory.resolve("serialslinger-2026-04-10.log")))
        assertFalse(Files.exists(directory.resolve("serialslinger-2026-04-10-1.log")))
        assertTrue(Files.exists(directory.resolve("notes.txt")))
    }

    @Test
    fun writesTemperatureLogWithInternalBatteryVoltageColumn() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
        )

        val file = log.beginTemperatureLog()
        log.appendTemperatureSample(
            timestamp = java.time.LocalDateTime.parse("2026-04-10T14:22:40"),
            temperatureC = 21.5,
            externalBatteryVolts = 12.1,
            internalBatteryVolts = 4.8,
        )

        assertEquals(
            "timestamp,temperature_c,external_battery_v,internal_battery_v\n" +
                "2026-04-10T14:22:40,21.5,12.1,4.8\n",
            Files.readString(file),
        )
    }

    @Test
    fun listsAndDeletesTemperatureLogsOnly() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
        )
        val directory = log.logDirectory()
        val temperatureLog = directory.resolve("serialslinger-temperature-2026-04-10-142233.csv")
        Files.writeString(temperatureLog, "temperature")
        Files.writeString(directory.resolve("serialslinger-2026-04-10.log"), "daily")
        Files.writeString(directory.resolve("notes.csv"), "keep")

        val logs = log.listTemperatureLogFiles()

        assertEquals(listOf("serialslinger-temperature-2026-04-10-142233.csv"), logs.map { it.name })
        assertTrue(log.deleteTemperatureLog(temperatureLog))
        assertFalse(Files.exists(temperatureLog))
        assertTrue(Files.exists(directory.resolve("serialslinger-2026-04-10.log")))
        assertTrue(Files.exists(directory.resolve("notes.csv")))
    }
}
