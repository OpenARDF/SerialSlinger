@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import com.openardf.serialslinger.model.DeviceInfo
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.DeviceSnapshot
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.session.DeviceSessionState
import com.openardf.serialslinger.session.DeviceSubmitResult
import com.openardf.serialslinger.session.SettingVerification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AndroidSubmitReadbackSupportTest {
    private val expectedDevice = DeviceInfo(
        productName = "SignalSlinger",
        deviceUniqueId = "42348279800081200106014200000000",
        serialPortName = "USB",
    )

    @Test
    fun verificationFailureCanDisplayFreshReadbackFromEditedDevice() {
        val actualFinish = "261012150500"
        val state = DeviceSessionState(
            snapshot = DeviceSnapshot(
                info = expectedDevice.copy(),
                settings = DeviceSettings.empty().copy(finishTimeCompact = actualFinish),
            ),
        )
        val result = submitResult(state = state, readbackLines = listOf("* Finish: Sun 12-Oct-2026 15:05:00"))

        val reconciled = AndroidSubmitReadbackSupport.reconciledViewState(expectedDevice, result)

        assertSame(state, reconciled?.state)
        assertEquals(actualFinish, reconciled?.state?.snapshot?.settings?.finishTimeCompact)
        assertEquals(result.submitTraceEntries + result.readbackTraceEntries, reconciled?.traceEntries)
    }

    @Test
    fun missingOrDifferentDeviceReadbackCannotReplaceDisplayedState() {
        val matchingState = DeviceSessionState(snapshot = DeviceSnapshot(info = expectedDevice.copy()))
        assertNull(
            AndroidSubmitReadbackSupport.reconciledViewState(
                expectedDevice,
                submitResult(matchingState, observedMismatch = false),
            ),
        )

        val differentState = DeviceSessionState(
            snapshot = DeviceSnapshot(info = expectedDevice.copy(deviceUniqueId = "42348279800053200112011900000000")),
        )
        assertNull(
            AndroidSubmitReadbackSupport.reconciledViewState(
                expectedDevice,
                submitResult(differentState),
            ),
        )
    }

    private fun submitResult(
        state: DeviceSessionState,
        readbackLines: List<String> = listOf("* Finish: Sun 12-Oct-2026 15:05:00"),
        observedMismatch: Boolean = true,
    ): DeviceSubmitResult = DeviceSubmitResult(
        state = state,
        commandsSent = listOf("CLK F 261016180000"),
        linesReceived = emptyList(),
        readbackCommandsSent = listOf("CLK"),
        readbackLinesReceived = readbackLines,
        verifications = listOf(
            SettingVerification(
                fieldKey = SettingKey.FINISH_TIME,
                expectedValue = "261016180000",
                actualValue = "261012150500",
                observedInReadback = observedMismatch,
                verified = false,
            ),
        ),
        submitTraceEntries = emptyList(),
        readbackTraceEntries = emptyList(),
    )
}
