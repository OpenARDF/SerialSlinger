package com.openardf.serialslinger.cli

import com.openardf.serialslinger.app.DesktopInputSupport
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.session.DeviceSubmitResult
import com.openardf.serialslinger.session.DeviceSessionController
import com.openardf.serialslinger.session.FirmwareCloneSession
import com.openardf.serialslinger.transport.DesktopSerialTransport
import java.time.Duration
import java.time.LocalDateTime

sealed interface DesktopSmokeCommand {
    data object ListPorts : DesktopSmokeCommand

    data class Load(
        val port: String,
    ) : DesktopSmokeCommand

    data class Clone(
        val port: String,
    ) : DesktopSmokeCommand

    data class Submit(
        val port: String,
        val assignments: Map<String, String>,
    ) : DesktopSmokeCommand

    data class Raw(
        val port: String,
        val commands: List<String>,
    ) : DesktopSmokeCommand
}

object DesktopSmokeCliParser {
    fun parse(args: Array<String>): DesktopSmokeCommand? {
        if (args.isEmpty()) {
            return null
        }

        return when (args[0].lowercase()) {
            "list" -> DesktopSmokeCommand.ListPorts
            "load" -> {
                if (args.size < 2) return null
                DesktopSmokeCommand.Load(port = args[1])
            }
            "clone" -> {
                if (args.size < 2) return null
                DesktopSmokeCommand.Clone(port = args[1])
            }
            "submit" -> {
                if (args.size < 3) return null
                DesktopSmokeCommand.Submit(
                    port = args[1],
                    assignments = parseAssignments(args.drop(2)),
                )
            }
            "raw" -> {
                if (args.size < 3) return null
                DesktopSmokeCommand.Raw(
                    port = args[1],
                    commands = args.drop(2),
                )
            }
            else -> null
        }
    }

    private fun parseAssignments(arguments: List<String>): Map<String, String> {
        return arguments.associate { entry ->
            val parts = entry.split("=", limit = 2)
            require(parts.size == 2) {
                "Submit arguments must use key=value format. Invalid entry: `$entry`"
            }
            parts[0] to parts[1]
        }
    }
}

fun main(args: Array<String>) {
    when (val command = DesktopSmokeCliParser.parse(args)) {
        is DesktopSmokeCommand.ListPorts -> runListPorts()
        is DesktopSmokeCommand.Load -> runLoad(command.port)
        is DesktopSmokeCommand.Clone -> runClone(command.port)
        is DesktopSmokeCommand.Submit -> runSubmit(command.port, command.assignments)
        is DesktopSmokeCommand.Raw -> runRaw(command.port, command.commands)
        null -> printUsage()
    }
}

private fun runListPorts() {
    val ports = DesktopSerialTransport.listAvailablePorts()
    if (ports.isEmpty()) {
        println("No serial ports found.")
        return
    }

    ports.forEach { port ->
        println("${port.systemPortPath} | ${port.systemPortName} | ${port.descriptivePortName} | ${port.portDescription}")
    }
}

private fun runLoad(port: String) {
    val transport = DesktopSerialTransport(port)
    val result = DeviceSessionController.connectAndLoad(transport)
    try {
        println("Commands sent:")
        result.commandsSent.forEach { println("  $it") }
        println()
        println("Lines received:")
        result.linesReceived.forEach { println("  $it") }
        println()
        println("Loaded snapshot:")
        println(formatSettings(result.state.snapshot?.settings ?: DeviceSettings.empty()))
    } finally {
        DeviceSessionController.disconnect(result.state, transport)
    }
}

