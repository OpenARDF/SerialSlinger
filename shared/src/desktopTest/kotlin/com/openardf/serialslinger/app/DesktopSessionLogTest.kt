package com.openardf.serialslinger.app

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSessionLogTest {
    @Test
    fun writesDailyLogFileWithDateInNameAndTimestampedEntries() {
        val tempDirectory = Files.createTempDirectory("serialslinger-log-test")
        val log = DesktopSessionLog(
            rootDirectory = tempDirectory,
            clock = Clock.fixed(Instant.parse("2026-04-10T14:22:33Z"), ZoneId.of("UTC")),
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
        assertTrue(rendered.contains("[14:22:33] == Auto Detect =="))
        assertTrue(rendered.contains("[14:22:33] [DEVICE] Detected /dev/tty.usbserial-1234"))
        assertEquals(rendered, Files.readString(file))
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
}
