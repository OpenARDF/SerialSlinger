package com.openardf.serialslinger.model

enum class TemperatureAlertLevel {
    NORMAL,
    WARNING,
    DANGER,
}

object TemperatureAlertSupport {
    private const val warningThresholdC = 40.0
    private const val dangerThresholdC = 50.0

    fun alertLevel(temperatureC: Double?): TemperatureAlertLevel {
        return when {
            temperatureC == null -> TemperatureAlertLevel.NORMAL
            temperatureC > dangerThresholdC -> TemperatureAlertLevel.DANGER
            temperatureC > warningThresholdC -> TemperatureAlertLevel.WARNING
            else -> TemperatureAlertLevel.NORMAL
        }
    }
}
