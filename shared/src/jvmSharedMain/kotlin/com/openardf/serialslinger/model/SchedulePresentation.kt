package com.openardf.serialslinger.model

import java.time.format.DateTimeFormatter

object SchedulePresentation {
    fun scheduleLines(settings: DeviceSettings): List<String> {
        val start = settings.startTimeCompact?.let(JvmTimeSupport::parseCompactTimestamp) ?: return emptyList()
        val finish = settings.finishTimeCompact?.let(JvmTimeSupport::parseCompactTimestamp) ?: return emptyList()
        if (!finish.isAfter(start)) return listOf("Schedule disabled or invalid (Finish must follow Start).")
        val days = settings.daysToRun
        if (days !in DaysToRunSupport.minimum..DaysToRunSupport.maximum) {
            return listOf("Schedule unavailable: invalid Days To Run ($days).")
        }
        val formatter = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")
        return buildList {
            add("Schedule: $days ${if (days == 1) "day" else "days"}")
            repeat(days) { day ->
                add("${day + 1}. ${start.plusDays(day.toLong()).format(formatter)} – ${finish.plusDays(day.toLong()).format(formatter)}")
            }
        }
    }

}
