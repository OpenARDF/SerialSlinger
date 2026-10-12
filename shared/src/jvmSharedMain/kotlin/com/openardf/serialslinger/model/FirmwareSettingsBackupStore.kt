package com.openardf.serialslinger.model

import java.io.File
import java.util.Properties

const val FIRMWARE_SETTINGS_REBOOT_VERIFICATION_ATTEMPTS = 3
const val FIRMWARE_SETTINGS_REBOOT_RETRY_DELAY_MS = 1_500L

/** Settings SerialSlinger can safely restore to the same unit after an EEPROM-map update. */
data class FirmwareSettingsBackup(
    val deviceUniqueId: String,
    val sourceVersion: String,
    val targetVersion: String,
    val settings: DeviceSettings,
    val thermalShutdownThresholdC: Int?,
    val thermalShutdownEnabled: Boolean?,
) {
    private val hasReportedDeviceIdentity: Boolean
        get() = !deviceUniqueId.startsWith(UNKNOWN_DEVICE_ID_PREFIX)

    fun settingsForRestore(current: DeviceSettings): DeviceSettings = settings.copy(
        foxRole = settings.foxRole ?: current.foxRole,
        arduconFoxRoleCode = settings.arduconFoxRoleCode ?: current.arduconFoxRoleCode,
        patternText = settings.patternText ?: current.patternText,
        currentTimeCompact = current.currentTimeCompact,
        startTimeCompact = settings.startTimeCompact ?: current.startTimeCompact,
        finishTimeCompact = settings.finishTimeCompact ?: current.finishTimeCompact,
        lowFrequencyHz = settings.lowFrequencyHz ?: current.lowFrequencyHz,
        mediumFrequencyHz = settings.mediumFrequencyHz ?: current.mediumFrequencyHz,
        highFrequencyHz = settings.highFrequencyHz ?: current.highFrequencyHz,
        beaconFrequencyHz = settings.beaconFrequencyHz ?: current.beaconFrequencyHz,
        lowBatteryThresholdVolts = settings.lowBatteryThresholdVolts ?: current.lowBatteryThresholdVolts,
        externalBatteryControlMode = settings.externalBatteryControlMode ?: current.externalBatteryControlMode,
        dtmfPassword = current.dtmfPassword,
        amToneFrequency = current.amToneFrequency,
        pttResetSetting = current.pttResetSetting,
        temperatureCalibration = current.temperatureCalibration,
    )

    fun requireMatches(snapshot: DeviceSnapshot) {
        snapshot.info.deviceUniqueId?.takeIf { it.isNotBlank() && hasReportedDeviceIdentity }?.let { reportedUid ->
            require(reportedUid.equals(deviceUniqueId, ignoreCase = true)) {
                "Updated SignalSlinger identity does not match the saved settings backup."
            }
        }
        require(snapshot.info.softwareVersion == targetVersion) {
            "Updated SignalSlinger reports ${snapshot.info.softwareVersion ?: "an unknown version"}; expected $targetVersion."
        }
    }

    fun requireRestored(snapshot: DeviceSnapshot) {
        requireMatches(snapshot)
        val differences = restorationDifferences(snapshot)
        require(differences.isEmpty()) {
            "Restored SignalSlinger settings did not survive reboot verification. " +
                "Differences: ${differences.joinToString("; ")}"
        }
    }

    /**
     * Keep a known-identity backup while the target cannot report its identity. A backup created
     * for pre-UID firmware has no identity claim to preserve and can be removed after verification.
     */
    fun canDeleteAfterSuccessfulVerification(snapshot: DeviceSnapshot): Boolean =
        !hasReportedDeviceIdentity ||
            snapshot.info.deviceUniqueId
                ?.takeIf { it.isNotBlank() }
                ?.equals(deviceUniqueId, ignoreCase = true) == true

    /**
     * Compares only values represented by this backup. Live clock data, fields absent from the
     * source firmware, and settings belonging only to other products are deliberately ignored.
     */
    fun restorationDifferences(snapshot: DeviceSnapshot): List<String> {
        val actual = snapshot.settings
        val expected = settingsForRestore(actual)
        return buildList {
            fun difference(label: String, expectedValue: Any?, actualValue: Any?) {
                if (expectedValue != actualValue) {
                    add("$label expected ${formatBackupValue(expectedValue)}, read ${formatBackupValue(actualValue)}")
                }
            }

            difference("Station ID", expected.stationId, actual.stationId)
            difference("Event type", expected.eventType, actual.eventType)
            difference("Fox role", expected.foxRole, actual.foxRole)
            difference("Pattern text", expected.patternText, actual.patternText)
            difference("ID speed", expected.idCodeSpeedWpm, actual.idCodeSpeedWpm)
            difference("Pattern speed", expected.patternCodeSpeedWpm, actual.patternCodeSpeedWpm)
            difference("Start time", expected.startTimeCompact, actual.startTimeCompact)
            difference("Finish time", expected.finishTimeCompact, actual.finishTimeCompact)
            difference("Days to run", expected.daysToRun, actual.daysToRun)
            difference("Default frequency", expected.defaultFrequencyHz, actual.defaultFrequencyHz)
            difference("Low frequency", expected.lowFrequencyHz, actual.lowFrequencyHz)
            difference("Medium frequency", expected.mediumFrequencyHz, actual.mediumFrequencyHz)
            difference("High frequency", expected.highFrequencyHz, actual.highFrequencyHz)
            difference("Beacon frequency", expected.beaconFrequencyHz, actual.beaconFrequencyHz)
            if (!approximatelyEqual(expected.lowBatteryThresholdVolts, actual.lowBatteryThresholdVolts)) {
                add(
                    "Low-battery threshold expected ${formatBackupValue(expected.lowBatteryThresholdVolts)}, " +
                        "read ${formatBackupValue(actual.lowBatteryThresholdVolts)}",
                )
            }
            difference(
                "External-battery control",
                expected.externalBatteryControlMode,
                actual.externalBatteryControlMode,
            )
            difference("Transmitter mode", expected.transmissionsEnabled, actual.transmissionsEnabled)
            if (snapshot.capabilities.supportsThermalShutdownMode) {
                thermalShutdownThresholdC?.let { expectedThreshold ->
                    difference(
                        "Thermal shutdown threshold",
                        expectedThreshold,
                        snapshot.status.thermalShutdownThresholdC?.toInt(),
                    )
                }
                thermalShutdownEnabled?.let { expectedEnabled ->
                    difference("Thermal shutdown mode", expectedEnabled, snapshot.status.thermalShutdownEnabled)
                }
            }
        }
    }

    fun requiresRestore(snapshot: DeviceSnapshot): Boolean = restorationDifferences(snapshot).isNotEmpty()

    /**
     * Reloads until the restored settings are visible or the conservative retry budget is exhausted.
     * A freshly rebooted unit can briefly answer with an incomplete startup readback.
     */
    fun <T : Any> verifyRestoredAfterReboot(
        attempts: Int = FIRMWARE_SETTINGS_REBOOT_VERIFICATION_ATTEMPTS,
        beforeAttempt: (attempt: Int) -> Unit = {},
        load: () -> T,
        snapshotOf: (T) -> DeviceSnapshot,
        discardRejected: (T) -> Unit = {},
        onRetry: (attempt: Int, failure: Throwable) -> Unit = { _, _ -> },
    ): T {
        require(attempts > 0) { "At least one reboot verification attempt is required." }
        var lastFailure: Throwable? = null
        for (attempt in 1..attempts) {
            beforeAttempt(attempt)
            var candidate: T? = null
            val failure = runCatching {
                load().also { loaded ->
                    candidate = loaded
                    requireRestored(snapshotOf(loaded))
                }
            }.exceptionOrNull()
            if (failure == null) return requireNotNull(candidate)
            candidate?.let(discardRejected)
            lastFailure = failure
            if (attempt < attempts) onRetry(attempt, failure)
        }
        throw lastFailure ?: error("SignalSlinger settings verification did not complete.")
    }

    fun supplementalRestoreCommands(snapshot: DeviceSnapshot): List<String> = buildList {
        if (!snapshot.capabilities.supportsThermalShutdownMode) return@buildList
        thermalShutdownThresholdC?.takeIf {
            snapshot.status.thermalShutdownThresholdC?.toInt() != it
        }?.let { add(ThermalShutdownSupport.commandForCelsius(it, snapshot.info.productName)) }
        thermalShutdownEnabled?.takeIf {
            snapshot.status.thermalShutdownEnabled != it
        }?.let { add(ThermalShutdownSupport.commandForEnabled(it, snapshot.info.productName)) }
    }

    private fun approximatelyEqual(expected: Double?, actual: Double?): Boolean {
        if (expected == null || actual == null) return expected == actual
        return kotlin.math.abs(expected - actual) < 0.0001
    }

    private fun formatBackupValue(value: Any?): String = when (value) {
        null -> "not reported"
        true -> "enabled"
        false -> "disabled"
        else -> "'$value'"
    }
}

