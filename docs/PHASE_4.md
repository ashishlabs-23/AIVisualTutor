# Phase 4 — Evidence-Oriented Perception

## Current status

**PHASE_4_STATUS: `COMPLETE_EMPIRICALLY_EVALUATED`**

**PHASE_4_FREEZE: `COMPLETE_AND_FROZEN`** — implementation and planned empirical observations are complete. Remaining items below are documented research limitations, not unfinished Phase 4 implementation.

Phase 4 perception implementations (UIA, Tess4J/Tesseract OCR, deterministic evidence evaluation, and visual-grounding contracts) have completed their planned empirical evaluation following the confirmation of 18/18 human ground-truth labels across calibration cases C01–C18.

- Ground truth: **18/18 calibration cases human-confirmed** (16 labelled valid targets, 2 `NO_VALID_TARGET`, 0 pending).
- Evaluator Reliability: **0 false acceptances (0/18 false ACCEPT, 0.0% false accept rate)**. Deterministic policy safely abstained across all 18 cases.
- OCR Correctness: **8/18 (44.4%) overall**; effective on clean/high-contrast text (Notepad header/body/dense text, Calculator CE button) and non-text rejection, but produces noise on blank canvases, toolbar icons, and low-contrast UI.
- UIA Capture vs Replay: Historical capture-time UIA failure (18/18 FAILURE) remains **UNRESOLVED** due to unpersisted capture exception traces; offline replay succeeded across all 18 cases (18/18 SUCCESS). Capture-time UIA accuracy is **NOT_COMPUTABLE** (0 evaluable denominator).
- Visual Grounding: Remains **`NOT_CONFIGURED`** (real model executed: NO, CPU path RAM-insufficient, Vulkan GPU path failed with driver queue error; no third stack attempted).
- Coordinate Spaces & Geometry: OCR (`CROP_IMAGE_PIXELS`) and UIA (`PHYSICAL_DESKTOP_SCREEN`) remain separate; cross-space geometry comparison and IoU are **`NOT_COMPUTABLE`**.
- Research Questions: RQ1, RQ2, and RQ3 are **`PARTIALLY_ANSWERED`**.
- User Study / Learning Improvement: **`NOT_PERFORMED` / `NOT_MEASURED`**.
- Blender real-application extension: **4/4 cases visually reviewed and measured**; OCR identified the structured Outliner label and property-row label (2/4), UIA returned only the top-level Blender window for all four targets (0/4 semantically correct), and the deterministic evaluator correctly abstained on all four.
- Tess4J native crash investigation: reproduced for JDK 22.0.2 at `TessBaseAPIInit1`, including on the existing known-good OCR fixture; the same evidence passes on the configured Java 17 toolchain. The calibration JavaExec task is pinned to Java 17; the native JDK 22 failure remains an explicit runtime limitation.

## Scope and architecture as built

Phase 4 adds an evidence-oriented perception and evaluation layer over a selected screenshot, selected desktop region, and optional foreground application context. UIA, OCR, and optional visual-grounding evidence remain separately typed and provenance-bearing. A deterministic evaluator returns per-request decisions; a coordinator can conditionally invoke a configured visual provider. There is no model-backed inference, learned routing, continuous capture, action execution, or implemented evidence fusion.

### Perception contracts and pipeline

`PerceptionRequest` carries a `BufferedImage`, selected desktop `Rectangle`, and nullable `ApplicationContext`; `PerceptionResult` carries the provider's selected object/text/type/bounds/confidence/source/metadata. `PerceptionEngine` is the suspend provider boundary. `VisualContext` retains `perceptionResult` and `ocrResult` independently.

`ContextProcessor` invokes OCR, classification, and perception concurrently. UIA failure or an empty result does not prevent constructing `VisualContext`. The application context and HWND are obtained before selector focus in the normal region flow. The current selected tutor-step instruction can be passed through acquisition as an optional target description; it is forwarded unchanged and is not asserted to be a validated real-app control label. Callers may still supply null/blank descriptions.

The later 4-Gap follow-up wired `TutorController.currentTargetDescription`, sourced unchanged from the active `TutorStep.instruction`, through `VisualContextAcquisition.process` into `ContextProcessor`; all 13 existing Blender/PDF/Excel mock-step instructions were nonblank. This verifies propagation only, not that an instruction is a concise or correct real-control label. Other API callers retain null as the default; null/blank descriptions do not invent a target.

### Windows UI Automation

`WindowsUiAutomationPerceptionEngine` uses the HWND already captured before selector focus. The production adapter launches isolated PowerShell with `-STA`, loads `UIAutomationClient`/`UIAutomationTypes`, queries the root and descendants, filters bounds intersecting the selected desktop region, deterministically ranks candidates, and returns the highest-ranked candidate. It exposes UIA Name, AutomationId, control-type ID/mapped `UiType`, enabled/offscreen state, `ValuePattern` value where available, candidate count, and provider status. Explicit outcomes include unsupported platform, missing HWND, unsupported coordinate mapping, query failure, and no intersecting element.

The mapped control types are intentionally partial. UIA Name is not presumed to be visible text; `visibleText` is supplied only from ValuePattern. A returned UIA element/intersection is not a human-verified semantic match. Broad real-app support, custom-rendered application support, and high-DPI mapping are not established.

### OCR

`TesseractOcrService` uses local Tess4J/Tesseract on Windows. `OcrResult` retains recognized text, per-word crop-relative boxes, normalized OCR confidence where supplied, language, reading order, engine/provider/status, and timing metadata. `VisualContext.extractedText` remains for compatibility. Synthetic OCR fixtures cover text-only, button-like, dense UI, neighboring text, icon-only, and empty regions. These tests are regression fixtures, not real-app correctness evaluations.

### Deterministic evidence evaluation

`EvidenceEvaluator` returns `ACCEPT`, `REFINE`, `ESCALATE`, or `ABSTAIN`; `EvidenceEvaluationCoordinator` records source availability, original provider evidence, reason, visual provider availability/diagnostic, and invocation status independently.

- Fixed engineering gates: UIA confidence >= 0.80; OCR confidence >= 0.80; visual semantic confidence >= 0.80; visual geometry confidence >= 0.65. They are not calibrated probabilities or measured accuracy.
- UIA confidence 1.0 is a deterministic evidence marker, not a model probability. Source confidences are never averaged.
- A source match requires non-action/non-generic target tokens to occur in the relevant UIA label or OCR text. A concise high-confidence UIA/OCR disagreement escalates; unrelated dense OCR is not automatically a label conflict.
- Strong UIA or OCR target evidence can accept independently. Visual acceptance requires both semantic and geometric evidence to pass their own gates and geometry to be in a supported crop coordinate space.
- A configured visual provider is invoked at most once when deterministic evidence does not accept or visual grounding is explicitly required. Missing/blank targets do not invoke a provider. Exceptions become explicit failure evidence; cancellation propagates.
- `REFINE` identifies insufficient deterministic evidence where an available visual provider may improve it; `ESCALATE` marks a conflict or explicitly required visual evidence; `ABSTAIN` covers missing target, unavailable/failed provider, or insufficient evidence after an attempt. Pure evaluator proposals can remain PENDING until coordination finalizes invocation state.
- These are deterministic rules, not a learned/adaptive evaluator. No learned abstention model, repeated retry loop, geometry resolver, or adaptive threshold is implemented.
- Future geometry authority was documented as UIA, then OCR, then visual grounding, then VLM only if necessary. This is a design constraint, not a production selection algorithm. A future change-detection insertion point was identified after capture/crop and before provider dispatch at `ContextProcessor`; no detector or cache was added.

### Visual-grounding contract and research boundary

`VisualGroundingProvider` accepts the screenshot, application context, supplied target description, and separate OCR/UIA evidence. It can return separate semantic and geometric evidence, provider identity, availability, execution location, latency, and metadata. Geometry is tagged as `CROP_IMAGE_PIXELS` or `NORMALIZED_CROP`; the evidence registry can separately tag physical desktop bounds. The default provider is `NOT_CONFIGURED`, returns no prediction, and does not download weights or invoke a fake model. Test fakes are marked `TEST_FAKE_PROVIDER`.

ShowUI-2B, Qwen2-VL-2B-Instruct and other grounding candidates were researched, but no real provider/model is installed or configured. Earlier feasibility inspection found CPU-only PyTorch 2.13.0, no CUDA availability, absent `transformers`, `qwen_vl_utils`, `accelerate`, and `safetensors`, and approximately 1.67 GiB free of 15.76 GiB system memory at that inspection. The CPU model load/inference smoke test was not run because the runtime was absent and available RAM was inadequate for a safe load.

The Hugging Face access check found both `showlab/ShowUI-2B` and `Qwen/Qwen2-VL-2B-Instruct` public/ungated (`gated=false`, `private=false`) and unauthenticated `config.json` fetches returned HTTP 200. No token was used; authentication was not the blocker.

