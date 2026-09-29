# AI Visual Tutor

AI Visual Tutor is a Windows desktop application built with Kotlin and Compose Multiplatform designed to guide users step-by-step through desktop workflows (such as **Blender**, **PDF viewers**, and **Microsoft Excel**) using on-screen visual overlays and contextual screen capture.

---

## Current Status: Phase 1 & Phase 2 Implemented

The repository contains the complete implementation of **Phase 1** and **Phase 2**:

* **Phase 1 (Tutor UI & Live Visual Overlay):**
  * Collapsible side panel and floating dock (`TutorPanel`, `TutorDock`).
  * Hardcoded mock workflows for Blender (5 steps), PDF (4 steps), and Excel (4 steps).
  * True transparent, click-through live visual overlay (`OverlayWindow`) rendering on-screen step highlights and callout arrows while the underlying application remains visible and interactive.
  * Multi-monitor virtual desktop coverage and top-pinned interactive close control.
* **Phase 2 (Visual Context Acquisition):**
  * Full-window external screen capture using a native .NET C# bridge powered by `Windows.Graphics.Capture`.
  * Freeze-frame manual region selection tool (`RegionSelector`) with High-DPI coordinate scaling.
  * Screenshot preview window (`ScreenshotPreviewWindow`) with temporary file lifecycle cleanup.
  * Global capture hotkey (`Ctrl+Alt+F12`) registering system-wide capture triggers.
  * Standalone Windows distribution packaging (.exe and .msi) with bundled native dependencies.

> [!NOTE]
> **Phase 3 (AI, OCR, VLM, and Screen Reasoning) is NOT started.**
> All tutorial instructions and step highlights currently originate from mock workflows or manual user selections. No AI, OCR, or automated action verification has been implemented yet.

---

## Technology Stack

* **Language:** Kotlin 2.0.21 (JVM target)
* **UI Framework:** Compose Multiplatform for Desktop 1.7.0 (JetBrains Skiko / Skia)
* **Java Toolchain:** OpenJDK 17 (64-bit)
* **Build System:** Gradle 8.9 (via included `gradlew.bat` wrapper)
* **Native Capture Bridge:** .NET 10 targeting `net10.0-windows10.0.19041.0` with `WgcSharp` (`Windows.Graphics.Capture`)
* **Target Platform:** Windows 10 (Build 19041+) / Windows 11 (`win-x64`)

---

## Project Structure

```text
AIVisualTutor/
├── composeApp/                              # Main Kotlin/Compose Desktop application module
│   ├── src/
│   │   ├── main/kotlin/
│   │   │   ├── Main.kt                      # Application entry point and window lifecycle orchestrator
│   │   │   ├── bridge/                      # Native capture and hotkey interop
│   │   │   │   ├── CaptureBridge.kt         # Client invoking wgc-bridge.exe
│   │   │   │   └── GlobalCaptureHotkey.kt   # System-wide Ctrl+Alt+F12 hotkey listener
│   │   │   ├── models/                      # Immutable data models
│   │   │   │   ├── ApplicationType.kt       # Supported app enum (Blender, PDF, Excel)
│   │   │   │   ├── HighlightRegion.kt       # Screen coordinate model (x, y, w, h)
│   │   │   │   ├── TutorState.kt            # Central state data class
│   │   │   │   └── TutorStep.kt             # Individual tutorial step model
│   │   │   ├── overlay/                     # Live transparent visual overlay
│   │   │   │   ├── OverlayManager.kt        # Overlay visibility state manager
│   │   │   │   └── OverlayWindow.kt         # Fullscreen click-through transparent window
│   │   │   ├── selection/                   # Manual screenshot region selection
│   │   │   │   └── RegionSelector.kt        # Freeze-frame Robot capture & High-DPI scaling
│   │   │   ├── tutor/                       # State management and mock data
│   │   │   │   ├── MockTutorData.kt         # Hardcoded tutorial workflows
│   │   │   │   └── TutorController.kt       # Single source of truth for TutorState
│   │   │   └── ui/                          # UI composables
│   │   │       ├── ApplicationSelector.kt   # App switcher segmented control
│   │   │       ├── Arrow.kt                 # Callout bubble and directional arrow
│   │   │       ├── Highlight.kt             # Highlight rectangle canvas renderer
│   │   │       ├── InstructionCard.kt       # Step title, instruction, and description card
│   │   │       ├── ScreenshotPreviewWindow.kt # Captured screenshot viewer with cleanup
│   │   │       ├── TutorControls.kt         # Prev/Next, Pause/Resume, assistance dot
│   │   │       ├── TutorDock.kt             # Collapsed floating "AI TUTOR" pill
│   │   │       └── TutorPanel.kt            # Full scrollable tutor panel
│   │   └── test/kotlin/                     # Unit test suites
│   │       ├── overlay/OverlayManagerTest.kt
│   │       ├── selection/RegionSelectorGeometryTest.kt
│   │       └── tutor/TutorControllerTest.kt
│   └── build.gradle.kts                     # Compose Desktop module build definition
├── wgc-bridge/                              # C# Windows.Graphics.Capture native bridge
│   ├── Program.cs                           # Window enumeration & frame capture CLI
│   ├── wgc-bridge.csproj                    # .NET 10 project targeting Windows 10.0.19041.0
│   └── WgcSharp/                            # WinRT capture library
├── docs/                                    # Detailed project documentation
│   ├── INSTALLATION.md                      # Complete setup, build, and run guide
│   ├── REQUIREMENTS.md                      # System requirements and technical boundaries
│   ├── PHASE_1.md                           # Phase 1 UI and overlay architecture
│   └── PHASE_2.md                           # Phase 2 capture bridge and selection architecture
├── build.gradle.kts                         # Root Gradle build script
├── settings.gradle.kts                      # Gradle project settings
└── gradlew.bat                              # Windows Gradle wrapper script
```

---

## Quick Start

### Prerequisites
* Windows 10 (Build 19041+) or Windows 11 (64-bit).
* JDK 17 installed with `JAVA_HOME` configured.
* .NET SDK installed (supporting .NET 10 / Windows 10.0.19041.0).

### Running in Development Mode
```powershell
.\gradlew.bat run
```

### Running Unit Tests
```powershell
.\gradlew.bat composeApp:test
```

### Packaging Windows Installers (.exe & .msi)
```powershell
.\gradlew.bat composeApp:packageDistributionForCurrentOS
```
Packaged binaries will be output to:
* `composeApp/build/compose/binaries/main/exe/AIVisualTutor-1.0.0.exe`
* `composeApp/build/compose/binaries/main/msi/AIVisualTutor-1.0.0.msi`

---

## Documentation Links

For in-depth architecture, design decisions, and setup instructions, refer to the documentation in `docs/`:

* [Installation & Setup Guide](docs/INSTALLATION.md)
* [Technical Requirements & Boundaries](docs/REQUIREMENTS.md)
* [Phase 1 Specification: UI & Overlay Foundation](docs/PHASE_1.md)
* [Phase 2 Specification: Screen Capture & Selection](docs/PHASE_2.md)
