package models

/**
 * A single step in a mock tutorial workflow.
 *
 * [targetRegion] is optional because not every step needs an on-screen
 * highlight (e.g. a purely instructional step). When present, it is used
 * to draw the highlight rectangle and arrow on the overlay window.
 */
data class TutorStep(
    val id: Int,
    val title: String,
    val instruction: String,
    val description: String,
    val stepNumber: Int,
    val totalSteps: Int,
    val targetRegion: HighlightRegion? = null
)
