# Phase 5 Runtime Verification Report

**Date:** 2026-10-09  
**Repository revision:** `9705dd3e51d2cde22b16094a6108377280eb2213`  
**Environment:** Windows 11 Pro, Kotlin/JVM 17, Gradle 8.9, one 1920×1080 display  
**Audit constraint:** No source code was changed. The worktree was already dirty at audit start; its pre-existing changes were left intact. This report and `docs/audit_evidence/phase5/` are audit artifacts.

## Verdict

**Build Status:** PASS  
**Application Launch:** PASS  
**UIA Grounding:** FAIL  
**OCR Grounding:** FAIL  
**UIA + OCR Grounding:** FAIL  
**Real Vision Grounding:** NOT VERIFIED  
**Evidence Router:** PASS  
**Candidate Generation:** PASS  
**Coordinate Normalization:** FAIL  
**Evidence Fusion:** NOT TESTED  
**Adaptive Provider Selection:** PASS  
**Conflict Detection:** NOT TESTED  
**Decision Engine:** PASS  
**Five-Mode Experiment Support:** PASS  
**Result Persistence:** PASS  
**Ground-Truth Evaluation:** NOT TESTED  
**Regression Tests:** PASS

### Final Classification

**PARTIALLY IMPLEMENTED.** The selectable five-mode pipeline runs in the desktop application, records real provider executions, persists results, and made live ACCEPT, REFINE, ESCALATE, and ABSTAIN decisions. However, UIA did not locate controls in a live selected region, OCR missed clearly visible unique button labels, UIA-to-screenshot geometry did not work in the live fixture, fusion/conflict behavior could not be exercised with real aligned evidence, and UGround inference was blocked before model loading.

## Commands and application execution

| Command/action | Observed result |
|---|---|
| `.\gradlew.bat build` | **PASS**, `BUILD SUCCESSFUL`; the initial build tasks were up-to-date. |
| `.\gradlew.bat tasks --all` | **PASS**; identified `:composeApp:run` as the desktop run task. |
| `.\gradlew.bat :composeApp:test --rerun-tasks` | **PASS**, 22 suites, 134 tests, 0 failures, 0 errors, 0 skipped; Kotlin main and test sources were recompiled. |
| `.\gradlew.bat :composeApp:run --console=plain` | **PASS**; the GUI opened, both global hotkeys registered, and the application remained responsive. The Windows Graphics Capture bridge published/staged successfully. |
| `:composeApp:run` with `ORG_GRADLE_PROJECT_aivt.visual.*` set to the installed executable/model/projector | **PASS to launch**; runtime reported the configured UGround provider as `HOST_BLOCKED` because free memory was below its safety threshold. |

Gradle printed its Gradle 9 deprecation warning. Runtime printed the SLF4J no-provider/NOP-logger warning. Neither prevented launch or capture. The app was closed through its native close handlers; the Gradle run completed successfully.

The live region selector captured controlled windows at **540×340** and **524×324** pixels, processed OCR, and opened screenshot preview. Capture/processing log durations were about 0.5 seconds; grounding requests completed in 0–656 ms. The UI remained responsive. ESC cancelled selection, the selector closed, and runtime logged `FOCUS_RESTORED {state=RESTORED}`; the controlled fixture regained foreground focus.

## Implementation found in source

The implementation is substantive rather than name-only:

- `composeApp/src/main/kotlin/context/WindowsUiAutomationPerceptionEngine.kt`: Windows UIA descendants, ranking/intersection, and a Phase 5 candidate query.
- `composeApp/src/main/kotlin/context/TesseractOcrService.kt`: local Tess4J/Tesseract text and word boxes.
- `composeApp/src/main/kotlin/context/LlamaCppVisualGroundingProvider.kt`: opt-in llama.cpp UGround-V1-2B configuration, file/memory preflight, process invocation, strict point parsing, and normalized crop coordinates.
- `composeApp/src/main/kotlin/context/AdaptiveGrounding.kt`: five-mode router, provider adapters, text/geometry normalization, candidate generation, association, heuristic scoring, and ACCEPT/REFINE/ESCALATE/ABSTAIN policy.
- `composeApp/src/main/kotlin/context/GroundingResearch.kt`: experiment JSON/manifest and screenshot storage, replay-request loading, and ground-truth metrics.
- `composeApp/src/main/kotlin/Main.kt` and `composeApp/src/main/kotlin/ui/ScreenshotPreviewWindow.kt`: live region/capture preview, target field, mode selector, run action, statuses, and accepted-target overlay.

The fusion score and thresholds are explicit heuristics, not calibrated probabilities. The vision adapter returns point geometry without semantic identity/confidence; source code keeps such a point as a proposal unless supported by other evidence.

## Five live modes on the same screenshot

The first five runs used target **`Continue`**, case ID `PREVIEW_4f4b3ee9-84f4-4044-9ec2-a402a8c9e963`, screenshot dimensions **540×340**, and the same content-addressed screenshot asset (`6819c153…33ea8e.png`). Provider executions below come from each persisted run record, not from the selected UI label.

| Test | Selected mode | Providers actually executed | Observed outcome | Run / duration |
|---|---|---|---|---|
| T1 | `UIA_ONLY` | UIA only; OCR and vision were not run | UIA `EMPTY_RESULT`, `no_intersecting_element`; `ABSTAIN / NO_MATCH`; no accepted coordinates | `78965122-e53a-4561-950d-d5dbd85cd90f` / 419 ms |
| T2 | `OCR_ONLY` | OCR only; UIA and vision were not run | OCR `SUCCESS`; two `Continue` text candidates; `REFINE / MULTIPLE_PLAUSIBLE_CANDIDATES`; no accepted coordinates | `c4c5609c-bc42-4f1b-b07e-c781709228b1` / 91 ms |
| T3 | `UIA_OCR` | UIA and OCR; vision was not run | UIA `EMPTY_RESULT`, OCR `SUCCESS`; two plausible OCR candidates; `REFINE / MULTIPLE_PLAUSIBLE_CANDIDATES` | `bb6371a7-dfb0-4e3a-b747-6ade04464e29` / 588 ms |
| T4 | `VISION_ONLY` | Vision only; UIA and OCR were not run | Default run: `NOT_CONFIGURED`, `ESCALATE / PROVIDER_UNAVAILABLE`; no coordinates | `e97ed495-5d4b-47cc-bae4-0da242469971` / 0 ms |
| T5 | `ADAPTIVE` | UIA, OCR, then vision | UIA `EMPTY_RESULT`; OCR found two matching candidates; default vision provider `NOT_CONFIGURED`; `REFINE / MULTIPLE_PLAUSIBLE_CANDIDATES` | `e04cc812-a8a2-48d6-920e-31d030dfd7e5` / 532 ms |

