package com.openardf.serialslinger.session

import com.openardf.serialslinger.model.ConnectionState
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.SettingsField
import com.openardf.serialslinger.transport.FakeDeviceTransport
import com.openardf.serialslinger.transport.SignalSlingerReadPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceSessionControllerTest {
    @Test
    fun connectAndLoadSendsReadPlanAndBuildsEditableState() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "VER" to listOf("* SW Ver: 1.2.3 HW Build: 3.5"),
                "ID" to listOf("* ID: W1FOX"),
                "EVT" to listOf("* Event:Classic"),
                """FOX""" to listOf("""* Fox:Classic Fox 1 "MOE""""),
                "PAT" to listOf("* PAT:TEST"),
                "SPD I" to listOf("* ID SPD:8 WPM"),
                "SPD P" to listOf("* PAT SPD:12 WPM"),
                "CLK T" to listOf(
                    "* Time:Fri 10-apr-2026 14:22:33",
                    "* Start:Fri 10-apr-2026 15:00:00",
                    "* Finish:Fri 10-apr-2026 17:00:00",
                    "* Days to run: 3",
                ),
                "CLK S" to listOf("* Start:Fri 10-apr-2026 15:00:00"),
                "CLK F" to listOf("* Finish:Fri 10-apr-2026 17:00:00"),
                "FRE" to listOf("* FRE=3550.0 kHz"),
                "FRE 1" to listOf("* FRE 1=3510.0 kHz"),
                "FRE 2" to listOf("* FRE 2=3550.0 kHz"),
                "FRE 3" to listOf("* FRE 3=3590.0 kHz"),
                "FRE B" to listOf("* FRE B=3570.0 kHz"),
                "BAT" to listOf(
                    "* Int. Bat = 4.1 Volts",
                    "* thresh   = 3.5 Volts",
                    "* Ext. Bat =12.8 Volts",
                    "* Ext. Bat. Ctrl = ON",
                    "* Transmitter = Enabled",
                ),
            ),
        )

        val result = DeviceSessionController.connectAndLoad(transport)

        assertTrue(transport.isConnected)
        assertEquals(SignalSlingerReadPlan.defaultLoadCommands, result.commandsSent)
        assertEquals(SignalSlingerReadPlan.defaultLoadCommands, transport.sentCommands)
        assertEquals("W1FOX", result.state.snapshot?.settings?.stationId)
        assertEquals(FoxRole.CLASSIC_1, result.state.snapshot?.settings?.foxRole)
        assertEquals("TEST", result.state.snapshot?.settings?.patternText)
        assertEquals(3_570_000L, result.state.snapshot?.settings?.beaconFrequencyHz)
        assertEquals(4.1, result.state.snapshot?.status?.internalBatteryVolts)
        assertEquals(ConnectionState.CONNECTED, result.state.connectionState)
        assertEquals("W1FOX", result.state.editableSettings?.stationId?.editedValue)
        assertFalse(result.state.editableSettings?.hasDirtyFields ?: true)
        assertEquals(result.commandsSent.size + result.linesReceived.size, result.traceEntries.size)
        assertEquals(SerialTraceDirection.TX, result.traceEntries.first().direction)
    }

    @Test
    fun submitEditsSendsMinimalCommandsAndRefreshesState() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "ID W1FOX" to listOf("* ID: W1FOX"),
                "CLK D 3" to listOf("* Days to run: 3"),
                "FRE B 3580000" to listOf("* FRE B=3580.0 kHz"),
                "ID" to listOf("* ID: W1FOX"),
                "CLK D" to listOf("* Days to run: 3"),
                "EVT" to listOf("* Event:Classic"),
                "FRE B" to listOf("* FRE B=3580.0 kHz"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(FakeDeviceTransport(), sampleSettings())
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            stationId = SettingsField("stationId", "Station ID", "N0CALL", "W1FOX"),
            daysToRun = SettingsField("daysToRun", "Days To Run", 1, 3),
            beaconFrequencyHz = SettingsField("beaconFrequencyHz", "Beacon Frequency", 3_570_000L, 3_580_000L),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(
            listOf("ID W1FOX", "CLK D 3", "FRE B 3580000"),
            result.commandsSent,
        )
        assertEquals(listOf("ID", "CLK D", "EVT", "FRE B"), result.readbackCommandsSent)
        assertEquals(
            listOf("ID W1FOX", "CLK D 3", "FRE B 3580000", "ID", "CLK D", "EVT", "FRE B"),
            transport.sentCommands,
        )
        assertEquals("W1FOX", result.state.snapshot?.settings?.stationId)
        assertEquals(3, result.state.snapshot?.settings?.daysToRun)
        assertEquals(3_580_000L, result.state.snapshot?.settings?.beaconFrequencyHz)
        assertEquals(
            listOf(SettingKey.STATION_ID, SettingKey.DAYS_TO_RUN, SettingKey.BEACON_FREQUENCY_HZ),
            result.verifications.map { it.fieldKey },
        )
        assertTrue(result.verifications.all { it.observedInReadback && it.verified })
        assertFalse(result.state.editableSettings?.hasDirtyFields ?: true)
        assertEquals(emptyList(), result.state.pendingSubmitCommands)
        assertEquals(result.commandsSent.size + result.linesReceived.size, result.submitTraceEntries.size)
        assertEquals(result.readbackCommandsSent.size + result.readbackLinesReceived.size, result.readbackTraceEntries.size)
    }

    @Test
    fun submitEditsUsesReadbackToCatchVerificationMismatch() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "EVT F" to listOf("* Event:Foxoring"),
                "EVT" to listOf("* Event:Classic"),
                "FRE" to listOf("* FRE=3510.0 kHz"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(FakeDeviceTransport(), sampleSettings())
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            eventType = SettingsField("eventType", "Event Type", EventType.CLASSIC, EventType.FOXORING),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("EVT F"), result.commandsSent)
        assertEquals(listOf("EVT", "FRE"), result.readbackCommandsSent)
        assertEquals(EventType.CLASSIC, result.state.snapshot?.settings?.eventType)
        assertEquals(
            SettingVerification(
                fieldKey = SettingKey.EVENT_TYPE,
                expectedValue = EventType.FOXORING,
                actualValue = EventType.CLASSIC,
                observedInReadback = true,
                verified = false,
            ),
            result.verifications.single(),
        )
    }

    @Test
    fun submitEditsRefreshesCurrentFrequencyAfterFoxRoleChange() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "FOX 2" to listOf("""* Fox:Foxoring "Medium Freq" Fox"""),
                "FOX" to listOf("""* Fox:Foxoring "Medium Freq" Fox"""),
                "SPD P" to listOf("* PAT SPD:8 WPM"),
                "FRE" to listOf("* FRE=3540.0 kHz"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(FakeDeviceTransport(), sampleSettings().copy(
            eventType = EventType.FOXORING,
            foxRole = FoxRole.FOXORING_1,
            defaultFrequencyHz = 3_520_000L,
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = 3_560_000L,
            beaconFrequencyHz = 3_600_000L,
        ))
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            foxRole = SettingsField("foxRole", "Fox Role (FOX)", FoxRole.FOXORING_1, FoxRole.FOXORING_2),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("FOX 2"), result.commandsSent)
        assertEquals(listOf("FOX", "SPD P", "FRE"), result.readbackCommandsSent)
        assertEquals(FoxRole.FOXORING_2, result.state.snapshot?.settings?.foxRole)
        assertEquals(3_540_000L, result.state.snapshot?.settings?.defaultFrequencyHz)
        assertEquals(
            listOf("FOX 2", "FOX", "SPD P", "FRE"),
            transport.sentCommands,
        )
    }

    @Test
    fun disconnectMarksStateAsDisconnected() {
        val transport = FakeDeviceTransport()
        val loaded = DeviceSessionController.connectAndLoad(transport, sampleSettings())

        val disconnected = DeviceSessionController.disconnect(loaded.state, transport)

        assertFalse(transport.isConnected)
        assertEquals(ConnectionState.DISCONNECTED, disconnected.connectionState)
        assertEquals(ConnectionState.DISCONNECTED, disconnected.snapshot?.status?.connectionState)
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
