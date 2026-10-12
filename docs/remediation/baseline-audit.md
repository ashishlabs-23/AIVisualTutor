# AI Visual Tutor: Baseline Architecture & Repository Audit

**Document:** `docs/remediation/baseline-audit.md`  
**Date:** 2026-10-12  
**Repository:** `https://github.com/ashishlabs-23/AIVisualTutor.git`  
**Boundary Notice:** **THIS IS NOT PHASE 9.** This audit inspects, validates, and prepares remediations for the existing system.

---

## 1. Version Control & Working Tree State

* **Branch:** `main`
* **Head Commit:** `227fc09f7238b5d7b76c8b7e3f3230e0a2e426fc` (`build: make WGC bridge packaging task conditional on dotnet availability`)
* **Working Tree State:** Active working tree contains uncommitted additions from recent verification, Groq/Gemini conversational AI framework, overlay glow linking, and documentation consolidation. No user changes were overwritten or discarded.
* **Build Toolchain & Runtimes:**
  * **Gradle:** 8.9 (via wrapper `gradlew.bat`)
  * **Kotlin / Compose Multiplatform:** Kotlin 2.0.21, Compose Desktop 1.7.0, Compose Compiler 2.0.21
  * **JVM:** OpenJDK 17.0.20.1 (Microsoft Build, Windows 11 amd64)
  * **.NET Bridge:** .NET 10.0 (`net10.0-windows10.0.19041.0`) Windows Graphics Capture bridge
  * **Blender:** 5.2.2 LTS (`C:\Program Files\Blender Foundation\Blender 5.2\blender.exe`)
* **Current Test Baseline:**
  * **Total Test Cases Executed:** **309**
  * **Passed:** **309** (100% pass rate)
  * **Failed:** **0**
  * **Skipped:** **3** (documented opt-in live external environment tests: `testUiaLiveDesktopIntegration`, `testUiaPaintIntegration`, and `testLlamaCppLiveInference`)
  * **Execution Duration:** ~41.5 seconds (`:composeApp:test --rerun-tasks`)

---

## 2. Component Inventory & Implementation Maturity

| Component / Subsystem | Source Path | Maturity Level | Evaluation Notes |
|---|---|---|---|
| **Windows UI Automation** | `composeApp/src/main/kotlin/context/UiAutomationClient.kt`, `WindowsUiAutomationPerceptionEngine.kt` | **IMPLEMENTED & TESTED** | Queries Windows accessibility tree via isolated PowerShell `-STA`. Tested across Notepad, Calculator, and synthetic trees. Does not inspect custom OpenGL/DirectX canvases where UIA is unexposed. |
| **OCR Text Perception** | `composeApp/src/main/kotlin/context/TesseractOcrService.kt` | **IMPLEMENTED & TESTED** | Local Tess4J / Tesseract 5.3 native integration. Operates on crop-image pixels. Pinned to Java 17 toolchain. |
| **Layered Dynamic Positioning** | `composeApp/src/main/kotlin/context/DynamicTargetPositioningService.kt` | **IMPLEMENTED & TESTED** | Layered resolution: UIA $\rightarrow$ OCR $\rightarrow$ Visual Grounding $\rightarrow$ Validation $\rightarrow$ Abstain. Transforms crop coordinates to physical screen coordinates. |
| **Evidence Grounding Router** | `composeApp/src/main/kotlin/context/AdaptiveGrounding.kt` | **IMPLEMENTED & TESTED** | Supports 5 modes (`UIA_ONLY`, `OCR_ONLY`, `UIA_OCR`, `VISION_ONLY`, `ADAPTIVE`). Enforces candidate association and winner margin. |
| **VLM Grounding Providers** | `composeApp/src/main/kotlin/context/GeminiVisualGroundingProvider.kt`, `LlamaCppVisualGroundingProvider.kt` | **IMPLEMENTED & TESTED** | Supports Google Gemini 0–1000 normalized coordinates and local llama.cpp UGround-V1-2B. Memory guard preflight enforced for local CPU inference. |
| **Closed-Loop State Verification** | `composeApp/src/main/kotlin/context/ClosedLoopStateVerification.kt`, `BlenderVerificationWorkflow.kt` | **IMPLEMENTED & TESTED** | Evaluates expected conditions vs observed state. Computes `StateDiff` (`OBJECT_ADDED`, `REMOVED`, `RENAMED`, `TRANSFORMED`). Supports background auto-verification. |
| **Blender Application Adapter** | `composeApp/src/main/kotlin/adapter/BlenderApplicationAdapter.kt`, `blender/ai_visual_tutor_state/__init__.py` | **IMPLEMENTED & LIVE TESTED** | Loopback HTTP REST (`127.0.0.1:47629`) exposing `/api/v1/health`, `/api/v1/snapshot`, `/api/v1/version`. Real-time viewport transform detection (`G`, `R`, `S`) updates `EVENT_COUNTER`. |
| **Evidence Reconciliation** | `composeApp/src/main/kotlin/adapter/EvidenceReconciler.kt` | **IMPLEMENTED & TESTED** | Reconciles multi-source observations. Enforces ground-truth precedence, rejects out-of-order snapshot versions, and validates rename identity. |
| **Adaptive Pedagogical Engine** | `composeApp/src/main/kotlin/pedagogy/AdaptivePedagogicalEngine.kt`, `AdaptationPolicy.kt` | **IMPLEMENTED & TESTED** | Implements pedagogical fading (Stage 1 Direct $\rightarrow$ Stage 2 Hint $\rightarrow$ Stage 3 Reflection Question), scaffolding recovery, and atomic state persistence. |
| **Interactive UI & Overlay** | `composeApp/src/main/kotlin/ui/InstructionCard.kt`, `TutorPanel.kt`, `ChatbotPanel.kt`, `overlay/OverlayWindow.kt` | **IMPLEMENTED & TESTED** | Stage 3 interactive question input, "Submit Answer", "I'm Stuck", dual-tab tutor/chatbot UI, glow overlay trigger, and auto-verification status. |
| **Conversational AI Framework** | `composeApp/src/main/kotlin/chat/` (`SmartChatbotProvider`, `GroqChatbotProvider`, `GeminiChatbotProvider`, `ApiKeyConfig`) | **IMPLEMENTED & TESTED** | High-speed Groq (`openai/gpt-oss-120b`, `qwen/qwen3.8-27b`) and Gemini cloud providers with offline fallback and directive parsing (`[TARGET: ...]`, `[ACTION: ...]`). |

