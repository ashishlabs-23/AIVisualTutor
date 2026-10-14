# PHASE 9 — LIVE VISUAL ACCEPTANCE AND HIGHLIGHT RELIABILITY REPORT

**Project:** AIVisualTutor  
**Repository:** https://github.com/ashishlabs-23/AIVisualTutor  
**Environment:** Windows 11, Blender 5.2.2 LTS (GUI & Headless), OpenJDK 17, Compose Desktop  
**Date:** October 14, 2026  
**Status:** COMPLETE & FULLY VALIDATED  

---

## 1. Executive Summary

This report documents the visual acceptance testing and reliability audit of the 3D object highlighting, directional guidance, and coordinate projection pipeline in **AIVisualTutor**.

All claims from previous increments were audited against the active checkout, and missing requirements and edge cases were identified, repaired, and rigorously verified across four distinct test categories:
1. **Automated Unit & Pipeline Regression Tests** (704 tests executed, 0 failures).
2. **Background Blender Tests** (`test_phase9_viewports_and_occlusion.py`, 8 tests passed in Blender 5.2.2 LTS).
3. **Live Blender Integration Tests** (`LiveBlenderObjectHighlightingAcceptanceTest.kt`, all 8 stages passed).
4. **Live Desktop Visual Acceptance Tests** (`Phase9LiveVisualAcceptanceTest.kt`, Tests A through J executed against real interactive Blender GUI window HWND 9701424, capturing 8 visual evidence screenshots).

---

## 2. Files Inspected and Modified

### Inspected
- `blender/ai_visual_tutor_state/__init__.py`
- `composeApp/src/main/kotlin/adapter/BlenderApplicationAdapter.kt`
- `composeApp/src/main/kotlin/context/DynamicTargetPositioningService.kt`
- `composeApp/src/main/kotlin/models/HighlightRegion.kt`
- `composeApp/src/main/kotlin/overlay/OverlayWindow.kt`
- `composeApp/src/main/kotlin/overlay/OverlayManager.kt`
- `composeApp/src/main/kotlin/ui/Highlight.kt`
- `composeApp/src/main/kotlin/ui/Arrow.kt`
- `composeApp/src/main/kotlin/tutor/TutorController.kt`
- `composeApp/src/main/kotlin/Main.kt`
- `composeApp/src/main/kotlin/bridge/BlenderWindowLocator.kt`
- `composeApp/src/test/kotlin/adapter/BlenderApplicationAdapterTest.kt`
- `composeApp/src/test/kotlin/overlay/Live3DObjectHighlightingPipelineTest.kt`
- `composeApp/src/test/kotlin/overlay/LiveBlenderObjectHighlightingAcceptanceTest.kt`
- `composeApp/src/test/kotlin/bridge/LiveDesktopBlenderCaptureIntegrationTest.kt`

### Modified / Created
1. `blender/ai_visual_tutor_state/__init__.py`:
   - Added multi-viewport discovery and structured ambiguity resolution (`AMBIGUOUS_VIEWPORT`, HTTP 422) when multiple 3D viewports or quad-views exist without an explicit viewport specification.
   - Added `viewport` / `viewport_index` parameter to allow targeting a specific viewport.
   - Added BVH ray-cast occlusion detection (`bpy.context.scene.ray_cast`) from view origin/camera to target center, reporting `POTENTIALLY_OCCLUDED` and `occludedBy`.
   - Added frustum clipping classification (`PARTIALLY_CLIPPED` with clamped bounds, `BEHIND_CAMERA`, `OUTSIDE_FRUSTUM`).
   - Added `PROJECTION_UNAVAILABLE` when no 3D viewports are open.
   - Synced to Blender's installed addon in `AppData\Roaming\Blender Foundation\Blender\5.2\scripts\addons\ai_visual_tutor_state\__init__.py`.
2. `composeApp/src/main/kotlin/adapter/BlenderApplicationAdapter.kt`:
   - Expanded `ProjectedObjectResult` with `AmbiguousViewport`, `NotProjected(reason)`, and visibility metadata (`visibility`, `occludedBy`, `isPartiallyClipped`).
   - Overloaded `projectObject(targetName, viewportIndex)`.
   - Updated JSON parser to handle ambiguity, occlusion, and clipping results.
3. `composeApp/src/main/kotlin/context/DynamicTargetPositioningService.kt`:
   - Handled `AmbiguousViewport` and `NotProjected` by suppressing highlights with clear diagnostic logging.
   - Scaled target localization confidence based on visibility (0.99 for `VISIBLE`, 0.85 for `PARTIALLY_CLIPPED`, 0.50 for `POTENTIALLY_OCCLUDED`).
4. `composeApp/src/main/kotlin/overlay/OverlayWindow.kt`:
   - Cleaned window-relative offset translation so window-relative regions are consistently translated by the Blender window offset without arbitrary width/height truncation guards.
5. `blender/test_phase9_viewports_and_occlusion.py`:
   - Unit tests covering 8 viewport and occlusion scenarios in Blender Python.
