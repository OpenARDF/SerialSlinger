package com.openardf.serialslinger.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloneDeviceIdentitySupportTest {
    @Test
    fun differentDeviceUidsAllowClone() {
        assertEquals(
            CloneDeviceIdentityComparison.DIFFERENT,
            CloneDeviceIdentitySupport.compare(
                templateSourceDeviceUniqueId = "314A323536384E171D00321700000000",
                targetDeviceUniqueId = "314A323536384E171D00321700000001",
            ),
        )

        CloneDeviceIdentitySupport.requireDifferentDevice(
            templateSourceDeviceUniqueId = "314A323536384E171D00321700000000",
            targetDeviceUniqueId = "314A323536384E171D00321700000001",
        )
    }

    @Test
    fun sameDeviceUidRejectsCloneCaseInsensitively() {
        assertEquals(
            CloneDeviceIdentityComparison.SAME,
            CloneDeviceIdentitySupport.compare(
                templateSourceDeviceUniqueId = "314A323536384E171D00321700000000",
                targetDeviceUniqueId = "314a323536384e171d00321700000000",
            ),
        )

        val failure =
            assertFailsWith<IllegalStateException> {
                CloneDeviceIdentitySupport.requireDifferentDevice(
                    templateSourceDeviceUniqueId = "314A323536384E171D00321700000000",
                    targetDeviceUniqueId = "314a323536384e171d00321700000000",
                )
            }
        assertTrue(failure.message.orEmpty().contains("same unit"))
    }

    @Test
    fun uidOnOnlyOneSideProvesCloneTargetIsDifferent() {
        assertEquals(
            CloneDeviceIdentityComparison.DIFFERENT,
            CloneDeviceIdentitySupport.compare(
                templateSourceDeviceUniqueId = "314A323536384E171D00321700000000",
                targetDeviceUniqueId = null,
            ),
        )
        assertEquals(
            CloneDeviceIdentityComparison.DIFFERENT,
            CloneDeviceIdentitySupport.compare(
                templateSourceDeviceUniqueId = null,
                targetDeviceUniqueId = "314A323536384E171D00321700000000",
            ),
        )

        CloneDeviceIdentitySupport.requireDifferentDevice(
            templateSourceDeviceUniqueId = "314A323536384E171D00321700000000",
            targetDeviceUniqueId = null,
        )
        CloneDeviceIdentitySupport.requireDifferentDevice(
            templateSourceDeviceUniqueId = null,
            targetDeviceUniqueId = "314A323536384E171D00321700000000",
        )
    }

    @Test
    fun missingUidOnBothLegacyDevicesDoesNotBlockClone() {
        assertEquals(
            CloneDeviceIdentityComparison.UNAVAILABLE,
            CloneDeviceIdentitySupport.compare(
                templateSourceDeviceUniqueId = null,
                targetDeviceUniqueId = null,
            ),
        )

        CloneDeviceIdentitySupport.requireDifferentDevice(
            templateSourceDeviceUniqueId = null,
            targetDeviceUniqueId = null,
        )
    }
}
