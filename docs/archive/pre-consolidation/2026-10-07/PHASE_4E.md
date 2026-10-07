# Phase 4E — GUI Visual-Grounding Evaluation Baseline

## Current repository evidence

| Source | Can provide in this implementation | Cannot provide from its current contract | Coordinates |
|---|---|---|---|
| Windows UI Automation | Selected intersecting descendant's exposed `Name`, `AutomationId`, control type ID/mapped `UiType`, bounding rectangle, enabled/offscreen state, and `ValuePattern` value; candidate count/status/provider metadata | Pixels, icon appearance, or meaning the application does not expose. Text is `visibleText` only when the UIA Value pattern supplied it. Control-type mapping is intentionally partial. | Physical desktop/screen coordinates when the provider's DPI precondition passes; otherwise explicit empty unsupported result |
| Tesseract OCR | Recognized text, word boxes, normalized confidence, configured language and provider/timing metadata | Control name/ID/type, interactivity, icon identity, or semantic role | Selected image/crop pixels (`CROP_IMAGE_PIXELS`), without conversion to desktop space |

**Coordinate comparison limitation:** `selectedRegion` is a Java AWT desktop/user-space rectangle. Frozen capture clips the selection and renders a crop using per-axis maximum monitor scales, but the crop's clipped origin and per-axis output scales are not carried as typed transform data with OCR evidence. The UIA DPI precondition is only a guard; it does not supply a mapping. OCR crop-pixel bounds and UIA physical-screen bounds therefore remain incomparable. No transform, including mixed-DPI mapping, is implemented or established.

The code can attempt standard UIA discovery and map several common types; this repository has not established that any particular target app (including Blender) exposes useful elements. OCR and UIA implementations were tested, but the 4E work did not run either against a real application target.

The task-conditioned visual-grounding request/result and execution boundary now exists, as does deterministic decision/routing policy. The current selection processing passes the active mock `TutorStep.instruction` as target description; it remains null for callers with no description. Instructions are not validated as concise or correct labels for real application controls. The default provider is not configured, so no real visual provider is invoked. There is no semantic fusion or geometry resolver. Existing implementation details are in `context/WindowsUiAutomationPerceptionEngine.kt`, `context/TesseractOcrService.kt`, `context/PerceptionContracts.kt`, `context/EvidenceEvaluator.kt`, `context/VisualContext.kt`, `context/ContextProcessor.kt`, and `tutor/TutorController.kt`.

## Research candidates (not installed or run)

| Candidate | Evidence / output | License and deployment evidence | Assessment for this app |
|---|---|---|---|
| ShowUI-2B | GUI vision-language-action model; repository example accepts image + natural-language target and returns normalized screenshot point `[x,y]`. | Hugging Face model card labels weights MIT; ShowUI code repository is Apache-2.0. Published Windows local-run instructions call for CUDA and at least 6 GB VRAM for their 4-bit variant. The sample uses PyTorch/Transformers and CUDA. | Closest small, instruction-conditioned point-grounding experiment. CUDA profile is not available on the inspected host. |
| ZonUI-3B | GUI-grounding VLM with text/icon desktop/web/mobile results; cross-resolution focus. | Hugging Face model card labels Apache-2.0. Official repository publishes inference/evaluation code and describes a single RTX 4090 24 GB training environment; model repository currently lists about 30.1 GB of files. CPU inference requirements are not documented in the inspected sources. | Strong high-resolution research candidate, but its documented artifact and compute footprint are not a fit for an unqualified in-process desktop dependency. |
| FocusUI (2B/3B) | GUI-grounding model with query-guided visual token selection; official repository has 2B/3B checkpoints and ScreenSpot-Pro evaluation script. | Requires a separate Python 3.12 environment and downloads model checkpoints. The inspected project README did not state a standalone model license or CPU support; those need auditing for a controlled run. | Relevant newer efficiency candidate; good comparison point after artifact/license/runtime audit. |
| OmniParser v2 | Separate screen parser: detects regions/icons and captions them, returning candidate boxes and labels rather than a single instruction-grounded point. | Separate Python stack and multiple weights. Current v2 detector/caption weight notes identify MIT components; earlier detector variants retain AGPL terms. | Potential visual detector baseline for icon/region proposals, but broader and more complex than the minimum semantic grounding boundary. |

Published benchmark scores are not project results. ScreenSpot-Pro contains 1,581 professional GUI grounding instructions over 23 apps and three operating systems, with high-resolution, text and icon targets; its original paper reports substantial difficulty for then-current models and improved results from search-area refinement. Later papers/models report different numbers under their own methods and evaluation protocols. None of those numbers establish performance on this app's captures. [ScreenSpot-Pro paper](https://arxiv.org/abs/2504.07981) · [ShowUI model card](https://huggingface.co/showlab/ShowUI-2B) · [ShowUI Windows run requirements](https://github.com/showlab/computer_use_ootb) · [ZonUI model card](https://huggingface.co/zonghanHZH/ZonUI-3B) · [ZonUI repository](https://github.com/Han1018/ZonUI-3B) · [FocusUI repository](https://github.com/showlab/FocusUI) · [OmniParser repository](https://github.com/microsoft/OmniParser)

## Decision

