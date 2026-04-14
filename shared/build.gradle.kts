import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.jar.JarFile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

val desktopVersion = rootProject.extra["serialSlingerVersion"].toString()
val desktopPackageVendor = rootProject.extra["serialSlingerVendor"].toString()
val desktopPackageDescription = rootProject.extra["serialSlingerDescription"].toString()
val generatedDesktopVersionDir = layout.buildDirectory.dir("generated/source/desktopVersion/desktopMain/kotlin")

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
            kotlin.srcDir(generatedDesktopVersionDir)

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

val generateDesktopVersionSource = tasks.register("generateDesktopVersionSource") {
    description = "Generates desktop version constants from the Gradle build version."

    inputs.property("desktopVersion", desktopVersion)
    outputs.dir(generatedDesktopVersionDir)

    doLast {
        val outputFile =
            generatedDesktopVersionDir.get().file("com/openardf/serialslinger/app/SerialSlingerVersion.kt").asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package com.openardf.serialslinger.app

            object SerialSlingerVersion {
                const val displayVersion = "$desktopVersion"
                const val packageVersion = "$desktopVersion"
            }
            """.trimIndent(),
        )
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
val desktopPackagingIconDir = layout.projectDirectory.dir("packaging/icons")
val desktopJdeployBundleDir = layout.buildDirectory.dir("jdeploy")
val desktopJdeployLibsDir = desktopJdeployBundleDir.map { it.dir("libs") }
val desktopJdeployJarName = "SerialSlinger-jdeploy.jar"

desktopMainCompilation.compileTaskProvider.configure {
    dependsOn(generateDesktopVersionSource)
}

val packageInputDir = layout.buildDirectory.dir("packaging/input")
val packageOutputDir = layout.buildDirectory.dir("packaging/output")

fun currentOsName(): String = System.getProperty("os.name").lowercase()

fun isCurrentOs(vararg names: String): Boolean = names.any { currentOsName().contains(it.lowercase()) }

fun resolvedJpackageExecutable(): String {
    val executableName = if (isCurrentOs("windows")) "jpackage.exe" else "jpackage"
    return File(System.getProperty("java.home"), "bin/$executableName").absolutePath
}

fun resolvedJpackageFile(): File = File(resolvedJpackageExecutable())

fun resolvedDesktopPackagingIcon(): File? {
    val iconFile =
        when {
            isCurrentOs("mac") -> desktopPackagingIconDir.file("SerialSlinger.icns").asFile
            isCurrentOs("windows") -> desktopPackagingIconDir.file("SerialSlinger.ico").asFile
            else -> desktopPackagingIconDir.file("SerialSlinger.png").asFile
        }

    return iconFile.takeIf(File::isFile)
}

fun ensureCompatiblePackagingOs(vararg names: String) {
    require(isCurrentOs(*names)) {
        "This packaging task must run on ${names.joinToString(" or ")}."
    }
}

fun desktopPackagingBaseArgs(
    inputDir: File,
    outputDir: File,
): List<String> =
    buildList {
        addAll(
            listOf(
                resolvedJpackageExecutable(),
                "--name",
                desktopAppName,
                "--app-version",
                desktopVersion,
                "--vendor",
                desktopPackageVendor,
                "--description",
                desktopPackageDescription,
                "--dest",
                outputDir.absolutePath,
                "--input",
                inputDir.absolutePath,
                "--main-jar",
                tasks.named("desktopJar").get().outputs.files.singleFile.name,
                "--main-class",
                desktopMainClass,
            ),
        )

        resolvedDesktopPackagingIcon()?.let { iconFile ->
            addAll(
                listOf(
                    "--icon",
                    iconFile.absolutePath,
                ),
            )
        }

        if (isCurrentOs("mac")) {
            addAll(
                listOf(
                    "--java-options",
                    "-Dapple.awt.application.name=$desktopAppName",
                ),
            )
        }
    }

tasks.register("verifyDesktopPackagingEnvironment") {
    group = "distribution"
    description = "Verifies that the current JDK includes jpackage for desktop packaging."

    doLast {
        val jpackageFile = resolvedJpackageFile()
        require(jpackageFile.isFile) {
            "jpackage executable was not found at ${jpackageFile.absolutePath}. Use a full JDK that includes jpackage."
        }
        require(jpackageFile.canExecute()) {
            "jpackage executable exists but is not executable: ${jpackageFile.absolutePath}"
        }

        logger.lifecycle("Using jpackage at ${jpackageFile.absolutePath}")
        logger.lifecycle("Packaging SerialSlinger version $desktopVersion")
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

val stageDesktopJdeployLibs = tasks.register<Sync>("stageDesktopJdeployLibs") {
    group = "distribution"
    description = "Stages desktop runtime dependencies for the SerialSlinger jDeploy bundle."

    from(desktopMainCompilation.runtimeDependencyFiles)
    into(desktopJdeployLibsDir)
}

val desktopJdeployJar = tasks.register<Jar>("desktopJdeployJar") {
    group = "distribution"
    description = "Builds an executable desktop jar for jDeploy."

    dependsOn(tasks.named("desktopJar"))
    dependsOn(stageDesktopJdeployLibs)

    val desktopJarTask = tasks.named<Jar>("desktopJar")
    val runtimeClasspathEntriesProvider =
        desktopMainCompilation.runtimeDependencyFiles.elements.map { elements ->
            elements
                .map { "libs/${it.asFile.name}" }
                .sorted()
                .joinToString(" ")
        }

    archiveFileName.set(desktopJdeployJarName)
    destinationDirectory.set(desktopJdeployBundleDir)

    from(desktopJarTask.map { zipTree(it.archiveFile) })

    manifest {
        attributes(
            "Main-Class" to desktopMainClass,
            "Class-Path" to runtimeClasspathEntriesProvider.get(),
            "Implementation-Title" to desktopAppName,
            "Implementation-Version" to desktopVersion,
        )
    }
}

tasks.register("prepareDesktopJdeployBundle") {
    group = "distribution"
    description = "Prepares the executable jar and dependency layout needed by jDeploy."

    dependsOn(stageDesktopJdeployLibs)
    dependsOn(desktopJdeployJar)
}

tasks.register("verifyDesktopJdeployBundle") {
    group = "distribution"
    description = "Verifies that the generated SerialSlinger jDeploy jar is executable and references staged dependencies."

    dependsOn(tasks.named("prepareDesktopJdeployBundle"))

    doLast {
        val jarFile = desktopJdeployJar.get().archiveFile.get().asFile
        require(jarFile.isFile) {
            "Expected jDeploy jar at ${jarFile.absolutePath}"
        }

        JarFile(jarFile).use { archive ->
            val manifest = archive.manifest ?: error("Generated jDeploy jar is missing a manifest.")
            val attributes = manifest.mainAttributes
            val mainClass = attributes.getValue("Main-Class")
            require(mainClass == desktopMainClass) {
                "Expected Main-Class=$desktopMainClass but found ${mainClass ?: "<missing>"}"
            }

            val classPathEntries =
                attributes
                    .getValue("Class-Path")
                    ?.split(' ')
                    ?.filter(String::isNotBlank)
                    .orEmpty()
            require(classPathEntries.isNotEmpty()) {
                "Generated jDeploy jar is missing a Class-Path manifest entry."
            }

            classPathEntries.forEach { relativePath ->
                val dependencyFile = jarFile.parentFile.resolve(relativePath)
                require(dependencyFile.isFile) {
                    "Class-Path entry $relativePath does not exist next to the generated jDeploy jar."
                }
            }
        }
    }
}

tasks.register<Exec>("desktopAppImage") {
    group = "distribution"
    description = "Builds a macOS app image for SerialSlinger using jpackage."

    dependsOn(tasks.named("stageDesktopPackageInput"))
    dependsOn(tasks.named("verifyDesktopPackagingEnvironment"))

    val inputDir = packageInputDir.get().asFile
    val outputDir = packageOutputDir.get().asFile

    doFirst {
        ensureCompatiblePackagingOs("mac")
        outputDir.mkdirs()
    }

    commandLine(
        *desktopPackagingBaseArgs(inputDir, outputDir).toTypedArray(),
        "--type",
        "app-image",
    )
}

tasks.register<Exec>("desktopDmg") {
    group = "distribution"
    description = "Builds an unsigned macOS .dmg for SerialSlinger using jpackage."

    dependsOn(tasks.named("stageDesktopPackageInput"))
    dependsOn(tasks.named("verifyDesktopPackagingEnvironment"))

    val inputDir = packageInputDir.get().asFile
    val outputDir = packageOutputDir.get().asFile

    doFirst {
        ensureCompatiblePackagingOs("mac")
        outputDir.mkdirs()
    }

    commandLine(
        *desktopPackagingBaseArgs(inputDir, outputDir).toTypedArray(),
        "--type",
        "dmg",
    )
}

tasks.register<Exec>("desktopExe") {
    group = "distribution"
    description = "Builds a Windows .exe installer for SerialSlinger using jpackage."

    dependsOn(tasks.named("stageDesktopPackageInput"))
    dependsOn(tasks.named("verifyDesktopPackagingEnvironment"))

    val inputDir = packageInputDir.get().asFile
    val outputDir = packageOutputDir.get().asFile

    doFirst {
        ensureCompatiblePackagingOs("windows")
        outputDir.mkdirs()
    }

    commandLine(
        *desktopPackagingBaseArgs(inputDir, outputDir).toTypedArray(),
        "--type",
        "exe",
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut",
    )
}

tasks.register<Exec>("desktopMsi") {
    group = "distribution"
    description = "Builds a Windows .msi installer for SerialSlinger using jpackage."

    dependsOn(tasks.named("stageDesktopPackageInput"))
    dependsOn(tasks.named("verifyDesktopPackagingEnvironment"))

    val inputDir = packageInputDir.get().asFile
    val outputDir = packageOutputDir.get().asFile

    doFirst {
        ensureCompatiblePackagingOs("windows")
        outputDir.mkdirs()
    }

    commandLine(
        *desktopPackagingBaseArgs(inputDir, outputDir).toTypedArray(),
        "--type",
        "msi",
        "--win-dir-chooser",
        "--win-menu",
        "--win-shortcut",
    )
}
