package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals

class FirmwareUpdateUiTextTest {
    @Test
    fun usesProductLabelForDialogTitle() {
        assertEquals("Updating SignalSlinger", FirmwareUpdateUiText.dialogTitle("SignalSlinger"))
        assertEquals("Updating Arducon", FirmwareUpdateUiText.dialogTitle("Arducon"))
    }

    @Test
    fun usesProductLabelForUnknownStageFallback() {
        assertEquals("Updating Arducon", FirmwareUpdateUiText.stageLabel("Waiting for device", "Arducon"))
    }

    @Test
    fun preservesKnownStageLabels() {
        assertEquals("Sending update", FirmwareUpdateUiText.stageLabel("Sending update", "Arducon"))
        assertEquals("Restarting Arducon", FirmwareUpdateUiText.stageLabel("Restarting Arducon", "Arducon"))
    }
}
