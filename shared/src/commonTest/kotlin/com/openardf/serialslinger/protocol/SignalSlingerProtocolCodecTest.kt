package com.openardf.serialslinger.protocol

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.WritePlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SignalSlingerProtocolCodecTest {
    @Test
    fun parsesVersionReplyIntoDeviceInfoPatch() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* SW Ver: 1.2.3 HW Build: 3.5")

        assertNotNull(update)
        assertEquals("1.2.3", update.deviceInfoPatch?.softwareVersion)
        assertEquals("3.5", update.deviceInfoPatch?.hardwareBuild)
    }

    @Test
    fun parsesStationIdReplyAndTrimsStoredLeadingSpace() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* ID: W1FOX")

        assertNotNull(update)
        assertEquals("W1FOX", update.settingsPatch?.stationId)
    }

    @Test
    fun parsesPatternTextReply() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* PAT:TEST")

        assertNotNull(update)
        assertEquals("TEST", update.settingsPatch?.patternText)
    }

    @Test
    fun parsesClockRepliesIntoCompactTimestamps() {
        val currentUpdate = SignalSlingerProtocolCodec.parseReportLine("* Time:Fri 10-apr-2026 14:22:33")
        val startUpdate = SignalSlingerProtocolCodec.parseReportLine("* Start:Fri 10-apr-2026 15:00:00")
        val finishUpdate = SignalSlingerProtocolCodec.parseReportLine("* Finish:Fri 10-apr-2026 17:00:00")

        assertEquals("260410142233", currentUpdate?.settingsPatch?.currentTimeCompact)
        assertEquals("260410150000", startUpdate?.settingsPatch?.startTimeCompact)
        assertEquals("260410170000", finishUpdate?.settingsPatch?.finishTimeCompact)
    }

    @Test
    fun treatsZeroEpochScheduleRepliesAsNotSet() {
        val startUpdate = SignalSlingerProtocolCodec.parseReportLine("* Start:Thu 01-jan-1970 00:00:00")
        val finishUpdate = SignalSlingerProtocolCodec.parseReportLine("* Finish:Thu 01-jan-1970 00:00:00")

        assertEquals(null, startUpdate?.settingsPatch?.startTimeCompact)
        assertEquals(null, finishUpdate?.settingsPatch?.finishTimeCompact)
    }

    @Test
    fun parsesTemperatureReplyIntoDeviceStatus() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* Temp: 42.5C")

        assertEquals(42.5, update?.deviceStatusPatch?.temperatureC)
    }

    @Test
    fun parsesFoxReplyIntoFoxRole() {
        val update = SignalSlingerProtocolCodec.parseReportLine("""* Fox:Classic Fox 1 "MOE"""")

        assertNotNull(update)
        assertEquals(FoxRole.CLASSIC_1, update.settingsPatch?.foxRole)
    }

    @Test
    fun parsesFrequencyReplyUsingFirmwareKhzFormat() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* FRE B=3570.0 kHz")

        assertNotNull(update)
        assertEquals(3_570_000L, update.settingsPatch?.beaconFrequencyHz)
    }

    @Test
    fun parsesFrequencyReplyUsingFirmwareMhzFormat() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* FRE 1=3.520 MHz")

        assertNotNull(update)
        assertEquals(3_520_000L, update.settingsPatch?.lowFrequencyHz)
    }

    @Test
    fun parsesBatteryAndTransmitterReplies() {
        val thresholdUpdate = SignalSlingerProtocolCodec.parseReportLine("* thresh   = 3.5 Volts")
        val batteryControlUpdate = SignalSlingerProtocolCodec.parseReportLine("* Ext. Bat. Ctrl = ON")
        val transmitterUpdate = SignalSlingerProtocolCodec.parseReportLine("* Transmitter = Disabled")

        assertEquals(3.5, thresholdUpdate?.settingsPatch?.lowBatteryThresholdVolts)
        assertEquals(
            ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
            batteryControlUpdate?.settingsPatch?.externalBatteryControlMode,
        )
        assertEquals(false, transmitterUpdate?.settingsPatch?.transmissionsEnabled)
    }

    @Test
    fun appliesParsedSettingsPatchToBaseSettings() {
        val base = sampleSettings()
        val update = SignalSlingerProtocolCodec.parseReportLine("* Days to run: 3")

        val patched = update?.settingsPatch?.applyTo(base)

        assertNotNull(patched)
        assertEquals(3, patched.daysToRun)
        assertEquals(base.stationId, patched.stationId)
    }

    @Test
    fun parsesDeviceReportedEventStateLines() {
        val enabledUpdate = SignalSlingerProtocolCodec.parseReportLine("* Running forever.")
        val disabledUpdate = SignalSlingerProtocolCodec.parseReportLine("* Not scheduled")
        val startsInUpdate = SignalSlingerProtocolCodec.parseReportLine("* Starts in: 23 hours 4 minutes 5 seconds")
        val lastsUpdate = SignalSlingerProtocolCodec.parseReportLine("* Lasts: 2 hours 0 minutes 0 seconds")
        val inProgressUpdate = SignalSlingerProtocolCodec.parseReportLine("* In progress")

        assertEquals(true, enabledUpdate?.deviceStatusPatch?.eventEnabled)
        assertEquals("Running forever.", enabledUpdate?.deviceStatusPatch?.eventStateSummary)
        assertEquals(false, disabledUpdate?.deviceStatusPatch?.eventEnabled)
        assertEquals("Not scheduled", disabledUpdate?.deviceStatusPatch?.eventStateSummary)
        assertEquals("23 hours 4 minutes 5 seconds", startsInUpdate?.deviceStatusPatch?.eventStartsInSummary)
        assertEquals("2 hours 0 minutes 0 seconds", lastsUpdate?.deviceStatusPatch?.eventDurationSummary)
        assertEquals("In Progress", inProgressUpdate?.deviceStatusPatch?.eventStartsInSummary)
    }

    @Test
    fun encodesMinimalWriteCommandsInStableOrder() {
        val original = sampleSettings()
        val edited = original.copy(
            stationId = "W1FOX",
            foxRole = FoxRole.CLASSIC_2,
            daysToRun = 3,
            beaconFrequencyHz = 3_580_000L,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(
            listOf(
                "ID W1FOX",
                "FOX 2",
                "CLK D 3",
                "FRE B 3580000",
            ),
            commands,
        )
    }

    @Test
    fun encodesClockWriteCommands() {
        val original = sampleSettings()
        val edited = original.copy(
            currentTimeCompact = "260410142233",
            startTimeCompact = "260410150000",
            finishTimeCompact = "260410170000",
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(
            listOf(
                "CLK T 260410142233",
                "CLK S 260410150000",
                "CLK F 260410170000",
            ),
            commands,
        )
    }

    @Test
    fun collapsesBatteryControlWritesIntoOneCommand() {
        val original = sampleSettings()
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_ONLY,
            transmissionsEnabled = false,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 2"), commands)
    }

    @Test
    fun encodesBatteryControlModeDisabledAsBatX0() {
        val original = sampleSettings().copy(
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
        )
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.OFF,
            transmissionsEnabled = true,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 0"), commands)
    }

    @Test
    fun encodesBatteryControlModeEnabledAsBatX1() {
        val original = sampleSettings()
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 1"), commands)
    }

    @Test
    fun encodesBatteryControlModeEnabledTxDisabledAsBatX2() {
        val original = sampleSettings()
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_ONLY,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 2"), commands)
    }

    @Test
    fun encodesNumberedFrequencyProfileCommands() {
        val original = sampleSettings()
        val edited = original.copy(
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = 3_560_000L,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(
            listOf(
                "FRE 1 3520000",
                "FRE 2 3540000",
                "FRE 3 3560000",
            ),
            commands,
        )
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
