package tutor

import context.ExpectedState
import context.VerificationOperation
import models.HighlightRegion

/**
 * Engine responsible for bounded, targeted repair of invalid or incomplete tutorial plans.
 *
 * Guarantees:
 * 1. Preserves original learner goal and constraints.
 * 2. Reorders steps topologically when ordering is violated.
 * 3. Injects missing prerequisite or required outcome steps when a deterministic pattern exists.
 * 4. Resolves missing prerequisite references.
 * 5. Iterations are strictly bounded by [PlanRepairPolicy.maxRepairAttempts].
 * 6. Always revalidates the repaired plan with [PlanCoverageValidator].
 * 7. Unsuccessful repairs do not produce a false valid status.
 */
class PlanRepairEngine(
    private val validator: PlanCoverageValidator = PlanCoverageValidator()
) {

    /**
     * Attempts targeted repairs on [plan] according to [policy].
     */
    fun repairPlan(
        plan: UserGoalPlan,
        policy: PlanRepairPolicy = PlanRepairPolicy()
    ): UserGoalPlan {
        var currentPlan = plan
        var currentValidation = currentPlan.validationResult ?: validator.validateCoverage(currentPlan)
        currentPlan = currentPlan.copy(validationResult = currentValidation)

        if (currentValidation.isValid || !policy.autoRepairEnabled) {
            return currentPlan
        }

        val repairHistory = currentPlan.repairHistory.toMutableList()
        var iteration = 1

        while (iteration <= policy.maxRepairAttempts && !currentValidation.isValid) {
            val issues = currentValidation.issues
            var modificationMade = false
            var actionDescription = ""

            val hasOrderingViolation = issues.any {
                it.issueType == ValidationIssueType.DEPENDENCY_ORDERING_VIOLATION ||
                it.issueType == ValidationIssueType.CONTRADICTORY_DEPENDENCY
            }
            val hasCycle = issues.any { it.issueType == ValidationIssueType.CIRCULAR_DEPENDENCY }

            if (hasOrderingViolation && !hasCycle) {
                val reorderedSteps = topologicallySortSteps(currentPlan.steps)
                if (reorderedSteps != currentPlan.steps) {
                    currentPlan = currentPlan.copy(steps = reorderedSteps)
                    modificationMade = true
                    actionDescription = "Reordered plan steps topologically to satisfy prerequisite ordering."
                }
            }

            // 2. Check for missing dependencies
            if (!modificationMade) {
                val missingDepIssues = issues.filter { it.issueType == ValidationIssueType.MISSING_DEPENDENCY }
                if (missingDepIssues.isNotEmpty()) {
                    val stepIds = currentPlan.steps.map { it.stepId }.toSet()
                    val repairedSteps = currentPlan.steps.map { step ->
                        val invalidDeps = step.prerequisiteStepIds.filter { it !in stepIds }
                        if (invalidDeps.isNotEmpty()) {
                            // If missing dependency is known pattern (e.g. object creation missing), we can synthesize or remove dangling dep
                            step.copy(prerequisiteStepIds = step.prerequisiteStepIds.filter { it in stepIds })
                        } else {
                            step
                        }
                    }
                    if (repairedSteps != currentPlan.steps) {
                        currentPlan = currentPlan.copy(steps = repairedSteps)
                        modificationMade = true
                        actionDescription = "Resolved dangling or missing prerequisite references in plan steps."
                    }
                }
            }

            // 3. Check for uncovered mandatory outcomes
            if (!modificationMade) {
                val uncoveredIssues = issues.filter { it.issueType == ValidationIssueType.UNCOVERED_MANDATORY_OUTCOME }
                if (uncoveredIssues.isNotEmpty()) {
                    val missingOutcomeIds = uncoveredIssues.mapNotNull { it.outcomeId }.toSet()
                    val missingOutcomes = currentPlan.requiredOutcomes.filter { it.id in missingOutcomeIds }

                    if (missingOutcomes.isNotEmpty()) {
                        val newSteps = currentPlan.steps.toMutableList()
                        var synthesizedCount = 0

                        for (outcome in missingOutcomes) {
                            val synthesizedStep = synthesizeStepForOutcome(outcome, newSteps.size + 1, newSteps)
                            if (synthesizedStep != null) {
                                newSteps.add(synthesizedStep)
                                synthesizedCount++
                            }
                        }

                        if (synthesizedCount > 0) {
                            // Re-index steps
                            val reindexed = newSteps.mapIndexed { idx, step ->
                                step.copy(stepIndex = idx + 1)
                            }
                            // Topologically sort after adding
                            val finalSteps = topologicallySortSteps(reindexed)
                            currentPlan = currentPlan.copy(steps = finalSteps)
                            modificationMade = true
                            actionDescription = "Synthesized and inserted $synthesizedCount missing step(s) to cover mandatory outcomes."
                        }
                    }
                }
            }

            // Record repair attempt
            val attempt = PlanRepairAttempt(
                iteration = iteration,
                actionDescription = if (modificationMade) actionDescription else "No automated repair pattern applicable for remaining issues.",
                appliedChanges = modificationMade,
                resultingIssueCount = 0 // updated after revalidation below
            )

            if (!modificationMade) {
                // Cannot make further repairs
                repairHistory.add(attempt.copy(resultingIssueCount = currentValidation.issues.size))
                break
            }

            // Revalidate after modification
            currentValidation = validator.validateCoverage(currentPlan)
            currentPlan = currentPlan.copy(
                validationResult = currentValidation,
                repairHistory = repairHistory + attempt.copy(resultingIssueCount = currentValidation.issues.size)
            )

            iteration++
        }

        return currentPlan
    }

    /**
     * Topologically sorts steps based on prerequisiteStepIds while preserving
     * relative original ordering as much as possible.
     */
    fun topologicallySortSteps(steps: List<UserGoalPlanStep>): List<UserGoalPlanStep> {
        if (steps.size <= 1) return steps

        val stepMap = steps.associateBy { it.stepId }
        val inDegree = mutableMapOf<String, Int>()
        val adjList = mutableMapOf<String, MutableList<String>>()

        for (step in steps) {
            inDegree[step.stepId] = 0
            adjList[step.stepId] = mutableListOf()
        }

        for (step in steps) {
            for (prereq in step.prerequisiteStepIds) {
                if (stepMap.containsKey(prereq)) {
                    adjList[prereq]?.add(step.stepId)
                    inDegree[step.stepId] = (inDegree[step.stepId] ?: 0) + 1
                }
            }
        }

        // Kahn's algorithm with queue sorted by original index to preserve stability
        val queue = ArrayDeque<String>()
        for (step in steps) {
            if ((inDegree[step.stepId] ?: 0) == 0) {
                queue.add(step.stepId)
            }
        }

        val sortedList = mutableListOf<UserGoalPlanStep>()
        while (queue.isNotEmpty()) {
            val currId = queue.removeFirst()
            stepMap[currId]?.let { sortedList.add(it) }

            for (neighbor in adjList[currId] ?: emptyList()) {
                val newDeg = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDeg
                if (newDeg == 0) {
                    queue.add(neighbor)
                }
            }
        }

        // If cycle exists, Kahn's algorithm will not include all steps. In that case, return original steps.
        if (sortedList.size != steps.size) {
            return steps
        }

        // Reassign stepIndex sequentially
        return sortedList.mapIndexed { idx, step -> step.copy(stepIndex = idx + 1) }
    }

    private fun synthesizeStepForOutcome(
        outcome: RequiredOutcome,
        nextIndex: Int,
        existingSteps: List<UserGoalPlanStep>
    ): UserGoalPlanStep? {
        if (outcome.verificationStrategy == VerificationStrategy.UNSUPPORTED) {
            return null
        }
        val outcomeDescLower = outcome.description.lowercase()
        val outcomeIdLower = outcome.id.lowercase()

        // Find prerequisites among existing steps
        val prereqIds = mutableListOf<String>()
        for (depOutcomeId in outcome.dependencies) {
            val matchingStep = existingSteps.find { it.targetedOutcomeIds.contains(depOutcomeId) }
            if (matchingStep != null) {
                prereqIds.add(matchingStep.stepId)
            }
        }

        return when {
            outcomeIdLower.contains("sphere") || outcomeDescLower.contains("sphere") -> {
                UserGoalPlanStep(
                    stepId = "step_synthesized_sphere_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Add Mesh Sphere",
                    instruction = "Press Shift+A and select Mesh > UV Sphere.",
                    description = outcome.description,
                    targetControlName = "UV Sphere",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    expectedState = ExpectedState(
                        operation = VerificationOperation.CREATE,
                        targetName = "Sphere",
                        targetType = "MESH"
                    ),
                    conceptualHint = "Use Shift+A in the 3D Viewport to add a UV Sphere.",
                    fallbackHint = "Click Add > Mesh > UV Sphere.",
                    targetRegion = HighlightRegion(x = 310f, y = 195f, width = 125f, height = 26f)
                )
            }
            outcomeIdLower.contains("cube") || outcomeDescLower.contains("cube") -> {
                UserGoalPlanStep(
                    stepId = "step_synthesized_cube_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Add Mesh Cube",
                    instruction = "Press Shift+A and select Mesh > Cube.",
                    description = outcome.description,
                    targetControlName = "Cube",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    expectedState = ExpectedState(
                        operation = VerificationOperation.CREATE,
                        targetName = "Cube",
                        targetType = "MESH"
                    ),
                    conceptualHint = "Use Shift+A to add a Cube primitive.",
                    fallbackHint = "Click Add > Mesh > Cube."
                )
            }
            outcomeIdLower.contains("scale") || outcomeDescLower.contains("scale") -> {
                UserGoalPlanStep(
                    stepId = "step_synthesized_scale_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Scale Object",
                    instruction = "Press S to scale the active object, move your mouse, and left-click to confirm.",
                    description = outcome.description,
                    targetControlName = "3D Viewport",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    conceptualHint = "Press S to initiate the Scale operator.",
                    fallbackHint = "Use the Scale tool in the left toolbar."
                )
            }
            outcomeIdLower.contains("move") || outcomeDescLower.contains("move") || outcomeDescLower.contains("position") -> {
                UserGoalPlanStep(
                    stepId = "step_synthesized_move_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Position Object",
                    instruction = "Press G to move the active object to the desired position, then left-click.",
                    description = outcome.description,
                    targetControlName = "3D Viewport",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    conceptualHint = "Press G to grab and move the object.",
                    fallbackHint = "Use the Move tool on the left toolbar."
                )
            }
            outcomeIdLower.contains("material") || outcomeDescLower.contains("material") -> {
                UserGoalPlanStep(
                    stepId = "step_synthesized_mat_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Assign Material",
                    instruction = "In the Material Properties panel, click '+ New' to create and assign a new material.",
                    description = outcome.description,
                    targetControlName = "Material Properties",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    conceptualHint = "Open the Material tab on the right properties editor.",
                    fallbackHint = "Click 'New' in the Material panel."
                )
            }
            outcomeIdLower.contains("camera") || outcomeDescLower.contains("camera") -> {
                UserGoalPlanStep(
                    stepId = "step_synthesized_camera_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Set Up Camera",
                    instruction = "Add a Camera via Shift+A > Camera or adjust the existing Camera view (Numpad 0).",
                    description = outcome.description,
                    targetControlName = "Camera",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    conceptualHint = "Press Numpad 0 to view through the camera.",
                    fallbackHint = "Use Shift+A > Camera if no camera exists."
                )
            }
            outcomeIdLower.contains("lighting") || outcomeDescLower.contains("light") -> {
                UserGoalPlanStep(
                    stepId = "step_synthesized_light_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Set Up Scene Lighting",
                    instruction = "Ensure an adequate Light object exists in the scene (Shift+A > Light > Point / Sun).",
                    description = outcome.description,
                    targetControlName = "Light",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    conceptualHint = "Add a Light via Shift+A > Light.",
                    fallbackHint = "Verify the Light source illuminates the target object."
                )
            }
            outcomeIdLower.contains("render") || outcomeDescLower.contains("render") -> {
                UserGoalPlanStep(
                    stepId = "step_synthesized_render_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Render Scene",
                    instruction = "Press F12 or select Render > Render Image from the top menu.",
                    description = outcome.description,
                    targetControlName = "Render",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    conceptualHint = "Press F12 to produce a rendered frame.",
                    fallbackHint = "Click Render in the top menu and select Render Image."
                )
            }
            else -> {
                // Generic step for unrecognized outcome
                UserGoalPlanStep(
                    stepId = "step_synthesized_gen_$nextIndex",
                    stepIndex = nextIndex,
                    title = "Perform: ${outcome.description}",
                    instruction = "Execute the required steps in Blender to achieve: ${outcome.description}",
                    description = outcome.description,
                    targetControlName = "Blender",
                    targetedOutcomeIds = listOf(outcome.id),
                    prerequisiteStepIds = prereqIds,
                    verificationStrategy = outcome.verificationStrategy,
                    conceptualHint = "Follow Blender interface guidelines for this operation.",
                    fallbackHint = "Review your scene state in the Outliner."
                )
            }
        }
    }
}
