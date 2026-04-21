package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.EventProfileSupport
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.model.JvmTimeSupport
import com.openardf.serialslinger.model.RelativeScheduleSelection
import com.openardf.serialslinger.model.RelativeScheduleSupport
import com.openardf.serialslinger.model.TimedEventFrequencyVisibility
import com.openardf.serialslinger.model.ClockPhaseSample as SharedClockPhaseSample
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToLong

object DesktopInputSupport {
    private val displayTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val timeSyncThresholdSeconds = 1L
    private const val syncLeadSeconds = 2L
    private val minimumValidTimestamp = LocalDateTime.of(2021, 1, 1, 0, 0, 0)
    private const val waitingForReadPlaceholder = "Waiting for read"
    private const val minimumSupportedFrequencyHz = 3_501_000L
    private const val maximumSupportedFrequencyHz = 3_700_000L

    data class ClockPhaseSample(
        val midpointAt: LocalDateTime,
        val reportedTimeCompact: String?,
    )

    data class ValidatedScheduleTimes(
        val startTimeCompact: String?,
        val finishTimeCompact: String?,
    )

    data class RelativeTimeSelection(
        val hours: Int,
        val minutes: Int,
        val useTopOfHour: Boolean = false,
    )

    private fun RelativeScheduleSelection.toDesktopSelection(): RelativeTimeSelection {
        return RelativeTimeSelection(
            hours = hours,
            minutes = minutes,
            useTopOfHour = useTopOfHour,
        )
    }

    private fun RelativeTimeSelection.toSharedSelection(): RelativeScheduleSelection {
        return RelativeScheduleSelection(
            hours = hours,
            minutes = minutes,
            useTopOfHour = useTopOfHour,
        )
    }

    fun selectableEventTypes(): List<EventType> {
        return EventProfileSupport.selectableEventTypes()
    }

    fun truncateToMinute(value: LocalDateTime): LocalDateTime {
        return JvmTimeSupport.truncateToMinute(value)
    }

    fun truncateToSecond(value: LocalDateTime): LocalDateTime {
        return JvmTimeSupport.truncateToSecond(value)
    }

    fun stepDateTimeByMinuteInterval(
        value: LocalDateTime,
        stepMinutes: Int,
        forward: Boolean,
    ): LocalDateTime {
        return JvmTimeSupport.stepDateTimeByMinuteInterval(value, stepMinutes, forward)
    }

    fun roundToSecond(value: LocalDateTime): LocalDateTime {
        return JvmTimeSupport.roundToSecond(value)
    }

    fun isManualEventStateSummary(eventStateSummary: String?): Boolean {
        return JvmTimeSupport.isManualEventStateSummary(eventStateSummary)
    }

    fun parseEventType(value: String): EventType {
        return requireNotNull(EventProfileSupport.parseEventTypeOrNull(value)) {
            "Unsupported eventType `$value`."
        }
    }

    fun parseBatteryMode(value: String): ExternalBatteryControlMode {
        return when (value.lowercase()) {
            "off", "disabled" -> ExternalBatteryControlMode.OFF
            "chargeandtransmit", "charge_and_transmit", "charge-transmit" -> {
                ExternalBatteryControlMode.CHARGE_AND_TRANSMIT
            }
            "enabled", "chargeandtransmitenabled" -> ExternalBatteryControlMode.CHARGE_AND_TRANSMIT
            "chargeonly", "charge_only", "charge-only", "enabledtxdisabled", "enabled_tx_disabled", "enabled-tx-disabled" -> ExternalBatteryControlMode.CHARGE_ONLY
            else -> error("Unsupported externalBatteryControlMode `$value`.")
        }
    }

    fun parseFoxRole(value: String, eventType: EventType): FoxRole {
        return requireNotNull(EventProfileSupport.parseFoxRoleOrNull(value, eventType)) {
            "Unsupported foxRole `$value` for $eventType."
        }
    }

    fun displayPatternText(
        eventType: EventType,
        foxRole: FoxRole?,
        storedPatternText: String?,
    ): String {
        return EventProfileSupport.displayPatternText(eventType, foxRole, storedPatternText)
    }

    fun patternSpeedBelongsToTimedEventSettings(eventType: EventType): Boolean {
        return EventProfileSupport.patternSpeedBelongsToTimedEventSettings(eventType)
    }

