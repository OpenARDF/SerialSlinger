package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloneTemplateEligibilityTest {
    @Test
    fun arduconTemplateDoesNotRequireUnsupportedTemplateFields() {
        assertTrue(
            CloneTemplateEligibility.hasCompleteTimedEventSettings(
                settings = sampleSettings().copy(
                    patternCodeSpeedWpm = 0,
                    lowFrequencyHz = null,
                    mediumFrequencyHz = null,
                    highFrequencyHz = null,
                    beaconFrequencyHz = null,
                ),
                daysRemaining = null,
                productName = "Arducon",
            ),
        )
    }

    @Test
    fun signalSlingerClassicTemplateStillRequiresPatternSpeed() {
        assertFalse(
            CloneTemplateEligibility.hasCompleteTimedEventSettings(
                settings = sampleSettings().copy(
                    patternCodeSpeedWpm = 0,
                ),
                daysRemaining = null,
                productName = "SignalSlinger",
            ),
        )
    }

    @Test
    fun signalSlingerClassicTemplateStillRequiresVisibleFrequencyFields() {
        assertFalse(
            CloneTemplateEligibility.hasCompleteTimedEventSettings(
                settings = sampleSettings().copy(
                    lowFrequencyHz = null,
                    beaconFrequencyHz = null,
                ),
                daysRemaining = null,
                productName = "SignalSlinger",
            ),
        )
    }

    @Test
    fun signalSlingerClassicTemplateAcceptsRequiredFrequencyFields() {
        assertTrue(
            CloneTemplateEligibility.hasCompleteTimedEventSettings(
                settings = sampleSettings().copy(
                    lowFrequencyHz = 3_520_000L,
                    mediumFrequencyHz = null,
                    highFrequencyHz = null,
                    beaconFrequencyHz = 3_600_000L,
                ),
                daysRemaining = null,
                productName = "SignalSlinger",
            ),
        )
    }

    private fun sampleSettings(): DeviceSettings {
        return DeviceSettings(
            stationId = "NZ0I",
            eventType = EventType.CLASSIC,
            foxRole = FoxRole.CLASSIC_1,
            arduconFoxRoleCode = 1,
            patternText = "MOE",
            idCodeSpeedWpm = 20,
            patternCodeSpeedWpm = 20,
            currentTimeCompact = "260616114500",
            startTimeCompact = "260616124500",
            finishTimeCompact = "500616174000",
            daysToRun = 1,
            defaultFrequencyHz = 3_520_000L,
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = null,
            highFrequencyHz = null,
            beaconFrequencyHz = 3_600_000L,
            lowBatteryThresholdVolts = 3.8,
            externalBatteryControlMode = null,
            transmissionsEnabled = true,
            dtmfPassword = "1357",
            amToneFrequency = 6,
            pttResetSetting = 0,
        )
    }
}
