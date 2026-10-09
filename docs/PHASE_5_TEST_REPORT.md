# Phase 5 Test and Validation Report

> Historical implementation snapshot. Subsequent final verification, the Windows application launch, the live UIA fixture run, and the new CSV five-mode evaluation are documented in [`PHASE_5_RUNTIME_AUDIT_2026-10-09.md`](PHASE_5_RUNTIME_AUDIT_2026-10-09.md).

**Checkout:** 9705dd3e51d2cde22b16094a6108377280eb2213  
**Validation:** 2026-10-09  
**Status:** **IMPLEMENTED EXPERIMENTAL PIPELINE; NOT READY TO FREEZE**

## Audit and architecture

The repository uses Kotlin/JVM 17, Compose Desktop, one Gradle module (:composeApp), and Windows native integration through PowerShell/.NET. Existing integration points were:

- Main.kt: capture/selection/preview entry points.
- VisualContextAcquisition, ContextProcessor: selected screenshot and legacy UIA/OCR processing.
- PerceptionEngine / WindowsUiAutomationPerceptionEngine: UIA, originally exposed as one selected result.
- OCRService / TesseractOcrService: OCR words with crop-pixel bounds.
- VisualGroundingProvider / LlamaCppVisualGroundingProvider: opt-in UGround-V1-2B through llama.cpp, returning a normalized point with no semantic confidence.
- EvidenceEvaluator: existing ACCEPT/REFINE/ESCALATE/ABSTAIN policy, preserved for Phase 4 calls.
- CalibrationResultStore: schema-v3 capture and replay persistence, preserved.

The additive Phase 5 pipeline lives in AdaptiveGrounding.kt and GroundingResearch.kt. It adds explicit provider routing, normalization, candidate generation/association, deterministic fusion/decision, content-addressed screenshot experiment persistence, replay loading, and ground-truth metrics. The preview can edit the target, select a mode, run it, display provider statuses, and overlay accepted geometry. WindowsUiAutomationPerceptionEngine.perceiveCandidates() supplies the full ranked candidate list to Phase 5 while the original perceive() contract is unchanged.

## Mode routing and isolation

| Mode | Providers |
|---|---|
| UIA_ONLY | UIA only |
| OCR_ONLY | OCR only |
| UIA_OCR | UIA + OCR; vision never invoked |
| VISION_ONLY | Vision only; no UIA/OCR evidence passed to the adapter |
| ADAPTIVE | UIA + OCR first; vision only when acceptance is not met, or with ALWAYS policy |

Tests assert provider call sets for all five modes. Same-preview mode reruns use the same crop/target/case ID with independent provider executions and unique run IDs. GroundingExperimentRunner.compareAllModes() runs all five sequentially for the same screenshot. Provider errors/timeouts are retained while remaining sources can still contribute. Missing targets skip all providers and produce ABSTAIN/INVALID_TARGET.

## Coordinate, candidate, and fusion policy

OCR uses crop/screenshot pixels; UIA uses physical desktop coordinates; UGround uses normalized crop coordinates. Phase 5 only converts UIA when the caller supplies a screen-to-screenshot transform. An absent transform remains INCOMPATIBLE_COORDINATES; it is not treated as disagreement or a valid screen target. The preview supplies the transform only if detected display transforms are identity. Mixed-DPI mapping remains unverified.

Candidates preserve source observations, text, provider confidence, text similarity, source metadata, original geometry and normalized geometry. OCR words are grouped by line only where the grouped text matches the target sufficiently; otherwise words stay separate. Candidate association requires compatible screenshot-pixel coordinates, adequate text similarity, and adequate IoU; the engine does not average boxes.

Fusion score is a declared heuristic: 0.8 × mean target-text similarity + 0.2 × cross-source agreement. Provider confidences are preserved but not combined. Defaults are text similarity 0.72, acceptance score 0.78, winner margin 0.12, and association IoU 0.20. These choices are configurable, uncalibrated engineering thresholds; they are not probabilities. Ambiguous and conflicting evidence does not auto-accept.

## Vision integration

Phase 5 calls the existing configured UGround adapter; it does not replace it with a fake. The real adapter parses a point in [0,1000), converts it to normalized crop coordinates, and persists raw model output/runtime metadata. A point remains a point; no box is invented. Since the real adapter supplies no semantic evidence or confidence, vision-only point results are retained as proposals and receive REFINE, not accepted target status.

**Real model execution: NOT RUN.** This checkout has no model artifacts or llama.cpp executable at the expected install locations. Available RAM was approximately 1.65 GB, below the existing 3.63 GB memory guard. No model was downloaded, and the guard was not bypassed. Provider behavior was tested only with deterministic mocks plus existing adapter unit tests; this is not evidence of real Phase 5 inference.

