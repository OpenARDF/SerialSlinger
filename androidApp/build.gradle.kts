import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.openardf.serialslinger.androidapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openardf.serialslinger.androidapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = rootProject.extra["serialSlingerDisplayVersion"].toString()
        buildConfigField("String", "PROJECT_URL", "\"${rootProject.extra["serialSlingerProjectUrl"]}\"")
        buildConfigField("String", "LICENSE_LABEL", "\"${rootProject.extra["serialSlingerLicenseLabel"]}\"")
        buildConfigField("String", "BUILD_DATE_UTC", "\"${rootProject.extra["serialSlingerBuildDateUtc"]}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
}
