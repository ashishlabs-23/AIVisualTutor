# Phase 4 implementation record

## Objective

Phase 4 establishes an evidence-oriented perception layer over a user-selected screenshot, desktop region, and foreground application context. UIA, OCR, and optional visual-grounding outputs remain separate, provenance-bearing evidence. A deterministic evaluator makes per-request decisions and can conditionally invoke a configured visual provider. It does not implement model-backed inference, learned routing, continuous capture, or action execution.

This record describes repository implementation and recorded study scope. **IMPLEMENTED** does not imply real-application success. Real target evidence was not established in 4H. Build/test results below are reported only when run in this finalization environment.

## 4A — perception contracts

**IMPLEMENTED.** `PerceptionRequest`, `PerceptionResult`, `UiType`, `PerceptionSource`, and suspend `PerceptionEngine` provide a platform-neutral perception seam. Input is `BufferedImage`, selected desktop `Rectangle`, and `ApplicationContext`.

Important files: `context/PerceptionContracts.kt`, `context/VisualContext.kt`.

Contract tests are present. Full-suite execution for this finalization is reported in the handoff; the contract alone does not implement OCR, computer vision, VLM, or fusion.

## 4B — Windows UI Automation provider

**IMPLEMENTED.** `WindowsUiAutomationPerceptionEngine` uses the HWND already captured before selector focus. Its isolated STA PowerShell adapter queries `System.Windows.Automation`, gathers descendants, intersects their desktop rectangles with the selected desktop rectangle, deterministically ranks candidates, and maps supported UIA control IDs into the current `UiType` enum.

It records exposed name, AutomationId, type ID, enabled/offscreen state, ValuePattern value where available, candidate count, and provider status. The provider returns an explicit result for missing HWND, unavailable platform, DPI mapping limitations, query errors, and absent intersecting candidates.

Important files: `context/WindowsUiAutomationPerceptionEngine.kt`, `context/ApplicationContextProvider.kt`.

Deterministic provider/ranking/mapping tests are present. **NOT ESTABLISHED:** broad real-application coverage, custom-rendered application behavior, and high-DPI coordinate conversion. UIA geometry is compared against `selectedRegion` only when the provider's AWT display-coordinate precondition passes; this is not a transform for OCR evidence.

## 4C — UIA integration

**IMPLEMENTED.** `ContextProcessor` concurrently invokes OCR, classification, and its `PerceptionEngine`. A UIA failure or empty result does not prevent `VisualContext` construction. `VisualContext.perceptionResult` retains the result separately from OCR evidence.

Important files: `context/ContextProcessor.kt`, `context/VisualContext.kt`, `context/VisualContextAcquisition.kt`.

Integration-level tests cover request propagation, empty/failure degradation, and cancellation. Phase 3 selection lifecycle behavior was not redesigned.

## 4D — local OCR evidence

**IMPLEMENTED.** `TesseractOcrService` wraps local Tess4J/Tesseract on Windows. It returns recognized text plus word-level crop-pixel bounds, normalized recognition confidence where provided, language, reading-order index, and safe timing/provider metadata. The full `OcrResult` is kept on `VisualContext.ocrResult`; the existing extracted-text field is preserved for compatibility.

Important files: `context/TesseractOcrService.kt`, `context/VisualContext.kt`, `composeApp/build.gradle.kts`.

The tests include deterministic synthetic fixtures. The bundled configuration supplies English/orientation data; additional models require configured Tesseract data. **NOT ESTABLISHED:** accuracy on arbitrary real applications and conversion of OCR crop pixels to desktop coordinates.

## Coordinate spaces and comparison limitation

- OCR word bounds are `CROP_IMAGE_PIXELS`, relative to the selected `BufferedImage`.
- UIA `BoundingRectangle` values are Windows physical desktop/screen coordinates when the provider's display-coordinate precondition passes.
- `selectedRegion` is a Java AWT desktop/user-space `Rectangle`.
- The screenshot is a `BufferedImage` in crop-image pixels. Frozen capture clips the requested selection to the monitor-union bounds and renders the crop on an output grid scaled to the maximum intersecting monitor scale on each axis.

