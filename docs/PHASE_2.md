# Phase 2: Visual Context Acquisition & Windows Capture

Phase 2 implements on-demand visual context acquisition for **AI Visual Tutor**, enabling window capture, manual region selection, screenshot preview, and global capture shortcuts without continuous screen recording.

---

## 1. Architectural Overview

```text
                                User Action
        ┌────────────────────────────┼────────────────────────────┐
        ▼                            ▼                            ▼
  Tutor Panel                Global Shortcut             Manual Region
 ("Capture Screen")           (Ctrl+Alt+F12)               Selection
        │                            │                            │
        └──────────────────┬─────────┘                            │
                           ▼                                      ▼
                  CaptureBridge.kt                        RegionSelector.kt
                 (Kotlin JVM Client)                     (AWT Robot Freeze)
                           │                                      │
                           ▼                                      │
                   wgc-bridge.exe                                 │
              (C# Windows.Graphics.Capture)                       │
                           │                                      │
                           └──────────────────┬───────────────────┘
                                              ▼
                                    Temporary PNG Storage
                                       (%TEMP%/AIVT_*.png)
                                              │
                                              ▼
                                 ScreenshotPreviewWindow.kt
                                   (Display & Safe Cleanup)
```

---

## 2. Key Components

### A. Windows.Graphics.Capture Native Bridge (`wgc-bridge/`)
* **Technology:** C# executable built on .NET 10 targeting `net10.0-windows10.0.19041.0`, leveraging `WgcSharp` for interop with `Windows.Graphics.Capture`.
* **Modes of Operation:**
  1. `--capture-previous-window --caller-pid <PID>`: Enumerates top-level Z-ordered windows, finds the topmost visible external application window outside the caller process, captures one frame, saves it as a PNG in `%TEMP%`, and writes the absolute path to `stdout`.
  2. `--capture-region --x <X> --y <Y> --width <W> --height <H>`: Captures a specific bounding rectangle across the virtual screen.
* **Exit Codes:**
  * `0`: Success (valid PNG path written to `stdout`).
  * `1`: Invalid arguments.
  * `2`: No suitable external window found.
  * `3`: Capture pipeline failure.
  * `4`: Output / PNG writing failure.
  * `5`: Unexpected error.

### B. Kotlin Bridge Client (`bridge/CaptureBridge.kt`)
* Asynchronously invokes `wgc-bridge.exe` using Kotlin Coroutines on `Dispatchers.IO`.
* Robustly discovers the bridge binary across:
  * Packaged Compose application resource directory (`compose.application.resources.dir`).
  * Code source location parents.
  * Project-relative `Release` and `Debug` build paths.
* Validates PNG magic bytes signature (`0x89, 0x50, 0x4E, 0x47, ...`) and builds diagnostic failure messages from `stdout`/`stderr`.

### C. Screenshot-Backed Manual Region Selector (`selection/RegionSelector.kt`)
* **Lifecycle:**
  1. Hides tutor windows to avoid capturing the tutor UI.
  2. Takes a multi-resolution freeze-frame snapshot via `Robot.createMultiResolutionScreenCapture(bounds)`.
  3. Displays the frozen image in a fullscreen borderless window (`RegionSelectorWindow`).
  4. Tracks drag gestures to draw a live green selection rectangle while dimming unselected areas.
  5. Crops the physical bitmap and writes it to a temporary PNG file.
  6. **High-DPI Coordinate Conversion:** Converts physical pixel crop coordinates to Compose logical DIPs (`crop.x / snapshot.scaleX`, `crop.y / snapshot.scaleY`) and passes the resulting `HighlightRegion` to `TutorController`.
  7. **Guaranteed Window Restoration:** Restores tutor window visibility across all completion and exit paths (normal selection, Cancel button, Esc key, <4px selection, or unexpected disposal).

### D. Screenshot Preview & Lifecycle Management (`ui/ScreenshotPreviewWindow.kt`)
* Opens a dedicated preview window displaying the captured image.
* Decodes the PNG using Skia (`Image.makeFromEncoded`).
* Automatically deletes the temporary PNG file from `%TEMP%` when the preview is dismissed.

### E. Global Capture Hotkey (`bridge/GlobalCaptureHotkey.kt`)
* Registers a system-wide `Ctrl+Alt+F12` hotkey on a dedicated background thread running a Win32 message pump (`RegisterHotKey`, `GetMessage`).
* Dispatches capture requests to `CaptureBridge.capturePreviousWindow()` whenever the shortcut is pressed, even when the tutor window is not focused.

### F. Automated Build & Distribution Packaging (`composeApp/build.gradle.kts`)
* Custom Gradle tasks `publishWgcBridge` and `stageWgcBridgeForDistribution` automatically compile the C# bridge into a self-contained single-file executable and stage it into `common/wgc-bridge/` for embedding in `.msi` and `.exe` distributions.

---

## 3. Scope Boundaries & Next Phase

* **Phase 2 Status:** Implemented, compiled, unit-tested, and packaged into standalone Windows distributions.
* **Phase 3 (Not Started):**
  * Automated screen reasoning (OCR, Vision-Language Models, RAG).
  * Semantic screen element identification.
  * Real-time user action verification and adaptive learning loops.
