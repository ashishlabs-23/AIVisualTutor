package pedagogy

import context.ExpectedState
import context.VerificationResult
import context.VerificationStatus
import models.HighlightRegion
import java.time.Instant
import java.util.UUID

/**
 * Three distinct and observable pedagogical stages for adaptive guidance.
 * These are guidance strategies applied per task, not permanent learner categories.
 */
enum class GuidanceStage(val displayName: String, val description: String) {
    /**
     * Stage 1 — Exact Visual Guidance
     * For beginners or learners who are struggling.
     * Names the target control, specifies location when reliable evidence supports it,
     * and describes the immediate action to perform.
     */
    STAGE_1_EXACT_GUIDANCE(
        displayName = "Direct Guidance",
        description = "Explicit instruction with control name and immediate action"
    ),

    /**
     * Stage 2 — Conceptual Hint
     * For learners who have demonstrated initial competence.
     * Explains the action conceptually and identifies the relevant interface area
     * while encouraging independent control location.
     */
    STAGE_2_CONCEPTUAL_HINT(
        displayName = "Conceptual Hint",
        description = "Conceptual explanation encouraging independent action"
    ),

    /**
     * Stage 3 — Self-Correction Question
     * For learners with verified competence on tasks suitable for reflection.
     * Asks a targeted question prompting retrieval and problem-solving without
     * revealing the immediate solution.
     */
    STAGE_3_SELF_CORRECTION_QUESTION(
        displayName = "Reflection Question",
        description = "Targeted question prompting independent problem-solving"
    );

    val nextMoreSupport: GuidanceStage
        get() = when (this) {
            STAGE_3_SELF_CORRECTION_QUESTION -> STAGE_2_CONCEPTUAL_HINT
            STAGE_2_CONCEPTUAL_HINT -> STAGE_1_EXACT_GUIDANCE
            STAGE_1_EXACT_GUIDANCE -> STAGE_1_EXACT_GUIDANCE
        }

    val nextLessSupport: GuidanceStage
        get() = when (this) {
            STAGE_1_EXACT_GUIDANCE -> STAGE_2_CONCEPTUAL_HINT
            STAGE_2_CONCEPTUAL_HINT -> STAGE_3_SELF_CORRECTION_QUESTION
            STAGE_3_SELF_CORRECTION_QUESTION -> STAGE_3_SELF_CORRECTION_QUESTION
        }
}

/**
 * Pedagogical outcomes mapped directly from Phase 6 verification outcomes
 * and runtime user action events.
 */
enum class PedagogicalOutcome {
    VERIFIED_SUCCESS,
    VERIFIED_FAILURE,
    UNCERTAIN,
    NOT_EVALUATED;

    companion object {
        fun fromVerificationResult(result: VerificationResult): PedagogicalOutcome = when (result.status) {
            VerificationStatus.SUCCESS -> VERIFIED_SUCCESS
            VerificationStatus.FAILURE -> VERIFIED_FAILURE
            VerificationStatus.UNCERTAIN -> UNCERTAIN
        }
    }
}

/**
 * Pedagogical task representation connecting learning objectives, expected outcomes,
 * and multi-stage instructional content.
 */
data class LearningTask(
    val taskId: String,
    val skillId: String,
    val title: String,
    val objective: String,
    val targetControlName: String? = null,
    val targetAreaDescription: String? = null,
    val exactInstruction: String,
    val conceptualHint: String,
    val reflectionQuestion: String,
    val expectedState: ExpectedState? = null,
    val targetRegion: HighlightRegion? = null,
    val supportsStage3: Boolean = true,
    val stepIndex: Int = 0
) {
    init {
        require(taskId.isNotBlank()) { "Task ID must not be blank." }
        require(skillId.isNotBlank()) { "Skill ID must not be blank." }
        require(exactInstruction.isNotBlank()) { "Exact instruction must not be blank." }
        require(conceptualHint.isNotBlank()) { "Conceptual hint must not be blank." }
        require(reflectionQuestion.isNotBlank()) { "Reflection question must not be blank." }
    }
}

