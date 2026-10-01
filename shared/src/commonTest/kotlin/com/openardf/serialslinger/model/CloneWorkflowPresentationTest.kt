package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloneWorkflowPresentationTest {
    private val sourceUid = "42348279800036200109013300000000"
    private val targetUid = "42348279800026200111012100000000"
    private val fox3Settings = DeviceSettings.empty().copy(foxRole = FoxRole.CLASSIC_3)
    private val fox2Settings = DeviceSettings.empty().copy(foxRole = FoxRole.CLASSIC_2)

    @Test fun unloadedWorkflowOffersLoadAndDisablesClone() {
        val presentation = CloneWorkflowPresentationSupport.present(
            templateLoaded = false,
            canLoadTemplate = true,
            canClone = true,
            templateSettings = fox3Settings,
            templateSourceDeviceUniqueId = sourceUid,
            attachedSettings = fox3Settings,
            attachedDeviceUniqueId = sourceUid,
        )

        assertEquals("Load Clone Settings", presentation.loadLabel)
        assertEquals(CloneTemplateLoadMode.LOAD, presentation.loadMode)
        assertTrue(presentation.loadEnabled)
        assertEquals("Clone", presentation.cloneLabel)
        assertFalse(presentation.cloneEnabled)
        assertNull(presentation.replacementConfirmationMessage)
    }

    @Test fun loadedSourceCanBeReloadedButNotClonedOntoItself() {
        val presentation = CloneWorkflowPresentationSupport.present(
            templateLoaded = true,
            canLoadTemplate = true,
            canClone = true,
            templateSettings = fox3Settings,
            templateSourceDeviceUniqueId = sourceUid,
            attachedSettings = fox3Settings,
            attachedDeviceUniqueId = sourceUid,
        )

        assertEquals("Reload Clone Settings", presentation.loadLabel)
        assertEquals(CloneTemplateLoadMode.RELOAD, presentation.loadMode)
        assertEquals("Clone (Settings Loaded from FOX 3)", presentation.cloneLabel)
        assertFalse(presentation.cloneEnabled)
        assertNull(presentation.replacementConfirmationMessage)
    }

    @Test fun differentDeviceCanReplaceTheTemplateOrReceiveTheClone() {
        val presentation = CloneWorkflowPresentationSupport.present(
            templateLoaded = true,
            canLoadTemplate = true,
            canClone = true,
            templateSettings = fox3Settings,
            templateSourceDeviceUniqueId = sourceUid,
            attachedSettings = fox2Settings,
            attachedDeviceUniqueId = targetUid,
        )

        assertEquals("Replace Clone Settings", presentation.loadLabel)
        assertEquals(CloneTemplateLoadMode.REPLACE, presentation.loadMode)
        assertEquals("Clone (Settings Loaded from FOX 3)", presentation.cloneLabel)
        assertTrue(presentation.cloneEnabled)
        assertEquals(
            "Replace clone settings loaded from FOX 3 (unit $sourceUid) " +
                "with settings from FOX 2 (unit $targetUid)?",
            presentation.replacementConfirmationMessage,
        )
    }

    @Test fun matchingRoleNamesRemainDistinguishableByFullUnitId() {
        val presentation = CloneWorkflowPresentationSupport.present(
            templateLoaded = true,
            canLoadTemplate = true,
            canClone = true,
            templateSettings = fox3Settings,
            templateSourceDeviceUniqueId = sourceUid,
            attachedSettings = fox3Settings,
            attachedDeviceUniqueId = targetUid,
        )

        assertEquals("Clone (Settings Loaded from FOX 3)", presentation.cloneLabel)
        assertEquals(
            "Replace clone settings loaded from FOX 3 (unit $sourceUid) " +
                "with settings from FOX 3 (unit $targetUid)?",
            presentation.replacementConfirmationMessage,
        )
    }
}
