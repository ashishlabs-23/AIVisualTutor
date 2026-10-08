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
* **Phase 4: Evidence-oriented perception**
  * **Implemented:** Windows UI Automation, local Tess4J/Tesseract OCR, a deterministic evidence evaluator, a llama.cpp/mtmd visual-grounding provider boundary for UGround-V1-2B, automatic schema-v3 calibration-result persistence, and a screenshot preview showing the supplied target and UIA/OCR/vision/decision status.
  * **Selected visual model:** UGround-V1-2B (`osunlp/UGround-V1-2B`, Apache-2.0). The Q4_K_M GGUF and matching projector are third-party quantization from `mradermacher/UGround-V1-2B-GGUF`; they are installed under LocalAppData and are not stored in the repository.
  * **Executed:** UIA/OCR have saved-capture observations, including four Blender cases. The real UGround model was executed through llama.cpp CPU inference on saved crops B01–B04 and C01–C18 using `--image-min-tokens 1024`. All 22 runs returned parseable points; all 22 deterministic evaluator decisions were `ABSTAIN`. An earlier 2026-10-08 memory check (about 1.69 GiB available) was safely `HOST_BLOCKED`; the later execution preflights passed the 3,629,247,837-byte guard. Model execution and parsed output are established, but no coordinate-level ground truth or visual accuracy/correctness is established.
  * **Persisted:** every completed calibration attempt records crop and structured provider/evaluator state, including unavailable, not-configured, host-blocked, failed, cancelled, and successful visual outcomes. Human labels remain separate research annotations; the 18 C cases and four Blender cases currently have human-authored labels (22/22), but labels are never required for operation and new machine records may use `PENDING` or `null`.
  * **Validated:** automated tests cover provider state handling and persistence, strict point parsing, preview status, and preservation of separate UIA/OCR evidence. No UGround accuracy, correctness, benefit, or visual solution of Blender cases is established.

### Out of scope and not yet validated
* **No dynamic instruction generation or LLM-based tutoring:** steps are mock data from `MockTutorData.kt`.
* **No executed UGround inference on the current host:** the provider integration boundary is implemented, but the current environment fails the memory safety guard. No visual grounding accuracy or correctness result is available.
* **Limited measured real-target correctness:** saved UIA/OCR and four Blender cases have human labels, but this sparse set does not establish general modality effectiveness. UIA bounds intersecting a crop do not prove that the intended target was identified. Human labels are optional for operation and are used only for research scoring when their format supports it.
* **No verified cross-provider geometry fusion:** OCR crop-pixel bounds and UIA desktop bounds are not compared without a verified coordinate transform.
* The historical capture-time UIA failures versus later saved-crop replay successes remain unresolved.
* No user study has been performed; learning gain, usability, and workload are not measured.
* **No Computer Vision / Object Detection:** coordinates are user-selected; visual object detection is not configured.
* **No Real-Time Action Verification:** The application does not monitor user actions to confirm task completion.
* **No Continuous Video Streaming:** Screen frames are acquired only on-demand when explicitly triggered.

See [`PHASE_0.md`](PHASE_0.md) for the proposed research direction, [`PHASE_1.md`](PHASE_1.md) through [`PHASE_4.md`](PHASE_4.md) for phase-specific implementation/status, and [`PHASE_3.md`](PHASE_3.md) for the desktop verification checklist.