---

## 3. Confirmed Architectural Deficiencies & Missing Capabilities

1. **Absence of Local Visual Frame-Difference Verification:**
   * **Problem:** Non-Blender applications (e.g. Paint, Excel, PDF viewers, generic desktop software) lack native add-ons. Currently, when learners perform actions in these applications, the tutor has no local visual verification mechanism to confirm whether pixels actually changed.
   * **Consequence:** The tutor must either rely on self-reported completion or dispatch costly multimodal VLM requests.
2. **Missing Deterministic State Mismatch Diagnostics:**
   * **Problem:** When a learner clicks "I'm Stuck", the current workflow constructs a natural-language query and asks the LLM to deduce why they are stuck.
   * **Consequence:** If the observed state contains a clear technical discrepancy (e.g., student is in `OBJECT` mode when `EDIT_MESH` is required, or selected `Camera` instead of `Cube`), the tutor relies on LLM inference rather than computing a deterministic state diff ($ExpectedState \setminus ObservedState$).
3. **Full-Frame VLM Escalation Waste (Missing `RegionFocus` Sub-Cropping):**
   * **Problem:** When local UIA and OCR fail to localize a target control and the router escalates to a VLM (Gemini or Groq Qwen), it transmits the entire 1080p/4K desktop screenshot.
   * **Consequence:** Transmitting full screenshots wastes tokens, increases network latency, and degrades small-icon localization accuracy.
4. **Lack of Microsoft Excel / Office Telemetry:**
   * **Problem:** While Blender has deep REST telemetry, Excel is only accessible via generic Windows UI Automation accessibility elements.
   * **Consequence:** Semantic spreadsheet state (active cell coordinates, formulas, cell values, worksheet names) cannot be verified.
5. **Incomplete Adapter Capability Declarations:**
   * **Problem:** `ApplicationAdapter` currently exposes snapshots and health, but does not explicitly declare which capabilities it supports (`OBJECT_STATE`, `SELECTION_STATE`, `DOCUMENT_STRUCTURE`, `CELL_FORMULAS`, `VISUAL_CHANGE`).
   * **Consequence:** Callers cannot query upfront what facts an adapter is capable of verifying.
6. **No Developer Verification Telemetry Dashboard:**
   * **Problem:** While research metrics exist in `GroundingResearchMetrics`, there is no developer-facing telemetry aggregator reporting verification accuracy, false acceptance, abstention rates, and latency across applications.

---

## 4. Integration Risks & Constraints

1. **JDK 17 Compatibility:**
   * The toolchain is pinned to Java 17. Any image processing or native bridging must remain fully compatible with JVM 17 and Compose Multiplatform Desktop.
2. **Windows Desktop & Multi-Monitor Origins:**
   * Coordinate conversions must strictly respect virtual desktop bounds (which may have negative $x$ or $y$ origins in multi-monitor setups) and Windows DPI scaling factors.
3. **COM / Office Automation Apartment Threading:**
   * Interacting with Excel via Windows COM requires careful thread management (STA apartments) and non-blocking background dispatch to prevent freezing the Compose UI event loop.
4. **Privacy & Security Boundaries:**
   * Telemetry and image captures must never exfiltrate entire spreadsheets, private document contents, or off-target window pixels to external cloud services without user consent.
