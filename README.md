# AI Visual Tutor

AI Visual Tutor is a Windows desktop application built with Kotlin, Compose Multiplatform Desktop, and a .NET capture bridge. It combines guided tutorial UI and visual overlays with screen capture and a universal region-to-context pipeline.

## Project status

### Phase 5: limited freeze

**Phase 5 is frozen as a limited, evidence-documented milestone.** Its selectable grounding modes are `UIA_ONLY`, `OCR_ONLY`, `UIA_OCR`, `VISION_ONLY`, and `ADAPTIVE`. UIA/OCR grounding, routing, deterministic decisions, persistence/replay, and evaluation tooling have automated or controlled-fixture coverage.

- The final Phase 5 audit passed the full build and **153 tests** (2 opt-in UIA integration skips); later Phase 6 integration passed the full Kotlin suite with **190 tests, 0 failures, 0 errors, and 3 skips**.
- Real UGround-V1-2B inference remains **NOT VERIFIED**: the host had about 0.83 GB available against the existing 3.63 GB safety threshold. The memory guard was preserved.
- Live multi-provider conflict resolution remains unverified. The independent comparison dataset has only two cases, so no comparative research-accuracy claim is established.
- See [`docs/PHASE_5_RUNTIME_AUDIT_2026-10-09.md`](docs/PHASE_5_RUNTIME_AUDIT_2026-10-09.md) and [`docs/PHASE_5.md`](docs/PHASE_5.md) for evidence, commands, and limitations.

### Phase 6: Blender state verification

Phase 6 adds a user-triggered verification workflow to the tutor panel when Blender is selected. The user chooses an operation and target, captures a baseline from Blender, performs the action in Blender, and clicks **I completed it** to capture and evaluate the resulting state. Supported operations are create, delete, rename, select, and modify (object location).

- A separately installed, opt-in Blender add-on provides read-only scene snapshots to the Kotlin app over loopback TCP (`127.0.0.1:47629` by default). It does not perform or change Blender actions.
- The deterministic verifier correlates baseline and post-action snapshots to one Blender session, returns `SUCCESS`, `FAILURE`, or `UNCERTAIN`, and saves JSON results under `%LOCALAPPDATA%\AIVisualTutor\verification-runs`.
- Automated tests and an isolated Blender snapshot integration test pass. **Activation in the user's existing desktop Blender session and a full tutor-UI user-action run are not verified.** The UI does not infer expected operations from tutorial text.
- See [`docs/PHASE_6.md`](docs/PHASE_6.md) for setup, interfaces, tests, and remaining validation.

## Phase 4 capabilities and evidence boundary

Phase 4 supplies UI Automation, OCR, deterministic evidence decisions, calibration persistence, and an opt-in UGround-V1-2B provider boundary. The final Phase 5 runtime audit classifies real UGround inference as **NOT VERIFIED** because the existing memory-safety preflight blocked model loading on the audited host. Historical calibration artifacts remain preserved, but their presence is not evidence of verified inference in the final audit or of grounding accuracy. Human labels are optional research annotations and are never required for application operation.

- Windows UI Automation and Tess4J/Tesseract OCR are implemented and preserved as separate evidence sources. The deterministic evaluator implements `ACCEPT`, `REFINE`, `ESCALATE`, and `ABSTAIN`; it does not learn or fuse provider confidence.
- UGround-V1-2B (`osunlp/UGround-V1-2B`, Apache-2.0) is integrated through the existing llama.cpp `mtmd` CPU provider. **The final Phase 5 runtime audit did not verify real model inference**: the memory preflight blocked loading at about 0.83 GB available versus the unchanged 3.63 GB safety threshold. Model artifacts and runtime are local and are not part of the repository; see the final audit for the latest status.
- B01–B04 historical evidence/labels and C01–C18 historical evidence/labels are preserved under LocalAppData; new machine records use `groundTruthLabel: PENDING`. Refer to the Phase 5 runtime audit for the final verified model status.
- Each completed calibration capture/replay is persisted automatically under `%LOCALAPPDATA%\AIVisualTutor\calibration-runs\` (or an explicit `aivt.calibration.outputDir`) as a crop plus atomic schema-v3 `result.json` and append-only manifest entry. The additive `visualGrounding` block distinguishes not-configured, unavailable, host-blocked, failure, cancelled, and successful results; it records points only when actually returned. Existing v1/v2 records remain unchanged and readable by manifest recovery. These private screen-content artifacts are outside the repository by default; do not commit or share them.
- Screenshot Preview shows the exact supplied target description (or `Not specified`), UIA/OCR/vision statuses, and the evaluator decision. A blank target does not invoke visual grounding.
- OCR boxes use crop-image pixels; UIA bounds use physical desktop coordinates. Their transform is supported when valid capture/window context is supplied; mixed-DPI and broader multi-monitor behavior remain unverified. Historical UIA discrepancies and limitations are detailed in the Phase 5 runtime audit.
- RQ1–RQ3 remain partially answered by the recorded UIA/OCR and four-case Blender observations. UGround correctness/benefit, modality improvement, adaptive benefit, user learning, usability, and workload are not established. No user study was performed.
- Phase-specific test counts above are historical to their audits. The latest complete suite after Phase 6 integration passed **190 tests, with 0 failures, 0 errors, and 3 skips**. Interactive desktop Blender verification and mixed-DPI/multi-monitor checks remain unverified.

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

Historical reports of 18/18 capture-time UIA failures versus later 18/18 saved-crop replay successes remain unexplained. UIA intersection is not proof of semantic correctness. Earlier B/C calibration artifacts are retained, but the final runtime audit classifies real UGround-V1-2B inference as **NOT VERIFIED** because the memory guard blocked model loading. No coordinate-level ground truth or visual accuracy has been established. A previous generic Qwen2-VL runtime smoke test is not GUI-grounding evidence; the previous Vulkan attempt failed with a driver/runtime error.

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
- [Phase 5: evidence-adaptive grounding](docs/PHASE_5.md)
- [Phase 5 runtime audit and limited-freeze assessment](docs/PHASE_5_RUNTIME_AUDIT_2026-10-09.md)
- [Phase 6: Blender closed-loop state verification](docs/PHASE_6.md)
