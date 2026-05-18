package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.model.TimedEventDefaultFrequencies
import java.util.prefs.Preferences

enum class FrequencyDisplayUnit {
    KHZ,
    MHZ,
}

enum class TemperatureDisplayUnit {
    CELSIUS,
    FAHRENHEIT,
}

enum class TimeSetMode {
    AUTOMATIC,
    SEMI_AUTOMATIC,
    MANUAL,
}

enum class ScheduleTimeInputMode {
    ABSOLUTE,
    RELATIVE,
}

data class DesktopDisplayPreferences(
    val frequencyDisplayUnit: FrequencyDisplayUnit = FrequencyDisplayUnit.MHZ,
    val temperatureDisplayUnit: TemperatureDisplayUnit = TemperatureDisplayUnit.CELSIUS,
    val logVisible: Boolean = false,
    val rawSerialVisible: Boolean = true,
    val timeSetMode: TimeSetMode = TimeSetMode.SEMI_AUTOMATIC,
    val scheduleTimeInputMode: ScheduleTimeInputMode = ScheduleTimeInputMode.ABSOLUTE,
    val defaultEventLengthMinutes: Int = 6 * 60,
    val advancedModeEnabled: Boolean = false,
    val automaticFirmwareUpdatesEnabled: Boolean = true,
    val timedEventDefaultFrequencies: TimedEventDefaultFrequencies = FrequencySupport.defaultTimedEventFrequencies,
)

interface DesktopDisplayPreferencesStore {
    fun load(): DesktopDisplayPreferences

    fun save(preferences: DesktopDisplayPreferences)
}

object PreferencesDesktopDisplayPreferencesStore : DesktopDisplayPreferencesStore {
    private const val nodePath = "com/openardf/serialslinger"
    private const val keyFrequencyDisplayUnit = "frequencyDisplayUnit"
    private const val keyTemperatureDisplayUnit = "temperatureDisplayUnit"
    private const val keyLogVisible = "logVisible"
    private const val keyRawSerialVisible = "rawSerialVisible"
    private const val keyTimeSetMode = "timeSetMode"
    private const val keyScheduleTimeInputMode = "scheduleTimeInputMode"
    private const val keyDefaultEventLengthMinutes = "defaultEventLengthMinutes"
    private const val keyAdvancedModeEnabled = "advancedModeEnabled"
    private const val keyAutomaticFirmwareUpdatesEnabled = "automaticFirmwareUpdatesEnabled"
    private const val keyTimedEventDefaultFrequency1Hz = "timedEventDefaultFrequency1Hz"
    private const val keyTimedEventDefaultFrequency2Hz = "timedEventDefaultFrequency2Hz"
    private const val keyTimedEventDefaultFrequency3Hz = "timedEventDefaultFrequency3Hz"
    private const val keyTimedEventDefaultFrequencyBHz = "timedEventDefaultFrequencyBHz"
    private val preferences: Preferences = Preferences.userRoot().node(nodePath)

    override fun load(): DesktopDisplayPreferences {
        return DesktopDisplayPreferences(
            frequencyDisplayUnit = loadEnum(
                key = keyFrequencyDisplayUnit,
                defaultValue = FrequencyDisplayUnit.MHZ,
            ),
            temperatureDisplayUnit = loadEnum(
                key = keyTemperatureDisplayUnit,
                defaultValue = TemperatureDisplayUnit.CELSIUS,
            ),
            logVisible = preferences.getBoolean(keyLogVisible, false),
            rawSerialVisible = preferences.getBoolean(keyRawSerialVisible, true),
            timeSetMode = loadEnum(
                key = keyTimeSetMode,
                defaultValue = TimeSetMode.SEMI_AUTOMATIC,
                legacyNames = mapOf("SYSTEM_CLOCK" to TimeSetMode.SEMI_AUTOMATIC),
            ),
            scheduleTimeInputMode = loadEnum(
                key = keyScheduleTimeInputMode,
                defaultValue = ScheduleTimeInputMode.ABSOLUTE,
            ),
            defaultEventLengthMinutes = preferences.getInt(keyDefaultEventLengthMinutes, 6 * 60).coerceIn(10, 24 * 60),
            advancedModeEnabled = preferences.getBoolean(keyAdvancedModeEnabled, false),
            automaticFirmwareUpdatesEnabled = preferences.getBoolean(keyAutomaticFirmwareUpdatesEnabled, true),
            timedEventDefaultFrequencies =
                FrequencySupport.sanitizeTimedEventDefaultFrequencies(
                    TimedEventDefaultFrequencies(
                        frequency1Hz = preferences.getLong(
                            keyTimedEventDefaultFrequency1Hz,
                            FrequencySupport.defaultTimedEventFrequencies.frequency1Hz,
                        ),
                        frequency2Hz = preferences.getLong(
                            keyTimedEventDefaultFrequency2Hz,
                            FrequencySupport.defaultTimedEventFrequencies.frequency2Hz,
                        ),
                        frequency3Hz = preferences.getLong(
                            keyTimedEventDefaultFrequency3Hz,
                            FrequencySupport.defaultTimedEventFrequencies.frequency3Hz,
                        ),
                        frequencyBHz = preferences.getLong(
                            keyTimedEventDefaultFrequencyBHz,
                            FrequencySupport.defaultTimedEventFrequencies.frequencyBHz,
                        ),
                    ),
                ),
        )
    }

    override fun save(preferences: DesktopDisplayPreferences) {
        val sanitizedTimedEventDefaults =
            FrequencySupport.sanitizeTimedEventDefaultFrequencies(preferences.timedEventDefaultFrequencies)
        this.preferences.put(keyFrequencyDisplayUnit, preferences.frequencyDisplayUnit.name)
        this.preferences.put(keyTemperatureDisplayUnit, preferences.temperatureDisplayUnit.name)
        this.preferences.putBoolean(keyLogVisible, preferences.logVisible)
        this.preferences.putBoolean(keyRawSerialVisible, preferences.rawSerialVisible)
        this.preferences.put(keyTimeSetMode, preferences.timeSetMode.name)
        this.preferences.put(keyScheduleTimeInputMode, preferences.scheduleTimeInputMode.name)
        this.preferences.putInt(keyDefaultEventLengthMinutes, preferences.defaultEventLengthMinutes.coerceIn(10, 24 * 60))
        this.preferences.putBoolean(keyAdvancedModeEnabled, preferences.advancedModeEnabled)
        this.preferences.putBoolean(keyAutomaticFirmwareUpdatesEnabled, preferences.automaticFirmwareUpdatesEnabled)
        this.preferences.putLong(keyTimedEventDefaultFrequency1Hz, sanitizedTimedEventDefaults.frequency1Hz)
        this.preferences.putLong(keyTimedEventDefaultFrequency2Hz, sanitizedTimedEventDefaults.frequency2Hz)
        this.preferences.putLong(keyTimedEventDefaultFrequency3Hz, sanitizedTimedEventDefaults.frequency3Hz)
        this.preferences.putLong(keyTimedEventDefaultFrequencyBHz, sanitizedTimedEventDefaults.frequencyBHz)
        this.preferences.flush()
    }

    private inline fun <reified T : Enum<T>> loadEnum(
        key: String,
        defaultValue: T,
        legacyNames: Map<String, T> = emptyMap(),
    ): T {
        val raw = preferences.get(key, defaultValue.name) ?: return defaultValue
        legacyNames[raw]?.let { return it }
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(defaultValue)
    }
}
