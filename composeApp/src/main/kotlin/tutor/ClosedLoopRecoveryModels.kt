package tutor

import context.ExpectedState
import context.MismatchCategory
import context.ObservedState
import context.StateMismatchReport
import context.VerificationResult
import context.VerificationStatus
import java.time.Instant
import java.util.UUID

/**
 * Conceptual lifecycle stages for closed-loop verification and recovery in Phase 11.
 */
enum class RecoveryLifecycleStage {
    NOT_STARTED,
    AWAITING_LEARNER_ACTION,
    AWAITING_STATE_CAPTURE,
    VERIFYING,
    VERIFIED_SUCCESS,
    VERIFIED_FAILURE,
    DIAGNOSING,
    RECOVERY_RECOMMENDED,
    AWAITING_RETRY,
    NEEDS_MORE_EVIDENCE,
    RETRY_LIMIT_REACHED,
    CANCELLED,
    ERROR
}

/**
 * Configurable policy controlling retry limits and timeouts for closed-loop recovery.
 */
data class RecoveryPolicy(
    val maxVerifiedFailuresPerStep: Int = 3,
    val autoVerifyPollIntervalMillis: Long = 300,
    val autoVerifyMaxWaitMillis: Long = 10000
)

/**
 * Tracked record of a single verification attempt for a specific lesson step.
 * Preserves traceability between the step, observed state, verification outcome,
 * and recovery guidance without storing sensitive credentials.
 */
data class StepRecoveryAttempt(
    val attemptId: String = UUID.randomUUID().toString(),
    val stepIndex: Int,
    val stepId: Int,
    val attemptNumber: Int,
    val verificationResult: VerificationResult,
    val observedState: ObservedState?,
    val mismatchCategory: MismatchCategory?,
    val recommendation: TutorRecommendation?,
    val timestamp: Instant = Instant.now(),
    val isRetried: Boolean = false,
    val retryOutcome: VerificationStatus? = null
)

/**
 * Sealed hierarchy representing the observable UI and lifecycle state of closed-loop recovery.
 */
sealed interface ClosedLoopRecoveryState {
    val stage: RecoveryLifecycleStage

    object NotStarted : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.NOT_STARTED
    }

    data class AwaitingLearnerAction(
        val stepIndex: Int,
        val stepTitle: String,
        val expectedState: ExpectedState?
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.AWAITING_LEARNER_ACTION
    }

    data class AwaitingStateCapture(
        val requestId: String,
        val stepIndex: Int,
        val stepTitle: String
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.AWAITING_STATE_CAPTURE
    }

    data class Verifying(
        val requestId: String,
        val stepIndex: Int,
        val stepTitle: String,
        val runId: String? = null
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.VERIFYING
    }

    data class VerifiedSuccess(
        val requestId: String,
        val stepIndex: Int,
        val stepTitle: String,
        val verificationResult: VerificationResult,
        val explanation: String
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.VERIFIED_SUCCESS
    }

    data class VerifiedFailure(
        val requestId: String,
        val stepIndex: Int,
        val stepTitle: String,
        val verificationResult: VerificationResult,
        val observedState: ObservedState?,
        val attemptNumber: Int
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.VERIFIED_FAILURE
    }

    data class Diagnosing(
        val requestId: String,
        val stepIndex: Int,
        val stepTitle: String,
        val attemptNumber: Int
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.DIAGNOSING
    }

    data class RecoveryRecommended(
        val requestId: String,
        val stepIndex: Int,
        val stepTitle: String,
        val recommendation: TutorRecommendation,
        val diagnosticReport: StateMismatchReport?,
        val attemptNumber: Int,
        val remainingAttempts: Int,
        val isRepeatedMismatch: Boolean = false,
        val adaptedGuidanceNote: String? = null
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.RECOVERY_RECOMMENDED
    }

    data class AwaitingRetry(
        val stepIndex: Int,
        val stepTitle: String,
        val attemptNumber: Int
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.AWAITING_RETRY
    }

    data class NeedsMoreEvidence(
        val requestId: String,
        val stepIndex: Int,
        val message: String,
        val diagnostic: String? = null,
        val canRetry: Boolean = true
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.NEEDS_MORE_EVIDENCE
    }

    data class RetryLimitReached(
        val stepIndex: Int,
        val stepTitle: String,
        val totalAttempts: Int,
        val lastRecommendation: TutorRecommendation?,
        val message: String,
        val canResetOrOverride: Boolean = true
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.RETRY_LIMIT_REACHED
    }

    data class Cancelled(
        val reason: String = "Verification or recovery was cancelled by learner."
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.CANCELLED
    }

    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val canRetry: Boolean = true
    ) : ClosedLoopRecoveryState {
        override val stage: RecoveryLifecycleStage get() = RecoveryLifecycleStage.ERROR
    }
}