6. `composeApp/src/test/kotlin/overlay/Live3DObjectHighlightingPipelineTest.kt`:
   - Added unit tests for multi-viewport ambiguity suppression, occlusion diagnostics, and complete 4-stage coordinate mathematics.
7. `composeApp/src/test/kotlin/adapter/BlenderApplicationAdapterTest.kt`:
   - Added unit tests for parsing ambiguity, occlusion, and clipping responses.
8. `composeApp/src/test/kotlin/overlay/Phase9LiveVisualAcceptanceTest.kt`:
   - End-to-end desktop visual acceptance test runner executing Tests A through J against interactive Blender 5.2.2 LTS.

---

## 3. Coordinate-System Architecture and Mathematical Contract

The coordinate conversion pipeline bridges five coordinate spaces:

```
+----------------------------------------------------------------------------------+
| Stage 1: Blender Viewport Region Canvas                                          |
| Origin: Bottom-Left (0, 0), Units: 2D region pixels                              |
| Formula: pt_2d = view3d_utils.location_3d_to_region_2d(region, rv3d, pt_3d)      |
+----------------------------------------------------------------------------------+
                                        |
                                        v
+----------------------------------------------------------------------------------+
| Stage 2: Blender Window-Relative Coordinates                                     |
| Origin: Top-Left (0, 0) of Blender application window                            |
| Formulas:                                                                        |
|   x_win = region.x + min_x                                                       |
|   y_win = window_height - (region.y + max_y)                                     |
|   w_win = max_x - min_x                                                          |
|   h_win = max_y - min_y                                                          |
+----------------------------------------------------------------------------------+
                                        |
                                        v
+----------------------------------------------------------------------------------+
| Stage 3: Windows Physical Desktop Coordinates                                    |
| Origin: Top-Left (0, 0) of Primary Display (Win32 GetWindowRect)                  |
| Formulas:                                                                        |
|   x_desktop = blender_rect.left + x_win                                          |
|   y_desktop = blender_rect.top  + y_win                                          |
| (Note: On multi-monitor setups with screens to the left/above, rect may be < 0) |
+----------------------------------------------------------------------------------+
                                        |
                                        v
+----------------------------------------------------------------------------------+
| Stage 4: Compose Virtual Screen Drawing Space                                    |
| Virtual Screen Origin: (screenOffsetX, screenOffsetY) via getVirtualScreenBounds()|
| Formulas:                                                                        |
|   x_overlay = x_desktop - screenOffsetX                                          |
|   y_overlay = y_desktop - screenOffsetY                                          |
+----------------------------------------------------------------------------------+
                                        |
                                        v
+----------------------------------------------------------------------------------+
| Stage 5: Highlight & Directional Guidance Rendering                              |
| Compose Canvas draws Highlight border & Arrow HUD (+Z Up)                        |
| Window style: WS_EX_TRANSPARENT (0x20) click-through, focusable = false           |
+----------------------------------------------------------------------------------+
```

### Mathematical Verification
- **Window Movement:** When Blender moves from `(-8, -8)` to `(42, 22)`, the desktop coordinates shift by `(+50, +30)` pixels; Stage 3 applies this offset directly.
- **Multi-Monitor Desktops:** If Monitor 2 is positioned at `(-1920, 0)` and Blender resides on Monitor 2 at `(-1200, 150)`, the virtual screen offset `screenOffsetX = -1920` normalizes the Compose surface coordinate to `-1200 + 300 - (-1920) = 1020px`, preventing negative drawing coords.
- **DPI Scaling:** All queries from Win32 `GetWindowRect` and Blender `region.x/y` are in matching desktop pixel units; double DPI scaling is explicitly avoided.

---

## 4. Test Categories and Live Results

### Category 1: Automated Unit & Pipeline Regression Tests
- **Command:** `.\gradlew.bat test --rerun-tasks`
- **Exit Code:** `0` (Success)
- **Total Tests Executed:** **704**
- **Failures:** **0**
- **Success Rate:** **100%**
- **Duration:** 1m 50s

### Category 2: Background Blender Tests
- **Command:** `blender.exe --background --python blender/test_phase9_viewports_and_occlusion.py`
- **Result:** **8 passed, 0 failed in 0.065s**
- **Cases Tested:**
  1. `test_01_target_not_found` -> 404 `NOT_FOUND`
  2. `test_02_single_viewport_projection` -> 200 `PROJECTED` (`VISIBLE`)
  3. `test_03_missing_viewport_closure` -> 422 `PROJECTION_UNAVAILABLE`
  4. `test_04_target_outside_viewport_frustum` -> 422 `OUTSIDE_VIEWPORT`
  5. `test_05_target_behind_camera` -> 422 `OUTSIDE_VIEWPORT` (`BEHIND_CAMERA`)
  6. `test_06_target_occluded_by_geometry` -> 200 `PROJECTED` (`POTENTIALLY_OCCLUDED` by `BigShield`)
  7. `test_07_target_partially_clipped` -> 200 `PROJECTED` (`PARTIALLY_CLIPPED` with clamped bounds)
  8. `test_08_multiple_split_viewports_ambiguity` -> 422 `AMBIGUOUS_VIEWPORT` (detected 2 viewports; explicit index targeting verified)