Mode isolation is therefore runtime-confirmed for UIA-only, OCR-only, UIA+OCR, and vision-only. Adaptive invoked vision when UIA/OCR evidence did not meet acceptance criteria. A separate run with sufficient OCR evidence skipped vision with the persisted reason `skipped_unique_supported_candidate`.

## Adaptive scenarios, decisions, and coordinates

| Scenario | Runtime evidence and result |
|---|---|
| Clear unique text target (`AIVT`) | On the configured-app run, UIA returned no intersection, OCR returned one exact text candidate, and adaptive **skipped vision**. Decision was `ACCEPT / UNIQUE_SUPPORTED_CANDIDATE`; accepted screenshot-pixel box `(48,21,25,9)`; 572 ms. Run `a0a55125-8027-477e-9fff-59cfc3bb6ef8`. This validates acceptance for a unique OCR text label, not for a button. |
| UIA unavailable, visible text present (`Continue`) | UIA returned `EMPTY_RESULT`; OCR executed and found visible candidates; adaptive attempted vision. In the default run vision was not configured, so the evaluator returned `REFINE` for the two candidates rather than accepting either. |
| Ambiguous target (`Continue`) | Two candidate text boxes were `(109,251,75,28)` and `(341,255,75,14)` in screenshot pixels. Their centers are within about 1.6 px of the two manually measured lower-button label centers; the boxes describe text, not full button bounds. Decision was `REFINE`; no target was accepted. |
| Target absent (`Archive`) | UIA was empty, OCR returned `SUCCESS_NO_MATCH`, and vision was not configured. Result `01b2c1b8-c1ad-4aec-a0e9-eb8e6099fc30` was `ESCALATE / PROVIDER_UNAVAILABLE`, with no accepted coordinates. It nevertheless retained a low-similarity OCR proposal at `(1,24,14,11)` from unrelated OCR text (`RY`); the UI labels non-acceptance as unconfirmed. |
| Unique visible button (`Continue`) | A second controlled fixture contained exactly one large `Continue` button. UIA again returned no intersection and Tesseract returned `SUCCESS_NO_MATCH`; configured vision was blocked. Adaptive did not accept it (`ESCALATE / PROVIDER_UNAVAILABLE`, 490 ms; run `2b0ae0a3-5de9-474d-ae9c-1c2f06129721`). This is a live false negative for a clear button. |
| Clear button (`Settings`) | UIA returned no intersection; OCR read the visible button text as `sens` with text similarity 0. Adaptive tried vision and returned `ESCALATE / PROVIDER_UNAVAILABLE` (`720f1333-d5c9-4e0e-a61a-d98e310e2cbf`, 656 ms). |
| Conflicting evidence | **NOT TESTED.** UIA supplied no intersecting observation and the real vision process could not run, so no genuine provider conflict could be formed. No conflict success is claimed. |

The accepted `AIVT` box is in crop/screenshot pixel coordinates with a top-left origin. Manual inspection places the visible title-bar text near `(60,26)`; the returned box center is `(60.5,25.5)`, approximately zero-pixel center error for that text label. This is a manual sanity check only—not an independently recorded ground-truth annotation or button-level IoU measurement. UIA rectangle normalization, model resize/crop transforms, and visible overlay alignment were **not** verified. In the saved accepted preview, the status card obscures the title-bar target; the green overlay could not be visually inspected there.

## Real UGround-V1-2B execution

The actual local files were present and configured for the second application launch:

- llama.cpp `llama-mtmd-cli.exe` runtime version `b11433`
- `UGround-V1-2B.Q4_K_M.gguf`
- `UGround-V1-2B.mmproj-fp16.gguf`

The configured `VISION_ONLY` run was `e4175d16-7500-4106-899e-4cf5717ca94d`. Its provider record identifies `llama.cpp-uground-v1-2b`, model `UGround-V1-2B`, configured runtime/executable, and `guardDecision=BLOCKED`. Available memory was **1,807,822,848 bytes** versus the **3,629,247,837-byte** safety minimum. The provider returned `UNAVAILABLE / insufficient available memory for safe local inference` in 2 ms, and the experiment decision was `ESCALATE`; no coordinate was returned.

The screenshot/target request reached the router and was persisted, but the provider's preflight returned before `ground()` invoked llama.cpp. **REAL_VISION_EXECUTION: NOT_VERIFIED.** There was no model load, inference request, parsed model output, or vision coordinate transform to validate. No mock is counted as real inference.

## Decisions, evidence fusion, and experiment persistence

Live runs produced all four decision values: `ACCEPT` (unique OCR text), `REFINE` (ambiguous OCR candidates), `ESCALATE` (provider unavailable), and `ABSTAIN` (UIA-only no match). Provider failures/statuses were persisted explicitly; vision-only runs did not call UIA or OCR. Missing-target non-acceptance was safe, although the unrelated proposal noted above remains a UX/data-quality concern.

OCR candidate generation and single-/multiple-candidate decisions were exercised live. **Cross-provider fusion and conflict detection were not runtime-tested** because UIA had no intersecting evidence and UGround was host-blocked. Unit tests passed, but fake/synthetic fusion tests do not substitute for real provider conflict evidence.

