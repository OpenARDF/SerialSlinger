package com.openardf.serialslinger.model

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

data class ClockPhaseSample(
    val midpointAt: LocalDateTime,
    val reportedTimeCompact: String?,
)

data class ValidatedScheduleTimes(
    val startTimeCompact: String?,
    val finishTimeCompact: String?,
)

object JvmTimeSupport {
    private val displayTimestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val textualDurationComponentPattern =
        Regex("""(\d+)\s+(day|days|hour|hours|minute|minutes|second|seconds)""", RegexOption.IGNORE_CASE)
    private const val timeSyncThresholdSeconds = 1L
    private const val syncLeadSeconds = 2L
    private val minimumValidTimestamp = LocalDateTime.of(2021, 1, 1, 0, 0, 0)

    fun truncateToMinute(value: LocalDateTime): LocalDateTime {
        return value.withSecond(0).withNano(0)
    }

    fun truncateToSecond(value: LocalDateTime): LocalDateTime {
        return value.withNano(0)
    }

    fun stepDateTimeByMinuteInterval(
        value: LocalDateTime,
        stepMinutes: Int,
        forward: Boolean,
    ): LocalDateTime {
        require(stepMinutes > 0) { "stepMinutes must be positive." }
        val truncated = truncateToMinute(value)
        if (stepMinutes == 1) {
            return truncated.plusMinutes(if (forward) 1 else -1)
        }

        val minute = truncated.minute
        val remainder = minute % stepMinutes
        val deltaMinutes = if (forward) {
            if (remainder == 0) stepMinutes else stepMinutes - remainder
        } else {
            if (remainder == 0) -stepMinutes else -remainder
        }
        return truncated.plusMinutes(deltaMinutes.toLong())
    }

    fun roundToSecond(value: LocalDateTime): LocalDateTime {
        return if (value.nano >= 500_000_000) {
            value.plusSeconds(1)
        } else {
            value
        }.withNano(0)
    }

    fun adjustManualTimeTargetForWrite(
        selectedTime: LocalDateTime,
        estimatedWriteDelayMillis: Long,
    ): LocalDateTime {
        val compensated = selectedTime.plus(Duration.ofMillis(estimatedWriteDelayMillis.coerceAtLeast(0L)))
        return roundToSecond(compensated)
    }

    fun isManualEventStateSummary(eventStateSummary: String?): Boolean {
        val summaryLower = eventStateSummary?.trim()?.lowercase().orEmpty()
        return summaryLower.contains("user launched") || summaryLower.contains("running forever")
    }

    fun validateCurrentTimeForWrite(currentTimeCompact: String?): String? {
        val current = requireValidTimestampForWrite("Device Time", currentTimeCompact) ?: return null
        return formatCompactTimestamp(current)
    }

    fun validateStartTimeForWrite(startTimeCompact: String?): String? {
        val start = requireValidTimestampForWrite("Start Time", startTimeCompact) ?: return null
        return formatCompactTimestamp(start)
    }

    fun validateFinishTimeForWrite(finishTimeCompact: String?): String? {
        val finish = requireValidTimestampForWrite("Finish Time", finishTimeCompact) ?: return null
        return formatCompactTimestamp(finish)
    }

    fun resolveStartTimeForChange(
        startTimeCompact: String?,
        currentTimeCompact: String?,
    ): String? {
        val current = requireValidTimestampForWrite("Device Time", currentTimeCompact)
            ?: error("Set Device Time first before changing Start Time.")
        val start = requireValidTimestampForWrite("Start Time", startTimeCompact) ?: return null
        require(!start.isBefore(current)) {
            "Start Time must not be earlier than Device Time."
        }
        return formatCompactTimestamp(start)
    }

    fun resolveScheduleForFinishTimeChange(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        currentTimeCompact: String?,
    ): ValidatedScheduleTimes {
        val start = normalizeStartTimeForFinishChange(startTimeCompact, currentTimeCompact)?.let(::parseCompactTimestamp)
        val finish = requireValidTimestampForWrite("Finish Time", finishTimeCompact)
            ?: return ValidatedScheduleTimes(
                startTimeCompact = start?.let(::formatCompactTimestamp),
                finishTimeCompact = null,
            )
        val current = requireValidTimestampForWrite("Device Time", currentTimeCompact)
            ?: error("Set Device Time first before changing Finish Time.")

        require(start == null || !finish.isBefore(start)) {
            "Finish Time must not be earlier than Start Time."
        }
        require(!finish.isBefore(current)) {
            "Finish Time must not be earlier than Device Time."
        }

        return ValidatedScheduleTimes(
            startTimeCompact = start?.let(::formatCompactTimestamp),
            finishTimeCompact = formatCompactTimestamp(finish),
        )
    }

