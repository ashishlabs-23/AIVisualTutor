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
 * Skill mastery classification based on verified outcomes.
 * Progresses conservatively through evidence-supported stages.
 */
enum class SkillMasteryLevel(val displayName: String, val description: String) {
    NOT_OBSERVED("Not Observed", "No verified demonstrations of this skill yet recorded"),
    EMERGING("Emerging", "Initial verified demonstration observed"),
    PRACTICED("Practiced", "Multiple verified demonstrations recorded across tasks"),
    CONSISTENTLY_DEMONSTRATED("Consistently Demonstrated", "Consistent verified success demonstrated with high reliability");

    companion object {
        fun fromSuccessCounts(verifiedSuccessCount: Int, consecutiveSuccessCount: Int): SkillMasteryLevel = when {
            consecutiveSuccessCount >= 3 -> CONSISTENTLY_DEMONSTRATED
            verifiedSuccessCount >= 2 -> PRACTICED
            verifiedSuccessCount >= 1 -> EMERGING
            else -> NOT_OBSERVED
        }
    }
}

/**
 * Verified capability demonstrated by the learner in Blender.
 */
data class DemonstratedSkill(
    val skillId: String,
    val displayName: String,
    val masteryLevel: SkillMasteryLevel = SkillMasteryLevel.NOT_OBSERVED,
    val verifiedSuccessCount: Int = 0,
    val consecutiveSuccessCount: Int = 0,
    val totalAttempts: Int = 0,
    val lastVerifiedAt: Instant? = null
) {
    init {
        require(skillId.isNotBlank()) { "Skill ID must not be blank." }
        require(verifiedSuccessCount >= 0) { "Verified successes must be non-negative." }
        require(consecutiveSuccessCount >= 0) { "Consecutive successes must be non-negative." }
    }
}

/**
 * Distinguishes the genuine root cause of an unsuccessful or uncertain attempt.
 * Crucial for Rule 2: Failed attempts are not automatically learner weaknesses.
 */
enum class FailureCause {
    /** The learner's action resulted in a state mismatch. */
    LEARNER_ACTION,
    /** Missing telemetry, socket disconnect, or AI provider failure. */
    INFRASTRUCTURE_OR_PROVIDER,
    /** Inconclusive, ambiguous, or conflicting telemetry evidence. */
    UNCERTAIN_EVIDENCE,
    /** Inaccurate expected state or contradictory step definition. */
    TASK_SPECIFICATION
}

/**
 * Record of recurring, evidence-supported difficulty experienced by the learner.
 * Created only when evidence demonstrates repeated verified errors on the same task/skill.
 */
data class LearnerDifficulty(
    val difficultyId: String = UUID.randomUUID().toString(),
    val skillId: String,
    val errorCategory: String,
    val occurrenceCount: Int = 1,
    val explanation: String = "",
    val firstOccurredAt: Instant = Instant.now(),
    val lastOccurredAt: Instant = Instant.now()
) {
    init {
        require(skillId.isNotBlank()) { "Skill ID must not be blank." }
        require(occurrenceCount >= 1) { "Occurrence count must be at least 1." }
    }
}

/**
 * Learner detail preference level.
 */
enum class DetailPreference {
    NORMAL,
    CONCISE,
    DETAILED
}

/**
 * Explicit instruction and tutoring preferences provided by the learner.
 */
data class LearnerPreferences(
    val detailPreference: DetailPreference = DetailPreference.NORMAL,
    val preferredGuidanceStage: GuidanceStage? = null,
    val wantsSmallerSteps: Boolean = false,
    val practiceRecommendationsEnabled: Boolean = true
)

/**
 * Instructional strategy selected by adaptive pedagogy.
 */
enum class AdaptationStrategy {
    NORMAL_GUIDANCE,
    CONCISE_INSTRUCTION,
    DETAILED_INSTRUCTION,
    SMALLER_STEP,
    TARGETED_PRACTICE
}

/**
 * Audit record of an adaptation decision and its supporting evidence.
 */
data class AdaptationDecisionRecord(
    val decisionId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val skillId: String,
    val strategy: AdaptationStrategy,
    val evidenceReason: String,
    val timestamp: Instant = Instant.now()
)

/**
 * Practice recommendation tailored to learner's emerging skills or difficulties.
 */
data class PracticeRecommendation(
    val recommendationId: String = UUID.randomUUID().toString(),
    val skillId: String,
    val title: String,
    val description: String,
    val suggestedGoalText: String,
    val rationale: String,
    val isCompleted: Boolean = false
)

