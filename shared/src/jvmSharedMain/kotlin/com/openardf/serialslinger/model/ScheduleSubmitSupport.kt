package com.openardf.serialslinger.model

import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import java.time.Duration

data class ScheduleEditRequest(
    val startTimeCompact: String?,
    val finishTimeCompact: String?,
    val requestedDaysToRun: Int? = null,
    val forceWriteKeys: Set<SettingKey> = emptySet(),
) {
    /** Keep the selected count as the write and verification target, even after a refresh. */
    fun applyTo(editable: EditableDeviceSettings): EditableDeviceSettings = editable.copy(
        startTimeCompact = editable.startTimeCompact.copy(editedValue = startTimeCompact),
        finishTimeCompact = editable.finishTimeCompact.copy(editedValue = finishTimeCompact),
        daysToRun = editable.daysToRun.copy(editedValue = requestedDaysToRun ?: editable.daysToRun.editedValue),
    )
}

data class DaysToRunEditRequest(
    val daysToRun: Int,
    val startTimeCompact: String?,
    val finishTimeCompact: String?,
    val forceWriteKeys: Set<SettingKey> = emptySet(),
)

object ScheduleSubmitSupport {
    fun sameDeviceForSchedule(expected: DeviceInfo?, actual: DeviceInfo?): Boolean =
        expected != null && actual != null &&
            expected.deviceUniqueId == actual.deviceUniqueId &&
            expected.serialPortName == actual.serialPortName &&
            expected.productName == actual.productName

    /** Only fresh readback can confirm the count; an inherited snapshot is not evidence. */
    fun requireSelectedDaysReadback(requestedDaysToRun: Int?, readbackLines: List<String>) {
        if (requestedDaysToRun == null) return
        val observed = readbackLines.mapNotNull {
            SignalSlingerProtocolCodec.parseReportLine(it)?.settingsPatch?.daysToRun
        }.lastOrNull()
        check(observed == requestedDaysToRun) {
            "Days To Run verification failed: selected=$requestedDaysToRun, readback=${observed ?: "not reported"}."
        }
    }

    fun relativeStartCommands(
        offsetCommand: String,
        finishOffsetCommand: String,
        preservedDaysToRun: Int? = null,
    ): List<String> {
        return buildList {
            add("CLK S $offsetCommand")
            add("CLK F $finishOffsetCommand")
            preservedDaysToRun?.let { add("CLK D $it") }
        }
    }

    fun relativeFinishCommands(
        offsetCommand: String,
        preservedDaysToRun: Int? = null,
    ): List<String> {
        return buildList {
            add("CLK F $offsetCommand")
            preservedDaysToRun?.let { add("CLK D $it") }
        }
    }

    fun disableEventCommands(): List<String> {
        return listOf("CLK S =")
    }

    fun absoluteStartEdit(
        currentSettings: DeviceSettings,
        normalizedStartTime: String,
        requestedFinishTimeCompact: String? = null,
        defaultEventLengthMinutes: Int? = null,
        requestedDaysToRun: Int? = null,
    ): ScheduleEditRequest {
        val resolvedStartTime = JvmTimeSupport.resolveStartTimeForChange(
            startTimeCompact = normalizedStartTime,
            currentTimeCompact = currentSettings.currentTimeCompact,
        )
        val resolvedFinishTime =
            resolvedStartTime?.let { startTimeCompact ->
                requestedFinishTimeCompact?.let { finishTimeCompact ->
                    JvmTimeSupport.resolveScheduleForFinishTimeChange(
                        startTimeCompact = startTimeCompact,
                        finishTimeCompact = finishTimeCompact,
                        currentTimeCompact = currentSettings.currentTimeCompact,
                    ).finishTimeCompact
                } ?: defaultEventLengthMinutes?.let { defaultMinutes ->
                    JvmTimeSupport.finishTimeCompactFromStart(
                        startTimeCompact = startTimeCompact,
                        duration = Duration.ofMinutes(defaultMinutes.toLong()),
                    )
                }
            }

        return ScheduleEditRequest(
            startTimeCompact = resolvedStartTime,
            finishTimeCompact = resolvedFinishTime,
            requestedDaysToRun = requestedDaysToRun?.let(DaysToRunSupport::validate),
            forceWriteKeys = if (requestedDaysToRun != null) setOf(SettingKey.DAYS_TO_RUN) else emptySet(),
        )
    }