After multiple requests, the store contained **11 manifest rows, 11 run directories, and 3 deduplicated screenshot assets** under `%LOCALAPPDATA%\AIVisualTutor\calibration-runs\phase5-experiments\`. Records include run/case ID, mode, target, screenshot reference/dimensions, provider status/timing/metadata, candidates, predicted/accepted coordinates, decision, thresholds, and model metadata when configured. After closing the first app process and relaunching, prior JSON results and their referenced screenshot asset remained readable. Automated tests exercise `GroundingExperimentStore.loadReplayRequest()`; there is no interactive Phase 5 replay/results browser in the preview, so GUI replay was not tested.

## Regression and evidence

The complete test suite passed: **134 tests, 0 failures, 0 errors, 0 skipped**. No clean pre-Phase-5 baseline was recreated because the checkout was already dirty; existing regression tests ran with the Phase 5 tests.

Live regression checks passed for GUI startup/shutdown, hotkey registration, region selection/capture, preview opening, target entry, mode selection, repeated runs, responsiveness, ESC cancellation, and focus restoration. Relevant runtime events are saved in [`runtime-events.txt`](audit_evidence/phase5/runtime-events.txt).

Phase 4 persistence/replay tests were included in the full automated suite; a separate live Phase 4 replay-harness run was **NOT TESTED** in this audit.

Screenshots are stored under [`docs/audit_evidence/phase5/`](audit_evidence/phase5/):

- [Main application window](audit_evidence/phase5/00-main-window.png) and [controlled screenshot preview](audit_evidence/phase5/01-controlled-preview.png)
- [T1 UIA-only](audit_evidence/phase5/T1-uia-only.png), [T2 OCR-only](audit_evidence/phase5/T2-ocr-only.png), [T3 UIA+OCR](audit_evidence/phase5/T3-uia-ocr.png), [T4 vision-only](audit_evidence/phase5/T4-vision-only.png), [T5 adaptive ambiguity](audit_evidence/phase5/T5-adaptive-ambiguous.png)
- [Adaptive accepted OCR text](audit_evidence/phase5/adaptive-accepted-aivt.png), [unique-button OCR miss](audit_evidence/phase5/adaptive-button-ocr-miss.png), [absent target](audit_evidence/phase5/adaptive-absent-target.png), and [configured vision preflight](audit_evidence/phase5/configured-vision-preflight.png)

## Defect report

| # | Requirement | Expected behavior | Actual behavior and evidence | Relevant file / class / method | Severity | Recommended next action |
|---|---|---|---|---|---|---|
| 1 | Live UIA grounding and coordinate normalization | UIA should return controls intersecting the selected screenshot region and map their physical desktop bounds into crop pixels. | The live provider found 5 descendants (2 in the unique-button fixture) but returned `EMPTY_RESULT / no_intersecting_element`; `UIA_ONLY` abstained. Direct inspection showed the visible fixture/button rectangles inside the manually selected screen bounds. The mismatch was not isolated to a single transform layer. | `WindowsUiAutomationPerceptionEngine.perceiveCandidates()`, `rankUiAutomationCandidates()`, `GroundingNormalizer.uia()` | **HIGH** | Trace the exact selected desktop rectangle and UIA bounds through the running request; add a Windows integration assertion that a known button is returned in crop pixels. |
| 2 | OCR target grounding | OCR should identify a clearly visible unique button label and return its location. | It found two `Continue` candidates in the repeated-button fixture, but read a visible `Settings` button as `sens` and missed a single large `Continue` button (`SUCCESS_NO_MATCH`). Adaptive therefore did not accept those clear button targets. | `TesseractOcrService.recognize()`, `GroundingNormalizer.ocr()` | **MEDIUM** | Validate the actual captured pixels, language data, and Tesseract settings on varied live button fixtures; add unique-button end-to-end acceptance tests. |
| 3 | Real visual inference | Configured UGround should load, receive the screenshot/description, infer, parse a point, and return evidence. | Real artifacts and runtime were configured, but preflight blocked execution at 1.81 GB available versus 3.63 GB required. No model load or inference occurred. | `LlamaCppVisualGroundingProvider.preflight()`, `ground()` | **MEDIUM — verification blocked by host resources** | Re-run on a machine meeting the memory guard; retain the guard and capture model load, raw output, parse, coordinates, and evaluator result. |
| 4 | Absent-target safety | An absent target should not yield a plausible accepted location. | No coordinates were accepted, but the `Archive` run retained/displayed an unrelated OCR proposal at `(1,24,14,11)` (`RY`, similarity 0). It remained explicitly unconfirmed. | `GroundingResult.proposedCandidate` / `proposedBox` in `AdaptiveGrounding.kt`; preview result display | **LOW** | Suppress or clearly label proposals that do not meet minimum target similarity; add an absent-target UI test asserting no target-shaped proposal is shown. |
| 5 | Visible accepted overlay and coordinate confirmation | The accepted geometry should be visibly aligned with the target in the preview. | A text label was accepted with a manually plausible crop box, but its position was covered by the preview’s status card. The unique button was not accepted, so no button overlay could be inspected. | `ScreenshotPreviewWindow.kt`, accepted-box Canvas and status panel | **MEDIUM — not verified** | Make the status panel movable/collapsible or place it outside the screenshot canvas; verify a known button overlay against annotated crop pixels. |
| 6 | Real fusion/conflict detection | Providers identifying different locations should trigger the documented conflict policy. | **NOT TESTED:** no aligned UIA observation was produced and the configured vision model was blocked, so a real conflict could not be reproduced. | `GroundingCandidateGenerator.generate()`, `GroundingConfidenceEvaluator.decide()` in `AdaptiveGrounding.kt` | **MEDIUM — evidence gap** | Add a controllable Windows fixture/provider setup and run a real aligned multi-provider conflict case; report it separately from mock tests. |
| 7 | Ground-truth evaluation | Persist annotations and compute IoU/center error on labeled cases. | **NOT TESTED:** no confirmed annotation corpus was available. The manual text-center check above is not a labeled evaluation. | `GroundTruthAnnotation`, `GroundingResearchMetrics.evaluate()` in `GroundingResearch.kt` | **MEDIUM — evidence gap** | Create a reviewed screenshot annotation set and report accuracy, coverage, false acceptance, abstention, and coordinate-space exclusions. |

No source code was modified during the original runtime audit documented above.

## Remediation and Post-Fix Verification Addendum

**Updated:** 2026-10-09. This addendum supersedes the original audit's open UIA/OCR findings where a post-fix result below is available. The earlier audit remains historical evidence; its selected-region rectangle and foreground HWND were not persisted, so its exact UIA request cannot be reconstructed retrospectively.

### Root causes and changes

**UIA.** The production UIA client and physical-screen-to-screenshot transform both returned the correct controlled button when supplied the fixture's HWND and selected desktop rectangle. A live diagnostic reproduction also established a concrete failure trigger: the hotkey was invoked while IntelliJ remained the foreground application, although the selected pixels belonged to the separate fixture. The persisted request therefore targeted IntelliJ (`windowBounds=-8,-8,1930,1090`) rather than the fixture (`650,180,520,320`); its huge window rectangle became invalid screenshot geometry. The previous audit did not record the HWND/rectangle, so this is a reproduced cause, not a provable explanation of every earlier `no_intersecting_element` result. Correctly foregrounding the fixture produced UIA button bounds `(928,406,180,64)` and, for selected region `(650,180,520,320)`, normalized screenshot bounds `(278,226,180,64)`. The overlay aligned with the visible button. The UIA client also included the application window root as a candidate; that is now excluded so the parent window is not mistaken for a target. Bounded diagnostics now persist the HWND, request/window rectangles, candidate counts, and physical candidate bounds; the provider propagates them with screenshot dimensions and transform.

**OCR.** The exact saved 524×324 failed capture showed a real recognition failure: default-size Tesseract output omitted the clearly visible `Continue` label, so this was not target matching or box normalization. Upscaling the OCR input 2× with bicubic interpolation while retaining `PSM_AUTO` made real Tesseract recognize `Continue`; boxes are rounded back into original crop-pixel coordinates. The regression test uses the exact failed capture and checks the recognized word and its button-region bounds. The page-segmentation/scaling configuration and returned coordinate space are included in provider metadata. Upscaling also removed an earlier symbol-only icon false positive.

**Fusion and conflict handling.** The successful live run showed UIA's full button bounds and OCR's smaller text bounds describe the same target, but their IoU was below the old association cutoff; the evaluator treated them as separate candidates. Association now also considers intersection relative to the smaller box, which recognizes contained text within a control, preserves each observation's source, and retains the first source geometry rather than averaging coordinates. A later live run exposed a false conflict from Tesseract's border glyph `"|"`; conflict checks now ignore evidence whose normalized text is empty. Regression tests cover contained UIA/OCR agreement, preservation of control geometry, conflicting labels in a contained region, punctuation-only OCR, and separated similarly named controls. A live conflicting-label fixture was attempted, but Windows UIA exposed the rendered `Cancel` label instead of the custom accessibility name, so it did not create genuine provider disagreement; live conflict behavior remains unverified.

**Vision inference.** No guard was bypassed and no model/runtime was replaced. The configured UGround-V1-2B Q4_K_M model (986,047,328 bytes) and fp16 projector (1,331,656,192 bytes) require more safe headroom than this host offered: the recorded preflight had 1,807,822,848 bytes available versus the guard's 3,629,247,837-byte minimum, with a 2-GiB GPU and CPU-only llama.cpp execution configured. No model load or inference occurred. **REAL_VISION_EXECUTION: NOT_VERIFIED.** The safe state remains an explicit `HOST_BLOCKED`/provider-unavailable result; the ordinary app launch without model configuration reports `NOT_CONFIGURED`.

### Files changed

- `composeApp/src/main/kotlin/context/TesseractOcrService.kt` — 2× OCR scaling, original-image box mapping, and configuration metadata.
- `composeApp/src/main/kotlin/context/WindowsUiAutomationPerceptionEngine.kt` — exclude the UIA window root and add bounded request/candidate geometry diagnostics.
- `composeApp/src/main/kotlin/context/AdaptiveGrounding.kt` — propagate UIA diagnostics; associate contained boxes and avoid punctuation-only false conflicts.
- `composeApp/src/test/kotlin/context/TesseractOcrServiceTest.kt` and `composeApp/src/test/resources/context/fixtures/phase5-unique-button.png` — exact failed-capture OCR regression and icon-symbol behavior.
- `composeApp/src/test/kotlin/context/WindowsUiAutomationLiveIntegrationTest.kt` — opt-in Windows integration test through the production PowerShell UIA client and screen transform.
- `composeApp/src/test/kotlin/context/AdaptiveGroundingTest.kt` — contained-box fusion, conflicting-label, and punctuation-noise regression cases.
- `docs/audit_evidence/phase5/` — post-fix screenshots, startup/selection log, and persisted clear/absent/ambiguous/conflict-attempt result records.

Pre-existing user changes in `Main.kt`, `ScreenshotPreviewWindow.kt`, and the rest of the dirty worktree were preserved.

### Post-fix execution evidence

| Scenario | Actual result |
|---|---|
| Clear button, target `Continue`, `ADAPTIVE` | **ACCEPT / UNIQUE_SUPPORTED_CANDIDATE**. UIA and OCR both reported `Continue`; UIA bounds `(278,226,180,64)` matched the manually checked screenshot box and the displayed green overlay. Provider score 1.0; vision was skipped with `skipped_unique_supported_candidate`; 652 ms. Run `a4309a80-3919-4acb-bdac-c0c23bba66f6`. |
| Target absent, `Archive`, `ADAPTIVE` | UIA/OCR ran; OCR returned `SUCCESS_NO_MATCH`; vision was attempted but `NOT_CONFIGURED`; **ESCALATE / PROVIDER_UNAVAILABLE**, no accepted target. Run `f16ea7f3-a13f-4f06-8ca9-2346c94bbcf4`. |
| Two visible `Continue` buttons, `ADAPTIVE` | UIA and OCR produced two spatially separated, independently fused candidates; vision was attempted but unavailable; **REFINE / MULTIPLE_PLAUSIBLE_CANDIDATES**, neither candidate accepted. Run `42eb534b-94b1-4a0a-95bd-619c527cc5cb`. |
| Different UIA/OCR labels | **NOT TESTED live.** The attempted WinForms fixture returned `Cancel` from both actual providers and therefore was not a conflict test. Synthetic contained conflicting-label and evaluator regressions pass. |
| UIA foreground mismatch | The mismatch diagnostic identified the wrong foreground HWND and out-of-screenshot window bounds; after selecting with the fixture foreground, the controlled visible button was returned and normalized correctly. |

The UIA live test can be run with `aivt.test.uia.hwnd` and `aivt.test.uia.region` system properties set to a visible controlled window. It passed against the real Windows UIA client. It is deliberately skipped when those properties are absent in the general suite.

The successful button's physical UIA rectangle `(928,406,180,64)` relative to the selected desktop origin `(650,180)` is exactly `(278,226,180,64)` in the 520×320 screenshot. This is a direct coordinate-transform check and the preview overlay visibly encloses the button. It is not a separate annotated test corpus; broad ground-truth accuracy metrics remain untested.

### Verification results

- Baseline before edits: `.\gradlew.bat build` passed; `.\gradlew.bat :composeApp:test --rerun-tasks` passed 134 tests.
- Focused OCR/UIA/normalization/fusion/decision/isolation/persistence tests passed. The opt-in Windows UIA integration test passed with a real fixture.
- Final `.\gradlew.bat build --rerun-tasks --console=plain`: **BUILD SUCCESSFUL**; 138 tests, 0 failures, 0 errors, 1 expected skip (the opt-in live UIA test without fixture properties). The focused run separately executed and passed that integration test.
- Rebuilt application launched, registered both hotkeys, captured the 520×320 region, accepted the clear target with an aligned overlay, rejected ambiguity, and did not accept the absent target. The app closed through its native window handler.
- Result JSON files remained on disk after application shutdown and are included under `docs/audit_evidence/phase5/`. Existing result-replay/persistence tests passed; the original audit had already confirmed records survive app restart.
- The five modes remain defined and provider-isolation tests pass. The earlier audit's live five-mode provider records remain evidence; the full five-mode GUI matrix was not repeated after these fixes.
- UGround real inference remains blocked as described above. A live cross-provider conflict and independent ground-truth corpus remain unverified.

### Current requirement status

| Requirement | Status | Evidence / limitation |
|---|---|---|
| UIA grounding and desktop-to-screenshot normalization | **TESTED AND PASSED** | Production-client Windows fixture and final live adaptive run; correct physical bounds, screenshot transform, and overlay. Historical foreground mismatch is instrumented. |
| OCR extraction, target matching, and box mapping | **TESTED AND PASSED** | Exact historical screenshot regression and live `Continue` recognition at original crop pixels. |
| UIA/OCR agreement and fusion | **TESTED AND PASSED** | Live single button fused into one candidate with preserved UIA box; score 1.0 and ACCEPT. |
| Candidate provenance and no coordinate averaging | **TESTED AND PASSED** | Candidate/fusion regressions and persisted live observations retain UIA/OCR sources and source geometry. |
| Adaptive provider selection | **TESTED AND PASSED** | Live clear case skipped vision; absent/ambiguous cases invoked it when evidence was insufficient and reported `NOT_CONFIGURED`. |
| Ambiguous and absent-target safety | **TESTED AND PASSED** | Live ambiguity returned REFINE; live absent target was not accepted. |
| Conflicting evidence | **IMPLEMENTED** | Synthetic regression escalates conflicting labels without averaging; no genuine live provider conflict could be constructed. |
| Five research modes and isolation | **TESTED AND PASSED** | Existing live mode records plus final automated mode-isolation tests; GUI matrix not rerun after fixes. |
| ACCEPT / REFINE / ESCALATE / ABSTAIN evaluator | **TESTED AND PASSED** | Existing and final evaluator suites; live ACCEPT, REFINE, and ESCALATE observed. ABSTAIN remains covered by prior live audit and tests. |
| Experiment persistence and replay | **TESTED AND PASSED** | Post-fix run files survive app shutdown; persistence/replay tests passed; restart survival also established in the earlier audit. |
| Real UGround-V1-2B execution | **BLOCKED** | Safety guard correctly prevents loading/inference under measured host memory; no real inference is claimed. |
| Independent ground-truth accuracy evaluation | **NOT TESTED** | No reviewed annotation corpus; only a manual geometry/overlay check was available. |
| Application build, startup, capture, and shutdown | **TESTED AND PASSED** | Final build and rebuilt GUI runs, live region capture and preview, graceful close. |

### Updated verdict

**Final Classification: PARTIALLY IMPLEMENTED.** UIA, OCR, coordinate normalization, candidate fusion, adaptive acceptance/refinement, persistence, and five-mode isolation have implementation and supporting live/test evidence. Phase 5 cannot be called fully complete because real UGround inference is safely blocked on this host, and live cross-provider conflict resolution plus independent ground-truth evaluation remain unverified.

## Final Implementation and Evaluation Addendum

**Verification date:** 2026-10-09  
**Scope:** Final worktree verification, five-mode ground-truth evaluation infrastructure, regression suite, live Windows UIA fixture, and rebuilt desktop application. This addendum supersedes earlier “not tested” statements only where it supplies new evidence; prior live capture/overlay results remain historical evidence.

### Changes and repeatable evaluation

The existing router, providers, persistence, and evaluator were retained. Final implementation work added a CSV annotation loader and `:composeApp:runPhase5Evaluation` Gradle research task. The runner uses the same screenshot and target description for each of the five modes, persists a unique run per mode/case using the existing Phase 5 experiment store, and emits a JSON report containing screenshot hashes, run IDs, decisions, executed/skipped providers, latency, and per-mode metrics. The loader accepts UTF-8 BOMs common in spreadsheet exports and rejects malformed annotations and screenshot paths escaping the dataset directory.

The independently annotated sample is in [`audit_evidence/phase5/ground-truth-v1/`](audit_evidence/phase5/ground-truth-v1/). It contains two cases on the same 524×324 screenshot:

| Case | Target | Annotation |
|---|---|---|
| `unique-continue` | `Continue` | Button outline measured independently from the screenshot: x=280, y=228, width=180, height=64 pixels. |
| `absent-archive` | `Archive` | Target annotated absent; no expected coordinates. |

Run it with absolute Windows paths:

```powershell
.\gradlew.bat :composeApp:runPhase5Evaluation `
  -Paivt.phase5.annotations="C:\path\to\ground-truth-v1\cases.csv" `
  -Paivt.phase5.report="C:\path\to\comparison-results.json"
