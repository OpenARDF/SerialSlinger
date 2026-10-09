package com.openardf.serialslinger.app

import java.awt.event.MouseWheelEvent
import javax.swing.DefaultBoundedRangeModel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNestedScrollPaneTest {
    @Test
    fun forwardsWheelDownToParentAtBottomEdge() {
        SwingUtilities.invokeAndWait {
            val nested = DesktopNestedScrollPane(JTextArea())
            val parent = JScrollPane(JPanel().apply { add(nested) })
            nested.verticalScrollBar.model = DefaultBoundedRangeModel(90, 10, 0, 100)
            var forwardedRotation = 0.0
            parent.addMouseWheelListener { forwardedRotation = it.preciseWheelRotation }

            nested.dispatchEvent(wheelEvent(nested, rotation = 1.25))

            assertEquals(1.25, forwardedRotation)
        }
    }

    @Test
    fun forwardsWheelUpToParentAtTopEdge() {
        SwingUtilities.invokeAndWait {
            val nested = DesktopNestedScrollPane(JTextArea())
            val parent = JScrollPane(JPanel().apply { add(nested) })
            nested.verticalScrollBar.model = DefaultBoundedRangeModel(0, 10, 0, 100)
            var forwardedRotation = 0.0
            var forwardedWheelRotation = 0
            parent.addMouseWheelListener {
                forwardedRotation = it.preciseWheelRotation
                forwardedWheelRotation = it.wheelRotation
            }

            nested.dispatchEvent(wheelEvent(nested, rotation = -0.75))

            assertEquals(-0.75, forwardedRotation)
            assertEquals(-1, forwardedWheelRotation)
        }
    }

    @Test
    fun recognizesWhetherNestedPaneCanScrollInGestureDirection() {
        SwingUtilities.invokeAndWait {
            val nested = DesktopNestedScrollPane(JTextArea())
            nested.verticalScrollBar.model = DefaultBoundedRangeModel(40, 10, 0, 100)
            assertTrue(nested.canScrollVertically(1.0))
            assertTrue(nested.canScrollVertically(-1.0))

            nested.verticalScrollBar.value = 90
            assertFalse(nested.canScrollVertically(1.0))
            nested.verticalScrollBar.value = 0
            assertFalse(nested.canScrollVertically(-1.0))
        }
    }

    private fun wheelEvent(source: DesktopNestedScrollPane, rotation: Double): MouseWheelEvent =
        MouseWheelEvent(
            source,
            MouseWheelEvent.MOUSE_WHEEL,
            System.currentTimeMillis(),
            0,
            1,
            1,
            1,
            1,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            3,
            rotation.toInt().coerceIn(-1, 1),
            rotation,
        )
}
