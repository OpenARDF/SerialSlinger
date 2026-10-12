package com.openardf.serialslinger.model

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FirmwareSettingsBackupStoreTest {
    private fun settings(stationId: String = "W8UM") = DeviceSettings.empty().copy(
        stationId = stationId,
        eventType = EventType.CLASSIC,
        foxRole = FoxRole.CLASSIC_3,
        currentTimeCompact = "261012120000",
        startTimeCompact = "261013090000",
        finishTimeCompact = "261013140000",
        defaultFrequencyHz = 3_520_000,
    )

    private fun snapshot(
        stationId: String = "W8UM",
        uid: String? = "42348279800068200107012700000000",
        version: String = "2.0.7",
    ) = DeviceSnapshot(
        info = DeviceInfo(productName = "SignalSlinger", deviceUniqueId = uid, softwareVersion = version),
        status = DeviceStatus(thermalShutdownThresholdC = 65.0, thermalShutdownEnabled = true),
        settings = settings(stationId),
    )

    @Test
    fun roundTripsAndDeletesTemporaryBackup() {
        val root = Files.createTempDirectory("firmware-backup-test")
        try {
            val store = FirmwareSettingsBackupStore(root.toFile())
            val settings = settings()
            val snapshot = snapshot()

            val file = store.save(snapshot, "2.1.0")
            assertTrue(file.isFile)
            val backup = store.load(file)
            assertEquals("2.1.0", backup.targetVersion)
            assertEquals(settings.copy(currentTimeCompact = null), backup.settings)
            assertEquals(settings, backup.settingsForRestore(settings))
            assertEquals(65, backup.thermalShutdownThresholdC)
            assertEquals(true, backup.thermalShutdownEnabled)
            assertEquals(
                listOf("TMP H 65", "TMP H ON"),
                backup.supplementalRestoreCommands(
                    snapshot.copy(status = DeviceStatus(thermalShutdownThresholdC = 70.0, thermalShutdownEnabled = false)),
                ),
            )
            assertTrue(backup.supplementalRestoreCommands(snapshot).isEmpty())
            backup.requireRestored(snapshot.copy(info = snapshot.info.copy(softwareVersion = "2.1.0")))
            val replacementSnapshot = snapshot.copy(settings = settings.copy(stationId = "SHOULD-NOT-REPLACE"))
            val interruptedPrevious = file.resolveSibling("${file.name}.previous")
            assertTrue(file.renameTo(interruptedPrevious))
            store.save(replacementSnapshot, "2.1.1")
            val retained = store.load(file)
            assertEquals("W8UM", retained.settings.stationId)
            assertEquals("2.1.1", retained.targetVersion)
            assertFalse(interruptedPrevious.exists())
            store.delete(file)
            assertFalse(file.exists())
            assertFalse(root.toFile().exists())
        } finally {
            root.toFile().deleteRecursively()
            root.deleteIfExists()
        }
    }

    @Test
    fun rejectsWrongDeviceVersionSettingsAndCorruptBackups() {
        val root = Files.createTempDirectory("firmware-backup-negative-test")
        try {
            val store = FirmwareSettingsBackupStore(root.toFile())
            assertFailsWith<IllegalArgumentException> { store.save(snapshot(uid = null), "2.1.0") }

            val file = store.save(snapshot(), "2.1.0")
            val backup = store.load(file)
            assertFailsWith<IllegalArgumentException> {
                backup.requireMatches(snapshot(uid = "different", version = "2.1.0"))
            }
            assertFailsWith<IllegalArgumentException> {
                backup.requireMatches(snapshot(version = "2.1.1"))
            }
            assertFailsWith<IllegalArgumentException> {
                backup.requireRestored(snapshot(stationId = "CHANGED", version = "2.1.0"))
            }
            assertFailsWith<IllegalArgumentException> {
                backup.requireRestored(
                    snapshot(version = "2.1.0").copy(
                        status = DeviceStatus(thermalShutdownThresholdC = 66.0, thermalShutdownEnabled = true),
                    ),
                )
            }

            file.writeText("format=99\nuid=invalid\n")
            assertFailsWith<IllegalArgumentException> { store.load(file) }
        } finally {
            root.toFile().deleteRecursively()
            root.deleteIfExists()
        }
    }
}
