# AI Visual Tutor: Integration Audit & Source-Level Architecture Map
**Document:** `docs/remediation/integration-audit.md`  
**Phase:** Remediation and Feature Enhancement (NOT Phase 9)  
**Baseline Git Commit:** `227fc09f7238b5d7b76c8b7e3f3230e0a2e426fc`  
**Execution Environment:** Windows 11 (AMD64), OpenJDK 17.0.14, Gradle 8.9, Kotlin 2.0.21, Compose Desktop 1.7.0  

---

## 1. Verified Execution Paths

### Path 1: Screenshot $\rightarrow$ RegionFocus $\rightarrow$ Visual Grounding $\rightarrow$ Coordinate Normalization $\rightarrow$ Overlay Highlight
* **Entry Point:** [DynamicTargetPositioningService.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/DynamicTargetPositioningService.kt#L52-L108)  
* **Integrated Pipeline:**
  1. UI Automation element resolution attempt ([PowerShellUiAutomationClient](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/WindowsUiAutomationPerceptionEngine.kt#L225)).
  2. OCR text candidate search attempt ([TesseractOcrService.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/TesseractOcrService.kt)).
  3. Visual Grounding with intelligent sub-cropping via [RegionFocusService.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/RegionFocusService.kt):
     * Heuristic 1: Candidate neighborhood crop ($\pm 180\text{ px}$ contextual margin).
     * Heuristic 2: Panel quadrant crop (toolbar on left, properties inspector on right, header/menu on top).
     * Heuristic 3: Active application window bounding box.
     * Heuristic 4: Fullscreen fallback.
  4. Local proposal dispatch to [EvidenceAdaptiveGroundingRouter.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/AdaptiveGrounding.kt#L408).
  5. Bidirectional coordinate transformation via `CropCoordinateTransform.cropToScreen()`.
  6. DPI normalization from physical screen coordinates to Compose DIPs via [DpiCoordinateNormalizer.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/DpiCoordinateNormalizer.kt).
  7. Final rendering in [InstructionCard.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/ui/InstructionCard.kt) and [TutorPanel.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/ui/TutorPanel.kt).

### Path 2: Expected State $\rightarrow$ Observed State $\rightarrow$ StateMismatchDiagnosticEngine $\rightarrow$ "I'm Stuck" Recovery
* **Entry Point:** [ChatbotPanel.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/ui/ChatbotPanel.kt#L260-L278) (Learner clicks `[🆘 I'm Stuck on this Step]`)  
* **Integrated Pipeline:**
  1. UI invokes `TutorController.diagnoseCurrentStep()`.
  2. `TutorController` supplies the active `TutorStep.expectedState` and freshest `SessionContext.lastObservedState`.
  3. [StaleEvidenceGuard.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/StaleEvidenceGuard.kt) checks telemetry age against the 10-second threshold and confirms application context match.
  4. [StateMismatchDiagnosticEngine.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/StateMismatchDiagnosticEngine.kt) evaluates deterministic rule set:
     * Mode mismatch (e.g. expected `EDIT`, observed `OBJECT` $\rightarrow$ action: `Press Tab to switch modes`).
     * Selection mismatch (e.g. expected `Cube`, observed `Camera` $\rightarrow$ action: `Select 'Cube' in Outliner or Viewport`).
     * Missing object (expected created object not found $\rightarrow$ action: `Add required object`).
     * Transform unmodified (location/rotation unchanged from baseline $\rightarrow$ action: `Apply transform`).
     * Stale telemetry ($\rightarrow$ action: `Refresh application snapshot`).
  5. Actionable guidance injected directly into learner chat transcript before or without making ungrounded LLM API calls.

### Path 3: Microsoft Excel Instance $\rightarrow$ COM/PowerShell Bridge $\rightarrow$ Snapshot Parsing $\rightarrow$ Closed-Loop Verification
* **Entry Point:** [ExcelApplicationAdapter.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/ExcelApplicationAdapter.kt#L31-L150)  
* **Integrated Pipeline:**
  1. `checkHealth()` and `takeSnapshot()` invoke PowerShell STA process querying `[Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')`.
  2. Extracts active workbook name, active worksheet name, active cell address, formula, and value.
  3. Handles non-running or closed Excel gracefully by returning `AdapterSnapshotResult.Unavailable`.
  4. Yields strongly typed `AdapterSnapshot` with properties `metadata["activeCell"]`, `metadata["activeFormula"]`, `metadata["activeValue"]`.

### Path 4: Baseline Screenshot $\rightarrow$ Learner Action $\rightarrow$ Post-Action Screenshot $\rightarrow$ VisualFrameDiffVerifier $\rightarrow$ EvidenceReconciler
* **Entry Point:** [ClosedLoopStateVerification.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/ClosedLoopStateVerification.kt) & [EvidenceReconciler.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/EvidenceReconciler.kt#L182-L220)  
* **Integrated Pipeline:**
  1. Baseline screenshot raster captured.
  2. Post-action screenshot raster captured.
  3. [VisualFrameDiffVerifier.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/VisualFrameDiffVerifier.kt) computes Euclidean RGB differences inside the region of interest (ROI).
  4. Connects contiguous changed pixels into bounding boxes; filters high-frequency noise.
  5. Returns structured `VisualDiffResult`.
  6. `EvidenceReconciler` integrates visual change as corroborating evidence without treating pixel diffs alone as proof of semantic step success.

---

## 2. Source Audit Findings & Integration Gaps Remediated

| Component | Audit Finding | Remediation Performed |
|---|---|---|
| **RegionFocusService** | Implemented as standalone class with unit test, but completely disconnected from `DynamicTargetPositioningService`. | Integrated `RegionFocusService` into `DynamicTargetPositioningService.resolveViaGrounding()`. Priority ordering updated so specific panel quadrant hints take precedence over generic full-window captures. |
| **DPI Awareness** | `WindowsUiAutomationPerceptionEngine` strictly checked `scaleX == 1.0` and aborted with `coordinate_mapping_unsupported` on scaled displays (125%, 150%, 200%). | Created `DpiCoordinateNormalizer.kt` providing physical $\leftrightarrow$ logical conversions, negative monitor origin mapping, and desktop clamping. Wired into `DynamicTargetPositioningService`. |
| **Telemetry Staleness** | Hardcoded staleness was set to 15 minutes, allowing stale observations to mislead verification. | Built `StaleEvidenceGuard.kt` with source-specific freshness policies (Telemetry: 10s, Screenshot: 8s, VLM: 15s) and clock injection. Integrated into `StateMismatchDiagnosticEngine`. |
| **Application Adapter Capabilities** | Blender adapter did not formally declare supported capabilities; Excel adapter lacked unified capability query. | Formalized `AdapterCapability` enum (`OBJECT_STATE`, `SELECTION_STATE`, `MODE_STATE`, `DOCUMENT_STRUCTURE`, `CELL_FORMULAS`, `VISUAL_CHANGE`, `LIVE_CHANGE_EVENTS`) across all application adapters. |

---

## 3. Official Documentation & Technical Decisions

1. **Microsoft Learn — Windows UI Automation Screen Scaling:**
   * *Documentation Fact:* `AutomationElement.BoundingRectangleProperty` returns physical screen coordinates relative to the virtual screen coordinate space.
   * *Architecture Decision:* Retain physical coordinates in `TargetPositionResult.boundsInScreenCoordinates`, but normalize to logical DIPs in `HighlightRegion` for Compose Desktop layout using `DpiCoordinateNormalizer`.
2. **Microsoft Learn — Excel Application Object & Running Object Table (ROT):**
   * *Documentation Fact:* `GetActiveObject('Excel.Application')` connects to the active Excel COM instance registered in the ROT. Non-visible or busy instances throw `RPC_E_CALL_REJECTED` (0x80010001) or `MK_E_UNAVAILABLE` (0x800401E3).
   * *Architecture Decision:* Handle both exceptions cleanly; return `AdapterSnapshotResult.Unavailable` without throwing unhandled exceptions or stalling the UI thread.
3. **Blender Python API (`bpy.msgbus` vs Socket Polling):**
   * *Documentation Fact:* `bpy.msgbus` does not trigger on 3D viewport spatial transforms or modal operators.
   * *Architecture Decision:* Retain the robust TCP socket polling bridge in `BlenderTcpStateProvider` with strict timestamp and version tracking to reject out-of-order frames.
