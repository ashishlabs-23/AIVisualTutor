# Phase 4H — Real Windows UIA + OCR Evidence Study

## Controlled browser retry — Playwright unavailable

**Outcome: STOPPED at the required availability check.** No Playwright runtime was available in the checked Node.js or Python environments. Per the retry instructions, nothing was installed and no browser was launched.

### 1. Test Environment

- Host: Windows.
- Existing browser binaries discovered: Google Chrome, Microsoft Edge, and Mozilla Firefox.
- No browser profile was opened or inspected for this check.

### 2. Playwright Availability

- Node.js `require.resolve`: `playwright`, `playwright-core`, and `puppeteer` all unavailable.
- Python module discovery: `playwright` and `pyppeteer` unavailable.
- No installed Playwright browser cache was discovered in the checked cache locations.
- **PLAYWRIGHT_STATUS: UNAVAILABLE.**
- No package was installed and no repository/runtime configuration was changed.

### 3. Controlled Browser Target

**NOT_RUN.** No isolated browser or context was launched. No local/data URL page or target was created.

### 4. Independent DOM Ground Truth

**NOT_RUN.** No DOM, target text, role, bounds, viewport, or page screenshot was obtained.

### 5. Human Verification

**NOT_RUN.** No test page was presented; target visibility and ambiguity were not assessed.

### 6. Project Capture Result

**NOT_RUN.** The project capture/selection path was not started. No project screenshot, selected region, or crop exists.

### 7. UIA Result

**NOT_RUN.** The production UIA provider was not invoked; no browser HWND or result exists.

### 8. OCR Result

**NOT_RUN.** The production OCR provider was not invoked; no project crop exists.

### 9. Coordinate Spaces

No target geometry was observed. DOM, UIA, crop, and project-selection coordinate spaces were not compared. Mapping is `NOT_VERIFIED`; IoU and point-in-target are `NOT_COMPUTABLE`.

### 10. Cross-Source Comparison

Ground truth, human, UIA, and OCR comparisons are all `NOT_RUN`. No agreement, conflict, or complementary evidence is claimed.

### 11. Failure / Limitation Analysis

The exact blocker is missing preinstalled Playwright in both checked runtimes. Although Chrome, Edge, and Firefox executables were discovered, the task requires Playwright-controlled isolation and explicitly prohibits installing packages when Playwright is unavailable. No browser process was started.

### 12. Research Implication

This retry adds no real-window evidence. The research decision remains **C — evidence insufficient**. Visual-grounding justification remains unverified, and adaptive-evaluator justification is not established.

### 13. Reproducibility Notes

Checked Node modules with `require.resolve` for `playwright`, `playwright-core`, and `puppeteer`; checked Python module availability for `playwright` and `pyppeteer`; checked standard browser executable paths and Playwright cache locations. No installation, browser launch, screenshot, capture, provider invocation, or persistent test artifact was produced.

## 4H retry — controlled target attempt

**Outcome: BLOCKED before target creation.** The prescribed argument-free `Start-Process notepad.exe -PassThru` launch returned PID `5300`, but that process had no main window handle or window title. It did not establish a separate, visible, human-verifiable blank Notepad window. No existing Notepad window was inspected or changed. The probe process was stopped by its exact PID and verified exited.

The attempt stopped at this blocker. No text was typed, no target was established, no project capture/selection flow was started, no screenshot/crop was obtained, and neither production provider was invoked. This is not a successful UIA/OCR validation.

### 1. Test Environment

- Host: Windows.
- Previous read-only host discovery in this study observed one primary monitor at 1920×1080, 96 DPI (100%). No display settings were changed.
- Controlled application launch: Notepad, process ID `5300`.
- HWND: unavailable (`MainWindowHandle = 0`).
- Visible window: not established; window title and bounds unavailable.
- Cleanup: PID `5300` exited after stopping the launched no-window process.

### 2. Controlled Target

No controlled window or target was established. Target count: `0`. The existing Notepad window was not inspected or operated.

### 3. Human Ground Truth

`NOT_RUN`. No target description, type, text, bounds, or neighboring-content assessment can be assigned because there was no human-visible controlled target.

### 4. Capture Result

