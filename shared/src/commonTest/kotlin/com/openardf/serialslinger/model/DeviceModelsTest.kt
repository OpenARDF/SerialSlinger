package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceModelsTest {
    @Test
    fun settingsFieldIsDirtyOnlyWhenValueChanges() {
        val unchanged = SettingsField(
            key = "stationId",
            label = "Station ID",
            originalValue = "N0CALL",
            editedValue = "N0CALL",
        )
        val changed = SettingsField(
            key = "stationId",
            label = "Station ID",
            originalValue = "N0CALL",
            editedValue = "W1FOX",
        )

        assertFalse(unchanged.isDirty)
        assertTrue(changed.isDirty)
    }

    @Test
    fun invalidFieldBlocksValidatedSettingsConversion() {
        val editable = sampleEditableSettings(
            stationId = SettingsField(
                key = "stationId",
                label = "Station ID",
                originalValue = "N0CALL",
                editedValue = "",
                validationState = ValidationState.INVALID,
                errorMessage = "Station ID cannot be blank.",
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            editable.toValidatedDeviceSettings()
        }

        assertTrue(error.message!!.contains("stationId"))
    }

    @Test
    fun editableSettingsReportDirtyState() {
        val editable = sampleEditableSettings(
            daysToRun = SettingsField(
                key = "daysToRun",
                label = "Days To Run",
                originalValue = 1,
                editedValue = 3,
            ),
        )

        assertTrue(editable.hasDirtyFields)
    }

    @Test
    fun validatedSettingsNormalizeDisabledTransmissionsToChargeOnlyMode() {
        val editable = sampleEditableSettings(
            externalBatteryControlMode = SettingsField(
                "externalBatteryControlMode",
                "External Battery Control",
                ExternalBatteryControlMode.OFF,
                ExternalBatteryControlMode.OFF,
            ),
            transmissionsEnabled = SettingsField("transmissionsEnabled", "RF Transmissions", true, false),
        )

        val settings = editable.toValidatedDeviceSettings()

        assertEquals(ExternalBatteryControlMode.CHARGE_ONLY, settings.externalBatteryControlMode)
        assertFalse(settings.transmissionsEnabled)
    }

    @Test
    fun unsupportedFieldsAreFilteredOutByCapabilities() {
        val editable = sampleEditableSettings()
        val capabilities = DeviceCapabilities(
            supportsPatternEditing = false,
            supportsExternalBatteryControl = false,
            supportsFrequencyProfiles = false,
            supportsScheduling = false,
        )

        val writable = editable.writableVisibleFields(capabilities).map { it.key }

        assertFalse("patternText" in writable)
        assertFalse("lowFrequencyHz" in writable)
        assertFalse("externalBatteryControlMode" in writable)
        assertFalse("daysToRun" in writable)
        assertTrue("stationId" in writable)
    }

    @Test
    fun editableSettingsUseFrequencyLabelsThatMatchFirmwareBanks() {
        val editable = EditableDeviceSettings.fromDeviceSettings(sampleSettings())

        assertEquals("Fox Role (FOX)", editable.foxRole.label)
        assertEquals("Current Time", editable.currentTimeCompact.label)
        assertEquals("Start Time", editable.startTimeCompact.label)
        assertEquals("Finish Time", editable.finishTimeCompact.label)
        assertEquals("Current Frequency (FRE)", editable.defaultFrequencyHz.label)
        assertEquals("Frequency 1 (FRE 1)", editable.lowFrequencyHz.label)
        assertEquals("Frequency 2 (FRE 2)", editable.mediumFrequencyHz.label)
        assertEquals("Frequency 3 (FRE 3)", editable.highFrequencyHz.label)
        assertEquals("Frequency B (FRE B)", editable.beaconFrequencyHz.label)
    }

    @Test
    fun foxoringRolesUseFrequencyBankStyleDisplayNames() {
        assertEquals("FREQ 1", FoxRole.FOXORING_1.toString())
        assertEquals("FREQ 2", FoxRole.FOXORING_2.toString())
        assertEquals("FREQ 3", FoxRole.FOXORING_3.toString())
    }

    @Test
    fun hasWallClockTimeSetIsFalseWhenCurrentTimeIsMissing() {
        val snapshot = DeviceSnapshot(
            settings = DeviceSettings.empty(),
            capabilities = DeviceCapabilities(supportsScheduling = true),
        )

        assertFalse(snapshot.hasWallClockTimeSet())
        assertFalse(snapshot.settings.hasWallClockTimeSet())
    }

    @Test
    fun hasWallClockTimeSetIsTrueWhenCurrentTimeIsPresent() {
        val settings = DeviceSettings.empty().copy(currentTimeCompact = "260429101530")
        val snapshot = DeviceSnapshot(
            settings = settings,
            capabilities = DeviceCapabilities(supportsScheduling = true),
        )

        assertTrue(snapshot.hasWallClockTimeSet())
        assertTrue(settings.hasWallClockTimeSet())
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
            defaultFrequencyHz = 3_520_000L,
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = 3_560_000L,
            beaconFrequencyHz = 3_600_000L,
            lowBatteryThresholdVolts = 3.5,
            externalBatteryControlMode = ExternalBatteryControlMode.OFF,
            transmissionsEnabled = true,
        )
    }

    private fun sampleEditableSettings(
        stationId: SettingsField<String> = SettingsField("stationId", "Station ID", "N0CALL", "N0CALL"),
        eventType: SettingsField<EventType> = SettingsField("eventType", "Event Type", EventType.CLASSIC, EventType.CLASSIC),
        foxRole: SettingsField<FoxRole?> = SettingsField("foxRole", "Fox Role (FOX)", FoxRole.CLASSIC_1, FoxRole.CLASSIC_1),
        patternText: SettingsField<String?> = SettingsField("patternText", "Pattern Text", "TEST", "TEST"),
        idCodeSpeedWpm: SettingsField<Int> = SettingsField("idCodeSpeedWpm", "ID Speed", 8, 8),
        patternCodeSpeedWpm: SettingsField<Int> = SettingsField("patternCodeSpeedWpm", "Pattern Speed", 12, 12),
        currentTimeCompact: SettingsField<String?> = SettingsField("currentTimeCompact", "Current Time", null, null),
        startTimeCompact: SettingsField<String?> = SettingsField("startTimeCompact", "Start Time", null, null),
        finishTimeCompact: SettingsField<String?> = SettingsField("finishTimeCompact", "Finish Time", null, null),
        daysToRun: SettingsField<Int> = SettingsField("daysToRun", "Days To Run", 1, 1),
        defaultFrequencyHz: SettingsField<Long> = SettingsField("defaultFrequencyHz", "Default Frequency", 3_550_000L, 3_550_000L),
        lowFrequencyHz: SettingsField<Long?> = SettingsField("lowFrequencyHz", "Low Frequency", 3_510_000L, 3_510_000L),
        mediumFrequencyHz: SettingsField<Long?> = SettingsField("mediumFrequencyHz", "Medium Frequency", 3_550_000L, 3_550_000L),
        highFrequencyHz: SettingsField<Long?> = SettingsField("highFrequencyHz", "High Frequency", 3_590_000L, 3_590_000L),
        beaconFrequencyHz: SettingsField<Long?> = SettingsField("beaconFrequencyHz", "Beacon Frequency", 3_570_000L, 3_570_000L),
        lowBatteryThresholdVolts: SettingsField<Double?> = SettingsField("lowBatteryThresholdVolts", "Low Battery Threshold", 3.5, 3.5),
        externalBatteryControlMode: SettingsField<ExternalBatteryControlMode?> = SettingsField("externalBatteryControlMode", "External Battery Control", ExternalBatteryControlMode.OFF, ExternalBatteryControlMode.OFF),
        transmissionsEnabled: SettingsField<Boolean> = SettingsField("transmissionsEnabled", "RF Transmissions", true, true),
    ): EditableDeviceSettings {
        return EditableDeviceSettings(
            stationId = stationId,
            eventType = eventType,
            foxRole = foxRole,
            patternText = patternText,
            idCodeSpeedWpm = idCodeSpeedWpm,
            patternCodeSpeedWpm = patternCodeSpeedWpm,
            currentTimeCompact = currentTimeCompact,
            startTimeCompact = startTimeCompact,
            finishTimeCompact = finishTimeCompact,
            daysToRun = daysToRun,
            defaultFrequencyHz = defaultFrequencyHz,
            lowFrequencyHz = lowFrequencyHz,
            mediumFrequencyHz = mediumFrequencyHz,
            highFrequencyHz = highFrequencyHz,
            beaconFrequencyHz = beaconFrequencyHz,
            lowBatteryThresholdVolts = lowBatteryThresholdVolts,
            externalBatteryControlMode = externalBatteryControlMode,
            transmissionsEnabled = transmissionsEnabled,
        )
    }
}
