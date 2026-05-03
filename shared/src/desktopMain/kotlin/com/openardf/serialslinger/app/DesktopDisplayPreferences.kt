package com.openardf.serialslinger.app

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
    MANUAL,
    SYSTEM_CLOCK,
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
    val timeSetMode: TimeSetMode = TimeSetMode.SYSTEM_CLOCK,
    val scheduleTimeInputMode: ScheduleTimeInputMode = ScheduleTimeInputMode.ABSOLUTE,
    val defaultEventLengthMinutes: Int = 6 * 60,
    val advancedModeEnabled: Boolean = false,
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
                defaultValue = TimeSetMode.SYSTEM_CLOCK,
            ),
            scheduleTimeInputMode = loadEnum(
                key = keyScheduleTimeInputMode,
                defaultValue = ScheduleTimeInputMode.ABSOLUTE,
            ),
            defaultEventLengthMinutes = preferences.getInt(keyDefaultEventLengthMinutes, 6 * 60).coerceIn(10, 24 * 60),
            advancedModeEnabled = preferences.getBoolean(keyAdvancedModeEnabled, false),
        )
    }

    override fun save(preferences: DesktopDisplayPreferences) {
        this.preferences.put(keyFrequencyDisplayUnit, preferences.frequencyDisplayUnit.name)
        this.preferences.put(keyTemperatureDisplayUnit, preferences.temperatureDisplayUnit.name)
        this.preferences.putBoolean(keyLogVisible, preferences.logVisible)
        this.preferences.putBoolean(keyRawSerialVisible, preferences.rawSerialVisible)
        this.preferences.put(keyTimeSetMode, preferences.timeSetMode.name)
        this.preferences.put(keyScheduleTimeInputMode, preferences.scheduleTimeInputMode.name)
        this.preferences.putInt(keyDefaultEventLengthMinutes, preferences.defaultEventLengthMinutes.coerceIn(10, 24 * 60))
        this.preferences.putBoolean(keyAdvancedModeEnabled, preferences.advancedModeEnabled)
        this.preferences.flush()
    }

    private inline fun <reified T : Enum<T>> loadEnum(key: String, defaultValue: T): T {
        val raw = preferences.get(key, defaultValue.name) ?: return defaultValue
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(defaultValue)
    }
}
