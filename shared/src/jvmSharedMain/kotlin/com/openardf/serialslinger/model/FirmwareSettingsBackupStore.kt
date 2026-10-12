package com.openardf.serialslinger.model

import java.io.File
import java.util.Properties

/** Settings SerialSlinger can safely restore to the same unit after an EEPROM-map update. */
data class FirmwareSettingsBackup(
    val deviceUniqueId: String,
    val sourceVersion: String,
    val targetVersion: String,
    val settings: DeviceSettings,
    val thermalShutdownThresholdC: Int?,
    val thermalShutdownEnabled: Boolean?,
) {
    fun settingsForRestore(current: DeviceSettings): DeviceSettings = settings.copy(
        currentTimeCompact = current.currentTimeCompact,
    )

    fun requireMatches(snapshot: DeviceSnapshot) {
        require(snapshot.info.deviceUniqueId.equals(deviceUniqueId, ignoreCase = true)) {
            "Updated SignalSlinger identity does not match the saved settings backup."
        }
        require(snapshot.info.softwareVersion == targetVersion) {
            "Updated SignalSlinger reports ${snapshot.info.softwareVersion ?: "an unknown version"}; expected $targetVersion."
        }
    }

    fun requireRestored(snapshot: DeviceSnapshot) {
        requireMatches(snapshot)
        require(settingsForRestore(snapshot.settings) == snapshot.settings) {
            "Restored SignalSlinger settings did not survive reboot verification."
        }
        require(
            thermalShutdownThresholdC == null ||
                snapshot.status.thermalShutdownThresholdC?.toInt() == thermalShutdownThresholdC,
        ) {
            "Restored SignalSlinger thermal shutdown threshold did not survive reboot verification."
        }
        require(thermalShutdownEnabled == null || snapshot.status.thermalShutdownEnabled == thermalShutdownEnabled) {
            "Restored SignalSlinger thermal shutdown mode did not survive reboot verification."
        }
    }

    fun supplementalRestoreCommands(snapshot: DeviceSnapshot): List<String> = buildList {
        thermalShutdownThresholdC?.takeIf {
            snapshot.status.thermalShutdownThresholdC?.toInt() != it
        }?.let { add(ThermalShutdownSupport.commandForCelsius(it, snapshot.info.productName)) }
        thermalShutdownEnabled?.takeIf {
            snapshot.status.thermalShutdownEnabled != it
        }?.let { add(ThermalShutdownSupport.commandForEnabled(it, snapshot.info.productName)) }
    }
}

/** Small, private, short-lived update safety net. Successful verified updates delete it. */
class FirmwareSettingsBackupStore(private val directory: File) {
    fun save(snapshot: DeviceSnapshot, targetVersion: String): File {
        val uid = requireNotNull(snapshot.info.deviceUniqueId?.takeIf { it.isNotBlank() }) {
            "Cannot back up firmware settings without a device identity."
        }
        directory.mkdirs()
        deleteOrphanedTemporaryFiles()
        val target = directory.resolve("${uid.lowercase()}.properties")
        val previous = directory.resolve("${target.name}.previous")
        if (!target.isFile && previous.isFile) {
            require(previous.renameTo(target)) {
                "Could not recover the previous firmware settings backup."
            }
        }
        val backup = if (target.isFile) {
            val pending = load(target)
            require(pending.deviceUniqueId.equals(uid, ignoreCase = true)) {
                "Pending firmware settings backup identity does not match the attached device."
            }
            pending.copy(targetVersion = targetVersion)
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
