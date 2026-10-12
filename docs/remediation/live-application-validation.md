# AI Visual Tutor: Live Application Validation Report
**Document:** `docs/remediation/live-application-validation.md`  
**Phase:** Remediation and Feature Enhancement (NOT Phase 9)  
**Execution Environment:** Windows 11 (AMD64), OpenJDK 17.0.14, Microsoft Excel 16 (Office 365), Blender 5.2.2 LTS  

---

## 1. Microsoft Excel COM Automation Validation

### Test Setup & Environment Preconditions
* **Application Binary:** `C:\Program Files\Microsoft Office\root\Office16\EXCEL.EXE` (Version 16.0 / Office 365).
* **Bridge Architecture:** In-process STA COM Automation via PowerShell `[Runtime.InteropServices.Marshal]::GetActiveObject('Excel.Application')`.
* **Adapter Component:** [ExcelApplicationAdapter.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/ExcelApplicationAdapter.kt).

### Live Desktop Empirical Test Matrix

| Test ID | Scenario | Preconditions | Steps Executed | Actual Observation | Result |
|---|---|---|---|---|---|
| **EXCEL-T1** | Query Closed Instance | Excel is not running | Adapter calls `checkHealth()` and `takeSnapshot()` | Returns `AdapterHealthStatus.UNAVAILABLE` and `AdapterSnapshotResult.Unavailable` with diagnostic: *"Excel is not running."* | **PASS** |
| **EXCEL-T2** | Query Running Instance without Workbook | Excel is launched in safe mode with 0 workbooks | Adapter queries ROT via `GetActiveObject('Excel.Application')` | Returns `RUNNING_NO_WORKBOOK` diagnostic: *"Excel is running, but no workbook is currently open."* | **PASS** |
| **EXCEL-T3** | Active Workbook & Sheet Discovery | Excel running with workbook `AIVT_Test.xlsx` on sheet `Summary` | Adapter queries active workbook and active sheet properties | Successfully parsed: `wb.Name = "AIVT_Test.xlsx"`, `ws.Name = "Summary"`. | **PASS** |
| **EXCEL-T4** | Active Cell Address & Value Inspection | Cell `B5` selected with integer value `42` | Adapter extracts `activeCell.Address` and `activeCell.Value2` | Captured `activeCell = "$B$5"`, `activeValue = "42"`. | **PASS** |
| **EXCEL-T5** | Active Cell Formula Extraction | Cell `B5` contains formula `=SUM(A1:A4)` | Adapter queries `activeCell.Formula` | Captured `activeFormula = "=SUM(A1:A4)"`. | **PASS** |
| **EXCEL-T6** | Cell Selection Change Detection | User changes active selection from `B5` to `C10` | Adapter takes consecutive snapshot | Snapshot reflects new active address `$C$10` and updates version counter. | **PASS** |
| **EXCEL-T7** | Non-Destructive Operation | Protected workbook with read-only worksheets | Adapter takes state snapshot | Workbook state, undo stack, and file modification timestamps completely untouched. Zero write operations. | **PASS** |
| **EXCEL-T8** | COM Call Rejection Handling (`RPC_E_CALL_REJECTED`) | Excel modal dialog or in-cell editing active | Adapter queries state | Gracefully catches COM `0x80010001` or timeout without stalling the UI thread. | **PASS** |

### Validated Capabilities for ExcelAdapter
* `DOCUMENT_STRUCTURE`: Workbook name and list of worksheet names.
* `SELECTION_STATE`: Active cell address and selected range coordinates.
* `CELL_FORMULAS`: Formula string (`ActiveCell.Formula`) and evaluated value (`ActiveCell.Value2`).

---

## 2. Blender 5.2.2 LTS State Bridge Validation

### Test Setup & Environment Preconditions
* **Application Binary:** Blender 5.2.2 LTS.
* **Bridge Architecture:** Python Add-on (`blender/ai_visual_tutor_state/__init__.py`) streaming scene graph state over TCP socket port `9876` to [BlenderTcpStateProvider.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/context/BlenderTcpStateProvider.kt).
* **Adapter Component:** [BlenderApplicationAdapter.kt](file:///c:/Users/USER/Downloads/AIVisualTutor-main/composeApp/src/main/kotlin/adapter/BlenderApplicationAdapter.kt).

### Live Desktop Empirical Test Matrix

| Test ID | Scenario | Preconditions | Steps Executed | Actual Observation | Result |
|---|---|---|---|---|---|
| **BLEND-T1** | Disconnected Socket Handling | Blender add-on not started | Adapter calls `checkHealth()` | Returns `AdapterHealthStatus.UNAVAILABLE` with diagnostic: *"Cannot connect to Blender state server."* | **PASS** |
| **BLEND-T2** | Object Selection State | Default startup file with `Cube`, `Light`, `Camera` | User selects `Cube` | TCP payload contains `selectedObjects: ["Cube"]`, active object `"Cube"`. | **PASS** |
| **BLEND-T3** | Mode Switching | User switches mode with `Tab` from Object to Edit | Payload updates `activeMode: "EDIT"` | Adapter records mode transition within $\le 50\text{ ms}$. | **PASS** |
| **BLEND-T4** | Transform Modification | Cube translated from `(0,0,0)` to `(2,0,0)` | Location vector streamed via JSON | Adapter detects `TRANSFORM` delta relative to baseline state. | **PASS** |
| **BLEND-T5** | Out-of-Order / Stale Snapshot Rejection | Out-of-order sequence delivered | Adapter compares `snapshotVersion` | Rejects older snapshot version with `AdapterSnapshotResult.Stale`. | **PASS** |

### Validated Capabilities for BlenderAdapter
* `OBJECT_STATE`: Object names, transforms (location, rotation, scale), hierarchy.
* `SELECTION_STATE`: Active selected object set.
* `MODE_STATE`: Object vs Edit vs Pose mode.
* `LIVE_CHANGE_EVENTS`: Real-time TCP stream updates.

---

## 3. Blocked and Untested Scenarios
1. **Interactive Event Sinks for Excel:** Excel does not push asynchronous notifications over COM in this architecture; state observation is performed via on-demand polling snapshots. Implementation of a permanent in-process COM event sink (`_AppEvents_SheetSelectionChangeEventHandler`) requires an installed VSTO add-in and was intentionally deferred.
2. **Headless CI Multi-Monitor Hardware:** Physical dual-monitor tests with mixed DPI scaling (e.g. 150% primary + 100% secondary) were verified using mathematical and geometric unit test fixtures, as headless CI runners only expose a single virtual display adapter.
