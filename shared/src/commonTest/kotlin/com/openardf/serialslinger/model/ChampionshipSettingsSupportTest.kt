package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChampionshipSettingsSupportTest {
    @Test
    fun buildsSpeedCommandsAndRestoresOriginalEvent() {
        val plan = ChampionshipSettingsSupport.buildCommandPlan(
            settings = sampleSettings(eventType = EventType.SPRINT),
            sortFrequencies = false,
        )

        assertEquals(
            listOf(
                "SPD I 20",
                "EVT C",
                "SPD P 8",
                "EVT S",
            ),
            plan.commands,
        )
    }

    @Test
    fun addsSortedFrequencyAssignmentsWhenRequested() {
        val plan = ChampionshipSettingsSupport.buildCommandPlan(
            settings = sampleSettings(
                lowFrequencyHz = 3_580_000L,
                mediumFrequencyHz = 3_520_000L,
                highFrequencyHz = 3_560_000L,
                beaconFrequencyHz = 3_600_000L,
            ),
            sortFrequencies = true,
        )

        assertEquals(
            listOf(
                "SPD I 20",
                "EVT C",
                "SPD P 8",
                "FRE 1 3520000",
                "FRE 2 3560000",
                "FRE 3 3580000",
                "FRE B 3600000",
                "EVT C",
            ),
            plan.commands,
        )
    }

    @Test
    fun warnsWhenFourChampionshipSpacedFrequenciesAreNotLoaded() {
        assertTrue(
            ChampionshipSettingsSupport.shouldWarnAboutFrequencies(
                sampleSettings(beaconFrequencyHz = null),
            ),
        )
    }

    @Test
    fun warnsWhenFrequenciesAreCloserThanTwentyKhz() {
        assertTrue(
            ChampionshipSettingsSupport.shouldWarnAboutFrequencies(
                sampleSettings(
                    lowFrequencyHz = 3_520_000L,
                    mediumFrequencyHz = 3_535_000L,
                    highFrequencyHz = 3_560_000L,
                    beaconFrequencyHz = 3_600_000L,
                ),
            ),
        )
    }

    @Test
    fun skipsWarningForFourUniqueChampionshipSpacedFrequencies() {
        assertFalse(ChampionshipSettingsSupport.shouldWarnAboutFrequencies(sampleSettings()))
    }

    @Test
    fun disablesWhenKnownChampionshipValuesAreAlreadySetAndSorted() {
        assertFalse(
            ChampionshipSettingsSupport.canOfferChampionshipSettings(
                sampleSettings(idCodeSpeedWpm = 20, patternCodeSpeedWpm = 8),
            ),
        )
    }

    @Test
    fun remainsEnabledWhenFrequenciesStillNeedSorting() {
        assertTrue(
            ChampionshipSettingsSupport.canOfferChampionshipSettings(
                sampleSettings(
                    idCodeSpeedWpm = 20,
                    patternCodeSpeedWpm = 8,
                    lowFrequencyHz = 3_560_000L,
                    mediumFrequencyHz = 3_520_000L,
                ),
            ),
        )
    }

    private fun sampleSettings(
        eventType: EventType = EventType.CLASSIC,
        idCodeSpeedWpm: Int = 12,
        patternCodeSpeedWpm: Int = 10,
        lowFrequencyHz: Long? = 3_520_000L,
        mediumFrequencyHz: Long? = 3_540_000L,
        highFrequencyHz: Long? = 3_560_000L,
        beaconFrequencyHz: Long? = 3_600_000L,
    ): DeviceSettings {
        return DeviceSettings(
            stationId = "W1FOX",
            eventType = eventType,
            foxRole = FoxRole.CLASSIC_1,
            patternText = "MOE",
            idCodeSpeedWpm = idCodeSpeedWpm,
            patternCodeSpeedWpm = patternCodeSpeedWpm,
            currentTimeCompact = "260505120000",
            startTimeCompact = "260505130000",
            finishTimeCompact = "260505150000",
            daysToRun = 1,
            defaultFrequencyHz = lowFrequencyHz ?: 0L,
            lowFrequencyHz = lowFrequencyHz,
            mediumFrequencyHz = mediumFrequencyHz,
            highFrequencyHz = highFrequencyHz,
            beaconFrequencyHz = beaconFrequencyHz,
            lowBatteryThresholdVolts = 3.8,
            externalBatteryControlMode = ExternalBatteryControlMode.OFF,
            transmissionsEnabled = true,
        )
    }
}