    fun normalizeStartTimeForFinishChange(
        startTimeCompact: String?,
        currentTimeCompact: String?,
    ): String? {
        val start = requireValidTimestampForWrite("Start Time", startTimeCompact) ?: return null
        val current = requireValidTimestampForWrite("Device Time", currentTimeCompact)
            ?: error("Set Device Time first before changing Finish Time.")
        val minimumStart = minimumStartTimeBoundary(formatCompactTimestamp(current), stepMinutes = 5)
        return formatCompactTimestamp(if (start.isBefore(minimumStart)) minimumStart else start)
    }

    fun isFinishTimeEditable(
        startTimeCompact: String?,
        currentTimeCompact: String?,
    ): Boolean {
        val current = schedulingFieldsEditableCurrentTime(currentTimeCompact) ?: return false
        val start =
            runCatching {
                requireValidTimestampForWrite("Start Time", startTimeCompact)
            }.getOrNull() ?: return false
        return !start.isBefore(current)
    }

    fun areSchedulingFieldsEditable(
        currentTimeCompact: String?,
    ): Boolean {
        return schedulingFieldsEditableCurrentTime(currentTimeCompact) != null
    }

    private fun schedulingFieldsEditableCurrentTime(
        currentTimeCompact: String?,
    ): LocalDateTime? {
        return runCatching {
            requireValidTimestampForWrite("Device Time", currentTimeCompact)
        }.getOrNull()
    }

    fun minimumStartTimeBoundary(
        currentTimeCompact: String,
        stepMinutes: Int = 5,
    ): LocalDateTime {
        require(stepMinutes > 0) { "stepMinutes must be positive." }
        val current = parseCompactTimestamp(currentTimeCompact)
        var minimum = truncateToMinute(current)
        if (current.second > 0 || current.nano > 0) {
            minimum = minimum.plusMinutes(1)
        }
        val remainder = minimum.minute % stepMinutes
        if (remainder != 0) {
            minimum = minimum.plusMinutes((stepMinutes - remainder).toLong())
        }
        return minimum
    }

    fun minimumFinishTimeBoundary(
        currentTimeCompact: String,
        startTimeCompact: String?,
    ): LocalDateTime {
        val current = parseCompactTimestamp(currentTimeCompact)
        val start = startTimeCompact
            ?.let(::validateStartTimeForWrite)
            ?.let(::parseCompactTimestamp)
        return listOfNotNull(current, start).maxOrNull() ?: current
    }

    fun normalizeCurrentTimeCompactForDisplay(value: String?): String? {
        val timestamp = value?.let(::parseCompactTimestamp) ?: return null
        return if (timestamp.isBefore(minimumValidTimestamp)) {
            null
        } else {
            formatCompactTimestamp(timestamp)
        }
    }

    fun formatCompactTimestamp(value: String?): String {
        val timestamp = value?.let(::parseCompactTimestamp) ?: return ""
        return timestamp.format(displayTimestampFormatter)
    }

    fun formatCompactTimestampOrNotSet(value: String?): String {
        return normalizeCurrentTimeCompactForDisplay(value)?.let(::formatCompactTimestamp).orEmpty().ifBlank { "Not Set" }
    }

    fun formatSystemTimestamp(systemNow: LocalDateTime = LocalDateTime.now()): String {
        return systemNow.format(displayTimestampFormatter)
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

    fun formatDurationHoursMinutesCompact(duration: Duration): String {
        val totalMinutes = duration.toMinutes().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            "${hours}h ${minutes.toString().padStart(2, '0')}m"
        } else {
            "${minutes}m"
        }
    }

