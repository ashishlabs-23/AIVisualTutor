package tutor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import models.ApplicationType
import models.HighlightRegion
import models.TutorState
import models.TutorStep

/**
 * Owns the single [TutorState] instance and every operation that can
 * change it. UI composables only ever read [state] and call these
 * functions - they never mutate state fields directly. This keeps state
 * ownership in one place instead of scattered across Main.kt.
 */
class TutorController {

    var state by mutableStateOf(TutorState())
        private set

    init {
        // Start with the first step's highlight already applied so the
        // overlay has something to show as soon as it is turned on.
        state = state.copy(currentHighlight = stepsForCurrentApp().firstOrNull()?.targetRegion)
    }

    private fun stepsForCurrentApp(): List<TutorStep> =
        MockTutorData.stepsFor(state.selectedApplication)

    /** The step currently being shown to the user. */
    val currentStep: TutorStep
        get() = stepsForCurrentApp()[state.currentStepIndex]

    fun selectApplication(application: ApplicationType) {
        if (application == state.selectedApplication) return
        val firstStep = MockTutorData.stepsFor(application).first()
        state = state.copy(
            selectedApplication = application,
            currentStepIndex = 0,
            currentHighlight = firstStep.targetRegion
        )
    }

    fun nextStep() {
        val steps = stepsForCurrentApp()
        val nextIndex = (state.currentStepIndex + 1).coerceAtMost(steps.lastIndex)
        state = state.copy(
            currentStepIndex = nextIndex,
            currentHighlight = steps[nextIndex].targetRegion
        )
    }

    fun previousStep() {
        val steps = stepsForCurrentApp()
        val previousIndex = (state.currentStepIndex - 1).coerceAtLeast(0)
        state = state.copy(
            currentStepIndex = previousIndex,
            currentHighlight = steps[previousIndex].targetRegion
        )
    }

    fun togglePause() {
        state = state.copy(isPaused = !state.isPaused)
    }

    fun toggleScreenAssistance() {
        state = state.copy(screenAssistanceEnabled = !state.screenAssistanceEnabled)
    }

    /** Used by the manual region selector prototype to set a custom highlight. */
    fun setHighlight(region: HighlightRegion?) {
        state = state.copy(currentHighlight = region)
    }
}