/** Small, private, short-lived update safety net. Successful verified updates delete it. */
class FirmwareSettingsBackupStore(private val directory: File) {
    fun save(snapshot: DeviceSnapshot, targetVersion: String): File {
        directory.mkdirs()
        deleteOrphanedTemporaryFiles()
        val reportedUid = snapshot.info.deviceUniqueId?.takeIf { it.isNotBlank() }
        val resumed = if (reportedUid == null) uniquePendingBackupFor(snapshot) else null
        // Firmware 2.0.0-2.0.2 predates UID reporting. The reserved private token gives those
        // updates an atomic replacement path without pretending that it is a hardware ID.
        val uid = reportedUid ?: resumed?.second?.deviceUniqueId
            ?: UNKNOWN_DEVICE_ID
        val target = resumed?.first ?: directory.resolve("${uid.lowercase()}.properties")
        val previous = directory.resolve("${target.name}.previous")
        if (!target.isFile && previous.isFile) {
            require(previous.renameTo(target)) {
                "Could not recover the previous firmware settings backup."
            }
        }
        if (target.isFile && reportedUid != null) {
            val pending = load(target)
            require(pending.deviceUniqueId.equals(uid, ignoreCase = true)) {
                "Pending firmware settings backup identity does not match the attached device."
            }
        }
        val backup = if (resumed != null) {
            resumed.second.reconciledWith(snapshot, targetVersion)
        } else {
            FirmwareSettingsBackup(
                deviceUniqueId = uid,
                sourceVersion = snapshot.info.softwareVersion.orEmpty(),
                targetVersion = targetVersion,
                settings = snapshot.settings,
                thermalShutdownThresholdC = snapshot.status.thermalShutdownThresholdC?.toInt(),
                thermalShutdownEnabled = snapshot.status.thermalShutdownEnabled,
            )
        }
        val temporary = File.createTempFile("firmware-settings-", ".tmp", directory)
        temporary.outputStream().use { output ->
            encode(backup)
                .store(output, "SerialSlinger temporary firmware migration backup")
        }
        load(temporary)
        require(!previous.exists() || previous.delete()) {
            "Could not remove a stale firmware settings backup."
        }
        if (target.exists()) {
            require(target.renameTo(previous)) {
                "Could not preserve the previous firmware settings backup."
            }
        }
        if (!temporary.renameTo(target)) {
            if (!target.exists() && previous.exists()) previous.renameTo(target)
            error("Could not commit the temporary firmware settings backup.")
        }
        require(!previous.exists() || previous.delete()) {
            "Could not remove the superseded firmware settings backup."
        }
        return target
    }

