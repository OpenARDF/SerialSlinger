plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

val serialSlingerVersion = "1.0.90"
// Bump this suffix for each unreleased testable batch, then clear it before a full release.
val serialSlingerVersionSuffix = ""
val serialSlingerDisplayVersion = serialSlingerVersion + serialSlingerVersionSuffix
val serialSlingerVendor = "OpenARDF"
val serialSlingerDescription = "Desktop configuration tool for SignalSlinger devices."

version = serialSlingerVersion

extra["serialSlingerVersion"] = serialSlingerVersion
extra["serialSlingerDisplayVersion"] = serialSlingerDisplayVersion
extra["serialSlingerVendor"] = serialSlingerVendor
extra["serialSlingerDescription"] = serialSlingerDescription