    fun roundDurationMinutesToNearestFive(duration: Duration): Duration {
        require(!duration.isNegative && !duration.isZero) {
            "Duration must be positive."
        }
        val totalMinutes = duration.toMinutes().coerceAtLeast(1)
        val roundedMinutes = (((totalMinutes + 2) / 5) * 5).coerceAtLeast(5)
        return Duration.ofMinutes(roundedMinutes)
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
        val normalizedSummary = eventStateSummary?.trim().orEmpty()
        val summaryLower = normalizedSummary.lowercase()
        val current = normalizeCurrentTimeCompactForDisplay(currentTimeCompact)?.let(::parseCompactTimestamp)
        val start = startTimeCompact?.let(::parseCompactTimestamp)
        val finish = finishTimeCompact?.let(::parseCompactTimestamp)

        if (current == null) {
            return "Device Time not set."
        }

        if (isManualEventStateSummary(normalizedSummary)) {
            return "Manually started event in progress"
        }

        if (start != null && finish != null) {
            if (start == finish) {
                return "Disabled"
            }
            if (current < start) {
                return "Starts in ${formatDurationCompact(Duration.between(current, start))}"
            }

            val totalDays = daysToRun?.coerceAtLeast(1) ?: 1
            if (totalDays > 1) {
                val overallFinish = finish.plusDays((totalDays - 1).toLong())
                if (!current.isBefore(overallFinish)) {
                    return "Completed $totalDays of $totalDays days"
                }

                val elapsedDays = max(0L, Duration.between(start, current).toDays())
                val activeWindowStart = start.plusDays(elapsedDays)
                val activeWindowFinish = finish.plusDays(elapsedDays)
                if (!current.isBefore(activeWindowStart) && current < activeWindowFinish) {
                    return "Running Day ${elapsedDays + 1} - Time remaining ${formatDurationCompact(Duration.between(current, activeWindowFinish))}"
                }

                return "Starts in ${formatDurationCompact(Duration.between(current, activeWindowStart.plusDays(1)))}"
            }

            if (current < finish) {
                return "Running - Time remaining ${formatDurationCompact(Duration.between(current, finish))}"
            }
            if (!current.isBefore(finish)) {
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

    fun describeEventDurationHoursMinutes(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        fallback: String?,
    ): String {
        val start = startTimeCompact?.let(::parseCompactTimestamp)
        val finish = finishTimeCompact?.let(::parseCompactTimestamp)
        if (start != null && finish != null && start == finish) {
            return ""
        }

        val validDuration = validEventDuration(startTimeCompact, finishTimeCompact)
        if (validDuration != null) {
            return formatDurationHoursMinutesCompact(validDuration)
        }

        val fallbackText = fallback.orEmpty().ifBlank { "Not Available" }
        if (fallbackText.equals("Disabled", ignoreCase = true)) {
            return ""
        }

        val parsedFallbackDuration = parseTextualDurationSummary(fallbackText)
        if (parsedFallbackDuration != null) {
            return if (parsedFallbackDuration.isZero) {
                ""
            } else {
                formatDurationHoursMinutesCompact(parsedFallbackDuration)
            }
        }

        return fallbackText
    }

    fun validEventDuration(
        startTimeCompact: String?,
        finishTimeCompact: String?,
    ): Duration? {
        val start = startTimeCompact?.let(::parseCompactTimestamp) ?: return null
        val finish = finishTimeCompact?.let(::parseCompactTimestamp) ?: return null
        if (!finish.isAfter(start)) {
            return null
        }
        return Duration.between(start, finish)
    }

    private fun parseTextualDurationSummary(summary: String?): Duration? {
        val trimmed = summary?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }

        var totalSeconds = 0L
        var matched = false
        textualDurationComponentPattern.findAll(trimmed).forEach { match ->
            val quantity = match.groupValues[1].toLongOrNull() ?: return@forEach
            val unit = match.groupValues[2].lowercase()
            matched = true
            totalSeconds += when (unit) {
                "day", "days" -> quantity * 86_400L
                "hour", "hours" -> quantity * 3_600L
                "minute", "minutes" -> quantity * 60L
                "second", "seconds" -> quantity
                else -> 0L
            }
        }

        return if (matched) Duration.ofSeconds(totalSeconds) else null
    }

    fun finishTimeCompactFromStart(
        startTimeCompact: String,
        duration: Duration,
    ): String {
        require(!duration.isNegative && !duration.isZero) {
            "Event duration must be positive."
        }
        val start = parseCompactTimestamp(startTimeCompact)
        return formatCompactTimestamp(start.plus(duration))
    }

    fun formatRelativeDurationCommand(duration: Duration): String {
        require(!duration.isNegative && !duration.isZero) {
            "Relative duration must be positive."
        }
        val totalSeconds = duration.seconds.coerceAtLeast(0)
        val totalMinutes = (totalSeconds / 60) + if (totalSeconds % 60 == 0L) 0 else 1
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0L) {
            "+$hours"
        } else {
            "+$hours:$minutes"
        }
    }

    fun relativeTargetTimeCompact(
        baseCompact: String?,
        hours: Int,
        minutes: Int,
        useTopOfHour: Boolean,
    ): String? {
        require(hours >= 0) { "Relative hours must not be negative." }
        require(minutes >= 0) { "Relative minutes must not be negative." }
        val base = normalizeCurrentTimeCompactForDisplay(baseCompact)?.let(::parseCompactTimestamp) ?: return null
        val target = truncateToMinute(base)
            .plusHours(hours.toLong())
            .plusMinutes(if (useTopOfHour) 0L else minutes.toLong())
        return formatCompactTimestamp(target)
    }

    fun eventDurationDiffersFromDefault(
        startTimeCompact: String?,
        finishTimeCompact: String?,
        defaultEventLengthMinutes: Int,
    ): Boolean {
        val start = startTimeCompact?.let(::parseCompactTimestamp) ?: return false
        val finish = finishTimeCompact?.let(::parseCompactTimestamp) ?: return false
        if (!finish.isAfter(start)) {
            return false
        }

        return Duration.between(start, finish).toMinutes() != defaultEventLengthMinutes.toLong()
    }

    fun formatDaysToRunRemainingSummary(
        totalDaysToRun: Int?,
        daysToRunRemaining: Int?,
        currentTimeCompact: String?,
    ): String {
        return formatDaysToRunRemainingSummary(
            totalDaysToRun = totalDaysToRun,
            daysToRunRemaining = daysToRunRemaining,
            currentTimeCompact = currentTimeCompact,
            startTimeCompact = null,
            finishTimeCompact = null,
        )
    }

    fun formatDaysToRunRemainingSummary(
        totalDaysToRun: Int?,
        daysToRunRemaining: Int?,
        currentTimeCompact: String?,
        startTimeCompact: String?,
        finishTimeCompact: String?,
    ): String {
        if (totalDaysToRun == null || totalDaysToRun <= 1) {
            return ""
        }
        if (normalizeCurrentTimeCompactForDisplay(currentTimeCompact) == null) {
            return "(? Remaining)"
        }
        val remainingDays =
            daysToRunRemaining ?: estimateDaysToRunRemaining(
                totalDaysToRun = totalDaysToRun,
                currentTimeCompact = currentTimeCompact,
                startTimeCompact = startTimeCompact,
                finishTimeCompact = finishTimeCompact,
            ) ?: return ""
        return "(${remainingDays.coerceAtLeast(0)} Remaining)"
    }

    fun estimateDaysToRunRemaining(
        totalDaysToRun: Int?,
        currentTimeCompact: String?,
        startTimeCompact: String?,
        finishTimeCompact: String?,
    ): Int? {
        val totalDays = totalDaysToRun?.coerceAtLeast(1) ?: return null
        if (totalDays <= 1) {
            return null
        }

        val current = normalizeCurrentTimeCompactForDisplay(currentTimeCompact)?.let(::parseCompactTimestamp) ?: return null
        val start = startTimeCompact?.let(::parseCompactTimestamp) ?: return null
        val finish = finishTimeCompact?.let(::parseCompactTimestamp) ?: return null

        if (!finish.isAfter(start)) {
            return null
        }

        if (current.isBefore(start)) {
            return totalDays
        }

        val overallFinish = finish.plusDays((totalDays - 1).toLong())
        if (!current.isBefore(overallFinish)) {
            return 0
        }

        val elapsedDays = max(0L, Duration.between(start, current).toDays())
        return (totalDays - elapsedDays.toInt() - 1).coerceIn(0, totalDays)
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

    fun shouldEnableTimeSync(
        currentTimeCompact: String?,
        systemNow: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val current = normalizeCurrentTimeCompactForDisplay(currentTimeCompact)?.let(::parseCompactTimestamp) ?: return true
        val differenceMillis = abs(Duration.between(current, systemNow).toMillis())
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
        val absMillis = abs(durationMillis)
        val totalSeconds = absMillis / 1_000
        val milliseconds = absMillis % 1_000
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60

        return buildString {
            append(sign)
            when {
                days > 0 -> {
                    append(days)
                    append("d ")
                    append(hours.toString().padStart(2, '0'))
                    append("h ")
                    append(minutes.toString().padStart(2, '0'))
                    append("m ")
                    append(seconds)
                    append('.')
                    append(milliseconds.toString().padStart(3, '0'))
                    append('s')
                }
                hours > 0 -> {
                    append(hours)
                    append("h ")
                    append(minutes.toString().padStart(2, '0'))
                    append("m ")
                    append(seconds)
                    append('.')
                    append(milliseconds.toString().padStart(3, '0'))
                    append('s')
                }
                minutes > 0 -> {
                    append(minutes)
                    append("m ")
                    append(seconds)
                    append('.')
                    append(milliseconds.toString().padStart(3, '0'))
                    append('s')
                }
                else -> {
                    append(seconds)
                    append('.')
                    append(milliseconds.toString().padStart(3, '0'))
                    append('s')
                }
            }
        }
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

    fun estimateCoarseClockErrorMillis(sample: ClockPhaseSample): Long? {
        val reportedTime = sample.reportedTimeCompact?.let(::parseCompactTimestamp) ?: return null
        return Duration.between(reportedTime, sample.midpointAt).toMillis() - 500L
    }

    fun parseOptionalCompactTimestamp(value: String): String? {
        if (value.isBlank() || value.equals("Not Set", ignoreCase = true)) {
            return null
        }
        return normalizeTimestampInput(value)
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