    /**
     * A no-UID firmware can be the middle of a downgrade/upgrade sequence. Refresh every value
     * it can report while retaining only fields absent from that legacy protocol.
     */
    private fun FirmwareSettingsBackup.reconciledWith(
        snapshot: DeviceSnapshot,
        targetVersion: String,
    ): FirmwareSettingsBackup {
        val current = snapshot.settings
        val previous = settings
        return copy(
            targetVersion = targetVersion,
            settings = current.copy(
                foxRole = current.foxRole ?: previous.foxRole,
                arduconFoxRoleCode = current.arduconFoxRoleCode ?: previous.arduconFoxRoleCode,
                patternText = current.patternText ?: previous.patternText,
                currentTimeCompact = null,
                startTimeCompact = current.startTimeCompact ?: previous.startTimeCompact,
                finishTimeCompact = current.finishTimeCompact ?: previous.finishTimeCompact,
                lowFrequencyHz = current.lowFrequencyHz ?: previous.lowFrequencyHz,
                mediumFrequencyHz = current.mediumFrequencyHz ?: previous.mediumFrequencyHz,
                highFrequencyHz = current.highFrequencyHz ?: previous.highFrequencyHz,
                beaconFrequencyHz = current.beaconFrequencyHz ?: previous.beaconFrequencyHz,
                lowBatteryThresholdVolts = current.lowBatteryThresholdVolts ?: previous.lowBatteryThresholdVolts,
                externalBatteryControlMode =
                    current.externalBatteryControlMode ?: previous.externalBatteryControlMode,
            ),
            thermalShutdownThresholdC =
                snapshot.status.thermalShutdownThresholdC?.toInt() ?: thermalShutdownThresholdC,
            thermalShutdownEnabled = snapshot.status.thermalShutdownEnabled ?: thermalShutdownEnabled,
        )
    }

