import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync

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

    testImplementation(kotlin("test"))
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
