package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.EventProfileSupport
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.JvmTimeSupport
import java.time.Duration
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
    fun adjustsManualTimeTargetForwardWhenMeasuredWriteDelayRoundsPastHalfSecond() {
        val selected = LocalDateTime.of(2026, 4, 10, 14, 0, 0)
        val adjusted = JvmTimeSupport.adjustManualTimeTargetForWrite(selected, estimatedWriteDelayMillis = 700L)

        assertEquals(LocalDateTime.of(2026, 4, 10, 14, 0, 1), adjusted)
    }

    @Test
    fun keepsManualTimeTargetUnchangedWhenMeasuredWriteDelayRoundsDown() {
        val selected = LocalDateTime.of(2026, 4, 10, 14, 0, 0)
        val adjusted = JvmTimeSupport.adjustManualTimeTargetForWrite(selected, estimatedWriteDelayMillis = 300L)

        assertEquals(selected, adjusted)
    }

    @Test
    fun relativeDurationSelectionRoundsUpPartialMinutes() {
        val selection = DesktopInputSupport.relativeTimeSelectionForDuration(Duration.ofSeconds((2 * 60 * 60 + 61).toLong()))

        assertEquals(2, selection.hours)
        assertEquals(2, selection.minutes)
        assertFalse(selection.useTopOfHour)
    }

    @Test
    fun relativeDurationCommandRoundsUpPartialMinutes() {
        assertEquals(
            "+2:2",
            DesktopInputSupport.formatRelativeDurationCommand(Duration.ofSeconds((2 * 60 * 60 + 61).toLong())),
        )
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

        assertEquals("Running - Time remaining 59m 59s", label)
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

        assertEquals("Running - Time remaining 1h 00m 00s", label)
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
    fun describesGapBetweenMultiDayWindowsAsStartsIn() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = null,
            currentTimeCompact = "260411090000",
            startTimeCompact = "260410120000",
            finishTimeCompact = "260410170000",
            startsInFallback = null,
            daysToRun = 3,
        )

        assertEquals("Starts in 3h 00m 00s", label)
    }

    @Test
    fun describesRunningMultiDayEventWithDayNumber() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = null,
            currentTimeCompact = "260411130000",
            startTimeCompact = "260410120000",
            finishTimeCompact = "260410170000",
            startsInFallback = null,
            daysToRun = 3,
        )

        assertEquals("Running Day 2 - Time remaining 4h 00m 00s", label)
    }

    @Test
    fun describesCompletedMultiDayEventWithProgressSummary() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = null,
            currentTimeCompact = "260413090000",
            startTimeCompact = "260410120000",
            finishTimeCompact = "260410170000",
            startsInFallback = null,
            daysToRun = 3,
        )

        assertEquals("Completed 3 of 3 days", label)
    }

    @Test
    fun formatsDaysToRunRemainingSummaryForMultiDayEvent() {
        val summary = DesktopInputSupport.formatDaysToRunRemainingSummary(
            totalDaysToRun = 3,
            daysToRunRemaining = 1,
            currentTimeCompact = "260411090000",
        )

        assertEquals("(1 Remaining)", summary)
    }

    @Test
    fun formatsUnknownDaysToRunRemainingSummaryWhenClockIsInvalid() {
        val summary = DesktopInputSupport.formatDaysToRunRemainingSummary(
            totalDaysToRun = 3,
            daysToRunRemaining = 1,
            currentTimeCompact = null,
        )

        assertEquals("(? Remaining)", summary)
    }

    @Test
    fun estimatesDaysToRunRemainingBeforeFirstDayStarts() {
        val summary =
            JvmTimeSupport.formatDaysToRunRemainingSummary(
                totalDaysToRun = 3,
                daysToRunRemaining = null,
                currentTimeCompact = "260410110000",
                startTimeCompact = "260410120000",
                finishTimeCompact = "260410170000",
            )

        assertEquals("(3 Remaining)", summary)
    }

    @Test
    fun estimatesDaysToRunRemainingDuringSecondDay() {
        val summary =
            JvmTimeSupport.formatDaysToRunRemainingSummary(
                totalDaysToRun = 3,
                daysToRunRemaining = null,
                currentTimeCompact = "260411130000",
                startTimeCompact = "260410120000",
                finishTimeCompact = "260410170000",
            )

        assertEquals("(1 Remaining)", summary)
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
    fun derivesRelativeTimeSelectionForWholeHoursUsingToth() {
        val selection = DesktopInputSupport.deriveRelativeTimeSelection(
            baseCompact = "260410140000",
            targetCompact = "260410170000",
        )

        assertEquals(
            DesktopInputSupport.RelativeTimeSelection(hours = 3, minutes = 0, useTopOfHour = true),
            selection,
        )
    }

    @Test
    fun derivesRelativeTimeSelectionRoundedToNearestFiveMinutes() {
        val selection = DesktopInputSupport.deriveRelativeTimeSelection(
            baseCompact = "260410140000",
            targetCompact = "260410153200",
        )

        assertEquals(DesktopInputSupport.RelativeTimeSelection(hours = 1, minutes = 30), selection)
    }

    @Test
    fun formatsRelativeTimeSelectionForDisplayAndCommand() {
        val selection = DesktopInputSupport.RelativeTimeSelection(hours = 2, minutes = 15, useTopOfHour = false)

        assertEquals("+2:15", DesktopInputSupport.formatRelativeTimeSelection(selection))
        assertEquals("+2:15", DesktopInputSupport.formatRelativeTimeCommand(selection))
    }

    @Test
    fun formatsRelativeTimeSelectionWithTothDisplayAndWholeHourCommand() {
        val selection = DesktopInputSupport.RelativeTimeSelection(hours = 4, minutes = 0, useTopOfHour = true)

        assertEquals("+4 TOTH", DesktopInputSupport.formatRelativeTimeSelection(selection))
        assertEquals("+4", DesktopInputSupport.formatRelativeTimeCommand(selection))
    }

    @Test
    fun formatsExactWholeHourSelectionDifferentlyFromToth() {
        val selection = DesktopInputSupport.RelativeTimeSelection(hours = 6, minutes = 0, useTopOfHour = false)

        assertEquals("+6:0", DesktopInputSupport.formatRelativeTimeSelection(selection))
        assertEquals("+6:0", DesktopInputSupport.formatRelativeTimeCommand(selection))
    }

    @Test
    fun formatsDefaultEventLengthForDisplay() {
        assertEquals("6h 00m", DesktopInputSupport.formatDefaultEventLength(6 * 60))
        assertEquals("45m", DesktopInputSupport.formatDefaultEventLength(45))
    }

    @Test
    fun calculatesFinishTimeFromStartAndDefaultEventLength() {
        assertEquals(
            "260410220000",
            DesktopInputSupport.finishTimeCompactFromStart(
                startTimeCompact = "260410160000",
                defaultEventLengthMinutes = 6 * 60,
            ),
        )
    }

    @Test
    fun derivesRelativeSelectionForDefaultEventLength() {
        assertEquals(
            DesktopInputSupport.RelativeTimeSelection(hours = 6, minutes = 0, useTopOfHour = false),
            DesktopInputSupport.relativeTimeSelectionForDuration(6 * 60),
        )
        assertEquals(
            DesktopInputSupport.RelativeTimeSelection(hours = 1, minutes = 30, useTopOfHour = false),
            DesktopInputSupport.relativeTimeSelectionForDuration(90),
        )
    }

    @Test
    fun rejectsDefaultEventLengthOutsideSupportedRange() {
        assertFailsWith<IllegalArgumentException> {
            DesktopInputSupport.validateDefaultEventLengthMinutes(5)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopInputSupport.validateDefaultEventLengthMinutes((24 * 60) + 5)
        }
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
    fun estimatesCoarseClockErrorFromSingleSample() {
        val phaseError = DesktopInputSupport.estimateCoarseClockErrorMillis(
            DesktopInputSupport.ClockPhaseSample(
                midpointAt = LocalDateTime.of(2026, 4, 11, 12, 0, 5, 700_000_000),
                reportedTimeCompact = "260411120005",
            ),
        )

        assertEquals(200L, phaseError)
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
    fun rejectsStartTimeEarlierThanDeviceTime() {
        assertFailsWith<IllegalArgumentException> {
            DesktopInputSupport.resolveStartTimeForChange(
                startTimeCompact = "260411135959",
                currentTimeCompact = "260411140000",
            )
        }
    }

    @Test
    fun requiresDeviceTimeBeforeChangingFinishTime() {
        assertFailsWith<IllegalStateException> {
            DesktopInputSupport.resolveScheduleForFinishTimeChange(
                startTimeCompact = "260411140000",
                finishTimeCompact = "260411150000",
                currentTimeCompact = null,
            )
        }
    }

    @Test
    fun formatsPreMinimumCurrentTimeAsNotSet() {
        assertEquals("Not Set", DesktopInputSupport.formatCompactTimestampOrNotSet("000101000414"))
    }

    @Test
    fun formatsValidCurrentTimeForDisplay() {
        assertEquals("2026-04-14 20:35:10", DesktopInputSupport.formatCompactTimestampOrNotSet("260414203510"))
    }

    @Test
    fun convertsKhzSpinnerValueToFrequencyHz() {
        val frequencyHz = DesktopInputSupport.frequencyHzFromSpinnerValue(3501, FrequencyDisplayUnit.KHZ)

        assertEquals(3_501_000L, frequencyHz)
    }

    @Test
    fun convertsMhzSpinnerValueToFrequencyHz() {
        val frequencyHz = DesktopInputSupport.frequencyHzFromSpinnerValue(3.700, FrequencyDisplayUnit.MHZ)

        assertEquals(3_700_000L, frequencyHz)
    }

    @Test
    fun convertsFrequencyHzToMhzSpinnerValue() {
        val displayValue = DesktopInputSupport.frequencySpinnerValue(3_654_000L, FrequencyDisplayUnit.MHZ)

        assertEquals(3.654, displayValue)
    }

    @Test
    fun ignoresPreMinimumCurrentTimeWhenDescribingEventStatus() {
        val label = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = true,
            eventStateSummary = null,
            currentTimeCompact = "000101000414",
            startTimeCompact = "260410141530",
            finishTimeCompact = "260410160000",
            startsInFallback = "15 minutes 30 seconds",
        )

        assertEquals("Starts in 15 minutes 30 seconds", label)
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
    fun stepsStartTimeForwardOnFiveMinuteBoundaries() {
        val stepped = DesktopInputSupport.stepDateTimeByMinuteInterval(
            LocalDateTime.of(2026, 4, 11, 13, 12, 42),
            stepMinutes = 5,
            forward = true,
        )

        assertEquals(LocalDateTime.of(2026, 4, 11, 13, 15, 0), stepped)
    }

    @Test
    fun stepsStartTimeBackwardOnFiveMinuteBoundaries() {
        val stepped = DesktopInputSupport.stepDateTimeByMinuteInterval(
            LocalDateTime.of(2026, 4, 11, 13, 12, 42),
            stepMinutes = 5,
            forward = false,
        )

        assertEquals(LocalDateTime.of(2026, 4, 11, 13, 10, 0), stepped)
    }

    @Test
    fun stepsFinishTimeBySingleMinutes() {
        val stepped = DesktopInputSupport.stepDateTimeByMinuteInterval(
            LocalDateTime.of(2026, 4, 11, 13, 12, 42),
            stepMinutes = 1,
            forward = true,
        )

        assertEquals(LocalDateTime.of(2026, 4, 11, 13, 13, 0), stepped)
    }

    @Test
    fun alignsMinimumStartTimeToNextFiveMinuteBoundary() {
        val minimum = DesktopInputSupport.minimumStartTimeBoundary("260411131242")

        assertEquals(LocalDateTime.of(2026, 4, 11, 13, 15, 0), minimum)
    }

    @Test
    fun usesLaterOfDeviceTimeAndStartTimeForMinimumFinishTime() {
        val minimum = DesktopInputSupport.minimumFinishTimeBoundary(
            currentTimeCompact = "260411131242",
            startTimeCompact = "260411140000",
        )

        assertEquals(LocalDateTime.of(2026, 4, 11, 14, 0, 0), minimum)
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

    @Test
    fun desktopEventProfileHelpersStayAlignedWithSharedSupport() {
        EventType.entries
            .filter { it != EventType.NONE }
            .forEach { eventType ->
                assertEquals(
                    EventProfileSupport.patternTextIsEditable(eventType),
                    DesktopInputSupport.patternTextIsEditable(eventType),
                )
                assertEquals(
                    EventProfileSupport.patternSpeedBelongsToTimedEventSettings(eventType),
                    DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(eventType),
                )
                assertEquals(
                    EventProfileSupport.timedEventFrequencyVisibility(eventType),
                    DesktopInputSupport.timedEventFrequencyVisibility(eventType),
                )
            }
    }

    @Test
    fun desktopPatternDisplayStaysAlignedWithSharedSupport() {
        val cases =
            listOf(
                Triple(EventType.CLASSIC, FoxRole.CLASSIC_2, "MOH"),
                Triple(EventType.FOXORING, FoxRole.FOXORING_2, "MOS"),
                Triple(EventType.SPRINT, FoxRole.SPRINT_SLOW_2, "MOI"),
            )

        cases.forEach { (eventType, foxRole, storedPatternText) ->
            assertEquals(
                EventProfileSupport.displayPatternText(eventType, foxRole, storedPatternText),
                DesktopInputSupport.displayPatternText(eventType, foxRole, storedPatternText),
            )
        }
    }

    @Test
    fun desktopScheduleResolutionStaysAlignedWithSharedSupport() {
        val desktopStart =
            DesktopInputSupport.resolveStartTimeForChange(
                startTimeCompact = "260411140000",
                currentTimeCompact = "260411131500",
            )
        val sharedStart =
            JvmTimeSupport.resolveStartTimeForChange(
                startTimeCompact = "260411140000",
                currentTimeCompact = "260411131500",
            )
        assertEquals(sharedStart, desktopStart)

        val desktopSchedule =
            DesktopInputSupport.resolveScheduleForFinishTimeChange(
                startTimeCompact = "260411140000",
                finishTimeCompact = "260411170000",
                currentTimeCompact = "260411131500",
            )
        val sharedSchedule =
            JvmTimeSupport.resolveScheduleForFinishTimeChange(
                startTimeCompact = "260411140000",
                finishTimeCompact = "260411170000",
                currentTimeCompact = "260411131500",
            )

        assertEquals(sharedSchedule.startTimeCompact, desktopSchedule.startTimeCompact)
        assertEquals(sharedSchedule.finishTimeCompact, desktopSchedule.finishTimeCompact)
        assertEquals(
            JvmTimeSupport.minimumStartTimeBoundary("260411131242"),
            DesktopInputSupport.minimumStartTimeBoundary("260411131242"),
        )
        assertEquals(
            JvmTimeSupport.minimumFinishTimeBoundary(
                currentTimeCompact = "260411131242",
                startTimeCompact = "260411140000",
            ),
            DesktopInputSupport.minimumFinishTimeBoundary(
                currentTimeCompact = "260411131242",
                startTimeCompact = "260411140000",
            ),
        )
    }

    @Test
    fun desktopMultiDayStatusFormattingStaysAlignedWithSharedSupport() {
        val desktopStatus =
            DesktopInputSupport.describeEventStatus(
                deviceReportedEventEnabled = true,
                eventStateSummary = null,
                currentTimeCompact = "260411130000",
                startTimeCompact = "260410120000",
                finishTimeCompact = "260410170000",
                startsInFallback = null,
                daysToRun = 3,
            )
        val sharedStatus =
            JvmTimeSupport.describeEventStatus(
                deviceReportedEventEnabled = true,
                eventStateSummary = null,
                currentTimeCompact = "260411130000",
                startTimeCompact = "260410120000",
                finishTimeCompact = "260410170000",
                startsInFallback = null,
                daysToRun = 3,
            )
        assertEquals(sharedStatus, desktopStatus)

        val desktopDuration =
            DesktopInputSupport.describeEventDuration(
                startTimeCompact = "260410120000",
                finishTimeCompact = "260410170000",
                fallback = "5 hours",
            )
        val sharedDuration =
            JvmTimeSupport.describeEventDuration(
                startTimeCompact = "260410120000",
                finishTimeCompact = "260410170000",
                fallback = "5 hours",
            )
        assertEquals(sharedDuration, desktopDuration)

        assertEquals(
            JvmTimeSupport.formatDaysToRunRemainingSummary(
                totalDaysToRun = 3,
                daysToRunRemaining = 1,
                currentTimeCompact = "260411090000",
            ),
            DesktopInputSupport.formatDaysToRunRemainingSummary(
                totalDaysToRun = 3,
                daysToRunRemaining = 1,
                currentTimeCompact = "260411090000",
            ),
        )
    }
}