    private fun uniquePendingBackupFor(snapshot: DeviceSnapshot): Pair<File, FirmwareSettingsBackup>? {
        val currentVersion = snapshot.info.softwareVersion?.takeIf { it.isNotBlank() } ?: return null
        val matches = directory.listFiles { file ->
            file.isFile && file.name.endsWith(".properties")
        }.orEmpty().map { file -> file to load(file) }
            .filter { (_, backup) -> backup.targetVersion == currentVersion }
        require(matches.size <= 1) {
            "Multiple pending firmware settings backups match SignalSlinger $currentVersion; " +
                "the unit identity is required to select one safely."
        }
        return matches.singleOrNull()
    }

    fun load(file: File): FirmwareSettingsBackup {
        val properties = Properties().apply { file.inputStream().use { input -> load(input) } }
        return decode(properties)
    }

    fun delete(file: File) {
        require(!file.exists() || file.delete()) { "Could not remove the completed firmware settings backup." }
        val previous = file.resolveSibling("${file.name}.previous")
        require(!previous.exists() || previous.delete()) {
            "Could not remove the completed firmware settings backup recovery file."
        }
        deleteOrphanedTemporaryFiles()
        directory.listFiles()?.takeIf { it.isEmpty() }?.let { directory.delete() }
    }

    private fun deleteOrphanedTemporaryFiles() {
        directory.listFiles { _, name ->
            name.startsWith("firmware-settings-") && name.endsWith(".tmp")
        }?.forEach { temporary ->
            require(temporary.delete()) { "Could not remove an incomplete firmware settings backup." }
        }
    }

