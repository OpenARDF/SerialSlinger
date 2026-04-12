package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.ConnectionState
import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.DeviceSnapshot
import com.openardf.serialslinger.model.EditableDeviceSettings
import com.openardf.serialslinger.model.EventType
import com.openardf.serialslinger.model.ExternalBatteryControlMode
import com.openardf.serialslinger.model.FoxRole
import com.openardf.serialslinger.model.FrequencySupport
import com.openardf.serialslinger.model.SettingKey
import com.openardf.serialslinger.model.SettingsField
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
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.Duration
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import javax.swing.BoundedRangeModel
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.JFormattedTextField
import javax.swing.JTextField
import javax.swing.JTextPane
import javax.swing.Box
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.WindowConstants
import javax.swing.SpinnerDateModel
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.JList

fun main() {
    SwingUtilities.invokeLater {
        SerialSlingerDesktopFrame().isVisible = true
    }
}

private object SerialSlingerAppVersion {
    const val value = "0.1.50"
}

private class SerialSlingerDesktopFrame : JFrame("SerialSlinger ${SerialSlingerAppVersion.value}") {
    private val portModel = DefaultComboBoxModel<SignalSlingerPortProbe>()
    private val portComboBox = JComboBox(portModel)
    private val autoDetectButton = JButton("Auto Detect")
    private val submitButton = JButton("Clone")
    private val cloneTemplateLabel = JLabel("Clone template not set")
    private val toggleLogButton = JButton("Show Log")
    private val openLogFolderButton = JButton("Open Log Folder")
    private val headlineLabel = JLabel(" ")
    private val statusLabel = JLabel("Idle")
    private val rawCommandField = JTextField()
    private val logPane = JTextPane()
    private val formScroll by lazy { JScrollPane(buildFormPanel()) }
    private val logScroll by lazy {
        JScrollPane(logPane).apply {
            border = BorderFactory.createTitledBorder("Session Log")
        }
    }
    private val contentSplitPane by lazy { JSplitPane(JSplitPane.HORIZONTAL_SPLIT, formScroll, JPanel()) }

    private val stationIdField = JTextField()
    private val eventTypeCombo =
        JComboBox(DefaultComboBoxModel(DesktopInputSupport.selectableEventTypes().toTypedArray()))
    private val foxRoleCombo = JComboBox<FoxRole>()
    private val patternTextField = JTextField()
    private val idSpeedField = JTextField()
    private val patternSpeedField = JTextField()
    private val currentTimeField = JTextField()
    private val systemTimeField = JTextField()
    private val startTimeSpinner = createDateTimeSpinner()
    private val startTimeStatusLabel = JLabel(" ")
    private val finishTimeSpinner = createDateTimeSpinner()
    private val finishTimeStatusLabel = JLabel(" ")
    private val daysField = JTextField()
    private val startsInField = JTextField()
    private val lastsField = JTextField()
    private val eventEnabledField = JTextField()
    private val disableEventButton = JButton("Disable Event")
    private val currentFrequencyField = JTextField()
    private val currentBankField = JTextField()
    private val frequency1Field = JTextField()
    private val frequency2Field = JTextField()
    private val frequency3Field = JTextField()
    private val frequencyBField = JTextField()
    private val frequency1Label = JLabel("Frequency 1")
    private val frequency2Label = JLabel("Frequency 2")
    private val frequency3Label = JLabel("Frequency 3")
    private val frequencyBLabel = JLabel("Frequency B")
    private val batteryThresholdField = JTextField()
    private val batteryModeCombo = JComboBox(DefaultComboBoxModel(ExternalBatteryControlMode.entries.toTypedArray()))
    private val transmissionsField = JTextField()
    private val versionInfoField = JTextField()
    private val internalBatteryField = JTextField()
    private val externalBatteryField = JTextField()
    private val temperatureField = JTextField()
    private val syncTimeButton = JButton("Sync")

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
    private var autoDetectButtonLongPressTimer: Timer? = null
    private var suppressNextAutoDetectAction: Boolean = false
    private var cloneButtonLongPressTimer: Timer? = null
    private var suppressNextCloneAction: Boolean = false
    private val knownProbeResults = linkedMapOf<String, SignalSlingerPortProbe>()
    private val portMemory: DesktopPortMemory = PreferencesDesktopPortMemory
    private val sessionLog = DesktopSessionLog()
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

