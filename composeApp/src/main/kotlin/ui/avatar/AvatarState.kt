package ui.avatar

import context.BlenderObservationStatus
import context.VerificationStatus
import models.HighlightRegion
import models.TutorStep

/**
 * 12 Semantic presentation states for the AI Visual Tutor avatar.
 * Defined in Phase 1 specification.
 */
enum class AvatarPresentationState(
    val isTransient: Boolean,
    val defaultStatusLabel: String
) {
    /** Starting up / initializing session context. */
    STARTING(isTransient = true, defaultStatusLabel = "Starting..."),

    /** Idle, waiting for initial goal or command. */
    IDLE(isTransient = false, defaultStatusLabel = "Ready"),

    /** Processing user query, generating plan, or computing target grounding. */
    THINKING(isTransient = true, defaultStatusLabel = "Thinking..."),

    /** Active step instruction guidance. */
    GUIDING(isTransient = false, defaultStatusLabel = "Guiding"),

    /** Pointing directly at an on-screen target in Blender. */
    POINTING(isTransient = false, defaultStatusLabel = "Pointing"),

    /** Providing expanded explanation, concept summary, or hint. */
    EXPLAINING(isTransient = false, defaultStatusLabel = "Explaining"),

    /** Waiting for the learner to perform the action in Blender. */
    WAITING_FOR_USER(isTransient = false, defaultStatusLabel = "Waiting for you"),

    /** Analyzing screenshot or querying Blender live state to verify action. */
    VERIFYING(isTransient = true, defaultStatusLabel = "Verifying..."),

    /** Action verified successfully. */
    SUCCESS(isTransient = true, defaultStatusLabel = "Great job!"),

    /** Step was unverified or user asked for help/stuck. Offering recovery guidance. */
    RECOVERY(isTransient = false, defaultStatusLabel = "Let's fix this"),

    /** Blender adapter is disconnected or unavailable. */
    OFFLINE(isTransient = false, defaultStatusLabel = "Blender Offline"),

    /** Collapsed into mini dock mode. */
    COMPACT(isTransient = false, defaultStatusLabel = "Docked")
}

/**
 * Domain events that trigger avatar presentation state transitions.
 */
sealed interface AvatarDomainEvent {
    object Initialize : AvatarDomainEvent
    data class GoalStarted(val goalText: String) : AvatarDomainEvent
    object QuerySubmitted : AvatarDomainEvent
    data class StepActivated(val step: TutorStep, val hasTarget: Boolean) : AvatarDomainEvent
    object TargetLocated : AvatarDomainEvent
    object ExplanationRequested : AvatarDomainEvent
    object UserActionPending : AvatarDomainEvent
    object VerificationStarted : AvatarDomainEvent
    data class VerificationFinished(val status: VerificationStatus) : AvatarDomainEvent
    object StuckReported : AvatarDomainEvent
    data class BlenderConnectionChanged(val status: BlenderObservationStatus) : AvatarDomainEvent
    object CollapseToDock : AvatarDomainEvent
    object ExpandFromDock : AvatarDomainEvent
    object TransientTimeout : AvatarDomainEvent
}

/**
 * Minimum session contract required by the avatar view.
 * Reuses existing domain models without duplicating state.
 */
data class AvatarSessionContract(
    val currentTaskId: String? = null,
    val currentPlanId: String? = null,
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentInstruction: String? = null,
    val activeControlName: String? = null,
    val presentationState: AvatarPresentationState = AvatarPresentationState.IDLE,
    val isBlenderConnected: Boolean = false,
    val targetHighlight: HighlightRegion? = null,
    val lastVerificationOutcome: VerificationStatus? = null,
    val isChatVisible: Boolean = false,
    val isBusy: Boolean = false,
    val isReducedMotion: Boolean = false
)

/**
 * Pure, deterministic state machine for avatar presentation transitions.
 *
 * CRITICAL ARCHITECTURAL INVARIANT:
 * Avatar presentation state transitions NEVER mutate tutorial step progress,
 * mark tasks complete, or bypass pedagogical verification.
 * Step progression remains exclusively owned by [tutor.TutorController].
 */
object AvatarStateMachine {

