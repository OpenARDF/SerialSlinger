package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.ConnectionState
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.DeviceSnapshot
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.model.MultiDayDurationGuardChoice
import com.openardf.serialslinger.model.MultiDayDurationGuardOption
import com.openardf.serialslinger.model.MultiDayDurationGuardPlanner
import com.openardf.serialslinger.model.ScheduleDurationGuardSupport
import com.openardf.serialslinger.model.ScheduleSubmitSupport
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.SettingsField
import com.openardf.serialslinger.model.StartTimeAdjustmentOption
import com.openardf.serialslinger.model.StartTimeAdjustmentOptionKind
import com.openardf.serialslinger.model.StartTimeAdjustmentPlanner
import com.openardf.serialslinger.model.StartTimeDaysToRunChoice
import com.openardf.serialslinger.model.StartTimeDaysToRunPlanner
import com.openardf.serialslinger.model.TemperatureAlertLevel
import com.openardf.serialslinger.model.TemperatureAlertSupport
import com.openardf.serialslinger.model.WritePlan
import com.openardf.serialslinger.model.WritePlanner
import com.openardf.serialslinger.protocol.SignalSlingerProtocolCodec
import com.openardf.serialslinger.session.DeviceLoadResult
import com.openardf.serialslinger.session.DeviceSessionController
import com.openardf.serialslinger.session.DeviceSessionState
import com.openardf.serialslinger.session.DeviceSessionWorkflow
import com.openardf.serialslinger.session.DeviceSubmitResult
import com.openardf.serialslinger.session.SerialTraceDirection
import com.openardf.serialslinger.session.SerialTraceEntry
import com.openardf.serialslinger.session.SettingVerification
import com.openardf.serialslinger.transport.DesktopSerialPortInfo
import com.openardf.serialslinger.transport.DesktopSerialTransport
import java.awt.Desktop
import java.awt.Dialog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.net.URI
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import javax.imageio.ImageIO
import javax.swing.border.Border
import javax.swing.AbstractButton
import javax.swing.BoundedRangeModel
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Box
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.JSpinner
import javax.swing.JFormattedTextField
import javax.swing.JEditorPane
import javax.swing.JRadioButtonMenuItem
import javax.swing.JProgressBar
import javax.swing.SpinnerNumberModel
import javax.swing.JTextField
import javax.swing.JTextPane
import javax.swing.UIManager
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.SpinnerDateModel
import javax.swing.border.TitledBorder
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

fun main() {
    System.setProperty("apple.laf.useScreenMenuBar", shouldUseMacScreenMenuBar().toString())
    System.setProperty("apple.awt.application.name", "SerialSlinger")
    System.setProperty("com.apple.mrj.application.apple.menu.about.name", "SerialSlinger")
    SwingUtilities.invokeLater {
        SerialSlingerDesktopFrame().isVisible = true
    }
}

private fun shouldUseMacScreenMenuBar(): Boolean {
    // Keep the menu attached to the app window so it is discoverable in both
    // shell-launched and bundled desktop runs.
    return false
}

private object SerialSlingerAppVersion {
    const val value = SerialSlingerVersion.displayVersion
}

private object SerialSlingerAppIcon {
    private const val resourcePath = "/icons/serialslinger-icon-256.png"

    val image by lazy {
        SerialSlingerAppIcon::class.java.getResourceAsStream(resourcePath)?.use(ImageIO::read)
    }

    fun install(frame: JFrame) {
        val loadedImage = image ?: return
        frame.iconImage = loadedImage

        runCatching {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar.getTaskbar().iconImage = loadedImage
            }
        }
    }
}

private data class StartTimeFinishAdjustmentChoice(
    val label: String,
    val duration: Duration? = null,
    val disablesEvent: Boolean = false,
)

private class FieldAwareSpinnerDateModel(
    initialValue: Date,
    private val defaultCalendarField: Int,
    private val selectedCalendarFieldProvider: () -> Int,
    private val steppingProvider: (LocalDateTime, Int, Boolean) -> LocalDateTime?,
) : SpinnerDateModel(initialValue, null, null, defaultCalendarField) {
    override fun getNextValue(): Any {
        return steppedValue(forward = true)
    }

    override fun getPreviousValue(): Any {
        return steppedValue(forward = false)
    }

    private fun steppedValue(forward: Boolean): Date {
        val currentDate = value as? Date ?: return initialDate()
        val currentDateTime = currentDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
        val stepped = steppingProvider(
            currentDateTime,
            selectedCalendarFieldProvider(),
            forward,
        ) ?: currentDateTime
        return Date.from(stepped.atZone(ZoneId.systemDefault()).toInstant())
    }

    private fun initialDate(): Date {
        val now = LocalDateTime.now()
        return Date.from(DesktopInputSupport.truncateToMinute(now).atZone(ZoneId.systemDefault()).toInstant())
    }
}

private class SerialSlingerDesktopFrame : JFrame("SerialSlinger ${SerialSlingerAppVersion.value}") {
    private enum class ScheduleTimeField {
        START,
        FINISH,
    }

    private enum class ClockWarningChoice {
        CANCEL,
        SYNC_THEN_CONTINUE,
        CONTINUE,
    }

    private val cloneAccentColor = Color(0x1E, 0x40, 0xAF)
    private val cloneAccentBorderColor = Color(0x93, 0xC5, 0xFD)
    private val cloneAccentBackground = Color(0xEF, 0xF6, 0xFF)
    private val applyAccentColor = Color(0x16, 0x65, 0x34)
    private val applyAccentRolloverColor = Color(0x15, 0x80, 0x3D)
    private val unreadFieldForeground = Color(0x6B, 0x72, 0x80)
    private val warningForeground = Color(0x9A, 0x34, 0x12)
    private val alertForeground = Color(0xB9, 0x1C, 0x1C)
    private val portModel = DefaultComboBoxModel<SignalSlingerPortProbe>()
    private val portComboBox = JComboBox(portModel)
    private val autoDetectButton = JButton("Auto Detect")
    private val submitButton = createAccentButton("Clone")
    private val applyButton = createAccentButton(
        title = "Apply",
        accentColor = applyAccentColor,
        rolloverColor = applyAccentRolloverColor,
    ).apply {
        isEnabled = false
    }
    private val cloneTemplateLabel = JLabel("Clone template not set")
    private val headlineLabel = JLabel(" ")
    private val statusLabel = JLabel("Idle")
    private var connectionIndicatorState: ConnectionIndicatorState = ConnectionIndicatorState.DISCONNECTED
    private var connectionIndicatorMessage: String = "Not Connected"
    private var thermalHeadlineWarningMessage: String? = null
    private var busyDialog: JDialog? = null
    private var busyDialogStatusLabel: JLabel? = null
    private var busyDialogProgressBar: JProgressBar? = null
    private var busyDialogProgressPanel: JPanel? = null
    private var busyDialogShowTimer: Timer? = null
    private var pendingImmediateEdit: PendingImmediateEdit? = null
    @Volatile private var busyProgressState: BusyProgressState? = null
    private var cachedManualWriteDelayMillis: Long? = null
    private val rawCommandField = JTextField()
    private val rawSerialRowPanel = JPanel(BorderLayout(8, 0)).apply {
        add(JLabel("Command to send:"), BorderLayout.WEST)
        add(rawCommandField, BorderLayout.CENTER)
    }
    private val logPane = JTextPane()
    private val formScroll by lazy { JScrollPane(buildFormPanel()) }
    private val logScroll by lazy {
        JScrollPane(logPane).apply {
            border = BorderFactory.createTitledBorder("Session Log")
        }
    }
    private val contentSplitPane by lazy { JSplitPane(JSplitPane.HORIZONTAL_SPLIT, formScroll, JPanel()) }

    private fun codeSpeedOptions(): List<String> = (5..20).map { DesktopInputSupport.formatCodeSpeedWpm(it) }

    private fun batteryThresholdOptions(): List<String> = (35..41).map { "%.1f V".format(it / 10.0) }

    private val stationIdField = JTextField()
    private val eventTypeCombo =
        JComboBox(DefaultComboBoxModel(DesktopInputSupport.selectableEventTypes().toTypedArray()))
    private val foxRoleCombo = JComboBox<FoxRole>()
    private val patternTextField = JTextField()
    private val idSpeedField = JComboBox(DefaultComboBoxModel(codeSpeedOptions().toTypedArray()))
    private val devicePatternSpeedLabel = JLabel("Pattern Speed")
    private val devicePatternSpeedField = JComboBox(DefaultComboBoxModel(codeSpeedOptions().toTypedArray()))
    private val timedPatternSpeedLabel = JLabel("Pattern Speed")
    private val timedPatternSpeedField = JComboBox(DefaultComboBoxModel(codeSpeedOptions().toTypedArray()))
    private val currentTimeField = JTextField()
    private val systemTimeField = JTextField()
    private val manualTimeSpinner = createDateTimeSpinner(pattern = "yyyy-MM-dd HH:mm:ss", calendarField = Calendar.SECOND)
    private val startTimeSpinner = createFieldAwareDateTimeSpinner { current, field, forward ->
        stepStartTimeSelection(current, field, forward)
    }
    private val startTimeStatusLabel = JLabel(" ")
    private val startTimeRelativeField = createRelativeScheduleField("Start Time")
    private val startTimeAbsoluteMirrorField = createReadOnlySummaryValueLabel()
    private val startTimeAbsoluteMirrorLabel = JLabel("Current setting:")
    private val startTimeEditorHost = createScheduleTimeEditorHost()
    private val finishTimeSpinner = createFieldAwareDateTimeSpinner { current, field, forward ->
        stepFinishTimeSelection(current, field, forward)
    }
    private val finishTimeStatusLabel = JLabel(" ")
    private val finishTimeRelativeField = createRelativeScheduleField("Finish Time")
    private val finishTimeAbsoluteMirrorField = createReadOnlySummaryValueLabel()
    private val finishTimeAbsoluteMirrorLabel = JLabel("Current setting:")
    private val finishTimeEditorHost = createScheduleTimeEditorHost()
    private val daysField = JSpinner(SpinnerNumberModel(1, 1, 255, 1))
    private val daysRemainingLabel = JLabel(" ")
    private val startsInField = JTextField()
    private val lastsField = JTextField()
    private val lastsRowLabel = JLabel("Lasts")
    private val disableEventButton = JButton("Disable Event")
    private val currentFrequencyField = JTextField()
    private val currentBankField = JTextField()
    private val frequency1Field = createFrequencySpinner()
    private val frequency2Field = createFrequencySpinner()
    private val frequency3Field = createFrequencySpinner()
    private val frequencyBField = createFrequencySpinner()
    private val frequency1Label = JLabel("Frequency 1")
    private val frequency2Label = JLabel("Frequency 2")
    private val frequency3Label = JLabel("Frequency 3")
    private val frequencyBLabel = JLabel("Frequency B")
    private val batteryThresholdField = JComboBox(DefaultComboBoxModel(batteryThresholdOptions().toTypedArray()))
    private val batteryModeCombo = JComboBox(DefaultComboBoxModel(ExternalBatteryControlMode.entries.toTypedArray()))
    private val transmissionsField = JTextField()
    private val versionInfoField = JTextField()
    private val internalBatteryField = JTextField()
    private val externalBatteryField = JTextField()
    private val currentTemperatureField = JTextField()
    private val minimumTemperatureField = JTextField()
    private val maximumTemperatureField = JTextField()
    private val syncTimeButton = JButton("Sync")
    private val setTimeButton = JButton("Set Time")
    private val currentTimeRowLabel = JLabel("Device Time")
    private val systemTimeRowLabel = JLabel("System Time")
    private val manualTimeRowLabel = JLabel("Set Device Time")
    private val startTimeRowLabel = JLabel("Start Time")
    private val finishTimeRowLabel = JLabel("Finish Time")
    private val currentFrequencyRowLabel = JLabel("Frequency")
    private val currentBankRowLabel = JLabel("Memory Bank")
    private val currentTemperatureRowLabel = JLabel("Current Temperature")
    private val minimumTemperatureRowLabel = JLabel("Minimum Temperature")
    private val maximumTemperatureRowLabel = JLabel("Maximum Temperature")
    private val transmissionsRowLabel = JLabel("External device being controlled")
    private val defaultRowLabelForeground = currentTimeRowLabel.foreground
    private val currentTimeRowPanel by lazy { buildCurrentTimeRow() }
    private val manualTimeRowPanel by lazy { buildManualTimeRow() }
    private val currentTimeRowSpacer = Box.createHorizontalStrut(8)
    private val startTimeAbsoluteEditorRow by lazy { buildDateTimeEditorRow(startTimeSpinner, startTimeStatusLabel) }
    private val finishTimeAbsoluteEditorRow by lazy { buildDateTimeEditorRow(finishTimeSpinner, finishTimeStatusLabel) }
    private val startTimeRelativeEditorPanel by lazy {
        buildRelativeScheduleEditorPanel(
            startTimeRelativeField,
            startTimeAbsoluteMirrorField,
            startTimeAbsoluteMirrorLabel,
        )
    }
    private val finishTimeRelativeEditorPanel by lazy {
        buildRelativeScheduleEditorPanel(
            finishTimeRelativeField,
            finishTimeAbsoluteMirrorField,
            finishTimeAbsoluteMirrorLabel,
        )
    }