`REAL_TARGET_SCREENSHOT: NOT_OBTAINED`. The existing project capture → selector → selected `Rectangle` → crop → `ContextProcessor` flow was not started. Selected region, crop size, and screenshot human-verifiability are unavailable.

### 5. UIA Result

`NOT_RUN`. The production `WindowsUiAutomationPerceptionEngine` was not invoked. There is no real `PerceptionResult`, HWND-backed UIA result, provider status, or UIA geometry.

### 6. OCR Result

`NOT_RUN`. The production `TesseractOcrService` was not invoked because no project capture crop existed. The prior finding that `tesseract.exe` was not on a shell PATH is not evidence that the in-process Tess4J provider is unavailable.

### 7. Coordinate Spaces

No target geometries were observed. UIA-to-crop mapping is `NOT_VERIFIED`; geometry comparison, IoU, and point-in-target are `NOT_COMPUTABLE`. The single-monitor 100% DPI observation alone does not establish a capture coordinate transform.

### 8. Cross-Source Comparison

Semantic identity, visible text, and geometry comparisons are all `NOT_RUN`. No complementary or conflicting evidence was obtained.

### 9. Failure / Limitation Analysis

Observed blocker: the argument-free Notepad launch process had no main window handle or title, so an isolated visible window could not be verified. The exact launched PID was stopped and verified exited. No target, capture, UIA, or OCR failure was measured. No other app was tried; no source result is inferred.

### 10. Research Implication

This retry adds no real target evidence. Visual-grounding and adaptive-evaluator justification remain unverified/not established. The research decision remains **C — evidence insufficient**. A successful later attempt needs a safely isolated visible window and a human-verifiable target through the existing project capture path before either provider is run.

### 11. Reproducibility Notes

The only launch command used was `Start-Process notepad.exe -PassThru` with no document argument. The returned process PID was `5300`; `MainWindowHandle` was `0`. The process was stopped by PID and its exit verified. No screenshot, user document, or test file was created.

## 1. Objective

Observe how the implemented Windows UI Automation (UIA) and Tesseract OCR providers behave on the same human-identified real application targets. This is a small evidence study, not a benchmark or model evaluation.

**Status: REAL TARGET EVIDENCE NOT RUN.** Application discovery and read-only host/display inspection were performed, but no human-verified target was captured and evaluated by both providers. No real UIA/OCR agreement, conflict, or geometry result is claimed.

## 2. Experimental setup

- Host OS: Windows; exact edition/build was not collected.
- Display: one monitor, `\\.\DISPLAY1`, desktop bounds `(0, 0, 1920, 1080)`, primary.
- Monitor DPI: `GetDpiForMonitor` returned 96 × 96 DPI (100% relative to 96 DPI).
- Display settings were not changed.
- No application content, documents, web pages, or window titles were retained as study data.
- UIA/OCR integration and provider tests are automated tests, not manual real-window observations.
- No grounding model or VLM was run.
- A bounded subagent probe confirmed PowerShell can load `UIAutomationClient`; it did not invoke the repository UIA provider. `tesseract.exe` was not on that shell's PATH; the in-process Tess4J provider was not invoked, so OCR availability on a real target remains UNVERIFIED.
- The probe attempted `notepad.exe --new-window`, but no separate visible window appeared. The argument was treated as a document name and caused an empty `--new-window.txt` artifact in the repository; the subagent identified and removed only that empty file. It did not inspect or operate the existing Notepad window.
- Previously recorded validation in this study: clean `:composeApp:build` passed; full suite passed with 62 tests, 0 failures/errors; the then-targeted context/selection/capture/overlay regression run passed (53 tests). This is automated code validation, not real-window UIA/OCR behavior.
- Previous Phase 4 finalization rerun: `:composeApp:test --rerun-tasks` passed (62 tests, 0 failures/errors/skips); `:composeApp:test --tests 'context.*' --rerun-tasks` passed (37 tests, 0 failures/errors/skips); `:composeApp:build --rerun-tasks` passed.
- Visual-grounding/evaluator finalization rerun: `:composeApp:test --rerun-tasks` passed (81 tests, 0 failures/errors/skips); `:composeApp:test --tests 'context.*' --rerun-tasks` passed (56 tests, 0 failures/errors/skips); `:composeApp:build --rerun-tasks` passed. These automated reruns do not establish real-window UIA/OCR behavior.

