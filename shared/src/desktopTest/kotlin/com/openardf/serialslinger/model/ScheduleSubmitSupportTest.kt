package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleSubmitSupportTest {
    @Test
    fun `relative start commands include optional days to run`() {
        assertEquals(
            listOf("CLK S +2", "CLK F +6:0", "CLK D 3"),
            ScheduleSubmitSupport.relativeStartCommands(
                offsetCommand = "+2",
                finishOffsetCommand = "+6:0",
                preservedDaysToRun = 3,
            ),
        )
    }

    @Test
    fun `relative finish commands omit days to run when not preserving`() {
        assertEquals(
            listOf("CLK F +4"),
            ScheduleSubmitSupport.relativeFinishCommands(offsetCommand = "+4"),
        )
    }

    @Test
    fun `absolute start edit can derive finish from default duration and preserve days`() {
        val request = ScheduleSubmitSupport.absoluteStartEdit(
            currentSettings = sampleSettings().copy(currentTimeCompact = "260420100000"),
            normalizedStartTime = "260420120000",
            defaultEventLengthMinutes = 360,
            preserveDaysToRun = true,
        )

        assertEquals("260420120000", request.startTimeCompact)
        assertEquals("260420180000", request.finishTimeCompact)
        assertEquals(setOf(SettingKey.DAYS_TO_RUN), request.forceWriteKeys)
    }

    @Test
    fun `absolute finish edit preserves days and resolves schedule`() {
        val request = ScheduleSubmitSupport.absoluteFinishEdit(
            currentSettings = sampleSettings().copy(
                currentTimeCompact = "260420100000",
                startTimeCompact = "260420120000",
            ),
            normalizedFinishTime = "260420183000",
            preserveDaysToRun = true,
        )

        assertEquals("260420120000", request.startTimeCompact)
        assertEquals("260420183000", request.finishTimeCompact)
        assertEquals(setOf(SettingKey.DAYS_TO_RUN), request.forceWriteKeys)
    }

    @Test
    fun `absolute finish edit clamps past start time to next five minute boundary`() {
        val request = ScheduleSubmitSupport.absoluteFinishEdit(
            currentSettings = sampleSettings().copy(
                currentTimeCompact = "260420100231",
                startTimeCompact = "260420095500",
            ),
            normalizedFinishTime = "260420113000",
            preserveDaysToRun = false,
        )

        assertEquals("260420100500", request.startTimeCompact)
        assertEquals("260420113000", request.finishTimeCompact)
        assertEquals(emptySet(), request.forceWriteKeys)
    }

    @Test
    fun `absolute duration edit derives finish from start and preserves days`() {
        val request = ScheduleSubmitSupport.absoluteDurationEdit(
            currentSettings = sampleSettings().copy(
                currentTimeCompact = "260420100000",
                startTimeCompact = "260420120000",
            ),
            requestedDuration = java.time.Duration.ofMinutes(150),
            preserveDaysToRun = true,
        )

        assertEquals("260420120000", request.startTimeCompact)
        assertEquals("260420143000", request.finishTimeCompact)
        assertEquals(setOf(SettingKey.DAYS_TO_RUN), request.forceWriteKeys)
    }

    @Test
    fun `absolute finish edit with duration override delegates to duration edit`() {
        val request = ScheduleSubmitSupport.absoluteFinishEditWithDurationOverride(
            currentSettings = sampleSettings().copy(
                currentTimeCompact = "260420100000",
                startTimeCompact = "260420120000",
            ),
            normalizedFinishTime = "260420183000",
            requestedDurationOverride = java.time.Duration.ofMinutes(150),
            preserveDaysToRun = true,
        )

        assertEquals("260420120000", request.startTimeCompact)
        assertEquals("260420143000", request.finishTimeCompact)
        assertEquals(setOf(SettingKey.DAYS_TO_RUN), request.forceWriteKeys)
    }

    @Test
    fun `days to run edit can include shortened finish request`() {
        val request = ScheduleSubmitSupport.daysToRunEdit(
            currentSettings = sampleSettings().copy(
                currentTimeCompact = "260420100000",
                startTimeCompact = "260420120000",
                finishTimeCompact = "260420180000",
                daysToRun = 1,
            ),
            requestedDaysToRun = 4,
            requestedFinishTimeCompact = "260420143000",
        )

        assertEquals(4, request.daysToRun)
        assertEquals("260420120000", request.startTimeCompact)
        assertEquals("260420143000", request.finishTimeCompact)
        assertEquals(setOf(SettingKey.DAYS_TO_RUN), request.forceWriteKeys)
    }

    private fun sampleSettings(): DeviceSettings {
        return DeviceSettings(
            stationId = "N0CALL",
            eventType = EventType.CLASSIC,
            foxRole = FoxRole.CLASSIC_1,
            patternText = "TEST",
            idCodeSpeedWpm = 8,
            patternCodeSpeedWpm = 12,
            currentTimeCompact = null,
            startTimeCompact = null,
            finishTimeCompact = null,
            daysToRun = 1,
            defaultFrequencyHz = 3_550_000L,
            lowFrequencyHz = 3_510_000L,
            mediumFrequencyHz = 3_550_000L,
            highFrequencyHz = 3_590_000L,
            beaconFrequencyHz = 3_570_000L,
            lowBatteryThresholdVolts = 3.5,
            externalBatteryControlMode = ExternalBatteryControlMode.OFF,
            transmissionsEnabled = true,
        )
    }
}
