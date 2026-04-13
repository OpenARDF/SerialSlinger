package com.openardf.serialslinger.session

import com.openardf.serialslinger.model.ConnectionState
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.DeviceSnapshot
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.WritePlan
import com.openardf.serialslinger.protocol.DeviceReportUpdate
import com.openardf.serialslinger.protocol.SignalSlingerFirmwareSupport
import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import com.openardf.serialslinger.transport.DeviceTransport

data class DeviceLoadResult(
    val state: DeviceSessionState,
    val commandsSent: List<String>,
    val linesReceived: List<String>,
    val traceEntries: List<SerialTraceEntry>,
)

enum class SerialTraceDirection {
    TX,
    RX;

    val label: String
        get() = name
}

data class SerialTraceEntry(
    val timestampMs: Long,
    val direction: SerialTraceDirection,
    val payload: String,
)

data class SettingVerification(
    val fieldKey: SettingKey,
    val expectedValue: Any?,
    val actualValue: Any?,
    val observedInReadback: Boolean,
    val verified: Boolean,
)

data class DeviceSubmitResult(
    val state: DeviceSessionState,
    val commandsSent: List<String>,
    val linesReceived: List<String>,
    val readbackCommandsSent: List<String>,
    val readbackLinesReceived: List<String>,
    val verifications: List<SettingVerification>,
    val submitTraceEntries: List<SerialTraceEntry>,
    val readbackTraceEntries: List<SerialTraceEntry>,
)

object DeviceSessionController {
    fun connectAndLoad(
        transport: DeviceTransport,
        baseSettings: DeviceSettings = DeviceSettings.empty(),
    ): DeviceLoadResult {
        transport.connect()
        val connectedState = DeviceSessionWorkflow.connected(baseSettings)
        return refreshFromDevice(connectedState, transport, startEditing = true)
    }

    fun refreshFromDevice(
        state: DeviceSessionState,
        transport: DeviceTransport,
        startEditing: Boolean = false,
    ): DeviceLoadResult {
        val commands = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val traceEntries = mutableListOf<SerialTraceEntry>()
        var updatedState = state.copy(connectionState = ConnectionState.CONNECTED)

        for (command in SignalSlingerFirmwareSupport.bootstrapLoadCommands()) {
            val sentAtMs = System.currentTimeMillis()
            transport.sendCommands(listOf(command))
            traceEntries += SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, command)
            val responseLines = transport.readAvailableLines()
            val receivedAtMs = System.currentTimeMillis()
            commands += command
            lines += responseLines
            traceEntries += responseLines.map { line ->
                SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
            }
            updatedState = DeviceSessionWorkflow.ingestReportLines(updatedState, responseLines)
        }

