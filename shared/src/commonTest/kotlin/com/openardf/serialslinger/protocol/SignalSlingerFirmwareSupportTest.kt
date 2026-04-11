package com.openardf.serialslinger.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalSlingerFirmwareSupportTest {
    @Test
    fun resolvesModernProfileForCurrentFirmwareWithSuffix() {
        val profile = SignalSlingerFirmwareSupport.resolve("1.2s")

        assertEquals("modern-1.2+", profile.id)
        assertTrue(profile.capabilities.supportsScheduling)
        assertTrue("TMP" in profile.fullLoadCommands)
        assertTrue("FOX" in profile.fullLoadCommands)
        assertFalse("CLK T" in profile.fullLoadCommands)
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

        assertEquals("modern-1.2+", profile.id)
    }
}
