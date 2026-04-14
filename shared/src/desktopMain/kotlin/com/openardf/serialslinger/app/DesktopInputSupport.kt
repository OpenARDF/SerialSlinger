package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.FrequencySupport
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DesktopInputSupport {
    private val displayTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private const val timeSyncThresholdSeconds = 1L
    private const val syncLeadSeconds = 2L
    private val minimumValidTimestamp = LocalDateTime.of(2021, 1, 1, 0, 0, 0)
    private const val waitingForReadPlaceholder = "Waiting for read"

    data class ClockPhaseSample(
        val midpointAt: LocalDateTime,
        val reportedTimeCompact: String?,
    )

    data class ValidatedScheduleTimes(
        val startTimeCompact: String?,
        val finishTimeCompact: String?,
    )

    data class TimedEventFrequencyVisibility(
        val showFrequency1: Boolean,
        val showFrequency2: Boolean,
        val showFrequency3: Boolean,
        val showFrequencyB: Boolean,
    )

    fun selectableEventTypes(): List<EventType> {
        return EventType.entries.filterNot { it == EventType.NONE }
    }

    fun truncateToMinute(value: LocalDateTime): LocalDateTime {
        return value.withSecond(0).withNano(0)
    }

    fun truncateToSecond(value: LocalDateTime): LocalDateTime {
        return value.withNano(0)
    }

    fun roundToSecond(value: LocalDateTime): LocalDateTime {
        return if (value.nano >= 500_000_000) {
            value.plusSeconds(1)
        } else {
            value
        }.withNano(0)
    }

    fun isManualEventStateSummary(eventStateSummary: String?): Boolean {
        val summaryLower = eventStateSummary?.trim()?.lowercase().orEmpty()
        return summaryLower.contains("user launched") || summaryLower.contains("running forever")
    }

    fun parseEventType(value: String): EventType {
        return when (value.lowercase()) {
            "none" -> EventType.NONE
            "classic" -> EventType.CLASSIC
            "foxoring" -> EventType.FOXORING
            "sprint" -> EventType.SPRINT
            else -> error("Unsupported eventType `$value`.")
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
        val normalized = value.trim().lowercase()
        return when (eventType) {
            EventType.CLASSIC -> when (normalized) {
                "b", "beacon" -> FoxRole.BEACON
                "1" -> FoxRole.CLASSIC_1
                "2" -> FoxRole.CLASSIC_2
                "3" -> FoxRole.CLASSIC_3
                "4" -> FoxRole.CLASSIC_4
                "5" -> FoxRole.CLASSIC_5
                else -> error("Unsupported foxRole `$value` for CLASSIC.")
            }
            EventType.FOXORING -> when (normalized) {
                "1", "low", "lowfreq" -> FoxRole.FOXORING_1
                "2", "medium", "med", "mediumfreq" -> FoxRole.FOXORING_2
                "3", "high", "highfreq" -> FoxRole.FOXORING_3
                "f", "t", "test", "frequencytest", "frequency_test_beacon" -> FoxRole.FREQUENCY_TEST_BEACON
                "b", "beacon" -> FoxRole.BEACON
                else -> error("Unsupported foxRole `$value` for FOXORING.")
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
                else -> error("Unsupported foxRole `$value` for SPRINT.")
            }
            EventType.NONE -> when (normalized) {
                "b", "beacon" -> FoxRole.BEACON
                else -> error("Unsupported foxRole `$value` when no event is selected.")
            }
        }
    }

    fun displayPatternText(
        eventType: EventType,
        foxRole: FoxRole?,
        storedPatternText: String?,
    ): String {
        return when {
            foxRole?.fixedPatternText != null -> foxRole.fixedPatternText
            eventType == EventType.FOXORING -> storedPatternText.orEmpty()
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
        if (value.isBlank() || value.equals("Not Set", ignoreCase = true)) {
            return null
        }
        return normalizeTimestampInput(value)
    }

    fun validateCurrentTimeForWrite(currentTimeCompact: String?): String? {
        val current = requireValidTimestampForWrite("Current Time", currentTimeCompact) ?: return null
        return formatCompactTimestamp(current)
    }

    fun validateStartTimeForWrite(startTimeCompact: String?): String? {
        val start = requireValidTimestampForWrite("Start Time", startTimeCompact) ?: return null
        return formatCompactTimestamp(start)
    }

    fun resolveScheduleForFinishTimeChange(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        currentTimeCompact: String?,
    ): ValidatedScheduleTimes {
        val start = requireValidTimestampForWrite("Start Time", startTimeCompact)
        val finish = requireValidTimestampForWrite("Finish Time", finishTimeCompact)
            ?: return ValidatedScheduleTimes(
                startTimeCompact = start?.let(::formatCompactTimestamp),
                finishTimeCompact = null,
            )
        val current = currentTimeCompact?.let(::parseCompactTimestamp)

        require(start == null || !finish.isBefore(start)) {
            "Finish Time must not be earlier than Start Time."
        }
        require(current == null || !finish.isBefore(current)) {
            "Finish Time must not be earlier than Current Time."
        }

        return ValidatedScheduleTimes(
            startTimeCompact = start?.let(::formatCompactTimestamp),
            finishTimeCompact = formatCompactTimestamp(finish),
        )
    }

    fun formatCompactTimestamp(value: String?): String {
        val timestamp = value?.let(::parseCompactTimestamp) ?: return ""
        return timestamp.format(displayTimestampFormatter)
    }

    fun formatCompactTimestampOrNotSet(value: String?): String {
        return value?.let(::formatCompactTimestamp).orEmpty().ifBlank { "Not Set" }
    }

    fun formatSystemTimestamp(systemNow: LocalDateTime = LocalDateTime.now()): String {
        return systemNow.format(displayTimestampFormatter)
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

    fun formatDurationCompact(duration: Duration): String {
        val totalSeconds = duration.seconds.coerceAtLeast(0)
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60

        return buildString {
            if (days > 0) {
                append(days)
                append("d ")
            }
            if (days > 0 || hours > 0) {
                append(hours.toString().padStart(if (days > 0) 2 else 1, '0'))
                append("h ")
            }
            if (days > 0 || hours > 0 || minutes > 0) {
                append(minutes.toString().padStart(if (days > 0 || hours > 0) 2 else 1, '0'))
                append("m ")
            }
            append(seconds.toString().padStart(if (days > 0 || hours > 0 || minutes > 0) 2 else 1, '0'))
            append("s")
        }
    }

    fun describeEventStatus(
        deviceReportedEventEnabled: Boolean?,
        eventStateSummary: String?,
        currentTimeCompact: String?,
        startTimeCompact: String?,
        finishTimeCompact: String?,
        startsInFallback: String?,
    ): String {
        val normalizedSummary = eventStateSummary?.trim().orEmpty()
        val summaryLower = normalizedSummary.lowercase()
        val current = currentTimeCompact?.let(::parseCompactTimestamp)
        val start = startTimeCompact?.let(::parseCompactTimestamp)
        val finish = finishTimeCompact?.let(::parseCompactTimestamp)

        if (isManualEventStateSummary(normalizedSummary)) {
            return "Manually started event in progress"
        }

        if (current != null && start != null) {
            if (start == finish) {
                return "Disabled"
            }
            if (current < start) {
                return "Starts in ${formatDurationCompact(Duration.between(current, start))}"
            }
            if (finish != null && current < finish) {
                return "Time remaining: ${formatDurationCompact(Duration.between(current, finish))}"
            }
            if (finish != null && !current.isBefore(finish)) {
                return "Completed"
            }
        }

        if (summaryLower.startsWith("time remaining:")) {
            return normalizedSummary
        }
        if (summaryLower == "in progress" || summaryLower.contains("on the air")) {
            return "Event in progress"
        }
        if (deviceReportedEventEnabled == false) {
            return "Disabled"
        }

        if (current == null) {
            return startsInFallback?.takeIf { it.isNotBlank() }?.let { "Starts in $it" } ?: "Not Available"
        }
        if (start == null) {
            return "Disabled"
        }

        return if (start == finish) "Disabled" else "Not Available"
    }

    fun describeEventDuration(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        fallback: String?,
    ): String {
        val start = startTimeCompact?.let(::parseCompactTimestamp)
        val finish = finishTimeCompact?.let(::parseCompactTimestamp)

        if (start != null && finish != null) {
            if (start == finish) {
                return "Disabled"
            }
            if (!finish.isBefore(start)) {
                return formatDurationCompact(Duration.between(start, finish))
            }
        }

        return fallback.orEmpty().ifBlank { "Not Available" }
    }

    fun currentSystemTimeCompact(systemNow: LocalDateTime = LocalDateTime.now()): String {
        return formatCompactTimestamp(systemNow)
    }

    fun formatTruncatedCompactTimestamp(timestamp: LocalDateTime): String {
        return formatCompactTimestamp(timestamp.withNano(0))
    }

    fun formatRoundedCompactTimestamp(timestamp: LocalDateTime): String {
        val rounded = roundToSecond(timestamp)
        return formatCompactTimestamp(rounded)
    }

    fun nextSyncTargetTime(
        systemNow: LocalDateTime = LocalDateTime.now(),
        minimumLeadMillis: Long = syncLeadSeconds * 1_000L,
    ): LocalDateTime {
        val candidate = systemNow.plusNanos(minimumLeadMillis * 1_000_000L)
        return candidate.withNano(0).plusSeconds(1)
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

    fun eventEnabledLabel(
        deviceReportedEventEnabled: Boolean?,
        currentTimeCompact: String?,
        startTimeCompact: String?,
        finishTimeCompact: String?,
        systemNow: LocalDateTime = LocalDateTime.now(),
    ): String {
        if (deviceReportedEventEnabled != null) {
            return if (deviceReportedEventEnabled) "Enabled" else "Disabled"
        }

        val current = currentTimeCompact?.let(::parseCompactTimestamp) ?: return "Disabled"
        val start = startTimeCompact?.let(::parseCompactTimestamp) ?: return "Disabled"
        val finish = finishTimeCompact?.let(::parseCompactTimestamp) ?: return "Disabled"

        if (start == finish) {
            return "Disabled"
        }
        if (Duration.between(current, systemNow).toMinutes() > 30) {
            return "Disabled"
        }

        return "Enabled"
    }

    fun shouldEnableTimeSync(
        currentTimeCompact: String?,
        systemNow: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val current = currentTimeCompact?.let(::parseCompactTimestamp) ?: return true
        val differenceMillis = kotlin.math.abs(Duration.between(current, systemNow).toMillis())
        return differenceMillis > (timeSyncThresholdSeconds * 1_000L)
    }

    fun isTimeSynchronizedToSystem(
        currentTimeCompact: String?,
        systemNow: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        return !shouldEnableTimeSync(currentTimeCompact, systemNow)
    }

    fun formatSignedDurationMillis(durationMillis: Long): String {
        val sign = if (durationMillis < 0) "-" else "+"
        val absMillis = kotlin.math.abs(durationMillis)
        val seconds = absMillis / 1_000
        val milliseconds = absMillis % 1_000
        return "$sign${seconds}.${milliseconds.toString().padStart(3, '0')}s"
    }

    fun medianMillis(values: List<Long>): Long {
        require(values.isNotEmpty()) {
            "Cannot compute a median from an empty list."
        }
        val sorted = values.sorted()
        val midpoint = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[midpoint]
        } else {
            (sorted[midpoint - 1] + sorted[midpoint]) / 2
        }
    }

    fun estimateClockPhaseErrorMillis(samples: List<ClockPhaseSample>): Long? {
        val parsed = samples.mapNotNull { sample ->
            val reportedTime = sample.reportedTimeCompact?.let(::parseCompactTimestamp) ?: return@mapNotNull null
            sample.midpointAt to reportedTime
        }

        for (index in 1 until parsed.size) {
            val previous = parsed[index - 1]
            val current = parsed[index]
            if (current.second == previous.second) {
                continue
            }

            val transitionEstimate = previous.first.plus(
                Duration.between(previous.first, current.first).dividedBy(2),
            )
            return Duration.between(current.second, transitionEstimate).toMillis()
        }

        return null
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
        require(value.matches(Regex("""\d{12}"""))) {
            "Unsupported compact timestamp `$value`."
        }

        val year = 2000 + value.substring(0, 2).toInt()
        val month = value.substring(2, 4).toInt()
        val day = value.substring(4, 6).toInt()
        val hour = value.substring(6, 8).toInt()
        val minute = value.substring(8, 10).toInt()
        val second = value.substring(10, 12).toInt()
        return LocalDateTime.of(year, month, day, hour, minute, second)
    }

    fun formatCompactTimestamp(timestamp: LocalDateTime): String {
        return "%02d%02d%02d%02d%02d%02d".format(
            timestamp.year % 100,
            timestamp.monthValue,
            timestamp.dayOfMonth,
            timestamp.hour,
            timestamp.minute,
            timestamp.second,
        )
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