    fun patternTextIsEditable(eventType: EventType): Boolean {
        return EventProfileSupport.patternTextIsEditable(eventType)
    }

    fun timedEventFrequencyVisibility(eventType: EventType): TimedEventFrequencyVisibility {
        return EventProfileSupport.timedEventFrequencyVisibility(eventType)
    }

    fun parseFrequencyAssignment(value: String): Long {
        return requireNotNull(FrequencySupport.parseFrequencyHz(value)) {
            "Unsupported frequency value `$value`. Use bare values, Hz, kHz, or MHz."
        }
    }

    fun parseOptionalFrequencyAssignment(value: String): Long? {
        if (value.isBlank()) {
            return null
        }
        return parseFrequencyAssignment(value)
    }

    fun parseOptionalDouble(value: String): Double? {
        if (value.isBlank()) {
            return null
        }
        return value.toDouble()
    }

    fun parseOptionalCompactTimestamp(value: String): String? {
        return JvmTimeSupport.parseOptionalCompactTimestamp(value)
    }

    fun validateCurrentTimeForWrite(currentTimeCompact: String?): String? {
        return JvmTimeSupport.validateCurrentTimeForWrite(currentTimeCompact)
    }

    fun adjustManualTimeTargetForWrite(
        selectedTime: LocalDateTime,
        estimatedWriteDelayMillis: Long,
    ): LocalDateTime {
        return JvmTimeSupport.adjustManualTimeTargetForWrite(selectedTime, estimatedWriteDelayMillis)
    }

    fun validateStartTimeForWrite(startTimeCompact: String?): String? {
        return JvmTimeSupport.validateStartTimeForWrite(startTimeCompact)
    }

    fun validateFinishTimeForWrite(finishTimeCompact: String?): String? {
        return JvmTimeSupport.validateFinishTimeForWrite(finishTimeCompact)
    }

    fun resolveStartTimeForChange(
        startTimeCompact: String?,
        currentTimeCompact: String?,
    ): String? {
        return JvmTimeSupport.resolveStartTimeForChange(startTimeCompact, currentTimeCompact)
    }

    fun resolveScheduleForFinishTimeChange(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        currentTimeCompact: String?,
    ): ValidatedScheduleTimes {
        val shared = JvmTimeSupport.resolveScheduleForFinishTimeChange(
            startTimeCompact = startTimeCompact,
            finishTimeCompact = finishTimeCompact,
            currentTimeCompact = currentTimeCompact,
        )
        return ValidatedScheduleTimes(
            startTimeCompact = shared.startTimeCompact,
            finishTimeCompact = shared.finishTimeCompact,
        )
    }

    fun minimumStartTimeBoundary(
        currentTimeCompact: String,
        stepMinutes: Int = 5,
    ): LocalDateTime {
        return JvmTimeSupport.minimumStartTimeBoundary(currentTimeCompact, stepMinutes)
    }

    fun minimumFinishTimeBoundary(
        currentTimeCompact: String,
        startTimeCompact: String?,
    ): LocalDateTime {
        return JvmTimeSupport.minimumFinishTimeBoundary(currentTimeCompact, startTimeCompact)
    }

    fun normalizeCurrentTimeCompactForDisplay(value: String?): String? {
        return JvmTimeSupport.normalizeCurrentTimeCompactForDisplay(value)
    }

    fun formatCompactTimestamp(value: String?): String {
        return JvmTimeSupport.formatCompactTimestamp(value)
    }

    fun formatCompactTimestampOrNotSet(value: String?): String {
        return JvmTimeSupport.formatCompactTimestampOrNotSet(value)
    }

    fun deriveRelativeTimeSelection(
        baseCompact: String?,
        targetCompact: String?,
    ): RelativeTimeSelection {
        return RelativeScheduleSupport.deriveSelection(baseCompact, targetCompact).toDesktopSelection()
    }

    fun formatRelativeTimeSelection(selection: RelativeTimeSelection): String {
        return RelativeScheduleSupport.formatSelection(selection.toSharedSelection())
    }

    fun formatRelativeTimeCommand(selection: RelativeTimeSelection): String {
        return RelativeScheduleSupport.formatCommand(selection.toSharedSelection())
    }

