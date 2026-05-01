package com.openardf.serialslinger.session

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.protocol.FirmwareClonePlan
import com.openardf.serialslinger.protocol.FirmwareCloneProtocol
import com.openardf.serialslinger.protocol.SignalSlingerFirmwareVersion
import com.openardf.serialslinger.transport.DeviceTransport

data class FirmwareCloneSessionResult(
    val enteredCloneMode: Boolean,
    val acknowledged: Boolean,
    val legacyFallbackAllowed: Boolean,
    val plan: FirmwareClonePlan?,
    val commandsSent: List<String>,
    val linesReceived: List<String>,
    val traceEntries: List<SerialTraceEntry>,
    val failureMessage: String? = null,
) {
    val succeeded: Boolean
        get() = enteredCloneMode && acknowledged && failureMessage == null
}

object FirmwareCloneSession {
    private const val ResetCommand = "RST"
    private const val StartCommand = "MAS P"
    private const val StartReply = "MAS S"
    private const val CloneDiagnosticCommand = "CLN"
    private const val DiagnosticProbeCommand = "TMP"
    private const val StartAttempts = 4
    private const val CloneReplyReadAttempts = 10
    private const val CloneSessionAttempts = 3
    private val CloneDiagnosticRecoveryMinimumVersion = SignalSlingerFirmwareVersion(1, 2, 2)

    fun cloneFromTemplate(
        transport: DeviceTransport,
        templateSettings: DeviceSettings,
        targetSoftwareVersion: String? = null,
        currentTimeCompact: () -> String,
        afterReset: () -> List<String> = { emptyList() },
        afterStartAttempt: () -> Unit = {},
        afterCommandAcknowledged: (String) -> Unit = {},
    ): FirmwareCloneSessionResult {
        val cloneDiagnosticRecoveryAllowed =
            SignalSlingerFirmwareVersion.parse(targetSoftwareVersion)?.let { version ->
                version >= CloneDiagnosticRecoveryMinimumVersion
            } == true
        val attempts = mutableListOf<FirmwareCloneSessionResult>()
        repeat(CloneSessionAttempts) { attemptIndex ->
            val result =
                cloneFromTemplateOnce(
                    transport = transport,
                    templateSettings = templateSettings,
                    cloneDiagnosticRecoveryAllowed = cloneDiagnosticRecoveryAllowed,
                    currentTimeCompact = currentTimeCompact,
                    afterReset = afterReset,
                    afterStartAttempt = afterStartAttempt,
                    afterCommandAcknowledged = afterCommandAcknowledged,
                )
            attempts += result
            if (result.succeeded || !result.enteredCloneMode || attemptIndex == CloneSessionAttempts - 1) {
                return mergeAttempts(attempts)
            }
        }
        return mergeAttempts(attempts)
    }

    private fun cloneFromTemplateOnce(
        transport: DeviceTransport,
        templateSettings: DeviceSettings,
        cloneDiagnosticRecoveryAllowed: Boolean,
        currentTimeCompact: () -> String,
        afterReset: () -> List<String>,
        afterStartAttempt: () -> Unit,
        afterCommandAcknowledged: (String) -> Unit,
    ): FirmwareCloneSessionResult {
        val commands = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val traceEntries = mutableListOf<SerialTraceEntry>()

        fun sendAndRead(command: String): List<String> {
            val sentAtMs = System.currentTimeMillis()
            transport.sendCommands(listOf(command))
            commands += command
            traceEntries += SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, command)
            val responseLines = transport.readAvailableLines()
            val receivedAtMs = System.currentTimeMillis()
            lines += responseLines
            traceEntries += responseLines.map { line ->
                SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
            }
            return responseLines
        }

        fun sendAndReadForReply(
            command: String,
            expectedReply: String,
        ): List<String> {
            val sentAtMs = System.currentTimeMillis()
            transport.sendCommands(listOf(command))
            commands += command
            traceEntries += SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, command)

