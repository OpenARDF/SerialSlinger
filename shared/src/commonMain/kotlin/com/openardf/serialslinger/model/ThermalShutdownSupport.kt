package com.openardf.serialslinger.model

import kotlin.math.roundToInt

object ThermalShutdownSupport {
    const val passcode: String = "7373"
    const val minimumCelsius: Int = 30
    const val maximumCelsius: Int = 55

    fun validateCelsius(value: Int, productName: String? = null): Int {
        require(value in minimumCelsius..maximumCelsius) {
            "Thermal Shutdown Threshold must be between $minimumCelsius C and $maximumCelsius C."
        }
        return value
    }

    fun celsiusToFahrenheit(value: Int): Int {
        return ((value * 9.0 / 5.0) + 32.0).roundToInt()
    }

    fun fahrenheitToCelsius(value: Int): Int {
        return ((value - 32.0) * 5.0 / 9.0).roundToInt()
    }

    fun commandForCelsius(value: Int, productName: String? = null): String {
        val validated = validateCelsius(value, productName)
        return if (productName.equals("Arducon", ignoreCase = true)) {
            "UTI H $validated"
        } else {
            "TMP H $validated"
        }
    }
}
