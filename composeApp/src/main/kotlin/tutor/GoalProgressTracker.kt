package tutor

/**
 * Tracks execution progress against a [UserGoalPlan] and its [RequiredOutcome] instances.
 *
 * Guarantees:
 * 1. Plan validity is strictly decoupled from execution progress.
 * 2. Generating or approving a plan does not complete any outcomes or the goal.
 * 3. An outcome requiring multiple steps is only SATISFIED once all targeting steps are verified.
 * 4. Overall goal completion requires all mandatory outcomes to be SATISFIED.
 * 5. Optional outcomes cannot substitute for mandatory outcomes.
 * 6. Steps cannot execute before their prerequisite steps are completed and verified.
 * 7. Active re-planning preserves valid completed progress while incrementing plan version
 *    to prevent stale verification responses from corrupting state.
 */
class GoalProgressTracker {

    var currentPlan: UserGoalPlan? = null
        private set

    var planVersion: Long = 1L
        private set

    private val completedStepIds = mutableSetOf<String>()
    private val completedStepIndices = mutableSetOf<Int>()
    private val outcomeProgress = mutableMapOf<String, OutcomeSatisfactionStatus>()

    /**
     * Initializes tracking for a new or replaced [plan].
     *
     * If [preserveExistingProgress] is true, matching completed steps are retained.
     */
    fun initializePlan(plan: UserGoalPlan, preserveExistingProgress: Boolean = false): GoalProgressState {
        currentPlan = plan

        if (!preserveExistingProgress) {
            completedStepIds.clear()
            completedStepIndices.clear()
            outcomeProgress.clear()
            for (outcome in plan.requiredOutcomes) {
                outcomeProgress[outcome.id] = OutcomeSatisfactionStatus.NOT_STARTED
            }
        } else {
            // Retain steps that exist in the new plan
            val newStepIds = plan.steps.map { it.stepId }.toSet()
            completedStepIds.retainAll(newStepIds)

            completedStepIndices.clear()
            plan.steps.forEach { step ->
                if (step.stepId in completedStepIds) {
                    completedStepIndices.add(step.stepIndex)
                }
            }

            // Recompute outcome progress
            outcomeProgress.clear()
            for (outcome in plan.requiredOutcomes) {
                outcomeProgress[outcome.id] = computeOutcomeStatus(outcome, plan, completedStepIds)
            }
        }

        return computeCurrentProgressState()
    }

    /**
     * Safely updates to a revised plan during active tutoring session.
     * Increments [planVersion] to invalidate any in-flight asynchronous operations.
     */
    fun replan(newPlan: UserGoalPlan): GoalProgressState {
        planVersion++
        return initializePlan(newPlan, preserveExistingProgress = true)
    }

    /**
     * Checks whether all prerequisite steps for [step] have been completed and verified.
     */
    fun canExecuteStep(step: UserGoalPlanStep): Boolean {
        if (step.prerequisiteStepIds.isEmpty()) return true
        return completedStepIds.containsAll(step.prerequisiteStepIds)
    }

    /**
     * Checks whether all prerequisite steps for a step index have been completed.
     */
    fun canExecuteStepIndex(stepIndex: Int): Boolean {
        val step = currentPlan?.steps?.find { it.stepIndex == stepIndex } ?: return true
        return canExecuteStep(step)
    }

    /**
     * Records a verification result for a step.
     *
     * @param stepId Identifier of the step being verified.
     * @param verified True if Blender verification succeeded.
     * @param operationPlanVersion Version epoch of the plan when the verification was initiated.
     *                             Stale operations (version < [planVersion]) are safely rejected.
     */
    fun recordStepVerification(
        stepId: String,
        verified: Boolean,
        operationPlanVersion: Long = planVersion
    ): GoalProgressState {
        // Guard against stale asynchronous verification responses from superseded plans
        if (operationPlanVersion < planVersion) {
            return computeCurrentProgressState()
        }

        val plan = currentPlan ?: return computeCurrentProgressState()
        val step = plan.steps.find { it.stepId == stepId }

        if (verified) {
            completedStepIds.add(stepId)
            if (step != null) {
                completedStepIndices.add(step.stepIndex)
            }

            // Update outcomes targeted by this step
            val targetedOutcomes = if (step != null && step.targetedOutcomeIds.isNotEmpty()) {
                plan.requiredOutcomes.filter { it.id in step.targetedOutcomeIds }
            } else {
                emptyList()
            }

            for (outcome in targetedOutcomes) {
                outcomeProgress[outcome.id] = computeOutcomeStatus(outcome, plan, completedStepIds)
            }
        } else {
            // Failed verification does not add to completedStepIds
            val targetedOutcomes = if (step != null && step.targetedOutcomeIds.isNotEmpty()) {
                plan.requiredOutcomes.filter { it.id in step.targetedOutcomeIds }
            } else {
                emptyList()
            }

            for (outcome in targetedOutcomes) {
                // If it wasn't satisfied before, mark it as FAILED or IN_PROGRESS
                val currentStatus = outcomeProgress[outcome.id]
                if (currentStatus != OutcomeSatisfactionStatus.SATISFIED) {
                    outcomeProgress[outcome.id] = OutcomeSatisfactionStatus.FAILED
                }
            }
        }

        return computeCurrentProgressState()
    }

