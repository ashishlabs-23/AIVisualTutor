package models

/**
 * Deterministic domain events emitted during tutor lifecycle and user interactions.
 */
sealed interface TutorEvent {
    val timestampMillis: Long

    data class ApplicationChanged(
        val application: ApplicationType,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : TutorEvent

    data class StepAdvanced(
        val fromIndex: Int,
        val toIndex: Int,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : TutorEvent

    data class StepRegressed(
        val fromIndex: Int,
        val toIndex: Int,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : TutorEvent

    data class AssistanceToggled(
        val enabled: Boolean,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : TutorEvent

    data class PauseToggled(
        val isPaused: Boolean,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : TutorEvent

    data class OverlayVisibilityChanged(
        val isVisible: Boolean,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : TutorEvent

    data class FrameCaptured(
        val frame: CapturedFrame,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : TutorEvent

    data class RegionHighlighted(
        val region: HighlightRegion?,
        override val timestampMillis: Long = System.currentTimeMillis()
    ) : TutorEvent
}