/**
 * Record of a single pedagogical attempt and its verified outcome.
 */
data class PedagogicalAttempt(
    val attemptId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val skillId: String,
    val guidanceStage: GuidanceStage,
    val outcome: PedagogicalOutcome,
    val timestamp: Instant = Instant.now(),
    val verificationRunId: String? = null,
    val explanation: String = "",
    val evidenceConfidence: Double? = null
)

/**
 * Task-scoped competence profile to prevent ungrounded transfer of competence
 * between unrelated tasks or skill domains.
 */
data class TaskLearnerProfile(
    val taskId: String,
    val skillId: String,
    val guidanceStage: GuidanceStage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
    val consecutiveSuccesses: Int = 0,
    val consecutiveFailures: Int = 0,
    val stageConsecutiveSuccesses: Int = 0,
    val totalSuccesses: Int = 0,
    val totalFailures: Int = 0,
    val totalUncertain: Int = 0,
    val competenceEstimate: Double = 0.0,
    val lastOutcome: PedagogicalOutcome? = null,
    val lastAttemptAt: Instant? = null
) {
    init {
        require(competenceEstimate in 0.0..1.0) { "Competence estimate must be in 0.0..1.0." }
        require(consecutiveSuccesses >= 0) { "Consecutive successes must be non-negative." }
        require(consecutiveFailures >= 0) { "Consecutive failures must be non-negative." }
        require(stageConsecutiveSuccesses >= 0) { "Stage consecutive successes must be non-negative." }
    }
}

/**
 * Complete learner state tracking verified performance, active guidance stage,
 * and adaptation rationale.
 */
data class LearnerState(
    val learnerId: String = "default_learner",
    val currentTaskId: String = "default_task",
    val currentSkillId: String = "general",
    val currentGuidanceStage: GuidanceStage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
    val consecutiveSuccesses: Int = 0,
    val consecutiveFailures: Int = 0,
    val stageConsecutiveSuccesses: Int = 0,
    val totalVerifiedSuccesses: Int = 0,
    val totalVerifiedFailures: Int = 0,
    val totalUncertainOutcomes: Int = 0,
    val competenceEstimate: Double = 0.0,
    val evidenceConfidence: Double? = null,
    val adaptationReason: String = "Initial beginner state: defaulting to Stage 1 (Direct Guidance).",
    val requiresReassessment: Boolean = false,
    val recentAttempts: List<PedagogicalAttempt> = emptyList(),
    val taskProfiles: Map<String, TaskLearnerProfile> = emptyMap()
) {
    init {
        require(competenceEstimate in 0.0..1.0) { "Competence estimate must be in 0.0..1.0." }
        require(consecutiveSuccesses >= 0) { "Consecutive successes must be non-negative." }
        require(consecutiveFailures >= 0) { "Consecutive failures must be non-negative." }
    }

    fun profileForTask(taskId: String, skillId: String): TaskLearnerProfile =
        taskProfiles[taskId] ?: TaskLearnerProfile(taskId = taskId, skillId = skillId)
}

/**
 * Status of generated adaptive instruction.
 */
enum class GenerationStatus {
    GENERATED,
    FALLBACK_GENERATED,
    UNCERTAIN_EVIDENCE,
    REASSESSMENT_RECOMMENDED
}

/**
 * Structured output of the adaptive instruction generator.
 */
data class AdaptiveInstruction(
    val instructionId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val stage: GuidanceStage,
    val instructionText: String,
    val conceptSummary: String? = null,
    val questionText: String? = null,
    val targetControlName: String? = null,
    val targetLocationDescription: String? = null,
    val targetRegion: HighlightRegion? = null,
    val supportingEvidence: List<String> = emptyList(),
    val evidenceConfidence: Double? = null,
    val isFallback: Boolean = false,
    val status: GenerationStatus = GenerationStatus.GENERATED
)
