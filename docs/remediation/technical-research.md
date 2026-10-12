# AI Visual Tutor: Technical Research & Architectural Feasibility

**Document:** `docs/remediation/technical-research.md`  
**Date:** 2026-10-12  
**Boundary Notice:** **THIS IS NOT PHASE 9.** This research evaluates and designs remediations for the existing system.

---

## 1. Feature A: Local Visual Frame-Difference Verifier

### 1.1 Problem Statement
When learners perform tutorial tasks in applications without deep telemetry adapters (such as Paint, PDF viewers, browser editors, or generic CAD tools), the tutor cannot determine whether a learner has interacted with the interface. Currently, the system must either rely on unverified manual progression or make expensive multimodal cloud VLM requests.

### 1.2 Official Documentation & References
* **Java Desktop AWT (`java.awt.image.BufferedImage`, `java.awt.image.Raster`, `java.awt.image.DataBufferInt`):**
  * Direct raster buffer inspection provides sub-millisecond per-pixel access without native bridge overhead.
* **Wang, Bovik, Sheikh, Simoncelli (2004) — Structural Similarity (SSIM):**
  * SSIM evaluates luminance, contrast, and structural correlation. However, calculating full window-wide SSIM ($O(W \times H)$ convolution) across 1920×1080 frames introduces 40–120ms latency on CPU, whereas localized thresholded pixel differencing runs in 2–5ms.
* **Microsoft Learn — Display Scaling and DPI Awareness:**
  * Screen capture frames can have different physical pixel dimensions if the window moved across displays with differing DPI scaling factors.

### 1.3 Comparison of Candidate Approaches

| Approach | Latency (1080p) | Memory Overhead | Visual Noise Immunity | Semantic Accuracy | Recommendation |
|---|---|---|---|---|---|
| **A. Absolute Pixel Diff** | ~2 ms | Minimal | Very Low (fails on caret blinking/cursor) | None (change only) | Insufficient alone |
| **B. Thresholded Color Diff + ROI** | ~4 ms | Minimal | High (filters small rendering noise and restricts to canvas/window) | Detects bounded localized edits | **RECOMMENDED (Primary)** |
| **C. Full-Frame SSIM** | ~80 ms | High (multiple float buffers) | Medium (structural drift can mask small edits) | Inconclusive for small UI icons | Overkill for initial frame check |
| **D. Hybrid Mask + Bounding Cluster** | ~6 ms | Low | High (extracts bounding box of changed area) | High (provides spatial bounds of change) | **RECOMMENDED (Enhancement)** |

### 1.4 Technical Decision & Design
* **Implementation:** `VisualFrameDiffVerifier.kt`
* **Mechanism:**
  1. Validates dimensions; if dimensions differ (e.g. window resized), resamples or marks as `DIMENSIONS_CHANGED`.
  2. Supports optional `Rectangle` Region of Interest (ROI) to isolate the canvas from toolbars, title bars, and blinking cursors.
  3. Computes per-pixel RGB Euclidean distance: $d = \sqrt{\Delta R^2 + \Delta G^2 + \Delta B^2}$.
  4. Pixels with $d > \text{noiseThreshold}$ are marked as changed.
  5. Computes `changedRatio` ($\frac{\text{changedPixels}}{\text{totalPixels}}$) and extracts the minimum bounding box enclosing all changed pixels (`changedBoundingBox`).
  6. Filters out single-pixel noise and transient blinking carets ($< 30$ pixels).
  7. Returns structured `VisualDiffResult` with `status` (`NO_CHANGE`, `MEASURABLE_CHANGE`, `WINDOW_RESIZED`, `INVALID_IMAGE`), `changedRatio`, `changedBoundingBox`, and `durationMs`.

---

## 2. Feature B: Deterministic State Mismatch Diagnostics & Recovery

