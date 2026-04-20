package com.openardf.serialslinger.model

import java.time.Duration

enum class StartTimeAdjustmentOptionKind {
    KEEP_EXISTING_DURATION,
    ADJUST_FOR_DEFAULT_DURATION,
    LEAVE_FINISH_UNCHANGED,
    DISABLE_EVENT,
}

data class StartTimeAdjustmentOption(
    val kind: StartTimeAdjustmentOptionKind,
    val duration: Duration? = null,
)

object StartTimeAdjustmentPlanner {
    fun plan(
        currentStartTimeCompact: String?,
        currentFinishTimeCompact: String?,
        proposedStartTimeCompact: String?,
        defaultEventLengthMinutes: Int,
    ): List<StartTimeAdjustmentOption> {
        val defaultDuration = Duration.ofMinutes(defaultEventLengthMinutes.toLong())
        val existingDuration = JvmTimeSupport.validEventDuration(
            startTimeCompact = currentStartTimeCompact,
            finishTimeCompact = currentFinishTimeCompact,
        )
        if (existingDuration == null) {
            return listOf(
                StartTimeAdjustmentOption(
                    kind = StartTimeAdjustmentOptionKind.ADJUST_FOR_DEFAULT_DURATION,
                    duration = defaultDuration,
                ),
            )
        }

        return buildList {
            if (existingDuration != defaultDuration) {
                add(
                    StartTimeAdjustmentOption(
                        kind = StartTimeAdjustmentOptionKind.KEEP_EXISTING_DURATION,
                        duration = existingDuration,
                    ),
                )
            }
            add(
                StartTimeAdjustmentOption(
                    kind = StartTimeAdjustmentOptionKind.ADJUST_FOR_DEFAULT_DURATION,
                    duration = defaultDuration,
                ),
            )
            if (
                proposedStartTimeCompact != null &&
                currentFinishTimeCompact != null &&
                proposedStartTimeCompact == currentFinishTimeCompact
            ) {
                add(StartTimeAdjustmentOption(kind = StartTimeAdjustmentOptionKind.DISABLE_EVENT))
            } else {
                val unchangedDuration = JvmTimeSupport.validEventDuration(
                    startTimeCompact = proposedStartTimeCompact,
                    finishTimeCompact = currentFinishTimeCompact,
                )
                if (unchangedDuration != null && unchangedDuration != existingDuration && unchangedDuration != defaultDuration) {
                    add(
                        StartTimeAdjustmentOption(
                            kind = StartTimeAdjustmentOptionKind.LEAVE_FINISH_UNCHANGED,
                            duration = unchangedDuration,
                        ),
                    )
                }
            }
        }
    }
}
