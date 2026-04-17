package com.openardf.serialslinger.session

import com.openardf.serialslinger.model.ConnectionState
import com.openardf.serialslinger.model.DeviceInfo
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.DeviceSnapshot
import com.openardf.serialslinger.model.DeviceStatus
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.WritePlan
import com.openardf.serialslinger.model.WritePlanner
import com.openardf.serialslinger.protocol.DeviceReportUpdate
import com.openardf.serialslinger.protocol.SignalSlingerFirmwareSupport
import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec

data class DeviceSessionState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val snapshot: DeviceSnapshot? = null,
    val editableSettings: EditableDeviceSettings? = null,
    val pendingSubmitCommands: List<String> = emptyList(),
    val lastError: String? = null,
)

data class DeviceSubmission(
    val state: DeviceSessionState,
    val writePlan: WritePlan,
    val commands: List<String>,
)

object DeviceSessionWorkflow {
    fun connected(
        baseSettings: DeviceSettings = DeviceSettings.empty(),
    ): DeviceSessionState {
        return DeviceSessionState(
            connectionState = ConnectionState.CONNECTED,
            snapshot = DeviceSnapshot(
                status = DeviceStatus(connectionState = ConnectionState.CONNECTED),
                settings = baseSettings,
            ),
        )
    }

    fun ingestReportLines(
        state: DeviceSessionState,
        lines: List<String>,
    ): DeviceSessionState {
        var snapshot = state.snapshot ?: DeviceSnapshot()

        for (line in lines) {
            val update = SignalSlingerProtocolCodec.parseReportLine(line) ?: continue
            snapshot = snapshot.applyUpdate(update, state.connectionState)
        }

        val editableSettings = when {
            state.editableSettings == null -> null
            state.editableSettings.hasDirtyFields -> state.editableSettings
            else -> EditableDeviceSettings.fromDeviceSettings(snapshot.settings)
        }

        return state.copy(
            snapshot = snapshot,
            editableSettings = editableSettings,
            lastError = null,
        )
    }

    fun startEditing(state: DeviceSessionState): DeviceSessionState {
        val snapshot = state.snapshot ?: DeviceSnapshot(
            status = DeviceStatus(connectionState = state.connectionState),
        )

        return state.copy(
            snapshot = snapshot,
            editableSettings = EditableDeviceSettings.fromDeviceSettings(snapshot.settings),
        )
    }

    fun submitChanges(
        state: DeviceSessionState,
        editedSettings: EditableDeviceSettings,
    ): DeviceSubmission {
        val snapshot = requireNotNull(state.snapshot) {
            "Cannot submit changes before a device snapshot has been loaded."
        }

        val validatedSettings = editedSettings.toValidatedDeviceSettings()
        val writePlan = WritePlanner.create(snapshot.settings, validatedSettings)
        val commands = SignalSlingerProtocolCodec.encodeWritePlan(writePlan, validatedSettings)

        return DeviceSubmission(
            state = state.copy(
                editableSettings = editedSettings,
                pendingSubmitCommands = commands,
                lastError = null,
            ),
            writePlan = writePlan,
            commands = commands,
        )
    }

    private fun DeviceSnapshot.applyUpdate(
        update: DeviceReportUpdate,
        connectionState: ConnectionState,
    ): DeviceSnapshot {
        val nextInfo = info.copy(
            softwareVersion = update.deviceInfoPatch?.softwareVersion ?: info.softwareVersion,
            hardwareBuild = update.deviceInfoPatch?.hardwareBuild ?: info.hardwareBuild,
        )
        val firmwareProfile = SignalSlingerFirmwareSupport.resolve(nextInfo.softwareVersion)

        return copy(
            info = nextInfo,
            status = status.copy(
                connectionState = connectionState,
                temperatureC = update.deviceStatusPatch?.temperatureC ?: status.temperatureC,
                internalBatteryVolts = update.deviceStatusPatch?.internalBatteryVolts ?: status.internalBatteryVolts,
                externalBatteryVolts = update.deviceStatusPatch?.externalBatteryVolts ?: status.externalBatteryVolts,
                daysRemaining = update.deviceStatusPatch?.daysRemaining ?: status.daysRemaining,
                eventEnabled = update.deviceStatusPatch?.eventEnabled ?: status.eventEnabled,
                eventStateSummary = update.deviceStatusPatch?.eventStateSummary ?: status.eventStateSummary,
                eventStartsInSummary = update.deviceStatusPatch?.eventStartsInSummary ?: status.eventStartsInSummary,
                eventDurationSummary = update.deviceStatusPatch?.eventDurationSummary ?: status.eventDurationSummary,
            ),
            settings = update.settingsPatch?.applyTo(settings) ?: settings,
            capabilities = firmwareProfile.capabilities,
        )
    }
}
