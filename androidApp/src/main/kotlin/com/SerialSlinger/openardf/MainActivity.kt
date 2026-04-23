@file:Suppress("PackageName")
package com.SerialSlinger.openardf

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.SoundEffectConstants
import android.view.MotionEvent
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.URLSpan
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.Window
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.util.TypedValue
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.EventProfileSupport
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FrequencyBankId
import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.JvmTimeSupport
import com.openardf.serialslinger.model.MultiDayDurationGuardChoice
import com.openardf.serialslinger.model.MultiDayDurationGuardOption
import com.openardf.serialslinger.model.ScheduleDurationGuardSupport
import com.openardf.serialslinger.model.RelativeScheduleSelection
import com.openardf.serialslinger.model.RelativeScheduleSupport
import com.openardf.serialslinger.model.StartTimeAdjustmentOption
import com.openardf.serialslinger.model.StartTimeAdjustmentOptionKind
import com.openardf.serialslinger.model.StartTimeAdjustmentPlanner
import com.openardf.serialslinger.model.StartTimeDaysToRunChoice
import com.openardf.serialslinger.model.StartTimeDaysToRunPlanner
import com.openardf.serialslinger.model.TemperatureAlertLevel
import com.openardf.serialslinger.model.TemperatureAlertSupport
import com.openardf.serialslinger.transport.AndroidUsbDeviceDescriptor
import com.openardf.serialslinger.transport.AndroidUsbTransport
import com.openardf.serialslinger.transport.SignalSlingerReadPlan
import java.time.Duration
import java.time.LocalDateTime
import java.util.WeakHashMap

@SuppressLint("NewApi")
class MainActivity : Activity() {

    private enum class AndroidFrequencyDisplayUnit {
        KHZ,
        MHZ,
    }

    private enum class AndroidTemperatureDisplayUnit {
        CELSIUS,
        FAHRENHEIT,
    }

    private enum class AndroidDeviceTimeSetMode {
        MANUAL,
        AUTOMATIC,
    }

    private enum class AndroidScheduleTimeInputMode {
        ABSOLUTE,
        RELATIVE,
    }

    private data class RelativeTimeSelection(
        val hours: Int,
        val minutes: Int,
        val useTopOfHour: Boolean = false,
    )

private data class StartTimeFinishAdjustmentChoice(
    val label: String,
    val duration: Duration? = null,
    val disablesEvent: Boolean = false,
)

private fun RelativeScheduleSelection.toAndroidSelection(): RelativeTimeSelection {
    return RelativeTimeSelection(
        hours = hours,
        minutes = minutes,
        useTopOfHour = useTopOfHour,
    )
}

private fun RelativeTimeSelection.toSharedSelection(): RelativeScheduleSelection {
    return RelativeScheduleSelection(
        hours = hours,
        minutes = minutes,
        useTopOfHour = useTopOfHour,
    )
}

    private lateinit var usbManager: UsbManager
    private lateinit var uiPreferences: SharedPreferences
    private lateinit var content: LinearLayout
    private var developerDiagnosticsExpanded: Boolean = false
    private var frequencyDisplayUnit: AndroidFrequencyDisplayUnit = AndroidFrequencyDisplayUnit.MHZ
    private var temperatureDisplayUnit: AndroidTemperatureDisplayUnit = AndroidTemperatureDisplayUnit.CELSIUS
    private var deviceTimeSetMode: AndroidDeviceTimeSetMode = AndroidDeviceTimeSetMode.AUTOMATIC
    private var scheduleTimeInputMode: AndroidScheduleTimeInputMode = AndroidScheduleTimeInputMode.ABSOLUTE
    private var defaultEventLengthMinutes: Int = 6 * 60
    private var rawSerialVisible: Boolean = true
    private var systemTimeVisible: Boolean = false
    private var deviceDataVisible: Boolean = true
    private var loggingToolsVisible: Boolean = true
    private val autoDetectHandler = Handler(Looper.getMainLooper())
    private val clockDisplayHandler = Handler(Looper.getMainLooper())
    private var autoDetectGeneration: Int = 0
    private var pendingAutoPermissionDeviceName: String? = null
    private var autoProbeInFlightDeviceName: String? = null
    private val autoPermissionDeniedDeviceNames = mutableSetOf<String>()
    private var currentTimeDisplayField: EditText? = null
    private var currentTimeLabelView: TextView? = null
    private var systemTimeDisplayField: TextView? = null
    private var eventStatusDisplayField: TextView? = null
    private var daysRemainingSummaryView: TextView? = null
    private var displayedScheduleSnapshot: com.openardf.serialslinger.model.DeviceSnapshot? = null
    private var scheduleDerivedDataPending: Boolean = false
    private var headerStatusMessageView: TextView? = null
    private var thermalHeaderWarningText: String? = null
    private var thermalHeaderWarningDetail: String? = null
    private var relativeStartDisplaySelectionOverride: RelativeTimeSelection? = null
    private var relativeFinishDisplaySelectionOverride: RelativeTimeSelection? = null
    private var startTimeFinishAdjustmentDialogOpen: Boolean = false
    private var lastsDurationDialogOpen: Boolean = false
    private var multiDayDurationGuardDialogOpen: Boolean = false
    private val loggedWindows = WeakHashMap<Window, Boolean>()

    private val refreshListener: () -> Unit = { runOnUiThread { renderContent() } }
    private val clockTickRunnable =
        object : Runnable {
            override fun run() {
                updateDisplayedClockFields()
                scheduleClockDisplayTick()
            }
        }

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val deviceLabel = device?.productName ?: device?.deviceName ?: "USB device"
                        AndroidSessionController.logAppEvent(
                            title = "usb",
                            lines = listOf(
                                "Permission ${if (granted) "granted" else "denied"} for $deviceLabel.",
                                device?.deviceName?.let { "deviceName=$it" } ?: "deviceName=<unknown>",
                            ),
                        )
                        device?.deviceName?.let { deviceName ->
                            pendingAutoPermissionDeviceName = null
                            if (granted) {
                                autoPermissionDeniedDeviceNames -= deviceName
                            } else {
                                autoPermissionDeniedDeviceNames += deviceName
                            }
                        }
                        AndroidSessionController.recordStatus(
                            text = if (granted) {
                                "Loading SignalSlinger..."
                            } else {
                                "SignalSlinger disconnected."
                            },
                            isError = !granted,
                        )
                        if (granted) {
                            scheduleAutoDetect(delayMs = 0L)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        AndroidSessionController.logAppEvent(
                            title = "usb",
                            lines = listOf(
                                "Device attached: ${device?.productName ?: device?.deviceName ?: "<unknown>"}",
                                device?.deviceName?.let { "deviceName=$it" } ?: "deviceName=<unknown>",
                            ),
                        )
                        AndroidSessionController.recordStatus("Loading SignalSlinger...", isError = false)
                        scheduleAutoDetect(delayMs = AUTO_DETECT_ATTACH_DELAY_MS)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        AndroidSessionController.logAppEvent(
                            title = "usb",
                            lines = listOf(
                                "Device detached: ${device?.productName ?: device?.deviceName ?: "<unknown>"}",
                                device?.deviceName?.let { "deviceName=$it" } ?: "deviceName=<unknown>",
                            ),
                        )
                        device?.deviceName?.let { deviceName ->
                            if (pendingAutoPermissionDeviceName == deviceName) {
                                pendingAutoPermissionDeviceName = null
                            }
                            if (autoProbeInFlightDeviceName == deviceName) {
                                autoProbeInFlightDeviceName = null
                            }
                            autoPermissionDeniedDeviceNames -= deviceName
                            AndroidSessionController.clearLoadedSessionIfMatches(
                                deviceName = deviceName,
                                reasonText = "SignalSlinger disconnected.",
                            )
                        }
                        if (device == null) {
                            AndroidSessionController.recordStatus("SignalSlinger disconnected.", isError = false)
                        }
                        scheduleAutoDetect(delayMs = AUTO_DETECT_ATTACH_DELAY_MS)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidSessionController.initialize(applicationContext)
        uiPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        developerDiagnosticsExpanded = uiPreferences.getBoolean(PREF_DIAGNOSTICS_EXPANDED, false)
        rawSerialVisible = uiPreferences.getBoolean(PREF_RAW_SERIAL_VISIBLE, true)
        systemTimeVisible = uiPreferences.getBoolean(PREF_SYSTEM_TIME_VISIBLE, false)
        deviceDataVisible = uiPreferences.getBoolean(PREF_DEVICE_DATA_VISIBLE, true)
        loggingToolsVisible = uiPreferences.getBoolean(PREF_LOGGING_TOOLS_VISIBLE, true)
        frequencyDisplayUnit =
            AndroidFrequencyDisplayUnit.valueOf(
                uiPreferences.getString(PREF_FREQUENCY_DISPLAY_UNIT, AndroidFrequencyDisplayUnit.MHZ.name)
                    ?: AndroidFrequencyDisplayUnit.MHZ.name,
            )
        temperatureDisplayUnit =
            AndroidTemperatureDisplayUnit.valueOf(
                uiPreferences.getString(PREF_TEMPERATURE_DISPLAY_UNIT, AndroidTemperatureDisplayUnit.CELSIUS.name)
                    ?: AndroidTemperatureDisplayUnit.CELSIUS.name,
            )
        deviceTimeSetMode =
            AndroidDeviceTimeSetMode.valueOf(
                uiPreferences.getString(PREF_DEVICE_TIME_SET_MODE, AndroidDeviceTimeSetMode.AUTOMATIC.name)
                    ?: AndroidDeviceTimeSetMode.AUTOMATIC.name,
            )
        scheduleTimeInputMode =
            AndroidScheduleTimeInputMode.valueOf(
                uiPreferences.getString(PREF_SCHEDULE_TIME_INPUT_MODE, AndroidScheduleTimeInputMode.ABSOLUTE.name)
                    ?: AndroidScheduleTimeInputMode.ABSOLUTE.name,
            )
        defaultEventLengthMinutes =
            uiPreferences.getInt(PREF_DEFAULT_EVENT_LENGTH_MINUTES, 6 * 60).coerceIn(10, 24 * 60)
        usbManager = getSystemService(UsbManager::class.java)
        val spacingPx = (16 * resources.displayMetrics.density).toInt()

        content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                setPadding(spacingPx, spacingPx, spacingPx, spacingPx)
            }

