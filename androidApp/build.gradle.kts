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
        versionCode = 6
        versionName = rootProject.extra["serialSlingerDisplayVersion"].toString()
        buildConfigField("String", "PROJECT_URL", "\"${rootProject.extra["serialSlingerProjectUrl"]}\"")
        buildConfigField("String", "LICENSE_LABEL", "\"${rootProject.extra["serialSlingerLicenseLabel"]}\"")
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
}

tasks.register("printAndroidReleaseSigningStatus") {
    group = "help"
    description = "Prints whether the Android release signing inputs are available."

    doLast {
        if (hasCompleteReleaseSigningConfig) {
            logger.lifecycle("Android release signing is configured.")
        } else {
            logger.lifecycle(
                "Android release signing is not fully configured. " +
                    "Provide keystore.properties or SERIALSLINGER_UPLOAD_* environment variables.",
            )
        }
    }
}
