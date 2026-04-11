package com.openardf.serialslinger.protocol

import com.openardf.serialslinger.model.DeviceCapabilities
import com.openardf.serialslinger.model.SettingKey

data class SignalSlingerFirmwareVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0,
    val suffix: String = "",
) : Comparable<SignalSlingerFirmwareVersion> {
    override fun compareTo(other: SignalSlingerFirmwareVersion): Int {
        return compareValuesBy(this, other, SignalSlingerFirmwareVersion::major, SignalSlingerFirmwareVersion::minor, SignalSlingerFirmwareVersion::patch)
    }

    companion object {
        fun parse(raw: String?): SignalSlingerFirmwareVersion? {
            val normalized = raw?.trim().orEmpty()
            if (normalized.isEmpty()) {
                return null
            }

            val match = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?([A-Za-z].*)?$""").matchEntire(normalized)
                ?: return null

            return SignalSlingerFirmwareVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].ifEmpty { "0" }.toInt(),
                suffix = match.groupValues[4],
            )
        }
    }
}

data class SignalSlingerFirmwareProfile(
    val id: String,
    val minimumVersion: SignalSlingerFirmwareVersion? = null,
    val capabilities: DeviceCapabilities,
    val bootstrapLoadCommands: List<String>,
    val loadCommandsAfterVersion: List<String>,
    val verificationReadbackCommands: Map<SettingKey, List<String>>,
) {
    val fullLoadCommands: List<String>
        get() = bootstrapLoadCommands + loadCommandsAfterVersion
}

object SignalSlingerFirmwareSupport {
    private val versionBootstrapCommands = listOf("VER")
    private val sharedVerificationReadbackCommands: Map<SettingKey, List<String>> = mapOf(
        SettingKey.STATION_ID to listOf("ID"),
        SettingKey.EVENT_TYPE to listOf("EVT", "FRE"),
        SettingKey.FOX_ROLE to listOf("FOX", "SPD P", "FRE"),
        SettingKey.PATTERN_TEXT to listOf("PAT"),
        SettingKey.ID_CODE_SPEED_WPM to listOf("SPD I"),
        SettingKey.PATTERN_CODE_SPEED_WPM to listOf("SPD P"),
        SettingKey.CURRENT_TIME to listOf("CLK T", "EVT"),
        SettingKey.START_TIME to listOf("CLK S", "EVT"),
        SettingKey.FINISH_TIME to listOf("CLK F", "EVT"),
        SettingKey.DAYS_TO_RUN to listOf("CLK D", "EVT"),
        SettingKey.DEFAULT_FREQUENCY_HZ to listOf("FRE"),
        SettingKey.LOW_FREQUENCY_HZ to listOf("FRE 1"),
        SettingKey.MEDIUM_FREQUENCY_HZ to listOf("FRE 2"),
        SettingKey.HIGH_FREQUENCY_HZ to listOf("FRE 3"),
        SettingKey.BEACON_FREQUENCY_HZ to listOf("FRE B"),
        SettingKey.LOW_BATTERY_THRESHOLD_VOLTS to listOf("BAT"),
        SettingKey.EXTERNAL_BATTERY_CONTROL_MODE to listOf("BAT"),
        SettingKey.TRANSMISSIONS_ENABLED to listOf("BAT"),
    )
    private val legacyLoadCommands = listOf(
        "ID",
        "EVT",
        "PAT",
        "SPD I",
        "SPD P",
        "FRE",
        "FRE 1",
        "FRE 2",
        "FRE 3",
        "FRE B",
        "BAT",
    )
    private val modernLoadCommands = listOf(
        "ID",
        "EVT",
        "FOX",
        "PAT",
        "SPD I",
        "SPD P",
        "CLK S",
        "CLK F",
        "FRE",
        "FRE 1",
        "FRE 2",
        "FRE 3",
        "FRE B",
        "BAT",
        "TMP",
    )

    private val legacyProfile = SignalSlingerFirmwareProfile(
        id = "legacy-pre-1.2",
        capabilities = DeviceCapabilities(
            supportsTemperatureReadback = false,
            supportsExternalBatteryControl = true,
            supportsPatternEditing = true,
            supportsScheduling = false,
            supportsFrequencyProfiles = true,
        ),
        bootstrapLoadCommands = versionBootstrapCommands,
        loadCommandsAfterVersion = legacyLoadCommands,
        verificationReadbackCommands = sharedVerificationReadbackCommands,
    )

    private val modernProfile = SignalSlingerFirmwareProfile(
        id = "modern-1.2+",
        minimumVersion = SignalSlingerFirmwareVersion(1, 2),
        capabilities = DeviceCapabilities(
            supportsTemperatureReadback = true,
            supportsExternalBatteryControl = true,
            supportsPatternEditing = true,
            supportsScheduling = true,
            supportsFrequencyProfiles = true,
        ),
        bootstrapLoadCommands = versionBootstrapCommands,
        loadCommandsAfterVersion = modernLoadCommands,
        verificationReadbackCommands = sharedVerificationReadbackCommands,
    )

    fun resolve(softwareVersion: String?): SignalSlingerFirmwareProfile {
        val parsedVersion = SignalSlingerFirmwareVersion.parse(softwareVersion)
        if (parsedVersion == null) {
            return modernProfile
        }

        return if (parsedVersion >= SignalSlingerFirmwareVersion(1, 2)) {
            modernProfile
        } else {
            legacyProfile
        }
    }

    fun bootstrapLoadCommands(): List<String> = versionBootstrapCommands
}