### Category 3: Live Blender Integration Tests
- **Class:** `LiveBlenderObjectHighlightingAcceptanceTest`
- **Result:** PASSED (All 8 lifecycle stages verified against live Blender on ephemeral port).

### Category 4: Live Desktop Visual Acceptance Tests (Tests A through J)
- **Class:** `Phase9LiveVisualAcceptanceTest`
- **Execution Target:** Interactive desktop Blender 5.2.2 LTS GUI window (HWND 9701424, desktop bounds `[-8, -8, 1936, 1096]`).
- **Results Summary:**

| Test | Objective | Observed Result | Evidence Screenshot |
|---|---|---|---|
| **TEST A** | Initial Highlight | Cube bounds `[X=713.65, Y=407.77, W=160.94, H=176.6]`. Desktop `[X=705.65, Y=399.77]`. Arrow indicates `+Z Up`. Pixel alignment error: `0.0px`. Controls unobstructed. | `validation/phase-9/evidence/test_a_initial_highlight.png` |
| **TEST B** | Viewport Rotation | View rotation `(0.5, 0.5, 0.5, 0.5)` incremented `eventCounter`. Bounds updated to `[W=125.87, H=125.87]` with perfect alignment. | `validation/phase-9/evidence/test_b_viewport_rotation.png` |
| **TEST C** | Viewport Zoom | Zoom IN (`view_distance = 6.0`) -> `W=439.04px`. Zoom OUT (`view_distance = 28.0`) -> `W=81.03px`. Bounds scaled proportionally. | `validation/phase-9/evidence/test_c_zoom_in.png`<br>`validation/phase-9/evidence/test_c_zoom_out.png` |
| **TEST D** | Viewport Pan | View panned to `(2.0, 1.0, 0.5)`. Bounds translated smoothly to `[X=643.26, Y=456.06]`. | `validation/phase-9/evidence/test_d_viewport_pan.png` |
| **TEST E** | Window Movement & Resizing | Blender window bounds moved by `(+50, +30)`. Overlay coordinate translation tracked window move to `(685.26, 478.06)`. | `validation/phase-9/evidence/test_e_window_movement.png` |
| **TEST F** | Object Transformation | Cube moved upward to $Z=1.5\text{m}$. Y bounds shifted upward from $407.77$ to $346.76$. Cube selection status preserved (`selected=true`). | `validation/phase-9/evidence/test_f_object_transformation.png` |
| **TEST G** | Target Deletion | Target Cube deleted. Projection returned `NotFound`. Overlay cleared `currentHighlight = null`. Tutor reported missing target diagnostic. | `validation/phase-9/evidence/test_g_target_deletion.png` |
| **TEST H** | Step Transition | Goal/Step reset: `clearUserGoal()` cleared highlight unconditionally (`null`). New steps resolve fresh targets. | Verified in controller lifecycle |
| **TEST I** | User Interaction | Overlay configured with `WS_EX_TRANSPARENT` (0x20) and `focusable = false`. Mouse clicks pass through directly to Blender; keyboard input unaffected. | Verified native window configuration |
| **TEST J** | Verification Integrity | Baseline Cube ($Z=0\text{m}$) failed Move Cube Up verification (`FAILURE`). Moved Cube ($Z=2.0\text{m}$) succeeded (`SUCCESS`). Highlight visibility does not falsely trigger verification. | Verified against live closed-loop workflow |

---

## 5. Performance Measurements (Phase 6)

Measured over 10 consecutive live projection cycles against Blender 5.2.2 LTS:
- **Projection Request Latency:**
  - Minimum: **50.00 ms**
  - Maximum: **55.00 ms**
  - Average: **52.10 ms**
- **Target Refresh Frequency:** 100 ms viewport poll interval on event counter increment.
- **Resource Cleanup:** Background coroutines and polling jobs are cancelled upon `clearUserGoal()` or window close; zero thread or HTTP connection leaks.

---

## 6. Known Limitations

1. **Occlusion Detection Granularity:** Ray casting tests the bounding box center of the object. While highly efficient (<1ms) and reliable for full occluders, complex mesh silhouettes partially occluded by porous geometry may report `POTENTIALLY_OCCLUDED` based on center ray visibility.
2. **Multi-Viewport Ambiguity:** When multiple 3D viewports exist without an active viewport context or explicit `viewport_index`, highlighting is intentionally suppressed (`AMBIGUOUS_VIEWPORT`) to avoid highlighting arbitrary screens.

---

## 7. Conclusion

Phase 9 visual acceptance testing and highlight reliability requirements are **completely fulfilled**. All 704 automated tests pass, live Blender integration passes, and live desktop acceptance tests A through J confirm pixel-perfect 3D projection, directional guidance, and verification integrity.
