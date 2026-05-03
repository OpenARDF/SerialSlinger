package com.openardf.serialslinger.protocol

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EventType

data class FirmwareCloneStep(
    val command: String,
    val expectedReply: String,
    val checksumAfterStep: Long,
)

data class FirmwareClonePlan(
    val steps: List<FirmwareCloneStep>,
    val checksum: Long,
)

object FirmwareCloneProtocol {
    private const val firmwareEpochYearBase = 2000
    private val daysBeforeMonthCommon = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    fun buildPlan(
        templateSettings: DeviceSettings,
        currentTimeCompact: String,
    ): FirmwareClonePlan {
        val startEpoch = requireFirmwareEpoch("Start Time", templateSettings.startTimeCompact)
        val finishEpoch = requireFirmwareEpoch("Finish Time", templateSettings.finishTimeCompact)
        val currentEpoch = requireFirmwareEpoch("Device Time", currentTimeCompact)
        val daysToRun = templateSettings.daysToRun.coerceAtLeast(1)
        var checksum = 0L
        val steps = mutableListOf<FirmwareCloneStep>()

        fun addStep(
            command: String,
            expectedReply: String,
            checksumContribution: Long,
        ) {
            checksum = (checksum + checksumContribution) and 0xFFFF_FFFFL
            steps += FirmwareCloneStep(command, expectedReply, checksum)
        }

        addStep("FUN A", "FUN A", 'A'.code.toLong())
        addStep("CLK T $currentEpoch", "CLK T", currentEpoch)
        addStep("CLK S $startEpoch", "CLK S", startEpoch)
        addStep("CLK F $finishEpoch", "CLK F", finishEpoch)
        addStep("CLK D $daysToRun", "CLK D", daysToRun.toLong())

        val stationIdPayload = stationIdCommandPayload(templateSettings.stationId)
        addStep("ID $stationIdPayload", "ID", checksumForStationId(templateSettings.stationId))
        addStep("SPD I ${templateSettings.idCodeSpeedWpm}", "SPD I", templateSettings.idCodeSpeedWpm.toLong())

        val speedSlot = if (templateSettings.eventType == EventType.FOXORING) "F" else "P"
        addStep("SPD $speedSlot ${templateSettings.patternCodeSpeedWpm}", "SPD $speedSlot", templateSettings.patternCodeSpeedWpm.toLong())

        val eventToken = eventToken(templateSettings.eventType)
        addStep("EVT $eventToken", "EVT", eventToken.code.toLong())

        addStep("FRE X ${templateSettings.defaultFrequencyHz}", "FRE", templateSettings.defaultFrequencyHz)
        addStep("FRE L ${frequencyForClone(templateSettings.lowFrequencyHz, templateSettings.defaultFrequencyHz)}", "FRE", frequencyForClone(templateSettings.lowFrequencyHz, templateSettings.defaultFrequencyHz))
        addStep("FRE M ${frequencyForClone(templateSettings.mediumFrequencyHz, templateSettings.defaultFrequencyHz)}", "FRE", frequencyForClone(templateSettings.mediumFrequencyHz, templateSettings.defaultFrequencyHz))
        addStep("FRE H ${frequencyForClone(templateSettings.highFrequencyHz, templateSettings.defaultFrequencyHz)}", "FRE", frequencyForClone(templateSettings.highFrequencyHz, templateSettings.defaultFrequencyHz))
        addStep("FRE B ${frequencyForClone(templateSettings.beaconFrequencyHz, templateSettings.defaultFrequencyHz)}", "FRE", frequencyForClone(templateSettings.beaconFrequencyHz, templateSettings.defaultFrequencyHz))

        steps += FirmwareCloneStep("MAS Q $checksum", "MAS ACK", checksum)
        return FirmwareClonePlan(
            steps = steps,
            checksum = checksum,
        )
    }

    fun replyMatches(
        expectedReply: String,
        responseLines: List<String>,
    ): Boolean {
        return responseLines.any { line ->
            val normalized = line.trim().removePrefix(">").trim()
            normalized == expectedReply ||
                normalized.endsWith(expectedReply) ||
                truncatedReplyMatches(expectedReply, normalized) ||
                (expectedReply == "CLK T" && normalized.matches(Regex("""CLK T \d+"""))) ||
                (expectedReply == "CLK T" && normalized.contains(Regex("""CLK T \d+""")))
        }
    }

    fun firmwareEpochFromCompact(compactTimestamp: String): Long {
        val normalized = compactTimestamp.trim()
        require(normalized.length == 12 && normalized.all { it.isDigit() }) {
            "Timestamp must be YYMMDDhhmmss."
        }
        val year = firmwareEpochYearBase + normalized.substring(0, 2).toInt()
        val month = normalized.substring(2, 4).toInt()
        val day = normalized.substring(4, 6).toInt()
        val hour = normalized.substring(6, 8).toInt()
        val minute = normalized.substring(8, 10).toInt()
        val second = normalized.substring(10, 12).toInt()
        require(month in 1..12) { "Month must be 01 through 12." }
        require(day in 1..daysInMonth(year, month)) { "Day is not valid for month." }
        require(hour in 0..23) { "Hour must be 00 through 23." }
        require(minute in 0..59) { "Minute must be 00 through 59." }
        require(second in 0..59) { "Second must be 00 through 59." }

        val yearSince1900 = year - 1900
        val dayOfYear = daysBeforeMonthCommon[month - 1] + (if (month > 2 && isLeapYear(year)) 1 else 0) + (day - 1)
        return second.toLong() +
            minute.toLong() * 60L +
            hour.toLong() * 3_600L +
            dayOfYear.toLong() * 86_400L +
            (yearSince1900 - 70L) * 31_536_000L +
            ((yearSince1900 - 69L) / 4L) * 86_400L -
            ((yearSince1900 - 1L) / 100L) * 86_400L +
            ((yearSince1900 + 299L) / 400L) * 86_400L
    }

    private fun requireFirmwareEpoch(
        label: String,
        compactTimestamp: String?,
    ): Long {
        require(!compactTimestamp.isNullOrBlank()) {
            "$label must be set before firmware clone."
        }
        return firmwareEpochFromCompact(compactTimestamp)
    }

    private fun truncatedReplyMatches(
        expectedReply: String,
        actualReply: String,
    ): Boolean {
        if (expectedReply == "CLK F" && actualReply == "F") {
            return true
        }
        if (actualReply.length < 2 || actualReply.length >= expectedReply.length) {
            return false
        }
        return expectedReply.startsWith(actualReply)
    }

    private fun eventToken(eventType: EventType): Char {
        return when (eventType) {
            EventType.CLASSIC -> 'C'
            EventType.FOXORING -> 'F'
            EventType.SPRINT -> 'S'
            EventType.NONE -> 'N'
        }
    }

    private fun stationIdCommandPayload(stationId: String): String {
        return stationId.trim().takeIf { it.isNotEmpty() } ?: "\"\""
    }

    private fun checksumForStationId(stationId: String): Long {
        val normalized = stationId.trim()
        if (normalized.isEmpty()) {
            return 0L
        }
        return (" $normalized").sumOf { it.code.toLong() }
    }

    private fun frequencyForClone(
        frequencyHz: Long?,
        fallbackFrequencyHz: Long,
    ): Long {
        return frequencyHz ?: fallbackFrequencyHz
    }

    private fun daysInMonth(
        year: Int,
        month: Int,
    ): Int {
        return when (month) {
            2 -> if (isLeapYear(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }

    private fun isLeapYear(year: Int): Boolean {
        return year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    }
}
