import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()

if (keystorePropertiesFile.isFile) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

fun signingValue(propertyName: String, environmentName: String): String? {
    return keystoreProperties.getProperty(propertyName)?.trim()?.takeIf(String::isNotEmpty)
        ?: providers.environmentVariable(environmentName).orNull?.trim()?.takeIf(String::isNotEmpty)
}

val releaseStoreFilePath = signingValue("storeFile", "SERIALSLINGER_UPLOAD_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "SERIALSLINGER_UPLOAD_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SERIALSLINGER_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SERIALSLINGER_UPLOAD_KEY_PASSWORD")

val hasCompleteReleaseSigningConfig =
    listOf(
        releaseStoreFilePath,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.SerialSlinger.openardf"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.SerialSlinger.openardf"
        minSdk = 24
        targetSdk = 37
        versionCode = 31
        versionName = rootProject.extra["serialSlingerDisplayVersion"].toString()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PROJECT_URL", "\"${rootProject.extra["serialSlingerProjectUrl"]}\"")
        buildConfigField("String", "LICENSE_LABEL", "\"${rootProject.extra["serialSlingerLicenseLabel"]}\"")
        buildConfigField("String", "LICENSE_URL", "\"${rootProject.extra["serialSlingerLicenseUrl"]}\"")
        buildConfigField("String", "BUILD_DATE_UTC", "\"${rootProject.extra["serialSlingerBuildDateUtc"]}\"")
    }

    signingConfigs {
        if (hasCompleteReleaseSigningConfig) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasCompleteReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // New warnings fail the build; the checked baseline is limited to the fixed-landscape
        // finding documented in docs/android-lint-waivers.md.
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
        // Release workflows run the complete lintRelease task explicitly. Disabling the smaller
        // implicit vital pass avoids its misleading baseline-variant notice during bundleRelease.
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core)
    implementation(project(":shared"))
    testImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

tasks.register("printAndroidReleaseSigningStatus") {
    group = "help"
    description = "Prints whether the Android release signing inputs are available."
    inputs.property("signingConfigured", hasCompleteReleaseSigningConfig)

    doLast {
        val signingConfigured = inputs.properties.getValue("signingConfigured") as Boolean
        if (signingConfigured) {
            logger.lifecycle("Android release signing is configured.")
        } else {
            logger.lifecycle(
                "Android release signing is not fully configured. " +
                    "Provide keystore.properties or SERIALSLINGER_UPLOAD_* environment variables.",
            )
        }
    }
}
