package com.openardf.serialslinger.model

import com.openardf.serialslinger.session.deviceIdentityLabel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloneDeviceIdentitySupportTest {
    @Test
    fun factoryIdsWithIdenticalZeroSuffixesRemainDistinctAndRecognizable() {
        val fox4 = "42348279800081200106014200000000"
        val fox2 = "42348279800053200112011900000000"
        CloneDeviceIdentitySupport.requireDifferentDevice(fox4, fox2)
        val error = assertFailsWith<IllegalStateException> {
            CloneDeviceIdentitySupport.requireDifferentDevice(fox2, fox2)
        }
        assertTrue(error.message.orEmpty().contains(fox2))
        val labels = listOf(fox4, fox2).map {
            com.openardf.serialslinger.session.DeviceIdentityObservation(true, it)
        }.map { observation ->
            with(observation) { deviceIdentityLabel() }
        }
        assertEquals(2, labels.toSet().size)
        assertTrue(labels[0].contains(fox4))
    }
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
