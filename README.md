# AI Visual Tutor

AI Visual Tutor is a Windows desktop application built with Kotlin, Compose Multiplatform Desktop, and a .NET capture bridge. It combines guided tutorial UI and visual overlays with screen capture and a universal region-to-context pipeline.

## Project status

## Phase 4 Status

**Phase 4 core plumbing is complete, and real UGround CPU inference has now executed on saved crops.** This establishes that the configured provider can return and persist points; it does not establish visual-grounding accuracy or correctness. Human labels remain optional research annotations and are never required for application operation.

- Windows UI Automation and Tess4J/Tesseract OCR are implemented and preserved as separate evidence sources. The deterministic evaluator implements `ACCEPT`, `REFINE`, `ESCALATE`, and `ABSTAIN`; it does not learn or fuse provider confidence.
- UGround-V1-2B (`osunlp/UGround-V1-2B`, Apache-2.0) ran through the existing llama.cpp `mtmd` CPU provider using the third-party Q4_K_M GGUF and matching fp16 projector from `mradermacher/UGround-V1-2B-GGUF`. The artifacts are stored in LocalAppData and are not part of the repository.
- The earlier 2026-10-08 memory check (about 1.69 GiB free) was safely blocked by the 3.38 GiB guard. Later host checks passed the 3,629,247,837-byte guard, and the downloaded artifacts enabled real saved-crop inference. B01–B04 and C01–C18 were replayed with `--image-min-tokens 1024`: 22/22 provider attempts returned parseable points and 22/22 evaluator decisions were `ABSTAIN`. Provider latency was 20.921–27.102 seconds. These results show execution and persistence only; no target-coordinate ground truth or visual accuracy score was established.
- B01–B04 historical evidence/labels and C01–C18 historical evidence/labels are preserved. The current 1024-token visual replay records are under `%LOCALAPPDATA%\AIVisualTutor\calibration-runs\phase4-b01-b04-uground-1024\` and `%LOCALAPPDATA%\AIVisualTutor\calibration-runs\phase4-c01-c18-uground-1024\`; new machine records use `groundTruthLabel: PENDING`.
- Each completed calibration capture/replay is persisted automatically under `%LOCALAPPDATA%\AIVisualTutor\calibration-runs\` (or an explicit `aivt.calibration.outputDir`) as a crop plus atomic schema-v3 `result.json` and append-only manifest entry. The additive `visualGrounding` block distinguishes not-configured, unavailable, host-blocked, failure, cancelled, and successful results; it records points only when actually returned. The preflight-only record and B/C inference runs are in separate LocalAppData directories. Existing v1/v2 records remain unchanged and readable by manifest recovery. These private screen-content artifacts are outside the repository by default; do not commit or share them.
- Screenshot Preview shows the exact supplied target description (or `Not specified`), UIA/OCR/vision statuses, and the evaluator decision. A blank target does not invoke visual grounding.
- OCR boxes use crop-image pixels; UIA bounds use physical desktop coordinates. No verified cross-source transform exists, so geometry comparison/IoU is not computed. The historical 18/18 capture-time UIA failures versus later 18/18 replay successes remain unexplained; a separate `missing_window_handle` event also remains unresolved.
- RQ1–RQ3 remain partially answered by the recorded UIA/OCR and four-case Blender observations. UGround correctness/benefit, modality improvement, adaptive benefit, user learning, usability, and workload are not established. No user study was performed.
- Current closeout validation: **118 tests passed** with zero failures, errors, or skips; `:composeApp:build` passed; and the focused Phase 1–3/shared regression selection passed **38 tests**. `git diff --check` passed. Interactive desktop, DPI, and multi-monitor manual checks remain separate and are not claimed as passed.

The project has not started Ambient-RAG, retrieval, or model-backed tutoring assistance.

Phase 1 provides the tutor panel, dock, step controls, mock workflows, and transparent click-through guidance overlays. The existing mock workflow content includes Blender, PDF, and Excel examples.

Phase 2 adds whole-window capture through the Windows Graphics Capture bridge, a manual frozen-frame selector, screenshot preview, and the `Ctrl+Alt+F12` capture shortcut.

Phase 3 adds universal visual context acquisition. The region-selection pipeline does not depend on a particular foreground application: it records foreground-window metadata before opening the selector, freezes the desktop, lets the user select a rectangle, crops the frozen monitor images, and processes the crop into an in-memory `VisualContext`. Phase 4 adds Windows UI Automation evidence, local Tesseract OCR word evidence, deterministic evidence evaluation, capture-result persistence, and an opt-in llama.cpp visual-grounding boundary. The classifier remains a placeholder.

The desktop focus/cancellation lifecycle has code and headless regression coverage, including ESC key-down/key-up consumption and restoration of the previously foreground window. **Interactive ESC/focus restoration, real mouse selection, and multi-monitor/DPI behavior still require manual verification on a Windows desktop.** The repository does not claim those behaviors were manually verified.

## Features

- Tutor panel and collapsible dock with existing Phase 1 controls and example workflows.
- Transparent, click-through highlight and callout overlay.
- Global shortcuts, registered once and released on application shutdown:

  | Shortcut | Action |
  | --- | --- |
  | `Ctrl+Alt+F12` | Capture the previous external window (Phase 2) |
  | `Ctrl+Shift+Space` | Start universal region selection (Phase 3) |

- Full virtual-desktop selection overlay with drag selection in all four directions, ESC cancellation, and a minimum selection threshold of more than 8 captured pixels in both dimensions.
- Frozen per-monitor snapshots and crop mapping that accounts for monitor bounds, negative desktop origins, and per-monitor snapshot scale factors.
- Foreground application/window metadata captured before the overlay changes focus. Window titles are not written to lifecycle logs.
- `VisualContext` with region ID, captured image, desktop origin, image dimensions, timestamp, structured OCR word evidence, UI Automation perception result, classification result, application context, and processing metadata.
- Asynchronous OCR/classification/perception processing. On Windows the default OCR provider is Tess4J/Tesseract, using bundled English and orientation models; additional Tesseract language data can be configured. OCR boxes are relative to selected crop pixels. The default classifier still reports `UNKNOWN` unless replaced.
- Temporary PNG preview files are removed when the preview closes. Captured image data is otherwise held in memory.
- Lifecycle logs contain event names and safe metadata only; they exclude screenshot bytes, OCR text, and window titles.

## Architecture

```text
Global hotkey manager
        |
        v
