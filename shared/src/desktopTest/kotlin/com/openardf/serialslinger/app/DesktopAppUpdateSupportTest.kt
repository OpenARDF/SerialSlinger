package com.openardf.serialslinger.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAppUpdateSupportTest {
    @Test
    fun reportsJdeployUpdateAvailableFromInjectedProperty() {
        val status = DesktopAppUpdateSupport.status("2.0.9") { key ->
            when (key) {
                "jdeploy.updatesAvailable" -> "true"
                "jdeploy.app.version" -> "2.0.9"
                "jdeploy.app.source" -> "https://github.com/OpenARDF/SerialSlinger"
                else -> null
            }
        }

        assertTrue(status.launchedByJdeploy)
        assertEquals(true, status.jdeployUpdatesAvailable)
        assertTrue(DesktopAppUpdateSupport.shouldShowAutomaticNotice(status))
        assertTrue(DesktopAppUpdateSupport.dialogMessage(status).contains("updated version"))
        assertTrue(DesktopAppUpdateSupport.dialogMessage(status).contains(DesktopAppUpdateSupport.updatePageUrl))
    }

    @Test
    fun suppressesAutomaticNoticeWhenJdeployReportsNoUpdate() {
        val status = DesktopAppUpdateSupport.status("2.0.9") { key ->
            when (key) {
                "jdeploy.updatesAvailable" -> "false"
                "jdeploy.app.version" -> "2.0.9"
                else -> null
            }
        }

        assertTrue(status.launchedByJdeploy)
        assertEquals(false, status.jdeployUpdatesAvailable)
        assertFalse(DesktopAppUpdateSupport.shouldShowAutomaticNotice(status))
    }

    @Test
    fun treatsNonJdeployLaunchAsUnknownUpdateStatus() {
        val status = DesktopAppUpdateSupport.status("2.0.9") { null }

        assertFalse(status.launchedByJdeploy)
        assertNull(status.jdeployUpdatesAvailable)
        assertFalse(DesktopAppUpdateSupport.shouldShowAutomaticNotice(status))
        assertTrue(DesktopAppUpdateSupport.dialogMessage(status).contains("not launched by jDeploy"))
    }
}
