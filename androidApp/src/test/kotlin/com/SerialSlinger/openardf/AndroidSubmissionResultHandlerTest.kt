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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidSubmissionResultHandlerTest {
    private val expectedDevice = DeviceInfo(
        productName = "SignalSlinger",
        deviceUniqueId = "42348279800081200106014200000000",
        serialPortName = "USB",
    )

    @Test
    fun verifiedSubmissionCanUpdateDisplayedStateAndCloneTemplate() {
        val state = stateWithFinish("261016180000")
        val result = submitResult(state = state, verified = true)

        val handled = handle(Result.success(result))

        assertEquals(AndroidSubmissionDisposition.VERIFIED, handled.disposition)
        assertSame(state, handled.viewState?.state)
        assertTrue(handled.commandSucceeded)
        assertTrue(handled.canUpdateCloneTemplate)
        assertTrue(handled.callbackResult.isSuccess)
        assertFalse(handled.statusIsError)
        assertTrue(handled.submitSummary.startsWith("Submit succeeded."))
    }

    @Test
    fun observedVerificationFailureDisplaysSameDeviceReadbackButRejectsSubmission() {
        val actualFinish = "261012150500"
        val state = stateWithFinish(actualFinish)
        val result = submitResult(state = state, verified = false, observedInReadback = true)

        val handled = handle(Result.success(result))

        assertEquals(AndroidSubmissionDisposition.VERIFICATION_FAILED, handled.disposition)
        assertSame(state, handled.viewState?.state)
        assertEquals(actualFinish, handled.viewState?.state?.snapshot?.settings?.finishTimeCompact)
        assertTrue(handled.displayedReadbackAfterVerificationFailure)
        assertFalse(handled.commandSucceeded)
        assertFalse(handled.canUpdateCloneTemplate)
        assertTrue(handled.callbackResult.isFailure)
        assertTrue(handled.statusIsError)
        assertEquals("Finish Time did not match the request; device data was refreshed.", handled.statusText)
        assertTrue(handled.submitSummary.startsWith("Submit failed."))
    }

    @Test
    fun anyFailedVerificationRejectsTheWholeSubmission() {
        val state = stateWithFinish("261012150500")
        val result = submitResult(state = state, verified = true).copy(
            verifications =
                listOf(
                    SettingVerification(
                        fieldKey = SettingKey.START_TIME,
                        expectedValue = "261012140000",
                        actualValue = "261012140000",
                        observedInReadback = true,
                        verified = true,
                    ),
                    SettingVerification(
                        fieldKey = SettingKey.FINISH_TIME,
                        expectedValue = "261016180000",
                        actualValue = "261012150500",
                        observedInReadback = true,
                        verified = false,
                    ),
                ),
        )

        val handled = handle(Result.success(result))

        assertEquals(AndroidSubmissionDisposition.VERIFICATION_FAILED, handled.disposition)
        assertFalse(handled.commandSucceeded)
        assertFalse(handled.canUpdateCloneTemplate)
        assertTrue(handled.callbackResult.isFailure)
        assertTrue(handled.submitSummary.contains("START_TIME: verified=true"))
        assertTrue(handled.submitSummary.contains("FINISH_TIME: verified=false"))
    }

    @Test
    fun missingOrDifferentDeviceReadbackCannotReplaceDisplayedState() {
        val matchingState = stateWithFinish("261012150500")
        assertNull(
            handle(
                Result.success(submitResult(matchingState, verified = false, observedInReadback = false)),
            ).viewState,
        )

        val differentState = DeviceSessionState(
            snapshot = DeviceSnapshot(
                info = expectedDevice.copy(deviceUniqueId = "42348279800053200112011900000000"),
                settings = DeviceSettings.empty(),
            ),
        )
        assertNull(handle(Result.success(submitResult(differentState, verified = false))).viewState)
    }

    @Test
    fun transportFailureIsReportedWithoutChangingDisplayedOrCloneState() {
        val handled = handle(Result.failure(IllegalStateException("USB disconnected")))

        assertEquals(AndroidSubmissionDisposition.SUBMIT_FAILED, handled.disposition)
        assertNull(handled.viewState)
        assertFalse(handled.commandSucceeded)
        assertFalse(handled.canUpdateCloneTemplate)
        assertTrue(handled.callbackResult.isFailure)
        assertEquals("Finish Time update failed.", handled.statusText)
        assertEquals("Submit failed.\nUSB disconnected", handled.submitSummary)
    }

    @Test
    fun noOpRemainsASuccessfulSubmissionOutcome() {
        val state = stateWithFinish("261016180000")
        val result = submitResult(state = state, noOp = true)

        val handled = handle(Result.success(result))

        assertEquals(AndroidSubmissionDisposition.NO_CHANGE, handled.disposition)
        assertSame(state, handled.viewState?.state)
        assertTrue(handled.commandSucceeded)
        assertTrue(handled.canUpdateCloneTemplate)
        assertTrue(handled.callbackResult.isSuccess)
        assertEquals("Finish Time already matched the requested value.", handled.statusText)
    }

    private fun handle(result: Result<DeviceSubmitResult>): AndroidSubmissionResult =
        AndroidSubmissionResultHandler.handle(
            expectedDevice = expectedDevice,
            operationResult = result,
            settingLabel = "Finish Time",
        )

    private fun stateWithFinish(finishTimeCompact: String): DeviceSessionState = DeviceSessionState(
        snapshot = DeviceSnapshot(
            info = expectedDevice.copy(),
            settings = DeviceSettings.empty().copy(finishTimeCompact = finishTimeCompact),
        ),
    )

    private fun submitResult(
        state: DeviceSessionState,
        verified: Boolean = false,
        observedInReadback: Boolean = true,
        noOp: Boolean = false,
    ): DeviceSubmitResult = DeviceSubmitResult(
        state = state,
        commandsSent = if (noOp) emptyList() else listOf("CLK F 261016180000"),
        linesReceived = emptyList(),
        readbackCommandsSent = if (noOp) emptyList() else listOf("CLK"),
        readbackLinesReceived = if (noOp || !observedInReadback) emptyList() else listOf("* Finish: Sun 12-Oct-2026 15:05:00"),
        verifications =
            if (noOp) {
                emptyList()
            } else {
                listOf(
                    SettingVerification(
                        fieldKey = SettingKey.FINISH_TIME,
                        expectedValue = "261016180000",
                        actualValue = state.snapshot?.settings?.finishTimeCompact,
                        observedInReadback = observedInReadback,
                        verified = verified,
                    ),
                )
            },
        submitTraceEntries = emptyList(),
        readbackTraceEntries = emptyList(),
    )
}