    fun transition(
        currentState: AvatarPresentationState,
        event: AvatarDomainEvent,
        isBlenderConnected: Boolean = true
    ): AvatarPresentationState {
        // Blender disconnect immediately overrides state unless already starting or compact
        if (!isBlenderConnected && event !is AvatarDomainEvent.CollapseToDock) {
            return AvatarPresentationState.OFFLINE
        }

        return when (event) {
            is AvatarDomainEvent.Initialize -> AvatarPresentationState.STARTING

            is AvatarDomainEvent.GoalStarted -> AvatarPresentationState.THINKING

            is AvatarDomainEvent.QuerySubmitted -> AvatarPresentationState.THINKING

            is AvatarDomainEvent.StepActivated -> {
                if (event.hasTarget) AvatarPresentationState.POINTING
                else AvatarPresentationState.GUIDING
            }

            is AvatarDomainEvent.TargetLocated -> AvatarPresentationState.POINTING

            is AvatarDomainEvent.ExplanationRequested -> AvatarPresentationState.EXPLAINING

            is AvatarDomainEvent.UserActionPending -> AvatarPresentationState.WAITING_FOR_USER

            is AvatarDomainEvent.VerificationStarted -> AvatarPresentationState.VERIFYING

            is AvatarDomainEvent.VerificationFinished -> when (event.status) {
                VerificationStatus.SUCCESS -> AvatarPresentationState.SUCCESS
                VerificationStatus.FAILURE -> AvatarPresentationState.RECOVERY
                VerificationStatus.UNCERTAIN -> AvatarPresentationState.RECOVERY
            }

            is AvatarDomainEvent.StuckReported -> AvatarPresentationState.RECOVERY

            is AvatarDomainEvent.BlenderConnectionChanged -> {
                if (event.status == BlenderObservationStatus.AVAILABLE) {
                    if (currentState == AvatarPresentationState.OFFLINE) AvatarPresentationState.IDLE
                    else currentState
                } else {
                    AvatarPresentationState.OFFLINE
                }
            }

            is AvatarDomainEvent.CollapseToDock -> AvatarPresentationState.COMPACT

            is AvatarDomainEvent.ExpandFromDock -> {
                if (!isBlenderConnected) AvatarPresentationState.OFFLINE
                else AvatarPresentationState.IDLE
            }

            is AvatarDomainEvent.TransientTimeout -> {
                // Settle transient states into their persistent resting states
                when (currentState) {
                    AvatarPresentationState.STARTING -> AvatarPresentationState.IDLE
                    AvatarPresentationState.THINKING -> AvatarPresentationState.GUIDING
                    AvatarPresentationState.VERIFYING -> AvatarPresentationState.WAITING_FOR_USER
                    AvatarPresentationState.SUCCESS -> AvatarPresentationState.GUIDING
                    else -> currentState
                }
            }
        }
    }

    /**
     * Resolves the authoritative [AvatarPresentationState] directly from current session conditions.
     * Enforces strict event precedence:
     * 1. Blender disconnected -> OFFLINE
     * 2. Compact dock mode -> COMPACT (while preserving underlying state)
     * 3. Verification actively in flight -> VERIFYING
     * 4. Plan generation or LLM query in flight -> THINKING
     * 5. Active in-bubble or chat explanation -> EXPLAINING
     * 6. Authoritative verified success -> SUCCESS
     * 7. Unverified failure or stuck request -> RECOVERY
     * 8. Active tutorial step -> POINTING (if target present) or GUIDING
     * 9. Default resting state -> IDLE
     */
    fun resolvePresentationState(
        isBlenderConnected: Boolean,
        isCompact: Boolean = false,
        isVerificationBusy: Boolean = false,
        isThinking: Boolean = false,
        isExplaining: Boolean = false,
        isVerifiedSuccess: Boolean = false,
        isRecoveryActive: Boolean = false,
        activeStep: TutorStep? = null,
        hasTarget: Boolean = false
    ): AvatarPresentationState {
        // Precedence 1: Offline if Blender adapter is disconnected
        if (!isBlenderConnected) {
            return AvatarPresentationState.OFFLINE
        }

        // Precedence 2: Compact display mode
        if (isCompact) {
            return AvatarPresentationState.COMPACT
        }

        // Precedence 3: Closed-loop verification in flight
        if (isVerificationBusy) {
            return AvatarPresentationState.VERIFYING
        }

        // Precedence 4: Background thinking / planning
        if (isThinking) {
            return AvatarPresentationState.THINKING
        }

        // Precedence 5: Contextual explanation
        if (isExplaining) {
            return AvatarPresentationState.EXPLAINING
        }

        // Precedence 6: Transient verified success
        if (isVerifiedSuccess) {
            return AvatarPresentationState.SUCCESS
        }

        // Precedence 7: Recovery guidance needed
        if (isRecoveryActive) {
            return AvatarPresentationState.RECOVERY
        }

        // Precedence 8: Active step instruction
        if (activeStep != null) {
            return if (hasTarget) AvatarPresentationState.POINTING else AvatarPresentationState.GUIDING
        }

        // Precedence 9: Default idle
        return AvatarPresentationState.IDLE
    }
}