**B — A dedicated GUI-grounding provider is justified, but model integration belongs in a separate controlled experiment.** The evidence types are complementary and custom/visual-only target grounding is an unfilled capability. This host has 16 GiB system memory and `nvidia-smi` is unavailable; that alone does not prove no accelerator exists, but no NVIDIA CUDA device was discoverable. The published model stacks require separate Python/model artifacts, and ShowUI's Windows instructions explicitly assume CUDA. No weights or new runtime dependencies are added here.

The `VisualGroundingProvider` contract is defined in `PerceptionContracts.kt` and is wired through `EvidenceEvaluationCoordinator` at the end of `ContextProcessor`'s existing OCR/UIA processing. It accepts a selected screenshot, application context, supplied target instruction, and the original OCR/UIA evidence. It returns separate semantic and geometric evidence, provider ID, availability, execution location, optional latency and metadata. The default provider is NOT_CONFIGURED and returns no prediction; no real provider, weights, or runtime were installed or run. Provider outcomes include AVAILABLE, UNAVAILABLE, NOT_CONFIGURED, FAILURE, and CANCELLED. `PerceptionSource` and `PerceptionResult` remain unchanged; existing sources are not collapsed or replaced.

## Evidence and geometry constraints

Semantic identity/confidence and geometric bounds/point/confidence are independent nullable evidence. Visual-grounding geometry names crop image pixels or normalized crop coordinates. The separate evidence-registry geometry type can tag physical desktop screen bounds for UIA, but does not make those bounds comparable with crop pixels. The evaluator rejects comparisons across model/registry geometry types and differing tags. No coordinate conversion or fusion is implemented.

## Deterministic evaluation and invocation

`EvidenceEvaluator` exposes ACCEPT, REFINE, ESCALATE, and ABSTAIN decisions. Current fixed gates are UIA >= 0.80, OCR >= 0.80, visual semantic >= 0.80, and visual geometry >= 0.65. These values are deterministic engineering thresholds and are not calibrated probabilities or measured accuracy claims. A 1.0 UIA value denotes deterministic provider evidence, not a model probability. Concise, high-confidence UIA/OCR target-label disagreement escalates; unrelated dense OCR text is not treated as a direct label conflict. A strong single-source UIA or OCR target match may accept. Visual acceptance requires separate strong semantic and geometric evidence in validated crop-pixel or normalized-crop bounds.

The coordinator runs a configured provider at most once per target evaluation when initial evidence does not accept or the caller explicitly requires visual grounding. A missing or blank target never invokes a provider. A default NOT_CONFIGURED provider is not invoked; its diagnostic and invocation state are retained. Exceptions produce a FAILURE result without aborting the existing perception pipeline; coroutine cancellation propagates. `ContextProcessor` currently has no application-provided target description, so existing calls record an explicit missing-target decision and never invent a target. The pure evaluator may report PENDING before coordination; the coordinator returns a finalized invocation status. Test providers use the ID `TEST_FAKE_PROVIDER`; no fake provider is registered in production.

`EvidenceEvaluationResult` records each source status, the original per-source observations, final decision/reason, visual-provider availability, diagnostic, and invocation status. No confidence is merged, and evidence provenance is preserved. REFINE/ESCALATE are deterministic routing decisions, not a learned adaptive evaluator. The ABSTAIN decision is implemented as policy output; no learned abstention model, geometry resolver, retry loop, or calibration experiment is implemented.

Phase 4 design constraint for a future fusion stage: **UIA → OCR → visual grounding → VLM only if necessary** for geometric authority. A semantic model answer alone does not override trusted UIA/OCR geometry. This is a design rule, not an implemented selection algorithm.

## Fixtures and future experiment

The six synthetic OCR fixtures also have grounding annotations: image ID, synthetic application, optional target description, expected crop-pixel region, target type, source annotations and coordinate space. They are regression fixtures, not an accuracy dataset; ScreenSpot-Pro was not imported.

Planned comparison, not run:

1. UI Automation only.
2. OCR only.
3. UI Automation + OCR.
4. A selected GUI-grounding provider, preserving the first three outputs independently.
5. Adaptive routing only after provider baselines exist.

Measure target identification accuracy, box IoU, point-in-target, latency, CPU/GPU use, provider invocation rate and failure rate. Report model/version, weights, prompt, input resolution, coordinate transforms, runtime/hardware, and fixture provenance for reproducibility. Do not infer benchmark accuracy from the synthetic fixtures.

Future change-detection point: after capture/crop and before provider dispatch at the `ContextProcessor` boundary. No detector or cache is added.

## Environment and verification

ShowUI-2B Hugging Face model-card metadata declares MIT and lists Qwen2-VL-2B-Instruct as base; Qwen's model-card metadata declares Apache-2.0, and its repo contains the Apache license. On this host, Python 3.12.10 and CPU-only PyTorch 2.13.0 are present; Transformers, `qwen_vl_utils`, Accelerate, and Safetensors are absent; `nvidia-smi` is unavailable; system memory is 15.76 GiB with about 1.67 GiB free at inspection. The ShowUI load/inference smoke test was NOT RUN because the required runtime is absent and current free memory does not permit a safe model load. No dependencies or weights were installed. No real visual grounding result or 15–20-case calibration was obtained.
