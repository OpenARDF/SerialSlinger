plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

val serialSlingerVersion = "1.0.99"
// Bump this suffix for every new testable change set so desktop and Android builds expose
// an unmistakable version string during iterative testing. Clear it before a full release.
val serialSlingerVersionSuffix = ""
val serialSlingerDisplayVersion = serialSlingerVersion + serialSlingerVersionSuffix
val serialSlingerPackageVersion =
    if (serialSlingerVersionSuffix.isBlank()) {
        serialSlingerVersion
    } else {
        "$serialSlingerVersion-$serialSlingerVersionSuffix"
    }
val serialSlingerVendor = "OpenARDF"
val serialSlingerDescription = "Desktop configuration tool for SignalSlinger devices."
val serialSlingerProjectUrl = "https://github.com/OpenARDF/SerialSlinger"
val serialSlingerLicenseLabel = "MIT License"
val serialSlingerBuildDateUtc = java.time.Instant.now().toString()

version = serialSlingerPackageVersion

extra["serialSlingerVersion"] = serialSlingerVersion
extra["serialSlingerDisplayVersion"] = serialSlingerDisplayVersion
extra["serialSlingerPackageVersion"] = serialSlingerPackageVersion
extra["serialSlingerVendor"] = serialSlingerVendor
extra["serialSlingerDescription"] = serialSlingerDescription
extra["serialSlingerProjectUrl"] = serialSlingerProjectUrl
extra["serialSlingerLicenseLabel"] = serialSlingerLicenseLabel
extra["serialSlingerBuildDateUtc"] = serialSlingerBuildDateUtc
