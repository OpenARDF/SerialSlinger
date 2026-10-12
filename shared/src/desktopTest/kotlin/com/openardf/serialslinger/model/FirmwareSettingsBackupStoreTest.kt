package com.openardf.serialslinger.model

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertContains
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
        capabilities = DeviceCapabilities(supportsThermalShutdownMode = true),
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
            val updatedSnapshot = snapshot.copy(
                info = snapshot.info.copy(softwareVersion = "2.1.0"),
                settings = settings.copy(
                    currentTimeCompact = "261012120012",
                    dtmfPassword = "new-firmware-only-value",
                    temperatureCalibration = -110,
                ),
            )
            assertTrue(backup.restorationDifferences(updatedSnapshot).isEmpty())
            assertFalse(backup.requiresRestore(updatedSnapshot))
            assertEquals(
                updatedSnapshot.settings,
                backup.settingsForRestore(updatedSnapshot.settings),
            )
            backup.requireRestored(updatedSnapshot)
            val replacementSnapshot = snapshot.copy(settings = settings.copy(stationId = "SHOULD-NOT-REPLACE"))
            val interruptedPrevious = file.resolveSibling("${file.name}.previous")
            assertTrue(file.renameTo(interruptedPrevious))
            store.save(replacementSnapshot, "2.1.1")
            val retained = store.load(file)
            assertEquals("SHOULD-NOT-REPLACE", retained.settings.stationId)
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
    fun createsAndDeletesBackupForFirmwareThatPredatesDeviceIdentity() {
        val root = Files.createTempDirectory("firmware-backup-no-uid-test")
        try {
            val store = FirmwareSettingsBackupStore(root.toFile())
            val file = store.save(snapshot(uid = null, version = "2.0.2"), "2.1.0")
            val backup = store.load(file)

            assertEquals("legacy-no-uid", backup.deviceUniqueId)
            val updated = snapshot(version = "2.1.0")
            backup.requireRestored(updated)
            assertTrue(backup.canDeleteAfterSuccessfulVerification(updated))
            store.delete(file)
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
            val file = store.save(snapshot(), "2.1.0")
            val backup = store.load(file)
            assertFailsWith<IllegalArgumentException> {
                backup.requireMatches(snapshot(uid = "different", version = "2.1.0"))
            }
            backup.requireMatches(snapshot(uid = null, version = "2.1.0"))
            assertFailsWith<IllegalArgumentException> {
                backup.requireMatches(snapshot(version = "2.1.1"))
            }
            val settingsFailure = assertFailsWith<IllegalArgumentException> {
                backup.requireRestored(snapshot(stationId = "CHANGED", version = "2.1.0"))
            }
            assertContains(settingsFailure.message.orEmpty(), "Station ID expected 'W8UM', read 'CHANGED'")
            assertTrue(backup.requiresRestore(snapshot(stationId = "CHANGED", version = "2.1.0")))
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

    @Test
    fun reconcilesUniquePendingBackupWhenOlderFirmwareDoesNotReportIdentity() {
        val root = Files.createTempDirectory("firmware-backup-resume-test")
        try {
            val store = FirmwareSettingsBackupStore(root.toFile())
            val original = snapshot(stationId = "NZ0I", version = "2.0.7").copy(
                settings = settings("NZ0I").copy(lowFrequencyHz = 3_510_000),
            )
            val file = store.save(original, "2.0.2")

            val resumedFile = store.save(
                snapshot(stationId = "RESET", uid = null, version = "2.0.2"),
                "2.0.7",
            )

            assertEquals(file, resumedFile)
            val resumed = store.load(resumedFile)
            assertEquals(original.info.deviceUniqueId, resumed.deviceUniqueId)
            assertEquals("2.0.7", resumed.sourceVersion)
            assertEquals("2.0.7", resumed.targetVersion)
            assertEquals("RESET", resumed.settings.stationId)
            assertEquals(3_510_000L, resumed.settings.lowFrequencyHz)
        } finally {
            root.toFile().deleteRecursively()
            root.deleteIfExists()
        }
    }

    @Test
    fun refusesToGuessBetweenPendingBackupsWhenIdentityIsUnavailable() {
        val root = Files.createTempDirectory("firmware-backup-ambiguous-test")
        try {
            val store = FirmwareSettingsBackupStore(root.toFile())
            store.save(snapshot(uid = "unit-one", version = "2.0.7"), "2.0.2")
            store.save(snapshot(uid = "unit-two", version = "2.0.7"), "2.0.2")

            val failure = assertFailsWith<IllegalArgumentException> {
                store.save(snapshot(uid = null, version = "2.0.2"), "2.0.7")
            }
            assertContains(failure.message.orEmpty(), "Multiple pending firmware settings backups")
        } finally {
            root.toFile().deleteRecursively()
            root.deleteIfExists()
        }
    }

    @Test
    fun retriesTransientPostRebootReadbackBeforeAcceptingRestoredSettings() {
        val root = Files.createTempDirectory("firmware-backup-reboot-retry-test")
        try {
            val store = FirmwareSettingsBackupStore(root.toFile())
            val backup = store.load(store.save(snapshot(), "2.1.0"))
            val staleReadback = snapshot(stationId = "", version = "2.1.0")
            val restoredReadback = snapshot(version = "2.1.0")
            val readbacks = ArrayDeque(listOf(staleReadback, restoredReadback))
            val attempts = mutableListOf<Int>()
            val rejected = mutableListOf<DeviceSnapshot>()
            val retries = mutableListOf<String>()

            val verified = backup.verifyRestoredAfterReboot(
                beforeAttempt = attempts::add,
                load = { readbacks.removeFirst() },
                snapshotOf = { it },
                discardRejected = rejected::add,
                onRetry = { _, failure -> retries += failure.message.orEmpty() },
            )

            assertEquals(restoredReadback, verified)
            assertEquals(listOf(1, 2), attempts)
            assertEquals(listOf(staleReadback), rejected)
            assertEquals(1, retries.size)
            assertContains(retries.single(), "Station ID expected 'W8UM', read ''")
        } finally {
            root.toFile().deleteRecursively()
            root.deleteIfExists()
        }
    }

    @Test
    fun ignoresThermalSettingsWhenTargetFirmwareCannotReportThemAndRetainsBackupWithoutIdentity() {
        val root = Files.createTempDirectory("firmware-backup-old-target-test")
        try {
            val store = FirmwareSettingsBackupStore(root.toFile())
            val backup = store.load(store.save(snapshot(), "2.0.2"))
            val oldFirmwareSnapshot = snapshot(uid = null, version = "2.0.2").copy(
                capabilities = DeviceCapabilities(supportsThermalShutdownMode = false),
                status = DeviceStatus(thermalShutdownThresholdC = null, thermalShutdownEnabled = null),
            )

            assertTrue(backup.restorationDifferences(oldFirmwareSnapshot).isEmpty())
            assertTrue(backup.supplementalRestoreCommands(oldFirmwareSnapshot).isEmpty())
            backup.requireRestored(oldFirmwareSnapshot)
            assertFalse(backup.canDeleteAfterSuccessfulVerification(oldFirmwareSnapshot))
            assertTrue(
                backup.canDeleteAfterSuccessfulVerification(
                    oldFirmwareSnapshot.copy(
                        info = oldFirmwareSnapshot.info.copy(deviceUniqueId = backup.deviceUniqueId),
                    ),
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
            root.deleteIfExists()
        }
    }
}
