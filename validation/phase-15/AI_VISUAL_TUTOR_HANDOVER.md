# AIVisualTutor — FINAL PROJECT HANDOVER AND ENGINEERING GUIDE

**Project:** AIVisualTutor — AI-Powered Visual Tutoring for Blender  
**Target Platform:** Windows 10/11 Desktop (Kotlin Multiplatform / Compose Desktop)  
**Lifecycle Status:** Phase 15 Complete (`PHASE_15_COMPLETE_AUTOMATED_VALIDATION_ONLY`)  
**Date:** 2026-10-13

---

## 1. Project Purpose and Scope

AIVisualTutor is a desktop application engineered to provide contextual, evidence-aware tutoring for Blender. Rather than functioning as a passive chat bot or an autonomous agent that models on behalf of the learner, AIVisualTutor:
1. Decomposes high-level user learning goals into structured, ordered tutorial plans.
2. Directly highlights relevant controls and panels on top of the live Blender workspace.
3. Adapts instructional explanations dynamically (Direct Guidance, Conceptual Hints, Reflection Questions) based on demonstrated learner mastery.
4. Strictly requires accepted, evidence-based verification before marking tasks complete.
5. Diagnoses action errors and guides the learner through closed-loop recovery.

---

## 2. Complete Architecture Summary

The codebase is organized into modular components adhering to clean architecture principles:

```
composeApp/src/main/kotlin/
├── Main.kt                                # Application entry point and Compose window management
├── bridge/                                # Operating system and Blender integration bridges
│   ├── BlenderWindowLocator.kt            # Win32 window discovery and HWND revalidation
│   ├── BlenderScreenCaptureService.kt     # Java Robot-based screenshot acquisition
│   ├── GlobalCaptureHotkey.kt             # Global hotkey registration
│   └── CaptureBridge.kt                   # Bridge coordinating capture events
├── context/                               # State observation, verification, and coordinate math
│   ├── DesktopCoordinateTransform.kt      # Multi-space (Physical, DIP, Window, Raster) conversions
│   ├── ClosedLoopStateVerification.kt     # Authoritative verification engine and condition checks
│   ├── StateMismatchDiagnosticEngine.kt   # Deterministic failure diagnosis & shortcut suggestions
│   └── BlenderVerificationWorkflow.kt     # Workflow coordinating observation and verification
├── overlay/                               # Non-intrusive desktop visual guidance
│   ├── OverlayWindow.kt                   # Transparent full-screen overlay with WS_EX_TRANSPARENT
│   ├── OverlayManager.kt                  # Overlay visibility and highlight state manager
│   └── ArrowPointer.kt                    # Canvas drawing for contextual directional indicators
├── pedagogy/                              # Adaptive learning, skill tracking, and persistence
│   ├── AdaptivePedagogicalEngine.kt       # Pedagogical state machine and event ingester
│   ├── AdaptationPolicy.kt                # Conservative rule-based stage fading policies
│   ├── PedagogicalModels.kt               # Learner state, skills, difficulties, and preferences
│   └── LearnerStatePersistence.kt         # Atomic disk persistence for learner progress
├── tutor/                                 # Goal planning, execution tracking, and recovery
│   ├── UserGoalPlanner.kt                 # Natural language goal decomposition
│   ├── PlanCoverageValidator.kt           # Topological ordering and outcome coverage auditor
│   ├── PlanRepairEngine.kt                # Bounded auto-repair of invalid plans
│   ├── GoalProgressTracker.kt             # Version-controlled execution and outcome satisfaction
│   ├── ClosedLoopRecoveryCoordinator.kt   # Bounded retry and recovery workflow
│   ├── ScreenshotHelpCoordinator.kt       # Visual screenshot help and preview dialog coordinator
│   └── SmartMultimodalScreenshotAnalyzer.kt # Fallback-aware VLM vision analysis (Gemini/Groq)
└── ui/                                    # Compose Desktop user interface components
    ├── TutorPanel.kt                      # Main floating tutor window
    ├── TutorDock.kt                       # Compact docked status widget
    ├── ScreenshotHelpPreviewDialog.kt     # User consent and screenshot retake dialog
    └── ChatbotPanel.kt                    # Chat assistance and query panel
```

---

## 3. Completed Phases 6–15 Status Summary

| Phase | Title | Accepted Status |
|---|---|---|
| **Phase 6** | Real State Verification & Telemetry Ingestion | `PHASE_6_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 7** | Windows UI Automation Integration | `PHASE_7_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 8** | Screenshot-Assisted Help & Capture Pipeline | `PHASE_8_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 9** | Multimodal Vision Integration & Fallbacks | `PHASE_9_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 10** | Context-Aware Recommendations & Guidance | `PHASE_10_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 11** | Tutoring Continuity & Closed-Loop Recovery | `PHASE_11_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 12** | Reliable Goal Planning & Task Coverage | `PHASE_12_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 13** | Adaptive Learning & Persistent Progress | `PHASE_13_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 14** | Desktop Overlay & Interaction Reliability | `PHASE_14_COMPLETE_AUTOMATED_VALIDATION_ONLY` |
| **Phase 15** | End-to-End Validation & Final Evaluation | `PHASE_15_COMPLETE_AUTOMATED_VALIDATION_ONLY` |