            val responseLines = mutableListOf<String>()
            repeat(CloneReplyReadAttempts) {
                val attemptLines = transport.readAvailableLines()
                if (attemptLines.isNotEmpty()) {
                    val receivedAtMs = System.currentTimeMillis()
                    responseLines += attemptLines
                    lines += attemptLines
                    traceEntries += attemptLines.map { line ->
                        SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
                    }
                    if (FirmwareCloneProtocol.replyMatches(expectedReply, responseLines)) {
                        afterCommandAcknowledged(command)
                        return responseLines
                    }
                }
            }
            return responseLines
        }

        fun probeAfterMissingCloneReply(
            expectedCommand: String,
            expectedReply: String,
        ): Boolean {
            val cloneDiagnosticLines = sendAndRead(CloneDiagnosticCommand)
            if (cloneDiagnosticLines.isEmpty()) {
                sendAndRead(DiagnosticProbeCommand)
            }
            return cloneDiagnosticRecoveryAllowed &&
                cloneDiagnosticConfirmsAcknowledgement(
                    lines = cloneDiagnosticLines,
                    expectedCommand = expectedCommand,
                    expectedReply = expectedReply,
                )
        }

        sendAndRead(ResetCommand)
        val resetLines = afterReset()
        if (resetLines.isNotEmpty()) {
            val receivedAtMs = System.currentTimeMillis()
            lines += resetLines
            traceEntries += resetLines.map { line ->
                SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
            }
        }
        val startResponses = mutableListOf<String>()
        for (attemptIndex in 0 until StartAttempts) {
            val response = sendAndRead(StartCommand)
            startResponses += response
            if (FirmwareCloneProtocol.replyMatches(StartReply, response)) {
                afterCommandAcknowledged(StartCommand)
                break
            }
            if (attemptIndex < StartAttempts - 1) {
                afterStartAttempt()
            }
        }
        if (!FirmwareCloneProtocol.replyMatches(StartReply, startResponses)) {
            return FirmwareCloneSessionResult(
                enteredCloneMode = false,
                acknowledged = false,
                legacyFallbackAllowed = startResponses.isEmpty(),
                plan = null,
                commandsSent = commands,
                linesReceived = lines,
                traceEntries = traceEntries,
                failureMessage =
                    if (startResponses.isEmpty()) {
                        "SignalSlinger did not enter firmware clone mode."
                    } else {
                        "SignalSlinger did not acknowledge firmware clone mode after reset."
                    },
            )
        }

        val functionResponse = sendAndReadForReply("FUN A", "FUN A")
        if (!FirmwareCloneProtocol.replyMatches("FUN A", functionResponse)) {
            if (probeAfterMissingCloneReply(expectedCommand = "FUN A", expectedReply = "FUN A")) {
                afterCommandAcknowledged("FUN A")
            } else {
                return FirmwareCloneSessionResult(
                    enteredCloneMode = true,
                    acknowledged = false,
                    legacyFallbackAllowed = false,
                    plan = null,
                    commandsSent = commands,
                    linesReceived = lines,
                    traceEntries = traceEntries,
                    failureMessage = "Firmware clone failed after `FUN A`; expected `FUN A`.",
                )
            }
        }

        val plan = FirmwareCloneProtocol.buildPlan(
            templateSettings = templateSettings,
            currentTimeCompact = currentTimeCompact(),
        )
        for (step in plan.steps.drop(1)) {
            val responseLines = sendAndReadForReply(step.command, step.expectedReply)
            if (!FirmwareCloneProtocol.replyMatches(step.expectedReply, responseLines)) {
                if (probeAfterMissingCloneReply(expectedCommand = step.command, expectedReply = step.expectedReply)) {
                    afterCommandAcknowledged(step.command)
                } else {
                    return FirmwareCloneSessionResult(
                        enteredCloneMode = true,
                        acknowledged = false,
                        legacyFallbackAllowed = false,
                        plan = plan,
                        commandsSent = commands,
                        linesReceived = lines,
                        traceEntries = traceEntries,
                        failureMessage = "Firmware clone failed after `${step.command}`; expected `${step.expectedReply}`.",
                    )
                }
            }
        }

        return FirmwareCloneSessionResult(
            enteredCloneMode = true,
            acknowledged = true,
            legacyFallbackAllowed = false,
            plan = plan,
            commandsSent = commands,
            linesReceived = lines,
            traceEntries = traceEntries,
        )
    }

    private fun mergeAttempts(attempts: List<FirmwareCloneSessionResult>): FirmwareCloneSessionResult {
        require(attempts.isNotEmpty()) {
            "At least one firmware clone attempt is required."
        }
        val result = attempts.last()
        val attemptCount = attempts.size
        val failureMessage =
            result.failureMessage?.let { message ->
                if (attemptCount > 1) {
                    "$message Firmware clone attempts: $attemptCount."
                } else {
                    message
                }
            }
        return result.copy(
            commandsSent = attempts.flatMap { it.commandsSent },
            linesReceived = attempts.flatMap { it.linesReceived },
            traceEntries = attempts.flatMap { it.traceEntries },
            failureMessage = failureMessage,
        )
    }

    private fun cloneDiagnosticConfirmsAcknowledgement(
        lines: List<String>,
        expectedCommand: String,
        expectedReply: String,
    ): Boolean {
        val expectedCloneCommand = cloneDiagnosticCommandName(expectedCommand, expectedReply)
        return lines.any { line ->
            val diagnostic = CloneDiagnostic.fromLine(line) ?: return@any false
            diagnostic.failures == 0 &&
                diagnostic.lastCommand == expectedCloneCommand &&
                diagnostic.replyMatches(expectedReply)
        }
    }

    private fun cloneDiagnosticCommandName(
        command: String,
        expectedReply: String,
    ): String {
        return when {
            command.startsWith("MAS Q") -> "MAS Q"
            else -> expectedReply
        }
    }

    private data class CloneDiagnostic(
        val lastCommand: String,
        val replyText: String,
        val failures: Int,
    ) {
        fun replyMatches(expectedReply: String): Boolean {
            return FirmwareCloneProtocol.replyMatches(expectedReply, listOf(replyText))
        }

        companion object {
            private val Pattern = Regex("""CLN\s+last=(.+?)\s+reply=(.+?)\s+tries=\d+\s+ok=\d+\s+fail=(\d+)""")

            fun fromLine(line: String): CloneDiagnostic? {
                val match = Pattern.find(line.trim()) ?: return null
                return CloneDiagnostic(
                    lastCommand = match.groupValues[1].trim(),
                    replyText = match.groupValues[2].trim(),
                    failures = match.groupValues[3].toInt(),
                )
            }
        }
    }
}