**NOT IMPLEMENTED:** a typed transform carrying the actual clipped crop origin and both output-grid scale factors from capture through to the evidence records. The current `scaleFactor` metadata is a single string value and is not a complete transform. The UIA provider's DPI precondition is a guard, not a mapping. Therefore OCR and UIA geometry must not be compared or merged. Registry `EvidenceGeometry` can tag physical desktop geometry; visual-provider geometry is restricted by evaluation to crop pixels or normalized crop. IoU refuses model-geometry versus registry-geometry comparisons and mismatched tags. No coordinate conversion is claimed, including for mixed-DPI displays.

## 4E — visual-grounding research boundary

**CONTRACT AND EXECUTION BOUNDARY IMPLEMENTED; REAL PROVIDER/MODEL NOT IMPLEMENTED.** `VisualGroundingProvider` accepts the screenshot, application context, supplied target description, and separate OCR/UIA evidence. Its geometry contract accepts only `CROP_IMAGE_PIXELS` or `NORMALIZED_CROP`; a separate registry type labels physical desktop bounds. The provider boundary represents AVAILABLE, UNAVAILABLE, NOT_CONFIGURED, FAILURE, and CANCELLED outcomes. The default provider is NOT_CONFIGURED, returns no semantic or geometric prediction, and reports `no_model_or_runtime_configured`. It does not download weights or invoke a fake model.

`EvidenceEvaluationCoordinator` invokes an AVAILABLE provider at most once for a target evaluation, and only when the deterministic evaluator does not ACCEPT existing evidence or the caller explicitly marks visual grounding as required. Missing/blank target descriptions never invoke the provider. An unavailable/not-configured provider is not called. Provider exceptions become explicit FAILURE evidence; coroutine cancellation propagates. Test doubles identify themselves as `TEST_FAKE_PROVIDER`; no production fake is installed.

Important files: `context/PerceptionContracts.kt`, `context/EvidenceEvaluator.kt`, `context/ContextProcessor.kt`, `context/VisualContext.kt`, `docs/PHASE_4E.md`.

`Main.kt` now snapshots `TutorController.currentTargetDescription` when a selected region is submitted. It reuses the active `TutorStep.instruction` unchanged; all 13 existing Blender/PDF/Excel mock steps have nonblank instructions, but some are action guidance rather than a short control name and have not been validated against real app UI. The `ContextProcessor`/acquisition APIs keep null as the default for other callers, and blank values remain absent. No model weights or model runtime were installed or run.

## 4E — deterministic evidence evaluation

**IMPLEMENTED; NOT A LEARNED OR ADAPTIVE MODEL.** `EvidenceEvaluator` returns ACCEPT, REFINE, ESCALATE, or ABSTAIN, with source statuses, original provider evidence, reason, visual-provider identity/diagnostic, and invocation status retained separately in `VisualContext.evidenceEvaluation`.

Policy thresholds are deterministic: UIA match confidence >= 0.80; OCR match confidence >= 0.80; visual semantic confidence >= 0.80 and geometric confidence >= 0.65. These are local evidence gates, not calibrated probabilities. A UIA 1.0 remains a deterministic-provider evidence marker, not model probability. A match requires all non-action/non-generic target tokens to occur in the corresponding source label/text. High-confidence UIA/OCR target disagreement escalates rather than averaging confidences. Strong UIA or OCR evidence can accept independently. A visual result can accept only when both semantic and geometric evidence pass their separate gates and its geometry is valid in crop pixels or normalized-crop coordinates. Cross-source geometry is never compared.

REFINE means an available visual provider may improve insufficient evidence; ESCALATE marks conflict or explicitly required visual evidence; ABSTAIN marks missing target, unavailable/failed provider, or insufficient evidence after an attempt. These are deterministic engineering rules, not measured accuracy improvements. Pure evaluator proposals may report PENDING; the coordinator records final not-needed, missing-target, unavailable, not-configured, invoked, returned-evidence, failed, or cancelled state. No learned router, repeated retry loop, abstention model, or adaptive threshold is implemented.

## 4F — local model feasibility