---

## 4. How to Build and Test the Project

### Prerequisites
* **Operating System:** Windows 10/11 (AMD64)
* **JDK:** OpenJDK 17 or higher
* **Gradle:** 8.9 (included via `./gradlew.bat`)

### Execution Commands
```powershell
# Run all automated tests across all phases (613 tests)
.\gradlew.bat test

# Run dedicated Phase 15 End-to-End Evaluation Runner
.\gradlew.bat test --tests "bridge.Phase15EndToEndEvaluationRunner"

# Run dedicated Phase 14 Desktop Overlay Reliability tests
.\gradlew.bat test --tests "overlay.Phase14DesktopOverlayTest"

# Run dedicated Phase 13 Adaptive Learning tests
.\gradlew.bat test --tests "pedagogy.Phase13AdaptiveLearningTest"

# Run dedicated Phase 12 Goal Planning tests
.\gradlew.bat test --tests "tutor.Phase12AuditMatrixTest"

# Run dedicated Phase 11 Closed-Loop Recovery tests
.\gradlew.bat test --tests "tutor.ClosedLoopRecoveryCoordinatorTest"
```

---

## 5. How to Launch and Configure the Application

### Launching the Application
```powershell
.\gradlew.bat run
```

### Environment Configuration (.env)
The application reads environment configuration from a root `.env` file (copied from `.env.example`).
```env
# Optional Vision/LLM API credentials for live multimodal analysis
GROQ_API_KEY=your_groq_api_key_here
GEMINI_API_KEY=your_gemini_api_key_here

# Custom Blender executable path (if not in standard Program Files location)
AIVT_BLENDER_EXECUTABLE=C:\Program Files\Blender Foundation\Blender 5.2\blender.exe
```

> **Security Note:** Never commit `.env` containing live secrets. The test runner uses mocked vision boundaries and does not require active API keys for automated validation.

---

## 6. How to Connect to or Test Against Blender

1. **Window Discovery & Screen Capture:**
   * Launch Blender (version 4.x or 5.x).
   * AIVisualTutor detects the running process via `WindowsBlenderWindowLocator` by filtering processes named `blender.exe`.
   * When multiple windows exist, it selects the active foreground window or the largest workspace window.

2. **Real State Telemetry (Optional TCP Socket Bridge):**
   * Blender Python script: `blender_addon/aivt_state_telemetry.py` (if installed in Blender addons).
   * The addon opens a local TCP socket server on port `9876`, broadcasting scene hierarchy, active object, and interaction mode.
   * In standalone mode (without the addon), `BlenderVerificationWorkflow` gracefully falls back to visual verification and UI Automation.

---

## 7. Current Limitations and Known Behavior

1. **Non-Interactive Background Environments:**
   * In CI/CD pipelines or headless servers without an active desktop display, live screen capture (`Robot.createScreenCapture`) and live Blender window activation (`SetForegroundWindow`) are skipped via test mocks.
2. **Multi-Monitor Mixed DPI Edge Cases:**
   * If a user drags Blender across monitors with differing Windows scaling factors (e.g. 100% and 200%), highlight overlays calculate bounds based on the primary monitor intersection.
3. **External Vision Provider Quotas:**
   * If Groq or Gemini API endpoints return HTTP 429 (quota exceeded), `SmartMultimodalScreenshotAnalyzer` falls back cleanly to deterministic local diagnostic suggestions without corrupting learner progress.

---

## 8. Live Integration Tests Still Outstanding

The following three tests remain marked with `@Ignore` and require a live, foreground desktop session with Blender running:
1. `bridge.BlenderStateVerificationIntegrationLiveTest` — Live TCP socket query against running Blender.
2. `bridge.LiveDesktopBlenderCaptureIntegrationTest` — Real java.awt.Robot screenshot capture of an open Blender window.
3. `bridge.Phase9FinalLiveProviderVerificationTest` — Live API call to Gemini with active internet and API credentials.

---

## 9. Recommended Future Work (Post-Phase 15)

1. **Direct Blender Python Addon Package:** Package the telemetry socket script as a standard `.zip` Blender addon for one-click installation inside Blender Preferences.
2. **Local Vision Model (Ollama / ONNX):** Incorporate an on-device VLM (e.g., Moondream or LLaVA-phi) to eliminate third-party API dependencies entirely.
3. **Audio / Speech Guidance:** Add text-to-speech for audible step instructions so learners do not need to look away from their 3D viewport.
4. **Cross-Platform Overlay Support:** Extend `WS_EX_TRANSPARENT` click-through support to macOS (via `NSWindow` ignoresMouseEvents) and Linux (X11 / Wayland).
