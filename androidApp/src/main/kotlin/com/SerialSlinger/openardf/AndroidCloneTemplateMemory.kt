@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import com.openardf.serialslinger.model.DeviceSettings
import com.openardf.serialslinger.model.DeviceSnapshot

/** The template and its source identity must survive updates of clone targets together. */
internal class AndroidCloneTemplateMemory {
    var settings: DeviceSettings? = null
    var sourceDeviceUniqueId: String? = null
    var daysRemaining: Int? = null
    var timedEventEditsLocked: Boolean = false

    fun seedAfterFirmwareReload(snapshot: DeviceSnapshot?) {
        if (settings != null || snapshot == null) return
        settings = snapshot.settings
        sourceDeviceUniqueId = snapshot.info.deviceUniqueId
        daysRemaining = snapshot.status.daysRemaining
        timedEventEditsLocked = false
    }
}
