# Phase 4 — Evidence-Oriented Perception Test Report

**Checkout:** `9705dd3e51d2cde22b16094a6108377280eb2213` (`main`, clean at start)  
**Test date:** 2026-10-09 (workspace local date)  
**Final status:** **NOT FULLY TESTED**

## 1. Executive verdict

The current checkout compiles and its automated test suite passes: **118 tests, 0 failures, 0 errors, 0 skipped**. `:composeApp:build` also succeeds. This establishes JVM-level behavior covered by the existing suite, not production behavior on an interactive Windows desktop.

Phase 4 does not have enough current, independently inspectable evidence to freeze as empirically validated. The checked-in specification documents useful C01–C18 and Blender observations, but some later status passages conflict: earlier sections say the visual provider is not configured and no human labels are complete; the final closeout says UGround inference ran on 22 crops and that the 18 C cases were human-labelled. The result files cited for the later UGround batches live under LocalAppData and are not in this checkout. The report therefore does not count those 22 points as a result independently reproduced here. Even the documented records say all 22 decisions abstained and target accuracy was not established.

The deterministic evaluator implements all four outcomes and is well-covered at the unit level. Its `ACCEPT` policy is still a token-match policy over accessible names/OCR output and confidence gates, not proof that a matched intersecting control is the intended real target. UIA returns confidence `1.0` for an intersecting candidate as a deterministic marker. That is not a probability or a semantic correctness score.

No application was launched or driven during this run. UIA, scaling, monitor layout, selection/focus restoration, and preview rendering require interactive Windows observations. No code changes were made; no bug was patched because a safe minimal fix could not be established from the automated evidence without changing intended ranking/evaluator behavior.

## 2. Requirements-to-tests matrix

Requirements below are distilled from [`PHASE_4.md`](PHASE_4.md), especially its architecture, provider, empirical-evaluation, persistence, and closeout sections. “Evidence for PASS” means the evidence needed to justify the claim, not just a passing mock test.

