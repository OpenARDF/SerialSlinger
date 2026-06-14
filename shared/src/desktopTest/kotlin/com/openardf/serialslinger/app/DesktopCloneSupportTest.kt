package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.DeviceCapabilities
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.WritePlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopCloneSupportTest {
    @Test
    fun cloneDoesNotCopyPerDeviceIdentityFields() {
        val target = sampleSettings().copy(
            foxRole = FoxRole.FOXORING_2,
            arduconFoxRoleCode = 2,
            patternText = "TARGET",
            dtmfPassword = "1357",
            pttResetSetting = 0,
        )
        val template = sampleSettings().copy(
            stationId = "TEMPLATE",
            foxRole = FoxRole.CLASSIC_1,
            arduconFoxRoleCode = 1,
            patternText = "MOE",
            dtmfPassword = "2468",
            pttResetSetting = 1,
        )

        val editable = DesktopCloneSupport.buildEditableSettings(
            targetBaseSettings = target,
            templateSettings = template,
            capabilities = DeviceCapabilities(
                supportsFoxRoleEditing = true,
                supportsPatternEditing = true,
                supportsDtmfPasswordEditing = true,
                supportsPttResetEditing = true,
                supportsScheduling = true,
                supportsFrequencyProfiles = true,
            ),
        )
        val clonedSettings = editable.toValidatedDeviceSettings()

        assertEquals("TEMPLATE", clonedSettings.stationId)
        assertEquals(target.foxRole, clonedSettings.foxRole)
        assertEquals(target.arduconFoxRoleCode, clonedSettings.arduconFoxRoleCode)
        assertEquals(target.patternText, clonedSettings.patternText)
        assertEquals(target.dtmfPassword, clonedSettings.dtmfPassword)
        assertEquals(target.pttResetSetting, clonedSettings.pttResetSetting)

        val changedKeys = WritePlanner.create(target, clonedSettings).changes.map { it.fieldKey }
        assertFalse(SettingKey.FOX_ROLE in changedKeys)
        assertFalse(SettingKey.ARDUCON_FOX_ROLE in changedKeys)
        assertFalse(SettingKey.PATTERN_TEXT in changedKeys)
        assertFalse(SettingKey.DTMF_PASSWORD in changedKeys)
        assertFalse(SettingKey.PTT_RESET_SETTING in changedKeys)
    }

    @Test
    fun comparedFieldsDoNotListPerDeviceIdentityFields() {
        val fields = DesktopCloneSupport.comparedFieldKeys(
            capabilities = DeviceCapabilities(
                supportsFoxRoleEditing = true,
                supportsPatternEditing = true,
                supportsDtmfPasswordEditing = true,
                supportsPttResetEditing = true,
                supportsScheduling = true,
                supportsFrequencyProfiles = true,
            ),
            templateSettings = sampleSettings(),
        )

        assertFalse(SettingKey.FOX_ROLE in fields)
        assertFalse(SettingKey.ARDUCON_FOX_ROLE in fields)
        assertFalse(SettingKey.PATTERN_TEXT in fields)
        assertFalse(SettingKey.DTMF_PASSWORD in fields)
        assertFalse(SettingKey.PTT_RESET_SETTING in fields)
    }

    private fun sampleSettings(): DeviceSettings {
        return DeviceSettings(
            stationId = "VE3RXH",
            eventType = EventType.CLASSIC,
            foxRole = FoxRole.CLASSIC_5,
            arduconFoxRoleCode = 5,
            patternText = "MO5",
            idCodeSpeedWpm = 20,
            patternCodeSpeedWpm = 8,
            currentTimeCompact = "260430171643",
            startTimeCompact = "260430143000",
            finishTimeCompact = "260430203000",
            daysToRun = 4,
            defaultFrequencyHz = 3_520_000L,
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = 3_560_000L,
            beaconFrequencyHz = 3_600_000L,
            lowBatteryThresholdVolts = 3.8,
            externalBatteryControlMode = null,
            transmissionsEnabled = true,
            dtmfPassword = "1111",
            amToneFrequency = 3,
            pttResetSetting = 1,
        )
    }
}
