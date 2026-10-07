# Phase 3: Universal Region Selection

## Data flow

```text
Ctrl+Shift+Space -> GlobalHotkeyManager -> RegionSelectionController
 -> ApplicationContextProvider (before tutor windows hide)
 -> per-monitor frozen snapshot -> RegionSelectorWindow -> ScreenCaptureService crop
 -> ContextProcessor [OCRService || ContentClassifier || PerceptionEngine] -> VisualContext -> TutorController event/session
```

The existing Ctrl+Alt+F12 WGC previous-window capture remains available through the same host. The UI uses `VisualContextAcquisition` and `RegionSelectionController` for `IDLE -> SELECTING -> PROCESSING -> COMPLETED -> IDLE`; Escape, disposal, and tiny regions use `CANCELLED -> IDLE`. Overlapping starts are rejected atomically.

`PROCESSING` begins when the drag completes. A capture or processing error emits `PROCESSING_FAILED` and returns the controller to `IDLE`; cancellation emits `SELECTION_CANCELLED` and returns to `IDLE` as the UI restores the tutor windows. The controller uses synchronized, session-ID checked transitions so stale callbacks cannot finish a newer session.

## Coordinates and capture

`VisualContext.x/y` and selector bounds are AWT desktop user-space coordinates; origins may be negative. `VisualContext.width/height` are captured output pixel dimensions. `FrozenScreenSnapshot` stores a logical-size composition for selection display plus independent physical-resolution Robot images for each monitor. `FrozenSnapshotRegionCaptureService` intersects the selected logical rectangle with each monitor and maps through each monitor's independent X/Y scale. The output uses the highest intersecting monitor scale per axis, preserving source pixels and upscaling lower-density tiles when scales differ.

The implementation uses each `GraphicsDevice.bounds` and `Robot.createMultiResolutionScreenCapture`. JDK 17 returns monitor-specific resolution variants, while AWT bounds are toolkit/user-space coordinates. This mapping is covered with fake monitor layouts and was not exercised on mixed-DPI hardware here; Windows/JDK behavior must still be verified on real 100/125/150/200% and mixed-DPI monitor setups.

## Privacy and logging

Screenshots are represented in memory by `BufferedImage`; a temporary `AIVT_*.png` is created only for the existing preview and deleted when it closes or its Compose window is disposed. Lifecycle logging accepts a strict whitelist of IDs, sizes, durations, state, OCR character count, and registration IDs. OCR text, image bytes, app names, and window titles are not logged.

## Extension points

`PlaceholderOcrService` remains available for unsupported platforms/tests; Phase 4D supplies local Tesseract OCR on Windows. The classifier remains a placeholder. `ApplicationContextProvider` is nullable and independent. `VisualContext` is the boundary for later Ambient-RAG ingestion; this phase adds no embeddings, vector store, retrieval, or LLM calls.

## Limitations

OCR returns crop-relative word boxes from local Tesseract on Windows; synthetic fixtures cover the provider, while accuracy against real applications remains unverified. Active-window detection is implemented through the isolated PowerShell/Win32 boundary and excludes the tutor process, but title/process metadata collection requires runtime validation. Real hotkey registration was observed to succeed on this Windows host. Basic hotkey-triggered selector rendering has since been exercised via 8 live captures (see `PHASE_4.md`), but the DPI scaling, multi-monitor, and resilience items on the verification checklist below remain unverified; no checklist row is marked as passing based on those captures.

## Windows desktop verification checklist

Run this checklist on the target Windows machine. Mark each result PASS, FAIL, or NA and include the Windows build, JDK, monitor layout, and app version in Notes. Automated helper: `powershell -ExecutionPolicy Bypass -File scripts/verify-phase3.ps1 -LaunchApp`.

### Functional - single monitor, 100% scaling

