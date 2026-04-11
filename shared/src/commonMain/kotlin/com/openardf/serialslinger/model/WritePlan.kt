package com.openardf.serialslinger.model

enum class SettingKey {
    STATION_ID,
    EVENT_TYPE,
    FOX_ROLE,
    PATTERN_TEXT,
    ID_CODE_SPEED_WPM,
    PATTERN_CODE_SPEED_WPM,
    CURRENT_TIME,
    START_TIME,
    FINISH_TIME,
    DAYS_TO_RUN,
    DEFAULT_FREQUENCY_HZ,
    LOW_FREQUENCY_HZ,
    MEDIUM_FREQUENCY_HZ,
    HIGH_FREQUENCY_HZ,
    BEACON_FREQUENCY_HZ,
    LOW_BATTERY_THRESHOLD_VOLTS,
    EXTERNAL_BATTERY_CONTROL_MODE,
    TRANSMISSIONS_ENABLED,
}

data class SettingChange(
    val fieldKey: SettingKey,
    val oldValue: Any?,
    val newValue: Any?,
    val requiresVerification: Boolean,
    val writeOrder: Int,
)

data class WritePlan(
    val changes: List<SettingChange>,
    val warnings: List<String> = emptyList(),
    val blockingErrors: List<String> = emptyList(),
)

object WritePlanner {
    fun create(original: DeviceSettings, edited: DeviceSettings): WritePlan {
        val changes = buildList {
            addIfChanged(SettingKey.STATION_ID, 10, original.stationId, edited.stationId, true)
            addIfChanged(SettingKey.EVENT_TYPE, 20, original.eventType, edited.eventType, true)
            addIfChanged(SettingKey.FOX_ROLE, 30, original.foxRole, edited.foxRole, true)
            addIfChanged(SettingKey.PATTERN_TEXT, 40, original.patternText, edited.patternText, true)
            addIfChanged(SettingKey.ID_CODE_SPEED_WPM, 50, original.idCodeSpeedWpm, edited.idCodeSpeedWpm, true)
            addIfChanged(SettingKey.PATTERN_CODE_SPEED_WPM, 60, original.patternCodeSpeedWpm, edited.patternCodeSpeedWpm, true)
            addIfChanged(SettingKey.CURRENT_TIME, 70, original.currentTimeCompact, edited.currentTimeCompact, true)
            addIfChanged(SettingKey.START_TIME, 80, original.startTimeCompact, edited.startTimeCompact, true)
            addIfChanged(SettingKey.FINISH_TIME, 90, original.finishTimeCompact, edited.finishTimeCompact, true)
            addIfChanged(SettingKey.DAYS_TO_RUN, 100, original.daysToRun, edited.daysToRun, true)
            addIfChanged(SettingKey.DEFAULT_FREQUENCY_HZ, 110, original.defaultFrequencyHz, edited.defaultFrequencyHz, true)
            addIfChanged(SettingKey.LOW_FREQUENCY_HZ, 120, original.lowFrequencyHz, edited.lowFrequencyHz, true)
            addIfChanged(SettingKey.MEDIUM_FREQUENCY_HZ, 130, original.mediumFrequencyHz, edited.mediumFrequencyHz, true)
            addIfChanged(SettingKey.HIGH_FREQUENCY_HZ, 140, original.highFrequencyHz, edited.highFrequencyHz, true)
            addIfChanged(SettingKey.BEACON_FREQUENCY_HZ, 150, original.beaconFrequencyHz, edited.beaconFrequencyHz, true)
            addIfChanged(SettingKey.LOW_BATTERY_THRESHOLD_VOLTS, 160, original.lowBatteryThresholdVolts, edited.lowBatteryThresholdVolts, true)
            addIfChanged(SettingKey.EXTERNAL_BATTERY_CONTROL_MODE, 170, original.externalBatteryControlMode, edited.externalBatteryControlMode, true)
            addIfChanged(SettingKey.TRANSMISSIONS_ENABLED, 180, original.transmissionsEnabled, edited.transmissionsEnabled, true)
        }.sortedBy { it.writeOrder }

        return WritePlan(changes = changes)
    }

    private fun MutableList<SettingChange>.addIfChanged(
        key: SettingKey,
        order: Int,
        oldValue: Any?,
        newValue: Any?,
        requiresVerification: Boolean,
    ) {
        if (oldValue != newValue) {
            add(
                SettingChange(
                    fieldKey = key,
                    oldValue = oldValue,
                    newValue = newValue,
                    requiresVerification = requiresVerification,
                    writeOrder = order,
                ),
            )
        }
    }
}
