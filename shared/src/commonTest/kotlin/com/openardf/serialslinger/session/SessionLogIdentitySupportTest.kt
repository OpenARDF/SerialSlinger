package com.openardf.serialslinger.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionLogIdentitySupportTest {
    private val current = SessionLogIdentity("2.0.22b", "Android 15")

    @Test fun matchingHeaderNeedsNoSessionMarker() {
        assertFalse(
            SessionLogIdentitySupport.needsSessionMarker(
                existingText = current.headerText + "[08:00:00] == Existing ==\n",
                identity = current,
            ),
        )
    }

    @Test fun earlierVersionHeaderNeedsCurrentSessionMarker() {
        assertTrue(
            SessionLogIdentitySupport.needsSessionMarker(
                existingText = "SerialSlinger 2.0.17a\nPlatform: Android 15\n\n",
                identity = current,
            ),
        )
    }

    @Test fun latestSessionMarkerControlsWhetherAnotherMarkerIsNeeded() {
        val oldHeader = "SerialSlinger 2.0.17a\nPlatform: Android 15\n\n"
        val currentMarker = "[09:00:00] [APP] ${current.sessionMarkerMessage}\n"
        assertFalse(SessionLogIdentitySupport.needsSessionMarker(oldHeader + currentMarker, current))

        val newer = SessionLogIdentity("2.0.22c", "Android 15")
        assertTrue(SessionLogIdentitySupport.needsSessionMarker(oldHeader + currentMarker, newer))
    }
}
