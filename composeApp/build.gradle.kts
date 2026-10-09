import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "com.aivisualtutor"
version = "0.1.0-phase1"

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Pulls in the correct Compose Desktop artifacts for whichever OS
    // Gradle is running on (Windows in your case).
    implementation(compose.desktop.currentOs)
    implementation("net.sourceforge.tess4j:tess4j:5.20.0") {
        // This app OCRs in-memory screenshots; PDF conversion support is unused.
        exclude(group = "org.apache.pdfbox", module = "pdfbox")
        exclude(group = "org.apache.pdfbox", module = "pdfbox-tools")
        exclude(group = "org.apache.pdfbox", module = "jbig2-imageio")
    }

    testImplementation(kotlin("test"))
    testImplementation("org.slf4j:slf4j-nop:2.0.16")
}

val bridgePublishDirectory = rootProject.layout.projectDirectory.dir(
    "wgc-bridge/bin/Release/net10.0-windows10.0.19041.0/win-x64/publish"
)
val bridgeApplicationResources = layout.buildDirectory.dir("composeAppResources")

val publishWgcBridge by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Publishes the self-contained Windows WGC bridge for application packaging."
    workingDir(rootProject.projectDir)
    commandLine(
        "dotnet",
        "publish",
        "wgc-bridge/wgc-bridge.csproj",
        "-c", "Release",
        "-r", "win-x64",
        "--self-contained", "true",
        "-p:PublishSingleFile=true",
        "-o", bridgePublishDirectory.asFile.absolutePath
    )
}

val stageWgcBridgeForDistribution by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the published WGC bridge in Compose Desktop application resources."
    dependsOn(publishWgcBridge)
    from(bridgePublishDirectory.file("wgc-bridge.exe"))
    into(bridgeApplicationResources.map { it.dir("common/wgc-bridge") })
}

compose.desktop {
    application {
        mainClass = "MainKt"
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            jvmArgs("-Dskiko.renderApi=OPENGL")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AIVisualTutor"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(bridgeApplicationResources)
        }
    }
}

tasks.configureEach {
    if (name == "prepareAppResources") {
        dependsOn(stageWgcBridgeForDistribution)
    }
}

val projectJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(17))
}

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(projectJavaLauncher)
    if (project.findProperty("aivt.calibration.enabled")?.toString()?.equals("true", ignoreCase = true) == true) {
        systemProperty("aivt.calibration.enabled", "true")
        project.findProperty("aivt.calibration.outputDir")?.toString()?.let {
            systemProperty("aivt.calibration.outputDir", it)
        }
    }
    listOf(
        "aivt.visual.enabled",
        "aivt.visual.executable",
        "aivt.visual.model",
        "aivt.visual.mmproj",
        "aivt.visual.timeoutMillis",
        "aivt.visual.runtimeVersion"
    ).forEach { propertyName ->
        project.findProperty(propertyName)?.toString()?.let {
            systemProperty(propertyName, it)
        }
    }
}

// CALIBRATION_HARNESS_TASK
tasks.register<JavaExec>("runCalibrationHarness") {
    group = "research"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("calibration.CalibrationHarnessKt")
    when (project.findProperty("aivt.calibration.mode")?.toString()) {
        "visual-preflight" -> {
            args(
                "--visual-preflight",
                project.property("aivt.visualPreflight.caseId"),
                project.property("aivt.visualPreflight.targetDescription"),
                project.property("aivt.visualPreflight.crop")
            )
        }

        "replay-saved" -> {
            args(
                "--replay-saved",
                project.property("aivt.calibration.manifest"),
                project.property("aivt.calibration.cropDir"),
                project.property("aivt.calibration.existingResultsDir")
            )
        }
        else -> if (project.hasProperty("calibManifest")) {
            args(project.property("calibManifest"), project.property("calibOut"))
        }
    }
}

tasks.register<JavaExec>("runPhase5Evaluation") {
    group = "research"
    description = "Run every Phase 5 grounding mode on the independently annotated CSV cases."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("context.Phase5EvaluationKt")
    doFirst {
        val annotations = project.findProperty("aivt.phase5.annotations")?.toString()
            ?: throw GradleException("Set -Paivt.phase5.annotations=<dataset.csv>.")
        val report = project.findProperty("aivt.phase5.report")?.toString()
            ?: throw GradleException("Set -Paivt.phase5.report=<report.json>.")
        setArgs(listOf("--annotations", annotations, report))
    }
}
