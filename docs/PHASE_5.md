# Phase 5 — Evidence-Adaptive Grounding

## Implementation map and audit

The repository is a single Kotlin/JVM 17 Compose Desktop module (`:composeApp`), with Windows UI Automation in `WindowsUiAutomationPerceptionEngine`, Tess4J OCR in `TesseractOcrService`, the opt-in llama.cpp UGround-V1-2B adapter in `LlamaCppVisualGroundingProvider`, and schema-v3 capture/replay persistence in `CalibrationResultStore`. `Main.kt` owns capture/selection/preview; `ContextProcessor` invokes OCR and UIA and retains the legacy `EvidenceEvaluator` decisions. Phase 4 APIs and output are preserved.

Coordinate conventions discovered in the current implementation:

- OCR word boxes: crop/screenshot pixel coordinates, top-left origin.
- UGround point: normalized crop coordinates `[0,1)` after parsing llama.cpp's `[0,1000)` output.
- UIA bounds: physical desktop screen coordinates. AWT selection bounds may be logical/user-space coordinates.
- UIA-to-crop mapping is used only when the caller provides `ScreenToScreenshotTransform`. The preview supplies it only when all detected display transforms are identity; otherwise UIA geometry remains unaligned.
- The real UGround provider currently returns a point without semantic identity or calibrated confidence. A point is retained as a proposal; it is not turned into a fabricated box or accepted target.

### Reused, changed, added, and preserved

- **Reused:** `PerceptionEngine`, `OCRService`, `VisualGroundingProvider`, `WindowsUiAutomationPerceptionEngine`, `TesseractOcrService`, `LlamaCppVisualGroundingProvider`, `EvidenceDecision`, and existing LocalAppData calibration root.
- **Extended:** Windows UIA now exposes all ranked intersecting candidates for Phase 5 while its existing `perceive()` one-result behavior remains unchanged. Screenshot preview adds a grounding-mode selector and result/status display.
- **Added:** typed provider/run/observation/candidate contracts, a transform normalizer, candidate association/fusion, confidence/decision logic, explicit router, research metrics, content-addressed screenshot experiment persistence/replay, and comparison support.
- **Preserved:** legacy acquisition, OCR/UIA contracts, screenshot capture, selector lifecycle, legacy evaluator semantics, and schema-v3 calibration records.
- **Relevant existing defects/limits:** UIA and OCR coordinate spaces do not inherently align; historical UIA accuracy is unverified; UGround's real provider has no semantic response/confidence; labels and saved model replay data are outside this checkout.

## Routing and five experiment modes

`EvidenceAdaptiveGroundingRouter` is the sole mode router. It records requested, executed, successful, failed, and skipped providers, provider statuses/latencies, diagnostics, target, run/case identifiers, and final decision.

| Mode | Providers invoked |
|---|---|
| `UIA_ONLY` | UIA only |
| `OCR_ONLY` | OCR only |
| `UIA_OCR` | UIA and OCR; no vision |
| `VISION_ONLY` | Vision only; UIA/OCR contract inputs are null |
| `ADAPTIVE` | UIA and OCR first; vision only when the candidate set does not satisfy acceptance, or when configured `ALWAYS` |

Missing target descriptions stop provider execution with `ABSTAIN/INVALID_TARGET`. Provider timeouts are bounded per provider (default 30 seconds, configurable per request); caller cancellation propagates. A failed source remains recorded while other sources may still support a result. The adaptive default is `ON_AMBIGUITY_OR_MISSING_EVIDENCE`; when vision is skipped, the reason is persisted.

The preview allows one of the five modes to be run repeatedly against the current unchanged preview and target. `GroundingExperimentRunner.compareAllModes()` supports one-call sequential comparisons, with a new run ID and independent evidence lists per mode.

## Normalization, candidates, fusion, and decisions

