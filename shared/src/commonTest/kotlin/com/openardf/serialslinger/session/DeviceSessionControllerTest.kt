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
                "CLK" to listOf(
                    "* Time:Fri 10-apr-2026 14:22:33",
                    "* Start:Fri 10-apr-2026 15:00:00",
                    "* Finish:Fri 10-apr-2026 17:00:00",
                    "* Days to run: 3",
                ),
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
                "TMP" to listOf(
                    "* Max Ever: 27.1C",
                    "* Max Temp: 24.2C",
                    "* Temp: 20.9C",
                    "* Min Temp: 18.4C",
                    "* Thermal shutdown threshold: 50C",
                ),
            ),
        )

        val result = DeviceSessionController.connectAndLoad(transport)

        assertTrue(transport.isConnected)
        assertEquals(
            com.openardf.serialslinger.protocol.SignalSlingerFirmwareSupport.resolve("1.2.3").fullLoadCommands,
            result.commandsSent,
        )
        assertEquals(result.commandsSent, transport.sentCommands)
        assertEquals("W1FOX", result.state.snapshot?.settings?.stationId)
        assertEquals(FoxRole.CLASSIC_1, result.state.snapshot?.settings?.foxRole)
        assertEquals("TEST", result.state.snapshot?.settings?.patternText)
        assertEquals(3_570_000L, result.state.snapshot?.settings?.beaconFrequencyHz)
        assertEquals(20.9, result.state.snapshot?.status?.temperatureC)
        assertEquals(18.4, result.state.snapshot?.status?.minimumTemperatureC)
        assertEquals(24.2, result.state.snapshot?.status?.maximumTemperatureC)
        assertEquals(27.1, result.state.snapshot?.status?.maximumEverTemperatureC)
        assertEquals(50.0, result.state.snapshot?.status?.thermalShutdownThresholdC)
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
                "CLK" to listOf("* Days to run: 3"),
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
        assertEquals(listOf("ID", "CLK", "EVT", "FRE B"), result.readbackCommandsSent)
        assertEquals(
            listOf("ID W1FOX", "CLK D 3", "FRE B 3580000", "ID", "CLK", "EVT", "FRE B"),
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
    fun submitEditsCanForceDaysToRunWriteEvenWhenUnchanged() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "CLK S 260410151000" to listOf("* Start:Fri 10-apr-2026 15:10:00"),
                "CLK F 260410170000" to listOf("* Finish:Fri 10-apr-2026 17:00:00"),
                "CLK D 3" to listOf("* Days to run: 3"),
                "VER" to listOf("* SW Ver: 1.2 HW Build: 3.5"),
                "ID" to listOf("* ID: N0CALL"),
                "CLK" to listOf(
                    "* Start:Fri 10-apr-2026 15:10:00",
                    "* Finish:Fri 10-apr-2026 17:00:00",
                    "* Days to run: 3",
                ),
                "EVT" to listOf("* Event:Classic"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(FakeDeviceTransport(), sampleSettings().copy(
            startTimeCompact = "260410150000",
            finishTimeCompact = "260410170000",
            daysToRun = 3,
        ))
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            startTimeCompact = SettingsField("startTimeCompact", "Start Time", "260410150000", "260410151000"),
            finishTimeCompact = SettingsField("finishTimeCompact", "Finish Time", "260410170000", "260410170000"),
            daysToRun = SettingsField("daysToRun", "Days To Run", 3, 3),
        )

        val result = DeviceSessionController.submitEdits(
            connected.state,
            editable,
            transport,
            forceWriteKeys = setOf(SettingKey.DAYS_TO_RUN),
        )

        assertEquals(
            listOf("CLK S 260410151000", "CLK F 260410170000", "CLK D 3"),
            result.commandsSent,
        )
        assertEquals(3, result.state.snapshot?.settings?.daysToRun)
        assertEquals(
            listOf(SettingKey.START_TIME, SettingKey.FINISH_TIME, SettingKey.DAYS_TO_RUN),
            result.verifications.map { it.fieldKey },
        )
        assertTrue(result.verifications.all { it.verified })
    }

    @Test
    fun submitEditsAcceptsUppercaseReadbackForLowercaseTextInput() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "ID w1fox" to listOf("* ID: W1FOX"),
                "PAT moe" to listOf("* PAT:MOE"),
                "ID" to listOf("* ID: W1FOX"),
                "PAT" to listOf("* PAT:MOE"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(FakeDeviceTransport(), sampleSettings())
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            stationId = SettingsField("stationId", "Station ID", "N0CALL", "w1fox"),
            patternText = SettingsField("patternText", "Pattern Text", "MOE", "moe"),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("ID w1fox", "PAT moe"), result.commandsSent)
        assertEquals(listOf("ID", "PAT"), result.readbackCommandsSent)
        assertEquals("W1FOX", result.state.snapshot?.settings?.stationId)
        assertEquals("MOE", result.state.snapshot?.settings?.patternText)
        assertEquals(
            listOf(SettingKey.STATION_ID, SettingKey.PATTERN_TEXT),
            result.verifications.map { it.fieldKey },
        )
        assertTrue(result.verifications.all { it.observedInReadback && it.verified })
    }

    @Test
    fun submitEditsUsesReadbackToCatchVerificationMismatch() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "EVT F" to listOf("* Event:Foxoring"),
                "EVT" to listOf("* Event:Classic"),
                "FOX" to listOf("""* Fox:Classic Fox 1 "MOE""""),
                "PAT" to listOf("* PAT:MOE"),
                "SPD F" to listOf("* FOX-O SPD:8 WPM"),
                "FRE" to listOf("* FRE=3510.0 kHz"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(FakeDeviceTransport(), sampleSettings())
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            eventType = SettingsField("eventType", "Event Type", EventType.CLASSIC, EventType.FOXORING),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("EVT F"), result.commandsSent)
        assertEquals(listOf("EVT", "FOX", "PAT", "SPD F", "FRE"), result.readbackCommandsSent)
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
    fun submitEditsRefreshesFoxRoleAfterEventTypeChange() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "EVT C" to listOf(
                    "* Event:Classic",
                    """* Fox:Classic Fox 3 "MOS"""",
                ),
                "EVT" to listOf("* Event:Classic"),
                "FOX" to listOf("""* Fox:Classic Fox 3 "MOS""""),
                "PAT" to listOf("* PAT:MOS"),
                "SPD P" to listOf("* PAT SPD:8 WPM"),
                "FRE" to listOf("* FRE=3520.0 kHz"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(FakeDeviceTransport(), sampleSettings().copy(
            eventType = EventType.FOXORING,
            foxRole = FoxRole.FOXORING_3,
            defaultFrequencyHz = 3_560_000L,
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = 3_560_000L,
            beaconFrequencyHz = 3_600_000L,
        ))
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            eventType = SettingsField("eventType", "Event Type", EventType.FOXORING, EventType.CLASSIC),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("EVT C"), result.commandsSent)
        assertEquals(listOf("EVT", "FOX", "PAT", "SPD P", "FRE"), result.readbackCommandsSent)
        assertEquals(EventType.CLASSIC, result.state.snapshot?.settings?.eventType)
        assertEquals(FoxRole.CLASSIC_3, result.state.snapshot?.settings?.foxRole)
        assertEquals("MOS", result.state.snapshot?.settings?.patternText)
        assertEquals(8, result.state.snapshot?.settings?.patternCodeSpeedWpm)
        assertEquals(3_520_000L, result.state.snapshot?.settings?.defaultFrequencyHz)
    }

    @Test
    fun submitEditsRefreshesCurrentFrequencyAfterFoxRoleChange() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "FOX 2" to listOf("""* Fox:Foxoring "Medium Freq" Fox"""),
                "FOX" to listOf("""* Fox:Foxoring "Medium Freq" Fox"""),
                "PAT" to listOf("* PAT:MOI"),
                "SPD F" to listOf("* FOX-O SPD:8 WPM"),
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
        assertEquals(listOf("FOX", "PAT", "SPD F", "FRE"), result.readbackCommandsSent)
        assertEquals(FoxRole.FOXORING_2, result.state.snapshot?.settings?.foxRole)
        assertEquals("MOI", result.state.snapshot?.settings?.patternText)
        assertEquals(3_540_000L, result.state.snapshot?.settings?.defaultFrequencyHz)
        assertEquals(
            listOf("FOX 2", "FOX", "PAT", "SPD F", "FRE"),
            transport.sentCommands,
        )
    }

    @Test
    fun connectAndLoadUsesFoxoringPatternSpeedReadCommand() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "VER" to listOf("* SW Ver: 1.2.3 HW Build: 3.5"),
                "ID" to listOf("* ID: W1FOX"),
                "EVT" to listOf("* Event:Foxoring"),
                "FOX" to listOf("""* Fox:Foxoring "High Freq" Fox"""),
                "PAT" to listOf("* PAT:MOH"),
                "SPD I" to listOf("* ID SPD:20 WPM"),
                "SPD F" to listOf("* FOX-O SPD:8 WPM"),
                "CLK" to listOf(
                    "* Start:not set",
                    "* Finish:not set",
                ),
                "FRE" to listOf("* FRE=3560.0 kHz"),
                "FRE 1" to listOf("* FRE 1=3520.0 kHz"),
                "FRE 2" to listOf("* FRE 2=3540.0 kHz"),
                "FRE 3" to listOf("* FRE 3=3560.0 kHz"),
                "FRE B" to listOf("* FRE B=3600.0 kHz"),
                "BAT" to listOf("* thresh   = 3.8 Volts"),
                "TMP" to listOf("* Temp: 20.9C"),
            ),
        )

        val result = DeviceSessionController.connectAndLoad(transport)

        assertTrue("SPD F" in result.commandsSent)
        assertFalse("SPD P" in result.commandsSent)
        assertEquals(8, result.state.snapshot?.settings?.patternCodeSpeedWpm)
    }

    @Test
    fun submitEditsUsesFoxoringPatternSpeedWriteAndReadbackCommands() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "SPD F 8" to listOf("* FOX-O SPD:8 WPM"),
                "SPD F" to listOf("* FOX-O SPD:8 WPM"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(
            FakeDeviceTransport(),
            sampleSettings().copy(
                eventType = EventType.FOXORING,
                patternCodeSpeedWpm = 12,
            ),
        )
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            patternCodeSpeedWpm = SettingsField("patternCodeSpeedWpm", "Pattern Speed", 12, 8),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("SPD F 8"), result.commandsSent)
        assertEquals(listOf("SPD F"), result.readbackCommandsSent)
        assertEquals(listOf("SPD F 8", "SPD F"), transport.sentCommands)
        assertTrue(result.verifications.all { it.observedInReadback && it.verified })
    }

    @Test
    fun submitEditsUsesFullReloadVerificationForFinishTime() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "CLK F 260410172000" to listOf("* Finish:Fri 10-apr-2026 17:20:00"),
                "CLK" to listOf(
                    "* Time:Fri 10-apr-2026 14:22:33",
                    "* Start:Fri 10-apr-2026 15:00:00",
                    "* Finish:Fri 10-apr-2026 17:20:00",
                    "* Days to run: 3",
                ),
                "EVT" to listOf("* Event:Classic"),
                "VER" to listOf("* SW Ver: 1.2.3 HW Build: 3.5"),
                "ID" to listOf("* ID: N0CALL"),
                "FOX" to listOf("""* Fox:Classic Fox 1 "TEST""""),
                "PAT" to listOf("* PAT:TEST"),
                "SPD I" to listOf("* ID SPD:8 WPM"),
                "SPD P" to listOf("* PAT SPD:12 WPM"),
                "FRE" to listOf("* FRE=3550.0 kHz"),
                "FRE 1" to listOf("* FRE 1=3510.0 kHz"),
                "FRE 2" to listOf("* FRE 2=3550.0 kHz"),
                "FRE 3" to listOf("* FRE 3=3590.0 kHz"),
                "FRE B" to listOf("* FRE B=3570.0 kHz"),
                "BAT" to listOf(
                    "* Int. Bat = 4.1 Volts",
                    "* thresh   = 3.5 Volts",
                    "* Ext. Bat =12.8 Volts",
                    "* Ext. Bat. Ctrl = OFF",
                    "* Transmitter = Enabled",
                ),
                "TMP" to listOf("* Temp: 20.9C"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(
            FakeDeviceTransport(),
            sampleSettings().copy(
                currentTimeCompact = "260410142233",
                startTimeCompact = "260410150000",
                finishTimeCompact = "260410171500",
                daysToRun = 3,
            ),
        )
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            finishTimeCompact = SettingsField("finishTimeCompact", "Finish Time", "260410171500", "260410172000"),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("CLK F 260410172000"), result.commandsSent)
        assertEquals(
            listOf("CLK", "EVT") + com.openardf.serialslinger.protocol.SignalSlingerFirmwareSupport.resolve("1.2.3").fullLoadCommands,
            result.readbackCommandsSent,
        )
        assertTrue("VER" in transport.sentCommands)
        assertEquals("260410172000", result.state.snapshot?.settings?.finishTimeCompact)
        assertEquals(
            SettingVerification(
                fieldKey = SettingKey.FINISH_TIME,
                expectedValue = "260410172000",
                actualValue = "260410172000",
                observedInReadback = true,
                verified = true,
            ),
            result.verifications.single(),
        )
    }

    @Test
    fun submitEditsAllowsSmallClockDriftWhenVerifyingCurrentTime() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "CLK T 260410142233" to listOf("* Time:Fri 10-apr-2026 14:22:33"),
                "CLK" to listOf(
                    "* Time:Fri 10-apr-2026 14:22:35",
                    "* Start:Fri 10-apr-2026 15:00:00",
                    "* Finish:Fri 10-apr-2026 17:15:00",
                    "* Days to run: 3",
                ),
                "EVT" to listOf("* Event:Classic"),
                "VER" to listOf("* SW Ver: 1.2.3 HW Build: 3.5"),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(
            FakeDeviceTransport(),
            sampleSettings().copy(
                currentTimeCompact = "260410142230",
                startTimeCompact = "260410150000",
                finishTimeCompact = "260410171500",
                daysToRun = 3,
            ),
        )
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            currentTimeCompact = SettingsField("currentTimeCompact", "Current Time", "260410142230", "260410142233"),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("CLK T 260410142233"), result.commandsSent)
        assertEquals("260410142235", result.state.snapshot?.settings?.currentTimeCompact)
        assertEquals(
            SettingVerification(
                fieldKey = SettingKey.CURRENT_TIME,
                expectedValue = "260410142233",
                actualValue = "260410142235",
                observedInReadback = true,
                verified = true,
            ),
            result.verifications.single(),
        )
    }

    @Test
    fun submitEditsCanSkipFullReloadVerificationForCurrentTime() {
        val transport = FakeDeviceTransport(
            scriptedResponses = mapOf(
                "CLK T 260410142233" to listOf("* Time:Fri 10-apr-2026 14:22:33"),
                "CLK" to listOf(
                    "* Time:Fri 10-apr-2026 14:22:34",
                    "* Start:Fri 10-apr-2026 15:00:00",
                    "* Finish:Fri 10-apr-2026 17:15:00",
                    "* Days to run: 3",
                ),
                "EVT" to listOf(
                    "* Event:Classic",
                    "* Not scheduled",
                ),
            ),
        )

        val connected = DeviceSessionController.connectAndLoad(
            FakeDeviceTransport(),
            sampleSettings().copy(
                currentTimeCompact = "260410142230",
                startTimeCompact = "260410150000",
                finishTimeCompact = "260410171500",
                daysToRun = 3,
            ),
        )
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            currentTimeCompact = SettingsField("currentTimeCompact", "Current Time", "260410142230", "260410142233"),
        )

        val result = DeviceSessionController.submitEdits(
            connected.state,
            editable,
            transport,
            allowFullReloadVerification = false,
        )

        assertEquals(listOf("CLK T 260410142233"), result.commandsSent)
        assertEquals(listOf("CLK", "EVT"), result.readbackCommandsSent)
        assertFalse("VER" in transport.sentCommands)
        assertEquals("260410142234", result.state.snapshot?.settings?.currentTimeCompact)
    }

    @Test
    fun submitEditsReportsUnobservedVerificationWhenDeviceDoesNotAnswer() {
        val transport = FakeDeviceTransport()
        val connected = DeviceSessionController.connectAndLoad(
            FakeDeviceTransport(),
            sampleSettings().copy(
                startTimeCompact = "260427073000",
                finishTimeCompact = "260427080000",
                daysToRun = 1,
            ),
        )
        val editable = EditableDeviceSettings.fromDeviceSettings(assertNotNull(connected.state.snapshot).settings).copy(
            startTimeCompact = SettingsField("startTimeCompact", "Start Time", "260427073000", "260429180000"),
            finishTimeCompact = SettingsField("finishTimeCompact", "Finish Time", "260427080000", "260429183000"),
        )

        val result = DeviceSessionController.submitEdits(connected.state, editable, transport)

        assertEquals(listOf("CLK S 260429180000", "CLK F 260429183000"), result.commandsSent)
        assertEquals(0, result.linesReceived.size)
        assertEquals(0, result.readbackLinesReceived.size)
        assertEquals(
            listOf(
                SettingVerification(
                    fieldKey = SettingKey.START_TIME,
                    expectedValue = "260429180000",
                    actualValue = null,
                    observedInReadback = false,
                    verified = false,
                ),
                SettingVerification(
                    fieldKey = SettingKey.FINISH_TIME,
                    expectedValue = "260429183000",
                    actualValue = null,
                    observedInReadback = false,
                    verified = false,
                ),
            ),
            result.verifications,
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
