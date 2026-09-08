package com.openardf.serialslinger.model

import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import java.util.TimeZone
import kotlin.test.*

class SessionClockCompatibilityTest {
    @Test fun signalSlingerStopTimesAndScheduleMatchingPreserveDeviceWallClockInEveryHostZone() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("America/New_York", "UTC", "Asia/Kolkata", "Pacific/Auckland")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                // Firmware's 2026-09-04 09:00 schedule ends at 18:00 on September 6.
                val record = assertNotNull(SignalSlingerProtocolCodec.parseReportLine(
                    "* Session record: v=1 seq=9 base=1788512400 start=1788685200 finish=1788717600 at=1788717600 action=4 reason=1 flags=5 temp=350 limit=65",
                )?.deviceStatusPatch?.sessionHistoryRecord)
                assertEquals("260906180000", record.timestampCompact, zone)
                val status = JvmTimeSupport.describeEventStatus(false, null, "260906180001", "260904090000",
                    "260904180000", null, 3, SessionReport(4, 1, 0, false), listOf(record))
                assertTrue(status.contains("device confirmed"), zone)
                assertTrue(status.contains("last stop: scheduled finish (2026-09-06 18:00:00)"), zone)
                assertTrue(JvmTimeSupport.describeSessionHistory(listOf(record)).contains("2026-09-06 18:00:00"), zone)
                val otherSchedule = JvmTimeSupport.describeEventStatus(false, null, "260907180001", "260907090000",
                    "260907180000", null, 1, null, listOf(record))
                assertFalse(otherSchedule.contains("last stop:"), zone)
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun arduconUtcEpochStillConvertsToHostLocalTime() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val report = SignalSlingerProtocolCodec.parseReportLine("Epoch:1788717600")
            assertEquals("260906140000", report?.settingsPatch?.currentTimeCompact)
        } finally { TimeZone.setDefault(original) }
    }
}
