package com.openardf.serialslinger.session

import com.openardf.serialslinger.model.ConnectionState
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.SettingsField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceSessionWorkflowTest {
    @Test
    fun ingestReportLinesBuildsDeviceSnapshotFromFirmwareStyleReplies() {
        val state = DeviceSessionWorkflow.connected()

        val updated = DeviceSessionWorkflow.ingestReportLines(
            state,
            listOf(
                "* SW Ver: 1.2.3 HW Build: 3.5",
                "* ID: W1FOX",
                "* Event:Classic",
                """* Fox:Classic Fox 1 "MOE"""",
                "* ID SPD:8 WPM",
                "* PAT SPD:12 WPM",
                "* Days to run: 3",
                "* FRE B=3570.0 kHz",
                "* thresh   = 3.5 Volts",
                "* Ext. Bat. Ctrl = ON",
                "* Transmitter = Enabled",
                "* Running forever.",
                "* Starts in: 23 hours 4 minutes 5 seconds",
                "* Lasts: 2 hours 0 minutes 0 seconds",
                "* Int. Bat = 4.1 Volts",
                "* Ext. Bat =12.8 Volts",
            ),
        )

        val snapshot = assertNotNull(updated.snapshot)
        assertEquals(ConnectionState.CONNECTED, updated.connectionState)
        assertEquals("1.2.3", snapshot.info.softwareVersion)
        assertEquals("3.5", snapshot.info.hardwareBuild)
        assertTrue(snapshot.capabilities.supportsScheduling)
        assertTrue(snapshot.capabilities.supportsTemperatureReadback)
        assertEquals("W1FOX", snapshot.settings.stationId)
        assertEquals(EventType.CLASSIC, snapshot.settings.eventType)
        assertEquals(FoxRole.CLASSIC_1, snapshot.settings.foxRole)
        assertEquals(3, snapshot.settings.daysToRun)
        assertEquals(3_570_000L, snapshot.settings.beaconFrequencyHz)
        assertEquals(3.5, snapshot.settings.lowBatteryThresholdVolts)
        assertEquals(ExternalBatteryControlMode.CHARGE_AND_TRANSMIT, snapshot.settings.externalBatteryControlMode)
        assertTrue(snapshot.settings.transmissionsEnabled)
        assertEquals(4.1, snapshot.status.internalBatteryVolts)
        assertEquals(12.8, snapshot.status.externalBatteryVolts)
        assertEquals(true, snapshot.status.eventEnabled)
        assertEquals("Running forever.", snapshot.status.eventStateSummary)
        assertEquals("23 hours 4 minutes 5 seconds", snapshot.status.eventStartsInSummary)
        assertEquals("2 hours 0 minutes 0 seconds", snapshot.status.eventDurationSummary)
    }

    @Test
    fun ingestReportLinesPreservesDisabledTransmissionsStateFromBatteryReply() {
        val state = DeviceSessionWorkflow.connected()

        val updated = DeviceSessionWorkflow.ingestReportLines(
            state,
            listOf(
                "* Int. Bat = 4.3 Volts",
                "* Transmitter = Disabled",
            ),
        )

        val snapshot = assertNotNull(updated.snapshot)
        assertFalse(snapshot.settings.transmissionsEnabled)
        assertEquals(4.3, snapshot.status.internalBatteryVolts)
    }

    @Test
    fun ingestReportLinesCombinesBatteryControlAndTransmitterIntoChargeOnlyMode() {
        val state = DeviceSessionWorkflow.connected()

        val updated = DeviceSessionWorkflow.ingestReportLines(
            state,
            listOf(
                "* Ext. Bat. Ctrl = ON",
                "* Transmitter = Disabled",
            ),
        )

        val snapshot = assertNotNull(updated.snapshot)
        assertEquals(ExternalBatteryControlMode.CHARGE_ONLY, snapshot.settings.externalBatteryControlMode)
        assertFalse(snapshot.settings.transmissionsEnabled)
    }

    @Test
    fun startEditingCreatesEditableSettingsFromSnapshot() {
        val loaded = DeviceSessionWorkflow.ingestReportLines(
            DeviceSessionWorkflow.connected(sampleSettings()),
            listOf("* ID: W1FOX"),
        )

        val editing = DeviceSessionWorkflow.startEditing(loaded)

        assertEquals("W1FOX", editing.editableSettings?.stationId?.editedValue)
        assertFalse(editing.editableSettings?.hasDirtyFields ?: true)
    }

    @Test
    fun submitChangesProducesMinimalCommandListFromEditableSettings() {
        val loaded = DeviceSessionWorkflow.ingestReportLines(
            DeviceSessionWorkflow.connected(sampleSettings()),
            listOf("* ID: N0CALL", "* Days to run: 1", "* FRE B=3570.0 kHz"),
        )

        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(loaded.snapshot).settings).copy(
            stationId = SettingsField("stationId", "Station ID", "N0CALL", "W1FOX"),
            daysToRun = SettingsField("daysToRun", "Days To Run", 1, 3),
            beaconFrequencyHz = SettingsField("beaconFrequencyHz", "Beacon Frequency", 3_570_000L, 3_580_000L),
        )

        val submission = DeviceSessionWorkflow.submitChanges(loaded, editable)

        assertEquals(
            listOf("ID W1FOX", "CLK D 3", "FRE B 3580000"),
            submission.commands,
        )
        assertEquals(submission.commands, submission.state.pendingSubmitCommands)
    }

    @Test
    fun freshDeviceReportsDoNotOverwriteDirtyEditableState() {
        val loaded = DeviceSessionWorkflow.startEditing(
            DeviceSessionWorkflow.ingestReportLines(
                DeviceSessionWorkflow.connected(sampleSettings()),
                listOf("* ID: N0CALL"),
            ),
        )

        val dirtyState = loaded.copy(
            editableSettings = assertNotNull(loaded.editableSettings).copy(
                stationId = SettingsField("stationId", "Station ID", "N0CALL", "W1FOX"),
            ),
        )

        val refreshed = DeviceSessionWorkflow.ingestReportLines(
            dirtyState,
            listOf("* ID: N0CALL", "* Int. Bat = 4.0 Volts"),
        )

        assertEquals("W1FOX", refreshed.editableSettings?.stationId?.editedValue)
        assertEquals(4.0, refreshed.snapshot?.status?.internalBatteryVolts)
    }

    private fun sampleSettings(): DeviceSettings {
        return DeviceSettings(
            stationId = "",
            eventType = EventType.NONE,
            foxRole = null,
            patternText = "TEST",
            idCodeSpeedWpm = 0,
            patternCodeSpeedWpm = 0,
            currentTimeCompact = null,
            startTimeCompact = null,
            finishTimeCompact = null,
            daysToRun = 1,
            defaultFrequencyHz = 0L,
            lowFrequencyHz = null,
            mediumFrequencyHz = null,
            highFrequencyHz = null,
            beaconFrequencyHz = null,
            lowBatteryThresholdVolts = null,
            externalBatteryControlMode = null,
            transmissionsEnabled = true,
        )
    }
}