    fun absoluteFinishEdit(
        currentSettings: DeviceSettings,
        normalizedFinishTime: String,
        requestedDaysToRun: Int? = null,
    ): ScheduleEditRequest {
        val resolvedSchedule = JvmTimeSupport.resolveScheduleForFinishTimeChange(
            startTimeCompact = currentSettings.startTimeCompact,
            finishTimeCompact = normalizedFinishTime,
            currentTimeCompact = currentSettings.currentTimeCompact,
        )
        return ScheduleEditRequest(
            startTimeCompact = resolvedSchedule.startTimeCompact,
            finishTimeCompact = resolvedSchedule.finishTimeCompact,
            requestedDaysToRun = requestedDaysToRun?.let(DaysToRunSupport::validate),
            forceWriteKeys = if (requestedDaysToRun != null) setOf(SettingKey.DAYS_TO_RUN) else emptySet(),
        )
    }

    fun absoluteFinishEditWithDurationOverride(
        currentSettings: DeviceSettings,
        normalizedFinishTime: String,
        requestedDurationOverride: Duration? = null,
        requestedDaysToRun: Int? = null,
    ): ScheduleEditRequest {
        return if (requestedDurationOverride != null) {
            absoluteDurationEdit(
                currentSettings = currentSettings,
                requestedDuration = requestedDurationOverride,
                requestedDaysToRun = requestedDaysToRun,
            )
        } else {
            absoluteFinishEdit(
                currentSettings = currentSettings,
                normalizedFinishTime = normalizedFinishTime,
                requestedDaysToRun = requestedDaysToRun,
            )
        }
    }

    fun absoluteDurationEdit(
        currentSettings: DeviceSettings,
        requestedDuration: Duration,
        requestedDaysToRun: Int? = null,
    ): ScheduleEditRequest {
        require(!requestedDuration.isNegative && !requestedDuration.isZero) {
            "Event duration must be positive."
        }
        val normalizedStartTime = JvmTimeSupport.validateStartTimeForWrite(currentSettings.startTimeCompact)
            ?: error("Set a valid Start Time first before changing Duration.")
        return absoluteFinishEdit(
            currentSettings = currentSettings,
            normalizedFinishTime = JvmTimeSupport.finishTimeCompactFromStart(normalizedStartTime, requestedDuration),
            requestedDaysToRun = requestedDaysToRun,
        )
    }

    fun daysToRunEdit(
        currentSettings: DeviceSettings,
        requestedDaysToRun: Int,
        requestedFinishTimeCompact: String? = null,
    ): DaysToRunEditRequest {
        require(requestedDaysToRun >= 1) {
            "Days To Run must be at least 1."
        }
        val normalizedRequestedFinishTime = requestedFinishTimeCompact
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val scheduleEdit = normalizedRequestedFinishTime?.let { finishTimeCompact ->
            absoluteFinishEdit(
                currentSettings = currentSettings,
                normalizedFinishTime = finishTimeCompact,
                requestedDaysToRun = requestedDaysToRun,
            )
        }
        return DaysToRunEditRequest(
            daysToRun = requestedDaysToRun,
            startTimeCompact = scheduleEdit?.startTimeCompact ?: currentSettings.startTimeCompact,
            finishTimeCompact = scheduleEdit?.finishTimeCompact ?: currentSettings.finishTimeCompact,
            forceWriteKeys = scheduleEdit?.forceWriteKeys ?: emptySet(),
        )
    }
}
