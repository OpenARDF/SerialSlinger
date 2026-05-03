package com.openardf.serialslinger.protocol

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirmwareCloneProtocolTest {
    @Test
    fun convertsCompactTimestampToFirmwareEpoch() {
        assertEquals(946_684_800L, FirmwareCloneProtocol.firmwareEpochFromCompact("000101000000"))
        assertEquals(1_767_182_400L, FirmwareCloneProtocol.firmwareEpochFromCompact("251231120000"))
        assertEquals(1_772_323_199L, FirmwareCloneProtocol.firmwareEpochFromCompact("260228235959"))
        assertEquals(1_772_323_200L, FirmwareCloneProtocol.firmwareEpochFromCompact("260301000000"))
    }

    @Test
    fun buildsClassicClonePlanWithFirmwareChecksum() {
        val plan =
            FirmwareCloneProtocol.buildPlan(
                templateSettings = sampleSettings(),
                currentTimeCompact = "260430171643",
            )

        assertEquals(
            listOf(
                "FUN A",
                "CLK T 1777569403",
                "CLK S 1777559400",
                "CLK F 1777581000",
                "CLK D 4",
                "ID VE3RXH",
                "SPD I 20",
                "SPD P 8",
                "EVT C",
                "FRE X 3520000",
                "FRE L 3520000",
                "FRE M 3540000",
                "FRE H 3560000",
                "FRE B 3600000",
                "MAS Q 1055483151",
            ),
            plan.steps.map { it.command },
        )
        assertEquals(1_055_483_151L, plan.checksum)
    }

    @Test
    fun usesFoxoringSpeedSlotForFoxoringEvents() {
        val plan =
            FirmwareCloneProtocol.buildPlan(
                templateSettings = sampleSettings().copy(eventType = EventType.FOXORING),
                currentTimeCompact = "260430171643",
            )

        assertTrue("SPD F 8" in plan.steps.map { it.command })
        assertFalse("SPD P 8" in plan.steps.map { it.command })
        assertTrue("EVT F" in plan.steps.map { it.command })
    }

    @Test
    fun matchesFirmwareReplies() {
        assertTrue(FirmwareCloneProtocol.replyMatches("MAS S", listOf("MAS S")))
        assertTrue(FirmwareCloneProtocol.replyMatches("MAS S", listOf("> MAS PMAS S")))
        assertTrue(FirmwareCloneProtocol.replyMatches("FUN A", listOf("> FUN AFUN A")))
        assertTrue(FirmwareCloneProtocol.replyMatches("CLK T", listOf("CLK T 1777569403")))
        assertTrue(FirmwareCloneProtocol.replyMatches("CLK T", listOf("> CLK T 1777569403CLK T 1777569403")))
        assertTrue(FirmwareCloneProtocol.replyMatches("CLK F", listOf("F")))
        assertTrue(FirmwareCloneProtocol.replyMatches("EVT", listOf("EV")))
        assertTrue(FirmwareCloneProtocol.replyMatches("MAS ACK", listOf("MAS ACK")))
        assertTrue(FirmwareCloneProtocol.replyMatches("MAS ACK", listOf("> MAS Q 1055483151MAS ACK")))
        assertTrue(FirmwareCloneProtocol.replyMatches("MAS ACK", listOf("> MAS ACK")))
        assertFalse(FirmwareCloneProtocol.replyMatches("EVT", listOf("E")))
        assertFalse(FirmwareCloneProtocol.replyMatches("MAS ACK", listOf("MAS NAK")))
    }

    private fun sampleSettings(): DeviceSettings {
        return DeviceSettings(
            stationId = "VE3RXH",
            eventType = EventType.CLASSIC,
            foxRole = FoxRole.CLASSIC_5,
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
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
            transmissionsEnabled = true,
        )
    }
}
