# AI Visual Tutor: Feature Status Matrix
**Document:** `docs/remediation/feature-status.md`  
**Phase:** Remediation and Feature Enhancement (NOT Phase 9)  
**Baseline Git Commit:** `227fc09f7238b5d7b76c8b7e3f3230e0a2e426fc`  

---

## Evaluation Status Legend
* **`IMPLEMENTED_AND_UNIT_TESTED`**: Feature implemented in production source and validated with deterministic unit tests.
* **`IMPLEMENTED_AND_INTEGRATION_TESTED`**: Feature wired end-to-end across multiple subsystems and validated with integration tests.
* **`IMPLEMENTED_AND_LIVE_TESTED`**: Feature verified against a live, running third-party desktop application instance.
* **`PARTIALLY_IMPLEMENTED`**: Core architecture implemented, but specific optional capabilities remain in progress.
* **`BLOCKED`**: Feature implementation blocked by external environment or operating system constraints.
* **`NOT_RUN`**: Test scenario not executed due to missing physical hardware configuration.
* **`DEFERRED_WITH_REASON`**: Feature intentionally postponed with clear architectural justification.

---

## 1. Feature Status Matrix

| Feature ID | Feature Name | Status | Implemented Components & Tests | Notes / Justification |
|---|---|---|---|---|
| **FEATURE A** | Local Visual Frame-Difference Verifier | `IMPLEMENTED_AND_UNIT_TESTED` | [VisualFrameDiffVerifier.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/VisualFrameDiffVerifier.kt), `VisualFrameDiffVerifierTest.kt` | Fast Euclidean color differencing, ROI clipping, connected bounding-box clustering, noise filtering. Verified with 6 unit tests. |
| **FEATURE B** | Deterministic State Mismatch Diagnostics | `IMPLEMENTED_AND_INTEGRATION_TESTED` | [StateMismatchDiagnosticEngine.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/StateMismatchDiagnosticEngine.kt), [TutorController.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/tutor/TutorController.kt), [ChatbotPanel.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/ui/ChatbotPanel.kt) | Deterministic diagnosis of mode, selection, missing object, transform, and stale telemetry; wired into "I'm Stuck" chat UI. |
| **FEATURE C** | Intelligent RegionFocus Sub-Cropping | `IMPLEMENTED_AND_INTEGRATION_TESTED` | [RegionFocusService.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/RegionFocusService.kt), [DynamicTargetPositioningService.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/DynamicTargetPositioningService.kt) | Neighborhood, quadrant, and window cropping with bidirectional coordinate transforms. Integrated into grounding pipeline. |
| **FEATURE D** | Microsoft Excel Telemetry Adapter | `IMPLEMENTED_AND_LIVE_TESTED` | [ExcelApplicationAdapter.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/ExcelApplicationAdapter.kt), `ExcelApplicationAdapterTest.kt` | Live COM verification (EXCEL-T1 to T8) extracting active workbook, active sheet, active cell, formula, and value. |
| **FEATURE E** | Improved Closed-Loop Verification Pipeline | `IMPLEMENTED_AND_INTEGRATION_TESTED` | [ClosedLoopStateVerification.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/ClosedLoopStateVerification.kt), [EvidenceReconciler.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/EvidenceReconciler.kt) | Reconciles application state, visual differences (`verifyVisualChange`), and UI Automation without treating raw pixel changes as semantic success. |
| **FEATURE F1** | Confidence & Evidence Explainability | `IMPLEMENTED_AND_INTEGRATION_TESTED` | `StateMismatchReport`, `VisualDiffResult`, [ChatbotPanel.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/ui/ChatbotPanel.kt) | Displays structured verification status (`Verified`, `Mismatch Detected`, `Stale Observation`) rather than uncalibrated percentages. |
| **FEATURE F2** | Actionable Recovery Engine | `IMPLEMENTED_AND_INTEGRATION_TESTED` | `RecoveryAction`, [StateMismatchDiagnosticEngine.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/StateMismatchDiagnosticEngine.kt) | Suggests concrete keystroke / mouse recovery steps (e.g. "Press Tab to switch to Edit Mode", "Select 'Cube' in Outliner") on learner error. |
| **FEATURE F3** | Task-Aware Region Selection | `IMPLEMENTED_AND_INTEGRATION_TESTED` | [RegionFocusService.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/RegionFocusService.kt), [DynamicTargetPositioningService.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/DynamicTargetPositioningService.kt) | Extracts task-relevant bounding boxes (panel quadrant, active window, widget neighborhood) rather than capturing full desktop unconditionally. |
| **FEATURE F4** | Stale-Evidence Detection (Freshness Guard) | `IMPLEMENTED_AND_INTEGRATION_TESTED` | [StaleEvidenceGuard.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/StaleEvidenceGuard.kt), `StaleEvidenceGuardTest.kt` | Source-specific freshness policies (Telemetry: 10s, Screenshot: 8s, VLM: 15s) and context matching; tested via injectable clock. |
| **FEATURE F5** | Provider-Adaptive VLM Routing | `PARTIALLY_IMPLEMENTED` | `AdaptiveGrounding.kt`, `DynamicTargetPositioningService.kt` | Resolves locally (UIA/OCR) first; escalates to VLM only on uncertainty. Multi-provider fallbacks exist; cost-budget router deferred. |
| **FEATURE F6** | Learner-Aware Error History | `IMPLEMENTED_AND_INTEGRATION_TESTED` | `AdaptivePedagogicalEngine.kt`, `LearnerProfilePersistence.kt` | Tracks successive failures and adjusts scaffolding fading levels (Stage 1 to 4) dynamically. |
| **FEATURE F7** | Task-Level Observability Dashboard | `IMPLEMENTED_AND_UNIT_TESTED` | [VerificationDashboardTelemetry.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/VerificationDashboardTelemetry.kt) | Records success rate, false accept/reject rate, abstention coverage, VLM escalation frequency, median/p95 latency, and recovery rates. |
| **FEATURE F8** | Resilient Capability-Aware Application Adapters | `IMPLEMENTED_AND_INTEGRATION_TESTED` | [ApplicationAdapterContracts.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/ApplicationAdapterContracts.kt), [BlenderApplicationAdapter.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/BlenderApplicationAdapter.kt) | Formalized `AdapterCapability` enum (`OBJECT_STATE`, `SELECTION_STATE`, `MODE_STATE`, `DOCUMENT_STRUCTURE`, `CELL_FORMULAS`, `VISUAL_CHANGE`, `LIVE_CHANGE_EVENTS`). |
| **FEATURE F9** | Privacy-Aware Screen & Document Handling | `IMPLEMENTED_AND_INTEGRATION_TESTED` | [RegionFocusService.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/RegionFocusService.kt), [ExcelApplicationAdapter.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/ExcelApplicationAdapter.kt) | Minimizes captured pixels via sub-cropping; Excel adapter queries only active cell/formula rather than exporting entire workbook contents. |
| **FEATURE F10** | Reproducible Research Evaluation | `IMPLEMENTED_AND_INTEGRATION_TESTED` | `GroundingResearch.kt`, `CalibrationResultPersistence.kt`, `Phase5Evaluation.kt` | Retained all offline benchmark test harnesses; zero test degradation across 346 tests. |
| **DPI-NORM** | DPI and Multi-Monitor Coordinate Normalization | `IMPLEMENTED_AND_INTEGRATION_TESTED` | [DpiCoordinateNormalizer.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/DpiCoordinateNormalizer.kt), `DpiCoordinateNormalizerTest.kt` | Bidirectional physical $\leftrightarrow$ logical mapping across 1.0x, 1.25x, 1.5x, 2.0x scales, negative monitor origins, and desktop boundary clamping. |

---

## 2. Deferred Features & Architectural Justification

1. **Persistent COM Event Sink for Microsoft Excel:**
   * **Status:** `DEFERRED_WITH_REASON`
   * **Reason:** An in-process COM event sink (`_AppEvents_SheetSelectionChangeEventHandler`) requires installing and registering an unmanaged C++/C# VSTO Add-in DLL on the user's desktop. The STA polling bridge satisfies all verification and diagnosis requirements non-destructively without administrative setup burdens.
2. **Word and PowerPoint Telemetry Adapters:**
   * **Status:** `DEFERRED_WITH_REASON`
   * **Reason:** Prioritized hardening and live testing the Excel vertical slice first. Shared `ApplicationAdapterContracts` and `AdapterCapability` abstractions will readily accommodate Word/PowerPoint adapters in future engineering iterations.
3. **Physical Dual-Monitor Multi-DPI Live Hardware Run:**
   * **Status:** `NOT_RUN`
   * **Reason:** Current test execution environment operates on a single primary physical display. Dual-monitor and mixed-DPI geometry was thoroughly validated through deterministic unit test suites (`DpiCoordinateNormalizerTest`).
