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
                    "GO 0" to emptyList<String>(),
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
        var commandsWhenPreCloneStopWaitStarted: List<String> = emptyList()
        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            afterPreCloneStop = {
                commandsWhenPreCloneStopWaitStarted = transport.sentCommands.toList()
                emptyList()
            },
            currentTimeCompact = {
                commandsWhenClockValueWasChosen = transport.sentCommands.toList()
                "260430171643"
            },
        )

        assertTrue(result.succeeded)
        assertEquals(listOf("GO 0"), commandsWhenPreCloneStopWaitStarted)
        assertEquals(listOf("GO 0", "UI S", "MAS P", "FUN A"), commandsWhenClockValueWasChosen)
        assertEquals(listOf("GO 0", "UI S", "MAS P") + plan.steps.map { it.command }, transport.sentCommands)
        assertEquals(listOf("GO 0", "UI S", "MAS P") + plan.steps.map { it.command }, result.commandsSent)
    }

    @Test
    fun recordsPreCloneStopOutputBeforeStartingCloneProbes() {
        val transport = FakeDeviceTransport(
            mapOf(
                "GO 0" to emptyList<String>(),
                "MAS P" to listOf("MAS S"),
            ),
        )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = sampleSettings(),
            afterPreCloneStop = {
                listOf("* GO 0:Classic; Stopped")
            },
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.enteredCloneMode)
        assertTrue(result.linesReceived.contains("* GO 0:Classic; Stopped"))
        assertEquals(listOf("GO 0", "UI S", "MAS P", "FUN A"), result.commandsSent.take(4))
    }

    @Test
    fun clearsCloneSuccessLatchBeforeStartingCloneProbeWhenUiStatusReportsIt() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            FakeDeviceTransport(
                mapOf(
                    "GO 0" to emptyList(),
                    "UI S" to listOf("* UI cs=18000 cl=0 df=0 pc=0 pt=0", "* UI ms=0 mc=0 ee=0 ec=0 ru=0 fs=0"),
                    "UI C" to listOf("* UI C"),
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

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(listOf("GO 0", "UI S", "UI C", "MAS P"), transport.sentCommands.take(4))
        assertTrue(result.linesReceived.contains("* UI cs=18000 cl=0 df=0 pc=0 pt=0"))
        assertTrue(result.linesReceived.contains("* UI C"))
    }

    @Test
    fun recognizesPrefixedUiStatusWhenEchoAndReplyShareOneLine() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            FakeDeviceTransport(
                mapOf(
                    "GO 0" to emptyList(),
                    "UI S" to listOf("> UI S* UI cs=12750 cl=0 df=0 pc=0 pt=0"),
                    "UI C" to listOf("> UI C* UI C"),
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

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(listOf("GO 0", "UI S", "UI C", "MAS P"), transport.sentCommands.take(4))
    }

    @Test
    fun waitsForDelayedCloneStepAcknowledgementWithoutResendingCommand() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            DelayedReadTransport(
                scriptedReads =
                    buildMap {
                        put("GO 0", listOf(emptyList()))
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
        assertEquals(listOf("GO 0", "UI S", "MAS P") + plan.steps.map { it.command }, result.commandsSent)
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
                        put("GO 0", listOf(emptyList()))
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
                                    List(2) { emptyList<String>() } + listOf(listOf(response))
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
        assertEquals(listOf("GO 0", "UI S", "MAS P") + plan.steps.map { it.command }, result.commandsSent)
    }

    @Test
    fun allowsCloneCallerToSettleBetweenAcknowledgedCommands() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            FakeDeviceTransport(
                mapOf(
                    "GO 0" to emptyList<String>(),
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
        assertEquals(listOf("GO 0", "UI S", "MAS P", "MAS P", "MAS P", "MAS P"), result.commandsSent)
    }

    @Test
    fun failsWhenMasStartOnlyEchoes() {
        val transport = FakeDeviceTransport(
            mapOf(
                "GO 0" to emptyList<String>(),
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
        assertEquals(listOf("GO 0", "UI S", "MAS P", "MAS P", "MAS P", "MAS P"), result.commandsSent)
    }

    @Test
    fun acceptsMasStartEchoWithReply() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport = FakeDeviceTransport(
            mapOf("GO 0" to emptyList<String>()) +
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
        assertEquals(listOf("GO 0", "UI S", "MAS P") + plan.steps.map { it.command }, result.commandsSent)
    }

    @Test
    fun failsAfterCloneModeIfAnyProtocolStepIsNotAcknowledged() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val responses =
            mutableMapOf(
                "GO 0" to emptyList<String>(),
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
        assertEquals(3, result.commandsSent.count { it == "GO 0" })
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
                    put("GO 0", mutableListOf(listOf(emptyList()), listOf(emptyList())))
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
                },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(2, transport.sentCommands.count { it == "GO 0" })
        assertEquals(2, transport.sentCommands.count { it == "FRE M 3540000" })
        assertEquals(0, transport.sentCommands.count { it == "TMP" })
        assertEquals(1, transport.sentCommands.count { it == "MAS Q ${plan.checksum}" })
    }

    @Test
    fun failsWhenEveryAttemptMissesAcknowledgement() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val missedCommand = "SPD P 8"
        val transport =
            PerSendReadTransport(
                buildMap {
                    put("GO 0", mutableListOf(listOf(emptyList()), listOf(emptyList()), listOf(emptyList())))
                    put("MAS P", mutableListOf(listOf(listOf("MAS S")), listOf(listOf("MAS S")), listOf(listOf("MAS S"))))
                    plan.steps.forEach { step ->
                        val response =
                            when (step.expectedReply) {
                                "CLK T" -> "CLK T 1777569403"
                                else -> step.expectedReply
                            }
                        put(step.command, mutableListOf(listOf(listOf(response)), listOf(listOf(response)), listOf(listOf(response))))
                    }
                    put(missedCommand, mutableListOf(listOf(emptyList()), listOf(emptyList()), listOf(emptyList())))
                },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertFalse(result.succeeded)
        assertEquals(3, transport.sentCommands.count { it == "GO 0" })
        assertEquals(3, transport.sentCommands.count { it == missedCommand })
        assertEquals(0, transport.sentCommands.count { it == "TMP" })
        assertEquals(0, transport.sentCommands.count { it == "MAS Q ${plan.checksum}" })
        assertTrue(result.failureMessage.orEmpty().contains("Firmware clone attempts: 3."))
    }

    @Test
    fun failsChecksumCommitWithoutRetryingWholeCloneSession() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val checksumCommand = "MAS Q ${plan.checksum}"
        val transport =
            PerSendReadTransport(
                buildMap {
                    put("GO 0", mutableListOf(listOf(emptyList()), listOf(emptyList()), listOf(emptyList())))
                    put("MAS P", mutableListOf(listOf(listOf("MAS S")), listOf(listOf("MAS S")), listOf(listOf("MAS S"))))
                    plan.steps.forEach { step ->
                        val response =
                            when (step.expectedReply) {
                                "CLK T" -> "CLK T 1777569403"
                                else -> step.expectedReply
                            }
                        put(step.command, mutableListOf(listOf(listOf(response)), listOf(listOf(response)), listOf(listOf(response))))
                    }
                    put(checksumCommand, mutableListOf(listOf(emptyList()), listOf(listOf("MAS ACK")), listOf(listOf("MAS ACK"))))
                },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertFalse(result.succeeded)
        assertFalse(result.retryable)
        assertEquals(1, transport.sentCommands.count { it == "GO 0" })
        assertEquals(1, transport.sentCommands.count { it == checksumCommand })
        assertFalse(result.failureMessage.orEmpty().contains("Firmware clone attempts:"))
    }

    @Test
    fun retriesWholeFirmwareCloneSessionWithoutTemperatureProbe() {
        val settings = sampleSettings()
        val plan = FirmwareCloneProtocol.buildPlan(settings, currentTimeCompact = "260430171643")
        val transport =
            PerSendReadTransport(
                buildMap {
                    put("GO 0", mutableListOf(listOf(emptyList()), listOf(emptyList())))
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
                },
            )

        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = settings,
            currentTimeCompact = { "260430171643" },
        )

        assertTrue(result.succeeded)
        assertEquals(0, transport.sentCommands.count { it == "TMP" })
        assertEquals(2, transport.sentCommands.count { it == "CLK D 4" })
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
