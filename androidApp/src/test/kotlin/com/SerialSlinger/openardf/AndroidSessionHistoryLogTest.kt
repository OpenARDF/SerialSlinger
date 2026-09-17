@file:Suppress("PackageName")
package com.SerialSlinger.openardf

import java.nio.file.Files
import kotlin.test.*

class AndroidSessionHistoryLogTest {
    @Test fun oneSavedLogContainsExactHistoryAndReadableSummaryWithoutAdvancedMode() {
        val directory = Files.createTempDirectory("android-history-log").toFile()
        try {
            val log = AndroidSessionLog(directory, "test", "test")
            val raw = "* Session record: v=1 seq=22 base=1789542600 start=1789516800 finish=1789543257 at=1789543257 action=4 reason=1 flags=129 temp=272 limit=65"
            log.appendSection("Load", listOf(
                AndroidLogEntry("RX * Session history: v=1 count=1 capacity=7", AndroidLogCategory.SERIAL),
                AndroidLogEntry("RX $raw", AndroidLogCategory.SERIAL),
            ))
            val saved = log.loadCurrentLogText()
            assertTrue(saved.contains(raw))
            assertTrue(saved.contains("Device run history (captured readback)"))
            assertTrue(saved.contains("Completed at 2026-09-16 07:20:57"))
        } finally { directory.deleteRecursively() }
    }
}
