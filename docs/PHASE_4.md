# Phase 4 — Evidence-Oriented Perception

## Current status

**PHASE_4_STATUS: `PARTIAL_SIGNAL_UIA_OCR_PENDING_GROUND_TRUTH`**

UIA and local OCR are implemented and have been exercised on saved/live captures, but the historical capture-time UIA failures remain unexplained, some capture-specific UIA/OCR evidence was not persisted, UIA element intersection is not semantic correctness, and no human-confirmed ground-truth labels exist yet. Visual grounding remains `NOT_CONFIGURED`. **Phase 4 is not complete, validated, or correctness-rated.**

- Ground truth: **0/18 calibration cases human-confirmed; 18/18 remain pending.** Labeling is a human task being handled separately.
- Correctness: **not reportable** until human-confirmed ground truth is available. OCR character counts, UIA candidate intersection, and provider availability are not correctness measurements.
- Latest user Notepad capture: the crop exists at `%TEMP%\AIVT_9d243cd466434b55aede473304dc1834_15703030827535814018.png` (697 × 149); runtime output reported 136 OCR characters, but that capture's UIA status/object and OCR availability status were not recorded in a recoverable result file.
- Known open items: unexplained historical 18/18 UIA capture-time failures versus later 18/18 replay successes; one independent `missing_window_handle` observation; no human correctness results.

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

The saved harness contains 18 completed image-processing calibration cases C01–C18, with all 18 capture-time UIA statuses `FAILURE`; OCR was `AVAILABLE` on 13 and `INSUFFICIENT` on 5. Every case has `PENDING_LABEL`. The raw per-case result files did not preserve UIA exception type/message/stack sufficient to explain those failures. The full original records remain under [`calibration_4gap2/results/`](calibration_4gap2/results/), with manifest [`cases_manifest.tsv`](calibration_4gap2/cases_manifest.tsv), original crop inputs under [`crops/`](calibration_4gap2/crops/), and model artifacts under [`models/`](calibration_4gap2/models/).

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

### Calibration-case review and ground truth

This table is the single human-labeling section. Each image link and result link resolves from `docs/PHASE_4.md`; raw crops and result files remain under `docs/calibration_4gap2/results/`. The original full desktop screenshots were not retained. Selected coordinates are desktop `x,y,width,height`; target text is copied from the recorded case data. Update **Current label** directly in this file only after human confirmation. All currently remain `PENDING`.

Target-description audit (2026-10-07): C02, C03, C04, C06-C12, and C15-C18 were corrected because their recorded instructions referred to another application/target or did not describe the visible crop. The visible target is described conservatively from each saved crop; categories, coordinates, statuses, and pending labels are unchanged. C09 and C16 still have category metadata that conflicts with the visible File Explorer crop (no list item is visible in C09; C16 visibly contains folders despite its `no_access_desktop_empty` category); correcting those categories is outside this description-only update.