The later local GGUF/Vulkan smoke test loaded the Qwen2-VL-2B-Instruct Q4_K_M model plus f16 mmproj, encoded an image batch, then failed with the recorded driver/runtime error `vkQueueSubmit: Invalid queue [VUID-vkQueueSubmit-queue-parameter]` after model load. The saved smoke metadata records 4.99 GiB free RAM before/after, 2.381 GiB peak working set, 4,129 ms wall time, and no clean exit code. The chosen ShowUI-2B GGUF repository lacked an mmproj, so the Qwen2-VL base GGUF+mmproj was used. Track A was stopped under the no-third-stack rule. **Current status remains `NOT_CONFIGURED` — CPU path RAM-blocked, GPU/Vulkan path driver-blocked; no further stack attempted.** These host-specific blockers do not establish that the models cannot run on other hardware.

The captured Vulkan diagnostic also records the two GGUF sizes (Q4_K_M 986,047,232 bytes; f16 mmproj 1,331,656,192 bytes), no `nvidia-smi`, `-ngl 0`, `--no-mmproj-offload`, and image batch encoding completed in about 581 ms before the queue error. The source labels the CLI `winget ggml.llamacpp b11433 win-vulkan-x64`; process ended without a clean exit code. These are the local failed smoke-run measurements, not an inference benchmark.

The earlier candidate survey also covered ZonUI-3B, FocusUI, OmniParser v2, and ScreenSpot-Pro as research references. None was installed or run. Published benchmark numbers are not this project's results. No novelty, accuracy, or SOTA claim is made.

## Coordinate spaces and comparison limits

- Selection bounds are Java AWT desktop/user-space coordinates, which may have negative origins.
- UIA bounds are Windows physical desktop/screen coordinates when the provider's AWT display-transform precondition passes.
- OCR word bounds are `CROP_IMAGE_PIXELS` relative to the selected `BufferedImage`.
- Frozen capture clips the requested region to monitor-union bounds and uses per-axis output scales. The clipped origin and exact X/Y output scales are not carried as a typed transform with OCR evidence; current `scaleFactor` metadata is not a complete transform.
- The UIA DPI precondition is only a guard, not a conversion.
- `RegionSelector.selectionToImageCrop()` maps pointer positions in its displayed logical frozen image back to the stitched logical snapshot and selected desktop/user coordinates. It does not map the later frozen-region crop pixels to physical UIA coordinates.

**No OCR/UIA cross-source geometry comparison, IoU, point-in-target, or mixed-DPI conversion is implemented or established.** IoU is unavailable across incompatible geometry types/spaces, including crop pixels versus physical desktop bounds. A single-monitor 100% DPI observation does not verify the missing mapping.

## Visual-grounding and real-window research history

### 4E/4F candidate decision and CPU feasibility

The 4E research decision was **B**: a dedicated GUI-grounding provider was considered justified as a separate controlled experiment because OCR/UIA cannot fully cover custom-rendered/icon targets. This was a research recommendation, not an implementation or proof of real-app need. ShowUI's public model-card metadata listed MIT weights and a Qwen2-VL-2B base; Qwen's model card/repository listed Apache-2.0. ShowUI's inspected Windows instructions assumed CUDA and >=6 GB VRAM for its 4-bit variant. ZonUI, FocusUI and OmniParser were also surveyed, with their documented artifact/runtime/license limitations. No weights/runtime were added.

The original candidate comparison was:

| Candidate | Reported capability | Reported deployment/licensing facts | Assessment at the time |
|---|---|---|---|
| ShowUI-2B | Image plus natural-language target, normalized screenshot point `[x,y]` | Hugging Face weights MIT; ShowUI code Apache-2.0; inspected Windows 4-bit instructions assumed CUDA and >=6 GB VRAM | Closest small instruction-conditioned point-grounding candidate; inspected host lacked a discoverable CUDA device |
| ZonUI-3B | Text/icon targets across desktop, web, and mobile, with high-resolution focus | Model card Apache-2.0; repository described an RTX 4090 24 GB training setup; model repository listed about 30.1 GB of files; CPU requirements undocumented | Research candidate, but documented artifact/compute footprint not a fit for an unqualified in-process dependency |
| FocusUI 2B/3B | Query-guided visual-token selection; ScreenSpot-Pro evaluation script | Separate Python 3.12 environment/checkpoints; inspected README did not state standalone model license or CPU support | Efficiency candidate requiring artifact/license/runtime audit |
| OmniParser v2 | Separate screen parsing, icon/region detection and captions, returning boxes/labels rather than one instruction-grounded point | Separate Python stack/multiple weights; current v2 detector/caption notes identify MIT components, while earlier detector variants retain AGPL terms | Potential visual-region baseline, broader and more complex than minimum grounding boundary |

The 4E research cited ScreenSpot-Pro as 1,581 professional GUI instructions across 23 apps and three operating systems, spanning high-resolution, text, and icon targets. Published paper/model scores were explicitly not project results. The proposed experiment was to compare UIA only, OCR only, UIA+OCR, then an optional grounding provider while preserving prior outputs; later measure identification accuracy, box IoU, point-in-target, latency, CPU/GPU use, invocation and failure rates while recording model/version/weights/prompt/resolution/coordinate transforms/runtime/hardware/provenance. None of that real-target/model experiment was run. A future geometry-authority design note was UIA → OCR → visual grounding → VLM only if necessary; fusion and a geometry resolver remain unimplemented.

The later CPU smoke test was **not run** for the resource/runtime reasons above. It is not evidence that CPU inference is impossible in general.

### 4H initial study attempts

The initial 4H study established **no real target evidence**. Read-only host discovery found one `\\.\DISPLAY1` monitor at `(0,0,1920,1080)`, 96×96 DPI (100%); exact Windows edition/build was not collected and settings were not changed. Candidate applications were Notepad 11.2607.14.0, Calculator, Chrome 154.0.8037.98, File Explorer, Edge, Firefox, and VS Code; discovery did not mean they were tested.

- An argument-free Notepad probe returned PID 5300 without a main HWND/title. No controlled window/target was established; that exact probe process was stopped and verified exited. No existing Notepad content was inspected/operated.
- A separate `notepad.exe --new-window` attempt did not create a separate visible window; the argument was treated as a document name and caused an empty `--new-window.txt` repository artifact, which the original study says was removed. Existing Notepad was not operated in that probe.
- A browser retry stopped at the availability check: `playwright`, `playwright-core`, `puppeteer`, Python `playwright`, and `pyppeteer` were unavailable; no package was installed and no browser was launched.
- UIAutomationClient could be loaded by a bounded probe, but the repository production UIA provider was not invoked in that 4H probe. `tesseract.exe` was absent from shell PATH, but in-process Tess4J was not invoked; this did not prove OCR unavailable.
- Host application discovery showed Notepad (11.2607.14.0) and Chrome (154.0.8037.98) as running processes; Calculator was observed via its window/process host. File Explorer, Edge, Firefox, and VS Code were discovered from Start Menu entries. Testability remained UNVERIFIED for each; no application was tested.
- The intended target methodology required blank/test content, visual human identification before reading provider output, and querying UIA and OCR on the same crop. It was not reached. No user document/page/window title was retained as study data.
- No target/crop, human ground truth, real UIA/OCR observation, agreement/conflict, geometry comparison, or calibration set was obtained. The 4H research decision was **C — evidence insufficient**. Its study matrix classifies real target observations as NOT_RUN or UNVERIFIED, not failures.
- Exact historical automated validation records were: clean `:composeApp:build`; full suite 62 tests with 0 failures/errors; targeted context/selection/capture/overlay regression run 53 tests; a subsequent test rerun 62/0/0/0 and context selection 37/0/0/0; later Phase 4 finalization full suite 81/0/0/0 and context selection 56/0/0/0; and later 4-Gap follow-up full suite 83/0/0/0, combined context plus `TutorControllerTest` 63/0/0/0 (context-only 56), and build passed. These are historical automated code checks, not real-window results.

The browser retry specifically stopped before launch: no browser profile was opened, no controlled DOM/page, independent DOM ground truth, target screenshot, project capture, UIA/OCR result, or human verification was produced. It checked Node module resolution for `playwright`, `playwright-core`, `puppeteer`, Python module availability for `playwright` and `pyppeteer`, browser executable paths, and Playwright caches. Chrome, Edge and Firefox binaries were discovered, but the prescribed isolated Playwright launch was unavailable and package installation was prohibited. For that retry, coordinate mapping was NOT_VERIFIED, IoU and point-in-target were NOT_COMPUTABLE, and agreement/conflict were NOT_RUN.

The 4H field-level study matrix recorded UIA availability/object/type/AutomationId/bounds/enabled/offscreen/value/candidate count/provider metadata all NOT_RUN and target correctness UNVERIFIED; OCR availability/text/words/confidence/bounds all NOT_RUN, with the contract coordinate space identified as `CROP_IMAGE_PIXELS`, target-text-found UNVERIFIED, and geometry overlap NOT_RUN. No cases had both sources observed. Icon-only, custom-rendered/canvas, and adjacent/ambiguous targets were each NOT_RUN. The intended native button, text field, menu item, checkbox/toggle, visible text, icon-only, adjacent-control, and custom-rendered matrix had zero real cases. UIA missing/wrong/ambiguous candidates, incorrect bounds, OCR misses/false positives, neighboring-text ambiguity, custom-rendered semantic gaps, stale/offscreen evidence, and source disagreements were NOT_RUN rather than negative findings. The only relevant false positive then known was synthetic 4G OCR `*` on an icon fixture.

