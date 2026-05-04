package com.openardf.serialslinger.model

import kotlin.math.absoluteValue
import kotlin.math.roundToLong

enum class FrequencySlot(
    val fieldKey: String,
    val label: String,
) {
    DEFAULT("defaultFrequencyHz", "Default Frequency"),
    LOW("lowFrequencyHz", "Frequency 1"),
    MEDIUM("mediumFrequencyHz", "Frequency 2"),
    HIGH("highFrequencyHz", "Frequency 3"),
    BEACON("beaconFrequencyHz", "Beacon Frequency"),
}

enum class FrequencyBankId(
    val fieldKey: String,
    val label: String,
) {
    ONE("lowFrequencyHz", "Frequency 1"),
    TWO("mediumFrequencyHz", "Frequency 2"),
    THREE("highFrequencyHz", "Frequency 3"),
    BEACON("beaconFrequencyHz", "Frequency B"),
}

data class FrequencyBankState(
    val bankId: FrequencyBankId,
    val frequencyHz: Long?,
    val isCurrentBank: Boolean,
)

data class FrequencyPresentation(
    val currentFrequencyHz: Long,
    val currentBankId: FrequencyBankId?,
    val banks: List<FrequencyBankState>,
)

object FrequencySupport {
    private val unitPattern = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)\s*([kKmM]?)[hH][zZ]\s*$""")
    private val numericPattern = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)\s*$""")

    fun parseFrequencyHz(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        numericPattern.matchEntire(trimmed)?.let { match ->
            val magnitude = match.groupValues[1].toDouble()
            val scale = when {
                magnitude >= 100_000.0 -> 1.0
                magnitude >= 1_000.0 -> 1_000.0
                else -> 1_000_000.0
            }
            return (magnitude * scale).roundToLong()
        }

        val match = unitPattern.matchEntire(trimmed) ?: return null
        val magnitude = match.groupValues[1].toDouble()
        val scale = when (match.groupValues[2].lowercase()) {
            "m" -> 1_000_000.0
            "k" -> 1_000.0
            "" -> 1.0
            else -> return null
        }

        return (magnitude * scale).roundToLong()
    }

    fun formatFrequencyMhz(frequencyHz: Long?): String {
        if (frequencyHz == null) {
            return ""
        }

        val absoluteHz = frequencyHz.absoluteValue
        val wholeMhz = absoluteHz / 1_000_000
        val fractionalKhz = ((absoluteHz % 1_000_000) / 1_000).toInt()
        val sign = if (frequencyHz < 0) "-" else ""
        return "$sign$wholeMhz.${fractionalKhz.toString().padStart(3, '0')} MHz"
    }

    fun activeFrequencySlots(settings: DeviceSettings): List<FrequencySlot> {
        return buildList {
            add(FrequencySlot.DEFAULT)
            if (settings.lowFrequencyHz != null) add(FrequencySlot.LOW)
            if (settings.mediumFrequencyHz != null) add(FrequencySlot.MEDIUM)
            if (settings.highFrequencyHz != null) add(FrequencySlot.HIGH)
            if (settings.beaconFrequencyHz != null) add(FrequencySlot.BEACON)
        }
    }

    fun describeFrequencies(settings: DeviceSettings): FrequencyPresentation {
        val currentBankId = inferCurrentBank(settings)
        val banks = listOf(
            FrequencyBankState(
                bankId = FrequencyBankId.ONE,
                frequencyHz = settings.lowFrequencyHz,
                isCurrentBank = currentBankId == FrequencyBankId.ONE,
            ),
            FrequencyBankState(
                bankId = FrequencyBankId.TWO,
                frequencyHz = settings.mediumFrequencyHz,
                isCurrentBank = currentBankId == FrequencyBankId.TWO,
            ),
            FrequencyBankState(
                bankId = FrequencyBankId.THREE,
                frequencyHz = settings.highFrequencyHz,
                isCurrentBank = currentBankId == FrequencyBankId.THREE,
            ),
            FrequencyBankState(
                bankId = FrequencyBankId.BEACON,
                frequencyHz = settings.beaconFrequencyHz,
                isCurrentBank = currentBankId == FrequencyBankId.BEACON,
            ),
        )

        return FrequencyPresentation(
            currentFrequencyHz = settings.defaultFrequencyHz,
            currentBankId = currentBankId,
            banks = banks,
        )
    }

    private fun inferCurrentBank(settings: DeviceSettings): FrequencyBankId? {
        currentBankFromFoxRole(settings.foxRole)?.let { return it }

        if (settings.lowFrequencyHz != null && settings.defaultFrequencyHz == settings.lowFrequencyHz) {
            return FrequencyBankId.ONE
        }
        if (settings.mediumFrequencyHz != null && settings.defaultFrequencyHz == settings.mediumFrequencyHz) {
            return FrequencyBankId.TWO
        }
        if (settings.highFrequencyHz != null && settings.defaultFrequencyHz == settings.highFrequencyHz) {
            return FrequencyBankId.THREE
        }
        if (settings.beaconFrequencyHz != null && settings.defaultFrequencyHz == settings.beaconFrequencyHz) {
            return FrequencyBankId.BEACON
        }

        return null
    }

    fun currentBankFromFoxRole(foxRole: FoxRole?): FrequencyBankId? {
        return when (foxRole) {
            FoxRole.BEACON -> FrequencyBankId.BEACON
            FoxRole.CLASSIC_1,
            FoxRole.CLASSIC_2,
            FoxRole.CLASSIC_3,
            FoxRole.CLASSIC_4,
            FoxRole.CLASSIC_5,
            FoxRole.SPRINT_SLOW_1,
            FoxRole.SPRINT_SLOW_2,
            FoxRole.SPRINT_SLOW_3,
            FoxRole.SPRINT_SLOW_4,
            FoxRole.SPRINT_SLOW_5,
            FoxRole.FOXORING_1,
            -> FrequencyBankId.ONE
            FoxRole.SPRINT_SPECTATOR,
            FoxRole.FOXORING_2,
            -> FrequencyBankId.TWO
            FoxRole.SPRINT_FAST_1,
            FoxRole.SPRINT_FAST_2,
            FoxRole.SPRINT_FAST_3,
            FoxRole.SPRINT_FAST_4,
            FoxRole.SPRINT_FAST_5,
            FoxRole.FOXORING_3,
            -> FrequencyBankId.THREE
            FoxRole.FREQUENCY_TEST_BEACON,
            null,
            -> null
        }
    }
}
