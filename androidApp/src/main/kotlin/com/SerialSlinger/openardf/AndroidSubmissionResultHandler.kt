@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import com.openardf.serialslinger.model.DeviceInfo
import com.openardf.serialslinger.model.SchedulePresentation
import com.openardf.serialslinger.model.ScheduleSubmitSupport
import com.openardf.serialslinger.session.DeviceSubmitResult
import com.openardf.serialslinger.session.SerialTraceEntry

internal enum class AndroidSubmissionDisposition {
    NO_CHANGE,
    VERIFIED,
    VERIFICATION_FAILED,
    SUBMIT_FAILED,
}

internal data class AndroidSubmissionResult(
    val disposition: AndroidSubmissionDisposition,
    val viewState: AndroidSessionViewState?,
    val submitSummary: String,
    val probeSummary: String?,
    val statusText: String,
    val statusIsError: Boolean,
    val commandSucceeded: Boolean,
    val callbackResult: Result<DeviceSubmitResult>,
    val canUpdateCloneTemplate: Boolean,
    val traceEntries: List<SerialTraceEntry>,
) {
    val displayedReadbackAfterVerificationFailure: Boolean
        get() = disposition == AndroidSubmissionDisposition.VERIFICATION_FAILED && viewState != null
}

internal object AndroidSubmissionResultHandler {
    fun handle(
        expectedDevice: DeviceInfo?,
        operationResult: Result<DeviceSubmitResult>,
        settingLabel: String,
        noChangeStatusText: String = "$settingLabel already matched the requested value.",
        verifiedStatusText: String = "$settingLabel updated and verified.",
        submitFailedStatusText: String = "$settingLabel update failed.",
        noChangeProbeSummary: String = "Latest load remains available above. $settingLabel already matched the requested value.",
        verifiedProbeSummary: String = "Latest load remains available above. $settingLabel submission completed.",
        additionalSuccessSummaryLines: List<String> = emptyList(),
    ): AndroidSubmissionResult {
        val submitResult = operationResult.getOrNull()
            ?: return failedOperation(submitFailedStatusText, requireNotNull(operationResult.exceptionOrNull()))
        val traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries
        if (submitResult.wasNoOp()) {
            return AndroidSubmissionResult(
                disposition = AndroidSubmissionDisposition.NO_CHANGE,
                viewState = AndroidSessionViewState(submitResult.state, traceEntries),
                submitSummary = renderSuccessSummary(submitResult),
                probeSummary = noChangeProbeSummary,
                statusText = noChangeStatusText,
                statusIsError = false,
                commandSucceeded = true,
                callbackResult = Result.success(submitResult),
                canUpdateCloneTemplate = true,
                traceEntries = traceEntries,
            )
        }

        if (submitResult.verifications.any { !it.verified }) {
            val viewState = reconciledViewState(expectedDevice, submitResult)
            val verificationMessage = verificationFailureMessage(submitResult)
            return AndroidSubmissionResult(
                disposition = AndroidSubmissionDisposition.VERIFICATION_FAILED,
                viewState = viewState,
                submitSummary = renderFailureSummary(submitResult, verificationMessage),
                probeSummary =
                    if (viewState == null) {
                        "Latest load remains available above. $settingLabel submission failed verification."
                    } else {
                        "$settingLabel submission failed verification. Displayed device data was refreshed from readback."
                    },
                statusText =
                    if (viewState == null) {
                        "$settingLabel update failed verification."
                    } else {
                        "$settingLabel did not match the request; device data was refreshed."
                    },
                statusIsError = true,
                commandSucceeded = false,
                callbackResult = Result.failure(IllegalStateException(verificationMessage)),
                canUpdateCloneTemplate = false,
                traceEntries = traceEntries,
            )
        }

        return AndroidSubmissionResult(
            disposition = AndroidSubmissionDisposition.VERIFIED,
            viewState = AndroidSessionViewState(submitResult.state, traceEntries),
            submitSummary = renderSuccessSummary(submitResult, additionalSuccessSummaryLines),
            probeSummary = verifiedProbeSummary,
            statusText = verifiedStatusText,
            statusIsError = false,
            commandSucceeded = true,
            callbackResult = Result.success(submitResult),
            canUpdateCloneTemplate = true,
            traceEntries = traceEntries,
        )
    }

    private fun failedOperation(
        statusText: String,
        error: Throwable,
    ): AndroidSubmissionResult = AndroidSubmissionResult(
        disposition = AndroidSubmissionDisposition.SUBMIT_FAILED,
        viewState = null,
        submitSummary = buildString {
            appendLine("Submit failed.")
            append(error.message ?: "Unknown error")
        }.trim(),
        probeSummary = null,
        statusText = statusText,
        statusIsError = true,
        commandSucceeded = false,
        callbackResult = Result.failure(error),
        canUpdateCloneTemplate = false,
        traceEntries = emptyList(),
    )

    /** Adopt only a fresh readback that still belongs to the device the user edited. */
    private fun reconciledViewState(
        expectedDevice: DeviceInfo?,
        result: DeviceSubmitResult,
    ): AndroidSessionViewState? {
        val observedMismatch = result.verifications.any { !it.verified && it.observedInReadback }
        if (!observedMismatch || expectedDevice == null) return null
        val readbackDevice = result.state.snapshot?.info ?: return null
        if (!ScheduleSubmitSupport.sameDeviceForSchedule(expectedDevice, readbackDevice)) return null

        return AndroidSessionViewState(
            state = result.state,
            traceEntries = result.submitTraceEntries + result.readbackTraceEntries,
        )
    }

    private fun renderSuccessSummary(
        result: DeviceSubmitResult,
        additionalLines: List<String> = emptyList(),
    ): String {
        if (result.wasNoOp()) {
            val productName = result.state.snapshot?.info?.productName?.ifBlank { null } ?: "device"
            return buildString {
                appendLine("No setting changes were required.")
                appendLine("Commands sent: 0")
                appendLine("Readback commands sent: 0")
                append("The requested value already matched the loaded $productName snapshot.")
            }.trim()
        }

        return buildString {
            appendLine("Submit succeeded.")
            result.state.snapshot?.settings?.let { settings ->
                SchedulePresentation.scheduleLines(settings).forEach { appendLine(it) }
            }
            appendLine("Commands sent: ${result.commandsSent.size}")
            appendLine("Submit response lines: ${result.linesReceived.size}")
            appendLine("Readback commands sent: ${result.readbackCommandsSent.size}")
            appendLine("Readback response lines: ${result.readbackLinesReceived.size}")
            if (additionalLines.isNotEmpty()) {
                appendLine()
                appendLine("Observed coupled changes")
                additionalLines.forEach { line -> appendLine(line) }
            }
            appendLine()
            appendVerificationLines(result)
        }.trim()
    }

    private fun renderFailureSummary(
        result: DeviceSubmitResult,
        verificationMessage: String,
    ): String = buildString {
        appendLine("Submit failed.")
        appendLine(verificationMessage)
        appendLine("Commands sent: ${result.commandsSent.size}")
        appendLine("Submit response lines: ${result.linesReceived.size}")
        appendLine("Readback commands sent: ${result.readbackCommandsSent.size}")
        appendLine("Readback response lines: ${result.readbackLinesReceived.size}")
        appendLine()
        appendVerificationLines(result)
    }.trim()

    private fun StringBuilder.appendVerificationLines(result: DeviceSubmitResult) {
        appendLine("Verification")
        if (result.verifications.isEmpty()) {
            appendLine("No verification results were returned.")
        } else {
            result.verifications.forEach { verification ->
                appendLine(
                    "${verification.fieldKey}: verified=${verification.verified} " +
                        "observed=${verification.observedInReadback} expected=${verification.expectedValue} " +
                        "actual=${verification.actualValue}",
                )
            }
        }
    }

    private fun DeviceSubmitResult.wasNoOp(): Boolean =
        commandsSent.isEmpty() && readbackCommandsSent.isEmpty() && verifications.isEmpty()

    private fun verificationFailureMessage(result: DeviceSubmitResult): String =
        if (result.readbackLinesReceived.isEmpty()) {
            "SignalSlinger did not return readback data, so the change could not be verified."
        } else {
            "SignalSlinger readback did not match the requested change."
        }
}