| Requirement | Source files | Existing tests / evidence | Missing tests or evidence | Expected behavior and evidence needed for PASS |
|---|---|---|---|---|
| Keep OCR, UIA and visual evidence separate with explicit coordinate spaces | `PerceptionContracts.kt`, `VisualContext.kt`, `EvidenceEvaluation.kt` | `PerceptionContractsTest`, `EvidenceEvaluationTest`, `ContextProcessingTest` | Real capture coordinate transform validation | Preserve source provenance and types; pass requires contract tests plus validated crop-to-desktop transforms on target hardware before cross-space geometry claims. |
| Context processing degrades cleanly when OCR/UIA fail | `ContextProcessor.kt`, `VisualContextAcquisition.kt` | `ContextProcessingTest`, `CalibrationResultPersistenceTest` | Native-provider exception injection across full desktop capture path | One provider failure must not invent evidence or prevent remaining usable context; tests plus a real capture observation. |
| UIA reports explicit unavailable/failure states and returns intersecting candidates deterministically | `WindowsUiAutomationPerceptionEngine.kt` | `WindowsUiAutomationPerceptionEngineTest` covers missing HWND, unsupported platform/DPI, empty/missing bounds, ranking, names/ValuePattern | Query-failure test, disabled/offscreen behavior, custom UI, live HWND integration | Explicit status with null target on unsupported/missing/query/no-intersection outcomes; real UIA tree probes for native controls and custom controls. |
| UIA selection is a useful semantic target, not just a geometric intersection | `WindowsUiAutomationPerceptionEngine.kt`, `EvidenceEvaluator.kt` | Ranking tests; checked-in Blender notes report top-level window for B01–B04 | Ground-truth semantic-target evaluation, same-session tests | Correct intended target identification measured against human label; intersection alone is insufficient. |
| OCR returns crop-relative words, confidence and explicit empty/error results | `TesseractOcrService.kt`, `OcrModels.kt` | `TesseractOcrServiceTest`, `OcrFixtures` | Small/low-contrast/noisy/invalid crop matrix; native-version fault injection | Valid boxes inside crop, honest confidence/status; real labeled images and runtime-specific verification for accuracy claims. |
| Evaluator implements ACCEPT/REFINE/ESCALATE/ABSTAIN without unsafe ambiguity acceptance | `EvidenceEvaluator.kt` | `EvidenceEvaluatorTest`, `EvidenceEvaluationTest` cover strong/weak, conflict, provider unavailable/failure/incorrect geometry and cancellation paths | Ground-truth false-accept analysis for misleading UIA names/OCR token matches; broad target-description set | Correct accept/abstain rates require confirmed target labels and outcome accounting, not only expected-value unit tests. |
| Visual provider is opt-in, bounded, validated and records failures | `LlamaCppVisualGroundingProvider.kt`, `PerceptionContracts.kt` | `LlamaCppVisualGroundingProviderTest`, `VisualGroundingContractsTest`, `VisualGroundingPersistenceTest` | Current executable/model artifacts and inference rerun; output-to-human-target scoring | Config/resource checks, cancellation/timeout/failure, finite in-range coordinates; separate parse, bounds, and human accuracy scores. |
| Persist crop reference, target, evidence/statuses, decision/reason, timing, GT label | `CalibrationResultPersistence.kt`, `CalibrationHarness.kt` | `CalibrationResultPersistenceTest`, `VisualGroundingPersistenceTest` | Recovery under process termination and concurrent crash simulation; schema fixtures from all historical versions | Atomic records, recoverable manifest, PENDING preserved; interruption and compatibility tests plus real artifact audit. |
| Separate capture, replay, preflight and PENDING labels | `CalibrationHarness.kt`, `CalibrationResultPersistence.kt` | persistence tests cover replay/capture and pending labels; docs describe separate LocalAppData runs | Current private records unavailable in checkout; direct preflight/replay audit | Run-kind and label must survive writes without inferred labels; inspect actual records for each run kind. |
| Verify focus, selection, ESC, preview, DPI and multiple monitors | selection/bridge/UI sources; README manual checklist | Headless lifecycle and geometry tests | All live manual checks | Each item must be observed on an interactive Windows desktop at stated display configurations. |

## 3. Build and regression results

| Command | Exit | Result |
|---|---:|---|
| `./gradlew.bat test` (PowerShell invocation: ` .\gradlew.bat test`) | 0 | BUILD SUCCESSFUL; 118 tests, 0 failures, 0 errors, 0 skipped; 21 XML suite files. |
| `./gradlew.bat :composeApp:build` (PowerShell invocation: ` .\gradlew.bat :composeApp:build`) | 0 | BUILD SUCCESSFUL; task was up to date after the preceding test build. |

No test failure messages. Gradle 8.9 emitted the deprecation notice that some features will be incompatible with Gradle 9.0; this is not a Phase 4 test failure. Java used: Microsoft OpenJDK 17.0.20.1. No unrelated functionality was changed.

## 4. UI Automation results

**Automated:** the provider has explicit outcomes for unsupported platform, missing/zero HWND, unsupported AWT physical-coordinate precondition, thrown query, and no intersecting candidate. It ranks candidates by containment, smaller area, center distance, type, name, and AutomationId. Names remain accessible names; only ValuePattern supplies `visibleText`. Tests establish these isolated mapping/ranking/status branches.

**Gaps/risks:** `WindowsUiAutomationPerceptionEngineTest` does not exercise query failure, disabled candidates, off-screen candidates, custom-rendered controls, or the native PowerShell/UIAutomationClient adapter. The ranker currently filters by bounds/intersection but does not exclude `isEnabled == false` or `isOffscreen == true`; metadata exposes these states but the selection can still return such a candidate. It also accepts an intersecting candidate with a missing/empty accessible name and assigns deterministic confidence `1.0`. The no-name case is represented without inventing a name, but downstream consumers must not interpret that as semantic identification.

