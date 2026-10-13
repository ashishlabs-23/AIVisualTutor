package tutor

import context.ExpectedState
import models.ApplicationType
import models.HighlightRegion
import models.TutorStep
import java.util.UUID

/**
 * Importance of a required outcome for goal completion.
 */
enum class OutcomeRequirementLevel {
    MANDATORY,
    OPTIONAL
}

/**
 * Strategy used to verify a required outcome or step result.
 */
enum class VerificationStrategy {
    AUTHORITATIVE_STATE, // Authoritative telemetry via Blender adapter (e.g. object exists, mode, selection, rename, delete)
    VISUAL_INSPECTION,    // Visual model / multimodal screenshot inspection
    MANUAL_CHECK,         // Requires human learner manual verification (e.g. material assigned, render produced)
    UNSUPPORTED           // Not verifiable with current capabilities
}

/**
 * Status of an outcome within a plan's task coverage analysis.
 */
enum class OutcomeCoverageStatus {
    UNCOVERED,
    PARTIALLY_COVERED,
    FULLY_COVERED,
    UNSUPPORTED_ASSUMPTION
}

/**
 * Execution progress status of an outcome towards goal completion.
 */
enum class OutcomeSatisfactionStatus {
    NOT_STARTED,
    PENDING,
    IN_PROGRESS,
    SATISFIED,
    FAILED
}

/**
 * A concrete, verifiable outcome required to satisfy the learner's goal.
 */
data class RequiredOutcome(
    val id: String,
    val description: String,
    val requirementLevel: OutcomeRequirementLevel = OutcomeRequirementLevel.MANDATORY,
    val expectedState: ExpectedState? = null,
    val verificationStrategy: VerificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
    val prerequisiteOutcomeIds: List<String> = emptyList(),
    val isExplicitlyRequested: Boolean = true,
    val verificationLimitationNote: String? = null,
    val expectedFinalCondition: String? = null,
    val dependencies: List<String> = prerequisiteOutcomeIds,
    val limitationNote: String? = verificationLimitationNote,
    val importance: OutcomeRequirementLevel = requirementLevel
) {
    val effectiveImportance: OutcomeRequirementLevel
        get() = if (importance == OutcomeRequirementLevel.OPTIONAL || requirementLevel == OutcomeRequirementLevel.OPTIONAL) {
            OutcomeRequirementLevel.OPTIONAL
        } else {
            OutcomeRequirementLevel.MANDATORY
        }
}

enum class IssueSeverity {
    WARNING,
    ERROR
}

/**
 * Types of validation issues detected during plan coverage analysis.
 */
enum class ValidationIssueType {
    UNCOVERED_MANDATORY_OUTCOME,
    PARTIAL_COVERAGE,
    MISSING_PREREQUISITE_STEP,
    MISSING_DEPENDENCY,
    DEPENDENCY_ORDERING_VIOLATION,
    CIRCULAR_DEPENDENCY,
    SELF_DEPENDENCY,
    OUTCOME_LACKS_VERIFICATION,
    INVALID_OUTCOME_IDENTIFIER,
    ORPHANED_STEP,
    MALFORMED_PLAN_STEP,
    CONTRADICTORY_DEPENDENCY,
    AMBIGUOUS_GOAL
}

/**
 * A specific issue flagged during plan validation.
 */
data class PlanValidationIssue(
    val issueType: ValidationIssueType,
    val outcomeId: String? = null,
    val stepIndex: Int? = null,
    val description: String,
    val isRepairable: Boolean = true,
    val severity: IssueSeverity = IssueSeverity.ERROR
)

/**
 * Validation status of a generated or repaired plan.
 */
enum class PlanValidationStatus {
    VALID,
    NEEDS_REPAIR,
    INVALID,
    AMBIGUOUS
}

/**
 * Result of coverage and dependency analysis on a [UserGoalPlan].
 */
data class PlanCoverageValidationResult(
    val status: PlanValidationStatus,
    val totalMandatoryOutcomes: Int,
    val coveredMandatoryOutcomes: Int,
    val partiallyCoveredMandatoryOutcomes: Int,
    val uncoveredMandatoryOutcomes: Int,
    val totalOptionalOutcomes: Int = 0,
    val coveredOptionalOutcomes: Int = 0,
    val outcomeCoverage: Map<String, OutcomeCoverageStatus> = emptyMap(),
    val outcomesLackingVerification: List<String> = emptyList(),
    val orphanedStepIndices: List<Int> = emptyList(),
    val missingPrerequisiteErrors: List<String> = emptyList(),
    val cyclicDependencyErrors: List<String> = emptyList(),
    val explanation: String,
    val validationIssues: List<PlanValidationIssue> = emptyList()
) {
    val isExecutable: Boolean get() = status == PlanValidationStatus.VALID
    val isValid: Boolean get() = status == PlanValidationStatus.VALID
    val issues: List<PlanValidationIssue> get() = validationIssues
    val coveredOutcomeIds: Set<String> get() = outcomeCoverage.filter { it.value == OutcomeCoverageStatus.FULLY_COVERED }.keys
    val coveragePercentage: Double get() = if (totalMandatoryOutcomes > 0) {
        (coveredMandatoryOutcomes.toDouble() / totalMandatoryOutcomes) * 100.0
    } else 100.0
}

