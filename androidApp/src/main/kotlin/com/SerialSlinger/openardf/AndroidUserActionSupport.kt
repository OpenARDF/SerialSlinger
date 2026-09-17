@file:Suppress("PackageName")

package com.SerialSlinger.openardf

/** User-action entries describe controls, never the documents displayed inside them. */
internal fun androidTapDescription(label: String): String? {
    if (label.length > 160 || label.any { it == '\n' || it == '\r' }) return null
    return label.trim().takeIf { it.isNotEmpty() }?.let { "Tapped $it." }
}
