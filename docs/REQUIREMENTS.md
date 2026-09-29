# Technical Requirements & Specification

This document details the software, hardware, runtime, and architectural requirements for **AI Visual Tutor**.

---

## 1. Environment & Platform Dependencies

| Component | Required Version | Purpose / Notes |
| :--- | :--- | :--- |
| **Operating System** | Windows 10 (19041+) / Windows 11 | Required for `Windows.Graphics.Capture` and Skiko DWM layered windows |
| **Architecture** | `win-x64` (64-bit) | Target platform for JVM and native .NET bridge |
| **Java / JVM** | OpenJDK 17 (LTS) | Pinned via Gradle `jvmToolchain(17)` |
| **Kotlin** | 2.0.21 | Language runtime and compiler |
| **Compose Multiplatform** | 1.7.0 (Desktop) | UI toolkit, Skiko graphics layer |
| **.NET SDK** | .NET 10 | Compiles `wgc-bridge.exe` targeting `net10.0-windows10.0.19041.0` |
| **WgcSharp** | Project reference | C# wrapper around WinRT `Windows.Graphics.Capture` APIs |
| **Gradle** | 8.9 (via wrapper) | Build system |

---

## 2. Hardware Requirements

* **Processor:** 64-bit x86-64 processor (2.0 GHz or faster recommended).
* **RAM:** 4 GB minimum (8 GB recommended for development and packaging).
* **Display:**
  * Single or multi-monitor configurations supported.
  * Standard DPI (100%) and High-DPI scaling (125%, 150%, 200%) supported.
  * GPU with DirectX 11 / OpenGL 3.3+ support for Skiko rendering.

---

## 3. Project Scope Boundaries

### In Scope (Implemented in Phase 1 & Phase 2)
* **Phase 1: Tutor UI & Live Visual Overlay Foundation**
  * Collapsible side panel and floating dock (`TutorPanel`, `TutorDock`).
  * Application selector supporting hardcoded mock workflows for **Blender**, **PDF**, and **Excel**.
  * Step navigation (Previous / Next with boundary state disabling, Pause / Resume, Screen Assistance toggle).
  * True transparent, click-through live visual overlay (`OverlayWindow`).
  * Dynamic multi-monitor virtual desktop spanning (`getVirtualScreenBounds`).
  * Separate top-pinned interactive close control (`OverlayCloseControl`).
  * On-screen visual tutor graphics (`Highlight`, `Arrow`).
* **Phase 2: Visual Context Acquisition**
  * On-demand external window capture via `wgc-bridge` (`Windows.Graphics.Capture`).
  * Manual freeze-frame screenshot region selector (`RegionSelector`) using AWT `Robot` with High-DPI coordinate scaling.
  * Screenshot preview window (`ScreenshotPreviewWindow`) with temporary file lifecycle management.
  * Global capture hotkey listener (`Ctrl+Alt+F12`).
  * Automated packaging of standalone Windows distributions (.exe / .msi) with bundled native resources.

### Out of Scope (Phase 3 & Future Work)
* **No AI / LLM / VLM:** Instruction generation is not dynamic; steps are mock data from `MockTutorData.kt`.
* **No OCR / Text Recognition:** Text on screen is not extracted or parsed.
* **No Computer Vision / Object Detection:** Coordinates are pre-defined or user-selected, not inferred from image recognition.
* **No Real-Time Action Verification:** The application does not monitor user actions to confirm task completion.
* **No Continuous Video Streaming:** Screen frames are acquired only on-demand when explicitly triggered.