The checked-in Phase 4 narrative records a historical capture/replay discrepancy (18 capture failures versus 18 replay successes) and Blender root-window selection for four targets. Those are documented observations; this run did not reproduce them. Correct UIA target accuracy remains **NOT_COMPUTABLE** from this execution. Physical-screen to AWT/crop mapping under scaling and multiple monitors remains unverified. A real HWND, unsupported OS, actual PowerShell failure, native UIA query, and custom-rendered application all require live Windows integration checks.

## 5. OCR results

The existing Tesseract tests invoke the native OCR service on synthetic text-only, button-like, dense UI, neighboring-text, icon-only and blank fixtures. The passing tests verify recognition of expected fixture strings, nonempty positive-size boxes within crop dimensions, crop-relative coordinate tagging, blank empty-result status, and the icon fixture’s `*` false positive. This is real OCR execution on synthetic fixtures under the current Java 17/Tess4J installation, not real-world accuracy validation.

The repository’s calibration summary documents 8/18 correct OCR results (44.4%) for its then-labelled C01–C18 set and four Blender cases with 2/4 label-identification observations. These are recorded study claims, not recomputed from the underlying machine-local runs here; the report does not silently treat synthetic fixtures as labelled reality. Current test coverage lacks explicit small-text, low-contrast, noisy, invalid/zero-sized crop, and native OCR exception tests. The docs record a JDK 22/Tess4J native crash and successful configured Java 17 pair; this run used Java 17 only and did not repeat a crash investigation.

OCR correctness for current run: **NOT_COMPUTABLE** (no newly captured labeled real-world cases). OCR latency distribution: **NOT_COMPUTABLE** from this run (unit tests assert timing is present but do not publish measurements).

## 6. Evaluator results

Existing tests cover strong UIA/OCR acceptance, weak evidence/refinement, high-confidence conflict/escalation, unconfigured/unavailable providers, visual-provider exceptions/cancellation, missing descriptions, invalid bounds, and visual abstention. The coordinator invokes configured grounding at most once and preserves provider invocation status.

The evaluator accepts if all non-generic target tokens are present in a high-confidence accessible name or OCR text, subject to UIA bounds and provider status. It does not establish that the UIA element is semantically the requested target beyond that string match; OCR confidence is not calibrated accuracy. A parseable visual point carries no semantic evidence in the llama.cpp provider, so the evaluator cannot accept that output by itself. A parseable but wrong in-bounds point is still geometrically valid and must be scored against human ground truth before claiming correctness.

| Metric | Current execution | Documented historical calibration (not independently recomputed) |
|---|---|---|
| Correct acceptance / false acceptance | NOT_COMPUTABLE; no new confirmed real-app ground truth | Docs report zero false accepts among 18 and all ABSTAIN. |
| False rejection / correct abstention | NOT_COMPUTABLE for this run | Docs report OCR case judgments and all-ABSTAIN; historical denominator/labels are subject to the documentation inconsistency noted above. |
| Coverage (accepted / valid targets) | NOT_COMPUTABLE | The documented 22 UGround replays report 0/22 accepted (0% coverage); this is not successful identification. |
| Escalation/refine | Unit-level behavior passes | Real-world rate NOT_COMPUTABLE from verified current records. |

No model confidence thresholds were changed.

## 7. Visual-grounding provider results

The source implements an opt-in llama.cpp `mtmd` provider for UGround-V1-2B, taking an image crop and target prompt, converting the model’s 0–999 point to normalized crop coordinates, writing output to a temporary directory, applying a host-memory guard, and honoring coroutine timeout/cancellation. `parseModelPoint` requires one finite point with both coordinates in `[0,1000)`. Provider tests use controlled fake runners to verify configuration, parsing, failure, and cancellation paths.