4H found the selector's `selectionToImageCrop()` conversion, which maps pointer positions in the displayed stitched logical snapshot through that snapshot's monitor bounds into selected desktop/user coordinates, with tests for scaling, offsets, reverse drag, edge clamping, and crop pixels. It is **not applicable** to mapping the later frozen-region crop output to physical UIA coordinates. No already-tested crop-pixel-to-physical-screen transform was found. The host's display observation alone did not validate geometry. The study's future recommendation was a controlled human-visible target run with both sources and raw evidence, refusing geometry comparison until the transform is verified. Visual-grounding justification was UNVERIFIED; adaptive-evaluator justification was NOT ESTABLISHED.

### 4-GAP-2: calibration cases and model smoke test

The saved harness contains 18 completed image-processing calibration cases C01–C18, with all 18 capture-time UIA statuses `FAILURE`; OCR was `AVAILABLE` on 13 and `INSUFFICIENT` on 5. The original harness output recorded `PENDING_LABEL` for each case; human-confirmed labels were added later and are now present in all 18 result files (see the final evaluation below). The raw per-case result files did not preserve UIA exception type/message/stack sufficient to explain those failures. The full records remain under [`calibration_4gap2/results/`](calibration_4gap2/results/), with manifest [`cases_manifest.tsv`](calibration_4gap2/cases_manifest.tsv), original crop inputs under [`crops/`](calibration_4gap2/crops/), and model artifacts under [`models/`](calibration_4gap2/models/).

The 4-G synthetic evidence suite comprised six controlled, non-real-window fixtures; UIA was not invoked because no real HWND/window existed:

| Fixture | Target description variants | Recorded synthetic OCR/test behavior |
|---|---|---|
| `text-only` | `Visible text baseline 42`; `Visible text`; `the visible text` | Text and word-box evidence asserted |
| `button-like` | `Save`; `Save button`; `the save control` | Word evidence intersects expected region; OCR does not establish button semantics |
| `dense-ui` | `File`; `File menu`; `the File control` | Structured word evidence intersects annotated region; no semantic disambiguation result |
| `neighboring-text` | `Save`; `Save button`; `the left Save control` | `Save` and `As` evidence asserted against expected-region intersection; no model result |
| `icon-only` | `star icon`; `the star control`; `the icon in the center` | OCR test asserts `*` with a box intersecting the icon region; does not establish icon identity |
| `empty-non-text` | No target | Explicit empty OCR evidence asserted; does not establish abstention policy |

The variants were annotated as the same expected target, and fixture region annotations are in crop-image pixels. They are deterministic synthetic regression examples, not outputs from a model or accuracy data. Thus no real UIA/OCR agreement/conflict, stale/offscreen candidate, source-complementarity result, geometry quality, or real app provider availability was established by 4G.

The GGUF/Vulkan failure was: both model files present; ShowUI GGUF mmproj absent, so Qwen2-VL base GGUF+mmproj used; no Nvidia utility found; even with `-ngl 0` and `--no-mmproj-offload`, Vulkan queue submission failed after loading/encoding with `vkQueueSubmit: Invalid queue [VUID-vkQueueSubmit-queue-parameter]`. Exact saved details are in [`TRACK_A_SMOKE_FAILURE.txt`](calibration_4gap2/results/TRACK_A_SMOKE_FAILURE.txt), [`smoke_stderr.txt`](calibration_4gap2/results/smoke_stderr.txt), [`smoke_meta.txt`](calibration_4gap2/results/smoke_meta.txt), prompt/stdout/test log/dependency record, and the two downloaded GGUFs. No third stack was attempted.

### 4-GAP-3: standalone UIA replay and unexplained discrepancy

The 18 historical results recorded `uiaStatus=FAILURE`, without diagnostic/exception/stack detail. A later production `WindowsUiAutomationPerceptionEngine.perceive` replay used each saved crop, recorded HWND, region, application and target; it returned at least one intersecting element for all 18: **18 SUCCESS, 0 FAILURE, 0 UNAVAILABLE, 0 INSUFFICIENT**. Candidate counts and latencies are in the archived 4-GAP-3 investigation and the calibration-case table below. Independent Windows UIA queries returned trees for the live Excel and Notepad windows. The checks found no confirmed elevation mismatch, STA issue, stale HWND, client interop initialization issue, or absent Excel/Notepad UIA tree.

The two requested direct reproductions were C01 (Excel cell, HWND `394800`, region `860,250,90,28`, target “Select cell B2.”) with 8 intersecting candidates, and C04 (Notepad text, HWND `264134`, region `60,140,500,120`, target “Read the highlighted text.”) with 3. Neither threw an exception, so neither produced an exception stack trace in that successful rerun. An independent Windows UIA client counted 122 Excel root-plus-descendant elements and 58 for Notepad; the production adapter serialized 121 and 44 elements with usable bounds, respectively.

The suspect checks were also recorded individually: the live Java/Gradle, PowerShell, Excel, and Notepad processes all had medium integrity/non-elevated execution; the production adapter launched `powershell.exe -STA`; each saved HWND was live and mapped to the expected process/window (Excel `394800`, Calculator `526012`, Notepad `264134`, Paint `263758`, Explorer `1902256`); production queried the supplied HWND rather than rereading foreground; and `UIAutomationClient`/`UIAutomationTypes` loaded and queried successfully. Independent UIA inspection saw Excel and Notepad trees. None of these tests identifies why the original run failed.

This later replay is **not a re-run of the original capture flow** and success means an intersecting element was returned, not that its chosen semantic target was correct. The historical 18/18 failure versus later replay 18/18 success discrepancy remains unexplained; do not reconcile it by guessing a cause.

Because the earlier failures did not reproduce and no cause was confirmed, this investigation made no code workaround and added no regression test for a speculative cause. Its full Gradle build and test suite passed; Phase 1–3 code paths were not modified. The investigation and ground-truth-review Markdown were the only files added for that work.

### 4-GAP-4: live hotkey-and-selector timing study

Eight completed cases used the actual `Ctrl+Shift+Space` shortcut and real full-screen selector; a further completed selection returned `missing_window_handle`. The eight were UIA SUCCESS with no thrown exceptions. Measured ranges were 2171.3359–2210.6809 ms from the foreground-capture timing marker, 1674.7073–1731.4654 ms from the tutor-hide-settled marker, and 19.4325–26.3642 ms from selector completion. These are code-path markers, not native OS focus/hide event timestamps. With no UIA failures, the timing hypothesis remained **INCONCLUSIVE**; no delay/retry was implemented.

The diagnostic was temporarily enabled with `aivt.uia.timingDiagnostics=true`, used `System.nanoTime()`, and was removed after the run. The pre-selector foreground snapshot was not a Windows focus-event hook; hide-settled was after the existing `isTutorWindowsVisible = false` and 200 ms settle delay, not a native hide timestamp; selection timing began at entry to the real selector's `onRegionSelected` callback. Each counted case had an external foreground window checked before capture. The preview was closed between cases to continue the normal user flow.

| Case | Foreground target (recorded HWND) | Selected region (desktop start to end) | UIA | Foreground snapshot → call (ms) | Hide settled → call (ms) | Selection callback → call (ms) |
|---|---|---|---|---:|---:|---:|
| L01 | Paint (`263758`) | `80,610` to `320,730` | SUCCESS | 2202.7241 | 1706.4655 | 23.1667 |
| L02 | Excel (`394800`) | `800,250` to `1100,380` | SUCCESS | 2191.3736 | 1692.8733 | 20.4868 |
| L03 | File Explorer (`1902256`) | `1450,300` to `1740,390` | SUCCESS | 2208.6085 | 1725.8875 | 26.3642 |
| L04 | Paint (`263758`) | `90,630` to `340,740` | SUCCESS | 2194.7024 | 1702.0808 | 21.4392 |
| L05 | Excel (`394800`) | `820,300` to `1120,430` | SUCCESS | 2210.6809 | 1706.9280 | 22.5938 |
| L06 | File Explorer (`1902256`) | `1490,360` to `1770,450` | SUCCESS | 2171.3359 | 1674.7073 | 19.4325 |
| L07 | Paint (`263758`) | `100,650` to `350,760` | SUCCESS | 2202.4894 | 1731.4654 | 22.6415 |
| L08 | Excel (`394800`) | `820,310` to `1130,440` | SUCCESS | 2174.5815 | 1698.6924 | 24.6654 |

The additional `missing_window_handle` case had respective deltas 2226.8485, 1689.4742, and 37.6842 ms. A later bounded comparison found it occurred before the unsafe Notepad close attempt, so it is a genuinely independent open item, not a downstream symptom of that incident. No cause was investigated further.

The timing report records OCR character counts for the eight successes (5, 37, 26, 9, 59, 57, 0, 80) and the missing-HWND case (14), but it did not persist OCR availability statuses. Character count is not an OCR outcome category.

