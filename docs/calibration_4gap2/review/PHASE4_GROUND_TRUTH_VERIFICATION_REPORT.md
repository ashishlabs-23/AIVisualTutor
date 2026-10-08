# PHASE 4 — MANUAL GROUND-TRUTH VERIFICATION REPORT
## Cases C02–C18 | READ-ONLY PASS
## NO FILES MODIFIED — STOP BEFORE WRITE

---

## 🔬 KEY CROSS-CASE FINDINGS (from byte-level and pixel analysis)

| Observation | Evidence |
|---|---|
| **C07 == C18 (byte-identical)** | Same 2403-byte PNG. Identical Paint canvas crop for two different categories and target descriptions. |
| **C13 == C17 (byte-identical)** | Same 160-byte PNG. Both are 200×28 solid Excel-green rectangles (`RGB(33,115,70)`). Zero UI content. |
| **C04 is sub-region of C14** | C04 (500×120) is pixel-perfect sub-region at `y=20..140` within C14 (550×200). |
| **C05 is sub-region of C12** | C05 (500×50) is pixel-perfect sub-region at `y=30..80, x=20..520` within C12 (600×200). |
| **C15 is 78% Excel green** | 2,730/3,500 pixels are Excel green (`RGB(33,115,70)`). Inconsistent with Calculator UI. Possible mis-capture. |
| **C02 & C11 are Excel green** | C02 (100×30) is 97% green; C11 (120×40) is 93.5% green. Both capture Excel ribbon/cell areas. |
| **C16 shows folder icons** | Category is `no_access_desktop_empty`, but crop clearly contains multiple folder icons and labels. |

---

## ⚠️ SCORING RULE STATUS

> **CRITICAL POLICY DECISION:**
> The repository calibration framework does **NOT** specify whether `decision=ABSTAIN` against a visible, valid target scores as `FALSE` or `NOT_SCORABLE`.
>
> In this report, all 17 cases are proposed as `PROPOSED_GROUND_TRUTH_MATCH: NOT_SCORABLE` per the instruction:
> *"If the case cannot be scored under the project's rules, say NOT_SCORABLE. Do not make unsupported claims."*

---

## CASE-BY-CASE VERIFICATION TABLE (C02–C18)