**NOT RUN — FEASIBILITY LIMITATION RECORDED.** ShowUI-2B's Hugging Face model-card metadata declares the weights MIT and identifies `Qwen/Qwen2-VL-2B-Instruct` as its base; Qwen's model-card metadata and repository license declare Apache-2.0. The ShowUI GitHub code repository declares Apache-2.0. The [ShowUI model card](https://huggingface.co/showlab/ShowUI-2B) and [Qwen model card](https://huggingface.co/Qwen/Qwen2-VL-2B-Instruct) examples move inference inputs to CUDA; neither documents CPU smoke-test results. This host has CPU-only PyTorch 2.13.0, `torch.cuda.is_available() == false`, no `transformers`, `qwen_vl_utils`, `accelerate`, or `safetensors`, and about 1.67 GiB free of 15.76 GiB system memory at inspection. A load/inference smoke test was not run: the required model runtime is absent and current free memory is below the multi-gigabyte model weight footprint. No packages or weights were installed/downloaded. This is an environment feasibility blocker, not evidence that ShowUI cannot run on any CPU.

**NOT IMPLEMENTED:** ShowUI, ZonUI, FocusUI, OmniParser, VLM fallback, model routing, and model benchmarking.

## 4G — controlled evidence evaluation foundation

**IMPLEMENTED.** `EvidenceEvaluationCase` and `EvidenceEvaluationRecord` preserve target descriptions, source availability, semantic and geometric evidence, agreement states, geometry quality, human ground truth, and insufficiency independently. IoU is deliberately unavailable across incompatible coordinate spaces, including crop pixels versus physical desktop screen coordinates.

Six deterministic synthetic cases cover text, button-like text, dense UI text, neighboring text, icon-only, and blank content. The OCR tests assert fixture-specific outputs, including `*` on the icon-only fixture; this is not real Windows UIA evidence or a benchmark.

Important files: `context/EvidenceEvaluation.kt`, `context/EvidenceEvaluator.kt`, `context/OcrFixtures.kt`, `docs/PHASE_4G.md`.

## 4H — real Windows study

**ATTEMPTED; REAL TARGET EVIDENCE NOT ESTABLISHED.** The dedicated record is `docs/PHASE_4H_REAL_WINDOWS_EVIDENCE_STUDY.md`. The recorded Notepad retry had no main HWND or visible window; its process was stopped. The browser retry stopped before launch because Playwright was unavailable in the checked Node and Python runtimes; no package was installed. No target or project screenshot was established, and neither provider was run on a real target. No UIA/OCR agreement, conflict, target accuracy, or geometry comparison is claimed. The study decision remains **C**: evidence is insufficient.

Important files: `docs/PHASE_4H_REAL_WINDOWS_EVIDENCE_STUDY.md`.

The 4H study did not change the UIA provider, OCR provider, capture, selection, or Phase 1–3 behavior. Finalization changes are limited to Phase 4 coordinate-space typing/test coverage and Phase 4 documentation.

The 4-Gap follow-up verified an existing target source and wired it through the selected-region processing call; it did not add a UI field. It did not run a ShowUI model or assemble/run the requested 15–20 real-screenshot calibration cases: the required model smoke test was blocked by absent runtime dependencies/insufficient free memory, and no real screenshot results are claimed. The Phase 4 synthetic test suite does not substitute for that calibration.

Finalization validation before the 4-Gap follow-up: the complete `:composeApp:test --rerun-tasks` suite passed (81 tests, 0 failures/errors/skips); the Phase 4 `context.*` selection passed (56 tests, 0 failures/errors/skips); and `:composeApp:build --rerun-tasks` passed. After the 4-Gap follow-up, the complete suite passed (83 tests, 0 failures/errors/skips), the combined `context.*` and `TutorControllerTest` selection passed (63 tests, 0 failures/errors/skips), and `:composeApp:build --rerun-tasks` passed. The context-only selection contained 56 tests. These automated results do not substitute for manual real-window observations.

## Research constraint and future work

The documented geometry-authority constraint is UIA, then OCR, then future visual grounding, then VLM only when necessary. Fusion and a geometry resolver are **NOT IMPLEMENTED**. The evaluator's deterministic provider invocation is implemented, but there is no learned/adaptive evaluator. Future work requires a carried and tested crop-to-desktop transform plus human-verified real Windows cases before cross-source geometry comparison or claims about real-target accuracy are justified.