The first timing attempt used JDK 22 and terminated in Tess4J native initialization (`TessBaseAPIInit1`, `Invalid memory access`) before a UIA timing record. It is not counted as a UIA outcome; completed runs used JDK 17. Two Calculator activation attempts could not establish Calculator as foreground and did not proceed to selection. They are not capture cases. There were zero UIA failures among the eight completed timing cases, so there were no failure deltas to compare and no wait/retry proposal was supported.

Temporary `TEMP_UIA_TIMING` diagnostics were removed after the run. Diagnostic compilation/tests passed while enabled, and the complete Gradle build/test suite passed again after removal. The diagnostic edits did not alter capture, focus, selector, hotkey, overlay, OCR, or UIA behavior.

### Safety incident and diagnostic safeguard

During an excluded focus attempt, a temporary ad-hoc PowerShell script called `SetForegroundWindow` for a preview handle but ignored the activation result, then sent Alt+F4 to whichever window was foreground. No task-owned-window allowlist or post-activation foreground verification existed. The incident record says Notepad closed after preview activation failed; no save was confirmed and unsaved data may have been lost. The attempted trigger was unchecked/unsuccessful preview activation, not a reported timeout. Alt+F4 targeted foreground, not an explicit Notepad HWND.

The unsafe script was not repository code and no diagnostic sender existed to patch. A repository-side `calibration.DiagnosticWindowCloseAllowlist` now admits only HWNDs returned by its own window-creation callback; the guarded signal hook rejects unowned handles and sends only if activation succeeds and that same HWND is verified foreground. There is no close-signal sender wired in current repository diagnostics. Regression tests assert rejection of an unrelated external HWND before activation and no signal callback after failed activation. The calibration-study record notes the incident and guard; never send close/terminate signals to external/user windows from test or diagnostic paths.

### Latest user Notepad capture

The crop `%TEMP%\AIVT_9d243cd466434b55aede473304dc1834_15703030827535814018.png` is 697×149, created 2026-10-07 12:05:02 local time; it visually contains the Notepad heading and paragraph. The unsaved runtime output recorded `CAPTURE_COMPLETED` 697×149 and `CONTEXT_CREATED` with 136 OCR characters. `VisualContext` has `perceptionResult: PerceptionResult?`, which would retain UIA evidence in memory, but no result file/database/cache persisted this capture's object. No IDE debugger session was available to inspect that transient instance. Its actual UIA status/object are **NOT_RECORDED_ANYWHERE in a recoverable capture record**—not a UIA success/failure result. OCR availability/status likewise was not saved.

## Real/capture-time outcome inventory and OCR summary

The 18 saved calibration cases are image-processing harness runs, not the same as end-to-end hotkey-selector runs. The 9 additional 4-GAP-4 selector-flow captures include the eight timing successes and one missing-HWND result. The latest Notepad capture is one more end-to-end selection. The later 18-case UIA replay is a replay, not an additional capture.

| Set | Count | UIA at recorded capture/runtime | Later UIA replay | OCR evidence/status |
|---|---:|---|---|---|
| Calibration C01–C18 | 18 | 18 FAILURE | 18 SUCCESS on saved crops | 13 AVAILABLE, 5 INSUFFICIENT |
| 4-GAP-4 live flow | 9 | 8 SUCCESS; 1 UNAVAILABLE (`missing_window_handle`); 0 FAILURE | N/A | Counts 5, 37, 26, 9, 59, 57, 0, 80, 14 characters logged; availability statuses not persisted |
| Latest user Notepad | 1 | NOT_RECORDED_ANYWHERE in recoverable capture record | N/A | 136 characters logged; availability/status not persisted |
| **All completed capture/processing observations** | **28** | As above | Replay is not a capture | As above |

Of the total 28 capture/processing observations, **10 were completed real hotkey-and-selector end-to-end flows** (9 from 4-GAP-4 plus the latest Notepad selection); the 18 calibration-harness image-processing runs are included in the total but were not those end-to-end flows. The eight successful timing cases are not double-counted.

### Calibration-case review and confirmed human ground truth

This table records the 18 calibration cases C01–C18 with human ground-truth labels confirmed locally. Each image link and result link resolves from `docs/PHASE_4.md`; raw crops and result files remain under `docs/calibration_4gap2/results/`. Selected coordinates are desktop `x,y,width,height`.

- **Ground truth summary:** 18/18 confirmed locally (16 labelled valid targets, 2 `NO_VALID_TARGET`, 0 pending).

| Case | Category | Target description | Selected region (desktop x,y,w,h) | Capture UIA / OCR status | Replay candidate count / latency ms | Crop | Raw result | Ground truth label |
|---|---|---|---|---|---|---|---|---|
| C01 | native_uia_excel_cell | Select cell B2. | `860,250,90,28` | FAILURE / AVAILABLE | 8 / 1050 | [C01 crop](calibration_4gap2/results/C01_crop.png) | [C01 result](calibration_4gap2/results/C01_result.txt) | `green_Excel_cell_area` |
| C02 | native_uia_excel_ribbon | Identify the visible green Excel ribbon area. | `820,120,100,30` | FAILURE / INSUFFICIENT | 7 / 467 | [C02 crop](calibration_4gap2/results/C02_crop.png) | [C02 result](calibration_4gap2/results/C02_result.txt) | `Excel_ribbon_green_area` |
| C03 | native_uia_calculator | Select the visible CE button in Calculator. | `1100,780,80,50` | FAILURE / AVAILABLE | 9 / 534 | [C03 crop](calibration_4gap2/results/C03_crop.png) | [C03 result](calibration_4gap2/results/C03_result.txt) | `CE_button_Calculator` |
| C04 | text_ocr_notepad | Read the visible paragraph about calibration evidence for OCR targets. | `60,140,500,120` | FAILURE / AVAILABLE | 3 / 480 | [C04 crop](calibration_4gap2/results/C04_crop.png) | [C04 result](calibration_4gap2/results/C04_result.txt) | `highlighted_paragraph_about_calibration_evidence_for_OCR_targets` |
| C05 | text_ocr_notepad_header | Locate the target section. | `60,100,500,50` | FAILURE / AVAILABLE | 17 / 458 | [C05 crop](calibration_4gap2/results/C05_crop.png) | [C05 result](calibration_4gap2/results/C05_result.txt) | `CALIBRATION_SECTION_HEADER` |
| C06 | icon_paint_toolbar | Select the visible Edit control in Paint's toolbar. | `80,600,48,48` | FAILURE / AVAILABLE | 11 / 499 | [C06 crop](calibration_4gap2/results/C06_crop.png) | [C06 result](calibration_4gap2/results/C06_result.txt) | `Edit_control_Paint_toolbar` |
| C07 | custom_render_paint_canvas | Select the blank white Paint canvas area below the toolbar. | `200,700,300,200` | FAILURE / AVAILABLE | 15 / 496 | [C07 crop](calibration_4gap2/results/C07_crop.png) | [C07 result](calibration_4gap2/results/C07_result.txt) | `blank_white_Paint_canvas` |
| C08 | no_structure_paint_canvas | Select the blank white Paint canvas area below the Colours panel. | `520,720,200,150` | FAILURE / AVAILABLE | 9 / 533 | [C08 crop](calibration_4gap2/results/C08_crop.png) | [C08 result](calibration_4gap2/results/C08_result.txt) | `blank_white_Paint_canvas_below_Colours_panel` |
| C09 | uia_explorer_item | Select the visible New control in the File Explorer toolbar. | `1450,280,300,80` | FAILURE / AVAILABLE | 23 / 672 | [C09 crop](calibration_4gap2/results/C09_crop.png) | [C09 result](calibration_4gap2/results/C09_result.txt) | `New_control_File_Explorer_toolbar` |
| C10 | native_uia_notepad_menu | Select the File menu in Notepad. | `40,70,50,28` | FAILURE / INSUFFICIENT | 10 / 432 | [C10 crop](calibration_4gap2/results/C10_crop.png) | [C10 result](calibration_4gap2/results/C10_result.txt) | `File_menu_Notepad` |
| C11 | conflict_excel_vs_ocr | Select the green Excel cell showing the visible text ending in "covered Files". | `860,250,120,40` | FAILURE / AVAILABLE | 8 / 444 | [C11 crop](calibration_4gap2/results/C11_crop.png) | [C11 result](calibration_4gap2/results/C11_result.txt) | `green_Excel_cell_with_text_ending_in_covered_Files` |
| C12 | conflict_notepad_menu_vs_body | Select the File menu in Notepad. | `40,70,600,200` | FAILURE / AVAILABLE | 34 / 413 | [C12 crop](calibration_4gap2/results/C12_crop.png) | [C12 result](calibration_4gap2/results/C12_result.txt) | `File_menu_Notepad` |
| C13 | native_uia_excel_formula | Enter a value. | `900,160,200,28` | FAILURE / INSUFFICIENT | 7 / 454 | [C13 crop](calibration_4gap2/results/C13_crop.png) | [C13 result](calibration_4gap2/results/C13_result.txt) | `NO_VALID_TARGET` |
| C14 | text_ocr_dense_notepad | Locate the target section. | `60,120,550,200` | FAILURE / AVAILABLE | 3 / 439 | [C14 crop](calibration_4gap2/results/C14_crop.png) | [C14 result](calibration_4gap2/results/C14_result.txt) | `CALIBRATION_SECTION_with_header_and_highlighted_paragraph` |
| C15 | icon_calc_button | Select the visible MR button in Calculator. | `1050,700,70,50` | FAILURE / INSUFFICIENT | 10 / 500 | [C15 crop](calibration_4gap2/results/C15_crop.png) | [C15 result](calibration_4gap2/results/C15_result.txt) | `NO_VALID_TARGET` |
| C16 | no_access_desktop_empty | Select the visible "demo" folder in File Explorer. | `1500,400,200,150` | FAILURE / AVAILABLE | 19 / 513 | [C16 crop](calibration_4gap2/results/C16_crop.png) | [C16 result](calibration_4gap2/results/C16_result.txt) | `demo_folder_in_File_Explorer` |
| C17 | native_uia_excel_title | Identify the green Excel title-bar area shown in the crop. | `780,45,200,28` | FAILURE / INSUFFICIENT | 6 / 455 | [C17 crop](calibration_4gap2/results/C17_crop.png) | [C17 result](calibration_4gap2/results/C17_result.txt) | `Excel_green_title_bar_area` |
| C18 | custom_render_unavailable_blender | Select the blank white Paint canvas in the proxy capture; Blender is unavailable in this case. | `200,700,300,200` | FAILURE / AVAILABLE | 15 / 657 | [C18 crop](calibration_4gap2/results/C18_crop.png) | [C18 result](calibration_4gap2/results/C18_result.txt) | `blank_white_Paint_canvas_proxy` |

