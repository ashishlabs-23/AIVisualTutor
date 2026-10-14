# AI Visual Tutor — Frontend Avatar Architecture & Roadmap

This document outlines the architectural design, coordinate model, and phased roadmap for the desktop Robot Avatar in **AI Visual Tutor for Blender** on branch `feature/frontend-dev`.

---

## 1. System Vision & Design Objective

The AI Visual Tutor pairs a user learning Blender with an intelligent, friendly desktop companion. Instead of a detached modal dialog or heavy text panel, the tutor is embodied as a **floating, transparent desktop robot avatar**:
- **Aesthetic Identity:** Procedural Skia Canvas rendering of a pearl-white rounded helmet (`#F0F4F8`), graphite mechanical accents (`#334155`), a deep obsidian glossy visor (`#0B0F19`), and warm amber illuminated optics (`#F59E0B` / `#D97706`) with emissive radial glow.
- **Independence & Safety:** Rendered natively via Compose Multiplatform Desktop / Skia. No heavy 3D game engines, no external asset packs, and zero copy-pasting of AGPL-licensed codebases.
- **Non-Intrusive Guidance:** Accompanied by a sleek dark-slate instruction bubble showing the active instruction, verification status, inline contextual explanations, and a real highlight trigger.

---

## 2. Architectural Principles & State Authority

1. **Single Source of Truth (`TutorController`):**
   - The avatar **never** maintains an independent or parallel tutoring session.
   - All step definitions, task progress, verification results, and chat interactions are observed directly from the existing `TutorController` and `OverlayManager`.
2. **Deterministic State Precedence (`AvatarStateMachine`):**
   Conflicting UI events are resolved via strict priority:
   1. `OFFLINE`: Blender disconnected / telemetry unavailable.
   2. `VERIFYING`: Active computer-vision / Python verification running.
   3. `THINKING` / `EXPLAINING`: AI plan generation or contextual hint query pending.
   4. `SUCCESS`: Verification confirmed step completion.
   5. `RECOVERY`: Verification failed or user requested recovery assistance.
   6. `POINTING` / `GUIDING` / `WAITING_FOR_USER`: Active instruction in progress with/without resolved targets.
   7. `IDLE`: Resting state, breathing and natural blinking.
3. **Multi-Window Lifecycle:**
   - **`OverlayWindow`:** Fullscreen, transparent, click-through native overlay window displaying highlights, bounding boxes, and guidance arrows over Blender.
   - **`AvatarWindow`:** Undecorated, transparent, interactive floating assistant window hosting the robot and guidance bubble. Repositionable via `WindowDraggableArea`.
   - **`TutorPanel` / `TutorDock`:** Full workspace panel accessible on-demand via the bubble's HUD toggle without resetting task state.

---

## 3. Phased Implementation Roadmap

### Phase 1: Architectural Foundation & Transparent Proof (Completed)
- Built foundational Skia vector renderer `AvatarView.kt`.
- Created immutable state contracts `AvatarState.kt`.
- Implemented `NativeWindowProof.kt` demonstrating Windows DWM transparency via Skiko software fallback.
- Added 13 foundational unit tests in `AvatarStateContractTest.kt`.

### Phase 2: Application Wiring & Real Action Integration (Completed)
- Replaced standalone prototype with production `AvatarWindow.kt` integrated into `Main.kt` app startup.
- Implemented `AvatarStateMachine.resolvePresentationState` for authoritative event precedence.
- Enhanced `AvatarView.kt` with realistic blinking, emotional expressions, and compact mode.
- Enhanced `InstructionBubble.kt` with live step instructions, step counter, highlight trigger, and inline explanation viewer.
- Real actions: Highlight wired to `DynamicTargetPositioningService` / `OverlayManager`; Explain More wired to `SmartChatbotProvider`.
- Added 10 integration tests in `AvatarIntegrationTest.kt` (all 23 avatar tests passing; 650/650 total suite tests passing).

### Phase 3: Target-Aware Gaze, Pointing Gestures & Directional Guidance (Active)
- **Coordinate Model:** Establish mathematical transformation between desktop screen space (from `DynamicTargetPositioningService` / Blender client coordinates) and avatar window space.
- **Directional Gaze:** Dynamic pupil/optic offset clamped within natural ocular range toward the resolved target.
- **Directional Pointing:** Procedural 2D robot arm / hand gesture articulated toward the target coordinate.
- **Target Tracking & Invalidation:** Graceful handling of moving targets, window dragging, missing targets, and off-screen bounds without jitter or stale gestures.
- **State Synchronization:** Harmonizing robot pointing with the existing `OverlayManager` visual arrow and highlight box.

### Phase 4: Motion Polish, Fluid Transitions & Spatial Audio (Upcoming)
- Physics-based spring animations for eye tracking.
- Audio cues for verification success / recovery prompts.
- Advanced multi-monitor DPI auto-adaptation.

---

## 4. Coordinate Geometry Strategy (Phase 3 Foundation)

```text
+-------------------------------------------------------------------------------+
| Desktop Screen Space (Virtual Screen / Physical Pixels / DPI Scaled)           |
|                                                                               |
|   +--------------------------+                                                |
|   | Blender Window           |                                                |
|   |                          |                                                |
|   |      [Target Bounds]     | <==== (Tx, Ty) resolved by PositioningService  |
|   |                          |                                                |
|   +--------------------------+                                                |
|                                                                               |
|                  Vector: D = (Tx - Ax, Ty - Ay)                               |
|                  Angle:  θ = atan2(Dy, Dx)                                    |
|                                                                               |
|   +-----------------------+                                                   |
|   | AvatarWindow          |                                                   |
|   | (Wx, Wy)              |                                                   |
|   |   +---------------+   |                                                   |
|   |   | Robot Visor   |   |                                                   |
|   |   | Center (Ax,Ay)|   | ====> Gaze offset: (cos θ * r, sin θ * r)         |
|   |   +---------------+   | ====> Arm angle:   θ                              |
|   |   [Instruction    |   |                                                   |
|   |    Bubble]        |   |                                                   |
|   +-----------------------+                                                   |
+-------------------------------------------------------------------------------+
```

The guidance engine transforms global target rectangles into normalized direction vectors relative to the robot's facial center, ensuring pointing and gaze are physically meaningful on the user's screen.
