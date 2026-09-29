package models

/**
 * A rectangular region on screen that the tutor wants to draw attention to
 * (via [ui.Highlight] and [ui.Arrow]).
 *
 * Coordinates are in pixels, relative to the top-left corner of the
 * primary screen. In Phase 1 these are either hard-coded mock values
 * (see [tutor.MockTutorData]) or produced manually by the user via
 * [selection.RegionSelector]. Region capture uses the manually selected
 * coordinates to capture the corresponding screen pixels.
 */
data class HighlightRegion(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    /** Convenience center point, useful for pointing an arrow at the region. */
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f
}
