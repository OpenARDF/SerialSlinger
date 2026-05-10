package com.openardf.serialslinger.app

internal object TemperatureResetSupport {
    const val probeCommand: String = "TMP R"

    fun isSupportedProbeResponse(responseLines: List<String>): Boolean {
        return responseLines.any { line ->
            line.trim().removePrefix("*").trim().equals(probeCommand, ignoreCase = true)
        }
    }
}