private fun runClone(port: String) {
    val transport = DesktopSerialTransport(port)
    val loadResult = DeviceSessionController.connectAndLoad(transport)
    try {
        val snapshot = requireNotNull(loadResult.state.snapshot) {
            "No device snapshot was loaded."
        }
        val templateSettings = snapshot.settings
        val result = FirmwareCloneSession.cloneFromTemplate(
            transport = transport,
            templateSettings = templateSettings,
            targetSoftwareVersion = snapshot.info.softwareVersion,
            currentTimeCompact = ::nextFirmwareCloneClockTimeCompact,
            afterReset = { waitForFirmwareCloneReset(transport) },
            afterStartAttempt = ::waitForFirmwareCloneStartRetry,
            afterCommandAcknowledged = { waitForFirmwareCloneCommandSettle() },
            afterCloneDiagnosticRecovered = { waitForFirmwareCloneDiagnosticRecovery(transport) },
        )
        val refreshResult = DeviceSessionController.refreshFromDevice(loadResult.state, transport, startEditing = true)
        println("Clone result:")
        println("  succeeded=${result.succeeded}")
        println("  enteredCloneMode=${result.enteredCloneMode}")
        println("  acknowledged=${result.acknowledged}")
        println("  legacyFallbackAllowed=${result.legacyFallbackAllowed}")
        println("  checksum=${result.plan?.checksum ?: "<none>"}")
        println("  failureMessage=${result.failureMessage ?: "<none>"}")
        println()
        println("Firmware clone trace:")
        result.traceEntries.forEach { trace ->
            println("  ${trace.direction.label} ${trace.payload}")
        }
        println()
        println("Post-clone refresh:")
        println("  commands=${refreshResult.commandsSent.size}")
        println("  lines=${refreshResult.linesReceived.size}")
        println(formatSettings(refreshResult.state.snapshot?.settings ?: DeviceSettings.empty()))
        if (!result.succeeded) {
            error(result.failureMessage ?: "Firmware clone failed.")
        }
    } finally {
        DeviceSessionController.disconnect(loadResult.state, transport)
    }
}

private fun runSubmit(port: String, assignments: Map<String, String>) {
    val transport = DesktopSerialTransport(port)
    val loadResult = DeviceSessionController.connectAndLoad(transport)
    try {
        val snapshot = requireNotNull(loadResult.state.snapshot) {
            "No device snapshot was loaded."
        }
        val editable = applyAssignments(
            EditableDeviceSettings.fromDeviceSettings(snapshot.settings),
            assignments,
        )
        val submitResult = DeviceSessionController.submitEdits(loadResult.state, editable, transport)

        println("Submit commands:")
        submitResult.commandsSent.forEach { println("  $it") }
        println()
        println("Submit replies:")
        submitResult.linesReceived.forEach { println("  $it") }
        println()
        printVerificationSummary(submitResult)
        println("Updated snapshot:")
        println(formatSettings(submitResult.state.snapshot?.settings ?: DeviceSettings.empty()))
    } finally {
        DeviceSessionController.disconnect(loadResult.state, transport)
    }
}

private fun runRaw(port: String, commands: List<String>) {
    val transport = DesktopSerialTransport(port)
    transport.connect()
    try {
        println("Commands sent:")
        commands.forEach { command ->
            println("  $command")
            transport.sendCommands(listOf(command))
            val lines = transport.readAvailableLines()
            println("Replies:")
            if (lines.isEmpty()) {
                println("  <no lines>")
            } else {
                lines.forEach { println("  $it") }
            }
            println()
        }
    } finally {
        transport.disconnect()
    }
}

private fun nextFirmwareCloneClockTimeCompact(): String {
    val target = LocalDateTime.now().withNano(0).plusSeconds(1)
    val delayNanos = Duration.between(LocalDateTime.now(), target).toNanos().coerceAtLeast(0L)
    if (delayNanos > 0L) {
        Thread.sleep(delayNanos / 1_000_000L, (delayNanos % 1_000_000L).toInt())
    }
    return DesktopInputSupport.currentSystemTimeCompact(target)
}

private fun waitForFirmwareCloneReset(transport: DesktopSerialTransport): List<String> {
    val lines = mutableListOf<String>()
    val deadline = System.currentTimeMillis() + FirmwareCloneResetMaxWaitMs
    var sawResetOutput = false
    while (System.currentTimeMillis() < deadline) {
        val responseLines = transport.readAvailableLines()
        if (responseLines.isNotEmpty()) {
            lines += responseLines
            sawResetOutput = true
            Thread.sleep(FirmwareCloneCommandSettleMs)
        } else if (sawResetOutput) {
            return lines
        } else {
            Thread.sleep(FirmwareCloneCommandSettleMs)
        }
    }
    return lines
}

private fun waitForFirmwareCloneStartRetry() {
    Thread.sleep(FirmwareCloneCommandSettleMs)
}

private fun waitForFirmwareCloneCommandSettle() {
    Thread.sleep(FirmwareCloneCommandSettleMs)
}

private fun waitForFirmwareCloneDiagnosticRecovery(transport: DesktopSerialTransport): List<String> {
    Thread.sleep(80L)
    return transport.readAvailableLinesBriefly()
}

