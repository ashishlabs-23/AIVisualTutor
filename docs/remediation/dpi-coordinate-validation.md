# AI Visual Tutor: DPI and Coordinate Normalization Validation
**Document:** `docs/remediation/dpi-coordinate-validation.md`  
**Phase:** Remediation and Feature Enhancement (NOT Phase 9)  
**Baseline Git Commit:** `227fc09f7238b5d7b76c8b7e3f3230e0a2e426fc`  

---

## 1. Coordinate Space Contract

The AI Visual Tutor pipeline processes visual, automation, and rendering coordinates across four explicitly distinguished coordinate spaces:

```
+----------------------------------------------------------------------------------+
| PHYSICAL SCREEN SPACE (Win32 API, raw screenshot rasters, UIA BoundingRectangle) |
+----------------------------------------------------------------------------------+
                                        │
           DpiCoordinateNormalizer.physicalToLogical(rect)
           [ scaled by 1 / monitor.scaleX, 1 / monitor.scaleY ]
                                        ▼
+----------------------------------------------------------------------------------+
| LOGICAL DESKTOP SPACE (Java AWT, Compose Desktop layout bounds, HighlightRegion)  |
+----------------------------------------------------------------------------------+
                                        │
           RegionFocusService.createFocusedCrop(screenshot, targetBounds)
           [ contextual margin padding, quadrant clipping, clamp ]
                                        ▼
+----------------------------------------------------------------------------------+
| CROP PIXEL SPACE (Sub-image pixels sent to VLM / Multimodal Vision Providers)     |
+----------------------------------------------------------------------------------+
                                        │
           CropCoordinateTransform.cropToScreen(cropX, cropY)
           [ scaled and offset relative to cropScreenOriginX, cropScreenOriginY ]
                                        ▼
+----------------------------------------------------------------------------------+
| TARGET VALIDATION & SCREEN OVERLAY POSITIONING                                  |
+----------------------------------------------------------------------------------+
```

### Definitions:
1. **`PHYSICAL_SCREEN`**:
   * Units: Hardware physical pixels.
   * Coordinate Origin: $(0, 0)$ at the top-left of the primary monitor. Monitors positioned to the left or above have negative physical origins.
   * Producers: `PowerShellUiAutomationClient`, `Robot.createMultiResolutionScreenCapture`, `User32.GetWindowRect`.
2. **`LOGICAL_DESKTOP`**:
   * Units: Device Independent Pixels (DIPs).
   * Coordinate Origin: $(0, 0)$ at the primary monitor's AWT origin.
   * Producers: Java AWT `GraphicsConfiguration.bounds`, Compose Desktop layout (`Modifier.offset`, `Modifier.size`), `HighlightRegion`.
3. **`CROP_PIXELS`**:
   * Units: Pixel offsets $(0 \dots \text{width}-1, 0 \dots \text{height}-1)$ within an extracted sub-image `BufferedImage`.
   * Producers: `RegionFocusService`, VLM bounding box annotations.
4. **`NORMALIZED`**:
   * Units: Unit intervals $[0.0 \dots 1.0]$.
   * Producers: Model normalized predictions (Gemini $y_{min}, x_{min}, y_{max}, x_{max}$ normalized by $1000$).

---

## 2. Mathematical Conversion Rules

### Physical to Logical Conversion:
$$\text{LogicalX} = \text{Monitor.logicalBounds.x} + \left\lfloor \frac{\text{PhysicalX} - \text{Monitor.physicalBounds.x}}{\text{Monitor.scaleX}} \right\rceil$$
$$\text{LogicalY} = \text{Monitor.logicalBounds.y} + \left\lfloor \frac{\text{PhysicalY} - \text{Monitor.physicalBounds.y}}{\text{Monitor.scaleY}} \right\rceil$$
$$\text{LogicalW} = \max\left(1, \left\lfloor \frac{\text{PhysicalW}}{\text{Monitor.scaleX}} \right\rceil\right), \quad \text{LogicalH} = \max\left(1, \left\lfloor \frac{\text{PhysicalH}}{\text{Monitor.scaleY}} \right\rceil\right)$$

### Logical to Physical Conversion:
$$\text{PhysicalX} = \text{Monitor.physicalBounds.x} + \left\lfloor (\text{LogicalX} - \text{Monitor.logicalBounds.x}) \times \text{Monitor.scaleX} \right\rceil$$
$$\text{PhysicalY} = \text{Monitor.physicalBounds.y} + \left\lfloor (\text{LogicalY} - \text{Monitor.logicalBounds.y}) \times \text{Monitor.scaleY} \right\rceil$$

### Crop to Screen Conversion:
$$\text{ScreenX} = (\text{CropX} \times \text{scaleFactorX}) + \text{screenOffsetX}$$
$$\text{ScreenY} = (\text{CropY} \times \text{scaleFactorY}) + \text{screenOffsetY}$$

---

## 3. Supported Scenarios & Verification Evidence

| Scenario | Behavior & Guarantee | Validation Status |
|---|---|---|
| **Standard 100% Display ($1.0\times$)** | Exact 1:1 mapping; physical coordinates equal logical DIPs. | `VERIFIED` via `DpiCoordinateNormalizerTest.identity transform` |
| **High-DPI 125% Display ($1.25\times$)** | Physical 500,250 maps to logical 400,200. Round-trip transformation is lossless. | `VERIFIED` via `DpiCoordinateNormalizerTest.scale 1_25` |
| **High-DPI 150% Display ($1.5\times$)** | Physical 300,150 maps to logical 200,100. Round-trip transformation is lossless. | `VERIFIED` via `DpiCoordinateNormalizerTest.scale 1_5` |
| **High-DPI 200% Display ($2.0\times$)** | Physical 400,200 maps to logical 200,100. Round-trip transformation is lossless. | `VERIFIED` via `DpiCoordinateNormalizerTest.scale 2_0` |
| **Secondary Monitor with Negative Origin** | Left monitor at physical $(-1920, 0)$ mapped to negative logical coordinates without clipping. | `VERIFIED` via `DpiCoordinateNormalizerTest.negative origin` |
| **Virtual Desktop Clamping** | Overhanging or off-screen candidate bounds clamped to desktop perimeter. | `VERIFIED` via `DpiCoordinateNormalizerTest.clamp to virtual desktop` |
| **RegionFocus Coordinate Round-Trip** | Sub-crop coordinates bidirectional mapping verified at 0.0 px error. | `VERIFIED` via `RegionFocusServiceTest.bidirectional transform` |

---

## 4. Known Limitations
1. **Windows Mixed-DPI Dragging Transitions:** When an application window spans the boundary between a $100\%$ display and a $125\%$ display simultaneously, Windows OS renders the window using the DPI of the monitor containing the majority of the window's area. If an element straddles the seam, minor 1–2 pixel rounding artifacts may occur at the boundary.
2. **Dynamic DPI Switching at Runtime:** If display scale settings are modified in Windows Settings while AI Visual Tutor is running, Java AWT caches the initial graphics configuration until process restart.
