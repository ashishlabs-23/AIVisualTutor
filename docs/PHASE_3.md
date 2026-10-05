# Phase 3: Universal Region Selection

## Data flow

```text
Ctrl+Shift+Space -> GlobalHotkeyManager -> RegionSelectionController
 -> ApplicationContextProvider (before tutor windows hide)
 -> per-monitor frozen snapshot -> RegionSelectorWindow -> ScreenCaptureService crop
 -> ContextProcessor [OCRService || ContentClassifier] -> VisualContext -> TutorController event/session
```

The existing Ctrl+Alt+F12 WGC previous-window capture remains available through the same host. The UI uses `VisualContextAcquisition` and `RegionSelectionController` for `IDLE -> SELECTING -> PROCESSING -> COMPLETED -> IDLE`; Escape, disposal, and tiny regions use `CANCELLED -> IDLE`. Overlapping starts are rejected atomically.

`PROCESSING` begins when the drag completes. A capture or processing error emits `PROCESSING_FAILED` and returns the controller to `IDLE`; cancellation emits `SELECTION_CANCELLED` and returns to `IDLE` as the UI restores the tutor windows. The controller uses synchronized, session-ID checked transitions so stale callbacks cannot finish a newer session.

## Coordinates and capture

`VisualContext.x/y` and selector bounds are AWT desktop user-space coordinates; origins may be negative. `VisualContext.width/height` are captured output pixel dimensions. `FrozenScreenSnapshot` stores a logical-size composition for selection display plus independent physical-resolution Robot images for each monitor. `FrozenSnapshotRegionCaptureService` intersects the selected logical rectangle with each monitor and maps through each monitor's independent X/Y scale. The output uses the highest intersecting monitor scale per axis, preserving source pixels and upscaling lower-density tiles when scales differ.

The implementation uses each `GraphicsDevice.bounds` and `Robot.createMultiResolutionScreenCapture`. JDK 17 returns monitor-specific resolution variants, while AWT bounds are toolkit/user-space coordinates. This mapping is covered with fake monitor layouts and was not exercised on mixed-DPI hardware here; Windows/JDK behavior must still be verified on real 100/125/150/200% and mixed-DPI monitor setups.

## Privacy and logging

Screenshots are represented in memory by `BufferedImage`; a temporary `AIVT_*.png` is created only for the existing preview and deleted when it closes or its Compose window is disposed. Lifecycle logging accepts a strict whitelist of IDs, sizes, durations, state, OCR character count, and registration IDs. OCR text, image bytes, app names, and window titles are not logged.

## Extension points

Replace `PlaceholderOcrService` with OCR and `PlaceholderContentClassifier` with a vision model. `ApplicationContextProvider` is nullable and independent. `VisualContext` is the boundary for later Ambient-RAG ingestion; this phase adds no embeddings, vector store, retrieval, or LLM calls.

## Limitations

Placeholder OCR returns no text. Active-window detection is implemented through the isolated PowerShell/Win32 boundary and excludes the tutor process, but title/process metadata collection requires runtime validation. Real hotkey registration was observed to succeed on this Windows host; hotkey triggering, selector rendering, and multi-monitor/DPI capture remain unverified.