This checkout’s provider was **not invoked**. No machine-local calibration run root was present, and this investigation did not search broadly for or load model files, install a runtime, or download artifacts. Thus current model/projector/executable configuration, inference availability, latency, and output persistence are **NOT VERIFIED HERE**. The docs’ final section says 22 saved-crop runs were executed, with 22 parseable/in-bounds points, 20.921–27.102 s combined latency, 22 abstentions, and no coordinate-level target accuracy. Those are documented, not reproduced results; even if accepted as historical evidence, target accuracy is **NOT_ESTABLISHED** and acceptance coverage is 0/22.

| Visual property | Result |
|---|---|
| Parseable output | Unit parser tests pass; real replay parse success NOT independently verified in this run. |
| In-bounds point | Unit bounds validation passes; real replay in-bounds rate NOT independently verified in this run. |
| Correct target point | NOT_ESTABLISHED; no confirmed point-level ground truth scored here. |
| Latency | NOT_MEASURED in this run. |
| Timeout/cancellation/error path | Unit tests pass; live process/model path not executed. |

## 8. Persistence and data integrity

The automated persistence suite covers schema-v3 result records, crop hashing, target and PENDING label preservation, run uniqueness, append sequence, manifest reconstruction, injected atomic-write failure cleanup, replay source-byte preservation, provider/evaluator status and latency fields, and cancellation/failure records. Current `CalibrationResultStore.writeAtomic` requires an atomic filesystem move; it fails explicitly if unsupported and cleans temporary files. This is preferable to silently writing a partial final result.

The tests do not simulate abrupt process termination between file flush and rename, a crash during manifest append, concurrent writer crash recovery, or independently validate a corpus of v1/v2 records. The current machine-local calibration artifacts were absent, so run separation and PENDING labels in the documented UGround batches could not be audited. Persistence is **PASS for exercised unit cases; NOT FULLY VERIFIED for crash recovery and the described private run corpus**.

## 9. Manual Windows test plan and status

All steps below are **NOT RUN** in this investigation.

1. Launch AIVisualTutor and confirm the tutor panel loads.
2. Open a controlled external application with non-sensitive test content; capture its window.
3. Invoke region selection with the configured shortcut.
4. Select visible text; inspect crop, OCR words/boxes, UIA status and target description.
5. Select a standard native button/control; compare the selected element with human-observed ground truth.
6. Select an icon-only/non-text region; verify OCR noise does not become target identity.
7. Select a blank canvas region; verify no target is invented and evaluator abstains.
8. Press ESC during selection; confirm cancellation and return to idle.
9. Confirm the previously foreground window regains focus after cancellation.
10. Repeat at available Windows display scaling settings; record scaling and monitor layout.
11. Repeat across multiple monitors if available, including negative-origin layouts if present.
12. Confirm preview shows the exact target description (or “Not specified”), source statuses, visual status and evaluator decision/reason.

Record OS/JDK, app version, display scaling/monitor layout, case ground truth, statuses and timing without publishing sensitive screenshot content or local paths.

## 10. Research metrics and evidence separation

| Evidence class | Current findings |
|---|---|
| A. Unit tests | 118 pass; 0 fail/error/skip. Native OCR fixture tests ran under Java 17. UIA uses injected client fakes; UGround uses fake process runners. |
| B. Calibration/replay | Checked-in docs report C01–C18 and B01–B04 observations and 22 UGround replays. Private replay result corpus was unavailable in this checkout. Current newly verified replay count: 0. |
| C. Real application observations | This run performed none. Documentation records Blender/Notepad/Excel observations; not independently repeated. |
| D. Manual interactive results | None; all checklist items not run. |

Metrics requiring valid current, confirmed ground truth—OCR correctness, UIA semantic target correctness, evaluator false acceptance/rejection, correct abstention, accepted-target coverage, visual target accuracy, and latency distributions—are **NOT_COMPUTABLE for this run**. Coordinate error/IoU across UIA physical desktop coordinates and OCR crop pixels is **NOT_COMPUTABLE** without a validated transform. A point’s parse success or in-bounds status is not target accuracy.

## 11. Known bugs and severity