    private fun encode(backup: FirmwareSettingsBackup) = Properties().apply {
        this["format"] = "1"
        this["uid"] = backup.deviceUniqueId
        this["sourceVersion"] = backup.sourceVersion
        this["targetVersion"] = backup.targetVersion
        backup.settings.let { settings ->
            this["stationId"] = settings.stationId
            this["eventType"] = settings.eventType.name
            settings.foxRole?.let { this["foxRole"] = it.name }
            settings.arduconFoxRoleCode?.let { this["arduconFoxRoleCode"] = it.toString() }
            settings.patternText?.let { this["patternText"] = it }
            this["idCodeSpeedWpm"] = settings.idCodeSpeedWpm.toString()
            this["patternCodeSpeedWpm"] = settings.patternCodeSpeedWpm.toString()
            settings.startTimeCompact?.let { this["startTimeCompact"] = it }
            settings.finishTimeCompact?.let { this["finishTimeCompact"] = it }
            this["daysToRun"] = settings.daysToRun.toString()
            this["defaultFrequencyHz"] = settings.defaultFrequencyHz.toString()
            settings.lowFrequencyHz?.let { this["lowFrequencyHz"] = it.toString() }
            settings.mediumFrequencyHz?.let { this["mediumFrequencyHz"] = it.toString() }
            settings.highFrequencyHz?.let { this["highFrequencyHz"] = it.toString() }
            settings.beaconFrequencyHz?.let { this["beaconFrequencyHz"] = it.toString() }
            settings.lowBatteryThresholdVolts?.let { this["lowBatteryThresholdVolts"] = it.toString() }
            settings.externalBatteryControlMode?.let { this["externalBatteryControlMode"] = it.name }
            this["transmissionsEnabled"] = settings.transmissionsEnabled.toString()
        }
        backup.thermalShutdownThresholdC?.let { this["thermalShutdownThresholdC"] = it.toString() }
        backup.thermalShutdownEnabled?.let { this["thermalShutdownEnabled"] = it.toString() }
    }

    private fun decode(values: Properties): FirmwareSettingsBackup {
        require(values.getProperty("format") == "1") { "Unsupported firmware settings backup format." }
        fun required(name: String): String = requireNotNull(values.getProperty(name)) { "Backup is missing $name." }
        fun long(name: String): Long? = values.getProperty(name)?.toLongOrNull()
        fun int(name: String): Int? = values.getProperty(name)?.toIntOrNull()
        fun double(name: String): Double? = values.getProperty(name)?.toDoubleOrNull()
        return FirmwareSettingsBackup(
            deviceUniqueId = required("uid"),
            sourceVersion = required("sourceVersion"),
            targetVersion = required("targetVersion"),
            settings = DeviceSettings(
                stationId = required("stationId"),
                eventType = EventType.valueOf(required("eventType")),
                foxRole = values.getProperty("foxRole")?.let(FoxRole::valueOf),
                arduconFoxRoleCode = int("arduconFoxRoleCode"),
                patternText = values.getProperty("patternText"),
                idCodeSpeedWpm = required("idCodeSpeedWpm").toInt(),
                patternCodeSpeedWpm = required("patternCodeSpeedWpm").toInt(),
                currentTimeCompact = null,
                startTimeCompact = values.getProperty("startTimeCompact"),
                finishTimeCompact = values.getProperty("finishTimeCompact"),
                daysToRun = required("daysToRun").toInt(),
                defaultFrequencyHz = required("defaultFrequencyHz").toLong(),
                lowFrequencyHz = long("lowFrequencyHz"),
                mediumFrequencyHz = long("mediumFrequencyHz"),
                highFrequencyHz = long("highFrequencyHz"),
                beaconFrequencyHz = long("beaconFrequencyHz"),
                lowBatteryThresholdVolts = double("lowBatteryThresholdVolts"),
                externalBatteryControlMode = values.getProperty("externalBatteryControlMode")?.let(ExternalBatteryControlMode::valueOf),
                transmissionsEnabled = required("transmissionsEnabled").toBooleanStrict(),
            ),
            thermalShutdownThresholdC = int("thermalShutdownThresholdC"),
            thermalShutdownEnabled = values.getProperty("thermalShutdownEnabled")?.toBooleanStrict(),
        )
    }
}

private const val UNKNOWN_DEVICE_ID_PREFIX = "legacy-no-uid"
private const val UNKNOWN_DEVICE_ID = UNKNOWN_DEVICE_ID_PREFIX
