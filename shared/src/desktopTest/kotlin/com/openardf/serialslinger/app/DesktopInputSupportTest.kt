package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.FoxRole
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class DesktopInputSupportTest {
    @Test
    fun describesEventStatusForScheduledFutureEvent() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = null,
            currentTimeCompact = "260410140000",
            startTimeCompact = "260410141530",
            finishTimeCompact = "260410160000",
            startsInFallback = "15 minutes 30 seconds",
        )

        assertEquals("Starts in 15m 30s", label)
    }

    @Test
    fun describesEventStatusUsesLiveCountdownInsteadOfStaticFallback() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = null,
            currentTimeCompact = "260410140001",
            startTimeCompact = "260410141530",
            finishTimeCompact = "260410160000",
            startsInFallback = "15 minutes 30 seconds",
        )

        assertEquals("Starts in 15m 29s", label)
    }

    @Test
    fun describesEventStatusUsesLiveRemainingTimeInsteadOfStaticSummary() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = "Time remaining: 1h 00m 00s",
            currentTimeCompact = "260410150001",
            startTimeCompact = "260410141530",
            finishTimeCompact = "260410160000",
            startsInFallback = null,
        )

        assertEquals("Time remaining: 59m 59s", label)
    }

    @Test
    fun describesFutureEventAsStartsInEvenIfDeviceSummarySaysTimeRemaining() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = "Time remaining: 1d 23h 59m 59s",
            currentTimeCompact = "260410140000",
            startTimeCompact = "260410142015",
            finishTimeCompact = "260411140000",
            startsInFallback = null,
        )

        assertEquals("Starts in 20m 15s", label)
    }

    @Test
    fun describesEventStatusForInProgressEvent() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = "In progress",
            currentTimeCompact = "260410150000",
            startTimeCompact = "260410141530",
            finishTimeCompact = "260410160000",
            startsInFallback = "In Progress",
        )

        assertEquals("Time remaining: 1h 00m 00s", label)
    }

    @Test
    fun describesEventStatusForManualLaunch() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = "User launched.",
            currentTimeCompact = "260410150000",
            startTimeCompact = null,
            finishTimeCompact = null,
            startsInFallback = null,
        )

        assertEquals("Manually started event in progress", label)
    }

    @Test
    fun describesCompletedEventAsCompleted() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = null,
            currentTimeCompact = "260410170001",
            startTimeCompact = "260410150000",
            finishTimeCompact = "260410170000",
            startsInFallback = null,
        )

        assertEquals("Completed", label)
    }

    @Test
    fun describesDurationFromStartAndFinishTimes() {
        val label = DesktopInputSupport.describeEventDuration(
            startTimeCompact = "260410150000",
            finishTimeCompact = "260410170000",
            fallback = "2 hours 0 minutes 0 seconds",
        )

        assertEquals("2h 00m 00s", label)
    }

    @Test
    fun roundsSubsecondTimesWithoutFallingBackOneSecond() {
        val compact = DesktopInputSupport.formatRoundedCompactTimestamp(
            LocalDateTime.of(2026, 4, 11, 12, 0, 5, 750_000_000),
        )

        assertEquals("260411120006", compact)
    }

    @Test
    fun truncatesSubsecondTimesForDisplayAlignment() {
        val compact = DesktopInputSupport.formatTruncatedCompactTimestamp(
            LocalDateTime.of(2026, 4, 11, 12, 0, 5, 750_000_000),
        )

        assertEquals("260411120005", compact)
    }

    @Test
    fun doesNotEnableSyncForSubsecondDifferenceUnderThreshold() {
        val shouldSync = DesktopInputSupport.shouldEnableTimeSync(
            currentTimeCompact = "260411120005",
            systemNow = LocalDateTime.of(2026, 4, 11, 12, 0, 5, 750_000_000),
        )

        assertFalse(shouldSync)
    }

    @Test
    fun estimatesClockPhaseFromObservedSecondRollover() {
        val phaseError = DesktopInputSupport.estimateClockPhaseErrorMillis(
            listOf(
                DesktopInputSupport.ClockPhaseSample(
                    midpointAt = LocalDateTime.of(2026, 4, 11, 12, 0, 5, 800_000_000),
                    reportedTimeCompact = "260411120005",
                ),
                DesktopInputSupport.ClockPhaseSample(
                    midpointAt = LocalDateTime.of(2026, 4, 11, 12, 0, 6, 200_000_000),
                    reportedTimeCompact = "260411120006",
                ),
            ),
        )

        assertEquals(0L, phaseError)
    }

    @Test
    fun picksNextWholeSecondAfterRequestedLeadTime() {
        val target = DesktopInputSupport.nextSyncTargetTime(
            systemNow = LocalDateTime.of(2026, 4, 11, 12, 0, 5, 600_000_000),
            minimumLeadMillis = 450L,
        )

        assertEquals(LocalDateTime.of(2026, 4, 11, 12, 0, 7), target)
    }

    @Test
    fun roundsManualSetTargetsToNearestSecond() {
        val rounded = DesktopInputSupport.roundToSecond(
            LocalDateTime.of(2026, 4, 11, 12, 0, 6, 700_000_000),
        )

        assertEquals(LocalDateTime.of(2026, 4, 11, 12, 0, 7), rounded)
    }

    @Test
    fun acceptsStartTimeAtOrAfterFirmwareMinimumEpoch() {
        val validated = DesktopInputSupport.validateStartTimeForWrite("210101000000")

        assertEquals("210101000000", validated)
    }

    @Test
    fun rejectsStartTimeEarlierThanFirmwareMinimumEpoch() {
        assertFailsWith<IllegalArgumentException> {
            DesktopInputSupport.validateStartTimeForWrite("201231235959")
        }
    }

    @Test
    fun rejectsFinishTimeEarlierThanStartTime() {
        assertFailsWith<IllegalArgumentException> {
            DesktopInputSupport.resolveScheduleForFinishTimeChange(
                startTimeCompact = "260411130000",
                finishTimeCompact = "260411125959",
                currentTimeCompact = "260411120000",
            )
        }
    }

    @Test
    fun rejectsFinishTimeEarlierThanCurrentTime() {
        assertFailsWith<IllegalArgumentException> {
            DesktopInputSupport.resolveScheduleForFinishTimeChange(
                startTimeCompact = "260411130000",
                finishTimeCompact = "260411135959",
                currentTimeCompact = "260411140000",
            )
        }
    }

    @Test
    fun acceptsCurrentTimeAtOrAfterFirmwareMinimumEpoch() {
        val validated = DesktopInputSupport.validateCurrentTimeForWrite("210101000000")

        assertEquals("210101000000", validated)
    }

    @Test
    fun displaysClassicPatternTextFromFoxRoleInsteadOfStoredPatValue() {
        val text = DesktopInputSupport.displayPatternText(
            eventType = EventType.CLASSIC,
            foxRole = FoxRole.CLASSIC_2,
            storedPatternText = "MOH",
        )

        assertEquals("MOI", text)
    }

    @Test
    fun displaysFoxoringPatternTextFromStoredPatValue() {
        val text = DesktopInputSupport.displayPatternText(
            eventType = EventType.FOXORING,
            foxRole = FoxRole.FOXORING_2,
            storedPatternText = "MOH",
        )

        assertEquals("MOH", text)
    }

    @Test
    fun patternTextIsEditableOnlyForFoxoring() {
        assertEquals(false, DesktopInputSupport.patternTextIsEditable(EventType.CLASSIC))
        assertEquals(true, DesktopInputSupport.patternTextIsEditable(EventType.FOXORING))
        assertEquals(false, DesktopInputSupport.patternTextIsEditable(EventType.SPRINT))
    }

    @Test
    fun treatsPatternSpeedAsTimedEventSettingOnlyOutsideFoxoring() {
        assertEquals(true, DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(EventType.CLASSIC))
        assertEquals(false, DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(EventType.FOXORING))
        assertEquals(true, DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(EventType.SPRINT))
    }

    @Test
    fun truncatesScheduleEditorTimesToMinutePrecision() {
        val truncated = DesktopInputSupport.truncateToMinute(
            LocalDateTime.of(2026, 4, 11, 13, 5, 42, 900_000_000),
        )

        assertEquals(LocalDateTime.of(2026, 4, 11, 13, 5, 0, 0), truncated)
    }

    @Test
    fun formatsUnsetBatteryAndTemperatureFieldsAsWaitingForRead() {
        assertEquals("Waiting for read", DesktopInputSupport.formatThresholdOrWaiting(null))
        assertEquals("Waiting for read", DesktopInputSupport.formatVoltageOrWaiting(null))
        assertEquals("Waiting for read", DesktopInputSupport.formatTemperatureOrWaiting(null))
    }

    @Test
    fun formatsThresholdWithVoltageUnits() {
        assertEquals("3.8 V", DesktopInputSupport.formatThresholdOrWaiting(3.8))
    }

    @Test
    fun formatsCodeSpeedWithWpmUnits() {
        assertEquals("20 WPM", DesktopInputSupport.formatCodeSpeedWpm(20))
    }

    @Test
    fun parsesCodeSpeedWithOrWithoutWpmSuffix() {
        assertEquals(8, DesktopInputSupport.parseCodeSpeedWpm("8"))
        assertEquals(8, DesktopInputSupport.parseCodeSpeedWpm("8 WPM"))
        assertEquals(8, DesktopInputSupport.parseCodeSpeedWpm("8 wpm"))
    }

    @Test
    fun formatsTemperatureInRequestedUnit() {
        assertEquals("20.0 C", DesktopInputSupport.formatTemperatureOrWaiting(20.0, TemperatureDisplayUnit.CELSIUS))
        assertEquals("68.0 F", DesktopInputSupport.formatTemperatureOrWaiting(20.0, TemperatureDisplayUnit.FAHRENHEIT))
    }

    @Test
    fun formatsFrequencyInRequestedUnit() {
        assertEquals("3520 kHz", DesktopInputSupport.formatFrequencyForDisplay(3_520_000L, FrequencyDisplayUnit.KHZ))
        assertEquals("3.520 MHz", DesktopInputSupport.formatFrequencyForDisplay(3_520_000L, FrequencyDisplayUnit.MHZ))
    }

    @Test
    fun hidesClassicTimedEventFrequencyRowsThatDoNotApply() {
        val visibility = DesktopInputSupport.timedEventFrequencyVisibility(EventType.CLASSIC)

        assertEquals(true, visibility.showFrequency1)
        assertEquals(false, visibility.showFrequency2)
        assertEquals(false, visibility.showFrequency3)
        assertEquals(true, visibility.showFrequencyB)
    }

    @Test
    fun suppressesPlaceholderNoneEventTypeFromUiOptions() {
        assertEquals(
            listOf(EventType.CLASSIC, EventType.FOXORING, EventType.SPRINT),
            DesktopInputSupport.selectableEventTypes(),
        )
    }
}
