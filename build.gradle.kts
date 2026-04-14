plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}

val serialSlingerVersion = "1.0.85"
val serialSlingerVendor = "OpenARDF"
val serialSlingerDescription = "Desktop configuration tool for SignalSlinger devices."

version = serialSlingerVersion

extra["serialSlingerVersion"] = serialSlingerVersion
extra["serialSlingerVendor"] = serialSlingerVendor
extra["serialSlingerDescription"] = serialSlingerDescription
