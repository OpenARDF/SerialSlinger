package com.openardf.serialslinger.app

import com.openardf.serialslinger.model.*
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*
import kotlin.test.*

class DesktopSessionHistoryViewTest {
    @Test fun advancedModeControlsTheWholeRowAndDetailsRemainAccessible() {
        SwingUtilities.invokeAndWait {
            var opened = false
            val view = DesktopSessionHistoryView { opened = true }
            assertFalse(view.label.isVisible)
            assertFalse(view.panel.isVisible)
            view.setAdvancedMode(true)
            assertTrue(view.label.isVisible)
            assertTrue(view.panel.isVisible)
            view.details.doClick()
            assertTrue(opened)
            view.setAdvancedMode(false)
            assertFalse(view.label.isVisible)
            assertFalse(view.panel.isVisible)
        }
    }

    @Test fun renderRepresentativeHistoryForVisualReview() {
        SwingUtilities.invokeAndWait {
            val view = DesktopSessionHistoryView {}
            view.setAdvancedMode(true)
            view.summary.text = SessionHistoryPresentation.summary(listOf(
                SessionHistoryRecord(21,1789542600,1789516800,1789543257,"260916071057",1,0,129,23.2,65),
                SessionHistoryRecord(22,1789542600,1789516800,1789543257,"260916072057",4,1,129,27.2,65),
            ))
            view.summary.caretPosition = 0
            val panel = JPanel(BorderLayout(8,8)).apply {
                border = BorderFactory.createEmptyBorder(12,12,12,12)
                add(view.label, BorderLayout.NORTH)
                add(view.panel, BorderLayout.CENTER)
                setSize(600,240)
            }
            fun layout(component: Component) {
                if (component is Container) {
                    component.doLayout()
                    component.components.forEach(::layout)
                }
            }
            layout(panel)
            val image = BufferedImage(panel.width,panel.height,BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            panel.printAll(graphics)
            graphics.dispose()
            val output = Path.of("build/reports/session-history-preview.png")
            Files.createDirectories(output.parent)
            ImageIO.write(image,"png",output.toFile())
            assertEquals(0,view.summary.caretPosition)
        }
    }
}
