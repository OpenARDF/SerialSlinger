package com.openardf.serialslinger.cli

import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DesktopSmokeCliTest {
    @Test
    fun parsesListCommand() {
        val command = DesktopSmokeCliParser.parse(arrayOf("list"))

        assertIs<DesktopSmokeCommand.ListPorts>(command)
    }

    @Test
    fun parsesLoadCommand() {
        val command = DesktopSmokeCliParser.parse(arrayOf("load", "/dev/tty.usbserial"))

        assertEquals("/dev/tty.usbserial", assertIs<DesktopSmokeCommand.Load>(command).port)
    }

    @Test
    fun parsesSubmitCommandAssignments() {
        val command = DesktopSmokeCliParser.parse(
            arrayOf("submit", "/dev/tty.usbserial", "stationId=W1FOX", "daysToRun=3"),
        )

        val submit = assertIs<DesktopSmokeCommand.Submit>(command)
        assertEquals("/dev/tty.usbserial", submit.port)
        assertEquals("W1FOX", submit.assignments["stationId"])
        assertEquals("3", submit.assignments["daysToRun"])
    }

    @Test
    fun appliesAssignmentsToEditableSettings() {
        val editable = EditableDeviceSettings.fromDeviceSettings(sampleSettings())

        val updated = applyAssignments(
            editable,
            mapOf(
                "stationId" to "W1FOX",
                "eventType" to "foxoring",
                "foxRole" to "2",
                "lowFrequencyHz" to "3520 kHz",
                "beaconFrequencyHz" to "3.580 MHz",
                "transmissionsEnabled" to "false",
                "externalBatteryControlMode" to "charge_only",
            ),
        )

        assertEquals("W1FOX", updated.stationId.editedValue)
        assertEquals(EventType.FOXORING, updated.eventType.editedValue)
        assertEquals(FoxRole.FOXORING_2, updated.foxRole.editedValue)
        assertEquals(3_520_000L, updated.lowFrequencyHz.editedValue)
        assertEquals(3_580_000L, updated.beaconFrequencyHz.editedValue)
        assertEquals(false, updated.transmissionsEnabled.editedValue)
        assertEquals(ExternalBatteryControlMode.CHARGE_ONLY, updated.externalBatteryControlMode.editedValue)
    }

    @Test
    fun rejectsDefaultFrequencyThatConflictsWithSelectedActiveBank() {
        val editable = EditableDeviceSettings.fromDeviceSettings(sampleSettings())

        val failure = assertFailsWith<IllegalArgumentException> {
            applyAssignments(
                editable,
                mapOf(
                    "eventType" to "foxoring",
                    "foxRole" to "2",
                    "defaultFrequencyHz" to "3590 kHz",
                    "mediumFrequencyHz" to "3545 kHz",
                ),
            )
        }

        assertEquals(
            "`defaultFrequencyHz` conflicts with `mediumFrequencyHz` for Foxoring \"Medium Freq\" Fox. " +
                "The firmware reports current frequency from the active bank, so set only the bank frequency or use the same value for both.",
            failure.message,
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
