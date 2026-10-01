@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidSessionLogIdentityTest {
    @Test fun appendsCurrentIdentityOnceWhenDailyLogStartedUnderEarlierVersion() {
        val directory = Files.createTempDirectory("android-session-identity").toFile()
        try {
            val oldLog = AndroidSessionLog(directory, "2.0.17a", "Android 15")
            oldLog.appendSection("Existing", listOf(AndroidLogEntry("old")))

            val currentLog = AndroidSessionLog(directory, "2.0.22b", "Android 15")
            currentLog.appendSection("New", listOf(AndroidLogEntry("new")))
            currentLog.appendSection("Later", listOf(AndroidLogEntry("later")))

            val text = currentLog.loadCurrentLogText()
            val marker = "Session identity: SerialSlinger 2.0.22b; Platform: Android 15"
            assertTrue(text.startsWith("SerialSlinger 2.0.17a\nPlatform: Android 15\n\n"))
            assertEquals(1, text.windowed(marker.length).count { it == marker })
            assertTrue(text.indexOf(marker) < text.indexOf("== New =="))
        } finally {
            directory.deleteRecursively()
        }
    }
}
