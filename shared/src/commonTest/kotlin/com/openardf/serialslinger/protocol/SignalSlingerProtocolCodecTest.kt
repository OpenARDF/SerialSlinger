package com.openardf.serialslinger.protocol

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.DeviceInfo
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.WritePlanner
import com.openardf.serialslinger.platform.PlatformDateTimeFields
import com.openardf.serialslinger.platform.platformEpochSecondsFromLocalDateTimeFields
import com.openardf.serialslinger.platform.platformUtcDateTimeFields
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SignalSlingerProtocolCodecTest {
    @Test
    fun parsesVersionReplyIntoDeviceInfoPatch() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* SW Ver: 1.2.3 HW Build: 3.5")

        assertNotNull(update)
        assertEquals("1.2.3", update.deviceInfoPatch?.softwareVersion)
        assertEquals("3.5", update.deviceInfoPatch?.hardwareBuild)
    }

    @Test
    fun parsesBootloaderVersionReplyIntoDeviceInfoPatch() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* Bootloader: BL0.13 protocol 1")

        assertNotNull(update)
        assertEquals("BL0.13", update.deviceInfoPatch?.bootloaderVersion)
        assertEquals(1, update.deviceInfoPatch?.bootloaderProtocolVersion)
    }

    @Test
    fun parsesAppInfoBootloaderReplyIntoDeviceInfoPatch() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* INF bl=BL0.13 proto=1")

        assertNotNull(update)
        assertEquals("BL0.13", update.deviceInfoPatch?.bootloaderVersion)
        assertEquals(1, update.deviceInfoPatch?.bootloaderProtocolVersion)
    }

    @Test
    fun parsesAppInfoVersionReplyIntoDeviceInfoPatch() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* INF sw=2.0.2 hw=3.5 app=0x2000 baud=115200")

        assertNotNull(update)
        assertEquals("2.0.2", update.deviceInfoPatch?.softwareVersion)
        assertEquals("3.5", update.deviceInfoPatch?.hardwareBuild)
        assertEquals(0x2000, update.deviceInfoPatch?.appStartAddress)
        assertEquals(115_200, update.deviceInfoPatch?.updateBaud)
    }

    @Test
    fun parsesArduconAppInfoReplyIntoDeviceInfoPatch() {
        val update = SignalSlingerProtocolCodec.parseReportLine(
            "* INF product=Arducon update=UPD sw=2.0.0 hw=ATmega328P-16 app=0x0000 appbaud=57600 baud=115200 bl=unknown proto=stk500v1",
        )

        assertNotNull(update)
        assertEquals("Arducon", update.deviceInfoPatch?.productName)
        assertEquals("UPD", update.deviceInfoPatch?.appUpdateCommand)
        assertEquals("2.0.0", update.deviceInfoPatch?.softwareVersion)
        assertEquals("ATmega328P-16", update.deviceInfoPatch?.hardwareBuild)
        assertEquals(0x0000, update.deviceInfoPatch?.appStartAddress)
        assertEquals(57_600, update.deviceInfoPatch?.appBaud)
        assertEquals(115_200, update.deviceInfoPatch?.updateBaud)
        assertEquals(null, update.deviceInfoPatch?.bootloaderVersion)
        assertEquals(null, update.deviceInfoPatch?.bootloaderProtocolVersion)
        assertEquals("stk500v1", update.deviceInfoPatch?.bootloaderProtocol)
    }

    @Test
    fun treatsUnknownBootloaderVersionReplyAsUnavailable() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* Bootloader: unknown protocol unknown")

        assertNotNull(update)
        assertEquals(null, update.deviceInfoPatch?.bootloaderVersion)
        assertEquals(null, update.deviceInfoPatch?.bootloaderProtocolVersion)
    }

    @Test
    fun parsesStationIdReplyAndTrimsStoredLeadingSpace() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* ID: W1FOX")

        assertNotNull(update)
        assertEquals("W1FOX", update.settingsPatch?.stationId)
    }

    @Test
    fun parsesPatternTextReply() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* PAT:TEST")

        assertNotNull(update)
        assertEquals("TEST", update.settingsPatch?.patternText)
    }

    @Test
    fun parsesFoxoringPatternSpeedReply() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* FOX-O SPD:8 WPM")

        assertNotNull(update)
        assertEquals(8, update.settingsPatch?.patternCodeSpeedWpm)
    }

    @Test
    fun parsesClockRepliesIntoCompactTimestamps() {
        val currentUpdate = SignalSlingerProtocolCodec.parseReportLine("* Time:Fri 10-apr-2026 14:22:33")
        val startUpdate = SignalSlingerProtocolCodec.parseReportLine("* Start:Fri 10-apr-2026 15:00:00")
        val finishUpdate = SignalSlingerProtocolCodec.parseReportLine("* Finish:Fri 10-apr-2026 17:00:00")

        assertEquals("260410142233", currentUpdate?.settingsPatch?.currentTimeCompact)
        assertEquals("260410150000", startUpdate?.settingsPatch?.startTimeCompact)
        assertEquals("260410170000", finishUpdate?.settingsPatch?.finishTimeCompact)
    }

    @Test
    fun treatsZeroEpochScheduleRepliesAsNotSet() {
        val startUpdate = SignalSlingerProtocolCodec.parseReportLine("* Start:Thu 01-jan-1970 00:00:00")
        val finishUpdate = SignalSlingerProtocolCodec.parseReportLine("* Finish:Thu 01-jan-1970 00:00:00")

        assertEquals(null, startUpdate?.settingsPatch?.startTimeCompact)
        assertEquals(null, finishUpdate?.settingsPatch?.finishTimeCompact)
    }

    @Test
    fun notSetClockRepliesClearExistingScheduleTimes() {
        val settings = sampleSettings().copy(
            currentTimeCompact = "260430073600",
            startTimeCompact = "260430143000",
            finishTimeCompact = "260430203000",
        )
        val currentUpdate = SignalSlingerProtocolCodec.parseReportLine("* Time:not set")
        val startUpdate = SignalSlingerProtocolCodec.parseReportLine("* Start:not set")
        val finishUpdate = SignalSlingerProtocolCodec.parseReportLine("* Finish:not set")

        assertNull(currentUpdate?.settingsPatch?.applyTo(settings)?.currentTimeCompact)
        assertNull(startUpdate?.settingsPatch?.applyTo(settings)?.startTimeCompact)
        assertNull(finishUpdate?.settingsPatch?.applyTo(settings)?.finishTimeCompact)
    }

    @Test
    fun parsesArduconEpochClockRepliesIntoCompactTimestamps() {
        val currentUpdate = SignalSlingerProtocolCodec.parseReportLine("Epoch:1779988193")
        val startUpdate = SignalSlingerProtocolCodec.parseReportLine("Start:1779988200")
        val finishUpdate = SignalSlingerProtocolCodec.parseReportLine("Finish:0")
        val tooEarlyUpdate = SignalSlingerProtocolCodec.parseReportLine("Epoch:1609459199")
        val erasedStartUpdate = SignalSlingerProtocolCodec.parseReportLine("Start:4294967295")

        val currentPatch = assertNotNull(currentUpdate?.settingsPatch)
        val startPatch = assertNotNull(startUpdate?.settingsPatch)
        val currentCompact = assertNotNull(currentPatch.currentTimeCompact)
        val startCompact = assertNotNull(startPatch.startTimeCompact)
        assertEquals(12, currentCompact.length)
        assertEquals(12, startCompact.length)
        assertEquals("26", currentCompact.take(2))
        assertEquals(true, currentPatch.currentTimeObserved)
        assertEquals(true, startPatch.startTimeObserved)
        assertEquals(null, finishUpdate?.settingsPatch?.finishTimeCompact)
        assertEquals(true, finishUpdate?.settingsPatch?.finishTimeObserved)
        assertEquals(null, tooEarlyUpdate?.settingsPatch?.currentTimeCompact)
        assertEquals(true, tooEarlyUpdate?.settingsPatch?.currentTimeObserved)
        assertEquals(null, erasedStartUpdate?.settingsPatch?.startTimeCompact)
        assertEquals(true, erasedStartUpdate?.settingsPatch?.startTimeObserved)
    }

    @Test
    fun parsesObservedArduconReadbackLines() {
        val stationUpdate = SignalSlingerProtocolCodec.parseReportLine("ID: NZ0I")
        val idSpeedUpdate = SignalSlingerProtocolCodec.parseReportLine("ID: 20 wpm")
        val daysToRunUpdate = SignalSlingerProtocolCodec.parseReportLine("CLK D 2")
        val maximumDaysToRunUpdate = SignalSlingerProtocolCodec.parseReportLine("CLK D 100")
        val temperatureUpdate = SignalSlingerProtocolCodec.parseReportLine("T=34C")

        assertEquals("NZ0I", stationUpdate?.settingsPatch?.stationId)
        assertEquals(20, idSpeedUpdate?.settingsPatch?.idCodeSpeedWpm)
        assertEquals(2, daysToRunUpdate?.settingsPatch?.daysToRun)
        assertEquals(100, maximumDaysToRunUpdate?.settingsPatch?.daysToRun)
        assertEquals(34.0, temperatureUpdate?.deviceStatusPatch?.temperatureC)
    }

    @Test
    fun parsesArduconUtilityTemperatureReportIntoDeviceStatus() {
        val currentUpdate = SignalSlingerProtocolCodec.parseReportLine("T=41.7C")
        val maximumUpdate = SignalSlingerProtocolCodec.parseReportLine("Max=41.7C")
        val maximumEverUpdate = SignalSlingerProtocolCodec.parseReportLine("Max Ever=42.5C")
        val maximumEverResetUpdate = SignalSlingerProtocolCodec.parseReportLine("Max Ever Reset=41.7C")
        val thresholdUpdate = SignalSlingerProtocolCodec.parseReportLine("Thermal Shutdown=50C")
        val spacedThresholdUpdate = SignalSlingerProtocolCodec.parseReportLine("Thermal Shutdown= 50C")

        assertEquals(41.7, currentUpdate?.deviceStatusPatch?.temperatureC)
        assertEquals(41.7, maximumUpdate?.deviceStatusPatch?.maximumTemperatureC)
        assertEquals(42.5, maximumEverUpdate?.deviceStatusPatch?.maximumEverTemperatureC)
        assertEquals(41.7, maximumEverResetUpdate?.deviceStatusPatch?.maximumEverTemperatureC)
        assertEquals(50.0, thresholdUpdate?.deviceStatusPatch?.thermalShutdownThresholdC)
        assertEquals(50.0, spacedThresholdUpdate?.deviceStatusPatch?.thermalShutdownThresholdC)
    }

    @Test
    fun parsesTemperatureReplyIntoDeviceStatus() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* Temp: 42.5C")

        assertEquals(42.5, update?.deviceStatusPatch?.temperatureC)
    }

    @Test
    fun parsesReportedMinimumAndMaximumTemperatureRepliesIntoDeviceStatus() {
        val currentUpdate = SignalSlingerProtocolCodec.parseReportLine("*   Cur Temp: 42.5C")
        val minimumUpdate = SignalSlingerProtocolCodec.parseReportLine("*   Min Temp: 38.5C")
        val maximumUpdate = SignalSlingerProtocolCodec.parseReportLine("*   Max Temp: 47.0C")

        assertEquals(42.5, currentUpdate?.deviceStatusPatch?.temperatureC)
        assertEquals(38.5, minimumUpdate?.deviceStatusPatch?.minimumTemperatureC)
        assertEquals(47.0, maximumUpdate?.deviceStatusPatch?.maximumTemperatureC)
    }

    @Test
    fun parsesModernTmpTemperatureReportIntoDeviceStatus() {
        val maximumEverUpdate = SignalSlingerProtocolCodec.parseReportLine("* Max Ever: 48.0C")
        val maximumUpdate = SignalSlingerProtocolCodec.parseReportLine("* Max Temp: 42.5C")
        val currentUpdate = SignalSlingerProtocolCodec.parseReportLine("* Temp: 40.0C")
        val minimumUpdate = SignalSlingerProtocolCodec.parseReportLine("* Min Temp: 35.5C")
        val thresholdUpdate = SignalSlingerProtocolCodec.parseReportLine("* Thermal shutdown threshold: 50C")

        assertEquals(48.0, maximumEverUpdate?.deviceStatusPatch?.maximumEverTemperatureC)
        assertEquals(42.5, maximumUpdate?.deviceStatusPatch?.maximumTemperatureC)
        assertEquals(40.0, currentUpdate?.deviceStatusPatch?.temperatureC)
        assertEquals(35.5, minimumUpdate?.deviceStatusPatch?.minimumTemperatureC)
        assertEquals(50.0, thresholdUpdate?.deviceStatusPatch?.thermalShutdownThresholdC)
    }

    @Test
    fun parsesFoxReplyIntoFoxRole() {
        val update = SignalSlingerProtocolCodec.parseReportLine("""* Fox:Classic Fox 1 "MOE"""")

        assertNotNull(update)
        assertEquals(FoxRole.CLASSIC_1, update.settingsPatch?.foxRole)
    }

    @Test
    fun parsesFrequencyReplyUsingFirmwareKhzFormat() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* FRE B=3570.0 kHz")

        assertNotNull(update)
        assertEquals(3_570_000L, update.settingsPatch?.beaconFrequencyHz)
    }

    @Test
    fun parsesFrequencyReplyUsingFirmwareMhzFormat() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* FRE 1=3.520 MHz")

        assertNotNull(update)
        assertEquals(3_520_000L, update.settingsPatch?.lowFrequencyHz)
    }

    @Test
    fun parsesBatteryAndTransmitterReplies() {
        val thresholdUpdate = SignalSlingerProtocolCodec.parseReportLine("* thresh   = 3.5 Volts")
        val batteryControlUpdate = SignalSlingerProtocolCodec.parseReportLine("* Ext. Bat. Ctrl = ON")
        val transmitterUpdate = SignalSlingerProtocolCodec.parseReportLine("* Transmitter = Disabled")

        assertEquals(3.5, thresholdUpdate?.settingsPatch?.lowBatteryThresholdVolts)
        assertEquals(
            ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
            batteryControlUpdate?.settingsPatch?.externalBatteryControlMode,
        )
        assertEquals(false, transmitterUpdate?.settingsPatch?.transmissionsEnabled)
    }

    @Test
    fun appliesParsedSettingsPatchToBaseSettings() {
        val base = sampleSettings()
        val update = SignalSlingerProtocolCodec.parseReportLine("* Days to run: 3")

        val patched = update?.settingsPatch?.applyTo(base)

        assertNotNull(patched)
        assertEquals(3, patched.daysToRun)
        assertEquals(base.stationId, patched.stationId)
    }

    @Test
    fun parsesDaysRemainingIntoDeviceStatusPatch() {
        val update = SignalSlingerProtocolCodec.parseReportLine("* Days remaining: 1")

        assertNotNull(update)
        assertEquals(1, update.deviceStatusPatch?.daysRemaining)
    }

    @Test
    fun parsesDeviceReportedEventStateLines() {
        val enabledUpdate = SignalSlingerProtocolCodec.parseReportLine("* Running forever.")
        val disabledUpdate = SignalSlingerProtocolCodec.parseReportLine("* Not scheduled")
        val eventStartDisabledUpdate = SignalSlingerProtocolCodec.parseReportLine("* Event start disabled (Start = Finish)")
        val configErrorUpdate = SignalSlingerProtocolCodec.parseReportLine("* Config err 1")
        val interruptedUpdate = SignalSlingerProtocolCodec.parseReportLine("* Event interrupted!")
        val startsInUpdate = SignalSlingerProtocolCodec.parseReportLine("* Starts in: 23 hours 4 minutes 5 seconds")
        val lastsUpdate = SignalSlingerProtocolCodec.parseReportLine("* Lasts: 2 hours 0 minutes 0 seconds")
        val inProgressUpdate = SignalSlingerProtocolCodec.parseReportLine("* In progress")

        assertEquals(true, enabledUpdate?.deviceStatusPatch?.eventEnabled)
        assertEquals("Running forever.", enabledUpdate?.deviceStatusPatch?.eventStateSummary)
        assertEquals(false, disabledUpdate?.deviceStatusPatch?.eventEnabled)
        assertEquals("Not scheduled", disabledUpdate?.deviceStatusPatch?.eventStateSummary)
        assertEquals(false, eventStartDisabledUpdate?.deviceStatusPatch?.eventEnabled)
        assertEquals("Event start disabled (Start = Finish)", eventStartDisabledUpdate?.deviceStatusPatch?.eventStateSummary)
        assertEquals(false, configErrorUpdate?.deviceStatusPatch?.eventEnabled)
        assertEquals("Config err 1", configErrorUpdate?.deviceStatusPatch?.eventStateSummary)
        assertEquals(false, interruptedUpdate?.deviceStatusPatch?.eventEnabled)
        assertEquals("Event interrupted!", interruptedUpdate?.deviceStatusPatch?.eventStateSummary)
        assertEquals("23 hours 4 minutes 5 seconds", startsInUpdate?.deviceStatusPatch?.eventStartsInSummary)
        assertEquals("2 hours 0 minutes 0 seconds", lastsUpdate?.deviceStatusPatch?.eventDurationSummary)
        assertEquals("In Progress", inProgressUpdate?.deviceStatusPatch?.eventStartsInSummary)
    }

    @Test
    fun encodesMinimalWriteCommandsInStableOrder() {
        val original = sampleSettings()
        val edited = original.copy(
            stationId = "W1FOX",
            foxRole = FoxRole.CLASSIC_2,
            daysToRun = 3,
            beaconFrequencyHz = 3_580_000L,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(
            listOf(
                "ID W1FOX",
                "FOX 2",
                "CLK D 3",
                "FRE B 3580000",
            ),
            commands,
        )
    }

    @Test
    fun encodesClockWriteCommands() {
        val original = sampleSettings()
        val edited = original.copy(
            currentTimeCompact = "260410142233",
            startTimeCompact = "260410150000",
            finishTimeCompact = "260410170000",
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(
            listOf(
                "CLK T 260410142233",
                "CLK S 260410150000",
                "CLK F 260410170000",
            ),
            commands,
        )
    }

    @Test
    fun encodesArduconClockWriteCommandsAsUtcCompactTimestamps() {
        val original = sampleSettings()
        val edited = original.copy(
            currentTimeCompact = "260410142233",
            startTimeCompact = "260410150000",
            finishTimeCompact = "260410170000",
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(
            writePlan = writePlan,
            editedSettings = edited,
            deviceInfo = DeviceInfo(productName = "Arducon"),
        )

        assertEquals(
            listOf(
                "CLK T ${utcCompactForLocal(2026, 4, 10, 14, 22, 33)}",
                "CLK S ${utcCompactForLocal(2026, 4, 10, 15, 0, 0)}",
                "CLK F ${utcCompactForLocal(2026, 4, 10, 17, 0, 0)}",
            ),
            commands,
        )
    }

    @Test
    fun encodesArduconIdSpeedWithSetCommand() {
        val original = sampleSettings().copy(idCodeSpeedWpm = 15)
        val edited = original.copy(idCodeSpeedWpm = 20)

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(
            writePlan = writePlan,
            editedSettings = edited,
            deviceInfo = DeviceInfo(productName = "Arducon"),
        )

        assertEquals(listOf("SET S 20"), commands)
    }

    @Test
    fun parsesArduconFoxReadback() {
        val update = SignalSlingerProtocolCodec.parseReportLine("Fox=1")

        assertNotNull(update)
        assertEquals(1, update.settingsPatch?.arduconFoxRoleCode)
        assertEquals(EventType.CLASSIC, update.settingsPatch?.eventType)
        assertEquals("MOE", update.settingsPatch?.patternText)
    }

    @Test
    fun parsesArduconVoltageAndTemperatureFoxReadback() {
        val update = SignalSlingerProtocolCodec.parseReportLine("Fox=19")

        assertNotNull(update)
        assertEquals(19, update.settingsPatch?.arduconFoxRoleCode)
        assertEquals(EventType.NONE, update.settingsPatch?.eventType)
        assertEquals("Volts / Degrees C", update.settingsPatch?.patternText)
    }

    @Test
    fun encodesArduconFoxRoleCommandsWithTwoDigitDesignator() {
        val original = sampleSettings().copy(arduconFoxRoleCode = 1)
        val edited = original.copy(arduconFoxRoleCode = 6)

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(
            writePlan = writePlan,
            editedSettings = edited,
            deviceInfo = DeviceInfo(productName = "Arducon"),
        )

        assertEquals(listOf("FOX 06"), commands)
    }

    @Test
    fun encodesForcedArduconVoltageAndTemperatureFoxRoleCommand() {
        val original = sampleSettings().copy(arduconFoxRoleCode = 19)
        val edited = original.copy(arduconFoxRoleCode = 19)

        val writePlan = WritePlanner.create(original, edited, forceWriteKeys = setOf(SettingKey.ARDUCON_FOX_ROLE))
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(
            writePlan = writePlan,
            editedSettings = edited,
            deviceInfo = DeviceInfo(productName = "Arducon"),
        )

        assertEquals(listOf("FOX 19"), commands)
    }

    @Test
    fun parsesArduconUtilityAndAdvancedSettingsReadbacks() {
        val voltageUpdate = SignalSlingerProtocolCodec.parseReportLine("V=11.07V")
        val passwordUpdate = SignalSlingerProtocolCodec.parseReportLine("PWD=1357")
        val amUpdate = SignalSlingerProtocolCodec.parseReportLine("AM:3")
        val pttUpdate = SignalSlingerProtocolCodec.parseReportLine("DRP:1")
        val invalidPttUpdate = SignalSlingerProtocolCodec.parseReportLine("DRP:2")
        val calibrationUpdate = SignalSlingerProtocolCodec.parseReportLine("T Cal= -110")

        assertEquals(11.07, voltageUpdate?.deviceStatusPatch?.externalBatteryVolts)
        assertEquals("1357", passwordUpdate?.settingsPatch?.dtmfPassword)
        assertEquals(3, amUpdate?.settingsPatch?.amToneFrequency)
        assertEquals(1, pttUpdate?.settingsPatch?.pttResetSetting)
        assertNull(invalidPttUpdate)
        assertEquals(-110, calibrationUpdate?.settingsPatch?.temperatureCalibration)
    }

    @Test
    fun ignoresErasedArduconEepromReadbacks() {
        assertNull(SignalSlingerProtocolCodec.parseReportLine("ID:\uFFFFNZ0I"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("PWD=\uFFFF\uFFFF\uFFFF\uFFFF\uFFFF\uFFFF\uFFFF\uFFFF"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("AM:255"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("Thermal Shutdown=-1C"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("CLK D 0"))
        assertNull(SignalSlingerProtocolCodec.parseReportLine("CLK D 255"))
    }

    @Test
    fun encodesArduconAdvancedSettingsCommands() {
        val original = sampleSettings().copy(
            dtmfPassword = "1357",
            amToneFrequency = 0,
            pttResetSetting = 0,
            temperatureCalibration = -110,
        )
        val edited = original.copy(
            dtmfPassword = "2468",
            amToneFrequency = 3,
            pttResetSetting = 1,
            temperatureCalibration = -125,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(
            writePlan = writePlan,
            editedSettings = edited,
            deviceInfo = DeviceInfo(productName = "Arducon"),
        )

        assertEquals(listOf("PWD 2468", "AM 3", "SET P 1", "UTI C -125"), commands)
    }

    @Test
    fun rejectsInvalidArduconDtmfPasswordCommands() {
        val original = sampleSettings().copy(dtmfPassword = "1357")
        val edited = original.copy(dtmfPassword = "12A4")
        val writePlan = WritePlanner.create(original, edited)

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SignalSlingerProtocolCodec.encodeWritePlan(
                writePlan = writePlan,
                editedSettings = edited,
                deviceInfo = DeviceInfo(productName = "Arducon"),
            )
        }
    }

    @Test
    fun encodesFinishWriteWhenStartChangesButAbsoluteFinishMustBePreserved() {
        val original = sampleSettings().copy(
            startTimeCompact = "260410150000",
            finishTimeCompact = "260410170000",
        )
        val edited = original.copy(
            startTimeCompact = "260410151000",
            finishTimeCompact = "260410170000",
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(
            listOf(
                "CLK S 260410151000",
                "CLK F 260410170000",
            ),
            commands,
        )
    }

    @Test
    fun encodesFoxoringPatternSpeedWithFoxoringCommand() {
        val original = sampleSettings().copy(
            eventType = EventType.FOXORING,
            patternCodeSpeedWpm = 12,
        )
        val edited = original.copy(
            patternCodeSpeedWpm = 8,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("SPD F 8"), commands)
    }

    @Test
    fun collapsesBatteryControlWritesIntoOneCommand() {
        val original = sampleSettings()
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_ONLY,
            transmissionsEnabled = false,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 2"), commands)
    }

    @Test
    fun encodesBatteryControlModeDisabledAsBatX0() {
        val original = sampleSettings().copy(
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
        )
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.OFF,
            transmissionsEnabled = true,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 0"), commands)
    }

    @Test
    fun encodesDisabledTransmissionsAsBatX2EvenWhenExternalControlIsOff() {
        val original = sampleSettings()
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.OFF,
            transmissionsEnabled = false,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 2"), commands)
    }

    @Test
    fun encodesBatteryControlModeEnabledAsBatX1() {
        val original = sampleSettings()
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_AND_TRANSMIT,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 1"), commands)
    }

    @Test
    fun encodesBatteryControlModeEnabledTxDisabledAsBatX2() {
        val original = sampleSettings()
        val edited = original.copy(
            externalBatteryControlMode = ExternalBatteryControlMode.CHARGE_ONLY,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(listOf("BAT X 2"), commands)
    }

    @Test
    fun encodesNumberedFrequencyProfileCommands() {
        val original = sampleSettings()
        val edited = original.copy(
            lowFrequencyHz = 3_520_000L,
            mediumFrequencyHz = 3_540_000L,
            highFrequencyHz = 3_560_000L,
        )

        val writePlan = WritePlanner.create(original, edited)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, edited)

        assertEquals(
            listOf(
                "FRE 1 3520000",
                "FRE 2 3540000",
                "FRE 3 3560000",
            ),
            commands,
        )
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

    private fun utcCompactForLocal(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): String {
        val epoch = requireNotNull(
            platformEpochSecondsFromLocalDateTimeFields(
                PlatformDateTimeFields(
                    year = year,
                    month = month,
                    day = day,
                    hour = hour,
                    minute = minute,
                    second = second,
                ),
            ),
        )
        val utc = requireNotNull(platformUtcDateTimeFields(epoch))
        return buildString {
            append((utc.year % 100).toString().padStart(2, '0'))
            append(utc.month.toString().padStart(2, '0'))
            append(utc.day.toString().padStart(2, '0'))
            append(utc.hour.toString().padStart(2, '0'))
            append(utc.minute.toString().padStart(2, '0'))
            append(utc.second.toString().padStart(2, '0'))
        }
    }

}