| Case | Category | Target description | Selected region (desktop x,y,w,h) | Capture UIA / OCR status | Replay candidate count / latency ms | Crop | Raw result | Current label |
|---|---|---|---|---|---|---|---|---|
| C01 | native_uia_excel_cell | Select cell B2. | `860,250,90,28` | FAILURE / AVAILABLE | 8 / 1050 | [C01 crop](calibration_4gap2/results/C01_crop.png) | [C01 result](calibration_4gap2/results/C01_result.txt) | **PENDING** |
| C02 | native_uia_excel_ribbon | Identify the visible green Excel ribbon area. | `820,120,100,30` | FAILURE / INSUFFICIENT | 7 / 467 | [C02 crop](calibration_4gap2/results/C02_crop.png) | [C02 result](calibration_4gap2/results/C02_result.txt) | **PENDING** |
| C03 | native_uia_calculator | Select the visible CE button in Calculator. | `1100,780,80,50` | FAILURE / AVAILABLE | 9 / 534 | [C03 crop](calibration_4gap2/results/C03_crop.png) | [C03 result](calibration_4gap2/results/C03_result.txt) | **PENDING** |
| C04 | text_ocr_notepad | Read the visible paragraph about calibration evidence for OCR targets. | `60,140,500,120` | FAILURE / AVAILABLE | 3 / 480 | [C04 crop](calibration_4gap2/results/C04_crop.png) | [C04 result](calibration_4gap2/results/C04_result.txt) | **PENDING** |
| C05 | text_ocr_notepad_header | Locate the target section. | `60,100,500,50` | FAILURE / AVAILABLE | 17 / 458 | [C05 crop](calibration_4gap2/results/C05_crop.png) | [C05 result](calibration_4gap2/results/C05_result.txt) | **PENDING** |
| C06 | icon_paint_toolbar | Select the visible Edit control in Paint's toolbar. | `80,600,48,48` | FAILURE / AVAILABLE | 11 / 499 | [C06 crop](calibration_4gap2/results/C06_crop.png) | [C06 result](calibration_4gap2/results/C06_result.txt) | **PENDING** |
| C07 | custom_render_paint_canvas | Select the blank white Paint canvas area below the toolbar. | `200,700,300,200` | FAILURE / AVAILABLE | 15 / 496 | [C07 crop](calibration_4gap2/results/C07_crop.png) | [C07 result](calibration_4gap2/results/C07_result.txt) | **PENDING** |
| C08 | no_structure_paint_canvas | Select the blank white Paint canvas area below the Colours panel. | `520,720,200,150` | FAILURE / AVAILABLE | 9 / 533 | [C08 crop](calibration_4gap2/results/C08_crop.png) | [C08 result](calibration_4gap2/results/C08_result.txt) | **PENDING** |
| C09 | uia_explorer_item | Select the visible New control in the File Explorer toolbar. | `1450,280,300,80` | FAILURE / AVAILABLE | 23 / 672 | [C09 crop](calibration_4gap2/results/C09_crop.png) | [C09 result](calibration_4gap2/results/C09_result.txt) | **PENDING** |
| C10 | native_uia_notepad_menu | Select the File menu in Notepad. | `40,70,50,28` | FAILURE / INSUFFICIENT | 10 / 432 | [C10 crop](calibration_4gap2/results/C10_crop.png) | [C10 result](calibration_4gap2/results/C10_result.txt) | **PENDING** |
| C11 | conflict_excel_vs_ocr | Select the green Excel cell showing the visible text ending in "covered Files". | `860,250,120,40` | FAILURE / AVAILABLE | 8 / 444 | [C11 crop](calibration_4gap2/results/C11_crop.png) | [C11 result](calibration_4gap2/results/C11_result.txt) | **PENDING** |
| C12 | conflict_notepad_menu_vs_body | Select the File menu in Notepad. | `40,70,600,200` | FAILURE / AVAILABLE | 34 / 413 | [C12 crop](calibration_4gap2/results/C12_crop.png) | [C12 result](calibration_4gap2/results/C12_result.txt) | **PENDING** |
| C13 | native_uia_excel_formula | Enter a value. | `900,160,200,28` | FAILURE / INSUFFICIENT | 7 / 454 | [C13 crop](calibration_4gap2/results/C13_crop.png) | [C13 result](calibration_4gap2/results/C13_result.txt) | **PENDING** |
| C14 | text_ocr_dense_notepad | Locate the target section. | `60,120,550,200` | FAILURE / AVAILABLE | 3 / 439 | [C14 crop](calibration_4gap2/results/C14_crop.png) | [C14 result](calibration_4gap2/results/C14_result.txt) | **PENDING** |
| C15 | icon_calc_button | Select the visible MR button in Calculator. | `1050,700,70,50` | FAILURE / INSUFFICIENT | 10 / 500 | [C15 crop](calibration_4gap2/results/C15_crop.png) | [C15 result](calibration_4gap2/results/C15_result.txt) | **PENDING** |
| C16 | no_access_desktop_empty | Select the visible "demo" folder in File Explorer. | `1500,400,200,150` | FAILURE / AVAILABLE | 19 / 513 | [C16 crop](calibration_4gap2/results/C16_crop.png) | [C16 result](calibration_4gap2/results/C16_result.txt) | **PENDING** |
| C17 | native_uia_excel_title | Identify the green Excel title-bar area shown in the crop. | `780,45,200,28` | FAILURE / INSUFFICIENT | 6 / 455 | [C17 crop](calibration_4gap2/results/C17_crop.png) | [C17 result](calibration_4gap2/results/C17_result.txt) | **PENDING** |
| C18 | custom_render_unavailable_blender | Select the blank white Paint canvas in the proxy capture; Blender is unavailable in this case. | `200,700,300,200` | FAILURE / AVAILABLE | 15 / 657 | [C18 crop](calibration_4gap2/results/C18_crop.png) | [C18 result](calibration_4gap2/results/C18_result.txt) | **PENDING** |

## Preserved evidence and raw artifact inventory

No raw/non-markdown artifact was moved, deleted, regenerated, or edited during documentation consolidation. The following are still at their original paths:

- `docs/calibration_4gap2/cases_manifest.tsv`.
- `docs/calibration_4gap2/crops/smoke_taskbar_start.png`.
- `docs/calibration_4gap2/models/Qwen2-VL-2B-Instruct-Q4_K_M.gguf` and `mmproj-Qwen2-VL-2B-Instruct-f16.gguf`, plus the Hugging Face `.gitignore`, `CACHEDIR.TAG`, and two `.metadata` records under `models/.cache/huggingface/...`.
- `docs/calibration_4gap2/results/`: `C01_crop.png` through `C18_crop.png`; `C01_result.txt` through `C18_result.txt`; `SUMMARY.tsv`; `deps.txt`; `harness_run.log`; `smoke_meta.txt`; `smoke_prompt.txt`; `smoke_stderr.txt`; `smoke_stdout.txt`; `smoke_test_log.txt`; `TRACK_A_SMOKE_FAILURE.txt`.
- `docs/calibration_4gap3/`, `docs/calibration_4gap4/`, and `docs/calibration_4gap5/` contain only the original Markdown narratives/review files, which are archived below; no case crops or raw result records are moved from these folders because none are stored there.
- The latest user Notepad crop is outside the repository in `%TEMP%` at the path documented above; no additional structured result exists.

