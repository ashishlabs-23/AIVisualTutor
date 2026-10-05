# AI Visual Tutor

AI Visual Tutor is a Windows desktop application built with Kotlin, Compose Multiplatform Desktop, and a .NET capture bridge. It combines guided tutorial UI and visual overlays with screen capture and a universal region-to-context pipeline.

## Project status

**Phases 1–3 are implemented. Phase 4 (Ambient-RAG, retrieval, and model-backed assistance) has not started.**

Phase 1 provides the tutor panel, dock, step controls, mock workflows, and transparent click-through guidance overlays. The existing mock workflow content includes Blender, PDF, and Excel examples.

Phase 2 adds whole-window capture through the Windows Graphics Capture bridge, a manual frozen-frame selector, screenshot preview, and the `Ctrl+Alt+F12` capture shortcut.

Phase 3 adds universal visual context acquisition. The region-selection pipeline does not depend on a particular foreground application: it records foreground-window metadata before opening the selector, freezes the desktop, lets the user select a rectangle, crops the frozen monitor images, and processes the crop into an in-memory `VisualContext`. OCR and content classification use replaceable placeholder implementations by default.

The desktop focus/cancellation lifecycle has code and headless regression coverage, including ESC key-down/key-up consumption and restoration of the previously foreground window. **Interactive ESC/focus restoration, real mouse selection, and multi-monitor/DPI behavior still require manual verification on a Windows desktop.** The repository does not claim those behaviors were manually verified.

## Features

- Tutor panel and collapsible dock with existing Phase 1 controls and example workflows.
- Transparent, click-through highlight and callout overlay.
- Global shortcuts, registered once and released on application shutdown:

  | Shortcut | Action |
  | --- | --- |
  | `Ctrl+Alt+F12` | Capture the previous external window (Phase 2) |
  | `Ctrl+Shift+Space` | Start universal region selection (Phase 3) |

- Full virtual-desktop selection overlay with drag selection in all four directions, ESC cancellation, and a minimum selection threshold of more than 8 captured pixels in both dimensions.
- Frozen per-monitor snapshots and crop mapping that accounts for monitor bounds, negative desktop origins, and per-monitor snapshot scale factors.
- Foreground application/window metadata captured before the overlay changes focus. Window titles are not written to lifecycle logs.
- `VisualContext` with region ID, captured image, desktop origin, image dimensions, timestamp, OCR/classification results, application context, and processing metadata.
- Asynchronous OCR/classification processing. The default OCR engine is `placeholder` and returns no text; the default classifier reports `UNKNOWN` unless replaced.
- Temporary PNG preview files are removed when the preview closes. Captured image data is otherwise held in memory.
- Lifecycle logs contain event names and safe metadata only; they exclude screenshot bytes, OCR text, and window titles.

## Architecture

```text
Global hotkey manager
        |
        v
Region selection controller <---- foreground-window snapshot/context
        |
        v
Fullscreen Compose selector <---- frozen per-monitor desktop snapshot
        |
        v
ScreenCaptureService (selected crop only)
        |
        v
ContextProcessor (OCR + content classifier)
        |
        v
VisualContext -> tutor session/events + screenshot preview
```

The selection controller owns the `IDLE -> SELECTING -> PROCESSING -> COMPLETED -> IDLE` lifecycle, with cancellation and failure paths returning to `IDLE`. Duplicate starts are rejected. On cancellation, ESC is consumed through key-up before teardown; after the overlay is removed and tutor visibility is restored, the app requests focus restoration to the captured foreground window. Native window operations are isolated behind `WindowFocusManager` for headless testing.

Selection and window placement use the desktop coordinate space reported by Java AWT; cropped image dimensions are capture pixels. The crop service maps each monitor tile independently and uses the highest intersecting monitor scale for the output image. Java 17 and mixed-DPI monitor behavior can vary by Windows/JDK configuration; validate exact boundaries on the target hardware.

## Technology

- Kotlin 2.0.21, JVM target 17
- Compose Multiplatform Desktop 1.7.0
- Gradle 8.9 wrapper
- .NET 10 Windows bridge targeting `net10.0-windows10.0.19041.0`
- Windows 10 build 19041+ or Windows 11, x64

## Run and test

Install JDK 17 and the .NET 10 SDK, then from PowerShell:

```powershell
$env:JAVA_HOME = "C:\Path\To\JDK17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :composeApp:run
```

Run the Kotlin unit tests:

```powershell
.\gradlew.bat clean composeApp:compileKotlin
.\gradlew.bat composeApp:test --rerun-tasks
```

The headless suite covers hotkey lifecycle, selection-state transitions and cancellation, geometry, frozen-crop mapping, multi-monitor/negative-origin math, processing degradation, asynchronous execution, privacy-safe logging, and existing Phase 1/2 regressions. The latest run recorded in this workspace passed **34 tests** with no failures or skips. This does not replace interactive desktop verification.

Build the bridge independently with:

```powershell
dotnet build wgc-bridge -c Release
```

For the Windows desktop acceptance checklist, see [Phase 3 verification](docs/PHASE_3_VERIFICATION.md) and [the results template](docs/PHASE_3_RESULTS_TEMPLATE.md). Run `scripts/verify-phase3.ps1` for local build, test, monitor, and temporary-file checks.

## Repository layout

```text
composeApp/src/main/kotlin/
  Main.kt                         # Compose windows and application wiring
  bridge/                         # Capture services, WGC bridge, global hotkeys
  context/                        # Selection lifecycle, app/window context, OCR and processing
  models/                         # Tutor contracts and captured-frame models
  overlay/                        # Transparent tutor overlay
  selection/                      # Frozen desktop snapshot and region selector
  tutor/                          # Tutor controller and mock tutorial content
  ui/                              # Tutor panel, dock, and screenshot preview
composeApp/src/test/kotlin/       # Headless unit and regression tests
wgc-bridge/                       # .NET 10 Windows capture bridge
scripts/                          # Verification helpers
docs/                             # Phase specifications and verification guides
```

## Documentation

- [Installation and setup](docs/INSTALLATION.md)
- [Requirements and boundaries](docs/REQUIREMENTS.md)
- [Phase 1: tutor UI and overlay](docs/PHASE_1.md)
- [Phase 2: capture and selection](docs/PHASE_2.md)
- [Phase 3: universal visual context acquisition](docs/PHASE_3.md)
- [Phase 3 Windows verification checklist](docs/PHASE_3_VERIFICATION.md)