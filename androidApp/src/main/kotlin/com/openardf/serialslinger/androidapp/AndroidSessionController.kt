package com.openardf.serialslinger.androidapp

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.SettingsField
import com.openardf.serialslinger.model.WritePlanner
import com.openardf.serialslinger.session.DeviceLoadResult
import com.openardf.serialslinger.session.DeviceSessionController
import com.openardf.serialslinger.session.DeviceSessionState
import com.openardf.serialslinger.session.DeviceSubmitResult
import com.openardf.serialslinger.session.DeviceSessionWorkflow
import com.openardf.serialslinger.session.SerialTraceDirection
import com.openardf.serialslinger.session.SerialTraceEntry
import com.openardf.serialslinger.transport.AndroidUsbTransport
import java.time.Duration
import java.time.LocalDateTime
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
    val latestLoadedDeviceName: String?,
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

data class CloneSubmitResult(
    val state: DeviceSessionState,
    val writeCommandCount: Int,
    val writeResponseLineCount: Int,
    val refreshCommandCount: Int,
    val refreshResponseLineCount: Int,
    val syncAttemptCount: Int,
    val syncSucceeded: Boolean,
    val traceEntries: List<SerialTraceEntry>,
)

private data class ClockDisplayAnchor(
    val currentTimeCompact: String?,
    val referenceTime: LocalDateTime?,
    val phaseErrorMillis: Long?,
)

object AndroidSessionController {
    private const val logTag = "SerialSlingerDebug"
    private const val syncLatencySampleCount = 3
    private const val syncMaxAttempts = 2
    private const val syncVerificationSampleMax = 8
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<() -> Unit>()
    private var applicationContext: Context? = null
    private var sessionLog: AndroidSessionLog? = null

    private var statusText: String = "Waiting for USB devices."
    private var statusIsError: Boolean = false
    private var latestProbeSummary: String = "No SignalSlinger probe has been run yet."
    private var latestSessionViewState: AndroidSessionViewState? = null
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
    private var deviceTimeOffset: Duration? = null
    private var lastClockPhaseErrorMillis: Long? = null
    private var cachedManualWriteDelayMillis: Long? = null
    private var cloneTemplateSettings: DeviceSettings? = null