        setContentView(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            },
        )
        installWindowUserActionLogging(window, "Main")

        registerUsbReceiver()
        if (!handleUsbLaunchIntent(intent, fromNewIntent = false)) {
            AndroidSessionController.recordStatus("SignalSlinger disconnected.", isError = false)
            scheduleAutoDetect(delayMs = 0L)
        }
        renderContent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbLaunchIntent(intent, fromNewIntent = true)
        renderContent()
    }

    override fun onResume() {
        super.onResume()
        AndroidSessionController.addListener(refreshListener)
        scheduleAutoDetect(delayMs = 0L)
        renderContent()
        scheduleClockDisplayTick()
    }

    override fun onPause() {
        autoDetectGeneration += 1
        autoDetectHandler.removeCallbacksAndMessages(null)
        clockDisplayHandler.removeCallbacksAndMessages(null)
        AndroidSessionController.removeListener(refreshListener)
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerUsbReceiver() {
        val filter =
            IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun scheduleAutoDetect(
        delayMs: Long,
        attempt: Int = 0,
    ) {
        val generation = ++autoDetectGeneration
        autoDetectHandler.postDelayed(
            {
                if (generation == autoDetectGeneration) {
                    runAutoDetect(attempt)
                }
            },
            delayMs,
        )
    }

    private fun runAutoDetect(attempt: Int) {
        val usbDevices = AndroidUsbTransport.connectedDevices(usbManager)
        val supportedDevices = usbDevices.filter { it.supportedSerialDriver }
        val permittedSupportedDevices = supportedDevices.filter { it.hasPermission }
        val uiState = AndroidSessionController.snapshotUiState()
        val loadedDeviceName = uiState.latestLoadedDeviceName

        if (loadedDeviceName != null && usbDevices.none { it.deviceName == loadedDeviceName }) {
            AndroidSessionController.clearLoadedSession("SignalSlinger disconnected.")
        }

        if (permittedSupportedDevices.isNotEmpty()) {
            val targetDevice =
                preferredAutoDetectDevice(
                    candidates = permittedSupportedDevices,
                    latestLoadedDeviceName = loadedDeviceName,
                )
            if (targetDevice != null) {
                if (uiState.sessionViewState != null && uiState.latestLoadedDeviceName == targetDevice.deviceName) {
                    return
                }
                if (autoProbeInFlightDeviceName == targetDevice.deviceName) {
                    return
                }

                autoProbeInFlightDeviceName = targetDevice.deviceName
                AndroidSessionController.logAppEvent(
                    title = "usb",
                    lines = listOf(
                        "Auto-probing ${targetDevice.productName ?: targetDevice.deviceName}.",
                        "deviceName=${targetDevice.deviceName}",
                    ),
                )
                AndroidSessionController.runProbe(
                    context = applicationContext,
                    requestedDeviceName = targetDevice.deviceName,
                    source = "auto",
                ) { result ->
                    autoProbeInFlightDeviceName = null
                    AndroidSessionController.logAppEvent(
                        title = "usb",
                        lines = listOf(
                            "Auto-probe ${if (result.isSuccess) "succeeded" else "failed"} for ${targetDevice.productName ?: targetDevice.deviceName}.",
                            "deviceName=${targetDevice.deviceName}",
                            result.exceptionOrNull()?.message?.let { "message=$it" } ?: "message=<none>",
                        ),
                    )
                    if (result.isFailure && attempt < AUTO_DETECT_MAX_RETRIES) {
                        scheduleAutoDetect(
                            delayMs = AUTO_DETECT_RETRY_DELAY_MS,
                            attempt = attempt + 1,
                        )
                    }
                }
                return
            }
        }

        val permissionCandidate =
            supportedDevices.firstOrNull { descriptor ->
                !descriptor.hasPermission &&
                    descriptor.deviceName != pendingAutoPermissionDeviceName &&
                    descriptor.deviceName !in autoPermissionDeniedDeviceNames
            }
        if (permissionCandidate != null) {
            val usbDevice = usbManager.deviceList.values.firstOrNull { it.deviceName == permissionCandidate.deviceName }
            if (usbDevice != null) {
                pendingAutoPermissionDeviceName = permissionCandidate.deviceName
                AndroidSessionController.logAppEvent(
                    title = "usb",
                    lines = listOf(
                        "Requesting USB permission for ${permissionCandidate.productName ?: permissionCandidate.deviceName}.",
                        "deviceName=${permissionCandidate.deviceName}",
                    ),
                )
                AndroidUsbTransport.requestPermission(
                    context = this,
                    usbManager = usbManager,
                    usbDevice = usbDevice,
                    action = ACTION_USB_PERMISSION,
                )
                AndroidSessionController.recordStatus(
                    "Loading SignalSlinger...",
                    isError = false,
                )
                return
            }
        }

        if (usbDevices.isEmpty() && attempt < AUTO_DETECT_MAX_RETRIES) {
            if (uiState.sessionViewState != null) {
                AndroidSessionController.clearLoadedSession("No SignalSlinger is attached.")
            }
            scheduleAutoDetect(
                delayMs = AUTO_DETECT_RETRY_DELAY_MS,
                attempt = attempt + 1,
            )
        }
    }

    private fun preferredAutoDetectDevice(
        candidates: List<AndroidUsbDeviceDescriptor>,
        latestLoadedDeviceName: String?,
    ): AndroidUsbDeviceDescriptor? {
        return candidates.firstOrNull { it.deviceName == latestLoadedDeviceName }
            ?: candidates.firstOrNull()
    }

    private fun renderContent() {
        val uiState = AndroidSessionController.snapshotUiState()
        val editableSettings = EditableDeviceSettings.fromDeviceSettings(DeviceSettings.empty())
        val usbDevices = AndroidUsbTransport.connectedDevices(usbManager)
        val sessionViewState = uiState.sessionViewState
        updateThermalHeaderWarning(sessionViewState?.state?.snapshot?.status?.temperatureC)
        currentTimeDisplayField = null
        currentTimeLabelView = null
        systemTimeDisplayField = null
        eventStatusDisplayField = null
        daysRemainingSummaryView = null
        displayedScheduleSnapshot = null
        scheduleDerivedDataPending = uiState.scheduleDerivedDataPending

        content.removeAllViews()
        content.addView(headerCard(uiState))

        if (sessionViewState == null) {
            content.addView(usbSessionCard(uiState, usbDevices))

            val discoveryCard = cardLayout().apply {
                addView(sectionTitle("USB Devices"))
                if (usbDevices.isEmpty()) {
                    addView(sectionBody("No USB devices visible."))
                } else {
                    usbDevices.forEach { device ->
                        addView(deviceCard(device, uiState.latestLoadedDeviceName))
                    }
                }
                if (uiState.latestProbeSummary.isNotBlank()) {
                    addView(fieldLabel("Latest Probe"))
                    addView(sectionBody(uiState.latestProbeSummary))
                }
            }
            content.addView(discoveryCard)
            return
        }

        val loadedSessionViewState = sessionViewState
        val snapshot = loadedSessionViewState.state.snapshot
        displayedScheduleSnapshot = snapshot
        val loadedSettings = snapshot?.settings ?: DeviceSettings.empty()
        val loadedStatus = snapshot?.status
        val loadedInfo = snapshot?.info
        val mainRow = tabletRow()
        val deviceSettingsCard = cardLayout()
        val timedEventCard = cardLayout()
        val deviceDataCard = cardLayout()

        val eventTypeOptions = EventProfileSupport.selectableEventTypes()
        val selectedEventType =
            EventProfileSupport.parseEventTypeOrNull(uiState.draftEventType.orEmpty())
                ?: loadedSettings.eventType
        val selectedFoxRole =
            EventProfileSupport.parseFoxRoleOrNull(uiState.draftFoxRole.orEmpty(), selectedEventType)
                ?: loadedSettings.foxRole
                ?: EventProfileSupport.foxRoleOptions(selectedEventType).firstOrNull()
        val loadedEventType = loadedSettings.eventType
        val loadedFoxRole = loadedSettings.foxRole
        var currentEventType = selectedEventType
        var currentFoxRole = loadedFoxRole ?: selectedFoxRole
        lateinit var eventTypeChooserButton: Button
        lateinit var foxRoleChooserButton: Button

        fun refreshProfileSelection(eventType: EventType, requestedFoxRole: FoxRole?) {
            val workflow =
                EventProfileSupport.resolveWorkflowState(
                    loadedEventType = loadedEventType,
                    loadedFoxRole = loadedFoxRole,
                    selectedEventType = eventType,
                    requestedFoxRole = requestedFoxRole,
                )
            currentEventType = workflow.selectedEventType
            currentFoxRole = workflow.selectedFoxRole
            eventTypeChooserButton.text = formatEventTypeLabel(workflow.selectedEventType)
            foxRoleChooserButton.text = workflow.selectedFoxRole?.uiLabel ?: "<none>"
            foxRoleChooserButton.isEnabled = workflow.roleSelectionEnabled
        }

        eventTypeChooserButton =
            rowButton("Choose Event Type") {
                val labels = eventTypeOptions.map(::formatEventTypeLabel).toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Choose Event Type")
                    .setSingleChoiceItems(labels, eventTypeOptions.indexOf(currentEventType).coerceAtLeast(0)) { dialog, which ->
                        val chosenEventType = eventTypeOptions[which]
                        refreshProfileSelection(chosenEventType, currentFoxRole)
                        if (chosenEventType != loadedEventType) {
                            AndroidSessionController.runEventTypeSubmit(
                                context = applicationContext,
                                eventTypeInput = chosenEventType.name,
                            )
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .showLogged("Choose Event Type")
            }
        foxRoleChooserButton =
            rowButton("Choose Fox Role") {
                val foxRoleOptions = EventProfileSupport.foxRoleOptions(loadedEventType)
                val labels = foxRoleOptions.map { it.uiLabel }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Choose Fox Role")
                    .setSingleChoiceItems(labels, foxRoleOptions.indexOf(currentFoxRole).coerceAtLeast(0)) { dialog, which ->
                        val chosenFoxRole = foxRoleOptions[which]
                        refreshProfileSelection(currentEventType, chosenFoxRole)
                        if (chosenFoxRole != loadedFoxRole) {
                            AndroidSessionController.runFoxRoleSubmit(
                                context = applicationContext,
                                foxRoleInput = chosenFoxRole.uiLabel,
                            )
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .showLogged("Choose Fox Role")
            }
        refreshProfileSelection(selectedEventType, selectedFoxRole)

        val frequencyPresentation = FrequencySupport.describeFrequencies(loadedSettings)
        val frequencyVisibility = EventProfileSupport.timedEventFrequencyVisibility(loadedSettings.eventType)
        val patternSpeedInTimedEvent = EventProfileSupport.patternSpeedBelongsToTimedEventSettings(loadedSettings.eventType)
        val derivedEventStatus =
            JvmTimeSupport.describeEventStatus(
                deviceReportedEventEnabled = loadedStatus?.eventEnabled,
                eventStateSummary = loadedStatus?.eventStateSummary,
                currentTimeCompact = loadedSettings.currentTimeCompact,
                startTimeCompact = loadedSettings.startTimeCompact,
                finishTimeCompact = loadedSettings.finishTimeCompact,
                startsInFallback = loadedStatus?.eventStartsInSummary,
                daysToRun = loadedSettings.daysToRun,
            )
        val durationSummary =
            JvmTimeSupport.describeEventDurationHoursMinutes(
                loadedSettings.startTimeCompact,
                loadedSettings.finishTimeCompact,
                loadedStatus?.eventDurationSummary,
            )
        val durationDiffersFromDefault =
            JvmTimeSupport.eventDurationDiffersFromDefault(
                startTimeCompact = loadedSettings.startTimeCompact,
                finishTimeCompact = loadedSettings.finishTimeCompact,
                defaultEventLengthMinutes = defaultEventLengthMinutes,
            )
        val warningColor = Color.parseColor("#9E1C1C")
        val normalLabelColor = Color.parseColor("#1F1F1F")
        val daysRemainingSummary = AndroidSessionController.displayedDaysToRunRemainingSummary()

        deviceSettingsCard.addView(sectionTitle("Device Settings"))
        deviceSettingsCard.addView(compactLabeledRow("Fox Role", foxRoleChooserButton))

        val patternTextEditable = EventProfileSupport.patternTextIsEditable(loadedSettings.eventType)
        if (patternTextEditable) {
            val patternTextEditor =
                EditText(this).apply {
                    hint = "Pattern Text"
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine()
                    setText(uiState.draftPatternText ?: loadedSettings.patternText.orEmpty())
                    layoutParams =
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                            val bottomMargin = (12 * resources.displayMetrics.density).toInt()
                            this.bottomMargin = bottomMargin
                        }
                }
            configureCommitTextEditor(patternTextEditor) {
                AndroidSessionController.runPatternTextSubmit(
                    context = applicationContext,
                    patternTextInput = patternTextEditor.text.toString(),
                )
            }
            deviceSettingsCard.addView(compactLabeledRow("Pattern Text", patternTextEditor))
        } else {
            deviceSettingsCard.addView(
                compactLabeledRow(
                    "Pattern Text",
                    readOnlyField(
                        EventProfileSupport.displayPatternText(
                            loadedSettings.eventType,
                            loadedSettings.foxRole,
                            loadedSettings.patternText,
                        ).ifBlank { "<blank>" },
                    ),
                ),
            )
        }

        if (!patternSpeedInTimedEvent) {
            val devicePatternSpeedSpinner =
                integerSpinner(
                    values = 5..20,
                    selectedValue = uiState.draftPatternSpeedWpm?.toIntOrNull() ?: loadedSettings.patternCodeSpeedWpm,
                )
            deviceSettingsCard.addView(compactLabeledRow("Pattern Speed", devicePatternSpeedSpinner))
            wireImmediateIntSpinner(
                devicePatternSpeedSpinner,
                selectedValue = uiState.draftPatternSpeedWpm?.toIntOrNull() ?: loadedSettings.patternCodeSpeedWpm,
            ) { selectedValue ->
                AndroidSessionController.runPatternSpeedSubmit(
                    context = applicationContext,
                    patternSpeedWpmText = selectedValue.toString(),
                )
            }
        }

        lateinit var currentTimeField: EditText
        currentTimeField =
            pickerField(
                text = uiState.draftCurrentTime ?: JvmTimeSupport.formatCompactTimestamp(loadedSettings.currentTimeCompact),
                hint = "Device Time",
                textSizeSp = timestampFieldTextSizeSp(),
                emphasizedInputStyle = deviceTimeSetMode == AndroidDeviceTimeSetMode.MANUAL,
                actionLabel = "Device Time",
            ) {
                if (deviceTimeSetMode == AndroidDeviceTimeSetMode.MANUAL) {
                    pickDateTime(
                        initialValue = uiState.draftCurrentTime ?: JvmTimeSupport.formatCompactTimestamp(loadedSettings.currentTimeCompact),
                        defaultValue = LocalDateTime.now(),
                    ) { selected ->
                        val formattedTimestamp = formatDisplayTimestamp(selected)
                        currentTimeField.setText(formattedTimestamp)
                        AndroidSessionController.runCurrentTimeSubmit(
                            context = applicationContext,
                            currentTimeInput = formattedTimestamp,
                        )
                    }
                } else {
                    AndroidSessionController.runCurrentTimeSystemSync(context = applicationContext)
                }
            }
        var currentTimeLabelField: TextView? = null
        val currentTimeRow =
            if (deviceDataVisible) {
                stackedLabeledRow(
                    deviceTimeRowLabel(),
                    currentTimeField,
                    captureLabelView = { labelView ->
                        currentTimeLabelView = labelView
                        currentTimeLabelField = labelView
                    },
                )
            } else {
                compactLabeledRow(
                    deviceTimeRowLabel(),
                    currentTimeField,
                    labelWidthDp = 132,
                    captureLabelView = { labelView ->
                        currentTimeLabelView = labelView
                        currentTimeLabelField = labelView
                    },
                )
            }
        deviceSettingsCard.addView(currentTimeRow)
        installDeviceTimeSettingToggle(currentTimeField, currentTimeLabelField, currentTimeRow)
        currentTimeDisplayField = currentTimeField
        if (systemTimeVisible) {
            val systemTimeField = readOnlyField(
                formatDisplayTimestamp(LocalDateTime.now().withNano(0)),
                textSizeSp = 13f,
                singleLine = true,
            )
            systemTimeDisplayField = systemTimeField
            deviceSettingsCard.addView(
                compactLabeledRow(
                    "System Time",
                    systemTimeField,
                ),
            )
        }
        val currentFrequencyField = readOnlyField(formatFrequencyForUnit(frequencyPresentation.currentFrequencyHz))
        var currentFrequencyLabelView: TextView? = null
        val currentFrequencyRow =
            compactLabeledRow(
                "Frequency",
                currentFrequencyField,
                captureLabelView = { currentFrequencyLabelView = it },
            )
        deviceSettingsCard.addView(currentFrequencyRow)
        installFrequencyDisplayUnitToggle(currentFrequencyField, currentFrequencyLabelView, currentFrequencyRow)
        deviceSettingsCard.addView(
            compactLabeledRow(
                "Memory Bank",
                readOnlyField(frequencyPresentation.currentBankId?.label ?: "<not inferred>"),
            ),
        )
        val externalBatteryControlField =
            if (snapshot?.capabilities?.supportsExternalBatteryControl == true) {
                enumSpinner(
                    options = ExternalBatteryControlMode.entries.toList(),
                    selectedValue = loadedSettings.externalBatteryControlMode ?: ExternalBatteryControlMode.OFF,
                ).also { batteryModeSpinner ->
                    wireImmediateSelectionSpinner(
                        spinner = batteryModeSpinner,
                        selectedValue = loadedSettings.externalBatteryControlMode ?: ExternalBatteryControlMode.OFF,
                    ) { selectedMode ->
                        AndroidSessionController.runExternalBatteryControlSubmit(
                            context = applicationContext,
                            mode = selectedMode,
                        )
                    }
                }
            } else {
                readOnlyField(loadedSettings.externalBatteryControlMode?.uiLabel.orUnknown())
            }
        deviceSettingsCard.addView(
            if (deviceDataVisible) {
                stackedLabeledRow(
                    "Ext. Bat. Ctrl",
                    externalBatteryControlField,
                )
            } else {
                compactLabeledRow(
                    "Ext. Bat. Ctrl",
                    externalBatteryControlField,
                )
            },
        )
        if (loadedSettings.externalBatteryControlMode == ExternalBatteryControlMode.CHARGE_ONLY) {
            deviceSettingsCard.addView(compactAlertRow("Transmitter Disabled"))
        }
        deviceSettingsCard.addView(
            compactLabeledRow(
                "Low Bat. Thresh.",
                if (snapshot?.capabilities?.supportsExternalBatteryControl == true) {
                    enumSpinner(
                        options = batteryThresholdOptions(),
                        selectedValue = loadedSettings.lowBatteryThresholdVolts?.let { "%.1f V".format(it) } ?: batteryThresholdOptions().first(),
                    ).also { batteryThresholdSpinner ->
                        wireImmediateSelectionSpinner(
                            spinner = batteryThresholdSpinner,
                            selectedValue = loadedSettings.lowBatteryThresholdVolts?.let { "%.1f V".format(it) } ?: batteryThresholdOptions().first(),
                        ) { selectedThreshold ->
                            AndroidSessionController.runLowBatteryThresholdSubmit(
                                context = applicationContext,
                                thresholdText = selectedThreshold,
                            )
                        }
                    }
                } else {
                    readOnlyField(loadedSettings.lowBatteryThresholdVolts?.let { "%.1f V".format(it) }.orUnknown())
                },
                labelWidthDp = 132,
            ),
        )

        timedEventCard.addView(sectionTitle("Timed Event Settings"))
        uiState.timeWorkflowNotice?.let { notice ->
            timedEventCard.addView(calloutView(notice))
        }
        timedEventCard.addView(compactLabeledRow("Event Type", eventTypeChooserButton))
        val stationIdEditor =
            EditText(this).apply {
                hint = "Station ID"
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setText(uiState.draftStationId ?: loadedSettings.stationId)
                layoutParams =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        val bottomMargin = (12 * resources.displayMetrics.density).toInt()
                        this.bottomMargin = bottomMargin
                    }
            }
        configureCommitTextEditor(stationIdEditor) {
            AndroidSessionController.runStationIdSubmit(
                context = applicationContext,
                stationId = stationIdEditor.text.toString(),
            )
        }
        timedEventCard.addView(compactLabeledRow("Station ID", stationIdEditor))
        val idSpeedSpinner =
            integerSpinner(
                values = 5..20,
                selectedValue = uiState.draftIdSpeedWpm?.toIntOrNull() ?: loadedSettings.idCodeSpeedWpm,
            )
        timedEventCard.addView(compactLabeledRow("ID Speed", idSpeedSpinner))
        wireImmediateIntSpinner(idSpeedSpinner, selectedValue = uiState.draftIdSpeedWpm?.toIntOrNull() ?: loadedSettings.idCodeSpeedWpm) { selectedValue ->
            AndroidSessionController.runIdSpeedSubmit(
                context = applicationContext,
                idSpeedWpmText = selectedValue.toString(),
            )
        }
        if (patternSpeedInTimedEvent) {
            val timedPatternSpeedSpinner =
                integerSpinner(
                    values = 5..20,
                    selectedValue = uiState.draftPatternSpeedWpm?.toIntOrNull() ?: loadedSettings.patternCodeSpeedWpm,
                )
            timedEventCard.addView(compactLabeledRow("Pattern Speed", timedPatternSpeedSpinner))
            wireImmediateIntSpinner(
                timedPatternSpeedSpinner,
                selectedValue = uiState.draftPatternSpeedWpm?.toIntOrNull() ?: loadedSettings.patternCodeSpeedWpm,
            ) { selectedValue ->
                AndroidSessionController.runPatternSpeedSubmit(
                    context = applicationContext,
                    patternSpeedWpmText = selectedValue.toString(),
                )
            }
        }

        val schedulingFieldsEditable = JvmTimeSupport.areSchedulingFieldsEditable(loadedSettings.currentTimeCompact)

        lateinit var startTimeField: EditText
        lateinit var finishTimeField: EditText
        startTimeField =
            if (scheduleTimeInputMode == AndroidScheduleTimeInputMode.RELATIVE) {
                val initialSelection =
                    relativeEditorSelectionForStart(
                        baseCompact = AndroidSessionController.displayedDeviceTimeCompact(),
                        targetCompact = loadedSettings.startTimeCompact,
                    )
                pickerField(
                    text = formatRelativeTimeSelection(initialSelection),
                    hint = "Start Time",
                    textSizeSp = timestampFieldTextSizeSp(),
                    actionLabel = "Start Time",
                    isEnabledForInteraction = schedulingFieldsEditable,
                ) {
                    showRelativeTimePickerDialog(
                        title = "Relative Start Time",
                        initialSelection = initialSelection,
                    ) { selection ->
                        val proposedStartTimeCompact = JvmTimeSupport.relativeTargetTimeCompact(
                            baseCompact = AndroidSessionController.displayedDeviceTimeCompact(),
                            hours = selection.hours,
                            minutes = selection.minutes,
                            useTopOfHour = selection.useTopOfHour,
                        )
                        chooseStartTimeFinishAdjustmentDuration(
                            currentStartTimeCompact = loadedSettings.startTimeCompact,
                            currentFinishTimeCompact = loadedSettings.finishTimeCompact,
                            proposedStartTimeCompact = proposedStartTimeCompact,
                            onCancel = {
                                relativeStartDisplaySelectionOverride = null
                                startTimeField.setText(formatRelativeTimeSelection(initialSelection))
                            },
                        ) { choice ->
                            if (choice.disablesEvent) {
                                clearRelativeScheduleDisplayOverrides()
                                AndroidSessionController.runDisableEventViaStartTimeCommand(
                                    context = applicationContext,
                                )
                                return@chooseStartTimeFinishAdjustmentDuration
                            }
                            val chosenDuration = choice.duration ?: return@chooseStartTimeFinishAdjustmentDuration
                            chooseScheduleChangeDurationResolution(
                                currentDaysToRun = loadedSettings.daysToRun,
                                proposedDuration = chosenDuration,
                                onCancel = {
                                    relativeStartDisplaySelectionOverride = null
                                    startTimeField.setText(formatRelativeTimeSelection(initialSelection))
                                },
                            ) { preserveDaysToRun, effectiveDuration ->
                                val resolvedDuration = effectiveDuration ?: chosenDuration
                                val finishSelection = relativeTimeSelectionForDuration(resolvedDuration)
                                relativeStartDisplaySelectionOverride = selection
                                relativeFinishDisplaySelectionOverride = finishSelection
                                val formattedSelection = formatRelativeTimeSelection(selection)
                                startTimeField.setText(formattedSelection)
                                AndroidSessionController.runRelativeStartTimeSubmit(
                                    context = applicationContext,
                                    offsetCommand = formatRelativeTimeCommand(selection),
                                    finishOffsetCommand = JvmTimeSupport.formatRelativeDurationCommand(resolvedDuration),
                                    preservedDaysToRun = if (preserveDaysToRun) loadedSettings.daysToRun else null,
                                )
                            }
                        }
                    }
                }
            } else {
                pickerField(
                    text = uiState.draftStartTime ?: JvmTimeSupport.formatCompactTimestamp(loadedSettings.startTimeCompact),
                    hint = "Start Time",
                    textSizeSp = timestampFieldTextSizeSp(),
                    actionLabel = "Start Time",
                    isEnabledForInteraction = schedulingFieldsEditable,
                ) {
                    val originalStartTimeText =
                        uiState.draftStartTime ?: JvmTimeSupport.formatCompactTimestamp(loadedSettings.startTimeCompact)
                    pickValidatedStartTime(
                        initialValue = startTimeField.text.toString(),
                        currentTimeCompact = AndroidSessionController.displayedDeviceTimeCompact(),
                        onCanceled = {
                            startTimeField.setText(originalStartTimeText)
                        },
                        onAdjustedToMinimum = { adjustedDisplay ->
                            startTimeField.setText(adjustedDisplay)
                        },
                    ) { normalizedStartTime ->
                        val formattedTimestamp = JvmTimeSupport.formatCompactTimestamp(normalizedStartTime)
                        val currentDurationFinishTime =
                            currentFinishTimeCompactForStartAdjustment(
                                editorText = finishTimeField.text.toString(),
                                originalFinishTimeCompact = loadedSettings.finishTimeCompact,
                            )
                        chooseStartTimeFinishAdjustmentDuration(
                            currentStartTimeCompact = loadedSettings.startTimeCompact,
                            currentFinishTimeCompact = currentDurationFinishTime,
                            proposedStartTimeCompact = normalizedStartTime,
                            onCancel = {
                                startTimeField.setText(formattedTimestamp)
                            },
                        ) { choice ->
                            if (choice.disablesEvent) {
                                clearRelativeScheduleDisplayOverrides()
                                AndroidSessionController.runDisableEventViaStartTimeCommand(
                                    context = applicationContext,
                                )
                                return@chooseStartTimeFinishAdjustmentDuration
                            }
                            val chosenDuration = choice.duration ?: return@chooseStartTimeFinishAdjustmentDuration
                            chooseScheduleChangeDurationResolution(
                                currentDaysToRun = loadedSettings.daysToRun,
                                proposedDuration = chosenDuration,
                                onCancel = {
                                    startTimeField.setText(formattedTimestamp)
                                },
                            ) { preserveDaysToRun, effectiveDuration ->
                                clearRelativeScheduleDisplayOverrides()
                                startTimeField.setText(formattedTimestamp)
                                AndroidSessionController.runStartTimeSubmit(
                                    context = applicationContext,
                                    startTimeInput = formattedTimestamp,
                                    defaultEventLengthMinutes = defaultEventLengthMinutes,
                                    requestedFinishTimeInput = JvmTimeSupport.finishTimeCompactFromStart(
                                        normalizedStartTime,
                                        effectiveDuration ?: chosenDuration,
                                    ),
                                    preserveDaysToRun = preserveDaysToRun,
                                )
                            }
                        }
                    }
                }
            }
        var startTimeLabelView: TextView? = null
        var absoluteStartField: TextView? = null
        val startTimeRow =
            if (deviceDataVisible) {
                stackedLabeledRow(
                    "Start Time",
                    startTimeField,
                    captureLabelView = { startTimeLabelView = it },
                )
            } else {
                compactLabeledRow(
                    "Start Time",
                    startTimeField,
                    captureLabelView = { startTimeLabelView = it },
                )
            }
        timedEventCard.addView(startTimeRow)
        startTimeLabelView?.alpha = if (schedulingFieldsEditable) 1f else 0.55f
        if (scheduleTimeInputMode == AndroidScheduleTimeInputMode.RELATIVE) {
            absoluteStartField =
                readOnlyField(
                    JvmTimeSupport.formatCompactTimestamp(loadedSettings.startTimeCompact),
                    textSizeSp = 13f,
                    singleLine = true,
                )
            val absoluteStartRow =
                compactLabeledRow(
                    "Start",
                    absoluteStartField,
                )
            timedEventCard.addView(absoluteStartRow)
        }
        val finishTimeEditable =
            schedulingFieldsEditable &&
                JvmTimeSupport.isFinishTimeEditable(
                startTimeCompact = loadedSettings.startTimeCompact,
                currentTimeCompact = loadedSettings.currentTimeCompact,
                )

        finishTimeField =
            if (scheduleTimeInputMode == AndroidScheduleTimeInputMode.RELATIVE) {
                val initialSelection =
                    relativeEditorSelectionForFinish()
                pickerField(
                    text = formatRelativeTimeSelection(initialSelection),
                    hint = "Finish Time",
                    textSizeSp = timestampFieldTextSizeSp(),
                    actionLabel = "Finish Time",
                    isEnabledForInteraction = finishTimeEditable,
                ) {
                    showRelativeTimePickerDialog(
                        title = "Relative Finish Time",
                        initialSelection = initialSelection,
                    ) { selection ->
                        val proposedFinishTimeCompact = JvmTimeSupport.relativeTargetTimeCompact(
                            baseCompact = loadedSettings.startTimeCompact,
                            hours = selection.hours,
                            minutes = selection.minutes,
                            useTopOfHour = selection.useTopOfHour,
                        )
                        val proposedDuration = JvmTimeSupport.validEventDuration(
                            loadedSettings.startTimeCompact,
                            proposedFinishTimeCompact,
                        )
                        chooseScheduleChangeDurationResolution(
                            currentDaysToRun = loadedSettings.daysToRun,
                            proposedDuration = proposedDuration,
                            onCancel = {
                                relativeFinishDisplaySelectionOverride = null
                                finishTimeField.setText(formatRelativeTimeSelection(initialSelection))
                            },
                        ) { preserveDaysToRun, effectiveDuration ->
                            val effectiveSelection = if (effectiveDuration != null && effectiveDuration != proposedDuration) {
                                relativeTimeSelectionForDuration(effectiveDuration)
                            } else {
                                selection
                            }
                            relativeFinishDisplaySelectionOverride = effectiveSelection
                            val formattedSelection = formatRelativeTimeSelection(effectiveSelection)
                            finishTimeField.setText(formattedSelection)
                            AndroidSessionController.runRelativeFinishTimeSubmit(
                                context = applicationContext,
                                offsetCommand = formatRelativeTimeCommand(effectiveSelection),
                                preservedDaysToRun = if (preserveDaysToRun) loadedSettings.daysToRun else null,
                            )
                        }
                    }
                }
            } else {
                pickerField(
                    text = uiState.draftFinishTime ?: JvmTimeSupport.formatCompactTimestamp(loadedSettings.finishTimeCompact),
                    hint = "Finish Time",
                    textSizeSp = timestampFieldTextSizeSp(),
                    actionLabel = "Finish Time",
                    isEnabledForInteraction = finishTimeEditable,
                ) {
                    pickDateTime(initialValue = finishTimeField.text.toString()) { selected ->
                        val formattedTimestamp = formatDisplayTimestamp(selected)
                        val proposedDuration = JvmTimeSupport.validEventDuration(
                            loadedSettings.startTimeCompact,
                            JvmTimeSupport.parseOptionalCompactTimestamp(formattedTimestamp),
                        )
                        chooseScheduleChangeDurationResolution(
                            currentDaysToRun = loadedSettings.daysToRun,
                            proposedDuration = proposedDuration,
                            onCancel = {
                                finishTimeField.setText(
                                    uiState.draftFinishTime ?: JvmTimeSupport.formatCompactTimestamp(loadedSettings.finishTimeCompact),
                                )
                            },
                        ) { preserveDaysToRun, effectiveDuration ->
                            clearRelativeScheduleDisplayOverrides()
                            val finalFinishTimeInput = if (effectiveDuration?.takeIf { it != proposedDuration } != null) {
                                val startTimeCompact = loadedSettings.startTimeCompact ?: return@chooseScheduleChangeDurationResolution
                                JvmTimeSupport.finishTimeCompactFromStart(startTimeCompact, effectiveDuration)
                            } else {
                                formattedTimestamp
                            }
                            finishTimeField.setText(JvmTimeSupport.formatCompactTimestamp(finalFinishTimeInput))
                            AndroidSessionController.runFinishTimeSubmit(
                                context = applicationContext,
                                finishTimeInput = finalFinishTimeInput,
                                preserveDaysToRun = preserveDaysToRun,
                            )
                        }
                    }
                }
        }
        var finishTimeLabelView: TextView? = null
        var absoluteFinishField: TextView? = null
        val finishTimeRow =
            if (deviceDataVisible) {
                stackedLabeledRow(
                    "Finish Time",
                    finishTimeField,
                    captureLabelView = { finishTimeLabelView = it },
                )
            } else {
                compactLabeledRow(
                    "Finish Time",
                    finishTimeField,
                    captureLabelView = { finishTimeLabelView = it },
                )
            }
        timedEventCard.addView(finishTimeRow)
        finishTimeLabelView?.apply {
            alpha = if (finishTimeEditable) 1f else 0.55f
        }
        if (scheduleTimeInputMode == AndroidScheduleTimeInputMode.RELATIVE) {
            absoluteFinishField =
                readOnlyField(
                    JvmTimeSupport.formatCompactTimestamp(loadedSettings.finishTimeCompact),
                    textSizeSp = 13f,
                    singleLine = true,
                )
            val absoluteFinishRow =
                compactLabeledRow(
                    "Finish",
                    absoluteFinishField,
                )
            timedEventCard.addView(absoluteFinishRow)
        }
        val eventStatusField =
            readOnlyField(
                if (uiState.scheduleDerivedDataPending) "Updating..." else derivedEventStatus,
                textSizeSp = 13f,
                maxLines = 2,
            )
        eventStatusDisplayField = eventStatusField
        timedEventCard.addView(
            if (deviceDataVisible) {
                stackedLabeledRow(
                    "Event Status",
                    eventStatusField,
                )
            } else {
                compactLabeledRow(
                    "Event Status",
                    eventStatusField,
                    labelWidthDp = 108,
                )
            },
        )
        val lastsField =
            pickerField(
                text = durationSummary,
                hint = "Lasts",
                textSizeSp = timestampFieldTextSizeSp(),
                actionLabel = "Lasts",
                isEnabledForInteraction = finishTimeEditable,
            ) {
                showLastsDurationDialog(
                    initialDuration =
                        JvmTimeSupport.validEventDuration(
                            loadedSettings.startTimeCompact,
                            loadedSettings.finishTimeCompact,
                        ) ?: Duration.ofMinutes(defaultEventLengthMinutes.toLong()),
                ) { selectedDuration ->
                    chooseScheduleChangeDurationResolution(
                        currentDaysToRun = loadedSettings.daysToRun,
                        proposedDuration = selectedDuration,
                        onCancel = {},
                    ) { preserveDaysToRun, effectiveDuration ->
                        clearRelativeScheduleDisplayOverrides()
                        AndroidSessionController.runEventDurationSubmit(
                            context = applicationContext,
                            requestedDuration = effectiveDuration ?: selectedDuration,
                            preserveDaysToRun = preserveDaysToRun,
                        )
                    }
                }
            }.apply {
                if (durationDiffersFromDefault) {
                    setTextColor(warningColor)
                }
            }
        var lastsLabelView: TextView? = null
        timedEventCard.addView(
            compactLabeledRow(
                "Lasts",
                lastsField,
                captureLabelView = { labelView ->
                    lastsLabelView = labelView
                },
            ),
        )
        lastsLabelView?.setTextColor(if (durationDiffersFromDefault) warningColor else normalLabelColor)
        lastsLabelView?.alpha = if (finishTimeEditable) 1f else 0.55f
        lastsLabelView?.setOnClickListener(if (finishTimeEditable) {
            View.OnClickListener { lastsField.performClick() }
        } else {
            null
        })

        val daysToRunSpinner =
            integerSpinner(
                values = 1..255,
                selectedValue = uiState.draftDaysToRun?.toIntOrNull() ?: loadedSettings.daysToRun,
            ).apply {
                isEnabled = schedulingFieldsEditable
                isClickable = schedulingFieldsEditable
                alpha = if (schedulingFieldsEditable) 1f else 0.55f
            }
        var daysToRunLabelView: TextView? = null
        val daysRowField =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                daysToRunSpinner.layoutParams =
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        val endMargin = (8 * resources.displayMetrics.density).toInt()
                        marginEnd = endMargin
                    }
                addView(daysToRunSpinner)
                val daysRemainingView =
                    TextView(this@MainActivity).apply {
                        text =
                            if (uiState.scheduleDerivedDataPending) {
                                "(updating...)"
                            } else {
                                daysRemainingSummary
                                    .replace("Remaining", "remain")
                                    .replace("remaining", "remain")
                            }
                        textSize = 13f
                        setTextColor(Color.parseColor("#1F2937"))
                        visibility =
                            if (uiState.scheduleDerivedDataPending || daysRemainingSummary.isNotBlank()) {
                                View.VISIBLE
                            } else {
                                View.GONE
                            }
                    }
                daysRemainingSummaryView = daysRemainingView
                addView(daysRemainingView)
            }
        timedEventCard.addView(
            compactLabeledRow(
                "Days To Run",
                daysRowField,
                captureLabelView = { daysToRunLabelView = it },
            ),
        )
        daysToRunLabelView?.alpha = if (schedulingFieldsEditable) 1f else 0.55f
        wireImmediateIntSpinner(daysToRunSpinner, selectedValue = uiState.draftDaysToRun?.toIntOrNull() ?: loadedSettings.daysToRun) { selectedValue ->
            val currentDuration = JvmTimeSupport.validEventDuration(
                loadedSettings.startTimeCompact,
                loadedSettings.finishTimeCompact,
            )
            chooseMultiDayDurationGuardHandling(
                options = ScheduleDurationGuardSupport.planForDirectDaysToRunChange(
                    selectedDaysToRun = selectedValue,
                    currentDuration = currentDuration,
                ),
                onCancel = {
                    daysToRunSpinner.setSelection((loadedSettings.daysToRun - 1).coerceAtLeast(0))
                },
            ) { option ->
                val resolution = ScheduleDurationGuardSupport.resolveDirectDaysToRunChange(
                    selectedDaysToRun = selectedValue,
                    currentDuration = currentDuration,
                    selectedOption = option,
                )
                when (option?.choice) {
                    MultiDayDurationGuardChoice.SHORTEN_DURATION -> {
                        clearRelativeScheduleDisplayOverrides()
                        val startTimeCompact = loadedSettings.startTimeCompact ?: return@chooseMultiDayDurationGuardHandling
                        AndroidSessionController.runDaysToRunSubmit(
                            context = applicationContext,
                            daysToRunText = resolution.resultingDaysToRun.toString(),
                            requestedFinishTimeInput = JvmTimeSupport.finishTimeCompactFromStart(
                                startTimeCompact,
                                resolution.resultingDuration ?: return@chooseMultiDayDurationGuardHandling,
                            ),
                        )
                    }
                    MultiDayDurationGuardChoice.SET_DAYS_TO_ONE -> {
                        AndroidSessionController.runDaysToRunSubmit(
                            context = applicationContext,
                            daysToRunText = resolution.resultingDaysToRun.toString(),
                        )
                    }
                    null -> {
                        AndroidSessionController.runDaysToRunSubmit(
                            context = applicationContext,
                            daysToRunText = resolution.resultingDaysToRun.toString(),
                        )
                    }
                }
            }
        }

        addFrequencyControl(
            card = timedEventCard,
            label = "Frequency 1",
            selectedFrequencyHz = loadedSettings.lowFrequencyHz,
        ) { selectedFrequencyHz ->
            AndroidSessionController.runFrequencyBankSubmit(
                context = applicationContext,
                bankId = FrequencyBankId.ONE,
                frequencyInput = (selectedFrequencyHz / 1_000).toString(),
            )
        }
        if (frequencyVisibility.showFrequency2) {
            addFrequencyControl(
                card = timedEventCard,
                label = "Frequency 2",
                selectedFrequencyHz = loadedSettings.mediumFrequencyHz,
            ) { selectedFrequencyHz ->
                AndroidSessionController.runFrequencyBankSubmit(
                    context = applicationContext,
                    bankId = FrequencyBankId.TWO,
                    frequencyInput = (selectedFrequencyHz / 1_000).toString(),
                )
            }
        }
        if (frequencyVisibility.showFrequency3) {
            addFrequencyControl(
                card = timedEventCard,
                label = "Frequency 3",
                selectedFrequencyHz = loadedSettings.highFrequencyHz,
            ) { selectedFrequencyHz ->
                AndroidSessionController.runFrequencyBankSubmit(
                    context = applicationContext,
                    bankId = FrequencyBankId.THREE,
                    frequencyInput = (selectedFrequencyHz / 1_000).toString(),
                )
            }
        }
        if (frequencyVisibility.showFrequencyB) {
            addFrequencyControl(
                card = timedEventCard,
                label = "Frequency B",
                selectedFrequencyHz = loadedSettings.beaconFrequencyHz,
            ) { selectedFrequencyHz ->
                AndroidSessionController.runFrequencyBankSubmit(
                    context = applicationContext,
                    bankId = FrequencyBankId.BEACON,
                    frequencyInput = (selectedFrequencyHz / 1_000).toString(),
                )
            }
        }

        val temperatureReadbackSupported = snapshot?.capabilities?.supportsTemperatureReadback == true
        val extendedTemperatureReadbackSupported = snapshot?.capabilities?.supportsExtendedTemperatureReadback == true
        deviceDataCard.addView(sectionTitle("Device Data"))
        deviceDataCard.addView(
            compactLabeledRow(
                "Internal Battery",
                readOnlyField(loadedStatus?.internalBatteryVolts?.let { "$it V" }.orUnknown()),
                labelWidthDp = 128,
            ),
        )
        deviceDataCard.addView(
            compactLabeledRow(
                "External Battery",
                readOnlyField(loadedStatus?.externalBatteryVolts?.let { "$it V" }.orUnknown()),
                labelWidthDp = 128,
            ),
        )
        val maximumEverTemperatureField = readOnlyField(formatTemperatureForDeviceData(loadedStatus?.maximumEverTemperatureC, extendedTemperatureReadbackSupported))
        var maximumEverTemperatureLabelView: TextView? = null
        deviceDataCard.addView(
            compactLabeledRow(
                "Maximum Ever Temperature",
                maximumEverTemperatureField,
                labelWidthDp = 132,
                captureLabelView = { maximumEverTemperatureLabelView = it },
            ),
        )
        if (extendedTemperatureReadbackSupported) {
            applyTemperatureRowAppearance(maximumEverTemperatureLabelView, maximumEverTemperatureField, loadedStatus?.maximumEverTemperatureC)
        }
        val maximumTemperatureField = readOnlyField(formatTemperatureForDeviceData(loadedStatus?.maximumTemperatureC, temperatureReadbackSupported))
        var maximumTemperatureLabelView: TextView? = null
        deviceDataCard.addView(
            compactLabeledRow(
                "Maximum Temperature",
                maximumTemperatureField,
                labelWidthDp = 132,
                captureLabelView = { maximumTemperatureLabelView = it },
            ),
        )
        if (temperatureReadbackSupported) {
            applyTemperatureRowAppearance(maximumTemperatureLabelView, maximumTemperatureField, loadedStatus?.maximumTemperatureC)
        }
        val currentTemperatureField = readOnlyField(formatTemperatureForDeviceData(loadedStatus?.temperatureC, temperatureReadbackSupported))
        var currentTemperatureLabelView: TextView? = null
        val currentTemperatureRow =
            compactLabeledRow(
                "Current Temperature",
                currentTemperatureField,
                labelWidthDp = 132,
                captureLabelView = { currentTemperatureLabelView = it },
            )
        deviceDataCard.addView(currentTemperatureRow)
        if (temperatureReadbackSupported) {
            applyTemperatureRowAppearance(currentTemperatureLabelView, currentTemperatureField, loadedStatus?.temperatureC)
        }
        val minimumTemperatureField = readOnlyField(formatTemperatureForDeviceData(loadedStatus?.minimumTemperatureC, temperatureReadbackSupported))
        var minimumTemperatureLabelView: TextView? = null
        deviceDataCard.addView(
            compactLabeledRow(
                "Minimum Temperature",
                minimumTemperatureField,
                labelWidthDp = 132,
                captureLabelView = { minimumTemperatureLabelView = it },
            ),
        )
        if (temperatureReadbackSupported) {
            applyTemperatureRowAppearance(minimumTemperatureLabelView, minimumTemperatureField, loadedStatus?.minimumTemperatureC)
        }
        deviceDataCard.addView(
            compactLabeledRow(
                "Thermal Shutdown Threshold",
                readOnlyField(formatTemperatureForDeviceData(loadedStatus?.thermalShutdownThresholdC, extendedTemperatureReadbackSupported)),
                labelWidthDp = 132,
            ),
        )
        installTemperatureDisplayUnitToggle(currentTemperatureField, currentTemperatureLabelView, currentTemperatureRow)
        deviceDataCard.addView(
            compactLabeledRow(
                "Version",
                readOnlyField(
                    listOfNotNull(
                        loadedInfo?.softwareVersion?.takeIf { it.isNotBlank() },
                        loadedInfo?.hardwareBuild?.takeIf { it.isNotBlank() }?.let { "HW $it" },
                    ).joinToString(" / ").ifBlank { "<unknown>" },
                ),
            ),
        )

        mainRow.addView(weightedCard(deviceSettingsCard))
        mainRow.addView(weightedCard(timedEventCard))
        if (deviceDataVisible) {
            mainRow.addView(weightedCard(deviceDataCard))
        }
        content.addView(mainRow)

        if (developerDiagnosticsExpanded) {
            val diagnosticsCard = cardLayout()
            diagnosticsCard.addView(sectionTitle("Developer Diagnostics"))
            diagnosticsCard.addView(fieldLabel("Shared-Core Signals"))
            diagnosticsCard.addView(sectionBody("Editable fields: ${editableSettings.fields.size}"))
            diagnosticsCard.addView(sectionBody("Load commands: ${SignalSlingerReadPlan.defaultLoadCommands.size}"))
            diagnosticsCard.addView(fieldLabel("Latest Probe"))
            diagnosticsCard.addView(sectionBody(uiState.latestProbeSummary))
            diagnosticsCard.addView(fieldLabel("Loaded Device Snapshot"))
            diagnosticsCard.addView(sectionBody(renderLoadedSnapshot(loadedSessionViewState.state)))
            diagnosticsCard.addView(fieldLabel("Latest Submit"))
            diagnosticsCard.addView(sectionBody(uiState.latestSubmitSummary))
            diagnosticsCard.addView(fieldLabel("Recent Serial Trace"))
            diagnosticsCard.addView(
                sectionBody(
                    if (rawSerialVisible) {
                        renderTrace(loadedSessionViewState.traceEntries)
                    } else {
                        "Hidden by current View setting."
                    },
                ),
            )
            diagnosticsCard.addView(fieldLabel("USB Devices"))
            diagnosticsCard.addView(
                rowButton("Refresh USB Devices") {
                    AndroidSessionController.recordStatus("SignalSlinger loaded.", isError = false)
                    renderContent()
                },
            )
            if (usbDevices.isEmpty()) {
                diagnosticsCard.addView(sectionBody("No USB devices visible."))
            } else {
                usbDevices.forEach { device ->
                    diagnosticsCard.addView(deviceCard(device, uiState.latestLoadedDeviceName))
                }
            }
            content.addView(diagnosticsCard)
        }
        updateDisplayedClockFields()
    }

    private fun updateDisplayedClockFields() {
        val systemNow = LocalDateTime.now()
        currentTimeDisplayField?.setText(AndroidSessionController.displayedDeviceTimeText(systemNow))
        val deviceTimeOutOfSync =
            deviceTimeSetMode == AndroidDeviceTimeSetMode.AUTOMATIC &&
                !AndroidSessionController.isDisplayedDeviceTimeSynchronized(systemNow)
        val warningColor = Color.parseColor("#9E1C1C")
        val normalLabelColor = Color.parseColor("#1F1F1F")
        val normalFieldColor = Color.parseColor("#1F2937")
        currentTimeLabelView?.setTextColor(if (deviceTimeOutOfSync) warningColor else normalLabelColor)
        currentTimeDisplayField?.setTextColor(if (deviceTimeOutOfSync) warningColor else normalFieldColor)
        currentTimeDisplayField?.hint =
            if (deviceTimeSetMode == AndroidDeviceTimeSetMode.AUTOMATIC) {
                "Tap to sync Device Time"
            } else {
                "Device Time"
            }
        systemTimeDisplayField?.text = formatDisplayTimestamp(LocalDateTime.now().withNano(0))
        val currentSchedulePending = AndroidSessionController.isScheduleDerivedDataPending()
        scheduleDerivedDataPending = currentSchedulePending
        eventStatusDisplayField?.text =
            if (currentSchedulePending) {
                "Updating..."
            } else {
                displayedScheduleSnapshot?.let { snapshot ->
                    JvmTimeSupport.describeEventStatus(
                        deviceReportedEventEnabled = snapshot.status.eventEnabled,
                        eventStateSummary = snapshot.status.eventStateSummary,
                        currentTimeCompact = AndroidSessionController.displayedDeviceTimeCompact(systemNow),
                        startTimeCompact = snapshot.settings.startTimeCompact,
                        finishTimeCompact = snapshot.settings.finishTimeCompact,
                        startsInFallback = snapshot.status.eventStartsInSummary,
                        daysToRun = snapshot.settings.daysToRun,
                    )
                }.orEmpty()
            }
        daysRemainingSummaryView?.let { summaryView ->
            val summary =
                if (currentSchedulePending) {
                    "(updating...)"
                } else {
                    AndroidSessionController.displayedDaysToRunRemainingSummary(systemNow)
                        .replace("Remaining", "remain")
                        .replace("remaining", "remain")
                }
            summaryView.text = summary
            summaryView.visibility = if (summary.isNotBlank()) View.VISIBLE else View.GONE
        }
    }

    private fun scheduleClockDisplayTick() {
        clockDisplayHandler.removeCallbacks(clockTickRunnable)
        val nowMs = System.currentTimeMillis()
        val millisPastSecond = nowMs % 1_000L
        val displayTickOffsetMs = 500L
        val delayMs =
            if (millisPastSecond < displayTickOffsetMs) {
                displayTickOffsetMs - millisPastSecond
            } else {
                (1_000L - millisPastSecond) + displayTickOffsetMs
            }
        clockDisplayHandler.postDelayed(clockTickRunnable, delayMs)
    }

    private fun handleUsbLaunchIntent(
        intent: Intent?,
        fromNewIntent: Boolean,
    ): Boolean {
        val action = intent?.action ?: return false
        if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            return false
        }
        val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
        val deviceLabel = device?.productName ?: device?.deviceName ?: "USB serial device"
        AndroidSessionController.logAppEvent(
            title = "usb",
            lines = listOf(
                if (fromNewIntent) {
                    "Activity reused for attached device: $deviceLabel"
                } else {
                    "Activity launched for attached device: $deviceLabel"
                },
                device?.deviceName?.let { "deviceName=$it" } ?: "deviceName=<unknown>",
            ),
        )
        AndroidSessionController.recordStatus("Loading SignalSlinger...", isError = false)
        scheduleAutoDetect(delayMs = AUTO_DETECT_ATTACH_DELAY_MS)
        return true
    }

    private fun usbSessionCard(
        uiState: AndroidUiState,
        usbDevices: List<AndroidUsbDeviceDescriptor>,
    ): LinearLayout {
        val supportedDevices = usbDevices.filter { it.supportedSerialDriver }
        val permittedSupportedDevices = supportedDevices.filter { it.hasPermission }
        val deniedSupportedDevices = supportedDevices.filter { it.deviceName in autoPermissionDeniedDeviceNames }
        val connectedDevice = usbDevices.firstOrNull { it.deviceName == uiState.latestLoadedDeviceName }

        val headline =
            when {
                uiState.sessionViewState != null && connectedDevice != null ->
                    "SignalSlinger connected on ${connectedDevice.productName ?: connectedDevice.deviceName}."
                autoProbeInFlightDeviceName != null ->
                    "SignalSlinger detected. Auto-loading snapshot now."
                pendingAutoPermissionDeviceName != null ->
                    "USB serial device detected. Waiting for Android USB permission."
                deniedSupportedDevices.isNotEmpty() ->
                    "USB permission is still needed before SerialSlinger can connect."
                permittedSupportedDevices.isNotEmpty() ->
                    "USB serial device is ready. SerialSlinger will probe it automatically."
                supportedDevices.isNotEmpty() ->
                    "Supported USB serial hardware is attached, but permission is not available yet."
                usbDevices.isNotEmpty() ->
                    "A USB device is attached, but it does not currently look like a supported SignalSlinger serial device."
                else ->
                    "No SignalSlinger is attached. SerialSlinger is waiting for a USB device."
            }

        val detailLines =
            buildList {
                add("Visible: ${usbDevices.size}   Supported: ${supportedDevices.size}")
                if (connectedDevice != null && uiState.sessionViewState != null) {
                    add("Active: ${connectedDevice.productName ?: connectedDevice.deviceName}")
                } else if (autoProbeInFlightDeviceName != null) {
                    add("Probing: $autoProbeInFlightDeviceName")
                } else if (pendingAutoPermissionDeviceName != null) {
                    add("Permission: $pendingAutoPermissionDeviceName")
                } else if (deniedSupportedDevices.isNotEmpty()) {
                    add("Denied: ${deniedSupportedDevices.joinToString { it.productName ?: it.deviceName }}")
                } else if (permittedSupportedDevices.isNotEmpty()) {
                    add("Ready: ${permittedSupportedDevices.joinToString { it.productName ?: it.deviceName }}")
                }
            }

        return cardLayout().apply {
            addView(sectionTitle("USB Session"))
            addView(calloutView(headline))
            addView(sectionBody(detailLines.joinToString("\n")))
        }
    }

    private fun deviceCard(
        device: AndroidUsbDeviceDescriptor,
        latestLoadedDeviceName: String?,
    ): LinearLayout {
        val cardPadding = (12 * resources.displayMetrics.density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
            setBackgroundColor(if (device.likelySerial) 0xFFEAF6EA.toInt() else 0xFFF3F3F3.toInt())
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = cardPadding
                }

            addView(
                sectionBody(
                    buildString {
                        appendLine(device.productName ?: "Unnamed USB device")
                        appendLine(device.deviceName)
                        device.manufacturerName?.let { appendLine(it) }
                        appendLine("Vendor 0x${device.vendorId.toHex()} Product 0x${device.productId.toHex()}")
                        appendLine("Interfaces: ${device.interfaceCount}")
                        appendLine("Likely serial device: ${if (device.likelySerial) "yes" else "no"}")
                        appendLine("Supported serial driver: ${if (device.supportedSerialDriver) "yes" else "no"}")
                        append("Permission granted: ${device.hasPermission}")
                    },
                ),
            )

            if (!device.hasPermission) {
                addView(
                    rowButton("Request USB Permission") {
                        val usbDevice = usbManager.deviceList.values.firstOrNull { it.deviceName == device.deviceName }
                        if (usbDevice == null) {
                            AndroidSessionController.recordStatus(
                                "USB device disappeared before permission could be requested.",
                                isError = true,
                            )
                            return@rowButton
                        }
                        AndroidUsbTransport.requestPermission(
                            context = this@MainActivity,
                            usbManager = usbManager,
                            usbDevice = usbDevice,
                            action = ACTION_USB_PERMISSION,
                        )
                        AndroidSessionController.recordStatus(
                            "Permission request sent for ${device.productName ?: device.deviceName}.",
                            isError = false,
                        )
                    },
                )
            } else if (device.supportedSerialDriver) {
                addView(
                    rowButton(
                        if (latestLoadedDeviceName == device.deviceName) {
                            "Refresh SignalSlinger Snapshot"
                        } else {
                            "Load SignalSlinger Snapshot"
                        },
                    ) {
                        AndroidSessionController.runProbe(
                            context = applicationContext,
                            requestedDeviceName = device.deviceName,
                        )
                    },
                )
            } else {
                addView(sectionBody("USB permission is already available for this device."))
                addView(sectionBody("No supported Android serial driver is available for this USB device yet."))
            }
        }
    }

    private fun renderLoadedSnapshot(state: com.openardf.serialslinger.session.DeviceSessionState): String {
        val snapshot = state.snapshot ?: return "No snapshot is loaded."
        val info = snapshot.info
        val status = snapshot.status
        val settings = snapshot.settings
        val capabilities = snapshot.capabilities

        return buildString {
            appendLine("Connection: ${state.connectionState}")
            appendLine()
            appendLine("Device Info")
            appendLine("Software version: ${info.softwareVersion.orUnknown()}")
            appendLine("Hardware build: ${info.hardwareBuild.orUnknown()}")
            appendLine("Product name: ${info.productName.orUnknown()}")
            appendLine("Serial port name: ${info.serialPortName.orUnknown()}")
            appendLine()
            appendLine("Status")
            appendLine("Maximum ever temperature: ${formatTemperatureForUnit(status.maximumEverTemperatureC)}")
            appendLine("Maximum temperature: ${formatTemperatureForUnit(status.maximumTemperatureC)}")
            appendLine("Current temperature: ${formatTemperatureForUnit(status.temperatureC)}")
            appendLine("Minimum temperature: ${formatTemperatureForUnit(status.minimumTemperatureC)}")
            appendLine("Thermal shutdown threshold: ${formatTemperatureForUnit(status.thermalShutdownThresholdC)}")
            appendLine("Internal battery V: ${status.internalBatteryVolts?.toString().orUnknown()}")
            appendLine("External battery V: ${status.externalBatteryVolts?.toString().orUnknown()}")
            appendLine("Event enabled: ${status.eventEnabled?.toString().orUnknown()}")
            appendLine("Event state: ${status.eventStateSummary.orUnknown()}")
            appendLine(
                "Derived event status: ${
                    JvmTimeSupport.describeEventStatus(
                        deviceReportedEventEnabled = status.eventEnabled,
                        eventStateSummary = status.eventStateSummary,
                        currentTimeCompact = settings.currentTimeCompact,
                        startTimeCompact = settings.startTimeCompact,
                        finishTimeCompact = settings.finishTimeCompact,
                        startsInFallback = status.eventStartsInSummary,
                        daysToRun = settings.daysToRun,
                    )
                }",
            )
            appendLine("Starts in: ${status.eventStartsInSummary.orUnknown()}")
            appendLine("Duration: ${JvmTimeSupport.describeEventDuration(settings.startTimeCompact, settings.finishTimeCompact, status.eventDurationSummary)}")
            appendLine("Last communication error: ${status.lastCommunicationError.orUnknown()}")
            appendLine()
            appendLine("Settings")
            appendLine("Station ID: ${settings.stationId}")
            appendLine("Event type: ${settings.eventType}")
            appendLine("Fox role: ${settings.foxRole?.uiLabel.orUnknown()}")
            appendLine("Effective pattern text: ${EventProfileSupport.displayPatternText(settings.eventType, settings.foxRole, settings.patternText).ifBlank { null }.orUnknown()}")
            appendLine("Stored pattern text: ${settings.patternText.orUnknown()}")
            appendLine("ID speed WPM: ${settings.idCodeSpeedWpm}")
            appendLine("Pattern speed WPM: ${settings.patternCodeSpeedWpm}")
            appendLine("Current time: ${JvmTimeSupport.formatCompactTimestampOrNotSet(settings.currentTimeCompact)}")
            appendLine("Start time: ${JvmTimeSupport.formatCompactTimestampOrNotSet(settings.startTimeCompact)}")
            appendLine("Finish time: ${JvmTimeSupport.formatCompactTimestampOrNotSet(settings.finishTimeCompact)}")
            appendLine("Days to run: ${settings.daysToRun}")
            appendLine("Default frequency Hz: ${settings.defaultFrequencyHz}")
            appendLine("Low frequency Hz: ${settings.lowFrequencyHz?.toString().orUnknown()}")
            appendLine("Medium frequency Hz: ${settings.mediumFrequencyHz?.toString().orUnknown()}")
            appendLine("High frequency Hz: ${settings.highFrequencyHz?.toString().orUnknown()}")
            appendLine("Beacon frequency Hz: ${settings.beaconFrequencyHz?.toString().orUnknown()}")
            appendLine("Low battery threshold V: ${settings.lowBatteryThresholdVolts?.toString().orUnknown()}")
            appendLine("External battery control: ${settings.externalBatteryControlMode?.uiLabel.orUnknown()}")
            appendLine("Transmissions enabled: ${settings.transmissionsEnabled}")
            appendLine()
            appendLine("Capabilities")
            appendLine("Temperature readback: ${capabilities.supportsTemperatureReadback}")
            appendLine("External battery control: ${capabilities.supportsExternalBatteryControl}")
            appendLine("Pattern editing: ${capabilities.supportsPatternEditing}")
            appendLine("Scheduling: ${capabilities.supportsScheduling}")
            appendLine("Frequency profiles: ${capabilities.supportsFrequencyProfiles}")
        }.trim()
    }

    private fun renderTrace(traceEntries: List<com.openardf.serialslinger.session.SerialTraceEntry>): String {
        if (traceEntries.isEmpty()) {
            return "No serial trace captured."
        }

        return traceEntries
            .takeLast(24)
            .joinToString("\n") { entry ->
                "${entry.direction.label} ${entry.payload}"
            }
    }

    private fun headerCard(uiState: AndroidUiState): LinearLayout {
        headerStatusMessageView = null
        val thermalWarningActive = thermalHeaderWarningText != null
        val connectionText =
            thermalHeaderWarningText ?: when {
                uiState.latestLoadedDeviceName != null && uiState.sessionViewState != null -> "SignalSlinger loaded."
                else -> uiState.statusText
            }
        val headerMessage =
            thermalHeaderWarningDetail ?: uiState.statusText
                .takeIf { it.isNotBlank() && it != connectionText }
        val headerIsError = thermalWarningActive || uiState.statusIsError
        return cardLayout().apply {
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL or Gravity.START
                            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                            addView(
                                TextView(this@MainActivity).apply {
                                    text = "SerialSlinger:"
                                    setTypeface(Typeface.DEFAULT_BOLD)
                                    textSize = 18f
                                    layoutParams =
                                        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                                            val endMargin = (10 * resources.displayMetrics.density).toInt()
                                            marginEnd = endMargin
                                        }
                                },
                            )
                            addView(
                                statusView(
                                    text = connectionText,
                                    isError = headerIsError,
                                    onClick =
                                        uiState.latestLoadedDeviceName?.let { deviceName ->
                                            {
                                                AndroidSessionController.runProbe(
                                                    context = applicationContext,
                                                    requestedDeviceName = deviceName,
                                                )
                                            }
                                        },
                                ).apply {
                                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                                    setPadding(0, 0, 0, (6 * resources.displayMetrics.density).toInt())
                                },
                            )
                        },
                    )
                    addView(headerCloneButton(uiState))
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL or Gravity.END
                            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                            headerMessage?.let { message ->
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = message
                                        textSize = 13f
                                        gravity = Gravity.END
                                        textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                                        setTextColor(if (headerIsError) Color.parseColor("#9E1C1C") else Color.parseColor("#475569"))
                                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                                        val startPadding = (12 * resources.displayMetrics.density).toInt()
                                        val bottomPadding = (6 * resources.displayMetrics.density).toInt()
                                        setPadding(startPadding, 0, 0, bottomPadding)
                                        headerStatusMessageView = this
                                    },
                                )
                            }
                        },
                    )
                },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(weightedButton("Settings") { showSettingsDialog() })
                    if (loggingToolsVisible) {
                        addView(weightedButton("Tools") { showToolsDialog(uiState) })
                    }
                },
            )
        }
    }

    private fun tabletRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (16 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
        }

    private fun tabletColumn(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

    private fun <T : View> weightedCard(view: T): T {
        view.layoutParams =
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                val horizontalMargin = (8 * resources.displayMetrics.density).toInt()
                marginStart = horizontalMargin
                marginEnd = horizontalMargin
            }
        return view
    }

    private fun compactButtonRow(
        primaryLabel: String,
        primaryAction: () -> Unit,
        secondaryLabel: String,
        secondaryAction: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (12 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
            addView(weightedButton(primaryLabel, primaryAction))
            addView(weightedButton(secondaryLabel, secondaryAction))
        }

    private fun compactLabeledRow(
        label: String,
        field: View,
        labelWidthDp: Int = 108,
        captureLabelView: ((TextView) -> Unit)? = null,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (6 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }

            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    setTypeface(Typeface.DEFAULT_BOLD)
                    textSize = 13f
                    layoutParams =
                        LinearLayout.LayoutParams(
                            (labelWidthDp * resources.displayMetrics.density).toInt(),
                            WRAP_CONTENT,
                        ).apply {
                            val endMargin = (8 * resources.displayMetrics.density).toInt()
                            marginEnd = endMargin
                        }
                    maxLines = 2
                    captureLabelView?.invoke(this)
                },
            )

            field.layoutParams =
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(field)
        }

    private fun stackedLabeledRow(
        label: String,
        field: View,
        captureLabelView: ((TextView) -> Unit)? = null,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (10 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }

            addView(
                TextView(this@MainActivity).apply {
                    text = label
                    setTypeface(Typeface.DEFAULT_BOLD)
                    textSize = 13f
                    layoutParams =
                        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                            val bottomMargin = (4 * resources.displayMetrics.density).toInt()
                            this.bottomMargin = bottomMargin
                        }
                    captureLabelView?.invoke(this)
                },
            )

            field.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(field)
        }

    private fun summaryCard(state: com.openardf.serialslinger.session.DeviceSessionState): LinearLayout {
        val snapshot = state.snapshot ?: return cardLayout().apply { addView(sectionBody("No loaded SignalSlinger snapshot is available yet.")) }
        return cardLayout().apply {
            addView(sectionTitle("Connected Device"))
            addView(
                sectionBody(
                    buildString {
                        appendLine("Station ID: ${snapshot.settings.stationId}")
                        appendLine("Event: ${formatEventTypeLabel(snapshot.settings.eventType)}")
                        appendLine("Role: ${snapshot.settings.foxRole?.uiLabel ?: "<unknown>"}")
                        appendLine("Active frequency: ${FrequencySupport.formatFrequencyMhz(snapshot.settings.defaultFrequencyHz)}")
                        append("Software version: ${snapshot.info.softwareVersion ?: "<unknown>"}")
                    },
                ),
            )
        }
    }

    private fun deviceStatusCard(state: com.openardf.serialslinger.session.DeviceSessionState): LinearLayout {
        val snapshot = state.snapshot ?: return cardLayout()
        val status = snapshot.status
        val settings = snapshot.settings
        val derivedEventStatus =
            JvmTimeSupport.describeEventStatus(
                deviceReportedEventEnabled = status.eventEnabled,
                eventStateSummary = status.eventStateSummary,
                currentTimeCompact = settings.currentTimeCompact,
                startTimeCompact = settings.startTimeCompact,
                finishTimeCompact = settings.finishTimeCompact,
                startsInFallback = status.eventStartsInSummary,
                daysToRun = settings.daysToRun,
            )

        return cardLayout().apply {
            addView(sectionTitle("Device Status"))
            addView(
                sectionBody(
                    buildString {
                        appendLine("Derived event status: $derivedEventStatus")
                        appendLine("Starts in: ${status.eventStartsInSummary.orUnknown()}")
                        appendLine("Duration: ${JvmTimeSupport.describeEventDuration(settings.startTimeCompact, settings.finishTimeCompact, status.eventDurationSummary)}")
                        appendLine("Maximum ever temperature: ${formatTemperatureForUnit(status.maximumEverTemperatureC)}")
                        appendLine("Maximum temperature: ${formatTemperatureForUnit(status.maximumTemperatureC)}")
                        appendLine("Current temperature: ${formatTemperatureForUnit(status.temperatureC)}")
                        appendLine("Minimum temperature: ${formatTemperatureForUnit(status.minimumTemperatureC)}")
                        appendLine("Thermal shutdown threshold: ${formatTemperatureForUnit(status.thermalShutdownThresholdC)}")
                        appendLine("Internal battery: ${status.internalBatteryVolts?.let { "$it V" }.orUnknown()}")
                        appendLine("External battery: ${status.externalBatteryVolts?.let { "$it V" }.orUnknown()}")
                        append("Transmissions enabled: ${settings.transmissionsEnabled}")
                    },
                ),
            )
        }
    }

    private fun appMenuCard(uiState: AndroidUiState): LinearLayout {
        return cardLayout().apply {
            addView(sectionTitle("Menu"))
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(weightedButton("Settings") { showSettingsDialog() })
                    if (loggingToolsVisible) {
                        addView(weightedButton("Tools") { showToolsDialog(uiState) })
                    }
                },
            )
        }
    }

    private fun actionsCard(
        latestLoadedDeviceName: String?,
        diagnosticsExpanded: Boolean,
    ): LinearLayout {
        return cardLayout().apply {
            addView(sectionTitle("Actions"))
            addView(
                rowButton("Refresh USB Devices") {
                    AndroidSessionController.recordStatus("USB device list refreshed.", isError = false)
                    renderContent()
                },
            )
            addView(
                disclosureButton(
                    label = "Developer Diagnostics",
                    expanded = diagnosticsExpanded,
                ) {
                    developerDiagnosticsExpanded = !developerDiagnosticsExpanded
                    saveDiagnosticsExpandedPreference()
                    renderContent()
                },
            )
        }
    }

    private fun showViewDialog(uiState: AndroidUiState) {
        val sessionViewState = uiState.sessionViewState
        val options =
            listOf(
                (if (developerDiagnosticsExpanded) "Hide Developer Diagnostics" else "Show Developer Diagnostics") to {
                    developerDiagnosticsExpanded = !developerDiagnosticsExpanded
                    saveDiagnosticsExpandedPreference()
                    renderContent()
                },
                (if (rawSerialVisible) "Hide Raw Serial Trace" else "Show Raw Serial Trace") to {
                    rawSerialVisible = !rawSerialVisible
                    saveRawSerialVisiblePreference()
                    renderContent()
                },
                (if (systemTimeVisible) "Hide System Time" else "Show System Time") to {
                    systemTimeVisible = !systemTimeVisible
                    saveSystemTimeVisiblePreference()
                    renderContent()
                },
                (if (deviceDataVisible) "Hide Device Data" else "Show Device Data") to {
                    deviceDataVisible = !deviceDataVisible
                    saveDeviceDataVisiblePreference()
                    renderContent()
                },
                "View Loaded Snapshot Text" to {
                    showLargeTextDialog(
                        title = "Loaded Snapshot",
                        text = sessionViewState?.state?.let(::renderLoadedSnapshot) ?: "No SignalSlinger snapshot is currently loaded.",
                    )
                },
                "View Recent Serial Trace" to {
                    showLargeTextDialog(
                        title = "Recent Serial Trace",
                        text = renderTrace(sessionViewState?.traceEntries.orEmpty()),
                    )
                },
            )
        AlertDialog.Builder(this)
            .setTitle("View")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .setNegativeButton("Close", null)
            .showLogged("View")
    }

    private fun showSettingsDialog() {
        val appVersionLabel = appVersionLabel()
        val labels =
            arrayOf(
                "Frequency Units (${if (frequencyDisplayUnit == AndroidFrequencyDisplayUnit.MHZ) "MHz" else "kHz"})",
                "Temperature Units (${if (temperatureDisplayUnit == AndroidTemperatureDisplayUnit.CELSIUS) "Celsius" else "Fahrenheit"})",
                "Device Time Setting (${if (deviceTimeSetMode == AndroidDeviceTimeSetMode.MANUAL) "Manual" else "Automatic"})",
                "Schedule Time Setting (${if (scheduleTimeInputMode == AndroidScheduleTimeInputMode.RELATIVE) "Relative" else "Absolute"})",
                "Default Event Length (${formatDefaultEventLength(defaultEventLengthMinutes)})",
                "${if (systemTimeVisible) "Hide" else "Show"} System Time",
                "${if (deviceDataVisible) "Hide" else "Show"} Device Data",
                "${if (loggingToolsVisible) "Hide" else "Show"} Logging Tools",
                "About (Ver $appVersionLabel)",
            )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(labels) { dialog, which ->
                when (which) {
                    0 -> showFrequencySettingsDialog()
                    1 -> showTemperatureSettingsDialog()
                    2 -> showDeviceTimeSettingDialog()
                    3 -> showScheduleTimeInputSettingDialog()
                    4 -> showDefaultEventLengthDialog()
                    5 -> setSystemTimeVisible(!systemTimeVisible)
                    6 -> setDeviceDataVisible(!deviceDataVisible)
                    7 -> setLoggingToolsVisible(!loggingToolsVisible)
                    8 -> showAboutDialog()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .showLogged("Settings")
    }

    private fun showFrequencySettingsDialog() {
        val labels = arrayOf("MHz", "kHz")
        AlertDialog.Builder(this)
            .setTitle("Frequency Units")
            .setSingleChoiceItems(labels, if (frequencyDisplayUnit == AndroidFrequencyDisplayUnit.MHZ) 0 else 1) { dialog, which ->
                setFrequencyDisplayUnit(if (which == 0) AndroidFrequencyDisplayUnit.MHZ else AndroidFrequencyDisplayUnit.KHZ)
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .showLogged("Frequency Units")
    }

    private fun showTemperatureSettingsDialog() {
        val labels = arrayOf("Celsius", "Fahrenheit")
        AlertDialog.Builder(this)
            .setTitle("Temperature Units")
            .setSingleChoiceItems(labels, if (temperatureDisplayUnit == AndroidTemperatureDisplayUnit.CELSIUS) 0 else 1) { dialog, which ->
                setTemperatureDisplayUnit(
                    if (which == 0) {
                        AndroidTemperatureDisplayUnit.CELSIUS
                    } else {
                        AndroidTemperatureDisplayUnit.FAHRENHEIT
                    },
                )
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .showLogged("Temperature Units")
    }

    private fun showDeviceTimeSettingDialog() {
        val labels = arrayOf("Automatic", "Manual")
        AlertDialog.Builder(this)
            .setTitle("Device Time Setting")
            .setSingleChoiceItems(labels, if (deviceTimeSetMode == AndroidDeviceTimeSetMode.AUTOMATIC) 0 else 1) { dialog, which ->
                setDeviceTimeSetMode(
                    if (which == 0) {
                        AndroidDeviceTimeSetMode.AUTOMATIC
                    } else {
                        AndroidDeviceTimeSetMode.MANUAL
                    },
                )
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .showLogged("Device Time Setting")
    }

    private fun showScheduleTimeInputSettingDialog() {
        val labels = arrayOf("Absolute", "Relative")
        AlertDialog.Builder(this)
            .setTitle("Schedule Time Setting")
            .setSingleChoiceItems(labels, if (scheduleTimeInputMode == AndroidScheduleTimeInputMode.ABSOLUTE) 0 else 1) { dialog, which ->
                if (startTimeFinishAdjustmentDialogOpen || lastsDurationDialogOpen || multiDayDurationGuardDialogOpen) {
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                scheduleTimeInputMode =
                    if (which == 0) {
                        AndroidScheduleTimeInputMode.ABSOLUTE
                    } else {
                        AndroidScheduleTimeInputMode.RELATIVE
                    }
                if (scheduleTimeInputMode == AndroidScheduleTimeInputMode.ABSOLUTE) {
                    clearRelativeScheduleDisplayOverrides()
                }
                saveScheduleTimeInputModePreference()
                renderContent()
                dialog.dismiss()
            }
            .setNegativeButton("Close", null)
            .showLogged("Schedule Time Setting")
    }

    private fun showDefaultEventLengthDialog() {
        val dialogHorizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val previewView =
            sectionBody("").apply {
                setTextColor(Color.parseColor("#2F5EA6"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dialogHorizontalPadding, 0, dialogHorizontalPadding, (12 * resources.displayMetrics.density).toInt())
            }
        val pickerLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dialogHorizontalPadding, 0, dialogHorizontalPadding, 0)
                addView(previewView)
            }

        val initialHours = (defaultEventLengthMinutes / 60).coerceIn(0, 24)
        val initialMinutes = (defaultEventLengthMinutes % 60).coerceIn(0, 55)
        val hourDigits = initialHours.toString().padStart(2, '0')
        val tensSpinner = digitSpinner((0..2).toList(), hourDigits[0].digitToInt())
        val onesSpinner = digitSpinner((0..9).toList(), hourDigits[1].digitToInt())
        val minuteOptions = (0..55 step 5).map { it.toString().padStart(2, '0') }
        val minuteSpinner = dialogSelectionSpinner(minuteOptions, initialMinutes.toString().padStart(2, '0'), emphasized = true)

        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(tensSpinner)
                addView(onesSpinner)
                addView(pickerContextLabel("h", emphasized = true))
                addView(minuteSpinner)
                addView(pickerContextLabel("m", emphasized = true))
            }
        pickerLayout.addView(row, 0)
        pickerLayout.addView(
            sectionBody("Range: 10 minutes to 24 hours."),
        )

        val currentMinutes: () -> Int = {
            val rawHours = ((tensSpinner.selectedItem as Int) * 10) + (onesSpinner.selectedItem as Int)
            val boundedHours = rawHours.coerceIn(0, 24)
            if (boundedHours != rawHours) {
                val boundedText = boundedHours.toString().padStart(2, '0')
                setSpinnerSelectionByValue(tensSpinner, (0..2).toList(), boundedText[0].digitToInt())
                setSpinnerSelectionByValue(onesSpinner, (0..9).toList(), boundedText[1].digitToInt())
            }
            (boundedHours * 60) + (minuteSpinner.selectedItem as String).toInt()
        }

        val refreshSelection = {
            val minutes = currentMinutes()
            previewView.text =
                if (minutes in 10..(24 * 60)) {
                    "Default finish offset: ${formatDefaultEventLength(minutes)}"
                } else {
                    "Choose between 10 minutes and 24 hours."
                }
        }
        wireDigitSpinner(tensSpinner, refreshSelection)
        wireDigitSpinner(onesSpinner, refreshSelection)
        wireImmediateSelectionSpinner(minuteSpinner, initialMinutes.toString().padStart(2, '0')) { _ -> refreshSelection() }
        refreshSelection.invoke()

        AlertDialog.Builder(this)
            .setTitle("Default Event Length")
            .setView(pickerLayout)
            .setPositiveButton("OK") { _, _ ->
                setDefaultEventLengthMinutes(currentMinutes())
            }
            .setNegativeButton("Cancel", null)
            .showLogged("Default Event Length")
    }

    private fun showLastsDurationDialog(
        initialDuration: Duration,
        onSelected: (Duration) -> Unit,
    ) {
        val dialogHorizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val previewView =
            sectionBody("").apply {
                setTextColor(Color.parseColor("#2F5EA6"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dialogHorizontalPadding, 0, dialogHorizontalPadding, (12 * resources.displayMetrics.density).toInt())
            }
        val pickerLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dialogHorizontalPadding, 0, dialogHorizontalPadding, 0)
                addView(previewView)
            }

        val initialMinutes = JvmTimeSupport.roundDurationMinutesToNearestFive(initialDuration).toMinutes()
        val hourDigits = (initialMinutes / 60).toInt().coerceIn(0, 480).toString().padStart(3, '0')
        val hundredsSpinner = digitSpinner((0..4).toList(), hourDigits[0].digitToInt())
        val tensSpinner = digitSpinner((0..9).toList(), hourDigits[1].digitToInt())
        val onesSpinner = digitSpinner((0..9).toList(), hourDigits[2].digitToInt())
        val minuteOptions = (0..55 step 5).map { it.toString().padStart(2, '0') }
        val minuteSpinner = dialogSelectionSpinner(minuteOptions, (initialMinutes % 60).toString().padStart(2, '0'), emphasized = true)

        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(hundredsSpinner)
                addView(tensSpinner)
                addView(onesSpinner)
                addView(pickerContextLabel("h", emphasized = true))
                addView(minuteSpinner)
                addView(pickerContextLabel("m", emphasized = true))
            }
        pickerLayout.addView(row, 0)

        val currentMinutes: () -> Int = {
            val rawHours =
                ((hundredsSpinner.selectedItem as Int) * 100) +
                    ((tensSpinner.selectedItem as Int) * 10) +
                    (onesSpinner.selectedItem as Int)
            val boundedHours = rawHours.coerceIn(0, 480)
            if (boundedHours != rawHours) {
                val boundedText = boundedHours.toString().padStart(3, '0')
                setSpinnerSelectionByValue(hundredsSpinner, (0..4).toList(), boundedText[0].digitToInt())
                setSpinnerSelectionByValue(tensSpinner, (0..9).toList(), boundedText[1].digitToInt())
                setSpinnerSelectionByValue(onesSpinner, (0..9).toList(), boundedText[2].digitToInt())
            }
            (boundedHours * 60) + (minuteSpinner.selectedItem as String).toInt()
        }

        val refreshSelection = {
            val minutes = currentMinutes()
            previewView.text =
                if (minutes > 0) {
                    "Event duration: ${JvmTimeSupport.formatDurationHoursMinutesCompact(Duration.ofMinutes(minutes.toLong()))}"
                } else {
                    "Choose at least 5 minutes."
                }
        }
        wireDigitSpinner(hundredsSpinner, refreshSelection)
        wireDigitSpinner(tensSpinner, refreshSelection)
        wireDigitSpinner(onesSpinner, refreshSelection)
        wireImmediateSelectionSpinner(minuteSpinner, (initialMinutes % 60).toString().padStart(2, '0')) { _ -> refreshSelection() }
        refreshSelection.invoke()

        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Lasts")
                .setView(pickerLayout)
                .setPositiveButton("OK") { _, _ ->
                    val minutes = currentMinutes()
                    if (minutes <= 0) {
                        AndroidSessionController.recordStatus("Event duration must be at least 5 minutes.", isError = true)
                    } else {
                        onSelected(Duration.ofMinutes(minutes.toLong()))
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()
        dialog.setOnDismissListener { lastsDurationDialogOpen = false }
        lastsDurationDialogOpen = true
        dialog.showLogged("Lasts")
    }

    private fun showAboutDialog() {
        val aboutText =
            SpannableStringBuilder().apply {
                append("App Version: ${appVersionLabel()}\n")
                append("Build Date (UTC): ${BuildConfig.BUILD_DATE_UTC}\n")
                append("Package: $packageName\n")
                append("Project: ")
                val linkStart = length
                append(BuildConfig.PROJECT_URL)
                setSpan(URLSpan(BuildConfig.PROJECT_URL), linkStart, length, 0)
                append("\n")
                append("License: ${BuildConfig.LICENSE_LABEL}")
            }
        val aboutView =
            TextView(this).apply {
                text = aboutText
                val horizontalPadding = (20 * resources.displayMetrics.density).toInt()
                val topPadding = (8 * resources.displayMetrics.density).toInt()
                setPadding(horizontalPadding, topPadding, horizontalPadding, 0)
                autoLinkMask = 0
                linksClickable = true
                movementMethod = LinkMovementMethod.getInstance()
                setTextColor(Color.parseColor("#111827"))
                textSize = 15f
            }
        AlertDialog.Builder(this)
            .setTitle("About SerialSlinger")
            .setView(aboutView)
            .setPositiveButton("Close", null)
            .showLogged("About SerialSlinger")
    }

    private fun appVersionLabel(): String {
        return runCatching {
            val packageInfo =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
            packageInfo.versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    private fun installFrequencyDisplayUnitToggle(
        view: View,
        labelView: TextView?,
        extraTouchTarget: View? = null,
    ) {
        installDelayedLongPressToggle(view, labelView, extraTouchTarget) {
            performAnimatedToggle(view, labelView) {
                toggleFrequencyDisplayUnit()
            }
        }
    }

    private fun installTemperatureDisplayUnitToggle(
        view: View,
        labelView: TextView?,
        extraTouchTarget: View? = null,
    ) {
        installDelayedLongPressToggle(view, labelView, extraTouchTarget) {
            performAnimatedToggle(view, labelView) {
                toggleTemperatureDisplayUnit()
            }
        }
    }

    private fun installDeviceTimeSettingToggle(
        view: View,
        labelView: TextView?,
        extraTouchTarget: View? = null,
    ) {
        installDelayedLongPressToggle(view, labelView, extraTouchTarget) {
            performAnimatedToggle(view, labelView) {
                toggleDeviceTimeSetMode()
            }
        }
    }

    private fun performAnimatedToggle(
        view: View,
        labelView: TextView?,
        onToggle: () -> Unit,
    ) {
        animateToggleFeedback(view, labelView)
        clockDisplayHandler.postDelayed(onToggle, 220L)
    }

    private fun performAnimatedToggleGroup(
        targets: List<Pair<View, TextView?>>,
        onToggle: () -> Unit,
    ) {
        targets.forEach { (view, labelView) ->
            animateToggleFeedback(view, labelView)
        }
        clockDisplayHandler.postDelayed(onToggle, 220L)
    }

    private fun installDelayedLongPressToggle(
        view: View,
        labelView: TextView?,
        extraTouchTarget: View? = null,
        onLongPress: () -> Unit,
    ) {
        installLongPressGesture(view, allowShortTap = true, onLongPress = onLongPress)
        labelView?.let { label ->
            installLongPressGesture(label, allowShortTap = false, onLongPress = onLongPress)
        }
        extraTouchTarget?.takeIf { it !== view && it !== labelView }?.let { target ->
            installLongPressGesture(target, allowShortTap = false, onLongPress = onLongPress)
        }
    }

    private fun installLongPressGesture(
        view: View,
        allowShortTap: Boolean,
        onLongPress: () -> Unit,
    ) {
        var longPressTriggered = false
        var longPressRunnable: Runnable? = null
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    longPressRunnable =
                        Runnable {
                            longPressTriggered = true
                            onLongPress()
                        }.also { runnable ->
                            clockDisplayHandler.postDelayed(runnable, CUSTOM_LONG_PRESS_TIMEOUT_MS)
                        }
                    true
                }
                MotionEvent.ACTION_MOVE -> true
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let(clockDisplayHandler::removeCallbacks)
                    longPressRunnable = null
                    if (!longPressTriggered && allowShortTap) {
                        touchedView.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let(clockDisplayHandler::removeCallbacks)
                    longPressRunnable = null
                    true
                }
                else -> false
            }
        }
    }

    private fun setFrequencyDisplayUnit(unit: AndroidFrequencyDisplayUnit) {
        if (frequencyDisplayUnit == unit) {
            return
        }
        frequencyDisplayUnit = unit
        saveFrequencyDisplayUnitPreference()
        AndroidSessionController.recordStatus(
            "Frequency units set to ${if (unit == AndroidFrequencyDisplayUnit.MHZ) "MHz" else "kHz"}.",
            isError = false,
        )
        renderContent()
        clockDisplayHandler.post { animateHeaderStatusFeedback() }
    }

    private fun toggleFrequencyDisplayUnit() {
        setFrequencyDisplayUnit(
            if (frequencyDisplayUnit == AndroidFrequencyDisplayUnit.MHZ) {
                AndroidFrequencyDisplayUnit.KHZ
            } else {
                AndroidFrequencyDisplayUnit.MHZ
            },
        )
    }

    private fun setTemperatureDisplayUnit(unit: AndroidTemperatureDisplayUnit) {
        if (temperatureDisplayUnit == unit) {
            return
        }
        temperatureDisplayUnit = unit
        saveTemperatureDisplayUnitPreference()
        AndroidSessionController.recordStatus(
            "Temperature units set to ${if (unit == AndroidTemperatureDisplayUnit.CELSIUS) "Celsius" else "Fahrenheit"}.",
            isError = false,
        )
        renderContent()
        clockDisplayHandler.post { animateHeaderStatusFeedback() }
    }

    private fun toggleTemperatureDisplayUnit() {
        setTemperatureDisplayUnit(
            if (temperatureDisplayUnit == AndroidTemperatureDisplayUnit.CELSIUS) {
                AndroidTemperatureDisplayUnit.FAHRENHEIT
            } else {
                AndroidTemperatureDisplayUnit.CELSIUS
            },
        )
    }

    private fun setDeviceTimeSetMode(mode: AndroidDeviceTimeSetMode) {
        if (deviceTimeSetMode == mode) {
            return
        }
        deviceTimeSetMode = mode
        saveDeviceTimeSetModePreference()
        AndroidSessionController.recordStatus(
            "Device Time Setting is now ${if (mode == AndroidDeviceTimeSetMode.AUTOMATIC) "Automatic" else "Manual"}.",
            isError = false,
        )
        renderContent()
        clockDisplayHandler.post { animateHeaderStatusFeedback() }
    }

    private fun toggleDeviceTimeSetMode() {
        setDeviceTimeSetMode(
            if (deviceTimeSetMode == AndroidDeviceTimeSetMode.AUTOMATIC) {
                AndroidDeviceTimeSetMode.MANUAL
            } else {
                AndroidDeviceTimeSetMode.AUTOMATIC
            },
        )
    }

    private fun setDefaultEventLengthMinutes(minutes: Int) {
        val validatedMinutes = validateDefaultEventLengthMinutes(minutes)
        if (defaultEventLengthMinutes == validatedMinutes) {
            return
        }
        defaultEventLengthMinutes = validatedMinutes
        relativeFinishDisplaySelectionOverride = defaultEventLengthRelativeSelection()
        saveDefaultEventLengthPreference()
        AndroidSessionController.recordStatus(
            "Default Event Length is now ${formatDefaultEventLength(validatedMinutes)}.",
            isError = false,
        )
        renderContent()
        clockDisplayHandler.post { animateHeaderStatusFeedback() }
    }

    private fun setSystemTimeVisible(isVisible: Boolean) {
        if (systemTimeVisible == isVisible) {
            return
        }
        systemTimeVisible = isVisible
        saveSystemTimeVisiblePreference()
        AndroidSessionController.recordStatus(
            "System Time is now ${if (isVisible) "shown" else "hidden"}.",
            isError = false,
        )
        renderContent()
        clockDisplayHandler.post { animateHeaderStatusFeedback() }
    }

    private fun setDeviceDataVisible(isVisible: Boolean) {
        if (deviceDataVisible == isVisible) {
            return
        }
        deviceDataVisible = isVisible
        saveDeviceDataVisiblePreference()
        AndroidSessionController.recordStatus(
            "Device Data is now ${if (isVisible) "shown" else "hidden"}.",
            isError = false,
        )
        renderContent()
        clockDisplayHandler.post { animateHeaderStatusFeedback() }
    }

    private fun setLoggingToolsVisible(isVisible: Boolean) {
        if (loggingToolsVisible == isVisible) {
            return
        }
        loggingToolsVisible = isVisible
        saveLoggingToolsVisiblePreference()
        AndroidSessionController.recordStatus(
            "Logging Tools are now ${if (isVisible) "shown" else "hidden"}.",
            isError = false,
        )
        renderContent()
        clockDisplayHandler.post { animateHeaderStatusFeedback() }
    }

    private fun animateToggleFeedback(
        primaryView: View,
        labelView: TextView?,
    ) {
        animateFeedback(primaryView, labelView, playSound = true)
    }

    private fun animateFeedback(
        primaryView: View,
        labelView: TextView?,
        playSound: Boolean,
    ) {
        if (playSound) {
            primaryView.playSoundEffect(SoundEffectConstants.CLICK)
        }
        val highlightColor = Color.parseColor("#2F5EA6")
        listOfNotNull(primaryView, labelView).forEach { view ->
            view.animate().cancel()
            view.animate()
                .scaleX(1.04f)
                .scaleY(1.04f)
                .setDuration(90L)
                .withEndAction {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(140L)
                        .start()
                }
                .start()
            if (view is TextView) {
                val originalColor = view.currentTextColor
                val originalTypeface = view.typeface
                view.setTextColor(highlightColor)
                view.setTypeface(originalTypeface, Typeface.BOLD)
                clockDisplayHandler.postDelayed(
                    {
                        view.setTextColor(originalColor)
                        view.typeface = originalTypeface
                    },
                    260L,
                )
            }
        }
    }

    private fun animateHeaderStatusFeedback() {
        headerStatusMessageView?.let { messageView ->
            animateFeedback(messageView, null, playSound = false)
        }
    }

    private fun showToolsDialog(uiState: AndroidUiState) {
        val options =
            buildList<Pair<String, () -> Unit>> {
                add("Refresh USB Devices" to {
                    AndroidSessionController.recordStatus("USB device list refreshed.", isError = false)
                    renderContent()
                })
                add("View Android Session Log" to {
                    showLargeTextDialog(
                        title = "Android Session Log",
                        text = AndroidSessionController.debugSessionLogSummary(),
                        colorizeLogCategories = true,
                    )
                })
                add("Clear Android Session Logs" to {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Clear Android Session Logs")
                        .setMessage("Delete all current SerialSlinger log files stored by this Android app?")
                        .setPositiveButton("Delete") { _, _ ->
                            val message = AndroidSessionController.clearSessionLogs()
                            AndroidSessionController.recordStatus(message, isError = false)
                            showLargeTextDialog("Android Session Logs", message)
                        }
                        .setNegativeButton("Cancel", null)
                        .showLogged("Clear Android Session Logs")
                })
            }
        AlertDialog.Builder(this)
            .setTitle("Tools")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .setNegativeButton("Close", null)
            .showLogged("Tools")
    }

    private fun showLargeTextDialog(
        title: String,
        text: String,
        colorizeLogCategories: Boolean = false,
    ) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val textView =
            TextView(this).apply {
                this.text = if (colorizeLogCategories) buildColorizedLogText(text) else text.ifBlank { "<empty>" }
                textSize = 14f
                setTextColor(Color.parseColor("#2B2B2B"))
                setPadding(padding, padding, padding, padding)
            }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(
                ScrollView(this).apply {
                    addView(textView)
                },
            )
            .setPositiveButton("Close", null)
            .showLogged(title)
    }

    private fun buildColorizedLogText(text: String): CharSequence {
        val rendered = text.ifBlank { "<empty>" }
        val lines = rendered.split('\n')
        val builder = SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            val start = builder.length
            builder.append(line)
            val color =
                when {
                    line.contains("[${AndroidLogCategory.USER.label}]") -> Color.parseColor("#7A285B")
                    line.contains("[${AndroidLogCategory.SERIAL.label}]") -> Color.parseColor("#166534")
                    line.contains("[${AndroidLogCategory.APP.label}]") -> Color.parseColor("#0B3D91")
                    line.contains("==") -> Color.parseColor("#111827")
                    else -> null
                }
            if (color != null && line.isNotBlank()) {
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    builder.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            if (index < lines.lastIndex) {
                builder.append('\n')
            }
        }
        return builder
    }

    private fun AlertDialog.Builder.showLogged(title: String): AlertDialog = showLoggedDialog(title, create())

    private fun <T : Dialog> T.showLogged(title: String): T = showLoggedDialog(title, this)

    private fun <T : Dialog> showLoggedDialog(
        title: String,
        dialog: T,
    ): T {
        dialog.setOnShowListener {
            installWindowUserActionLogging(dialog.window, title)
        }
        dialog.show()
        return dialog
    }

    private fun installWindowUserActionLogging(
        targetWindow: Window?,
        title: String,
    ) {
        val safeWindow = targetWindow ?: return
        if (loggedWindows.containsKey(safeWindow)) {
            return
        }
        val originalCallback = safeWindow.callback ?: return
        safeWindow.callback =
            UserActionLoggingWindowCallback(
                trackedWindow = safeWindow,
                delegate = originalCallback,
            )
        loggedWindows[safeWindow] = true
    }

    private fun logWindowUserAction(
        targetWindow: Window,
        event: MotionEvent,
    ) {
        val targetView =
            findTouchedClickableView(
                view = targetWindow.decorView,
                rawX = event.rawX.toInt(),
                rawY = event.rawY.toInt(),
            ) ?: return
        val description = resolveAndroidUserActionDescription(targetView) ?: return
        AndroidSessionController.logUserAction(description)
    }

    private fun findTouchedClickableView(
        view: View,
        rawX: Int,
        rawY: Int,
    ): View? {
        if (!view.isShown || !view.containsScreenPoint(rawX, rawY)) {
            return null
        }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(index)
                val nestedMatch = findTouchedClickableView(child, rawX, rawY)
                if (nestedMatch != null) {
                    return nestedMatch
                }
            }
        }
        return if (view.hasOnClickListeners() || view.isClickable || view.isLongClickable) view else null
    }

    private fun View.containsScreenPoint(
        rawX: Int,
        rawY: Int,
    ): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        return rawX >= left && rawX < left + width && rawY >= top && rawY < top + height
    }

    private fun resolveAndroidUserActionDescription(view: View): String? {
        val explicitLabel = view.contentDescription?.toString()?.trim().takeIf { !it.isNullOrBlank() }
        if (explicitLabel != null) {
            return "Tapped $explicitLabel."
        }
        val directText =
            (view as? TextView)?.text
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotBlank)
        if (directText != null) {
            return "Tapped $directText."
        }
        val nestedText = firstTextLabel(view)
        if (nestedText != null) {
            return "Tapped $nestedText."
        }
        return null
    }

    private fun firstTextLabel(view: View): String? {
        if (view is TextView) {
            val text = view.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                return text
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val nested = firstTextLabel(view.getChildAt(index))
                if (!nested.isNullOrBlank()) {
                    return nested
                }
            }
        }
        return null
    }

    private inner class UserActionLoggingWindowCallback(
        private val trackedWindow: Window,
        private val delegate: Window.Callback,
    ) : Window.Callback by delegate {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                logWindowUserAction(trackedWindow, event)
            }
            return delegate.dispatchTouchEvent(event)
        }
    }

    private fun integerSpinner(
        values: IntRange,
        selectedValue: Int,
    ): Spinner {
        val options = values.toList()
        return Spinner(this).apply {
            adapter =
                ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    options,
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (12 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
            setBackgroundColor(Color.WHITE)
            val resolvedValue = selectedValue.coerceIn(values.first, values.last)
            setSelection(options.indexOf(resolvedValue).coerceAtLeast(0))
        }
    }

    private fun batteryThresholdOptions(): List<String> = (35..41).map { "%.1f V".format(it / 10.0) }

    private fun <T> enumSpinner(
        options: List<T>,
        selectedValue: T,
    ): Spinner {
        return Spinner(this).apply {
            adapter =
                ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    options,
                ).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (12 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
            setBackgroundColor(Color.WHITE)
            setSelection(options.indexOf(selectedValue).coerceAtLeast(0))
        }
    }

    private fun <T> dialogSelectionSpinner(
        options: List<T>,
        selectedValue: T,
        emphasized: Boolean = false,
    ): Spinner {
        return Spinner(this).apply {
            adapter =
                dialogSelectionSpinnerAdapter(options, emphasized = emphasized)
            layoutParams =
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    val horizontalMargin = (4 * resources.displayMetrics.density).toInt()
                    marginStart = horizontalMargin
                    marginEnd = horizontalMargin
                }
            setSelection(options.indexOf(selectedValue).coerceAtLeast(0))
        }
    }

    private fun <T> dialogSelectionSpinnerAdapter(
        options: List<T>,
        emphasized: Boolean,
    ): ArrayAdapter<T> =
        object : ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, options) {
            init {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return dialogSelectionSpinnerText(super.getView(position, convertView, parent), position, selected = true)
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return dialogSelectionSpinnerText(super.getDropDownView(position, convertView, parent), position, selected = false)
            }

            private fun dialogSelectionSpinnerText(view: View, position: Int, selected: Boolean): View {
                val textView = view as? TextView ?: return view
                textView.text = getItem(position)?.toString().orEmpty()
                textView.setTypeface(Typeface.DEFAULT_BOLD)
                textView.setTextColor(Color.parseColor("#1F1F1F"))
                textView.gravity = Gravity.CENTER
                textView.textSize = if (emphasized) {
                    if (selected) 32f else 28f
                } else {
                    if (selected) 20f else 18f
                }
                val verticalPadding = (8 * resources.displayMetrics.density).toInt()
                val horizontalPadding = if (emphasized) (10 * resources.displayMetrics.density).toInt() else (8 * resources.displayMetrics.density).toInt()
                textView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                if (emphasized) {
                    textView.setMinWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 78f, resources.displayMetrics).toInt())
                }
                return textView
            }
        }

    private fun wireImmediateIntSpinner(
        spinner: Spinner,
        selectedValue: Int,
        onSelected: (Int) -> Unit,
    ) {
        var ignoreInitialSelection = true
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val value = parent?.getItemAtPosition(position) as? Int ?: return
                    if (ignoreInitialSelection) {
                        ignoreInitialSelection = false
                        return
                    }
                    if (value == selectedValue) {
                        return
                    }
                    onSelected(value)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun <T> wireImmediateSelectionSpinner(
        spinner: Spinner,
        selectedValue: T,
        onSelected: (T) -> Unit,
    ) {
        var ignoreInitialSelection = true
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    @Suppress("UNCHECKED_CAST")
                    val value = parent?.getItemAtPosition(position) as? T ?: return
                    if (ignoreInitialSelection) {
                        ignoreInitialSelection = false
                        return
                    }
                    if (value == selectedValue) {
                        return
                    }
                    onSelected(value)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun addFrequencyControl(
        card: LinearLayout,
        label: String,
        selectedFrequencyHz: Long?,
        onSubmit: (Long) -> Unit,
    ) {
        var currentFrequencyHz = selectedFrequencyHz?.coerceIn(3_501_000L, 3_700_000L) ?: 3_520_000L
        lateinit var valueView: EditText
        valueView =
            pickerField(
                text = formatFrequencyForUnit(currentFrequencyHz),
                hint = label,
                actionLabel = label,
            ) {
                showFrequencyPickerDialog(
                    title = label,
                    initialFrequencyHz = currentFrequencyHz,
                    displayUnit = frequencyDisplayUnit,
                ) { chosenFrequencyHz ->
                    currentFrequencyHz = chosenFrequencyHz
                    valueView.setText(formatFrequencyForUnit(chosenFrequencyHz))
                    onSubmit(chosenFrequencyHz)
                }
            }
        val frequencyRow = compactLabeledRow(label, valueView)
        installFrequencyDisplayUnitToggle(valueView, null, frequencyRow)
        card.addView(frequencyRow)
    }

    private fun showFrequencyPickerDialog(
        title: String,
        initialFrequencyHz: Long,
        displayUnit: AndroidFrequencyDisplayUnit,
        onSelected: (Long) -> Unit,
    ) {
        val dialogHorizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val initialKhz = (initialFrequencyHz.coerceIn(3_501_000L, 3_700_000L) / 1_000L).toInt()
        val previewView =
            sectionBody("").apply {
                setTextColor(Color.parseColor("#2F5EA6"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dialogHorizontalPadding, 0, dialogHorizontalPadding, (12 * resources.displayMetrics.density).toInt())
            }
        val pickerLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dialogHorizontalPadding, 0, dialogHorizontalPadding, 0)
                addView(previewView)
            }

        val currentKhz: () -> Int

        when (displayUnit) {
            AndroidFrequencyDisplayUnit.KHZ -> {
                val hundredsOptions = listOf(5, 6, 7)
                val digitOptions = (0..9).toList()
                val hundredsSpinner = digitSpinner(hundredsOptions, (initialKhz % 1000) / 100)
                val tensSpinner = digitSpinner(digitOptions, (initialKhz / 10) % 10)
                val onesSpinner = digitSpinner(digitOptions, initialKhz % 10)
                val row =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        addView(pickerContextLabel("3", emphasized = true))
                        addView(hundredsSpinner)
                        addView(tensSpinner)
                        addView(onesSpinner)
                        addView(pickerContextLabel("kHz"))
                    }
                pickerLayout.addView(row, 0)
                currentKhz = {
                    3000 +
                        ((hundredsSpinner.selectedItem as Int) * 100) +
                        ((tensSpinner.selectedItem as Int) * 10) +
                        (onesSpinner.selectedItem as Int)
                }
                val refreshSelection = {
                    val boundedKhz = currentKhz().coerceIn(3501, 3700)
                    if (boundedKhz != currentKhz()) {
                        setSpinnerSelectionByValue(hundredsSpinner, hundredsOptions, (boundedKhz % 1000) / 100)
                        setSpinnerSelectionByValue(tensSpinner, digitOptions, (boundedKhz % 100) / 10)
                        setSpinnerSelectionByValue(onesSpinner, digitOptions, boundedKhz % 10)
                    }
                    val frequencyHz = boundedKhz * 1_000L
                    previewView.text =
                        buildString {
                            appendLine(formatFrequencyForUnit(frequencyHz))
                            append(alternateFrequencyForUnit(frequencyHz))
                        }
                }
                wireDigitSpinner(hundredsSpinner, refreshSelection)
                wireDigitSpinner(tensSpinner, refreshSelection)
                wireDigitSpinner(onesSpinner, refreshSelection)
                refreshSelection.invoke()
            }
            AndroidFrequencyDisplayUnit.MHZ -> {
                val hundredsOptions = listOf(5, 6, 7)
                val digitOptions = (0..9).toList()
                val hundredsSpinner = digitSpinner(hundredsOptions, (initialKhz % 1000) / 100)
                val tensSpinner = digitSpinner(digitOptions, (initialKhz / 10) % 10)
                val onesSpinner = digitSpinner(digitOptions, initialKhz % 10)
                val row =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        addView(
                            pickerContextLabel(
                                text = "3.",
                                emphasized = true,
                            ),
                        )
                        addView(hundredsSpinner)
                        addView(tensSpinner)
                        addView(onesSpinner)
                        addView(pickerContextLabel("MHz"))
                    }
                pickerLayout.addView(row, 0)
                currentKhz = {
                    3000 +
                        ((hundredsSpinner.selectedItem as Int) * 100) +
                        ((tensSpinner.selectedItem as Int) * 10) +
                        (onesSpinner.selectedItem as Int)
                }
                val refreshSelection = {
                    val boundedKhz = currentKhz().coerceIn(3501, 3700)
                    if (boundedKhz != currentKhz()) {
                        setSpinnerSelectionByValue(hundredsSpinner, hundredsOptions, (boundedKhz % 1000) / 100)
                        setSpinnerSelectionByValue(tensSpinner, digitOptions, (boundedKhz % 100) / 10)
                        setSpinnerSelectionByValue(onesSpinner, digitOptions, boundedKhz % 10)
                    }
                    val frequencyHz = boundedKhz * 1_000L
                    previewView.text =
                        buildString {
                            appendLine(formatFrequencyForUnit(frequencyHz))
                            append(alternateFrequencyForUnit(frequencyHz))
                        }
                }
                wireDigitSpinner(hundredsSpinner, refreshSelection)
                wireDigitSpinner(tensSpinner, refreshSelection)
                wireDigitSpinner(onesSpinner, refreshSelection)
                refreshSelection.invoke()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Pick $title")
            .setView(pickerLayout)
            .setPositiveButton("OK") { _, _ ->
                onSelected(currentKhz() * 1_000L)
            }
            .setNegativeButton("Cancel", null)
            .showLogged("Pick $title")
    }

    private fun digitSpinner(
        options: List<Int>,
        selectedValue: Int,
    ): Spinner =
        Spinner(this).apply {
            adapter = digitSpinnerAdapter(options)
            layoutParams =
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    val horizontalMargin = (4 * resources.displayMetrics.density).toInt()
                    marginStart = horizontalMargin
                    marginEnd = horizontalMargin
                }
            setSelection(options.indexOf(selectedValue).coerceAtLeast(0))
        }

    private fun digitSpinnerAdapter(options: List<Int>): ArrayAdapter<Int> =
        object : ArrayAdapter<Int>(this, android.R.layout.simple_spinner_item, options) {
            init {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return digitSpinnerText(super.getView(position, convertView, parent), position, selected = true)
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                return digitSpinnerText(super.getDropDownView(position, convertView, parent), position, selected = false)
            }

            private fun digitSpinnerText(view: View, position: Int, selected: Boolean): View {
                val textView = view as? TextView ?: return view
                textView.text = getItem(position)?.toString().orEmpty()
                textView.setTypeface(Typeface.DEFAULT_BOLD)
                textView.textSize = if (selected) 32f else 28f
                textView.setTextColor(Color.parseColor("#1F1F1F"))
                textView.gravity = Gravity.CENTER
                val verticalPadding = (8 * resources.displayMetrics.density).toInt()
                val horizontalPadding = (10 * resources.displayMetrics.density).toInt()
                textView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                return textView
            }
        }

    private fun wireDigitSpinner(
        spinner: Spinner,
        onSelected: () -> Unit,
    ) {
        var ignoreInitialSelection = true
        spinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (ignoreInitialSelection) {
                        ignoreInitialSelection = false
                        return
                    }
                    onSelected()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
    }

    private fun setSpinnerSelectionByValue(
        spinner: Spinner,
        options: List<Int>,
        value: Int,
    ) {
        val targetIndex = options.indexOf(value).coerceAtLeast(0)
        if (spinner.selectedItemPosition != targetIndex) {
            spinner.setSelection(targetIndex)
        }
    }

    private fun pickerContextLabel(
        text: String,
        emphasized: Boolean = false,
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = if (emphasized) 32f else 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(if (emphasized) Color.parseColor("#1F1F1F") else Color.parseColor("#2F5EA6"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams =
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    val horizontalMargin = (10 * resources.displayMetrics.density).toInt()
                    marginStart = horizontalMargin
                    marginEnd = horizontalMargin
                }
        }

    private fun formatFrequencyForUnit(frequencyHz: Long): String {
        return when (frequencyDisplayUnit) {
            AndroidFrequencyDisplayUnit.KHZ -> "${frequencyHz / 1_000L} kHz"
            AndroidFrequencyDisplayUnit.MHZ -> FrequencySupport.formatFrequencyMhz(frequencyHz)
        }
    }

    private fun alternateFrequencyForUnit(frequencyHz: Long): String {
        return when (frequencyDisplayUnit) {
            AndroidFrequencyDisplayUnit.KHZ -> FrequencySupport.formatFrequencyMhz(frequencyHz)
            AndroidFrequencyDisplayUnit.MHZ -> "${frequencyHz / 1_000L} kHz"
        }
    }

    private fun formatTemperatureForUnit(temperatureC: Double?): String {
        val value = temperatureC ?: return "<unknown>"
        return when (temperatureDisplayUnit) {
            AndroidTemperatureDisplayUnit.CELSIUS -> String.format("%.1f C", value)
            AndroidTemperatureDisplayUnit.FAHRENHEIT -> String.format("%.1f F", (value * 9.0 / 5.0) + 32.0)
        }
    }

    private fun formatTemperatureForDeviceData(
        temperatureC: Double?,
        supported: Boolean,
    ): String {
        return if (supported) {
            formatTemperatureForUnit(temperatureC)
        } else {
            "Not supported"
        }
    }

    private fun applyTemperatureRowAppearance(
        labelView: TextView?,
        fieldView: TextView,
        temperatureC: Double?,
    ) {
        val alertColor =
            when (TemperatureAlertSupport.alertLevel(temperatureC)) {
                TemperatureAlertLevel.NORMAL -> null
                TemperatureAlertLevel.WARNING -> Color.parseColor("#9A3412")
                TemperatureAlertLevel.DANGER -> Color.parseColor("#9E1C1C")
            }
        labelView?.setTextColor(alertColor ?: Color.parseColor("#1F1F1F"))
        fieldView.setTextColor(alertColor ?: Color.parseColor("#1F2937"))
    }

    private fun configureIndependentTextEditor(editor: EditText) {
        editor.imeOptions = EditorInfo.IME_ACTION_DONE
        editor.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dismissKeyboard(view)
                view.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun configureCommitTextEditor(
        editor: EditText,
        onCommit: () -> Unit,
    ) {
        editor.setBackgroundColor(Color.WHITE)
        configureIndependentTextEditor(editor)
        editor.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                onCommit()
            }
        }
    }

    private fun readOnlyField(
        text: String,
        textSizeSp: Float = 15f,
        singleLine: Boolean = false,
        maxLines: Int? = null,
    ): TextView =
        TextView(this).apply {
            this.text = text
            textSize = textSizeSp
            setTextColor(Color.parseColor("#1F2937"))
            if (singleLine) {
                setSingleLine(true)
            } else {
                setHorizontallyScrolling(false)
                maxLines?.let { this.maxLines = it }
            }
            val horizontalPadding = (10 * resources.displayMetrics.density).toInt()
            val verticalPadding = (8 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
        }

    private fun pickerField(
        text: String,
        hint: String,
        textSizeSp: Float = 15f,
        emphasizedInputStyle: Boolean = true,
        actionLabel: String = hint.ifBlank { text },
        isEnabledForInteraction: Boolean = true,
        onPick: () -> Unit,
    ): EditText =
        EditText(this).apply {
            setText(text)
            this.hint = hint
            contentDescription = actionLabel
            textSize = textSizeSp
            inputType = InputType.TYPE_NULL
            isFocusable = false
            isFocusableInTouchMode = false
            isCursorVisible = false
            setSingleLine()
            applyPickerFieldPresentation(
                emphasizedInputStyle = emphasizedInputStyle,
                enabledForInteraction = isEnabledForInteraction,
            )
            setOnClickListener { onPick() }
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
        }

    private fun EditText.applyPickerFieldPresentation(
        emphasizedInputStyle: Boolean,
        enabledForInteraction: Boolean = true,
    ) {
        val horizontalPadding = (10 * resources.displayMetrics.density).toInt()
        val verticalPadding = (8 * resources.displayMetrics.density).toInt()
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        isEnabled = enabledForInteraction
        isClickable = enabledForInteraction
        if (enabledForInteraction && emphasizedInputStyle) {
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.parseColor("#1F2937"))
            alpha = 1f
        } else if (enabledForInteraction) {
            background = null
            setTextColor(Color.parseColor("#4B5563"))
            alpha = 1f
        } else {
            if (emphasizedInputStyle) {
                setBackgroundColor(Color.parseColor("#E5E7EB"))
            } else {
                background = null
            }
            setTextColor(Color.parseColor("#9CA3AF"))
            alpha = 1f
        }
    }

    private fun deviceTimeRowLabel(): String {
        return if (deviceTimeSetMode == AndroidDeviceTimeSetMode.AUTOMATIC) {
            "Device Time (Tap to Sync)"
        } else {
            "Device Time (Manual)"
        }
    }

    private fun timestampFieldTextSizeSp(): Float {
        return if (deviceDataVisible) 14f else 15f
    }

    private fun dismissKeyboard(view: View) {
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun pickDateTime(
        initialValue: String,
        defaultValue: LocalDateTime = LocalDateTime.now().withSecond(0).withNano(0),
        onCanceled: () -> Unit = {},
        onSelected: (LocalDateTime) -> Unit,
    ) {
        val initialDateTime = parseDateTimeInput(initialValue) ?: defaultValue
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selectedDate = initialDateTime.withYear(year).withMonth(month + 1).withDayOfMonth(dayOfMonth)
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        onSelected(selectedDate.withHour(hourOfDay).withMinute(minute).withSecond(0).withNano(0))
                    },
                    initialDateTime.hour,
                    initialDateTime.minute,
                    true,
                ).apply {
                    setOnCancelListener { onCanceled() }
                }.showLogged("Pick Time")
            },
            initialDateTime.year,
            initialDateTime.monthValue - 1,
            initialDateTime.dayOfMonth,
        ).apply {
            setOnCancelListener { onCanceled() }
        }.showLogged("Pick Date")
    }

    private fun pickValidatedStartTime(
        initialValue: String,
        currentTimeCompact: String?,
        onCanceled: () -> Unit,
        onAdjustedToMinimum: (String) -> Unit,
        onSelected: (String) -> Unit,
    ) {
        pickDateTime(
            initialValue = initialValue,
            onCanceled = onCanceled,
        ) { selected ->
            val selectedDisplayTimestamp = formatDisplayTimestamp(selected)
            val selectedCompact = JvmTimeSupport.parseOptionalCompactTimestamp(selectedDisplayTimestamp) ?: return@pickDateTime

            val normalizedSelection = try {
                JvmTimeSupport.normalizeStartTimeForChange(
                    startTimeCompact = selectedCompact,
                    currentTimeCompact = currentTimeCompact,
                    stepMinutes = 5,
                )
            } catch (exception: Exception) {
                showStartTimeValidationDialog(
                    message = exception.message ?: "Invalid Start Time.",
                    onDismiss = onCanceled,
                )
                return@pickDateTime
            }

            val normalizedStart = normalizedSelection.startTimeCompact ?: return@pickDateTime
            if (normalizedSelection.wasAdjustedToMinimum) {
                val normalizedDisplay = JvmTimeSupport.formatCompactTimestamp(normalizedStart)
                showStartTimeValidationDialog(
                    message = JvmTimeSupport.startTimeBeforeDeviceTimeMessage(),
                    onDismiss = {
                        onAdjustedToMinimum(normalizedDisplay)
                        pickValidatedStartTime(
                            initialValue = normalizedDisplay,
                            currentTimeCompact = currentTimeCompact,
                            onCanceled = onCanceled,
                            onAdjustedToMinimum = onAdjustedToMinimum,
                            onSelected = onSelected,
                        )
                    },
                )
                return@pickDateTime
            }

            onSelected(normalizedStart)
        }
    }

    private fun showStartTimeValidationDialog(
        message: String,
        onDismiss: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle("Start Time")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                onDismiss()
            }
            .setOnCancelListener { onDismiss() }
            .showLogged("Start Time")
    }

    private fun showRelativeTimePickerDialog(
        title: String,
        initialSelection: RelativeTimeSelection,
        onSelected: (RelativeTimeSelection) -> Unit,
    ) {
        val dialogHorizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val previewView =
            sectionBody("").apply {
                setTextColor(Color.parseColor("#2F5EA6"))
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dialogHorizontalPadding, 0, dialogHorizontalPadding, (12 * resources.displayMetrics.density).toInt())
            }
        val pickerLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dialogHorizontalPadding, 0, dialogHorizontalPadding, 0)
                addView(previewView)
            }

        val hourDigits = initialSelection.hours.coerceIn(0, 480).toString().padStart(3, '0')
        val hundredsSpinner = digitSpinner((0..4).toList(), hourDigits[0].digitToInt())
        val tensSpinner = digitSpinner((0..9).toList(), hourDigits[1].digitToInt())
        val onesSpinner = digitSpinner((0..9).toList(), hourDigits[2].digitToInt())
        val minuteOptions = listOf("TOTH") + (0..55 step 5).map { it.toString().padStart(2, '0') }
        val initialMinuteOption = if (initialSelection.useTopOfHour) "TOTH" else initialSelection.minutes.toString().padStart(2, '0')
        val minuteSpinner = dialogSelectionSpinner(minuteOptions, initialMinuteOption, emphasized = true)

        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(pickerContextLabel("+", emphasized = true))
                addView(hundredsSpinner)
                addView(tensSpinner)
                addView(onesSpinner)
                addView(pickerContextLabel("h", emphasized = true))
                addView(minuteSpinner)
                addView(pickerContextLabel("m", emphasized = true))
            }
        pickerLayout.addView(row, 0)

        val currentSelection: () -> RelativeTimeSelection = {
            val rawHours =
                ((hundredsSpinner.selectedItem as Int) * 100) +
                    ((tensSpinner.selectedItem as Int) * 10) +
                    (onesSpinner.selectedItem as Int)
            val boundedHours = rawHours.coerceIn(0, 480)
            if (boundedHours != rawHours) {
                val boundedText = boundedHours.toString().padStart(3, '0')
                setSpinnerSelectionByValue(hundredsSpinner, (0..4).toList(), boundedText[0].digitToInt())
                setSpinnerSelectionByValue(tensSpinner, (0..9).toList(), boundedText[1].digitToInt())
                setSpinnerSelectionByValue(onesSpinner, (0..9).toList(), boundedText[2].digitToInt())
            }
            val minuteChoice = minuteSpinner.selectedItem as String
            RelativeTimeSelection(
                hours = boundedHours,
                minutes = if (minuteChoice == "TOTH") 0 else minuteChoice.toInt(),
                useTopOfHour = minuteChoice == "TOTH",
            )
        }

        val refreshSelection = {
            previewView.text = formatRelativeTimeSelection(currentSelection())
        }
        wireDigitSpinner(hundredsSpinner, refreshSelection)
        wireDigitSpinner(tensSpinner, refreshSelection)
        wireDigitSpinner(onesSpinner, refreshSelection)
        wireImmediateSelectionSpinner(minuteSpinner, initialMinuteOption) { _ -> refreshSelection() }
        refreshSelection.invoke()
        pickerLayout.addView(sectionBody("TOTH = Top of the Hour"))

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(pickerLayout)
            .setPositiveButton("OK") { _, _ ->
                onSelected(currentSelection())
            }
            .setNegativeButton("Cancel", null)
            .showLogged(title)
    }

    private fun parseDateTimeInput(value: String): LocalDateTime? {
        val compactTimestamp = runCatching { JvmTimeSupport.parseOptionalCompactTimestamp(value) }.getOrNull() ?: return null
        return runCatching { JvmTimeSupport.parseCompactTimestamp(compactTimestamp) }.getOrNull()
    }

    private fun formatDisplayTimestamp(value: LocalDateTime): String {
        return JvmTimeSupport.formatCompactTimestamp(JvmTimeSupport.formatCompactTimestamp(value))
    }

    private fun saveDiagnosticsExpandedPreference() {
        uiPreferences.edit().putBoolean(PREF_DIAGNOSTICS_EXPANDED, developerDiagnosticsExpanded).apply()
    }

    private fun saveRawSerialVisiblePreference() {
        uiPreferences.edit().putBoolean(PREF_RAW_SERIAL_VISIBLE, rawSerialVisible).apply()
    }

    private fun saveSystemTimeVisiblePreference() {
        uiPreferences.edit().putBoolean(PREF_SYSTEM_TIME_VISIBLE, systemTimeVisible).apply()
    }

    private fun saveDeviceDataVisiblePreference() {
        uiPreferences.edit().putBoolean(PREF_DEVICE_DATA_VISIBLE, deviceDataVisible).apply()
    }

    private fun saveLoggingToolsVisiblePreference() {
        uiPreferences.edit().putBoolean(PREF_LOGGING_TOOLS_VISIBLE, loggingToolsVisible).apply()
    }

    private fun saveScheduleTimeInputModePreference() {
        uiPreferences.edit().putString(PREF_SCHEDULE_TIME_INPUT_MODE, scheduleTimeInputMode.name).apply()
    }

    private fun saveDefaultEventLengthPreference() {
        uiPreferences.edit().putInt(PREF_DEFAULT_EVENT_LENGTH_MINUTES, defaultEventLengthMinutes.coerceIn(10, 24 * 60)).apply()
    }

    private fun relativeEditorSelectionForStart(
        baseCompact: String?,
        targetCompact: String?,
    ): RelativeTimeSelection {
        return relativeStartDisplaySelectionOverride ?: deriveRelativeTimeSelection(baseCompact, targetCompact)
    }

    private fun relativeEditorSelectionForFinish(): RelativeTimeSelection {
        return relativeFinishDisplaySelectionOverride ?: defaultEventLengthRelativeSelection()
    }

    private fun defaultEventLengthRelativeSelection(): RelativeTimeSelection {
        return RelativeScheduleSupport.selectionForDuration(defaultEventLengthMinutes).toAndroidSelection()
    }

    private fun relativeTimeSelectionForDuration(duration: Duration): RelativeTimeSelection {
        return RelativeScheduleSupport.selectionForDuration(duration).toAndroidSelection()
    }

    private fun clearRelativeScheduleDisplayOverrides() {
        relativeStartDisplaySelectionOverride = null
        relativeFinishDisplaySelectionOverride = null
    }

    private fun chooseStartTimeFinishAdjustmentDuration(
        currentStartTimeCompact: String?,
        currentFinishTimeCompact: String?,
        proposedStartTimeCompact: String?,
        onCancel: () -> Unit,
        onSelected: (StartTimeFinishAdjustmentChoice) -> Unit,
    ) {
        val plannedOptions = StartTimeAdjustmentPlanner.plan(
            currentStartTimeCompact = currentStartTimeCompact,
            currentFinishTimeCompact = currentFinishTimeCompact,
            proposedStartTimeCompact = proposedStartTimeCompact,
            defaultEventLengthMinutes = defaultEventLengthMinutes,
        )
        if (plannedOptions.size == 1) {
            runAfterModalUiSettles {
                onSelected(plannedOptions.single().toAndroidStartTimeFinishAdjustmentChoice())
            }
            return
        }
        val options = plannedOptions.map { it.toAndroidStartTimeFinishAdjustmentChoice() }
        if (options.isEmpty()) {
            runAfterModalUiSettles {
                onSelected(
                    StartTimeAdjustmentOption(
                        kind = StartTimeAdjustmentOptionKind.ADJUST_FOR_DEFAULT_DURATION,
                        duration = Duration.ofMinutes(defaultEventLengthMinutes.toLong()),
                    ).toAndroidStartTimeFinishAdjustmentChoice(),
                )
            }
            return
        }
        runAfterModalUiSettles {
            if (startTimeFinishAdjustmentDialogOpen) {
                return@runAfterModalUiSettles
            }
            startTimeFinishAdjustmentDialogOpen = true
            var actionHandled = false
            var dialog: AlertDialog? = null

            fun completeSelection(choice: StartTimeFinishAdjustmentChoice) {
                if (actionHandled) {
                    return
                }
                actionHandled = true
                startTimeFinishAdjustmentDialogOpen = false
                dialog?.dismiss()
                runAfterModalUiSettles {
                    onSelected(choice)
                }
            }

            fun completeCancel() {
                if (actionHandled) {
                    return
                }
                actionHandled = true
                startTimeFinishAdjustmentDialogOpen = false
                dialog?.dismiss()
                runAfterModalUiSettles {
                    onCancel()
                }
            }

            val contentView =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val horizontalPadding = (20 * resources.displayMetrics.density).toInt()
                    val verticalPadding = (12 * resources.displayMetrics.density).toInt()
                    setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "Choose desired event duration:"
                            textSize = 15f
                            val bottomPadding = (12 * resources.displayMetrics.density).toInt()
                            setPadding(0, 0, 0, bottomPadding)
                        },
                    )
                    options.forEach { option ->
                        addView(
                            Button(this@MainActivity).apply {
                                text = option.label
                                isAllCaps = false
                                setOnClickListener {
                                    completeSelection(option)
                                }
                            },
                        )
                    }
                }

            dialog = AlertDialog.Builder(this)
                .setTitle("Adjust Finish Time")
                .setView(contentView)
                .setNegativeButton("Cancel") { _, _ ->
                    completeCancel()
                }
                .setOnCancelListener {
                    completeCancel()
                }
                .showLogged("Adjust Finish Time")
        }
    }

    private fun StartTimeAdjustmentOption.toAndroidStartTimeFinishAdjustmentChoice(): StartTimeFinishAdjustmentChoice {
        return when (kind) {
            StartTimeAdjustmentOptionKind.KEEP_EXISTING_DURATION -> StartTimeFinishAdjustmentChoice(
                label = "Keep ${JvmTimeSupport.formatDurationCompact(duration!!)}",
                duration = duration,
            )
            StartTimeAdjustmentOptionKind.ADJUST_FOR_DEFAULT_DURATION -> StartTimeFinishAdjustmentChoice(
                label = "Adjust for ${formatDefaultEventLength(defaultEventLengthMinutes)}",
                duration = duration,
            )
            StartTimeAdjustmentOptionKind.LEAVE_FINISH_UNCHANGED -> StartTimeFinishAdjustmentChoice(
                label = "Leave Finish unchanged (${JvmTimeSupport.formatDurationCompact(duration!!)})",
                duration = duration,
            )
            StartTimeAdjustmentOptionKind.DISABLE_EVENT -> StartTimeFinishAdjustmentChoice(
                label = "Disable Event",
                disablesEvent = true,
            )
        }
    }

    private fun chooseStartTimeDaysToRunHandling(
        currentDaysToRun: Int,
        onCancel: () -> Unit,
        onSelected: (StartTimeDaysToRunChoice) -> Unit,
    ) {
        val plan = StartTimeDaysToRunPlanner.plan(currentDaysToRun)
        val autoChoice = plan.autoChoice
        if (autoChoice != null) {
            runAfterModalUiSettles {
                onSelected(autoChoice)
            }
            return
        }
        val options = plan.options
        if (options.isEmpty()) {
            runAfterModalUiSettles {
                onSelected(StartTimeDaysToRunChoice.RESET)
            }
            return
        }
        var actionHandled = false
        var dialog: AlertDialog? = null

        fun completeSelection(choice: StartTimeDaysToRunChoice) {
            if (actionHandled) {
                return
            }
            actionHandled = true
            dialog?.dismiss()
            runAfterModalUiSettles {
                onSelected(choice)
            }
        }

        fun completeCancel() {
            if (actionHandled) {
                return
            }
            actionHandled = true
            dialog?.dismiss()
            runAfterModalUiSettles {
                onCancel()
            }
        }

        val contentView =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val horizontalPadding = (20 * resources.displayMetrics.density).toInt()
                val verticalPadding = (12 * resources.displayMetrics.density).toInt()
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, 0)
                addView(
                    TextView(this@MainActivity).apply {
                        text = "Choose Days To Run handling:"
                        textSize = 15f
                        val bottomPadding = (12 * resources.displayMetrics.density).toInt()
                        setPadding(0, 0, 0, bottomPadding)
                    },
                )
                options.forEach { option ->
                    addView(
                        Button(this@MainActivity).apply {
                            text = option.label
                            isAllCaps = false
                            setOnClickListener {
                                completeSelection(option.choice)
                            }
                        },
                    )
                }
            }

        dialog = AlertDialog.Builder(this)
            .setTitle("Days To Run")
            .setView(contentView)
            .setNegativeButton("Cancel") { _, _ ->
                completeCancel()
            }
            .setOnCancelListener {
                completeCancel()
            }
            .showLogged("Days To Run")
    }

    private fun chooseMultiDayDurationGuardHandling(
        options: List<MultiDayDurationGuardOption>,
        onCancel: () -> Unit,
        onSelected: (MultiDayDurationGuardOption?) -> Unit,
    ) {
        if (options.isEmpty()) {
            onSelected(null)
            return
        }
        multiDayDurationGuardDialogOpen = true
        AlertDialog.Builder(this)
            .setTitle("Invalid Multi-Day Event")
            .setMessage("Multi-day events must last less than 24 hours. Choose how to proceed:")
            .setItems(options.map { it.label }.toTypedArray()) { dialog, which ->
                multiDayDurationGuardDialogOpen = false
                dialog.dismiss()
                runAfterModalUiSettles {
                    onSelected(options[which])
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                multiDayDurationGuardDialogOpen = false
                dialog.dismiss()
                runAfterModalUiSettles {
                    onCancel()
                }
            }
            .setOnCancelListener {
                multiDayDurationGuardDialogOpen = false
                runAfterModalUiSettles {
                    onCancel()
                }
            }
            .showLogged("Invalid Multi-Day Event")
    }

    private fun resolveMultiDayDurationGuardForScheduleChange(
        currentDaysToRun: Int,
        daysChoice: StartTimeDaysToRunChoice,
        proposedDuration: Duration?,
        onCancel: () -> Unit,
        onResolved: (preserveDaysToRun: Boolean, resultingDuration: Duration?) -> Unit,
    ) {
        val options = ScheduleDurationGuardSupport.planForScheduleChange(
            currentDaysToRun = currentDaysToRun,
            daysChoice = daysChoice,
            proposedDuration = proposedDuration,
        )
        chooseMultiDayDurationGuardHandling(
            options = options,
            onCancel = onCancel,
        ) { option ->
            val resolution = ScheduleDurationGuardSupport.resolveScheduleChange(
                currentDaysToRun = currentDaysToRun,
                daysChoice = daysChoice,
                proposedDuration = proposedDuration,
                selectedOption = option,
            )
            onResolved(resolution.preserveDaysToRun, resolution.resultingDuration)
        }
    }

    private fun chooseScheduleChangeDurationResolution(
        currentDaysToRun: Int,
        proposedDuration: Duration?,
        onCancel: () -> Unit,
        onResolved: (preserveDaysToRun: Boolean, effectiveDuration: Duration?) -> Unit,
    ) {
        chooseStartTimeDaysToRunHandling(
            currentDaysToRun = currentDaysToRun,
            onCancel = onCancel,
        ) { daysChoice ->
            resolveMultiDayDurationGuardForScheduleChange(
                currentDaysToRun = currentDaysToRun,
                daysChoice = daysChoice,
                proposedDuration = proposedDuration,
                onCancel = onCancel,
            ) { preserveDaysToRun, resolvedDuration ->
                onResolved(preserveDaysToRun, resolvedDuration ?: proposedDuration)
            }
        }
    }

    private fun runAfterModalUiSettles(action: () -> Unit) {
        content.post {
            content.post {
                action()
            }
        }
    }

    private fun currentFinishTimeCompactForStartAdjustment(
        editorText: String,
        originalFinishTimeCompact: String?,
    ): String? {
        if (scheduleTimeInputMode != AndroidScheduleTimeInputMode.ABSOLUTE) {
            return originalFinishTimeCompact
        }
        return parseDateTimeInput(editorText)?.let(JvmTimeSupport::formatCompactTimestamp) ?: originalFinishTimeCompact
    }

    private fun validateDefaultEventLengthMinutes(minutes: Int): Int {
        return RelativeScheduleSupport.validateDefaultEventLengthMinutes(minutes)
    }

    private fun formatDefaultEventLength(minutes: Int): String {
        return RelativeScheduleSupport.formatDefaultEventLength(minutes)
    }

    private fun deriveRelativeTimeSelection(
        baseCompact: String?,
        targetCompact: String?,
    ): RelativeTimeSelection {
        return RelativeScheduleSupport.deriveSelection(baseCompact, targetCompact).toAndroidSelection()
    }

    private fun formatRelativeTimeSelection(selection: RelativeTimeSelection): String {
        return RelativeScheduleSupport.formatSelection(selection.toSharedSelection())
    }

    private fun formatRelativeTimeCommand(selection: RelativeTimeSelection): String {
        return RelativeScheduleSupport.formatCommand(selection.toSharedSelection())
    }

    private fun saveFrequencyDisplayUnitPreference() {
        uiPreferences.edit().putString(PREF_FREQUENCY_DISPLAY_UNIT, frequencyDisplayUnit.name).apply()
    }

    private fun saveTemperatureDisplayUnitPreference() {
        uiPreferences.edit().putString(PREF_TEMPERATURE_DISPLAY_UNIT, temperatureDisplayUnit.name).apply()
    }

    private fun saveDeviceTimeSetModePreference() {
        uiPreferences.edit().putString(PREF_DEVICE_TIME_SET_MODE, deviceTimeSetMode.name).apply()
    }

    private fun cardLayout(): LinearLayout {
        val cardPadding = (12 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPadding, cardPadding, cardPadding, cardPadding)
            setBackgroundColor(0xFFF6F3EA.toInt())
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (12 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
        }
    }

    private fun fieldLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 14f
            val bottomPadding = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, bottomPadding)
        }

    private fun disclosureButton(
        label: String,
        expanded: Boolean,
        onClick: () -> Unit,
    ): Button = rowButton(if (expanded) "Hide $label" else "Show $label", onClick)

    private fun formatEventTypeLabel(eventType: EventType): String =
        eventType.name.lowercase().replaceFirstChar { it.uppercase() }

    private fun rowButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            contentDescription = text
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
            }
            setOnClickListener { onClick() }
        }

    private fun weightedButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            contentDescription = text
            layoutParams =
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                    val horizontalMargin = (4 * resources.displayMetrics.density).toInt()
                    this.marginEnd = horizontalMargin
                }
            setOnClickListener { onClick() }
        }

    private fun headerCloneButton(uiState: AndroidUiState): Button =
        Button(this).apply {
            text = "Clone"
            setBackgroundColor(Color.parseColor("#1E40AF"))
            setTextColor(Color.WHITE)
            isEnabled = uiState.canClone
            alpha = if (uiState.canClone) 1f else 0.55f
            layoutParams =
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    val horizontalMargin = (8 * resources.displayMetrics.density).toInt()
                    marginStart = horizontalMargin
                    marginEnd = horizontalMargin
                }
            setOnClickListener {
                maybeRunCloneWithClockWarning(
                    uiState = AndroidSessionController.snapshotUiState(),
                    requestedDeviceName = uiState.latestLoadedDeviceName,
                )
            }
            contentDescription = "Clone timed event settings"
            installLongPressGesture(this, allowShortTap = true) {
                if (!isEnabled) {
                    return@installLongPressGesture
                }
                animateFeedback(this, null, playSound = true)
                clockDisplayHandler.postDelayed(
                    {
                        AndroidSessionController.reloadCloneTemplateFromAttachedDevice(
                            context = applicationContext,
                            requestedDeviceName = uiState.latestLoadedDeviceName,
                        )
                    },
                    220L,
                )
            }
        }

    private fun cloneButton(latestLoadedDeviceName: String?): Button =
        weightedButton("Clone") {
            maybeRunCloneWithClockWarning(
                uiState = AndroidSessionController.snapshotUiState(),
                requestedDeviceName = latestLoadedDeviceName,
            )
        }.apply {
            contentDescription = "Clone timed event settings"
            installLongPressGesture(this, allowShortTap = true) {
                animateFeedback(this, null, playSound = true)
                clockDisplayHandler.postDelayed(
                    {
                        AndroidSessionController.reloadCloneTemplateFromAttachedDevice(
                            context = applicationContext,
                            requestedDeviceName = latestLoadedDeviceName,
                        )
                    },
                    220L,
                )
            }
        }

    private fun maybeRunCloneWithClockWarning(
        uiState: AndroidUiState,
        requestedDeviceName: String?,
    ) {
        if (!uiState.hasClockPhaseWarning) {
            AndroidSessionController.runCloneTimedEventSettings(
                context = applicationContext,
                requestedDeviceName = requestedDeviceName,
            )
            return
        }

        val phaseSummary = uiState.clockPhaseErrorMillis?.let(JvmTimeSupport::formatSignedDurationMillis) ?: "unavailable"
        val padding = (16 * resources.displayMetrics.density).toInt()
        val buttonSpacing = (8 * resources.displayMetrics.density).toInt()
        lateinit var dialog: AlertDialog
        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, 0)
                addView(
                    TextView(this@MainActivity).apply {
                        text =
                            "Device Time differs noticeably from Android system time.\n\n" +
                                "Measured phase error: $phaseSummary\n\n" +
                                "Syncing the clock before cloning is strongly recommended."
                        textSize = 15f
                        setTextColor(Color.parseColor("#1F2937"))
                    },
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, padding, 0, 0)
                        addView(
                            Button(this@MainActivity).apply {
                                text = "Continue Clone"
                                setOnClickListener {
                                    dialog.dismiss()
                                    AndroidSessionController.runCloneTimedEventSettings(
                                        context = applicationContext,
                                        requestedDeviceName = requestedDeviceName,
                                    )
                                }
                            },
                            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                                bottomMargin = buttonSpacing
                            },
                        )
                        addView(
                            Button(this@MainActivity).apply {
                                text = "Sync then Clone"
                                setOnClickListener {
                                    dialog.dismiss()
                                    AndroidSessionController.runCurrentTimeSystemSync(
                                        context = applicationContext,
                                        requestedDeviceName = requestedDeviceName,
                                    ) { result ->
                                        if (result.isSuccess) {
                                            AndroidSessionController.runCloneTimedEventSettings(
                                                context = applicationContext,
                                                requestedDeviceName = requestedDeviceName,
                                            )
                                        }
                                    }
                                }
                            },
                            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                                bottomMargin = buttonSpacing
                            },
                        )
                        addView(
                            Button(this@MainActivity).apply {
                                text = "Cancel"
                                setOnClickListener {
                                    dialog.dismiss()
                                }
                            },
                            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
                        )
                    },
                )
            }

        dialog =
            AlertDialog.Builder(this)
                .setTitle("Device Time Warning")
                .setView(container)
                .create()
        dialog.showLogged("Device Time Warning")
    }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 18f
            val bottomPadding = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, bottomPadding, 0, bottomPadding)
        }

    private fun sectionBody(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            val bottomPadding = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, bottomPadding)
        }

    private fun statusView(
        text: String,
        isError: Boolean,
        onClick: (() -> Unit)? = null,
    ): TextView =
        TextView(this).apply {
            this.text = text
            contentDescription = text
            textSize = 15f
            setTextColor(if (isError) Color.parseColor("#9E1C1C") else Color.parseColor("#1F5F2C"))
            if (onClick != null) {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            val bottomPadding = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, bottomPadding)
        }

    private fun updateThermalHeaderWarning(currentTemperatureC: Double?) {
        when (TemperatureAlertSupport.alertLevel(currentTemperatureC)) {
            TemperatureAlertLevel.DANGER -> {
                thermalHeaderWarningText = "High Temperature Warning"
                thermalHeaderWarningDetail =
                    "Current temperature ${formatTemperatureForUnit(currentTemperatureC)}. Reduce device temperature before continuing."
            }
            TemperatureAlertLevel.NORMAL,
            TemperatureAlertLevel.WARNING,
            -> {
                if (currentTemperatureC != null) {
                    thermalHeaderWarningText = null
                    thermalHeaderWarningDetail = null
                }
            }
        }
    }

    private fun calloutView(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#6F4E00"))
            setBackgroundColor(0xFFFFF4D6.toInt())
            val horizontalPadding = (12 * resources.displayMetrics.density).toInt()
            val verticalPadding = (10 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
        }

    private fun compactAlertRow(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#9E1C1C"))
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    val bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    this.bottomMargin = bottomMargin
                }
        }

    private fun Int.toHex(): String = toString(16).uppercase().padStart(4, '0')

    private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "<unknown>"

    private inline fun <reified T> Intent.getParcelableExtraCompat(name: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.SerialSlinger.openardf.USB_PERMISSION"
        private const val CUSTOM_LONG_PRESS_TIMEOUT_MS = 1_200L
        private const val PREFS_NAME = "serialslinger_android_ui"
        private const val PREF_DIAGNOSTICS_EXPANDED = "diagnostics_expanded"
        private const val PREF_RAW_SERIAL_VISIBLE = "raw_serial_visible"
        private const val PREF_SYSTEM_TIME_VISIBLE = "system_time_visible"
        private const val PREF_DEVICE_DATA_VISIBLE = "device_data_visible"
        private const val PREF_LOGGING_TOOLS_VISIBLE = "logging_tools_visible"
        private const val PREF_SCHEDULE_TIME_INPUT_MODE = "schedule_time_input_mode"
        private const val PREF_DEFAULT_EVENT_LENGTH_MINUTES = "default_event_length_minutes"
        private const val PREF_FREQUENCY_DISPLAY_UNIT = "frequency_display_unit"
        private const val PREF_TEMPERATURE_DISPLAY_UNIT = "temperature_display_unit"
        private const val PREF_DEVICE_TIME_SET_MODE = "device_time_set_mode"
        private const val AUTO_DETECT_ATTACH_DELAY_MS = 180L
        private const val AUTO_DETECT_RETRY_DELAY_MS = 350L
        private const val AUTO_DETECT_MAX_RETRIES = 4
    }
}