    init {
        defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        minimumSize = Dimension(1200, 780)
        layout = BorderLayout(12, 12)

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
        eventEnabledField.isEditable = false
        versionInfoField.isEditable = false
        internalBatteryField.isEditable = false
        externalBatteryField.isEditable = false
        temperatureField.isEditable = false
        transmissionsField.isEditable = false
        configureNullableDateTimeSpinner(startTimeSpinner, startTimeStatusLabel)
        configureNullableDateTimeSpinner(finishTimeSpinner, finishTimeStatusLabel)
        installTrimmedComboRenderer(eventTypeCombo)
        installTrimmedComboRenderer(foxRoleCombo)
        installTrimmedComboRenderer(batteryModeCombo)
        configureLogAutoScroll()
        showConnectionIndicator(ConnectionIndicatorState.DISCONNECTED, "Not Connected")
        appendRenderedLog(sessionLog.loadCurrentLogText())
        setLogVisible(false)

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
        syncTimeButton.addActionListener { syncDeviceTimeToSystem() }
        disableEventButton.addActionListener { disableProgrammedEvent() }
        toggleLogButton.addActionListener { setLogVisible(!logVisible) }
        openLogFolderButton.addActionListener { openLogFolder() }
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
            add(Box.createHorizontalStrut(16))
            add(toggleLogButton)
            add(Box.createHorizontalStrut(8))
            add(openLogFolderButton)
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
            add(
                JPanel(BorderLayout(8, 0)).apply {
                    add(JLabel("Raw Serial"), BorderLayout.WEST)
                    add(rawCommandField, BorderLayout.CENTER)
                },
                BorderLayout.NORTH,
            )
            add(statusLabel, BorderLayout.SOUTH)
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
            add(buildSectionPanel("Device Settings") { section ->
                var row = 0
                row = addRow(section, row, "Fox Role", foxRoleCombo)
                row = addRow(section, row, "Pattern Text", patternTextField)
                row = addRow(section, row, "Pattern Speed (WPM)", patternSpeedField)
                row = addRow(section, row, "Current Time", buildCurrentTimeRow())
                row = addRow(section, row, "System Time", systemTimeField)
                row = addRow(section, row, "Current Frequency", currentFrequencyField)
                row = addRow(section, row, "Current Bank", currentBankField)
                row = addRow(section, row, "Battery Mode", batteryModeCombo)
                row = addRow(section, row, "Transmissions", transmissionsField)
                addRow(section, row, "Low Battery Threshold", batteryThresholdField)
            })
            add(Box.createVerticalStrut(12))
            add(buildSectionPanel("Timed Event Settings") { section ->
                var row = 0
                row = addRow(section, row, "Event Type", eventTypeCombo)
                row = addRow(section, row, "Station ID", stationIdField)
                row = addRow(section, row, "ID Speed (WPM)", idSpeedField)
                row = addRow(section, row, "Start Time", buildDateTimeEditorRow(startTimeSpinner, startTimeStatusLabel))
                row = addRow(section, row, "Finish Time", buildDateTimeEditorRow(finishTimeSpinner, finishTimeStatusLabel))
                row = addRow(section, row, "Event Status", buildEventStatusRow())
                row = addRow(section, row, "Lasts", lastsField)
                row = addRow(section, row, "Days To Run", daysField)
                row = addRow(section, row, frequency1Label, frequency1Field)
                row = addRow(section, row, frequency2Label, frequency2Field)
                row = addRow(section, row, frequency3Label, frequency3Field)
                row = addRow(section, row, frequencyBLabel, frequencyBField)
                addRow(section, row, "Event Enabled", eventEnabledField)
            })
            add(Box.createVerticalStrut(12))
            add(buildSectionPanel("Device Data") { section ->
                var row = 0
                row = addRow(section, row, "Internal Battery", internalBatteryField)
                row = addRow(section, row, "External Battery", externalBatteryField)
                row = addRow(section, row, "Device Temperature", temperatureField)
                addRow(section, row, "Version", versionInfoField)
            })
            add(Box.createVerticalGlue())
        }
    }

    private fun buildSectionPanel(title: String, buildRows: (JPanel) -> Unit): JPanel {
        return JPanel(GridBagLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createTitledBorder(title)
            buildRows(this)
            preferredSize = preferredSize
            minimumSize = preferredSize
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun buildCurrentTimeRow(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(currentTimeField)
            add(Box.createHorizontalStrut(8))
            add(syncTimeButton)
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

    private fun buildDateTimeEditorRow(spinner: JSpinner, statusLabel: JLabel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(spinner)
            add(Box.createHorizontalStrut(8))
            add(statusLabel)
        }
    }

    private fun installImmediateApplyHandlers() {
        installTextCommitHandler(stationIdField) { applyStationIdChange() }
        installTextCommitHandler(patternTextField) { applyPatternTextChange() }
        installTextCommitHandler(idSpeedField) { applyIdSpeedChange() }
        installTextCommitHandler(patternSpeedField) { applyPatternSpeedChange() }
        installTextCommitHandler(daysField) { applyDaysToRunChange() }
        installTextCommitHandler(frequency1Field) { applyFrequency1Change() }
        installTextCommitHandler(frequency2Field) { applyFrequency2Change() }
        installTextCommitHandler(frequency3Field) { applyFrequency3Change() }
        installTextCommitHandler(frequencyBField) { applyFrequencyBChange() }
        installTextCommitHandler(batteryThresholdField) { applyBatteryThresholdChange() }

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
        installDateTimeCommitHandler(startTimeSpinner) { applyStartTimeChange() }
        installDateTimeCommitHandler(finishTimeSpinner) { applyFinishTimeChange() }
    }

    private fun installTextCommitHandler(field: JTextField, onCommit: () -> Unit) {
        field.addActionListener {
            if (!updatingForm) {
                onCommit()
            }
        }
        field.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(event: FocusEvent?) {
                    if (!updatingForm) {
                        onCommit()
                    }
                }
            },
        )
    }

    private fun installDateTimeCommitHandler(spinner: JSpinner, onCommit: () -> Unit) {
        val textField = spinnerEditorTextField(spinner)
        textField.addActionListener {
            if (!updatingForm) {
                onCommit()
            }
        }
        textField.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(event: FocusEvent?) {
                    if (!updatingForm) {
                        onCommit()
                    }
                }
            },
        )
    }

    private fun addRow(panel: JPanel, row: Int, label: String, component: Component): Int {
        return addRow(panel, row, JLabel(label), component)
    }

    private fun addRow(panel: JPanel, row: Int, labelComponent: JLabel, component: Component): Int {
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.WEST
            insets = Insets(4, 8, 4, 8)
        }
        val fieldConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
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
        val ports = DesktopSmartPollingPolicy.canonicalizePorts(
            SignalSlingerPortDiscovery.listAvailablePorts().map { it.portInfo },
        )
        val lastWorkingPortPath = portMemory.loadLastWorkingPortPath()
        val connectedPortPath = currentConnectedPortPath
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
            val scanResult = SignalSlingerPortDiscovery.findFirstDetectedPort(
                ports = orderedPorts,
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

            val detected = scanResult.detected
            val loadPortPath = detected?.let { detectedProbe ->
                DesktopSmartPollingPolicy.preferredPortPath(ports, detectedProbe.portInfo.systemPortPath)
            }
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

            SwingUtilities.invokeLater {
                refreshAvailablePorts(silent = true)
                appendLog("Auto Detect", buildList {
                    add(DesktopLogEntry("Scanning ${orderedPorts.size} serial port(s).", DesktopLogCategory.APP))
                    scanResult.probes.forEach { result ->
                        add(DesktopLogEntry(result.displayLabel, DesktopLogCategory.DEVICE))
                        result.evidenceLines.take(3).forEach { line ->
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

                val connectedPortStillAvailable =
                    connectedPortPath != null && ports.any { it.systemPortPath == connectedPortPath }
                val fallbackPath = DesktopAutoDetectPolicy.defaultSelectionPath(
                    availablePorts = ports,
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
                        if (lastWorkingPortPath != null && ports.any { it.systemPortPath == lastWorkingPortPath }) {
                            "No SignalSlinger found. Restored last working port ${lastWorkingPortPath}."
                        } else {
                            "No SignalSlinger found on current serial ports."
                        },
                    )
                }
            }
        }
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

    private fun reloadCurrentPortAsDetected() {
        val selectedPath = selectedProbe()?.portInfo?.systemPortPath ?: currentConnectedPortPath
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

    private fun cloneTimedEventSettings() {
        val transport = currentTransport
        val state = currentState
        val templateSettings = cloneTemplateSettings
        if (transport == null || state == null || loadedSnapshot == null || templateSettings == null) {
            JOptionPane.showMessageDialog(this, "Connect and load a SignalSlinger first.")
            return
        }
        updateCloneTemplateLabel(
            "Clone template locked. Display shows attached device state.",
            Color(0x9A, 0x67, 0x11),
        )

        runInBackground("Cloning Timed Event Settings...") {
            val targetRefresh = DeviceSessionController.refreshFromDevice(state, transport, startEditing = true)
            val targetSnapshot = requireNotNull(targetRefresh.state.snapshot)
            val editable = buildCloneEditableSettings(targetSnapshot.settings, templateSettings)
            val validated = editable.toValidatedDeviceSettings()
            val writePlan = WritePlanner.create(targetSnapshot.settings, validated)
            val result = DeviceSessionController.submitEdits(targetRefresh.state, editable, transport)
            val verificationFailures = result.verifications.filter { !it.verified }
            val refreshed = DeviceSessionController.refreshFromDevice(result.state, transport, startEditing = true)
            var finalState = refreshed.state
            var syncOperation: TimeSyncOperationResult? = null

            if (verificationFailures.isEmpty() && refreshed.state.snapshot?.capabilities?.supportsScheduling == true) {
                syncOperation = performAlignedTimeSync(
                    transport = transport,
                    state = refreshed.state,
                    snapshot = requireNotNull(refreshed.state.snapshot),
                )
                finalState = syncOperation.finalAttempt.state
            }

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
            lowFrequencyHz = SettingsField("lowFrequencyHz", "Frequency 1 (FRE 1)", targetBaseSettings.lowFrequencyHz, templateSettings.lowFrequencyHz),
            mediumFrequencyHz = SettingsField("mediumFrequencyHz", "Frequency 2 (FRE 2)", targetBaseSettings.mediumFrequencyHz, templateSettings.mediumFrequencyHz),
            highFrequencyHz = SettingsField("highFrequencyHz", "Frequency 3 (FRE 3)", targetBaseSettings.highFrequencyHz, templateSettings.highFrequencyHz),
            beaconFrequencyHz = SettingsField("beaconFrequencyHz", "Frequency B (FRE B)", targetBaseSettings.beaconFrequencyHz, templateSettings.beaconFrequencyHz),
        )
    }

    private fun cloneComparedFieldKeys(): List<SettingKey> {
        return listOf(
            SettingKey.STATION_ID,
            SettingKey.EVENT_TYPE,
            SettingKey.ID_CODE_SPEED_WPM,
            SettingKey.START_TIME,
            SettingKey.FINISH_TIME,
            SettingKey.DAYS_TO_RUN,
            SettingKey.LOW_FREQUENCY_HZ,
            SettingKey.MEDIUM_FREQUENCY_HZ,
            SettingKey.HIGH_FREQUENCY_HZ,
            SettingKey.BEACON_FREQUENCY_HZ,
        )
    }

    private fun applyImmediateEdit(
        description: String,
        updatesTimedEventTemplate: Boolean,
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

        val writePlan = WritePlanner.create(baseSettings, validatedSettings)
        if (writePlan.changes.isEmpty()) {
            return
        }

        showConnectionIndicator(
            ConnectionIndicatorState.SEARCHING,
            "Applying $description on ${currentConnectedPortPath.orEmpty()} and waiting for confirmation...",
        )
        runInBackground("Applying $description...") {
            val result = DeviceSessionController.submitEdits(state, editable, transport)
            val verificationFailures = result.verifications.filter { !it.verified }
            val changeSucceeded = verificationFailures.isEmpty()
            val refreshed = if (updatesTimedEventTemplate && changeSucceeded) {
                DeviceSessionController.refreshFromDevice(result.state, transport, startEditing = true)
            } else {
                null
            }
            val refreshClockSample = refreshed?.let { postLoadClockSample(transport, it.state.snapshot) }
            val refreshedWithClock = refreshed?.let { mergeLoadResults(it, refreshClockSample?.first) }
            val finalState = refreshedWithClock?.state ?: result.state
            currentState = finalState
            loadedSnapshot = finalState.snapshot

            if (updatesTimedEventTemplate && changeSucceeded) {
                finalState.snapshot?.settings?.let(::rememberCloneTemplateFrom)
            }

            SwingUtilities.invokeLater {
                refreshClockSample?.second?.let(::applyClockDisplayAnchor)
                applySnapshotToForm(
                    finalState.snapshot,
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
                idCodeSpeedWpm = SettingsField("idCodeSpeedWpm", "ID Speed", base.idCodeSpeedWpm, idSpeedField.text.trim().toInt()),
            )
        }
    }

    private fun applyPatternSpeedChange() {
        applyImmediateEdit("Pattern Speed", updatesTimedEventTemplate = false) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                patternCodeSpeedWpm = SettingsField(
                    "patternCodeSpeedWpm",
                    "Pattern Speed",
                    base.patternCodeSpeedWpm,
                    patternSpeedField.text.trim().toInt(),
                ),
            )
        }
    }

    private fun applyStartTimeChange() {
        val connectedTimedSettings = currentConnectedTimedSettings()
        applyImmediateEdit("Start Time", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                startTimeCompact = SettingsField(
                    "startTimeCompact",
                    "Start Time",
                    base.startTimeCompact,
                    DesktopInputSupport.validateStartTimeForWrite(
                        selectedDateTimeCompact(startTimeSpinner, connectedTimedSettings.startTimeCompact),
                    ),
                ),
            )
        }
    }

    private fun applyFinishTimeChange() {
        val connectedTimedSettings = currentConnectedTimedSettings()
        applyImmediateEdit("Finish Time", updatesTimedEventTemplate = true) { base ->
            val validatedSchedule = DesktopInputSupport.resolveScheduleForFinishTimeChange(
                startTimeCompact = connectedTimedSettings.startTimeCompact,
                finishTimeCompact = selectedDateTimeCompact(finishTimeSpinner, connectedTimedSettings.finishTimeCompact),
                currentTimeCompact = displayedDeviceTimeCompact(),
            )
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                finishTimeCompact = SettingsField(
                    "finishTimeCompact",
                    "Finish Time",
                    base.finishTimeCompact,
                    validatedSchedule.finishTimeCompact,
                ),
            )
        }
    }

    private fun applyDaysToRunChange() {
        applyImmediateEdit("Days To Run", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                daysToRun = SettingsField("daysToRun", "Days To Run", base.daysToRun, daysField.text.trim().toInt()),
            )
        }
    }

    private fun applyFrequency1Change() {
        applyImmediateEdit("Frequency 1", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                lowFrequencyHz = SettingsField(
                    "lowFrequencyHz",
                    "Frequency 1 (FRE 1)",
                    base.lowFrequencyHz,
                    DesktopInputSupport.parseOptionalFrequencyAssignment(frequency1Field.text.trim()),
                ),
            )
        }
    }

    private fun applyFrequency2Change() {
        applyImmediateEdit("Frequency 2", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                mediumFrequencyHz = SettingsField(
                    "mediumFrequencyHz",
                    "Frequency 2 (FRE 2)",
                    base.mediumFrequencyHz,
                    DesktopInputSupport.parseOptionalFrequencyAssignment(frequency2Field.text.trim()),
                ),
            )
        }
    }

    private fun applyFrequency3Change() {
        applyImmediateEdit("Frequency 3", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                highFrequencyHz = SettingsField(
                    "highFrequencyHz",
                    "Frequency 3 (FRE 3)",
                    base.highFrequencyHz,
                    DesktopInputSupport.parseOptionalFrequencyAssignment(frequency3Field.text.trim()),
                ),
            )
        }
    }

    private fun applyFrequencyBChange() {
        applyImmediateEdit("Frequency B", updatesTimedEventTemplate = true) { base ->
            EditableDeviceSettings.fromDeviceSettings(base).copy(
                beaconFrequencyHz = SettingsField(
                    "beaconFrequencyHz",
                    "Frequency B (FRE B)",
                    base.beaconFrequencyHz,
                    DesktopInputSupport.parseOptionalFrequencyAssignment(frequencyBField.text.trim()),
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
                    DesktopInputSupport.parseOptionalDouble(batteryThresholdField.text.trim()),
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

    private fun applySnapshotToForm(snapshot: DeviceSnapshot?, recalculateClockOffset: Boolean = true) {
        if (snapshot == null) {
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
            idSpeedField.text = timedSettings.idCodeSpeedWpm.toString()
            patternSpeedField.text = settings.patternCodeSpeedWpm.toString()
            setDateTimeEditorValue(startTimeSpinner, startTimeStatusLabel, timedSettings.startTimeCompact)
            setDateTimeEditorValue(finishTimeSpinner, finishTimeStatusLabel, timedSettings.finishTimeCompact)
            applyDateTimeEditorCapability(startTimeSpinner, schedulingSupported)
            applyDateTimeEditorCapability(finishTimeSpinner, schedulingSupported)
            daysField.isEnabled = schedulingSupported
            daysField.text = timedSettings.daysToRun.toString()
            currentFrequencyField.text = FrequencySupport.formatFrequencyMhz(frequencies.currentFrequencyHz)
            currentBankField.text = frequencies.currentBankId?.label ?: "Unknown"
            frequency1Field.text = FrequencySupport.formatFrequencyMhz(timedSettings.lowFrequencyHz)
            frequency2Field.text = FrequencySupport.formatFrequencyMhz(timedSettings.mediumFrequencyHz)
            frequency3Field.text = FrequencySupport.formatFrequencyMhz(timedSettings.highFrequencyHz)
            frequencyBField.text = FrequencySupport.formatFrequencyMhz(timedSettings.beaconFrequencyHz)
            updateTimedEventFrequencyVisibility(timedSettings.eventType)
            batteryThresholdField.text = DesktopInputSupport.formatThresholdOrWaiting(settings.lowBatteryThresholdVolts)
            batteryModeCombo.selectedItem = settings.externalBatteryControlMode ?: ExternalBatteryControlMode.OFF
            updateTransmissionsField(settings.transmissionsEnabled)
            versionInfoField.text = DesktopInputSupport.formatReportedVersion(
                softwareVersion = snapshot.info.softwareVersion,
                hardwareBuild = snapshot.info.hardwareBuild,
            )
            internalBatteryField.text = DesktopInputSupport.formatVoltageOrWaiting(snapshot.status.internalBatteryVolts)
            externalBatteryField.text = DesktopInputSupport.formatVoltageOrWaiting(snapshot.status.externalBatteryVolts)
            temperatureField.text = DesktopInputSupport.formatTemperatureOrWaiting(snapshot.status.temperatureC)
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
        transmissionsField.text = if (isEnabled) "Enabled" else "Disabled"
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
            SettingKey.CURRENT_TIME -> "Current Time"
            SettingKey.START_TIME -> "Start Time"
            SettingKey.FINISH_TIME -> "Finish Time"
            SettingKey.DAYS_TO_RUN -> "Days To Run"
            SettingKey.DEFAULT_FREQUENCY_HZ -> "Current Frequency"
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
            -> FrequencySupport.formatFrequencyMhz(value as? Long)
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
                entries += DesktopLogEntry("TX(verify) CLK T", DesktopLogCategory.SERIAL, sample.sentAtMs)
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

    private fun runInBackground(status: String, task: () -> Unit) {
        if (backgroundWorkInProgress) {
            return
        }
        backgroundWorkInProgress = true
        setBusy(true)
        setStatus(status)
        Thread {
            try {
                task()
            } catch (exception: Exception) {
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(this, exception.message ?: exception.toString())
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
            } finally {
                SwingUtilities.invokeLater {
                    backgroundWorkInProgress = false
                    setBusy(false)
                }
            }
        }.start()
    }

    private fun setBusy(isBusy: Boolean) {
        autoDetectButton.isEnabled = !isBusy
        portComboBox.isEnabled = !isBusy
        toggleLogButton.isEnabled = true
        openLogFolderButton.isEnabled = logVisible
        rawCommandField.isEnabled =
            !isBusy &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED
        updateWritableControlAvailability(isBusy)
        if (isBusy) {
            syncTimeButton.isEnabled = false
            disableEventButton.isEnabled = false
        } else {
            updateDisplayedClockFields()
        }
        submitButton.isEnabled =
            !isBusy &&
            currentTransport != null &&
            currentState?.connectionState == ConnectionState.CONNECTED &&
            cloneTemplateSettings != null
    }

    private fun updateWritableControlAvailability(isBusy: Boolean) {
        val connected = currentTransport != null && currentState?.connectionState == ConnectionState.CONNECTED
        val schedulingSupported = loadedSnapshot?.capabilities?.supportsScheduling == true
        val writableEnabled = connected && !isBusy

        stationIdField.isEnabled = writableEnabled
        eventTypeCombo.isEnabled = writableEnabled
        foxRoleCombo.isEnabled = writableEnabled
        patternTextField.isEnabled = writableEnabled
        idSpeedField.isEnabled = writableEnabled
        patternSpeedField.isEnabled = writableEnabled
        batteryThresholdField.isEnabled = writableEnabled
        batteryModeCombo.isEnabled = writableEnabled
        transmissionsField.isEnabled = true

        daysField.isEnabled = writableEnabled && schedulingSupported
        frequency1Field.isEnabled = writableEnabled
        frequency2Field.isEnabled = writableEnabled
        frequency3Field.isEnabled = writableEnabled
        frequencyBField.isEnabled = writableEnabled

        applyDateTimeEditorCapability(startTimeSpinner, writableEnabled && schedulingSupported)
        applyDateTimeEditorCapability(finishTimeSpinner, writableEnabled && schedulingSupported)
    }

    private fun setStatus(status: String) {
        statusLabel.text = status
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
            transport.sendCommands(listOf(command))
            val responseLines = transport.readAvailableLines()
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
            )
            val finalClockSample = postLoadClockSample(requireNotNull(currentTransport), initialRefresh.state.snapshot)
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
        val initialLoad = DeviceSessionController.connectAndLoad(transport)
        val finalClockSample = postLoadClockSample(transport, initialLoad.state.snapshot)
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
            commandsSent = List(samples.size) { "CLK T" },
            linesReceived = samples.flatMap { it.responseLines },
            traceEntries = buildList {
                samples.forEach { sample ->
                    add(SerialTraceEntry(sample.sentAtMs, SerialTraceDirection.TX, "CLK T"))
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
        toggleLogButton.text = if (isVisible) "Hide Log" else "Show Log"
        openLogFolderButton.isVisible = isVisible
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

    private fun syncDeviceTimeToSystem() {
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

        runInBackground("Syncing device time to system time...") {
            val syncResult = performAlignedTimeSync(
                transport = transport,
                state = state,
                snapshot = snapshot,
            )
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
            }
        }
    }

    private fun performAlignedTimeSync(
        transport: DesktopSerialTransport,
        state: DeviceSessionState,
        snapshot: DeviceSnapshot,
    ): TimeSyncOperationResult {
        val latencySamples = sampleClockReadLatency(transport, sampleCount = 3)
        var oneWayDelayMillis = DesktopInputSupport.medianMillis(latencySamples.map { it.roundTripMillis }) / 2
        var workingState = state
        var workingSnapshot = snapshot
        var bestAttempt: SyncAttempt? = null
        val attempts = mutableListOf<SyncAttempt>()
        var syncComplete = false

        repeat(2) {
            if (syncComplete) {
                return@repeat
            }
            val attempt = performSyncAttempt(
                transport = transport,
                state = workingState,
                snapshot = workingSnapshot,
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
            val goLines = mutableListOf<String>()
            transport.sendCommands(goCommands)
            goLines += transport.readAvailableLines()

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
            }
            if (finishMirrorLines.isNotEmpty()) {
                nextState = DeviceSessionWorkflow.ingestReportLines(nextState, finishMirrorLines)
            }

            currentState = nextState
            loadedSnapshot = nextState.snapshot
            nextState.snapshot?.settings?.let(::rememberCloneTemplateFrom)

            SwingUtilities.invokeLater {
                applySnapshotToForm(nextState.snapshot)
                appendDisableEventLog(goCommands, goLines, finishMirrorCommands, finishMirrorLines)
                setStatus("Event disabled.")
            }
        }
    }

    private fun updateDisplayedClockFields() {
        val snapshot = loadedSnapshot
        val settings = snapshot?.settings
        val timedSettings = settings
        val sampledSystemNow = java.time.LocalDateTime.now()
        val displayNow = sampledSystemNow.withNano(0)
        val displayedDeviceTimeCompact = displayedDeviceTimeCompact(sampledSystemNow)

        systemTimeField.text = DesktopInputSupport.formatSystemTimestamp(displayNow)
        currentTimeField.text = DesktopInputSupport.formatCompactTimestampOrNotSet(displayedDeviceTimeCompact)
        startsInField.text = DesktopInputSupport.describeEventStatus(
            deviceReportedEventEnabled = snapshot?.status?.eventEnabled,
            eventStateSummary = snapshot?.status?.eventStateSummary,
            currentTimeCompact = displayedDeviceTimeCompact,
            startTimeCompact = timedSettings?.startTimeCompact,
            finishTimeCompact = timedSettings?.finishTimeCompact,
            startsInFallback = snapshot?.status?.eventStartsInSummary,
        )
        lastsField.text = DesktopInputSupport.describeEventDuration(
            startTimeCompact = timedSettings?.startTimeCompact,
            finishTimeCompact = timedSettings?.finishTimeCompact,
            fallback = snapshot?.status?.eventDurationSummary,
        )
        eventEnabledField.text = DesktopInputSupport.eventEnabledLabel(
            deviceReportedEventEnabled = snapshot?.status?.eventEnabled,
            currentTimeCompact = displayedDeviceTimeCompact,
            startTimeCompact = timedSettings?.startTimeCompact,
            finishTimeCompact = timedSettings?.finishTimeCompact,
            systemNow = displayNow,
        )
        syncTimeButton.isEnabled =
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

    private fun displayedDeviceTimeCompact(systemNow: java.time.LocalDateTime = java.time.LocalDateTime.now()): String? {
        val settings = loadedSnapshot?.settings
        return when {
            deviceTimeOffset != null -> DesktopInputSupport.formatTruncatedCompactTimestamp(
                systemNow.plus(requireNotNull(deviceTimeOffset)),
            )
            else -> settings?.currentTimeCompact
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
        if ((System.currentTimeMillis() - lastDeviceTimeCheckAtMs) < 600_000L) {
            return
        }

        runInBackground("Refreshing device time from SignalSlinger...") {
            val transport = requireNotNull(currentTransport)
            val state = requireNotNull(currentState)
            val clockSamples = observeClockPhaseSamples(transport, maxSamples = 8)
            val responseLines = mutableListOf<String>()
            responseLines += clockSamples.flatMap { it.responseLines }
            val eventSentAtMs = System.currentTimeMillis()
            transport.sendCommands(listOf("EVT"))
            val eventLines = transport.readAvailableLines()
            val eventReceivedAtMs = System.currentTimeMillis()
            responseLines += eventLines
            lastDeviceTimeCheckAtMs = System.currentTimeMillis()
            val phaseErrorMillis = DesktopInputSupport.estimateClockPhaseErrorMillis(
                clockSamples.map { sample ->
                    DesktopInputSupport.ClockPhaseSample(
                        midpointAt = sample.midpointAt,
                        reportedTimeCompact = sample.reportedTimeCompact,
                    )
                },
            )
            val latestClockSample = clockSamples.lastOrNull()

            SwingUtilities.invokeLater {
                if (responseLines.isNotEmpty()) {
                    consecutiveDeviceTimeCheckNoResponseCount = 0
                    currentState = DeviceSessionWorkflow.ingestReportLines(state, responseLines)
                    loadedSnapshot = currentState?.snapshot
                    if (phaseErrorMillis != null && kotlin.math.abs(phaseErrorMillis) <= 500L) {
                        applyClockDisplayAnchor(
                            ClockDisplayAnchor(
                                currentTimeCompact = latestClockSample?.reportedTimeCompact,
                                referenceTime = latestClockSample?.midpointAt,
                                phaseErrorMillis = phaseErrorMillis,
                            ),
                        )
                    }
                    updateClockPhaseWarning(phaseErrorMillis)
                    appendLog("Device Time Check", buildList {
                        clockSamples.forEach { sample ->
                            add(DesktopLogEntry("TX CLK T", DesktopLogCategory.SERIAL, sample.sentAtMs))
                            sample.responseLines.forEach { line ->
                                add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL, sample.receivedAtMs))
                            }
                        }
                        add(DesktopLogEntry("TX EVT", DesktopLogCategory.SERIAL, eventSentAtMs))
                        eventLines.forEach { line ->
                            add(DesktopLogEntry("RX $line", DesktopLogCategory.SERIAL, eventReceivedAtMs))
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
        refreshAvailablePorts(silent = true)
        updateDisplayedClockFields()
    }

    private fun updateClockPhaseWarning(phaseErrorMillis: Long?) {
        val portPath = currentConnectedPortPath.orEmpty()
        val shouldWarn = phaseErrorMillis != null && kotlin.math.abs(phaseErrorMillis) > 500L
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

    private fun createDateTimeSpinner(): JSpinner {
        return JSpinner(SpinnerDateModel(Date(), null, null, Calendar.MINUTE)).apply {
            editor = JSpinner.DateEditor(this, "yyyy-MM-dd HH:mm")
        }
    }

    private fun configureNullableDateTimeSpinner(spinner: JSpinner, statusLabel: JLabel) {
        spinner.putClientProperty(NULLABLE_TIMESTAMP_ACTIVE_KEY, false)
        spinner.putClientProperty(NULLABLE_TIMESTAMP_STATUS_LABEL_KEY, statusLabel)
        val textField = spinnerEditorTextField(spinner)
        spinner.addChangeListener {
            if (!updatingForm && isNullableDateTimeSpinnerActive(spinner)) {
                setNullableDateTimeSpinnerActive(spinner, false)
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
    }

    private fun setDateTimeEditorValue(spinner: JSpinner, statusLabel: JLabel, compactTimestamp: String?) {
        if (compactTimestamp == null) {
            spinner.value = suggestedDateTimeEditorValue()
            setNullableDateTimeSpinnerActive(spinner, true)
            statusLabel.text = "Not Set"
            return
        }

        val localDateTime = DesktopInputSupport.parseCompactTimestamp(compactTimestamp)
        spinner.putClientProperty(NULLABLE_TIMESTAMP_ACTIVE_KEY, false)
        statusLabel.text = " "
        spinner.value = Date.from(
            DesktopInputSupport.truncateToMinute(localDateTime).atZone(ZoneId.systemDefault()).toInstant(),
        )
        updateDateTimeEditorPresentation(spinner, showNotSet = false)
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

    private fun updateDateTimeEditorPresentation(spinner: JSpinner, showNotSet: Boolean) {
        val textField = spinnerEditorTextField(spinner)
        textField.isEditable = true
        if (showNotSet) {
            textField.text = "Not Set"
        } else {
            textField.value = spinner.value
        }
    }

    private fun spinnerEditorTextField(spinner: JSpinner): JFormattedTextField {
        val editor = spinner.editor as? JSpinner.DateEditor
            ?: error("Date/time spinner editor is unavailable.")
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

    private fun performSyncAttempt(
        transport: DesktopSerialTransport,
        state: DeviceSessionState,
        snapshot: DeviceSnapshot,
        estimatedOneWayDelayMillis: Long,
    ): SyncAttempt {
        val minimumLeadMillis = maxOf(1_500L, estimatedOneWayDelayMillis + 400L)
        val syncTargetTime = DesktopInputSupport.nextSyncTargetTime(
            minimumLeadMillis = minimumLeadMillis,
        )
        waitForSyncTargetDispatch(syncTargetTime, estimatedOneWayDelayMillis)
        val editable = EditableDeviceSettings.fromDeviceSettings(snapshot.settings).copy(
            currentTimeCompact = SettingsField(
                "currentTimeCompact",
                "Current Time",
                snapshot.settings.currentTimeCompact,
                DesktopInputSupport.formatCompactTimestamp(syncTargetTime),
            ),
        )
        val submitResult = DeviceSessionController.submitEdits(state, editable, transport)
        var nextState = submitResult.state
        val verificationSamples = observeClockPhaseSamples(transport)
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
            targetTime = syncTargetTime,
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

    private fun readClockSample(transport: DesktopSerialTransport): ClockReadSample {
        val sentAt = java.time.LocalDateTime.now()
        transport.sendCommands(listOf("CLK T"))
        val responseLines = transport.readAvailableLines()
        val receivedAt = java.time.LocalDateTime.now()
        return ClockReadSample(
            sentAt = sentAt,
            receivedAt = receivedAt,
            responseLines = responseLines,
            reportedTimeCompact = responseLines
                .mapNotNull { line -> SignalSlingerProtocolCodec.parseReportLine(line)?.settingsPatch?.currentTimeCompact }
                .firstOrNull(),
        )
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
        const val NULLABLE_TIMESTAMP_ACTIVE_KEY = "serialslinger.nullableTimestampActive"
        const val NULLABLE_TIMESTAMP_STATUS_LABEL_KEY = "serialslinger.nullableTimestampStatusLabel"
        const val AUTO_DETECT_BUTTON_LONG_PRESS_MS = 900
        const val CLONE_BUTTON_LONG_PRESS_MS = 900
    }
}
