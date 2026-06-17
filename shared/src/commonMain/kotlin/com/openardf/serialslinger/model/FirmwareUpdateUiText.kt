package com.openardf.serialslinger.model

object FirmwareUpdateUiText {
    fun stageLabel(stage: String, productLabel: String): String =
        when (stage) {
            "Preparing update" -> "Preparing update"
            "Checking file" -> "Checking file"
            "Sending update" -> "Sending update"
            "Verifying update" -> "Verifying update"
            "Restarting SignalSlinger" -> "Restarting SignalSlinger"
            "Restarting Arducon" -> "Restarting Arducon"
            else -> "Updating ${productLabel.ifBlank { "device" }}"
        }

    fun dialogTitle(productLabel: String): String =
        "Updating ${productLabel.ifBlank { "Device" }}"
}
