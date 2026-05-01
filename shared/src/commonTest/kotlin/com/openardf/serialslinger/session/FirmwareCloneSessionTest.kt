package com.openardf.serialslinger.session

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.protocol.FirmwareCloneProtocol
import com.openardf.serialslinger.transport.DeviceTransport
import com.openardf.serialslinger.transport.FakeDeviceTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirmwareCloneSessionTest {
    @Test
    fun sendsFirmwareCloneHandshakeAndPlanOneCommandAtATime() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            FakeDeviceTransport(
                mapOf(
                    "RST" to emptyList<String>(),
                    "MAS P" to listOf("MAS S"),
                ) +
                    plan.steps.associate { step ->
                        val response =
                            when (step.expectedReply) {
                                "CLK T" -> "CLK T 1777569403"
                                else -> step.expectedReply
                            }
                        step.command to listOf(response)
                    },
            )

        var commandsWhenClockValueWasChosen: List<String> = emptyList()
        var commandsWhenResetWaitStarted: List<String> = emptyList()
        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            afterReset = {
                commandsWhenResetWaitStarted = transport.sentCommands.toList()
                emptyList()
            },
            currentTimeCompact = {
                commandsWhenClockValueWasChosen = transport.sentCommands.toList()
                "260430171643"
            },
        )

        assertTrue(result.succeeded)
        assertEquals(listOf("RST"), commandsWhenResetWaitStarted)
        assertEquals(listOf("RST", "MAS P", "FUN A"), commandsWhenClockValueWasChosen)
        assertEquals(listOf("RST", "MAS P") + plan.steps.map { it.command }, transport.sentCommands)
        assertEquals(listOf("RST", "MAS P") + plan.steps.map { it.command }, result.commandsSent)
    }

    @Test
    fun recordsResetOutputBeforeStartingCloneProbes() {
        val transport = FakeDeviceTransport(
            mapOf(
                "RST" to emptyList<String>(),
                "MAS P" to listOf("MAS S"),
            ),
        )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = sampleSettings(),
            afterReset = {
                listOf("* Warning: CPU Reset! Need to set clock", "* Power off. Press and hold pushbutton for power on")
            },
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.enteredCloneMode)
        assertTrue(result.linesReceived.contains("* Warning: CPU Reset! Need to set clock"))
        assertEquals(listOf("RST", "MAS P", "FUN A"), result.commandsSent.take(3))
    }

    @Test
    fun waitsForDelayedCloneStepAcknowledgementWithoutResendingCommand() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            DelayedReadTransport(
                scriptedReads =
                    buildMap {
                        put("RST", listOf(emptyList()))
                        put("MAS P", listOf(listOf("MAS S")))
                        plan.steps.forEach { step ->
                            val response =
                                when (step.expectedReply) {
                                    "CLK T" -> "CLK T 1777569403"
                                    else -> step.expectedReply
                                }
                            put(step.command, listOf(emptyList(), listOf(response)))
                        }
                    },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(1, transport.sentCommands.count { it == "CLK D 4" })
        assertEquals(listOf("RST", "MAS P") + plan.steps.map { it.command }, result.commandsSent)
    }

    @Test
    fun givesEveryCloneStepAnExtendedAcknowledgementWindow() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val delayedStep = "CLK D 4"
        val transport =
            DelayedReadTransport(
                scriptedReads =
                    buildMap {
                        put("RST", listOf(emptyList()))
                        put("MAS P", listOf(listOf("MAS S")))
                        plan.steps.forEach { step ->
                            val response =
                                when (step.expectedReply) {
                                    "CLK T" -> "CLK T 1777569403"
                                    else -> step.expectedReply
                                }
                            put(
                                step.command,
                                if (step.command == delayedStep) {
                                    List(9) { emptyList<String>() } + listOf(listOf(response))
                                } else {
                                    listOf(listOf(response))
                                },
                            )
                        }
                    },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(1, transport.sentCommands.count { it == delayedStep })
        assertEquals(listOf("RST", "MAS P") + plan.steps.map { it.command }, result.commandsSent)
    }

    @Test
    fun allowsCloneCallerToSettleBetweenAcknowledgedCommands() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            FakeDeviceTransport(
                mapOf(
                    "RST" to emptyList<String>(),
                    "MAS P" to listOf("MAS S"),
                ) +
                    plan.steps.associate { step ->
                        val response =
                            when (step.expectedReply) {
                                "CLK T" -> "CLK T 1777569403"
                                else -> step.expectedReply
                            }
                        step.command to listOf(response)
                    },
            )
        val acknowledgedCommands = mutableListOf<String>()

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
            afterCommandAcknowledged = { acknowledgedCommands += it },
        )

        assertTrue(result.succeeded)
        assertEquals(listOf("MAS P") + plan.steps.map { it.command }, acknowledgedCommands)
    }

    @Test
    fun reportsNoCloneModeWhenMasStartIsNotAcknowledged() {
        val transport = FakeDeviceTransport()

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = sampleSettings(),
            currentTimeCompact = { "260430171643" },
        )

        assertFalse(result.succeeded)
        assertFalse(result.enteredCloneMode)
        assertTrue(result.legacyFallbackAllowed)
        assertEquals(listOf("RST", "MAS P", "MAS P", "MAS P", "MAS P"), result.commandsSent)
    }

    @Test
    fun doesNotAllowLegacyFallbackWhenMasStartOnlyEchoes() {
        val transport = FakeDeviceTransport(
            mapOf(
                "RST" to emptyList<String>(),
                "MAS P" to listOf("> MAS P"),
            ),
        )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = sampleSettings(),
            currentTimeCompact = { "260430171643" },
        )

        assertFalse(result.succeeded)
        assertFalse(result.enteredCloneMode)
        assertFalse(result.legacyFallbackAllowed)
        assertEquals(listOf("RST", "MAS P", "MAS P", "MAS P", "MAS P"), result.commandsSent)
    }

    @Test
    fun acceptsMasStartEchoWithReply() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport = FakeDeviceTransport(
            mapOf("RST" to emptyList<String>()) +
                mapOf("MAS P" to listOf("> MAS PMAS S")) +
                plan.steps.associate { step ->
                    val response =
                        when (step.expectedReply) {
                            "CLK T" -> "> ${step.command}CLK T 1777569403"
                            else -> "> ${step.command}${step.expectedReply}"
                        }
                    step.command to listOf(response)
                },
        )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertTrue(result.enteredCloneMode)
        assertFalse(result.legacyFallbackAllowed)
        assertEquals(listOf("RST", "MAS P") + plan.steps.map { it.command }, result.commandsSent)
    }

    @Test
    fun failsAfterCloneModeIfAnyProtocolStepIsNotAcknowledged() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val responses =
            mutableMapOf(
                "RST" to emptyList<String>(),
                "MAS P" to listOf("MAS S"),
            ).apply {
                plan.steps.forEach { step ->
                    this[step.command] = listOf(step.expectedReply)
                }
                this["CLK S 1777559400"] = emptyList()
            }
        val transport = FakeDeviceTransport(responses)

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertFalse(result.succeeded)
        assertTrue(result.enteredCloneMode)
        assertFalse(result.legacyFallbackAllowed)
        assertEquals(3, result.commandsSent.count { it == "RST" })
        assertEquals(3, result.commandsSent.count { it == "CLK S 1777559400" })
        assertTrue(result.failureMessage.orEmpty().contains("Firmware clone attempts: 3."))
    }

    @Test
    fun retriesWholeFirmwareCloneSessionAfterMidProtocolMissingAcknowledgement() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            PerSendReadTransport(
                buildMap {
                    put("RST", mutableListOf(listOf(emptyList()), listOf(emptyList())))
                    put("MAS P", mutableListOf(listOf(listOf("MAS S")), listOf(listOf("MAS S"))))
                    plan.steps.forEach { step ->
                        val response =
                            when (step.expectedReply) {
                                "CLK T" -> "CLK T 1777569403"
                                else -> step.expectedReply
                            }
                        put(step.command, mutableListOf(listOf(listOf(response)), listOf(listOf(response))))
                    }
                    put("FRE M 3540000", mutableListOf(listOf(emptyList()), listOf(listOf("FRE"))))
                    put("CLN", mutableListOf(listOf(listOf("CLN last=FRE M reply=FRE ok=0 fail=0"))))
                },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            targetSoftwareVersion = "1.2.1",
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(2, transport.sentCommands.count { it == "RST" })
        assertEquals(2, transport.sentCommands.count { it == "FRE M 3540000" })
        assertEquals(1, transport.sentCommands.count { it == "CLN" })
        assertEquals(0, transport.sentCommands.count { it == "TMP" })
        assertTrue(result.linesReceived.contains("CLN last=FRE M reply=FRE ok=0 fail=0"))
        assertEquals(1, transport.sentCommands.count { it == "MAS Q ${plan.checksum}" })
    }

    @Test
    fun continuesFirmwareCloneWhenSupportedCloneDiagnosticConfirmsMissingAcknowledgement() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val missedCommand = "SPD P 8"
        val transport =
            PerSendReadTransport(
                buildMap {
                    put("RST", mutableListOf(listOf(emptyList())))
                    put("MAS P", mutableListOf(listOf(listOf("MAS S"))))
                    plan.steps.forEach { step ->
                        val response =
                            when (step.expectedReply) {
                                "CLK T" -> "CLK T 1777569403"
                                else -> step.expectedReply
                            }
                        put(step.command, mutableListOf(listOf(listOf(response))))
                    }
                    put(missedCommand, mutableListOf(listOf(emptyList())))
                    put("CLN", mutableListOf(listOf(listOf("CLN last=SPD P reply=SPD P tries=1 ok=9 fail=0"))))
                },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            targetSoftwareVersion = "1.2.2",
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(1, transport.sentCommands.count { it == "RST" })
        assertEquals(1, transport.sentCommands.count { it == missedCommand })
        assertEquals(1, transport.sentCommands.count { it == "CLN" })
        assertEquals(0, transport.sentCommands.count { it == "TMP" })
        assertEquals(1, transport.sentCommands.count { it == "MAS Q ${plan.checksum}" })
        assertTrue(result.linesReceived.contains("CLN last=SPD P reply=SPD P tries=1 ok=9 fail=0"))
    }

    @Test
    fun fallsBackToTemperatureProbeWhenCloneDiagnosticIsUnsupported() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            PerSendReadTransport(
                buildMap {
                    put("RST", mutableListOf(listOf(emptyList()), listOf(emptyList())))
                    put("MAS P", mutableListOf(listOf(listOf("MAS S")), listOf(listOf("MAS S"))))
                    plan.steps.forEach { step ->
                        val response =
                            when (step.expectedReply) {
                                "CLK T" -> "CLK T 1777569403"
                                else -> step.expectedReply
                            }
                        put(step.command, mutableListOf(listOf(listOf(response)), listOf(listOf(response))))
                    }
                    put("CLK D 4", mutableListOf(listOf(emptyList()), listOf(listOf("CLK D"))))
                    put("CLN", mutableListOf(listOf(emptyList())))
                    put("TMP", mutableListOf(listOf(listOf("* Temp: 22.0C"))))
                },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(1, transport.sentCommands.count { it == "CLN" })
        assertEquals(1, transport.sentCommands.count { it == "TMP" })
        assertTrue(result.linesReceived.contains("* Temp: 22.0C"))
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

    private class DelayedReadTransport(
        private val scriptedReads: Map<String, List<List<String>>>,
    ) : DeviceTransport {
        val sentCommands: MutableList<String> = mutableListOf()
        private val pendingReads: MutableList<List<String>> = mutableListOf()

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun sendCommands(commands: List<String>) {
            sentCommands += commands
            commands.forEach { command ->
                pendingReads += scriptedReads[command].orEmpty()
            }
        }

        override fun readAvailableLines(): List<String> {
            return if (pendingReads.isEmpty()) {
                emptyList()
            } else {
                pendingReads.removeAt(0)
            }
        }
    }

    private class PerSendReadTransport(
        private val scriptedReads: Map<String, MutableList<List<List<String>>>>,
    ) : DeviceTransport {
        val sentCommands: MutableList<String> = mutableListOf()
        private val pendingReads: MutableList<List<String>> = mutableListOf()

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun sendCommands(commands: List<String>) {
            sentCommands += commands
            commands.forEach { command ->
                val reads = scriptedReads[command]?.removeFirstOrNull().orEmpty()
                pendingReads += reads
            }
        }

        override fun readAvailableLines(): List<String> {
            return if (pendingReads.isEmpty()) {
                emptyList()
            } else {
                pendingReads.removeAt(0)
            }
        }
    }
}
