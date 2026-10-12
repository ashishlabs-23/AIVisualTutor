# Ground-Truth Annotation Guidelines and Dataset Schema (v2)

## 1. Objective and Boundary

The `ground-truth-v2` evaluation set provides an independently reviewable evaluation corpus for evaluating the five grounding modes in AIVisualTutor:
- `UIA_ONLY`: Windows UI Automation accessibility tree queries
- `OCR_ONLY`: Tesseract OCR text and bounding box extraction
- `UIA_OCR`: Multi-source non-visual grounding
- `VISION_ONLY`: Visual grounding model boundary
- `ADAPTIVE`: Evidence-adaptive routing and candidate fusion

The dataset separates real-world screenshot observations from synthetic regression fixtures. No evaluation metric is claimed without verified ground-truth correspondence.

## 2. Dataset Schema (RFC-4180 CSV)

Each row in `cases.csv` specifies:

| Column | Type | Description |
|---|---|---|
| `caseId` | String | Unique case identifier across the dataset |
| `screenshotPath` | Relative Path | Relative path to the screenshot image file within the dataset directory |
| `targetDescription` | String | User-facing instruction or query text (e.g. "Continue", "Cube") |
| `expectedTargetIdentity` | String (optional) | Semantic identity/label expected from provider observation |
| `expectedGeometry` | Enum (`BOX`, `POINT`, `ABSENT`) | Type of geometric ground-truth annotation |
| `x`, `y`, `width`, `height` | Float | Bounding box coordinates in screenshot pixels (top-left origin). Blank for `ABSENT`. |
| `coordinateSpace` | Enum | Coordinate space convention: `SCREENSHOT_PIXELS` |
| `expectedAbsent` | Boolean (`true` or `false`) | `true` if target is absent in screenshot, `false` otherwise |
| `notes` | String (optional) | Annotator notes explaining target properties, visual features, and boundary |
| `windowHandle` | Long (optional) | Window handle (HWND) for live UIA interaction |
| `pid` | Long (optional) | Process ID of target application |
| `applicationName` | String (optional) | Name of application (e.g. `Blender`, `Calculator`, `Notepad`, `Excel`) |
| `processName` | String (optional) | Process executable name (e.g. `blender.exe`, `notepad.exe`) |
| `windowX`, `windowY`, `windowWidth`, `windowHeight` | Int (optional) | Desktop window bounds |
| `selectedX`, `selectedY`, `selectedWidth`, `selectedHeight` | Int (optional) | Selected desktop region rectangle |
| `textX`, `textY`, `textWidth`, `textHeight` | Float (optional) | Text-region specific ink bounds (sub-box within full button outline) |

## 3. Case Diversity and Coverage

The v2 dataset covers:
1. **Positive Target Detection**: High-contrast buttons, menu items, text labels, and spreadsheet cells.
2. **Absent Targets**: Explicit negative cases (`expectedAbsent=true`) to evaluate false acceptance rates and safe abstention (`ABSTAIN / NO_MATCH`).
3. **Application Diversity**: Standard Windows controls (WinForms, Calculator), text editor (Notepad), complex 3D suite (Blender), and spreadsheet (Excel).
4. **Control Types**: Standard Buttons, Tree Items, Dense Text, Grid Cells, and Icon-only Tools.
5. **OCR-Friendly vs. OCR-Unfriendly**: Textual controls vs. graphical icons without text.
6. **UIA-Accessible vs. Custom OpenGL/DirectX**: Native Win32/UIA elements vs. custom-rendered viewports.
7. **Spatial Granularity**: Full control bounds vs. contained text ink bounds (for evaluating IoU containment and smaller-box overlap).

## 4. Evaluation Execution

Run the repeatable evaluation runner using:

```powershell
.\gradlew.bat :composeApp:runPhase5Evaluation `
  -Paivt.phase5.annotations="docs\audit_evidence\phase5\ground-truth-v2\cases.csv" `
  -Paivt.phase5.report="docs\audit_evidence\phase5\ground-truth-v2\comparison-results.json"
```