    private var currentTransport: DesktopSerialTransport? = null
    private var currentState: DeviceSessionState? = null
    private var currentConnectedPortPath: String? = null
    private var loadedSnapshot: DeviceSnapshot? = null
    private var updatingForm: Boolean = false
    private var backgroundWorkInProgress: Boolean = false
    private var passiveProbeInProgress: Boolean = false
    private var logVisible: Boolean = false
    private var logAutoScroll: Boolean = true
    private var suppressLogAutoScrollTracking: Boolean = false
    private var suppressPortSelectionHandling: Boolean = false
    private var autoDetectNoDeviceFound: Boolean = false
    private var deviceTimeOffset: Duration? = null
    private var lastDeviceTimeCheckAtMs: Long = 0L
    private var consecutiveDeviceTimeCheckNoResponseCount: Int = 0
    private var cloneTemplateSettings: DeviceSettings? = null
    private var clockDisplayTimer: Timer? = null
    private var clockPhaseWarningActive: Boolean = false
    private var lastClockPhaseErrorMillis: Long? = null
    private var autoDetectButtonLongPressTimer: Timer? = null
    private var suppressNextAutoDetectAction: Boolean = false
    private var cloneButtonLongPressTimer: Timer? = null
    private var suppressNextCloneAction: Boolean = false
    private var suppressScheduleInteractionUntilMs: Long = 0L
    private var relativeStartDisplaySelectionOverride: DesktopInputSupport.RelativeTimeSelection? = null
    private var relativeFinishDisplaySelectionOverride: DesktopInputSupport.RelativeTimeSelection? = null
    private var startTimeFinishAdjustmentDialogOpen: Boolean = false
    private var startTimeDaysToRunDialogOpen: Boolean = false
    private var multiDayDurationGuardDialogOpen: Boolean = false
    private var lastsDurationDialogOpen: Boolean = false
    private var displayPreferences: DesktopDisplayPreferences = PreferencesDesktopDisplayPreferencesStore.load()
    private val knownProbeResults = linkedMapOf<String, SignalSlingerPortProbe>()
    private val portMemory: DesktopPortMemory = PreferencesDesktopPortMemory
    private val displayPreferencesStore: DesktopDisplayPreferencesStore = PreferencesDesktopDisplayPreferencesStore
    private val sessionLog = DesktopSessionLog()
    private lateinit var showLogMenuItem: JCheckBoxMenuItem
    private lateinit var showRawSerialMenuItem: JCheckBoxMenuItem
    private lateinit var frequencyKhzMenuItem: JRadioButtonMenuItem
    private lateinit var frequencyMhzMenuItem: JRadioButtonMenuItem
    private lateinit var temperatureCMenuItem: JRadioButtonMenuItem
    private lateinit var temperatureFMenuItem: JRadioButtonMenuItem
    private lateinit var timeSetAutomaticMenuItem: JRadioButtonMenuItem
    private lateinit var timeSetManualMenuItem: JRadioButtonMenuItem
    private lateinit var scheduleTimeAbsoluteMenuItem: JRadioButtonMenuItem
    private lateinit var scheduleTimeRelativeMenuItem: JRadioButtonMenuItem
    private lateinit var defaultEventLengthMenuItem: JMenuItem
    private val headerLogStyle = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, Color(0x11, 0x18, 0x27))
        StyleConstants.setBold(this, true)
    }
    private val appLogStyle = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, Color(0x0B, 0x3D, 0x91))
    }
    private val serialLogStyle = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, Color(0x16, 0x65, 0x34))
    }
    private val deviceLogStyle = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, Color(0x9A, 0x34, 0x12))
    }
    private val neutralLogStyle = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, Color(0x1F, 0x29, 0x37))
    }
    private val editableTextFieldBackground = patternTextField.background
    private val readOnlyTextFieldBackground = UIManager.getColor("TextField.inactiveBackground") ?: editableTextFieldBackground
    private val editableTextFieldBorder: Border = patternTextField.border
    private val informationalTextFieldBorder: Border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
    private val defaultInformationalFieldForeground = currentTimeField.foreground

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        minimumSize = Dimension(1200, 780)
        layout = BorderLayout(12, 12)
        jMenuBar = buildMenuBar()
        SerialSlingerAppIcon.install(this)

        add(buildHeader(), BorderLayout.NORTH)
        add(configuredContentSplitPane(), BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)

        logPane.isEditable = false
        logPane.background = Color(0xFC, 0xFC, 0xFC)
        headlineLabel.isOpaque = true
        headlineLabel.border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        cloneTemplateLabel.foreground = Color(0x55, 0x65, 0x73)
        currentTimeField.isEditable = false
        systemTimeField.isEditable = false
        currentFrequencyField.isEditable = false
        currentBankField.isEditable = false
        startsInField.isEditable = false
        lastsField.isEditable = false
        versionInfoField.isEditable = false
        internalBatteryField.isEditable = false
        externalBatteryField.isEditable = false
        currentTemperatureField.isEditable = false
        minimumTemperatureField.isEditable = false
        maximumTemperatureField.isEditable = false
        transmissionsField.isEditable = false
        configureInformationalField(currentTimeField)
        configureInformationalField(systemTimeField)
        configureInformationalField(currentFrequencyField)
        configureInformationalField(currentBankField)
        configureInformationalField(startsInField)
        configureInformationalField(lastsField)
        configureInteractiveSelectionField(lastsField)
        configureInformationalField(versionInfoField)
        configureInformationalField(internalBatteryField)
        configureInformationalField(externalBatteryField)
        configureInformationalField(currentTemperatureField)
        configureInformationalField(minimumTemperatureField)
        configureInformationalField(maximumTemperatureField)
        configureInformationalField(transmissionsField)
        configureNullableDateTimeSpinner(startTimeSpinner, startTimeStatusLabel)
        configureNullableDateTimeSpinner(finishTimeSpinner, finishTimeStatusLabel)
        configureDateTimeSpinnerArrowFieldPreservation(manualTimeSpinner)
        configureDateTimeSpinnerKeyboardFieldPreservation(manualTimeSpinner)
        setDateTimeSpinnerValue(
            manualTimeSpinner,
            DesktopInputSupport.nextSyncTargetTime(minimumLeadMillis = 2_500L),
        )
        installTrimmedComboRenderer(eventTypeCombo)
        installTrimmedComboRenderer(foxRoleCombo)
        installTrimmedComboRenderer(idSpeedField)
        installTrimmedComboRenderer(devicePatternSpeedField)
        installTrimmedComboRenderer(timedPatternSpeedField)
        installTrimmedComboRenderer(batteryThresholdField)
        installTrimmedComboRenderer(batteryModeCombo)
        spinnerEditorTextField(daysField).horizontalAlignment = JFormattedTextField.LEFT
        configureFrequencySpinner(frequency1Field)
        configureFrequencySpinner(frequency2Field)
        configureFrequencySpinner(frequency3Field)
        configureFrequencySpinner(frequencyBField)
        configureLogAutoScroll()
        showConnectionIndicator(ConnectionIndicatorState.DISCONNECTED, "Not Connected")
        appendRenderedLog(sessionLog.loadCurrentLogText())
        setRawSerialVisible(displayPreferences.rawSerialVisible)
        setLogVisible(displayPreferences.logVisible)
        applyTimeSetMode(displayPreferences.timeSetMode)
        refreshScheduleTimeEditorPresentation(loadedSnapshot)
        clearFormForUnread()
        submitButton.margin = Insets(
            autoDetectButton.margin.top,
            14,
            autoDetectButton.margin.bottom,
            14,
        )
        applyButton.margin = submitButton.margin
        val autoDetectPreferredSize = autoDetectButton.preferredSize
        val clonePreferredSize = submitButton.preferredSize
        val matchedCloneSize = Dimension(clonePreferredSize.width, autoDetectPreferredSize.height)
        submitButton.preferredSize = matchedCloneSize
        submitButton.minimumSize = matchedCloneSize
        submitButton.maximumSize = matchedCloneSize
        val applyPreferredSize = applyButton.preferredSize
        val matchedApplySize = Dimension(applyPreferredSize.width, autoDetectPreferredSize.height)
        applyButton.preferredSize = matchedApplySize
        applyButton.minimumSize = matchedApplySize
        applyButton.maximumSize = matchedApplySize

        autoDetectButton.toolTipText = "Click to scan ports. Press and hold to reload the current/selected port as the active device."
        installAutoDetectButtonLongPressHandler()
        autoDetectButton.addActionListener {
            if (suppressNextAutoDetectAction) {
                suppressNextAutoDetectAction = false
                return@addActionListener
            }
            autoDetectPorts()
        }
        submitButton.toolTipText = "Click to clone. Press and hold to reload the clone template from the attached device."
        installCloneButtonLongPressHandler()
        submitButton.addActionListener {
            if (suppressNextCloneAction) {
                suppressNextCloneAction = false
                return@addActionListener
            }
            cloneTimedEventSettings()
        }
        applyButton.toolTipText = "Make a change in a field, then click Apply."
        applyButton.addActionListener { commitFocusedEditorThenPendingImmediateEdit() }
        syncTimeButton.addActionListener { syncDeviceTimeToSystem() }
        setTimeButton.addActionListener { setDeviceTimeToSelection() }
        spinnerEditorTextField(manualTimeSpinner).addActionListener {
            if (!updatingForm) {
                setDeviceTimeToSelection()
            }
        }
        startTimeRelativeField.addActionListener { showRelativeTimePickerDialog(ScheduleTimeField.START) }
        startTimeRelativeField.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (consumeLongPressClick(startTimeRelativeField)) {
                        return
                    }
                    if (isScheduleInteractionSuppressed()) {
                        return
                    }
                    if (event.button == MouseEvent.BUTTON1 && !backgroundWorkInProgress && startTimeRelativeField.isEnabled) {
                        showRelativeTimePickerDialog(ScheduleTimeField.START)
                    }
                }
            },
        )
        finishTimeRelativeField.addActionListener { showRelativeTimePickerDialog(ScheduleTimeField.FINISH) }
        finishTimeRelativeField.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (consumeLongPressClick(finishTimeRelativeField)) {
                        return
                    }
                    if (isScheduleInteractionSuppressed()) {
                        return
                    }
                    if (event.button == MouseEvent.BUTTON1 && !backgroundWorkInProgress && finishTimeRelativeField.isEnabled) {
                        showRelativeTimePickerDialog(ScheduleTimeField.FINISH)
                    }
                }
            },
        )
        lastsField.toolTipText = "Click to choose event duration."
        lastsRowLabel.toolTipText = lastsField.toolTipText
        lastsField.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) {
                        showLastsDurationDialog()
                    }
                }
            },
        )
        lastsRowLabel.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) {
                        showLastsDurationDialog()
                    }
                }
            },
        )
        disableEventButton.addActionListener { disableProgrammedEvent() }
        rawCommandField.addActionListener { sendRawSerialCommand() }
        portComboBox.addActionListener {
            if (suppressPortSelectionHandling || backgroundWorkInProgress || updatingForm) {
                return@addActionListener
            }
            val selectedPath = selectedProbe()?.portInfo?.systemPortPath ?: return@addActionListener
            if (selectedPath == currentConnectedPortPath && currentState?.connectionState == ConnectionState.CONNECTED) {
                autoDetectNoDeviceFound = false
                showConnectionIndicator(ConnectionIndicatorState.CONNECTED, "Connected to SignalSlinger on ${selectedPath}")
                refreshAvailablePorts(silent = true)
                setStatus("Connected to ${selectedPath}.")
                return@addActionListener
            }
            autoDetectNoDeviceFound = false
            showConnectionIndicator(ConnectionIndicatorState.SEARCHING, "Connecting to ${selectedPath}...")
            connectAndLoadSelectedPort()
        }
        eventTypeCombo.addActionListener {
            if (!updatingForm) {
                updatingForm = true
                try {
                    val eventType = eventTypeCombo.selectedItem as EventType
                    syncFoxRoleOptions(eventType, foxRoleCombo.selectedItem as FoxRole?)
                    updateTimedEventFrequencyVisibility(eventType)
                } finally {
                    updatingForm = false
                }
            }
        }
        installImmediateApplyHandlers()
        installSettingsShortcutLongPressHandlers()

        Timer(3_000) { smartPollPorts() }.start()
        updateDisplayedClockFields()
        scheduleClockDisplayTick()
        refreshAvailablePorts(silent = true)
        appendLog(
            "Session Started",
            listOf(
                DesktopLogEntry("Desktop UI launched.", DesktopLogCategory.APP),
                DesktopLogEntry("SerialSlinger app version ${SerialSlingerAppVersion.value}.", DesktopLogCategory.APP),
                DesktopLogEntry("Current log file: ${sessionLog.currentLogFile()}", DesktopLogCategory.APP),
            ),
        )
    }

    private fun buildHeader(): JPanel {
        return JPanel(BorderLayout()).apply {
            add(buildToolbar(), BorderLayout.NORTH)
            add(headlineLabel, BorderLayout.SOUTH)
        }
    }

    private fun buildToolbar(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 0, 12)
            add(JLabel("Serial Port"))
            add(Box.createHorizontalStrut(8))
            add(portComboBox)
            add(Box.createHorizontalStrut(8))
            add(autoDetectButton)
            add(Box.createHorizontalStrut(8))
            add(submitButton)
            add(Box.createHorizontalStrut(12))
            add(cloneTemplateLabel)
        }
    }

    private fun buildMenuBar(): JMenuBar {
        return JMenuBar().apply {
            add(
                JMenu("Settings").apply {
                    add(
                        JMenu("Device Time Setting").apply {
                            val group = ButtonGroup()
                            timeSetAutomaticMenuItem = JRadioButtonMenuItem(
                                "Automatic",
                                displayPreferences.timeSetMode == TimeSetMode.SYSTEM_CLOCK,
                            ).apply {
                                addActionListener { setTimeSetMode(TimeSetMode.SYSTEM_CLOCK) }
                            }
                            timeSetManualMenuItem = JRadioButtonMenuItem(
                                "Manual",
                                displayPreferences.timeSetMode == TimeSetMode.MANUAL,
                            ).apply {
                                addActionListener { setTimeSetMode(TimeSetMode.MANUAL) }
                            }
                            group.add(timeSetAutomaticMenuItem)
                            group.add(timeSetManualMenuItem)
                            add(timeSetAutomaticMenuItem)
                            add(timeSetManualMenuItem)
                        },
                    )
                    add(
                        JMenu("Schedule Time Setting").apply {
                            val group = ButtonGroup()
                            scheduleTimeAbsoluteMenuItem = JRadioButtonMenuItem(
                                "Absolute",
                                displayPreferences.scheduleTimeInputMode == ScheduleTimeInputMode.ABSOLUTE,
                            ).apply {
                                addActionListener { setScheduleTimeInputMode(ScheduleTimeInputMode.ABSOLUTE) }
                            }
                            scheduleTimeRelativeMenuItem = JRadioButtonMenuItem(
                                "Relative",
                                displayPreferences.scheduleTimeInputMode == ScheduleTimeInputMode.RELATIVE,
                            ).apply {
                                addActionListener { setScheduleTimeInputMode(ScheduleTimeInputMode.RELATIVE) }
                            }
                            group.add(scheduleTimeAbsoluteMenuItem)
                            group.add(scheduleTimeRelativeMenuItem)
                            add(scheduleTimeAbsoluteMenuItem)
                            add(scheduleTimeRelativeMenuItem)
                        },
                    )
                    defaultEventLengthMenuItem = JMenuItem().apply {
                        addActionListener { showDefaultEventLengthDialog() }
                    }
                    updateDefaultEventLengthMenuItem()
                    add(defaultEventLengthMenuItem)
                    addSeparator()
                    add(
                        JMenu("Frequency Units").apply {
                            val group = ButtonGroup()
                            frequencyKhzMenuItem = JRadioButtonMenuItem(
                                "kHz",
                                displayPreferences.frequencyDisplayUnit == FrequencyDisplayUnit.KHZ,
                            ).apply {
                                addActionListener { setFrequencyDisplayUnit(FrequencyDisplayUnit.KHZ) }
                            }
                            frequencyMhzMenuItem = JRadioButtonMenuItem(
                                "MHz",
                                displayPreferences.frequencyDisplayUnit == FrequencyDisplayUnit.MHZ,
                            ).apply {
                                addActionListener { setFrequencyDisplayUnit(FrequencyDisplayUnit.MHZ) }
                            }
                            group.add(frequencyKhzMenuItem)
                            group.add(frequencyMhzMenuItem)
                            add(frequencyKhzMenuItem)
                            add(frequencyMhzMenuItem)
                        },
                    )
                    add(
                        JMenu("Temperature Units").apply {
                            val group = ButtonGroup()
                            temperatureCMenuItem = JRadioButtonMenuItem(
                                "Celsius",
                                displayPreferences.temperatureDisplayUnit == TemperatureDisplayUnit.CELSIUS,
                            ).apply {
                                addActionListener { setTemperatureDisplayUnit(TemperatureDisplayUnit.CELSIUS) }
                            }
                            temperatureFMenuItem = JRadioButtonMenuItem(
                                "Fahrenheit",
                                displayPreferences.temperatureDisplayUnit == TemperatureDisplayUnit.FAHRENHEIT,
                            ).apply {
                                addActionListener { setTemperatureDisplayUnit(TemperatureDisplayUnit.FAHRENHEIT) }
                            }
                            group.add(temperatureCMenuItem)
                            group.add(temperatureFMenuItem)
                            add(temperatureCMenuItem)
                            add(temperatureFMenuItem)
                        },
                    )
                    addSeparator()
                    add(
                        JMenuItem("About SerialSlinger").apply {
                            addActionListener { showAboutDialog() }
                        },
                    )
                },
            )
            add(
                JMenu("Tools").apply {
                    showRawSerialMenuItem = JCheckBoxMenuItem("Show Manual Command Line", displayPreferences.rawSerialVisible).apply {
                        addActionListener { setRawSerialVisible(isSelected) }
                    }
                    add(showRawSerialMenuItem)
                    showLogMenuItem = JCheckBoxMenuItem("Show Session Log", displayPreferences.logVisible).apply {
                        addActionListener { setLogVisible(isSelected) }
                    }
                    add(showLogMenuItem)
                    addSeparator()
                    add(
                        JMenuItem("Open Log Folder").apply {
                            addActionListener { openLogFolder() }
                        },
                    )
                    add(
                        JMenuItem("Copy Current Log").apply {
                            addActionListener { copyCurrentLogToClipboard() }
                        },
                    )
                    addSeparator()
                    add(
                        JMenuItem("Clear Current Log...").apply {
                            addActionListener { confirmAndClearCurrentLog() }
                        },
                    )
                    add(
                        JMenuItem("Delete All Log Files...").apply {
                            addActionListener { confirmAndDeleteAllLogs() }
                        },
                    )
                },
            )
        }
    }

    private fun showAboutDialog() {
        JOptionPane.showMessageDialog(
            this,
            JEditorPane(
                "text/html",
                buildString {
                    append("<html><body style='font-family:sans-serif;'>")
                    append("<div>App Version: ${SerialSlingerVersion.displayVersion}</div>")
                    append("<div>Build Date (UTC): ${SerialSlingerVersion.buildDateUtc}</div>")
                    append("<div>Platform: Desktop</div>")
                    append("<div>Project: <a href='${SerialSlingerVersion.projectUrl}'>${SerialSlingerVersion.projectUrl}</a></div>")
                    append("<div>License: ${SerialSlingerVersion.licenseLabel}</div>")
                    append("</body></html>")
                },
            ).apply {
                isEditable = false
                isOpaque = false
                addHyperlinkListener { event ->
                    if (event.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED &&
                        Desktop.isDesktopSupported() &&
                        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                    ) {
                        runCatching {
                            Desktop.getDesktop().browse(URI(event.url.toString()))
                        }
                    }
                }
            },
            "About SerialSlinger",
            JOptionPane.INFORMATION_MESSAGE,
        )
    }

    private fun showDefaultEventLengthDialog() {
        val initialMinutes = displayPreferences.defaultEventLengthMinutes
        val initialHours = initialMinutes / 60
        val initialRemainderMinutes = initialMinutes % 60
        val hourSpinner = JSpinner(SpinnerNumberModel(initialHours, 0, 24, 1))
        val minuteSpinner = JSpinner(SpinnerNumberModel(initialRemainderMinutes, 0, 55, 5))
        val summaryLabel = JLabel().apply {
            foreground = cloneAccentColor
        }

        fun selectedMinutes(): Int {
            val hours = (hourSpinner.value as? Number)?.toInt()?.coerceIn(0, 24) ?: 0
            val minutes = (minuteSpinner.value as? Number)?.toInt()?.coerceIn(0, 55) ?: 0
            return (hours * 60) + minutes
        }

        fun refreshSummary() {
            val totalMinutes = selectedMinutes()
            summaryLabel.text = if (totalMinutes in 10..(24 * 60)) {
                "Default finish offset: ${DesktopInputSupport.formatDefaultEventLength(totalMinutes)}"
            } else {
                "Choose between 10 minutes and 24 hours."
            }
        }

        hourSpinner.addChangeListener { refreshSummary() }
        minuteSpinner.addChangeListener { refreshSummary() }
        refreshSummary()

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(summaryLabel)
            add(Box.createVerticalStrut(10))
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(JLabel("Hours"))
                    add(Box.createHorizontalStrut(6))
                    add(hourSpinner)
                    add(Box.createHorizontalStrut(12))
                    add(JLabel("Minutes"))
                    add(Box.createHorizontalStrut(6))
                    add(minuteSpinner)
                },
            )
            add(Box.createVerticalStrut(10))
            add(JLabel("Range: 10 minutes to 24 hours."))
        }

        val result = JOptionPane.showConfirmDialog(
            this,
            panel,
            "Default Event Length",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (result != JOptionPane.OK_OPTION) {
            return
        }

        try {
            setDefaultEventLengthMinutes(selectedMinutes())
        } catch (exception: IllegalArgumentException) {
            JOptionPane.showMessageDialog(
                this,
                exception.message ?: "Invalid Default Event Length.",
                "Default Event Length",
                JOptionPane.WARNING_MESSAGE,
            )
        }
    }

    private fun configuredContentSplitPane(): JSplitPane {
        return contentSplitPane.apply {
            resizeWeight = 0.60
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }
    }

    private fun buildFooter(): JPanel {
        return JPanel(BorderLayout(8, 8)).apply {
            border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
            add(rawSerialRowPanel, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout(8, 0)).apply {
                    isOpaque = false
                    add(statusLabel, BorderLayout.CENTER)
                    add(applyButton, BorderLayout.EAST)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun installTrimmedComboRenderer(comboBox: JComboBox<*>) {
        comboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(
                    list,
                    value?.toString()?.trim(),
                    index,
                    isSelected,
                    cellHasFocus,
                )
                border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
                text = value?.toString()?.trim().orEmpty()
                return component
            }
        }
    }

    private fun buildFormPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(0, 0, 12, 0)
            add(buildSectionPanel(
                title = "Device Settings",
                helperText = "These settings are individual to each device. They are not applied when Clone is used.",
            ) { section ->
                var row = 0
                row = addSectionNote(section, row)
                row = addRow(section, row, "Fox Role", foxRoleCombo)
                row = addRow(section, row, "Pattern Text", patternTextField)
                row = addRow(section, row, devicePatternSpeedLabel, devicePatternSpeedField)
                row = addRow(section, row, currentTimeRowLabel, currentTimeRowPanel)
                row = addRow(section, row, systemTimeRowLabel, systemTimeField)
                row = addRow(section, row, manualTimeRowLabel, manualTimeRowPanel)
                row = addRow(section, row, currentFrequencyRowLabel, currentFrequencyField)
                row = addRow(section, row, currentBankRowLabel, currentBankField)
                row = addRow(section, row, "Ext. Bat. Ctrl", batteryModeCombo)
                row = addRow(section, row, transmissionsRowLabel, transmissionsField)
                addRow(section, row, "Low Battery Threshold", batteryThresholdField)
            })
            add(Box.createVerticalStrut(12))
            add(
                buildSectionPanel(
                    title = "Timed Event Settings",
                    titleColor = cloneAccentColor,
                    borderColor = cloneAccentBorderColor,
                    helperText = "These settings are written to the connected device when Clone is used.",
                    helperForeground = cloneAccentColor,
                    helperBackground = cloneAccentBackground,
                ) { section ->
                    var row = 0
                    row = addSectionNote(section, row)
                    row = addRow(section, row, "Event Type", eventTypeCombo)
                    row = addRow(section, row, "Station ID", stationIdField)
                    row = addRow(section, row, "ID Speed", idSpeedField)
                    row = addRow(section, row, timedPatternSpeedLabel, timedPatternSpeedField)
                    row = addRow(section, row, startTimeRowLabel, startTimeEditorHost)
                    row = addRow(section, row, finishTimeRowLabel, finishTimeEditorHost)
                    row = addRow(section, row, "Event Status", buildEventStatusRow())
                    row = addRow(section, row, lastsRowLabel, lastsField)
                    row = addRow(section, row, "Days To Run", buildDaysToRunRow())
                    row = addRow(section, row, frequency1Label, frequency1Field)
                    row = addRow(section, row, frequency2Label, frequency2Field)
                    row = addRow(section, row, frequency3Label, frequency3Field)
                    addRow(section, row, frequencyBLabel, frequencyBField)
                },
            )
            add(Box.createVerticalStrut(12))
            add(buildSectionPanel("Device Data") { section ->
                var row = 0
                row = addRow(section, row, "Internal Battery", internalBatteryField)
                row = addRow(section, row, "External Battery", externalBatteryField)
                row = addRow(section, row, maximumTemperatureRowLabel, maximumTemperatureField)
                row = addRow(section, row, currentTemperatureRowLabel, currentTemperatureField)
                row = addRow(section, row, minimumTemperatureRowLabel, minimumTemperatureField)
                addRow(section, row, "Version", versionInfoField)
            })
            add(Box.createVerticalGlue())
            updatePatternSpeedVisibility(EventType.NONE)
        }
    }

    private fun buildSectionPanel(
        title: String,
        titleColor: Color? = null,
        borderColor: Color? = null,
        helperText: String? = null,
        helperForeground: Color? = null,
        helperBackground: Color? = null,
        buildRows: (JPanel) -> Unit,
    ): JPanel {
        return JPanel(GridBagLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder(
                borderColor?.let(BorderFactory::createLineBorder),
                title,
            ).apply {
                titleColor?.let { this.titleColor = it }
            }
            helperText?.let {
                putClientProperty("sectionHelperText", it)
                putClientProperty("sectionHelperForeground", helperForeground)
                putClientProperty("sectionHelperBackground", helperBackground)
            }
            buildRows(this)
        }
    }

    private fun addSectionNote(panel: JPanel, row: Int): Int {
        val helperText = panel.getClientProperty("sectionHelperText") as? String ?: return row
        val helperForeground = panel.getClientProperty("sectionHelperForeground") as? Color ?: Color(0x1F, 0x29, 0x37)
        val helperBackground = panel.getClientProperty("sectionHelperBackground") as? Color ?: Color(0xF3, 0xF4, 0xF6)
        val helperLabel = JLabel(helperText).apply {
            foreground = helperForeground
            border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        }
        val helperPanel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = helperBackground
            border = BorderFactory.createLineBorder(helperBackground.darker())
            add(helperLabel, BorderLayout.CENTER)
        }
        val helperConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            gridwidth = 2
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 8, 8)
        }
        panel.add(helperPanel, helperConstraints)
        return row + 1
    }

    private fun createAccentButton(
        title: String,
        accentColor: Color = cloneAccentColor,
        rolloverColor: Color = accentColor,
    ): JButton {
        return object : JButton(title) {
            override fun paintComponent(graphics: Graphics) {
                val graphics2d = graphics.create() as Graphics2D
                try {
                    graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val referenceInsets = autoDetectButton.border.getBorderInsets(autoDetectButton)
                    val topInset = referenceInsets.top.coerceAtLeast(2)
                    val bottomInset = referenceInsets.bottom.coerceAtLeast(2)
                    val fillY = topInset
                    val fillHeight = (height - topInset - bottomInset).coerceAtLeast(10)
                    val fillColor = when {
                        !isEnabled -> Color(0xD1, 0xD5, 0xDB)
                        model.isPressed -> accentColor.darker()
                        model.isRollover -> rolloverColor
                        else -> accentColor
                    }
                    graphics2d.color = fillColor
                    graphics2d.fillRoundRect(1, fillY, width - 2, fillHeight, 12, 12)
                } finally {
                    graphics2d.dispose()
                }
                super.paintComponent(graphics)
            }

            override fun paintBorder(graphics: Graphics) {
                val graphics2d = graphics.create() as Graphics2D
                try {
                    graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val referenceInsets = autoDetectButton.border.getBorderInsets(autoDetectButton)
                    val topInset = referenceInsets.top.coerceAtLeast(2)
                    val bottomInset = referenceInsets.bottom.coerceAtLeast(2)
                    val borderY = topInset
                    val borderHeight = (height - topInset - bottomInset).coerceAtLeast(10)
                    graphics2d.color = if (isEnabled) accentColor.darker() else Color(0x9C, 0xA3, 0xAF)
                    graphics2d.drawRoundRect(1, borderY, width - 3, borderHeight - 1, 12, 12)
                } finally {
                    graphics2d.dispose()
                }
            }
        }.apply {
            foreground = Color.WHITE
            isOpaque = false
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            isRolloverEnabled = true
        }
    }

    private fun buildCurrentTimeRow(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(currentTimeField)
            add(currentTimeRowSpacer)
            add(syncTimeButton)
        }
    }

    private fun buildManualTimeRow(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(manualTimeSpinner)
            add(Box.createHorizontalStrut(8))
            add(setTimeButton)
        }
    }

    private fun buildEventStatusRow(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(startsInField)
            add(Box.createHorizontalStrut(8))
            add(disableEventButton)
        }
    }

    private fun createScheduleTimeEditorHost(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
    }

    private fun createRelativeScheduleField(fieldLabel: String): JTextField {
        return JTextField().apply {
            isEditable = false
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = "Click to choose $fieldLabel."
        }
    }

    private fun createReadOnlySummaryValueLabel(): JLabel {
        return JLabel("Not Set").apply {
            foreground = currentTimeField.foreground
            border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        }
    }

    private fun buildRelativeScheduleEditorPanel(
        relativeField: JTextField,
        absoluteMirrorField: JLabel,
        absoluteMirrorLabel: JLabel,
    ): JPanel {
        configureInteractiveSelectionField(relativeField)
        absoluteMirrorField.font = absoluteMirrorField.font.deriveFont(absoluteMirrorField.font.size2D - 1f)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(relativeField)
            add(Box.createVerticalStrut(4))
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(absoluteMirrorLabel)
                    add(Box.createHorizontalStrut(8))
                    add(absoluteMirrorField)
                },
            )
        }
    }

    private fun configureInteractiveSelectionField(field: JTextField) {
        field.border = editableTextFieldBorder
        field.background = editableTextFieldBackground
        field.isOpaque = true
        field.disabledTextColor = field.foreground
    }

    private fun buildDateTimeEditorRow(spinner: JSpinner, statusLabel: JLabel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(spinner)
            add(Box.createHorizontalStrut(8))
            add(statusLabel)
        }
    }

    private fun buildDaysToRunRow(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(daysField)
            add(Box.createHorizontalStrut(8))
            add(daysRemainingLabel)
        }
    }

    private fun updateDaysToRunDisplay(snapshot: DeviceSnapshot) {
        val settings = snapshot.settings
        daysField.value = settings.daysToRun.coerceAtLeast(1)
        daysRemainingLabel.text = DesktopInputSupport.formatDaysToRunRemainingSummary(
            totalDaysToRun = settings.daysToRun,
            daysToRunRemaining = snapshot.status.daysRemaining,
            currentTimeCompact = settings.currentTimeCompact,
        )
        daysRemainingLabel.toolTipText = if (daysRemainingLabel.text.isBlank()) {
            null
        } else {
            "Remaining days reported by the latest CLK read."
        }
    }

    private fun installImmediateApplyHandlers() {
        installTextCommitHandler(stationIdField, "Station ID") { applyStationIdChange() }
        installTextCommitHandler(patternTextField, "Pattern Text") { applyPatternTextChange() }
        installComboCommitHandler(idSpeedField) { applyIdSpeedChange() }
        installComboCommitHandler(devicePatternSpeedField) { applyPatternSpeedChange() }
        installComboCommitHandler(timedPatternSpeedField) { applyPatternSpeedChange() }
        installComboCommitHandler(batteryThresholdField) { applyBatteryThresholdChange() }
        installSpinnerCommitHandler(daysField, "Days To Run", hasMeaningfulChange = { true }) { applyDaysToRunChange() }
        installSpinnerCommitHandler(
            frequency1Field,
            "Frequency 1",
            hasMeaningfulChange = { selectedFrequencyHz(frequency1Field) != currentConnectedTimedSettings().lowFrequencyHz },
        ) { applyFrequency1Change() }
        installSpinnerCommitHandler(
            frequency2Field,
            "Frequency 2",
            hasMeaningfulChange = { selectedFrequencyHz(frequency2Field) != currentConnectedTimedSettings().mediumFrequencyHz },
        ) { applyFrequency2Change() }
        installSpinnerCommitHandler(
            frequency3Field,
            "Frequency 3",
            hasMeaningfulChange = { selectedFrequencyHz(frequency3Field) != currentConnectedTimedSettings().highFrequencyHz },
        ) { applyFrequency3Change() }
        installSpinnerCommitHandler(
            frequencyBField,
            "Frequency B",
            hasMeaningfulChange = { selectedFrequencyHz(frequencyBField) != currentConnectedTimedSettings().beaconFrequencyHz },
        ) { applyFrequencyBChange() }

        eventTypeCombo.addActionListener {
            if (!updatingForm) {
                applyEventTypeChange()
            }
        }
        foxRoleCombo.addActionListener {
            if (!updatingForm) {
                applyFoxRoleChange()
            }
        }
        batteryModeCombo.addActionListener {
            if (!updatingForm) {
                applyBatteryModeChange()
            }
        }
        installDateTimeCommitHandler(
            startTimeSpinner,
            "Start Time",
        ) { applyStartTimeChange() }
        installDateTimeCommitHandler(
            finishTimeSpinner,
            "Finish Time",
        ) { applyFinishTimeChange() }
    }

    private fun installTextCommitHandler(field: JTextField, description: String, onCommit: () -> Unit) {
        field.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(event: javax.swing.event.DocumentEvent?) = markPending()
                override fun removeUpdate(event: javax.swing.event.DocumentEvent?) = markPending()
                override fun changedUpdate(event: javax.swing.event.DocumentEvent?) = markPending()

                private fun markPending() {
                    if (!updatingForm && field.isEditable && field.isEnabled) {
                        rememberPendingImmediateEdit(description, field, onCommit)
                    }
                }
            },
        )
        field.addActionListener {
            if (!updatingForm && field.isEditable && field.isEnabled) {
                commitPendingImmediateEdit(orElse = onCommit)
            }
        }
        field.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(event: FocusEvent?) {
                    if (!updatingForm && field.isEditable && field.isEnabled) {
                        commitPendingImmediateEdit(orElse = onCommit)
                    }
                }
            },
        )
    }

    private fun installComboCommitHandler(comboBox: JComboBox<*>, onCommit: () -> Unit) {
        comboBox.addActionListener {
            if (!updatingForm && comboBox.isEnabled) {
                onCommit()
            }
        }
    }

    private fun installSpinnerCommitHandler(
        spinner: JSpinner,
        description: String,
        hasMeaningfulChange: () -> Boolean = { true },
        onCommit: () -> Unit,
    ) {
        fun markPending() {
            if (!updatingForm && spinner.isEnabled) {
                rememberPendingImmediateEdit(description, spinner, onCommit)
            }
        }

        fun attachEditorHandlers() {
            val textField = spinnerEditorTextField(spinner)
            if (textField.getClientProperty(SPINNER_COMMIT_HANDLER_INSTALLED_KEY) == true) {
                return
            }
            textField.putClientProperty(SPINNER_COMMIT_HANDLER_INSTALLED_KEY, true)
            textField.document.addDocumentListener(
                object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(event: javax.swing.event.DocumentEvent?) = markPending()
                    override fun removeUpdate(event: javax.swing.event.DocumentEvent?) = markPending()
                    override fun changedUpdate(event: javax.swing.event.DocumentEvent?) = markPending()
                },
            )
            val commitAction = {
                commitSpinnerEditorAndPendingEdit(
                    spinner = spinner,
                    source = spinner,
                    onCommit = onCommit,
                    canCommit = { !updatingForm && spinner.isEnabled },
                    hasMeaningfulChange = hasMeaningfulChange,
                )
            }
            registerSpinnerEditorCommitAction(textField, commitAction)
            textField.addActionListener { commitAction() }
            textField.addFocusListener(
                object : FocusAdapter() {
                    override fun focusLost(event: FocusEvent?) {
                        if (!updatingForm && spinner.isEnabled) {
                            commitAction()
                        }
                    }
                },
            )
        }

        attachEditorHandlers()
        spinner.addPropertyChangeListener("editor") {
            attachEditorHandlers()
        }
        spinner.addChangeListener {
            markPending()
        }
    }

    private fun installDateTimeCommitHandler(
        spinner: JSpinner,
        description: String,
        hasMeaningfulChange: () -> Boolean = { true },
        onCommit: () -> Unit,
    ) {
        val textField = spinnerEditorTextField(spinner)
        textField.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(event: javax.swing.event.DocumentEvent?) = markPending()
                override fun removeUpdate(event: javax.swing.event.DocumentEvent?) = markPending()
                override fun changedUpdate(event: javax.swing.event.DocumentEvent?) = markPending()

                private fun markPending() {
                    if (!updatingForm && !isDateTimeCommitSuppressed(spinner) && !shouldSuppressScheduleCommit(spinner)) {
                        rememberPendingImmediateEdit(description, spinner, onCommit)
                    }
                }
            },
        )
        val commitAction = {
            commitSpinnerEditorAndPendingEdit(
                spinner = spinner,
                source = spinner,
                onCommit = onCommit,
                canCommit = {
                    !updatingForm && !isDateTimeCommitSuppressed(spinner) && !shouldSuppressScheduleCommit(spinner)
                },
                hasMeaningfulChange = hasMeaningfulChange,
            )
        }
        registerSpinnerEditorCommitAction(textField, commitAction)
        textField.addActionListener { commitAction() }
        textField.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(event: FocusEvent?) {
                    if (!updatingForm && !isDateTimeCommitSuppressed(spinner) && !shouldSuppressScheduleCommit(spinner)) {
                        commitAction()
                    }
                }
            },
        )
    }

    private fun registerSpinnerEditorCommitAction(textField: JFormattedTextField, commitAction: () -> Unit) {
        textField.inputMap.put(KeyStroke.getKeyStroke("ENTER"), SPINNER_EDITOR_COMMIT_ACTION_KEY)
        textField.actionMap.put(
            SPINNER_EDITOR_COMMIT_ACTION_KEY,
            object : AbstractAction() {
                override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                    commitAction()
                }
            },
        )
    }

    private fun commitSpinnerEditorAndPendingEdit(
        spinner: JSpinner,
        source: Any,
        onCommit: () -> Unit,
        canCommit: () -> Boolean,
        hasMeaningfulChange: () -> Boolean,
    ) {
        if (!canCommit()) {
            return
        }
        try {
            spinner.commitEdit()
        } catch (_: Exception) {
            // Let the existing field validation path surface the actual problem.
        }
        if (!hasMeaningfulChange()) {
            clearPendingImmediateEdit(source)
            return
        }
        commitPendingImmediateEdit(orElse = onCommit)
    }

    private fun commitFocusedEditorThenPendingImmediateEdit() {
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val commitAction = (focusOwner as? JFormattedTextField)
            ?.actionMap
            ?.get(SPINNER_EDITOR_COMMIT_ACTION_KEY)
        if (commitAction != null) {
            commitAction.actionPerformed(null)
            return
        }
        commitPendingImmediateEdit()
    }

    private fun shouldSuppressScheduleCommit(spinner: JSpinner): Boolean {
        return spinner in listOf(startTimeSpinner, finishTimeSpinner) &&
            (
                isScheduleInteractionSuppressed() ||
                    startTimeFinishAdjustmentDialogOpen ||
                    startTimeDaysToRunDialogOpen ||
                    multiDayDurationGuardDialogOpen ||
                    lastsDurationDialogOpen
                )
    }

    private fun rememberPendingImmediateEdit(description: String, source: Any, onCommit: () -> Unit) {
        if (backgroundWorkInProgress) {
            return
        }
        pendingImmediateEdit = PendingImmediateEdit(description, source, onCommit)
        updateApplyButtonState()
    }

    private fun clearPendingImmediateEdit(source: Any? = null) {
        if (source != null && pendingImmediateEdit?.source !== source) {
            return
        }
        pendingImmediateEdit = null
        updateApplyButtonState()
    }

    private fun commitPendingImmediateEdit(orElse: (() -> Unit)? = null) {
        if (backgroundWorkInProgress) {
            return
        }
        val pending = pendingImmediateEdit
        clearPendingImmediateEdit()
        (pending?.onCommit ?: orElse)?.invoke()
    }

    private fun updateApplyButtonState() {
        val pending = pendingImmediateEdit
        applyButton.isEnabled =
            pending != null &&
            !backgroundWorkInProgress &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED
        applyButton.toolTipText = pending?.let {
            "Apply pending ${it.description.lowercase()} change."
        } ?: "Make a change in a field, then click Apply."
    }

    private fun addRow(panel: JPanel, row: Int, label: String, component: Component): Int {
        return addRow(panel, row, JLabel(label), component)
    }

    private fun addRow(panel: JPanel, row: Int, labelComponent: JLabel, component: Component): Int {
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.WEST
            insets = Insets(2, 8, 2, 8)
        }
        val fieldConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(2, 8, 2, 8)
        }
        panel.add(labelComponent, labelConstraints)
        panel.add(component, fieldConstraints)
        return row + 1
    }

    private fun smartPollPorts() {
        val freshPorts = SignalSlingerPortDiscovery.listAvailablePorts()
        refreshAvailablePorts(freshPorts, silent = true)
        maybeSchedulePassiveProbe(freshPorts.map { it.portInfo })
    }

    private fun refreshAvailablePorts(
        freshPorts: List<SignalSlingerPortProbe> = SignalSlingerPortDiscovery.listAvailablePorts(),
        silent: Boolean = false,
    ) {
        if (connectedPortMissingFrom(freshPorts.map { it.portInfo })) {
            handleConnectedPortRemoved()
        }

        val selectedPath = selectedProbe()?.portInfo?.systemPortPath
        val connectedAliasGroup = connectedAliasGroupKey()
        knownProbeResults.keys.retainAll(freshPorts.map { it.portInfo.systemPortPath }.toSet())
        val merged = freshPorts.map { fresh ->
            val mergedProbe = knownProbeResults[fresh.portInfo.systemPortPath] ?: fresh
            if (
                connectedAliasGroup != null &&
                fresh.portInfo.systemPortPath != currentConnectedPortPath &&
                DesktopSmartPollingPolicy.aliasGroupKey(fresh.portInfo.systemPortPath) == connectedAliasGroup
            ) {
                mergedProbe.copy(
                    state = PortProbeState.DETECTED,
                    summary = "Paired alias of connected SignalSlinger",
                )
            } else {
                mergedProbe
            }
        }.toMutableList()
        if (autoDetectNoDeviceFound) {
            merged.add(0, noConnectedDeviceProbe())
        }

        suppressPortSelectionHandling = true
        try {
            portModel.removeAllElements()
            merged.forEach { portModel.addElement(it) }

            val preferredPath = DesktopAutoDetectPolicy.defaultSelectionPath(
                availablePorts = merged.map { it.portInfo },
                currentSelectionPath = selectedPath,
                lastWorkingPortPath = portMemory.loadLastWorkingPortPath(),
            )
            val selectedIndex = merged.indexOfFirst { it.portInfo.systemPortPath == preferredPath }
            if (selectedIndex >= 0) {
                portComboBox.selectedIndex = selectedIndex
            } else if (autoDetectNoDeviceFound && merged.isNotEmpty()) {
                portComboBox.selectedIndex = 0
            } else if (merged.isNotEmpty()) {
                portComboBox.selectedIndex = 0
            }
        } finally {
            suppressPortSelectionHandling = false
        }

        if (!silent) {
            setStatus("Found ${merged.size} serial port(s).")
        }
    }

    private fun connectedPortMissingFrom(availablePorts: List<DesktopSerialPortInfo>): Boolean {
        val connectedPortPath = currentConnectedPortPath ?: return false
        val connectedAliasGroup = connectedAliasGroupKey()
        return availablePorts.none { portInfo ->
            portInfo.systemPortPath == connectedPortPath ||
                (
                    connectedAliasGroup != null &&
                        DesktopSmartPollingPolicy.aliasGroupKey(portInfo.systemPortPath) == connectedAliasGroup
                    )
        }
    }

    private fun handleConnectedPortRemoved() {
        val removedPortPath = currentConnectedPortPath ?: return
        try {
            currentTransport?.disconnect()
        } catch (_: Exception) {
        }
        currentTransport = null
        currentConnectedPortPath = null
        val updatedState = currentState?.let { state ->
            state.copy(
                connectionState = ConnectionState.DISCONNECTED,
                snapshot = state.snapshot?.copy(
                    status = state.snapshot.status.copy(
                        connectionState = ConnectionState.DISCONNECTED,
                        lastCommunicationError = "Connected serial port was removed.",
                    ),
                ),
                lastError = "Connected serial port was removed.",
            )
        }
        currentState = updatedState
        loadedSnapshot = updatedState?.snapshot
        consecutiveDeviceTimeCheckNoResponseCount = 0
        clockPhaseWarningActive = false
        lastClockPhaseErrorMillis = null
        appendLog(
            "Port Monitor",
            listOf(
                DesktopLogEntry(
                    "Connected serial port $removedPortPath was removed.",
                    DesktopLogCategory.DEVICE,
                ),
            ),
        )
        showConnectionIndicator(
            ConnectionIndicatorState.DISCONNECTED,
            "Connected SignalSlinger was removed",
        )
        setStatus("Connected serial port was removed.")
        updateDisplayedClockFields()
    }

    private fun maybeSchedulePassiveProbe(availablePorts: List<DesktopSerialPortInfo>) {
        if (backgroundWorkInProgress || passiveProbeInProgress) {
            return
        }

        val connectedAliasGroup = connectedAliasGroupKey()
        val candidatePorts = if (currentTransport != null && currentState?.connectionState == ConnectionState.CONNECTED) {
            availablePorts.filterNot { portInfo ->
                portInfo.systemPortPath == currentConnectedPortPath ||
                    (
                        connectedAliasGroup != null &&
                            DesktopSmartPollingPolicy.aliasGroupKey(portInfo.systemPortPath) == connectedAliasGroup
                        )
            }
        } else {
            availablePorts
        }
        val candidate = DesktopSmartPollingPolicy.nextProbeCandidate(
            availablePorts = candidatePorts,
            knownProbes = knownProbeResults,
            connectedPortPath = currentConnectedPortPath,
        ) ?: return

        passiveProbeInProgress = true
        Thread {
            val result = SignalSlingerPortDiscovery.probePort(candidate)

            SwingUtilities.invokeLater {
                passiveProbeInProgress = false
                applyPassiveProbeResult(result)
            }
        }.start()
    }

    private fun applyPassiveProbeResult(result: SignalSlingerPortProbe) {
        val previous = knownProbeResults[result.portInfo.systemPortPath]
        knownProbeResults[result.portInfo.systemPortPath] = result

        if (previous?.state == result.state && previous.summary == result.summary) {
            refreshAvailablePorts(silent = true)
            return
        }

        when (result.state) {
            PortProbeState.DETECTED -> {
                if (currentConnectedPortPath == null || autoDetectNoDeviceFound) {
                    autoDetectNoDeviceFound = false
                }
                refreshAvailablePorts(silent = true)
                appendLog(
                    "Port Monitor",
                    listOf(
                        DesktopLogEntry(
                            "SignalSlinger is responding on ${result.portInfo.systemPortPath}.",
                            DesktopLogCategory.DEVICE,
                        ),
                    ),
                )
                if (currentConnectedPortPath == null) {
                    val preferredDetectedPath = preferredDetectedPortPath(result.portInfo.systemPortPath)
                    if (preferredDetectedPath != null) {
                        selectPort(preferredDetectedPath)
                    }
                    showConnectionIndicator(
                        ConnectionIndicatorState.SEARCHING,
                        "SignalSlinger detected on ${result.portInfo.systemPortPath}. Select it or run Auto Detect.",
                    )
                    setStatus("SignalSlinger detected on ${result.portInfo.systemPortPath}.")
                }
            }
            PortProbeState.NOT_DETECTED,
            PortProbeState.ERROR,
            -> {
                refreshAvailablePorts(silent = true)
                if (previous?.state == PortProbeState.DETECTED) {
                    appendLog(
                        "Port Monitor",
                        listOf(
                            DesktopLogEntry(
                                "SignalSlinger is no longer responding on ${result.portInfo.systemPortPath}.",
                                DesktopLogCategory.DEVICE,
                            ),
                        ),
                    )
                }

                if (
                    result.portInfo.systemPortPath == currentConnectedPortPath &&
                    currentState?.connectionState == ConnectionState.CONNECTED &&
                    result.state != PortProbeState.DETECTED
                ) {
                    showConnectionIndicator(
                        ConnectionIndicatorState.DISCONNECTED,
                        "SignalSlinger is not responding on ${result.portInfo.systemPortPath}",
                    )
                    setStatus("SignalSlinger stopped responding on ${result.portInfo.systemPortPath}.")
                }
            }
            PortProbeState.UNCHECKED -> refreshAvailablePorts(silent = true)
        }
    }

    private fun autoDetectPorts() {
        showConnectionIndicator(ConnectionIndicatorState.SEARCHING, "Searching for SignalSlinger...")
        refreshAvailablePorts(silent = true)
        val ports = SignalSlingerPortDiscovery.listAvailablePorts().map { it.portInfo }
        val lastWorkingPortPath = portMemory.loadLastWorkingPortPath()
        val selectedPortPath = selectedProbe()?.portInfo?.systemPortPath
        val connectedPortPath = currentConnectedPortPath.takeIf {
            currentTransport != null && currentState?.connectionState == ConnectionState.CONNECTED
        }
        val orderedPorts = DesktopAutoDetectPolicy.detectionOrder(
            availablePorts = ports,
            lastWorkingPortPath = lastWorkingPortPath,
            connectedPortPath = connectedPortPath,
        )

        if (orderedPorts.isEmpty()) {
            val fallbackPath = DesktopAutoDetectPolicy.defaultSelectionPath(
                availablePorts = ports,
                currentSelectionPath = connectedPortPath,
                lastWorkingPortPath = lastWorkingPortPath,
            )
            if (fallbackPath != null) {
                selectPort(fallbackPath)
            }
            setStatus("No additional serial ports are available to probe.")
            showConnectionIndicator(ConnectionIndicatorState.DISCONNECTED, "No Connected SignalSlinger Found")
            return
        }

        runInBackground("Probing serial ports for SignalSlinger...") {
            val preferredProbeAttempt = attemptAutoDetectPreferredPortRecovery(
                initialAvailablePorts = ports,
                selectedPortPath = selectedPortPath,
                lastWorkingPortPath = lastWorkingPortPath,
            )
            val preferredProbeResult = preferredProbeAttempt.probeResult
            val preferredLoadResult = preferredProbeAttempt.loadResult
            if (preferredLoadResult != null) {
                SwingUtilities.invokeLater {
                    preferredProbeResult?.let { result ->
                        knownProbeResults[result.portInfo.systemPortPath] = result
                    }
                    refreshAvailablePorts(silent = true)
                    appendLog("Auto Detect", buildList {
                        add(DesktopLogEntry("Trying remembered SignalSlinger port first.", DesktopLogCategory.APP))
                        preferredProbeResult?.let { result ->
                            add(DesktopLogEntry(result.displayLabel, DesktopLogCategory.DEVICE))
                            result.evidenceLines.take(3).forEach { line ->
                                add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL))
                            }
                        }
                    })
                    autoDetectNoDeviceFound = false
                    selectPort(preferredLoadResult.portPath)
                    applyLoadedConnection(preferredLoadResult)
                    setStatus("Detected and loaded SignalSlinger on ${preferredLoadResult.portPath}.")
                }
                return@runInBackground
            }

            val scannedPorts = if (preferredProbeAttempt.probedPath != null) {
                orderedPorts.filterNot { it.systemPortPath == preferredProbeAttempt.probedPath }
            } else {
                orderedPorts
            }
            val scanResult = SignalSlingerPortDiscovery.findFirstDetectedPort(
                ports = scannedPorts,
                onProbeComplete = { result ->
                    knownProbeResults[result.portInfo.systemPortPath] = result
                    SwingUtilities.invokeLater {
                        refreshAvailablePorts(silent = true)
                        selectPort(result.portInfo.systemPortPath)
                        setStatus(
                            if (result.state == PortProbeState.DETECTED) {
                                "Detected SignalSlinger on ${result.portInfo.systemPortPath}. Loading settings..."
                            } else {
                                "Probed ${result.portInfo.systemPortPath}. Continuing search..."
                            },
                        )
                    }
                },
            )
            val finalPorts = ports
            val finalScanResult = scanResult
            val detected = finalScanResult.detected
            val loadPortPath = detected?.portInfo?.systemPortPath
            val loadResult = loadPortPath?.let { resolvedPortPath ->
                loadPort(resolvedPortPath).let { connection ->
                    connection.copy(
                        loadLogTitle = "Auto Detect Load",
                        loadLogLeadEntries = listOf(
                            DesktopLogEntry(
                                "Detected ${detected.portInfo.systemPortPath}; loading settings from $resolvedPortPath.",
                                DesktopLogCategory.APP,
                            ),
                            DesktopLogEntry(
                                "Loaded ${connection.result.commandsSent.size} command(s) and received ${connection.result.linesReceived.size} line(s).",
                                DesktopLogCategory.APP,
                            ),
                        ),
                    )
                }
            }
            val connectedPortVerification = connectedPortPath
                ?.takeIf { path -> finalPorts.any { it.systemPortPath == path } }
                ?.let { path ->
                    val preferredPath = DesktopSmartPollingPolicy.preferredPortPath(finalPorts, path) ?: path
                    SignalSlingerPortDiscovery.probePort(resolvePortInfoFor(preferredPath))
                }

            SwingUtilities.invokeLater {
                preferredProbeResult?.let { result ->
                    knownProbeResults[result.portInfo.systemPortPath] = result
                }
                connectedPortVerification?.let { result ->
                    knownProbeResults[result.portInfo.systemPortPath] = result
                }
                refreshAvailablePorts(silent = true)
                appendLog("Auto Detect", buildList {
                    add(DesktopLogEntry("Scanning ${scannedPorts.size} serial port(s).", DesktopLogCategory.APP))
                    if (
                        preferredProbeResult != null &&
                        finalScanResult.probes.none { it.portInfo.systemPortPath == preferredProbeResult.portInfo.systemPortPath }
                    ) {
                        val preferredProbeIntro = if (preferredProbeAttempt.attemptCount > 1) {
                            "Trying remembered SignalSlinger port first (${preferredProbeAttempt.attemptCount} attempts)."
                        } else {
                            "Trying remembered SignalSlinger port first."
                        }
                        add(DesktopLogEntry(preferredProbeIntro, DesktopLogCategory.APP))
                        add(DesktopLogEntry(preferredProbeResult.displayLabel, DesktopLogCategory.DEVICE))
                        preferredProbeResult.evidenceLines.take(3).forEach { line ->
                            add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL))
                        }
                    }
                    scanResult.probes.forEach { result ->
                        add(DesktopLogEntry(result.displayLabel, DesktopLogCategory.DEVICE))
                        result.evidenceLines.take(3).forEach { line ->
                            add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL))
                        }
                    }
                    if (
                        connectedPortVerification != null &&
                        finalScanResult.probes.none { it.portInfo.systemPortPath == connectedPortVerification.portInfo.systemPortPath }
                    ) {
                        add(
                            DesktopLogEntry(
                                connectedPortVerification.displayLabel,
                                DesktopLogCategory.DEVICE,
                            ),
                        )
                        connectedPortVerification.evidenceLines.take(3).forEach { line ->
                            add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL))
                        }
                    }
                })

                if (detected != null && loadResult != null) {
                    autoDetectNoDeviceFound = false
                    selectPort(loadResult.portPath)
                    applyLoadedConnection(loadResult)
                    setStatus("Detected and loaded SignalSlinger on ${loadResult.portPath}.")
                    return@invokeLater
                }

                val connectedPortStillAvailable = connectedPortVerification?.state == PortProbeState.DETECTED
                val fallbackPath = DesktopAutoDetectPolicy.defaultSelectionPath(
                    availablePorts = finalPorts,
                    currentSelectionPath = connectedPortPath,
                    lastWorkingPortPath = lastWorkingPortPath,
                )
                if (fallbackPath != null) {
                    selectPort(fallbackPath)
                }

                if (connectedPortStillAvailable) {
                    autoDetectNoDeviceFound = false
                    showConnectionIndicator(ConnectionIndicatorState.CONNECTED, "Connected to SignalSlinger on ${connectedPortPath}")
                    refreshAvailablePorts(silent = true)
                    appendLog(
                        "Auto Detect",
                        listOf(
                            DesktopLogEntry(
                                "No additional SignalSlinger found. Keeping ${connectedPortPath} connected.",
                                DesktopLogCategory.APP,
                            ),
                        ),
                    )
                    setStatus("No additional SignalSlinger found. Keeping ${connectedPortPath} selected.")
                } else {
                    autoDetectNoDeviceFound = true
                    refreshAvailablePorts(silent = true)
                    appendLog(
                        "Auto Detect Failed",
                        listOf(
                            DesktopLogEntry(
                                "No connected SignalSlinger was found on the available serial ports.",
                                DesktopLogCategory.DEVICE,
                            ),
                        ),
                    )
                    showConnectionIndicator(ConnectionIndicatorState.DISCONNECTED, "No Connected SignalSlinger Found")
                    setStatus(
                        if (lastWorkingPortPath != null && finalPorts.any { it.systemPortPath == lastWorkingPortPath }) {
                            "No SignalSlinger found. Restored last working port ${lastWorkingPortPath}."
                        } else {
                            "No SignalSlinger found on current serial ports."
                        },
                    )
                }
            }
        }
    }

    private fun preferredAutoDetectProbePath(
        availablePorts: List<DesktopSerialPortInfo>,
        selectedPortPath: String?,
        lastWorkingPortPath: String?,
    ): String? {
        val preferredLastWorkingPath = DesktopSmartPollingPolicy.preferredPortPath(availablePorts, lastWorkingPortPath)
        if (preferredLastWorkingPath != null) {
            return preferredLastWorkingPath
        }
        return DesktopSmartPollingPolicy.preferredPortPath(availablePorts, selectedPortPath)
    }

    private fun isTransientAutoDetectProbeFailure(result: SignalSlingerPortProbe?): Boolean {
        val summary = result?.summary?.lowercase().orEmpty()
        return summary.contains("error code 2") ||
            summary.contains("failed to write complete payload") ||
            summary.contains("invalid port descriptor")
    }

    private fun attemptAutoDetectPreferredPortRecovery(
        initialAvailablePorts: List<DesktopSerialPortInfo>,
        selectedPortPath: String?,
        lastWorkingPortPath: String?,
    ): PreferredProbeAttempt {
        var availablePorts = initialAvailablePorts
        var preferredPath = preferredAutoDetectProbePath(
            availablePorts = availablePorts,
            selectedPortPath = selectedPortPath,
            lastWorkingPortPath = lastWorkingPortPath,
        )
        var lastResult: SignalSlingerPortProbe? = null
        var attemptCount = 0

        repeat(4) { attemptIndex ->
            if (preferredPath == null) {
                if (attemptIndex == 3) {
                    return PreferredProbeAttempt(
                        probeResult = lastResult,
                        loadResult = null,
                        attemptCount = attemptCount,
                        probedPath = null,
                    )
                }
                Thread.sleep(600L)
                availablePorts = SignalSlingerPortDiscovery.listAvailablePorts().map { it.portInfo }
                preferredPath = preferredAutoDetectProbePath(
                    availablePorts = availablePorts,
                    selectedPortPath = selectedPortPath,
                    lastWorkingPortPath = lastWorkingPortPath,
                )
                return@repeat
            }

            val candidatePaths = DesktopSmartPollingPolicy.aliasCandidates(preferredPath)
            var shouldRetryAfterCandidates = false

            for (candidatePath in candidatePaths) {
                attemptCount += 1
                try {
                    val connection = loadPort(candidatePath)
                    val probeResult = SignalSlingerPortProbe(
                        portInfo = resolvePortInfoFor(candidatePath),
                        state = PortProbeState.DETECTED,
                        summary = "SignalSlinger detected",
                        evidenceLines = connection.result.linesReceived.take(3),
                        lastProbedAtMs = System.currentTimeMillis(),
                    )
                    return PreferredProbeAttempt(
                        probeResult = probeResult,
                        loadResult = connection.copy(
                            loadLogTitle = "Auto Detect Load",
                            loadLogLeadEntries = listOf(
                                DesktopLogEntry(
                                    "Tried remembered port $candidatePath first; loading settings from $candidatePath.",
                                    DesktopLogCategory.APP,
                                ),
                                DesktopLogEntry(
                                    "Loaded ${connection.result.commandsSent.size} command(s) and received ${connection.result.linesReceived.size} line(s).",
                                    DesktopLogCategory.APP,
                                ),
                            ),
                        ),
                        attemptCount = attemptCount,
                        probedPath = candidatePath,
                    )
                } catch (exception: Exception) {
                    val failure = SignalSlingerPortProbe(
                        portInfo = resolvePortInfoFor(candidatePath),
                        state = PortProbeState.ERROR,
                        summary = exception.message ?: "Probe failed",
                        lastProbedAtMs = System.currentTimeMillis(),
                    )
                    lastResult = failure
                    if (!isTransientAutoDetectProbeFailure(failure)) {
                        return PreferredProbeAttempt(
                            probeResult = failure,
                            loadResult = null,
                            attemptCount = attemptCount,
                            probedPath = candidatePath,
                        )
                    }
                    shouldRetryAfterCandidates = true
                }
            }

            if (!shouldRetryAfterCandidates || attemptIndex == 3) {
                return PreferredProbeAttempt(
                    probeResult = lastResult,
                    loadResult = null,
                    attemptCount = attemptCount,
                    probedPath = preferredPath,
                )
            }

            Thread.sleep(600L)
            availablePorts = SignalSlingerPortDiscovery.listAvailablePorts().map { it.portInfo }
            preferredPath = preferredAutoDetectProbePath(
                availablePorts = availablePorts,
                selectedPortPath = selectedPortPath,
                lastWorkingPortPath = lastWorkingPortPath,
            )
        }

        return PreferredProbeAttempt(
            probeResult = lastResult,
            loadResult = null,
            attemptCount = attemptCount,
            probedPath = preferredPath,
        )
    }

    private fun installAutoDetectButtonLongPressHandler() {
        autoDetectButton.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (event.button != MouseEvent.BUTTON1 || !autoDetectButton.isEnabled) {
                        return
                    }
                    autoDetectButtonLongPressTimer?.stop()
                    autoDetectButtonLongPressTimer = Timer(AUTO_DETECT_BUTTON_LONG_PRESS_MS) {
                        autoDetectButtonLongPressTimer = null
                        if (
                            autoDetectButton.model.isPressed &&
                            autoDetectButton.model.isArmed &&
                            !backgroundWorkInProgress
                        ) {
                            suppressNextAutoDetectAction = true
                            reloadCurrentPortAsDetected()
                        }
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }

                override fun mouseReleased(event: MouseEvent) {
                    autoDetectButtonLongPressTimer?.stop()
                    autoDetectButtonLongPressTimer = null
                }

                override fun mouseExited(event: MouseEvent) {
                    autoDetectButtonLongPressTimer?.stop()
                    autoDetectButtonLongPressTimer = null
                }
            },
        )
    }

    private fun installSettingsShortcutLongPressHandlers() {
        installSettingLongPressShortcut(
            targets = listOf(currentTimeField, currentTimeRowLabel),
            animatedTargets = timeSetToggleAnimatedTargets(),
        ) { toggleTimeSetMode() }
        installTimeSetSpinnerLongPressShortcut()
        installSettingLongPressShortcut(
            targets = listOf(systemTimeField, systemTimeRowLabel),
            animatedTargets = timeSetToggleAnimatedTargets(),
        ) { toggleTimeSetMode() }
        installSettingLongPressShortcut(
            targets = listOf(manualTimeRowLabel, manualTimeRowPanel, setTimeButton),
            animatedTargets = timeSetToggleAnimatedTargets(),
        ) { toggleTimeSetMode() }
        installSettingLongPressShortcut(
            targets = listOf(currentFrequencyField, currentFrequencyRowLabel),
            animatedTargets = frequencyToggleAnimatedTargets(),
        ) { toggleFrequencyDisplayUnit() }
        installFrequencySpinnerLongPressShortcut(frequency1Field, frequency1Label)
        installFrequencySpinnerLongPressShortcut(frequency2Field, frequency2Label)
        installFrequencySpinnerLongPressShortcut(frequency3Field, frequency3Label)
        installFrequencySpinnerLongPressShortcut(frequencyBField, frequencyBLabel)
        installSettingLongPressShortcut(
            targets = listOf(currentTemperatureField, currentTemperatureRowLabel),
            animatedTargets = listOf(currentTemperatureField, currentTemperatureRowLabel),
        ) { toggleTemperatureDisplayUnit() }
    }

    private fun installTimeSetSpinnerLongPressShortcut() {
        installSettingLongPressShortcut(
            targets = listOf(manualTimeSpinner, spinnerEditorTextField(manualTimeSpinner)),
            animatedTargets = timeSetToggleAnimatedTargets(),
        ) { toggleTimeSetMode() }
        manualTimeSpinner.addPropertyChangeListener("editor") {
            installSettingLongPressShortcut(
                targets = listOf(spinnerEditorTextField(manualTimeSpinner)),
                animatedTargets = timeSetToggleAnimatedTargets(),
            ) { toggleTimeSetMode() }
        }
    }

    private fun installFrequencySpinnerLongPressShortcut(spinner: JSpinner, label: JLabel) {
        installSettingLongPressShortcut(
            targets = listOf(spinner, label, spinnerEditorTextField(spinner)),
            animatedTargets = frequencyToggleAnimatedTargets(),
        ) { toggleFrequencyDisplayUnit() }
        spinner.addPropertyChangeListener("editor") {
            installSettingLongPressShortcut(
                targets = listOf(spinnerEditorTextField(spinner)),
                animatedTargets = frequencyToggleAnimatedTargets(),
            ) { toggleFrequencyDisplayUnit() }
        }
    }

    private fun timeSetToggleAnimatedTargets(): List<Component> {
        return buildList {
            add(currentTimeRowLabel)
            add(currentTimeField)
            if (displayPreferences.timeSetMode == TimeSetMode.MANUAL) {
                add(manualTimeRowLabel)
                add(manualTimeSpinner)
                add(spinnerEditorTextField(manualTimeSpinner))
            } else {
                add(systemTimeRowLabel)
                add(systemTimeField)
            }
        }
    }

    private fun frequencyToggleAnimatedTargets(): List<Component> {
        return buildList {
            add(currentFrequencyRowLabel)
            add(currentFrequencyField)
            if (frequency1Label.isVisible && frequency1Field.isVisible) {
                add(frequency1Label)
                add(frequency1Field)
            }
            if (frequency2Label.isVisible && frequency2Field.isVisible) {
                add(frequency2Label)
                add(frequency2Field)
            }
            if (frequency3Label.isVisible && frequency3Field.isVisible) {
                add(frequency3Label)
                add(frequency3Field)
            }
            if (frequencyBLabel.isVisible && frequencyBField.isVisible) {
                add(frequencyBLabel)
                add(frequencyBField)
            }
        }
    }

    private fun installSettingLongPressShortcut(
        targets: List<Component>,
        animatedTargets: List<Component>,
        onLongPress: () -> Unit,
    ) {
        val uniqueTargets = targets.distinct()
        uniqueTargets.forEach { component ->
            val installable = component as? javax.swing.JComponent ?: return@forEach
            if (installable.getClientProperty(SETTING_LONG_PRESS_HANDLER_INSTALLED_KEY) == true) {
                return@forEach
            }
            installable.putClientProperty(SETTING_LONG_PRESS_HANDLER_INSTALLED_KEY, true)
            component.addMouseListener(
                object : MouseAdapter() {
                    private var longPressTimer: Timer? = null

                    override fun mousePressed(event: MouseEvent) {
                        if (event.button != MouseEvent.BUTTON1 || !component.isEnabled) {
                            return
                        }
                        longPressTimer?.stop()
                        longPressTimer = Timer(SETTING_TOGGLE_LONG_PRESS_MS) {
                            longPressTimer = null
                            if (!backgroundWorkInProgress && component.isEnabled) {
                                markLongPressTriggered(component)
                                performAnimatedToggle(animatedTargets.distinct(), onLongPress)
                            }
                        }.apply {
                            isRepeats = false
                            start()
                        }
                    }

                    override fun mouseReleased(event: MouseEvent) {
                        longPressTimer?.stop()
                        longPressTimer = null
                    }

                    override fun mouseExited(event: MouseEvent) {
                        longPressTimer?.stop()
                        longPressTimer = null
                    }
                },
            )
        }
    }

    private fun performAnimatedToggle(targets: List<Component>, onToggle: () -> Unit) {
        animateToggleFeedback(targets)
        Timer(SETTING_TOGGLE_FEEDBACK_MS) {
            onToggle()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun animateToggleFeedback(targets: List<Component>) {
        targets.distinct().forEach { component ->
            when (component) {
                is JLabel -> {
                    val originalForeground = component.foreground
                    val originalFont = component.font
                    component.foreground = cloneAccentColor
                    component.font = originalFont.deriveFont(java.awt.Font.BOLD)
                    Timer(SETTING_TOGGLE_ANIMATION_MS) {
                        component.foreground = originalForeground
                        component.font = originalFont
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
                is javax.swing.JComponent -> {
                    val originalOpaque = component.isOpaque
                    val originalBackground = component.background
                    val originalBorder = component.border
                    component.isOpaque = true
                    component.background = Color(0xE0, 0xEC, 0xFF)
                    component.border = BorderFactory.createLineBorder(cloneAccentColor, 2, true)
                    component.repaint()
                    Timer(SETTING_TOGGLE_ANIMATION_MS) {
                        component.isOpaque = originalOpaque
                        component.background = originalBackground
                        component.border = originalBorder
                        component.repaint()
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            }
        }
    }

    private fun markLongPressTriggered(component: Component) {
        (component as? javax.swing.JComponent)?.putClientProperty(SETTING_LONG_PRESS_TRIGGERED_KEY, true)
    }

    private fun consumeLongPressClick(component: Component): Boolean {
        val installable = component as? javax.swing.JComponent ?: return false
        val triggered = installable.getClientProperty(SETTING_LONG_PRESS_TRIGGERED_KEY) == true
        if (triggered) {
            installable.putClientProperty(SETTING_LONG_PRESS_TRIGGERED_KEY, false)
        }
        return triggered
    }

    private fun reloadCurrentPortAsDetected() {
        val selectedPath = preferredReloadPortPath()
        if (selectedPath == null) {
            JOptionPane.showMessageDialog(this, "Select or connect a serial port first.")
            return
        }

        showConnectionIndicator(
            ConnectionIndicatorState.SEARCHING,
            "Reloading $selectedPath as the active SignalSlinger...",
        )
        runInBackground("Reloading $selectedPath...") {
            val reloadedConnection = loadPort(selectedPath)
            val loadResult = reloadedConnection.copy(
                loadLogTitle = "Auto Detect Reload",
                loadLogLeadEntries = listOf(
                    DesktopLogEntry(
                        "Reloading $selectedPath as the active device without scanning other ports.",
                        DesktopLogCategory.APP,
                    ),
                    DesktopLogEntry(
                        "Loaded ${reloadedConnection.result.commandsSent.size} command(s) and received ${reloadedConnection.result.linesReceived.size} line(s).",
                        DesktopLogCategory.APP,
                    ),
                ),
            )
            SwingUtilities.invokeLater {
                autoDetectNoDeviceFound = false
                selectPort(selectedPath)
                applyLoadedConnection(loadResult)
                setStatus("Reloaded settings from $selectedPath.")
            }
        }
    }

    private fun preferredReloadPortPath(): String? {
        val selectedPath = selectedProbe()?.portInfo?.systemPortPath
        val selectedProbeState = selectedPath?.let { knownProbeResults[it]?.state }
        if (selectedPath != null && selectedProbeState == PortProbeState.DETECTED) {
            return preferredDetectedPortPath(selectedPath)
        }

        val connectedPath = currentConnectedPortPath
        if (connectedPath != null) {
            return preferredDetectedPortPath(connectedPath) ?: connectedPath
        }

        return firstKnownDetectedPortPath()
            ?: selectedPath
    }

    private fun firstKnownDetectedPortPath(): String? {
        val availablePorts = DesktopSmartPollingPolicy.canonicalizePorts(
            SignalSlingerPortDiscovery.listAvailablePorts().map { it.portInfo },
        )
        val detectedPath = availablePorts.firstOrNull { portInfo ->
            knownProbeResults[portInfo.systemPortPath]?.state == PortProbeState.DETECTED
        }?.systemPortPath
        return preferredDetectedPortPath(detectedPath)
    }

    private fun preferredDetectedPortPath(path: String?): String? {
        val availablePorts = DesktopSmartPollingPolicy.canonicalizePorts(
            SignalSlingerPortDiscovery.listAvailablePorts().map { it.portInfo },
        )
        return DesktopSmartPollingPolicy.preferredPortPath(availablePorts, path)
    }

    private fun connectAndLoadSelectedPort() {
        val probe = selectedProbe()
        if (probe == null) {
            JOptionPane.showMessageDialog(this, "Select a serial port first.")
            return
        }

        runInBackground("Loading settings from ${probe.portInfo.systemPortPath}...") {
            val loadResult = loadPort(probe.portInfo.systemPortPath)
            SwingUtilities.invokeLater {
                applyLoadedConnection(loadResult)
                setStatus("Loaded settings from ${probe.portInfo.systemPortPath}.")
            }
        }
    }

    private fun cloneTimedEventSettings(skipClockWarning: Boolean = false) {
        val transport = currentTransport
        val state = currentState
        val templateSettings = cloneTemplateSettings
        if (transport == null || state == null || loadedSnapshot == null || templateSettings == null) {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        if (!skipClockWarning) {
            when (
                maybeShowCloneClockReminder(
                    message = "Device time is not synchronized with system time.\n\n" +
                        "Syncing before cloning is typical protocol and strongly recommended.",
                    title = "Clone Reminder",
                    continueActionLabel = "Continue Clone",
                    syncActionLabel = "Sync then Clone",
                )
            ) {
                ClockWarningChoice.CONTINUE -> Unit
                ClockWarningChoice.SYNC_THEN_CONTINUE -> {
                    syncDeviceTimeToSystem {
                        if (it) {
                            Timer(1) { cloneTimedEventSettings(skipClockWarning = true) }.apply {
                                isRepeats = false
                                start()
                            }
                        }
                    }
                    return
                }
                ClockWarningChoice.CANCEL -> {
                    setStatus("Clone cancelled so device time can be synchronized first.")
                    return
                }
            }
        }
        updateCloneTemplateLabel(
            "Clone template locked. Display shows attached device state.",
            Color(0x9A, 0x67, 0x11),
        )

        runInBackground("Cloning Timed Event Settings...") {
            val targetRefresh = DeviceSessionController.refreshFromDevice(
                state,
                transport,
                startEditing = true,
                progress = { completed, total ->
                    setBusyProgressRange(0, 20, completed, total, commandProgressLabel(completed, total))
                },
            )
            val targetSnapshot = requireNotNull(targetRefresh.state.snapshot)
            val editable = buildCloneEditableSettings(targetSnapshot.settings, templateSettings)
            val validated = editable.toValidatedDeviceSettings()
            val writePlan = WritePlanner.create(targetSnapshot.settings, validated)
            val result = DeviceSessionController.submitEdits(
                targetRefresh.state,
                editable,
                transport,
                progress = { completed, total ->
                    setBusyProgressRange(20, 70, completed, total, commandProgressLabel(completed, total))
                },
            )
            val verificationFailures = result.verifications.filter { !it.verified }
            val refreshed = DeviceSessionController.refreshFromDevice(
                result.state,
                transport,
                startEditing = true,
                progress = { completed, total ->
                    setBusyProgressRange(70, 90, completed, total, commandProgressLabel(completed, total))
                },
            )
            var finalState = refreshed.state
            var syncOperation: TimeSyncOperationResult? = null

            if (verificationFailures.isEmpty() && refreshed.state.snapshot?.capabilities?.supportsScheduling == true) {
                setBusyProgress(94, 100, "Syncing device time")
                syncOperation = performAlignedTimeSync(
                    transport = transport,
                    state = refreshed.state,
                    snapshot = requireNotNull(refreshed.state.snapshot),
                )
                finalState = syncOperation.finalAttempt.state
            }
            setBusyProgress(100, 100, "Done")

            currentState = finalState
            loadedSnapshot = finalState.snapshot
            val cloneSucceeded =
                verificationFailures.isEmpty() &&
                    (syncOperation == null || syncOperation.succeeded)

            SwingUtilities.invokeLater {
                if (syncOperation != null) {
                    deviceTimeOffset = Duration.ofMillis(-(syncOperation.finalAttempt.phaseErrorMillis ?: 0L))
                    lastDeviceTimeCheckAtMs = System.currentTimeMillis()
                    applySnapshotToForm(syncOperation.finalAttempt.state.snapshot, recalculateClockOffset = false)
                } else {
                    applySnapshotToForm(finalState.snapshot)
                }
                appendWriteLog(
                    title = "Clone",
                    writePlan = writePlan,
                    result = result,
                    preRefreshResult = targetRefresh,
                    refreshResult = refreshed,
                    comparedFieldKeys = cloneComparedFieldKeys(),
                )
                syncOperation?.let {
                    appendSyncLog(
                        title = "Clone Time Sync",
                        attempts = it.attempts,
                        latencySamples = it.latencySamples,
                    )
                }
                if (cloneSucceeded) {
                    updateCloneTemplateLabel(
                        "Clone template locked. Display shows attached device state.",
                        Color(0x9A, 0x67, 0x11),
                    )
                    showConnectionIndicator(
                        ConnectionIndicatorState.CONNECTED,
                        if (writePlan.changes.isEmpty()) {
                            "Clone succeeded. Timed Event Settings already matched ${currentConnectedPortPath.orEmpty()} and time was synchronized."
                        } else {
                            "Clone succeeded on ${currentConnectedPortPath.orEmpty()} and time was synchronized."
                        },
                    )
                    setStatus("Clone succeeded.")
                } else {
                    updateCloneTemplateLabel(
                        "Clone template locked. Display shows attached device state.",
                        Color(0x9A, 0x67, 0x11),
                    )
                    showConnectionIndicator(
                        ConnectionIndicatorState.DISCONNECTED,
                        "Clone failed on ${currentConnectedPortPath.orEmpty()}. Check verification or sync results in the log.",
                    )
                    setStatus("Clone failed.")
                    JOptionPane.showMessageDialog(
                        this,
                        "Clone failed. Review the log, then you can retry Clone.",
                        "Clone Failed",
                        JOptionPane.WARNING_MESSAGE,
                    )
                }
            }
        }
    }

    private fun installCloneButtonLongPressHandler() {
        submitButton.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (event.button != MouseEvent.BUTTON1 || !submitButton.isEnabled) {
                        return
                    }
                    cloneButtonLongPressTimer?.stop()
                    cloneButtonLongPressTimer = Timer(CLONE_BUTTON_LONG_PRESS_MS) {
                        cloneButtonLongPressTimer = null
                        if (
                            submitButton.model.isPressed &&
                            submitButton.model.isArmed &&
                            !backgroundWorkInProgress
                        ) {
                            suppressNextCloneAction = true
                            reloadCloneTemplateFromAttachedDevice()
                        }
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }

                override fun mouseReleased(event: MouseEvent) {
                    cloneButtonLongPressTimer?.stop()
                    cloneButtonLongPressTimer = null
                }

                override fun mouseExited(event: MouseEvent) {
                    cloneButtonLongPressTimer?.stop()
                    cloneButtonLongPressTimer = null
                }
            },
        )
    }

    private fun reloadCloneTemplateFromAttachedDevice() {
        val portPath = currentConnectedPortPath
        if (portPath == null || currentState?.connectionState != ConnectionState.CONNECTED) {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        when (
            maybeShowCloneClockReminder(
                message = "Device time is not synchronized with system time.\n\n" +
                    "Syncing before reloading the clone template is typical protocol and strongly recommended.",
                title = "Clone Template Reminder",
                continueActionLabel = "Continue Reload",
                syncActionLabel = "Sync then Reload",
            )
        ) {
            ClockWarningChoice.CONTINUE -> Unit
            ClockWarningChoice.SYNC_THEN_CONTINUE -> {
                syncDeviceTimeToSystem {
                    if (it) {
                        Timer(1) { reloadCloneTemplateFromAttachedDevice() }.apply {
                            isRepeats = false
                            start()
                        }
                    }
                }
                return
            }
            ClockWarningChoice.CANCEL -> {
                setStatus("Clone template reload cancelled so device time can be synchronized first.")
                return
            }
        }

        runInBackground("Reloading clone template from attached device...") {
            val refreshedConnection = loadPort(portPath).copy(
                loadLogTitle = "Clone Template Reload",
                loadLogLeadEntries = listOf(
                    DesktopLogEntry(
                        "Reloading clone template from the attached device before updating the template.",
                        DesktopLogCategory.APP,
                    ),
                ),
            )

            SwingUtilities.invokeLater {
                applyLoadedConnection(refreshedConnection)
                refreshedConnection.result.state.snapshot?.settings?.let(::rememberCloneTemplateFrom)
                updateCloneTemplateLabel(
                    "Clone template reloaded from attached device.",
                    Color(0x0B, 0x3D, 0x91),
                )
                setStatus("Clone template reloaded from attached device.")
            }
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
            patternCodeSpeedWpm = targetBaseSettings.patternCodeSpeedWpm.let { targetPatternSpeed ->
                SettingsField(
                    "patternCodeSpeedWpm",
                    "Pattern Speed",
                    targetPatternSpeed,
                    cloneTemplatePatternSpeedFor(templateSettings) ?: targetPatternSpeed,
                )
            },
            lowFrequencyHz = SettingsField("lowFrequencyHz", "Frequency 1 (FRE 1)", targetBaseSettings.lowFrequencyHz, templateSettings.lowFrequencyHz),
            mediumFrequencyHz = SettingsField("mediumFrequencyHz", "Frequency 2 (FRE 2)", targetBaseSettings.mediumFrequencyHz, templateSettings.mediumFrequencyHz),
            highFrequencyHz = SettingsField("highFrequencyHz", "Frequency 3 (FRE 3)", targetBaseSettings.highFrequencyHz, templateSettings.highFrequencyHz),
            beaconFrequencyHz = SettingsField("beaconFrequencyHz", "Frequency B (FRE B)", targetBaseSettings.beaconFrequencyHz, templateSettings.beaconFrequencyHz),
        )
    }

    private fun cloneComparedFieldKeys(): List<SettingKey> {
        return buildList {
            add(SettingKey.STATION_ID)
            add(SettingKey.EVENT_TYPE)
            add(SettingKey.ID_CODE_SPEED_WPM)
            if (cloneTemplateSettings?.let { DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(it.eventType) } == true) {
                add(SettingKey.PATTERN_CODE_SPEED_WPM)
            }
            add(SettingKey.START_TIME)
            add(SettingKey.FINISH_TIME)
            add(SettingKey.DAYS_TO_RUN)
            add(SettingKey.LOW_FREQUENCY_HZ)
            add(SettingKey.MEDIUM_FREQUENCY_HZ)
            add(SettingKey.HIGH_FREQUENCY_HZ)
            add(SettingKey.BEACON_FREQUENCY_HZ)
        }
    }

    private fun applyImmediateEdit(
        description: String,
        updatesTimedEventTemplate: Boolean,
        forceWriteKeys: Set<SettingKey> = emptySet(),
        showBusyDialog: Boolean = true,
        buildEditable: (DeviceSettings) -> EditableDeviceSettings,
    ) {
        if (updatingForm || backgroundWorkInProgress) {
            return
        }

        val transport = currentTransport ?: return
        val state = currentState ?: return
        val snapshot = loadedSnapshot ?: return
        val baseSettings = snapshot.settings

        val editable = try {
            buildEditable(baseSettings)
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(this, exception.message ?: "Invalid input.")
            applySnapshotToForm(snapshot)
            return
        }

        val validatedSettings = try {
            editable.toValidatedDeviceSettings()
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(this, exception.message ?: "Invalid input.")
            applySnapshotToForm(snapshot)
            return
        }

        val writePlan = WritePlanner.create(baseSettings, validatedSettings, forceWriteKeys = forceWriteKeys)
        if (writePlan.changes.isEmpty()) {
            return
        }

        showConnectionIndicator(
            ConnectionIndicatorState.SEARCHING,
            "Applying $description on ${currentConnectedPortPath.orEmpty()} and waiting for confirmation...",
        )
        runInBackground("Applying $description...", showBusyDialog = showBusyDialog) {
            val result = DeviceSessionController.submitEdits(
                state,
                editable,
                transport,
                forceWriteKeys = forceWriteKeys,
                progress = { completed, total ->
                    setBusyProgressRange(0, 75, completed, total, commandProgressLabel(completed, total))
                },
            )
            val verificationFailures = result.verifications.filter { !it.verified }
            val changeSucceeded = verificationFailures.isEmpty()
            val refreshed = if (updatesTimedEventTemplate && changeSucceeded) {
                DeviceSessionController.refreshFromDevice(
                    result.state,
                    transport,
                    startEditing = true,
                    progress = { completed, total ->
                        setBusyProgressRange(75, 95, completed, total, commandProgressLabel(completed, total))
                    },
                )
            } else {
                null
            }
            if (refreshed == null) {
                setBusyProgress(100, 100, "Done")
            } else {
                setBusyProgress(97, 100, "Checking device time")
            }
            val refreshClockSample = refreshed?.let { postLoadClockSample(transport, it.state.snapshot) }
            if (refreshClockSample != null) {
                setBusyProgress(100, 100, "Done")
            }
            val refreshedWithClock = refreshed?.let { mergeLoadResults(it, refreshClockSample?.first) }
            val finalState = refreshedWithClock?.state ?: result.state
            val finalSnapshot = if (changeSucceeded) {
                mergeSnapshotWithVerifiedWrite(
                    snapshot = finalState.snapshot,
                    expectedSettings = validatedSettings,
                    writePlan = writePlan,
                )
            } else {
                finalState.snapshot
            }
            val renderedState = if (finalSnapshot != finalState.snapshot) {
                finalState.copy(snapshot = finalSnapshot)
            } else {
                finalState
            }
            currentState = renderedState
            loadedSnapshot = renderedState.snapshot

            if (updatesTimedEventTemplate && changeSucceeded) {
                renderedState.snapshot?.settings?.let(::rememberCloneTemplateFrom)
            }

            SwingUtilities.invokeLater {
                refreshClockSample?.second?.let(::applyClockDisplayAnchor)
                applySnapshotToForm(
                    renderedState.snapshot,
                    recalculateClockOffset = refreshClockSample == null,
                )
                if (updatesTimedEventTemplate && changeSucceeded) {
                    updateCloneTemplateLabel(
                        "Clone template updated from current device.",
                        Color(0x0B, 0x3D, 0x91),
                    )
                }
                appendWriteLog("Apply $description", writePlan, result, refreshResult = refreshedWithClock)
                if (changeSucceeded) {
                    showConnectionIndicator(
                        ConnectionIndicatorState.CONNECTED,
                        "Applied $description on ${currentConnectedPortPath.orEmpty()}.",
                    )
                    setStatus("Applied $description.")
                } else {
                    showConnectionIndicator(
                        ConnectionIndicatorState.DISCONNECTED,
                        "Change failed verification: $description",
                    )
                    setStatus("Change failed verification: $description.")
                }
            }
        }
    }

    private fun mergeSnapshotWithVerifiedWrite(
        snapshot: DeviceSnapshot?,
        expectedSettings: DeviceSettings,
        writePlan: WritePlan,
    ): DeviceSnapshot? {
        if (snapshot == null || writePlan.changes.isEmpty()) {
            return snapshot
        }

        val changedKeys = writePlan.changes.mapTo(linkedSetOf()) { it.fieldKey }
        val mergedSettings = mergeExpectedSettings(snapshot.settings, expectedSettings, changedKeys)
        val mergedStatus = when {
            SettingKey.START_TIME in changedKeys || SettingKey.FINISH_TIME in changedKeys -> {
                val eventDisabled = mergedSettings.startTimeCompact != null &&
                    mergedSettings.startTimeCompact == mergedSettings.finishTimeCompact
                if (eventDisabled) {
                    snapshot.status.copy(
                        eventEnabled = false,
                        eventStateSummary = "Disabled",
                        eventStartsInSummary = null,
                        eventDurationSummary = "Disabled",
                    )
                } else {
                    snapshot.status
                }
            }

            else -> snapshot.status
        }

        return snapshot.copy(
            status = mergedStatus,
            settings = mergedSettings,
        )
    }

    private fun mergeExpectedSettings(
        current: DeviceSettings,
        expected: DeviceSettings,
        changedKeys: Set<SettingKey>,
    ): DeviceSettings {
        return current.copy(
            stationId = if (SettingKey.STATION_ID in changedKeys) expected.stationId else current.stationId,
            eventType = if (SettingKey.EVENT_TYPE in changedKeys) expected.eventType else current.eventType,
            foxRole = if (SettingKey.FOX_ROLE in changedKeys) expected.foxRole else current.foxRole,
            patternText = if (SettingKey.PATTERN_TEXT in changedKeys) expected.patternText else current.patternText,
            idCodeSpeedWpm = if (SettingKey.ID_CODE_SPEED_WPM in changedKeys) expected.idCodeSpeedWpm else current.idCodeSpeedWpm,
            patternCodeSpeedWpm = if (SettingKey.PATTERN_CODE_SPEED_WPM in changedKeys) {
                expected.patternCodeSpeedWpm
            } else {
                current.patternCodeSpeedWpm
            },
            currentTimeCompact = if (SettingKey.CURRENT_TIME in changedKeys) {
                expected.currentTimeCompact
            } else {
                current.currentTimeCompact
            },
            startTimeCompact = if (SettingKey.START_TIME in changedKeys) expected.startTimeCompact else current.startTimeCompact,
            finishTimeCompact = if (SettingKey.FINISH_TIME in changedKeys) expected.finishTimeCompact else current.finishTimeCompact,
            daysToRun = if (SettingKey.DAYS_TO_RUN in changedKeys) expected.daysToRun else current.daysToRun,
            defaultFrequencyHz = if (SettingKey.DEFAULT_FREQUENCY_HZ in changedKeys) {
                expected.defaultFrequencyHz
            } else {
                current.defaultFrequencyHz
            },
            lowFrequencyHz = if (SettingKey.LOW_FREQUENCY_HZ in changedKeys) expected.lowFrequencyHz else current.lowFrequencyHz,
            mediumFrequencyHz = if (SettingKey.MEDIUM_FREQUENCY_HZ in changedKeys) {
                expected.mediumFrequencyHz
            } else {
                current.mediumFrequencyHz
            },
            highFrequencyHz = if (SettingKey.HIGH_FREQUENCY_HZ in changedKeys) expected.highFrequencyHz else current.highFrequencyHz,
            beaconFrequencyHz = if (SettingKey.BEACON_FREQUENCY_HZ in changedKeys) {
                expected.beaconFrequencyHz
            } else {
                current.beaconFrequencyHz
            },
            lowBatteryThresholdVolts = if (SettingKey.LOW_BATTERY_THRESHOLD_VOLTS in changedKeys) {
                expected.lowBatteryThresholdVolts
            } else {
                current.lowBatteryThresholdVolts
            },
            externalBatteryControlMode = if (SettingKey.EXTERNAL_BATTERY_CONTROL_MODE in changedKeys) {
                expected.externalBatteryControlMode
            } else {
                current.externalBatteryControlMode
            },
            transmissionsEnabled = if (SettingKey.TRANSMISSIONS_ENABLED in changedKeys) {
                expected.transmissionsEnabled
            } else {
                current.transmissionsEnabled
            },
        )
    }

    private fun applyStationIdChange() {
        applyImmediateEdit("Station ID", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                stationId = SettingsField("stationId", "Station ID", base.stationId, stationIdField.text.trim()),
            )
        }
    }

    private fun applyEventTypeChange() {
        applyImmediateEdit("Event Type", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                eventType = SettingsField("eventType", "Event Type", base.eventType, eventTypeCombo.selectedItem as EventType),
            )
        }
    }

    private fun applyFoxRoleChange() {
        applyImmediateEdit("Fox Role", updatesTimedEventTemplate = false) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                foxRole = SettingsField("foxRole", "Fox Role (FOX)", base.foxRole, foxRoleCombo.selectedItem as FoxRole?),
            )
        }
    }

    private fun applyPatternTextChange() {
        val eventType = loadedSnapshot?.settings?.eventType ?: (eventTypeCombo.selectedItem as? EventType ?: EventType.NONE)
        if (!DesktopInputSupport.patternTextIsEditable(eventType)) {
            return
        }
        applyImmediateEdit("Pattern Text", updatesTimedEventTemplate = false) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                patternText = SettingsField(
                    "patternText",
                    "Pattern Text",
                    base.patternText,
                    patternTextField.text.trim().ifBlank { null },
                ),
            )
        }
    }

    private fun applyIdSpeedChange() {
        applyImmediateEdit("ID Speed", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                idCodeSpeedWpm = SettingsField(
                    "idCodeSpeedWpm",
                    "ID Speed",
                    base.idCodeSpeedWpm,
                    DesktopInputSupport.parseCodeSpeedWpm(idSpeedField.selectedItem as String),
                ),
            )
        }
    }

    private fun applyPatternSpeedChange() {
        val updatesCloneTemplate = DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(
            currentConnectedTimedSettings().eventType,
        )
        applyImmediateEdit("Pattern Speed", updatesTimedEventTemplate = updatesCloneTemplate) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                patternCodeSpeedWpm = SettingsField(
                    "patternCodeSpeedWpm",
                    "Pattern Speed",
                    base.patternCodeSpeedWpm,
                    DesktopInputSupport.parseCodeSpeedWpm(currentPatternSpeedField().selectedItem as String),
                ),
            )
        }
    }

    private fun applyStartTimeChange() {
        if (isScheduleInteractionSuppressed()) {
            return
        }
        val connectedTimedSettings = currentConnectedTimedSettings()
        val selectedStartTimeCompact = selectedDateTimeCompactForComparison(
            startTimeSpinner,
            connectedTimedSettings.startTimeCompact,
        )
        if (selectedStartTimeCompact == connectedTimedSettings.startTimeCompact) {
            clearPendingImmediateEdit(startTimeSpinner)
            return
        }
        clearRelativeScheduleDisplayOverrides()
        val normalizedStartTime = normalizedStartTimeSelectionForCommit(
            originalStartTimeCompact = connectedTimedSettings.startTimeCompact,
            selectedCompact = selectedStartTimeCompact,
        ) ?: return
        val currentDurationFinishTime = currentFinishTimeCompactForStartAdjustment(connectedTimedSettings.finishTimeCompact)
        chooseStartTimeFinishAdjustmentDuration(
            currentStartTimeCompact = connectedTimedSettings.startTimeCompact,
            currentFinishTimeCompact = currentDurationFinishTime,
            proposedStartTimeCompact = normalizedStartTime,
            onCancel = {
                restoreAbsoluteStartTimeEditor(connectedTimedSettings.startTimeCompact)
            },
        ) { choice ->
            if (choice.disablesEvent) {
                disableEventViaStartTimeCommand()
                return@chooseStartTimeFinishAdjustmentDuration
            }
            val chosenDuration = choice.duration ?: return@chooseStartTimeFinishAdjustmentDuration
            chooseScheduleChangeDurationResolution(
                currentDaysToRun = connectedTimedSettings.daysToRun,
                proposedDuration = chosenDuration,
                onCancel = {
                    restoreAbsoluteStartTimeEditor(connectedTimedSettings.startTimeCompact)
                },
            ) { preserveDaysToRun, effectiveDuration ->
                val editRequest = ScheduleSubmitSupport.absoluteStartEdit(
                    currentSettings = connectedTimedSettings,
                    normalizedStartTime = normalizedStartTime,
                    requestedFinishTimeCompact = DesktopInputSupport.finishTimeCompactFromStart(
                        startTimeCompact = normalizedStartTime,
                        duration = effectiveDuration ?: chosenDuration,
                    ),
                    preserveDaysToRun = preserveDaysToRun,
                )
                applyImmediateEdit(
                    "Start Time",
                    updatesTimedEventTemplate = true,
                    forceWriteKeys = editRequest.forceWriteKeys,
                ) { base ->
                    EditableDeviceSettings.fromDeviceSettings(base).copy(
                        startTimeCompact = SettingsField(
                            "startTimeCompact",
                            "Start Time",
                            base.startTimeCompact,
                            editRequest.startTimeCompact,
                        ),
                        finishTimeCompact = SettingsField(
                            "finishTimeCompact",
                            "Finish Time",
                            base.finishTimeCompact,
                            editRequest.finishTimeCompact,
                        ),
                    )
                }
            }
        }
    }

    private fun applyFinishTimeChange() {
        if (isScheduleInteractionSuppressed()) {
            return
        }
        val connectedTimedSettings = currentConnectedTimedSettings()
        val selectedFinishTimeCompact = selectedDateTimeCompactForComparison(
            finishTimeSpinner,
            connectedTimedSettings.finishTimeCompact,
        )
        if (selectedFinishTimeCompact == connectedTimedSettings.finishTimeCompact) {
            clearPendingImmediateEdit(finishTimeSpinner)
            return
        }
        clearRelativeScheduleDisplayOverrides()
        val normalizedFinishTime = normalizedFinishTimeSelectionForCommit(
            originalStartTimeCompact = connectedTimedSettings.startTimeCompact,
            originalFinishTimeCompact = connectedTimedSettings.finishTimeCompact,
            selectedCompact = selectedFinishTimeCompact,
        ) ?: return
        val proposedDuration = DesktopInputSupport.validEventDuration(
            connectedTimedSettings.startTimeCompact,
            normalizedFinishTime,
        )
        chooseScheduleChangeDurationResolution(
            currentDaysToRun = connectedTimedSettings.daysToRun,
            proposedDuration = proposedDuration,
            onCancel = {
                restoreAbsoluteFinishTimeEditor(connectedTimedSettings.finishTimeCompact)
            },
        ) { preserveDaysToRun, effectiveDuration ->
            val editRequest = ScheduleSubmitSupport.absoluteFinishEditWithDurationOverride(
                currentSettings = connectedTimedSettings,
                normalizedFinishTime = normalizedFinishTime,
                requestedDurationOverride = effectiveDuration?.takeIf { it != proposedDuration },
                preserveDaysToRun = preserveDaysToRun,
            )
            applyImmediateEdit(
                "Finish Time",
                updatesTimedEventTemplate = true,
                forceWriteKeys = editRequest.forceWriteKeys,
            ) { base ->
                EditableDeviceSettings.fromDeviceSettings(base).copy(
                    startTimeCompact = SettingsField(
                        "startTimeCompact",
                        "Start Time",
                        base.startTimeCompact,
                        editRequest.startTimeCompact,
                    ),
                    finishTimeCompact = SettingsField(
                        "finishTimeCompact",
                        "Finish Time",
                        base.finishTimeCompact,
                        editRequest.finishTimeCompact,
                    ),
                )
            }
        }
    }

    private fun showLastsDurationDialog() {
        if (isScheduleInteractionSuppressed() || !canEditLastsDuration()) {
            return
        }
        val snapshot = loadedSnapshot ?: return
        val initialDuration =
            DesktopInputSupport.validEventDuration(
                snapshot.settings.startTimeCompact,
                snapshot.settings.finishTimeCompact,
            ) ?: Duration.ofMinutes(displayPreferences.defaultEventLengthMinutes.toLong())
        val initialMinutes = DesktopInputSupport.roundDurationMinutesToNearestFive(initialDuration).toMinutes()
        val hourSpinner = JSpinner(SpinnerNumberModel((initialMinutes / 60).toInt().coerceIn(0, 480), 0, 480, 1))
        val minuteSpinner = JSpinner(SpinnerNumberModel((initialMinutes % 60).toInt().coerceIn(0, 55), 0, 55, 5))
        val summaryLabel = JLabel().apply {
            foreground = cloneAccentColor
        }

        fun selectedMinutes(): Int {
            val hours = (hourSpinner.value as? Number)?.toInt()?.coerceIn(0, 480) ?: 0
            val minutes = (minuteSpinner.value as? Number)?.toInt()?.coerceIn(0, 59) ?: 0
            return (hours * 60) + minutes
        }

        fun refreshSummary() {
            val totalMinutes = selectedMinutes()
            summaryLabel.text = if (totalMinutes > 0) {
                "Event duration: ${DesktopInputSupport.formatDurationHoursMinutesCompact(Duration.ofMinutes(totalMinutes.toLong()))}"
            } else {
                "Choose at least 5 minutes."
            }
        }

        hourSpinner.addChangeListener { refreshSummary() }
        minuteSpinner.addChangeListener { refreshSummary() }
        refreshSummary()

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(summaryLabel)
            add(Box.createVerticalStrut(10))
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(JLabel("Hours"))
                    add(Box.createHorizontalStrut(6))
                    add(hourSpinner)
                    add(Box.createHorizontalStrut(12))
                    add(JLabel("Minutes"))
                    add(Box.createHorizontalStrut(6))
                    add(minuteSpinner)
                },
            )
        }

        lastsDurationDialogOpen = true
        val result =
            try {
                JOptionPane.showConfirmDialog(
                    this,
                    panel,
                    "Lasts",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE,
                )
            } finally {
                lastsDurationDialogOpen = false
            }
        if (result != JOptionPane.OK_OPTION) {
            return
        }

        val requestedMinutes = selectedMinutes()
        if (requestedMinutes <= 0) {
            JOptionPane.showMessageDialog(
                this,
                "Event duration must be at least 5 minutes.",
                "Lasts",
                JOptionPane.WARNING_MESSAGE,
            )
            return
        }

        val requestedDuration = Duration.ofMinutes(requestedMinutes.toLong())
        chooseScheduleChangeDurationResolution(
            currentDaysToRun = snapshot.settings.daysToRun,
            proposedDuration = requestedDuration,
            onCancel = {},
        ) { preserveDaysToRun, effectiveDuration ->
            clearRelativeScheduleDisplayOverrides()
            val editRequest =
                try {
                    ScheduleSubmitSupport.absoluteDurationEdit(
                        currentSettings = snapshot.settings,
                        requestedDuration = effectiveDuration ?: requestedDuration,
                        preserveDaysToRun = preserveDaysToRun,
                    )
                } catch (exception: IllegalArgumentException) {
                    JOptionPane.showMessageDialog(
                        this,
                        exception.message ?: "Invalid Lasts value.",
                        "Lasts",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return@chooseScheduleChangeDurationResolution
                } catch (exception: IllegalStateException) {
                    JOptionPane.showMessageDialog(
                        this,
                        exception.message ?: "Invalid Lasts value.",
                        "Lasts",
                        JOptionPane.WARNING_MESSAGE,
                    )
                    return@chooseScheduleChangeDurationResolution
                }
            applyImmediateEdit(
                "Lasts",
                updatesTimedEventTemplate = true,
                forceWriteKeys = editRequest.forceWriteKeys,
            ) { base ->
                EditableDeviceSettings.fromDeviceSettings(base).copy(
                    startTimeCompact = SettingsField(
                        "startTimeCompact",
                        "Start Time",
                        base.startTimeCompact,
                        editRequest.startTimeCompact,
                    ),
                    finishTimeCompact = SettingsField(
                        "finishTimeCompact",
                        "Finish Time",
                        base.finishTimeCompact,
                        editRequest.finishTimeCompact,
                    ),
                )
            }
        }
    }

    private fun normalizedStartTimeSelectionForCommit(
        originalStartTimeCompact: String?,
        selectedCompact: String? = null,
    ): String? {
        val selectedCompact = selectedCompact
            ?: takePendingDateTimeCompact(startTimeSpinner)
            ?: selectedDateTimeCompact(startTimeSpinner, originalStartTimeCompact)
        val validatedSelected = try {
            DesktopInputSupport.validateStartTimeForWrite(selectedCompact)
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(this, exception.message ?: "Invalid Start Time.")
            return null
        } ?: return null

        val validatedDeviceTime = try {
            DesktopInputSupport.validateCurrentTimeForWrite(displayedDeviceTimeCompact())
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(this, exception.message ?: "Set Device Time first before changing Start Time.")
            return null
        }
        if (validatedDeviceTime == null) {
            JOptionPane.showMessageDialog(this, "Set Device Time first before changing Start Time.")
            return null
        }

        val minimumStart = DesktopInputSupport.minimumStartTimeBoundary(validatedDeviceTime, stepMinutes = 5)
        val selectedStart = DesktopInputSupport.parseCompactTimestamp(validatedSelected)
        return if (selectedStart.isBefore(minimumStart)) {
            JOptionPane.showMessageDialog(this, "Start Time can't be set earlier than Device Time.")
            setDateTimeSpinnerValue(startTimeSpinner, minimumStart)
            DesktopInputSupport.formatCompactTimestamp(minimumStart)
        } else {
            validatedSelected
        }
    }

    private fun normalizedFinishTimeSelectionForCommit(
        originalStartTimeCompact: String?,
        originalFinishTimeCompact: String?,
        selectedCompact: String? = null,
    ): String? {
        val validatedDeviceTime = try {
            DesktopInputSupport.validateCurrentTimeForWrite(displayedDeviceTimeCompact())
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(this, exception.message ?: "Set Device Time first before changing Finish Time.")
            return null
        }
        if (validatedDeviceTime == null) {
            JOptionPane.showMessageDialog(this, "Set Device Time first before changing Finish Time.")
            return null
        }

        val normalizedStart = normalizedStartTimeSelectionForCommit(originalStartTimeCompact) ?: return null
        val selectedCompact = selectedCompact
            ?: takePendingDateTimeCompact(finishTimeSpinner)
            ?: selectedDateTimeCompact(finishTimeSpinner, originalFinishTimeCompact)
        val validatedSelected = try {
            DesktopInputSupport.validateFinishTimeForWrite(selectedCompact)
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(this, exception.message ?: "Invalid Finish Time.")
            return null
        } ?: return null

        val minimumFinish = DesktopInputSupport.minimumFinishTimeBoundary(
            currentTimeCompact = validatedDeviceTime,
            startTimeCompact = normalizedStart,
        )
        val selectedFinish = DesktopInputSupport.parseCompactTimestamp(validatedSelected)
        return if (selectedFinish.isBefore(minimumFinish)) {
            JOptionPane.showMessageDialog(this, finishTimeLowerBoundMessage(
                deviceTime = DesktopInputSupport.parseCompactTimestamp(validatedDeviceTime),
                startTime = normalizedStart.let(DesktopInputSupport::parseCompactTimestamp),
            ))
            setDateTimeSpinnerValue(finishTimeSpinner, minimumFinish)
            DesktopInputSupport.formatCompactTimestamp(minimumFinish)
        } else {
            validatedSelected
        }
    }

    private fun applyDaysToRunChange() {
        val connectedTimedSettings = currentConnectedTimedSettings()
        val selectedDaysToRun = (daysField.value as? Number)?.toInt() ?: connectedTimedSettings.daysToRun
        val currentDuration = DesktopInputSupport.validEventDuration(
            connectedTimedSettings.startTimeCompact,
            connectedTimedSettings.finishTimeCompact,
        )
        chooseMultiDayDurationGuardHandling(
            options = ScheduleDurationGuardSupport.planForDirectDaysToRunChange(
                selectedDaysToRun = selectedDaysToRun,
                currentDuration = currentDuration,
            ),
            onCancel = {
                restoreDaysToRunEditor(connectedTimedSettings.daysToRun)
            },
        ) { option ->
            val resolution = ScheduleDurationGuardSupport.resolveDirectDaysToRunChange(
                selectedDaysToRun = selectedDaysToRun,
                currentDuration = currentDuration,
                selectedOption = option,
            )
            when (option?.choice) {
                MultiDayDurationGuardChoice.SHORTEN_DURATION -> {
                    clearRelativeScheduleDisplayOverrides()
                    val startTimeCompact = connectedTimedSettings.startTimeCompact ?: return@chooseMultiDayDurationGuardHandling
                    val editRequest = ScheduleSubmitSupport.daysToRunEdit(
                        currentSettings = connectedTimedSettings,
                        requestedDaysToRun = resolution.resultingDaysToRun,
                        requestedFinishTimeCompact = DesktopInputSupport.finishTimeCompactFromStart(
                            startTimeCompact = startTimeCompact,
                            duration = resolution.resultingDuration ?: return@chooseMultiDayDurationGuardHandling,
                        ),
                    )
                    applyImmediateEdit("Days To Run", updatesTimedEventTemplate = true) { base ->
                        EditableDeviceSettings.fromDeviceSettings(base).copy(
                            daysToRun = SettingsField("daysToRun", "Days To Run", base.daysToRun, editRequest.daysToRun),
                            startTimeCompact = SettingsField(
                                "startTimeCompact",
                                "Start Time",
                                base.startTimeCompact,
                                editRequest.startTimeCompact,
                            ),
                            finishTimeCompact = SettingsField(
                                "finishTimeCompact",
                                "Finish Time",
                                base.finishTimeCompact,
                                editRequest.finishTimeCompact,
                            ),
                        )
                    }
                }
                MultiDayDurationGuardChoice.SET_DAYS_TO_ONE -> {
                    val editRequest = ScheduleSubmitSupport.daysToRunEdit(
                        currentSettings = connectedTimedSettings,
                        requestedDaysToRun = resolution.resultingDaysToRun,
                    )
                    applyImmediateEdit("Days To Run", updatesTimedEventTemplate = true) { base ->
                        EditableDeviceSettings.fromDeviceSettings(base).copy(
                            daysToRun = SettingsField("daysToRun", "Days To Run", base.daysToRun, editRequest.daysToRun),
                        )
                    }
                }
                null -> {
                    val editRequest = ScheduleSubmitSupport.daysToRunEdit(
                        currentSettings = connectedTimedSettings,
                        requestedDaysToRun = resolution.resultingDaysToRun,
                    )
                    applyImmediateEdit("Days To Run", updatesTimedEventTemplate = true) { base ->
                        EditableDeviceSettings.fromDeviceSettings(base).copy(
                            daysToRun = SettingsField("daysToRun", "Days To Run", base.daysToRun, editRequest.daysToRun),
                        )
                    }
                }
            }
        }
    }

    private fun applyFrequency1Change() {
        val selectedFrequency = selectedFrequencyHz(frequency1Field)
        if (selectedFrequency == currentConnectedTimedSettings().lowFrequencyHz) {
            clearPendingImmediateEdit(frequency1Field)
            return
        }
        applyImmediateEdit("Frequency 1", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                lowFrequencyHz = SettingsField(
                    "lowFrequencyHz",
                    "Frequency 1 (FRE 1)",
                    base.lowFrequencyHz,
                    selectedFrequency,
                ),
            )
        }
    }

    private fun applyFrequency2Change() {
        val selectedFrequency = selectedFrequencyHz(frequency2Field)
        if (selectedFrequency == currentConnectedTimedSettings().mediumFrequencyHz) {
            clearPendingImmediateEdit(frequency2Field)
            return
        }
        applyImmediateEdit("Frequency 2", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                mediumFrequencyHz = SettingsField(
                    "mediumFrequencyHz",
                    "Frequency 2 (FRE 2)",
                    base.mediumFrequencyHz,
                    selectedFrequency,
                ),
            )
        }
    }

    private fun applyFrequency3Change() {
        val selectedFrequency = selectedFrequencyHz(frequency3Field)
        if (selectedFrequency == currentConnectedTimedSettings().highFrequencyHz) {
            clearPendingImmediateEdit(frequency3Field)
            return
        }
        applyImmediateEdit("Frequency 3", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                highFrequencyHz = SettingsField(
                    "highFrequencyHz",
                    "Frequency 3 (FRE 3)",
                    base.highFrequencyHz,
                    selectedFrequency,
                ),
            )
        }
    }

    private fun applyFrequencyBChange() {
        val selectedFrequency = selectedFrequencyHz(frequencyBField)
        if (selectedFrequency == currentConnectedTimedSettings().beaconFrequencyHz) {
            clearPendingImmediateEdit(frequencyBField)
            return
        }
        applyImmediateEdit("Frequency B", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                beaconFrequencyHz = SettingsField(
                    "beaconFrequencyHz",
                    "Frequency B (FRE B)",
                    base.beaconFrequencyHz,
                    selectedFrequency,
                ),
            )
        }
    }

    private fun applyBatteryThresholdChange() {
        applyImmediateEdit("Low Battery Threshold", updatesTimedEventTemplate = false) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                lowBatteryThresholdVolts = SettingsField(
                    "lowBatteryThresholdVolts",
                    "Low Battery Threshold",
                    base.lowBatteryThresholdVolts,
                    (batteryThresholdField.selectedItem as? String)
                        ?.removeSuffix("V")
                        ?.trim()
                        ?.toDouble(),
                ),
            )
        }
    }

    private fun applyBatteryModeChange() {
        applyImmediateEdit("Battery Mode", updatesTimedEventTemplate = false) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                externalBatteryControlMode = SettingsField(
                    "externalBatteryControlMode",
                    "External Battery Control",
                    base.externalBatteryControlMode,
                    batteryModeCombo.selectedItem as ExternalBatteryControlMode,
                ),
            )
        }
    }

    private fun refreshScheduleTimeEditorPresentation(snapshot: DeviceSnapshot?) {
        startTimeEditorHost.removeAll()
        finishTimeEditorHost.removeAll()

        if (displayPreferences.scheduleTimeInputMode == ScheduleTimeInputMode.RELATIVE) {
            startTimeRelativeField.text = DesktopInputSupport.formatRelativeTimeSelection(
                relativeEditorSelectionFor(ScheduleTimeField.START, snapshot),
            )
            startTimeAbsoluteMirrorField.text = DesktopInputSupport.formatCompactTimestampOrNotSet(snapshot?.settings?.startTimeCompact)
            finishTimeRelativeField.text = DesktopInputSupport.formatRelativeTimeSelection(
                relativeEditorSelectionFor(ScheduleTimeField.FINISH, snapshot),
            )
            finishTimeAbsoluteMirrorField.text = DesktopInputSupport.formatCompactTimestampOrNotSet(snapshot?.settings?.finishTimeCompact)
            startTimeEditorHost.add(startTimeRelativeEditorPanel)
            finishTimeEditorHost.add(finishTimeRelativeEditorPanel)
        } else {
            startTimeEditorHost.add(startTimeAbsoluteEditorRow)
            finishTimeEditorHost.add(finishTimeAbsoluteEditorRow)
        }

        startTimeEditorHost.revalidate()
        startTimeEditorHost.repaint()
        finishTimeEditorHost.revalidate()
        finishTimeEditorHost.repaint()
    }

    private fun relativeEditorSelectionFor(
        field: ScheduleTimeField,
        snapshot: DeviceSnapshot?,
    ): DesktopInputSupport.RelativeTimeSelection {
        return when (field) {
            ScheduleTimeField.START -> relativeStartDisplaySelectionOverride ?: relativeTimeSelectionFor(field, snapshot)
            ScheduleTimeField.FINISH -> relativeFinishDisplaySelectionOverride ?: defaultEventLengthRelativeSelection()
        }
    }

    private fun relativeTimeSelectionFor(
        field: ScheduleTimeField,
        snapshot: DeviceSnapshot?,
    ): DesktopInputSupport.RelativeTimeSelection {
        return DesktopInputSupport.deriveRelativeTimeSelection(
            baseCompact = relativeBaseCompact(field, snapshot),
            targetCompact = relativeTargetCompact(field, snapshot),
        )
    }

    private fun relativeBaseCompact(
        field: ScheduleTimeField,
        snapshot: DeviceSnapshot? = loadedSnapshot,
    ): String? {
        return when (field) {
            ScheduleTimeField.START -> displayedDeviceTimeCompact()
            ScheduleTimeField.FINISH -> snapshot?.settings?.startTimeCompact
        }
    }

    private fun relativeTargetCompact(
        field: ScheduleTimeField,
        snapshot: DeviceSnapshot? = loadedSnapshot,
    ): String? {
        return when (field) {
            ScheduleTimeField.START -> snapshot?.settings?.startTimeCompact
            ScheduleTimeField.FINISH -> snapshot?.settings?.finishTimeCompact
        }
    }

    private fun showRelativeTimePickerDialog(field: ScheduleTimeField) {
        if (isScheduleInteractionSuppressed()) {
            return
        }
        val snapshot = loadedSnapshot ?: run {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        if (!snapshot.capabilities.supportsScheduling) {
            JOptionPane.showMessageDialog(this, "Scheduling is not supported by the loaded snapshot.")
            return
        }

        val initialSelection = relativeEditorSelectionFor(field, snapshot)
        val hoursSpinner = JSpinner(SpinnerNumberModel(initialSelection.hours, 0, 480, 1))
        val minuteOptions = listOf("TOTH") + (0..55 step 5).map { it.toString().padStart(2, '0') }
        val minuteCombo = JComboBox(DefaultComboBoxModel(minuteOptions.toTypedArray())).apply {
            selectedItem = if (initialSelection.useTopOfHour) {
                "TOTH"
            } else {
                initialSelection.minutes.toString().padStart(2, '0')
            }
        }
        val previewLabel = JLabel().apply {
            foreground = cloneAccentColor
        }

        fun currentSelection(): DesktopInputSupport.RelativeTimeSelection {
            val hours = (hoursSpinner.value as? Number)?.toInt()?.coerceIn(0, 480) ?: 0
            val minuteChoice = minuteCombo.selectedItem as? String ?: "TOTH"
            return DesktopInputSupport.RelativeTimeSelection(
                hours = hours,
                minutes = if (minuteChoice == "TOTH") 0 else minuteChoice.toInt(),
                useTopOfHour = minuteChoice == "TOTH",
            )
        }

        fun refreshPreview() {
            previewLabel.text = DesktopInputSupport.formatRelativeTimeSelection(currentSelection())
        }

        hoursSpinner.addChangeListener { refreshPreview() }
        minuteCombo.addActionListener { refreshPreview() }
        refreshPreview()

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(previewLabel)
            add(Box.createVerticalStrut(10))
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    add(JLabel("+"))
                    add(Box.createHorizontalStrut(6))
                    add(hoursSpinner)
                    add(Box.createHorizontalStrut(6))
                    add(JLabel("hours"))
                    add(Box.createHorizontalStrut(10))
                    add(minuteCombo)
                    add(Box.createHorizontalStrut(6))
                    add(JLabel("minutes"))
                },
            )
            add(Box.createVerticalStrut(10))
            add(JLabel("TOTH = Top of the Hour"))
        }

        val result = JOptionPane.showConfirmDialog(
            this,
            panel,
            when (field) {
                ScheduleTimeField.START -> "Relative Start Time"
                ScheduleTimeField.FINISH -> "Relative Finish Time"
            },
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE,
        )
        if (result == JOptionPane.OK_OPTION) {
            applyRelativeScheduleTimeChange(field, currentSelection())
        }
    }

    private fun applyRelativeScheduleTimeChange(
        field: ScheduleTimeField,
        selection: DesktopInputSupport.RelativeTimeSelection,
    ) {
        if (updatingForm || backgroundWorkInProgress || isScheduleInteractionSuppressed()) {
            return
        }

        val transport = currentTransport ?: run {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        val state = currentState ?: run {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        val snapshot = loadedSnapshot ?: run {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        if (!snapshot.capabilities.supportsScheduling) {
            JOptionPane.showMessageDialog(this, "Scheduling is not supported by the loaded snapshot.")
            return
        }

        val description = when (field) {
            ScheduleTimeField.START -> "Relative Start Time"
            ScheduleTimeField.FINISH -> "Relative Finish Time"
        }
        if (field == ScheduleTimeField.START) {
            val proposedStartTimeCompact = DesktopInputSupport.relativeTargetTimeCompact(
                baseCompact = displayedDeviceTimeCompact(),
                selection = selection,
            )
            chooseStartTimeFinishAdjustmentDuration(
                currentStartTimeCompact = snapshot.settings.startTimeCompact,
                currentFinishTimeCompact = snapshot.settings.finishTimeCompact,
                proposedStartTimeCompact = proposedStartTimeCompact,
                onCancel = {
                    restoreRelativeStartTimeEditor()
                },
            ) { choice ->
                if (choice.disablesEvent) {
                    clearRelativeScheduleDisplayOverrides()
                    disableEventViaStartTimeCommand(transport, state)
                    return@chooseStartTimeFinishAdjustmentDuration
                }
                val chosenStartDuration = choice.duration ?: return@chooseStartTimeFinishAdjustmentDuration
                chooseScheduleChangeDurationResolution(
                    currentDaysToRun = snapshot.settings.daysToRun,
                    proposedDuration = chosenStartDuration,
                    onCancel = {
                        restoreRelativeStartTimeEditor()
                    },
                ) { preserveDaysToRun, effectiveDuration ->
                    applyRelativeStartTimeChange(
                        selection = selection,
                        chosenDuration = effectiveDuration ?: chosenStartDuration,
                        transport = transport,
                        state = state,
                        preservedDaysToRun = if (preserveDaysToRun) snapshot.settings.daysToRun else null,
                    )
                }
            }
            return
        }
        val proposedFinishTimeCompact = DesktopInputSupport.relativeTargetTimeCompact(
            baseCompact = snapshot.settings.startTimeCompact,
            selection = selection,
        )
        val proposedDuration = DesktopInputSupport.validEventDuration(
            snapshot.settings.startTimeCompact,
            proposedFinishTimeCompact,
        )
        chooseScheduleChangeDurationResolution(
            currentDaysToRun = snapshot.settings.daysToRun,
            proposedDuration = proposedDuration,
            onCancel = {
                restoreRelativeFinishTimeEditor()
            },
        ) { preserveDaysToRun, effectiveDuration ->
            val effectiveSelection = if (effectiveDuration != null && effectiveDuration != proposedDuration) {
                DesktopInputSupport.relativeTimeSelectionForDuration(effectiveDuration)
            } else {
                selection
            }
            val commands = ScheduleSubmitSupport.relativeFinishCommands(
                offsetCommand = DesktopInputSupport.formatRelativeTimeCommand(effectiveSelection),
                preservedDaysToRun = if (preserveDaysToRun) {
                    snapshot.settings.daysToRun
                } else {
                    null
                },
            )

            showConnectionIndicator(
                ConnectionIndicatorState.SEARCHING,
                "Applying $description on ${currentConnectedPortPath.orEmpty()} and waiting for confirmation...",
            )
            runInBackground("Applying $description...") {
                setBusyProgress(0, 100, "Sending relative schedule command")
                val sentAtMs = System.currentTimeMillis()
                transport.sendCommands(commands)
                val responseLines = transport.readAvailableLines()
                val receivedAtMs = System.currentTimeMillis()
                extractDeviceError(responseLines)?.let { error ->
                    throw IllegalStateException(error)
                }

                val updatedState = if (responseLines.isNotEmpty()) {
                    DeviceSessionWorkflow.ingestReportLines(state, responseLines)
                } else {
                    state
                }

                val refreshed = DeviceSessionController.refreshFromDevice(
                    updatedState,
                    transport,
                    startEditing = true,
                    progress = { completed, total ->
                        setBusyProgressRange(20, 95, completed, total, commandProgressLabel(completed, total))
                    },
                )
                setBusyProgress(97, 100, "Checking device time")
                val refreshClockSample = postLoadClockSample(transport, refreshed.state.snapshot)
                setBusyProgress(100, 100, "Done")
                val refreshedWithClock = mergeLoadResults(refreshed, refreshClockSample?.first)
                currentState = refreshedWithClock.state
                loadedSnapshot = refreshedWithClock.state.snapshot
                refreshedWithClock.state.snapshot?.settings?.let(::rememberCloneTemplateFrom)

                SwingUtilities.invokeLater {
                    relativeFinishDisplaySelectionOverride = effectiveSelection
                    refreshClockSample?.second?.let(::applyClockDisplayAnchor)
                    applySnapshotToForm(
                        refreshedWithClock.state.snapshot,
                        recalculateClockOffset = refreshClockSample == null,
                    )
                    updateCloneTemplateLabel(
                        "Clone template updated from current device.",
                        Color(0x0B, 0x3D, 0x91),
                    )
                    appendRelativeScheduleLog(
                        title = "Apply $description",
                        commands = commands,
                        sentAtMs = sentAtMs,
                        responseLines = responseLines,
                        receivedAtMs = receivedAtMs,
                        refreshResult = refreshedWithClock,
                    )
                    showConnectionIndicator(
                        ConnectionIndicatorState.CONNECTED,
                        "Applied $description on ${currentConnectedPortPath.orEmpty()}.",
                    )
                    setStatus("Applied $description.")
                }
            }
        }
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
            defaultEventLengthMinutes = displayPreferences.defaultEventLengthMinutes,
        )
        if (plannedOptions.size == 1) {
            onSelected(plannedOptions.single().toDesktopStartTimeFinishAdjustmentChoice())
            return
        }
        if (startTimeFinishAdjustmentDialogOpen) {
            return
        }
        startTimeFinishAdjustmentDialogOpen = true
        val selectedDuration = try {
            val options = plannedOptions.map { it.toDesktopStartTimeFinishAdjustmentChoice() }
            showStartTimeFinishAdjustmentDialog(options)
        } finally {
            startTimeFinishAdjustmentDialogOpen = false
        }
        if (selectedDuration != null) {
            onSelected(selectedDuration)
        } else {
            onCancel()
        }
    }

    private fun showStartTimeFinishAdjustmentDialog(
        options: List<StartTimeFinishAdjustmentChoice>,
    ): StartTimeFinishAdjustmentChoice? {
        var selectedChoice: StartTimeFinishAdjustmentChoice? = null
        val dialog = JDialog(this, "Adjust Finish Time", true).apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            isResizable = false
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
            add(JLabel("Choose desired event duration:").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(12))
            options.forEach { option ->
                add(
                    JButton(option.label).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                        addActionListener {
                            selectedChoice = option
                            dialog.dispose()
                        }
                    },
                )
                add(Box.createVerticalStrut(8))
            }
            add(
                JButton("Cancel").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    addActionListener { dialog.dispose() }
                },
            )
        }

        dialog.contentPane.add(content)
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
        return selectedChoice
    }

    private fun StartTimeAdjustmentOption.toDesktopStartTimeFinishAdjustmentChoice(): StartTimeFinishAdjustmentChoice {
        return when (kind) {
            StartTimeAdjustmentOptionKind.KEEP_EXISTING_DURATION -> StartTimeFinishAdjustmentChoice(
                label = "Keep ${DesktopInputSupport.formatDurationCompact(duration!!)}",
                duration = duration,
            )
            StartTimeAdjustmentOptionKind.ADJUST_FOR_DEFAULT_DURATION -> StartTimeFinishAdjustmentChoice(
                label = "Adjust for ${DesktopInputSupport.formatDefaultEventLength(displayPreferences.defaultEventLengthMinutes)}",
                duration = duration,
            )
            StartTimeAdjustmentOptionKind.LEAVE_FINISH_UNCHANGED -> StartTimeFinishAdjustmentChoice(
                label = "Leave Finish unchanged (${DesktopInputSupport.formatDurationCompact(duration!!)})",
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
        if (plan.autoChoice != null) {
            runAfterModalUiSettles {
                onSelected(plan.autoChoice)
            }
            return
        }
        val selectedChoiceIndex = showStartTimeDaysToRunDialog(plan.options.map { it.label })
        if (selectedChoiceIndex != null) {
            runAfterModalUiSettles {
                onSelected(plan.options[selectedChoiceIndex].choice)
            }
        } else {
            runAfterModalUiSettles {
                onCancel()
            }
        }
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
        val selectedChoiceIndex = showMultiDayDurationGuardDialog(options)
        if (selectedChoiceIndex != null) {
            runAfterModalUiSettles {
                onSelected(options[selectedChoiceIndex])
            }
        } else {
            runAfterModalUiSettles {
                onCancel()
            }
        }
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
        SwingUtilities.invokeLater {
            SwingUtilities.invokeLater {
                action()
            }
        }
    }

    private fun showStartTimeDaysToRunDialog(optionLabels: List<String>): Int? {
        var selectedChoiceIndex: Int? = null
        val dialog = JDialog(this, "Days To Run", true).apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            isResizable = false
        }
        startTimeDaysToRunDialogOpen = true

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
            add(JLabel("Choose Days To Run handling:").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(12))
            optionLabels.forEachIndexed { index, label ->
                add(
                    JButton(label).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                        addActionListener {
                            selectedChoiceIndex = index
                            dialog.dispose()
                        }
                    },
                )
                add(Box.createVerticalStrut(8))
            }
            add(
                JButton("Cancel").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    addActionListener { dialog.dispose() }
                },
            )
        }

        dialog.contentPane.add(content)
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        try {
            dialog.isVisible = true
        } finally {
            startTimeDaysToRunDialogOpen = false
        }
        return selectedChoiceIndex
    }

    private fun showMultiDayDurationGuardDialog(options: List<MultiDayDurationGuardOption>): Int? {
        var selectedChoiceIndex: Int? = null
        val dialog = JDialog(this, "Invalid Multi-Day Event", true).apply {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            isResizable = false
        }
        multiDayDurationGuardDialogOpen = true

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(14, 14, 14, 14)
            add(JLabel("Multi-day events must last less than 24 hours. Choose how to proceed:").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(Box.createVerticalStrut(12))
            options.forEachIndexed { index, option ->
                add(
                    JButton(option.label).apply {
                        alignmentX = Component.LEFT_ALIGNMENT
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                        addActionListener {
                            selectedChoiceIndex = index
                            dialog.dispose()
                        }
                    },
                )
                add(Box.createVerticalStrut(8))
            }
            add(
                JButton("Cancel").apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    addActionListener { dialog.dispose() }
                },
            )
        }

        dialog.contentPane.add(content)
        dialog.pack()
        dialog.setLocationRelativeTo(this)
        try {
            dialog.isVisible = true
        } finally {
            multiDayDurationGuardDialogOpen = false
        }
        return selectedChoiceIndex
    }

    private fun restoreDaysToRunEditor(daysToRun: Int) {
        daysField.value = daysToRun
        clearPendingImmediateEdit(daysField)
    }

    private fun disableEventViaStartTimeCommand(
        transport: DesktopSerialTransport? = currentTransport,
        state: DeviceSessionState? = currentState,
    ) {
        if (updatingForm || backgroundWorkInProgress) {
            return
        }
        val activeTransport = transport ?: run {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        val activeState = state ?: run {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }

        val description = "Disable Event"
        val commands = ScheduleSubmitSupport.disableEventCommands()
        showConnectionIndicator(
            ConnectionIndicatorState.SEARCHING,
            "Applying $description on ${currentConnectedPortPath.orEmpty()} and waiting for confirmation...",
        )
        runInBackground("Applying $description...") {
            setBusyProgress(0, 100, "Sending schedule command")
            val sentAtMs = System.currentTimeMillis()
            activeTransport.sendCommands(commands)
            val responseLines = activeTransport.readAvailableLines()
            val receivedAtMs = System.currentTimeMillis()
            extractDeviceError(responseLines)?.let { error ->
                throw IllegalStateException(error)
            }

            val updatedState = if (responseLines.isNotEmpty()) {
                DeviceSessionWorkflow.ingestReportLines(activeState, responseLines)
            } else {
                activeState
            }

            val refreshed = DeviceSessionController.refreshFromDevice(
                updatedState,
                activeTransport,
                startEditing = true,
                progress = { completed, total ->
                    setBusyProgressRange(20, 95, completed, total, commandProgressLabel(completed, total))
                },
            )
            setBusyProgress(97, 100, "Checking device time")
            val refreshClockSample = postLoadClockSample(activeTransport, refreshed.state.snapshot)
            setBusyProgress(100, 100, "Done")
            val refreshedWithClock = mergeLoadResults(refreshed, refreshClockSample?.first)
            currentState = refreshedWithClock.state
            loadedSnapshot = refreshedWithClock.state.snapshot
            refreshedWithClock.state.snapshot?.settings?.let(::rememberCloneTemplateFrom)

            SwingUtilities.invokeLater {
                clearRelativeScheduleDisplayOverrides()
                refreshClockSample?.second?.let(::applyClockDisplayAnchor)
                applySnapshotToForm(
                    refreshedWithClock.state.snapshot,
                    recalculateClockOffset = refreshClockSample == null,
                )
                updateCloneTemplateLabel(
                    "Clone template updated from current device.",
                    Color(0x0B, 0x3D, 0x91),
                )
                appendRelativeScheduleLog(
                    title = description,
                    commands = commands,
                    sentAtMs = sentAtMs,
                    responseLines = responseLines,
                    receivedAtMs = receivedAtMs,
                    refreshResult = refreshedWithClock,
                )
                showConnectionIndicator(
                    ConnectionIndicatorState.CONNECTED,
                    "Applied $description on ${currentConnectedPortPath.orEmpty()}.",
                )
                setStatus("Event disabled.")
            }
        }
    }

    private fun currentFinishTimeCompactForStartAdjustment(originalFinishTimeCompact: String?): String? {
        if (displayPreferences.scheduleTimeInputMode != ScheduleTimeInputMode.ABSOLUTE) {
            return originalFinishTimeCompact
        }
        return runCatching {
            selectedDateTimeCompact(finishTimeSpinner, originalFinishTimeCompact)
        }.getOrDefault(originalFinishTimeCompact)
    }

    private fun restoreAbsoluteStartTimeEditor(startTimeCompact: String?) {
        clearPendingDateTimeCompact(startTimeSpinner)
        setDateTimeEditorValue(startTimeSpinner, startTimeStatusLabel, startTimeCompact)
        clearPendingImmediateEdit()
    }

    private fun restoreRelativeStartTimeEditor() {
        relativeStartDisplaySelectionOverride = null
        refreshScheduleTimeEditorPresentation(loadedSnapshot)
        clearPendingImmediateEdit()
    }

    private fun restoreAbsoluteFinishTimeEditor(finishTimeCompact: String?) {
        clearPendingDateTimeCompact(finishTimeSpinner)
        setDateTimeEditorValue(finishTimeSpinner, finishTimeStatusLabel, finishTimeCompact)
        clearPendingImmediateEdit()
    }

    private fun restoreRelativeFinishTimeEditor() {
        relativeFinishDisplaySelectionOverride = null
        refreshScheduleTimeEditorPresentation(loadedSnapshot)
        clearPendingImmediateEdit()
    }

    private fun applyRelativeStartTimeChange(
        selection: DesktopInputSupport.RelativeTimeSelection,
        chosenDuration: Duration,
        transport: DesktopSerialTransport,
        state: DeviceSessionState,
        preservedDaysToRun: Int? = null,
    ) {
        val description = "Relative Start Time"
        val chosenFinishSelection = DesktopInputSupport.relativeTimeSelectionForDuration(chosenDuration)
        val commands = ScheduleSubmitSupport.relativeStartCommands(
            offsetCommand = DesktopInputSupport.formatRelativeTimeCommand(selection),
            finishOffsetCommand = DesktopInputSupport.formatRelativeDurationCommand(chosenDuration),
            preservedDaysToRun = preservedDaysToRun,
        )

        showConnectionIndicator(
            ConnectionIndicatorState.SEARCHING,
            "Applying $description on ${currentConnectedPortPath.orEmpty()} and waiting for confirmation...",
        )
        runInBackground("Applying $description...") {
            setBusyProgress(0, 100, "Sending relative schedule command")
            val sentAtMs = System.currentTimeMillis()
            transport.sendCommands(commands)
            val responseLines = transport.readAvailableLines()
            val receivedAtMs = System.currentTimeMillis()
            extractDeviceError(responseLines)?.let { error ->
                throw IllegalStateException(error)
            }

            val updatedState = if (responseLines.isNotEmpty()) {
                DeviceSessionWorkflow.ingestReportLines(state, responseLines)
            } else {
                state
            }

            val refreshed = DeviceSessionController.refreshFromDevice(
                updatedState,
                transport,
                startEditing = true,
                progress = { completed, total ->
                    setBusyProgressRange(20, 95, completed, total, commandProgressLabel(completed, total))
                },
            )
            setBusyProgress(97, 100, "Checking device time")
            val refreshClockSample = postLoadClockSample(transport, refreshed.state.snapshot)
            setBusyProgress(100, 100, "Done")
            val refreshedWithClock = mergeLoadResults(refreshed, refreshClockSample?.first)
            currentState = refreshedWithClock.state
            loadedSnapshot = refreshedWithClock.state.snapshot
            refreshedWithClock.state.snapshot?.settings?.let(::rememberCloneTemplateFrom)

            SwingUtilities.invokeLater {
                relativeStartDisplaySelectionOverride = selection
                relativeFinishDisplaySelectionOverride = chosenFinishSelection
                refreshClockSample?.second?.let(::applyClockDisplayAnchor)
                applySnapshotToForm(
                    refreshedWithClock.state.snapshot,
                    recalculateClockOffset = refreshClockSample == null,
                )
                updateCloneTemplateLabel(
                    "Clone template updated from current device.",
                    Color(0x0B, 0x3D, 0x91),
                )
                appendRelativeScheduleLog(
                    title = "Apply $description",
                    commands = commands,
                    sentAtMs = sentAtMs,
                    responseLines = responseLines,
                    receivedAtMs = receivedAtMs,
                    refreshResult = refreshedWithClock,
                )
                showConnectionIndicator(
                    ConnectionIndicatorState.CONNECTED,
                    "Applied $description on ${currentConnectedPortPath.orEmpty()}.",
                )
                setStatus("Applied $description.")
            }
        }
    }

    private fun appendRelativeScheduleLog(
        title: String,
        commands: List<String>,
        sentAtMs: Long,
        responseLines: List<String>,
        receivedAtMs: Long,
        refreshResult: DeviceLoadResult,
    ) {
        appendLog(title, buildList {
            commands.forEach { command ->
                add(DesktopLogEntry("TX $command", DesktopLogCategory.SERIAL, sentAtMs))
            }
            responseLines.forEach { line ->
                add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL, receivedAtMs))
            }
            refreshResult.traceEntries.forEach { entry ->
                add(
                    DesktopLogEntry(
                        message = "${entry.direction.label} ${entry.payload}",
                        category = DesktopLogCategory.SERIAL,
                        timestampMs = entry.timestampMs,
                    ),
                )
            }
            add(
                DesktopLogEntry(
                    message = "Reloaded settings after relative schedule change.",
                    category = DesktopLogCategory.APP,
                ),
            )
        })
    }

    private fun extractDeviceError(responseLines: List<String>): String? {
        return responseLines.firstOrNull { it.contains("* Err:") }?.removePrefix("* ")?.trim()
    }

    private fun applySnapshotToForm(snapshot: DeviceSnapshot?, recalculateClockOffset: Boolean = true) {
        clearPendingImmediateEdit()
        if (snapshot == null) {
            clearFormForUnread()
            return
        }

        val settings = snapshot.settings
        val timedSettings = snapshot.settings
        val frequencies = FrequencySupport.describeFrequencies(settings)
        val schedulingSupported = snapshot.capabilities.supportsScheduling
        updatingForm = true
        try {
            stationIdField.text = timedSettings.stationId
            eventTypeCombo.selectedItem =
                timedSettings.eventType.takeIf { it in DesktopInputSupport.selectableEventTypes() }
                    ?: DesktopInputSupport.selectableEventTypes().first()
            syncFoxRoleOptions(settings.eventType, settings.foxRole)
            patternTextField.text = DesktopInputSupport.displayPatternText(
                eventType = settings.eventType,
                foxRole = settings.foxRole,
                storedPatternText = settings.patternText,
            )
            updatePatternTextEditability(settings.eventType, !backgroundWorkInProgress)
            idSpeedField.selectedItem = DesktopInputSupport.formatCodeSpeedWpm(timedSettings.idCodeSpeedWpm)
            devicePatternSpeedField.selectedItem = DesktopInputSupport.formatCodeSpeedWpm(settings.patternCodeSpeedWpm)
            timedPatternSpeedField.selectedItem = DesktopInputSupport.formatCodeSpeedWpm(settings.patternCodeSpeedWpm)
            setDateTimeEditorValue(startTimeSpinner, startTimeStatusLabel, timedSettings.startTimeCompact)
            setDateTimeEditorValue(finishTimeSpinner, finishTimeStatusLabel, timedSettings.finishTimeCompact)
            applyDateTimeEditorCapability(startTimeSpinner, schedulingSupported)
            applyDateTimeEditorCapability(finishTimeSpinner, schedulingSupported)
            refreshScheduleTimeEditorPresentation(snapshot)
            daysField.isEnabled = schedulingSupported
            updateDaysToRunDisplay(snapshot)
            setInformationalFieldText(currentFrequencyField, formatFrequencyForDisplay(frequencies.currentFrequencyHz), unreadPlaceholder = false)
            setInformationalFieldText(currentBankField, frequencies.currentBankId?.label ?: "Unknown", unreadPlaceholder = false)
            setFrequencySpinnerValue(frequency1Field, timedSettings.lowFrequencyHz)
            setFrequencySpinnerValue(frequency2Field, timedSettings.mediumFrequencyHz)
            setFrequencySpinnerValue(frequency3Field, timedSettings.highFrequencyHz)
            setFrequencySpinnerValue(frequencyBField, timedSettings.beaconFrequencyHz)
            updateTimedEventFrequencyVisibility(timedSettings.eventType)
            updatePatternSpeedVisibility(settings.eventType)
            batteryThresholdField.selectedItem = settings.lowBatteryThresholdVolts?.let { "%.1f V".format(it) }
            batteryModeCombo.selectedItem = settings.externalBatteryControlMode ?: ExternalBatteryControlMode.OFF
            updateTransmissionsField(settings.transmissionsEnabled)
            setInformationalFieldText(versionInfoField, DesktopInputSupport.formatReportedVersion(
                softwareVersion = snapshot.info.softwareVersion,
                hardwareBuild = snapshot.info.hardwareBuild,
            ), unreadPlaceholder = false)
            setInformationalFieldText(internalBatteryField, DesktopInputSupport.formatVoltageOrWaiting(snapshot.status.internalBatteryVolts), unreadPlaceholder = false)
            setInformationalFieldText(externalBatteryField, DesktopInputSupport.formatVoltageOrWaiting(snapshot.status.externalBatteryVolts), unreadPlaceholder = false)
            updateTemperatureRow(
                rowLabel = maximumTemperatureRowLabel,
                field = maximumTemperatureField,
                temperatureC = snapshot.status.maximumTemperatureC,
            )
            updateTemperatureRow(
                rowLabel = currentTemperatureRowLabel,
                field = currentTemperatureField,
                temperatureC = snapshot.status.temperatureC,
            )
            updateTemperatureRow(
                rowLabel = minimumTemperatureRowLabel,
                field = minimumTemperatureField,
                temperatureC = snapshot.status.minimumTemperatureC,
            )
            updateThermalHeadlineWarning(snapshot.status.temperatureC)
            lastDeviceTimeCheckAtMs = System.currentTimeMillis()
            if (recalculateClockOffset) {
                updateDeviceClockOffset(settings.currentTimeCompact)
            }
            updateDisplayedClockFields()
            updateWritableControlAvailability(backgroundWorkInProgress)
        } finally {
            updatingForm = false
        }
    }

    private fun updateTransmissionsField(isEnabled: Boolean) {
        val showWarning = !isEnabled
        transmissionsRowLabel.isVisible = showWarning
        transmissionsField.isVisible = showWarning
        transmissionsRowLabel.foreground = if (showWarning) alertForeground else defaultInformationalFieldForeground
        setInformationalFieldText(
            transmissionsField,
            if (showWarning) "Transmissions disabled" else "",
            unreadPlaceholder = false,
            alert = showWarning,
        )
    }

    private fun syncFoxRoleOptions(eventType: EventType, selectedRole: FoxRole?) {
        val options = DesktopInputSupport.foxRoleOptions(eventType)
        val model = DefaultComboBoxModel(options.toTypedArray())
        foxRoleCombo.model = model
        foxRoleCombo.selectedItem = selectedRole?.takeIf { it in options } ?: options.firstOrNull()
    }

    private fun updateTimedEventFrequencyVisibility(eventType: EventType) {
        val visibility = DesktopInputSupport.timedEventFrequencyVisibility(eventType)
        frequency1Label.isVisible = visibility.showFrequency1
        frequency1Field.isVisible = visibility.showFrequency1
        frequency2Label.isVisible = visibility.showFrequency2
        frequency2Field.isVisible = visibility.showFrequency2
        frequency3Label.isVisible = visibility.showFrequency3
        frequency3Field.isVisible = visibility.showFrequency3
        frequencyBLabel.isVisible = visibility.showFrequencyB
        frequencyBField.isVisible = visibility.showFrequencyB
        revalidate()
        repaint()
    }

    private fun updatePatternSpeedVisibility(eventType: EventType) {
        val timedEventOwned = DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(eventType)
        devicePatternSpeedLabel.isVisible = !timedEventOwned
        devicePatternSpeedField.isVisible = !timedEventOwned
        timedPatternSpeedLabel.isVisible = timedEventOwned
        timedPatternSpeedField.isVisible = timedEventOwned
        revalidate()
        repaint()
    }

    private fun displayedTimedEventSettings(snapshot: DeviceSnapshot): DeviceSettings {
        return snapshot.settings
    }

    private fun rememberCloneTemplateFrom(sourceSettings: DeviceSettings) {
        val existingTemplate = cloneTemplateSettings
        cloneTemplateSettings = if (existingTemplate == null) {
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

    private fun updateCloneTemplateLabel(
        message: String,
        foreground: Color = Color(0x55, 0x65, 0x73),
    ) {
        cloneTemplateLabel.text = message
        cloneTemplateLabel.foreground = foreground
    }

    private fun currentConnectedTimedSettings(): DeviceSettings {
        return (loadedSnapshot ?: error("No device snapshot is loaded.")).settings
    }

    private fun hasDifferentTimedEventSettings(
        currentSettings: DeviceSettings?,
        displayedSettings: DeviceSettings,
    ): Boolean {
        val current = currentSettings ?: return false
        return current.stationId != displayedSettings.stationId ||
            current.eventType != displayedSettings.eventType ||
            current.idCodeSpeedWpm != displayedSettings.idCodeSpeedWpm ||
            cloneTemplatePatternSpeedFor(current) != cloneTemplatePatternSpeedFor(displayedSettings) ||
            current.startTimeCompact != displayedSettings.startTimeCompact ||
            current.finishTimeCompact != displayedSettings.finishTimeCompact ||
            current.daysToRun != displayedSettings.daysToRun ||
            current.lowFrequencyHz != displayedSettings.lowFrequencyHz ||
            current.mediumFrequencyHz != displayedSettings.mediumFrequencyHz ||
            current.highFrequencyHz != displayedSettings.highFrequencyHz ||
            current.beaconFrequencyHz != displayedSettings.beaconFrequencyHz
    }

    private fun appendLoadLog(result: DeviceLoadResult) {
        appendLoadLog(title = "Load", result = result)
    }

    private fun appendLoadLog(
        title: String,
        result: DeviceLoadResult,
        leadEntries: List<DesktopLogEntry> = emptyList(),
    ) {
        val entries = buildList {
            addAll(leadEntries)
            addAll(traceEntriesToLogEntries(result.traceEntries))
            add(
                DesktopLogEntry(
                    message = "Device settings loaded.",
                    category = DesktopLogCategory.DEVICE,
                    timestampMs = latestTraceTimestamp(result.traceEntries),
                ),
            )
        }
        appendLog(title, entries)
    }

    private fun appendWriteLog(
        title: String,
        writePlan: WritePlan,
        result: DeviceSubmitResult,
        preRefreshResult: DeviceLoadResult? = null,
        refreshResult: DeviceLoadResult? = null,
        comparedFieldKeys: List<SettingKey>? = null,
    ) {
        val entries = mutableListOf<DesktopLogEntry>()
        val changedFieldKeys = writePlan.changes.map { it.fieldKey }
        if (preRefreshResult != null) {
            entries += DesktopLogEntry(
                message = "Refreshing attached device snapshot before comparing clone settings.",
                category = DesktopLogCategory.APP,
                timestampMs = preRefreshResult.traceEntries.firstOrNull()?.timestampMs ?: System.currentTimeMillis(),
            )
            entries += traceEntriesToLogEntries(preRefreshResult.traceEntries, suffix = "(target)")
        }
        entries += traceEntriesToLogEntries(result.submitTraceEntries)
        entries += traceEntriesToLogEntries(result.readbackTraceEntries, suffix = "(readback)")
        if (result.verifications.isNotEmpty()) {
            val verificationTimestampMs = latestTimestamp(
                result.readbackTraceEntries,
                result.submitTraceEntries,
            )
            entries += result.verifications.map { verification ->
                DesktopLogEntry(
                    message = "${verification.fieldKey}: ${if (verification.verified) "OK" else "MISMATCH"}",
                    category = DesktopLogCategory.DEVICE,
                    timestampMs = verificationTimestampMs,
                )
            }
        }
        if (refreshResult != null) {
            entries += DesktopLogEntry(
                message = "Refreshing device snapshot after write.",
                category = DesktopLogCategory.APP,
                timestampMs = refreshResult.traceEntries.firstOrNull()?.timestampMs ?: System.currentTimeMillis(),
            )
            entries += traceEntriesToLogEntries(refreshResult.traceEntries, suffix = "(refresh)")
        }
        entries += buildWriteSummaryEntries(
            writePlan = writePlan,
            comparedFieldKeys = comparedFieldKeys,
            changedFieldKeys = changedFieldKeys,
            timestampMs = latestTimestamp(
                refreshResult?.traceEntries,
                preRefreshResult?.traceEntries,
                result.readbackTraceEntries,
                result.submitTraceEntries,
            ),
        )
        appendLog(title, entries)
    }

    private fun describeSettingKey(fieldKey: SettingKey): String {
        return when (fieldKey) {
            SettingKey.STATION_ID -> "Station ID"
            SettingKey.EVENT_TYPE -> "Event Type"
            SettingKey.FOX_ROLE -> "Fox Role"
            SettingKey.PATTERN_TEXT -> "Pattern Text"
            SettingKey.ID_CODE_SPEED_WPM -> "ID Speed"
            SettingKey.PATTERN_CODE_SPEED_WPM -> "Pattern Speed"
            SettingKey.CURRENT_TIME -> "Device Time"
            SettingKey.START_TIME -> "Start Time"
            SettingKey.FINISH_TIME -> "Finish Time"
            SettingKey.DAYS_TO_RUN -> "Days To Run"
            SettingKey.DEFAULT_FREQUENCY_HZ -> "Frequency"
            SettingKey.LOW_FREQUENCY_HZ -> "Frequency 1"
            SettingKey.MEDIUM_FREQUENCY_HZ -> "Frequency 2"
            SettingKey.HIGH_FREQUENCY_HZ -> "Frequency 3"
            SettingKey.BEACON_FREQUENCY_HZ -> "Frequency B"
            SettingKey.LOW_BATTERY_THRESHOLD_VOLTS -> "Low Battery Threshold"
            SettingKey.EXTERNAL_BATTERY_CONTROL_MODE -> "Battery Mode"
            SettingKey.TRANSMISSIONS_ENABLED -> "Transmissions"
        }
    }

    private fun formatSettingValue(fieldKey: SettingKey, value: Any?): String {
        return when (fieldKey) {
            SettingKey.EVENT_TYPE -> (value as? EventType)?.toString().orEmpty()
            SettingKey.FOX_ROLE -> (value as? FoxRole)?.toString() ?: "Not Set"
            SettingKey.ID_CODE_SPEED_WPM,
            SettingKey.PATTERN_CODE_SPEED_WPM,
            -> value?.let { "$it WPM" } ?: "Not Set"
            SettingKey.CURRENT_TIME,
            SettingKey.START_TIME,
            SettingKey.FINISH_TIME,
            -> DesktopInputSupport.formatCompactTimestampOrNotSet(value as? String)
            SettingKey.DEFAULT_FREQUENCY_HZ,
            SettingKey.LOW_FREQUENCY_HZ,
            SettingKey.MEDIUM_FREQUENCY_HZ,
            SettingKey.HIGH_FREQUENCY_HZ,
            SettingKey.BEACON_FREQUENCY_HZ,
            -> formatFrequencyForDisplay(value as? Long)
            SettingKey.LOW_BATTERY_THRESHOLD_VOLTS -> value?.toString() ?: "Not Set"
            SettingKey.EXTERNAL_BATTERY_CONTROL_MODE -> (value as? ExternalBatteryControlMode)?.toString() ?: "Not Set"
            SettingKey.TRANSMISSIONS_ENABLED -> if (value == true) "Enabled" else "Disabled"
            else -> value?.toString() ?: "Not Set"
        }
    }

    private fun traceEntriesToLogEntries(
        traceEntries: List<SerialTraceEntry>,
        suffix: String = "",
    ): List<DesktopLogEntry> {
        return traceEntries.map { trace ->
            DesktopLogEntry(
                message = "${trace.direction.label}$suffix ${trace.payload}",
                category = DesktopLogCategory.SERIAL,
                timestampMs = trace.timestampMs,
            )
        }
    }

    private fun buildWriteSummaryEntries(
        writePlan: WritePlan,
        comparedFieldKeys: List<SettingKey>?,
        changedFieldKeys: List<SettingKey>,
        timestampMs: Long,
    ): List<DesktopLogEntry> {
        if (comparedFieldKeys != null) {
            val alreadyMatchedKeys = comparedFieldKeys.filterNot { it in changedFieldKeys }
            return buildList {
                add(
                    DesktopLogEntry(
                        "Fields compared: ${comparedFieldKeys.joinToString(", ") { describeSettingKey(it) }}",
                        DesktopLogCategory.APP,
                        timestampMs,
                    ),
                )
                if (changedFieldKeys.isEmpty()) {
                    add(
                        DesktopLogEntry(
                            "Fields written: none",
                            DesktopLogCategory.APP,
                            timestampMs,
                        ),
                    )
                } else {
                    add(
                        DesktopLogEntry(
                            "Fields written:",
                            DesktopLogCategory.APP,
                            timestampMs,
                        ),
                    )
                    addAll(
                        writePlan.changes.map { change ->
                            DesktopLogEntry(
                                "${describeSettingKey(change.fieldKey)} = ${formatSettingValue(change.fieldKey, change.newValue)}",
                                DesktopLogCategory.APP,
                                timestampMs,
                            )
                        },
                    )
                }
                if (alreadyMatchedKeys.isNotEmpty()) {
                    add(
                        DesktopLogEntry(
                            "Fields already matched: ${alreadyMatchedKeys.joinToString(", ") { describeSettingKey(it) }}",
                            DesktopLogCategory.APP,
                            timestampMs,
                        ),
                    )
                }
            }
        }

        if (writePlan.changes.isEmpty()) {
            return listOf(
                DesktopLogEntry(
                    "No differing settings needed to be written.",
                    DesktopLogCategory.APP,
                    timestampMs,
                ),
            )
        }

        return buildList {
            add(DesktopLogEntry("Planned writes:", DesktopLogCategory.APP, timestampMs))
            addAll(
                writePlan.changes.map { change ->
                    DesktopLogEntry(
                        "${describeSettingKey(change.fieldKey)} = ${formatSettingValue(change.fieldKey, change.newValue)}",
                        DesktopLogCategory.APP,
                        timestampMs,
                    )
                },
            )
        }
    }

    private fun latestTimestamp(vararg traceGroups: List<SerialTraceEntry>?): Long {
        return traceGroups.asSequence()
            .filterNotNull()
            .flatMap { it.asSequence() }
            .maxOfOrNull { it.timestampMs }
            ?: System.currentTimeMillis()
    }

    private fun latestTraceTimestamp(traceEntries: List<SerialTraceEntry>): Long {
        return traceEntries.maxOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
    }

    private fun appendSyncLog(
        title: String = "Sync",
        attempts: List<SyncAttempt>,
        latencySamples: List<ClockReadSample>,
    ) {
        val entries = mutableListOf<DesktopLogEntry>()
        if (latencySamples.isNotEmpty()) {
            val medianRtt = DesktopInputSupport.medianMillis(latencySamples.map { it.roundTripMillis })
            entries += DesktopLogEntry(
                "Clock probe RTT median: ${DesktopInputSupport.formatSignedDurationMillis(medianRtt)}",
                DesktopLogCategory.APP,
                latencySamples.first().receivedAtMs,
            )
        }
        attempts.forEachIndexed { index, attempt ->
            val attemptTimestampMs = latestTimestamp(
                attempt.submitResult.readbackTraceEntries,
                attempt.submitResult.submitTraceEntries,
            )
            entries += DesktopLogEntry(
                "Attempt ${index + 1}: target ${DesktopInputSupport.formatSystemTimestamp(attempt.targetTime)}, estimated one-way delay ${attempt.estimatedOneWayDelayMillis} ms",
                DesktopLogCategory.APP,
                attemptTimestampMs,
            )
            entries += traceEntriesToLogEntries(attempt.submitResult.submitTraceEntries)
            entries += traceEntriesToLogEntries(attempt.submitResult.readbackTraceEntries, suffix = "(readback)")
            attempt.verificationSamples.forEach { sample ->
                entries += DesktopLogEntry("TX(verify) ${sample.command}", DesktopLogCategory.SERIAL, sample.sentAtMs)
                sample.responseLines.forEach { line ->
                    entries += DesktopLogEntry("RX(verify) $line", DesktopLogCategory.SERIAL, sample.receivedAtMs)
                }
            }
            entries += DesktopLogEntry(
                attempt.phaseErrorMillis?.let {
                    "Measured phase error: ${DesktopInputSupport.formatSignedDurationMillis(it)}"
                } ?: "Measured phase error: unavailable",
                DesktopLogCategory.APP,
                attempt.verificationSamples.lastOrNull()?.receivedAtMs ?: attemptTimestampMs,
            )
        }
        appendLog(title, entries)
    }

    private fun appendDisableEventLog(
        goCommands: List<String>,
        goLines: List<String>,
        finishMirrorCommands: List<String>,
        finishMirrorLines: List<String>,
        clockRefreshCommands: List<String>,
        clockRefreshLines: List<String>,
    ) {
        val entries = mutableListOf<DesktopLogEntry>()
        goCommands.forEach { command ->
            entries += DesktopLogEntry("TX $command", DesktopLogCategory.SERIAL)
        }
        goLines.forEach { line ->
            entries += DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL)
        }
        finishMirrorCommands.forEach { command ->
            entries += DesktopLogEntry("TX $command", DesktopLogCategory.SERIAL)
        }
        finishMirrorLines.forEach { line ->
            entries += DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL)
        }
        clockRefreshCommands.forEach { command ->
            entries += DesktopLogEntry("TX $command", DesktopLogCategory.SERIAL)
        }
        clockRefreshLines.forEach { line ->
            entries += DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL)
        }
        entries += DesktopLogEntry(
            "Finish Time was mirrored to Start Time using CLK F =.",
            DesktopLogCategory.APP,
        )
        appendLog("Disable Event", entries)
    }

    private fun appendLog(title: String, entries: List<DesktopLogEntry>) {
        val rendered = sessionLog.appendSection(title, entries)
        appendRenderedLog(rendered)
    }

    private fun runInBackground(
        status: String,
        showErrorDialog: Boolean = true,
        showBusyDialog: Boolean = true,
        task: () -> Unit,
    ) {
        if (backgroundWorkInProgress) {
            return
        }
        backgroundWorkInProgress = true
        clearBusyProgress()
        setBusy(true)
        setStatus(status)
        if (showBusyDialog) {
            scheduleBusyDialog(status)
        }
        Thread {
            try {
                task()
            } catch (exception: Exception) {
                SwingUtilities.invokeLater {
                    if (isTransportCommunicationFailure(exception)) {
                        handleTransportCommunicationFailure(
                            message = exception.message ?: exception.toString(),
                            showDialog = showErrorDialog,
                        )
                    } else {
                        if (showErrorDialog) {
                            JOptionPane.showMessageDialog(this, exception.message ?: exception.toString())
                        }
                        appendLog(
                            "Error",
                            listOf(
                                DesktopLogEntry(
                                    exception.message ?: exception.toString(),
                                    DesktopLogCategory.APP,
                                ),
                            ),
                        )
                        setStatus("Error: ${exception.message ?: exception::class.simpleName}")
                    }
                }
            } finally {
                SwingUtilities.invokeLater {
                    backgroundWorkInProgress = false
                    hideBusyDialog()
                    clearBusyProgress()
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun setBusy(isBusy: Boolean) {
        autoDetectButton.isEnabled = !isBusy
        portComboBox.isEnabled = !isBusy
        rawCommandField.isEnabled =
            !isBusy &&
            rawSerialRowPanel.isVisible &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED
        updateWritableControlAvailability(isBusy)
        if (isBusy) {
            syncTimeButton.isEnabled = false
            setTimeButton.isEnabled = false
            disableEventButton.isEnabled = false
        } else {
            updateDisplayedClockFields()
        }
        submitButton.isEnabled =
            !isBusy &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED &&
            cloneTemplateSettings != null
        updateApplyButtonState()
    }

    private fun updateWritableControlAvailability(isBusy: Boolean) {
        val connected = currentTransport != null && currentState?.connectionState == ConnectionState.CONNECTED
        val schedulingSupported = loadedSnapshot?.capabilities?.supportsScheduling == true
        val writableEnabled = connected && !isBusy

        stationIdField.isEnabled = writableEnabled
        eventTypeCombo.isEnabled = writableEnabled
        foxRoleCombo.isEnabled = writableEnabled
        updatePatternTextEditability(
            loadedSnapshot?.settings?.eventType ?: (eventTypeCombo.selectedItem as? EventType ?: EventType.NONE),
            writableEnabled,
        )
        idSpeedField.isEnabled = writableEnabled
        devicePatternSpeedField.isEnabled = writableEnabled
        timedPatternSpeedField.isEnabled = writableEnabled
        batteryThresholdField.isEnabled = writableEnabled
        batteryModeCombo.isEnabled = writableEnabled
        transmissionsField.isEnabled = true

        daysField.isEnabled = writableEnabled && schedulingSupported
        frequency1Field.isEnabled = writableEnabled
        frequency2Field.isEnabled = writableEnabled
        frequency3Field.isEnabled = writableEnabled
        frequencyBField.isEnabled = writableEnabled

        applyDateTimeEditorCapability(manualTimeSpinner, writableEnabled && schedulingSupported)
        applyDateTimeEditorCapability(startTimeSpinner, writableEnabled && schedulingSupported)
        applyDateTimeEditorCapability(finishTimeSpinner, writableEnabled && schedulingSupported)
        startTimeRelativeField.isEnabled = writableEnabled && schedulingSupported
        finishTimeRelativeField.isEnabled = writableEnabled && schedulingSupported
        startTimeAbsoluteMirrorField.isEnabled = writableEnabled && schedulingSupported
        finishTimeAbsoluteMirrorField.isEnabled = writableEnabled && schedulingSupported
        updateLastsRowEditability(writableEnabled && schedulingSupported)
        setTimeButton.isEnabled =
            writableEnabled &&
            schedulingSupported &&
            displayPreferences.timeSetMode == TimeSetMode.MANUAL
        syncTimeButton.isEnabled =
            writableEnabled &&
            schedulingSupported &&
            displayPreferences.timeSetMode == TimeSetMode.SYSTEM_CLOCK &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED
    }

    private fun updateLastsRowEditability(enabled: Boolean) {
        val cursor =
            if (enabled) {
                java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            } else {
                java.awt.Cursor.getDefaultCursor()
            }
        lastsField.cursor = cursor
        lastsRowLabel.cursor = cursor
        val tooltip = if (enabled) "Click to choose event duration." else null
        lastsField.toolTipText = tooltip
        lastsRowLabel.toolTipText = tooltip
    }

    private fun currentPatternSpeedField(): JComboBox<String> {
        val eventType = loadedSnapshot?.settings?.eventType ?: EventType.NONE
        return if (DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(eventType)) {
            timedPatternSpeedField
        } else {
            devicePatternSpeedField
        }
    }

    private fun updatePatternTextEditability(eventType: EventType, writableEnabled: Boolean) {
        val editable = writableEnabled && DesktopInputSupport.patternTextIsEditable(eventType)
        patternTextField.isEnabled = true
        patternTextField.isEditable = editable
        if (editable) {
            patternTextField.border = editableTextFieldBorder
            patternTextField.background = editableTextFieldBackground
            patternTextField.isOpaque = true
        } else {
            patternTextField.border = editableTextFieldBorder
            patternTextField.background = readOnlyTextFieldBackground
            patternTextField.isOpaque = true
        }
        patternTextField.toolTipText = if (editable) {
            "Editable in Foxoring."
        } else {
            "For Classic and Sprint, pattern text is determined by Fox Role."
        }
    }

    private fun configureInformationalField(field: JTextField) {
        field.border = informationalTextFieldBorder
        field.background = editableTextFieldBackground
        field.isOpaque = false
        field.disabledTextColor = field.foreground
    }

    private fun setInformationalFieldText(
        field: JTextField,
        value: String?,
        unreadPlaceholder: Boolean = true,
        alert: Boolean = false,
    ) {
        val displayText = value.orEmpty().ifBlank { if (unreadPlaceholder) "Not read" else "" }
        field.text = displayText
        field.foreground = when {
            alert -> alertForeground
            unreadPlaceholder && displayText == "Not read" -> unreadFieldForeground
            else -> defaultInformationalFieldForeground
        }
        field.disabledTextColor = field.foreground
    }

    private fun setFrequencyDisplayUnit(unit: FrequencyDisplayUnit) {
        if (displayPreferences.frequencyDisplayUnit == unit) {
            return
        }
        displayPreferences = displayPreferences.copy(frequencyDisplayUnit = unit)
        persistDisplayPreferences()
        frequencyKhzMenuItem.isSelected = unit == FrequencyDisplayUnit.KHZ
        frequencyMhzMenuItem.isSelected = unit == FrequencyDisplayUnit.MHZ
        applySnapshotToForm(loadedSnapshot, recalculateClockOffset = false)
        setStatus("Frequency units set to ${if (unit == FrequencyDisplayUnit.MHZ) "MHz" else "kHz"}.")
    }

    private fun setTemperatureDisplayUnit(unit: TemperatureDisplayUnit) {
        if (displayPreferences.temperatureDisplayUnit == unit) {
            return
        }
        displayPreferences = displayPreferences.copy(temperatureDisplayUnit = unit)
        persistDisplayPreferences()
        temperatureCMenuItem.isSelected = unit == TemperatureDisplayUnit.CELSIUS
        temperatureFMenuItem.isSelected = unit == TemperatureDisplayUnit.FAHRENHEIT
        applySnapshotToForm(loadedSnapshot, recalculateClockOffset = false)
        setStatus("Temperature units set to ${if (unit == TemperatureDisplayUnit.CELSIUS) "Celsius" else "Fahrenheit"}.")
    }

    private fun setTimeSetMode(mode: TimeSetMode) {
        if (displayPreferences.timeSetMode == mode) {
            return
        }
        displayPreferences = displayPreferences.copy(timeSetMode = mode)
        applyTimeSetMode(mode)
        persistDisplayPreferences()
        setStatus("Device Time Setting is now ${if (mode == TimeSetMode.SYSTEM_CLOCK) "Automatic" else "Manual"}.")
    }

    private fun applyTimeSetMode(mode: TimeSetMode) {
        val manualMode = mode == TimeSetMode.MANUAL
        currentTimeRowLabel.isVisible = true
        currentTimeRowPanel.isVisible = true
        systemTimeRowLabel.isVisible = !manualMode
        systemTimeField.isVisible = !manualMode
        manualTimeRowLabel.isVisible = manualMode
        manualTimeRowPanel.isVisible = manualMode
        currentTimeRowSpacer.isVisible = !manualMode
        syncTimeButton.isVisible = !manualMode
        if (::timeSetAutomaticMenuItem.isInitialized) {
            timeSetAutomaticMenuItem.isSelected = !manualMode
        }
        if (::timeSetManualMenuItem.isInitialized) {
            timeSetManualMenuItem.isSelected = manualMode
        }
        updateWritableControlAvailability(backgroundWorkInProgress)
        updateClockPhaseWarning(lastClockPhaseErrorMillis)
        formScroll.revalidate()
        formScroll.repaint()
    }

    private fun setScheduleTimeInputMode(mode: ScheduleTimeInputMode) {
        if (startTimeFinishAdjustmentDialogOpen || startTimeDaysToRunDialogOpen || multiDayDurationGuardDialogOpen || lastsDurationDialogOpen) {
            return
        }
        if (displayPreferences.scheduleTimeInputMode == mode) {
            return
        }
        suppressScheduleInteractionUntilMs = System.currentTimeMillis() + SCHEDULE_MODE_TOGGLE_SUPPRESSION_MS
        clearPendingImmediateEdit()
        if (mode == ScheduleTimeInputMode.ABSOLUTE) {
            clearRelativeScheduleDisplayOverrides()
        }
        displayPreferences = displayPreferences.copy(scheduleTimeInputMode = mode)
        persistDisplayPreferences()
        if (::scheduleTimeAbsoluteMenuItem.isInitialized) {
            scheduleTimeAbsoluteMenuItem.isSelected = mode == ScheduleTimeInputMode.ABSOLUTE
        }
        if (::scheduleTimeRelativeMenuItem.isInitialized) {
            scheduleTimeRelativeMenuItem.isSelected = mode == ScheduleTimeInputMode.RELATIVE
        }
        refreshScheduleTimeEditorPresentation(loadedSnapshot)
        updateWritableControlAvailability(backgroundWorkInProgress)
        formScroll.revalidate()
        formScroll.repaint()
        setStatus("Schedule Time Setting is now ${if (mode == ScheduleTimeInputMode.RELATIVE) "Relative" else "Absolute"}.")
    }

    private fun setDefaultEventLengthMinutes(minutes: Int) {
        val validatedMinutes = DesktopInputSupport.validateDefaultEventLengthMinutes(minutes)
        if (displayPreferences.defaultEventLengthMinutes == validatedMinutes) {
            return
        }
        displayPreferences = displayPreferences.copy(defaultEventLengthMinutes = validatedMinutes)
        relativeFinishDisplaySelectionOverride = defaultEventLengthRelativeSelection()
        persistDisplayPreferences()
        updateDefaultEventLengthMenuItem()
        refreshScheduleTimeEditorPresentation(loadedSnapshot)
        setStatus("Default Event Length is now ${DesktopInputSupport.formatDefaultEventLength(validatedMinutes)}.")
    }

    private fun updateDefaultEventLengthMenuItem() {
        if (!::defaultEventLengthMenuItem.isInitialized) {
            return
        }
        defaultEventLengthMenuItem.text =
            "Default Event Length (${DesktopInputSupport.formatDefaultEventLength(displayPreferences.defaultEventLengthMinutes)})..."
    }

    private fun defaultEventLengthRelativeSelection(): DesktopInputSupport.RelativeTimeSelection {
        return DesktopInputSupport.relativeTimeSelectionForDuration(displayPreferences.defaultEventLengthMinutes)
    }

    private fun clearRelativeScheduleDisplayOverrides() {
        relativeStartDisplaySelectionOverride = null
        relativeFinishDisplaySelectionOverride = null
    }

    private fun toggleFrequencyDisplayUnit() {
        setFrequencyDisplayUnit(
            if (displayPreferences.frequencyDisplayUnit == FrequencyDisplayUnit.MHZ) {
                FrequencyDisplayUnit.KHZ
            } else {
                FrequencyDisplayUnit.MHZ
            },
        )
    }

    private fun toggleTemperatureDisplayUnit() {
        setTemperatureDisplayUnit(
            if (displayPreferences.temperatureDisplayUnit == TemperatureDisplayUnit.CELSIUS) {
                TemperatureDisplayUnit.FAHRENHEIT
            } else {
                TemperatureDisplayUnit.CELSIUS
            },
        )
    }

    private fun toggleTimeSetMode() {
        setTimeSetMode(
            if (displayPreferences.timeSetMode == TimeSetMode.SYSTEM_CLOCK) {
                TimeSetMode.MANUAL
            } else {
                TimeSetMode.SYSTEM_CLOCK
            },
        )
    }

    private fun isScheduleInteractionSuppressed(): Boolean {
        return System.currentTimeMillis() < suppressScheduleInteractionUntilMs
    }

    private fun setRawSerialVisible(isVisible: Boolean) {
        rawSerialRowPanel.isVisible = isVisible
        displayPreferences = displayPreferences.copy(rawSerialVisible = isVisible)
        persistDisplayPreferences()
        if (::showRawSerialMenuItem.isInitialized) {
            showRawSerialMenuItem.isSelected = isVisible
        }
        rawCommandField.isEnabled =
            isVisible &&
            !backgroundWorkInProgress &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED
        revalidate()
        repaint()
    }

    private fun formatFrequencyForDisplay(frequencyHz: Long?): String {
        return DesktopInputSupport.formatFrequencyForDisplay(
            frequencyHz,
            displayPreferences.frequencyDisplayUnit,
        )
    }

    private fun selectedFrequencyHz(spinner: JSpinner): Long {
        spinner.commitEdit()
        val selectedValue = spinner.value as? Number
            ?: error("Frequency selection is unavailable.")
        return DesktopInputSupport.frequencyHzFromSpinnerValue(
            selectedValue,
            displayPreferences.frequencyDisplayUnit,
        )
    }

    private fun persistDisplayPreferences() {
        displayPreferencesStore.save(
            displayPreferences.copy(
                logVisible = logVisible,
                rawSerialVisible = rawSerialRowPanel.isVisible,
            ),
        )
    }

    private fun cloneTemplatePatternSpeedFor(settings: DeviceSettings): Int? {
        return if (DesktopInputSupport.patternSpeedBelongsToTimedEventSettings(settings.eventType)) {
            settings.patternCodeSpeedWpm
        } else {
            null
        }
    }

    private fun setStatus(status: String) {
        statusLabel.text = status
        busyDialogStatusLabel?.text = formatBusyDialogStatus(status)
    }

    private fun updateTemperatureRow(
        rowLabel: JLabel,
        field: JTextField,
        temperatureC: Double?,
    ) {
        setInformationalFieldText(
            field,
            DesktopInputSupport.formatTemperatureOrWaiting(temperatureC, displayPreferences.temperatureDisplayUnit),
            unreadPlaceholder = false,
        )
        val alertColor = when (TemperatureAlertSupport.alertLevel(temperatureC)) {
            TemperatureAlertLevel.NORMAL -> null
            TemperatureAlertLevel.WARNING -> warningForeground
            TemperatureAlertLevel.DANGER -> alertForeground
        }
        rowLabel.foreground = alertColor ?: defaultRowLabelForeground
        field.foreground = alertColor ?: defaultInformationalFieldForeground
        field.disabledTextColor = field.foreground
    }

    private fun clearTemperatureRow(rowLabel: JLabel, field: JTextField) {
        rowLabel.foreground = defaultRowLabelForeground
        setInformationalFieldText(field, "Not read")
    }

    private fun updateThermalHeadlineWarning(currentTemperatureC: Double?) {
        thermalHeadlineWarningMessage =
            when (TemperatureAlertSupport.alertLevel(currentTemperatureC)) {
                TemperatureAlertLevel.DANGER -> {
                    "High Temperature Warning! Current temperature ${
                        DesktopInputSupport.formatTemperatureOrWaiting(
                            currentTemperatureC,
                            displayPreferences.temperatureDisplayUnit,
                        )
                    }. Reduce device temperature before continuing."
                }
                TemperatureAlertLevel.NORMAL,
                TemperatureAlertLevel.WARNING,
                -> if (currentTemperatureC != null) {
                    null
                } else {
                    thermalHeadlineWarningMessage
                }
            }
        renderConnectionIndicator()
    }

    private fun scheduleBusyDialog(status: String) {
        busyDialogShowTimer?.stop()
        busyDialogShowTimer = Timer(BUSY_DIALOG_DELAY_MS) {
            busyDialogShowTimer?.stop()
            busyDialogShowTimer = null
            if (!backgroundWorkInProgress || busyDialog != null) {
                return@Timer
            }
            showBusyDialog(status)
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun showBusyDialog(status: String) {
        if (!backgroundWorkInProgress || busyDialog != null) {
            return
        }

        val detailLabel = JLabel(formatBusyDialogStatus(status)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
        }
        val progressBar = JProgressBar().apply {
            minimum = 0
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
        val progressPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(progressBar, BorderLayout.CENTER)
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(16, 18, 14, 18)
            add(
                JLabel(busyDialogPrimaryMessage()).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                },
            )
            add(detailLabel)
            add(Box.createVerticalStrut(12))
            add(progressPanel)
        }
        val dialog = JDialog(this, "Please Wait", Dialog.ModalityType.APPLICATION_MODAL).apply {
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            isResizable = false
            contentPane.add(content)
            pack()
            setLocationRelativeTo(this@SerialSlingerDesktopFrame)
        }

        busyDialog = dialog
        busyDialogStatusLabel = detailLabel
        busyDialogProgressBar = progressBar
        busyDialogProgressPanel = progressPanel
        updateBusyDialogProgressUi()
        dialog.isVisible = true
    }

    private fun hideBusyDialog() {
        busyDialogShowTimer?.stop()
        busyDialogShowTimer = null
        busyDialogStatusLabel = null
        busyDialogProgressBar = null
        busyDialogProgressPanel = null
        busyDialog?.let { dialog ->
            busyDialog = null
            if (dialog.isDisplayable) {
                dialog.isVisible = false
                dialog.dispose()
            }
        }
    }

    private fun busyDialogPrimaryMessage(): String {
        return if (currentTransport != null || currentConnectedPortPath != null) {
            "Talking with device..."
        } else {
            "Working..."
        }
    }

    private fun setBusyProgress(completed: Int, total: Int, label: String? = null) {
        val safeTotal = total.coerceAtLeast(1)
        busyProgressState = BusyProgressState(
            completed = completed.coerceIn(0, safeTotal),
            total = safeTotal,
            label = label,
        )
        SwingUtilities.invokeLater { updateBusyDialogProgressUi() }
    }

    private fun setBusyProgressRange(
        startPercent: Int,
        endPercent: Int,
        completed: Int,
        total: Int,
        label: String? = null,
    ) {
        val boundedStart = startPercent.coerceIn(0, 100)
        val boundedEnd = endPercent.coerceIn(boundedStart, 100)
        val safeTotal = total.coerceAtLeast(1)
        val safeCompleted = completed.coerceIn(0, safeTotal)
        val scaledCompleted = boundedStart + (((boundedEnd - boundedStart) * safeCompleted) / safeTotal)
        setBusyProgress(scaledCompleted, 100, label)
    }

    private fun clearBusyProgress() {
        busyProgressState = null
        SwingUtilities.invokeLater { updateBusyDialogProgressUi() }
    }

    private fun updateBusyDialogProgressUi() {
        val progressBar = busyDialogProgressBar ?: return
        val progressPanel = busyDialogProgressPanel ?: return
        val state = busyProgressState
        progressBar.string = null
        progressBar.isStringPainted = false
        if (state == null) {
            progressPanel.isVisible = false
        } else {
            progressPanel.isVisible = true
            progressBar.minimum = 0
            progressBar.maximum = state.total
            progressBar.value = state.completed
        }
        progressPanel.revalidate()
        progressPanel.repaint()
    }

    private fun commandProgressLabel(completed: Int, total: Int): String {
        val noun = if (total == 1) "command" else "commands"
        return "$completed of $total $noun"
    }

    private fun formatBusyDialogStatus(status: String): String {
        val escapedStatus = status
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return "<html><div style='width: 260px;'>$escapedStatus</div></html>"
    }

    private fun sendRawSerialCommand() {
        val command = rawCommandField.text.trim()
        if (command.isEmpty() || backgroundWorkInProgress) {
            return
        }

        val transport = currentTransport ?: run {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        val state = currentState ?: run {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }

        showConnectionIndicator(
            ConnectionIndicatorState.SEARCHING,
            "Sending raw serial command on ${currentConnectedPortPath.orEmpty()}...",
        )
        runInBackground("Sending raw serial command...") {
            setBusyProgress(0, 1, commandProgressLabel(0, 1))
            transport.sendCommands(listOf(command))
            val responseLines = transport.readAvailableLines()
            setBusyProgress(1, 1, commandProgressLabel(1, 1))
            val nextState = DeviceSessionWorkflow.ingestReportLines(state, responseLines)
            currentState = nextState
            loadedSnapshot = nextState.snapshot

            SwingUtilities.invokeLater {
                rawCommandField.text = ""
                applySnapshotToForm(nextState.snapshot)
                appendLog(
                    "Raw Command",
                    buildList {
                        add(DesktopLogEntry("TX $command", DesktopLogCategory.SERIAL))
                        responseLines.forEach { line ->
                            add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL))
                        }
                    },
                )
                showConnectionIndicator(
                    ConnectionIndicatorState.CONNECTED,
                    "Connected to SignalSlinger on ${currentConnectedPortPath.orEmpty()}",
                )
                setStatus("Sent raw command.")
            }
        }
    }

    private fun showConnectionIndicator(state: ConnectionIndicatorState, message: String) {
        connectionIndicatorState = state
        connectionIndicatorMessage = message
        renderConnectionIndicator()
    }

    private fun renderConnectionIndicator() {
        val state = if (thermalHeadlineWarningMessage != null) {
            ConnectionIndicatorState.DISCONNECTED
        } else {
            connectionIndicatorState
        }
        val message = thermalHeadlineWarningMessage ?: connectionIndicatorMessage
        headlineLabel.text = "${state.label}: $message"
        headlineLabel.foreground = state.foreground
        headlineLabel.background = state.background
        headlineLabel.isVisible = true
        autoDetectButton.background = state.background
        autoDetectButton.foreground = state.foreground
        autoDetectButton.isOpaque = true
    }

    private fun selectedProbe(): SignalSlingerPortProbe? {
        return (portComboBox.selectedItem as? SignalSlingerPortProbe)?.takeUnless { it.isPlaceholder }
    }

    private fun noConnectedDeviceProbe(): SignalSlingerPortProbe {
        return SignalSlingerPortProbe(
            portInfo = com.openardf.serialslinger.transport.DesktopSerialPortInfo(
                systemPortName = "",
                systemPortPath = "",
                descriptivePortName = "",
                portDescription = "",
            ),
            state = PortProbeState.NOT_DETECTED,
            summary = "No Connected Device Found",
            isPlaceholder = true,
        )
    }

    private fun connectedAliasGroupKey(): String? {
        if (currentState?.connectionState != ConnectionState.CONNECTED) {
            return null
        }
        val portPath = currentConnectedPortPath ?: return null
        return DesktopSmartPollingPolicy.aliasGroupKey(portPath)
    }

    private fun selectPort(systemPortPath: String) {
        for (index in 0 until portModel.size) {
            val probe = portModel.getElementAt(index)
            if (probe.portInfo.systemPortPath == systemPortPath) {
                portComboBox.selectedIndex = index
                break
            }
        }
    }

    private fun loadPort(portPath: String): LoadedConnection {
        if (
            currentConnectedPortPath == portPath &&
            currentTransport != null &&
            currentState != null &&
            currentState?.connectionState == ConnectionState.CONNECTED
        ) {
            val initialRefresh = DeviceSessionController.refreshFromDevice(
                state = requireNotNull(currentState),
                transport = requireNotNull(currentTransport),
                startEditing = true,
                progress = { completed, total ->
                    setBusyProgressRange(0, 85, completed, total, commandProgressLabel(completed, total))
                },
            )
            requireLoadResponses(portPath, initialRefresh)
            setBusyProgress(92, 100, "Checking device time")
            val finalClockSample = postLoadClockSample(requireNotNull(currentTransport), initialRefresh.state.snapshot)
            setBusyProgress(100, 100, "Done")
            val finalResult = mergeLoadResults(initialRefresh, finalClockSample?.first)
            return LoadedConnection(
                portPath = portPath,
                transport = requireNotNull(currentTransport),
                result = finalResult,
                previousTransport = null,
                previousState = null,
                loadLogTitle = "Load",
                loadLogLeadEntries = emptyList(),
                clockAnchor = finalClockSample?.second,
            )
        }

        val previousTransport = currentTransport
        val previousState = currentState
        val transport = DesktopSerialTransport(portPath)
        val initialLoad = DeviceSessionController.connectAndLoad(
            transport,
            progress = { completed, total ->
                setBusyProgressRange(0, 85, completed, total, commandProgressLabel(completed, total))
            },
        )
        requireLoadResponses(portPath, initialLoad)
        setBusyProgress(92, 100, "Checking device time")
        val finalClockSample = postLoadClockSample(transport, initialLoad.state.snapshot)
        setBusyProgress(100, 100, "Done")
        val finalResult = mergeLoadResults(initialLoad, finalClockSample?.first)

        return LoadedConnection(
            portPath = portPath,
            transport = transport,
            result = finalResult,
            previousTransport = previousTransport,
            previousState = previousState,
            loadLogTitle = "Load",
            loadLogLeadEntries = emptyList(),
            clockAnchor = finalClockSample?.second,
        )
    }

    private fun requireLoadResponses(
        portPath: String,
        result: DeviceLoadResult,
    ) {
        require(result.linesReceived.isNotEmpty()) {
            "No response from SignalSlinger on `$portPath` while loading settings."
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
        transport: DesktopSerialTransport,
        snapshot: DeviceSnapshot?,
    ): Pair<DeviceLoadResult, ClockDisplayAnchor>? {
        val loadedSnapshot = snapshot ?: return null
        if (!loadedSnapshot.capabilities.supportsScheduling) {
            return null
        }

        Thread.sleep(80L)
        val samples = observeClockPhaseSamples(transport, maxSamples = 4)
        cachedManualWriteDelayMillis = estimateManualWriteDelayMillis(samples)
        var updatedState = DeviceSessionState(
            connectionState = ConnectionState.CONNECTED,
            snapshot = loadedSnapshot,
            editableSettings = EditableDeviceSettings.fromDeviceSettings(loadedSnapshot.settings),
        )
        samples.forEach { sample ->
            updatedState = DeviceSessionWorkflow.ingestReportLines(updatedState, sample.responseLines)
        }
        val phaseErrorMillis = DesktopInputSupport.estimateClockPhaseErrorMillis(
            samples.map { sample ->
                DesktopInputSupport.ClockPhaseSample(
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
            traceEntries = buildList {
                samples.forEach { sample ->
                    add(SerialTraceEntry(sample.sentAtMs, SerialTraceDirection.TX, sample.command))
                    addAll(sample.responseLines.map { line ->
                        SerialTraceEntry(sample.receivedAtMs, SerialTraceDirection.RX, line)
                    })
                }
            },
        ) to ClockDisplayAnchor(
            currentTimeCompact = latestSample?.reportedTimeCompact,
            referenceTime = latestSample?.midpointAt,
            phaseErrorMillis = phaseErrorMillis,
        )
    }

    private fun applyLoadedConnection(connection: LoadedConnection) {
        if (connection.previousTransport != null && connection.previousTransport !== connection.transport) {
            connection.previousState?.let { previousState ->
                DeviceSessionController.disconnect(previousState, connection.previousTransport)
            }
        }

        currentTransport = connection.transport
        currentState = connection.result.state
        currentConnectedPortPath = connection.portPath
        loadedSnapshot = connection.result.state.snapshot
        consecutiveDeviceTimeCheckNoResponseCount = 0
        connection.result.state.snapshot?.settings?.let { loadedSettings ->
            if (cloneTemplateSettings == null) {
                rememberCloneTemplateFrom(loadedSettings)
                updateCloneTemplateLabel(
                    "Clone template captured from current device.",
                    Color(0x0B, 0x3D, 0x91),
                )
            } else {
                updateCloneTemplateLabel(
                    "Clone template locked. Display shows attached device state.",
                    Color(0x9A, 0x67, 0x11),
                )
            }
        }
        autoDetectNoDeviceFound = false
        showConnectionIndicator(ConnectionIndicatorState.CONNECTED, "Connected to SignalSlinger on ${connection.portPath}")
        portMemory.saveLastWorkingPortPath(connection.portPath)
        try {
            knownProbeResults[connection.portPath] = knownProbeResults[connection.portPath]?.copy(
                state = PortProbeState.DETECTED,
                summary = "SignalSlinger detected",
                lastProbedAtMs = System.currentTimeMillis(),
            ) ?: SignalSlingerPortProbe(
                portInfo = resolvePortInfoFor(connection.portPath),
                state = PortProbeState.DETECTED,
                summary = "SignalSlinger detected",
                lastProbedAtMs = System.currentTimeMillis(),
            )
            connection.clockAnchor?.let { anchor ->
                applyClockDisplayAnchor(anchor)
                updateClockPhaseWarning(anchor.phaseErrorMillis)
            }
            applySnapshotToForm(
                connection.result.state.snapshot,
                recalculateClockOffset = connection.clockAnchor == null,
            )
            SwingUtilities.invokeLater {
                autoDetectButton.requestFocusInWindow()
            }
            appendLoadLog(
                title = connection.loadLogTitle,
                result = connection.result,
                leadEntries = connection.loadLogLeadEntries,
            )
            refreshAvailablePorts(silent = true)
        } catch (exception: Exception) {
            appendLog(
                "Load Apply Error",
                listOf(
                    DesktopLogEntry(
                        "Failed while applying loaded settings for ${connection.portPath}: ${exception.message ?: exception::class.simpleName}",
                        DesktopLogCategory.APP,
                    ),
                ),
            )
            setStatus("Load apply error on ${connection.portPath}.")
            throw exception
        }
    }

    private fun resolvePortInfoFor(portPath: String): DesktopSerialPortInfo {
        return knownProbeResults[portPath]?.portInfo
            ?: SignalSlingerPortDiscovery.listAvailablePorts()
                .map { it.portInfo }
                .firstOrNull { it.systemPortPath == portPath }
            ?: DesktopSerialPortInfo(
                systemPortName = portPath.substringAfterLast('/'),
                systemPortPath = portPath,
                descriptivePortName = portPath,
                portDescription = "Connected serial port",
            )
    }

    private fun setLogVisible(isVisible: Boolean) {
        logVisible = isVisible
        if (::showLogMenuItem.isInitialized) {
            showLogMenuItem.isSelected = isVisible
        }
        displayPreferences = displayPreferences.copy(logVisible = isVisible)
        persistDisplayPreferences()
        contentSplitPane.rightComponent = if (isVisible) logScroll else JPanel()
        contentSplitPane.dividerSize = if (isVisible) 8 else 0
        if (isVisible) {
            logAutoScroll = true
            contentSplitPane.resizeWeight = 0.60
            contentSplitPane.setDividerLocation(0.60)
            SwingUtilities.invokeLater { scrollLogToBottom() }
        } else {
            contentSplitPane.resizeWeight = 1.0
        }
        setBusy(backgroundWorkInProgress)
        revalidate()
        repaint()
    }

    private fun openLogFolder() {
        val logDirectory = sessionLog.logDirectory().toFile()
        require(Desktop.isDesktopSupported()) {
            "Desktop integration is not supported on this system."
        }
        Desktop.getDesktop().open(logDirectory)
        setStatus("Opened log folder ${logDirectory.absolutePath}.")
    }

    private fun copyCurrentLogToClipboard() {
        val currentLogText = sessionLog.loadCurrentLogText()
        if (currentLogText.isBlank()) {
            JOptionPane.showMessageDialog(this, "There is no current log content to copy yet.")
            return
        }

        Toolkit.getDefaultToolkit().systemClipboard.setContents(
            StringSelection(currentLogText),
            null,
        )
        setStatus("Copied current log to the clipboard.")
    }

    private fun confirmAndClearCurrentLog() {
        val confirmation = JOptionPane.showConfirmDialog(
            this,
            "Archive the current log and start a fresh empty log file?",
            "Clear Current Log",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (confirmation != JOptionPane.OK_OPTION) {
            return
        }

        val archivedFile = sessionLog.archiveCurrentLog()
        refreshDisplayedLogFromDisk()
        if (archivedFile != null) {
            setStatus("Archived current log to ${archivedFile.fileName}.")
        } else {
            setStatus("Current log was already empty.")
        }
    }

    private fun confirmAndDeleteAllLogs() {
        val confirmation = JOptionPane.showConfirmDialog(
            this,
            "Delete all SerialSlinger log files in ${sessionLog.logDirectory()}?",
            "Delete All Log Files",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (confirmation != JOptionPane.OK_OPTION) {
            return
        }

        val deletedCount = sessionLog.deleteAllLogs()
        refreshDisplayedLogFromDisk()
        setStatus(
            if (deletedCount == 1) {
                "Deleted 1 log file."
            } else {
                "Deleted $deletedCount log files."
            },
        )
    }

    private fun refreshDisplayedLogFromDisk() {
        logPane.text = ""
        appendRenderedLog(sessionLog.loadCurrentLogText())
    }

    private fun appendRenderedLog(rendered: String) {
        if (rendered.isEmpty()) {
            return
        }

        val document = logPane.styledDocument
        rendered.split('\n').forEach { line ->
            val style = styleForRenderedLogLine(line)
            document.insertString(document.length, "$line\n", style)
        }
        if (logAutoScroll) {
            SwingUtilities.invokeLater { scrollLogToBottom() }
        }
    }

    private fun syncDeviceTimeToSystem(onComplete: (Boolean) -> Unit = {}) {
        val transport = currentTransport
        val state = currentState
        val snapshot = loadedSnapshot
        if (transport == null || state == null || snapshot == null) {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            onComplete(false)
            return
        }
        if (!snapshot.capabilities.supportsScheduling) {
            JOptionPane.showMessageDialog(this, "This firmware version does not report scheduling/time support.")
            onComplete(false)
            return
        }

        runInBackground("Syncing device time to system time...") {
            try {
                val syncProgressTotal = estimatedSyncProgressUnits()
                setBusyProgress(0, syncProgressTotal, "Preparing sync")
                val syncResult = performAlignedTimeSync(
                    transport = transport,
                    state = state,
                    snapshot = snapshot,
                    onProgress = { completed, label ->
                        setBusyProgress(completed, syncProgressTotal, label)
                    },
                )
                setBusyProgress(syncProgressTotal, syncProgressTotal, "Done")
                val finalAttempt = syncResult.finalAttempt
                currentState = finalAttempt.state
                loadedSnapshot = finalAttempt.state.snapshot
                deviceTimeOffset = Duration.ofMillis(-(finalAttempt.phaseErrorMillis ?: 0L))
                lastDeviceTimeCheckAtMs = System.currentTimeMillis()

                SwingUtilities.invokeLater {
                    applySnapshotToForm(finalAttempt.state.snapshot, recalculateClockOffset = false)
                    appendSyncLog(
                        attempts = syncResult.attempts,
                        latencySamples = syncResult.latencySamples,
                    )
                    updateClockPhaseWarning(finalAttempt.phaseErrorMillis)
                    setStatus(
                        finalAttempt.phaseErrorMillis?.let { phase ->
                            "Device time synchronized. Measured phase error ${DesktopInputSupport.formatSignedDurationMillis(phase)}."
                        } ?: "Device time synchronized.",
                    )
                    Timer(1) { onComplete(true) }.apply {
                        isRepeats = false
                        start()
                    }
                }
            } catch (exception: Exception) {
                SwingUtilities.invokeLater {
                    Timer(1) { onComplete(false) }.apply {
                        isRepeats = false
                        start()
                    }
                }
                throw exception
            }
        }
    }

    private fun setDeviceTimeToSelection() {
        val transport = currentTransport
        val state = currentState
        val snapshot = loadedSnapshot
        if (transport == null || state == null || snapshot == null) {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        if (!snapshot.capabilities.supportsScheduling) {
            JOptionPane.showMessageDialog(this, "This firmware version does not report scheduling/time support.")
            return
        }

        val selectedBaseTime = try {
            val selected = selectedSpinnerDateTime(manualTimeSpinner)
            val validatedCompact = DesktopInputSupport.validateCurrentTimeForWrite(
                DesktopInputSupport.formatCompactTimestamp(selected),
            )
            requireNotNull(validatedCompact) { "Choose a valid date and time to apply." }
            DesktopInputSupport.parseCompactTimestamp(validatedCompact)
        } catch (exception: IllegalArgumentException) {
            JOptionPane.showMessageDialog(
                this,
                exception.message ?: "Choose a valid date and time to apply.",
                "Set Device Time",
                JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        val selectedAt = java.time.LocalDateTime.now()
        val selectedCompact = DesktopInputSupport.formatCompactTimestamp(selectedBaseTime)
        val optimisticSnapshot = snapshot.copy(
            settings = snapshot.settings.copy(currentTimeCompact = selectedCompact),
        )
        currentState = state.copy(snapshot = optimisticSnapshot)
        loadedSnapshot = optimisticSnapshot
        deviceTimeOffset = Duration.between(selectedAt, selectedBaseTime)
        applySnapshotToForm(optimisticSnapshot, recalculateClockOffset = false)
        setStatus("Setting device time to ${DesktopInputSupport.formatSystemTimestamp(selectedBaseTime)}...")

        runInBackground(
            "Setting device time to ${DesktopInputSupport.formatSystemTimestamp(selectedBaseTime)}...",
            showErrorDialog = false,
        ) {
            try {
                val setResult = performAlignedManualTimeSet(
                    transport = transport,
                    state = state,
                    snapshot = snapshot,
                    selectedBaseTime = selectedBaseTime,
                    selectedAt = selectedAt,
                )
                currentState = setResult.attempt.state
                loadedSnapshot = setResult.attempt.state.snapshot
                lastDeviceTimeCheckAtMs = System.currentTimeMillis()

                SwingUtilities.invokeLater {
                    applySnapshotToForm(setResult.attempt.state.snapshot, recalculateClockOffset = false)
                    appendSyncLog(
                        title = "Set Device Time",
                        attempts = listOf(setResult.attempt),
                        latencySamples = setResult.latencySamples,
                    )
                    updateClockPhaseWarning(setResult.attempt.phaseErrorMillis)
                    if (setResult.succeeded) {
                        setStatus("Device time set to ${DesktopInputSupport.formatSystemTimestamp(selectedBaseTime)}.")
                    } else {
                        setStatus("Device time was written, but verification differed from the requested manual set.")
                        JOptionPane.showMessageDialog(
                            this,
                            "The device time was written, but verification differed from the requested manual set by " +
                                "${setResult.deviationMillis?.let(DesktopInputSupport::formatSignedDurationMillis) ?: "an unknown amount"}.\n\n" +
                                "Review the log and retry if needed.",
                            "Set Device Time",
                            JOptionPane.WARNING_MESSAGE,
                        )
                    }
                }
            } catch (exception: IllegalArgumentException) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this,
                        exception.message ?: "Choose a valid date and time to apply.",
                        "Set Device Time",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                    setStatus(exception.message ?: "Manual time set cancelled.")
                }
            }
        }
    }

    private fun performAlignedTimeSync(
        transport: DesktopSerialTransport,
        state: DeviceSessionState,
        snapshot: DeviceSnapshot,
        onProgress: ((completed: Int, label: String) -> Unit)? = null,
    ): TimeSyncOperationResult {
        onProgress?.invoke(0, "Measuring clock latency")
        val latencySamples = sampleClockReadLatency(transport, sampleCount = SYNC_LATENCY_SAMPLE_COUNT) { completed, _ ->
            onProgress?.invoke(completed, "Measuring clock latency")
        }
        var oneWayDelayMillis = DesktopInputSupport.medianMillis(latencySamples.map { it.roundTripMillis }) / 2
        var workingState = state
        var workingSnapshot = snapshot
        var bestAttempt: SyncAttempt? = null
        val attempts = mutableListOf<SyncAttempt>()
        var syncComplete = false

        repeat(SYNC_MAX_ATTEMPTS) { attemptIndex ->
            if (syncComplete) {
                return@repeat
            }
            val attemptBase = SYNC_LATENCY_SAMPLE_COUNT + (attemptIndex * SYNC_ATTEMPT_PROGRESS_UNITS)
            val syncTargetTime = DesktopInputSupport.nextSyncTargetTime(
                minimumLeadMillis = maxOf(1_500L, oneWayDelayMillis + 400L),
            )
            onProgress?.invoke(attemptBase, "Waiting to send sync target")
            val attempt = performSyncAttempt(
                transport = transport,
                state = workingState,
                snapshot = workingSnapshot,
                targetTime = syncTargetTime,
                dispatchTime = syncTargetTime,
                estimatedOneWayDelayMillis = oneWayDelayMillis.coerceIn(20L, 750L),
                onSubmitProgress = { completed, _ ->
                    onProgress?.invoke(
                        attemptBase + completed,
                        "Sync attempt ${attemptIndex + 1} of $SYNC_MAX_ATTEMPTS",
                    )
                },
                onVerificationProgress = { completed, _ ->
                    onProgress?.invoke(
                        attemptBase + SYNC_SUBMIT_PROGRESS_UNITS + completed,
                        "Verifying sync attempt ${attemptIndex + 1}",
                    )
                },
            )
            attempts += attempt
            workingState = attempt.state
            workingSnapshot = requireNotNull(attempt.state.snapshot)
            bestAttempt = chooseBetterSyncAttempt(bestAttempt, attempt)

            val phaseErrorMillis = attempt.phaseErrorMillis ?: run {
                syncComplete = true
                return@repeat
            }
            if (kotlin.math.abs(phaseErrorMillis) <= 500L) {
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
            succeeded = finalAttempt.phaseErrorMillis?.let { kotlin.math.abs(it) <= 500L } ?: true,
        )
    }

    private fun performAlignedManualTimeSet(
        transport: DesktopSerialTransport,
        state: DeviceSessionState,
        snapshot: DeviceSnapshot,
        selectedBaseTime: java.time.LocalDateTime,
        selectedAt: java.time.LocalDateTime,
    ): ManualTimeSetResult {
        val estimatedWriteDelayMillis = cachedManualWriteDelayMillis ?: 0L
        val appliedDeviceTime = DesktopInputSupport.adjustManualTimeTargetForWrite(selectedBaseTime, estimatedWriteDelayMillis)
        val attempt = performSyncAttempt(
            transport = transport,
            state = state,
            snapshot = snapshot,
            targetTime = appliedDeviceTime,
            dispatchTime = java.time.LocalDateTime.now(),
            estimatedOneWayDelayMillis = estimatedWriteDelayMillis,
        )
        val expectedOffset = Duration.between(selectedAt, selectedBaseTime)
        val deviationMillis = DesktopInputSupport.estimateClockPhaseErrorMillis(
            attempt.verificationSamples.map { sample ->
                DesktopInputSupport.ClockPhaseSample(
                    midpointAt = sample.midpointAt.plus(expectedOffset),
                    reportedTimeCompact = sample.reportedTimeCompact,
                )
            },
        )
        return ManualTimeSetResult(
            latencySamples = emptyList(),
            attempt = attempt,
            appliedDeviceTime = appliedDeviceTime,
            deviationMillis = deviationMillis,
            succeeded = deviationMillis?.let { kotlin.math.abs(it) <= 500L } ?: false,
        )
    }

    private fun disableProgrammedEvent() {
        val transport = currentTransport
        val state = currentState
        val snapshot = loadedSnapshot
        if (transport == null || state == null || snapshot == null) {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }

        runInBackground("Disabling event...") {
            val goCommands = listOf("GO 0")
            val currentTotalCommands = 1 + 3 + if (snapshot.capabilities.supportsScheduling) 1 else 0
            var commandsCompleted = 0
            val goLines = mutableListOf<String>()
            setBusyProgress(commandsCompleted, currentTotalCommands, commandProgressLabel(commandsCompleted, currentTotalCommands))
            transport.sendCommands(goCommands)
            goLines += transport.readAvailableLines()
            commandsCompleted += goCommands.size
            setBusyProgress(commandsCompleted, currentTotalCommands, commandProgressLabel(commandsCompleted, currentTotalCommands))

            var nextState = if (goLines.isNotEmpty()) {
                DeviceSessionWorkflow.ingestReportLines(state, goLines)
            } else {
                state
            }

            val currentSnapshot = nextState.snapshot ?: snapshot
            val finishMirrorCommands = listOf("CLK F =", "CLK F", "EVT")
            val finishMirrorLines = mutableListOf<String>()
            finishMirrorCommands.forEach { command ->
                transport.sendCommands(listOf(command))
                finishMirrorLines += transport.readAvailableLines()
                commandsCompleted += 1
                setBusyProgress(commandsCompleted, currentTotalCommands, commandProgressLabel(commandsCompleted, currentTotalCommands))
            }
            if (finishMirrorLines.isNotEmpty()) {
                nextState = DeviceSessionWorkflow.ingestReportLines(nextState, finishMirrorLines)
            }

            val clockRefreshCommands = if (currentSnapshot.capabilities.supportsScheduling) listOf("CLK") else emptyList()
            val clockRefreshLines = mutableListOf<String>()
            val clockRefreshAnchor = if (clockRefreshCommands.isNotEmpty()) {
                val clockSample = readClockSample(transport, command = "CLK")
                clockRefreshLines += clockSample.responseLines
                commandsCompleted += 1
                setBusyProgress(commandsCompleted, currentTotalCommands, commandProgressLabel(commandsCompleted, currentTotalCommands))
                if (clockRefreshLines.isNotEmpty()) {
                    nextState = DeviceSessionWorkflow.ingestReportLines(nextState, clockRefreshLines)
                }
                ClockDisplayAnchor(
                    currentTimeCompact = clockSample.reportedTimeCompact,
                    referenceTime = clockSample.midpointAt,
                    phaseErrorMillis = DesktopInputSupport.estimateCoarseClockErrorMillis(
                        DesktopInputSupport.ClockPhaseSample(
                            midpointAt = clockSample.midpointAt,
                            reportedTimeCompact = clockSample.reportedTimeCompact,
                        ),
                    ),
                )
            } else {
                null
            }

            currentState = nextState
            loadedSnapshot = nextState.snapshot
            nextState.snapshot?.settings?.let(::rememberCloneTemplateFrom)

            SwingUtilities.invokeLater {
                if (clockRefreshLines.isNotEmpty()) {
                    lastDeviceTimeCheckAtMs = System.currentTimeMillis()
                    clockRefreshAnchor?.let(::applyClockDisplayAnchor)
                    updateClockPhaseWarning(clockRefreshAnchor?.phaseErrorMillis)
                }
                applySnapshotToForm(
                    nextState.snapshot,
                    recalculateClockOffset = clockRefreshLines.isEmpty(),
                )
                appendDisableEventLog(
                    goCommands = goCommands,
                    goLines = goLines,
                    finishMirrorCommands = finishMirrorCommands,
                    finishMirrorLines = finishMirrorLines,
                    clockRefreshCommands = clockRefreshCommands,
                    clockRefreshLines = clockRefreshLines,
                )
                setStatus("Event disabled.")
            }
        }
    }

    private fun updateDisplayedClockFields() {
        val snapshot = loadedSnapshot
        val sampledSystemNow = java.time.LocalDateTime.now()
        val displayNow = sampledSystemNow.withNano(0)
        if (snapshot == null) {
            systemTimeField.text = DesktopInputSupport.formatSystemTimestamp(displayNow)
            setInformationalFieldText(currentTimeField, "Not read")
            currentTimeField.toolTipText = null
            systemTimeField.toolTipText = null
            setInformationalFieldText(startsInField, "Not read")
            setInformationalFieldText(lastsField, "Not read")
            disableEventButton.isEnabled = false
            syncTimeButton.isEnabled = false
            return
        }
        val timedSettings = snapshot.settings
        val displayedDeviceTimeCompact = displayedDeviceTimeCompact(sampledSystemNow)

        systemTimeField.text = DesktopInputSupport.formatSystemTimestamp(displayNow)
        val clockWarningActive = hasClockPhaseWarning()
        val phaseErrorSummary = lastClockPhaseErrorMillis?.let {
            "Measured phase error: ${DesktopInputSupport.formatSignedDurationMillis(it)}"
        }
        setInformationalFieldText(
            currentTimeField,
            DesktopInputSupport.formatCompactTimestampOrNotSet(displayedDeviceTimeCompact),
            unreadPlaceholder = false,
            alert = clockWarningActive,
        )
        currentTimeField.toolTipText = if (clockWarningActive) {
            phaseErrorSummary
        } else {
            null
        }
        systemTimeField.toolTipText = phaseErrorSummary
        setInformationalFieldText(startsInField, DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = snapshot.status.eventEnabled,
            eventStateSummary = snapshot.status.eventStateSummary,
            currentTimeCompact = displayedDeviceTimeCompact,
            startTimeCompact = timedSettings.startTimeCompact,
            finishTimeCompact = timedSettings.finishTimeCompact,
            startsInFallback = snapshot.status.eventStartsInSummary,
            daysToRun = timedSettings.daysToRun,
        ), unreadPlaceholder = false)
        val lastsAlertActive = DesktopInputSupport.eventDurationDiffersFromDefault(
            startTimeCompact = timedSettings.startTimeCompact,
            finishTimeCompact = timedSettings.finishTimeCompact,
            defaultEventLengthMinutes = displayPreferences.defaultEventLengthMinutes,
        )
        lastsRowLabel.foreground = if (lastsAlertActive) alertForeground else defaultRowLabelForeground
        setInformationalFieldText(
            lastsField,
            DesktopInputSupport.describeEventDurationHoursMinutes(
                startTimeCompact = timedSettings.startTimeCompact,
                finishTimeCompact = timedSettings.finishTimeCompact,
                fallback = snapshot.status.eventDurationSummary,
            ),
            unreadPlaceholder = false,
            alert = lastsAlertActive,
        )
        syncTimeButton.isEnabled =
            displayPreferences.timeSetMode == TimeSetMode.SYSTEM_CLOCK &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED &&
            loadedSnapshot?.capabilities?.supportsScheduling == true
        disableEventButton.isEnabled =
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED &&
            (
                loadedSnapshot?.status?.eventEnabled != false ||
                    DesktopInputSupport.isManualEventStateSummary(loadedSnapshot?.status?.eventStateSummary)
                )
    }

    private fun clearFormForUnread() {
        loadedSnapshot = null
        clearRelativeScheduleDisplayOverrides()
        deviceTimeOffset = null
        cachedManualWriteDelayMillis = null
        stationIdField.text = ""
        eventTypeCombo.selectedItem = null
        syncFoxRoleOptions(EventType.NONE, null)
        foxRoleCombo.selectedItem = null
        patternTextField.text = ""
        idSpeedField.selectedItem = null
        devicePatternSpeedField.selectedItem = null
        timedPatternSpeedField.selectedItem = null
        setDateTimeEditorValue(startTimeSpinner, startTimeStatusLabel, null)
        setDateTimeEditorValue(finishTimeSpinner, finishTimeStatusLabel, null)
        refreshScheduleTimeEditorPresentation(null)
        daysField.value = 1
        daysRemainingLabel.text = " "
        daysRemainingLabel.toolTipText = null
        setInformationalFieldText(currentFrequencyField, "Not read")
        setInformationalFieldText(currentBankField, "Not read")
        clearFrequencySpinner(frequency1Field)
        clearFrequencySpinner(frequency2Field)
        clearFrequencySpinner(frequency3Field)
        clearFrequencySpinner(frequencyBField)
        batteryThresholdField.selectedItem = null
        batteryModeCombo.selectedItem = null
        updateTransmissionsField(isEnabled = true)
        setInformationalFieldText(versionInfoField, "Not read")
        setInformationalFieldText(internalBatteryField, "Not read")
        setInformationalFieldText(externalBatteryField, "Not read")
        clearTemperatureRow(maximumTemperatureRowLabel, maximumTemperatureField)
        clearTemperatureRow(currentTemperatureRowLabel, currentTemperatureField)
        clearTemperatureRow(minimumTemperatureRowLabel, minimumTemperatureField)
        lastsRowLabel.foreground = defaultRowLabelForeground
        updateDisplayedClockFields()
        updateWritableControlAvailability(backgroundWorkInProgress)
    }

    private fun canEditLastsDuration(): Boolean {
        return !backgroundWorkInProgress &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED &&
            loadedSnapshot?.capabilities?.supportsScheduling == true
    }

    private fun displayedDeviceTimeCompact(systemNow: java.time.LocalDateTime = java.time.LocalDateTime.now()): String? {
        val settings = loadedSnapshot?.settings
        return when {
            deviceTimeOffset != null -> DesktopInputSupport.formatTruncatedCompactTimestamp(
                systemNow.plus(requireNotNull(deviceTimeOffset)),
            )
            else -> DesktopInputSupport.normalizeCurrentTimeCompactForDisplay(settings?.currentTimeCompact)
        }
    }

    private fun scheduleClockDisplayTick() {
        clockDisplayTimer?.stop()
        val nowMs = System.currentTimeMillis()
        val millisPastSecond = nowMs % 1_000L
        val displayTickOffsetMs = 500L
        val delayMs = if (millisPastSecond < displayTickOffsetMs) {
            displayTickOffsetMs - millisPastSecond
        } else {
            (1_000L - millisPastSecond) + displayTickOffsetMs
        }
        clockDisplayTimer = Timer(delayMs.toInt()) {
            updateDisplayedClockFields()
            maybeRefreshDeviceTimeFromDevice()
            scheduleClockDisplayTick()
        }.apply {
            isRepeats = false
            start()
        }
    }

    private fun maybeRefreshDeviceTimeFromDevice() {
        if (backgroundWorkInProgress) {
            return
        }
        if (currentTransport == null || currentState?.connectionState != ConnectionState.CONNECTED) {
            return
        }
        if (loadedSnapshot?.capabilities?.supportsScheduling != true) {
            return
        }
        if ((System.currentTimeMillis() - lastDeviceTimeCheckAtMs) < PERIODIC_DEVICE_TIME_CHECK_INTERVAL_MS) {
            return
        }

        runInBackground(
            "Refreshing device time from SignalSlinger...",
            showErrorDialog = false,
            showBusyDialog = false,
        ) {
            val transport = requireNotNull(currentTransport)
            val state = requireNotNull(currentState)
            val clockSample = readClockSample(transport, command = "CLK")
            val responseLines = clockSample.responseLines.toMutableList()
            lastDeviceTimeCheckAtMs = System.currentTimeMillis()
            val phaseErrorMillis = DesktopInputSupport.estimateCoarseClockErrorMillis(
                DesktopInputSupport.ClockPhaseSample(
                    midpointAt = clockSample.midpointAt,
                    reportedTimeCompact = clockSample.reportedTimeCompact,
                ),
            )
            val latestClockSample = clockSample

            SwingUtilities.invokeLater {
                if (responseLines.isNotEmpty()) {
                    consecutiveDeviceTimeCheckNoResponseCount = 0
                    currentState = DeviceSessionWorkflow.ingestReportLines(state, responseLines)
                    loadedSnapshot = currentState?.snapshot
                    if (phaseErrorMillis != null && kotlin.math.abs(phaseErrorMillis) <= 500L) {
                        applyClockDisplayAnchor(
                            ClockDisplayAnchor(
                                currentTimeCompact = latestClockSample.reportedTimeCompact,
                                referenceTime = latestClockSample.midpointAt,
                                phaseErrorMillis = phaseErrorMillis,
                            ),
                        )
                    }
                    updateClockPhaseWarning(phaseErrorMillis)
                    appendLog("Device Time Check", buildList {
                        add(DesktopLogEntry("TX ${clockSample.command}", DesktopLogCategory.SERIAL, clockSample.sentAtMs))
                        clockSample.responseLines.forEach { line ->
                            add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL, clockSample.receivedAtMs))
                        }
                        add(
                            DesktopLogEntry(
                                phaseErrorMillis?.let {
                                    "Estimated phase error: ${DesktopInputSupport.formatSignedDurationMillis(it)}"
                                } ?: "Estimated phase error: unavailable",
                                if (phaseErrorMillis != null && kotlin.math.abs(phaseErrorMillis) > 500L) {
                                    DesktopLogCategory.DEVICE
                                } else {
                                    DesktopLogCategory.APP
                                },
                                System.currentTimeMillis(),
                            ),
                        )
                    })
                    updateDisplayedClockFields()
                    setStatus(
                        if (phaseErrorMillis != null && kotlin.math.abs(phaseErrorMillis) > 500L) {
                            "Device clock appears out of sync with system time."
                        } else {
                            "Device time checked."
                        },
                    )
                } else {
                    consecutiveDeviceTimeCheckNoResponseCount += 1
                    val noResponseCount = consecutiveDeviceTimeCheckNoResponseCount
                    appendLog(
                        "Device Time Check",
                        listOf(
                            DesktopLogEntry(
                                "No response from connected SignalSlinger during periodic time check (${noResponseCount} of 2).",
                                DesktopLogCategory.APP,
                            ),
                        ),
                    )
                    if (noResponseCount >= 2) {
                        markConnectedPortAsUnresponsive("No response to two consecutive periodic device time checks.")
                    } else {
                        setStatus("No response from SignalSlinger during periodic time check (1 of 2).")
                    }
                }
            }
        }
    }

    private fun markConnectedPortAsUnresponsive(reason: String) {
        val portPath = currentConnectedPortPath ?: return
        try {
            currentTransport?.disconnect()
        } catch (_: Exception) {
        }
        currentTransport = null
        currentConnectedPortPath = null
        val updatedState = currentState?.let { state ->
            state.copy(
                connectionState = ConnectionState.DISCONNECTED,
                snapshot = state.snapshot?.copy(
                    status = state.snapshot.status.copy(
                        connectionState = ConnectionState.DISCONNECTED,
                        lastCommunicationError = reason,
                    ),
                ),
                lastError = reason,
            )
        }
        currentState = updatedState
        loadedSnapshot = updatedState?.snapshot
        knownProbeResults[portPath] = knownProbeResults[portPath]?.copy(
            state = PortProbeState.NOT_DETECTED,
            summary = "SignalSlinger no longer detected",
            lastProbedAtMs = System.currentTimeMillis(),
        ) ?: SignalSlingerPortProbe(
            portInfo = resolvePortInfoFor(portPath),
            state = PortProbeState.NOT_DETECTED,
            summary = "SignalSlinger no longer detected",
            lastProbedAtMs = System.currentTimeMillis(),
        )
        showConnectionIndicator(
            ConnectionIndicatorState.DISCONNECTED,
            "SignalSlinger is not responding on ${portPath}",
        )
        setStatus("SignalSlinger stopped responding on ${portPath}.")
        consecutiveDeviceTimeCheckNoResponseCount = 0
        clockPhaseWarningActive = false
        lastClockPhaseErrorMillis = null
        refreshAvailablePorts(silent = true)
        updateDisplayedClockFields()
    }

    private fun isTransportCommunicationFailure(exception: Exception): Boolean {
        val message = exception.message.orEmpty().lowercase()
        return message.contains("failed to write complete payload") ||
            message.contains("failed to open serial port") ||
            message.contains("no response from signalslinger") ||
            (message.contains("serial port `") && message.contains("is not connected"))
    }

    private fun handleTransportCommunicationFailure(
        message: String,
        showDialog: Boolean,
    ) {
        appendLog(
            "Communication Error",
            listOf(
                DesktopLogEntry(
                    message,
                    DesktopLogCategory.APP,
                ),
            ),
        )
        if (currentConnectedPortPath != null && currentState?.connectionState == ConnectionState.CONNECTED) {
            markConnectedPortAsUnresponsive(message)
        } else {
            try {
                currentTransport?.disconnect()
            } catch (_: Exception) {
            }
            currentTransport = null
            setStatus("Communication error: $message")
        }
        if (showDialog) {
            JOptionPane.showMessageDialog(
                this,
                "$message\n\nThe connection has been marked disconnected. You can reconnect or run Auto Detect again.",
            )
        }
    }

    private fun updateClockPhaseWarning(phaseErrorMillis: Long?) {
        val portPath = currentConnectedPortPath.orEmpty()
        lastClockPhaseErrorMillis = phaseErrorMillis
        val shouldWarn = hasClockPhaseWarning(phaseErrorMillis)
        clockPhaseWarningActive = shouldWarn
        when {
            shouldWarn -> showConnectionIndicator(
                ConnectionIndicatorState.SEARCHING,
                "Connected to SignalSlinger on $portPath. Device clock appears out of sync with system time.",
            )
            currentState?.connectionState == ConnectionState.CONNECTED -> showConnectionIndicator(
                ConnectionIndicatorState.CONNECTED,
                "Connected to SignalSlinger on $portPath",
            )
        }
    }

    private fun maybeShowCloneClockReminder(
        message: String,
        title: String,
        continueActionLabel: String,
        syncActionLabel: String,
    ): ClockWarningChoice {
        if (loadedSnapshot?.capabilities?.supportsScheduling != true || !clockPhaseWarningActive) {
            return ClockWarningChoice.CONTINUE
        }
        val phaseSummary = lastClockPhaseErrorMillis?.let {
            "Measured phase error: ${DesktopInputSupport.formatSignedDurationMillis(it)}"
        } ?: "Measured phase error: unavailable"
        val options = arrayOf("Cancel", syncActionLabel, continueActionLabel)
        val selection = JOptionPane.showOptionDialog(
            this,
            "$message\n\n$phaseSummary",
            title,
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[1],
        )
        return when (selection) {
            1 -> ClockWarningChoice.SYNC_THEN_CONTINUE
            2 -> ClockWarningChoice.CONTINUE
            else -> ClockWarningChoice.CANCEL
        }
    }

    private fun hasClockPhaseWarning(phaseErrorMillis: Long? = lastClockPhaseErrorMillis): Boolean {
        return phaseErrorMillis != null && kotlin.math.abs(phaseErrorMillis) > 500L
    }

    private fun createDateTimeSpinner(
        pattern: String = "yyyy-MM-dd HH:mm",
        calendarField: Int = Calendar.MINUTE,
    ): JSpinner {
        return JSpinner(SpinnerDateModel(Date(), null, null, calendarField)).apply {
            editor = JSpinner.DateEditor(this, pattern)
        }
    }

    private fun createFrequencySpinner(): JSpinner {
        return JSpinner()
    }

    private fun createFieldAwareDateTimeSpinner(
        pattern: String = "yyyy-MM-dd HH:mm",
        steppingProvider: (LocalDateTime, Int, Boolean) -> LocalDateTime?,
    ): JSpinner {
        lateinit var spinner: JSpinner
        spinner = JSpinner(
            FieldAwareSpinnerDateModel(
                initialValue = Date(),
                defaultCalendarField = Calendar.MINUTE,
                selectedCalendarFieldProvider = { selectedDateTimeEditorCalendarField(spinner) },
                steppingProvider = steppingProvider,
            ),
        ).apply {
            editor = JSpinner.DateEditor(this, pattern)
        }
        return spinner
    }

    private fun configureFrequencySpinner(spinner: JSpinner, frequencyHz: Long? = null) {
        when (displayPreferences.frequencyDisplayUnit) {
            FrequencyDisplayUnit.KHZ -> {
                val displayValue = (frequencyHz?.let {
                    DesktopInputSupport.frequencySpinnerValue(it, FrequencyDisplayUnit.KHZ)
                } ?: DesktopInputSupport.defaultFrequencySpinnerValue(FrequencyDisplayUnit.KHZ)).toInt()
                spinner.model = SpinnerNumberModel(displayValue, 3501, 3700, 1)
                spinner.editor = JSpinner.NumberEditor(spinner, "0' kHz'")
            }
            FrequencyDisplayUnit.MHZ -> {
                val displayValue = (frequencyHz?.let {
                    DesktopInputSupport.frequencySpinnerValue(it, FrequencyDisplayUnit.MHZ)
                } ?: DesktopInputSupport.defaultFrequencySpinnerValue(FrequencyDisplayUnit.MHZ)).toDouble()
                spinner.model = SpinnerNumberModel(displayValue, 3.501, 3.700, 0.001)
                spinner.editor = JSpinner.NumberEditor(spinner, "0.000' MHz'")
            }
        }
        spinnerEditorTextField(spinner).horizontalAlignment = JFormattedTextField.LEFT
        if (frequencyHz == null) {
            spinnerEditorTextField(spinner).text = ""
        }
    }

    private fun setFrequencySpinnerValue(spinner: JSpinner, frequencyHz: Long?) {
        configureFrequencySpinner(spinner, frequencyHz)
    }

    private fun clearFrequencySpinner(spinner: JSpinner) {
        configureFrequencySpinner(spinner, null)
    }

    private fun selectedDateTimeEditorCalendarField(
        spinner: JSpinner,
        defaultCalendarField: Int = Calendar.MINUTE,
    ): Int {
        val forcedField = spinner.getClientProperty(DATE_TIME_FORCED_STEP_FIELD_KEY) as? Int
        if (forcedField != null) {
            return forcedField
        }
        val textField = spinnerEditorTextField(spinner)
        val rememberedField = (spinner.getClientProperty(DATE_TIME_ACTIVE_FIELD_KEY) as? Int) ?: defaultCalendarField
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner === textField) {
            return selectedDateTimeEditorFieldFromText(textField, rememberedField)
        }
        return rememberedField
    }

    private fun stepDateTimeBySelectedField(
        current: LocalDateTime,
        selectedField: Int,
        forward: Boolean,
        minuteStep: Int,
    ): LocalDateTime {
        val truncated = DesktopInputSupport.truncateToMinute(current)
        if (selectedField == Calendar.MINUTE) {
            return DesktopInputSupport.stepDateTimeByMinuteInterval(truncated, minuteStep, forward)
        }

        val calendar = Calendar.getInstance().apply {
            time = Date.from(truncated.atZone(ZoneId.systemDefault()).toInstant())
            add(selectedField, if (forward) 1 else -1)
        }
        return calendar.time.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
            .let(DesktopInputSupport::truncateToMinute)
    }

    private fun validatedDeviceTimeForScheduling(fieldLabel: String): LocalDateTime? {
        val validatedCompact = try {
            DesktopInputSupport.validateCurrentTimeForWrite(displayedDeviceTimeCompact())
        } catch (exception: Exception) {
            JOptionPane.showMessageDialog(this, exception.message ?: "Set Device Time first before changing $fieldLabel.")
            return null
        }
        if (validatedCompact == null) {
            JOptionPane.showMessageDialog(this, "Set Device Time first before changing $fieldLabel.")
            return null
        }
        return DesktopInputSupport.parseCompactTimestamp(validatedCompact)
    }

    private fun stepStartTimeSelection(
        current: LocalDateTime,
        selectedField: Int,
        forward: Boolean,
    ): LocalDateTime? {
        clearPendingDateTimeCompact(startTimeSpinner)
        val deviceTime = validatedDeviceTimeForScheduling("Start Time") ?: return null
        val minimumStart = DesktopInputSupport.minimumStartTimeBoundary(
            DesktopInputSupport.formatCompactTimestamp(deviceTime),
            stepMinutes = 5,
        )
        if (!forward && !current.isAfter(minimumStart)) {
            snapDateTimeSpinnerToMinimum(
                spinner = startTimeSpinner,
                minimum = minimumStart,
                message = "Start Time can't be set earlier than Device Time.",
            )
            return minimumStart
        }

        val stepped = stepDateTimeBySelectedField(current, selectedField, forward, minuteStep = 5)
        if (stepped.isBefore(minimumStart)) {
            return minimumStart
        }
        return stepped
    }

    private fun stepFinishTimeSelection(
        current: LocalDateTime,
        selectedField: Int,
        forward: Boolean,
    ): LocalDateTime? {
        clearPendingDateTimeCompact(finishTimeSpinner)
        val deviceTime = validatedDeviceTimeForScheduling("Finish Time") ?: return null
        val selectedStartTime = selectedStartTimeForFinishConstraint()
        val minimumFinish = listOfNotNull(deviceTime, selectedStartTime).maxOrNull() ?: deviceTime

        if (!forward && !current.isAfter(minimumFinish)) {
            snapDateTimeSpinnerToMinimum(
                spinner = finishTimeSpinner,
                minimum = minimumFinish,
                message = finishTimeLowerBoundMessage(deviceTime, selectedStartTime),
            )
            return minimumFinish
        }

        val stepped = stepDateTimeBySelectedField(current, selectedField, forward, minuteStep = 1)
        if (stepped.isBefore(minimumFinish)) {
            return minimumFinish
        }
        return stepped
    }

    private fun snapDateTimeSpinnerToMinimum(
        spinner: JSpinner,
        minimum: LocalDateTime,
        message: String,
    ) {
        spinner.putClientProperty(
            DATE_TIME_PENDING_COMPACT_KEY,
            DesktopInputSupport.formatCompactTimestamp(minimum),
        )
        spinner.putClientProperty(DATE_TIME_COMMIT_SUPPRESSED_KEY, true)
        SwingUtilities.invokeLater {
            setDateTimeSpinnerValue(spinner, minimum)
            SwingUtilities.invokeLater {
                try {
                    JOptionPane.showMessageDialog(this, message)
                } finally {
                    spinner.putClientProperty(DATE_TIME_COMMIT_SUPPRESSED_KEY, false)
                }
            }
        }
    }

    private fun isDateTimeCommitSuppressed(spinner: JSpinner): Boolean {
        return spinner.getClientProperty(DATE_TIME_COMMIT_SUPPRESSED_KEY) as? Boolean ?: false
    }

    private fun takePendingDateTimeCompact(spinner: JSpinner): String? {
        val pending = pendingDateTimeCompact(spinner)
        clearPendingDateTimeCompact(spinner)
        return pending
    }

    private fun pendingDateTimeCompact(spinner: JSpinner): String? {
        return spinner.getClientProperty(DATE_TIME_PENDING_COMPACT_KEY) as? String
    }

    private fun clearPendingDateTimeCompact(spinner: JSpinner) {
        spinner.putClientProperty(DATE_TIME_PENDING_COMPACT_KEY, null)
    }

    private fun selectedDateTimeCompactForComparison(spinner: JSpinner, originalValue: String?): String? {
        return pendingDateTimeCompact(spinner) ?: selectedDateTimeCompact(spinner, originalValue)
    }

    private fun selectedStartTimeForFinishConstraint(): LocalDateTime? {
        val selectedCompact = runCatching {
            selectedDateTimeCompact(startTimeSpinner, currentConnectedTimedSettings().startTimeCompact)
        }.getOrNull() ?: return null

        val validatedCompact = runCatching {
            DesktopInputSupport.validateStartTimeForWrite(selectedCompact)
        }.getOrNull() ?: return null

        return DesktopInputSupport.parseCompactTimestamp(validatedCompact)
    }

    private fun finishTimeLowerBoundMessage(
        deviceTime: LocalDateTime,
        startTime: LocalDateTime?,
    ): String {
        return if (startTime != null && !startTime.isBefore(deviceTime)) {
            "Finish Time can't be set earlier than Start Time."
        } else {
            "Finish Time can't be set earlier than Device Time."
        }
    }

    private fun configureNullableDateTimeSpinner(spinner: JSpinner, statusLabel: JLabel) {
        spinner.putClientProperty(NULLABLE_TIMESTAMP_ACTIVE_KEY, false)
        spinner.putClientProperty(NULLABLE_TIMESTAMP_STATUS_LABEL_KEY, statusLabel)
        configureDateTimeSpinnerArrowFieldPreservation(spinner)
        configureDateTimeSpinnerKeyboardFieldPreservation(spinner)
        val textField = spinnerEditorTextField(spinner)
        spinner.addChangeListener {
            if (!updatingForm && isNullableDateTimeSpinnerActive(spinner)) {
                setNullableDateTimeSpinnerActive(spinner, false)
            }
        }
        textField.addFocusListener(
            object : FocusAdapter() {
                override fun focusGained(event: FocusEvent?) {
                    if (updatingForm) {
                        return
                    }
                    SwingUtilities.invokeLater {
                        if (updatingForm || !textField.hasFocus()) {
                            return@invokeLater
                        }
                        val explicitField = explicitlySelectedDateTimeEditorFieldFromText(textField)
                        if (explicitField != null) {
                            spinner.putClientProperty(DATE_TIME_ACTIVE_FIELD_KEY, explicitField)
                        } else {
                            positionDateTimeEditorCaret(spinner)
                        }
                    }
                }
            },
        )
        textField.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent?) {
                    if (setSelectedDateTimeEditorFieldFromPoint(spinner, event?.point)) {
                        event?.consume()
                    }
                }

                override fun mouseReleased(event: MouseEvent?) {
                    val selectedField = dateTimeEditorFieldFromPoint(spinner, event?.point)
                    if (selectedField != null) {
                        event?.consume()
                        SwingUtilities.invokeLater {
                            if (!updatingForm && textField.hasFocus()) {
                                setSelectedDateTimeEditorField(spinner, selectedField)
                            }
                        }
                    } else {
                        scheduleRememberSelectedDateTimeEditorField(spinner)
                    }
                }
            },
        )
        textField.addKeyListener(
            object : java.awt.event.KeyAdapter() {
                override fun keyReleased(event: java.awt.event.KeyEvent?) {
                    scheduleRememberSelectedDateTimeEditorField(spinner)
                }
            },
        )
        textField.addCaretListener {
            if (!updatingForm) {
                scheduleRememberSelectedDateTimeEditorField(spinner)
            }
        }
        textField.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(event: javax.swing.event.DocumentEvent?) = onEdited()
                override fun removeUpdate(event: javax.swing.event.DocumentEvent?) = onEdited()
                override fun changedUpdate(event: javax.swing.event.DocumentEvent?) = onEdited()

                private fun onEdited() {
                    if (updatingForm) {
                        return
                    }
                    if (isNullableDateTimeSpinnerActive(spinner) && textField.text != "Not Set") {
                        SwingUtilities.invokeLater {
                            if (!updatingForm && isNullableDateTimeSpinnerActive(spinner) && textField.text != "Not Set") {
                                setNullableDateTimeSpinnerActive(spinner, false)
                            }
                        }
                    }
                }
            },
        )
        positionDateTimeEditorCaret(spinner)
    }

    private fun configureDateTimeSpinnerArrowFieldPreservation(spinner: JSpinner) {
        installDateTimeSpinnerArrowFieldPreservation(spinner)
        spinner.addPropertyChangeListener("UI") {
            installDateTimeSpinnerArrowFieldPreservation(spinner)
        }
        spinner.addPropertyChangeListener("editor") {
            installDateTimeSpinnerArrowFieldPreservation(spinner)
        }
    }

    private fun installDateTimeSpinnerArrowFieldPreservation(spinner: JSpinner) {
        fun visit(component: Component) {
            if (component is AbstractButton) {
                if (component.getClientProperty(DATE_TIME_ARROW_FIELD_PRESERVER_KEY) != true) {
                    component.addMouseListener(
                        object : MouseAdapter() {
                            override fun mousePressed(event: MouseEvent?) {
                                if (updatingForm) {
                                    return
                                }
                                val selectedField =
                                    spinner.getClientProperty(DATE_TIME_ACTIVE_FIELD_KEY) as? Int ?: Calendar.MINUTE
                                setSelectedDateTimeEditorField(spinner, selectedField)
                            }
                        },
                    )
                    component.putClientProperty(DATE_TIME_ARROW_FIELD_PRESERVER_KEY, true)
                }
            }
            if (component is Container) {
                component.components.forEach(::visit)
            }
        }

        spinner.components.forEach(::visit)
    }

    private fun configureDateTimeSpinnerKeyboardFieldPreservation(spinner: JSpinner) {
        installDateTimeSpinnerKeyboardFieldPreservation(spinner)
        spinner.addPropertyChangeListener("editor") {
            installDateTimeSpinnerKeyboardFieldPreservation(spinner)
        }
    }

    private fun installDateTimeSpinnerKeyboardFieldPreservation(spinner: JSpinner) {
        val textField = spinnerEditorTextField(spinner)
        if (textField.getClientProperty(DATE_TIME_KEYBOARD_STEP_HANDLER_INSTALLED_KEY) == true) {
            return
        }

        fun registerStep(keyStroke: String, actionKey: String, forward: Boolean) {
            textField.inputMap.put(KeyStroke.getKeyStroke(keyStroke), actionKey)
            spinner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(keyStroke), actionKey)
            textField.actionMap.put(
                actionKey,
                object : AbstractAction() {
                    override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                        stepDateTimeSpinnerFromKeyboard(spinner, forward)
                    }
                },
            )
        }

        fun registerMove(keyStroke: String, actionKey: String, forward: Boolean) {
            textField.inputMap.put(KeyStroke.getKeyStroke(keyStroke), actionKey)
            spinner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(keyStroke), actionKey)
            textField.actionMap.put(
                actionKey,
                object : AbstractAction() {
                    override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                        moveDateTimeSpinnerSelectionFromKeyboard(spinner, forward)
                    }
                },
            )
        }

        registerStep("UP", DATE_TIME_INCREMENT_ACTION_KEY, true)
        registerStep("KP_UP", DATE_TIME_INCREMENT_ACTION_KEY, true)
        registerStep("DOWN", DATE_TIME_DECREMENT_ACTION_KEY, false)
        registerStep("KP_DOWN", DATE_TIME_DECREMENT_ACTION_KEY, false)
        registerMove("LEFT", DATE_TIME_PREVIOUS_FIELD_ACTION_KEY, false)
        registerMove("KP_LEFT", DATE_TIME_PREVIOUS_FIELD_ACTION_KEY, false)
        registerMove("RIGHT", DATE_TIME_NEXT_FIELD_ACTION_KEY, true)
        registerMove("KP_RIGHT", DATE_TIME_NEXT_FIELD_ACTION_KEY, true)
        textField.putClientProperty(DATE_TIME_KEYBOARD_STEP_HANDLER_INSTALLED_KEY, true)
    }

    private fun stepDateTimeSpinnerFromKeyboard(spinner: JSpinner, forward: Boolean) {
        if (updatingForm || isDateTimeCommitSuppressed(spinner) || shouldSuppressScheduleCommit(spinner)) {
            return
        }

        val textField = spinnerEditorTextField(spinner)
        val currentField = spinner.getClientProperty(DATE_TIME_ACTIVE_FIELD_KEY) as? Int ?: Calendar.MINUTE
        val selectedField = selectedDateTimeEditorFieldFromText(textField, currentField)
        setSelectedDateTimeEditorField(spinner, selectedField)
        val steppedValue = withForcedDateTimeStepField(spinner, selectedField) {
            try {
                spinner.commitEdit()
            } catch (_: Exception) {
                // Let the formatter keep its existing validation behavior.
            }

            (spinner.model as? SpinnerDateModel)?.calendarField = selectedField
            if (forward) {
                spinner.nextValue
            } else {
                spinner.previousValue
            }
        } ?: return
        spinner.value = steppedValue
        SwingUtilities.invokeLater {
            if (!updatingForm && textField.hasFocus()) {
                setSelectedDateTimeEditorField(spinner, selectedField)
            }
        }
    }

    private fun <T> withForcedDateTimeStepField(spinner: JSpinner, calendarField: Int, block: () -> T): T {
        spinner.putClientProperty(DATE_TIME_FORCED_STEP_FIELD_KEY, calendarField)
        return try {
            block()
        } finally {
            spinner.putClientProperty(DATE_TIME_FORCED_STEP_FIELD_KEY, null)
        }
    }

    private fun moveDateTimeSpinnerSelectionFromKeyboard(spinner: JSpinner, forward: Boolean) {
        if (updatingForm) {
            return
        }
        val textField = spinnerEditorTextField(spinner)
        val currentField = spinner.getClientProperty(DATE_TIME_ACTIVE_FIELD_KEY) as? Int ?: Calendar.MINUTE
        val selectedField = selectedDateTimeEditorFieldFromText(textField, currentField)
        val targetField = adjacentDateTimeEditorField(spinner, selectedField, forward) ?: return
        setSelectedDateTimeEditorField(spinner, targetField)
    }

    private fun setDateTimeEditorValue(spinner: JSpinner, statusLabel: JLabel, compactTimestamp: String?) {
        if (compactTimestamp == null) {
            spinner.value = suggestedDateTimeEditorValue()
            setNullableDateTimeSpinnerActive(spinner, true)
            statusLabel.text = "Not Set"
            positionDateTimeEditorCaret(spinner)
            return
        }

        val localDateTime = DesktopInputSupport.parseCompactTimestamp(compactTimestamp)
        spinner.putClientProperty(NULLABLE_TIMESTAMP_ACTIVE_KEY, false)
        statusLabel.text = " "
        spinner.value = Date.from(
            DesktopInputSupport.truncateToMinute(localDateTime).atZone(ZoneId.systemDefault()).toInstant(),
        )
        updateDateTimeEditorPresentation(spinner, showNotSet = false)
        positionDateTimeEditorCaret(spinner)
    }

    private fun selectedDateTimeCompact(spinner: JSpinner, originalValue: String?): String? {
        if (isNullableDateTimeSpinnerActive(spinner)) {
            return originalValue
        }

        spinner.commitEdit()
        val selectedDate = spinner.value as? Date
            ?: error("Date/time selection is not available.")
        val localDateTime = DesktopInputSupport.truncateToMinute(
            selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
        )
        return DesktopInputSupport.formatCompactTimestamp(localDateTime)
    }

    private fun selectedSpinnerDateTime(spinner: JSpinner): java.time.LocalDateTime {
        spinner.commitEdit()
        val selectedDate = spinner.value as? Date
            ?: error("Date/time selection is not available.")
        return DesktopInputSupport.truncateToSecond(
            selectedDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
        )
    }

    private fun setDateTimeSpinnerValue(spinner: JSpinner, localDateTime: java.time.LocalDateTime) {
        spinner.value = Date.from(
            DesktopInputSupport.truncateToSecond(localDateTime).atZone(ZoneId.systemDefault()).toInstant(),
        )
        updateDateTimeEditorPresentation(spinner, showNotSet = false)
    }

    private fun updateDateTimeEditorPresentation(spinner: JSpinner, showNotSet: Boolean) {
        val textField = spinnerEditorTextField(spinner)
        textField.isEditable = true
        if (showNotSet) {
            textField.text = "Not Set"
        } else {
            textField.value = spinner.value
        }
        positionDateTimeEditorCaret(spinner)
    }

    private fun positionDateTimeEditorCaret(spinner: JSpinner) {
        SwingUtilities.invokeLater {
            val textField = spinnerEditorTextField(spinner)
            val selectedField = spinner.getClientProperty(DATE_TIME_ACTIVE_FIELD_KEY) as? Int ?: Calendar.MINUTE
            val fieldRange = dateTimeEditorSelectionRangeForField(textField.text, selectedField)
            val clampedStart = fieldRange.first.coerceIn(0, textField.text.length)
            val clampedEnd = fieldRange.second.coerceIn(clampedStart, textField.text.length)
            spinner.putClientProperty(DATE_TIME_ACTIVE_FIELD_KEY, selectedField)
            textField.select(clampedStart, clampedEnd)
        }
    }

    private fun rememberSelectedDateTimeEditorField(spinner: JSpinner) {
        val currentField = spinner.getClientProperty(DATE_TIME_ACTIVE_FIELD_KEY) as? Int ?: Calendar.MINUTE
        spinner.putClientProperty(
            DATE_TIME_ACTIVE_FIELD_KEY,
            selectedDateTimeEditorFieldFromText(spinnerEditorTextField(spinner), currentField),
        )
    }

    private fun scheduleRememberSelectedDateTimeEditorField(spinner: JSpinner) {
        SwingUtilities.invokeLater {
            if (!updatingForm) {
                rememberSelectedDateTimeEditorField(spinner)
            }
        }
    }

    private fun dateTimeEditorFieldFromPoint(spinner: JSpinner, point: Point?): Int? {
        if (point == null) {
            return null
        }
        val textField = spinnerEditorTextField(spinner)
        val offset = textField.viewToModel2D(point)
        return DesktopDateTimeEditorSupport.fieldFromOffset(offset, null)
    }

    private fun setSelectedDateTimeEditorFieldFromPoint(spinner: JSpinner, point: Point?): Boolean {
        if (updatingForm) {
            return false
        }
        val selectedField = dateTimeEditorFieldFromPoint(spinner, point) ?: return false
        setSelectedDateTimeEditorField(spinner, selectedField)
        return true
    }

    private fun setSelectedDateTimeEditorField(spinner: JSpinner, calendarField: Int) {
        val textField = spinnerEditorTextField(spinner)
        spinner.putClientProperty(DATE_TIME_ACTIVE_FIELD_KEY, calendarField)
        val fieldRange = DesktopDateTimeEditorSupport.selectionRangeForField(textField.text, calendarField)
        val clampedStart = fieldRange.first.coerceIn(0, textField.text.length)
        val clampedEnd = fieldRange.second.coerceIn(clampedStart, textField.text.length)
        textField.select(clampedStart, clampedEnd)
    }

    private fun adjacentDateTimeEditorField(spinner: JSpinner, currentField: Int, forward: Boolean): Int? {
        return DesktopDateTimeEditorSupport.adjacentField(
            text = spinnerEditorTextField(spinner).text,
            currentField = currentField,
            forward = forward,
        )
    }

    private fun dateTimeEditorSelectionRangeForField(text: String, calendarField: Int): Pair<Int, Int> {
        return DesktopDateTimeEditorSupport.selectionRangeForField(text, calendarField)
    }

    private fun selectedDateTimeEditorFieldFromText(
        textField: JFormattedTextField,
        fallbackField: Int = Calendar.MINUTE,
    ): Int {
        return DesktopDateTimeEditorSupport.fieldFromSelection(
            text = textField.text,
            selectionStart = textField.selectionStart,
            selectionEnd = textField.selectionEnd,
            caretPosition = textField.caretPosition,
            fallbackField = fallbackField,
        )
    }

    private fun dateTimeEditorFieldFromOffset(offset: Int, fallbackField: Int? = Calendar.MINUTE): Int {
        return DesktopDateTimeEditorSupport.fieldFromOffset(offset, fallbackField)
    }

    private fun explicitlySelectedDateTimeEditorFieldFromText(textField: JFormattedTextField): Int? {
        return DesktopDateTimeEditorSupport.explicitFieldFromSelection(
            text = textField.text,
            selectionStart = textField.selectionStart,
            selectionEnd = textField.selectionEnd,
        )
    }

    private fun spinnerEditorTextField(spinner: JSpinner): JFormattedTextField {
        val editor = spinner.editor as? JSpinner.DefaultEditor
            ?: error("Spinner editor is unavailable.")
        return editor.textField
    }

    private fun applyDateTimeEditorCapability(spinner: JSpinner, supported: Boolean) {
        val textField = spinnerEditorTextField(spinner)
        spinner.isEnabled = supported
        textField.isEditable = supported
    }

    private fun updateDeviceClockOffset(
        currentTimeCompact: String?,
        referenceTime: java.time.LocalDateTime = java.time.LocalDateTime.now(),
    ) {
        val deviceTime = currentTimeCompact?.let(DesktopInputSupport::parseCompactTimestamp)
        deviceTimeOffset = deviceTime?.let { java.time.Duration.between(referenceTime, it) }
    }

    private fun applyClockDisplayAnchor(anchor: ClockDisplayAnchor) {
        deviceTimeOffset = anchor.phaseErrorMillis?.let { Duration.ofMillis(-it) }
        if (deviceTimeOffset == null) {
            updateDeviceClockOffset(
                currentTimeCompact = anchor.currentTimeCompact,
                referenceTime = anchor.referenceTime ?: java.time.LocalDateTime.now(),
            )
        }
    }

    private fun sampleClockReadLatency(
        transport: DesktopSerialTransport,
        sampleCount: Int,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): List<ClockReadSample> {
        onProgress?.invoke(0, sampleCount)
        return buildList {
            repeat(sampleCount) { index ->
                add(readClockSample(transport))
                onProgress?.invoke(index + 1, sampleCount)
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
        return (DesktopInputSupport.medianMillis(samples.map { it.roundTripMillis }) / 2).coerceAtLeast(0L)
    }

    private fun performSyncAttempt(
        transport: DesktopSerialTransport,
        state: DeviceSessionState,
        snapshot: DeviceSnapshot,
        targetTime: java.time.LocalDateTime,
        dispatchTime: java.time.LocalDateTime,
        estimatedOneWayDelayMillis: Long,
        onSubmitProgress: ((completed: Int, total: Int) -> Unit)? = null,
        onVerificationProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): SyncAttempt {
        waitForSyncTargetDispatch(dispatchTime, estimatedOneWayDelayMillis)
        val editable = EditableDeviceSettings.fromDeviceSettings(snapshot.settings).copy(
            currentTimeCompact = SettingsField(
                "currentTimeCompact",
                "Current Time",
                snapshot.settings.currentTimeCompact,
                DesktopInputSupport.formatCompactTimestamp(targetTime),
            ),
        )
        val submitResult = DeviceSessionController.submitEdits(
            state,
            editable,
            transport,
            progress = onSubmitProgress,
        )
        var nextState = submitResult.state
        val verificationSamples = observeClockPhaseSamples(
            transport,
            maxSamples = SYNC_VERIFICATION_SAMPLE_MAX,
            onProgress = onVerificationProgress,
        )
        verificationSamples.forEach { sample ->
            nextState = DeviceSessionWorkflow.ingestReportLines(nextState, sample.responseLines)
        }
        val phaseErrorMillis = DesktopInputSupport.estimateClockPhaseErrorMillis(
            verificationSamples.map { sample ->
                DesktopInputSupport.ClockPhaseSample(
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
        transport: DesktopSerialTransport,
        maxSamples: Int = 8,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): List<ClockReadSample> {
        val samples = mutableListOf<ClockReadSample>()
        onProgress?.invoke(0, maxSamples)
        repeat(maxSamples) { index ->
            val sample = readClockSample(transport)
            samples += sample
            onProgress?.invoke(samples.size, maxSamples)
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
        transport: DesktopSerialTransport,
        command: String = "CLK T",
    ): ClockReadSample {
        val sentAt = java.time.LocalDateTime.now()
        transport.sendCommands(listOf(command))
        val responseLines = transport.readAvailableLines()
        val receivedAt = java.time.LocalDateTime.now()
        return ClockReadSample(
            sentAt = sentAt,
            receivedAt = receivedAt,
            responseLines = responseLines,
            reportedTimeCompact = responseLines
                .mapNotNull { line -> SignalSlingerProtocolCodec.parseReportLine(line)?.settingsPatch?.currentTimeCompact }
                .firstOrNull(),
            command = command,
        )
    }

    private fun estimatedSyncProgressUnits(): Int {
        return SYNC_LATENCY_SAMPLE_COUNT + (SYNC_MAX_ATTEMPTS * SYNC_ATTEMPT_PROGRESS_UNITS)
    }

    private fun chooseBetterSyncAttempt(
        currentBest: SyncAttempt?,
        candidate: SyncAttempt,
    ): SyncAttempt {
        if (currentBest == null) {
            return candidate
        }

        val currentScore = kotlin.math.abs(currentBest.phaseErrorMillis ?: Long.MAX_VALUE / 4)
        val candidateScore = kotlin.math.abs(candidate.phaseErrorMillis ?: Long.MAX_VALUE / 4)
        return if (candidateScore <= currentScore) candidate else currentBest
    }

    private fun waitForSyncTargetDispatch(
        targetTime: java.time.LocalDateTime,
        estimatedOneWayDelayMillis: Long,
    ) {
        val dispatchMoment = targetTime.minus(java.time.Duration.ofMillis(estimatedOneWayDelayMillis))
        while (true) {
            val remaining = Duration.between(java.time.LocalDateTime.now(), dispatchMoment).toMillis()
            if (remaining <= 0) {
                return
            }
            Thread.sleep(minOf(remaining, 5L))
        }
    }

    private fun suggestedDateTimeEditorValue(): Date {
        val referenceCompact = loadedSnapshot?.settings?.currentTimeCompact ?: DesktopInputSupport.currentSystemTimeCompact()
        val referenceDateTime = DesktopInputSupport.parseCompactTimestamp(referenceCompact)
        return Date.from(referenceDateTime.atZone(ZoneId.systemDefault()).toInstant())
    }

    private fun setNullableDateTimeSpinnerActive(spinner: JSpinner, active: Boolean) {
        spinner.putClientProperty(NULLABLE_TIMESTAMP_ACTIVE_KEY, active)
        val statusLabel = spinner.getClientProperty(NULLABLE_TIMESTAMP_STATUS_LABEL_KEY) as? JLabel
        if (statusLabel != null) {
            statusLabel.text = if (active) "Not Set" else " "
        }
        updateDateTimeEditorPresentation(spinner, showNotSet = active)
    }

    private fun isNullableDateTimeSpinnerActive(spinner: JSpinner): Boolean {
        return spinner.getClientProperty(NULLABLE_TIMESTAMP_ACTIVE_KEY) as? Boolean ?: false
    }

    private fun configureLogAutoScroll() {
        logScroll.verticalScrollBar.model.addChangeListener {
            if (suppressLogAutoScrollTracking) {
                return@addChangeListener
            }
            if (!logVisible) {
                return@addChangeListener
            }
            logAutoScroll = isAtBottom(logScroll.verticalScrollBar.model)
        }
    }

    private fun isAtBottom(model: BoundedRangeModel): Boolean {
        return model.value + model.extent >= model.maximum - 12
    }

    private fun scrollLogToBottom() {
        suppressLogAutoScrollTracking = true
        try {
            val verticalScrollBar = logScroll.verticalScrollBar
            verticalScrollBar.value = verticalScrollBar.maximum
            logPane.caretPosition = logPane.document.length
            logAutoScroll = true
        } finally {
            suppressLogAutoScrollTracking = false
        }
    }

    private fun styleForRenderedLogLine(line: String): SimpleAttributeSet {
        return when {
            line.isBlank() -> neutralLogStyle
            line.contains("==") -> headerLogStyle
            line.contains("[${DesktopLogCategory.SERIAL.label}]") -> serialLogStyle
            line.contains("[${DesktopLogCategory.DEVICE.label}]") -> deviceLogStyle
            line.contains("[${DesktopLogCategory.APP.label}]") -> appLogStyle
            else -> neutralLogStyle
        }
    }

    private data class PendingImmediateEdit(
        val description: String,
        val source: Any,
        val onCommit: () -> Unit,
    )

    private data class BusyProgressState(
        val completed: Int,
        val total: Int,
        val label: String?,
    )

    private data class LoadedConnection(
        val portPath: String,
        val transport: DesktopSerialTransport,
        val result: DeviceLoadResult,
        val previousTransport: DesktopSerialTransport?,
        val previousState: DeviceSessionState?,
        val loadLogTitle: String,
        val loadLogLeadEntries: List<DesktopLogEntry>,
        val clockAnchor: ClockDisplayAnchor?,
    )

    private data class PreferredProbeAttempt(
        val probeResult: SignalSlingerPortProbe?,
        val loadResult: LoadedConnection?,
        val attemptCount: Int,
        val probedPath: String?,
    )

    private data class ClockDisplayAnchor(
        val currentTimeCompact: String?,
        val referenceTime: java.time.LocalDateTime?,
        val phaseErrorMillis: Long?,
    )

    private data class ClockReadSample(
        val sentAt: java.time.LocalDateTime,
        val receivedAt: java.time.LocalDateTime,
        val responseLines: List<String>,
        val reportedTimeCompact: String?,
        val command: String,
    ) {
        val sentAtMs: Long
            get() = sentAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val receivedAtMs: Long
            get() = receivedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val roundTripMillis: Long
            get() = Duration.between(sentAt, receivedAt).toMillis()

        val midpointAt: java.time.LocalDateTime
            get() = sentAt.plus(Duration.ofMillis(roundTripMillis / 2))
    }

    private data class SyncAttempt(
        val targetTime: java.time.LocalDateTime,
        val dispatchTime: java.time.LocalDateTime,
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

    private data class ManualTimeSetResult(
        val latencySamples: List<ClockReadSample>,
        val attempt: SyncAttempt,
        val appliedDeviceTime: java.time.LocalDateTime,
        val deviationMillis: Long?,
        val succeeded: Boolean,
    )

    private enum class ConnectionIndicatorState(
        val label: String,
        val foreground: Color,
        val background: Color,
    ) {
        CONNECTED("GREEN", Color(0x0F, 0x51, 0x2A), Color(0xD1, 0xFA, 0xE5)),
        SEARCHING("YELLOW", Color(0x85, 0x4D, 0x0E), Color(0xFE, 0xF3, 0xC7)),
        DISCONNECTED("RED", Color(0x7F, 0x1D, 0x1D), Color(0xFE, 0xF2, 0xF2)),
    }

    private companion object {
        const val BUSY_DIALOG_DELAY_MS = 200
        const val SYNC_LATENCY_SAMPLE_COUNT = 3
        const val SYNC_MAX_ATTEMPTS = 2
        const val SYNC_SUBMIT_PROGRESS_UNITS = 3
        const val SYNC_VERIFICATION_SAMPLE_MAX = 8
        const val SYNC_ATTEMPT_PROGRESS_UNITS = SYNC_SUBMIT_PROGRESS_UNITS + SYNC_VERIFICATION_SAMPLE_MAX
        const val SPINNER_COMMIT_HANDLER_INSTALLED_KEY = "serialslinger.spinnerCommitHandlerInstalled"
        const val SPINNER_EDITOR_COMMIT_ACTION_KEY = "serialslinger.spinnerEditorCommitAction"
        const val DATE_TIME_ARROW_FIELD_PRESERVER_KEY = "serialslinger.dateTimeArrowFieldPreserverInstalled"
        const val DATE_TIME_KEYBOARD_STEP_HANDLER_INSTALLED_KEY = "serialslinger.dateTimeKeyboardStepInstalled"
        const val DATE_TIME_INCREMENT_ACTION_KEY = "serialslinger.dateTimeIncrementAction"
        const val DATE_TIME_DECREMENT_ACTION_KEY = "serialslinger.dateTimeDecrementAction"
        const val DATE_TIME_NEXT_FIELD_ACTION_KEY = "serialslinger.dateTimeNextFieldAction"
        const val DATE_TIME_PREVIOUS_FIELD_ACTION_KEY = "serialslinger.dateTimePreviousFieldAction"
        const val DATE_TIME_FORCED_STEP_FIELD_KEY = "serialslinger.dateTimeForcedStepField"
        const val DATE_TIME_ACTIVE_FIELD_KEY = "serialslinger.dateTimeActiveField"
        const val DATE_TIME_PENDING_COMPACT_KEY = "serialslinger.dateTimePendingCompact"
        const val DATE_TIME_COMMIT_SUPPRESSED_KEY = "serialslinger.dateTimeCommitSuppressed"
        const val NULLABLE_TIMESTAMP_ACTIVE_KEY = "serialslinger.nullableTimestampActive"
        const val NULLABLE_TIMESTAMP_STATUS_LABEL_KEY = "serialslinger.nullableTimestampStatusLabel"
        const val SETTING_LONG_PRESS_HANDLER_INSTALLED_KEY = "serialslinger.settingLongPressHandlerInstalled"
        const val SETTING_LONG_PRESS_TRIGGERED_KEY = "serialslinger.settingLongPressTriggered"
        const val AUTO_DETECT_BUTTON_LONG_PRESS_MS = 900
        const val CLONE_BUTTON_LONG_PRESS_MS = 900
        const val SETTING_TOGGLE_LONG_PRESS_MS = 900
        const val SETTING_TOGGLE_FEEDBACK_MS = 220
        const val SETTING_TOGGLE_ANIMATION_MS = 260
        const val SCHEDULE_MODE_TOGGLE_SUPPRESSION_MS = 750L
        const val PERIODIC_DEVICE_TIME_CHECK_INTERVAL_MS = 1_800_000L
    }
}