Provider-supplied confidence, normalized target-text similarity, cross-source agreement, and the final heuristic score are distinct values. Provider confidence is retained only when supplied; UIA's Phase 4 `1.0` marker is not blended into Phase 5 scoring. Target text is lowercased, punctuation/whitespace normalized, and common action/generic words removed. Similarity is a deterministic token Dice score.

OCR words are grouped by OCR line only when the line itself sufficiently matches the target; otherwise they remain separate word candidates. UIA candidates with disabled/offscreen flags and the window root are excluded. Candidate association requires compatible screenshot-pixel coordinates and different providers. Similar text associates when the boxes overlap sufficiently by IoU or by intersection relative to the smaller box, allowing OCR text inside a UIA control to associate without averaging geometry. A vision point can associate only when it falls inside supported target geometry. Ambiguous associations remain separate; source observations and original geometry are preserved. Points remain zero-area geometry and are never coerced into boxes.

The score is a deterministic heuristic: `0.8 × mean target-text similarity + 0.2 × cross-provider agreement`. Equal source contributions avoid treating uncalibrated provider confidence scales as comparable; the 80/20 weighting is an engineering choice, not learned or statistically calibrated. Default acceptance requires text similarity at least `0.72`, heuristic score at least `0.78`, and a winner margin at least `0.12`; the default association threshold is `0.20`. These are configurable and require calibration before accuracy/probability claims. Conflicting labels or a vision point outside supported target geometry are not averaged and produce a non-acceptance decision.

The existing four `EvidenceDecision` states remain the output decision:

- `ACCEPT`: one supported candidate with valid screenshot-pixel geometry and sufficient score.
- `REFINE`: several plausible candidates or a target-conditioned visual point without matching semantic evidence.
- `ESCALATE`: provider unavailability/failure or aligned conflicting source labels.
- `ABSTAIN`: missing/invalid target, no match, or incompatible coordinates.

Conflicting observations are never coordinate-averaged. The preview draws only accepted boxes/points; proposals and non-accepting outcomes are labeled unconfirmed.

## Persistence and evaluation

Phase 5 records are written under `AIVisualTutor/calibration-runs/phase5-experiments/`, alongside the existing calibration store. Each run has atomic `result.json`; `manifest.jsonl` appends under a file lock. Identical screenshot PNGs are content-addressed and shared under a private `screenshots/` subdirectory so the saved run remains replayable after the temporary preview is deleted. Records include mode, case/run IDs, target, screenshot dimensions/reference, provider states/timings/metadata, observations, candidates, score components, decision/reason, thresholds, model ID and accepted/proposed coordinates. Duplicate run IDs are rejected to avoid replacing a prior result. `loadReplayRequest()` deserializes target/mode/config/reference and loads the saved screenshot; a caller can override mode to compare a replay.

`GroundTruthAnnotation` is a separate input model, never passed to providers or candidate selection. `GroundingResearchMetrics` computes box IoU and center error or point-distance accuracy with a caller-supplied tolerance; reports coverage, selective accuracy, false acceptance, abstention, valid prediction, provider failure, target-identification accuracy, adaptive vision invocation rate, and mean end-to-end latency. Cases count only when an annotation in screenshot pixel coordinates is supplied; incompatible coordinate spaces are excluded. `Phase5Evaluation.kt` reads an RFC-style CSV dataset, runs each case through all five modes against the same screenshot, persists independent experiment records, and writes a JSON metrics report.

The repeatable command is:

```powershell
.\gradlew.bat :composeApp:runPhase5Evaluation `
  -Paivt.phase5.annotations="C:\path\to\cases.csv" `
  -Paivt.phase5.report="C:\path\to\comparison-results.json"
```

Each CSV row supplies an independently annotated screenshot-relative `BOX`, `POINT`, or `ABSENT` target. For a live UIA evaluation, include the visible target window handle and selected desktop rectangle; do not store a stale handle in a reusable dataset. The checked-in sample under `docs/audit_evidence/phase5/ground-truth-v1/` contains one manually outlined button and one absent target on the same screenshot. Its two-case run verifies the harness and provider isolation, not comparative accuracy. UIA was unavailable without a live window handle and the default vision provider was not configured for this offline run; the metrics are descriptive and do not answer the research question.

