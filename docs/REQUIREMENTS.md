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
  * The selector and capture code include per-monitor snapshot/crop handling and negative desktop origins.
  * Real Windows mixed-DPI and multi-monitor alignment remain unverified; consult the checklist in `PHASE_3.md`.
  * GPU with DirectX 11 / OpenGL 3.3+ support for Skiko rendering.

---

## 3. Project Scope Boundaries

### Implemented scope (Phases 1–4)
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
* **Phase 3: Universal region context acquisition**
  * Ctrl+Shift+Space selection shortcut alongside the Phase 2 shortcut.
  * Frozen region selection, application/window context, `VisualContext`, processing lifecycle, and selection geometry.
  * Basic hotkey-triggered selector rendering exercised in 8 live captures; DPI scaling, multi-monitor behavior, and resilience checklist items remain unverified.
* **Phase 4: Evidence-oriented perception baseline**
  * Windows UI Automation and local Tesseract OCR evidence providers behind replaceable interfaces.
  * Deterministic evidence evaluation with `ACCEPT`, `REFINE`, `ESCALATE`, and `ABSTAIN` decisions.
  * Synthetic provider/evaluator regression coverage and saved calibration artifacts.
  * Phase 4 status is `PARTIAL_SIGNAL_UIA_OCR_PENDING_GROUND_TRUTH`; the 18 calibration labels are still pending, so correctness is not yet reportable.
  * Visual grounding is `NOT_CONFIGURED`. A CPU feasibility attempt was blocked by runtime/memory constraints; the local Vulkan/GPU smoke run failed with a driver/runtime error. No third model stack is configured.

### Out of scope and not yet validated
* **No dynamic instruction generation or LLM-based tutoring:** steps are mock data from `MockTutorData.kt`.
* **No configured VLM/visual-grounding provider:** the contract exists, but visual grounding remains `NOT_CONFIGURED`.
* **No measured real-target correctness:** UIA/OCR have been exercised on saved/live captures, but UIA intersection does not prove the intended target was identified. Human-confirmed labels are required before reporting correctness.
* **No verified cross-provider geometry fusion:** OCR crop-pixel bounds and UIA desktop bounds are not compared without a verified coordinate transform.
* **No Computer Vision / Object Detection:** coordinates are user-selected; visual object detection is not configured.
* **No Real-Time Action Verification:** The application does not monitor user actions to confirm task completion.
* **No Continuous Video Streaming:** Screen frames are acquired only on-demand when explicitly triggered.

See [`PHASE_0.md`](PHASE_0.md) for the proposed research direction, [`PHASE_1.md`](PHASE_1.md) through [`PHASE_4.md`](PHASE_4.md) for phase-specific implementation/status, and [`PHASE_3.md`](PHASE_3.md) for the desktop verification checklist.