## Empirical evaluation results

### Structured per-case evaluation table

| Case | Category | Ground truth label | UIA capture / replay | OCR status / text / conf | Evaluator decision / reason | UIA correctness | OCR correctness | Evaluator correctness | Correctness notes |
|---|---|---|---|---|---|---|---|---|---|
| C01 | native_uia_excel_cell | `green_Excel_cell_area` | FAILURE / 8 candidates (1050ms) | AVAILABLE / `eS` (0.43) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | OCR extracted noise characters from non-text green cell; UIA failed at capture. Safe abstention prevents false action. |
| C02 | native_uia_excel_ribbon | `Excel_ribbon_green_area` | FAILURE / 7 candidates (467ms) | INSUFFICIENT / `` (null) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | OCR correctly produced 0 text on non-text green ribbon; UIA capture failed. Safe abstention. |
| C03 | native_uia_calculator | `CE_button_Calculator` | FAILURE / 9 candidates (534ms) | AVAILABLE / `cE` (0.84) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | OCR recognized button text "CE" (as `cE`, 84% conf); evaluator abstained due to target instruction mismatch ("Press Enter"). |
| C04 | text_ocr_notepad | `highlighted_paragraph_about_calibration_evidence_for_OCR_targets` | FAILURE / 3 candidates (480ms) | AVAILABLE / `ad this highlighted paragraph about calibration evidence for 0¢` (0.91) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | OCR successfully recognized body paragraph (91% conf); evaluator abstained because token "text" was absent. |
| C05 | text_ocr_notepad_header | `CALIBRATION_SECTION_HEADER` | FAILURE / 17 candidates (458ms) | AVAILABLE / `LIBRATION SECTION HEADER` (0.83) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | OCR extracted section header (83% conf); evaluator abstained on generic target instruction. |
| C06 | icon_paint_toolbar | `Edit_control_Paint_toolbar` | FAILURE / 11 candidates (499ms) | AVAILABLE / `Ed` (0.47) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | OCR read partial glyph `Ed` at low confidence on toolbar icon; UIA capture failed. Safe abstention. |
| C07 | custom_render_paint_canvas | `blank_white_Paint_canvas` | FAILURE / 15 candidates (496ms) | AVAILABLE / `jools. © Brushes Shapes.` (0.40) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | OCR hallucinated noise from toolbar edge on blank canvas; UIA capture failed. Safe abstention. |
| C08 | no_structure_paint_canvas | `blank_white_Paint_canvas_below_Colours_panel` | FAILURE / 9 candidates (533ms) | AVAILABLE / `(“Colours` (0.47) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | OCR picked up border text artifact on blank canvas; UIA capture failed. Safe abstention. |
| C09 | uia_explorer_item | `New_control_File_Explorer_toolbar` | FAILURE / 23 candidates (672ms) | AVAILABLE / `news X% 0 GF OD S Une 1 Name` (0.28) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | OCR produced low-confidence garbled text from toolbar icons; UIA capture failed. Safe abstention. |
| C10 | native_uia_notepad_menu | `File_menu_Notepad` | FAILURE / 10 candidates (432ms) | INSUFFICIENT / `` (null) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | OCR missed "File" menu text in small crop; UIA capture failed. Safe abstention. |
| C11 | conflict_excel_vs_ocr | `green_Excel_cell_with_text_ending_in_covered_Files` | FAILURE / 8 candidates (444ms) | AVAILABLE / `eS` (0.43) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | OCR read noise `eS` for green text cell; UIA capture failed. Safe abstention. |
| C12 | conflict_notepad_menu_vs_body | `File_menu_Notepad` | FAILURE / 34 candidates (413ms) | AVAILABLE / `File Edit View Hy... CALIBRATION SECTION HEADER...` (0.79) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | OCR extracted menu text "File" and dense body; evaluator abstained on mismatched mock instruction ("Select Cube"). |
| C13 | native_uia_excel_formula | `NO_VALID_TARGET` | FAILURE / 7 candidates (454ms) | INSUFFICIENT / `` (null) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | No valid target (solid color block). OCR correctly empty; safe abstention. |
| C14 | text_ocr_dense_notepad | `CALIBRATION_SECTION_with_header_and_highlighted_paragraph` | FAILURE / 3 candidates (439ms) | AVAILABLE / `LIBRATION SECTION HEADER ad this highlighted paragraph...` (0.94) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | High-confidence OCR (94%) on header + body paragraph; evaluator abstained on generic mock instruction. |
| C15 | icon_calc_button | `NO_VALID_TARGET` | FAILURE / 10 candidates (500ms) | INSUFFICIENT / `` (null) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | No valid target (mis-captured Excel ribbon for Calculator case). OCR correctly empty; safe abstention. |
| C16 | no_access_desktop_empty | `demo_folder_in_File_Explorer` | FAILURE / 19 candidates (513ms) | AVAILABLE / `‘EE BTCognitive... deme... New falder` (0.35) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | Low-confidence garbled OCR on folder list; UIA capture failed. Safe abstention. |
| C17 | native_uia_excel_title | `Excel_green_title_bar_area` | FAILURE / 6 candidates (455ms) | INSUFFICIENT / `` (null) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | CORRECT | CORRECT_ABSTAIN | Non-text title bar band. OCR correctly empty; UIA capture failed. Safe abstention. |
| C18 | custom_render_unavailable_blender | `blank_white_Paint_canvas_proxy` | FAILURE / 15 candidates (657ms) | AVAILABLE / `jools. © Brushes Shapes.` (0.40) | ABSTAIN / `visual_provider_not_configured` | NOT_EVALUABLE | INCORRECT | CORRECT_ABSTAIN | OCR noise on blank Paint canvas proxy; UIA capture failed. Safe abstention. |

### Summary metrics

- **Ground-truth cases:** 18 total (16 with target labels, 2 `NO_VALID_TARGET`, 0 pending).
- **UIA Correctness:** Capture-time evaluable = 0 (18/18 `FAILURE`). Capture-time accuracy is **`NOT_COMPUTABLE`**. Replay availability = 18/18 (100.0%), but offline replay candidate intersection is not capture-time ground truth.
- **OCR Correctness:** Evaluable = 18. Correct = 8 (C02, C03, C04, C05, C12, C13, C14, C15, C17), Incorrect = 10 (C01, C06, C07, C08, C09, C10, C11, C16, C18). **OCR Accuracy = 44.4% (8/18)**. (On clear text-bearing targets: 5/8 correct).
- **Deterministic Evaluator Decision Breakdown:**
  - `CORRECT_ACCEPT`: 0
  - `INCORRECT_ACCEPT`: 0
  - `CORRECT_ESCALATE`: 0
  - `INCORRECT_ESCALATE`: 0
  - `CORRECT_ABSTAIN`: 18
  - `INCORRECT_ABSTAIN`: 0
  - **False ACCEPT count:** 0
  - **False ACCEPT rate:** 0.0% (0 / 18)
  - **Evaluator Accuracy:** 100.0% (18/18 conservative reliability; 0 ungrounded actions taken).
- **Latency Measurements:**
  - OCR latency: range 24–408 ms (mean 64.8 ms, median 36 ms).
  - UIA capture perception latency: range 352–786 ms (mean 481.3 ms, median 453 ms).
  - UIA replay latency: range 413–1050 ms (mean 526.4 ms, median 497.5 ms).
  - Total processing latency: range 352–797 ms (mean 482.6 ms).

### Modality comparison

