package com.openardf.serialslinger.session

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.protocol.FirmwareClonePlan
import com.openardf.serialslinger.protocol.FirmwareCloneProtocol
import com.openardf.serialslinger.transport.DeviceTransport

data class FirmwareCloneSessionResult(
    val enteredCloneMode: Boolean,
    val acknowledged: Boolean,
    val plan: FirmwareClonePlan?,
    val commandsSent: List<String>,
    val linesReceived: List<String>,
    val traceEntries: List<SerialTraceEntry>,
    val failureMessage: String? = null,
    val retryable: Boolean = true,
) {
    val succeeded: Boolean
        get() = enteredCloneMode && acknowledged && failureMessage == null
}

object FirmwareCloneSession {
    private const val PreCloneStopCommand = "GO 0"
    private const val UiStatusCommand = "UI S"
    private const val UiClearCommand = "UI C"
    private const val StartCommand = "MAS P"
    private const val StartReply = "MAS S"
    private const val StartAttempts = 4
    private const val CloneReplyReadAttempts = 3
    private const val CloneSessionAttempts = 3

    fun cloneFromTemplate(
        transport: DeviceTransport,
        templateSettings: DeviceSettings,
        currentTimeCompact: () -> String,
        afterPreCloneStop: () -> List<String> = { emptyList() },
        afterStartAttempt: () -> Unit = {},
        afterCommandAcknowledged: (String) -> Unit = {},
    ): FirmwareCloneSessionResult {
        val attempts = mutableListOf<FirmwareCloneSessionResult>()
        repeat(CloneSessionAttempts) { attemptIndex ->
            val result =
                cloneFromTemplateOnce(
                    transport = transport,
                    templateSettings = templateSettings,
                    currentTimeCompact = currentTimeCompact,
                    afterPreCloneStop = afterPreCloneStop,
                    afterStartAttempt = afterStartAttempt,
                    afterCommandAcknowledged = afterCommandAcknowledged,
                )
            attempts += result
            if (result.succeeded || !result.enteredCloneMode || !result.retryable || attemptIndex == CloneSessionAttempts - 1) {
                return mergeAttempts(attempts)
            }
        }
        return mergeAttempts(attempts)
    }

    private fun cloneFromTemplateOnce(
        transport: DeviceTransport,
        templateSettings: DeviceSettings,
        currentTimeCompact: () -> String,
        afterPreCloneStop: () -> List<String>,
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

        sendAndRead(PreCloneStopCommand)
        val preCloneStopLines = afterPreCloneStop()
        if (preCloneStopLines.isNotEmpty()) {
            val receivedAtMs = System.currentTimeMillis()
            lines += preCloneStopLines
            traceEntries += preCloneStopLines.map { line ->
                SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
            }
        }
        val uiStatusLines = sendAndRead(UiStatusCommand)
        if (uiStatusLines.cloneSuccessCountdown() > 0) {
            sendAndRead(UiClearCommand)
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
                plan = null,
                commandsSent = commands,
                linesReceived = lines,
                traceEntries = traceEntries,
                failureMessage =
                    if (startResponses.isEmpty()) {
                        "SignalSlinger did not enter firmware clone mode."
                    } else {
                        "SignalSlinger did not acknowledge firmware clone mode after the pre-clone stop command."
                    },
            )
        }

        val functionResponse = sendAndReadForReply("FUN A", "FUN A")
        if (!FirmwareCloneProtocol.replyMatches("FUN A", functionResponse)) {
            return FirmwareCloneSessionResult(
                enteredCloneMode = true,
                acknowledged = false,
                plan = null,
                commandsSent = commands,
                linesReceived = lines,
                traceEntries = traceEntries,
                failureMessage = "Firmware clone failed after `FUN A`; expected `FUN A`.",
            )
        }

        val plan = FirmwareCloneProtocol.buildPlan(
            templateSettings = templateSettings,
            currentTimeCompact = currentTimeCompact(),
        )
        for (step in plan.steps.drop(1)) {
            val responseLines = sendAndReadForReply(step.command, step.expectedReply)
            if (!FirmwareCloneProtocol.replyMatches(step.expectedReply, responseLines)) {
                val isFinalChecksumStep = step == plan.steps.last()
                return FirmwareCloneSessionResult(
                    enteredCloneMode = true,
                    acknowledged = false,
                    plan = plan,
                    commandsSent = commands,
                    linesReceived = lines,
                    traceEntries = traceEntries,
                    failureMessage = "Firmware clone failed after `${step.command}`; expected `${step.expectedReply}`.",
                    retryable = !isFinalChecksumStep,
                )
            }
        }

        return FirmwareCloneSessionResult(
            enteredCloneMode = true,
            acknowledged = true,
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

    private fun List<String>.cloneSuccessCountdown(): Int {
        return firstNotNullOfOrNull { line ->
            val tokens = line.trim().split(Regex("\\s+"))
            tokens.firstNotNullOfOrNull { token ->
                val parts = token.split("=", limit = 2)
                if (parts.size == 2 && parts[0] == "cs") {
                    parts[1].toIntOrNull()
                } else {
                    null
                }
            }
        } ?: 0
    }

}
