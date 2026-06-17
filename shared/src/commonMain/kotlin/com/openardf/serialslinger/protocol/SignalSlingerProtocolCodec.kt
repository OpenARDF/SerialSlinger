package com.openardf.serialslinger.protocol

import com.openardf.serialslinger.model.DeviceInfo
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.EventProfileSupport
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.TemperatureCalibrationSupport
import com.openardf.serialslinger.model.WritePlan
import com.openardf.serialslinger.model.normalizeDtmfPasswordForWrite
import com.openardf.serialslinger.platform.PlatformDateTimeFields
import com.openardf.serialslinger.platform.platformEpochSecondsFromLocalDateTimeFields
import com.openardf.serialslinger.platform.platformLocalDateTimeFields
import com.openardf.serialslinger.platform.platformUtcDateTimeFields

data class DeviceInfoPatch(
    val productName: String? = null,
    val softwareVersion: String? = null,
    val hardwareBuild: String? = null,
    val appStartAddress: Int? = null,
    val appBaud: Int? = null,
    val updateBaud: Int? = null,
    val appUpdateCommand: String? = null,
    val bootloaderVersion: String? = null,
    val bootloaderProtocolVersion: Int? = null,
    val bootloaderProtocol: String? = null,
)

data class DeviceStatusPatch(
    val temperatureC: Double? = null,
    val minimumTemperatureC: Double? = null,
    val maximumTemperatureC: Double? = null,
    val maximumEverTemperatureC: Double? = null,
    val thermalShutdownThresholdC: Double? = null,
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
    val arduconFoxRoleCode: Int? = null,
    val patternText: String? = null,
    val idCodeSpeedWpm: Int? = null,
    val patternCodeSpeedWpm: Int? = null,
    val currentTimeCompact: String? = null,
    val currentTimeObserved: Boolean = false,
    val startTimeCompact: String? = null,
    val startTimeObserved: Boolean = false,
    val finishTimeCompact: String? = null,
    val finishTimeObserved: Boolean = false,
    val daysToRun: Int? = null,
    val defaultFrequencyHz: Long? = null,
    val lowFrequencyHz: Long? = null,
    val mediumFrequencyHz: Long? = null,
    val highFrequencyHz: Long? = null,
    val beaconFrequencyHz: Long? = null,
    val lowBatteryThresholdVolts: Double? = null,
    val externalBatteryControlMode: ExternalBatteryControlMode? = null,
    val transmissionsEnabled: Boolean? = null,
    val dtmfPassword: String? = null,
    val amToneFrequency: Int? = null,
    val pttResetSetting: Int? = null,
    val temperatureCalibration: Int? = null,
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
            arduconFoxRoleCode = arduconFoxRoleCode ?: base.arduconFoxRoleCode,
            patternText = patternText ?: base.patternText,
            idCodeSpeedWpm = idCodeSpeedWpm ?: base.idCodeSpeedWpm,
            patternCodeSpeedWpm = patternCodeSpeedWpm ?: base.patternCodeSpeedWpm,
            currentTimeCompact = if (currentTimeObserved) currentTimeCompact else base.currentTimeCompact,
            startTimeCompact = if (startTimeObserved) startTimeCompact else base.startTimeCompact,
            finishTimeCompact = if (finishTimeObserved) finishTimeCompact else base.finishTimeCompact,
            daysToRun = daysToRun ?: base.daysToRun,
            defaultFrequencyHz = defaultFrequencyHz ?: base.defaultFrequencyHz,
            lowFrequencyHz = lowFrequencyHz ?: base.lowFrequencyHz,
            mediumFrequencyHz = mediumFrequencyHz ?: base.mediumFrequencyHz,
            highFrequencyHz = highFrequencyHz ?: base.highFrequencyHz,
            beaconFrequencyHz = beaconFrequencyHz ?: base.beaconFrequencyHz,
            lowBatteryThresholdVolts = lowBatteryThresholdVolts ?: base.lowBatteryThresholdVolts,
            externalBatteryControlMode = nextExternalBatteryMode,
            transmissionsEnabled = nextTransmissionsEnabled,
            dtmfPassword = dtmfPassword ?: base.dtmfPassword,
            amToneFrequency = amToneFrequency ?: base.amToneFrequency,
            pttResetSetting = pttResetSetting ?: base.pttResetSetting,
            temperatureCalibration = temperatureCalibration ?: base.temperatureCalibration,
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
    private val bootloaderVersionPattern = Regex("""^\* Bootloader:\s*(\S+)\s+protocol\s+(\S+)$""", RegexOption.IGNORE_CASE)
    private val appInfoPattern = Regex("""\*\s+INF\s+(.+)$""")
    private val stationIdPattern = Regex("""^\* ID:\s*(.*)$""")
    private val eventPattern = Regex("""^\* Event:\s*(.+)$""")
    private val foxPattern = Regex("""^\*\s*Fox:\s*(.+)$""")
    private val arduconFoxPattern = Regex("""^Fox\s*[:=]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val patternTextPattern = Regex("""^\* PAT:\s*(.*)$""")
    private val idSpeedPattern = Regex("""^\* ID SPD:\s*(\d+)\s+WPM$""")
    private val patternSpeedPattern = Regex("""^\* PAT SPD:\s*(\d+)\s+WPM$""")
    private val foxoringPatternSpeedPattern = Regex("""^\* FOX-O SPD:\s*(\d+)\s+WPM$""")
    private val currentTimePattern = Regex("""^\*\s*Time:\s*(.+)$""")
    private val startTimePattern = Regex("""^\*\s*Start:\s*(.+)$""")
    private val finishTimePattern = Regex("""^\*\s*Finish:\s*(.+)$""")
    private val arduconEpochPattern = Regex("""^Epoch:(\d+)$""", RegexOption.IGNORE_CASE)
    private val arduconStartEpochPattern = Regex("""^Start:(\d+)$""", RegexOption.IGNORE_CASE)
    private val arduconFinishEpochPattern = Regex("""^Finish:(\d+)$""", RegexOption.IGNORE_CASE)
    private val arduconDaysToRunPattern = Regex("""^CLK\s+D\s+(\d+)$""", RegexOption.IGNORE_CASE)
    private val arduconIdSpeedPattern = Regex("""^ID:\s*(\d+)\s+wpm$""", RegexOption.IGNORE_CASE)
    private val arduconStationIdPattern = Regex("""^ID:\s*(\S+)$""")
    private val arduconTemperaturePattern = Regex("""^T=(-?\d+(?:\.\d+)?)C$""", RegexOption.IGNORE_CASE)
    private val arduconMaximumTemperaturePattern = Regex("""^Max\s*=\s*(-?\d+(?:\.\d+)?)C$""", RegexOption.IGNORE_CASE)
    private val arduconMaximumEverTemperaturePattern = Regex("""^Max Ever\s*=\s*(-?\d+(?:\.\d+)?)C$""", RegexOption.IGNORE_CASE)
    private val arduconMaximumEverResetTemperaturePattern = Regex("""^Max Ever Reset\s*=\s*(-?\d+(?:\.\d+)?)C$""", RegexOption.IGNORE_CASE)
    private val arduconThermalShutdownThresholdPattern = Regex("""^Thermal Shutdown\s*=\s*(-?\d+(?:\.\d+)?)C$""", RegexOption.IGNORE_CASE)
    private val arduconTemperatureCalibrationPattern = Regex("""^T Cal\s*=\s*(-?\d+)$""", RegexOption.IGNORE_CASE)
    private val arduconVoltagePattern = Regex("""^V=([0-9]+(?:\.[0-9]+)?)V$""", RegexOption.IGNORE_CASE)
    private val arduconPasswordPattern = Regex("""^PWD=(\S*)$""", RegexOption.IGNORE_CASE)
    private val arduconAmTonePattern = Regex("""^AM:(\d+)$""", RegexOption.IGNORE_CASE)
    private val arduconPttResetPattern = Regex("""^DRP:([01])$""", RegexOption.IGNORE_CASE)
    private val daysToRunPattern = Regex("""^\* Days to run:\s*(\d+)$""")
    private val daysRemainingPattern = Regex("""^\* Days remaining:\s*(\d+)$""")
    private val frequencyPattern = Regex("""^\* FRE(?:\s+([123B]))?=(.+)$""")
    private val batteryThresholdPattern = Regex("""^\* thresh\s*=\s*([0-9.]+)\s+Volts$""")
    private val externalBatteryControlPattern = Regex("""^\* Ext\. Bat\. Ctrl\s*=\s*(ON|OFF)$""")
    private val transmitterPattern = Regex("""^\* Transmitter\s*=\s*(Enabled|Disabled)$""")
    private val internalBatteryPattern = Regex("""^\* Int\. Bat\s*=\s*([0-9.]+)\s+Volts$""")
    private val externalBatteryPattern = Regex("""^\* Ext\. Bat\s*=\s*([0-9.]+)\s+Volts$""")
    private val temperaturePattern = Regex("""^\*\s*Temp:\s*(-?\d+(?:\.\d+)?)C$""")
    private val currentTemperaturePattern = Regex("""^\*\s*Cur Temp:\s*(-?\d+(?:\.\d+)?)C$""")
    private val maximumTemperaturePattern = Regex("""^\*\s*Max Temp:\s*(-?\d+(?:\.\d+)?)C$""")
    private val minimumTemperaturePattern = Regex("""^\*\s*Min Temp:\s*(-?\d+(?:\.\d+)?)C$""")
    private val maximumEverTemperaturePattern = Regex("""^\*\s*Max Ever:\s*(-?\d+(?:\.\d+)?)C$""")
    private val thermalShutdownThresholdPattern = Regex("""^\*\s*Thermal shutdown threshold:\s*(-?\d+(?:\.\d+)?)C$""", RegexOption.IGNORE_CASE)
    private val negativeEventStatePatterns = listOf(
        Regex("""^\*\s*Not scheduled$""", RegexOption.IGNORE_CASE),
        Regex("""^\*.*will not run.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*Event start disabled.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*No remaining scheduled day window.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*Config err\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*Event interrupted!$""", RegexOption.IGNORE_CASE),
    )
    private val positiveEventStatePatterns = listOf(
        Regex("""^\*\s*Running forever\.$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*User launched\.$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*Time remaining:.*$""", RegexOption.IGNORE_CASE),
        Regex("""^\*\s*On the air(?: in .*)?\.$""", RegexOption.IGNORE_CASE),
    )
    private val startsInPattern = Regex("""^\*\s*Starts in:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val lastsPattern = Regex("""^\*\s*Lasts:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val inProgressPattern = Regex("""^\*\s*In progress$""", RegexOption.IGNORE_CASE)

    fun parseReportLine(line: String): DeviceReportUpdate? {
        val trimmed = line.trim()
        appInfoPattern.find(trimmed)?.let { match ->
            val fields = parseKeyValueFields(match.groupValues[1])
            val productName = fields["product"]
            val softwareVersion = fields["sw"]
            val hardwareBuild = fields["hw"]
            val appStartAddress = fields["app"]?.let(::parseIntLiteral)
            val appBaud = fields["appbaud"]?.toIntOrNull()
            val updateBaud = fields["baud"]?.toIntOrNull()
            val appUpdateCommand = fields["update"]
            val bootloaderVersion = fields["bl"]?.takeUnless { it.equals("unknown", ignoreCase = true) }
            val bootloaderProtocol = fields["proto"]?.takeUnless { it.equals("unknown", ignoreCase = true) }
            val bootloaderProtocolVersion = fields["proto"]
                ?.takeUnless { it.equals("unknown", ignoreCase = true) }
                ?.toIntOrNull()

            if (
                productName != null ||
                softwareVersion != null ||
                hardwareBuild != null ||
                appStartAddress != null ||
                appBaud != null ||
                updateBaud != null ||
                appUpdateCommand != null ||
                bootloaderVersion != null ||
                bootloaderProtocolVersion != null ||
                bootloaderProtocol != null
            ) {
                return DeviceReportUpdate(
                    deviceInfoPatch = DeviceInfoPatch(
                        productName = productName,
                        softwareVersion = softwareVersion,
                        hardwareBuild = hardwareBuild,
                        appStartAddress = appStartAddress,
                        appBaud = appBaud,
                        updateBaud = updateBaud,
                        appUpdateCommand = appUpdateCommand,
                        bootloaderVersion = bootloaderVersion,
                        bootloaderProtocolVersion = bootloaderProtocolVersion,
                        bootloaderProtocol = bootloaderProtocol,
                    ),
                )
            }
        }

        arduconEpochPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    currentTimeCompact = parseEpochSecondsCompact(match.groupValues[1]),
                    currentTimeObserved = true,
                ),
            )
        }

        arduconStartEpochPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    startTimeCompact = parseEpochSecondsCompact(match.groupValues[1]),
                    startTimeObserved = true,
                ),
            )
        }

        arduconFinishEpochPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    finishTimeCompact = parseEpochSecondsCompact(match.groupValues[1]),
                    finishTimeObserved = true,
                ),
            )
        }

        arduconDaysToRunPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    daysToRun = match.groupValues[1].toInt(),
                ),
            )
        }

        arduconIdSpeedPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    idCodeSpeedWpm = match.groupValues[1].toInt(),
                ),
            )
        }

        arduconStationIdPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    stationId = match.groupValues[1].trim(),
                ),
            )
        }

        arduconFoxPattern.matchEntire(trimmed)?.let { match ->
            EventProfileSupport.parseArduconFoxRoleOrNull(match.groupValues[1])?.let { arduconRole ->
                return DeviceReportUpdate(
                    settingsPatch = DeviceSettingsPatch(
                        arduconFoxRoleCode = arduconRole.numericalDesignator,
                        eventType = arduconRole.eventType,
                        patternText = arduconRole.morsePatternSent,
                    ),
                )
            }
            parseFoxRole(match.groupValues[1])?.let { foxRole ->
                return DeviceReportUpdate(
                    settingsPatch = DeviceSettingsPatch(foxRole = foxRole),
                )
            }
            return null
        }

        arduconTemperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    temperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        arduconMaximumTemperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    maximumTemperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        arduconMaximumEverTemperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    maximumEverTemperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        arduconMaximumEverResetTemperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    maximumEverTemperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        arduconThermalShutdownThresholdPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    thermalShutdownThresholdC = match.groupValues[1].toDouble(),
                ),
            )
        }

        arduconTemperatureCalibrationPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    temperatureCalibration = match.groupValues[1].toInt(),
                ),
            )
        }

        arduconVoltagePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    externalBatteryVolts = match.groupValues[1].toDouble(),
                ),
            )
        }

        arduconPasswordPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    dtmfPassword = match.groupValues[1].trim(),
                ),
            )
        }

        arduconAmTonePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    amToneFrequency = match.groupValues[1].toInt(),
                ),
            )
        }

        arduconPttResetPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    pttResetSetting = match.groupValues[1].toInt(),
                ),
            )
        }

        if (!trimmed.startsWith("*")) {
            return null
        }

        versionPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceInfoPatch = DeviceInfoPatch(
                    productName = "SignalSlinger",
                    softwareVersion = match.groupValues[1].trim(),
                    hardwareBuild = match.groupValues[2].trim(),
                ),
            )
        }

        bootloaderVersionPattern.matchEntire(trimmed)?.let { match ->
            val bootloaderVersion = match.groupValues[1].trim().takeUnless { it.equals("unknown", ignoreCase = true) }
            val bootloaderProtocolVersion = match.groupValues[2].trim().takeUnless { it.equals("unknown", ignoreCase = true) }?.toIntOrNull()
            return DeviceReportUpdate(
                deviceInfoPatch = DeviceInfoPatch(
                    bootloaderVersion = bootloaderVersion,
                    bootloaderProtocolVersion = bootloaderProtocolVersion,
                    bootloaderProtocol = bootloaderProtocolVersion?.toString(),
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
                    currentTimeObserved = true,
                ),
            )
        }

        startTimePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    startTimeCompact = parseFirmwareDisplayTime(match.groupValues[1]),
                    startTimeObserved = true,
                ),
            )
        }

        finishTimePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                settingsPatch = DeviceSettingsPatch(
                    finishTimeCompact = parseFirmwareDisplayTime(match.groupValues[1]),
                    finishTimeObserved = true,
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

        currentTemperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    temperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        maximumTemperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    maximumTemperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        minimumTemperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    minimumTemperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        maximumEverTemperaturePattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    maximumEverTemperatureC = match.groupValues[1].toDouble(),
                ),
            )
        }

        thermalShutdownThresholdPattern.matchEntire(trimmed)?.let { match ->
            return DeviceReportUpdate(
                deviceStatusPatch = DeviceStatusPatch(
                    thermalShutdownThresholdC = match.groupValues[1].toDouble(),
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

    fun encodeWritePlan(
        writePlan: WritePlan,
        editedSettings: DeviceSettings,
        deviceInfo: DeviceInfo? = null,
    ): List<String> {
        val commands = mutableListOf<String>()
        var batteryCommandAdded = false
        val useUtcCompactClockWrites = deviceInfo?.productName.equals("Arducon", ignoreCase = true)

        fun encodeClockWrite(prefix: String, compactTimestamp: String?): String? {
            if (compactTimestamp == null) {
                return null
            }
            val payload = if (useUtcCompactClockWrites) {
                localCompactToUtcCompact(compactTimestamp)
            } else {
                compactTimestamp
            }
            return "$prefix $payload"
        }

        for (change in writePlan.changes.sortedBy { it.writeOrder }) {
            when (change.fieldKey) {
                SettingKey.STATION_ID -> commands += encodeStationId(editedSettings.stationId)
                SettingKey.EVENT_TYPE -> commands += "EVT ${encodeEventType(editedSettings.eventType)}"
                SettingKey.FOX_ROLE -> editedSettings.foxRole?.let { commands += "FOX ${it.commandToken}" }
                SettingKey.ARDUCON_FOX_ROLE -> editedSettings.arduconFoxRoleCode?.let { commands += "FOX ${it.toString().padStart(2, '0')}" }
                SettingKey.PATTERN_TEXT -> commands += "PAT ${editedSettings.patternText.orEmpty()}"
                SettingKey.ID_CODE_SPEED_WPM -> {
                    commands += if (useUtcCompactClockWrites) {
                        "SET S ${editedSettings.idCodeSpeedWpm}"
                    } else {
                        "SPD I ${editedSettings.idCodeSpeedWpm}"
                    }
                }
                SettingKey.PATTERN_CODE_SPEED_WPM -> commands += encodePatternSpeedCommand(editedSettings)
                SettingKey.CURRENT_TIME -> encodeClockWrite("CLK T", editedSettings.currentTimeCompact)?.let { commands += it }
                SettingKey.START_TIME -> encodeClockWrite("CLK S", editedSettings.startTimeCompact)?.let { commands += it }
                SettingKey.FINISH_TIME -> encodeClockWrite("CLK F", editedSettings.finishTimeCompact)?.let { commands += it }
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
                SettingKey.DTMF_PASSWORD -> normalizeDtmfPasswordForWrite(editedSettings.dtmfPassword)?.let { commands += "PWD $it" }
                SettingKey.AM_TONE_FREQUENCY -> editedSettings.amToneFrequency?.let { commands += "AM $it" }
                SettingKey.PTT_RESET_SETTING -> editedSettings.pttResetSetting?.takeIf { it == 0 || it == 1 }?.let { commands += "SET P $it" }
                SettingKey.TEMPERATURE_CALIBRATION -> editedSettings.temperatureCalibration?.let {
                    commands += "UTI C ${TemperatureCalibrationSupport.validate(it)}"
                }
            }
        }

        return commands
    }

    private fun localCompactToUtcCompact(compactTimestamp: String): String {
        val normalized = compactTimestamp.trim()
        require(normalized.length == 12 && normalized.all { it.isDigit() }) {
            "Timestamp must be YYMMDDhhmmss."
        }
        val localFields = PlatformDateTimeFields(
            year = 2000 + normalized.substring(0, 2).toInt(),
            month = normalized.substring(2, 4).toInt(),
            day = normalized.substring(4, 6).toInt(),
            hour = normalized.substring(6, 8).toInt(),
            minute = normalized.substring(8, 10).toInt(),
            second = normalized.substring(10, 12).toInt(),
        )
        val epochSeconds = requireNotNull(platformEpochSecondsFromLocalDateTimeFields(localFields)) {
            "Timestamp is not valid for the local timezone."
        }
        val utcFields = requireNotNull(platformUtcDateTimeFields(epochSeconds)) {
            "Timestamp could not be converted to UTC."
        }
        return buildString {
            append((utcFields.year % 100).toString().padStart(2, '0'))
            append(utcFields.month.toString().padStart(2, '0'))
            append(utcFields.day.toString().padStart(2, '0'))
            append(utcFields.hour.toString().padStart(2, '0'))
            append(utcFields.minute.toString().padStart(2, '0'))
            append(utcFields.second.toString().padStart(2, '0'))
        }
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

    private fun parseKeyValueFields(text: String): Map<String, String> {
        return text
            .split(Regex("""\s+"""))
            .mapNotNull { token ->
                val separatorIndex = token.indexOf('=')
                if (separatorIndex <= 0 || separatorIndex == token.lastIndex) {
                    null
                } else {
                    token.substring(0, separatorIndex) to token.substring(separatorIndex + 1)
                }
            }
            .toMap()
    }

    private fun parseIntLiteral(text: String): Int {
        val normalized = text.trim()
        return if (normalized.startsWith("0x", ignoreCase = true)) {
            normalized.drop(2).toInt(16)
        } else {
            normalized.toInt()
        }
    }

    private fun parseFoxRole(raw: String): FoxRole? {
        val normalized = raw.trim()
        val normalizedToken = normalized.trimStart('0').ifEmpty { "0" }
        return FoxRole.entries.firstOrNull {
            it.label.equals(normalized, ignoreCase = true) ||
                it.uiLabel.equals(normalized, ignoreCase = true) ||
                it.commandToken.equals(normalized, ignoreCase = true) ||
                it.commandToken.equals(normalizedToken, ignoreCase = true)
        }
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

    private fun parseEpochSecondsCompact(raw: String): String? {
        val epochSeconds = raw.trim().toLongOrNull() ?: return null
        if (epochSeconds < MinimumValidEpochSeconds) {
            return null
        }
        val fields = platformLocalDateTimeFields(epochSeconds) ?: return null
        val year = (fields.year % 100).toString().padStart(2, '0')
        val month = fields.month.toString().padStart(2, '0')
        val day = fields.day.toString().padStart(2, '0')
        val hour = fields.hour.toString().padStart(2, '0')
        val minute = fields.minute.toString().padStart(2, '0')
        val second = fields.second.toString().padStart(2, '0')
        return "$year$month$day$hour$minute$second"
    }

    private const val MinimumValidEpochSeconds = 1_609_459_200L

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
        val modeToken = when {
            editedSettings.externalBatteryControlMode == ExternalBatteryControlMode.CHARGE_ONLY ||
                !editedSettings.transmissionsEnabled -> "2"
            editedSettings.externalBatteryControlMode == ExternalBatteryControlMode.CHARGE_AND_TRANSMIT -> "1"
            else -> "0"
        }

        return "BAT X $modeToken"
    }
}
