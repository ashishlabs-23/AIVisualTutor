# Installation & Developer Setup Guide

This guide walks you through setting up your development environment, building, running, and packaging **AI Visual Tutor** from source.

---

## 1. System Requirements

* **Operating System:** Windows 10 (Version 2004 / Build 19041 or later) or Windows 11 (64-bit).
* **Architecture:** `win-x64`
* **Shell:** PowerShell 5.1 or later

---

## 2. Required Software & SDKs

Before building the project, ensure the following tools are installed on your workstation:

### A. Java Development Kit (JDK 17)
* **Version:** JDK 17 (64-bit).
* **Recommended Distributions:** [Microsoft Build of OpenJDK 17](https://learn.microsoft.com/en-us/java/openjdk/download) or [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17).
* **Environment Variable:** Set `JAVA_HOME` to your JDK 17 root directory and ensure `%JAVA_HOME%\bin` is in your `PATH`.
  ```powershell
  # Verify in PowerShell:
  java -version
  ```

### B. .NET SDK
* **Version:** .NET SDK 10 (or .NET SDK supporting `net10.0-windows10.0.19041.0`).
* The .NET SDK is used to compile the native `wgc-bridge` executable for Windows.Graphics.Capture.
  ```powershell
  # Verify in PowerShell:
  dotnet --version
  ```

### C. IDE (Recommended)
* **IntelliJ IDEA** (Community or Ultimate edition) with the Kotlin plugin (bundled by default).

---

## 3. Cloning & Opening the Project

1. Clone the repository to a local directory:
   ```powershell
   git clone <repository-url> AIVisualTutor
   cd AIVisualTutor
   ```
2. Open IntelliJ IDEA:
   * Select **File $\rightarrow$ Open...** and select the `AIVisualTutor` root folder (where `settings.gradle.kts` is located).
   * Allow Gradle to sync dependencies. If prompted for the Gradle JVM, point it to your JDK 17 installation.

---

## 4. Building & Running

The project includes the Gradle Wrapper (`gradlew.bat` for Windows, pinned to Gradle 8.9).

### Running Locally in Development Mode
To launch the Compose Desktop application directly:
```powershell
.\gradlew.bat run
```
*(Or in IntelliJ: Run the `composeApp [run]` Gradle task).*

### Running Unit Tests
To execute all deterministic unit tests across geometry, state, and bounds:
```powershell
.\gradlew.bat composeApp:test
```

### Packaging Standalone Windows Installers (.exe & .msi)
To build the complete standalone Windows distribution (including automated publishing and bundling of `wgc-bridge.exe`):
```powershell
.\gradlew.bat composeApp:packageDistributionForCurrentOS
```
The packaged installers will be generated in:
* **EXE Installer:** `composeApp/build/compose/binaries/main/exe/AIVisualTutor-1.0.0.exe`
* **MSI Installer:** `composeApp/build/compose/binaries/main/msi/AIVisualTutor-1.0.0.msi`

---

## 5. Building the Native WGC Bridge Independently (Optional)

Gradle automatically publishes and stages the bridge during packaging via `publishWgcBridge`. If you need to build the C# capture bridge manually:
```powershell
dotnet publish wgc-bridge/wgc-bridge.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -o wgc-bridge/bin/Release/net10.0-windows10.0.19041.0/win-x64/publish
```
The resulting executable will be placed at `wgc-bridge/bin/Release/net10.0-windows10.0.19041.0/win-x64/publish/wgc-bridge.exe`.
