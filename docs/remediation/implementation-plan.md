# AI Visual Tutor: Remediation Implementation Plan

**Document:** `docs/remediation/implementation-plan.md`  
**Date:** 2026-10-12  
**Boundary Notice:** **THIS IS NOT PHASE 9.** This plan structures verified remediations for the existing system.

---

## 1. Work Breakdown Structure & Dependency Graph

```text
Step 1: Adapter Capabilities & Provenance Freshness
  │     (ApplicationAdapterContracts.kt)
  ▼
Step 2: Local Visual Frame-Difference Verifier
  │     (VisualFrameDiffVerifier.kt & VisualFrameDiffVerifierTest.kt)
  ▼
Step 3: Deterministic State Mismatch Diagnostics & Recovery Engine
  │     (StateMismatchDiagnosticEngine.kt & StateMismatchDiagnosticEngineTest.kt)
  ▼
Step 4: Intelligent RegionFocus Sub-Cropping Service
  │     (RegionFocusService.kt & RegionFocusServiceTest.kt)
  ▼
Step 5: Capability-Aware Microsoft Excel Telemetry Adapter
  │     (ExcelApplicationAdapter.kt & ExcelApplicationAdapterTest.kt)
  ▼
Step 6: Closed-Loop Integration & "I'm Stuck" Workflow Enhancement
  │     (EvidenceReconciler.kt, ChatbotPanel.kt, TutorController.kt)
  ▼
Step 7: Verification Telemetry Dashboard & Research Quality Metrics
        (VerificationDashboardTelemetry.kt & VerificationDashboardTelemetryTest.kt)
```

---

## 2. File-Level Changes & Planned Scope

### New Files to Create

| File Path | Responsibility |
|---|---|
| `composeApp/src/main/kotlin/context/VisualFrameDiffVerifier.kt` | Local visual frame differencing: RGB Euclidean thresholding, ROI scoping, changed bounding box clustering, and normalized ratio scoring. |
| `composeApp/src/main/kotlin/context/RegionFocusService.kt` | Sub-cropping engine extracting active application window and target neighborhood regions with coordinate transform preservation. |
| `composeApp/src/main/kotlin/context/StateMismatchDiagnosticEngine.kt` | Deterministic state comparison ($ExpectedState \setminus ObservedState$), mismatch code classification, and recovery action formulation. |
| `composeApp/src/main/kotlin/adapter/ExcelApplicationAdapter.kt` | Capability-aware Excel adapter querying active workbook, active sheet, active cell address, formula, and value via Windows COM Automation with UIA fallback. |
| `composeApp/src/main/kotlin/context/VerificationDashboardTelemetry.kt` | Developer & research verification telemetry tracker: success rates, false accept rates, abstention rates, VLM escalation frequency, and latencies. |
| `composeApp/src/test/kotlin/context/VisualFrameDiffVerifierTest.kt` | Deterministic unit tests for visual diffing: identical frames, localized canvas edits, noise filtering, and dimension mismatches. |
| `composeApp/src/test/kotlin/context/RegionFocusServiceTest.kt` | Unit tests for RegionFocus: window bounds cropping, margin expansion, clamp boundaries, and coordinate transform reversibility. |
| `composeApp/src/test/kotlin/context/StateMismatchDiagnosticEngineTest.kt` | Unit tests for mismatch diagnostics: mode mismatches, selection mismatches, missing objects, transform discrepancies, and recovery action generation. |
| `composeApp/src/test/kotlin/adapter/ExcelApplicationAdapterTest.kt` | Unit tests for Excel adapter: parsing COM output, snapshot creation, and offline/unavailable handling. |
| `composeApp/src/test/kotlin/context/VerificationDashboardTelemetryTest.kt` | Unit tests for telemetry aggregation and metric reporting. |

### Existing Files to Modify Non-Disruptively

| File Path | Planned Modifications |
|---|---|
| `composeApp/src/main/kotlin/adapter/ApplicationAdapterContracts.kt` | Add `AdapterCapability` enum and declare `val capabilities: Set<AdapterCapability>` on `ApplicationAdapter`. |
| `composeApp/src/main/kotlin/adapter/BlenderApplicationAdapter.kt` | Declare supported capabilities (`OBJECT_STATE`, `SELECTION_STATE`, `MODE_STATE`, `LIVE_CHANGE_EVENTS`). |
| `composeApp/src/main/kotlin/adapter/EvidenceReconciler.kt` | Wire `VisualFrameDiffVerifier` into multi-source reconciliation for non-telemetry applications. |
| `composeApp/src/main/kotlin/ui/ChatbotPanel.kt` | Integrate `StateMismatchDiagnosticEngine` into `[🆘 I'm Stuck on this Step]` so deterministic mismatches and recovery actions display instantly. |

---

## 3. Step-by-Step Implementation Sequence

1. **Step 1 — Capabilities & Provenance:** Update `ApplicationAdapterContracts.kt` with `AdapterCapability` and freshness verification.
2. **Step 2 — Visual Difference Engine:** Implement `VisualFrameDiffVerifier.kt` with synthetic test suite.
3. **Step 3 — State Mismatch Diagnostics:** Implement `StateMismatchDiagnosticEngine.kt` and tests covering mode, selection, object, and document mismatches.
4. **Step 4 — RegionFocus Sub-Cropping:** Implement `RegionFocusService.kt` and tests verifying coordinate transform integrity.
5. **Step 5 — Excel Telemetry Adapter:** Implement `ExcelApplicationAdapter.kt` and tests for Excel snapshot inspection and UIA fallback.
6. **Step 6 — Verification & UI Wiring:** Integrate visual diffing into `EvidenceReconciler.kt` and wire mismatch diagnostics into `ChatbotPanel.kt`.
7. **Step 7 — Telemetry Dashboard:** Implement `VerificationDashboardTelemetry.kt` and unit tests.
8. **Step 8 — Validation & Deliverables:** Run full Gradle test suite, verify compilation, and produce `validation-report.md` and `feature-status.md`.

---

## 4. Risk Mitigation & Quality Gates

* **Zero Regressions:** The existing 309 unit tests must continue to pass throughout all edits.
* **No Blocking UI Calls:** All COM, image diffing, and VLM operations must execute on background Dispatchers (`Dispatchers.Default` / `Dispatchers.IO`).
* **Safe Degradation:** If Excel is not installed or closed, the tutor must report `UNAVAILABLE` and fall back gracefully without exceptions.
