package com.openardf.serialslinger.model

/** One durable hardware-failure report read from a SignalSlinger unit. */
data class HardwareFaultRecord(
    val code: Int,
    val detectedAtCompact: String?,
    val timeValid: Boolean,
) {
    val description: String
        get() = when (code) {
            1 -> "Clock hardware did not respond"
            2 -> "Transmit hardware did not respond"
            else -> "Hardware fault $code"
        }
}

/** Shared warning text and clear command used by the desktop and Android interfaces. */
object HardwareFaultPresentation {
    const val title = "HARDWARE FAILURE DETECTED"
    const val clearLabel = "Clear After Repair"
    const val clearCommand = "HWF C"
    const val clearDialogTitle = "Clear Hardware Failure Log"
    const val clearConfirmation =
        "Clear the stored hardware-failure log only after the unit has been repaired?\n\n" +
            "A fault that is still active will be recorded again immediately."

    fun summary(records: List<HardwareFaultRecord>): String = records.joinToString("\n") { record ->
        val detected = record.detectedAtCompact?.let(::formatCompactTimestamp) ?: "time unavailable"
        "${record.description} — first detected $detected"
    }

    private fun formatCompactTimestamp(value: String): String {
        if (value.length != 12 || value.any { !it.isDigit() }) return value
        return "20${value.substring(0, 2)}-${value.substring(2, 4)}-${value.substring(4, 6)} " +
            "${value.substring(6, 8)}:${value.substring(8, 10)}:${value.substring(10, 12)}"
    }
}
