plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

val serialSlingerVersion = "1.0.91"
// Bump this suffix for each unreleased testable batch, then clear it before a full release.
val serialSlingerVersionSuffix = ""
val serialSlingerDisplayVersion = serialSlingerVersion + serialSlingerVersionSuffix
val serialSlingerVendor = "OpenARDF"
val serialSlingerDescription = "Desktop configuration tool for SignalSlinger devices."
val serialSlingerProjectUrl = "https://github.com/OpenARDF/SerialSlinger"
val serialSlingerLicenseLabel = "MIT License"
val serialSlingerBuildDateUtc = java.time.Instant.now().toString()

version = serialSlingerVersion

extra["serialSlingerVersion"] = serialSlingerVersion
extra["serialSlingerDisplayVersion"] = serialSlingerDisplayVersion
extra["serialSlingerVendor"] = serialSlingerVendor
extra["serialSlingerDescription"] = serialSlingerDescription
extra["serialSlingerProjectUrl"] = serialSlingerProjectUrl
extra["serialSlingerLicenseLabel"] = serialSlingerLicenseLabel
extra["serialSlingerBuildDateUtc"] = serialSlingerBuildDateUtc