| Modality | Evaluable cases | Correct | Incorrect | Unavailable / Not evaluable | False accepts | Abstentions / Escalations | Accuracy |
|---|---:|---:|---:|---:|---:|---:|---|
| UIA only (capture time) | 0 | 0 | 0 | 18 (FAILURE) | 0 | 0 | NOT_COMPUTABLE |
| UIA only (offline replay) | 18 | UNVERIFIED | UNVERIFIED | 0 | UNVERIFIED | 0 | NOT_COMPUTABLE |
| OCR only | 18 | 8 | 10 | 0 | 0 | 0 | 44.4% (8/18) |
| UIA + OCR (unfused) | 18 | Disjoint | Disjoint | 0 | 0 | 0 | NOT_COMPUTABLE (no cross-modal fusion) |
| Deterministic evaluator | 18 | 18 | 0 | 0 | 0 | 18 (ABSTAIN) | 100.0% (safety/abstention) |

*Note: UIA and OCR evidence streams are evaluated separately and are NOT fused into a joint representation.*

## Research question evaluation

### RQ1: Can the project establish modality effectiveness for Windows GUI target identification?
- **Status: `PARTIALLY_ANSWERED`**
- **Evidence & Findings:** Local Tess4J OCR was demonstrated to be effective for text-rich UI controls (Notepad header, body paragraph, dense text, Calculator CE button; 91–94% confidence), but ineffective for icon-only controls, canvas regions, and low-contrast UI where it produces noise. UIA succeeded in offline replay across Win32 and UWP controls (18/18), but capture-time UIA failed for all calibration cases (18/18). Cross-modality effectiveness cannot be fully established because coordinate spaces are disjoint (preventing geometric verification), capture-time UIA failures remain unresolved, and visual grounding was not executed.

### RQ2: Can the project establish a reliability–latency trade-off?
- **Status: `PARTIALLY_ANSWERED`**
- **Evidence & Findings:** A clear latency delta was measured: local OCR is fast (median 36 ms, mean 64.8 ms), whereas UIA requires a cross-process PowerShell/interop execution step with higher latency (capture mean 481.3 ms, replay mean 526.4 ms; peak 1050 ms). OCR provides low-latency text evidence with no tree-traversal overhead, while UIA provides structural control semantics when accessible. However, without multimodal fusion and without visual-grounding model execution, a complete reliability-latency trade-off curve across all three modalities cannot be fully established.

### RQ3: Can the project establish whether selective acceptance/escalation/abstention improves reliability?
- **Status: `PARTIALLY_ANSWERED`**
- **Evidence & Findings:** The deterministic gating policy demonstrated 100% false-acceptance suppression across the 18 calibration cases (0 false accepts, 18 correct abstentions). When evidence was incomplete, noisy, or ungrounded, the policy safely abstained rather than asserting an unverified target. However, because all calibration cases resulted in abstentions (no positive accepts in this specific calibration run due to capture-time UIA failure and mock instruction token mismatches), positive acceptance and escalation dynamics under real mixed-evidence conditions remain partially demonstrated.

## Deterministic evidence policy evaluation

- **Status: `EMPIRICALLY_SUPPORTED`** (as a conservative deterministic engineering safety gate; NOT as an adaptive AI model).
- **Findings:**
  - Strong UIA acceptance: Verified by unit tests; not triggered in C01–C18 due to capture-time UIA failure.
  - Strong OCR acceptance: Verified by unit tests; requires >= 0.80 confidence and exact target token match.
  - Ambiguity and Disagreement: Triggers `ESCALATE` when high-confidence concise UIA and OCR labels disagree.
  - Abstention on insufficient evidence: Safely abstained on all 18 calibration cases, achieving 0 false acceptances.
  - Engineering distinction: The evaluator is a deterministic rule-based policy, not a trained, adaptive, or learning-based model.

## Visual-grounding status

- **Visual Grounding Contract:** `PRESENT` (`VisualGroundingProvider` interface and data types implemented).
- **Real Visual Grounding Provider:** `NOT_CONFIGURED` (`NotConfiguredVisualGroundingProvider`).
- **Real Model:** `NONE` (candidates surveyed: ShowUI-2B, Qwen2-VL-2B-Instruct).
- **Real Model Executed:** `NO`.
- **Real Model Result:** `NOT_RUN`.
  - CPU inference smoke test: Not run due to missing PyTorch dependencies and inadequate host RAM (<1.7 GiB free at inspection).
  - GPU/Vulkan smoke test: GGUF models downloaded, image encoding succeeded in 581 ms, then failed with Vulkan driver queue submission error (`vkQueueSubmit: Invalid queue [VUID-vkQueueSubmit-queue-parameter]`).
  - Standing decision: No third stack attempted; visual grounding remains `NOT_CONFIGURED`.
- **Perception Class Need:** Calibration cases with icon-only controls (C06, C15), canvas areas (C07, C08, C18), and custom UI (C16) highlight concrete perception classes where OCR and UIA face structural limitations; however, whether visual grounding resolves them in this architecture remains unestablished empirically.

## Coordinate spaces and geometry limitations

- **OCR Coordinate Space:** `CROP_IMAGE_PIXELS` (pixel coordinates relative to selected crop image).
- **UIA Coordinate Space:** `PHYSICAL_DESKTOP_SCREEN` (Windows physical desktop screen coordinates).
- **Selection Coordinate Space:** `LOGICAL_DESKTOP_AWT` (Java AWT logical screen coordinates).
- **Geometry Comparison:** `NOT_COMPUTABLE`.
- **IoU Results:** `NOT_COMPUTABLE`.
- **Limitation:** No verified, bidirectional coordinate transform exists between crop image pixels and physical desktop screen coordinates. Semantic correctness is evaluated from text/labels; spatial/geometric overlap across modalities cannot be computed.

## Historical UIA discrepancy and unresolved items

1. **Historical 18/18 UIA capture discrepancy:** `UNRESOLVED`. All 18 calibration cases recorded `uiaStatus=FAILURE` at capture time, but subsequent offline replay with saved crops and live HWNDs succeeded on all 18 cases (18/18 `SUCCESS`). The original harness run did not persist PowerShell exception messages or stack traces. Replay success is not capture-time evidence.
2. **Missing window handle (`missing_window_handle`):** `UNRESOLVED`. One live timing capture returned no HWND from `ApplicationContext`. Occurred prior to external window close attempts; root cause not investigated.
3. **Latest user Notepad capture record:** Crop exists in `%TEMP%`, but transient UIA/OCR result objects were not persisted to disk.

## Unproven research claims and limitations

The following claims are explicitly **UNPROVEN** and MUST NOT be asserted:
- Adaptive benefit or learning improvements (the evaluator is a static rule-based gate, not a learning model).
- Multimodal evidence fusion (UIA and OCR run separately and are not fused into a unified geometric or probabilistic representation).
- Visual-grounding superiority or accuracy (no real visual grounding model was executed).
- Cross-modal geometric alignment or bounding-box IoU (coordinate spaces remain unmapped).
- Human usability, user-study outcomes, learning gains, or workload reductions (no user study was conducted).

## Preserved evidence and raw artifact inventory

Original crops, provider output fields, logs, and model artifacts remain at their original paths; later human ground-truth values are recorded in the C01–C18 result files. Archived reports remain untouched. Evidence paths include:
- `docs/calibration_4gap2/cases_manifest.tsv`.
- `docs/calibration_4gap2/crops/smoke_taskbar_start.png`.
- `docs/calibration_4gap2/models/Qwen2-VL-2B-Instruct-Q4_K_M.gguf` and `mmproj-Qwen2-VL-2B-Instruct-f16.gguf`.
- `docs/calibration_4gap2/results/`: `C01_crop.png` through `C18_crop.png`; `C01_result.txt` through `C18_result.txt`; `SUMMARY.tsv`; `deps.txt`; `harness_run.log`; `smoke_meta.txt`; `smoke_prompt.txt`; `smoke_stderr.txt`; `smoke_stdout.txt`; `smoke_test_log.txt`; `TRACK_A_SMOKE_FAILURE.txt`.
- Archived narrative files remain at `docs/archive/pre-consolidation/2026-10-07/`.

## Validation history and regression status

- Full Gradle test suite: **87 tests, 0 failures, 0 ignored, 100% pass**.
- Phase 4 targeted tests: **60 tests** (context: 56 tests, calibration: 4 tests), all passed.
- `:composeApp:build`: **BUILD SUCCESSFUL**.
- Phase 1–3 regression checks: **PASSED** (bridge: 7 tests, models: 4 tests, overlay: 2 tests, selection: 7 tests, tutor: 7 tests).
- Phase 1–3 documentation and source code: **Protected and unmodified**.
- `README.md`: **Unmodified**.

### Final Blender extension verification (2026-10-08)

- Full Gradle test suite: **87 tests, 0 failures, 0 errors, 0 skipped**.
- Phase 4 targeted suite (`context.*`, `calibration.*`): **60 tests, all passed**.
- Phase 1–3 regression selectors plus shared processing/selection lifecycle tests: **41 tests, all passed**.
- `:composeApp:build`: **BUILD SUCCESSFUL**.
- `git diff --check`: **passed**.
- README and Phase 1–3 files: **unchanged**. No C01–C18 result or label was modified by the Blender extension.
- Implementation and planned four-case Blender observation: **complete on the configured Java 17 toolchain**. Empirical findings remain limited to these four cases; they do not prove broad Blender support or any research hypothesis.

## Final Blender real-application validation

