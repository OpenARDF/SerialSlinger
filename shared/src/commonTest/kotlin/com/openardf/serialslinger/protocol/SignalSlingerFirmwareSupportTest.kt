package com.openardf.serialslinger.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalSlingerFirmwareSupportTest {
    @Test
    fun resolvesModernProfileForCurrentFirmwareWithSuffix() {
        val profile = SignalSlingerFirmwareSupport.resolve("1.2s")

        assertEquals("modern-1.2.0", profile.id)
        assertTrue(profile.capabilities.supportsScheduling)
        assertTrue(profile.capabilities.supportsTemperatureReadback)
        assertFalse(profile.capabilities.supportsExtendedTemperatureReadback)
        assertTrue("TMP" in profile.fullLoadCommands)
        assertTrue("FUN" in profile.fullLoadCommands)
        assertTrue("FOX" in profile.fullLoadCommands)
        assertTrue("CLK" in profile.fullLoadCommands)
        assertFalse("CLK T" in profile.fullLoadCommands)
        assertFalse("CLK S" in profile.fullLoadCommands)
        assertFalse("CLK F" in profile.fullLoadCommands)
    }

    @Test
    fun resolvesLegacyProfileForOlderFirmware() {
        val profile = SignalSlingerFirmwareSupport.resolve("1.1")

        assertEquals("legacy-pre-1.2", profile.id)
        assertFalse(profile.capabilities.supportsScheduling)
        assertFalse("TMP" in profile.fullLoadCommands)
        assertFalse("CLK T" in profile.fullLoadCommands)
    }

    @Test
    fun defaultsUnknownVersionToModernProfile() {
        val profile = SignalSlingerFirmwareSupport.resolve(null)

        assertEquals("modern-1.2.0", profile.id)
    }

    @Test
    fun resolvesTmpOnlyTemperatureProfileForFirmware121AndLater() {
        val profile = SignalSlingerFirmwareSupport.resolve("1.2.1")

        assertEquals("modern-1.2.1+", profile.id)
        assertTrue(profile.capabilities.supportsTemperatureReadback)
        assertTrue(profile.capabilities.supportsExtendedTemperatureReadback)
        assertTrue("TMP" in profile.fullLoadCommands)
        assertFalse("FUN" in profile.fullLoadCommands)
    }

    @Test
    fun foxRoleVerificationReadbackIncludesPatternSpeedRefresh() {
        val profile = SignalSlingerFirmwareSupport.resolve("1.2s")

        assertEquals(
            listOf("FOX", "PAT", "SPD P", "FRE"),
            profile.verificationReadbackCommands[com.openardf.serialslinger.model.SettingKey.FOX_ROLE],
        )
    }

    @Test
    fun scheduleVerificationReadbackUsesCombinedClockReport() {
        val profile = SignalSlingerFirmwareSupport.resolve("1.2s")

        assertEquals(
            listOf("CLK", "EVT"),
            profile.verificationReadbackCommands[com.openardf.serialslinger.model.SettingKey.CURRENT_TIME],
        )
        assertEquals(
            listOf("CLK", "EVT"),
            profile.verificationReadbackCommands[com.openardf.serialslinger.model.SettingKey.START_TIME],
        )
        assertEquals(
            listOf("CLK", "EVT"),
            profile.verificationReadbackCommands[com.openardf.serialslinger.model.SettingKey.FINISH_TIME],
        )
        assertEquals(
            listOf("CLK", "EVT"),
            profile.verificationReadbackCommands[com.openardf.serialslinger.model.SettingKey.DAYS_TO_RUN],
        )
    }
}
