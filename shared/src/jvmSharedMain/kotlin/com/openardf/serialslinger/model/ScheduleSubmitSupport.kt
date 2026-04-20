package com.openardf.serialslinger.model

import java.time.Duration

data class ScheduleEditRequest(
    val startTimeCompact: String?,
    val finishTimeCompact: String?,
    val forceWriteKeys: Set<SettingKey> = emptySet(),
)

object ScheduleSubmitSupport {
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
        preserveDaysToRun: Boolean = false,
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
            forceWriteKeys = if (preserveDaysToRun) setOf(SettingKey.DAYS_TO_RUN) else emptySet(),
        )
    }

    fun absoluteFinishEdit(
        currentSettings: DeviceSettings,
        normalizedFinishTime: String,
        preserveDaysToRun: Boolean = false,
    ): ScheduleEditRequest {
        val resolvedSchedule = JvmTimeSupport.resolveScheduleForFinishTimeChange(
            startTimeCompact = currentSettings.startTimeCompact,
            finishTimeCompact = normalizedFinishTime,
            currentTimeCompact = currentSettings.currentTimeCompact,
        )
        return ScheduleEditRequest(
            startTimeCompact = resolvedSchedule.startTimeCompact,
            finishTimeCompact = resolvedSchedule.finishTimeCompact,
            forceWriteKeys = if (preserveDaysToRun) setOf(SettingKey.DAYS_TO_RUN) else emptySet(),
        )
    }
}