internal fun applyAssignments(
    editable: EditableDeviceSettings,
    assignments: Map<String, String>,
): EditableDeviceSettings {
    var updated = editable

    assignments.forEach { (key, value) ->
        updated = when (key) {
            "stationId" -> updated.copy(
                stationId = updated.stationId.copy(editedValue = value),
            )
            "patternText" -> updated.copy(
                patternText = updated.patternText.copy(editedValue = value),
            )
            "eventType" -> updated.copy(
                eventType = updated.eventType.copy(editedValue = parseEventType(value)),
            )
            "foxRole" -> updated.copy(
                foxRole = updated.foxRole.copy(
                    editedValue = parseFoxRole(
                        value = value,
                        eventType = assignments["eventType"]?.let(::parseEventType) ?: updated.eventType.editedValue,
                    ),
                ),
            )
            "idCodeSpeedWpm" -> updated.copy(
                idCodeSpeedWpm = updated.idCodeSpeedWpm.copy(editedValue = value.toInt()),
            )
            "patternCodeSpeedWpm" -> updated.copy(
                patternCodeSpeedWpm = updated.patternCodeSpeedWpm.copy(editedValue = value.toInt()),
            )
            "daysToRun" -> updated.copy(
                daysToRun = updated.daysToRun.copy(editedValue = value.toInt()),
            )
            "defaultFrequencyHz" -> updated.copy(
                defaultFrequencyHz = updated.defaultFrequencyHz.copy(editedValue = parseFrequencyAssignment(value)),
            )
            "lowFrequencyHz" -> updated.copy(
                lowFrequencyHz = updated.lowFrequencyHz.copy(editedValue = parseFrequencyAssignment(value)),
            )
            "mediumFrequencyHz" -> updated.copy(
                mediumFrequencyHz = updated.mediumFrequencyHz.copy(editedValue = parseFrequencyAssignment(value)),
            )
            "highFrequencyHz" -> updated.copy(
                highFrequencyHz = updated.highFrequencyHz.copy(editedValue = parseFrequencyAssignment(value)),
            )
            "beaconFrequencyHz" -> updated.copy(
                beaconFrequencyHz = updated.beaconFrequencyHz.copy(editedValue = parseFrequencyAssignment(value)),
            )
            "lowBatteryThresholdVolts" -> updated.copy(
                lowBatteryThresholdVolts = updated.lowBatteryThresholdVolts.copy(editedValue = value.toDouble()),
            )
            "externalBatteryControlMode" -> updated.copy(
                externalBatteryControlMode = updated.externalBatteryControlMode.copy(
                    editedValue = parseBatteryMode(value),
                ),
            )
            "transmissionsEnabled" -> updated.copy(
                transmissionsEnabled = updated.transmissionsEnabled.copy(editedValue = value.toBooleanStrict()),
            )
            else -> error("Unsupported assignment key `$key`.")
        }
    }

    return updated
}

private fun parseEventType(value: String): EventType {
    return when (value.lowercase()) {
        "none" -> EventType.NONE
        "classic" -> EventType.CLASSIC
        "foxoring" -> EventType.FOXORING
        "sprint" -> EventType.SPRINT
        else -> error("Unsupported eventType `$value`.")
    }
}

private fun parseBatteryMode(value: String): ExternalBatteryControlMode {
    return when (value.lowercase()) {
        "off" -> ExternalBatteryControlMode.OFF
        "chargeandtransmit", "charge_and_transmit", "charge-transmit" -> {
            ExternalBatteryControlMode.CHARGE_AND_TRANSMIT
        }
        "chargeonly", "charge_only", "charge-only" -> ExternalBatteryControlMode.CHARGE_ONLY
        else -> error("Unsupported externalBatteryControlMode `$value`.")
    }
}

