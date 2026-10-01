@file:Suppress("PackageName")

package com.SerialSlinger.openardf

internal object AndroidHeaderPresentation {
    fun appTitle(version: String): String = "SerialSlinger ${version.ifBlank { "unknown" }}:"
}
