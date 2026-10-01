package com.openardf.serialslinger.model

enum class CloneTemplateLoadMode {
    LOAD,
    RELOAD,
    REPLACE,
}

data class CloneWorkflowPresentation(
    val loadLabel: String,
    val loadContentDescription: String,
    val loadEnabled: Boolean,
    val loadMode: CloneTemplateLoadMode,
    val cloneLabel: String,
    val cloneContentDescription: String,
    val cloneEnabled: Boolean,
    val sourceLabel: String?,
    val attachedLabel: String?,
) {
    val replacementConfirmationMessage: String?
        get() =
            if (loadMode == CloneTemplateLoadMode.REPLACE) {
                "Replace clone settings loaded from ${sourceLabel ?: "the current source"} " +
                    "with settings from ${attachedLabel ?: "the attached device"}?"
            } else {
                null
            }
}

/** Defines the same explicit load and clone controls for every SerialSlinger UI. */
object CloneWorkflowPresentationSupport {
    fun present(
        templateLoaded: Boolean,
        canLoadTemplate: Boolean,
        canClone: Boolean,
        templateSettings: DeviceSettings?,
        templateSourceDeviceUniqueId: String?,
        attachedSettings: DeviceSettings?,
        attachedDeviceUniqueId: String?,
    ): CloneWorkflowPresentation {
        val sourceDisplayLabel =
            if (templateLoaded) {
                deviceLabel(templateSettings, templateSourceDeviceUniqueId, "source device")
            } else {
                null
            }
        val sourceLabel =
            if (templateLoaded) {
                deviceIdentityLabel(templateSettings, templateSourceDeviceUniqueId, "source device")
            } else {
                null
            }
        val attachedLabel =
            if (canLoadTemplate) {
                deviceIdentityLabel(attachedSettings, attachedDeviceUniqueId, "the attached device")
            } else {
                null
            }
        val identityComparison =
            CloneDeviceIdentitySupport.compare(templateSourceDeviceUniqueId, attachedDeviceUniqueId)
        val loadMode =
            when {
                !templateLoaded -> CloneTemplateLoadMode.LOAD
                identityComparison == CloneDeviceIdentityComparison.SAME -> CloneTemplateLoadMode.RELOAD
                else -> CloneTemplateLoadMode.REPLACE
            }
        val loadLabel =
            when (loadMode) {
                CloneTemplateLoadMode.LOAD -> "Load Clone Settings"
                CloneTemplateLoadMode.RELOAD -> "Reload Clone Settings"
                CloneTemplateLoadMode.REPLACE -> "Replace Clone Settings"
            }
        val cloneLabel =
            sourceDisplayLabel?.let { "Clone (Settings Loaded from $it)" } ?: "Clone"
        val attachedIsSource = identityComparison == CloneDeviceIdentityComparison.SAME
        return CloneWorkflowPresentation(
            loadLabel = loadLabel,
            loadContentDescription = "$loadLabel from ${attachedLabel ?: "the attached device"}",
            loadEnabled = canLoadTemplate,
            loadMode = loadMode,
            cloneLabel = cloneLabel,
            cloneContentDescription =
                when {
                    sourceLabel == null -> "Load clone settings before cloning"
                    attachedIsSource -> "Clone settings loaded from $sourceLabel; connect a different target device"
                    else -> "Clone settings loaded from $sourceLabel to ${attachedLabel ?: "the attached device"}"
                },
            cloneEnabled = templateLoaded && canClone && !attachedIsSource,
            sourceLabel = sourceLabel,
            attachedLabel = attachedLabel,
        )
    }

    fun deviceLabel(
        settings: DeviceSettings?,
        deviceUniqueId: String?,
        fallback: String,
    ): String {
        return settings?.foxRole?.uiLabel
            ?: settings?.arduconFoxRoleCode
                ?.let(EventProfileSupport::arduconFoxRoleForDesignator)
                ?.roleName
            ?: settings?.stationId?.trim()?.takeIf(String::isNotEmpty)?.let { "station $it" }
            ?: deviceUniqueId?.trim()?.takeIf(String::isNotEmpty)?.let { "unit $it" }
            ?: fallback
    }

    fun deviceIdentityLabel(
        settings: DeviceSettings?,
        deviceUniqueId: String?,
        fallback: String,
    ): String {
        val displayLabel = deviceLabel(settings, deviceUniqueId, fallback)
        val normalizedId = deviceUniqueId?.trim()?.takeIf(String::isNotEmpty)
        return if (normalizedId == null || displayLabel.contains(normalizedId)) {
            displayLabel
        } else {
            "$displayLabel (unit $normalizedId)"
        }
    }
}