| Severity | Finding | Evidence / impact |
|---|---|---|
| High research risk | UIA ranker can choose disabled/off-screen candidates and treats an intersecting accessible name as candidate evidence without semantic ground truth. | `rankUiAutomationCandidates` filters only bounds/intersection; tests do not cover disabled/offscreen. Can return a geometrically intersecting but unusable/wrong control. |
| High validation gap | Real UGround result corpus and current model availability could not be independently inspected or rerun; docs report zero accepted cases and no accuracy evidence. | Machine-local artifacts are absent here; documented point outputs do not establish target accuracy. |
| Medium | UIA query-failure status path has no direct provider test. | Engine catches exceptions and maps to `uia_query_failed`, but no regression assertion exercises it. |
| Medium | Requested OCR robustness matrix is incomplete. | Small/low contrast/noise/invalid crop/native failure not tested. |
| Medium | Atomic result write is tested, but crash recovery and manifest interruption behavior are not. | Existing tests inject an error before rename and test manifest reconstruction after deletion, not abrupt termination. |
| Low | Documentation status/counts are not internally consistent across historical sections. | README says final 118 tests; earlier README section says 111; Phase 4 contains both NOT_CONFIGURED and later REAL INFERENCE EXECUTED; ground-truth statuses differ across sections. |

No bugs fixed in this test-only pass. The disabled/off-screen policy should be decided and specified before changing candidate ranking, and should then receive focused tests plus live UIA confirmation.

## 12. Prioritized recommendations

1. **P0:** Reconcile Phase 4/README status and preserve a machine-independent, privacy-reviewed evidence index for each claimed real replay (schema, case ID, label status, parse/bounds status, decision, timing). Do not include private crops in public reporting.
2. **P0:** Build a labeled, independently reviewed set for target-level UIA/OCR/UGround scoring. Report accuracy and coverage; an all-abstain result is 0% acceptance coverage, not target identification.
3. **P1:** Add UIA regression cases for query failure, disabled/off-screen elements, missing names, custom-rendered/no-tree targets, and deliberate wrong-but-intersecting candidates. Specify selection policy before implementation changes.
4. **P1:** Add OCR robustness tests using existing real crops/fixtures where labels are known: small text, low contrast, noise, invalid dimensions and native exceptions. Keep synthetic results distinct from real-app metrics.
5. **P1:** Run the manual Windows checklist at 100% and one non-100% scale, and across monitors if available. Capture exact coordinate transforms before computing cross-provider errors.
6. **P2:** Test interrupted writes and manifest appends with process-level fault injection; validate schema-v1/v2 compatibility using immutable fixtures.
7. **P2:** Rerun a small controlled UGround sample only when installed artifacts and the memory guard permit; store parse, bounds, semantic ground truth, coordinate convention and latency as separate fields.

## 13. Tests not executed

- No UIA provider call against a real foreground HWND or native PowerShell/UIAutomationClient tree.
- No real OS unsupported-platform run, actual query failure, or live no-element/candidate-ranking integration test.
- No real disabled/off-screen/custom-rendered UIA target checks.
- No interactive capture, selector, ESC, focus restoration, preview, DPI, or multi-monitor test.
- No new real-application OCR case; no explicit small/low-contrast/noisy/invalid-crop/native-exception test matrix.
- No controlled ground-truth evaluator scoring run.
- No llama.cpp executable/model/projector discovery or inference attempt; no UGround result corpus audit.
- No abrupt process-kill persistence recovery test; no historical schema-v1/v2 corpus audit.
- No dotnet bridge build was requested or run.

## 14. Final status

**NOT FULLY TESTED.** Automated build and existing unit regressions pass. Evidence does not support a full Phase 4 empirical PASS or a freeze-ready claim: real UIA behavior and manual desktop flows were not exercised, requested OCR robustness tests are incomplete, the visual provider was not run from this checkout, and current real-target accuracy/coverage cannot be independently recomputed from available artifacts.
