# Phase 1: Tutor UI & Live Visual Overlay Foundation

Phase 1 provides the complete user interface foundation, tutor workflow navigation, and live on-screen visual overlay for **AI Visual Tutor**.

---

## 1. Architectural Overview

Phase 1 establishes a clean separation of concerns across presentation, state ownership, data models, and overlay compositing:

```text
┌────────────────────────────────────────────────────────┐
│                        Main.kt                         │
│               (Window Lifecycle & Routing)              │
└───────────────┬────────────────────────┬───────────────┘
                │                        │
                ▼                        ▼
     ┌──────────────────────┐ ┌──────────────────────┐
     │   TutorController    │ │    OverlayManager    │
     │  (State Ownership)   │ │  (Overlay Visibility)│
     └──────────┬───────────┘ └──────────┬───────────┘
                │                        │
         ┌──────┴──────┐          ┌──────┴──────┐
         ▼             ▼          ▼             ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
    │TutorPanel│ │TutorDock │ │Overlay   │ │Close     │
    │ (Full UI)│ │(Collapsed│ │Window    │ │Control   │
    └──────────┘ └──────────┘ └──────────┘ └──────────┘
```

---

## 2. Key Components

### A. State Management & Navigation (`tutor/`)
* **`TutorController.kt`**: The single source of truth for the immutable `TutorState`. UI composables read state and dispatch actions (e.g., `nextStep()`, `previousStep()`, `selectApplication()`, `togglePause()`).
* **`MockTutorData.kt`**: Provides hardcoded multi-step workflows for 3 supported applications:
  * **Blender** (5 steps: Add Menu $\rightarrow$ Mesh Category $\rightarrow$ Cube $\rightarrow$ Move Into Viewport $\rightarrow$ Confirm)
  * **PDF Viewer** (4 steps: Find Section $\rightarrow$ Read Text $\rightarrow$ Find Table $\rightarrow$ Next Page)
  * **Microsoft Excel** (4 steps: Select Cell $\rightarrow$ Enter Value $\rightarrow$ Press Enter $\rightarrow$ Select Result)

### B. UI Presentation (`ui/`)
* **`TutorPanel.kt`**: The primary full panel featuring:
  * Draggable title bar (`WindowDraggableArea`).
  * Application selector (`ApplicationSelector.kt`).
  * Current step instruction card (`InstructionCard.kt`).
  * Step navigation buttons with disabled visual states at step 0 and step $N-1$ (`StepNavigationButtons`).
  * Screen assistance indicator dot and Pause/Resume toggle (`TutorControls.kt`).
  * Scrollable inner container (`Modifier.verticalScroll`) to ensure responsiveness across different display and font scaling factors.
* **`TutorDock.kt`**: A compact floating pill ("AI TUTOR") displayed when the main panel is collapsed. Clicking it restores the full panel.

### C. Live Visual Overlay (`overlay/`)
The live tutor overlay renders step labels, highlight boxes, and directional arrows directly over whatever desktop application the user is working in.

```text
    Blender / Notepad / Chrome / VS Code (Underlying App)
                     ↓  (Visible & Usable)
         Transparent Layered Canvas (Skiko per-pixel alpha)
                     ↓
         Step Label / Directional Arrow / Highlight Box
```

#### Transparency & Click-Through Implementation
1. **Per-Pixel Alpha Compositing**:
   * The Compose `Window` is initialized with `transparent = true`, `undecorated = true`, `alwaysOnTop = true`, and `focusable = false`.
   * Compose Desktop and Skiko render directly to a Windows DWM per-pixel alpha surface.
   * `OverlayWindowContent` uses a transparent `Box(modifier = Modifier.fillMaxSize())` without any background color brush. Pixels without tutor graphics have `alpha = 0.0f` and are completely transparent.
2. **Native Win32 Click-Through**:
   * After the window handle (`HWND`) is created, `enableWindowsClickThrough()` applies `WS_EX_TRANSPARENT | WS_EX_LAYERED` styles using `SetWindowLongPtr` and refreshes the frame using `SetWindowPos(HWND_TOPMOST, ..., SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE | SWP_FRAMECHANGED)`.
   * All mouse and keyboard input falls through directly to the underlying application.
3. **Multi-Monitor Virtual Desktop Coverage**:
   * `getVirtualScreenBounds()` computes the union of all connected monitor bounds from `GraphicsEnvironment.getLocalGraphicsEnvironment()`.
   * `OverlayWindow` is positioned at `(virtualBounds.x, virtualBounds.y)` with size `(virtualBounds.width, virtualBounds.height)`, ensuring full coverage across multi-display layouts.
   * Coordinates in `OverlayWindowContent` are adjusted by screen offsets (`screenOffsetX`, `screenOffsetY`) so highlights align properly on any monitor.
4. **Independent Close Control**:
   * An independent native window (`title = "AI Tutor Overlay Close"`) is positioned in the top-right corner. It deliberately does not receive `WS_EX_TRANSPARENT`, allowing users to dismiss the overlay at any time.

---

## 3. Important Technical Decisions & Lessons Learned

* **Color-Keying (`SetLayeredWindowAttributes`) vs. Per-Pixel Alpha**:
  * *Attempted Approach:* Setting a solid backing color (magenta) and applying `SetLayeredWindowAttributes(..., LWA_COLORKEY)` to filter it out.
  * *Resulting Issue:* Destroyed Skiko's per-pixel alpha pipeline, producing black/opaque backgrounds.
  * *Resolution:* Switched entirely to Skiko's native per-pixel alpha compositing with zero background color on `OverlayWindowContent`.
