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

data class DesktopDisplayPreferences(
    val frequencyDisplayUnit: FrequencyDisplayUnit = FrequencyDisplayUnit.MHZ,
    val temperatureDisplayUnit: TemperatureDisplayUnit = TemperatureDisplayUnit.CELSIUS,
    val logVisible: Boolean = false,
    val rawSerialVisible: Boolean = true,
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
        )
    }

    override fun save(preferences: DesktopDisplayPreferences) {
        this.preferences.put(keyFrequencyDisplayUnit, preferences.frequencyDisplayUnit.name)
        this.preferences.put(keyTemperatureDisplayUnit, preferences.temperatureDisplayUnit.name)
        this.preferences.putBoolean(keyLogVisible, preferences.logVisible)
        this.preferences.putBoolean(keyRawSerialVisible, preferences.rawSerialVisible)
        this.preferences.flush()
    }

    private inline fun <reified T : Enum<T>> loadEnum(key: String, defaultValue: T): T {
        val raw = preferences.get(key, defaultValue.name) ?: return defaultValue
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(defaultValue)
    }
}