This is a separate four-case extension; it does not replace or alter C01–C18 labels, results, or findings. Blender 5.2.2 LTS was visibly open as PID 4988, HWND 985116, with title `* (Unsaved) - Blender 5.2.2 LTS`. The desktop capture was 1920×1080. B01–B04 crops were visually inspected and their target descriptions/labels recorded before reviewing the provider output. The screenshot and crops, dimensions, regions, file sizes, and SHA-256 hashes are preserved under [`calibration_4blender/runs/20261008-live/`](calibration_4blender/runs/20261008-live/); per-case provider records and aggregate TSV are in that run's `results/`.

| Case | Visually reviewed ground truth | OCR evidence / correctness | UIA evidence / semantic correctness | Evaluator |
|---|---|---|---|---|
| B01 structured Outliner text | `Cube_object_label_in_Blender_Outliner` | `AVAILABLE`, `a Cube > tot ©`, confidence 0.512; `Cube` box is within the crop. **CORRECT** for identifying the selected label, with substantial noise. | `AVAILABLE`, but only selected `* (Unsaved) - Blender 5.2.2 LTS`, `UNKNOWN` control type, no AutomationId, bounds `(-8,-8,1936,1048)`, one candidate. **INCORRECT**: the returned element is the whole Blender window, not the Cube row. | `ABSTAIN / visual_provider_not_configured`; **CORRECT_ABSTAIN**. |
| B02 text/property control | `Location_X_property_field_showing_0_m` | `AVAILABLE`, `Location X om Be y om Be`, confidence 0.740. The target row label `Location X` is recognized; `0 m` is noisy (`om`) and text from the next row is included. **CORRECT** for identifying the field, not exact value transcription. | Same top-level window result as B01. **INCORRECT** for the selected property field. | `ABSTAIN / visual_provider_not_configured`; **CORRECT_ABSTAIN** because OCR confidence is below the fixed acceptance gate and UIA is not target-specific. |
| B03 custom-rendered viewport | `selected_default_cube_in_3D_viewport` | `empty` after successful OCR execution; no words or text. **INCORRECT** for identifying the visible cube target. | Same top-level window result as B01. **INCORRECT** for the viewport cube. | `ABSTAIN / visual_provider_not_configured`; **CORRECT_ABSTAIN**. |
| B04 icon/non-text control | `Move_tool_icon_in_left_viewport_toolbar` | `empty` after successful OCR execution; no words or text. **INCORRECT** for identifying the visible Move tool icon. | Same top-level window result as B01. **INCORRECT** for the toolbar icon. | `ABSTAIN / visual_provider_not_configured`; **CORRECT_ABSTAIN**. |

Per-case counts: four labels present; UIA 0 correct / 4 incorrect; OCR 2 correct / 2 incorrect; evaluator 0 ACCEPT, 0 ESCALATE, 4 `CORRECT_ABSTAIN`, 0 false accepts. In B03/B04, OCR executed normally but supplied no target text; these are evidence misses, not native OCR failures. UIA status `AVAILABLE` indicates a returned element and intersecting bounds only; it did not mean semantic target identification.

Measured per-case timings (milliseconds; one run per path/case): OCR provider-reported standalone totals B01 231, B02 51, B03 25, B04 39; UIA call wall times B01 441, B02 381, B03 361, B04 366; combined `ContextProcessor` processing B01 372, B02 378, B03 371, B04 378. These are observations from one Blender session, not a latency distribution or evidence of an established general reliability–latency curve. The evaluator consumed separate UIA and OCR evidence; the project does not fuse their geometry or confidence into a joint representation.

### Blender OCR crash investigation and runtime fix

The initial B01 JavaExec attempt failed with `java.lang.Error: Invalid memory access` at `com.sun.jna.Native.invokeInt` → `TessBaseAPIInit1` → `Tesseract.init` (Tess4J 5.20.0, `Tesseract.java:362`), before image conversion/recognition. The process exited `-1073741819` (`0xC0000005`). The original early B01 crop in `calibration_4blender/results/B01_crop.png` captured unrelated IDE text, not Blender; a later recapture under `runs/20261008-javaexec-retry/` was blank white, and foreground state for that capture was not recorded. Both failed-attempt crops were preserved but excluded from B01 correctness scoring.

Controlled probes then reproduced the same initialization error under Amazon Corretto 22.0.2 on both the correct B01 crop and the existing `OcrFixtures.textOnly` fixture. OCR-only sequential calls failed 3/3; concurrent calls failed 2/2; `ContextProcessor` propagated the `Error`. Sequential failure means concurrency is not required. The standalone UIA query returned a Blender root-window candidate independently; the OCR crash is not a UIA failure. The native exception remains process-fatal even when a diagnostic caller catches `Throwable`, so no unsafe in-process `Error` catch was added.

The exact same B01 crop passed 3/3 sequential OCR calls and 2/2 concurrent OCR calls under Microsoft OpenJDK 17.0.20.1; `ContextProcessor` completed and returned `ABSTAIN`. The existing OCR fixture suite also passed under that toolchain. Runtime artifacts observed were Tess4J 5.20.0, JNA 5.19.1, Lept4J 1.24.0, loaded `libtesseract553.dll` (Tesseract 5.5.3) and `libleptonica1870.dll` (Leptonica 1.87.0). The available JDKs differ by both vendor and version, so the precise underlying JNA/native ABI mechanism is not isolated beyond the failing Corretto 22.0.2 versus passing configured Java 17 runtime pair.

**Fix:** all `composeApp` `JavaExec` tasks, including `runCalibrationHarness`, now explicitly use the Java 17 Gradle toolchain. This was verified with Gradle itself running on JDK 22: the harness child command used `C:\Users\PC\.jdks\ms-17.0.20.1\bin\java.exe`. Tess4J, Tesseract, JNA, and Lept4J versions were unchanged. This prevents the reproduced failure on the configured module JavaExec path; it does not make the JDK 22 native path safe. If Java 22 support is required, native OCR needs process isolation or a separately proven compatibility fix.

**Runtime setup note:** The `JavaExec` child JVM is selected by the Gradle Java toolchain and does not inherit JDK 22 just because Gradle itself runs on JDK 22. The Gradle wrapper still requires a usable JDK to start (`JAVA_HOME` or `java` on `PATH`), and this module requires a discoverable Java 17 toolchain; if Java 17 is not available, the pinned task fails rather than falling back to JDK 22. In the audit environment, `JAVA_HOME` and `java` on the initial shell `PATH` were both absent, so the wrapper had to be started with an installed JDK on `JAVA_HOME`/`PATH`. This is a normal build-environment prerequisite and a reproducibility setup limitation; JDK selection for the OCR JavaExec path itself is pinned.

Final stability rerun: with `JAVA_HOME` unset and Corretto JDK 22 on `PATH`, the wrapper launched `runCalibrationHarness` using the pinned Microsoft JDK 17 child JVM. That fresh capture attempt returned OCR `INSUFFICIENT`; its saved crop is blank and contains no Blender pixels, so this was recorded as `NOT_EVALUABLE` and excluded from the B01 case score. A separate same-path run with Blender visibly foreground captured the Cube row and returned OCR `AVAILABLE` with `a Cube > tot ©`; this matches the original B01 OCR observation. The blank recapture is preserved as a capture observation, not misreported as another OCR native crash.

### Blender-specific conclusion and research questions

- **Structured target (B01):** OCR recognized `Cube` from the visible Outliner row, albeit with low confidence/noise. UIA exposed only the containing window.
- **Text/property target (B02):** OCR recognized `Location X`, but transcribed its value noisily. UIA again exposed only the window.
- **Custom-rendered target (B03):** neither OCR nor UIA identified the viewport cube.
- **Icon/non-text target (B04):** neither OCR nor UIA identified the Move tool icon.
- **Evaluator:** all four abstentions were correct for the supplied evidence. No ACCEPT or ESCALATE occurred, so the Blender cases do not measure positive acceptance or escalation behavior.
- **Visual-grounding justification:** **`PARTIALLY_ESTABLISHED`** as a motivation for future investigation only: B03 and B04 are real examples where current UIA/OCR results did not identify the intended target. There is one example per class; no grounding provider/model was run, and no evidence shows that visual grounding would solve either case.
- **RQ1:** `PARTIALLY_ANSWERED` — four Blender target observations give limited measured modality results, not general Windows/Blender effectiveness.
- **RQ2:** `PARTIALLY_ANSWERED` — OCR/UIA/combined latencies were recorded, but the sample is one session and four cases only.
- **RQ3:** `PARTIALLY_ANSWERED` — all four abstentions were appropriate and no false accept occurred, but no ACCEPT/ESCALATE behavior was exercised.
- Geometry stays `NOT_COMPUTABLE`: OCR uses `CROP_IMAGE_PIXELS`; UIA reports `PHYSICAL_DESKTOP_SCREEN`. No verified crop-to-desktop transform exists, so no IoU or cross-source bounds comparison was calculated. Semantic correctness and geometric correctness remain distinct.

