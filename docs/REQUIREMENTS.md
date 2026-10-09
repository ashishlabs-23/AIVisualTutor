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
| **Blender (optional)** | 4.0 or later | Required only to use the opt-in Phase 6 Blender state adapter; not required to build or run the tutor's other features |

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

### Implemented scope (Phases 1–6)
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
  * **Runtime audit status:** UIA/OCR have saved-capture observations, including four Blender cases. Real UGround-V1-2B inference remains **NOT VERIFIED**: the final audit's available-memory check was below the unchanged 3,629,247,837-byte safety guard, so model loading/inference was blocked. Keep the guard enabled; no visual accuracy/correctness claim is established.
  * **Persisted:** every completed calibration attempt records crop and structured provider/evaluator state, including unavailable, not-configured, host-blocked, failed, cancelled, and successful visual outcomes. Human labels remain separate research annotations; the 18 C cases and four Blender cases currently have human-authored labels (22/22), but labels are never required for operation and new machine records may use `PENDING` or `null`.
  * **Validated:** automated tests cover provider state handling and persistence, strict point parsing, preview status, and preservation of separate UIA/OCR evidence. No UGround accuracy, correctness, benefit, or visual solution of Blender cases is established.
* **Phase 5: evidence-adaptive grounding**
  * **Implemented:** selectable `UIA_ONLY`, `OCR_ONLY`, `UIA_OCR`, `VISION_ONLY`, and `ADAPTIVE` modes, provider execution/status accounting, candidate generation and provenance, heuristic evidence association/fusion, and `ACCEPT`, `REFINE`, `ESCALATE`, and `ABSTAIN` decisions.
  * **Validated with limits:** UIA/OCR grounding and transforms have controlled Windows fixture coverage; persistence/replay, five-mode isolation, and the evaluation harness have test coverage. The final runtime audit passed the full build and 153 tests (2 opt-in skips); the later complete Kotlin suite after Phase 6 passed 190 tests (3 skips). See [`PHASE_5_RUNTIME_AUDIT_2026-10-09.md`](PHASE_5_RUNTIME_AUDIT_2026-10-09.md) for exact commands and evidence.
  * **Limited freeze:** real UGround inference is **NOT VERIFIED** because the host was below the memory safety guard; genuine live multi-provider conflict resolution remains unverified; the independent dataset contains only two cases; comparative research accuracy is **NOT ESTABLISHED**.
* **Phase 6: Blender closed-loop state verification**
  * **Implemented:** when Blender is selected, users can choose create/delete/rename/select/modify (object location), capture a baseline, perform the action themselves in Blender, and request a post-action check. A deterministic verifier returns `SUCCESS`, `FAILURE`, or `UNCERTAIN` and persists JSON results under `%LOCALAPPDATA%\AIVisualTutor\verification-runs`.
  * **Integration boundary:** an opt-in Blender 4.0+ read-only add-on sends scene snapshots to the app over loopback TCP at `127.0.0.1:47629` by default. It observes Blender state; it does not execute actions, change scene content, or infer expected state from tutorial text.
  * **Not fully end-to-end verified:** isolated Blender snapshot transport and automated workflow tests pass, but activation in the user's existing desktop Blender session and a full tutor-UI action sequence remain unverified. The adapter is optional and is not required for the rest of the application.

### Out of scope and not yet validated
* **No dynamic instruction generation or LLM-based tutoring:** steps are mock data from `MockTutorData.kt`.
* **No verified real UGround inference in the final Phase 5 audit:** configured artifacts do not count as inference; the existing memory guard blocked model loading. No visual grounding accuracy or correctness result is available.
* **Limited measured real-target correctness:** saved UIA/OCR and four Blender cases have human labels, but this sparse set does not establish general modality effectiveness. UIA bounds intersecting a crop do not prove that the intended target was identified. Human labels are optional for operation and are used only for research scoring when their format supports it.
* **Cross-provider fusion has limits:** the Phase 5 implementation associates evidence only when both sources have compatible screenshot-pixel coordinates; deterministic agreement/conflict policy has test coverage. Genuine live multi-provider disagreement and broader mixed-DPI/multi-monitor transforms remain unverified.
* The historical capture-time UIA failures versus later saved-crop replay successes remain unresolved.
* No user study has been performed; learning gain, usability, and workload are not measured.
* **No Computer Vision / Object Detection:** coordinates are user-selected; visual object detection is not configured.
* **No automatic or continuous action monitoring:** Phase 6 supports an explicit user-triggered Blender baseline/post-action check only; it does not monitor actions continuously or automatically derive expected state from instructions.
* **No Continuous Video Streaming:** Screen frames are acquired only on-demand when explicitly triggered.

Phase 6's supported operations, snapshot fields, loopback protocol, setup, and verification status are documented in [`PHASE_6.md`](PHASE_6.md). See [`PHASE_0.md`](PHASE_0.md) for the proposed research direction, [`PHASE_1.md`](PHASE_1.md) through [`PHASE_5.md`](PHASE_5.md) for phase-specific implementation/status, the [Phase 5 runtime audit](PHASE_5_RUNTIME_AUDIT_2026-10-09.md) for the limited-freeze evidence, and [`PHASE_3.md`](PHASE_3.md) for the desktop verification checklist.
