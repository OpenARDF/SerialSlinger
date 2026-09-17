@file:Suppress("PackageName")

package com.SerialSlinger.openardf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidUserActionSupportTest {
    @Test fun viewingOrSelectingLogContentsCannotCopyThemIntoTheLog() {
        val log = "SerialSlinger 2.0.20\nPlatform: Android 17\n[07:38:43] [USER] Tapped View."
        assertNull(androidTapDescription(log))
        assertNull(androidTapDescription(log.repeat(10_000)))
        assertNull(androidTapDescription("first line\rsecond line"))
        assertNull(androidTapDescription("x".repeat(161)))
    }

    @Test fun ordinaryControlActionsRemainUseful() {
        assertEquals("Tapped Stop event and read.", androidTapDescription("Stop event and read"))
        assertEquals("Tapped View.", androidTapDescription(" View "))
        assertNull(androidTapDescription(" "))
    }
}