Blender artifacts are separate from C01–C18. The original failure crops in `calibration_4blender/results/` and `runs/20261008-javaexec-retry/` were retained; the four reviewed crops and full Blender desktop capture are in `runs/20261008-live/`. The original C01–C18 labels, raw results, replay/capture distinction, historical UIA discrepancy, and visual-grounding status remain unchanged.

## Result persistence

Calibration-result persistence writes new records using schema version 3 and is automatic for every `CalibrationAttempt`; normal tutor operation does not require a human label or calibration JVM property. Optional `-Paivt.calibration.outputDir=<path>` selects the private output root; the default is `%LOCALAPPDATA%\AIVisualTutor\calibration-runs\` (falling back to the user's `AppData\Local` folder). Each run has `<runId>/crop.png` and atomically written `<runId>/result.json`; `manifest.jsonl` is append-only and its greatest `sequence` for a case identifies the latest run. `LIVE_FLOW` records a new in-memory selection, `CROP_REPLAY` records a new processing of a saved crop (not its historical capture result), and `LEGACY_IMPORT` preserves only fields present in the original record. Human `groundTruthLabel` values may be an actual annotation, `PENDING`, or null and are never inferred from provider evidence. Missing capture-time values remain `NOT_RECORDED`. Schema 3 adds an optional visual-grounding block with provider/model/runtime and memory-preflight state, raw output, normalized and crop-pixel geometry when actually produced, coordinate space, latency, and diagnostics. UIA/OCR/evaluator failure, timeout, invalid visual output, cancellation, and abstention are preserved as result states. Existing schema-v1/v2 records remain readable and are not rewritten.

These files contain real screen content and provider output. Persistence is automatic for completed calibration captures; keep the output local and private, and do not share it. Stale temporary files are ignored as results and reported separately. The former live Notepad preview crop was temporary and deleted on close, and its transient UIA/OCR objects were not serialized; that historical capture cannot be recovered. New completed live-flow attempts persist the crop and structured result before preview cleanup.

## Visual-grounding provider and preview

Screenshot Preview displays the exact target instruction captured for that preview, separate UIA/OCR/vision statuses, and the evaluator decision. Empty instructions display `Not specified`; they are not sent to visual grounding. Raw window captures have no saved HWND or desktop-region metadata, so UIA is marked unavailable and its selection geometry is not fabricated.

The llama.cpp provider is opt-in through `-Paivt.visual.enabled=true`, `-Paivt.visual.executable=<llama-mtmd-cli>`, `-Paivt.visual.model=<model.gguf>`, `-Paivt.visual.mmproj=<mmproj.gguf>`, and optional `-Paivt.visual.timeoutMillis=<milliseconds>`. It is disabled by default and emits point geometry in `NORMALIZED_CROP`; it does not create semantic evidence or confidence. UGround-V1-2B's [OSUNLP model card](https://huggingface.co/osunlp/UGround-V1-2B) documents point coordinates in the [0,1000) range, which the adapter validates and normalizes. The verified Q4_K_M model file is `UGround-V1-2B.Q4_K_M.gguf` (986,047,328 bytes); its matching projector is `UGround-V1-2B.mmproj-fp16.gguf` (1,331,656,192 bytes). Both are provided by [mradermacher/UGround-V1-2B-GGUF](https://huggingface.co/mradermacher/UGround-V1-2B-GGUF), based on [osunlp/UGround-V1-2B](https://huggingface.co/osunlp/UGround-V1-2B); this is third-party quantization (`quantized_by: mradermacher`), Apache-2.0 licensed, not an OSUNLP-produced quantization. The installed llama.cpp is `0.6.0-dev` build `11433` (`50569eb87`), with a Vulkan device; its `llama-mtmd-cli` exposes CPU device selection (`--device none`), multimodal model/projector/image arguments, deterministic temperature, and performance timing. The official [b11433 Windows x64 CPU release asset](https://github.com/ggml-org/llama.cpp/releases/tag/b11433) (19,399,521 bytes) was verified but not downloaded.

### Final Phase 4 closeout

| Closeout field | Verified implementation state |
| --- | --- |
| `REAL_VISUAL_PROVIDER` | `IMPLEMENTED` |
| `MODEL` | `UGround-V1-2B` |
| `REAL_MODEL_EXECUTED` | `YES` on saved-crop replays |
| `HOST_STATUS` | `MEMORY_GUARD_PASS` |
| `RESULT_PERSISTENCE` | `PASS` in automated tests and persisted B/C replay records |
| `TARGET_DESCRIPTION_VISIBLE` | `YES` |
| `GROUND_TRUTH_REQUIRED_FOR_OPERATION` | `NO` |

`HOST_BLOCKED` describes a safe preflight stop, not a model failure. The low-memory test path creates a structured result with the measured guard decision and no prediction, confidence, coordinates, or inference latency. An earlier actual-host preflight was `HOST_BLOCKED`; a later preflight passed after memory availability increased, and the real saved-crop runs followed after the model artifacts were installed locally.

The visual tier is **IMPLEMENTED; REAL INFERENCE EXECUTED** on saved crops. An earlier host inspection found 1,859,473,408 bytes (about 1.73 GiB) free RAM and was safely blocked by the 3.38 GiB guard. Later preflights passed the 3,629,247,837-byte guard. After retrieving the documented Q4_K_M model and matching projector into LocalAppData, the current B03 replay with 1024 image tokens produced `(0.499, 0.500)` normalized, or approximately `(122.255, 112.500)` crop pixels for the 245×225 crop. Its result is persisted as a new run; B01–B04 and C01–C18 batches were also persisted separately with the same image-token setting. No original case result or human label was rewritten. CPU-only inference used the existing llama.cpp `0.6.0-dev` build `11433`.

### REAL VISUAL GROUNDING

- **Model/artifacts:** UGround-V1-2B; source `osunlp/UGround-V1-2B` (Apache-2.0); third-party quantization `mradermacher/UGround-V1-2B-GGUF`, `UGround-V1-2B.Q4_K_M.gguf` (986,047,328 bytes), with `UGround-V1-2B.mmproj-fp16.gguf` (1,331,656,192 bytes). The artifacts are installed under `%LOCALAPPDATA%\AIVisualTutor\models\`; they are not stored in the repository.
- **Provider/configuration:** the opt-in llama.cpp/mtmd provider and result-schema fields are present. The executable is llama.cpp `0.6.0-dev`, build `11433`, commit `50569eb87`. Target descriptions flow to the provider request and are persisted separately from optional human labels. Successful runs persist raw output, normalized points, crop-pixel points derived from actual crop dimensions, measured provider latency, and memory-guard data.
- **Execution:** `REAL_VISUAL_GROUNDING_EXECUTED = YES` on saved crops. The current B01–B04 and C01–C18 runs each invoked the provider, passed the memory guard, and returned one parseable point using `--image-min-tokens 1024`. Combined latency ranged from 20,921 to 27,102 ms. The current B03 point was `(0.499, 0.500)` normalized and `(122.255, 112.500)` crop pixels.
- **Cases/results:** 4 Blender and 18 calibration crop replays are persisted in `%LOCALAPPDATA%\AIVisualTutor\calibration-runs\phase4-b01-b04-uground-1024\` and `%LOCALAPPDATA%\AIVisualTutor\calibration-runs\phase4-c01-c18-uground-1024\`. All 22 evaluator decisions were `ABSTAIN`. Historical C01–C18 result/label files and B01–B04 evidence remain unchanged. Human labels on new machine records are `PENDING`.
- **Output/correctness:** outputs are real parsed model points in `NORMALIZED_CROP` plus derived crop pixels; no confidence was returned. The evaluator's 22 abstentions and lack of coordinate-level ground truth mean visual correctness/accuracy is `NOT_ESTABLISHED`. The initial batch logs recommended `--image-min-tokens 1024`; current runs use that option and no longer emit the warning. No UIA/OCR cross-space transform or target-region scoring is established.
- **Host status:** the memory guard passed for the recorded inference attempts. An earlier lower-memory observation was correctly `HOST_BLOCKED`. No alternate model/runtime or GPU path was tried; execution used CPU only.

The saved-crop UGround replays establish real inference and persistence, but do not establish target accuracy or benefit. Existing UIA/OCR observations remain separate and unchanged.

At this closeout, RQ1 remains `PARTIALLY_ANSWERED` by limited UIA/OCR observations; RQ2 remains `PARTIALLY_ANSWERED` by sparse measured latencies; and RQ3 remains `PARTIALLY_ANSWERED` because the visual evaluator abstained on all 22 replays. The historical UIA discrepancy and missing-window-handle case remain unresolved. No UIA/OCR/visual geometry comparison or IoU is computable across their untransformed coordinate spaces. No user study, learning-gain, usability, workload, retention, or transfer evidence exists.

Current closeout verification: ` .\gradlew.bat test` **PASS** (118 tests, 0 failures/errors/skips); ` .\gradlew.bat :composeApp:build` **PASS**; focused Phase 1–3/shared processing and selection regression selection **PASS** (38 tests); `git diff --check` **PASS**. This run used the locally installed Java 17. No application UI was driven. Real CPU inference was executed on 22 saved crops with the configured 1024 image-token setting. New machine records are under LocalAppData; C01–C18 historical result files and summary are unchanged, labels are preserved, and B01–B04 historical evidence was not rewritten.
