# Phase 3 Windows Desktop Verification

Run this checklist on the target Windows machine. Mark each result PASS, FAIL, or NA and include the Windows build, JDK, monitor layout, and app version in Notes. Automated helper: `powershell -ExecutionPolicy Bypass -File scripts/verify-phase3.ps1 -LaunchApp`.

## Functional — single monitor, 100% scaling

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

## Display geometry

| # | Action | Expected | Result (PASS/FAIL/NA) | Notes |
|---|---|---|---|---|
| D1 | Set display scaling to 125%, then 150%, then 200%; select a known-size reference | Captured physical dimensions match measured pixels | | |
| D2 | Use primary and secondary monitors | Both can be selected | | |
| D3 | Place secondary left of primary, then above primary | Negative desktop coordinates map correctly | | |
| D4 | Select a rectangle spanning monitors | Crop matches visible selection | | |
| D5 | Use different monitor resolutions and scaling factors | Mapping remains aligned; record JDK 17 mixed-DPI behavior | | |
| D6 | Select against all four screen edges/corners | Exact edge pixels are included | | |
| D7 | Drag beyond an edge | Selection clamps to virtual-screen bounds | | |

## Resilience and privacy

| # | Action | Expected | Result (PASS/FAIL/NA) | Notes |
|---|---|---|---|---|
| R1 | Process a full virtual-desktop selection | UI remains responsive | | |
| R2 | Temporarily rename/remove `wgc-bridge.exe`, then request Phase 2 capture | Visible error; no UI freeze | | |
| R3 | Capture several regions and exit | No `%TEMP%\AIVT_*.png` leftovers | | |
| R4 | Run 30 or more captures | Memory does not climb steadily; snapshots/images are released | | |
| R5 | Inspect terminal lifecycle logs | Expected event order; dimensions/durations only, no OCR text or titles | | |