## Production-path audit (code inspection; not a 4H runtime observation)

The Phase 3/4 path inspected for this study is:

1. `VisualContextAcquisition.begin()` obtains `ApplicationContext` before selector focus, retaining process identity, HWND, and available window bounds.
2. `VisualContextAcquisition.process()` passes the frozen screen snapshot and selected desktop rectangle to `ScreenCaptureService.captureFrozenRegion()`. It passes the resulting selected image, the selected rectangle's desktop origin/bounds, and the previously retained application context to `ContextProcessor.process()`.
3. `ContextProcessor` constructs one `PerceptionRequest` from that crop, selected desktop rectangle, and application context; the same crop is sent independently to OCR. It retains `perceptionResult` and `ocrResult` separately on `VisualContext`.
4. The Windows UIA provider queries descendants of the retained HWND, filters/ranks by intersection with the selected desktop rectangle, and returns one top-ranked candidate. Its desktop-coordinate precondition checks AWT display transforms.
5. Tesseract returns OCR word rectangles in crop-image pixels. Production code does not currently transform these word boxes into desktop coordinates.

This inspection confirms the intended data flow only. It does not show that the capture path, HWND, either provider, or a geometry transformation worked on a real target during 4H. The existing capture/selection flow was not operated in the manual probe because a separate controlled app window and human-verifiable selected crop were not obtained.

### Coordinate-transform status

**NOT IMPLEMENTED / NOT ESTABLISHED.** OCR word bounds remain crop-image pixels. UIA bounds are physical desktop/screen coordinates when the provider's display-coordinate precondition passes. The selection is supplied in Java AWT desktop/user coordinates. Frozen capture clips the selection and uses per-axis output scales, but the exact clipped origin and scale pair are not retained with OCR evidence. The UIA precondition is not a coordinate transform. No deterministic UIA-to-OCR geometry comparison is currently available; mixed-DPI comparison is not claimed.

The existing `RegionSelector.selectionToImageCrop()` conversion is **FOUND BUT NOT APPLICABLE** to this cross-provider mapping: it maps pointer positions in the selector's displayed image rectangle into pixels of the stitched logical frozen snapshot, then adds `snapshot.monitorBounds` to emit the selected desktop/user-space rectangle. Its geometry tests cover image-display scaling, offsets, reverse drag, edge clamping, and crop pixels. It does not map the later frozen-region output pixels (which use monitor scale factors and clipping) to physical UIA screen coordinates. No reusable, already-tested OCR-crop-to-physical-screen transform was found.

## 3. Host applications discovered

Discovery used running-window process identity and Start Menu shortcut names. A shortcut indicates a discovered entry, not that the application was launched or tested. Versions are included only where executable product metadata was safely available.

| Application | Version if observed | Available | Testable | Reason / evidence |
|---|---|---|---|---|
| Windows Notepad | `11.2607.14.0` | YES — running process observed | UNVERIFIED | Candidate native text editor; no controlled test window opened. Existing document/window content was not inspected. |
| Calculator | UNVERIFIED | YES — Calculator window/process host observed | UNVERIFIED | Candidate native application; no UIA/OCR target was captured. |
| Google Chrome | `154.0.8037.98` | YES — running process observed | UNVERIFIED | Candidate browser; active user profile/content was not inspected or used. |
| File Explorer | UNVERIFIED | YES — Start Menu entry observed | UNVERIFIED | Candidate native Windows application; no controlled window opened. |
| Microsoft Edge | UNVERIFIED | YES — Start Menu entry observed | UNVERIFIED | Candidate browser; not launched. |
| Firefox | UNVERIFIED | YES — Start Menu entry observed | UNVERIFIED | Candidate browser; not launched. |
| Visual Studio Code | UNVERIFIED | YES — Start Menu entry observed | UNVERIFIED | Candidate application with potentially custom UI; not launched. |

**Applications tested: none.** Discovery does not constitute target testing. The Notepad command-line probe did not produce a separate visible test window, and no application UI was inspected or operated.

## 4. Target selection methodology

