import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import java.io.File

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

val desktopTarget = kotlin.targets.getByName("desktop") as KotlinJvmTarget
val desktopMainCompilation = desktopTarget.compilations.getByName("main")
val desktopMainClass = "com.openardf.serialslinger.app.SerialSlingerDesktopAppKt"
val desktopAppName = "SerialSlinger"
val desktopPackageVersion = rootProject.version.toString()

val packageInputDir = layout.buildDirectory.dir("packaging/input")
val packageOutputDir = layout.buildDirectory.dir("packaging/output")

fun currentOsName(): String = System.getProperty("os.name").lowercase()

fun isCurrentOs(vararg names: String): Boolean = names.any { currentOsName().contains(it.lowercase()) }

fun resolvedJpackageExecutable(): String {
    val executableName = if (isCurrentOs("windows")) "jpackage.exe" else "jpackage"
    return File(System.getProperty("java.home"), "bin/$executableName").absolutePath
}

fun ensureCompatiblePackagingOs(vararg names: String) {
    require(isCurrentOs(*names)) {
        "This packaging task must run on ${names.joinToString(" or ")}."
    }
}

tasks.register<Sync>("stageDesktopPackageInput") {
    group = "distribution"
    description = "Stages jars for SerialSlinger desktop packaging."

    dependsOn(tasks.named("desktopJar"))

    from(desktopMainCompilation.runtimeDependencyFiles)
    from(tasks.named("desktopJar"))
    into(packageInputDir)
}

tasks.register<Exec>("desktopAppImage") {
    group = "distribution"
    description = "Builds a macOS app image for SerialSlinger using jpackage."

    dependsOn(tasks.named("stageDesktopPackageInput"))

    val inputDir = packageInputDir.get().asFile
    val outputDir = packageOutputDir.get().asFile

    doFirst {
        ensureCompatiblePackagingOs("mac")
        outputDir.mkdirs()
    }

    commandLine(
        resolvedJpackageExecutable(),
        "--type",
        "app-image",
        "--name",
        desktopAppName,
        "--app-version",
        desktopPackageVersion,
        "--dest",
        outputDir.absolutePath,
        "--input",
        inputDir.absolutePath,
        "--main-jar",
        tasks.named("desktopJar").get().outputs.files.singleFile.name,
        "--main-class",
        desktopMainClass,
        "--java-options",
        "-Dapple.awt.application.name=$desktopAppName",
    )
}

tasks.register<Exec>("desktopDmg") {
    group = "distribution"
    description = "Builds an unsigned macOS .dmg for SerialSlinger using jpackage."

    dependsOn(tasks.named("stageDesktopPackageInput"))

    val inputDir = packageInputDir.get().asFile
    val outputDir = packageOutputDir.get().asFile

    doFirst {
        ensureCompatiblePackagingOs("mac")
        outputDir.mkdirs()
    }

    commandLine(
        resolvedJpackageExecutable(),
        "--type",
        "dmg",
        "--name",
        desktopAppName,
        "--app-version",
        desktopPackageVersion,
        "--dest",
        outputDir.absolutePath,
        "--input",
        inputDir.absolutePath,
        "--main-jar",
        tasks.named("desktopJar").get().outputs.files.singleFile.name,
        "--main-class",
        desktopMainClass,
        "--java-options",
        "-Dapple.awt.application.name=$desktopAppName",
    )
}

tasks.register<Exec>("desktopExe") {
    group = "distribution"
    description = "Builds a Windows .exe installer for SerialSlinger using jpackage."

    dependsOn(tasks.named("stageDesktopPackageInput"))

    val inputDir = packageInputDir.get().asFile
    val outputDir = packageOutputDir.get().asFile

    doFirst {
        ensureCompatiblePackagingOs("windows")
        outputDir.mkdirs()
    }

    commandLine(
        resolvedJpackageExecutable(),
        "--type",
        "exe",
        "--name",
        desktopAppName,
        "--app-version",
        desktopPackageVersion,
        "--dest",
        outputDir.absolutePath,
        "--input",
        inputDir.absolutePath,
        "--main-jar",
        tasks.named("desktopJar").get().outputs.files.singleFile.name,
        "--main-class",
        desktopMainClass,
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut",
    )
}

tasks.register<Exec>("desktopMsi") {
    group = "distribution"
    description = "Builds a Windows .msi installer for SerialSlinger using jpackage."

    dependsOn(tasks.named("stageDesktopPackageInput"))

    val inputDir = packageInputDir.get().asFile
    val outputDir = packageOutputDir.get().asFile

    doFirst {
        ensureCompatiblePackagingOs("windows")
        outputDir.mkdirs()
    }

    commandLine(
        resolvedJpackageExecutable(),
        "--type",
        "msi",
        "--name",
        desktopAppName,
        "--app-version",
        desktopPackageVersion,
        "--dest",
        outputDir.absolutePath,
        "--input",
        inputDir.absolutePath,
        "--main-jar",
        tasks.named("desktopJar").get().outputs.files.singleFile.name,
        "--main-class",
        desktopMainClass,
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut",
    )
}