| Case | App | Recorded Category | PNG Crop Size | Human Visual Ground Truth | Proposed Label | Proposed Match | Key Evidence & Notes |
|:---:|:---:|:---|:---:|:---|:---|:---:|:---|
| **C02** | Excel | `native_uia_excel_ribbon` | 100×30 | Green Excel ribbon band | `Excel_ribbon_green_area` | `NOT_SCORABLE` | 97% Excel green. No text/buttons visible. |
| **C03** | Calculator | `native_uia_calculator` | 80×50 | `CE` (Clear Entry) button | `CE_button_Calculator` | `NOT_SCORABLE` | Win11 Calc button. OCR text `cE`. Not an Enter button. |
| **C04** | Notepad | `text_ocr_notepad` | 500×120 | Highlighted calibration paragraph | `highlighted_paragraph_about_calibration_evidence_for_OCR_targets` | `NOT_SCORABLE` | Colored highlights (blue, teal, orange). Sub-region of C14. |
| **C05** | Notepad | `text_ocr_notepad_header` | 500×50 | Header line text | `CALIBRATION_SECTION_HEADER` | `NOT_SCORABLE` | OCR `LIBRATION SECTION HEADER`. Sub-region of C12. |
| **C06** | Paint | `icon_paint_toolbar` | 48×48 | Paint toolbar control (`Edit`) | `Edit_control_Paint_toolbar` | `NOT_SCORABLE` | 48×48 crop. OCR `Ed`. No "Add menu" in Paint. |
| **C07** | Paint | `custom_render_paint_canvas` | 300×200 | Blank white Paint canvas | `blank_white_Paint_canvas` | `NOT_SCORABLE` | 98.1% white. Byte-identical to C18. |
| **C08** | Paint | `no_structure_paint_canvas` | 200×150 | Blank white Paint canvas below Colours | `blank_white_Paint_canvas_below_Colours_panel` | `NOT_SCORABLE` | 98.8% white canvas. "Colours" panel edge at border. |
| **C09** | Explorer | `uia_explorer_item` *(conflict)* | 300×80 | Explorer toolbar (`New` button area) | `New_control_File_Explorer_toolbar` | `NOT_SCORABLE` | Category conflict: no list item visible. Toolbar strip. |
| **C10** | Notepad | `native_uia_notepad_menu` | 50×28 | Notepad menu bar (`File`) | `File_menu_Notepad` | `NOT_SCORABLE` | 50×28 crop. Orange/dark glyphs match "File". No "Add menu". |
| **C11** | Excel | `conflict_excel_vs_ocr` | 120×40 | Green cell with partial white text | `green_Excel_cell_with_text_ending_in_covered_Files` | `NOT_SCORABLE` | 93.5% Excel green. OCR `eS`. "Select Mesh" not in Excel. |
| **C12** | Notepad | `conflict_notepad_menu_vs_body` | 600×200 | Menu bar (`File`) + Header + Paragraph | `File_menu_Notepad` | `NOT_SCORABLE` | Large Notepad window. File menu clearly visible. Contains C05, C04. |
| **C13** | Excel | `native_uia_excel_formula` | 200×28 | Solid green rectangle (uninformative) | `NO_VALID_TARGET` | `NOT_SCORABLE` | 100% single color `RGB(33,115,70)`. Byte-identical to C17. |
| **C14** | Notepad | `text_ocr_dense_notepad` | 550×200 | Header + highlighted paragraph | `CALIBRATION_SECTION_with_header_and_highlighted_paragraph` | `NOT_SCORABLE` | High-confidence OCR (0.94). Both header and body clearly visible. |
| **C15** | Calc *(err)* | `icon_calc_button` | 70×50 | Excel green ribbon band *(mis-capture)* | `NO_VALID_TARGET` | `NOT_SCORABLE` | 78% Excel green. Calc button absent. Data integrity issue. |
| **C16** | Explorer | `no_access_desktop_empty` *(conflict)* | 200×150 | Explorer folders (Cognitive, demo...) | `demo_folder_in_File_Explorer` | `NOT_SCORABLE` | Category conflict: NOT empty. OCR reads `deme` (demo). |
| **C17** | Excel | `native_uia_excel_title` | 200×28 | Solid green rectangle (uninformative) | `Excel_green_title_bar_area` | `NOT_SCORABLE` | 100% single color `RGB(33,115,70)`. Byte-identical to C13. |
| **C18** | Paint (proxy) | `custom_render_unavailable_blender` | 300×200 | Blank white Paint canvas (proxy) | `blank_white_Paint_canvas_proxy` | `NOT_SCORABLE` | Byte-identical to C07. Paint proxy only; no Blender viewport. |

---

## 📊 BATCH SUMMARY: CASES C02–C18

- **Total Cases Reviewed:** 17
- **Valid PNG Images Present:** 17 / 17
- **Target Visibility Breakdown:**
  - **CLEAR (7):** C03, C04, C05, C07, C08, C12, C14
  - **UNCLEAR (5):** C02, C06, C09, C10, C16
  - **ABSENT / UNUSABLE (5):** C11 (partial text), C13 (solid color), C15 (mis-capture), C17 (solid color), C18 (Blender absent)
- **Proposed Ground Truth Matches:** All 17 `NOT_SCORABLE` (ABSTAIN rule not defined in repository)
- **Special Data Issues Flagged:**
  - `C07` == `C18` (byte-identical Paint canvas)
  - `C13` == `C17` (byte-identical solid Excel green)
  - `C15` mis-capture (78% Excel green for Calculator case)
  - `C09` & `C16` category conflicts with visible UI elements
  - `C18` Paint proxy validity boundary