The intended methodology was to use only blank/test content, identify each target visually before reading provider results, capture one selected crop, and query both sources for that same target. No target was selected because the bounded probe did not produce a separate controlled window or human-verifiable screenshot, and the existing application's screenshot/selection flow could not be safely operated. No existing user application or document was manipulated.

## 5. Human ground truth methodology

Human ground truth requires a human-visible screenshot or live view of the exact target before interpreting UIA or OCR output. No such screenshot/target pair was captured for 4H. Therefore no target descriptions, expected controls, or correctness labels were assigned.

## 6. UIA observations

**NOT_RUN on real targets.** The production provider and its automated tests were inspected; those tests use injected candidates and do not establish observations from a real Windows window. Loading the UIAutomation assembly alone did not invoke the application provider.

For all intended real cases:

| UIA field | Study value |
|---|---|
| UIA_AVAILABLE | NOT_RUN |
| UIA_SELECTED_OBJECT | NOT_RUN |
| UIA_CONTROL_TYPE | NOT_RUN |
| UIA_AUTOMATION_ID | NOT_RUN |
| UIA_BOUNDS | NOT_RUN |
| UIA_ENABLED / UIA_OFFSCREEN / UIA_VALUE | NOT_RUN |
| UIA_CANDIDATE_COUNT | NOT_RUN |
| UIA_PROVIDER_METADATA | NOT_RUN |
| UIA_TARGET_CORRECT | UNVERIFIED |

The provider implementation currently queries the retained HWND, intersects descendants with the selected desktop rectangle, ranks candidates, and returns the first ranked candidate. Its integration/test behavior is recorded in `PHASE_4_IMPLEMENTATION.md`; none of that is evidence of real target correctness.

## 7. OCR observations

**NOT_RUN on real targets.** No real application crop was passed to Tesseract as part of a manual test. The absence of a `tesseract.exe` PATH entry does not establish that the in-process Tess4J provider is unavailable.

| OCR field | Study value |
|---|---|
| OCR_AVAILABLE | NOT_RUN |
| OCR_TEXT / OCR_WORDS / OCR_CONFIDENCE / OCR_BOUNDS | NOT_RUN |
| OCR_COORDINATE_SPACE | Would be `CROP_IMAGE_PIXELS` by the provider contract; no real observation |
| OCR_TARGET_TEXT_FOUND | UNVERIFIED |
| OCR_TARGET_GEOMETRY_OVERLAP | NOT_RUN |

The synthetic Phase 4G observation where OCR returned `*` for a star fixture remains a controlled synthetic result, not a real Windows OCR observation.

## 8. Coordinate-space handling

The inspected production contracts declare:

- Selection bounds: AWT desktop user-space coordinates.
- UIA candidate bounds: desktop screen coordinates; the provider refuses the query when its AWT display-transform precondition is not met.
- OCR word bounds: pixels relative to the selected `BufferedImage` (`CROP_IMAGE_PIXELS`).
- Captured image dimensions and desktop selection dimensions are distinct measurements; the 100% monitor DPI observation alone does not prove a crop-to-desktop transform for a captured target.

| Coordinate comparison field | Study value |
|---|---|
| UIA_COORDINATE_SPACE | Desktop/screen coordinates (implementation contract; no target observation) |
| OCR_COORDINATE_SPACE | Crop-image pixels (implementation contract; no target observation) |
| COMMON_SPACE | NONE established |
| TRANSFORMATION_VERIFIED | NO |
| GEOMETRY_COMPARISON | NOT_COMPUTABLE |

No IoU, point-in-target, boundary error, scale factor, or geometry agreement was calculated.

## 9. UIA/OCR agreement, conflicts, and complementary cases

No case had both sources observed on the same real target. Consequently:

- Semantic agreement: NOT_RUN.
- Semantic conflicts: NOT_RUN.
- UIA-only / OCR-only real cases: NOT_RUN.
- Cases where both sources agree or disagree: NOT_RUN.
- Cases where one source provides semantics and the other better geometry: NOT_RUN.
- Cases where both fail despite a human-identifiable target: NOT_RUN.

## 10. Icon-only, custom UI, and ambiguous targets

