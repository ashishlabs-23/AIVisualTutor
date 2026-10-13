package tutor

/**
 * Validates task coverage, outcome alignment, dependency ordering,
 * and verifiability of a [UserGoalPlan].
 */
class PlanCoverageValidator {

    /**
     * Performs a comprehensive coverage and dependency audit of [plan].
     */
    fun validateCoverage(plan: UserGoalPlan): PlanCoverageValidationResult {
        // 1. Ambiguity Guard
        if (plan.isAmbiguous) {
            val issue = PlanValidationIssue(
                issueType = ValidationIssueType.AMBIGUOUS_GOAL,
                description = plan.clarificationPrompt ?: "Goal intent is ambiguous; requires clarification before planning.",
                isRepairable = false
            )
            return PlanCoverageValidationResult(
                status = PlanValidationStatus.AMBIGUOUS,
                totalMandatoryOutcomes = 0,
                coveredMandatoryOutcomes = 0,
                partiallyCoveredMandatoryOutcomes = 0,
                uncoveredMandatoryOutcomes = 0,
                explanation = "Goal '${plan.goalText}' is ambiguous. Please clarify your specific objective.",
                validationIssues = listOf(issue)
            )
        }

        val outcomes = plan.requiredOutcomes
        val steps = plan.steps

        val mandatoryOutcomes = outcomes.filter { it.effectiveImportance == OutcomeRequirementLevel.MANDATORY }
        val optionalOutcomes = outcomes.filter { it.effectiveImportance == OutcomeRequirementLevel.OPTIONAL }

        val outcomeCoverage = mutableMapOf<String, OutcomeCoverageStatus>()
        val validationIssues = mutableListOf<PlanValidationIssue>()
        val outcomesLackingVerification = mutableListOf<String>()

        // 2. Coverage Analysis per Outcome
        var coveredMandatoryCount = 0
        var partiallyCoveredMandatoryCount = 0
        var uncoveredMandatoryCount = 0

        var coveredOptionalCount = 0

        val validOutcomeIds = outcomes.map { it.id }.toSet()
        val stepMap = steps.associateBy { it.stepId }
        val stepIndexMap = steps.associateBy { it.stepIndex }

        for (outcome in outcomes) {
            // Find all steps that explicitly claim to target this outcome
            val targetingSteps = steps.filter { it.targetedOutcomeIds.contains(outcome.id) }

            val status = when {
                targetingSteps.isEmpty() -> OutcomeCoverageStatus.UNCOVERED
                targetingSteps.any { it.instruction.isNotBlank() && (it.expectedState != null || outcome.expectedState != null || it.description.isNotBlank()) } -> {
                    OutcomeCoverageStatus.FULLY_COVERED
                }
                else -> OutcomeCoverageStatus.PARTIALLY_COVERED
            }

            outcomeCoverage[outcome.id] = status

            // Check if outcome lacks a viable verification strategy
            if (outcome.verificationStrategy == VerificationStrategy.UNSUPPORTED) {
                outcomesLackingVerification.add(outcome.id)
                validationIssues.add(
                    PlanValidationIssue(
                        issueType = ValidationIssueType.OUTCOME_LACKS_VERIFICATION,
                        outcomeId = outcome.id,
                        description = "Outcome '${outcome.description}' has no supported verification strategy (${outcome.verificationLimitationNote ?: "Unsupported"}).",
                        isRepairable = false
                    )
                )
            }

            if (outcome.effectiveImportance == OutcomeRequirementLevel.MANDATORY) {
                when (status) {
                    OutcomeCoverageStatus.FULLY_COVERED -> coveredMandatoryCount++
                    OutcomeCoverageStatus.PARTIALLY_COVERED -> {
                        partiallyCoveredMandatoryCount++
                        validationIssues.add(
                            PlanValidationIssue(
                                issueType = ValidationIssueType.PARTIAL_COVERAGE,
                                outcomeId = outcome.id,
                                description = "Mandatory outcome '${outcome.description}' is only partially covered by plan steps.",
                                isRepairable = true
                            )
                        )
                    }
                    OutcomeCoverageStatus.UNCOVERED, OutcomeCoverageStatus.UNSUPPORTED_ASSUMPTION -> {
                        uncoveredMandatoryCount++
                        validationIssues.add(
                            PlanValidationIssue(
                                issueType = ValidationIssueType.UNCOVERED_MANDATORY_OUTCOME,
                                outcomeId = outcome.id,
                                description = "Mandatory outcome '${outcome.description}' is not covered by any plan step.",
                                isRepairable = true
                            )
                        )
                    }
                }
            } else {
                if (status == OutcomeCoverageStatus.FULLY_COVERED) {
                    coveredOptionalCount++
                }
            }
        }

        // 3. Step Validation: Malformed fields, Invalid Outcomes, and Orphaned Steps
        val orphanedStepIndices = mutableListOf<Int>()
        var hasMalformedStep = false
        var hasInvalidOutcomeRef = false

        for (step in steps) {
            // Check malformed step
            if (step.stepId.isBlank() || step.stepIndex <= 0 || step.title.isBlank()) {
                hasMalformedStep = true
                validationIssues.add(
                    PlanValidationIssue(
                        issueType = ValidationIssueType.MALFORMED_PLAN_STEP,
                        stepIndex = step.stepIndex,
                        description = "Step ${step.stepIndex} is malformed: missing required identifiers or title.",
                        isRepairable = false
                    )
                )
            }

            // Check invalid outcome references
            val invalidOutcomes = step.targetedOutcomeIds.filter { !validOutcomeIds.contains(it) }
            if (invalidOutcomes.isNotEmpty()) {
                hasInvalidOutcomeRef = true
                validationIssues.add(
                    PlanValidationIssue(
                        issueType = ValidationIssueType.INVALID_OUTCOME_IDENTIFIER,
                        stepIndex = step.stepIndex,
                        description = "Step ${step.stepIndex} ('${step.title}') references nonexistent outcome(s): ${invalidOutcomes.joinToString(", ")}.",
                        isRepairable = false
                    )
                )
            }

            // Check orphaned steps
            val targetsValidOutcome = step.targetedOutcomeIds.any { validOutcomeIds.contains(it) }
            if (!targetsValidOutcome && outcomes.isNotEmpty()) {
                orphanedStepIndices.add(step.stepIndex)
                validationIssues.add(
                    PlanValidationIssue(
                        issueType = ValidationIssueType.ORPHANED_STEP,
                        stepIndex = step.stepIndex,
                        description = "Step ${step.stepIndex} ('${step.title}') does not contribute to any declared outcome.",
                        isRepairable = true
                    )
                )
            }
        }

        // 4. Dependency and Ordering Validation
        val missingPrerequisiteErrors = mutableListOf<String>()
        val cyclicDependencyErrors = mutableListOf<String>()
        var hasSelfDependency = false

        // Check self dependency
        for (step in steps) {
            if (step.prerequisiteStepIds.contains(step.stepId) || step.prerequisiteStepIds.contains("step_${step.stepIndex}")) {
                hasSelfDependency = true
                val err = "Step ${step.stepIndex} ('${step.title}') has a self-dependency on '${step.stepId}'."
                cyclicDependencyErrors.add(err)
                validationIssues.add(
                    PlanValidationIssue(
                        issueType = ValidationIssueType.SELF_DEPENDENCY,
                        stepIndex = step.stepIndex,
                        description = err,
                        isRepairable = false
                    )
                )
            }
        }

        // Check for missing dependency references
        for (step in steps) {
            for (prereqId in step.prerequisiteStepIds) {
                val exists = stepMap.containsKey(prereqId) || steps.any { "step_${it.stepIndex}" == prereqId }
                if (!exists) {
                    val err = "Step ${step.stepIndex} ('${step.title}') depends on missing prerequisite step '$prereqId'."
                    missingPrerequisiteErrors.add(err)
                    validationIssues.add(
                        PlanValidationIssue(
                            issueType = ValidationIssueType.MISSING_PREREQUISITE_STEP,
                            stepIndex = step.stepIndex,
                            description = err,
                            isRepairable = true
                        )
                    )
                }
            }
        }

        // Check for dependency cycles using DFS cycle detection
        val graph = mutableMapOf<String, MutableList<String>>()
        for (step in steps) {
            val prereqs = step.prerequisiteStepIds.filter { prereqId ->
                stepMap.containsKey(prereqId) || steps.any { "step_${it.stepIndex}" == prereqId }
            }
            graph[step.stepId] = prereqs.toMutableList()
        }

        val visited = mutableMapOf<String, Int>() // 0=unvisited, 1=visiting, 2=visited
        fun hasCycle(node: String, path: MutableList<String>): Boolean {
            visited[node] = 1
            path.add(node)
            for (neighbor in graph[node] ?: emptyList()) {
                if (visited[neighbor] == 1) {
                    val cycleDesc = "${path.joinToString(" -> ")} -> $neighbor"
                    cyclicDependencyErrors.add("Circular dependency detected: $cycleDesc")
                    validationIssues.add(
                        PlanValidationIssue(
                            issueType = ValidationIssueType.CIRCULAR_DEPENDENCY,
                            description = "Circular dependency detected involving steps: $cycleDesc",
                            isRepairable = false
                        )
                    )
                    return true
                }
                if (visited[neighbor] == null || visited[neighbor] == 0) {
                    if (hasCycle(neighbor, path)) return true
                }
            }
            path.removeAt(path.lastIndex)
            visited[node] = 2
            return false
        }

        for (step in steps) {
            if (visited[step.stepId] == null || visited[step.stepId] == 0) {
                hasCycle(step.stepId, mutableListOf())
            }
        }

        // Check for forward-reference ordering (prerequisites must precede dependent steps)
        var hasOrderingError = false
        for (i in steps.indices) {
            val step = steps[i]
            for (prereqId in step.prerequisiteStepIds) {
                val prereqStep = stepMap[prereqId] ?: steps.find { "step_${it.stepIndex}" == prereqId }
                if (prereqStep != null) {
                    val prereqIndex = steps.indexOf(prereqStep)
                    if (prereqIndex > i) {
                        hasOrderingError = true
                        val err = "Step ${step.stepIndex} ('${step.title}') appears at index $i before its prerequisite '${prereqStep.title}' at index $prereqIndex."
                        missingPrerequisiteErrors.add(err)
                        validationIssues.add(
                            PlanValidationIssue(
                                issueType = ValidationIssueType.DEPENDENCY_ORDERING_VIOLATION,
                                stepIndex = step.stepIndex,
                                description = err,
                                isRepairable = true
                            )
                        )
                    }
                }
            }
        }

        // 5. Final Validation Status Determination
        val overallStatus = when {
            hasMalformedStep || hasInvalidOutcomeRef -> PlanValidationStatus.INVALID
            hasSelfDependency || cyclicDependencyErrors.isNotEmpty() -> PlanValidationStatus.INVALID
            outcomesLackingVerification.isNotEmpty() -> PlanValidationStatus.INVALID
            missingPrerequisiteErrors.any { it.contains("missing prerequisite") && !hasOrderingError } -> PlanValidationStatus.NEEDS_REPAIR
            hasOrderingError -> PlanValidationStatus.NEEDS_REPAIR
            uncoveredMandatoryCount > 0 || partiallyCoveredMandatoryCount > 0 -> PlanValidationStatus.NEEDS_REPAIR
            else -> PlanValidationStatus.VALID
        }

        val explanation = when (overallStatus) {
            PlanValidationStatus.VALID -> {
                "Plan covers all $coveredMandatoryCount mandatory outcomes with valid prerequisite ordering."
            }
            PlanValidationStatus.NEEDS_REPAIR -> {
                val issues = mutableListOf<String>()
                if (uncoveredMandatoryCount > 0) issues.add("$uncoveredMandatoryCount mandatory outcome(s) uncovered")
                if (partiallyCoveredMandatoryCount > 0) issues.add("$partiallyCoveredMandatoryCount outcome(s) partially covered")
                if (hasOrderingError) issues.add("Step ordering violates prerequisites")
                if (missingPrerequisiteErrors.isNotEmpty()) issues.add("Prerequisite dependencies missing")
                "Plan needs repair: ${issues.joinToString(", ")}."
            }
            PlanValidationStatus.INVALID -> {
                "Plan is invalid: ${cyclicDependencyErrors.joinToString("; ")}."
            }
            PlanValidationStatus.AMBIGUOUS -> {
                "Goal is ambiguous."
            }
        }

        return PlanCoverageValidationResult(
            status = overallStatus,
            totalMandatoryOutcomes = mandatoryOutcomes.size,
            coveredMandatoryOutcomes = coveredMandatoryCount,
            partiallyCoveredMandatoryOutcomes = partiallyCoveredMandatoryCount,
            uncoveredMandatoryOutcomes = uncoveredMandatoryCount,
            totalOptionalOutcomes = optionalOutcomes.size,
            coveredOptionalOutcomes = coveredOptionalCount,
            outcomeCoverage = outcomeCoverage,
            outcomesLackingVerification = outcomesLackingVerification,
            orphanedStepIndices = orphanedStepIndices,
            missingPrerequisiteErrors = missingPrerequisiteErrors,
            cyclicDependencyErrors = cyclicDependencyErrors,
            explanation = explanation,
            validationIssues = validationIssues
        )
    }
}