    fun validateDefaultEventLengthMinutes(minutes: Int): Int {
        return RelativeScheduleSupport.validateDefaultEventLengthMinutes(minutes)
    }

    fun formatDefaultEventLength(minutes: Int): String {
        return RelativeScheduleSupport.formatDefaultEventLength(minutes)
    }

    fun finishTimeCompactFromStart(
        startTimeCompact: String,
        defaultEventLengthMinutes: Int,
    ): String {
        val start = parseCompactTimestamp(startTimeCompact)
        return formatCompactTimestamp(start.plusMinutes(validateDefaultEventLengthMinutes(defaultEventLengthMinutes).toLong()))
    }

    fun finishTimeCompactFromStart(
        startTimeCompact: String,
        duration: Duration,
    ): String {
        return JvmTimeSupport.finishTimeCompactFromStart(startTimeCompact, duration)
    }

    fun relativeTimeSelectionForDuration(minutes: Int): RelativeTimeSelection {
        return RelativeScheduleSupport.selectionForDuration(minutes).toDesktopSelection()
    }

    fun relativeTimeSelectionForDuration(duration: Duration): RelativeTimeSelection {
        return RelativeScheduleSupport.selectionForDuration(duration).toDesktopSelection()
    }

    fun validEventDuration(
        startTimeCompact: String?,
        finishTimeCompact: String?,
    ): Duration? {
        return JvmTimeSupport.validEventDuration(startTimeCompact, finishTimeCompact)
    }

    fun formatRelativeDurationCommand(duration: Duration): String {
        return JvmTimeSupport.formatRelativeDurationCommand(duration)
    }

    fun relativeTargetTimeCompact(
        baseCompact: String?,
        selection: RelativeTimeSelection,
    ): String? {
        return JvmTimeSupport.relativeTargetTimeCompact(
            baseCompact = baseCompact,
            hours = selection.hours,
            minutes = selection.minutes,
            useTopOfHour = selection.useTopOfHour,
        )
    }

    fun formatSystemTimestamp(systemNow: LocalDateTime = LocalDateTime.now()): String {
        return JvmTimeSupport.formatSystemTimestamp(systemNow)
    }

    fun formatVoltageOrWaiting(value: Double?): String {
        return value?.let { "$it V" } ?: waitingForReadPlaceholder
    }

    fun formatTemperatureOrWaiting(
        value: Double?,
        unit: TemperatureDisplayUnit = TemperatureDisplayUnit.CELSIUS,
    ): String {
        return value?.let {
            when (unit) {
                TemperatureDisplayUnit.CELSIUS -> "${"%.1f".format(it)} C"
                TemperatureDisplayUnit.FAHRENHEIT -> "${"%.1f".format((it * 9.0 / 5.0) + 32.0)} F"
            }
        } ?: waitingForReadPlaceholder
    }

    fun formatThresholdOrWaiting(value: Double?): String {
        return value?.let { "$it V" } ?: waitingForReadPlaceholder
    }

    fun formatCodeSpeedWpm(value: Int): String {
        return "$value WPM"
    }

    fun parseCodeSpeedWpm(value: String): Int {
        val normalized = value.trim().removeSuffix("WPM").trim().removeSuffix("wpm").trim()
        require(normalized.isNotEmpty()) {
            "Code speed must not be blank."
        }
        return normalized.toInt()
    }

    fun formatFrequencyForDisplay(
        frequencyHz: Long?,
        unit: FrequencyDisplayUnit = FrequencyDisplayUnit.MHZ,
    ): String {
        val value = frequencyHz ?: return ""
        return when (unit) {
            FrequencyDisplayUnit.KHZ -> "${value / 1_000} kHz"
            FrequencyDisplayUnit.MHZ -> "${"%.3f".format(value / 1_000_000.0)} MHz"
        }
    }

    fun defaultFrequencySpinnerValue(unit: FrequencyDisplayUnit): Number {
        return frequencySpinnerValue(minimumSupportedFrequencyHz, unit)
    }