| Target class | Real observation |
|---|---|
| Icon-only control | NOT_RUN. No real icon target was captured. The synthetic star-to-`*` result in 4G is not a real application observation. |
| Custom-rendered/canvas control | NOT_RUN. VS Code was discovered as a candidate only; it was not opened or tested. |
| Adjacent/ambiguous controls | NOT_RUN. No target-description variants were applied to a real target. |

No VLM or GUI-grounding model was invoked.

## 11. DPI observations

- `DISPLAY_COUNT`: 1.
- `DISPLAY_BOUNDS`: `\\.\DISPLAY1` at `(0, 0, 1920, 1080)`.
- `DISPLAY_SCALING`: 100% (96 DPI from `GetDpiForMonitor`).
- `DPI_RELEVANT_INFORMATION`: one monitor; no display settings were changed.
- `DPI_EXPERIMENT`: NOT_RUN. No second scaling condition was tested.

The monitor DPI reading is host configuration evidence only. It does not validate UIA/OCR geometry alignment for a selected application crop.

## 12. Complete case matrix

No real case IDs were created: no target met the prerequisite of human identification and same-target capture. Thus the real-target count is **0**. Every target type below is explicitly untested.

| Target type | Case ID | Case, UIA, OCR, semantic, geometry, and human assessment |
|---|---|---|
| Native button | NOT_RUN | No target selected; all result fields NOT_RUN; target correctness UNVERIFIED. |
| Text field | NOT_RUN | No target selected; all result fields NOT_RUN; target correctness UNVERIFIED. |
| Menu/menu item | NOT_RUN | No target selected; all result fields NOT_RUN; target correctness UNVERIFIED. |
| Checkbox/toggle | NOT_RUN | No target selected; all result fields NOT_RUN; target correctness UNVERIFIED. |
| Visible text | NOT_RUN | No target selected; all result fields NOT_RUN; target correctness UNVERIFIED. |
| Icon-only control | NOT_RUN | No target selected; all result fields NOT_RUN; target correctness UNVERIFIED. |
| Adjacent/ambiguous controls | NOT_RUN | No target selected; all description variants and agreement fields NOT_RUN. |
| Custom-rendered control | NOT_RUN | No target selected; UIA/OCR/human result NOT_RUN. |

The requested per-case matrix fields (case ID, application/window, target description, human target, UIA result/correctness, OCR result/correctness, semantic agreement, geometry comparison/quality, final human assessment, insufficient-evidence state, and notes) are therefore `NOT_RUN` or `UNVERIFIED`; there are no raw real-target observations to report.

## 13. Failure analysis

No real-window failure mode was observed. UIA missing/wrong/ambiguous candidates, incorrect bounds, OCR missing/false-positive text, neighboring-text ambiguity, icon/custom-rendered semantic gaps, stale/offscreen evidence, and source disagreement are all **NOT_RUN**, not negative results.

The only relevant observed false positive remains the controlled 4G OCR fixture result. It is not counted as a 4H failure.

## 14. Experimental limitations

- No real screenshot/crop and human target annotation were obtained.
- Neither production provider was invoked on a real application target.
- The bounded application probe did not produce a separate blank Notepad window; the existing Notepad window was left untouched.
- Application availability was discovered, but availability and versions were not uniformly verified.
- No same-target evidence exists for semantic or geometric comparison.
- The display DPI condition was observed once; alternate scaling and capture-to-desktop geometry were not tested.
- No benchmark, accuracy estimate, or model result is available.

## 15. Research interpretation and next decision

**VISUAL_GROUNDING_JUSTIFICATION: UNVERIFIED.** This study establishes neither that existing sources suffice nor that they leave a measured real-app gap.

**ADAPTIVE_EVALUATOR_JUSTIFICATION: NOT ESTABLISHED.** The 4G record type is implemented, but this 4H study contributes no real observations from which to justify adaptive evaluation, routing, or thresholds.

**RESEARCH_DECISION: C — Evidence remains insufficient because real-window evaluation did not establish reliable conclusions.**

Next research step: perform a controlled manual run with a human-visible selected target and same-crop UIA plus OCR observations, while retaining raw evidence and refusing geometry comparison until a crop-to-desktop transformation is verified. This unrun study does not validate a real visual-grounding model or justify learned adaptive routing.
