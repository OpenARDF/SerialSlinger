package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventProfileSupportTest {
    @Test
    fun selectableEventTypesExcludeNone() {
        assertEquals(
            listOf(EventType.CLASSIC, EventType.FOXORING, EventType.SPRINT),
            EventProfileSupport.selectableEventTypes(),
        )
    }

    @Test
    fun displayPatternTextUsesFixedFoxRolePatternWhenAvailable() {
        assertEquals(
            "MOS",
            EventProfileSupport.displayPatternText(
                eventType = EventType.CLASSIC,
                foxRole = FoxRole.CLASSIC_3,
                storedPatternText = "MO5",
            ),
        )
    }

    @Test
    fun patternTextEditingIsLimitedToFoxoring() {
        assertTrue(EventProfileSupport.patternTextIsEditable(EventType.FOXORING))
        assertFalse(EventProfileSupport.patternTextIsEditable(EventType.CLASSIC))
        assertFalse(EventProfileSupport.patternTextIsEditable(EventType.SPRINT))
    }

    @Test
    fun patternSpeedBelongsToTimedSettingsOutsideFoxoring() {
        assertFalse(EventProfileSupport.patternSpeedBelongsToTimedEventSettings(EventType.FOXORING))
        assertTrue(EventProfileSupport.patternSpeedBelongsToTimedEventSettings(EventType.CLASSIC))
        assertTrue(EventProfileSupport.patternSpeedBelongsToTimedEventSettings(EventType.SPRINT))
    }

    @Test
    fun classicFrequencyVisibilityHidesUnusedBanks() {
        assertEquals(
            TimedEventFrequencyVisibility(
                showFrequency1 = true,
                showFrequency2 = false,
                showFrequency3 = false,
                showFrequencyB = true,
            ),
            EventProfileSupport.timedEventFrequencyVisibility(EventType.CLASSIC),
        )
    }

    @Test
    fun parseEventTypeOrNullAcceptsSupportedValues() {
        assertEquals(EventType.CLASSIC, EventProfileSupport.parseEventTypeOrNull("classic"))
        assertEquals(EventType.FOXORING, EventProfileSupport.parseEventTypeOrNull("FOXORING"))
        assertNull(EventProfileSupport.parseEventTypeOrNull("unknown"))
    }

    @Test
    fun parseFoxRoleOrNullAcceptsUiLabelsAndAliases() {
        assertEquals(FoxRole.FOXORING_2, EventProfileSupport.parseFoxRoleOrNull("FREQ 2", EventType.FOXORING))
        assertEquals(FoxRole.FOXORING_2, EventProfileSupport.parseFoxRoleOrNull("medium", EventType.FOXORING))
        assertEquals(FoxRole.CLASSIC_3, EventProfileSupport.parseFoxRoleOrNull("FOX 3", EventType.CLASSIC))
        assertEquals(FoxRole.SPRINT_FAST_1, EventProfileSupport.parseFoxRoleOrNull("fast1", EventType.SPRINT))
        assertNull(EventProfileSupport.parseFoxRoleOrNull("", EventType.CLASSIC))
        assertNull(EventProfileSupport.parseFoxRoleOrNull("not-a-role", EventType.CLASSIC))
    }

    @Test
    fun workflowStateDisablesRoleSelectionUntilEventChangeIsApplied() {
        val workflow =
            EventProfileSupport.resolveWorkflowState(
                loadedEventType = EventType.CLASSIC,
                loadedFoxRole = FoxRole.CLASSIC_2,
                selectedEventType = EventType.FOXORING,
                requestedFoxRole = FoxRole.FOXORING_3,
            )

        assertFalse(workflow.roleSelectionEnabled)
        assertTrue(workflow.hasPendingEventTypeChange)
        assertEquals(EventType.FOXORING, workflow.selectedEventType)
        assertEquals(FoxRole.CLASSIC_2, workflow.selectedFoxRole)
        assertEquals(EventProfileSupport.foxRoleOptions(EventType.CLASSIC), workflow.availableFoxRoleOptions)
    }

    @Test
    fun workflowStateUsesRequestedRoleWhenSelectedEventMatchesLoadedEvent() {
        val workflow =
            EventProfileSupport.resolveWorkflowState(
                loadedEventType = EventType.FOXORING,
                loadedFoxRole = FoxRole.FOXORING_2,
                selectedEventType = EventType.FOXORING,
                requestedFoxRole = FoxRole.FOXORING_3,
            )

        assertTrue(workflow.roleSelectionEnabled)
        assertFalse(workflow.hasPendingEventTypeChange)
        assertEquals(FoxRole.FOXORING_3, workflow.selectedFoxRole)
        assertEquals(EventProfileSupport.foxRoleOptions(EventType.FOXORING), workflow.availableFoxRoleOptions)
    }
}