    fun frequencySpinnerValue(
        frequencyHz: Long,
        unit: FrequencyDisplayUnit,
    ): Number {
        val clamped = frequencyHz.coerceIn(minimumSupportedFrequencyHz, maximumSupportedFrequencyHz)
        return when (unit) {
            FrequencyDisplayUnit.KHZ -> (clamped / 1_000L).toInt()
            FrequencyDisplayUnit.MHZ -> clamped / 1_000_000.0
        }
    }

    fun frequencyHzFromSpinnerValue(
        value: Number,
        unit: FrequencyDisplayUnit,
    ): Long {
        val frequencyHz = when (unit) {
            FrequencyDisplayUnit.KHZ -> value.toLong() * 1_000L
            FrequencyDisplayUnit.MHZ -> (value.toDouble() * 1_000_000.0).roundToLong()
        }
        require(frequencyHz in minimumSupportedFrequencyHz..maximumSupportedFrequencyHz) {
            "Frequency must be between 3501 kHz and 3700 kHz."
        }
        return frequencyHz
    }

    fun formatDurationCompact(duration: Duration): String {
        return JvmTimeSupport.formatDurationCompact(duration)
    }

    fun formatDurationHoursMinutesCompact(duration: Duration): String {
        return JvmTimeSupport.formatDurationHoursMinutesCompact(duration)
    }

    fun roundDurationMinutesToNearestFive(duration: Duration): Duration {
        return JvmTimeSupport.roundDurationMinutesToNearestFive(duration)
    }

    fun describeEventStatus(
        deviceReportedEventEnabled: Boolean?,
        eventStateSummary: String?,
        currentTimeCompact: String?,
        startTimeCompact: String?,
        finishTimeCompact: String?,
        startsInFallback: String?,
        daysToRun: Int? = null,
    ): String {
        return JvmTimeSupport.describeEventStatus(
            deviceReportedEventEnabled = deviceReportedEventEnabled,
            eventStateSummary = eventStateSummary,
            currentTimeCompact = currentTimeCompact,
            startTimeCompact = startTimeCompact,
            finishTimeCompact = finishTimeCompact,
            startsInFallback = startsInFallback,
            daysToRun = daysToRun,
        )
    }

    fun describeEventDuration(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        fallback: String?,
    ): String {
        return JvmTimeSupport.describeEventDuration(startTimeCompact, finishTimeCompact, fallback)
    }

    fun describeEventDurationHoursMinutes(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        fallback: String?,
    ): String {
        return JvmTimeSupport.describeEventDurationHoursMinutes(startTimeCompact, finishTimeCompact, fallback)
    }

    fun eventDurationDiffersFromDefault(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        defaultEventLengthMinutes: Int,
    ): Boolean {
        return JvmTimeSupport.eventDurationDiffersFromDefault(
            startTimeCompact = startTimeCompact,
            finishTimeCompact = finishTimeCompact,
            defaultEventLengthMinutes = defaultEventLengthMinutes,
        )
    }

    fun formatDaysToRunRemainingSummary(
        totalDaysToRun: Int?,
        daysToRunRemaining: Int?,
        currentTimeCompact: String?,
    ): String {
        return JvmTimeSupport.formatDaysToRunRemainingSummary(totalDaysToRun, daysToRunRemaining, currentTimeCompact)
    }

    fun currentSystemTimeCompact(systemNow: LocalDateTime = LocalDateTime.now()): String {
        return JvmTimeSupport.currentSystemTimeCompact(systemNow)
    }

    fun formatTruncatedCompactTimestamp(timestamp: LocalDateTime): String {
        return JvmTimeSupport.formatTruncatedCompactTimestamp(timestamp)
    }

    fun formatRoundedCompactTimestamp(timestamp: LocalDateTime): String {
        return JvmTimeSupport.formatRoundedCompactTimestamp(timestamp)
    }

    fun nextSyncTargetTime(
        systemNow: LocalDateTime = LocalDateTime.now(),
        minimumLeadMillis: Long = syncLeadSeconds * 1_000L,
    ): LocalDateTime {
        return JvmTimeSupport.nextSyncTargetTime(systemNow, minimumLeadMillis)
    }

    fun formatReportedVersion(
        softwareVersion: String?,
        hardwareBuild: String?,
    ): String {
        return if (softwareVersion.isNullOrBlank() || hardwareBuild.isNullOrBlank()) {
            "Not Available"
        } else {
            "SW Ver: ${softwareVersion.trim()} HW Build: ${hardwareBuild.trim()}"
        }
    }