### 2.1 Problem Statement
When a learner clicks "I'm Stuck", the existing system constructs a prompt and asks an LLM to explain what went wrong. If the active application state already contains an explicit violation (e.g. Blender is in `OBJECT` mode instead of `EDIT_MESH`, or `Camera` is selected instead of `Cube`), querying an LLM introduces 500–2000ms latency, risks hallucination, and costs tokens for an answer that is deterministically knowable.

### 2.2 Official Documentation & References
* **Blender 5.2 Python API (`bpy.context.mode`, `bpy.context.active_object`, `bpy.context.selected_objects`):**
  * Blender modes are mutually exclusive enums (`OBJECT`, `EDIT_MESH`, `SCULPT`, `POSE`, etc.).
  * Active object vs. selected objects are strictly defined.
* **Microsoft Learn — UI State & Verification Patterns:**
  * Deterministic state comparison ($ExpectedState \setminus ObservedState$) identifies preconditions, invariants, and postconditions.

### 2.3 Comparison of Candidate Approaches

| Approach | Speed | Reliability | Actionability | Token Cost | Recommendation |
|---|---|---|---|---|---|
| **A. Pure LLM Interpretation** | 800–2500 ms | Variable (depends on prompt) | Moderate | High | Suboptimal as first step |
| **B. Pure Deterministic Rules** | < 1 ms | 100% Deterministic | High for supported fields | Zero | Excellent for known fields |
| **C. Hybrid: Deterministic First + LLM Fallback** | < 1 ms (common) / 800 ms (fallback) | 100% for known states; LLM for ambiguous | Very High (structured recovery actions) | Minimal | **RECOMMENDED** |

### 2.4 Technical Decision & Design
* **Implementation:** `StateMismatchDiagnosticEngine.kt`
* **Diagnostic Categories:**
  1. `APPLICATION_MISMATCH`: Focused application differs from expected task application.
  2. `MODE_MISMATCH`: Active mode differs (e.g. Expected `EDIT_MESH`, Observed `OBJECT`).
  3. `SELECTION_MISMATCH`: Wrong object selected (e.g. Expected `Cube`, Observed `Camera`).
  4. `OBJECT_MISSING`: Expected target object does not exist in the scene.
  5. `UNEXPECTED_OBJECT`: Object exists that should have been deleted.
  6. `TRANSFORM_UNMODIFIED`: Object location/rotation/scale has not changed from baseline.
  7. `DOCUMENT_STRUCTURE_MISMATCH`: Expected worksheet or cell not active.
* **Recovery Action Formulation:**
  For each detected mismatch, the engine emits a machine-readable `RecoveryAction` (e.g., `SwitchModeAction(targetMode = "EDIT_MESH", shortcut = "Tab")`, `SelectObjectAction(targetName = "Cube")`).

---

## 3. Feature C: Intelligent `RegionFocus` Sub-Cropping

### 3.1 Problem Statement
When local UI Automation and OCR cannot identify a target and the tutor must escalate to a multimodal Visual Language Model (VLM), transmitting a full 1920×1080 or 3840×2160 screenshot has major drawbacks:
1. High token consumption (Groq and Gemini bill multimodal requests proportionally to image dimensions/tiles).
2. Small icon features (such as a 20×20 toolbar button) become blurry when scaled down to fit standard VLM vision encoder patches (e.g. 768×768 or 1024×1024).

### 3.2 Official Documentation & References
* **Google Gemini API Documentation (Image Sizing & Vision Prompts):**
  * Images larger than 3072×3072 are downscaled. Gemini divides images into 768×768 tiles. Cropping directly to the active application window or suspected panel region preserves native pixel sharpness at lower tile costs.
* **Groq API Documentation (Multimodal Inputs via `qwen/qwen3.8-27b`):**
  * Requires images with at least 32 pixels in each dimension; smaller images trigger HTTP 400.
* **Microsoft Learn — Windows Window Bounds (`GetWindowRect`):**
  * The target application window bounds define the true region of interest, excluding other windows and the desktop background.

