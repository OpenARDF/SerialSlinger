package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrequencySupportTest {
    @Test
    fun parsesFrequenciesInHzKhzAndMhz() {
        assertEquals(3_520_000L, FrequencySupport.parseFrequencyHz("3520000"))
        assertEquals(3_521_000L, FrequencySupport.parseFrequencyHz("3521"))
        assertEquals(3_521_000L, FrequencySupport.parseFrequencyHz("3.521"))
        assertEquals(3_520_000L, FrequencySupport.parseFrequencyHz("3520 kHz"))
        assertEquals(3_520_000L, FrequencySupport.parseFrequencyHz("3.520 MHz"))
        assertEquals(3_520_000L, FrequencySupport.parseFrequencyHz("3.52mhz"))
    }

    @Test
    fun formatsFrequencyValuesInMhzForDisplay() {
        assertEquals("3.520 MHz", FrequencySupport.formatFrequencyMhz(3_520_000L))
        assertEquals("", FrequencySupport.formatFrequencyMhz(null))
    }

    @Test
    fun returnsActiveFrequencySlotsFromLoadedSettings() {
        val settings = DeviceSettings(
            stationId = "NZ0I",
            eventType = EventType.FOXORING,
            foxRole = FoxRole.BEACON,
            patternText = "MOH",
            idCodeSpeedWpm = 20,
            patternCodeSpeedWpm = 8,
            currentTimeCompact = null,
            startTimeCompact = null,
            finishTimeCompact = null,
            daysToRun = 3,
            defaultFrequencyHz = 3_600_000L,
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = null,
            beaconFrequencyHz = 3_600_000L,
            lowBatteryThresholdVolts = 3.8,
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
            transmissionsEnabled = true,
        )

        assertEquals(
            listOf(
                FrequencySlot.DEFAULT,
                FrequencySlot.LOW,
                FrequencySlot.MEDIUM,
                FrequencySlot.BEACON,
            ),
            FrequencySupport.activeFrequencySlots(settings),
        )
    }

    @Test
    fun describesCurrentFrequencyAndActiveMemoryBank() {
        val settings = DeviceSettings(
            stationId = "NZ0I",
            eventType = EventType.CLASSIC,
            foxRole = FoxRole.CLASSIC_1,
            patternText = "MOH",
            idCodeSpeedWpm = 20,
            patternCodeSpeedWpm = 8,
            currentTimeCompact = null,
            startTimeCompact = null,
            finishTimeCompact = null,
            daysToRun = 3,
            defaultFrequencyHz = 3_520_000L,
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = 3_560_000L,
            beaconFrequencyHz = 3_600_000L,
            lowBatteryThresholdVolts = 3.8,
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
            transmissionsEnabled = true,
        )

        val presentation = FrequencySupport.describeFrequencies(settings)

        assertEquals(3_520_000L, presentation.currentFrequencyHz)
        assertEquals(FrequencyBankId.ONE, presentation.currentBankId)
        assertEquals(
            listOf(
                FrequencyBankId.ONE to true,
                FrequencyBankId.TWO to false,
                FrequencyBankId.THREE to false,
                FrequencyBankId.BEACON to false,
            ),
            presentation.banks.map { it.bankId to it.isCurrentBank },
        )
    }

    @Test
    fun leavesCurrentBankUnknownWhenCurrentFrequencyDoesNotMatchABank() {
        val settings = DeviceSettings(
            stationId = "NZ0I",
            eventType = EventType.FOXORING,
            foxRole = FoxRole.FREQUENCY_TEST_BEACON,
            patternText = "MOH",
            idCodeSpeedWpm = 20,
            patternCodeSpeedWpm = 8,
            currentTimeCompact = null,
            startTimeCompact = null,
            finishTimeCompact = null,
            daysToRun = 3,
            defaultFrequencyHz = 3_530_000L,
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = 3_560_000L,
            beaconFrequencyHz = 3_600_000L,
            lowBatteryThresholdVolts = 3.8,
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
            transmissionsEnabled = true,
        )

        val presentation = FrequencySupport.describeFrequencies(settings)

        assertEquals(null, presentation.currentBankId)
        assertEquals(false, presentation.banks.any { it.isCurrentBank })
    }

    @Test
    fun rejectsMalformedFrequencyInput() {
        assertNull(FrequencySupport.parseFrequencyHz("three point five MHz"))
    }
}
