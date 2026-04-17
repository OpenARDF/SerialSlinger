package com.openardf.serialslinger.model

data class TimedEventFrequencyVisibility(
    val showFrequency1: Boolean,
    val showFrequency2: Boolean,
    val showFrequency3: Boolean,
    val showFrequencyB: Boolean,
)

data class EventProfileWorkflowState(
    val loadedEventType: EventType,
    val loadedFoxRole: FoxRole?,
    val selectedEventType: EventType,
    val selectedFoxRole: FoxRole?,
    val availableFoxRoleOptions: List<FoxRole>,
    val roleSelectionEnabled: Boolean,
    val hasPendingEventTypeChange: Boolean,
)

object EventProfileSupport {
    fun selectableEventTypes(): List<EventType> {
        return EventType.entries.filterNot { it == EventType.NONE }
    }

    fun parseEventTypeOrNull(raw: String): EventType? {
        return when (raw.trim().lowercase()) {
            "none" -> EventType.NONE
            "classic" -> EventType.CLASSIC
            "foxoring" -> EventType.FOXORING
            "sprint" -> EventType.SPRINT
            else -> null
        }
    }

    fun foxRoleOptions(eventType: EventType): List<FoxRole> {
        return when (eventType) {
            EventType.CLASSIC -> listOf(
                FoxRole.BEACON,
                FoxRole.CLASSIC_1,
                FoxRole.CLASSIC_2,
                FoxRole.CLASSIC_3,
                FoxRole.CLASSIC_4,
                FoxRole.CLASSIC_5,
            )
            EventType.FOXORING -> listOf(
                FoxRole.BEACON,
                FoxRole.FOXORING_1,
                FoxRole.FOXORING_2,
                FoxRole.FOXORING_3,
                FoxRole.FREQUENCY_TEST_BEACON,
            )
            EventType.SPRINT -> listOf(
                FoxRole.BEACON,
                FoxRole.SPRINT_SPECTATOR,
                FoxRole.SPRINT_SLOW_1,
                FoxRole.SPRINT_SLOW_2,
                FoxRole.SPRINT_SLOW_3,
                FoxRole.SPRINT_SLOW_4,
                FoxRole.SPRINT_SLOW_5,
                FoxRole.SPRINT_FAST_1,
                FoxRole.SPRINT_FAST_2,
                FoxRole.SPRINT_FAST_3,
                FoxRole.SPRINT_FAST_4,
                FoxRole.SPRINT_FAST_5,
            )
            EventType.NONE -> listOf(FoxRole.BEACON)
        }
    }

    fun parseFoxRoleOrNull(raw: String, eventType: EventType): FoxRole? {
        val normalized = raw.trim().lowercase()
        if (normalized.isEmpty()) {
            return null
        }

        return foxRoleOptions(eventType).firstOrNull { role ->
            normalized == role.uiLabel.lowercase() ||
                normalized == role.commandToken.lowercase() ||
                normalized == role.label.lowercase() ||
                normalized == role.label.substringBefore(' ').lowercase() ||
                normalized == role.uiLabel.removePrefix("FREQ ").lowercase() ||
                normalized == role.uiLabel.removePrefix("FOX ").lowercase()
        } ?: when (eventType) {
            EventType.FOXORING -> when (normalized) {
                "low", "lowfreq" -> FoxRole.FOXORING_1
                "medium", "med", "mediumfreq" -> FoxRole.FOXORING_2
                "high", "highfreq" -> FoxRole.FOXORING_3
                "f", "t", "test", "frequencytest", "frequency_test_beacon" -> FoxRole.FREQUENCY_TEST_BEACON
                "b", "beacon" -> FoxRole.BEACON
                else -> null
            }
            EventType.CLASSIC -> when (normalized) {
                "b", "beacon" -> FoxRole.BEACON
                else -> null
            }
            EventType.SPRINT -> when (normalized) {
                "s", "spectator" -> FoxRole.SPRINT_SPECTATOR
                "1", "s1", "slow1" -> FoxRole.SPRINT_SLOW_1
                "2", "s2", "slow2" -> FoxRole.SPRINT_SLOW_2
                "3", "s3", "slow3" -> FoxRole.SPRINT_SLOW_3
                "4", "s4", "slow4" -> FoxRole.SPRINT_SLOW_4
                "5", "s5", "slow5" -> FoxRole.SPRINT_SLOW_5
                "1f", "f1", "fast1" -> FoxRole.SPRINT_FAST_1
                "2f", "f2", "fast2" -> FoxRole.SPRINT_FAST_2
                "3f", "f3", "fast3" -> FoxRole.SPRINT_FAST_3
                "4f", "f4", "fast4" -> FoxRole.SPRINT_FAST_4
                "5f", "f5", "fast5" -> FoxRole.SPRINT_FAST_5
                "b", "beacon" -> FoxRole.BEACON
                else -> null
            }
            EventType.NONE -> when (normalized) {
                "b", "beacon" -> FoxRole.BEACON
                else -> null
            }
        }
    }

    fun displayPatternText(
        eventType: EventType,
        foxRole: FoxRole?,
        storedPatternText: String?,
    ): String {
        return foxRole?.fixedPatternText ?: when (eventType) {
            EventType.FOXORING -> storedPatternText.orEmpty()
            else -> storedPatternText.orEmpty()
        }
    }

    fun patternSpeedBelongsToTimedEventSettings(eventType: EventType): Boolean {
        return eventType != EventType.FOXORING
    }

    fun patternTextIsEditable(eventType: EventType): Boolean {
        return eventType == EventType.FOXORING
    }

    fun timedEventFrequencyVisibility(eventType: EventType): TimedEventFrequencyVisibility {
        return when (eventType) {
            EventType.CLASSIC -> TimedEventFrequencyVisibility(
                showFrequency1 = true,
                showFrequency2 = false,
                showFrequency3 = false,
                showFrequencyB = true,
            )
            EventType.FOXORING,
            EventType.SPRINT,
            EventType.NONE,
            -> TimedEventFrequencyVisibility(
                showFrequency1 = true,
                showFrequency2 = true,
                showFrequency3 = true,
                showFrequencyB = true,
            )
        }
    }

    fun resolveWorkflowState(
        loadedEventType: EventType,
        loadedFoxRole: FoxRole?,
        selectedEventType: EventType,
        requestedFoxRole: FoxRole?,
    ): EventProfileWorkflowState {
        val roleSelectionEnabled = selectedEventType == loadedEventType
        val availableFoxRoleOptions = foxRoleOptions(if (roleSelectionEnabled) selectedEventType else loadedEventType)
        val selectedFoxRole =
            if (roleSelectionEnabled) {
                requestedFoxRole?.takeIf { it in availableFoxRoleOptions } ?: loadedFoxRole?.takeIf { it in availableFoxRoleOptions }
                    ?: availableFoxRoleOptions.firstOrNull()
            } else {
                loadedFoxRole?.takeIf { it in availableFoxRoleOptions } ?: availableFoxRoleOptions.firstOrNull()
            }

        return EventProfileWorkflowState(
            loadedEventType = loadedEventType,
            loadedFoxRole = loadedFoxRole,
            selectedEventType = selectedEventType,
            selectedFoxRole = selectedFoxRole,
            availableFoxRoleOptions = availableFoxRoleOptions,
            roleSelectionEnabled = roleSelectionEnabled,
            hasPendingEventTypeChange = selectedEventType != loadedEventType,
        )
    }
}
