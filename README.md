# AI Visual Tutor

AI Visual Tutor is a Windows desktop application built with Kotlin, Compose Multiplatform Desktop, and a .NET capture bridge. It combines guided tutorial UI and visual overlays with screen capture and a universal region-to-context pipeline.

## Project status

**Phase 4 is in progress at `PARTIAL_SIGNAL_UIA_OCR_PENDING_GROUND_TRUTH`.** UIA/OCR providers and deterministic evidence evaluation are implemented; visual grounding remains unconfigured, and correctness has not been measured because the calibration cases still need human-confirmed labels. Ambient-RAG, retrieval, and model-backed assistance have not started.

Phase 1 provides the tutor panel, dock, step controls, mock workflows, and transparent click-through guidance overlays. The existing mock workflow content includes Blender, PDF, and Excel examples.

Phase 2 adds whole-window capture through the Windows Graphics Capture bridge, a manual frozen-frame selector, screenshot preview, and the `Ctrl+Alt+F12` capture shortcut.

Phase 3 adds universal visual context acquisition. The region-selection pipeline does not depend on a particular foreground application: it records foreground-window metadata before opening the selector, freezes the desktop, lets the user select a rectangle, crops the frozen monitor images, and processes the crop into an in-memory `VisualContext`. Phase 4 adds Windows UI Automation evidence, local Tesseract OCR word evidence, and deterministic evidence evaluation behind replaceable provider interfaces. The classifier remains a placeholder. Phase 4's saved calibration set has 18 cases, but all ground-truth labels remain pending; UIA/OCR correctness is therefore not yet reportable.

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

The headless suite covers hotkey lifecycle, selection-state transitions and cancellation, geometry, frozen-crop mapping, multi-monitor/negative-origin math, processing degradation, asynchronous execution, privacy-safe logging, perception providers/evaluation, and existing Phase 1/2 regressions. The latest run recorded in this workspace passed **34 tests** with no failures or skips. This does not replace interactive desktop verification.

### Phase 4 evaluation still needed

Phase 4 is currently an **UIA + OCR-only partial signal**, not a correctness-validated system. The 18 calibration crops and their result files are linked in [the Phase 4 record](docs/PHASE_4.md). To produce a correctness report:

1. A human reviews each linked crop and its corrected target description, then enters the confirmed target in that case's **Current label** column in `docs/PHASE_4.md`. Use `NO_VALID_TARGET` only when the crop contains no valid target for that description. Do not infer labels from UIA/OCR output; leave unconfirmed cases `PENDING`.
2. Double-check C09 and C16 during labeling: their saved categories conflict with what is visible in the File Explorer crops. C09 shows a toolbar rather than a list item; C16 shows folders despite its `no_access_desktop_empty` category. Their descriptions were corrected, but the categories were intentionally not changed.
3. Treat C18 as a Paint proxy capture, not a Blender observation. It cannot validate Blender-specific behavior; Paint is also represented by C07/C08.
4. Re-run the production UIA + OCR path only for human-labeled cases, with visual grounding left `NOT_CONFIGURED`. Record evaluator decision, whether the result matches the human label, and per-provider latency. Report correctness overall and by category; separate any false `ACCEPT` from cautious `ESCALATE`/`ABSTAIN` outcomes. Do not score `PENDING` cases.
5. Run `.\gradlew.bat build` and the Phase 1–3 regression/manual checks after implementation changes. The Phase 3 checklist is included in [`docs/PHASE_3.md`](docs/PHASE_3.md); its DPI, multi-monitor, and resilience checks remain unverified and must not be treated as passed based on the Phase 4 captures.

The recorded historical 18/18 capture-time UIA failures and later 18/18 saved-crop replay successes remain unexplained. UIA intersection is not proof of semantic correctness. The visual-grounding CPU path was RAM-blocked and the Vulkan/GPU attempt failed with a driver/runtime error; no third model stack is in scope. These limitations and the full evidence history are documented in [`docs/PHASE_4.md`](docs/PHASE_4.md).

### Phase 0 research claims: established vs. unproven

**Established by implementation and recorded tests:** Windows UIA and local Tesseract OCR providers exist; their evidence is kept separate; a deterministic evaluator implements `ACCEPT`, `REFINE`, `ESCALATE`, and `ABSTAIN`; provider failure/cancellation handling and explicit coordinate-space types exist; and synthetic tests exercise provider/evaluator behavior. These are implementation facts, not evidence that the system is more accurate or reliable.

**Observed, but not proof of correctness:** the 18 saved calibration cases recorded UIA `FAILURE` at capture time, while a later replay found intersecting UIA elements in all 18. Eight later live selector captures returned UIA `SUCCESS`, with one additional `missing_window_handle` outcome. These results do not identify the intended target or explain the historical discrepancy. All 18 calibration ground-truth labels remain pending, so no correctness score is available.

**Not established by the end of Phase 4:** whether UIA outperforms OCR, OCR helps where UIA cannot, UIA+OCR outperforms either alone, visual grounding improves accuracy or is necessary, adaptive selection beats a fixed modality, or the evaluator improves reliability, false accepts, abstention, accuracy, or the reliability-latency trade-off. There is no labeled real-target comparison or verified cross-source geometry fusion. Reliability across real applications and custom-rendered/icon-heavy targets is not established; DPI/multi-monitor behavior remains unverified. The historical UIA discrepancy is unexplained, a suitable grounding model has not been selected or run, and local grounding performance on this hardware is unestablished. Novelty versus prior work has not been formally established. Human learning, task completion, error reduction, hint/escalation rates, usability, workload, retention, and transfer have not been evaluated in a user study.

**Bottom line:** Phase 4 establishes much of the implementation and testing infrastructure needed to investigate the Phase 0 hypotheses; it does not establish that those hypotheses are true. Do not present implementation coverage or provider availability as proof of research effectiveness. See [`docs/PHASE_0.md`](docs/PHASE_0.md) for the questions and proposed evaluation measures, and [`docs/PHASE_4.md`](docs/PHASE_4.md) for the evidence and current status.

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