private fun parseFoxRole(value: String, eventType: EventType): FoxRole {
    val normalized = value.trim().lowercase()
    return when (eventType) {
        EventType.CLASSIC -> when (normalized) {
            "b", "beacon" -> FoxRole.BEACON
            "1" -> FoxRole.CLASSIC_1
            "2" -> FoxRole.CLASSIC_2
            "3" -> FoxRole.CLASSIC_3
            "4" -> FoxRole.CLASSIC_4
            "5" -> FoxRole.CLASSIC_5
            else -> error("Unsupported foxRole `$value` for CLASSIC.")
        }
        EventType.FOXORING -> when (normalized) {
            "1", "low", "lowfreq" -> FoxRole.FOXORING_1
            "2", "medium", "med", "mediumfreq" -> FoxRole.FOXORING_2
            "3", "high", "highfreq" -> FoxRole.FOXORING_3
            "f", "t", "test", "frequencytest", "frequency_test_beacon" -> FoxRole.FREQUENCY_TEST_BEACON
            "b", "beacon" -> FoxRole.BEACON
            else -> error("Unsupported foxRole `$value` for FOXORING.")
        }
        EventType.SPRINT -> when (normalized) {
            "s", "spectator" -> FoxRole.SPRINT_SPECTATOR
            "1", "s1", "slow1" -> FoxRole.SPRINT_SLOW_1
            "2", "s2", "slow2" -> FoxRole.SPRINT_SLOW_2
            "3", "s3", "slow3" -> FoxRole.SPRINT_SLOW_3
            "4", "s4", "slow4" -> FoxRole.SPRINT_SLOW_4
            "5", "s5", "slow5" -> FoxRole.SPRINT_SLOW_5
            "1f", "f1", "fast1" -> FoxRole.SPRINT_FAST_1
            "2f", "f2", "fast2" -> FoxRole.SPRINT_FAST_2
            "3f", "f3", "fast3" -> FoxRole.SPRINT_FAST_3
            "4f", "f4", "fast4" -> FoxRole.SPRINT_FAST_4
            "5f", "f5", "fast5" -> FoxRole.SPRINT_FAST_5
            "b", "beacon" -> FoxRole.BEACON
            else -> error("Unsupported foxRole `$value` for SPRINT.")
        }
        EventType.NONE -> when (normalized) {
            "b", "beacon" -> FoxRole.BEACON
            else -> error("Unsupported foxRole `$value` when no event is selected.")
        }
    }
}

private fun parseFrequencyAssignment(value: String): Long {
    return requireNotNull(FrequencySupport.parseFrequencyHz(value)) {
        "Unsupported frequency value `$value`. Use Hz directly or add kHz/MHz units."
    }
}

private fun formatSettings(settings: DeviceSettings): String {
    val frequencies = FrequencySupport.describeFrequencies(settings)

    return buildString {
        appendLine("stationId=${settings.stationId}")
        appendLine("eventType=${settings.eventType}")
        appendLine("foxRole=${settings.foxRole?.label.orEmpty()}")
        appendLine("patternText=${settings.patternText.orEmpty()}")
        appendLine("idCodeSpeedWpm=${settings.idCodeSpeedWpm}")
        appendLine("patternCodeSpeedWpm=${settings.patternCodeSpeedWpm}")
        appendLine("daysToRun=${settings.daysToRun}")
        appendLine("currentFrequencyHz=${FrequencySupport.formatFrequencyMhz(frequencies.currentFrequencyHz)}")
        appendLine("currentFrequencyBank=${frequencies.currentBankId?.label ?: "Unknown"}")
        frequencies.banks.forEach { bank ->
            val activeMarker = if (bank.isCurrentBank) " (current)" else ""
            appendLine("${bank.bankId.fieldKey}=${FrequencySupport.formatFrequencyMhz(bank.frequencyHz)}$activeMarker")
        }
        appendLine("lowBatteryThresholdVolts=${settings.lowBatteryThresholdVolts}")
        appendLine("externalBatteryControlMode=${settings.externalBatteryControlMode}")
        append("transmissionsEnabled=${settings.transmissionsEnabled}")
    }
}

private fun printVerificationSummary(submitResult: DeviceSubmitResult) {
    if (submitResult.readbackCommandsSent.isEmpty()) {
        return
    }

    println("Verification commands:")
    submitResult.readbackCommandsSent.forEach { println("  $it") }
    println()
    println("Verification replies:")
    if (submitResult.readbackLinesReceived.isEmpty()) {
        println("  <no lines>")
    } else {
        submitResult.readbackLinesReceived.forEach { println("  $it") }
    }
    println()
    println("Verification results:")
    submitResult.verifications.forEach { verification ->
        val status = if (verification.verified) "OK" else "MISMATCH"
        val actual = if (verification.observedInReadback) {
            verification.actualValue
        } else {
            "<not observed>"
        }
        println(
            "  ${verification.fieldKey}: $status expected=${verification.expectedValue} actual=$actual",
        )
    }
    println()
}

private fun printUsage() {
    println("SerialSlinger desktop smoke CLI")
    println("Usage:")
    println("  list")
    println("  load <portDescriptor>")
    println("  clone <portDescriptor>")
    println("  submit <portDescriptor> key=value [key=value ...]")
    println("  raw <portDescriptor> \"COMMAND\" [\"COMMAND\" ...]")
    println()
    println("Example:")
    println("  submit /dev/tty.usbserial stationId=W1FOX daysToRun=3 beaconFrequencyHz=3580000")
}

private const val FirmwareCloneCommandSettleMs = 250L
private const val FirmwareCloneResetMaxWaitMs = 8_000L
