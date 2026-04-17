package com.openardf.serialslinger.protocol

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.WritePlan

data class DeviceInfoPatch(
    val softwareVersion: String? = null,
    val hardwareBuild: String? = null,
)

data class DeviceStatusPatch(
    val temperatureC: Double? = null,
    val internalBatteryVolts: Double? = null,
    val externalBatteryVolts: Double? = null,
    val daysRemaining: Int? = null,
    val eventEnabled: Boolean? = null,
    val eventStateSummary: String? = null,
    val eventStartsInSummary: String? = null,
    val eventDurationSummary: String? = null,
)

data class DeviceSettingsPatch(
    val stationId: String? = null,
    val eventType: EventType? = null,
    val foxRole: FoxRole? = null,
    val patternText: String? = null,
    val idCodeSpeedWpm: Int? = null,
    val patternCodeSpeedWpm: Int? = null,
    val currentTimeCompact: String? = null,
    val startTimeCompact: String? = null,
    val finishTimeCompact: String? = null,
    val daysToRun: Int? = null,
    val defaultFrequencyHz: Long? = null,
    val lowFrequencyHz: Long? = null,
    val mediumFrequencyHz: Long? = null,
    val highFrequencyHz: Long? = null,
    val beaconFrequencyHz: Long? = null,
    val lowBatteryThresholdVolts: Double? = null,
    val externalBatteryControlMode: ExternalBatteryControlMode? = null,
    val transmissionsEnabled: Boolean? = null,
) {
    fun applyTo(base: DeviceSettings): DeviceSettings {
        val nextTransmissionsEnabled = transmissionsEnabled ?: base.transmissionsEnabled
        val rawExternalBatteryMode = externalBatteryControlMode ?: base.externalBatteryControlMode
        val nextExternalBatteryMode = when (rawExternalBatteryMode) {
            null -> null
            ExternalBatteryControlMode.OFF -> ExternalBatteryControlMode.OFF
            ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
            ExternalBatteryControlMode.CHARGE_ONLY,
            -> if (nextTransmissionsEnabled) {
                ExternalBatteryControlMode.CHARGE_AND_TRANSMIT
            } else {
                ExternalBatteryControlMode.CHARGE_ONLY
            }
        }

        return base.copy(
            stationId = stationId ?: base.stationId,
            eventType = eventType ?: base.eventType,
            foxRole = foxRole ?: base.foxRole,
            patternText = patternText ?: base.patternText,
            idCodeSpeedWpm = idCodeSpeedWpm ?: base.idCodeSpeedWpm,
            patternCodeSpeedWpm = patternCodeSpeedWpm ?: base.patternCodeSpeedWpm,
            currentTimeCompact = currentTimeCompact ?: base.currentTimeCompact,
            startTimeCompact = startTimeCompact ?: base.startTimeCompact,
            finishTimeCompact = finishTimeCompact ?: base.finishTimeCompact,
            daysToRun = daysToRun ?: base.daysToRun,
            defaultFrequencyHz = defaultFrequencyHz ?: base.defaultFrequencyHz,
            lowFrequencyHz = lowFrequencyHz ?: base.lowFrequencyHz,
            mediumFrequencyHz = mediumFrequencyHz ?: base.mediumFrequencyHz,
            highFrequencyHz = highFrequencyHz ?: base.highFrequencyHz,
            beaconFrequencyHz = beaconFrequencyHz ?: base.beaconFrequencyHz,
            lowBatteryThresholdVolts = lowBatteryThresholdVolts ?: base.lowBatteryThresholdVolts,
            externalBatteryControlMode = nextExternalBatteryMode,
            transmissionsEnabled = nextTransmissionsEnabled,
        )
    }
}

data class DeviceReportUpdate(
    val settingsPatch: DeviceSettingsPatch? = null,
    val deviceInfoPatch: DeviceInfoPatch? = null,
    val deviceStatusPatch: DeviceStatusPatch? = null,
)