/**
 * Record of a single plan repair iteration.
 */
/**
 * Record of a single plan repair iteration.
 */
data class PlanRepairAttempt(
    val iteration: Int,
    val appliedFixes: List<String> = emptyList(),
    val actionDescription: String = appliedFixes.joinToString("; "),
    val appliedChanges: Boolean = appliedFixes.isNotEmpty(),
    val resultingIssueCount: Int = 0,
    val isSuccessful: Boolean = false
)

/**
 * Configuration policy for plan repair attempts.
 */
data class PlanRepairPolicy(
    val maxRepairIterations: Int = 3,
    val autoRepairEnabled: Boolean = true
) {
    val maxRepairAttempts: Int get() = maxRepairIterations
}

/**
 * Overall goal completion progress across all required outcomes.
 */
enum class GoalCompletionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    PARTIALLY_SATISFIED,
    COMPLETED
}

/**
 * Observable runtime state tracking progress against [RequiredOutcome] instances.
 */
data class GoalProgressState(
    val goalId: String,
    val originalGoalText: String,
    val requiredOutcomes: List<RequiredOutcome>,
    val outcomeProgress: Map<String, OutcomeSatisfactionStatus> = emptyMap(),
    val completedStepIndices: Set<Int> = emptySet(),
    val completedStepIds: Set<String> = emptySet(),
    val completionStatus: GoalCompletionStatus = GoalCompletionStatus.NOT_STARTED,
    val completionRatio: Double = 0.0,
    val remainingMandatoryOutcomes: List<String> = emptyList(),
    val planVersion: Long = 1L
)

/**
 * Represents a structured, ordered tutorial plan generated for a user-specified goal.
 */
data class UserGoalPlan(
    val goalText: String,
    val application: ApplicationType = ApplicationType.BLENDER,
    val steps: List<UserGoalPlanStep>,
    val planId: String = UUID.randomUUID().toString(),
    val requiredOutcomes: List<RequiredOutcome> = emptyList(),
    val validationResult: PlanCoverageValidationResult? = null,
    val repairHistory: List<PlanRepairAttempt> = emptyList(),
    val constraints: List<String> = emptyList(),
    val isAmbiguous: Boolean = false,
    val clarificationPrompt: String? = null
) {
    fun toTutorSteps(): List<TutorStep> = steps.map { it.toTutorStep(steps.size) }
}

/**
 * A single step within a [UserGoalPlan].
 */
data class UserGoalPlanStep(
    val stepIndex: Int,
    val title: String,
    val instruction: String,
    val description: String = instruction,
    val targetControlName: String? = null,
    val expectedState: ExpectedState? = null,
    val conceptualHint: String? = null,
    val fallbackHint: String? = null,
    val reflectionQuestion: String? = null,
    val targetRegion: HighlightRegion? = null,
    val stepId: String = "step_$stepIndex",
    val targetedOutcomeIds: List<String> = emptyList(),
    val prerequisiteStepIds: List<String> = emptyList(),
    val verificationStrategy: VerificationStrategy = VerificationStrategy.AUTHORITATIVE_STATE,
    val skillId: String = deriveSkillIdFromStep(title, expectedState)
) {
    fun toTutorStep(totalSteps: Int): TutorStep = TutorStep(
        id = stepIndex,
        title = title,
        instruction = instruction,
        description = description,
        stepNumber = stepIndex,
        totalSteps = totalSteps,
        targetRegion = targetRegion,
        taskId = "goal_step_${stepIndex}_${title.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}",
        skillId = skillId,
        conceptualHint = conceptualHint ?: fallbackHint,
        reflectionQuestion = reflectionQuestion,
        targetControlName = targetControlName,
        expectedState = expectedState
    )
}

private fun deriveSkillIdFromStep(title: String, expected: ExpectedState?): String {
    val norm = title.lowercase()
    return when {
        expected?.operation == context.VerificationOperation.CREATE -> "blender.primitives"
        expected?.operation == context.VerificationOperation.SELECT -> "blender.selection"
        expected?.operation == context.VerificationOperation.RENAME -> "blender.rename"
        expected?.operation == context.VerificationOperation.DELETE -> "blender.delete"
        expected?.operation == context.VerificationOperation.MODIFY -> "blender.transform"
        norm.contains("mode") -> "blender.mode_switch"
        norm.contains("sphere") || norm.contains("cube") || norm.contains("cylinder") || norm.contains("add") -> "blender.primitives"
        norm.contains("select") -> "blender.selection"
        norm.contains("scale") || norm.contains("move") || norm.contains("rotate") || norm.contains("transform") -> "blender.transform"
        norm.contains("material") || norm.contains("color") -> "blender.materials"
        norm.contains("render") -> "blender.rendering"
        else -> "blender.operations"
    }
}

