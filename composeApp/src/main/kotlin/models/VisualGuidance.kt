package models

/**
 * Represents visual guidance instructions to be rendered on the transparent overlay.
 */
data class VisualGuidance(
    val targetRegion: HighlightRegion? = null,
    val stepLabel: String? = null,
    val isVisible: Boolean = true
)
