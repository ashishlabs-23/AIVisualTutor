package models

/**
 * The complete state of the tutor UI at any point in time.
 *
 * This is deliberately a plain immutable data class. [tutor.TutorController]
 * owns the single mutable instance of it and every UI change goes through
 * a `state = state.copy(...)` update, so there is exactly one source of
 * truth and no state hidden inside individual composables.
 */
data class TutorState(
    val selectedApplication: ApplicationType = ApplicationType.BLENDER,
    val currentStepIndex: Int = 0,
    val isPaused: Boolean = false,
    val screenAssistanceEnabled: Boolean = true,
    val currentHighlight: HighlightRegion? = null
)