object SignalSlingerProtocolCodec {
    private val versionPattern = Regex("""^\* SW Ver:\s*(.+?)\s+HW Build:\s*(.+)$""")
    private val stationIdPattern = Regex("""^\* ID:\s*(.*)$""")
    private val eventPattern = Regex("""^\* Event:\s*(.+)$""")
    private val foxPattern = Regex("""^\*\s*Fox:\s*(.+)$""")
    private val patternTextPattern = Regex("""^\* PAT:\s*(.*)$""")
    private val idSpeedPattern = Regex("""^\* ID SPD:\s*(\d+)\s+WPM$""")
    private val patternSpeedPattern = Regex("""^\* PAT SPD:\s*(\d+)\s+WPM$""")
    private val foxoringPatternSpeedPattern = Regex("""^\* FOX-O SPD:\s*(\d+)\s+WPM$""")
    private val currentTimePattern = Regex("""^\*\s*Time:\s*(.+)$""")
    private val startTimePattern = Regex("""^\*\s*Start:\s*(.+)$""")
    private val finishTimePattern = Regex("""^\*\s*Finish:\s*(.+)$""")
    private val daysToRunPattern = Regex("""^\* Days to run:\s*(\d+)$""")
    private val daysRemainingPattern = Regex("""^\* Days remaining:\s*(\d+)$""")
    private val frequencyPattern = Regex("""^\* FRE(?:\s+([123B]))?=(.+)$""")
    private val batteryThresholdPattern = Regex("""^\* thresh\s*=\s*([0-9.]+)\s+Volts$""")
    private val externalBatteryControlPattern = Regex("""^\* Ext\. Bat\. Ctrl\s*=\s*(ON|OFF)$""")
    private val transmitterPattern = Regex("""^\* Transmitter\s*=\s*(Enabled|Disabled)$""")
    private val internalBatteryPattern = Regex("""^\* Int\. Bat\s*=\s*([0-9.]+)\s+Volts$""")
    private val externalBatteryPattern = Regex("""^\* Ext\. Bat\s*=\s*([0-9.]+)\s+Volts$""")
    private val temperaturePattern = Regex("""^\*\s*Temp:\s*(-?\d+(?:\.\d+)?)C$""")
    private val negativeEventStatePatterns = listOf(
        Regex("""^\*\s*Not scheduled$""", RegexOption.IGNORE_CASE),
        Regex("""^\*.*will not run.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*Event start disabled.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*No remaining scheduled day window.*$""", RegexOption.IGNORE_CASE),
    )
    private val positiveEventStatePatterns = listOf(
        Regex("""^\*\s*Running forever\.$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*User launched\.$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*Time remaining:.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*On the air(?: in .*)?\.$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*Event interrupted!$""", RegexOption.IGNORE_CASE),
    )
    private val startsInPattern = Regex("""^\*\s*Starts in:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val lastsPattern = Regex("""^\*\s*Lasts:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val inProgressPattern = Regex("""^\*\s*In progress$""", RegexOption.IGNORE_CASE)

    fun parseReportLine(line: String): DeviceReportUpdate? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("*")) {
            return null
        }

        versionPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceInfoPatch = DeviceInfoPatch(
                    softwareVersion = match.groupValues[1].trim(),
                    hardwareBuild = match.groupValues[2].trim(),
                ),
            )
        }

        stationIdPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    stationId = match.groupValues[1].trim(),
                ),
            )
        }

        eventPattern.matchEntire(trimmed)?.let { match ->
            parseEventType(match.groupValues[1])?.let { eventType ->
                return DeviceReportUpdate(
                    settingsPatch = DeviceSettingsPatch(eventType = eventType),
                )
            }
            return null
        }

        foxPattern.matchEntire(trimmed)?.let { match ->
            parseFoxRole(match.groupValues[1])?.let { foxRole ->
                return DeviceReportUpdate(
                    settingsPatch = DeviceSettingsPatch(foxRole = foxRole),
                )
            }
            return null
        }

        patternTextPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    patternText = match.groupValues[1].trim(),
                ),
            )
        }

        idSpeedPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    idCodeSpeedWpm = match.groupValues[1].toInt(),
                ),
            )
        }

        patternSpeedPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    patternCodeSpeedWpm = match.groupValues[1].toInt(),
                ),
            )
        }

        foxoringPatternSpeedPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    patternCodeSpeedWpm = match.groupValues[1].toInt(),
                ),
            )
        }

        currentTimePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    currentTimeCompact = parseFirmwareDisplayTime(match.groupValues[1]),
                ),
            )
        }

        startTimePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    startTimeCompact = parseFirmwareDisplayTime(match.groupValues[1]),
                ),
            )
        }

        finishTimePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    finishTimeCompact = parseFirmwareDisplayTime(match.groupValues[1]),
                ),
            )
        }

        daysToRunPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    daysToRun = match.groupValues[1].toInt(),
                ),
            )
        }

        daysRemainingPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    daysRemaining = match.groupValues[1].toInt(),
                ),
            )
        }

        frequencyPattern.matchEntire(trimmed)?.let { match ->
            val slot = match.groupValues[1]
            val frequencyHz = parseFrequencyHz(match.groupValues[2]) ?: return null
            val patch = when (slot) {
                "1" -> DeviceSettingsPatch(lowFrequencyHz = frequencyHz)
                "2" -> DeviceSettingsPatch(mediumFrequencyHz = frequencyHz)
                "3" -> DeviceSettingsPatch(highFrequencyHz = frequencyHz)
                "B" -> DeviceSettingsPatch(beaconFrequencyHz = frequencyHz)
                else -> DeviceSettingsPatch(defaultFrequencyHz = frequencyHz)
            }
            return DeviceReportUpdate(settingsPatch = patch)
        }

        batteryThresholdPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    lowBatteryThresholdVolts = match.groupValues[1].toDouble(),
                ),
            )
        }

        externalBatteryControlPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    externalBatteryControlMode = if (match.groupValues[1] == "ON") {
                        ExternalBatteryControlMode.CHARGE_AND_TRANSMIT
                    } else {
                        ExternalBatteryControlMode.OFF
                    },
                ),
            )
        }

        transmitterPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    transmissionsEnabled = match.groupValues[1] == "Enabled",
                ),
            )
        }

        internalBatteryPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    internalBatteryVolts = match.groupValues[1].toDouble(),
                ),
            )
        }

        externalBatteryPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    externalBatteryVolts = match.groupValues[1].toDouble(),
                ),
            )
        }

        temperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    temperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        parseEventTiming(trimmed)?.let { statusPatch ->
            return DeviceReportUpdate(deviceStatusPatch = statusPatch)
        }

        parseEventState(trimmed)?.let { statusPatch ->
            return DeviceReportUpdate(deviceStatusPatch = statusPatch)
        }

        return null
    }

    fun encodeWritePlan(writePlan: WritePlan, editedSettings: DeviceSettings): List<String> {
        val commands = mutableListOf<String>()
        var batteryCommandAdded = false

        for (change in writePlan.changes.sortedBy { it.writeOrder }) {
            when (change.fieldKey) {
                SettingKey.STATION_ID -> commands += encodeStationId(editedSettings.stationId)
                SettingKey.EVENT_TYPE -> commands += "EVT ${encodeEventType(editedSettings.eventType)}"
                SettingKey.FOX_ROLE -> editedSettings.foxRole?.let { commands += "FOX ${it.commandToken}" }
                SettingKey.PATTERN_TEXT -> commands += "PAT ${editedSettings.patternText.orEmpty()}"
                SettingKey.ID_CODE_SPEED_WPM -> commands += "SPD I ${editedSettings.idCodeSpeedWpm}"
                SettingKey.PATTERN_CODE_SPEED_WPM -> commands += encodePatternSpeedCommand(editedSettings)
                SettingKey.CURRENT_TIME -> editedSettings.currentTimeCompact?.let { commands += "CLK T $it" }
                SettingKey.START_TIME -> editedSettings.startTimeCompact?.let { commands += "CLK S $it" }
                SettingKey.FINISH_TIME -> editedSettings.finishTimeCompact?.let { commands += "CLK F $it" }
                SettingKey.DAYS_TO_RUN -> commands += "CLK D ${editedSettings.daysToRun}"
                SettingKey.DEFAULT_FREQUENCY_HZ -> commands += "FRE ${editedSettings.defaultFrequencyHz}"
                SettingKey.LOW_FREQUENCY_HZ -> editedSettings.lowFrequencyHz?.let { commands += "FRE 1 $it" }
                SettingKey.MEDIUM_FREQUENCY_HZ -> editedSettings.mediumFrequencyHz?.let { commands += "FRE 2 $it" }
                SettingKey.HIGH_FREQUENCY_HZ -> editedSettings.highFrequencyHz?.let { commands += "FRE 3 $it" }
                SettingKey.BEACON_FREQUENCY_HZ -> editedSettings.beaconFrequencyHz?.let { commands += "FRE B $it" }
                SettingKey.LOW_BATTERY_THRESHOLD_VOLTS -> {
                    editedSettings.lowBatteryThresholdVolts?.let { commands += "BAT T $it" }
                }
                SettingKey.EXTERNAL_BATTERY_CONTROL_MODE,
                SettingKey.TRANSMISSIONS_ENABLED,
                -> {
                    if (!batteryCommandAdded) {
                        commands += encodeBatteryControl(editedSettings)
                        batteryCommandAdded = true
                    }
                }
            }
        }

        return commands
    }

    private fun parseEventType(raw: String): EventType? {
        return when (raw.trim()) {
            "Classic" -> EventType.CLASSIC
            "Foxoring" -> EventType.FOXORING
            "Sprint" -> EventType.SPRINT
            "None Set" -> EventType.NONE
            else -> null
        }
    }

    private fun parseFoxRole(raw: String): FoxRole? {
        val normalized = raw.trim()
        return FoxRole.entries.firstOrNull { it.label == normalized }
    }

    private fun parseFirmwareDisplayTime(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.equals("not set", ignoreCase = true)) {
            return null
        }

        val match = Regex("""^[A-Za-z]{3}\s+(\d{1,2})-([A-Za-z]{3})-(\d{4})\s+(\d{2}):(\d{2}):(\d{2})$""")
            .matchEntire(trimmed) ?: return null

        val day = match.groupValues[1].padStart(2, '0')
        val month = when (match.groupValues[2].lowercase()) {
            "jan" -> "01"
            "feb" -> "02"
            "mar" -> "03"
            "apr" -> "04"
            "may" -> "05"
            "jun" -> "06"
            "jul" -> "07"
            "aug" -> "08"
            "sep" -> "09"
            "oct" -> "10"
            "nov" -> "11"
            "dec" -> "12"
            else -> return null
        }
        val year = match.groupValues[3].takeLast(2)
        val hour = match.groupValues[4]
        val minute = match.groupValues[5]
        val second = match.groupValues[6]

        if (match.groupValues[3] == "1970" && month == "01" && day == "01" && hour == "00" && minute == "00" && second == "00") {
            return null
        }

        return "$year$month$day$hour$minute$second"
    }

    private fun parseEventState(trimmed: String): DeviceStatusPatch? {
        val summary = trimmed.removePrefix("*").trim()
        if (negativeEventStatePatterns.any { it.matches(trimmed) }) {
            return DeviceStatusPatch(
                eventEnabled = false,
                eventStateSummary = summary,
            )
        }
        if (inProgressPattern.matches(trimmed)) {
            return DeviceStatusPatch(
                eventEnabled = true,
                eventStateSummary = summary,
                eventStartsInSummary = "In Progress",
            )
        }
        if (positiveEventStatePatterns.any { it.matches(trimmed) }) {
            return DeviceStatusPatch(
                eventEnabled = true,
                eventStateSummary = summary,
            )
        }
        return null
    }

    private fun parseEventTiming(trimmed: String): DeviceStatusPatch? {
        startsInPattern.matchEntire(trimmed)?.let { match ->
            return DeviceStatusPatch(
                eventEnabled = true,
                eventStartsInSummary = match.groupValues[1].trim(),
            )
        }
        lastsPattern.matchEntire(trimmed)?.let { match ->
            return DeviceStatusPatch(
                eventEnabled = true,
                eventDurationSummary = match.groupValues[1].trim(),
            )
        }
        return null
    }

    private fun parseFrequencyHz(raw: String): Long? {
        return FrequencySupport.parseFrequencyHz(raw)
    }

    private fun encodeStationId(stationId: String): String {
        val normalized = stationId.trim()
        return if (normalized.isEmpty()) {
            """ID """""
        } else {
            "ID $normalized"
        }
    }

    private fun encodeEventType(eventType: EventType): String {
        return when (eventType) {
            EventType.CLASSIC -> "C"
            EventType.FOXORING -> "F"
            EventType.SPRINT -> "S"
            EventType.NONE -> "N"
        }
    }

    private fun encodePatternSpeedCommand(editedSettings: DeviceSettings): String {
        val speed = editedSettings.patternCodeSpeedWpm
        val command = if (editedSettings.eventType == EventType.FOXORING) {
            "SPD F"
        } else {
            "SPD P"
        }
        return "$command $speed"
    }

    private fun encodeBatteryControl(editedSettings: DeviceSettings): String {
        val modeToken = when (editedSettings.externalBatteryControlMode) {
            ExternalBatteryControlMode.OFF,
            null,
            -> "0"
            ExternalBatteryControlMode.CHARGE_AND_TRANSMIT -> "1"
            ExternalBatteryControlMode.CHARGE_ONLY -> "2"
        }

        return "BAT X $modeToken"
    }
}
