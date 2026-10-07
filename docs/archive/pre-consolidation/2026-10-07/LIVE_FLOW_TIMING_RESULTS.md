# 4-GAP-4 Live-Flow Timing Results

## Method

The application was launched normally with temporary diagnostics enabled by the JVM property `aivt.uia.timingDiagnostics=true`. Each counted case used the registered `Ctrl+Shift+Space` shortcut, the actual full-screen region selector, and a drag selection. The target window was brought to the foreground before the hotkey and checked as foreground before the capture. The preview was closed between completed cases so the next capture followed the normal user path.

Elapsed times use `System.nanoTime()` and are measured at the production perception invocation:

- **Foreground-captured → UIA call:** from the completed pre-selector foreground-window snapshot. This is the app's recorded foreground observation, not a Windows focus-event hook or a reconstruction of how long the target had been focused before the hotkey.
- **Tutor-hide-settled → UIA call:** from the marker after the existing `isTutorWindowsVisible = false` and 200 ms settle delay. This is a post-hide marker, not a native window-hide event timestamp.
- **Selection-completed → UIA call:** from entry into the real selector's `onRegionSelected` callback.

The hide marker and foreground observation are operational timing anchors in the existing flow; they do not measure OS event timestamps. The logs below are actual diagnostic output; no timing values were interpolated.

## Eight completed live-flow cases

All eight had a verified external foreground window before triggering the shortcut. Each UIA status is `SUCCESS` (the production engine returned an element intersecting the selected region), and no exception was thrown.

| Case | Foreground target | Selected region (desktop x,y to x,y) | UIA status | Foreground captured → call (ms) | Hide-settled → call (ms) | Selection complete → call (ms) |
|---|---|---|---|---:|---:|---:|
| L01 | Paint (`263758`) | `80,610` to `320,730` | SUCCESS | 2202.7241 | 1706.4655 | 23.1667 |
| L02 | Excel (`394800`) | `800,250` to `1100,380` | SUCCESS | 2191.3736 | 1692.8733 | 20.4868 |
| L03 | File Explorer (`1902256`) | `1450,300` to `1740,390` | SUCCESS | 2208.6085 | 1725.8875 | 26.3642 |
| L04 | Paint (`263758`) | `90,630` to `340,740` | SUCCESS | 2194.7024 | 1702.0808 | 21.4392 |
| L05 | Excel (`394800`) | `820,300` to `1120,430` | SUCCESS | 2210.6809 | 1706.9280 | 22.5938 |
| L06 | File Explorer (`1902256`) | `1490,360` to `1770,450` | SUCCESS | 2171.3359 | 1674.7073 | 19.4325 |
| L07 | Paint (`263758`) | `100,650` to `350,760` | SUCCESS | 2202.4894 | 1731.4654 | 22.6415 |
| L08 | Excel (`394800`) | `820,310` to `1130,440` | SUCCESS | 2174.5815 | 1698.6924 | 24.6654 |

Observed ranges across these eight successes: 2171.3359–2210.6809 ms from foreground capture, 1674.7073–1731.4654 ms from the hide-settled marker, and 19.4325–26.3642 ms from selection completion.

## Additional and excluded attempts

- One additional real selector capture reached perception but returned `missing_window_handle`; it was **UNAVAILABLE**, not a UIA query failure. Its deltas were 2226.8485 ms, 1689.4742 ms, and 37.6842 ms, respectively. It is excluded from the eight-case success cohort because the app context had no HWND.
- A first attempt under JDK 22 captured a selection but the app process terminated in Tess4J native initialization (`TessBaseAPIInit1`, `Invalid memory access`) before a UIA timing record was produced. It is not counted as a UIA outcome. The completed runs used JDK 17.
- Two Calculator focus attempts could not establish Calculator as foreground and did not proceed to selection; they are not capture cases.

## Comparison

There were **0 UIA failures in 8 completed cases**, so there are no failure deltas to compare against success deltas. All eight succeeded over a narrow range of measured deltas. This run therefore does not confirm a timing-dependent defect and cannot rule out a rare or previously present one. No delay, retry, or other behavior change is proposed based on this evidence.

These cases establish UIA query/intersection availability only; they do not establish that the chosen UIA element was semantically correct. No ground-truth labels were changed.

## Side effect during an excluded focus attempt

During an unsuccessful attempt to activate Notepad, an Alt+F4 keystroke was sent after preview activation had failed. The Notepad process subsequently was no longer present. No save was confirmed; unsaved Notepad contents may have been discarded. This attempt was excluded from the eight cases.

## Instrumentation and validation

Temporary `TEMP_UIA_TIMING` logging was gated by the JVM property above and was removed after the run. The diagnostic edits did not alter capture, focus, selector, hotkey, overlay, OCR, or UIA behavior. Kotlin compilation and tests passed with instrumentation enabled; the full Gradle build and test suite passed again after the instrumentation was removed.
