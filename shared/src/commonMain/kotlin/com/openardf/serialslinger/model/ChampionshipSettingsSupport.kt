package com.openardf.serialslinger.model

private const val CHAMPIONSHIP_ID_SPEED_WPM = 20
private const val CHAMPIONSHIP_PATTERN_SPEED_WPM = 8
private const val CHAMPIONSHIP_MIN_FREQUENCY_SPACING_HZ = 20_000L

data class ChampionshipFrequencySlot(
    val commandSlot: String,
    val frequencyHz: Long?,
)

data class ChampionshipSettingsCommandPlan(
    val commands: List<String>,
    val shouldWarnAboutFrequencies: Boolean,
    val sortRequired: Boolean,
)

object ChampionshipSettingsSupport {
    fun canOfferChampionshipSettings(settings: DeviceSettings?): Boolean {
        val loadedSettings = settings ?: return false
        return !alreadyAtChampionshipSettings(loadedSettings)
    }

    fun shouldWarnAboutFrequencies(settings: DeviceSettings): Boolean {
        val frequencies = frequencySlots(settings).map { it.frequencyHz }
        if (frequencies.any { it == null }) {
            return true
        }

        val sorted = frequencies.filterNotNull().sorted()
        return sorted.zipWithNext().any { (lower, higher) ->
            higher - lower < CHAMPIONSHIP_MIN_FREQUENCY_SPACING_HZ
        }
    }

    fun frequencySortRequired(settings: DeviceSettings): Boolean {
        val frequencies = frequencySlots(settings).mapNotNull { it.frequencyHz }
        return frequencies.zipWithNext().any { (lower, higher) -> lower > higher }
    }

    fun buildCommandPlan(
        settings: DeviceSettings,
        sortFrequencies: Boolean,
    ): ChampionshipSettingsCommandPlan {
        val commands = buildList {
            add("SPD I $CHAMPIONSHIP_ID_SPEED_WPM")
            add("EVT C")
            add("SPD P $CHAMPIONSHIP_PATTERN_SPEED_WPM")
            if (sortFrequencies) {
                addAll(sortedFrequencyCommands(settings))
            }
            add("EVT ${eventToken(settings.eventType)}")
        }
        return ChampionshipSettingsCommandPlan(
            commands = commands,
            shouldWarnAboutFrequencies = shouldWarnAboutFrequencies(settings),
            sortRequired = frequencySortRequired(settings),
        )
    }

    private fun alreadyAtChampionshipSettings(settings: DeviceSettings): Boolean {
        return settings.idCodeSpeedWpm == CHAMPIONSHIP_ID_SPEED_WPM &&
            settings.eventType != EventType.FOXORING &&
            settings.patternCodeSpeedWpm == CHAMPIONSHIP_PATTERN_SPEED_WPM &&
            !frequencySortRequired(settings)
    }

    private fun sortedFrequencyCommands(settings: DeviceSettings): List<String> {
        val sortedFrequencies = frequencySlots(settings)
            .mapNotNull { it.frequencyHz }
            .sorted()
        return frequencySlots(settings)
            .take(sortedFrequencies.size)
            .zip(sortedFrequencies)
            .map { (slot, frequencyHz) -> "FRE ${slot.commandSlot} $frequencyHz" }
    }

    private fun frequencySlots(settings: DeviceSettings): List<ChampionshipFrequencySlot> {
        return listOf(
            ChampionshipFrequencySlot("1", settings.lowFrequencyHz),
            ChampionshipFrequencySlot("2", settings.mediumFrequencyHz),
            ChampionshipFrequencySlot("3", settings.highFrequencyHz),
            ChampionshipFrequencySlot("B", settings.beaconFrequencyHz),
        )
    }

    private fun eventToken(eventType: EventType): String {
        return when (eventType) {
            EventType.CLASSIC -> "C"
            EventType.FOXORING -> "F"
            EventType.SPRINT -> "S"
            EventType.NONE -> "N"
        }
    }
}