## Open items

1. **Historical UIA discrepancy:** explain only with new reproducible evidence why all 18 calibration capture-time records said UIA FAILURE while the later production-path replay returned SUCCESS on all 18 saved crops. The old records lack sufficient diagnostics; do not infer a cause from the replay.
2. **`missing_window_handle`:** one live selector capture was unavailable because application context had no HWND. It was temporally before the unsafe Notepad close incident and is independent; its cause was not investigated further.
3. **Human labels and correctness:** all 18 review cases remain PENDING. Once the human reviewer confirms labels, evaluate the labeled subset and report actual correctness/per-category evidence. Until then, correctness is not reportable.
4. **Latest Notepad capture record:** its crop and OCR character count survive, but its UIA status/object and OCR status were not serialized. It cannot be reconstructed as the actual capture-time result from the PNG alone.
5. **Geometry:** a tested crop-to-desktop transform is required before comparing UIA and OCR geometry or reporting IoU/point-in-target; mixed-DPI behavior remains unverified.
6. **Provider research:** visual grounding remains `NOT_CONFIGURED`; no third stack is to be attempted under the standing hardware/runtime decision.

## Consolidation, reconciliation, and archive

This is the current single source of truth for Phase 4. Earlier Phase 4 implementation, 4E/4G/4H studies, and 4-GAP narratives are archived at [`archive/pre-consolidation/2026-10-07/`](archive/pre-consolidation/2026-10-07/). The evidence PNGs, GGUFs, manifests, result files, logs, and metadata remain untouched at their existing paths.

The eight archived Phase 4 narrative files are:

- `docs/PHASE_4_IMPLEMENTATION.md`
- `docs/PHASE_4E.md`
- `docs/PHASE_4G.md`
- `docs/PHASE_4H_REAL_WINDOWS_EVIDENCE_STUDY.md`
- `docs/calibration_4gap3/UIA_INVESTIGATION.md`
- `docs/calibration_4gap3/GROUND_TRUTH_REVIEW.md`
- `docs/calibration_4gap4/LIVE_FLOW_TIMING_RESULTS.md`
- `docs/calibration_4gap5/PHASE_4_CLOSEOUT.md`

Repository-wide Markdown inventory also found `docs/REQUIREMENTS.md` and `docs/PHASE_3.md` contain Phase 4 references, but they are general requirements and the canonical Phase 3 narrative rather than Phase 4 reports; `docs/PHASE_1.md`, `docs/PHASE_2.md`, and `docs/PHASE_3.md` remain the existing per-phase canonical files. `docs/PHASE_3_VERIFICATION.md` and `docs/PHASE_3_RESULTS_TEMPLATE.md` were later consolidated into `docs/PHASE_3.md` and archived at `docs/archive/pre-consolidation/2026-10-07/phase-3/`. `docs/INSTALLATION.md`, root `README.md`, and `wgc-bridge/WgcSharp/README.md` are not Phase 4 narrative sources and are not archived. No other Markdown with Phase 4 findings was found.

Reconciliation is explicit: 4H's earlier statement that no real-target evidence was established is retained as the initial study result; later 4-GAP live/crop-replay evidence is added chronologically rather than replacing it. The later 18/18 replay is distinct from the historical 18/18 capture-time failures. The 4-GAP-4 8/8 UIA successes and one unavailable case are distinct from the latest Notepad capture, whose UIA status was not persisted. OCR is reported as status only where saved status exists; the later live captures' character counts are not promoted to OCR AVAILABLE/INSUFFICIENT. The artifact recount corrects the OCR summary to **13 AVAILABLE / 5 INSUFFICIENT** from the actual `SUMMARY.tsv`.

No finding or open question is intentionally omitted as duplication. Repeated architecture descriptions are consolidated here; source-specific detailed content is retained in this file, while original narratives remain archived for reversibility.

## Validation history and phase boundary

Automated suite/build counts in the archived reports describe different historical code states and are not current real-window validation. The safety follow-up's targeted `:composeApp:test --tests calibration.DiagnosticWindowCloseAllowlistTest` passed, and its full build/test suite passed. The 4-GAP-5 consolidation verification runs the build on the current worktree; its result is reported in the task handoff. Phase 1–3 files (`docs/PHASE_1.md`, `docs/PHASE_2.md`, `docs/PHASE_3.md`) remain the existing one-file-per-phase narratives; this documentation-only consolidation does not edit their behavior or content.
