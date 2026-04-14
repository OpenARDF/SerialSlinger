plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

val serialSlingerAppVersion = "0.1.85"
require(serialSlingerAppVersion.startsWith("0.")) {
    "Expected a 0.x.y SerialSlinger app version for jpackage version remapping."
}
val serialSlingerPackageVersion = serialSlingerAppVersion.replaceFirst("0.", "1.")
val serialSlingerVendor = "OpenARDF"
val serialSlingerDescription = "Desktop configuration tool for SignalSlinger devices."

version = serialSlingerPackageVersion

extra["serialSlingerAppVersion"] = serialSlingerAppVersion
extra["serialSlingerPackageVersion"] = serialSlingerPackageVersion
extra["serialSlingerVendor"] = serialSlingerVendor
extra["serialSlingerDescription"] = serialSlingerDescription