| # | Action | Expected | Result (PASS/FAIL/NA) | Notes |
|---|---|---|---|---|
| F1 | Start `gradlew run`; inspect tutor status | Hotkey registration result is truthful; Ctrl+Shift+Space reports active only if registered | | |
| F2 | Trigger hotkey | Tutor windows hide before the frozen image; screen dims | | |
| F3 | Drag top-left to bottom-right | Correct region is outlined and captured | | |
| F4 | Drag bottom-right to top-left | Same normalized bounds and correct capture | | |
| F5 | Drag top-right to bottom-left | Correct normalized bounds and capture | | |
| F6 | Drag bottom-left to top-right | Correct normalized bounds and capture | | |
| F7 | Press Escape | Selector closes, tutor windows restore, no capture remains | | |
| F8 | Click or select about 3 physical pixels | Rejected with a visible status; no stale session | | |
| F9 | Complete a selection | Panel reports dimensions, content type, OCR length, and app name when available | | |
| F10 | Inspect preview | It matches selected pixels; no dim tint or selection border | | |
| F11 | Double-press shortcut during selection/processing | Second session is ignored | | |
| F12 | Repeat over Notepad, Chrome, VS Code, Excel, a PDF viewer, and a video player | Selection works regardless of foreground application | | |
| F13 | Trigger Ctrl+Alt+F12 | Existing previous-window capture still works | | |
| F14 | Use old Select Region button and Phase 1 controls | Existing controls remain functional | | |
| F15 | Close app, then press shortcut | No hotkey callback or app restart occurs | | |
| F16 | Reserve Ctrl+Shift+Space in another process before launch | App stays usable and reports registration conflict accurately | | |
| F17 | Run packaged MSI/EXE | Behavior matches `gradlew run` | | |

### Display geometry

| # | Action | Expected | Result (PASS/FAIL/NA) | Notes |
|---|---|---|---|---|
| D1 | Set display scaling to 125%, then 150%, then 200%; select a known-size reference | Captured physical dimensions match measured pixels | | |
| D2 | Use primary and secondary monitors | Both can be selected | | |
| D3 | Place secondary left of primary, then above primary | Negative desktop coordinates map correctly | | |
| D4 | Select a rectangle spanning monitors | Crop matches visible selection | | |
| D5 | Use different monitor resolutions and scaling factors | Mapping remains aligned; record JDK 17 mixed-DPI behavior | | |
| D6 | Select against all four screen edges/corners | Exact edge pixels are included | | |
| D7 | Drag beyond an edge | Selection clamps to virtual-screen bounds | | |

### Resilience and privacy

| # | Action | Expected | Result (PASS/FAIL/NA) | Notes |
|---|---|---|---|---|
| R1 | Process a full virtual-desktop selection | UI remains responsive | | |
| R2 | Temporarily rename/remove `wgc-bridge.exe`, then request Phase 2 capture | Visible error; no UI freeze | | |
| R3 | Capture several regions and exit | No `%TEMP%\AIVT_*.png` leftovers | | |
| R4 | Run 30 or more captures | Memory does not climb steadily; snapshots/images are released | | |
| R5 | Inspect terminal lifecycle logs | Expected event order; dimensions/durations only, no OCR text or titles | | |

## Verification sign-off record

Complete this record after running the automated and manual checks on the target machine. Blank fields are intentionally unverified; do not treat them as passing.

Machine:
Windows build:
JDK:
.NET SDK:
Monitor layout/scales:
Commit/build identifier:

| Acceptance criterion | Status (MET / NOT MET / UNVERIFIED) | Evidence / notes |
|---|---|---|
| Project compiles; automated tests pass; 16 original tests unchanged and pass | | |
| Every required test area has meaningful coverage; all mutations are caught | | |
| Logs contain no sensitive content; Phase 3 code has no app-specific logic | | |
| State machine, hotkey lifecycle, and cleanup meet requirements | | |
| Real Windows hotkey, overlay, monitor/DPI, and packaging checks | UNVERIFIED until run on the target desktop | |

| Check group | Result (PASS / FAIL / SKIPPED / NOT RUN) | Evidence / notes |
|---|---|---|
| Environment | | |
| Static review A1-A9 | | |
| Clean compile | | |
| Automated tests and original regression tests | | |
| Coverage and mutation checks | | |
| Three-run flakiness check | | |
| .NET bridge build | | |
| Manual Windows checklist | | |

Sign-off / date:
