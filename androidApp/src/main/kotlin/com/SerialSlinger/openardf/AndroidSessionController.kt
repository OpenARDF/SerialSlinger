@file:Suppress("PackageName")
package com.SerialSlinger.openardf

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openardf.serialslinger.model.DeviceSnapshot
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.EventProfileSupport
import com.openardf.serialslinger.model.FrequencyBankId
import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.JvmTimeSupport
import com.openardf.serialslinger.model.ClockPhaseSample
import com.openardf.serialslinger.model.ScheduleSubmitSupport
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.SettingsField
import com.openardf.serialslinger.model.WritePlanner
import com.openardf.serialslinger.model.hasWallClockTimeSet
import com.openardf.serialslinger.session.DeviceLoadInterventionResult
import com.openardf.serialslinger.session.DeviceLoadResult
import com.openardf.serialslinger.session.DeviceSessionController
import com.openardf.serialslinger.session.DeviceSessionState
import com.openardf.serialslinger.session.DeviceSubmitResult
import com.openardf.serialslinger.session.DeviceSessionWorkflow
import com.openardf.serialslinger.session.FirmwareCloneSession
import com.openardf.serialslinger.session.SerialTraceDirection
import com.openardf.serialslinger.session.SerialTraceEntry
import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import com.openardf.serialslinger.transport.AndroidDirectSerialTransport
import com.openardf.serialslinger.transport.AndroidUsbTransport
import com.openardf.serialslinger.transport.DeviceTransport
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.math.abs

data class AndroidSessionViewState(
    val state: DeviceSessionState,
    val traceEntries: List<SerialTraceEntry>,
)

data class AndroidUiState(
    val statusText: String,
    val statusIsError: Boolean,
    val latestProbeSummary: String,
    val sessionViewState: AndroidSessionViewState?,
    val latestLoadedTarget: AndroidConnectionTarget?,
    val latestLoadedTargetLabel: String?,
    val latestLoadedDeviceName: String?,
    val cloneTemplateSettings: DeviceSettings?,
    val cloneTemplateDaysRemaining: Int?,
    val draftStationId: String?,
    val draftEventType: String?,
    val draftFoxRole: String?,
    val draftIdSpeedWpm: String?,
    val draftPatternText: String?,
    val draftPatternSpeedWpm: String?,
    val draftCurrentFrequency: String?,
    val draftCurrentTime: String?,
    val draftStartTime: String?,
    val draftFinishTime: String?,
    val draftDaysToRun: String?,
    val latestSubmitSummary: String,
    val timeWorkflowNotice: String?,
    val scheduleDerivedDataPending: Boolean,
    val probeInFlight: Boolean,
    val signalSlingerReadInFlight: Boolean,
    val currentTimeSyncInFlight: Boolean,
    val clockPhaseErrorMillis: Long?,
    val hasClockPhaseWarning: Boolean,
    val canClone: Boolean,
)

private data class ScheduleImpactSummary(
    val summaryLines: List<String>,
    val statusText: String?,
    val noticeText: String?,
)

private data class ClockReadSample(
    val sentAt: LocalDateTime,
    val receivedAt: LocalDateTime,
    val responseLines: List<String>,
    val reportedTimeCompact: String?,
    val reportedTimeObserved: Boolean,
    val command: String,
) {
    val roundTripMillis: Long
        get() = Duration.between(sentAt, receivedAt).toMillis()

    val midpointAt: LocalDateTime
        get() = sentAt.plus(Duration.ofMillis(roundTripMillis / 2))
}

private data class SyncAttempt(
    val targetTime: LocalDateTime,
    val dispatchTime: LocalDateTime,
    val estimatedOneWayDelayMillis: Long,
    val submitResult: DeviceSubmitResult,
    val verificationSamples: List<ClockReadSample>,
    val phaseErrorMillis: Long?,
    val state: DeviceSessionState,
)

private data class TimeSyncOperationResult(
    val latencySamples: List<ClockReadSample>,
    val attempts: List<SyncAttempt>,
    val finalAttempt: SyncAttempt,
    val succeeded: Boolean,
)

private data class RelativeScheduleSubmitResult(
    val state: DeviceSessionState,
    val commandsSent: List<String>,
    val responseLines: List<String>,
    val reloadResult: DeviceLoadResult,
    val traceEntries: List<SerialTraceEntry>,
)

private data class RawCommandResult(
    val state: DeviceSessionState,
    val responseLines: List<String>,
    val traceEntries: List<SerialTraceEntry>,
)

data class CloneSubmitResult(
    val state: DeviceSessionState,
    val cloneProtocol: String,
    val firmwareCloneChecksum: Long? = null,
    val writeCommandCount: Int,
    val writeResponseLineCount: Int,
    val refreshCommandCount: Int,
    val refreshResponseLineCount: Int,
    val syncAttemptCount: Int,
    val syncSucceeded: Boolean,
    val traceEntries: List<SerialTraceEntry>,
    val clockPhaseErrorMillis: Long? = null,
    val clockReferenceTime: LocalDateTime? = null,
    val possibleNewDeviceReasons: List<String> = emptyList(),
)

data class EventPauseNotice(
    val id: Long,
    val message: String,
)

private data class ClonePreflight(
    val sessionState: DeviceSessionState?,
    val templateSettings: DeviceSettings?,
    val target: AndroidConnectionTarget?,
)

private data class ProbeStart(
    val started: Boolean,
    val target: AndroidConnectionTarget?,
    val previousSnapshot: DeviceSnapshot?,
    val previousPhaseErrorMillis: Long?,
)

private data class ClockDisplayAnchor(
    val currentTimeCompact: String?,
    val referenceTime: LocalDateTime?,
    val phaseErrorMillis: Long?,
)

private data class EventPauseRequest(
    val id: Long,
    val message: String,
    val latch: CountDownLatch,
)

private data class ResolvedTransport(
    val target: AndroidConnectionTarget,
    val transport: DeviceTransport,
)

@SuppressLint("NewApi")
object AndroidSessionController {
    const val CLOCK_PHASE_WARNING_THRESHOLD_MILLIS = 500L
    private const val logTag = "SerialSlingerDebug"
    private const val POSSIBLE_NEW_DEVICE_PHASE_SHIFT_THRESHOLD_MILLIS = 1_000L
    private const val syncLatencySampleCount = 3
    private const val syncMaxAttempts = 2
    private const val syncVerificationSampleMax = 8
    private const val firmwareCloneCommandSettleMs = 250L
    private const val firmwareCloneResetMaxWaitMs = 8_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private var applicationContext: Context? = null
    private var sessionLog: AndroidSessionLog? = null

    private var statusText: String = "Waiting for USB devices."
    private var statusIsError: Boolean = false
    private var latestProbeSummary: String = "No SignalSlinger probe has been run yet."
    private var latestSessionViewState: AndroidSessionViewState? = null
    private var latestLoadedTarget: AndroidConnectionTarget? = null
    private var latestLoadedDeviceName: String? = null
    private var draftStationId: String? = null
    private var draftEventType: String? = null
    private var draftFoxRole: String? = null
    private var draftIdSpeedWpm: String? = null
    private var draftPatternText: String? = null
    private var draftPatternSpeedWpm: String? = null
    private var draftCurrentFrequency: String? = null
    private var draftCurrentTime: String? = null
    private var draftStartTime: String? = null
    private var draftFinishTime: String? = null
    private var draftDaysToRun: String? = null
    private var latestSubmitSummary: String = "No Android-side setting changes have been submitted yet."
    private var timeWorkflowNotice: String? = null
    private var scheduleDerivedDataPending: Boolean = false
    private var probeInFlight: Boolean = false
    private var signalSlingerReadInFlight: Boolean = false
    private var currentTimeSyncInFlight: Boolean = false
    private var deviceTimeOffset: Duration? = null
    private var lastClockPhaseErrorMillis: Long? = null
    private var cachedManualWriteDelayMillis: Long? = null
    private var cloneTemplateSettings: DeviceSettings? = null
    private var cloneTemplateDaysRemaining: Int? = null
    private var cloneTemplateTimedEventEditsLocked: Boolean = false
    private var pendingTimelyReplyWarning: String? = null
    private var pendingEventPauseRequest: EventPauseRequest? = null
    private var nextEventPauseRequestId: Long = 1L

    fun initialize(context: Context) {
        synchronized(this) {
            if (applicationContext == null) {
                applicationContext = context.applicationContext
            }
            if (sessionLog == null) {
                sessionLog =
                    AndroidSessionLog(
                        rootDirectory = context.applicationContext.filesDir.resolve("logs"),
                        appVersion = BuildConfig.VERSION_NAME,
                        platformLabel = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                    )
            }
        }
    }

    fun addListener(listener: () -> Unit) {
        synchronized(this) {
            listeners += listener
        }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(this) {
            listeners -= listener
        }
    }

    fun snapshotUiState(): AndroidUiState {
        synchronized(this) {
            return AndroidUiState(
                statusText = statusText,
                statusIsError = statusIsError,
                latestProbeSummary = latestProbeSummary,
                sessionViewState = latestSessionViewState,
                latestLoadedTarget = latestLoadedTarget,
                latestLoadedTargetLabel = latestLoadedTarget?.label,
                latestLoadedDeviceName = latestLoadedDeviceName,
                cloneTemplateSettings = cloneTemplateSettings,
                cloneTemplateDaysRemaining = cloneTemplateDaysRemaining,
                draftStationId = draftStationId,
                draftEventType = draftEventType,
                draftFoxRole = draftFoxRole,
                draftIdSpeedWpm = draftIdSpeedWpm,
                draftPatternText = draftPatternText,
                draftPatternSpeedWpm = draftPatternSpeedWpm,
                draftCurrentFrequency = draftCurrentFrequency,
                draftCurrentTime = draftCurrentTime,
                draftStartTime = draftStartTime,
                draftFinishTime = draftFinishTime,
                draftDaysToRun = draftDaysToRun,
                latestSubmitSummary = latestSubmitSummary,
                timeWorkflowNotice = timeWorkflowNotice,
                scheduleDerivedDataPending = scheduleDerivedDataPending,
                probeInFlight = probeInFlight,
                signalSlingerReadInFlight = signalSlingerReadInFlight,
                currentTimeSyncInFlight = currentTimeSyncInFlight,
                clockPhaseErrorMillis = currentClockSkewMillisLocked(),
                hasClockPhaseWarning = hasClockPhaseWarningLocked(),
                canClone = canCloneLocked(),
            )
        }
    }

    fun recordStatus(text: String, isError: Boolean) {
        synchronized(this) {
            statusText = text
            statusIsError = isError
        }
        notifyListeners()
    }

    fun lockCloneTemplateTimedEventEdits() {
        synchronized(this) {
            cloneTemplateTimedEventEditsLocked = true
        }
    }

    fun allowCloneTemplateTimedEventEdits() {
        synchronized(this) {
            cloneTemplateTimedEventEditsLocked = false
        }
    }

    fun consumeTimelyReplyWarning(): String? {
        synchronized(this) {
            val warning = pendingTimelyReplyWarning
            pendingTimelyReplyWarning = null
            return warning
        }
    }

    fun pendingEventPauseNotice(): EventPauseNotice? {
        synchronized(this) {
            val request = pendingEventPauseRequest ?: return null
            return EventPauseNotice(
                id = request.id,
                message = request.message,
            )
        }
    }

    fun confirmEventPauseNotice(id: Long) {
        val request =
            synchronized(this) {
                pendingEventPauseRequest
                    ?.takeIf { it.id == id }
                    ?.also { pendingEventPauseRequest = null }
            }
        request?.latch?.countDown()
        if (request != null) {
            notifyListeners()
        }
    }

    fun displayedDeviceTimeText(systemNow: LocalDateTime = LocalDateTime.now()): String {
        synchronized(this) {
            return JvmTimeSupport.formatCompactTimestampOrNotSet(displayedDeviceTimeCompactLocked(systemNow))
        }
    }

    fun isDisplayedDeviceTimeSynchronized(systemNow: LocalDateTime = LocalDateTime.now()): Boolean {
        synchronized(this) {
            return JvmTimeSupport.isTimeSynchronizedToSystem(displayedDeviceTimeCompactLocked(systemNow), systemNow)
        }
    }

    fun currentClockSkewMillis(systemNow: LocalDateTime = LocalDateTime.now()): Long? {
        synchronized(this) {
            return currentClockSkewMillisLocked(systemNow)
        }
    }

    fun displayedDeviceTimeCompact(systemNow: LocalDateTime = LocalDateTime.now()): String? {
        synchronized(this) {
            return displayedDeviceTimeCompactLocked(systemNow)
        }
    }

    fun displayedDaysToRunRemainingSummary(systemNow: LocalDateTime = LocalDateTime.now()): String {
        synchronized(this) {
            val snapshot = latestSessionViewState?.state?.snapshot ?: return ""
            return JvmTimeSupport.formatDaysToRunRemainingSummary(
                totalDaysToRun = snapshot.settings.daysToRun,
                daysToRunRemaining = snapshot.status.daysRemaining,
                currentTimeCompact = displayedDeviceTimeCompactLocked(systemNow),
                startTimeCompact = snapshot.settings.startTimeCompact,
                finishTimeCompact = snapshot.settings.finishTimeCompact,
            )
        }
    }

    fun isScheduleDerivedDataPending(): Boolean {
        synchronized(this) {
            return scheduleDerivedDataPending
        }
    }

    fun clearLoadedSession(reasonText: String) {
        synchronized(this) {
            latestSessionViewState = null
            latestLoadedTarget = null
            latestLoadedDeviceName = null
            applySnapshotDrafts(null)
            deviceTimeOffset = null
            lastClockPhaseErrorMillis = null
            cachedManualWriteDelayMillis = null
            timeWorkflowNotice = null
            scheduleDerivedDataPending = false
            latestProbeSummary = "No SignalSlinger is currently attached."
            latestSubmitSummary = "No Android-side setting changes have been submitted yet."
            statusText = reasonText
            statusIsError = false
        }
        notifyListeners()
    }

    fun runCloneTimedEventSettings(
        context: Context,
        requestedDeviceName: String? = null,
        requestedTarget: AndroidConnectionTarget? = null,
        source: String = "ui",
        onComplete: ((Result<CloneSubmitResult>) -> Unit)? = null,
    ) {
        val clonePreflight =
            synchronized(this) {
                ClonePreflight(
                    sessionState = latestSessionViewState?.state,
                    templateSettings = cloneTemplateSettings,
                    target = resolveRequestedTargetLocked(
                        requestedTarget = requestedTarget,
                        requestedDeviceName = requestedDeviceName,
                    ),
                )
            }
        val preflightError = clonePreflightError(context, clonePreflight)
        if (preflightError != null) {
            synchronized(this) {
                latestSubmitSummary = "Clone failed.\n${preflightError.message}"
                statusText = "Clone unavailable."
                statusIsError = true
            }
            emitCommandLog("clone", source, success = false, summary = preflightError.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback -> mainHandler.post { callback(Result.failure(preflightError)) } }
            return
        }
        val sessionState = requireNotNull(clonePreflight.sessionState)
        val templateSettings = requireNotNull(clonePreflight.templateSettings)

        synchronized(this) {
            latestSubmitSummary = "Submitting Clone to attached SignalSlinger..."
            statusText = "Submitting Clone to attached SignalSlinger..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-clone-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            var cloneFailureTraceEntries: List<SerialTraceEntry> = emptyList()
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = requestedTarget,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val targetRefresh = DeviceSessionController.refreshFromDevice(sessionState, transport, startEditing = true)
                require(hasSignalSlingerReportLine(targetRefresh.linesReceived)) {
                    "The attached SignalSlinger did not respond. Check the configuration cable and try again."
                }
                val targetSnapshot = requireNotNull(targetRefresh.state.snapshot)
                val possibleNewDeviceReasons =
                    possibleNewDeviceReasons(
                        previousSnapshot = sessionState.snapshot,
                        loadedSnapshot = targetSnapshot,
                        previousPhaseErrorMillis = null,
                        loadedPhaseErrorMillis = null,
                    )
                val editable = buildCloneEditableSettings(targetSnapshot.settings, templateSettings)
                val validated = editable.toValidatedDeviceSettings()
                val writePlan = WritePlanner.create(targetSnapshot.settings, validated)
                val firmwareCloneResult =
                    FirmwareCloneSession.cloneFromTemplate(
                        transport = transport,
                        templateSettings = validated,
                        targetSoftwareVersion = targetSnapshot.info.softwareVersion,
                        currentTimeCompact = ::nextFirmwareCloneClockTimeCompact,
                        afterReset = { waitForFirmwareCloneReset(transport) },
                        afterStartAttempt = ::waitForFirmwareCloneStartRetry,
                        afterCommandAcknowledged = { waitForFirmwareCloneCommandSettle() },
                        afterCloneDiagnosticRecovered = { waitForFirmwareCloneDiagnosticRecovery(transport) },
                    )
                cloneFailureTraceEntries = targetRefresh.traceEntries + firmwareCloneResult.traceEntries
                if (firmwareCloneResult.succeeded) {
                    val refreshed = DeviceSessionController.refreshFromDevice(targetRefresh.state, transport, startEditing = true)
                    cloneFailureTraceEntries = cloneFailureTraceEntries + refreshed.traceEntries
                    require(hasSignalSlingerReportLine(refreshed.linesReceived)) {
                        "The attached SignalSlinger did not respond during clone verification. Check the configuration cable and try again."
                    }
                    val clockSample = postLoadClockSample(transport, refreshed)
                    val finalRefresh = clockSample?.first ?: refreshed
                    val clockAnchor = clockSample?.second
                    CloneSubmitResult(
                        state = finalRefresh.state,
                        cloneProtocol = "firmware MAS P",
                        firmwareCloneChecksum = firmwareCloneResult.plan?.checksum,
                        writeCommandCount = firmwareCloneResult.commandsSent.size,
                        writeResponseLineCount = firmwareCloneResult.linesReceived.size,
                        refreshCommandCount = refreshed.commandsSent.size + (clockSample?.first?.commandsSent?.size ?: 0),
                        refreshResponseLineCount = refreshed.linesReceived.size + (clockSample?.first?.linesReceived?.size ?: 0),
                        syncAttemptCount = 0,
                        syncSucceeded = true,
                        traceEntries =
                            buildList {
                                addAll(targetRefresh.traceEntries)
                                addAll(firmwareCloneResult.traceEntries)
                                addAll(refreshed.traceEntries)
                                clockSample?.first?.let { addAll(it.traceEntries) }
                            },
                        clockPhaseErrorMillis = clockAnchor?.phaseErrorMillis,
                        clockReferenceTime = clockAnchor?.referenceTime,
                        possibleNewDeviceReasons = possibleNewDeviceReasons,
                    )
                } else {
                    require(firmwareCloneResult.legacyFallbackAllowed) {
                        firmwareCloneResult.failureMessage ?: "Firmware clone failed after clone mode started."
                    }
                    val submitResult = DeviceSessionController.submitEdits(targetRefresh.state, editable, transport)
                    val verificationFailures = submitResult.verifications.filter { !it.verified }
                    cloneFailureTraceEntries =
                        cloneFailureTraceEntries + submitResult.submitTraceEntries + submitResult.readbackTraceEntries
                    val refreshed = DeviceSessionController.refreshFromDevice(submitResult.state, transport, startEditing = true)
                    cloneFailureTraceEntries = cloneFailureTraceEntries + refreshed.traceEntries
                    require(hasSignalSlingerReportLine(refreshed.linesReceived)) {
                        "The attached SignalSlinger did not respond during clone verification. Check the configuration cable and try again."
                    }
                    var finalState = refreshed.state
                    var syncOperation: TimeSyncOperationResult? = null
                    if (verificationFailures.isEmpty() && refreshed.state.snapshot?.capabilities?.supportsScheduling == true) {
                        syncOperation =
                            performAlignedTimeSync(
                                transport = transport,
                                state = refreshed.state,
                                snapshot = requireNotNull(refreshed.state.snapshot),
                            )
                        finalState = syncOperation.finalAttempt.state
                    }
                    CloneSubmitResult(
                        state = finalState,
                        cloneProtocol = "legacy writes",
                        writeCommandCount = writePlan.changes.size,
                        writeResponseLineCount = submitResult.linesReceived.size,
                        refreshCommandCount = refreshed.commandsSent.size,
                        refreshResponseLineCount = refreshed.linesReceived.size,
                        syncAttemptCount = syncOperation?.attempts?.size ?: 0,
                        syncSucceeded = syncOperation?.succeeded ?: true,
                        traceEntries =
                            buildList {
                                addAll(targetRefresh.traceEntries)
                                addAll(firmwareCloneResult.traceEntries)
                                addAll(submitResult.submitTraceEntries)
                                addAll(submitResult.readbackTraceEntries)
                                addAll(refreshed.traceEntries)
                                syncOperation?.let { addAll(buildSyncTraceEntries(it)) }
                            },
                        clockPhaseErrorMillis = syncOperation?.finalAttempt?.phaseErrorMillis,
                        clockReferenceTime = syncOperation?.finalAttempt?.verificationSamples?.lastOrNull()?.midpointAt,
                        possibleNewDeviceReasons = possibleNewDeviceReasons,
                    )
                }
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val cloneResult = result.getOrThrow()
                    latestSessionViewState = AndroidSessionViewState(
                        state = cloneResult.state,
                        traceEntries = cloneResult.traceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    applySnapshotDrafts(
                        cloneResult.state.snapshot,
                        refreshClockDisplayAnchor = cloneResult.clockPhaseErrorMillis == null,
                    )
                    if (cloneResult.clockPhaseErrorMillis != null || cloneResult.clockReferenceTime != null) {
                        applyClockDisplayAnchor(
                            currentTimeCompact = cloneResult.state.snapshot?.settings?.currentTimeCompact,
                            phaseErrorMillis = cloneResult.clockPhaseErrorMillis,
                            referenceTime = cloneResult.clockReferenceTime ?: LocalDateTime.now(),
                        )
                    }
                    timeWorkflowNotice = null
                    clearScheduleDerivedDataPendingLocked()
                    latestSubmitSummary = renderCloneSubmitSummary(cloneResult)
                    latestProbeSummary = "Latest load remains available above. Clone completed."
                    statusText =
                        if (cloneResult.syncSucceeded) {
                            "Clone succeeded."
                        } else {
                            "Clone completed, but time sync needs attention."
                        }
                    statusIsError = !cloneResult.syncSucceeded
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Clone failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Clone failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                }
            }

            emitCommandLog(
                command = "clone",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
                traceEntries = result.getOrNull()?.traceEntries ?: cloneFailureTraceEntries,
                warnOnMissingTimelyReplies = result.isFailure,
            )
            notifyListeners()
            onComplete?.let { callback -> mainHandler.post { callback(result) } }
        }
    }

    fun reloadCloneTemplateFromAttachedDevice(
        context: Context,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceLoadResult>) -> Unit)? = null,
    ) {
        runProbe(
            context = context,
            requestedDeviceName = requestedDeviceName,
            source = source,
        ) { result ->
            synchronized(this) {
                result.getOrNull()?.state?.snapshot?.let(::rememberCloneTemplateFrom)
                if (result.isSuccess) {
                    latestSubmitSummary = "Clone template reloaded from attached device."
                    statusText = "Clone template reloaded from attached device."
                    statusIsError = false
                }
            }
            notifyListeners()
            onComplete?.invoke(result)
        }
    }

    private fun displayedDeviceTimeCompactLocked(systemNow: LocalDateTime): String? {
        return when {
            deviceTimeOffset != null -> JvmTimeSupport.formatTruncatedCompactTimestamp(systemNow.plus(requireNotNull(deviceTimeOffset)))
            else -> latestSessionViewState?.state?.snapshot?.settings?.currentTimeCompact
        }
    }

    private fun markScheduleDerivedDataPendingLocked() {
        scheduleDerivedDataPending = true
    }

    private fun clearScheduleDerivedDataPendingLocked() {
        scheduleDerivedDataPending = false
    }

    private fun canCloneLocked(): Boolean {
        if (probeInFlight) {
            return false
        }
        val snapshot = latestSessionViewState?.state?.snapshot ?: return false
        val template = cloneTemplateSettings ?: return false
        if (latestLoadedTarget == null) {
            return false
        }
        if (!snapshot.capabilities.supportsScheduling) {
            return false
        }
        return hasCompleteCloneTimedEventSettings(template, cloneTemplateDaysRemaining)
    }

    private fun clonePreflightError(
        context: Context,
        preflight: ClonePreflight,
    ): IllegalStateException? {
        val snapshot = preflight.sessionState?.snapshot
            ?: return IllegalStateException("Load the source SignalSlinger before using Clone.")
        val template = preflight.templateSettings
            ?: return IllegalStateException("Load the source SignalSlinger before using Clone.")
        if (!snapshot.capabilities.supportsScheduling) {
            return IllegalStateException("Clone requires a SignalSlinger snapshot with timed-event support.")
        }
        if (!hasCompleteCloneTimedEventSettings(template, cloneTemplateDaysRemaining)) {
            return IllegalStateException("Clone template is missing complete timed-event settings.")
        }
        val target = preflight.target
            ?: return IllegalStateException("Connect the target SignalSlinger before using Clone.")
        if (!target.isAvailable(context)) {
            return IllegalStateException("Connect the target SignalSlinger before using Clone.")
        }
        return null
    }

    private fun AndroidConnectionTarget.isAvailable(context: Context): Boolean {
        return when (this) {
            is AndroidConnectionTarget.DirectSerial -> true
            is AndroidConnectionTarget.Usb -> {
                val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
                resolveUsbDevice(usbManager, deviceName) != null
            }
        }
    }

    private fun hasClockPhaseWarningLocked(): Boolean {
        return currentClockSkewMillisLocked()?.let { abs(it) > CLOCK_PHASE_WARNING_THRESHOLD_MILLIS } == true
    }

    private fun currentClockSkewMillisLocked(systemNow: LocalDateTime = LocalDateTime.now()): Long? {
        val offset = deviceTimeOffset
        if (offset != null) {
            return Duration.between(systemNow.plus(offset), systemNow).toMillis()
        }

        val displayedDeviceTime = displayedDeviceTimeCompactLocked(systemNow)
            ?.let(JvmTimeSupport::normalizeCurrentTimeCompactForDisplay)
            ?.let(JvmTimeSupport::parseCompactTimestamp)
            ?: return null
        return Duration.between(displayedDeviceTime, systemNow).toMillis()
    }

    private fun hasCompleteCloneTimedEventSettings(settings: DeviceSettings, daysRemaining: Int?): Boolean {
        if (settings.idCodeSpeedWpm !in 5..20) {
            return false
        }
        if (
            EventProfileSupport.patternSpeedBelongsToTimedEventSettings(settings.eventType) &&
            settings.patternCodeSpeedWpm !in 5..20
        ) {
            return false
        }
        if (
            !JvmTimeSupport.isCloneScheduleEligible(
                startTimeCompact = settings.startTimeCompact,
                finishTimeCompact = settings.finishTimeCompact,
                currentTimeCompact = settings.currentTimeCompact,
                daysToRun = settings.daysToRun,
                daysRemaining = daysRemaining,
            )
        ) {
            return false
        }
        if (settings.daysToRun !in 1..255) {
            return false
        }
        val frequencyVisibility = EventProfileSupport.timedEventFrequencyVisibility(settings.eventType)
        if (frequencyVisibility.showFrequency1 && settings.lowFrequencyHz == null) {
            return false
        }
        if (frequencyVisibility.showFrequency2 && settings.mediumFrequencyHz == null) {
            return false
        }
        if (frequencyVisibility.showFrequency3 && settings.highFrequencyHz == null) {
            return false
        }
        if (frequencyVisibility.showFrequencyB && settings.beaconFrequencyHz == null) {
            return false
        }
        return true
    }

    private fun markSignalSlingerReadInFlight() {
        val shouldNotify =
            synchronized(this) {
                if (signalSlingerReadInFlight) {
                    false
                } else {
                    signalSlingerReadInFlight = true
                    statusText = "Reading data..."
                    statusIsError = false
                    true
                }
            }
        if (shouldNotify) {
            notifyListeners()
        }
    }

    private fun hasSignalSlingerReportLine(lines: List<String>): Boolean {
        return lines.any { line -> SignalSlingerProtocolCodec.parseReportLine(line) != null }
    }

    private fun pauseRunningEventAfterEvtIfNeeded(
        command: String,
        state: DeviceSessionState,
        transport: DeviceTransport,
    ): DeviceLoadInterventionResult? {
        if (!command.equals("EVT", ignoreCase = true)) {
            return null
        }
        val eventSummary = activeEventSummary(state) ?: return null
        val message =
            "The attached SignalSlinger reports that an event is in progress:\n\n" +
                "$eventSummary\n\n" +
                "A running event may let the SignalSlinger sleep between transmissions and miss commands. " +
                "When you tap OK, SerialSlinger will send GO 0 to pause the event, then continue loading data."
        waitForEventPauseDismissal(message)
        val sentAtMs = System.currentTimeMillis()
        transport.sendCommands(listOf("GO 0"))
        val responseLines = transport.readAvailableLines()
        val receivedAtMs = System.currentTimeMillis()
        logAppEvent(
            title = "event-state",
            lines = listOf(
                "SignalSlinger reported an event in progress: $eventSummary",
                "User dismissed warning; sent GO 0 before continuing.",
            ),
        )
        return DeviceLoadInterventionResult(
            state = DeviceSessionWorkflow.ingestReportLines(state, responseLines),
            commandsSent = listOf("GO 0"),
            linesReceived = responseLines,
            traceEntries =
                listOf(SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, "GO 0")) +
                    responseLines.map { line -> SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line) },
        )
    }

    private fun activeEventSummary(state: DeviceSessionState): String? {
        val status = state.snapshot?.status ?: return null
        if (status.eventEnabled != true) {
            return null
        }
        val statusText = listOfNotNull(
            status.eventStateSummary?.trim()?.takeIf { it.isNotEmpty() },
            status.eventStartsInSummary?.trim()?.takeIf { it.isNotEmpty() },
        )
        val joinedStatus = statusText.joinToString(" ")
        val lowerStatus = joinedStatus.lowercase()
        val activeEventMarkers =
            listOf(
                "in progress",
                "time remaining",
                "on the air",
                "running forever",
                "user launched",
            )
        if (activeEventMarkers.none { marker -> lowerStatus.contains(marker) }) {
            return null
        }
        return status.eventStateSummary?.trim()?.takeIf { it.isNotEmpty() }
            ?: status.eventStartsInSummary?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun waitForEventPauseDismissal(message: String) {
        val request =
            synchronized(this) {
                val request = EventPauseRequest(
                    id = nextEventPauseRequestId++,
                    message = message,
                    latch = CountDownLatch(1),
                )
                pendingEventPauseRequest = request
                statusText = "SignalSlinger event in progress."
                statusIsError = false
                request
            }
        notifyListeners()
        request.latch.await()
    }

    fun clearLoadedSessionIfMatches(
        deviceName: String?,
        reasonText: String,
    ) {
        if (deviceName == null) {
            return
        }
        val shouldClear =
            synchronized(this) {
                latestLoadedDeviceName == deviceName
            }
        if (shouldClear) {
            clearLoadedSession(reasonText)
        }
    }

    fun runProbe(
        context: Context,
        requestedDeviceName: String? = null,
        requestedTarget: AndroidConnectionTarget? = null,
        requestedTargets: List<AndroidConnectionTarget>? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceLoadResult>) -> Unit)? = null,
    ) {
        val probeStart =
            synchronized(this) {
                val desiredTarget =
                    if (requestedTargets.isNullOrEmpty()) {
                        resolveRequestedTargetLocked(requestedTarget = requestedTarget, requestedDeviceName = requestedDeviceName)
                    } else {
                        requestedTargets.first()
                    }
                if (probeInFlight) {
                    ProbeStart(
                        started = false,
                        target = desiredTarget,
                        previousSnapshot = latestSessionViewState?.state?.snapshot,
                        previousPhaseErrorMillis = lastClockPhaseErrorMillis,
                    )
                } else {
                    probeInFlight = true
                    signalSlingerReadInFlight = false
                    ProbeStart(
                        started = true,
                        target = desiredTarget,
                        previousSnapshot = latestSessionViewState?.state?.snapshot,
                        previousPhaseErrorMillis = lastClockPhaseErrorMillis,
                    )
                }
            }
        val startedProbe = probeStart.started
        val target = probeStart.target
        if (!startedProbe) {
            onComplete?.let { callback ->
                mainHandler.post {
                    callback(Result.failure(IllegalStateException("Probe already in progress.")))
                }
            }
            return
        }
        recordStatus("Loading SignalSlinger...", isError = false)
        synchronized(this) {
            latestProbeSummary = "Probe in progress..."
        }
        notifyListeners()

        thread(name = "serialslinger-android-probe") {
            var resolvedTarget: AndroidConnectionTarget? = null
            fun attemptProbe(
                requestedDeviceNameForAttempt: String?,
                requestedTargetForAttempt: AndroidConnectionTarget?,
                allowUsbAutoDetect: Boolean,
                missingMessage: String,
            ): Result<Pair<DeviceLoadResult, ClockDisplayAnchor?>> =
                runWithResolvedTransport(
                    context = context,
                    requestedDeviceName = requestedDeviceNameForAttempt,
                    requestedTarget = requestedTargetForAttempt,
                    allowUsbAutoDetect = allowUsbAutoDetect,
                    missingMessage = missingMessage,
                ) { activeTarget, transport ->
                    resolvedTarget = activeTarget
                    val initialLoad = DeviceSessionController.connectAndLoad(
                        transport = transport,
                        onReportReceived = ::markSignalSlingerReadInFlight,
                        afterCommand = ::pauseRunningEventAfterEvtIfNeeded,
                    )
                    require(hasSignalSlingerReportLine(initialLoad.linesReceived)) {
                        "No SignalSlinger response was received."
                    }
                    val postLoadClockSample = postLoadClockSample(transport, initialLoad)
                    mergeLoadResults(initialLoad, postLoadClockSample?.first) to postLoadClockSample?.second
                }

            val result =
                if (requestedTargets.isNullOrEmpty()) {
                    attemptProbe(
                        requestedDeviceNameForAttempt = (target as? AndroidConnectionTarget.Usb)?.deviceName,
                        requestedTargetForAttempt = target,
                        allowUsbAutoDetect = true,
                        missingMessage = "No permitted supported USB serial device is connected.",
                    )
                } else {
                    val attemptSummaries = mutableListOf<String>()
                    var successfulResult: Result<Pair<DeviceLoadResult, ClockDisplayAnchor?>>? = null
                    for (candidateTarget in requestedTargets) {
                        val candidateResult =
                            attemptProbe(
                                requestedDeviceNameForAttempt = (candidateTarget as? AndroidConnectionTarget.Usb)?.deviceName,
                                requestedTargetForAttempt = candidateTarget,
                                allowUsbAutoDetect = false,
                                missingMessage = "${candidateTarget.label} is not available.",
                            )
                        if (candidateResult.isSuccess) {
                            val (loadResult, _) = candidateResult.getOrThrow()
                            if (loadResult.linesReceived.isNotEmpty()) {
                                successfulResult = candidateResult
                                break
                            }
                            attemptSummaries += "${candidateTarget.label}: no response lines were received."
                        } else {
                            attemptSummaries += buildString {
                                append(candidateTarget.label)
                                append(": ")
                                append(candidateResult.exceptionOrNull()?.message ?: "Unknown error")
                            }
                        }
                    }
                    successfulResult
                        ?: Result.failure(
                            IllegalStateException(
                                buildString {
                                    appendLine("No emulator serial path responded successfully.")
                                    append(attemptSummaries.joinToString("\n"))
                                }.trim(),
                            ),
                        )
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val (loadResult, clockAnchor) = result.getOrThrow()
                    val loadedSnapshot = loadResult.state.snapshot
                    val possibleNewDeviceReasons =
                        possibleNewDeviceReasons(
                            previousSnapshot = probeStart.previousSnapshot,
                            loadedSnapshot = loadedSnapshot,
                            previousPhaseErrorMillis = probeStart.previousPhaseErrorMillis,
                            loadedPhaseErrorMillis = clockAnchor?.phaseErrorMillis,
                        )
                    if (possibleNewDeviceReasons.isNotEmpty()) {
                        deviceTimeOffset = null
                        lastClockPhaseErrorMillis = null
                        cachedManualWriteDelayMillis = null
                    }
                    latestSessionViewState = AndroidSessionViewState(
                        state = loadResult.state,
                        traceEntries = loadResult.traceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    loadedSnapshot?.let(::rememberCloneTemplateFrom)
                    applySnapshotDrafts(loadedSnapshot)
                    clockAnchor?.let { anchor ->
                        applyClockDisplayAnchor(
                            currentTimeCompact = anchor.currentTimeCompact,
                            phaseErrorMillis = anchor.phaseErrorMillis,
                            referenceTime = anchor.referenceTime ?: LocalDateTime.now(),
                        )
                    }
                    timeWorkflowNotice = null
                    latestProbeSummary =
                        buildString {
                            append(renderProbeSummary(loadResult))
                            possibleNewDeviceReasons.forEach { reason ->
                                appendLine()
                                append("Possible new device detected due to $reason.")
                            }
                        }
                    statusText = "SignalSlinger Data Read Successfully"
                    statusIsError = false
                } else {
                    latestProbeSummary = buildString {
                        appendLine("No SignalSlinger found.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "No SignalSlinger found."
                    statusIsError = true
                }
                probeInFlight = false
                signalSlingerReadInFlight = false
            }

            emitCommandLog(
                command = "load",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestProbeSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post {
                    callback(
                        result.fold(
                            onSuccess = { (loadResult, _) -> Result.success(loadResult) },
                            onFailure = { error -> Result.failure(error) },
                        ),
                    )
                }
            }
        }
    }

    fun runStationIdSubmit(
        context: Context,
        stationId: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val editableSettings = sessionState?.editableSettings

        if (sessionState == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Station ID update failed."
                statusIsError = true
            }
            emitCommandLog("set-station-id", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            draftStationId = stationId
            latestSubmitSummary = "Submitting Station ID update..."
            statusText = "Submitting Station ID update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings = editableSettings.copy(
                    stationId = editableSettings.stationId.copy(editedValue = stationId.trim()),
                )
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                    draftStationId = submitResult.state.snapshot?.settings?.stationId.orEmpty()
                    draftEventType = submitResult.state.snapshot?.settings?.eventType?.name.orEmpty()
                    draftFoxRole = submitResult.state.snapshot?.settings?.foxRole?.uiLabel.orEmpty()
                    draftIdSpeedWpm = submitResult.state.snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
                    draftPatternText = submitResult.state.snapshot?.settings?.patternText.orEmpty()
                    draftPatternSpeedWpm = submitResult.state.snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
                    draftCurrentFrequency = formatFrequencyInput(submitResult.state.snapshot?.settings?.defaultFrequencyHz)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Station ID already matched the requested value."
                        } else {
                            "Latest load remains available above. Station ID submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Station ID already matched the requested value."
                        } else {
                            "Station ID updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Station ID update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-station-id",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runIdSpeedSubmit(
        context: Context,
        idSpeedWpmText: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val parsedSpeed = idSpeedWpmText.trim().toIntOrNull()
        if (parsedSpeed == null || parsedSpeed !in 5..20) {
            val error = IllegalArgumentException("ID Speed must be an integer from 5 to 20 WPM.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "ID Speed update failed."
                statusIsError = true
            }
            emitCommandLog("set-id-speed", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "ID Speed update failed."
                statusIsError = true
            }
            emitCommandLog("set-id-speed", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            draftIdSpeedWpm = parsedSpeed.toString()
            latestSubmitSummary = "Submitting ID Speed update..."
            statusText = "Submitting ID Speed update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-id-speed-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings = editableSettings.copy(
                    idCodeSpeedWpm = editableSettings.idCodeSpeedWpm.copy(editedValue = parsedSpeed),
                )
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                    draftStationId = submitResult.state.snapshot?.settings?.stationId.orEmpty()
                    draftEventType = submitResult.state.snapshot?.settings?.eventType?.name.orEmpty()
                    draftFoxRole = submitResult.state.snapshot?.settings?.foxRole?.uiLabel.orEmpty()
                    draftIdSpeedWpm = submitResult.state.snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
                    draftPatternText = submitResult.state.snapshot?.settings?.patternText.orEmpty()
                    draftPatternSpeedWpm = submitResult.state.snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
                    draftCurrentFrequency = formatFrequencyInput(submitResult.state.snapshot?.settings?.defaultFrequencyHz)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. ID Speed already matched the requested value."
                        } else {
                            "Latest load remains available above. ID Speed submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "ID Speed already matched the requested value."
                        } else {
                            "ID Speed updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "ID Speed update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-id-speed",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runLowBatteryThresholdSubmit(
        context: Context,
        thresholdText: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val parsedThreshold =
            thresholdText
                .removeSuffix("V")
                .trim()
                .toDoubleOrNull()
        if (parsedThreshold == null || parsedThreshold < 3.5 || parsedThreshold > 4.1) {
            val error = IllegalArgumentException("Low Battery Threshold must be between 3.5 V and 4.1 V.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Low Bat. Thresh. update failed."
                statusIsError = true
            }
            emitCommandLog("set-low-battery-threshold", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Low Bat. Thresh. update failed."
                statusIsError = true
            }
            emitCommandLog("set-low-battery-threshold", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsExternalBatteryControl) {
            val error = IllegalStateException("Low battery threshold is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Low Bat. Thresh. update failed."
                statusIsError = true
            }
            emitCommandLog("set-low-battery-threshold", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            latestSubmitSummary = "Submitting Low Bat. Thresh. update..."
            statusText = "Submitting Low Bat. Thresh. update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-low-battery-threshold-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result =
                if (snapshot.settings.lowBatteryThresholdVolts == parsedThreshold) {
                    Result.success(noOpSubmitResult(sessionState))
                } else {
                    runWithResolvedTransport(
                        context = context,
                        requestedDeviceName = requestedDeviceName,
                        requestedTarget = null,
                        allowUsbAutoDetect = false,
                        missingMessage = "SignalSlinger is no longer connected.",
                    ) { target, transport ->
                        resolvedTarget = target
                        val editedSettings = editableSettings.copy(
                            lowBatteryThresholdVolts = editableSettings.lowBatteryThresholdVolts.copy(editedValue = parsedThreshold),
                        )
                        DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
                    }
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    applySnapshotDrafts(submitResult.state.snapshot, refreshClockDisplayAnchor = false)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Low Bat. Thresh. already matched the requested value."
                        } else {
                            "Latest load remains available above. Low Bat. Thresh. submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Low Bat. Thresh. already matched the requested value."
                        } else {
                            "Low Bat. Thresh. updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Low Bat. Thresh. update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-low-battery-threshold",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runExternalBatteryControlSubmit(
        context: Context,
        mode: ExternalBatteryControlMode,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Ext. Bat. Ctrl update failed."
                statusIsError = true
            }
            emitCommandLog("set-external-battery-control", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsExternalBatteryControl) {
            val error = IllegalStateException("External battery control is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Ext. Bat. Ctrl update failed."
                statusIsError = true
            }
            emitCommandLog("set-external-battery-control", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            latestSubmitSummary = "Submitting Ext. Bat. Ctrl update..."
            statusText = "Submitting Ext. Bat. Ctrl update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-external-battery-control-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result =
                if (snapshot.settings.externalBatteryControlMode == mode) {
                    Result.success(noOpSubmitResult(sessionState))
                } else {
                    runWithResolvedTransport(
                        context = context,
                        requestedDeviceName = requestedDeviceName,
                        requestedTarget = null,
                        allowUsbAutoDetect = false,
                        missingMessage = "SignalSlinger is no longer connected.",
                    ) { target, transport ->
                        resolvedTarget = target
                        val editedSettings = editableSettings.copy(
                            externalBatteryControlMode = editableSettings.externalBatteryControlMode.copy(editedValue = mode),
                        )
                        DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
                    }
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    applySnapshotDrafts(submitResult.state.snapshot, refreshClockDisplayAnchor = false)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Ext. Bat. Ctrl already matched the requested value."
                        } else {
                            "Latest load remains available above. Ext. Bat. Ctrl submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Ext. Bat. Ctrl already matched the requested value."
                        } else {
                            "Ext. Bat. Ctrl updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Ext. Bat. Ctrl update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-external-battery-control",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runPatternSpeedSubmit(
        context: Context,
        patternSpeedWpmText: String,
        updateCloneTemplate: Boolean = false,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val parsedSpeed = patternSpeedWpmText.trim().toIntOrNull()
        if (parsedSpeed == null || parsedSpeed !in 5..20) {
            val error = IllegalArgumentException("Pattern Speed must be an integer from 5 to 20 WPM.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Pattern Speed update failed."
                statusIsError = true
            }
            emitCommandLog("set-pattern-speed", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Pattern Speed update failed."
                statusIsError = true
            }
            emitCommandLog("set-pattern-speed", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (snapshot.settings.eventType != EventType.FOXORING) {
            val error = IllegalStateException("Pattern Speed editing is currently limited to FOXORING snapshots.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Pattern Speed update failed."
                statusIsError = true
            }
            emitCommandLog("set-pattern-speed", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            draftPatternSpeedWpm = parsedSpeed.toString()
            latestSubmitSummary = "Submitting Pattern Speed update..."
            statusText = "Submitting Pattern Speed update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-pattern-speed-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings = editableSettings.copy(
                    patternCodeSpeedWpm = editableSettings.patternCodeSpeedWpm.copy(editedValue = parsedSpeed),
                )
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    if (updateCloneTemplate) {
                        rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                    }
                    draftStationId = submitResult.state.snapshot?.settings?.stationId.orEmpty()
                    draftEventType = submitResult.state.snapshot?.settings?.eventType?.name.orEmpty()
                    draftFoxRole = submitResult.state.snapshot?.settings?.foxRole?.uiLabel.orEmpty()
                    draftIdSpeedWpm = submitResult.state.snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
                    draftPatternText = submitResult.state.snapshot?.settings?.patternText.orEmpty()
                    draftPatternSpeedWpm = submitResult.state.snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
                    draftCurrentFrequency = formatFrequencyInput(submitResult.state.snapshot?.settings?.defaultFrequencyHz)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Pattern Speed already matched the requested value."
                        } else {
                            "Latest load remains available above. Pattern Speed submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Pattern Speed already matched the requested value."
                        } else {
                            "Pattern Speed updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Pattern Speed update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-pattern-speed",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runPatternTextSubmit(
        context: Context,
        patternTextInput: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val normalizedPatternText = patternTextInput.trim().ifBlank { null }
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Pattern Text update failed."
                statusIsError = true
            }
            emitCommandLog("set-pattern-text", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsPatternEditing || snapshot.settings.eventType.name != "FOXORING") {
            val error = IllegalStateException("Pattern Text editing is only supported for FOXORING snapshots.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Pattern Text update failed."
                statusIsError = true
            }
            emitCommandLog("set-pattern-text", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            draftPatternText = normalizedPatternText.orEmpty()
            latestSubmitSummary = "Submitting Pattern Text update..."
            statusText = "Submitting Pattern Text update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-pattern-text-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings = editableSettings.copy(
                    patternText = editableSettings.patternText.copy(editedValue = normalizedPatternText),
                )
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    draftStationId = submitResult.state.snapshot?.settings?.stationId.orEmpty()
                    draftEventType = submitResult.state.snapshot?.settings?.eventType?.name.orEmpty()
                    draftFoxRole = submitResult.state.snapshot?.settings?.foxRole?.uiLabel.orEmpty()
                    draftIdSpeedWpm = submitResult.state.snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
                    draftPatternText = submitResult.state.snapshot?.settings?.patternText.orEmpty()
                    draftPatternSpeedWpm = submitResult.state.snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
                    draftCurrentFrequency = formatFrequencyInput(submitResult.state.snapshot?.settings?.defaultFrequencyHz)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Pattern Text already matched the requested value."
                        } else {
                            "Latest load remains available above. Pattern Text submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Pattern Text already matched the requested value."
                        } else {
                            "Pattern Text updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Pattern Text update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-pattern-text",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runCurrentFrequencySubmit(
        context: Context,
        frequencyInput: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val parsedFrequencyHz = FrequencySupport.parseFrequencyHz(frequencyInput)
        if (parsedFrequencyHz == null) {
            val error = IllegalArgumentException("Current Frequency must be a valid Hz, kHz, or MHz value.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Current Frequency update failed."
                statusIsError = true
            }
            emitCommandLog("set-current-frequency", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Current Frequency update failed."
                statusIsError = true
            }
            emitCommandLog("set-current-frequency", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            draftCurrentFrequency = formatFrequencyInput(parsedFrequencyHz)
            latestSubmitSummary = "Submitting Current Frequency update..."
            statusText = "Submitting Current Frequency update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-current-frequency-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings = editableSettings.copy(
                    defaultFrequencyHz = editableSettings.defaultFrequencyHz.copy(editedValue = parsedFrequencyHz),
                )
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    draftStationId = submitResult.state.snapshot?.settings?.stationId.orEmpty()
                    draftEventType = submitResult.state.snapshot?.settings?.eventType?.name.orEmpty()
                    draftFoxRole = submitResult.state.snapshot?.settings?.foxRole?.uiLabel.orEmpty()
                    draftIdSpeedWpm = submitResult.state.snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
                    draftPatternText = submitResult.state.snapshot?.settings?.patternText.orEmpty()
                    draftPatternSpeedWpm = submitResult.state.snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
                    draftCurrentFrequency = formatFrequencyInput(submitResult.state.snapshot?.settings?.defaultFrequencyHz)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Current Frequency already matched the requested value."
                        } else {
                            "Latest load remains available above. Current Frequency submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Current Frequency already matched the requested value."
                        } else {
                            "Current Frequency updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Current Frequency update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-current-frequency",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runFrequencyBankSubmit(
        context: Context,
        bankId: FrequencyBankId,
        frequencyInput: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val parsedFrequencyHz = FrequencySupport.parseFrequencyHz(frequencyInput)
        if (parsedFrequencyHz == null) {
            val error = IllegalArgumentException("${bankId.label} must be a valid Hz, kHz, or MHz value.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "${bankId.label} update failed."
                statusIsError = true
            }
            emitCommandLog("set-frequency-bank", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "${bankId.label} update failed."
                statusIsError = true
            }
            emitCommandLog("set-frequency-bank", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsFrequencyProfiles) {
            val error = IllegalStateException("Frequency bank editing is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "${bankId.label} update failed."
                statusIsError = true
            }
            emitCommandLog("set-frequency-bank", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            latestSubmitSummary = "Submitting ${bankId.label} update..."
            statusText = "Submitting ${bankId.label} update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-frequency-bank-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings =
                    when (bankId) {
                        FrequencyBankId.ONE ->
                            editableSettings.copy(
                                lowFrequencyHz = editableSettings.lowFrequencyHz.copy(editedValue = parsedFrequencyHz),
                            )
                        FrequencyBankId.TWO ->
                            editableSettings.copy(
                                mediumFrequencyHz = editableSettings.mediumFrequencyHz.copy(editedValue = parsedFrequencyHz),
                            )
                        FrequencyBankId.THREE ->
                            editableSettings.copy(
                                highFrequencyHz = editableSettings.highFrequencyHz.copy(editedValue = parsedFrequencyHz),
                            )
                        FrequencyBankId.BEACON ->
                            editableSettings.copy(
                                beaconFrequencyHz = editableSettings.beaconFrequencyHz.copy(editedValue = parsedFrequencyHz),
                            )
                    }
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                    draftStationId = submitResult.state.snapshot?.settings?.stationId.orEmpty()
                    draftEventType = submitResult.state.snapshot?.settings?.eventType?.name.orEmpty()
                    draftFoxRole = submitResult.state.snapshot?.settings?.foxRole?.uiLabel.orEmpty()
                    draftIdSpeedWpm = submitResult.state.snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
                    draftPatternText = submitResult.state.snapshot?.settings?.patternText.orEmpty()
                    draftPatternSpeedWpm = submitResult.state.snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
                    draftCurrentFrequency = formatFrequencyInput(submitResult.state.snapshot?.settings?.defaultFrequencyHz)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. ${bankId.label} already matched the requested value."
                        } else {
                            "Latest load remains available above. ${bankId.label} submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "${bankId.label} already matched the requested value."
                        } else {
                            "${bankId.label} updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "${bankId.label} update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-frequency-bank",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runFoxRoleSubmit(
        context: Context,
        foxRoleInput: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Fox Role update failed."
                statusIsError = true
            }
            emitCommandLog("set-fox-role", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val parsedFoxRole =
            parseFoxRoleInput(
                raw = foxRoleInput,
                eventType = snapshot.settings.eventType,
            )
        if (parsedFoxRole == null) {
            val allowed = foxRoleOptions(snapshot.settings.eventType).joinToString(", ") { it.uiLabel }
            val error = IllegalArgumentException("Unsupported Fox Role for ${snapshot.settings.eventType}: `$foxRoleInput`. Try one of: $allowed")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Fox Role update failed."
                statusIsError = true
            }
            emitCommandLog("set-fox-role", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            draftFoxRole = parsedFoxRole.uiLabel
            latestSubmitSummary = "Submitting Fox Role update: ${parsedFoxRole.uiLabel}"
            statusText = "Submitting Fox Role: ${parsedFoxRole.uiLabel}"
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-fox-role-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            var callbackResult: Result<DeviceSubmitResult>
            var commandSucceeded: Boolean
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings = editableSettings.copy(
                    foxRole = editableSettings.foxRole.copy(editedValue = parsedFoxRole),
                )
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    val verificationFailure = submitResult.submitVerificationFailure()
                    if (verificationFailure) {
                        latestSubmitSummary = renderSubmitFailureSummary(submitResult)
                        latestProbeSummary = "Latest load remains available above. Fox Role submission failed verification."
                        statusText = "Fox Role update failed verification."
                        statusIsError = true
                        callbackResult = Result.failure(IllegalStateException(submitResult.submitVerificationFailureMessage()))
                        commandSucceeded = false
                    } else {
                        latestSessionViewState = AndroidSessionViewState(
                            state = submitResult.state,
                            traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                        )
                        resolvedTarget?.let(::rememberLoadedTargetLocked)
                        draftStationId = submitResult.state.snapshot?.settings?.stationId.orEmpty()
                        draftEventType = submitResult.state.snapshot?.settings?.eventType?.name.orEmpty()
                        draftFoxRole = submitResult.state.snapshot?.settings?.foxRole?.uiLabel.orEmpty()
                        draftIdSpeedWpm = submitResult.state.snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
                        draftPatternText = submitResult.state.snapshot?.settings?.patternText.orEmpty()
                        draftPatternSpeedWpm = submitResult.state.snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
                        draftCurrentFrequency = formatFrequencyInput(submitResult.state.snapshot?.settings?.defaultFrequencyHz)
                        latestSubmitSummary = renderSubmitSummary(submitResult)
                        latestProbeSummary =
                            if (wasNoOp) {
                                "Latest load remains available above. Fox Role already matched the requested value."
                            } else {
                                "Latest load remains available above. Fox Role submission completed."
                            }
                        statusText =
                            if (wasNoOp) {
                                "Fox Role already matched the requested value."
                            } else {
                                "Fox Role updated and verified."
                            }
                        statusIsError = false
                        callbackResult = Result.success(submitResult)
                        commandSucceeded = true
                    }
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Fox Role update failed."
                    statusIsError = true
                    callbackResult = Result.failure(result.exceptionOrNull() ?: IllegalStateException("Fox Role update failed."))
                    commandSucceeded = false
                }
            }

            emitCommandLog(
                command = "set-fox-role",
                source = source,
                success = commandSucceeded,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(callbackResult) }
            }
        }
    }

    fun runEventTypeSubmit(
        context: Context,
        eventTypeInput: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Event Type update failed."
                statusIsError = true
            }
            emitCommandLog("set-event-type", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val parsedEventType = parseEventTypeInput(eventTypeInput)
        if (parsedEventType == null || parsedEventType == EventType.NONE) {
            val error = IllegalArgumentException("Unsupported Event Type `$eventTypeInput`. Try CLASSIC, FOXORING, or SPRINT.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Event Type update failed."
                statusIsError = true
            }
            emitCommandLog("set-event-type", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            draftEventType = parsedEventType.name
            latestSubmitSummary = "Submitting Event Type update..."
            statusText = "Submitting Event Type update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-event-type-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings = editableSettings.copy(
                    eventType = editableSettings.eventType.copy(editedValue = parsedEventType),
                )
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                    draftStationId = submitResult.state.snapshot?.settings?.stationId.orEmpty()
                    draftEventType = submitResult.state.snapshot?.settings?.eventType?.name.orEmpty()
                    draftFoxRole = submitResult.state.snapshot?.settings?.foxRole?.uiLabel.orEmpty()
                    draftIdSpeedWpm = submitResult.state.snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
                    draftPatternText = submitResult.state.snapshot?.settings?.patternText.orEmpty()
                    draftPatternSpeedWpm = submitResult.state.snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
                    draftCurrentFrequency = formatFrequencyInput(submitResult.state.snapshot?.settings?.defaultFrequencyHz)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Event Type already matched the requested value."
                        } else {
                            "Latest load remains available above. Event Type submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Event Type already matched the requested value."
                        } else {
                            "Event Type updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Event Type update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-event-type",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runEventProfileSubmit(
        context: Context,
        eventTypeInput: String,
        foxRoleInput: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Event Profile update failed."
                statusIsError = true
            }
            emitCommandLog("set-event-profile", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val parsedEventType = parseEventTypeInput(eventTypeInput)
        if (parsedEventType == null || parsedEventType == EventType.NONE) {
            val error = IllegalArgumentException("Unsupported Event Type `$eventTypeInput`. Try CLASSIC, FOXORING, or SPRINT.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Event Profile update failed."
                statusIsError = true
            }
            emitCommandLog("set-event-profile", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val parsedFoxRole = parseFoxRoleInput(foxRoleInput, parsedEventType)
        if (parsedFoxRole == null) {
            val allowed = foxRoleOptions(parsedEventType).joinToString(", ") { it.uiLabel }
            val error = IllegalArgumentException("Unsupported Fox Role for $parsedEventType: `$foxRoleInput`. Try one of: $allowed")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Event Profile update failed."
                statusIsError = true
            }
            emitCommandLog("set-event-profile", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            draftEventType = parsedEventType.name
            draftFoxRole = parsedFoxRole.uiLabel
            latestSubmitSummary = "Submitting Event Profile update..."
            statusText = "Submitting Event Profile update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-event-profile-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editedSettings = editableSettings.copy(
                    eventType = editableSettings.eventType.copy(editedValue = parsedEventType),
                    foxRole = editableSettings.foxRole.copy(editedValue = parsedFoxRole),
                )
                DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                    applySnapshotDrafts(submitResult.state.snapshot, refreshClockDisplayAnchor = false)
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Event Profile already matched the requested values."
                        } else {
                            "Latest load remains available above. Event Profile submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Event Profile already matched the requested values."
                        } else {
                            "Event Profile updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Event Profile update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-event-profile",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runDaysToRunSubmit(
        context: Context,
        daysToRunText: String,
        requestedFinishTimeInput: String? = null,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val parsedDaysToRun = daysToRunText.trim().toIntOrNull()
        if (parsedDaysToRun == null || parsedDaysToRun < 1) {
            val error = IllegalArgumentException("Days To Run must be an integer of at least 1.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Days To Run update failed."
                statusIsError = true
            }
            emitCommandLog("set-days-to-run", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Days To Run update failed."
                statusIsError = true
            }
            emitCommandLog("set-days-to-run", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsScheduling) {
            val error = IllegalStateException("Days To Run editing is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Days To Run update failed."
                statusIsError = true
            }
            emitCommandLog("set-days-to-run", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val normalizedRequestedFinishTime =
            try {
                requestedFinishTimeInput
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { requestedFinishInput ->
                        JvmTimeSupport.parseOptionalCompactTimestamp(requestedFinishInput)
                            ?: error("Finish Time must not be blank.")
                    }
            } catch (error: Throwable) {
                synchronized(this) {
                    latestSubmitSummary = "Submit failed.\n${error.message}"
                    statusText = "Days To Run update failed."
                    statusIsError = true
                }
                emitCommandLog("set-days-to-run", source, success = false, summary = error.message.orEmpty())
                notifyListeners()
                onComplete?.let { callback ->
                    mainHandler.post { callback(Result.failure(error)) }
                }
                return
            }

        synchronized(this) {
            draftDaysToRun = parsedDaysToRun.toString()
            latestSubmitSummary = "Submitting Days To Run update..."
            statusText = "Submitting Days To Run update..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-days-to-run-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val editRequest = ScheduleSubmitSupport.daysToRunEdit(
                    currentSettings = snapshot.settings,
                    requestedDaysToRun = parsedDaysToRun,
                    requestedFinishTimeCompact = normalizedRequestedFinishTime,
                )
                if (
                    editRequest.daysToRun == snapshot.settings.daysToRun &&
                    editRequest.startTimeCompact == snapshot.settings.startTimeCompact &&
                    editRequest.finishTimeCompact == snapshot.settings.finishTimeCompact &&
                    editRequest.forceWriteKeys.isEmpty()
                ) {
                    noOpSubmitResult(sessionState)
                } else {
                    val editedSettings = editableSettings.copy(
                        daysToRun = editableSettings.daysToRun.copy(editedValue = editRequest.daysToRun),
                        startTimeCompact = editableSettings.startTimeCompact.copy(
                            editedValue = editRequest.startTimeCompact,
                        ),
                        finishTimeCompact = editableSettings.finishTimeCompact.copy(
                            editedValue = editRequest.finishTimeCompact,
                        ),
                    )
                    DeviceSessionController.submitEdits(
                        sessionState,
                        editedSettings,
                        transport,
                        forceWriteKeys = editRequest.forceWriteKeys,
                    )
                }
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                    applySnapshotDrafts(submitResult.state.snapshot, refreshClockDisplayAnchor = false)
                    timeWorkflowNotice = null
                    clearScheduleDerivedDataPendingLocked()
                    latestSubmitSummary = renderSubmitSummary(submitResult)
                    latestProbeSummary =
                        if (wasNoOp) {
                            "Latest load remains available above. Days To Run already matched the requested value."
                        } else {
                            "Latest load remains available above. Days To Run submission completed."
                        }
                    statusText =
                        if (wasNoOp) {
                            "Days To Run already matched the requested value."
                        } else {
                            "Days To Run updated and verified."
                        }
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Days To Run update failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                }
            }

            emitCommandLog(
                command = "set-days-to-run",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(result) }
            }
        }
    }

    fun runTimeSettingSequenceSubmit(
        context: Context,
        currentTimeInput: String,
        startTimeInput: String,
        finishTimeInput: String,
        daysToRunText: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceLoadResult>) -> Unit)? = null,
    ) {
        synchronized(this) {
            latestSubmitSummary = "Submitting time-setting sequence in device order..."
            statusText = "Submitting time-setting sequence..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        runCurrentTimeSubmit(
            context = context,
            currentTimeInput = currentTimeInput,
            requestedDeviceName = requestedDeviceName,
            source = source,
        ) { currentResult ->
            val currentError = currentResult.exceptionOrNull()
            if (currentError != null) {
                onComplete?.let { callback ->
                    mainHandler.post { callback(Result.failure(currentError)) }
                }
                return@runCurrentTimeSubmit
            }

            runStartTimeSubmit(
                context = context,
                startTimeInput = startTimeInput,
                defaultEventLengthMinutes = 6 * 60,
                requestedDeviceName = requestedDeviceName,
                source = source,
            ) { startResult ->
                val startError = startResult.exceptionOrNull()
                if (startError != null) {
                    onComplete?.let { callback ->
                        mainHandler.post { callback(Result.failure(startError)) }
                    }
                    return@runStartTimeSubmit
                }

                runFinishTimeSubmit(
                    context = context,
                    finishTimeInput = finishTimeInput,
                    requestedDeviceName = requestedDeviceName,
                    source = source,
                ) { finishResult ->
                    val finishError = finishResult.exceptionOrNull()
                    if (finishError != null) {
                        onComplete?.let { callback ->
                            mainHandler.post { callback(Result.failure(finishError)) }
                        }
                        return@runFinishTimeSubmit
                    }

                    runDaysToRunSubmit(
                        context = context,
                        daysToRunText = daysToRunText,
                        requestedDeviceName = requestedDeviceName,
                        source = source,
                    ) { daysResult ->
                        val daysError = daysResult.exceptionOrNull()
                        if (daysError != null) {
                            onComplete?.let { callback ->
                                mainHandler.post { callback(Result.failure(daysError)) }
                            }
                            return@runDaysToRunSubmit
                        }

                        synchronized(this) {
                            latestProbeSummary = "Reloading SignalSlinger after time-setting sequence..."
                            statusText = "Reloading SignalSlinger after time-setting sequence..."
                            statusIsError = false
                        }
                        notifyListeners()

                        runProbe(
                            context = context,
                            requestedDeviceName = requestedDeviceName,
                            source = source,
                            onComplete = onComplete,
                        )
                    }
                }
            }
        }
    }

    fun runCurrentTimeSystemSync(
        context: Context,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        if (sessionState == null || snapshot == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Device Time sync failed."
                statusIsError = true
            }
            emitCommandLog("sync-current-time", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsScheduling) {
            val error = IllegalStateException("Device Time editing is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Device Time sync failed."
                statusIsError = true
            }
            emitCommandLog("sync-current-time", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            currentTimeSyncInFlight = true
            latestSubmitSummary = "Synchronizing Device Time to Android system time..."
            statusText = "Synchronizing Device Time..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-current-time-sync") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                performAlignedTimeSync(
                    transport = transport,
                    state = sessionState,
                    snapshot = snapshot,
                )
            }

            var commandSucceeded = false
            var callbackResult: Result<DeviceSubmitResult>
            synchronized(this) {
                currentTimeSyncInFlight = false
                if (result.isSuccess) {
                    val syncResult = result.getOrThrow()
                    val finalAttempt = syncResult.finalAttempt
                    commandSucceeded = syncResult.succeeded
                    latestSessionViewState = AndroidSessionViewState(
                        state = finalAttempt.state,
                        traceEntries = buildSyncTraceEntries(syncResult),
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    applySnapshotDrafts(finalAttempt.state.snapshot)
                    applyClockDisplayAnchor(
                        currentTimeCompact = finalAttempt.state.snapshot?.settings?.currentTimeCompact,
                        phaseErrorMillis = finalAttempt.phaseErrorMillis,
                    )
                    timeWorkflowNotice = null
                    clearScheduleDerivedDataPendingLocked()
                    latestSubmitSummary =
                        buildString {
                            appendLine(
                                if (syncResult.succeeded) {
                                    "Device Time synchronized to Android system time."
                                } else {
                                    "Device Time sync failed."
                                },
                            )
                            finalAttempt.phaseErrorMillis?.let { phase ->
                                appendLine("Measured phase error: ${JvmTimeSupport.formatSignedDurationMillis(phase)}")
                            }
                            append("Attempts: ${syncResult.attempts.size}")
                        }.trim()
                    latestProbeSummary =
                        if (syncResult.succeeded) {
                            "Latest load remains available above. Device Time sync completed."
                        } else {
                            "Latest load remains available above. Device Time sync failed."
                        }
                    statusText =
                        if (syncResult.succeeded) {
                            finalAttempt.phaseErrorMillis?.let { phase ->
                                "Device Time synchronized (${JvmTimeSupport.formatSignedDurationMillis(phase)})."
                            } ?: "Device Time synchronized."
                        } else {
                            finalAttempt.phaseErrorMillis?.let { phase ->
                                "Device Time sync failed (${JvmTimeSupport.formatSignedDurationMillis(phase)})."
                            } ?: "Device Time sync failed."
                        }
                    statusIsError = !syncResult.succeeded
                    callbackResult =
                        if (syncResult.succeeded) {
                            Result.success(finalAttempt.submitResult)
                        } else {
                            Result.failure(
                                IllegalStateException(
                                    finalAttempt.phaseErrorMillis?.let { phase ->
                                        "Device Time sync failed; measured phase error ${JvmTimeSupport.formatSignedDurationMillis(phase)}."
                                    } ?: "Device Time sync failed; measured phase error is unavailable.",
                                ),
                            )
                        }
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Device Time sync failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                    callbackResult = Result.failure(result.exceptionOrNull() ?: IllegalStateException("Device Time sync failed."))
                }
            }

            emitCommandLog(
                command = "sync-current-time",
                source = source,
                success = commandSucceeded,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post {
                    callback(callbackResult)
                }
            }
        }
    }

    fun runCurrentTimeSubmit(
        context: Context,
        currentTimeInput: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Device Time update failed."
                statusIsError = true
            }
            emitCommandLog("set-current-time", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsScheduling) {
            val error = IllegalStateException("Device Time editing is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Device Time update failed."
                statusIsError = true
            }
            emitCommandLog("set-current-time", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val normalizedCurrentTime =
            try {
                JvmTimeSupport.parseOptionalCompactTimestamp(currentTimeInput.trim())
                    ?.let(JvmTimeSupport::validateCurrentTimeForWrite)
                    ?: error("Device Time must not be blank. Use YYYY-MM-DD HH:MM[:SS] or YYMMDDhhmmss.")
            } catch (error: Throwable) {
                synchronized(this) {
                    latestSubmitSummary = "Submit failed.\n${error.message}"
                    statusText = "Device Time update failed."
                    statusIsError = true
                }
                emitCommandLog("set-current-time", source, success = false, summary = error.message.orEmpty())
                notifyListeners()
                onComplete?.let { callback ->
                    mainHandler.post { callback(Result.failure(error)) }
                }
                return
            }

        synchronized(this) {
            draftCurrentTime = formatDateTimeInput(normalizedCurrentTime)
            latestSubmitSummary = "Submitting Device Time update..."
            statusText = "Submitting Device Time update..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-current-time-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val estimatedWriteDelayMillis = synchronized(this@AndroidSessionController) { cachedManualWriteDelayMillis } ?: 0L
                val adjustedCurrentTime =
                    JvmTimeSupport.formatCompactTimestamp(
                        JvmTimeSupport.adjustManualTimeTargetForWrite(
                            selectedTime = JvmTimeSupport.parseCompactTimestamp(normalizedCurrentTime),
                            estimatedWriteDelayMillis = estimatedWriteDelayMillis,
                        ),
                    )
                if (adjustedCurrentTime == snapshot.settings.currentTimeCompact) {
                    noOpSubmitResult(sessionState)
                } else {
                    val editedSettings = editableSettings.copy(
                        currentTimeCompact = editableSettings.currentTimeCompact.copy(editedValue = adjustedCurrentTime),
                    )
                    DeviceSessionController.submitEdits(sessionState, editedSettings, transport)
                }
            }

            var callbackResult: Result<DeviceSubmitResult>
            var commandSucceeded: Boolean
            var commandTraceEntries: List<SerialTraceEntry> = emptyList()
            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    commandTraceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries
                    val verificationFailure = submitResult.submitVerificationFailure()
                    commandSucceeded = !verificationFailure
                    val wasNoOp = submitResult.wasNoOp()
                    val scheduleImpact = summarizeScheduleImpact(snapshot.settings, submitResult.state.snapshot?.settings, SettingKey.CURRENT_TIME)
                    if (verificationFailure) {
                        latestSubmitSummary = renderSubmitFailureSummary(submitResult)
                        latestProbeSummary = "Latest load remains available above. Device Time submission failed verification."
                        statusText = "Device Time update failed verification."
                        statusIsError = true
                        clearScheduleDerivedDataPendingLocked()
                        callbackResult = Result.failure(IllegalStateException(submitResult.submitVerificationFailureMessage()))
                    } else {
                        latestSessionViewState = AndroidSessionViewState(
                            state = submitResult.state,
                            traceEntries = commandTraceEntries,
                        )
                        resolvedTarget?.let(::rememberLoadedTargetLocked)
                        rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                        applySnapshotDrafts(submitResult.state.snapshot, refreshClockDisplayAnchor = false)
                        timeWorkflowNotice = scheduleImpact.noticeText
                        clearScheduleDerivedDataPendingLocked()
                        latestSubmitSummary = renderSubmitSummary(submitResult, scheduleImpact.summaryLines)
                        latestProbeSummary =
                            if (wasNoOp) {
                                "Latest load remains available above. Device Time already matched the requested value."
                            } else {
                                "Latest load remains available above. Device Time submission completed."
                            }
                        statusText =
                            if (wasNoOp) {
                                "Device Time already matched the requested value."
                            } else {
                                scheduleImpact.statusText ?: "Device Time updated and verified."
                            }
                        statusIsError = false
                        callbackResult = Result.success(submitResult)
                    }
                } else {
                    commandSucceeded = false
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Device Time update failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                    callbackResult = Result.failure(result.exceptionOrNull() ?: IllegalStateException("Device Time update failed."))
                }
            }

            emitCommandLog(
                command = "set-current-time",
                source = source,
                success = commandSucceeded,
                summary = synchronized(this) { latestSubmitSummary },
                traceEntries = commandTraceEntries,
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(callbackResult) }
            }
        }
    }

    fun runRelativeStartTimeSubmit(
        context: Context,
        offsetCommand: String,
        finishOffsetCommand: String,
        preservedDaysToRun: Int? = null,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceLoadResult>) -> Unit)? = null,
    ) {
        runRelativeScheduleSubmit(
            context = context,
            commands = ScheduleSubmitSupport.relativeStartCommands(
                offsetCommand = offsetCommand,
                finishOffsetCommand = finishOffsetCommand,
                preservedDaysToRun = preservedDaysToRun,
            ),
            primaryField = SettingKey.START_TIME,
            statusPrefix = "Relative Start Time",
            requestedDeviceName = requestedDeviceName,
            source = source,
            onComplete = onComplete,
        )
    }

    fun runDisableEventViaStartTimeCommand(
        context: Context,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceLoadResult>) -> Unit)? = null,
    ) {
        runRelativeScheduleSubmit(
            context = context,
            commands = ScheduleSubmitSupport.disableEventCommands(),
            primaryField = SettingKey.START_TIME,
            statusPrefix = "Disable Event",
            requestedDeviceName = requestedDeviceName,
            source = source,
            onComplete = onComplete,
        )
    }

    fun runRelativeFinishTimeSubmit(
        context: Context,
        offsetCommand: String,
        preservedDaysToRun: Int? = null,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceLoadResult>) -> Unit)? = null,
    ) {
        runRelativeScheduleSubmit(
            context = context,
            commands = ScheduleSubmitSupport.relativeFinishCommands(
                offsetCommand = offsetCommand,
                preservedDaysToRun = preservedDaysToRun,
            ),
            primaryField = SettingKey.FINISH_TIME,
            statusPrefix = "Relative Finish Time",
            requestedDeviceName = requestedDeviceName,
            source = source,
            onComplete = onComplete,
        )
    }

    fun runStartTimeSubmit(
        context: Context,
        startTimeInput: String,
        defaultEventLengthMinutes: Int,
        requestedFinishTimeInput: String? = null,
        preserveDaysToRun: Boolean = false,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Start Time update failed."
                statusIsError = true
            }
            emitCommandLog("set-start-time", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsScheduling) {
            val error = IllegalStateException("Start Time editing is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Start Time update failed."
                statusIsError = true
            }
            emitCommandLog("set-start-time", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val normalizedStartTime =
            try {
                JvmTimeSupport.parseOptionalCompactTimestamp(startTimeInput.trim())
                    ?: error("Start Time must not be blank. Use YYYY-MM-DD HH:MM[:SS] or YYMMDDhhmmss.")
            } catch (error: Throwable) {
                synchronized(this) {
                    latestSubmitSummary = "Submit failed.\n${error.message}"
                    statusText = "Start Time update failed."
                    statusIsError = true
                }
                emitCommandLog("set-start-time", source, success = false, summary = error.message.orEmpty())
                notifyListeners()
                onComplete?.let { callback ->
                    mainHandler.post { callback(Result.failure(error)) }
                }
                return
            }

        synchronized(this) {
            draftStartTime = formatDateTimeInput(normalizedStartTime)
            latestSubmitSummary = "Submitting Start Time update..."
            statusText = "Submitting Start Time update..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-start-time-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result =
                try {
                    val normalizedRequestedFinishTime =
                        requestedFinishTimeInput
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { requestedFinishInput ->
                                JvmTimeSupport.parseOptionalCompactTimestamp(requestedFinishInput)
                                    ?: error("Finish Time must not be blank.")
                            }
                    val editRequest = ScheduleSubmitSupport.absoluteStartEdit(
                        currentSettings = snapshot.settings,
                        normalizedStartTime = normalizedStartTime,
                        requestedFinishTimeCompact = normalizedRequestedFinishTime,
                        defaultEventLengthMinutes = if (normalizedRequestedFinishTime == null) {
                            validateDefaultEventLengthMinutes(defaultEventLengthMinutes)
                        } else {
                            null
                        },
                        preserveDaysToRun = preserveDaysToRun,
                    )

                    if (
                        editRequest.startTimeCompact == snapshot.settings.startTimeCompact &&
                        editRequest.finishTimeCompact == snapshot.settings.finishTimeCompact &&
                        editRequest.forceWriteKeys.isEmpty()
                    ) {
                        Result.success(noOpSubmitResult(sessionState))
                    } else {
                        runWithResolvedTransport(
                            context = context,
                            requestedDeviceName = requestedDeviceName,
                            requestedTarget = null,
                            allowUsbAutoDetect = false,
                            missingMessage = "SignalSlinger is no longer connected.",
                        ) { target, transport ->
                            resolvedTarget = target
                            val editedSettings = editableSettings.copy(
                                startTimeCompact = editableSettings.startTimeCompact.copy(editedValue = editRequest.startTimeCompact),
                                finishTimeCompact = editableSettings.finishTimeCompact.copy(editedValue = editRequest.finishTimeCompact),
                            )
                            DeviceSessionController.submitEdits(
                                sessionState,
                                editedSettings,
                                transport,
                                forceWriteKeys = editRequest.forceWriteKeys,
                            )
                        }
                    }
                } catch (error: Throwable) {
                    Result.failure(error)
                }

            var callbackResult: Result<DeviceSubmitResult>
            var commandSucceeded: Boolean
            var commandTraceEntries: List<SerialTraceEntry> = emptyList()
            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    commandTraceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries
                    val verificationFailure = submitResult.submitVerificationFailure()
                    commandSucceeded = !verificationFailure
                    val wasNoOp = submitResult.wasNoOp()
                    val scheduleImpact = summarizeScheduleImpact(snapshot.settings, submitResult.state.snapshot?.settings, SettingKey.START_TIME)
                    if (verificationFailure) {
                        latestSubmitSummary = renderSubmitFailureSummary(submitResult)
                        latestProbeSummary = "Latest load remains available above. Start Time submission failed verification."
                        statusText = "Start Time update failed verification."
                        statusIsError = true
                        clearScheduleDerivedDataPendingLocked()
                        callbackResult = Result.failure(IllegalStateException(submitResult.submitVerificationFailureMessage()))
                    } else {
                        latestSessionViewState = AndroidSessionViewState(
                            state = submitResult.state,
                            traceEntries = commandTraceEntries,
                        )
                        resolvedTarget?.let(::rememberLoadedTargetLocked)
                        rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                        applySnapshotDrafts(submitResult.state.snapshot, refreshClockDisplayAnchor = false)
                        timeWorkflowNotice = scheduleImpact.noticeText
                        clearScheduleDerivedDataPendingLocked()
                        latestSubmitSummary = renderSubmitSummary(submitResult, scheduleImpact.summaryLines)
                        latestProbeSummary =
                            if (wasNoOp) {
                                "Latest load remains available above. Start Time already matched the requested value."
                            } else {
                                "Latest load remains available above. Start Time submission completed."
                            }
                        statusText =
                            if (wasNoOp) {
                                "Start Time already matched the requested value."
                            } else {
                                scheduleImpact.statusText ?: "Start Time updated and verified."
                            }
                        statusIsError = false
                        callbackResult = Result.success(submitResult)
                    }
                } else {
                    commandSucceeded = false
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Start Time update failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                    callbackResult = Result.failure(result.exceptionOrNull() ?: IllegalStateException("Start Time update failed."))
                }
            }

            emitCommandLog(
                command = "set-start-time",
                source = source,
                success = commandSucceeded,
                summary = synchronized(this) { latestSubmitSummary },
                traceEntries = commandTraceEntries,
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(callbackResult) }
            }
        }
    }

    fun runFinishTimeSubmit(
        context: Context,
        finishTimeInput: String,
        preserveDaysToRun: Boolean = false,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val editableSettings = sessionState?.editableSettings
        if (sessionState == null || snapshot == null || editableSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Finish Time update failed."
                statusIsError = true
            }
            emitCommandLog("set-finish-time", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsScheduling) {
            val error = IllegalStateException("Finish Time editing is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Finish Time update failed."
                statusIsError = true
            }
            emitCommandLog("set-finish-time", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        val normalizedFinishTime =
            try {
                JvmTimeSupport.parseOptionalCompactTimestamp(finishTimeInput.trim())
                    ?: error("Finish Time must not be blank. Use YYYY-MM-DD HH:MM[:SS] or YYMMDDhhmmss.")
            } catch (error: Throwable) {
                synchronized(this) {
                    latestSubmitSummary = "Submit failed.\n${error.message}"
                    statusText = "Finish Time update failed."
                    statusIsError = true
                }
                emitCommandLog("set-finish-time", source, success = false, summary = error.message.orEmpty())
                notifyListeners()
                onComplete?.let { callback ->
                    mainHandler.post { callback(Result.failure(error)) }
                }
                return
            }

        synchronized(this) {
            draftFinishTime = formatDateTimeInput(normalizedFinishTime)
            latestSubmitSummary = "Submitting Finish Time update..."
            statusText = "Submitting Finish Time update..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-finish-time-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result =
                runCatching {
                    val editRequest = ScheduleSubmitSupport.absoluteFinishEdit(
                        currentSettings = snapshot.settings,
                        normalizedFinishTime = normalizedFinishTime,
                        preserveDaysToRun = preserveDaysToRun,
                    )
                    if (
                        editRequest.finishTimeCompact == snapshot.settings.finishTimeCompact &&
                        editRequest.startTimeCompact == snapshot.settings.startTimeCompact &&
                        editRequest.forceWriteKeys.isEmpty()
                    ) {
                        Result.success(noOpSubmitResult(sessionState))
                    } else {
                        runWithResolvedTransport(
                            context = context,
                            requestedDeviceName = requestedDeviceName,
                            requestedTarget = null,
                            allowUsbAutoDetect = false,
                            missingMessage = "SignalSlinger is no longer connected.",
                        ) { target, transport ->
                            resolvedTarget = target
                            val editedSettings =
                                editableSettings.copy(
                                    startTimeCompact = editableSettings.startTimeCompact.copy(
                                        editedValue = editRequest.startTimeCompact,
                                    ),
                                    finishTimeCompact = editableSettings.finishTimeCompact.copy(
                                        editedValue = editRequest.finishTimeCompact,
                                    ),
                                )
                            DeviceSessionController.submitEdits(
                                sessionState,
                                editedSettings,
                                transport,
                                forceWriteKeys = editRequest.forceWriteKeys,
                            )
                        }
                    }
                }.getOrElse { error -> Result.failure(error) }

            var callbackResult: Result<DeviceSubmitResult>
            var commandSucceeded: Boolean
            var commandTraceEntries: List<SerialTraceEntry> = emptyList()
            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    commandTraceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries
                    val verificationFailure = submitResult.submitVerificationFailure()
                    commandSucceeded = !verificationFailure
                    val wasNoOp = submitResult.wasNoOp()
                    val scheduleImpact = summarizeScheduleImpact(snapshot.settings, submitResult.state.snapshot?.settings, SettingKey.FINISH_TIME)
                    if (verificationFailure) {
                        latestSubmitSummary = renderSubmitFailureSummary(submitResult)
                        latestProbeSummary = "Latest load remains available above. Finish Time submission failed verification."
                        statusText = "Finish Time update failed verification."
                        statusIsError = true
                        clearScheduleDerivedDataPendingLocked()
                        callbackResult = Result.failure(IllegalStateException(submitResult.submitVerificationFailureMessage()))
                    } else {
                        latestSessionViewState = AndroidSessionViewState(
                            state = submitResult.state,
                            traceEntries = commandTraceEntries,
                        )
                        resolvedTarget?.let(::rememberLoadedTargetLocked)
                        rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                        applySnapshotDrafts(submitResult.state.snapshot, refreshClockDisplayAnchor = false)
                        timeWorkflowNotice = scheduleImpact.noticeText
                        clearScheduleDerivedDataPendingLocked()
                        latestSubmitSummary = renderSubmitSummary(submitResult, scheduleImpact.summaryLines)
                        latestProbeSummary =
                            if (wasNoOp) {
                                "Latest load remains available above. Finish Time already matched the requested value."
                            } else {
                                "Latest load remains available above. Finish Time submission completed."
                            }
                        statusText =
                            if (wasNoOp) {
                                "Finish Time already matched the requested value."
                            } else {
                                scheduleImpact.statusText ?: "Finish Time updated and verified."
                            }
                        statusIsError = false
                        callbackResult = Result.success(submitResult)
                    }
                } else {
                    commandSucceeded = false
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Finish Time update failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                    callbackResult = Result.failure(result.exceptionOrNull() ?: IllegalStateException("Finish Time update failed."))
                }
            }

            emitCommandLog(
                command = "set-finish-time",
                source = source,
                success = commandSucceeded,
                summary = synchronized(this) { latestSubmitSummary },
                traceEntries = commandTraceEntries,
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(callbackResult) }
            }
        }
    }

    fun runEventDurationSubmit(
        context: Context,
        requestedDuration: Duration,
        preserveDaysToRun: Boolean = false,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceSubmitResult>) -> Unit)? = null,
    ) {
        val snapshot = synchronized(this) { latestSessionViewState?.state?.snapshot }
        val normalizedFinishTime =
            try {
                ScheduleSubmitSupport.absoluteDurationEdit(
                    currentSettings = snapshot?.settings ?: error("Load a SignalSlinger snapshot before submitting changes."),
                    requestedDuration = requestedDuration,
                    preserveDaysToRun = preserveDaysToRun,
                ).finishTimeCompact ?: error("Finish Time must not be blank.")
            } catch (error: Throwable) {
                synchronized(this) {
                    latestSubmitSummary = "Submit failed.\n${error.message}"
                    statusText = "Lasts update failed."
                    statusIsError = true
                }
                emitCommandLog("set-lasts", source, success = false, summary = error.message.orEmpty())
                notifyListeners()
                onComplete?.let { callback ->
                    mainHandler.post { callback(Result.failure(error)) }
                }
                return
            }

        runFinishTimeSubmit(
            context = context,
            finishTimeInput = normalizedFinishTime,
            preserveDaysToRun = preserveDaysToRun,
            requestedDeviceName = requestedDeviceName,
            source = "set-lasts",
            onComplete = onComplete,
        )
    }

    fun debugStateSummary(): String {
        val uiState = snapshotUiState()
        val snapshot = uiState.sessionViewState?.state?.snapshot
        val currentLogFile = synchronized(this) { sessionLog?.currentLogFile()?.absolutePath }
        return buildString {
            appendLine("status=${uiState.statusText}")
            appendLine("statusIsError=${uiState.statusIsError}")
            appendLine("loadedDevice=${uiState.latestLoadedDeviceName ?: "<none>"}")
            appendLine("loadedTarget=${uiState.latestLoadedTargetLabel ?: "<none>"}")
            appendLine("softwareVersion=${snapshot?.info?.softwareVersion ?: "<none>"}")
            appendLine("stationId=${snapshot?.settings?.stationId ?: "<none>"}")
            appendLine("currentLogFile=${currentLogFile ?: "<none>"}")
            appendLine("timeWorkflowNotice=${uiState.timeWorkflowNotice ?: "<none>"}")
            appendLine("latestProbe=${uiState.latestProbeSummary.replace('\n', ' ')}")
            append("latestSubmit=${uiState.latestSubmitSummary.replace('\n', ' ')}")
        }
    }

    fun debugSnapshotSummary(): String {
        val sessionViewState = snapshotUiState().sessionViewState
        val snapshot = sessionViewState?.state?.snapshot ?: return "No SignalSlinger snapshot is currently loaded."

        return buildString {
            appendLine("connectionState=${sessionViewState.state.connectionState}")
            appendLine("softwareVersion=${snapshot.info.softwareVersion ?: "<unknown>"}")
            appendLine("hardwareBuild=${snapshot.info.hardwareBuild ?: "<unknown>"}")
            appendLine("productName=${snapshot.info.productName ?: "<unknown>"}")
            appendLine("stationId=${snapshot.settings.stationId}")
            appendLine("eventType=${snapshot.settings.eventType}")
            appendLine("foxRole=${snapshot.settings.foxRole?.uiLabel ?: "<unknown>"}")
            appendLine("effectivePatternText=${EventProfileSupport.displayPatternText(snapshot.settings.eventType, snapshot.settings.foxRole, snapshot.settings.patternText).ifBlank { "<unknown>" }}")
            appendLine("storedPatternText=${snapshot.settings.patternText ?: "<unknown>"}")
            appendLine("idCodeSpeedWpm=${snapshot.settings.idCodeSpeedWpm}")
            appendLine("patternCodeSpeedWpm=${snapshot.settings.patternCodeSpeedWpm}")
            appendLine("currentTime=${snapshot.settings.currentTimeCompact ?: "<unknown>"}")
            appendLine("startTime=${snapshot.settings.startTimeCompact ?: "<unknown>"}")
            appendLine("finishTime=${snapshot.settings.finishTimeCompact ?: "<unknown>"}")
            appendLine("daysToRun=${snapshot.settings.daysToRun}")
            appendLine("defaultFrequencyHz=${snapshot.settings.defaultFrequencyHz}")
            appendLine("lowFrequencyHz=${snapshot.settings.lowFrequencyHz ?: "<unknown>"}")
            appendLine("mediumFrequencyHz=${snapshot.settings.mediumFrequencyHz ?: "<unknown>"}")
            appendLine("highFrequencyHz=${snapshot.settings.highFrequencyHz ?: "<unknown>"}")
            appendLine("beaconFrequencyHz=${snapshot.settings.beaconFrequencyHz ?: "<unknown>"}")
            appendLine("maximumEverTemperatureC=${snapshot.status.maximumEverTemperatureC ?: "<unknown>"}")
            appendLine("maximumTemperatureC=${snapshot.status.maximumTemperatureC ?: "<unknown>"}")
            appendLine("temperatureC=${snapshot.status.temperatureC ?: "<unknown>"}")
            appendLine("minimumTemperatureC=${snapshot.status.minimumTemperatureC ?: "<unknown>"}")
            appendLine("thermalShutdownThresholdC=${snapshot.status.thermalShutdownThresholdC ?: "<unknown>"}")
            appendLine("internalBatteryVolts=${snapshot.status.internalBatteryVolts ?: "<unknown>"}")
            appendLine("externalBatteryVolts=${snapshot.status.externalBatteryVolts ?: "<unknown>"}")
            appendLine("eventEnabled=${snapshot.status.eventEnabled ?: "<unknown>"}")
            appendLine("supportsScheduling=${snapshot.capabilities.supportsScheduling}")
            appendLine("supportsFrequencyProfiles=${snapshot.capabilities.supportsFrequencyProfiles}")
            append("supportsPatternEditing=${snapshot.capabilities.supportsPatternEditing}")
        }
    }

    fun debugTraceSummary(limit: Int = 24): String {
        val traceEntries = snapshotUiState().sessionViewState?.traceEntries.orEmpty()
        if (traceEntries.isEmpty()) {
            return "No serial trace has been captured yet."
        }

        return traceEntries.takeLast(limit).joinToString("\n") { entry ->
            "${entry.direction.label} ${entry.payload}"
        }
    }

    fun debugSessionLogSummary(maxChars: Int = 16000): String {
        val log =
            synchronized(this) {
                sessionLog
            } ?: return "Android session log is not initialized yet."
        val tail = log.loadCurrentLogTail(maxChars)
        if (tail.isBlank()) {
            return "No Android session log has been captured yet.\nfile=${log.currentLogFile().absolutePath}"
        }
        return buildString {
            appendLine("file=${log.currentLogFile().absolutePath}")
            append(tail.trimEnd())
        }
    }

    fun clearSessionLogs(): String {
        val log =
            synchronized(this) {
                sessionLog
            } ?: return "Android session log is not initialized yet."
        val deletedCount = log.deleteAllLogs()
        return when (deletedCount) {
            0 -> "No Android session logs were present."
            1 -> "Deleted 1 Android session log."
            else -> "Deleted $deletedCount Android session logs."
        }
    }

    fun currentSessionLogFile(): File? {
        val log =
            synchronized(this) {
                sessionLog
            } ?: return null
        return log.ensureCurrentLogFile()
    }

    fun logAppEvent(
        title: String,
        lines: List<String>,
    ) {
        val trimmedLines = lines.map(String::trim).filter(String::isNotBlank)
        if (trimmedLines.isEmpty()) {
            return
        }
        trimmedLines.forEach { line ->
            Log.i(logTag, "event=$title $line")
        }
        appendSessionLogEntries(
            title = title,
            entries = trimmedLines.map { line ->
                AndroidLogEntry(
                    message = line,
                    category = AndroidLogCategory.APP,
                )
            },
        )
    }

    fun logUserAction(action: String) {
        val message = action.trim()
        if (message.isBlank()) {
            return
        }
        Log.i(logTag, "user_action=$message")
        appendSessionLogEntries(
            title = "User Action",
            entries = listOf(
                AndroidLogEntry(
                    message = message,
                    category = AndroidLogCategory.USER,
                ),
            ),
        )
    }

    private fun appendSessionLogEntries(
        title: String,
        entries: List<AndroidLogEntry>,
    ) {
        if (entries.isEmpty()) {
            return
        }
        synchronized(this) {
            sessionLog
        }?.appendSection(
            title = title,
            entries = entries,
        )
    }

    private fun resolveUsbDevice(
        usbManager: UsbManager,
        requestedDeviceName: String?,
    ): UsbDevice? {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceName }
        val preferredByName =
            requestedDeviceName?.let { name ->
                devices.firstOrNull { device ->
                    device.deviceName == name &&
                        usbManager.hasPermission(device) &&
                        AndroidUsbTransport.hasSupportedDriver(usbManager, device)
                }
            }
        if (preferredByName != null) {
            return preferredByName
        }

        return devices.firstOrNull { device ->
            usbManager.hasPermission(device) && AndroidUsbTransport.hasSupportedDriver(usbManager, device)
        }
    }

    private fun resolveRequestedTargetLocked(
        requestedTarget: AndroidConnectionTarget?,
        requestedDeviceName: String?,
    ): AndroidConnectionTarget? {
        return requestedTarget
            ?: requestedDeviceName?.let(AndroidConnectionTarget::Usb)
            ?: latestLoadedTarget
    }

    private fun rememberLoadedTargetLocked(target: AndroidConnectionTarget) {
        latestLoadedTarget = target
        latestLoadedDeviceName = (target as? AndroidConnectionTarget.Usb)?.deviceName
    }

    private fun resolveTransport(
        context: Context,
        requestedDeviceName: String?,
        requestedTarget: AndroidConnectionTarget?,
        allowUsbAutoDetect: Boolean,
    ): ResolvedTransport? {
        val desiredTarget =
            synchronized(this) {
                resolveRequestedTargetLocked(
                    requestedTarget = requestedTarget,
                    requestedDeviceName = requestedDeviceName,
                )
            }

        return when (desiredTarget) {
            is AndroidConnectionTarget.DirectSerial ->
                ResolvedTransport(
                    target = desiredTarget,
                    transport = AndroidDirectSerialTransport(path = desiredTarget.path),
                )
            is AndroidConnectionTarget.Usb -> {
                val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
                val usbDevice = resolveUsbDevice(usbManager, desiredTarget.deviceName) ?: return null
                ResolvedTransport(
                    target = AndroidConnectionTarget.Usb(usbDevice.deviceName),
                    transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice),
                )
            }
            null -> {
                if (!allowUsbAutoDetect) {
                    return null
                }
                val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
                val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName = null) ?: return null
                ResolvedTransport(
                    target = AndroidConnectionTarget.Usb(usbDevice.deviceName),
                    transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice),
                )
            }
        }
    }

    private inline fun <T> runWithResolvedTransport(
        context: Context,
        requestedDeviceName: String?,
        requestedTarget: AndroidConnectionTarget?,
        allowUsbAutoDetect: Boolean,
        missingMessage: String,
        block: (AndroidConnectionTarget, DeviceTransport) -> T,
    ): Result<T> {
        val resolvedTransport =
            resolveTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = requestedTarget,
                allowUsbAutoDetect = allowUsbAutoDetect,
            ) ?: return Result.failure(IllegalStateException(missingMessage))

        return try {
            resolvedTransport.transport.connect()
            Result.success(block(resolvedTransport.target, resolvedTransport.transport))
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            resolvedTransport.transport.disconnect()
        }
    }

    private fun buildSyncTraceEntries(result: TimeSyncOperationResult): List<SerialTraceEntry> {
        val traceEntries = mutableListOf<SerialTraceEntry>()
        result.attempts.forEach { attempt ->
            traceEntries += attempt.submitResult.submitTraceEntries
            traceEntries += attempt.submitResult.readbackTraceEntries
            attempt.verificationSamples.forEach { sample ->
                val sentAtMs = sample.sentAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val receivedAtMs = sample.receivedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                traceEntries += SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, sample.command)
                traceEntries += sample.responseLines.map { line ->
                    SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line)
                }
            }
        }
        return traceEntries
    }

    private fun nextFirmwareCloneClockTimeCompact(): String {
        val target = LocalDateTime.now().withNano(0).plusSeconds(1)
        val delayNanos = Duration.between(LocalDateTime.now(), target).toNanos().coerceAtLeast(0L)
        if (delayNanos > 0L) {
            Thread.sleep(delayNanos / 1_000_000L, (delayNanos % 1_000_000L).toInt())
        }
        return JvmTimeSupport.formatCompactTimestamp(target)
    }

    private fun waitForFirmwareCloneReset(transport: DeviceTransport): List<String> {
        val lines = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + firmwareCloneResetMaxWaitMs
        var sawResetOutput = false
        while (System.currentTimeMillis() < deadline) {
            val responseLines = transport.readAvailableLines()
            if (responseLines.isNotEmpty()) {
                lines += responseLines
                sawResetOutput = true
                Thread.sleep(250L)
            } else if (sawResetOutput) {
                return lines
            } else {
                Thread.sleep(250L)
            }
        }
        return lines
    }

    private fun waitForFirmwareCloneStartRetry() {
        Thread.sleep(250L)
    }

    private fun waitForFirmwareCloneCommandSettle() {
        Thread.sleep(firmwareCloneCommandSettleMs)
    }

    private fun waitForFirmwareCloneDiagnosticRecovery(transport: DeviceTransport): List<String> {
        Thread.sleep(80L)
        return if (transport is AndroidUsbTransport) {
            transport.readAvailableLinesBriefly()
        } else {
            emptyList()
        }
    }

    private fun mergeLoadResults(
        base: DeviceLoadResult,
        additional: DeviceLoadResult?,
    ): DeviceLoadResult {
        additional ?: return base
        return DeviceLoadResult(
            state = additional.state,
            commandsSent = base.commandsSent + additional.commandsSent,
            linesReceived = base.linesReceived + additional.linesReceived,
            traceEntries = base.traceEntries + additional.traceEntries,
        )
    }

    private fun postLoadClockSample(
        transport: DeviceTransport,
        result: DeviceLoadResult,
    ): Pair<DeviceLoadResult, ClockDisplayAnchor>? {
        val loadedSnapshot = result.state.snapshot ?: return null
        if (!loadedSnapshot.capabilities.supportsScheduling) {
            return null
        }
        if (!loadedSnapshot.hasWallClockTimeSet()) {
            return null
        }

        Thread.sleep(80L)
        val samples = observeClockPhaseSamples(transport = transport, maxSamples = 4)
        synchronized(this) {
            cachedManualWriteDelayMillis = estimateManualWriteDelayMillis(samples)
        }
        var updatedState = result.state
        samples.forEach { sample ->
            updatedState = DeviceSessionWorkflow.ingestReportLines(updatedState, sample.responseLines)
        }
        val phaseSamples =
            samples.map { sample ->
                ClockPhaseSample(
                    midpointAt = sample.midpointAt,
                    reportedTimeCompact = sample.reportedTimeCompact,
                )
            }
        val phaseErrorMillis =
            JvmTimeSupport.estimateClockPhaseErrorMillis(phaseSamples)
                ?: estimateCoarsePostLoadClockPhaseErrorMillis(phaseSamples)
        val latestSample = samples.lastOrNull()
        return DeviceLoadResult(
            state = updatedState,
            commandsSent = samples.map { it.command },
            linesReceived = samples.flatMap { it.responseLines },
            traceEntries =
                buildList {
                    samples.forEach { sample ->
                        val sentAtMs = sample.sentAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        val receivedAtMs = sample.receivedAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        add(SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, sample.command))
                        addAll(sample.responseLines.map { line -> SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line) })
                    }
                },
        ) to ClockDisplayAnchor(
            currentTimeCompact = latestSample?.reportedTimeCompact,
            referenceTime = latestSample?.midpointAt,
            phaseErrorMillis = phaseErrorMillis,
        )
    }

    private fun sampleClockReadLatency(
        transport: DeviceTransport,
        sampleCount: Int,
    ): List<ClockReadSample> {
        return buildList {
            repeat(sampleCount) { index ->
                add(readClockSample(transport))
                if (index < sampleCount - 1) {
                    Thread.sleep(80L)
                }
            }
        }
    }

    private fun estimateManualWriteDelayMillis(samples: List<ClockReadSample>): Long? {
        if (samples.isEmpty()) {
            return null
        }
        return (JvmTimeSupport.medianMillis(samples.map { it.roundTripMillis }) / 2).coerceAtLeast(0L)
    }

    private fun estimateCoarsePostLoadClockPhaseErrorMillis(samples: List<ClockPhaseSample>): Long? {
        val estimates = samples.mapNotNull(JvmTimeSupport::estimateCoarseClockErrorMillis)
        return estimates.takeIf { it.isNotEmpty() }?.let(JvmTimeSupport::medianMillis)
    }

    private fun performAlignedTimeSync(
        transport: DeviceTransport,
        state: DeviceSessionState,
        snapshot: DeviceSnapshot,
    ): TimeSyncOperationResult {
        val latencySamples = sampleClockReadLatency(transport, sampleCount = syncLatencySampleCount)
        var oneWayDelayMillis = JvmTimeSupport.medianMillis(latencySamples.map { it.roundTripMillis }) / 2
        var workingState = state
        var workingSnapshot = snapshot
        var bestAttempt: SyncAttempt? = null
        val attempts = mutableListOf<SyncAttempt>()
        var syncComplete = false

        repeat(syncMaxAttempts) {
            if (syncComplete) {
                return@repeat
            }
            val syncTargetTime = JvmTimeSupport.nextSyncTargetTime(
                minimumLeadMillis = maxOf(1_500L, oneWayDelayMillis + 400L),
            )
            val attempt =
                performSyncAttempt(
                    transport = transport,
                    state = workingState,
                    snapshot = workingSnapshot,
                    targetTime = syncTargetTime,
                    dispatchTime = syncTargetTime,
                    estimatedOneWayDelayMillis = oneWayDelayMillis.coerceIn(20L, 750L),
                )
            attempts += attempt
            workingState = attempt.state
            workingSnapshot = requireNotNull(attempt.state.snapshot)
            bestAttempt = chooseBetterSyncAttempt(bestAttempt, attempt)

            val phaseErrorMillis = attempt.phaseErrorMillis ?: run {
                syncComplete = true
                return@repeat
            }
            if (abs(phaseErrorMillis) <= CLOCK_PHASE_WARNING_THRESHOLD_MILLIS) {
                syncComplete = true
                return@repeat
            }
            oneWayDelayMillis = (oneWayDelayMillis + phaseErrorMillis).coerceIn(20L, 900L)
        }

        val finalAttempt = requireNotNull(bestAttempt) { "No sync attempt was completed." }
        return TimeSyncOperationResult(
            latencySamples = latencySamples,
            attempts = attempts,
            finalAttempt = finalAttempt,
            succeeded = finalAttempt.phaseErrorMillis?.let { abs(it) <= CLOCK_PHASE_WARNING_THRESHOLD_MILLIS } ?: true,
        )
    }

    private fun performSyncAttempt(
        transport: DeviceTransport,
        state: DeviceSessionState,
        snapshot: DeviceSnapshot,
        targetTime: LocalDateTime,
        dispatchTime: LocalDateTime,
        estimatedOneWayDelayMillis: Long,
    ): SyncAttempt {
        waitForSyncTargetDispatch(dispatchTime, estimatedOneWayDelayMillis)
        val editable = requireNotNull(state.editableSettings ?: snapshot.let { com.openardf.serialslinger.model.EditableDeviceSettings.fromDeviceSettings(it.settings) })
        val syncTimeCompact = JvmTimeSupport.formatCompactTimestamp(targetTime)
        val submitResult =
            DeviceSessionController.submitEdits(
                state = state,
                editedSettings =
                    editable.copy(
                        currentTimeCompact =
                            SettingsField(
                                "currentTimeCompact",
                                "Current Time",
                                snapshot.settings.currentTimeCompact,
                                syncTimeCompact,
                            ),
                    ),
                transport = transport,
                allowFullReloadVerification = false,
            )
        var nextState = submitResult.state
        val verificationSamples = observeClockPhaseSamples(transport, maxSamples = syncVerificationSampleMax)
        verificationSamples.forEach { sample ->
            nextState = DeviceSessionWorkflow.ingestReportLines(nextState, sample.responseLines)
        }
        val phaseErrorMillis =
            JvmTimeSupport.estimateClockPhaseErrorMillis(
                verificationSamples.map { sample ->
                    ClockPhaseSample(
                        midpointAt = sample.midpointAt,
                        reportedTimeCompact = sample.reportedTimeCompact,
                    )
                },
            )

        return SyncAttempt(
            targetTime = targetTime,
            dispatchTime = dispatchTime,
            estimatedOneWayDelayMillis = estimatedOneWayDelayMillis,
            submitResult = submitResult,
            verificationSamples = verificationSamples,
            phaseErrorMillis = phaseErrorMillis,
            state = nextState,
        )
    }

    private fun observeClockPhaseSamples(
        transport: DeviceTransport,
        maxSamples: Int = syncVerificationSampleMax,
    ): List<ClockReadSample> {
        val samples = mutableListOf<ClockReadSample>()
        repeat(maxSamples) { index ->
            val sample = readClockSample(transport)
            samples += sample
            val previousReported = samples.getOrNull(samples.lastIndex - 1)?.reportedTimeCompact
            if (sample.reportedTimeObserved && sample.reportedTimeCompact == null) {
                return samples
            }
            if (previousReported != null && sample.reportedTimeCompact != null && previousReported != sample.reportedTimeCompact) {
                return samples
            }
            if (index < maxSamples - 1) {
                Thread.sleep(90L)
            }
        }
        return samples
    }

    private fun readClockSample(
        transport: DeviceTransport,
        command: String = "CLK T",
    ): ClockReadSample {
        val sentAt = LocalDateTime.now()
        transport.sendCommands(listOf(command))
        val responseLines = transport.readAvailableLines()
        val receivedAt = LocalDateTime.now()
        val reportedTimeCompact =
            responseLines
                .mapNotNull { line -> com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec.parseReportLine(line)?.settingsPatch?.currentTimeCompact }
                .firstOrNull()
        return ClockReadSample(
            sentAt = sentAt,
            receivedAt = receivedAt,
            responseLines = responseLines,
            reportedTimeCompact = reportedTimeCompact,
            reportedTimeObserved = responseLines.any(::isClockTimeResponseLine),
            command = command,
        )
    }

    private fun isClockTimeResponseLine(line: String): Boolean {
        return line.trim().startsWith("* Time:", ignoreCase = true)
    }

    private fun chooseBetterSyncAttempt(
        currentBest: SyncAttempt?,
        candidate: SyncAttempt,
    ): SyncAttempt {
        if (currentBest == null) {
            return candidate
        }
        val currentScore = abs(currentBest.phaseErrorMillis ?: Long.MAX_VALUE / 4)
        val candidateScore = abs(candidate.phaseErrorMillis ?: Long.MAX_VALUE / 4)
        return if (candidateScore <= currentScore) candidate else currentBest
    }

    private fun waitForSyncTargetDispatch(
        targetTime: LocalDateTime,
        estimatedOneWayDelayMillis: Long,
    ) {
        val dispatchMoment = targetTime.minus(Duration.ofMillis(estimatedOneWayDelayMillis))
        while (true) {
            val remaining = Duration.between(LocalDateTime.now(), dispatchMoment).toMillis()
            if (remaining <= 0) {
                return
            }
            Thread.sleep(minOf(remaining, 5L))
        }
    }

    private fun renderProbeSummary(result: DeviceLoadResult): String {
        val snapshot = result.state.snapshot
        val productName = snapshot?.info?.productName?.ifBlank { null }
        val stationId = snapshot?.settings?.stationId?.ifBlank { null }
        return buildString {
            appendLine("Probe succeeded.")
            appendLine("Commands sent: ${result.commandsSent.size}")
            appendLine("Response lines: ${result.linesReceived.size}")
            appendLine("Software version: ${snapshot?.info?.softwareVersion ?: "<unknown>"}")
            productName?.let { appendLine("Product name: $it") }
            stationId?.let { appendLine("Station ID: $it") }
            if (result.linesReceived.isNotEmpty()) {
                appendLine("First lines:")
                result.linesReceived.take(8).forEach { line ->
                    appendLine(line)
                }
            }
        }.trim()
    }

    private fun renderRelativeScheduleSubmitSummary(
        statusPrefix: String,
        result: RelativeScheduleSubmitResult,
        additionalLines: List<String> = emptyList(),
    ): String {
        return buildString {
            appendLine("$statusPrefix submitted.")
            appendLine("Commands sent: ${result.commandsSent.size}")
            result.commandsSent.forEach { command ->
                appendLine("TX $command")
            }
            appendLine("Immediate response lines: ${result.responseLines.size}")
            appendLine("Reload commands sent: ${result.reloadResult.commandsSent.size}")
            appendLine("Reload response lines: ${result.reloadResult.linesReceived.size}")
            if (additionalLines.isNotEmpty()) {
                appendLine()
                appendLine("Observed coupled changes")
                additionalLines.forEach { line ->
                    appendLine(line)
                }
            }
        }.trim()
    }

    private fun renderCloneSubmitSummary(result: CloneSubmitResult): String {
        return buildString {
            appendLine("Clone submitted to attached SignalSlinger.")
            appendLine("Clone protocol: ${result.cloneProtocol}")
            result.firmwareCloneChecksum?.let { checksum ->
                appendLine("Firmware clone checksum: $checksum")
            }
            appendLine("Write commands sent: ${result.writeCommandCount}")
            appendLine("Write response lines: ${result.writeResponseLineCount}")
            appendLine("Reload commands sent: ${result.refreshCommandCount}")
            appendLine("Reload response lines: ${result.refreshResponseLineCount}")
            appendLine("Sync attempts: ${result.syncAttemptCount}")
            appendLine("Time sync status: ${if (result.syncSucceeded) "ok" else "attention needed"}")
            result.possibleNewDeviceReasons.forEach { reason ->
                appendLine("Possible new device detected due to $reason.")
            }
        }.trim()
    }

    private fun renderSubmitSummary(
        result: DeviceSubmitResult,
        additionalLines: List<String> = emptyList(),
    ): String {
        if (result.wasNoOp()) {
            return buildString {
                appendLine("No setting changes were required.")
                appendLine("Commands sent: 0")
                appendLine("Readback commands sent: 0")
                append("The requested value already matched the loaded SignalSlinger snapshot.")
            }.trim()
        }

        return buildString {
            appendLine("Submit succeeded.")
            appendLine("Commands sent: ${result.commandsSent.size}")
            appendLine("Submit response lines: ${result.linesReceived.size}")
            appendLine("Readback commands sent: ${result.readbackCommandsSent.size}")
            appendLine("Readback response lines: ${result.readbackLinesReceived.size}")
            if (additionalLines.isNotEmpty()) {
                appendLine()
                appendLine("Observed coupled changes")
                additionalLines.forEach { line ->
                    appendLine(line)
                }
            }
            appendLine()
            appendLine("Verification")
            if (result.verifications.isEmpty()) {
                appendLine("No verification results were returned.")
            } else {
                result.verifications.forEach { verification ->
                    appendLine(
                        "${verification.fieldKey}: verified=${verification.verified} observed=${verification.observedInReadback} expected=${verification.expectedValue} actual=${verification.actualValue}",
                    )
                }
            }
        }.trim()
    }

    private fun renderSubmitFailureSummary(
        result: DeviceSubmitResult,
    ): String {
        return buildString {
            appendLine("Submit failed.")
            appendLine(result.submitVerificationFailureMessage())
            appendLine("Commands sent: ${result.commandsSent.size}")
            appendLine("Submit response lines: ${result.linesReceived.size}")
            appendLine("Readback commands sent: ${result.readbackCommandsSent.size}")
            appendLine("Readback response lines: ${result.readbackLinesReceived.size}")
            appendLine()
            appendLine("Verification")
            if (result.verifications.isEmpty()) {
                appendLine("No verification results were returned.")
            } else {
                result.verifications.forEach { verification ->
                    appendLine(
                        "${verification.fieldKey}: verified=${verification.verified} observed=${verification.observedInReadback} expected=${verification.expectedValue} actual=${verification.actualValue}",
                    )
                }
            }
        }.trim()
    }

    private fun summarizeScheduleImpact(
        before: DeviceSettings,
        after: DeviceSettings?,
        primaryField: SettingKey,
    ): ScheduleImpactSummary {
        if (after == null) {
            return ScheduleImpactSummary(emptyList(), null, null)
        }

        val summaryLines = mutableListOf<String>()
        var statusText: String? = null
        var noticeText: String? = null

        if (before.daysToRun != after.daysToRun && primaryField != SettingKey.DAYS_TO_RUN) {
            summaryLines += "Days To Run changed from ${before.daysToRun} to ${after.daysToRun}."
            if ((primaryField == SettingKey.START_TIME || primaryField == SettingKey.FINISH_TIME) && after.daysToRun == 1) {
                summaryLines += "SignalSlinger reset Days To Run to 1 after this schedule change."
                noticeText = "SignalSlinger reset Days To Run to 1. Submit Days To Run again after changing Start Time or Finish Time."
                statusText =
                    when (primaryField) {
                        SettingKey.START_TIME -> "Start Time updated. Days To Run reset to 1."
                        SettingKey.FINISH_TIME -> "Finish Time updated. Days To Run reset to 1."
                    }
            }
        }

        if (primaryField != SettingKey.START_TIME && before.startTimeCompact != after.startTimeCompact) {
            summaryLines += "Start Time changed from ${before.startTimeCompact ?: "<not set>"} to ${after.startTimeCompact ?: "<not set>"}."
        }

        if (primaryField != SettingKey.FINISH_TIME && before.finishTimeCompact != after.finishTimeCompact) {
            summaryLines += "Finish Time changed from ${before.finishTimeCompact ?: "<not set>"} to ${after.finishTimeCompact ?: "<not set>"}."
        }

        return ScheduleImpactSummary(summaryLines = summaryLines, statusText = statusText, noticeText = noticeText)
    }

    private fun formatFrequencyInput(frequencyHz: Long?): String {
        return frequencyHz?.let(FrequencySupport::formatFrequencyMhz).orEmpty()
    }

    private fun formatDateTimeInput(compactTimestamp: String?): String {
        return JvmTimeSupport.formatCompactTimestamp(compactTimestamp)
    }

    private fun validateDefaultEventLengthMinutes(minutes: Int): Int {
        require(minutes in 10..(24 * 60)) {
            "Default Event Length must be between 10 minutes and 24 hours."
        }
        return minutes
    }

    private fun updateDeviceTimeOffset(
        currentTimeCompact: String?,
        referenceTime: LocalDateTime = LocalDateTime.now(),
    ) {
        val deviceTime = currentTimeCompact?.let(JvmTimeSupport::normalizeCurrentTimeCompactForDisplay)?.let(JvmTimeSupport::parseCompactTimestamp)
        deviceTimeOffset = deviceTime?.let { Duration.between(referenceTime, it) }
    }

    private fun applyClockDisplayAnchor(
        currentTimeCompact: String?,
        phaseErrorMillis: Long? = null,
        referenceTime: LocalDateTime = LocalDateTime.now(),
    ) {
        lastClockPhaseErrorMillis = phaseErrorMillis
        deviceTimeOffset = phaseErrorMillis?.let { Duration.ofMillis(-it) }
        if (deviceTimeOffset == null) {
            updateDeviceTimeOffset(currentTimeCompact = currentTimeCompact, referenceTime = referenceTime)
        }
    }

    private fun applySnapshotDrafts(
        snapshot: DeviceSnapshot?,
        refreshClockDisplayAnchor: Boolean = true,
    ) {
        draftStationId = snapshot?.settings?.stationId.orEmpty()
        draftEventType = snapshot?.settings?.eventType?.name.orEmpty()
        draftFoxRole = snapshot?.settings?.foxRole?.uiLabel.orEmpty()
        draftIdSpeedWpm = snapshot?.settings?.idCodeSpeedWpm?.toString().orEmpty()
        draftPatternText = snapshot?.settings?.patternText.orEmpty()
        draftPatternSpeedWpm = snapshot?.settings?.patternCodeSpeedWpm?.toString().orEmpty()
        draftCurrentFrequency = formatFrequencyInput(snapshot?.settings?.defaultFrequencyHz)
        draftCurrentTime = formatDateTimeInput(snapshot?.settings?.currentTimeCompact)
        draftStartTime = formatDateTimeInput(snapshot?.settings?.startTimeCompact)
        draftFinishTime = formatDateTimeInput(snapshot?.settings?.finishTimeCompact)
        draftDaysToRun = snapshot?.settings?.daysToRun?.toString().orEmpty()
        if (refreshClockDisplayAnchor) {
            applyClockDisplayAnchor(snapshot?.settings?.currentTimeCompact)
        }
    }

    private fun rememberCloneTemplateFrom(sourceSnapshot: DeviceSnapshot) {
        rememberCloneTemplateFrom(sourceSnapshot.settings)
        cloneTemplateDaysRemaining = sourceSnapshot.status.daysRemaining
        cloneTemplateTimedEventEditsLocked = false
    }

    private fun possibleNewDeviceReasons(
        previousSnapshot: DeviceSnapshot?,
        loadedSnapshot: DeviceSnapshot?,
        previousPhaseErrorMillis: Long?,
        loadedPhaseErrorMillis: Long?,
    ): List<String> {
        previousSnapshot ?: return emptyList()
        loadedSnapshot ?: return emptyList()
        return buildList {
            if (previousSnapshot.info != loadedSnapshot.info) {
                add("device info changed")
            }
            if (previousSnapshot.capabilities != loadedSnapshot.capabilities) {
                add("device capabilities changed")
            }
            if (previousSnapshot.settings.copy(currentTimeCompact = null) != loadedSnapshot.settings.copy(currentTimeCompact = null)) {
                add("device settings changed other than Device Time")
            }
            if (
                previousSnapshot.status.copy(
                    eventStartsInSummary = null,
                    eventDurationSummary = null,
                    lastCommunicationError = null,
                ) != loadedSnapshot.status.copy(
                    eventStartsInSummary = null,
                    eventDurationSummary = null,
                    lastCommunicationError = null,
                )
            ) {
                add("device data changed")
            }

            val previousClockValid = previousSnapshot.hasValidWallClockTime()
            val loadedClockValid = loadedSnapshot.hasValidWallClockTime()
            if (previousClockValid != loadedClockValid) {
                add(
                    "wall clock validity changed from " +
                        "${if (previousClockValid) "valid" else "invalid"} to ${if (loadedClockValid) "valid" else "invalid"}",
                )
            }

            if (previousPhaseErrorMillis != null && loadedPhaseErrorMillis != null) {
                val phaseShiftMillis = loadedPhaseErrorMillis - previousPhaseErrorMillis
                if (abs(phaseShiftMillis) > POSSIBLE_NEW_DEVICE_PHASE_SHIFT_THRESHOLD_MILLIS) {
                    add(
                        "clock phase changed by ${JvmTimeSupport.formatSignedDurationMillis(phaseShiftMillis)} " +
                            "(previous ${JvmTimeSupport.formatSignedDurationMillis(previousPhaseErrorMillis)}, " +
                            "now ${JvmTimeSupport.formatSignedDurationMillis(loadedPhaseErrorMillis)})",
                    )
                }
            }
        }
    }

    private fun DeviceSnapshot.hasValidWallClockTime(): Boolean {
        return JvmTimeSupport.normalizeCurrentTimeCompactForDisplay(settings.currentTimeCompact) != null
    }

    private fun rememberCloneTemplateFrom(sourceSettings: DeviceSettings) {
        val existingTemplate = cloneTemplateSettings
        cloneTemplateSettings =
            if (existingTemplate == null) {
                sourceSettings
            } else {
                existingTemplate.copy(
                    stationId = sourceSettings.stationId,
                    eventType = sourceSettings.eventType,
                    foxRole = sourceSettings.foxRole,
                    idCodeSpeedWpm = sourceSettings.idCodeSpeedWpm,
                    patternText = sourceSettings.patternText,
                    patternCodeSpeedWpm = sourceSettings.patternCodeSpeedWpm,
                    currentTimeCompact = sourceSettings.currentTimeCompact,
                    startTimeCompact = sourceSettings.startTimeCompact,
                    finishTimeCompact = sourceSettings.finishTimeCompact,
                    daysToRun = sourceSettings.daysToRun,
                    lowFrequencyHz = sourceSettings.lowFrequencyHz,
                    mediumFrequencyHz = sourceSettings.mediumFrequencyHz,
                    highFrequencyHz = sourceSettings.highFrequencyHz,
                    beaconFrequencyHz = sourceSettings.beaconFrequencyHz,
                )
            }
    }

    private fun rememberCloneTemplateTimedEventFieldsFrom(sourceSnapshot: DeviceSnapshot?) {
        if (sourceSnapshot == null || cloneTemplateTimedEventEditsLocked) {
            return
        }
        val existingTemplate = cloneTemplateSettings ?: return
        val sourceSettings = sourceSnapshot.settings
        cloneTemplateSettings =
            existingTemplate.copy(
                stationId = sourceSettings.stationId,
                eventType = sourceSettings.eventType,
                idCodeSpeedWpm = sourceSettings.idCodeSpeedWpm,
                patternCodeSpeedWpm = sourceSettings.patternCodeSpeedWpm,
                currentTimeCompact = sourceSettings.currentTimeCompact,
                startTimeCompact = sourceSettings.startTimeCompact,
                finishTimeCompact = sourceSettings.finishTimeCompact,
                daysToRun = sourceSettings.daysToRun,
                lowFrequencyHz = sourceSettings.lowFrequencyHz,
                mediumFrequencyHz = sourceSettings.mediumFrequencyHz,
                highFrequencyHz = sourceSettings.highFrequencyHz,
                beaconFrequencyHz = sourceSettings.beaconFrequencyHz,
            )
        cloneTemplateDaysRemaining = sourceSnapshot.status.daysRemaining
    }

    private fun buildCloneEditableSettings(
        targetBaseSettings: DeviceSettings,
        templateSettings: DeviceSettings,
    ): EditableDeviceSettings {
        return EditableDeviceSettings.fromDeviceSettings(targetBaseSettings).copy(
            stationId = SettingsField("stationId", "Station ID", targetBaseSettings.stationId, templateSettings.stationId),
            eventType = SettingsField("eventType", "Event Type", targetBaseSettings.eventType, templateSettings.eventType),
            idCodeSpeedWpm = SettingsField("idCodeSpeedWpm", "ID Speed", targetBaseSettings.idCodeSpeedWpm, templateSettings.idCodeSpeedWpm),
            startTimeCompact = SettingsField("startTimeCompact", "Start Time", targetBaseSettings.startTimeCompact, templateSettings.startTimeCompact),
            finishTimeCompact = SettingsField("finishTimeCompact", "Finish Time", targetBaseSettings.finishTimeCompact, templateSettings.finishTimeCompact),
            daysToRun = SettingsField("daysToRun", "Days To Run", targetBaseSettings.daysToRun, templateSettings.daysToRun),
            patternCodeSpeedWpm = SettingsField(
                "patternCodeSpeedWpm",
                "Pattern Speed",
                targetBaseSettings.patternCodeSpeedWpm,
                templateSettings.patternCodeSpeedWpm,
            ),
            lowFrequencyHz = SettingsField("lowFrequencyHz", "Frequency 1 (FRE 1)", targetBaseSettings.lowFrequencyHz, templateSettings.lowFrequencyHz),
            mediumFrequencyHz = SettingsField("mediumFrequencyHz", "Frequency 2 (FRE 2)", targetBaseSettings.mediumFrequencyHz, templateSettings.mediumFrequencyHz),
            highFrequencyHz = SettingsField("highFrequencyHz", "Frequency 3 (FRE 3)", targetBaseSettings.highFrequencyHz, templateSettings.highFrequencyHz),
            beaconFrequencyHz = SettingsField("beaconFrequencyHz", "Frequency B (FRE B)", targetBaseSettings.beaconFrequencyHz, templateSettings.beaconFrequencyHz),
        )
    }

    private fun noOpSubmitResult(state: DeviceSessionState): DeviceSubmitResult {
        val snapshot = requireNotNull(state.snapshot) {
            "A loaded SignalSlinger snapshot is required for a no-op submit."
        }
        return DeviceSubmitResult(
            state = state.copy(
                editableSettings = com.openardf.serialslinger.model.EditableDeviceSettings.fromDeviceSettings(snapshot.settings),
                pendingSubmitCommands = emptyList(),
            ),
            commandsSent = emptyList(),
            linesReceived = emptyList(),
            readbackCommandsSent = emptyList(),
            readbackLinesReceived = emptyList(),
            verifications = emptyList(),
            submitTraceEntries = emptyList(),
            readbackTraceEntries = emptyList(),
        )
    }

    private fun runRelativeScheduleSubmit(
        context: Context,
        commands: List<String>,
        primaryField: SettingKey,
        statusPrefix: String,
        requestedDeviceName: String? = null,
        source: String = "ui",
        onComplete: ((Result<DeviceLoadResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val snapshot = sessionState?.snapshot
        val commandLogLabel =
            commands.joinToString(separator = "_") { command ->
                command.lowercase().replace(' ', '-')
            }
        if (sessionState == null || snapshot == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before submitting changes.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "$statusPrefix failed."
                statusIsError = true
            }
            emitCommandLog(commandLogLabel, source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        if (!snapshot.capabilities.supportsScheduling) {
            val error = IllegalStateException("$statusPrefix is not supported by the loaded snapshot.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "$statusPrefix failed."
                statusIsError = true
            }
            emitCommandLog(commandLogLabel, source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            latestSubmitSummary = "Submitting $statusPrefix..."
            statusText = "Submitting $statusPrefix..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-relative-schedule-submit") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = null,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val sentAtMs = System.currentTimeMillis()
                transport.sendCommands(commands)
                val responseLines = transport.readAvailableLines()
                val receivedAtMs = System.currentTimeMillis()
                val deviceError = responseLines.firstOrNull { it.contains("* Err:") }
                if (deviceError != null) {
                    throw IllegalStateException(deviceError.removePrefix("* ").trim())
                }
                var nextState = sessionState
                if (responseLines.isNotEmpty()) {
                    nextState = DeviceSessionWorkflow.ingestReportLines(nextState, responseLines)
                }
                val reloadResult = DeviceSessionController.refreshFromDevice(nextState, transport, startEditing = false)
                val refreshedState =
                    reloadResult.state.copy(
                        editableSettings = reloadResult.state.snapshot?.let { snapshot ->
                            EditableDeviceSettings.fromDeviceSettings(snapshot.settings)
                        },
                    )
                RelativeScheduleSubmitResult(
                    state = refreshedState,
                    commandsSent = commands,
                    responseLines = responseLines,
                    reloadResult = reloadResult,
                    traceEntries =
                        buildList {
                            addAll(commands.map { command -> SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, command) })
                            addAll(responseLines.map { line -> SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line) })
                            addAll(reloadResult.traceEntries)
                        },
                )
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val scheduleImpact = summarizeScheduleImpact(snapshot.settings, submitResult.state.snapshot?.settings, primaryField)
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.traceEntries,
                    )
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    rememberCloneTemplateTimedEventFieldsFrom(submitResult.state.snapshot)
                    applySnapshotDrafts(submitResult.state.snapshot, refreshClockDisplayAnchor = false)
                    timeWorkflowNotice = scheduleImpact.noticeText
                    clearScheduleDerivedDataPendingLocked()
                    latestSubmitSummary = renderRelativeScheduleSubmitSummary(statusPrefix, submitResult, scheduleImpact.summaryLines)
                    latestProbeSummary = "Latest load remains available above. $statusPrefix completed."
                    statusText = scheduleImpact.statusText ?: "$statusPrefix updated and reloaded."
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "$statusPrefix failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                }
            }

            emitCommandLog(
                command = commandLogLabel,
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post {
                    callback(result.fold(onSuccess = { Result.success(it.reloadResult) }, onFailure = { Result.failure(it) }))
                }
            }
        }
    }

    fun runRawCommand(
        context: Context,
        commandInput: String,
        requestedDeviceName: String? = null,
        requestedTarget: AndroidConnectionTarget? = null,
        source: String = "ui",
        onComplete: ((Result<List<String>>) -> Unit)? = null,
    ) {
        val command = commandInput.trim()
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        if (command.isBlank()) {
            val error = IllegalArgumentException("Enter a command to send.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Raw command failed."
                statusIsError = true
            }
            emitCommandLog("raw-command", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }
        if (sessionState == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before sending a command.")
            synchronized(this) {
                latestSubmitSummary = "Submit failed.\n${error.message}"
                statusText = "Raw command failed."
                statusIsError = true
            }
            emitCommandLog("raw-command", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post { callback(Result.failure(error)) }
            }
            return
        }

        synchronized(this) {
            latestSubmitSummary = "Sending raw command..."
            statusText = "Sending raw command..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-raw-command") {
            var resolvedTarget: AndroidConnectionTarget? = null
            val result = runWithResolvedTransport(
                context = context,
                requestedDeviceName = requestedDeviceName,
                requestedTarget = requestedTarget,
                allowUsbAutoDetect = false,
                missingMessage = "SignalSlinger is no longer connected.",
            ) { target, transport ->
                resolvedTarget = target
                val sentAtMs = System.currentTimeMillis()
                transport.sendCommands(listOf(command))
                val responseLines = transport.readAvailableLines()
                val receivedAtMs = System.currentTimeMillis()
                val deviceError = responseLines.firstOrNull { it.contains("* Err:") }
                if (deviceError != null) {
                    throw IllegalStateException(deviceError.removePrefix("* ").trim())
                }
                val nextState =
                    if (responseLines.isNotEmpty()) {
                        DeviceSessionWorkflow.ingestReportLines(sessionState, responseLines)
                    } else {
                        sessionState
                    }
                RawCommandResult(
                    state = nextState,
                    responseLines = responseLines,
                    traceEntries =
                        buildList {
                            add(SerialTraceEntry(sentAtMs, SerialTraceDirection.TX, command))
                            addAll(responseLines.map { line -> SerialTraceEntry(receivedAtMs, SerialTraceDirection.RX, line) })
                        },
                )
            }

            synchronized(this) {
                if (result.isSuccess) {
                    val commandResult = result.getOrThrow()
                    val normalizedState =
                        commandResult.state.copy(
                            editableSettings =
                                commandResult.state.snapshot?.let { snapshot ->
                                    EditableDeviceSettings.fromDeviceSettings(snapshot.settings)
                                } ?: commandResult.state.editableSettings,
                        )
                    latestSessionViewState = AndroidSessionViewState(state = normalizedState, traceEntries = commandResult.traceEntries)
                    resolvedTarget?.let(::rememberLoadedTargetLocked)
                    rememberCloneTemplateTimedEventFieldsFrom(normalizedState.snapshot)
                    applySnapshotDrafts(normalizedState.snapshot, refreshClockDisplayAnchor = false)
                    latestSubmitSummary =
                        buildString {
                            appendLine("Sent raw command.")
                            appendLine("TX $command")
                            commandResult.responseLines.forEach { line ->
                                appendLine("RX $line")
                            }
                        }.trim()
                    latestProbeSummary = "Latest load remains available above. Raw command completed."
                    statusText = "Sent raw command."
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Raw command failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "raw-command",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
                traceEntries = result.getOrNull()?.traceEntries.orEmpty(),
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post {
                    callback(result.fold(onSuccess = { Result.success(it.responseLines) }, onFailure = { Result.failure(it) }))
                }
            }
        }
    }

    private fun effectivePatternText(
        eventType: EventType,
        foxRole: FoxRole?,
        storedPatternText: String?,
    ): String? {
        return EventProfileSupport.displayPatternText(eventType, foxRole, storedPatternText).ifBlank { null }
    }

    private fun parseEventTypeInput(raw: String): EventType? {
        return EventProfileSupport.parseEventTypeOrNull(raw)
    }

    private fun parseFoxRoleInput(raw: String, eventType: EventType): FoxRole? {
        return EventProfileSupport.parseFoxRoleOrNull(raw, eventType)
    }

    private fun foxRoleOptions(eventType: EventType): List<FoxRole> = EventProfileSupport.foxRoleOptions(eventType)

    private fun emitCommandLog(
        command: String,
        source: String,
        success: Boolean,
        summary: String,
        traceEntries: List<SerialTraceEntry> = emptyList(),
        warnOnMissingTimelyReplies: Boolean = true,
    ) {
        val normalizedSummary = summary.replace('\n', ' ').trim()
        Log.i(
            logTag,
            "source=$source command=$command success=$success summary=$normalizedSummary",
        )
        val persistedTraceEntries =
            when {
                traceEntries.isNotEmpty() -> traceEntries
                success -> snapshotUiState().sessionViewState?.traceEntries.orEmpty()
                else -> emptyList()
            }
        if (warnOnMissingTimelyReplies) {
            recordTimelyReplyWarningIfNeeded(persistedTraceEntries)
        }
        synchronized(this) {
            sessionLog
        }?.appendCommandSection(
            command = command,
            source = source,
            success = success,
            summary = summary,
            traceEntries = persistedTraceEntries,
        )
    }

    private fun recordTimelyReplyWarningIfNeeded(traceEntries: List<SerialTraceEntry>) {
        val missedCommands = commandsWithoutTimelyReplies(traceEntries)
        if (missedCommands.isEmpty()) {
            return
        }
        val commandSummary = missedCommands.take(6).joinToString(", ")
        val suffix =
            if (missedCommands.size > 6) {
                ", and ${missedCommands.size - 6} more"
            } else {
                ""
            }
        synchronized(this) {
            pendingTimelyReplyWarning =
                "The attached SignalSlinger did not provide timely replies to: $commandSummary$suffix.\n\n" +
                    "Check that the SignalSlinger is awake, powered, and firmly connected. If this repeats, reload the device data before making changes."
        }
    }

    private fun commandsWithoutTimelyReplies(traceEntries: List<SerialTraceEntry>): List<String> {
        val missedCommands = linkedSetOf<String>()
        var activeCommand: String? = null
        var activeCommandHasReply = false

        fun closeActiveCommand() {
            val command = activeCommand
            if (command != null && !activeCommandHasReply && expectsTimelyReply(command)) {
                missedCommands += command
            }
        }

        traceEntries.forEach { entry ->
            when (entry.direction) {
                SerialTraceDirection.TX -> {
                    closeActiveCommand()
                    activeCommand = entry.payload
                    activeCommandHasReply = false
                }
                SerialTraceDirection.RX -> {
                    if (activeCommand != null && entry.payload.isNotBlank()) {
                        activeCommandHasReply = true
                    }
                }
            }
        }
        closeActiveCommand()
        return missedCommands.toList()
    }

    private fun expectsTimelyReply(command: String): Boolean {
        return !command.equals("RST", ignoreCase = true) &&
            !command.equals("MAS P", ignoreCase = true)
    }

    private fun notifyListeners() {
        val snapshotListeners =
            synchronized(this) {
                listeners.toList()
            }
        if (snapshotListeners.isEmpty()) {
            return
        }
        mainHandler.post {
            snapshotListeners.forEach { listener -> listener() }
        }
    }

    private fun DeviceSubmitResult.wasNoOp(): Boolean {
        return commandsSent.isEmpty() &&
            readbackCommandsSent.isEmpty() &&
            verifications.isEmpty()
    }

    private fun DeviceSubmitResult.submitVerificationFailure(): Boolean {
        return !wasNoOp() && verifications.any { !it.verified }
    }

    private fun DeviceSubmitResult.submitVerificationFailureMessage(): String {
        return if (readbackLinesReceived.isEmpty()) {
            "SignalSlinger did not return readback data, so the change could not be verified."
        } else {
            "SignalSlinger readback did not match the requested change."
        }
    }
}
