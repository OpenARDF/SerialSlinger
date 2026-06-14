package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.DeviceCapabilities
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.EventProfileSupport
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.SettingsField

object DesktopCloneSupport {
    fun buildEditableSettings(
        targetBaseSettings: DeviceSettings,
        templateSettings: DeviceSettings,
        capabilities: DeviceCapabilities,
    ): EditableDeviceSettings {
        return EditableDeviceSettings.fromDeviceSettings(targetBaseSettings).copy(
            stationId = cloneField("stationId", "Station ID", targetBaseSettings.stationId, templateSettings.stationId, capabilities.supportsStationIdEditing),
            eventType = cloneField("eventType", "Event Type", targetBaseSettings.eventType, templateSettings.eventType, capabilities.supportsEventTypeEditing),
            idCodeSpeedWpm = cloneField("idCodeSpeedWpm", "ID Speed", targetBaseSettings.idCodeSpeedWpm, templateSettings.idCodeSpeedWpm, capabilities.supportsIdCodeSpeedEditing),
            amToneFrequency = cloneField("amToneFrequency", "AM Tone", targetBaseSettings.amToneFrequency, templateSettings.amToneFrequency, capabilities.supportsAmToneEditing),
            startTimeCompact = cloneField("startTimeCompact", "Start Time", targetBaseSettings.startTimeCompact, templateSettings.startTimeCompact, capabilities.supportsScheduling),
            finishTimeCompact = cloneField("finishTimeCompact", "Finish Time", targetBaseSettings.finishTimeCompact, templateSettings.finishTimeCompact, capabilities.supportsScheduling),
            daysToRun = cloneField("daysToRun", "Days To Run", targetBaseSettings.daysToRun, templateSettings.daysToRun, capabilities.supportsScheduling && capabilities.supportsDaysToRun),
            patternCodeSpeedWpm = targetBaseSettings.patternCodeSpeedWpm.let { targetPatternSpeed ->
                SettingsField(
                    "patternCodeSpeedWpm",
                    "Pattern Speed",
                    targetPatternSpeed,
                    if (capabilities.supportsPatternEditing) {
                        cloneTemplatePatternSpeedFor(templateSettings) ?: targetPatternSpeed
                    } else {
                        targetPatternSpeed
                    },
                )
            },
            lowFrequencyHz = cloneField("lowFrequencyHz", "Frequency 1 (FRE 1)", targetBaseSettings.lowFrequencyHz, templateSettings.lowFrequencyHz, capabilities.supportsFrequencyProfiles),
            mediumFrequencyHz = cloneField("mediumFrequencyHz", "Frequency 2 (FRE 2)", targetBaseSettings.mediumFrequencyHz, templateSettings.mediumFrequencyHz, capabilities.supportsFrequencyProfiles),
            highFrequencyHz = cloneField("highFrequencyHz", "Frequency 3 (FRE 3)", targetBaseSettings.highFrequencyHz, templateSettings.highFrequencyHz, capabilities.supportsFrequencyProfiles),
            beaconFrequencyHz = cloneField("beaconFrequencyHz", "Frequency B (FRE B)", targetBaseSettings.beaconFrequencyHz, templateSettings.beaconFrequencyHz, capabilities.supportsFrequencyProfiles),
        )
    }

    fun comparedFieldKeys(
        capabilities: DeviceCapabilities,
        templateSettings: DeviceSettings?,
    ): List<SettingKey> {
        return buildList {
            if (capabilities.supportsStationIdEditing) {
                add(SettingKey.STATION_ID)
            }
            if (capabilities.supportsEventTypeEditing) {
                add(SettingKey.EVENT_TYPE)
            }
            if (capabilities.supportsIdCodeSpeedEditing) {
                add(SettingKey.ID_CODE_SPEED_WPM)
            }
            if (capabilities.supportsAmToneEditing) {
                add(SettingKey.AM_TONE_FREQUENCY)
            }
            if (
                capabilities.supportsPatternEditing &&
                templateSettings?.let { cloneTemplatePatternSpeedFor(it) } != null
            ) {
                add(SettingKey.PATTERN_CODE_SPEED_WPM)
            }
            if (capabilities.supportsScheduling) {
                add(SettingKey.START_TIME)
                add(SettingKey.FINISH_TIME)
            }
            if (capabilities.supportsDaysToRun) {
                add(SettingKey.DAYS_TO_RUN)
            }
            if (capabilities.supportsFrequencyProfiles) {
                add(SettingKey.LOW_FREQUENCY_HZ)
                add(SettingKey.MEDIUM_FREQUENCY_HZ)
                add(SettingKey.HIGH_FREQUENCY_HZ)
                add(SettingKey.BEACON_FREQUENCY_HZ)
            }
        }
    }

    private fun <T> cloneField(
        key: String,
        label: String,
        currentValue: T,
        templateValue: T,
        supported: Boolean,
    ): SettingsField<T> = SettingsField(key, label, currentValue, if (supported) templateValue else currentValue)

    private fun cloneTemplatePatternSpeedFor(settings: DeviceSettings): Int? {
        return if (EventProfileSupport.patternSpeedBelongsToTimedEventSettings(settings.eventType)) {
            settings.patternCodeSpeedWpm
        } else {
            null
        }
    }
}