        val firmwareProfile = SignalSlingerFirmwareSupport.resolve(
            updatedState.snapshot?.info?.softwareVersion,
        )
        updatedState = updatedState.copy(
            snapshot = updatedState.snapshot?.copy(capabilities = firmwareProfile.capabilities),
        )
        for (command in firmwareProfile.loadCommandsAfterVersion) {
            val resolvedCommand = resolvePatternSpeedReadCommand(
                command = command,
                eventType = updatedState.snapshot?.settings?.eventType,
            )
            val sentAtMs = System.currentTimeMillis()
            transport.sendCommands(listOf(resolvedCommand))
            traceEntries += SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, resolvedCommand)
            val responseLines = transport.readAvailableLines()
            val receivedAtMs = System.currentTimeMillis()
            commands += resolvedCommand
            lines += responseLines
            traceEntries += responseLines.map { line ->
                SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
            }
            updatedState = DeviceSessionWorkflow.ingestReportLines(updatedState, responseLines)
        }

        if (startEditing && updatedState.editableSettings == null) {
            updatedState = DeviceSessionWorkflow.startEditing(updatedState)
        }

        return DeviceLoadResult(
            state = updatedState,
            commandsSent = commands,
            linesReceived = lines,
            traceEntries = traceEntries,
        )
    }

    fun submitEdits(
        state: DeviceSessionState,
        editedSettings: EditableDeviceSettings,
        transport: DeviceTransport,
    ): DeviceSubmitResult {
        val submission = DeviceSessionWorkflow.submitChanges(state, editedSettings)
        val submitLines = mutableListOf<String>()
        val submitTraceEntries = mutableListOf<SerialTraceEntry>()
        for (command in submission.commands) {
            val sentAtMs = System.currentTimeMillis()
            transport.sendCommands(listOf(command))
            submitTraceEntries += SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, command)
            val responseLines = transport.readAvailableLines()
            val receivedAtMs = System.currentTimeMillis()
            submitLines += responseLines
            submitTraceEntries += responseLines.map { line ->
                SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
            }
        }
        val validatedSettings = editedSettings.toValidatedDeviceSettings()
        val baseSnapshot = requireNotNull(submission.state.snapshot) {
            "Cannot finalize submission without a loaded snapshot."
        }

        var nextState = submission.state.copy(
            snapshot = baseSnapshot.copy(
                status = baseSnapshot.status.copy(connectionState = ConnectionState.CONNECTED),
                settings = validatedSettings,
            ),
            editableSettings = EditableDeviceSettings.fromDeviceSettings(validatedSettings),
            pendingSubmitCommands = emptyList(),
        )

        if (submitLines.isNotEmpty()) {
            nextState = DeviceSessionWorkflow.ingestReportLines(nextState, submitLines)
        }

        val firmwareProfile = SignalSlingerFirmwareSupport.resolve(
            submission.state.snapshot.info.softwareVersion,
        )
        val readbackCommands = buildVerificationReadbackCommands(
            writePlan = submission.writePlan,
            expectedSettings = validatedSettings,
            firmwareProfile = firmwareProfile,
        )
        val readbackLines = mutableListOf<String>()
        val readbackTraceEntries = mutableListOf<SerialTraceEntry>()
        val observedReadbackKeys = linkedSetOf<SettingKey>()

        for (command in readbackCommands) {
            val sentAtMs = System.currentTimeMillis()
            transport.sendCommands(listOf(command))
            readbackTraceEntries += SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, command)
            val responseLines = transport.readAvailableLines()
            val receivedAtMs = System.currentTimeMillis()
            readbackLines += responseLines
            readbackTraceEntries += responseLines.map { line ->
                SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
            }
            observedReadbackKeys += extractObservedSettingKeys(responseLines)
            nextState = DeviceSessionWorkflow.ingestReportLines(nextState, responseLines)
        }

        nextState = nextState.copy(
            editableSettings = EditableDeviceSettings.fromDeviceSettings(
                nextState.snapshot?.settings ?: validatedSettings,
            ),
            pendingSubmitCommands = emptyList(),
        )

        val actualSettings = nextState.snapshot?.settings ?: validatedSettings
        val verifications = buildVerifications(
            writePlan = submission.writePlan,
            expectedSettings = validatedSettings,
            actualSettings = actualSettings,
            observedReadbackKeys = observedReadbackKeys,
        )

        return DeviceSubmitResult(
            state = nextState,
            commandsSent = submission.commands,
            linesReceived = submitLines,
            readbackCommandsSent = readbackCommands,
            readbackLinesReceived = readbackLines,
            verifications = verifications,
            submitTraceEntries = submitTraceEntries,
            readbackTraceEntries = readbackTraceEntries,
        )
    }

    fun disconnect(state: DeviceSessionState, transport: DeviceTransport): DeviceSessionState {
        transport.disconnect()
        val snapshot = state.snapshot ?: DeviceSnapshot()

        return state.copy(
            connectionState = ConnectionState.DISCONNECTED,
            snapshot = snapshot.copy(
                status = snapshot.status.copy(connectionState = ConnectionState.DISCONNECTED),
            ),
            pendingSubmitCommands = emptyList(),
        )
    }

    private fun buildVerificationReadbackCommands(
        writePlan: WritePlan,
        expectedSettings: DeviceSettings,
        firmwareProfile: com.openardf.serialslinger.protocol.SignalSlingerFirmwareProfile,
    ): List<String> {
        val commands = linkedSetOf<String>()

        for (change in writePlan.changes.sortedBy { it.writeOrder }) {
            if (!change.requiresVerification) {
                continue
            }

            commands += verificationCommandsFor(
                fieldKey = change.fieldKey,
                expectedSettings = expectedSettings,
                firmwareProfile = firmwareProfile,
            )
        }

        return commands.toList()
    }

    private fun verificationCommandsFor(
        fieldKey: SettingKey,
        expectedSettings: DeviceSettings,
        firmwareProfile: com.openardf.serialslinger.protocol.SignalSlingerFirmwareProfile,
    ): List<String> {
        val commands = firmwareProfile.verificationReadbackCommands[fieldKey].orEmpty()
        return commands.map { command ->
            resolvePatternSpeedReadCommand(command, expectedSettings.eventType)
        }
    }

    private fun resolvePatternSpeedReadCommand(
        command: String,
        eventType: EventType?,
    ): String {
        return if (command == "SPD P" && eventType == EventType.FOXORING) {
            "SPD F"
        } else {
            command
        }
    }

    private fun extractObservedSettingKeys(lines: List<String>): Set<SettingKey> {
        return buildSet {
            for (line in lines) {
                val update = SignalSlingerProtocolCodec.parseReportLine(line) ?: continue
                addAll(extractObservedSettingKeys(update))
            }
        }
    }

    private fun extractObservedSettingKeys(update: DeviceReportUpdate): Set<SettingKey> {
        val patch = update.settingsPatch ?: return emptySet()

        return buildSet {
            if (patch.stationId != null) add(SettingKey.STATION_ID)
            if (patch.eventType != null) add(SettingKey.EVENT_TYPE)
            if (patch.foxRole != null) add(SettingKey.FOX_ROLE)
            if (patch.patternText != null) add(SettingKey.PATTERN_TEXT)
            if (patch.idCodeSpeedWpm != null) add(SettingKey.ID_CODE_SPEED_WPM)
            if (patch.patternCodeSpeedWpm != null) add(SettingKey.PATTERN_CODE_SPEED_WPM)
            if (patch.currentTimeCompact != null) add(SettingKey.CURRENT_TIME)
            if (patch.startTimeCompact != null) add(SettingKey.START_TIME)
            if (patch.finishTimeCompact != null) add(SettingKey.FINISH_TIME)
            if (patch.daysToRun != null) add(SettingKey.DAYS_TO_RUN)
            if (patch.defaultFrequencyHz != null) add(SettingKey.DEFAULT_FREQUENCY_HZ)
            if (patch.lowFrequencyHz != null) add(SettingKey.LOW_FREQUENCY_HZ)
            if (patch.mediumFrequencyHz != null) add(SettingKey.MEDIUM_FREQUENCY_HZ)
            if (patch.highFrequencyHz != null) add(SettingKey.HIGH_FREQUENCY_HZ)
            if (patch.beaconFrequencyHz != null) add(SettingKey.BEACON_FREQUENCY_HZ)
            if (patch.lowBatteryThresholdVolts != null) add(SettingKey.LOW_BATTERY_THRESHOLD_VOLTS)
            if (patch.externalBatteryControlMode != null) add(SettingKey.EXTERNAL_BATTERY_CONTROL_MODE)
            if (patch.transmissionsEnabled != null) add(SettingKey.TRANSMISSIONS_ENABLED)
        }
    }

    private fun buildVerifications(
        writePlan: WritePlan,
        expectedSettings: DeviceSettings,
        actualSettings: DeviceSettings,
        observedReadbackKeys: Set<SettingKey>,
    ): List<SettingVerification> {
        return writePlan.changes
            .sortedBy { it.writeOrder }
            .filter { it.requiresVerification }
            .map { change ->
                val expectedValue = readSettingValue(expectedSettings, change.fieldKey)
                val observedInReadback = change.fieldKey in observedReadbackKeys
                val actualValue = if (observedInReadback) {
                    readSettingValue(actualSettings, change.fieldKey)
                } else {
                    null
                }

                SettingVerification(
                    fieldKey = change.fieldKey,
                    expectedValue = expectedValue,
                    actualValue = actualValue,
                    observedInReadback = observedInReadback,
                    verified = observedInReadback && expectedValue == actualValue,
                )
            }
    }

    private fun readSettingValue(settings: DeviceSettings, fieldKey: SettingKey): Any? {
        return when (fieldKey) {
            SettingKey.STATION_ID -> settings.stationId
            SettingKey.EVENT_TYPE -> settings.eventType
            SettingKey.FOX_ROLE -> settings.foxRole
            SettingKey.PATTERN_TEXT -> settings.patternText
            SettingKey.ID_CODE_SPEED_WPM -> settings.idCodeSpeedWpm
            SettingKey.PATTERN_CODE_SPEED_WPM -> settings.patternCodeSpeedWpm
            SettingKey.CURRENT_TIME -> settings.currentTimeCompact
            SettingKey.START_TIME -> settings.startTimeCompact
            SettingKey.FINISH_TIME -> settings.finishTimeCompact
            SettingKey.DAYS_TO_RUN -> settings.daysToRun
            SettingKey.DEFAULT_FREQUENCY_HZ -> settings.defaultFrequencyHz
            SettingKey.LOW_FREQUENCY_HZ -> settings.lowFrequencyHz
            SettingKey.MEDIUM_FREQUENCY_HZ -> settings.mediumFrequencyHz
            SettingKey.HIGH_FREQUENCY_HZ -> settings.highFrequencyHz
            SettingKey.BEACON_FREQUENCY_HZ -> settings.beaconFrequencyHz
            SettingKey.LOW_BATTERY_THRESHOLD_VOLTS -> settings.lowBatteryThresholdVolts
            SettingKey.EXTERNAL_BATTERY_CONTROL_MODE -> settings.externalBatteryControlMode
            SettingKey.TRANSMISSIONS_ENABLED -> settings.transmissionsEnabled
        }
    }
}