### 3.3 Technical Decision & Design
* **Implementation:** `RegionFocusService.kt`
* **Heuristic Priority Hierarchy:**
  1. **Candidate Target Neighborhood:** If an OCR or UIA candidate matched with medium confidence, crop to its bounding box expanded by a 200px contextual margin.
  2. **Active Application Window:** If the application window bounds are known (`ApplicationContext.windowBounds`), crop to the application window, excluding desktop wallpaper and background apps.
  3. **Task Area Heuristic:** If step definition specifies a `targetAreaDescription` (e.g. "Left Toolbar", "Properties Panel"), crop to that relative panel quadrant.
  4. **Full Screenshot Fallback:** When no window or region metadata is available.
* **Coordinate Invariant Preservation:**
  The service computes and retains a `CropToScreenTransform`:
  $$\begin{aligned} x_{\text{screen}} &= x_{\text{crop}} + \text{cropOffsetX} \\ y_{\text{screen}} &= y_{\text{crop}} + \text{cropOffsetY} \end{aligned}$$
  Ensuring any point returned by the VLM in crop coordinates maps back to physical desktop coordinates.

---

## 4. Feature D: Capability-Aware Application Adapters & Microsoft Excel Telemetry

### 4.1 Problem Statement
The tutor currently treats Blender as deeply verified and all other applications as static/unsupported. A scalable architecture must allow adapters to explicitly declare what they can and cannot observe. Furthermore, Microsoft Excel is the most common non-Blender tutoring target in the curriculum.

### 4.2 Official Documentation & References
* **Microsoft Learn — Excel Application Object Model:**
  * `Excel.Application` exposes `ActiveWorkbook`, `ActiveSheet`, and `ActiveCell`.
  * `Range.Address` returns coordinate strings (e.g. `"$B$5"`).
  * `Range.Formula` returns formulas (e.g. `"=SUM(B2:B4)"`).
  * `Range.Value2` returns calculated or raw cell contents without formatting artifacts.
* **Microsoft Learn — Excel Application & Worksheet Events:**
  * `Worksheet.Change`: Fires when cells are modified by the user or external links. Does not fire during automatic calculation.
  * `Workbook.SheetSelectionChange`: Fires when active cell or selection changes.
* **Windows COM Automation on Windows 11/10:**
  * Running Excel instances register in the Windows Running Object Table (ROT).
  * Accessible via `[Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')` via PowerShell or C# helper.

### 4.3 Candidate Architectures for Excel Telemetry

| Architecture | External Dependency | Reliability | Security / Privileges | Deployment Complexity | Recommendation |
|---|---|---|---|---|---|
| **A. Third-Party JVM COM (Jacob/JCom)** | Native DLL + unmaintained jar | Medium | Native memory risk | Complex (DLL extraction) | High dependency risk |
| **B. Native PowerShell STA Dispatcher** | Built-in Windows components | High | Standard user permissions | Zero extra packaging | **RECOMMENDED** |
| **C. Windows UI Automation Fallback** | Built-in Windows accessibility | High (structural) | Standard user permissions | Zero | **RECOMMENDED (Fallback)** |

### 4.4 Technical Decision & Design
* **Implementation:** `ExcelApplicationAdapter.kt`
* **Adapter Capabilities:**
  ```kotlin
  enum class AdapterCapability {
      OBJECT_STATE,
      SELECTION_STATE,
      MODE_STATE,
      DOCUMENT_STRUCTURE,
      CELL_FORMULAS,
      VISUAL_CHANGE,
      LIVE_CHANGE_EVENTS
  }
  ```
* **Excel Adapter Operations:**
  * Queries active Excel workbook name, active worksheet name, active cell address, active cell formula, and active cell value.
  * Connects directly to `ApplicationAdapter` contract.
  * In the absence of an open Excel instance, reports `AdapterHealthStatus.UNAVAILABLE` and falls back gracefully to UIA accessibility tree observation without crashing.
