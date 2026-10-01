package com.openardf.serialslinger.app

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopInstalledPackageSmokeTest {
    @Test
    fun writesVersionedEvidenceOnlyWhenExplicitlyRequested() {
        val directory = Files.createTempDirectory("serialslinger-installed-smoke-")
        try {
            val evidence = directory.resolve("nested/evidence.txt")
            assertFalse(DesktopInstalledPackageSmoke.runIfRequested(emptyArray()))
            assertTrue(
                DesktopInstalledPackageSmoke.runIfRequested(
                    arrayOf("--installed-package-smoke", evidence.toString()),
                ),
            )
            val text = Files.readString(evidence)
            assertTrue(text.contains("SerialSlinger installed package smoke"))
            assertTrue(text.contains("packageVersion=${SerialSlingerVersion.packageVersion}"))
            assertTrue(text.contains("displayVersion=${SerialSlingerVersion.displayVersion}"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
