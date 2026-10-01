package com.openardf.serialslinger.app

import java.nio.file.Files
import java.nio.file.Path

internal object DesktopInstalledPackageSmoke {
    private const val ARGUMENT = "--installed-package-smoke"

    fun runIfRequested(args: Array<String>): Boolean {
        if (args.firstOrNull() != ARGUMENT) {
            return false
        }
        require(args.size == 2) { "$ARGUMENT requires exactly one evidence-file path." }

        val evidencePath = Path.of(args[1]).toAbsolutePath().normalize()
        evidencePath.parent?.let(Files::createDirectories)
        // Keep this probe independent of serial hardware and user configuration. Reaching this
        // point proves the installed launcher, application JAR, dependencies, and generated
        // version source are all usable on the host platform.
        Files.writeString(
            evidencePath,
            buildString {
                appendLine("SerialSlinger installed package smoke")
                appendLine("packageVersion=${SerialSlingerVersion.packageVersion}")
                appendLine("displayVersion=${SerialSlingerVersion.displayVersion}")
                appendLine("javaVersion=${System.getProperty("java.version")}")
                appendLine("osName=${System.getProperty("os.name")}")
            },
        )
        return true
    }
}
