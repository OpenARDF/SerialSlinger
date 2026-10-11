@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import com.openardf.serialslinger.model.DeviceInfo
import com.openardf.serialslinger.model.ScheduleSubmitSupport
import com.openardf.serialslinger.session.DeviceSubmitResult

internal object AndroidSubmitReadbackSupport {
    /** Adopt only a fresh readback that still belongs to the device the user edited. */
    fun reconciledViewState(
        expectedDevice: DeviceInfo,
        result: DeviceSubmitResult,
    ): AndroidSessionViewState? {
        val observedMismatch = result.verifications.any { !it.verified && it.observedInReadback }
        if (!observedMismatch) return null
        val readbackDevice = result.state.snapshot?.info ?: return null
        if (!ScheduleSubmitSupport.sameDeviceForSchedule(expectedDevice, readbackDevice)) return null

        return AndroidSessionViewState(
            state = result.state,
            traceEntries = result.submitTraceEntries + result.readbackTraceEntries,
        )
    }
}
