# 4-GAP-5 Phase 4 Close-out

## Latest Notepad capture

The capture has one durable local artifact: `%TEMP%\AIVT_9d243cd466434b55aede473304dc1834_15703030827535814018.png` (697 × 149, created 2026-10-07 12:05:02 local time). It is the crop shown to the user and contains the Notepad section heading and paragraph. The runtime emitted `CAPTURE_COMPLETED` (697 × 149) and `CONTEXT_CREATED` (136 OCR characters), but that console output was not saved to a result file.

The production result type is `context.VisualContext`; it carries `perceptionResult: PerceptionResult?`, and that result carries a `metadata["status"]` and `selectedObject`. The runtime code does not serialize that object to the crop PNG, a database, or a capture-result file. The repository has no result file for this capture, and the 4-GAP-4 timing report is unrelated to it. The IDE currently has no active debugger session from which the transient `VisualContext` can be inspected. Therefore the specific capture's UIA status and selected object are **NOT_RECORDED_ANYWHERE in a recoverable capture record**. This is not evidence of either UIA failure or success.

## Safety incident and guard

The close signal came from an ad-hoc PowerShell input script used during the prior diagnostic, not from repository application or calibration-harness code. That script attempted `SetForegroundWindow` on a found preview handle, ignored the activation result, and then unconditionally emitted Alt+F4 to the foreground window. There was no task-created-window ownership check and no verification that the preview was actually foreground. Alt+F4 targets whichever window is foreground; it did not explicitly target Notepad. The recorded incident says Notepad was the window that closed. The recorded trigger was an unchecked/unsuccessful preview activation attempt, not a reported timeout.

No diagnostic or calibration source file contains the incident's close-key sender to patch. The unsafe transient script is no longer used. To guard future in-repository diagnostics, `calibration.DiagnosticWindowCloseAllowlist` records only HWNDs returned by its diagnostic window-creation callback and exposes a gated close-signal hook: it rejects unowned handles, calls the signal callback only after activation succeeds and the same HWND is verified as foreground, and otherwise returns without sending. There is no actual close-signal sender in repository diagnostics to wire to this hook. Regression tests verify an external HWND is rejected before activation and that failed activation never reaches the signal callback.

## Bounded missing-HWND check

The one 4-GAP-4 `missing_window_handle` observation is recorded with the initial live-flow attempts, before the later failed preview-activation/Alt+F4 action. Its timing was 2226.8485 ms from foreground capture, 1689.4742 ms from the hide-settled marker, and 37.6842 ms from selection completion. The ordering rules out the Notepad close as its downstream cause. Treat it as a **genuinely independent open item**: an unavailable application context was observed, but no cause was established or pursued here.

## Recounted capture inventory

Counts distinguish the 18 saved calibration-harness observations from the 9 additional 4-GAP-4 selector-flow observations and the latest user capture. The JDK 22 native OCR crash before `CONTEXT_CREATED`, two Calculator activation attempts that never reached selection, and other incomplete/failed activation attempts are not counted as completed captures.

### Saved calibration cases C01–C18

These are completed screenshot/crop processing runs from `docs/calibration_4gap2/results/`, but not hotkey-and-selector end-to-end runs. Their stored UIA and OCR statuses are copied from `SUMMARY.tsv`; all labels remain pending.

| Case | Stored capture-time UIA | Later UIA replay on the saved crop | OCR |
|---|---|---|---|
| C01 | FAILURE | SUCCESS | AVAILABLE |
| C02 | FAILURE | SUCCESS | INSUFFICIENT |
| C03 | FAILURE | SUCCESS | AVAILABLE |
| C04 | FAILURE | SUCCESS | AVAILABLE |
| C05 | FAILURE | SUCCESS | AVAILABLE |
| C06 | FAILURE | SUCCESS | AVAILABLE |
| C07 | FAILURE | SUCCESS | AVAILABLE |
| C08 | FAILURE | SUCCESS | AVAILABLE |
| C09 | FAILURE | SUCCESS | AVAILABLE |
| C10 | FAILURE | SUCCESS | INSUFFICIENT |
| C11 | FAILURE | SUCCESS | AVAILABLE |
| C12 | FAILURE | SUCCESS | AVAILABLE |
| C13 | FAILURE | SUCCESS | INSUFFICIENT |
| C14 | FAILURE | SUCCESS | AVAILABLE |
| C15 | FAILURE | SUCCESS | INSUFFICIENT |
| C16 | FAILURE | SUCCESS | AVAILABLE |
| C17 | FAILURE | SUCCESS | INSUFFICIENT |
| C18 | FAILURE | SUCCESS | AVAILABLE |

**Subtotal:** 18 completed calibration-harness cases; stored capture-time UIA status was FAILURE on all 18, while a later production-path replay returned SUCCESS on all 18 saved crops. OCR was 13 AVAILABLE and 5 INSUFFICIENT at capture time. The later UIA replay is not an additional capture and does not validate semantic correctness.

### 4-GAP-4 live selector flow

The timing report records eight completed cases and one additional selected case. The report contains UIA results and timing for the eight; the Gradle console output recorded OCR character counts, but not OCR statuses. Character counts are not substituted for OCR status.

| Case | UIA | OCR evidence retained |
|---|---|---|
| L01 | SUCCESS | 5 OCR characters logged |
| L02 | SUCCESS | 37 OCR characters logged |
| L03 | SUCCESS | 26 OCR characters logged |
| L04 | SUCCESS | 9 OCR characters logged |
| L05 | SUCCESS | 59 OCR characters logged |
| L06 | SUCCESS | 57 OCR characters logged |
| L07 | SUCCESS | 0 OCR characters logged |
| L08 | SUCCESS | 80 OCR characters logged |
| Additional case | UNAVAILABLE (`missing_window_handle`) | 14 OCR characters logged |

**Subtotal:** 9 completed live selector-flow cases; UIA 8 SUCCESS, 1 UNAVAILABLE, 0 FAILURE. OCR availability statuses were not persisted for these cases.

### Latest user Notepad capture

| Capture | UIA | OCR |
|---|---|---|
| Notepad, 697 × 149 | NOT_RECORDED_ANYWHERE in a recoverable capture record | 136 characters reported by `CONTEXT_CREATED`; OCR availability/status not persisted |

**Total counted completed captures: 28** (18 calibration-harness image-processing cases + 9 selector-flow cases + 1 latest Notepad capture). Of these, **10 were completed real hotkey-and-selector end-to-end flows** (the 9 4-GAP-4 observations plus the latest user capture); the calibration-harness cases are included in the total inventory but were not hotkey-and-selector end-to-end captures. The 8 successful UIA timing cases are included within the 9 selector-flow observations, not counted twice.

## Validation status

- Visual grounding remains NOT_CONFIGURED: CPU path RAM-blocked, GPU/Vulkan path driver-blocked; no third stack attempted.
- Ground truth remains 0 human-confirmed cases and is in progress separately. Labeling is intentionally not a Codex task.
- No correctness result can be reported: correctness evaluation is blocked on human-confirmed ground truth, not on additional engineering work.
- Phase 1–3 implementation was not changed. Full build and test results are recorded in the handoff for this task.
- Pre-existing dirty worktree changes were left untouched.