    fun initialize(context: Context) {
        synchronized(this) {
            if (applicationContext == null) {
                applicationContext = context.applicationContext
            }
            if (sessionLog == null) {
                sessionLog = AndroidSessionLog(context.applicationContext.filesDir.resolve("logs"))
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
                latestLoadedDeviceName = latestLoadedDeviceName,
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
        source: String = "ui",
        onComplete: ((Result<CloneSubmitResult>) -> Unit)? = null,
    ) {
        val sessionState = synchronized(this) { latestSessionViewState?.state }
        val templateSettings = synchronized(this) { cloneTemplateSettings }
        if (sessionState == null || sessionState.snapshot == null || templateSettings == null) {
            val error = IllegalStateException("Load a SignalSlinger snapshot before using Clone.")
            synchronized(this) {
                latestSubmitSummary = "Clone failed.\n${error.message}"
                statusText = "Clone failed."
                statusIsError = true
            }
            emitCommandLog("clone", source, success = false, summary = error.message.orEmpty())
            notifyListeners()
            onComplete?.let { callback -> mainHandler.post { callback(Result.failure(error)) } }
            return
        }

        synchronized(this) {
            latestSubmitSummary = "Submitting Clone..."
            statusText = "Submitting Clone..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-clone-submit") {
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val targetRefresh = DeviceSessionController.refreshFromDevice(sessionState, transport, startEditing = true)
                        val targetSnapshot = requireNotNull(targetRefresh.state.snapshot)
                        val editable = buildCloneEditableSettings(targetSnapshot.settings, templateSettings)
                        val validated = editable.toValidatedDeviceSettings()
                        val writePlan = WritePlanner.create(targetSnapshot.settings, validated)
                        val submitResult = DeviceSessionController.submitEdits(targetRefresh.state, editable, transport)
                        val verificationFailures = submitResult.verifications.filter { !it.verified }
                        val refreshed = DeviceSessionController.refreshFromDevice(submitResult.state, transport, startEditing = true)
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
                        Result.success(
                            CloneSubmitResult(
                                state = finalState,
                                writeCommandCount = writePlan.changes.size,
                                writeResponseLineCount = submitResult.linesReceived.size,
                                refreshCommandCount = refreshed.commandsSent.size,
                                refreshResponseLineCount = refreshed.linesReceived.size,
                                syncAttemptCount = syncOperation?.attempts?.size ?: 0,
                                syncSucceeded = syncOperation?.succeeded ?: true,
                                traceEntries =
                                    buildList {
                                        addAll(targetRefresh.traceEntries)
                                        addAll(submitResult.submitTraceEntries)
                                        addAll(submitResult.readbackTraceEntries)
                                        addAll(refreshed.traceEntries)
                                        syncOperation?.let { addAll(buildSyncTraceEntries(it)) }
                                    },
                            ),
                        )
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
                    }
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val cloneResult = result.getOrThrow()
                    latestSessionViewState = AndroidSessionViewState(
                        state = cloneResult.state,
                        traceEntries = cloneResult.traceEntries,
                    )
                    latestLoadedDeviceName = usbDevice?.deviceName
                    cloneResult.state.snapshot?.settings?.let(::rememberCloneTemplateFrom)
                    applySnapshotDrafts(cloneResult.state.snapshot, refreshClockDisplayAnchor = cloneResult.syncAttemptCount == 0)
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

            emitCommandLog("clone", source, success = result.isSuccess, summary = synchronized(this) { latestSubmitSummary })
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
                result.getOrNull()?.state?.snapshot?.settings?.let(::rememberCloneTemplateFrom)
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
        if (probeInFlight || scheduleDerivedDataPending) {
            return false
        }
        val snapshot = latestSessionViewState?.state?.snapshot ?: return false
        val template = cloneTemplateSettings ?: return false
        if (!snapshot.capabilities.supportsScheduling) {
            return false
        }
        return hasRequiredCloneEventData(template) && hasRequiredCloneEventData(snapshot.settings)
    }

    private fun hasClockPhaseWarningLocked(): Boolean {
        return currentClockSkewMillisLocked()?.let { abs(it) > 500L } == true
    }

    private fun currentClockSkewMillisLocked(systemNow: LocalDateTime = LocalDateTime.now()): Long? {
        val exactMeasuredSkew = lastClockPhaseErrorMillis
        if (exactMeasuredSkew != null) {
            return exactMeasuredSkew
        }

        val displayedDeviceTime = displayedDeviceTimeCompactLocked(systemNow)
            ?.let(JvmTimeSupport::normalizeCurrentTimeCompactForDisplay)
            ?.let(JvmTimeSupport::parseCompactTimestamp)
            ?: return null
        return Duration.between(displayedDeviceTime, systemNow).toMillis()
    }

    private fun hasRequiredCloneEventData(settings: DeviceSettings): Boolean {
        if (settings.eventType == EventType.NONE) {
            return false
        }
        if (settings.stationId.isBlank()) {
            return false
        }
        if (settings.startTimeCompact == null || settings.finishTimeCompact == null) {
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
        source: String = "ui",
        onComplete: ((Result<DeviceLoadResult>) -> Unit)? = null,
    ) {
        val probeStart =
            synchronized(this) {
                if (probeInFlight) {
                    false to (requestedDeviceName ?: latestLoadedDeviceName)
                } else {
                    probeInFlight = true
                    true to (requestedDeviceName ?: latestLoadedDeviceName)
                }
            }
        val (startedProbe, deviceName) = probeStart
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, deviceName)
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("No permitted supported USB serial device is connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val initialLoad = DeviceSessionController.connectAndLoad(transport)
                        val postLoadClockSample = postLoadClockSample(transport, initialLoad)
                        Result.success(
                            mergeLoadResults(initialLoad, postLoadClockSample?.first) to postLoadClockSample?.second,
                        )
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
                    }
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val (loadResult, clockAnchor) = result.getOrThrow()
                    latestSessionViewState = AndroidSessionViewState(
                        state = loadResult.state,
                        traceEntries = loadResult.traceEntries,
                    )
                    latestLoadedDeviceName = usbDevice?.deviceName
                    if (cloneTemplateSettings == null) {
                        loadResult.state.snapshot?.settings?.let(::rememberCloneTemplateFrom)
                    }
                    applySnapshotDrafts(loadResult.state.snapshot)
                    clockAnchor?.let { anchor ->
                        applyClockDisplayAnchor(
                            currentTimeCompact = anchor.currentTimeCompact,
                            phaseErrorMillis = anchor.phaseErrorMillis,
                            referenceTime = anchor.referenceTime ?: LocalDateTime.now(),
                        )
                    }
                    timeWorkflowNotice = null
                    latestProbeSummary = renderProbeSummary(loadResult)
                    statusText = "SignalSlinger loaded."
                    statusIsError = false
                } else {
                    latestProbeSummary = buildString {
                        appendLine("Probe failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "SignalSlinger probe failed."
                    statusIsError = true
                }
                probeInFlight = false
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            stationId = editableSettings.stationId.copy(editedValue = stationId.trim()),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            idCodeSpeedWpm = editableSettings.idCodeSpeedWpm.copy(editedValue = parsedSpeed),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
            var resolvedDeviceName: String? = null
            val result =
                if (snapshot.settings.lowBatteryThresholdVolts == parsedThreshold) {
                    Result.success(noOpSubmitResult(sessionState))
                } else {
                    val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
                    val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
                    if (usbDevice == null) {
                        Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                    } else {
                        resolvedDeviceName = usbDevice.deviceName
                        val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                        try {
                            transport.connect()
                            val editedSettings = editableSettings.copy(
                                lowBatteryThresholdVolts = editableSettings.lowBatteryThresholdVolts.copy(editedValue = parsedThreshold),
                            )
                            Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                        } catch (error: Throwable) {
                            Result.failure(error)
                        } finally {
                            transport.disconnect()
                        }
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
                    latestLoadedDeviceName = resolvedDeviceName ?: latestLoadedDeviceName
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
            var resolvedDeviceName: String? = null
            val result =
                if (snapshot.settings.externalBatteryControlMode == mode) {
                    Result.success(noOpSubmitResult(sessionState))
                } else {
                    val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
                    val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
                    if (usbDevice == null) {
                        Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                    } else {
                        resolvedDeviceName = usbDevice.deviceName
                        val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                        try {
                            transport.connect()
                            val editedSettings = editableSettings.copy(
                                externalBatteryControlMode = editableSettings.externalBatteryControlMode.copy(editedValue = mode),
                            )
                            Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                        } catch (error: Throwable) {
                            Result.failure(error)
                        } finally {
                            transport.disconnect()
                        }
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
                    latestLoadedDeviceName = resolvedDeviceName ?: latestLoadedDeviceName
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            patternCodeSpeedWpm = editableSettings.patternCodeSpeedWpm.copy(editedValue = parsedSpeed),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            patternText = editableSettings.patternText.copy(editedValue = normalizedPatternText),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            defaultFrequencyHz = editableSettings.defaultFrequencyHz.copy(editedValue = parsedFrequencyHz),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
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
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
            latestSubmitSummary = "Submitting Fox Role update..."
            statusText = "Submitting Fox Role update..."
            statusIsError = false
        }
        notifyListeners()

        thread(name = "serialslinger-android-fox-role-submit") {
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            foxRole = editableSettings.foxRole.copy(editedValue = parsedFoxRole),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Fox Role update failed."
                    statusIsError = true
                }
            }

            emitCommandLog(
                command = "set-fox-role",
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            eventType = editableSettings.eventType.copy(editedValue = parsedEventType),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            eventType = editableSettings.eventType.copy(editedValue = parsedEventType),
                            foxRole = editableSettings.foxRole.copy(editedValue = parsedFoxRole),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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

        synchronized(this) {
            draftDaysToRun = parsedDaysToRun.toString()
            latestSubmitSummary = "Submitting Days To Run update..."
            statusText = "Submitting Days To Run update..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-days-to-run-submit") {
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val editedSettings = editableSettings.copy(
                            daysToRun = editableSettings.daysToRun.copy(editedValue = parsedDaysToRun),
                        )
                        Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
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
                    latestLoadedDeviceName = usbDevice?.deviceName
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
            latestSubmitSummary = "Synchronizing Device Time to Android system time..."
            statusText = "Synchronizing Device Time..."
            statusIsError = false
            markScheduleDerivedDataPendingLocked()
        }
        notifyListeners()

        thread(name = "serialslinger-android-current-time-sync") {
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        Result.success(
                            performAlignedTimeSync(
                                transport = transport,
                                state = sessionState,
                                snapshot = snapshot,
                            ),
                        )
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
                    }
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val syncResult = result.getOrThrow()
                    val finalAttempt = syncResult.finalAttempt
                    latestSessionViewState = AndroidSessionViewState(
                        state = finalAttempt.state,
                        traceEntries = buildSyncTraceEntries(syncResult),
                    )
                    applySnapshotDrafts(finalAttempt.state.snapshot)
                    applyClockDisplayAnchor(
                        currentTimeCompact = finalAttempt.state.snapshot?.settings?.currentTimeCompact,
                        phaseErrorMillis = finalAttempt.phaseErrorMillis,
                    )
                    timeWorkflowNotice = null
                    clearScheduleDerivedDataPendingLocked()
                    latestSubmitSummary =
                        buildString {
                            appendLine("Device Time synchronized to Android system time.")
                            finalAttempt.phaseErrorMillis?.let { phase ->
                                appendLine("Measured phase error: ${JvmTimeSupport.formatSignedDurationMillis(phase)}")
                            }
                            append("Attempts: ${syncResult.attempts.size}")
                        }.trim()
                    latestProbeSummary = "Latest load remains available above. Device Time sync completed."
                    statusText =
                        finalAttempt.phaseErrorMillis?.let { phase ->
                            "Device Time synchronized (${JvmTimeSupport.formatSignedDurationMillis(phase)})."
                        } ?: "Device Time synchronized."
                    statusIsError = false
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Device Time sync failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                }
            }

            emitCommandLog(
                command = "sync-current-time",
                source = source,
                success = result.isSuccess,
                summary = synchronized(this) { latestSubmitSummary },
            )
            notifyListeners()
            onComplete?.let { callback ->
                mainHandler.post {
                    callback(
                        result.fold(
                            onSuccess = { syncResult -> Result.success(syncResult.finalAttempt.submitResult) },
                            onFailure = { error -> Result.failure(error) },
                        ),
                    )
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
            val result =
                run {
                    val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
                    val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
                    if (usbDevice == null) {
                        Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                    } else {
                        val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                        try {
                            transport.connect()
                            val estimatedWriteDelayMillis = synchronized(this@AndroidSessionController) { cachedManualWriteDelayMillis } ?: 0L
                            val adjustedCurrentTime =
                                JvmTimeSupport.formatCompactTimestamp(
                                    JvmTimeSupport.adjustManualTimeTargetForWrite(
                                        selectedTime = JvmTimeSupport.parseCompactTimestamp(normalizedCurrentTime),
                                        estimatedWriteDelayMillis = estimatedWriteDelayMillis,
                                    ),
                                )
                            if (adjustedCurrentTime == snapshot.settings.currentTimeCompact) {
                                Result.success(noOpSubmitResult(sessionState))
                            } else {
                                val editedSettings = editableSettings.copy(
                                    currentTimeCompact = editableSettings.currentTimeCompact.copy(editedValue = adjustedCurrentTime),
                                )
                                Result.success(DeviceSessionController.submitEdits(sessionState, editedSettings, transport))
                            }
                        } catch (error: Throwable) {
                            Result.failure(error)
                        } finally {
                            transport.disconnect()
                        }
                    }
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    val scheduleImpact = summarizeScheduleImpact(snapshot.settings, submitResult.state.snapshot?.settings, SettingKey.CURRENT_TIME)
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    applySnapshotDrafts(submitResult.state.snapshot)
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
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Device Time update failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                }
            }

            emitCommandLog(
                command = "set-current-time",
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
            commands = buildList {
                add("CLK S $offsetCommand")
                add("CLK F $finishOffsetCommand")
                preservedDaysToRun?.let { add("CLK D $it") }
            },
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
            commands = listOf("CLK S ="),
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
            commands = buildList {
                add("CLK F $offsetCommand")
                preservedDaysToRun?.let { add("CLK D $it") }
            },
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
            val result =
                try {
                    val resolvedStartTime = JvmTimeSupport.resolveStartTimeForChange(
                        startTimeCompact = normalizedStartTime,
                        currentTimeCompact = snapshot.settings.currentTimeCompact,
                    )
                    val resolvedFinishTime =
                        resolvedStartTime?.let { startTimeCompact ->
                            requestedFinishTimeInput
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { requestedFinishInput ->
                                    val normalizedFinishTime =
                                        JvmTimeSupport.parseOptionalCompactTimestamp(requestedFinishInput)
                                            ?: error("Finish Time must not be blank.")
                                    JvmTimeSupport.resolveScheduleForFinishTimeChange(
                                        startTimeCompact = startTimeCompact,
                                        finishTimeCompact = normalizedFinishTime,
                                        currentTimeCompact = snapshot.settings.currentTimeCompact,
                                    ).finishTimeCompact
                                }
                                ?: JvmTimeSupport.finishTimeCompactFromStart(
                                    startTimeCompact = startTimeCompact,
                                    duration = Duration.ofMinutes(validateDefaultEventLengthMinutes(defaultEventLengthMinutes).toLong()),
                                )
                        }

                    if (
                        resolvedStartTime == snapshot.settings.startTimeCompact &&
                        resolvedFinishTime == snapshot.settings.finishTimeCompact
                    ) {
                        Result.success(noOpSubmitResult(sessionState))
                    } else {
                        val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
                        val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
                        if (usbDevice == null) {
                            Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                        } else {
                            val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                            try {
                                transport.connect()
                                val editedSettings = editableSettings.copy(
                                    startTimeCompact = editableSettings.startTimeCompact.copy(editedValue = resolvedStartTime),
                                    finishTimeCompact = editableSettings.finishTimeCompact.copy(editedValue = resolvedFinishTime),
                                )
                                Result.success(
                                    DeviceSessionController.submitEdits(
                                        sessionState,
                                        editedSettings,
                                        transport,
                                        forceWriteKeys = if (preserveDaysToRun) setOf(SettingKey.DAYS_TO_RUN) else emptySet(),
                                    ),
                                )
                            } catch (error: Throwable) {
                                Result.failure(error)
                            } finally {
                                transport.disconnect()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    Result.failure(error)
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    val scheduleImpact = summarizeScheduleImpact(snapshot.settings, submitResult.state.snapshot?.settings, SettingKey.START_TIME)
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    applySnapshotDrafts(submitResult.state.snapshot)
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
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Start Time update failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                }
            }

            emitCommandLog(
                command = "set-start-time",
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
            val result =
                if (normalizedFinishTime == snapshot.settings.finishTimeCompact) {
                    Result.success(noOpSubmitResult(sessionState))
                } else {
                    val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
                    val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
                    if (usbDevice == null) {
                        Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                    } else {
                        val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                        try {
                            transport.connect()
                            val resolvedSchedule = JvmTimeSupport.resolveScheduleForFinishTimeChange(
                                startTimeCompact = snapshot.settings.startTimeCompact,
                                finishTimeCompact = normalizedFinishTime,
                                currentTimeCompact = snapshot.settings.currentTimeCompact,
                            )
                            val editedSettings =
                                editableSettings.copy(
                                    startTimeCompact = editableSettings.startTimeCompact.copy(
                                        editedValue = resolvedSchedule.startTimeCompact,
                                    ),
                                    finishTimeCompact = editableSettings.finishTimeCompact.copy(
                                        editedValue = resolvedSchedule.finishTimeCompact,
                                    ),
                                )
                            Result.success(
                                DeviceSessionController.submitEdits(
                                    sessionState,
                                    editedSettings,
                                    transport,
                                    forceWriteKeys = if (preserveDaysToRun) setOf(SettingKey.DAYS_TO_RUN) else emptySet(),
                                ),
                            )
                        } catch (error: Throwable) {
                            Result.failure(error)
                        } finally {
                            transport.disconnect()
                        }
                    }
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val wasNoOp = submitResult.wasNoOp()
                    val scheduleImpact = summarizeScheduleImpact(snapshot.settings, submitResult.state.snapshot?.settings, SettingKey.FINISH_TIME)
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.submitTraceEntries + submitResult.readbackTraceEntries,
                    )
                    applySnapshotDrafts(submitResult.state.snapshot)
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
                } else {
                    latestSubmitSummary = buildString {
                        appendLine("Submit failed.")
                        append(result.exceptionOrNull()?.message ?: "Unknown error")
                    }.trim()
                    statusText = "Finish Time update failed."
                    statusIsError = true
                    clearScheduleDerivedDataPendingLocked()
                }
            }

            emitCommandLog(
                command = "set-finish-time",
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

    fun debugStateSummary(): String {
        val uiState = snapshotUiState()
        val snapshot = uiState.sessionViewState?.state?.snapshot
        val currentLogFile = synchronized(this) { sessionLog?.currentLogFile()?.absolutePath }
        return buildString {
            appendLine("status=${uiState.statusText}")
            appendLine("statusIsError=${uiState.statusIsError}")
            appendLine("loadedDevice=${uiState.latestLoadedDeviceName ?: "<none>"}")
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
            appendLine("maximumTemperatureC=${snapshot.status.maximumTemperatureC ?: "<unknown>"}")
            appendLine("temperatureC=${snapshot.status.temperatureC ?: "<unknown>"}")
            appendLine("minimumTemperatureC=${snapshot.status.minimumTemperatureC ?: "<unknown>"}")
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
        synchronized(this) {
            sessionLog
        }?.appendSection(
            title = title,
            entries = trimmedLines.map { line ->
                AndroidLogEntry(
                    message = line,
                    category = AndroidLogCategory.APP,
                )
            },
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
        transport: AndroidUsbTransport,
        result: DeviceLoadResult,
    ): Pair<DeviceLoadResult, ClockDisplayAnchor>? {
        val loadedSnapshot = result.state.snapshot ?: return null
        if (!loadedSnapshot.capabilities.supportsScheduling) {
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
        val phaseErrorMillis =
            JvmTimeSupport.estimateClockPhaseErrorMillis(
                samples.map { sample ->
                    ClockPhaseSample(
                        midpointAt = sample.midpointAt,
                        reportedTimeCompact = sample.reportedTimeCompact,
                    )
                },
            )
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
        transport: AndroidUsbTransport,
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

    private fun performAlignedTimeSync(
        transport: AndroidUsbTransport,
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
            if (abs(phaseErrorMillis) <= 500L) {
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
            succeeded = finalAttempt.phaseErrorMillis?.let { abs(it) <= 500L } ?: true,
        )
    }

    private fun performSyncAttempt(
        transport: AndroidUsbTransport,
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
        transport: AndroidUsbTransport,
        maxSamples: Int = syncVerificationSampleMax,
    ): List<ClockReadSample> {
        val samples = mutableListOf<ClockReadSample>()
        repeat(maxSamples) { index ->
            val sample = readClockSample(transport)
            samples += sample
            val previousReported = samples.getOrNull(samples.lastIndex - 1)?.reportedTimeCompact
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
        transport: AndroidUsbTransport,
        command: String = "CLK T",
    ): ClockReadSample {
        val sentAt = LocalDateTime.now()
        transport.sendCommands(listOf(command))
        val responseLines = transport.readAvailableLines()
        val receivedAt = LocalDateTime.now()
        return ClockReadSample(
            sentAt = sentAt,
            receivedAt = receivedAt,
            responseLines = responseLines,
            reportedTimeCompact =
                responseLines
                    .mapNotNull { line -> com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec.parseReportLine(line)?.settingsPatch?.currentTimeCompact }
                    .firstOrNull(),
            command = command,
        )
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
        return buildString {
            appendLine("Probe succeeded.")
            appendLine("Commands sent: ${result.commandsSent.size}")
            appendLine("Response lines: ${result.linesReceived.size}")
            appendLine("Software version: ${snapshot?.info?.softwareVersion ?: "<unknown>"}")
            appendLine("Product name: ${snapshot?.info?.productName ?: "<unknown>"}")
            appendLine("Station ID: ${snapshot?.settings?.stationId ?: "<unknown>"}")
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
            appendLine("Clone submitted.")
            appendLine("Write commands sent: ${result.writeCommandCount}")
            appendLine("Write response lines: ${result.writeResponseLineCount}")
            appendLine("Reload commands sent: ${result.refreshCommandCount}")
            appendLine("Reload response lines: ${result.refreshResponseLineCount}")
            appendLine("Sync attempts: ${result.syncAttemptCount}")
            append("Time sync status: ${if (result.syncSucceeded) "ok" else "attention needed"}")
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

    private fun rememberCloneTemplateFrom(sourceSettings: DeviceSettings) {
        val existingTemplate = cloneTemplateSettings
        cloneTemplateSettings =
            if (existingTemplate == null) {
                sourceSettings
            } else {
                existingTemplate.copy(
                    stationId = sourceSettings.stationId,
                    eventType = sourceSettings.eventType,
                    idCodeSpeedWpm = sourceSettings.idCodeSpeedWpm,
                    patternCodeSpeedWpm = sourceSettings.patternCodeSpeedWpm,
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
            val usbManager = context.applicationContext.getSystemService(UsbManager::class.java)
            val usbDevice = resolveUsbDevice(usbManager, requestedDeviceName ?: synchronized(this) { latestLoadedDeviceName })
            val result =
                if (usbDevice == null) {
                    Result.failure(IllegalStateException("SignalSlinger is no longer connected."))
                } else {
                    val transport = AndroidUsbTransport(usbManager = usbManager, usbDevice = usbDevice)
                    try {
                        transport.connect()
                        val sentAtMs = System.currentTimeMillis()
                        transport.sendCommands(commands)
                        val responseLines = transport.readAvailableLines()
                        val receivedAtMs = System.currentTimeMillis()
                        val deviceError = responseLines.firstOrNull { it.contains("* Err:") }
                        if (deviceError != null) {
                            Result.failure(IllegalStateException(deviceError.removePrefix("* ").trim()))
                        } else {
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
                            Result.success(
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
                                ),
                            )
                        }
                    } catch (error: Throwable) {
                        Result.failure(error)
                    } finally {
                        transport.disconnect()
                    }
                }

            synchronized(this) {
                if (result.isSuccess) {
                    val submitResult = result.getOrThrow()
                    val scheduleImpact = summarizeScheduleImpact(snapshot.settings, submitResult.state.snapshot?.settings, primaryField)
                    latestSessionViewState = AndroidSessionViewState(
                        state = submitResult.state,
                        traceEntries = submitResult.traceEntries,
                    )
                    latestLoadedDeviceName = usbDevice?.deviceName
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
}
