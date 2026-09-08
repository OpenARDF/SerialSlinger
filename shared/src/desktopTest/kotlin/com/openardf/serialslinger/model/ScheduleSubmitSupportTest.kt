package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import com.openardf.serialslinger.session.DeviceSessionController
import com.openardf.serialslinger.session.DeviceSessionWorkflow
import com.openardf.serialslinger.transport.DeviceTransport
import java.time.Duration

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
            requestedDaysToRun = 3,
        )

        assertEquals("260420120000", request.startTimeCompact)
        assertEquals("260420180000", request.finishTimeCompact)
        assertEquals(setOf(SettingKey.DAYS_TO_RUN), request.forceWriteKeys)
    }

    @Test
    fun `absolute start edit rounds start up to five minute boundary before deriving finish`() {
        val request = ScheduleSubmitSupport.absoluteStartEdit(
            currentSettings = sampleSettings().copy(currentTimeCompact = "260420100000"),
            normalizedStartTime = "260420120401",
            defaultEventLengthMinutes = 360,
        )

        assertEquals("260420120500", request.startTimeCompact)
        assertEquals("260420180500", request.finishTimeCompact)
        assertEquals(emptySet(), request.forceWriteKeys)
    }

    @Test
    fun `absolute finish edit preserves days and resolves schedule`() {
        val request = ScheduleSubmitSupport.absoluteFinishEdit(
            currentSettings = sampleSettings().copy(
                currentTimeCompact = "260420100000",
                startTimeCompact = "260420120000",
            ),
            normalizedFinishTime = "260420183000",
            requestedDaysToRun = 3,
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
            requestedDaysToRun = null,
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
            requestedDaysToRun = 3,
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
            requestedDaysToRun = 3,
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

    @Test
    fun `preserve three survives refreshed count of one and verifies selected count`() {
        val selection = ScheduleDurationGuardSupport.resolveScheduleChange(
            currentDaysToRun = 3,
            daysChoice = StartTimeDaysToRunChoice.PRESERVE,
            proposedDuration = Duration.ofHours(9),
            selectedOption = null,
        )
        val refreshed = sampleSettings().copy(currentTimeCompact = "260901082250", daysToRun = 1)
        val request = ScheduleSubmitSupport.absoluteStartEdit(
            currentSettings = refreshed,
            normalizedStartTime = "260904090000",
            requestedFinishTimeCompact = "260904180000",
            requestedDaysToRun = selection.resultingDaysToRun,
        )
        for (readbackDays in listOf(3, 1, null)) {
            val transport = object : DeviceTransport {
                var lastCommand = ""
                override fun connect() {}
                override fun disconnect() {}
                override fun sendCommands(commands: List<String>) { lastCommand = commands.last() }
                override fun readAvailableLines(): List<String> = if (lastCommand == "CLK") {
                    buildList {
                        add("* Start: Fri 04-Sep-2026 09:00:00")
                        add("* Finish: Fri 04-Sep-2026 18:00:00")
                        readbackDays?.let { add("* Days to run: $it") }
                    }
                } else emptyList()
            }
            val result = DeviceSessionController.submitEdits(
                state = DeviceSessionWorkflow.connected(refreshed),
                editedSettings = request.applyTo(EditableDeviceSettings.fromDeviceSettings(refreshed)),
                transport = transport,
                forceWriteKeys = request.forceWriteKeys,
                allowFullReloadVerification = false,
            )
            assertTrue("CLK D 3" in result.commandsSent)
            assertFalse("CLK D 1" in result.commandsSent)
            val daysVerification = result.verifications.single { it.fieldKey == SettingKey.DAYS_TO_RUN }
            assertEquals(3, daysVerification.expectedValue)
            assertEquals(readbackDays, daysVerification.actualValue)
            assertEquals(readbackDays == 3, daysVerification.verified)
            if (readbackDays == 3) {
                assertTrue(result.verifications.all { it.verified })
                assertEquals(4, SchedulePresentation.scheduleLines(result.state.snapshot!!.settings).size)
            }
        }
    }

    @Test
    fun `reset choice explicitly writes one even if refreshed device has three`() {
        val settings = sampleSettings().copy(currentTimeCompact = "260901082250", daysToRun = 3)
        val request = ScheduleSubmitSupport.absoluteStartEdit(
            currentSettings = settings,
            normalizedStartTime = "260904090000",
            defaultEventLengthMinutes = 540,
            requestedDaysToRun = 1,
        )
        assertEquals(1, request.applyTo(EditableDeviceSettings.fromDeviceSettings(settings)).daysToRun.editedValue)
        assertEquals(setOf(SettingKey.DAYS_TO_RUN), request.forceWriteKeys)
    }

    @Test
    fun `relative verification rejects missing or wrong count`() {
        ScheduleSubmitSupport.requireSelectedDaysReadback(3, listOf("* Days to run: 3"))
        assertFailsWith<IllegalStateException> {
            ScheduleSubmitSupport.requireSelectedDaysReadback(3, listOf("* Days to run: 1"))
        }
        assertFailsWith<IllegalStateException> {
            ScheduleSubmitSupport.requireSelectedDaysReadback(3, emptyList())
        }
    }

    @Test
    fun `device guard uses full uid rather than shared suffix`() {
        val fox5 = DeviceInfo(deviceUniqueId = "42348279800068200107012700000000", serialPortName = "USB")
        val fox1 = fox5.copy(deviceUniqueId = "42348279800037200105014100000000")
        assertFalse(ScheduleSubmitSupport.sameDeviceForSchedule(fox5, fox1))
        assertTrue(ScheduleSubmitSupport.sameDeviceForSchedule(fox5, fox5.copy()))
        assertFalse(ScheduleSubmitSupport.sameDeviceForSchedule(fox5, null))
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
