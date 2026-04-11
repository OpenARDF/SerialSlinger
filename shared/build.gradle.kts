import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm("desktop")
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        val desktopMain by getting {
            dependencies {
                implementation(libs.jserialcomm)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.register<JavaExec>("desktopSmokeRun") {
    group = "application"
    description = "Runs the SerialSlinger desktop smoke-test CLI."

    val desktopTarget = kotlin.targets.getByName("desktop") as KotlinJvmTarget
    val mainCompilation = desktopTarget.compilations.getByName("main")

    dependsOn(mainCompilation.compileTaskProvider)
    classpath(mainCompilation.runtimeDependencyFiles, mainCompilation.output.allOutputs)
    mainClass.set("com.openardf.serialslinger.cli.DesktopSmokeCliKt")
}

tasks.register<JavaExec>("desktopAppRun") {
    group = "application"
    description = "Runs the SerialSlinger desktop UI."

    val desktopTarget = kotlin.targets.getByName("desktop") as KotlinJvmTarget
    val mainCompilation = desktopTarget.compilations.getByName("main")

    dependsOn(mainCompilation.compileTaskProvider)
    classpath(mainCompilation.runtimeDependencyFiles, mainCompilation.output.allOutputs)
    mainClass.set("com.openardf.serialslinger.app.SerialSlingerDesktopAppKt")
}
