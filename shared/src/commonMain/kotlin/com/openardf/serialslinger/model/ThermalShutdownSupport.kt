package com.openardf.serialslinger.model

import kotlin.math.roundToInt

object ThermalShutdownSupport {
    const val passcode: String = "7373"
    const val minimumCelsius: Int = 30
    const val signalSlingerMaximumCelsius: Int = 85
    const val arduconMaximumCelsius: Int = 60

    fun maximumCelsius(productName: String? = null): Int =
        if (productName.equals("Arducon", ignoreCase = true)) {
            arduconMaximumCelsius
        } else {
            signalSlingerMaximumCelsius
        }

    fun validateCelsius(value: Int, productName: String? = null): Int {
        val maximum = maximumCelsius(productName)
        require(value in minimumCelsius..maximum) {
            "Thermal Shutdown Threshold must be between $minimumCelsius C and $maximum C."
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

    fun commandForEnabled(enabled: Boolean, productName: String? = null): String {
        require(!productName.equals("Arducon", ignoreCase = true)) {
            "Arducon does not support changing Thermal Shutdown mode."
        }
        return "TMP H ${if (enabled) "ON" else "OFF"}"
    }
}