/**
 * Structured audit trail of a learner attempt.
 */
data class LearnerAttemptRecord(
    val attemptId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val skillId: String,
    val stepId: String? = null,
    val planVersion: Long = 1L,
    val outcome: PedagogicalOutcome,
    val errorCategory: String? = null,
    val failureCause: FailureCause = FailureCause.LEARNER_ACTION,
    val explanation: String = "",
    val timestamp: Instant = Instant.now()
)

/**
 * Complete learner state tracking verified performance, demonstrated skills,
 * recurring difficulties, preferences, and adaptation history.
 */
data class LearnerState(
    val learnerId: String = "default_learner",
    val schemaVersion: Int = 1,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
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
    val taskProfiles: Map<String, TaskLearnerProfile> = emptyMap(),
    val demonstratedSkills: Map<String, DemonstratedSkill> = emptyMap(),
    val verifiedOutcomes: Set<String> = emptySet(),
    val recordedDifficulties: Map<String, LearnerDifficulty> = emptyMap(),
    val preferences: LearnerPreferences = LearnerPreferences(),
    val adaptationDecisions: List<AdaptationDecisionRecord> = emptyList(),
    val practiceRecommendations: List<PracticeRecommendation> = emptyList(),
    val processedEventIds: Set<String> = emptySet()
) {
    init {
        require(competenceEstimate in 0.0..1.0) { "Competence estimate must be in 0.0..1.0." }
        require(consecutiveSuccesses >= 0) { "Consecutive successes must be non-negative." }
        require(consecutiveFailures >= 0) { "Consecutive failures must be non-negative." }
        require(schemaVersion >= 1) { "Schema version must be at least 1." }
    }

    fun profileForTask(taskId: String, skillId: String): TaskLearnerProfile =
        taskProfiles[taskId] ?: TaskLearnerProfile(taskId = taskId, skillId = skillId)

    fun getSkillLevel(skillId: String): SkillMasteryLevel =
        demonstratedSkills[skillId]?.masteryLevel ?: SkillMasteryLevel.NOT_OBSERVED

    fun hasDifficulty(skillId: String): Boolean =
        recordedDifficulties.containsKey(skillId)
}

/**
 * Trustworthy Learning Events boundary for Phase 13.
 * Connects authoritative verification and learner actions to the pedagogical engine idempotently.
 */
sealed interface LearningEvent {
    val eventId: String
    val learnerId: String
    val timestamp: Instant

    /**
     * Authoritative verification outcome emitted after Blender state analysis.
     */
    data class OutcomeVerified(
        override val eventId: String = UUID.randomUUID().toString(),
        override val learnerId: String = "default_learner",
        val sessionId: String = "default_session",
        val goalId: String? = null,
        val planVersion: Long = 1L,
        val stepId: String = "",
        val taskId: String,
        val skillId: String,
        val result: VerificationResult,
        val mismatchCategory: String? = null,
        val failureCause: FailureCause = FailureCause.LEARNER_ACTION,
        override val timestamp: Instant = Instant.now()
    ) : LearningEvent

    /**
     * Explicit learner update to their instruction preferences.
     */
    data class PreferenceUpdated(
        override val eventId: String = UUID.randomUUID().toString(),
        override val learnerId: String = "default_learner",
        val preferences: LearnerPreferences,
        override val timestamp: Instant = Instant.now()
    ) : LearningEvent

    /**
     * Recovery attempt outcome evaluated during Phase 11 recovery flow.
     */
    data class RecoveryAttemptEvaluated(
        override val eventId: String = UUID.randomUUID().toString(),
        override val learnerId: String = "default_learner",
        val stepId: String,
        val attemptNumber: Int,
        val status: VerificationStatus,
        val recoveryOutcome: String,
        override val timestamp: Instant = Instant.now()
    ) : LearningEvent

    /**
     * Explicit request by learner to reset or clear saved progress.
     */
    data class ResetRequested(
        override val eventId: String = UUID.randomUUID().toString(),
        override val learnerId: String = "default_learner",
        override val timestamp: Instant = Instant.now()
    ) : LearningEvent
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
    val status: GenerationStatus = GenerationStatus.GENERATED,
    val strategy: AdaptationStrategy = AdaptationStrategy.NORMAL_GUIDANCE,
    val adaptationRationale: String? = null
)