    fun shouldEnableTimeSync(
        currentTimeCompact: String?,
        systemNow: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        return JvmTimeSupport.shouldEnableTimeSync(currentTimeCompact, systemNow)
    }

    fun isTimeSynchronizedToSystem(
        currentTimeCompact: String?,
        systemNow: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        return JvmTimeSupport.isTimeSynchronizedToSystem(currentTimeCompact, systemNow)
    }

    fun formatSignedDurationMillis(durationMillis: Long): String {
        return JvmTimeSupport.formatSignedDurationMillis(durationMillis)
    }

    fun medianMillis(values: List<Long>): Long {
        return JvmTimeSupport.medianMillis(values)
    }

    fun estimateClockPhaseErrorMillis(samples: List<ClockPhaseSample>): Long? {
        return JvmTimeSupport.estimateClockPhaseErrorMillis(
            samples.map { sample ->
                SharedClockPhaseSample(
                    midpointAt = sample.midpointAt,
                    reportedTimeCompact = sample.reportedTimeCompact,
                )
            },
        )
    }

    fun estimateCoarseClockErrorMillis(sample: ClockPhaseSample): Long? {
        return JvmTimeSupport.estimateCoarseClockErrorMillis(
            SharedClockPhaseSample(
                midpointAt = sample.midpointAt,
                reportedTimeCompact = sample.reportedTimeCompact,
            ),
        )
    }

    fun foxRoleOptions(eventType: EventType): List<FoxRole> {
        return EventProfileSupport.foxRoleOptions(eventType)
    }

    private fun normalizeTimestampInput(value: String): String {
        val trimmed = value.trim()
        if (trimmed.matches(Regex("""\d{12}"""))) {
            return trimmed
        }
        if (trimmed.matches(Regex("""\d{10}"""))) {
            return trimmed + "00"
        }

        parseIsoLikeTimestamp(trimmed)?.let { return formatCompactTimestamp(it) }
        parseFirmwareDisplayTimestamp(trimmed)?.let { return formatCompactTimestamp(it) }

        error("Unsupported date/time `$value`. Use YYYY-MM-DD HH:MM[:SS] or YYMMDDhhmmss.")
    }

    fun parseCompactTimestamp(value: String): LocalDateTime {
        return JvmTimeSupport.parseCompactTimestamp(value)
    }

    fun formatCompactTimestamp(timestamp: LocalDateTime): String {
        return JvmTimeSupport.formatCompactTimestamp(timestamp)
    }

    private fun parseIsoLikeTimestamp(value: String): LocalDateTime? {
        return when {
            value.matches(Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}""")) -> {
                LocalDateTime.parse(value.replace(Regex("""\s+"""), "T"))
            }
            value.matches(Regex("""\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}""")) -> {
                LocalDateTime.parse(value.replace(Regex("""\s+"""), "T") + ":00")
            }
            else -> null
        }
    }

    private fun requireValidTimestampForWrite(
        label: String,
        compactTimestamp: String?,
    ): LocalDateTime? {
        val parsed = compactTimestamp?.let(::parseCompactTimestamp) ?: return null
        require(!parsed.isBefore(minimumValidTimestamp)) {
            "$label must be on or after ${minimumValidTimestamp.format(displayTimestampFormatter)}."
        }
        return parsed
    }

    private fun parseFirmwareDisplayTimestamp(value: String): LocalDateTime? {
        val match = Regex("""^[A-Za-z]{3}\s+(\d{1,2})-([A-Za-z]{3})-(\d{4})\s+(\d{2}):(\d{2}):(\d{2})$""")
            .matchEntire(value) ?: return null
        val day = match.groupValues[1].toInt()
        val month = when (match.groupValues[2].lowercase()) {
            "jan" -> 1
            "feb" -> 2
            "mar" -> 3
            "apr" -> 4
            "may" -> 5
            "jun" -> 6
            "jul" -> 7
            "aug" -> 8
            "sep" -> 9
            "oct" -> 10
            "nov" -> 11
            "dec" -> 12
            else -> return null
        }
        val year = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        return LocalDateTime.of(year, month, day, hour, minute, second)
    }
}
