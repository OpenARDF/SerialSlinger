@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import com.openardf.serialslinger.model.*
import kotlin.test.*

class AndroidCloneTemplateMemoryTest {
    private val fox4 = DeviceSnapshot(
        info = DeviceInfo(deviceUniqueId = "42348279800081200106014200000000"),
        settings = DeviceSettings.empty().copy(startTimeCompact = "260916071000", finishTimeCompact = "260916072000"),
        status = DeviceStatus(daysRemaining = 1),
    )
    private val fox2 = fox4.copy(
        info = DeviceInfo(deviceUniqueId = "42348279800053200112011900000000"),
        settings = fox4.settings.copy(startTimeCompact = "260912122000", finishTimeCompact = "260912132000"),
        status = DeviceStatus(daysRemaining = 0),
    )

    @Test fun updatingTheNextFoxPreservesTheSourceScheduleAndAllowsCloning() {
        val template = AndroidCloneTemplateMemory()
        template.seedAfterFirmwareReload(fox4)
        template.timedEventEditsLocked = true
        template.seedAfterFirmwareReload(fox2)

        assertEquals(fox4.settings, template.settings)
        assertEquals(fox4.info.deviceUniqueId, template.sourceDeviceUniqueId)
        assertEquals(1, template.daysRemaining)
        assertTrue(template.timedEventEditsLocked)
        CloneDeviceIdentitySupport.requireDifferentDevice(template.sourceDeviceUniqueId, fox2.info.deviceUniqueId)
        assertFailsWith<IllegalStateException> {
            CloneDeviceIdentitySupport.requireDifferentDevice(template.sourceDeviceUniqueId, fox4.info.deviceUniqueId)
        }
    }

    @Test fun updatingTheSourceOrFailingToReloadCannotDiscardTemplateEdits() {
        val template = AndroidCloneTemplateMemory()
        template.seedAfterFirmwareReload(fox4)
        val edited = fox4.settings.copy(finishTimeCompact = "260916082000")
        template.settings = edited
        template.seedAfterFirmwareReload(fox4)
        template.seedAfterFirmwareReload(null)
        assertEquals(edited, template.settings)
        assertEquals(fox4.info.deviceUniqueId, template.sourceDeviceUniqueId)
    }

    @Test fun firstSuccessfulReloadCanSeedALegacyTemplateWithoutAUid() {
        val template = AndroidCloneTemplateMemory()
        template.seedAfterFirmwareReload(null)
        assertNull(template.settings)
        template.seedAfterFirmwareReload(fox4.copy(info = DeviceInfo()))
        template.seedAfterFirmwareReload(fox2)
        assertEquals(fox4.settings, template.settings)
        assertNull(template.sourceDeviceUniqueId)
        assertEquals(1, template.daysRemaining)
    }
}
