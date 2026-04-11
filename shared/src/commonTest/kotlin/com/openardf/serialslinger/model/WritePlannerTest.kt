package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WritePlannerTest {
    @Test
    fun diffProducesExpectedSettingChanges() {
        val original = sampleSettings()
        val edited = original.copy(
            stationId = "W1FOX",
            daysToRun = 3,
            transmissionsEnabled = false,
        )

        val plan = WritePlanner.create(original, edited)

        assertEquals(
            listOf(
                SettingKey.STATION_ID,
                SettingKey.DAYS_TO_RUN,
                SettingKey.TRANSMISSIONS_ENABLED,
            ),
            plan.changes.map { it.fieldKey },
        )
    }

    @Test
    fun writePlanUsesStableWriteOrder() {
        val original = sampleSettings()
        val edited = original.copy(
            transmissionsEnabled = false,
            foxRole = FoxRole.CLASSIC_2,
            stationId = "W1FOX",
            beaconFrequencyHz = 3_580_000L,
        )

        val plan = WritePlanner.create(original, edited)
        val orders = plan.changes.map { it.writeOrder }

        assertEquals(listOf(10, 30, 150, 180), orders)
    }

    @Test
    fun frequencyChangesRequestVerification() {
        val original = sampleSettings()
        val edited = original.copy(defaultFrequencyHz = 3_560_000L)

        val plan = WritePlanner.create(original, edited)

        assertTrue(plan.changes.single().requiresVerification)
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