Region selection controller <---- foreground-window snapshot/context
        |
        v
Fullscreen Compose selector <---- frozen per-monitor desktop snapshot
        |
        v
ScreenCaptureService (selected crop only)
        |
        v
ContextProcessor (OCR + UIA + content classifier)
        |
        v
Evidence evaluation -> VisualContext -> tutor session/events + screenshot preview
```

The selection controller owns the `IDLE -> SELECTING -> PROCESSING -> COMPLETED -> IDLE` lifecycle, with cancellation and failure paths returning to `IDLE`. Duplicate starts are rejected. On cancellation, ESC is consumed through key-up before teardown; after the overlay is removed and tutor visibility is restored, the app requests focus restoration to the captured foreground window. Native window operations are isolated behind `WindowFocusManager` for headless testing.

Selection and window placement use the desktop coordinate space reported by Java AWT; cropped image dimensions are capture pixels. The crop service maps each monitor tile independently and uses the highest intersecting monitor scale for the output image. Java 17 and mixed-DPI monitor behavior can vary by Windows/JDK configuration; validate exact boundaries on the target hardware.

## Technology

- Kotlin 2.0.21, JVM target 17
- Compose Multiplatform Desktop 1.7.0
- Gradle 8.9 wrapper
- .NET 10 Windows bridge targeting `net10.0-windows10.0.19041.0`
- Windows 10 build 19041+ or Windows 11, x64

## Run and test

Install JDK 17 and the .NET 10 SDK, then from PowerShell:

```powershell
$env:JAVA_HOME = "C:\Path\To\JDK17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :composeApp:run
```

Run the Kotlin unit tests:

```powershell
.\gradlew.bat clean composeApp:compileKotlin
.\gradlew.bat composeApp:test --rerun-tasks
```

The headless suite covers hotkey lifecycle, selection-state transitions and cancellation, geometry, frozen-crop mapping, multi-monitor/negative-origin math, processing degradation, asynchronous execution, privacy-safe logging, perception providers/evaluation, and existing Phase 1/2 regressions. The final Phase 4 closeout run passed **111 tests** with no failures, errors, or skips. This does not replace interactive desktop verification.

### Phase 4 evidence and research limitations

The C01–C18 human labels and B01–B04 labels are present in local research artifacts; result persistence itself does not require them and keeps machine outputs marked `PENDING`. Existing labels/evidence are preserved. B01–B04 provide four limited Blender UIA/OCR observations, not visual-grounding results or a broad application validation.

The historical 18/18 capture-time UIA failures and later 18/18 saved-crop replay successes remain unexplained. UIA intersection is not proof of semantic correctness. UGround inference has run on the 22 saved B/C crops, but the evaluator abstained on all 22 and no coordinate-level ground truth or visual accuracy was established. A previous generic Qwen2-VL runtime smoke test is not GUI-grounding evidence; the previous Vulkan attempt failed with a driver/runtime error.

### Phase 0 research claims: established vs. unproven

**Established by implementation and recorded tests:** Windows UIA and local Tesseract OCR providers exist; their evidence is kept separate; a deterministic evaluator implements `ACCEPT`, `REFINE`, `ESCALATE`, and `ABSTAIN`; provider failure/cancellation handling and explicit coordinate-space types exist; and synthetic tests exercise provider/evaluator behavior. These are implementation facts, not evidence that the system is more accurate or reliable.

**Observed, but not proof of correctness:** the 18 saved calibration cases recorded UIA `FAILURE` at capture time, while a later replay found intersecting UIA elements in all 18. Eight later live selector captures returned UIA `SUCCESS`, with one additional `missing_window_handle` outcome. These results do not identify the intended target or explain the historical discrepancy. Human labels exist locally, but are not substituted for missing or mismatched provider evidence.

**Not established by the end of Phase 4:** whether UIA outperforms OCR, OCR helps where UIA cannot, UIA+OCR outperforms either alone, visual grounding improves accuracy or is necessary, adaptive selection beats a fixed modality, or the evaluator improves reliability or the reliability-latency trade-off. The 22 UGround outputs are limited calibration observations, not a broad modality comparison; no visual correctness or benefit is established. No verified cross-source geometry fusion exists. Reliability across applications and custom-rendered/icon-heavy targets is not established; DPI/multi-monitor behavior remains unverified. The historical UIA discrepancy is unexplained. Novelty versus prior work has not been formally established. Human learning, task completion, error reduction, hint/escalation rates, usability, workload, retention, and transfer have not been evaluated in a user study.

**Bottom line:** Phase 4 core implementation and its final evidence/persistence validation are complete; remaining items are documented research limitations and do not establish that the Phase 0 hypotheses are true. Do not present implementation coverage or provider availability as proof of research effectiveness. See [`docs/PHASE_0.md`](docs/PHASE_0.md) for the questions and proposed evaluation measures, and [`docs/PHASE_4.md`](docs/PHASE_4.md) for the evidence and current status.

Build the bridge independently with:

```powershell
dotnet build wgc-bridge -c Release
```

For the Windows desktop acceptance checklist and sign-off template, see [Phase 3](docs/PHASE_3.md). Run `scripts/verify-phase3.ps1` for local build, test, monitor, and temporary-file checks.

## Repository layout

```text
composeApp/src/main/kotlin/
  Main.kt                         # Compose windows and application wiring
  bridge/                         # Capture services, WGC bridge, global hotkeys
  context/                        # Selection lifecycle, app/window context, OCR and processing
  models/                         # Tutor contracts and captured-frame models
  overlay/                        # Transparent tutor overlay
  selection/                      # Frozen desktop snapshot and region selector
  tutor/                          # Tutor controller and mock tutorial content
  ui/                              # Tutor panel, dock, and screenshot preview
composeApp/src/test/kotlin/       # Headless unit and regression tests
wgc-bridge/                       # .NET 10 Windows capture bridge
scripts/                          # Verification helpers
docs/                             # Phase specifications and verification guides
```

## Documentation

- [Installation and setup](docs/INSTALLATION.md)
- [Requirements and boundaries](docs/REQUIREMENTS.md)
- [Phase 1: tutor UI and overlay](docs/PHASE_1.md)
- [Phase 2: capture and selection](docs/PHASE_2.md)
- [Phase 3: universal visual context acquisition](docs/PHASE_3.md)
- [Phase 4: perception status, calibration cases, and evaluation plan](docs/PHASE_4.md)