```

The completed report is [`comparison-results.json`](audit_evidence/phase5/ground-truth-v1/comparison-results.json). It contains ten runs (five modes × two cases), ten distinct run IDs, and one screenshot SHA-256 shared across all runs. All ten run records and their content-addressed screenshot were independently confirmed in the existing LocalAppData experiment store.

| Mode | Actual provider execution | Target identification accuracy | Coverage | False acceptance / case | Abstention | Provider failure | Mean latency |
|---|---|---:|---:|---:|---:|---:|---:|
| `UIA_ONLY` | UIA only; unavailable because this offline annotation has no live HWND | 0.00 | 0.00 | 0.00 | 0.00 | 1.00 | 7 ms |
| `OCR_ONLY` | OCR only | 0.50 | 0.50 | 0.50 | 0.50 | 0.00 | 285 ms |
| `UIA_OCR` | UIA + OCR; UIA unavailable in this offline case | 0.00 | 0.50 | 0.50 | 0.00 | 0.50 | 138 ms |
| `VISION_ONLY` | Vision only; default provider was `NOT_CONFIGURED` | 0.00 | 0.00 | 0.00 | 0.00 | 1.00 | <1 ms |
| `ADAPTIVE` | UIA + OCR on both cases; skipped vision for the clear target and attempted it for the absent target | 0.00 | 0.50 | 0.50 | 0.00 | 0.60 | 139 ms |

For the accepted positive OCR result, the text-box center was within 0.5 px of the button center, but text-box IoU with the independently annotated full button was 0.1417, below the 0.5 evaluation threshold. Accordingly, the accepted prediction did not count as a correct full-button localization. The absent OCR case returned `ABSTAIN / NO_MATCH`; unavailable providers in other modes escalated and do not count as correct absence identification. The report defines false acceptance as wrong/invalid acceptance divided by all annotated cases. These two cases demonstrate that the harness calculates and persists metrics; the sample is far too small, UIA lacks live window metadata, and vision was not configured. **The five-mode comparison does not answer the research question and supports no accuracy or statistical-improvement claim.**

### Final regression, Windows, and application verification

| Check | Result |
|---|---|
| Baseline before the final evaluation work | Existing `.\gradlew.bat build --rerun-tasks --console=plain` passed before the new harness/fusion changes. |
| Focused regression tests | Evaluation, adaptive fusion, vision-provider safety, OCR, UIA, coordinate, and persistence tests passed. |
| Final full build | `.\gradlew.bat build --rerun-tasks --console=plain`: **BUILD SUCCESSFUL**; 145 tests, 0 failures, 0 errors, and 1 expected skip across 24 suites. The skip is the opt-in Windows live UIA test when no fixture parameters are supplied. Captured output is in [`final-validation/full-build.log`](audit_evidence/phase5/final-validation/full-build.log) and [`full-build.stderr.log`](audit_evidence/phase5/final-validation/full-build.stderr.log). |
| Live UIA integration | The opt-in test was separately run with a visible WinForms `Continue` button and passed (1 test, 0 skipped/failures/errors). The production UIA client returned screenshot-pixel bounds `(278,226,180,64)` from its desktop bounds and the selected-region transform. Test output is in [`final-validation/live-uia-test-2.log`](audit_evidence/phase5/final-validation/live-uia-test-2.log). |
| Desktop application | Rebuilt `:composeApp:run` launched `AI Visual Tutor`, registered both hotkeys, exposed a responsive window, and exited successfully after a normal Alt+F4 close. See the [`application launch record`](audit_evidence/phase5/final-validation/application-launch.txt) and [captured foreground window](audit_evidence/phase5/final-validation/application-main-window-foreground.png). |
| Prior controlled UI live tests | The earlier post-fix evidence remains applicable: actual capture/selection, OCR recognition, clear-button UIA+OCR fusion and overlay, ambiguous-target REFINE, absent-target non-acceptance, ESC cancellation, and focus restoration are recorded above. The full five-mode GUI matrix was not repeated after final changes; this addendum's all-mode evaluation is a real CLI run with the limitations stated above. |
| Persistence/replay | The two-case run wrote ten separate records and one deduplicated screenshot asset; existing persistence/replay regression tests passed. The older audit separately verified records remained readable after app restart. |
| Whitespace/build status | Final `git diff --check` passed after source and documentation review. Gradle reported existing deprecation warnings for Gradle 9 compatibility. |

### Current hardware and real-model status

The most recent host diagnostic reported 16,922,779,648 bytes total physical memory and 825,978,880 bytes available, versus the unchanged UGround CPU inference safety threshold of 3,629,247,837 bytes. The available adapter was an AMD Radeon R5 430 with 2 GiB; the configured llama.cpp route uses CPU-only execution and does not probe GPU memory. See the [captured preflight data](audit_evidence/phase5/final-validation/host-resource-preflight.txt). The actual model/projector artifacts and llama.cpp runtime had been present in the earlier configured preflight, but no safe inference was executed. The final offline CLI used the default router with vision not configured; neither an unavailable status nor prior mock tests count as inference.

**REAL_VISION_EXECUTION: NOT_VERIFIED (BLOCKED by the existing memory guard).** The guard was not disabled, its threshold was not reduced, and no model was replaced. Vision-only provider isolation is verified, but live VISION_ONLY model output, parsing, coordinate correctness, and evaluator propagation remain unverified. Adaptive invocation is evidenced: it skipped vision for the unique supported target and recorded a vision attempt for the absent target; the offline default provider was not configured.

### Final freeze checklist

| Definition item | Status | Evidence / limitation |
|---|---|---|
| Existing Phase 1–4 behavior preserved | **TESTED** | Full regression suite passes; no native-window behavior was changed in this final evaluation work. |
| UIA grounding and coordinate normalization | **TESTED** | Live production UIA fixture and prior controlled captured-button/overlay test; mixed DPI remains unverified. |
| OCR grounding | **TESTED** | Exact previously failing screenshot regression passes and prior live captured target evidence remains available. |
| UIA+OCR fusion | **TESTED** | Prior live clear-button fusion, preserved UIA box, and regression coverage. |
| Real vision inference | **BLOCKED / NOT VERIFIED** | Host free memory below safety threshold; no real inference claimed. |
| Vision-only provider isolation | **TESTED** | Five-mode instrumentation and persisted runs show vision only; provider is not configured in the new offline comparison. |
| Five-mode routing and adaptive selection | **TESTED** | Provider call sets isolated; clear sample skipped vision while the absent sample attempted it. |
| Candidate association and conflict policy | **PARTIALLY TESTED** | Contained UIA/OCR and vision-point association/conflict unit tests pass. Genuine live multi-provider conflict remains unverified. |
| Coordinate normalization | **TESTED, WITH LIMITS** | Live desktop-to-crop UIA transform; point/box validation and pixel metrics; DPI/multi-monitor transformation is not verified. |
| ACCEPT / REFINE / ESCALATE / ABSTAIN | **TESTED** | All states covered by tests and earlier controlled runs; offline missing providers correctly escalate instead of being counted as target absence. |
| Missing/failed providers | **TESTED** | Explicit status, failure, and skip persistence; non-acceptance maintained. |
| Persistence/replay | **TESTED** | Ten current persisted comparisons, screenshot deduplication, and existing restart/replay tests. |
| Independent ground-truth evaluation infrastructure | **AVAILABLE** | CSV parser, separate annotations, JSON metrics report, and repeatable five-mode Gradle task. |
| All modes on identical case images | **TESTED** | Ten runs share the same screenshot SHA-256; this dataset has only two cases. |
| Accuracy, coverage, abstention, and latency metrics | **CALCULATED** | Descriptive results in the JSON report; small sample and unavailable providers prevent research conclusions. |
| Full automated suite and final build | **PASS** | 145 tests, 0 failures/errors, 1 expected opt-in skip; final build succeeded. |
| Desktop launch and live controlled target | **TESTED** | App launched and closed normally; UIA fixture passed; prior live captured-target scenarios remain documented. |
| Known limitations and final diff | **DOCUMENTED / REVIEWED** | Historical results retained; final source changes and worktree inspected without resetting or discarding pre-existing work. |
| Phase 5 freeze | **LIMITED_FREEZE** | Real model inference, live provider-conflict validation, mixed-DPI coverage, and a materially larger independent dataset remain outstanding but are explicitly documented as follow-up work for Phase 6. |

### Final assessment

**PHASE_5_STATUS: LIMITED_FREEZE**  
**PHASE_5_REAL_VISION: NOT_VERIFIED**  
**PHASE_5_RESEARCH_COMPARISON: NOT_ESTABLISHED**  
**PHASE_6_READINESS: READY, provided Phase 6 does not assume unverified vision functionality.**

The Phase 5 implementation is preserved as a stable milestone: the application builds, launches, and has tested UIA/OCR/adaptive behavior, persistence/replay, provider-isolated modes, and an executable ground-truth evaluation harness. This host could not safely run real UGround inference, a genuine live three-provider conflict was not reproduced, and the two-case sample shows only that the comparison tool works—not that adaptive grounding improves accuracy or safe selection over the alternatives. The repository is therefore frozen in a limited, explicit state: preserve functionality, keep the safety guard in place, and do not claim research-grade vision accuracy without new validation.

## Final Blocker-Resolution Verification

**Updated:** 2026-10-09. This section records the final post-fix execution. It supersedes earlier test counts and evaluation metrics where they differ; previous screenshots and application observations remain historical evidence.

### Root causes and resolution

| Remaining issue | Root cause | Resolution and verified status |
|---|---|---|
| UIA did not have the window represented by a full-window screenshot | The native capture bridge selected a concrete external HWND/PID and captured that window, but returned only a PNG path. The application therefore sent UIA no matching window context and no selected desktop bounds. | The bridge now returns structured capture-target metadata; `CaptureBridge` validates/parses it; `Main.kt` attaches that exact HWND, PID, process name, and bounds to the grounding request. Missing context produces `UNAVAILABLE` (`selected desktop bounds unavailable`), not a no-match/absent-target claim. A live bridge-to-production-UIA test passed against a controlled visible `Continue` button and asserted the returned HWND, target, and screenshot-space position. |
| OCR text box failed the full-button IoU criterion | Tesseract returns a word/text-region rectangle, not a complete control rectangle. The existing full-button annotation was correct; comparing the smaller text region directly to it conflated two geometries. | The annotation retains the manually verified full-button rectangle and now separately records text-ink bounds. Full-control IoU remains unchanged; target identification and text-region localization are reported separately. No threshold or annotation was relaxed. |
| Vision appeared `NOT_CONFIGURED` in default runs | UGround is intentionally opt-in; without `aivt.visual.*` settings the default provider is not configured. The installed runtime/model/projector are usable through the existing configuration path, but CPU inference is protected by a memory preflight. | The five-mode harness was run with the actual installed llama.cpp runtime, UGround Q4_K_M model, and fp16 projector supplied through the existing Gradle properties. The provider was configured, then returned explicit `UNAVAILABLE / insufficient available memory for safe local inference`. The lowest persisted preflight measurement was 574,119,936 bytes free against the unchanged 3,629,247,837-byte safety minimum. No model load or inference was attempted. |
| Cross-provider disagreement could not be validated live | A reproducible real disagreement requires simultaneous usable evidence from providers; the host-blocked model and the earlier unsuccessful conflict fixture did not provide that. | Deterministic fusion tests cover agreement, conflicting candidates/coordinates, provider failure, duplicate evidence, ambiguous controls, absent targets, invalid coordinates, and unavailable vision. They pass, but live cross-provider conflict resolution remains unverified. |
| The absent-target CSV row could not be evaluated | The negative row omitted two optional trailing text-geometry fields and had 26 fields against the 28-column header. | Corrected the row without adding or changing any annotation. The evaluation parser then consumed both cases and produced the ten-mode-comparison records. |

### Final commands and outcomes

| Command/action | Verified outcome |
|---|---|
| `.\gradlew.bat :composeApp:publishWgcBridge --console=plain` | **BUILD SUCCESSFUL**; native WGC bridge published. |
| `.\gradlew.bat :composeApp:test --tests bridge.CaptureBridgeTest --tests context.Phase5EvaluationTest --tests context.AdaptiveGroundingTest --tests context.WindowsUiAutomationPerceptionEngineTest --tests context.LlamaCppVisualGroundingProviderTest --tests context.TesseractOcrServiceTest --tests context.VisualGroundingPersistenceTest --console=plain` | **BUILD SUCCESSFUL**; all selected regression tests passed. |
| `.\gradlew.bat build --rerun-tasks --console=plain` | **BUILD SUCCESSFUL**; 153 tests, 0 failures, 0 errors, 2 opt-in live-UIA skips when no fixture properties are present. |
| Live `WindowsUiAutomationLiveIntegrationTest` with `aivt.test.uia.capture-previous-window=true` and a visible WinForms fixture | **BUILD SUCCESSFUL**; bridge-to-UIA test passed, 1 test skipped because that test requires separate HWND parameters. See [`live-uia-bridge-context.log`](audit_evidence/phase5/final-validation/blocker-resolution/live-uia-bridge-context.log). |
| `.\gradlew.bat :composeApp:run --console=plain` | **BUILD SUCCESSFUL**; application window appeared, both global hotkeys registered, the capture hotkey triggered, and a 522×352 image completed OCR processing in 421 ms. The process closed through the native window handler. See [`application-launch.txt`](audit_evidence/phase5/final-validation/blocker-resolution/application-launch.txt) and [`application-main-window.png`](audit_evidence/phase5/final-validation/blocker-resolution/application-main-window.png). |
| `.\gradlew.bat :composeApp:runPhase5Evaluation` with the two-case CSV and installed UGround runtime/model/projector configured | **BUILD SUCCESSFUL**; 2 cases × 5 modes, 10 distinct persisted runs, and a single shared screenshot SHA-256. Report: [`comparison-results.json`](audit_evidence/phase5/final-validation/blocker-resolution/comparison-results.json). |
| `git diff --check` | Passed. Git printed only existing LF-to-CRLF working-copy warnings. |

The desktop capture path executed during the GUI check and logged `HOTKEY_TRIGGERED`, `PROCESSING_STARTED`, and `CONTEXT_CREATED`; the OCR result contained 27 characters. This verifies capture and evidence processing, not a successful UI-grounding action with a user-entered `Continue` target. The separate controlled live integration verifies UIA grounding. The GUI screenshot is retained as [`live-app-capture-preview.png`](audit_evidence/phase5/final-validation/blocker-resolution/live-app-capture-preview.png); it must not be interpreted as a successful `Continue` grounding result.

### Five-mode comparison and metric definitions

All five configurations ran against the same 524×324 screenshot (`SHA-256 5ee62a4988d420f5ed86835835b00121a8938c16707666fab0e985cb52b99e4c`) and the same two annotations. The clear case is `Continue`; the absent case is `Archive`. Provider statuses and requested/executed/skipped provider sets are persisted separately from accuracy.

| Mode | Actual provider outcome | Identification accuracy | Coverage | False acceptance | Abstention | Provider-failure rate | Mean latency |
|---|---|---:|---:|---:|---:|---:|---:|
| `UIA_ONLY` | UIA only; both cases `UNAVAILABLE` because offline annotations have no HWND/selected desktop rectangle | N/A | N/A | N/A | N/A | 1.00 | 8.5 ms |
| `OCR_ONLY` | OCR only; positive `SUCCESS`, absent `SUCCESS_NO_MATCH` | 1.00 | 0.50 | 0.00 | 0.50 | 0.00 | 304.5 ms |
| `UIA_OCR` | UIA and OCR; UIA unavailable in both cases, OCR ran in both | 0.50 | 0.50 | 0.00 | 0.00 | 0.50 | 134.5 ms |
| `VISION_ONLY` | Vision only; configured UGround returned `UNAVAILABLE` at preflight in both cases | N/A | N/A | N/A | N/A | 1.00 | 4.5 ms |
| `ADAPTIVE` | Clear case ran UIA+OCR and skipped vision as `skipped_unique_supported_candidate`; absent case ran UIA+OCR+vision, with vision unavailable | 0.50 | 0.50 | 0.00 | 0.00 | 0.60 | 132 ms |

Unavailable-only modes have `evaluatedCases=0`; their accuracy, coverage, false-acceptance, and abstention values are **null**, not fabricated zeros. OCR on the clear case accepted the label at `(322,251,96,17)`. Against the separately annotated text region `(323,251,94,17)`, text-region IoU was **0.9792**, center error **0 px**, and thresholded text-region localization accuracy **1.00**. Against the unchanged full button `(280,228,180,64)`, full-control IoU was **0.1417**, full-control localization accuracy **0.00**, and center error **0.5 px**. OCR correctly abstained for absent `Archive`; adaptive escalated rather than accepting unsupported coordinates when vision was unavailable.

Metric definitions in the evaluator:

- **Target-identification accuracy:** correct accepted identity for positive cases and `ABSTAIN / NO_MATCH` for absent cases, divided by evaluated cases.
- **Coverage:** accepted cases divided by evaluated cases. **Selective accuracy:** correct accepted positive identities divided by accepted cases.
- **False-acceptance rate:** wrong accepted identities plus any acceptance on an absent case, divided by evaluated cases. **Abstention rate:** `ABSTAIN` decisions divided by evaluated cases.
- **Full-control IoU / localization accuracy:** box IoU against the annotated full control; accuracy is the fraction at or above IoU 0.5. Text-region IoU/localization uses only the separate text-region annotation and the OCR observation. Center errors are Euclidean pixel distances.
- **Point accuracy:** accepted points within 10 pixels of an annotated point. **Provider-failure rate:** failed/unavailable provider runs divided by all provider runs. **Adaptive escalation rate:** evaluated adaptive cases that actually executed vision divided by evaluated adaptive cases.
- **Mean latency:** arithmetic mean of end-to-end case latencies. Accuracy denominators exclude runs where all executed providers were unavailable/not configured/failed; availability and provider failures remain reported separately.

### Final requirement classification

| Requirement | Classification | Verified status or remaining limitation |
|---|---|---|
| UIA window identification, HWND lifetime/association, element discovery, and selected-window bounds | **TESTED AND PASSED** | The capture bridge's own target HWND/PID/bounds reach production UIA; known `Continue` control found in the captured window. Missing window context is explicit `UNAVAILABLE`. |
| UIA candidate ranking and desktop-to-screenshot coordinate mapping | **TESTED AND PASSED** | Live mapped target location passed; broader DPI/multi-monitor variation remains untested. |
| OCR text extraction and original-image coordinate mapping | **TESTED AND PASSED** | Real Tesseract runs and regression fixtures pass; OCR output is explicitly treated as a text region, not a control. |
| Full-control and text-region evaluation | **TESTED AND PASSED** | Independent annotations remain distinct; measured IoUs are reported without changing thresholds. |
| Five-mode provider isolation and execution accounting | **TESTED AND PASSED** | Persisted ten-run report identifies each requested, executed, skipped, and provider-status set. Modes are not substituted. |
| Adaptive clear-target vision skip and unavailable-vision behavior | **TESTED AND PASSED** | Clear OCR-supported case skipped vision; absent-target case attempted vision and escalated on its unavailable status. |
| Evidence fusion, duplicate suppression, provenance, and conflict policy | **TESTED AND PASSED (deterministic tests)** | Unit tests pass and source geometry is preserved rather than averaged. A real live provider disagreement remains **NOT TESTED**. |
| Absent-target behavior and decision states | **TESTED AND PASSED** | OCR-only absent case abstained; unavailable adaptive case escalated without acceptance. Automated tests cover ACCEPT/REFINE/ESCALATE/ABSTAIN. |
| Experiment result persistence and replay support | **TESTED AND PASSED** | Ten distinct run records and screenshot hash are persisted; persistence/replay regression tests pass. |
| Real UGround-V1-2B loading, inference, output parsing, and coordinate validation | **BLOCKED** | Artifacts and runtime configured, but 0.57 GB free was below the 3.63 GB guard. **REAL_VISION_EXECUTION: NOT_VERIFIED.** |
| Independent accuracy comparison and expanded scenario coverage | **NOT TESTED / BLOCKED BY DATA** | Two manually annotated cases demonstrate harness operation only; there are no independent annotations for similar controls, UIA-only-visible controls, boundary selection, or transform variation. |
| Full live GUI grounding with manually entered target and aligned result overlay | **NOT TESTED** | Application launch, screenshot capture, OCR processing, and responsiveness passed. The final GUI capture used the tutor's current instruction and is explicitly not claimed as a successful target-grounding run. |
| Full build, regression suite, live UIA integration, and graceful shutdown | **TESTED AND PASSED** | 153-test full suite passed with 2 opt-in skips; the bridge-to-UIA live test separately passed; GUI exited normally. |

**Final recommendation: NOT READY FOR FREEZE.** Technically feasible UIA context propagation, text/control metric separation, availability-aware denominators, provider execution accounting, duplicate evidence handling, and deterministic fusion coverage are implemented and verified. Freeze remains blocked by the host's safe-inference memory limit, absent live disagreement validation, a two-case dataset, and untested mixed-DPI/multi-monitor behavior. No comparative-accuracy or research-hypothesis claim is supported. Preserve the current implementation and evidence; do not claim that the research question has been answered.
