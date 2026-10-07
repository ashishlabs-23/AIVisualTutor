# Phase 4G — Windows Evidence Evaluation and Perception-Gap Study

## Scope and retained evidence

This is a controlled Phase 4 study. Its synthetic evidence evaluation cases do not alter capture, selection, UI Automation, OCR, or application state. The production `EvidenceEvaluator`/`EvidenceEvaluationCoordinator` now consume the independent provider outputs at the end of `ContextProcessor`; test images are not real Windows windows, and no manual real-application evaluation was performed.

## 4-Gap follow-up status

The live selection path now passes `TutorController.currentTargetDescription`, sourced unchanged from the active `TutorStep.instruction`, through `VisualContextAcquisition.process` to `ContextProcessor`. All 13 existing mock steps have a nonblank instruction. This confirms description propagation, not that each instruction is a precise real-app control label; no UI target is inferred when a caller passes null/blank.

ShowUI-2B and Qwen2-VL-2B-Instruct license metadata were checked against their Hugging Face model cards/repos (MIT and Apache-2.0, respectively). The local ShowUI smoke test was **NOT RUN**: Transformers, `qwen_vl_utils`, Accelerate, and Safetensors are not installed; PyTorch is CPU-only; available RAM at inspection was about 1.67 GiB of 15.76 GiB, inadequate for a safe model load. No model dependencies/weights were installed. Therefore the requested real-image calibration set was **NOT ASSEMBLED** and the full pipeline calibration was **NOT RUN**. No results, correct-case count, false accepts, over-caution cases, or per-provider latency are established. The synthetic fixture tests below are not substituted for real screenshots.

| Source | Retained evidence | Geometry and provenance |
|---|---|---|
| UI Automation | `selectedObject`, mapped `UiType`, optional `visibleText` from ValuePattern, bounding rectangle, AutomationId, enabled/offscreen state, status/candidate/provider metadata | Physical desktop coordinates only when the DPI precondition passes. The result does not retain all candidates. |
| OCR | Aggregate text/confidence, word text/bounds/confidence, language, word index, engine/provider/status/timing metadata | `CROP_IMAGE_PIXELS`, relative to the selected `BufferedImage`. |

The selected rectangle is Java AWT desktop/user space. The frozen capture path clips it to monitor-union bounds and creates an image using per-axis output scales; those exact transform inputs are not carried as typed OCR evidence. UIA's DPI check does not provide the missing mapping. **Cross-source geometry remains unavailable**, including for mixed-DPI displays. No UIA/OCR rectangle comparison is measured here.

`ApplicationContext` retains application/process/title, PID, desktop window bounds, and captured HWND. `VisualContext` keeps independent `perceptionResult` and `ocrResult`. `PerceptionRequest` retains screenshot, selected desktop rectangle, and application context. Existing UIA and OCR tests exercise their provider seams; real Windows UIA execution and human correctness verification are not part of this study.

## Evaluation record

`EvidenceEvaluationCase` and `EvidenceEvaluationRecord` in `context/EvidenceEvaluation.kt` remain study records that keep source availability, semantic and geometric evidence, agreement, geometry quality, human ground truth, and insufficiency separate. They do not produce a fused confidence. The distinct production `EvidenceEvaluator` in `context/EvidenceEvaluator.kt` applies fixed documented thresholds and returns ACCEPT / REFINE / ESCALATE / ABSTAIN; its coordinator conditionally invokes a configured visual provider and records availability/invocation state. This is deterministic policy, not a learned adaptive evaluator.

IoU is available only for two bounding regions in the same explicitly declared geometry type and coordinate space. It returns no value for UIA physical desktop geometry versus OCR crop-pixel geometry, visual-provider geometry versus evidence-registry geometry, points, or missing bounds. Deterministic tests cover incompatible crop, normalized-crop, and physical-desktop tags. The production evaluator does not compare cross-source geometry and validates visual geometry only in crop-pixel or normalized-crop spaces. This prevents an unverified coordinate transformation from becoming a measurement.

## Controlled Phase-4 evaluation cases

The fixtures are deterministic synthetic images. Their expected regions are crop-image pixels and their expected descriptions are fixture annotations, not model outputs or a benchmark.

| Case | Target and variants | OCR observed in this run | UIA observed | Ground truth | Result |
|---|---|---|---|---|---|
| `text-only` | `Visible text baseline 42`; `Visible text`; `the visible text` | Test asserts text and word boxes | NOT RUN — fixture has no HWND/window | Synthetic expected region | OCR test coverage only |
| `button-like` | `Save`; `Save button`; `the save control` | Test asserts word evidence intersects expected region | NOT RUN | Synthetic expected region | OCR tests cover visible text, not button semantics |
| `dense-ui` | `File`; `File menu`; `the File control` | Test asserts structured word evidence intersects the annotated region | NOT RUN | Synthetic expected region | OCR test coverage only; no semantic disambiguation result |
| `neighboring-text` | `Save`; `Save button`; `the left Save control` | Test asserts `Save` and `As` evidence and expected-region intersection | NOT RUN | Synthetic expected region | OCR test coverage only; no model result |
| `icon-only` | `star icon`; `the star control`; `the icon in the center` | Test asserts `*` with a box intersecting the icon region | NOT RUN | Synthetic expected region | OCR false-positive behavior asserted by a test; no icon identity |
| `empty-non-text` | no target | Test asserts explicit empty OCR evidence | NOT RUN | Synthetic non-target region | Empty-evidence test coverage; no abstention policy |

UIA is **not** marked unavailable in these cases: it was not invoked because fixture images have no real Windows window/HWND. Consequently, there is no observed UIA/OCR agreement, no observed UIA/OCR conflict, and no cross-source geometry metric in this study.

## Target-description variants

Variants are retained per case with `expectedSameTarget = true`; e.g., `Save`, `Save button`, and `the save control`. They establish the evaluation input needed for a later grounding study. No language model was invoked, so no instruction-sensitivity outcome is claimed.

## Observed coverage and failure modes

The OCR tests specify coverage for text-only, button-like, dense UI, neighboring text, blank, and icon-only fixtures. Their assertions are synthetic regression checks, not real-app or benchmark results. OCR confidence remains OCR recognition confidence and is never treated as target correctness.

The test-defined limitations are that OCR does not supply control semantics, asterisk OCR on the icon fixture does not establish icon identity, and empty OCR evidence does not imply an implemented abstention policy. No UIA failures, stale/offscreen candidates, multiple-UIA conflicts, or real-application outcomes are established by this synthetic fixture suite.

No real-world geometry quality claim is made: OCR tests assert intersections against synthetic expected regions; UIA geometry was not observed in this study. Cross-source IoU is `NOT_COMPUTABLE` because no transform is implemented.

## Interpretation

The synthetic test cases cover word geometry and cases where OCR alone does not encode button/icon semantics or target intent. They do not establish a real-app UIA-versus-OCR gap or target accuracy. Evidence is insufficient to decide whether visual grounding is needed for Windows application targets or to validate the deterministic routing policy on real targets; it does not justify a learned adaptive evaluator. A later real-window study needs human-verified targets, captured application context/HWND, UIA result, OCR result, and a verified same-space geometry mapping before it can measure source agreement.

No GUI-grounding model, VLM, router, evaluator, threshold, cache, or change detector was implemented.
