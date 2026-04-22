package com.openardf.serialslinger.model

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

enum class EventType {
    NONE,
    CLASSIC,
    FOXORING,
    SPRINT,
}

enum class FoxRole(
    val label: String,
    val commandToken: String,
    val uiLabel: String,
    val fixedPatternText: String? = null,
) {
    BEACON("""Beacon "MO"""", "B", "BEACON", "MO"),
    CLASSIC_1("""Classic Fox 1 "MOE"""", "1", "FOX 1", "MOE"),
    CLASSIC_2("""Classic Fox 2 "MOI"""", "2", "FOX 2", "MOI"),
    CLASSIC_3("""Classic Fox 3 "MOS"""", "3", "FOX 3", "MOS"),
    CLASSIC_4("""Classic Fox 4 "MOH"""", "4", "FOX 4", "MOH"),
    CLASSIC_5("""Classic Fox 5 "MO5"""", "5", "FOX 5", "MO5"),
    SPRINT_SPECTATOR("""Spectator "S"""", "S", "SPECTATOR", "S"),
    SPRINT_SLOW_1("""Sprint Slow 1 "ME"""", "1", "SLOW 1", "ME"),
    SPRINT_SLOW_2("""Sprint Slow 2 "MI"""", "2", "SLOW 2", "MI"),
    SPRINT_SLOW_3("""Sprint Slow 3 "MS"""", "3", "SLOW 3", "MS"),
    SPRINT_SLOW_4("""Sprint Slow 4 "MH"""", "4", "SLOW 4", "MH"),
    SPRINT_SLOW_5("""Sprint Slow 5 "M5"""", "5", "SLOW 5", "M5"),
    SPRINT_FAST_1("""Sprint Fast 1 "OE"""", "1F", "FAST 1", "OE"),
    SPRINT_FAST_2("""Sprint Fast 2 "OI"""", "2F", "FAST 2", "OI"),
    SPRINT_FAST_3("""Sprint Fast 3 "OS"""", "3F", "FAST 3", "OS"),
    SPRINT_FAST_4("""Sprint Fast 4 "OH"""", "4F", "FAST 4", "OH"),
    SPRINT_FAST_5("""Sprint Fast 5 "O5"""", "5F", "FAST 5", "O5"),
    FOXORING_1("""Foxoring "Low Freq" Fox""", "1", "FREQ 1"),
    FOXORING_2("""Foxoring "Medium Freq" Fox""", "2", "FREQ 2"),
    FOXORING_3("""Foxoring "High Freq" Fox""", "3", "FREQ 3"),
    FREQUENCY_TEST_BEACON("Frequency Test Beacon", "F", "FREQ TEST"),

    ;

    override fun toString(): String = uiLabel
}

enum class ExternalBatteryControlMode(
    val uiLabel: String,
) {
    OFF("Ext Battery Control Disabled"),
    CHARGE_AND_TRANSMIT("Ext Battery Control Enabled"),
    CHARGE_ONLY("Ext Device Control (Tx Disabled)"),
    ;

    override fun toString(): String = uiLabel
}

data class DeviceCapabilities(
    val supportsTemperatureReadback: Boolean = false,
    val supportsExtendedTemperatureReadback: Boolean = false,
    val supportsExternalBatteryControl: Boolean = false,
    val supportsPatternEditing: Boolean = false,
    val supportsScheduling: Boolean = false,
    val supportsFrequencyProfiles: Boolean = false,
)

data class DeviceInfo(
    val productName: String? = null,
    val softwareVersion: String? = null,
    val hardwareBuild: String? = null,
    val serialPortName: String? = null,
)

data class DeviceStatus(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
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
    val lastCommunicationError: String? = null,
)

data class DeviceSettings(
    val stationId: String,
    val eventType: EventType,
    val foxRole: FoxRole? = null,
    val patternText: String?,
    val idCodeSpeedWpm: Int,
    val patternCodeSpeedWpm: Int,
    val currentTimeCompact: String?,
    val startTimeCompact: String?,
    val finishTimeCompact: String?,
    val daysToRun: Int,
    val defaultFrequencyHz: Long,
    val lowFrequencyHz: Long?,
    val mediumFrequencyHz: Long?,
    val highFrequencyHz: Long?,
    val beaconFrequencyHz: Long?,
    val lowBatteryThresholdVolts: Double?,
    val externalBatteryControlMode: ExternalBatteryControlMode?,
    val transmissionsEnabled: Boolean,
) {
    companion object {
        fun empty(): DeviceSettings {
            return DeviceSettings(
                stationId = "",
                eventType = EventType.NONE,
                foxRole = null,
                patternText = null,
                idCodeSpeedWpm = 0,
                patternCodeSpeedWpm = 0,
                currentTimeCompact = null,
                startTimeCompact = null,
                finishTimeCompact = null,
                daysToRun = 1,
                defaultFrequencyHz = 0L,
                lowFrequencyHz = null,
                mediumFrequencyHz = null,
                highFrequencyHz = null,
                beaconFrequencyHz = null,
                lowBatteryThresholdVolts = null,
                externalBatteryControlMode = null,
                transmissionsEnabled = true,
            )
        }
    }
}

data class DeviceSnapshot(
    val info: DeviceInfo = DeviceInfo(),
    val status: DeviceStatus = DeviceStatus(),
    val settings: DeviceSettings = DeviceSettings.empty(),
    val capabilities: DeviceCapabilities = DeviceCapabilities(),
)

enum class ValidationState {
    VALID,
    INVALID,
}

data class SettingsField<T>(
    val key: String,
    val label: String,
    val originalValue: T,
    val editedValue: T,
    val isWritable: Boolean = true,
    val isVisible: Boolean = true,
    val validationState: ValidationState = ValidationState.VALID,
    val errorMessage: String? = null,
) {
    val isDirty: Boolean
        get() = originalValue != editedValue

    val hasError: Boolean
        get() = validationState == ValidationState.INVALID
}

data class EditableDeviceSettings(
    val stationId: SettingsField<String>,
    val eventType: SettingsField<EventType>,
    val foxRole: SettingsField<FoxRole?>,
    val patternText: SettingsField<String?>,
    val idCodeSpeedWpm: SettingsField<Int>,
    val patternCodeSpeedWpm: SettingsField<Int>,
    val currentTimeCompact: SettingsField<String?>,
    val startTimeCompact: SettingsField<String?>,
    val finishTimeCompact: SettingsField<String?>,
    val daysToRun: SettingsField<Int>,
    val defaultFrequencyHz: SettingsField<Long>,
    val lowFrequencyHz: SettingsField<Long?>,
    val mediumFrequencyHz: SettingsField<Long?>,
    val highFrequencyHz: SettingsField<Long?>,
    val beaconFrequencyHz: SettingsField<Long?>,
    val lowBatteryThresholdVolts: SettingsField<Double?>,
    val externalBatteryControlMode: SettingsField<ExternalBatteryControlMode?>,
    val transmissionsEnabled: SettingsField<Boolean>,
) {
    val fields: List<SettingsField<*>> = listOf(
        stationId,
        eventType,
        foxRole,
        patternText,
        idCodeSpeedWpm,
        patternCodeSpeedWpm,
        currentTimeCompact,
        startTimeCompact,
        finishTimeCompact,
        daysToRun,
        defaultFrequencyHz,
        lowFrequencyHz,
        mediumFrequencyHz,
        highFrequencyHz,
        beaconFrequencyHz,
        lowBatteryThresholdVolts,
        externalBatteryControlMode,
        transmissionsEnabled,
    )

    val hasDirtyFields: Boolean
        get() = fields.any { it.isDirty }

    fun toValidatedDeviceSettings(): DeviceSettings {
        val invalidField = fields.firstOrNull { it.hasError }
        require(invalidField == null) {
            "Field `${invalidField!!.key}` is invalid${invalidField.errorMessage?.let { ": $it" } ?: "."}"
        }

        return DeviceSettings(
            stationId = stationId.editedValue,
            eventType = eventType.editedValue,
            foxRole = foxRole.editedValue,
            patternText = patternText.editedValue,
            idCodeSpeedWpm = idCodeSpeedWpm.editedValue,
            patternCodeSpeedWpm = patternCodeSpeedWpm.editedValue,
            currentTimeCompact = currentTimeCompact.editedValue,
            startTimeCompact = startTimeCompact.editedValue,
            finishTimeCompact = finishTimeCompact.editedValue,
            daysToRun = daysToRun.editedValue,
            defaultFrequencyHz = defaultFrequencyHz.editedValue,
            lowFrequencyHz = lowFrequencyHz.editedValue,
            mediumFrequencyHz = mediumFrequencyHz.editedValue,
            highFrequencyHz = highFrequencyHz.editedValue,
            beaconFrequencyHz = beaconFrequencyHz.editedValue,
            lowBatteryThresholdVolts = lowBatteryThresholdVolts.editedValue,
            externalBatteryControlMode = externalBatteryControlMode.editedValue,
            transmissionsEnabled = transmissionsEnabled.editedValue,
        )
    }

    fun writableVisibleFields(capabilities: DeviceCapabilities): List<SettingsField<*>> {
        return fields.filter { field ->
            field.isWritable && field.isVisible && capabilityAllows(field.key, capabilities)
        }
    }

    private fun capabilityAllows(fieldKey: String, capabilities: DeviceCapabilities): Boolean {
        return when (fieldKey) {
            "patternText" -> capabilities.supportsPatternEditing
            "lowFrequencyHz", "mediumFrequencyHz", "highFrequencyHz", "beaconFrequencyHz" -> capabilities.supportsFrequencyProfiles
            "lowBatteryThresholdVolts", "externalBatteryControlMode" -> capabilities.supportsExternalBatteryControl
            "currentTimeCompact", "startTimeCompact", "finishTimeCompact", "daysToRun" -> capabilities.supportsScheduling
            else -> true
        }
    }

    companion object {
        fun fromDeviceSettings(settings: DeviceSettings): EditableDeviceSettings {
            return EditableDeviceSettings(
                stationId = SettingsField("stationId", "Station ID", settings.stationId, settings.stationId),
                eventType = SettingsField("eventType", "Event Type", settings.eventType, settings.eventType),
                foxRole = SettingsField("foxRole", "Fox Role (FOX)", settings.foxRole, settings.foxRole),
                patternText = SettingsField("patternText", "Pattern Text", settings.patternText, settings.patternText),
                idCodeSpeedWpm = SettingsField("idCodeSpeedWpm", "ID Speed", settings.idCodeSpeedWpm, settings.idCodeSpeedWpm),
                patternCodeSpeedWpm = SettingsField("patternCodeSpeedWpm", "Pattern Speed", settings.patternCodeSpeedWpm, settings.patternCodeSpeedWpm),
                currentTimeCompact = SettingsField("currentTimeCompact", "Current Time", settings.currentTimeCompact, settings.currentTimeCompact),
                startTimeCompact = SettingsField("startTimeCompact", "Start Time", settings.startTimeCompact, settings.startTimeCompact),
                finishTimeCompact = SettingsField("finishTimeCompact", "Finish Time", settings.finishTimeCompact, settings.finishTimeCompact),
                daysToRun = SettingsField("daysToRun", "Days To Run", settings.daysToRun, settings.daysToRun),
                defaultFrequencyHz = SettingsField(
                    "defaultFrequencyHz",
                    "Current Frequency (FRE)",
                    settings.defaultFrequencyHz,
                    settings.defaultFrequencyHz,
                ),
                lowFrequencyHz = SettingsField("lowFrequencyHz", "Frequency 1 (FRE 1)", settings.lowFrequencyHz, settings.lowFrequencyHz),
                mediumFrequencyHz = SettingsField("mediumFrequencyHz", "Frequency 2 (FRE 2)", settings.mediumFrequencyHz, settings.mediumFrequencyHz),
                highFrequencyHz = SettingsField("highFrequencyHz", "Frequency 3 (FRE 3)", settings.highFrequencyHz, settings.highFrequencyHz),
                beaconFrequencyHz = SettingsField("beaconFrequencyHz", "Frequency B (FRE B)", settings.beaconFrequencyHz, settings.beaconFrequencyHz),
                lowBatteryThresholdVolts = SettingsField(
                    "lowBatteryThresholdVolts",
                    "Low Battery Threshold",
                    settings.lowBatteryThresholdVolts,
                    settings.lowBatteryThresholdVolts,
                ),
                externalBatteryControlMode = SettingsField(
                    "externalBatteryControlMode",
                    "External Battery Control",
                    settings.externalBatteryControlMode,
                    settings.externalBatteryControlMode,
                ),
                transmissionsEnabled = SettingsField(
                    "transmissionsEnabled",
                    "RF Transmissions",
                    settings.transmissionsEnabled,
                    settings.transmissionsEnabled,
                ),
            )
        }
    }
}
