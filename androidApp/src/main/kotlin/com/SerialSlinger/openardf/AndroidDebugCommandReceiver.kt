@file:Suppress("PackageName")
package com.SerialSlinger.openardf

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import com.openardf.serialslinger.model.FrequencyBankId

class AndroidDebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidSessionController.initialize(context)
        if (!isDebuggable(context)) {
            resultCode = resultCanceled
            resultData = "Debug automation is disabled in non-debug builds."
            return
        }

        when (intent.action) {
            ACTION_GET_STATE -> {
                resultCode = resultOk
                resultData = AndroidSessionController.debugStateSummary()
            }
            ACTION_GET_SNAPSHOT -> {
                resultCode = resultOk
                resultData = AndroidSessionController.debugSnapshotSummary()
            }
            ACTION_GET_TRACE -> {
                resultCode = resultOk
                resultData = AndroidSessionController.debugTraceSummary()
            }
            ACTION_GET_LOG -> {
                resultCode = resultOk
                resultData = AndroidSessionController.debugSessionLogSummary()
            }
            ACTION_CLEAR_LOG -> {
                resultCode = resultOk
                resultData = AndroidSessionController.clearSessionLogs()
            }
            ACTION_LOAD -> {
                val pendingResult = goAsync()
                AndroidSessionController.runProbe(
                    context = context,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown load failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_LOAD_EMULATOR -> {
                val pendingResult = goAsync()
                AndroidSessionController.runProbe(
                    context = context,
                    requestedTargets = emulatorDirectSerialTargets(),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown load failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_EVENT_TYPE -> {
                val eventType = intent.getStringExtra(EXTRA_EVENT_TYPE)
                if (eventType == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: event_type"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runEventTypeSubmit(
                    context = context,
                    eventTypeInput = eventType,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_FOX_ROLE -> {
                val foxRole = intent.getStringExtra(EXTRA_FOX_ROLE)
                if (foxRole == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: fox_role"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runFoxRoleSubmit(
                    context = context,
                    foxRoleInput = foxRole,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_STATION_ID -> {
                val stationId = intent.getStringExtra(EXTRA_STATION_ID)
                if (stationId == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: station_id"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runStationIdSubmit(
                    context = context,
                    stationId = stationId,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_ID_SPEED -> {
                val idSpeedWpm = intent.getStringExtra(EXTRA_ID_SPEED_WPM)
                if (idSpeedWpm == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: id_speed_wpm"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runIdSpeedSubmit(
                    context = context,
                    idSpeedWpmText = idSpeedWpm,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_PATTERN_SPEED -> {
                val patternSpeedWpm = intent.getStringExtra(EXTRA_PATTERN_SPEED_WPM)
                if (patternSpeedWpm == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: pattern_speed_wpm"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runPatternSpeedSubmit(
                    context = context,
                    patternSpeedWpmText = patternSpeedWpm,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_CURRENT_FREQUENCY -> {
                val frequency = intent.getStringExtra(EXTRA_CURRENT_FREQUENCY)
                if (frequency == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: current_frequency"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runCurrentFrequencySubmit(
                    context = context,
                    frequencyInput = frequency,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_FREQUENCY_BANK -> {
                val bankIdValue = intent.getStringExtra(EXTRA_BANK_ID)
                val frequency = intent.getStringExtra(EXTRA_BANK_FREQUENCY)
                if (bankIdValue == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: bank_id"
                    return
                }
                if (frequency == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: bank_frequency"
                    return
                }

                val bankId =
                    when (bankIdValue.uppercase()) {
                        "1", "ONE" -> FrequencyBankId.ONE
                        "2", "TWO" -> FrequencyBankId.TWO
                        "3", "THREE" -> FrequencyBankId.THREE
                        "B", "BEACON" -> FrequencyBankId.BEACON
                        else -> null
                    }
                if (bankId == null) {
                    resultCode = resultCanceled
                    resultData = "Unsupported bank_id: $bankIdValue"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runFrequencyBankSubmit(
                    context = context,
                    bankId = bankId,
                    frequencyInput = frequency,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_PATTERN_TEXT -> {
                val patternText = intent.getStringExtra(EXTRA_PATTERN_TEXT)
                if (patternText == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: pattern_text"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runPatternTextSubmit(
                    context = context,
                    patternTextInput = patternText,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_CURRENT_TIME -> {
                val currentTime = intent.getStringExtra(EXTRA_CURRENT_TIME)
                if (currentTime == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: current_time"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runCurrentTimeSubmit(
                    context = context,
                    currentTimeInput = currentTime,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_START_TIME -> {
                val startTime = intent.getStringExtra(EXTRA_START_TIME)
                if (startTime == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: start_time"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runStartTimeSubmit(
                    context = context,
                    startTimeInput = startTime,
                    defaultEventLengthMinutes = 6 * 60,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_FINISH_TIME -> {
                val finishTime = intent.getStringExtra(EXTRA_FINISH_TIME)
                if (finishTime == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: finish_time"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runFinishTimeSubmit(
                    context = context,
                    finishTimeInput = finishTime,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_SET_DAYS_TO_RUN -> {
                val daysToRun = intent.getStringExtra(EXTRA_DAYS_TO_RUN)
                if (daysToRun == null) {
                    resultCode = resultCanceled
                    resultData = "Missing required extra: days_to_run"
                    return
                }

                val pendingResult = goAsync()
                AndroidSessionController.runDaysToRunSubmit(
                    context = context,
                    daysToRunText = daysToRun,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown submit failure"
                        }
                    pendingResult.finish()
                }
            }
            ACTION_CLONE -> {
                AndroidSessionController.runCloneTimedEventSettings(
                    context = context,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                )
                resultCode = resultOk
                resultData = "Clone started. Poll DEBUG_GET_LOG for completion."
            }
            ACTION_CLONE_WAIT -> {
                val pendingResult = goAsync()
                AndroidSessionController.runCloneTimedEventSettings(
                    context = context,
                    requestedDeviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                    source = "adb",
                ) { result ->
                    pendingResult.resultCode = if (result.isSuccess) resultOk else resultCanceled
                    pendingResult.resultData =
                        if (result.isSuccess) {
                            AndroidSessionController.debugStateSummary()
                        } else {
                            result.exceptionOrNull()?.message ?: "Unknown clone failure"
                        }
                    pendingResult.finish()
                }
            }
            else -> {
                resultCode = resultCanceled
                resultData = "Unsupported action: ${intent.action}"
            }
        }
    }

    private fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    companion object {
        private const val resultOk = 1
        private const val resultCanceled = 0

        const val ACTION_GET_STATE = "com.SerialSlinger.openardf.DEBUG_GET_STATE"
        const val ACTION_GET_SNAPSHOT = "com.SerialSlinger.openardf.DEBUG_GET_SNAPSHOT"
        const val ACTION_GET_TRACE = "com.SerialSlinger.openardf.DEBUG_GET_TRACE"
        const val ACTION_GET_LOG = "com.SerialSlinger.openardf.DEBUG_GET_LOG"
        const val ACTION_CLEAR_LOG = "com.SerialSlinger.openardf.DEBUG_CLEAR_LOG"
        const val ACTION_LOAD = "com.SerialSlinger.openardf.DEBUG_LOAD"
        const val ACTION_LOAD_EMULATOR = "com.SerialSlinger.openardf.DEBUG_LOAD_EMULATOR"
        const val ACTION_SET_EVENT_TYPE = "com.SerialSlinger.openardf.DEBUG_SET_EVENT_TYPE"
        const val ACTION_SET_FOX_ROLE = "com.SerialSlinger.openardf.DEBUG_SET_FOX_ROLE"
        const val ACTION_SET_STATION_ID = "com.SerialSlinger.openardf.DEBUG_SET_STATION_ID"
        const val ACTION_SET_ID_SPEED = "com.SerialSlinger.openardf.DEBUG_SET_ID_SPEED"
        const val ACTION_SET_PATTERN_SPEED = "com.SerialSlinger.openardf.DEBUG_SET_PATTERN_SPEED"
        const val ACTION_SET_CURRENT_FREQUENCY = "com.SerialSlinger.openardf.DEBUG_SET_CURRENT_FREQUENCY"
        const val ACTION_SET_FREQUENCY_BANK = "com.SerialSlinger.openardf.DEBUG_SET_FREQUENCY_BANK"
        const val ACTION_SET_PATTERN_TEXT = "com.SerialSlinger.openardf.DEBUG_SET_PATTERN_TEXT"
        const val ACTION_SET_CURRENT_TIME = "com.SerialSlinger.openardf.DEBUG_SET_CURRENT_TIME"
        const val ACTION_SET_START_TIME = "com.SerialSlinger.openardf.DEBUG_SET_START_TIME"
        const val ACTION_SET_FINISH_TIME = "com.SerialSlinger.openardf.DEBUG_SET_FINISH_TIME"
        const val ACTION_SET_DAYS_TO_RUN = "com.SerialSlinger.openardf.DEBUG_SET_DAYS_TO_RUN"
        const val ACTION_CLONE = "com.SerialSlinger.openardf.DEBUG_CLONE"
        const val ACTION_CLONE_WAIT = "com.SerialSlinger.openardf.DEBUG_CLONE_WAIT"

        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_FOX_ROLE = "fox_role"
        const val EXTRA_STATION_ID = "station_id"
        const val EXTRA_ID_SPEED_WPM = "id_speed_wpm"
        const val EXTRA_PATTERN_SPEED_WPM = "pattern_speed_wpm"
        const val EXTRA_CURRENT_FREQUENCY = "current_frequency"
        const val EXTRA_BANK_ID = "bank_id"
        const val EXTRA_BANK_FREQUENCY = "bank_frequency"
        const val EXTRA_PATTERN_TEXT = "pattern_text"
        const val EXTRA_CURRENT_TIME = "current_time"
        const val EXTRA_START_TIME = "start_time"
        const val EXTRA_FINISH_TIME = "finish_time"
        const val EXTRA_DAYS_TO_RUN = "days_to_run"
    }
}
