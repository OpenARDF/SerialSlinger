package com.openardf.serialslinger.app

import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import com.openardf.serialslinger.session.DeviceLoadResult

internal object DesktopLoadValidationSupport {
    fun validate(portPath: String, result: DeviceLoadResult): String? {
        if (result.linesReceived.isEmpty()) {
            return "No response from device on `$portPath` while loading settings."
        }
        if (result.linesReceived.none { SignalSlingerProtocolCodec.parseReportLine(it) != null }) {
            return "No recognizable device response on `$portPath` while loading settings."
        }
        if (settingsOrStatusResponseCount(result) == 0) {
            return "No settings readback from device on `$portPath` while loading settings."
        }
        return null
    }

    fun settingsOrStatusResponseCount(result: DeviceLoadResult): Int {
        return result.linesReceived.count { line ->
            val update = SignalSlingerProtocolCodec.parseReportLine(line)
            update?.settingsPatch != null || update?.deviceStatusPatch != null
        }
    }
}
