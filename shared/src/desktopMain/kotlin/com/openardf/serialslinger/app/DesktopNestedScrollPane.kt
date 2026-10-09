package com.openardf.serialslinger.app

import java.awt.Component
import java.awt.event.MouseWheelEvent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A scroll pane that hands wheel gestures to its containing scroll pane after reaching an edge.
 *
 * Swing otherwise consumes the gesture in the nested pane, which makes a long parent form appear
 * to stop scrolling whenever the pointer happens to be over a small read-only text area.
 */
internal class DesktopNestedScrollPane(view: Component) : JScrollPane(view) {
    override fun processMouseWheelEvent(event: MouseWheelEvent) {
        if (event.preciseWheelRotation == 0.0 || canScrollVertically(event.preciseWheelRotation)) {
            super.processMouseWheelEvent(event)
            return
        }

        val ancestor = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, parent) as? JScrollPane
        if (ancestor == null) {
            super.processMouseWheelEvent(event)
            return
        }

        // Preserve the precise rotation so macOS trackpad momentum continues naturally in the form.
        val point = SwingUtilities.convertPoint(this, event.point, ancestor)
        ancestor.dispatchEvent(
            MouseWheelEvent(
                ancestor,
                event.id,
                event.`when`,
                event.modifiersEx,
                point.x,
                point.y,
                event.xOnScreen,
                event.yOnScreen,
                event.clickCount,
                event.isPopupTrigger,
                event.scrollType,
                event.scrollAmount,
                event.wheelRotation,
                event.preciseWheelRotation,
            ),
        )
    }

    internal fun canScrollVertically(rotation: Double): Boolean {
        val bar = verticalScrollBar
        return if (rotation > 0.0) {
            bar.value + bar.visibleAmount < bar.maximum
        } else {
            bar.value > bar.minimum
        }
    }
}
