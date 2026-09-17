package com.openardf.serialslinger.app

import java.awt.BorderLayout
import javax.swing.*

internal class DesktopSessionHistoryView(onDetails: () -> Unit) {
    val label = JLabel("Session history")
    val summary = JTextArea(6, 40).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    val details = JButton("View details...").apply { addActionListener { onDetails() } }
    val panel = JPanel(BorderLayout()).apply {
        add(JScrollPane(summary), BorderLayout.CENTER)
        add(details, BorderLayout.SOUTH)
    }

    init { setAdvancedMode(false) }

    fun setAdvancedMode(enabled: Boolean) {
        label.isVisible = enabled
        panel.isVisible = enabled
        panel.parent?.revalidate()
        panel.parent?.repaint()
    }
}
