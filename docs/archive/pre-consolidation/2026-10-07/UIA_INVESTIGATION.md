# 4-GAP-3 UI Automation Investigation

## Finding

The recorded 18/18 UIA failure is **not reproducible in the current live environment**. The existing calibration result files record `uiaStatus=FAILURE`, but do not preserve the provider diagnostic, exception type, message, or stack trace. The earlier run therefore does not provide enough evidence to identify its cause.

I reran the production `WindowsUiAutomationPerceptionEngine.perceive` path on the 18 saved crop images, using each case's recorded HWND, region, application, and target description. All 18 calls returned at least one UIA element whose bounds intersected the selected region. This establishes that the UIA query and region intersection work in the current run; it does **not** establish that the selected element is the semantically correct target.

### Two requested reproductions

| Case | Saved target | Saved HWND / selected region | Live result |
|---|---|---|---|
| C01, native Excel | Select cell B2. | `394800`; `860,250,90,28` | Production UIA query succeeded; 8 intersecting candidates. No exception was thrown, so no exception stack trace exists for this run. |
| C04, Notepad text | Read the highlighted text. | `264134`; `60,140,500,120` | Production UIA query succeeded; 3 intersecting candidates. No exception was thrown, so no exception stack trace exists for this run. |

An independent Windows UI Automation client query also succeeded against these same live windows: Excel's root plus descendants numbered 122, and Notepad's numbered 58. The production adapter serialized 121 and 44 elements with usable bounds, respectively. Those are tree/query observations, not answer-accuracy measurements.

## Suspect checks

| Suspect | Evidence from this run |
|---|---|
| a. Process elevation mismatch | The live Java/Gradle, PowerShell, Excel, and Notepad processes were all non-elevated (medium integrity). No elevation boundary was observed. |
| b. COM/STA threading | The production adapter launches its UIA client with `powershell.exe -STA`; the live production calls succeeded. No threading failure was reproduced. |
| c. Wrong/stale HWND or focus shift | Each recorded HWND was live and mapped to the expected current process/window (Excel `394800`, Calculator `526012`, Notepad `264134`, Paint `263758`, Explorer `1902256`). The production perception engine queries the supplied HWND and does not re-read the foreground window. |
| d. UIA registration/interop initialization | The production PowerShell client loaded `UIAutomationClient` and `UIAutomationTypes` and successfully queried the saved windows. No registration or interop failure was reproduced. |
| e. Target application UIA support | The independent Windows UIA client returned trees for Excel and Notepad. Both windows expose UIA elements at the time of this run. |

None of a–e is a confirmed cause of the historical failures. Since those failures were not reproduced, attributing them to an environmental condition or a code defect would be speculation. No code workaround or behavior change was made.

## Current UIA results on all saved cases

`SUCCESS` means the current query returned at least one element intersecting the saved selection. It does not mean that the element matches the user's intended target.

| Case | UIA status | Intersecting candidates | Query latency (ms) |
|---|---:|---:|---:|
| C01 | SUCCESS | 8 | 1050 |
| C02 | SUCCESS | 7 | 467 |
| C03 | SUCCESS | 9 | 534 |
| C04 | SUCCESS | 3 | 480 |
| C05 | SUCCESS | 17 | 458 |
| C06 | SUCCESS | 11 | 499 |
| C07 | SUCCESS | 15 | 496 |
| C08 | SUCCESS | 9 | 533 |
| C09 | SUCCESS | 23 | 672 |
| C10 | SUCCESS | 10 | 432 |
| C11 | SUCCESS | 8 | 444 |
| C12 | SUCCESS | 34 | 413 |
| C13 | SUCCESS | 7 | 454 |
| C14 | SUCCESS | 3 | 439 |
| C15 | SUCCESS | 10 | 500 |
| C16 | SUCCESS | 19 | 513 |
| C17 | SUCCESS | 6 | 455 |
| C18 | SUCCESS | 15 | 657 |

**Total:** 18 SUCCESS, 0 FAILURE, 0 UNAVAILABLE, 0 INSUFFICIENT in this live rerun. These results supersede neither the missing historical error detail nor the need for human ground truth.

## Ground truth and evaluation

No human-confirmed labels were available at the time of this run: 0/18 labeled, 18/18 pending. The review artifact is [GROUND_TRUTH_REVIEW.md](GROUND_TRUTH_REVIEW.md). It embeds the saved selected-region crop for each case, its recorded desktop coordinates, and the target description. No answer has been inferred.

Because no cases are labeled, there is no correctness result or per-category accuracy to report. OCR results from the prior run are available in the original case records, but are not correctness measurements. Visual grounding remains unconfigured; no third visual-grounding stack was attempted.

## Tests, build, and scope

- Full Gradle `build` completed successfully, including `:composeApp:test`.
- No regression test was added: no reproducible defect or confirmed root cause was found, so a test asserting a speculative cause would not be valid.
- No production code was changed for this investigation. Phase 1–3 code paths were not modified; the existing full test suite passed.
- The only files added for this task are this report and the ground-truth review artifact. The pre-existing worktree changes were left untouched.