## UI integration

The screenshot preview adds an editable target-description field, mode dropdown, and run button. It displays run ID, providers, provider status/latency/diagnostics, skipped reasons, heuristic score components, proposed coordinates, and final decision. A green overlay appears only for an accepted box or point. This is an operational entry point, not a redesigned tutor workflow. UIA is unavailable for full-window captures without a selected desktop rectangle; mixed/high-DPI layouts without a declared mapping retain explicit coordinate incompatibility.

## Verification and completion boundary

See [`PHASE_5_RUNTIME_AUDIT_2026-10-09.md`](PHASE_5_RUNTIME_AUDIT_2026-10-09.md) for historical audit commands, results, evidence, and the initial freeze assessment.

**Automated status:** The full Gradle test suite passes 216 tests across 26 suites with 0 failures, 0 errors, and 3 opt-in skips. Regression coverage includes deterministic coordinate transforms under multi-monitor negative origins and DPI scaling factors (125%, 150%), provider conflict escalation, winner-margin tie-breaks, memory guard preflight checks, and model parsing.

**Expanded Ground-Truth Evaluation (v2):**
An expanded 10-case evaluation dataset (`docs/audit_evidence/phase5/ground-truth-v2/cases.csv`) covers positive target detection, absent targets, diverse applications (WinForms, Blender, Notepad, Calculator, Excel, Paint), diverse control types (buttons, tree items, text, cells, icons), and OCR-unfriendly graphical tools. Running `:composeApp:runPhase5Evaluation` executed 50 runs across all 5 modes (`comparison-results.json`):
- `OCR_ONLY`: 1 accepted (`unique-continue`), mean text-region IoU = 0.979, 9 abstentions (`ABSTAIN / NO_MATCH`), 0.0 false acceptance rate, 109.0 ms mean latency.
- `ADAPTIVE`: Skipped vision for the unique supported candidate, escalated unconfigured vision on absent/ambiguous cases without false acceptance, 80.7 ms mean latency.
- `UIA_ONLY` & `VISION_ONLY`: Appropriately reported `UNAVAILABLE` / `NOT_CONFIGURED` without fabricating predictions.

**Model status:** Real UGround-V1-2B model loading remains safely blocked / not configured on hosts without local model weights or with available physical memory below the 3.63 GB safety guard threshold. Unit preflight tests verify that the memory safety guard triggers `HOST_BLOCKED` when memory is insufficient and `AVAILABLE` when sufficient.

| Definition item | Status |
|---|---|
| Preserve Phases 1–4; UIA/OCR remain present | TESTED AND PASSED by full regression suite |
| Five modes and provider isolation | TESTED AND PASSED with deterministic provider fakes and live router |
| Coordinate transform/candidate provenance | TESTED AND PASSED; multi-monitor negative origin offsets and DPI scaling factor math verified |
| Fusion conflict/ambiguity handling | TESTED AND PASSED; conflicting provider labels and detached vision points escalate without coordinate averaging |
| Adaptive provider invocation/skip reasons | TESTED AND PASSED for skip policy and fallback escalation |
| Persistence and replay | TESTED AND PASSED for same-run serialization/reload and screenshot deduplication |
| Ground-truth evaluation | TESTED AND PASSED on expanded 10-case, 50-run v2 dataset; false acceptance rate 0.0% |
| Real model execution | BLOCKED / NOT VERIFIED; memory safety guard remains enabled and model weights opt-in |
| Interactive preview/native Windows validation | PARTIALLY VERIFIED; app launches responsively, hotkeys register, and production UIA transforms tested |
| Accuracy improvement over Phase 4 modes | NOT ESTABLISHED (statistically uncalibrated heuristics; requires broad user study) |
| Phase 5 freeze | PARTIAL; core engineering and evaluation infrastructure complete, live model execution requires hardware environment |