## Persistence and research metrics

Phase 5 run records are written next to existing calibration data under phase5-experiments; result JSON writes are atomic, manifest appends are locked, and duplicate run IDs are rejected. Screenshot PNGs are content-addressed and deduplicated so records remain replayable after the transient preview closes. Records retain target, case/run/experiment IDs, mode, screenshot reference/dimensions, provider statuses/latencies/metadata, observations, candidates, scores, thresholds, model ID, decision/reason, and proposed/accepted coordinates. Replay loading reconstructs a request from the saved result and screenshot.

GroundTruthAnnotation is a separate object and is never sent to providers. GroundingResearchMetrics computes box IoU and center error or point-distance accuracy with a caller-supplied tolerance; reports coverage, selective accuracy, false acceptance, abstention, valid prediction, provider failure, and adaptive vision invocation rates. Cases count only when a confirmed annotation in screenshot pixel coordinates is supplied; incompatible coordinate spaces are excluded. This checkout contains no new Phase 5 labeled experiment runs, so no research accuracy metric is claimed.

## Automated tests and commands

Baseline before edits: .\gradlew.bat test passed 118 tests (0 failures/errors/skips).

Final verification:

| Command | Result |
|---|---|
| .\gradlew.bat test | PASS — 134 tests, 0 failures, 0 errors, 0 skipped. This includes 16 Phase 5 tests and all 118 existing tests. |
| .\gradlew.bat :composeApp:build | PASS — build successful (up to date after the final test compilation). |
| git diff --check | PASS — whitespace check clean. |

Phase 5 automated coverage includes text normalization, screen-to-image scaling, bounds validation, no fabricated provider confidence, UIA unaligned-coordinate behavior, all five mode routes, adaptive vision skip, vision-only isolation, OCR empty versus execution failure, UIA failure with OCR degradation, timeout, missing provider, vision point proposal, aligned text conflict, multiple similar candidates, all-provider failure, serialization/replay screenshot loading, content-addressed screenshot deduplication, same-fixture five-mode comparison, point-distance metrics, box IoU metrics, and exclusion of incompatible ground-truth coordinate spaces. Existing project tests remain present and passed.

## Real-world and manual validation

- Interactive application launch/preview/mode-selector use: NOT TESTED.
- Live Windows UIA tree and native target correctness: NOT TESTED.
- Live OCR accuracy on real application crops: NOT TESTED by this Phase 5 run.
- UGround inference and output persistence from the actual model: NOT RUN (artifacts absent; memory guard would block).
- Ground-truth comparison across the five modes: NOT RUN.
- DPI/multi-monitor manual validation: NOT TESTED.
- .NET bridge build: NOT RUN; not necessary for the Kotlin changes and not included in the requested Gradle validation.

## Limitations and known follow-up

1. Phase 5 scoring/thresholds are uncalibrated and have only synthetic/mock fusion evidence.
2. UIA currently exposes metadata for enabled/off-screen status; disabled/off-screen evidence is filtered, but custom-rendered target coverage remains unverified.
3. Real UGround point proposals lack semantic identity/confidence; target correctness is not established.
4. UIA screen-to-crop alignment is only supported when a valid transform is supplied; high-DPI/multi-monitor mapping needs live verification.
5. Cancellation propagates but does not currently persist a completed cancelled experiment record. Abrupt-process recovery for the new manifest is not fault-injection tested.
6. Replay request loading is tested; full cross-version JSON object migration is not implemented.
7. No statistical improvement claim is justified until a labeled, independent calibration/evaluation dataset is run.

## Completion checklist

- [x] Existing Phase 1–4 code paths and evaluator kept.
- [x] Five modes and router isolation implemented and unit-tested.
- [x] Coordinate transform/candidate provenance and deterministic fusion implemented.
- [x] Conflict/ambiguity safe decisions implemented and unit-tested.
- [x] Adaptive invoke/skip rationale recorded.
- [x] Experiment persistence, screenshot reference/deduplication and replay request implemented and unit-tested.
- [x] Ground-truth annotation model and box/point metrics implemented and unit-tested.
- [x] Existing and new automated tests pass; Gradle build passes.
- [ ] Real-model smoke test.
- [ ] Interactive Windows and live UIA validation.
- [ ] Labeled real-app five-mode evaluation and independent threshold calibration.
- [ ] Phase 5 freeze.

**Conclusion:** The code provides a tested experimental Phase 5 pipeline, but Phase 5 is **NOT COMPLETE / NOT READY TO FREEZE** because real-model execution, interactive Windows validation, and labeled comparative research evidence remain unavailable.