    /**
     * Records verification using step index for backwards compatibility.
     */
    fun recordStepIndexVerification(
        stepIndex: Int,
        verified: Boolean,
        operationPlanVersion: Long = planVersion
    ): GoalProgressState {
        val step = currentPlan?.steps?.find { it.stepIndex == stepIndex }
        val stepId = step?.stepId ?: "step_$stepIndex"
        return recordStepVerification(stepId, verified, operationPlanVersion)
    }

    /**
     * Returns true if and only if all mandatory outcomes are fully SATISFIED.
     */
    fun isGoalCompleted(): Boolean {
        val plan = currentPlan ?: return false
        val mandatoryOutcomes = plan.requiredOutcomes.filter { it.importance == OutcomeRequirementLevel.MANDATORY }

        if (mandatoryOutcomes.isEmpty()) {
            // If no explicit outcomes, fallback to checking whether all steps were verified
            return plan.steps.isNotEmpty() && completedStepIds.size == plan.steps.size
        }

        return mandatoryOutcomes.all { outcome ->
            outcomeProgress[outcome.id] == OutcomeSatisfactionStatus.SATISFIED
        }
    }

    /**
     * Returns the current progress state.
     */
    fun getCurrentState(): GoalProgressState = computeCurrentProgressState()

    fun getCompletedStepIds(): Set<String> = completedStepIds.toSet()

    fun getCompletedStepIndices(): Set<Int> = completedStepIndices.toSet()

    private fun computeOutcomeStatus(
        outcome: RequiredOutcome,
        plan: UserGoalPlan,
        completedSteps: Set<String>
    ): OutcomeSatisfactionStatus {
        val targetingSteps = plan.steps.filter { it.targetedOutcomeIds.contains(outcome.id) }
        if (targetingSteps.isEmpty()) {
            return OutcomeSatisfactionStatus.NOT_STARTED
        }

        val completedCount = targetingSteps.count { it.stepId in completedSteps }
        return when {
            completedCount == targetingSteps.size -> OutcomeSatisfactionStatus.SATISFIED
            completedCount > 0 -> OutcomeSatisfactionStatus.IN_PROGRESS
            else -> OutcomeSatisfactionStatus.NOT_STARTED
        }
    }

    private fun computeCurrentProgressState(): GoalProgressState {
        val plan = currentPlan
        if (plan == null) {
            return GoalProgressState(
                goalId = "",
                originalGoalText = "",
                requiredOutcomes = emptyList(),
                planVersion = planVersion
            )
        }

        val mandatoryOutcomes = plan.requiredOutcomes.filter { it.importance == OutcomeRequirementLevel.MANDATORY }
        val remainingMandatory = mandatoryOutcomes
            .filter { outcomeProgress[it.id] != OutcomeSatisfactionStatus.SATISFIED }
            .map { it.description }

        val satisfiedMandatoryCount = mandatoryOutcomes.count {
            outcomeProgress[it.id] == OutcomeSatisfactionStatus.SATISFIED
        }

        val totalMandatory = mandatoryOutcomes.size
        val ratio = if (totalMandatory > 0) {
            satisfiedMandatoryCount.toDouble() / totalMandatory.toDouble()
        } else if (plan.steps.isNotEmpty()) {
            completedStepIds.size.toDouble() / plan.steps.size.toDouble()
        } else {
            0.0
        }

        val completionStatus = when {
            isGoalCompleted() -> GoalCompletionStatus.COMPLETED
            satisfiedMandatoryCount > 0 -> GoalCompletionStatus.PARTIALLY_SATISFIED
            completedStepIds.isNotEmpty() -> GoalCompletionStatus.IN_PROGRESS
            else -> GoalCompletionStatus.NOT_STARTED
        }

        return GoalProgressState(
            goalId = plan.planId,
            originalGoalText = plan.goalText,
            requiredOutcomes = plan.requiredOutcomes,
            outcomeProgress = outcomeProgress.toMap(),
            completedStepIndices = completedStepIndices.toSet(),
            completedStepIds = completedStepIds.toSet(),
            completionStatus = completionStatus,
            completionRatio = ratio,
            remainingMandatoryOutcomes = remainingMandatory,
            planVersion = planVersion
        )
    }
}
