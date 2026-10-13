package tutor

import context.ExpectedState
import context.VerificationOperation
import models.ApplicationType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 12 Unit Tests P12-01 through P12-29.
 * Validates goal decomposition, coverage validation, dependency management,
 * plan repair, and progress tracking.
 */
class PlanCoverageValidatorTest {

    private val validator = PlanCoverageValidator()
    private val repairEngine = PlanRepairEngine(validator)
    private val planner = UserGoalPlanner(validator, repairEngine)

    // ==========================================
    // GOAL DECOMPOSITION (P12-01 to P12-05)
    // ==========================================

    @Test
    fun `P12-01 A simple goal produces the required outcomes`() {
        val plan = planner.planGoal("Teach me to create a UV Sphere in Blender")

        assertFalse(plan.isAmbiguous)
        assertTrue(plan.requiredOutcomes.isNotEmpty(), "Plan must have decomposed outcomes")
        val sphereOutcome = plan.requiredOutcomes.find { it.description.contains("Sphere", ignoreCase = true) }
        assertNotNull(sphereOutcome, "Plan must contain a Sphere existence outcome")
        assertEquals(OutcomeRequirementLevel.MANDATORY, sphereOutcome.importance)
        assertEquals(VerificationStrategy.AUTHORITATIVE_STATE, sphereOutcome.verificationStrategy)
    }

    @Test
    fun `P12-02 A compound goal is decomposed into multiple meaningful outcomes`() {
        val plan = planner.planGoal("Create a sphere, scale it, and move it above the ground plane.")

        assertFalse(plan.isAmbiguous)
        assertTrue(plan.requiredOutcomes.size >= 3, "Compound goal should produce at least 3 outcomes")
        val ids = plan.requiredOutcomes.map { it.id }
        assertTrue(ids.any { it.contains("sphere", ignoreCase = true) }, "Must have sphere outcome")
        assertTrue(ids.any { it.contains("scale", ignoreCase = true) }, "Must have scale outcome")
        assertTrue(ids.any { it.contains("move", ignoreCase = true) }, "Must have move outcome")
    }

    @Test
    fun `P12-03 The original learner request is preserved`() {
        val originalGoal = "Teach me to create a UV Sphere in Blender"
        val plan = planner.planGoal(originalGoal)

        assertEquals(originalGoal, plan.goalText, "Original learner request text must be preserved exactly")
    }

    @Test
    fun `P12-04 Explicit constraints are not silently discarded`() {
        val goalWithConstraint = "Move Cube to (0.0, 0.0, 2.0)"
        val plan = planner.planGoal(goalWithConstraint)

        val moveStep = plan.steps.find { it.expectedState?.operation == VerificationOperation.MODIFY }
        assertNotNull(moveStep, "Should contain a modify step with location property")
        assertEquals("0.0, 0.0, 2.0", moveStep.expectedState?.expectedPropertyValue)
    }

    @Test
    fun `P12-05 Ambiguous goals are handled appropriately`() {
        val ambiguousGoal = "make something"
        val plan = planner.planGoal(ambiguousGoal)

        assertTrue(plan.isAmbiguous, "Ambiguous goal must be marked isAmbiguous")
        assertNotNull(plan.clarificationPrompt, "Ambiguous goal must provide clarification prompt")
        assertEquals(PlanValidationStatus.AMBIGUOUS, plan.validationResult?.status)
    }

    // ==========================================
    // COVERAGE VALIDATION (P12-06 to P12-12)
    // ==========================================

    @Test
    fun `P12-06 A complete plan covers every mandatory outcome`() {
        val plan = planner.planCreateSphere("Create a UV sphere")
        val result = validator.validateCoverage(plan)

        assertTrue(result.isValid, "Valid plan must pass validation")
        assertEquals(PlanValidationStatus.VALID, result.status)
        assertEquals(result.totalMandatoryOutcomes, result.coveredMandatoryOutcomes)
        assertEquals(0, result.uncoveredMandatoryOutcomes)
    }

    @Test
    fun `P12-07 An uncovered mandatory outcome causes validation failure`() {
        val rawPlan = planner.planCreateSphere("Create sphere")
        // Remove steps targeting sphere creation
        val incompleteSteps = rawPlan.steps.filter { !it.targetedOutcomeIds.contains("out_sphere_exists") }
        val incompletePlan = rawPlan.copy(steps = incompleteSteps)

        val result = validator.validateCoverage(incompletePlan)

        assertFalse(result.isValid, "Plan with uncovered mandatory outcome must not be valid")
        assertTrue(result.uncoveredMandatoryOutcomes > 0)
        assertTrue(result.validationIssues.any { it.issueType == ValidationIssueType.UNCOVERED_MANDATORY_OUTCOME })
    }

    @Test
    fun `P12-08 A partially covered outcome is not reported as fully covered`() {
        val outcome = RequiredOutcome(
            id = "out_complex",
            description = "Complex modeling task",
            importance = OutcomeRequirementLevel.MANDATORY
        )
        // Step with blank instruction and no expected state
        val step = UserGoalPlanStep(
            stepId = "step_1",
            stepIndex = 1,
            title = "Incomplete step",
            instruction = "",
            description = "",
            targetedOutcomeIds = listOf("out_complex")
        )
        val plan = UserGoalPlan(
            goalText = "Complex test",
            requiredOutcomes = listOf(outcome),
            steps = listOf(step)
        )

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertEquals(OutcomeCoverageStatus.PARTIALLY_COVERED, result.outcomeCoverage["out_complex"])
    }

    @Test
    fun `P12-09 An outcome is not covered solely by keyword similarity`() {
        val outcome = RequiredOutcome(
            id = "out_sphere_target",
            description = "A UV Sphere exists in the scene",
            importance = OutcomeRequirementLevel.MANDATORY
        )
        // Step mentions "Sphere" in instruction but does NOT list out_sphere_target in targetedOutcomeIds
        val step = UserGoalPlanStep(
            stepId = "step_1",
            stepIndex = 1,
            title = "Unlinked Sphere instruction",
            instruction = "Think about spheres and shapes in Blender.",
            description = "Ponder sphere geometry.",
            targetedOutcomeIds = emptyList() // Not linked!
        )
        val plan = UserGoalPlan(
            goalText = "Think spheres",
            requiredOutcomes = listOf(outcome),
            steps = listOf(step)
        )

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid, "Step mentioning keyword without explicit target must not cover outcome")
        assertEquals(OutcomeCoverageStatus.UNCOVERED, result.outcomeCoverage["out_sphere_target"])
    }

    @Test
    fun `P12-10 An optional enhancement cannot replace a mandatory outcome`() {
        val mandatoryOutcome = RequiredOutcome(
            id = "out_mandatory",
            description = "Mandatory Base Mesh",
            importance = OutcomeRequirementLevel.MANDATORY
        )
        val optionalOutcome = RequiredOutcome(
            id = "out_optional",
            description = "Optional Wireframe Display",
            importance = OutcomeRequirementLevel.OPTIONAL
        )
        // Only the optional outcome is targeted
        val step = UserGoalPlanStep(
            stepId = "step_1",
            stepIndex = 1,
            title = "Toggle Wireframe",
            instruction = "Press Shift+Z to enable wireframe display.",
            targetedOutcomeIds = listOf("out_optional")
        )
        val plan = UserGoalPlan(
            goalText = "Mesh and wireframe",
            requiredOutcomes = listOf(mandatoryOutcome, optionalOutcome),
            steps = listOf(step)
        )

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid, "Covered optional outcome cannot compensate for uncovered mandatory outcome")
        assertEquals(1, result.coveredOptionalOutcomes)
        assertEquals(1, result.uncoveredMandatoryOutcomes)
    }

    @Test
    fun `P12-11 Orphaned steps are detected or reported according to the defined policy`() {
        val outcome = RequiredOutcome(id = "out_1", description = "Target outcome")
        val step1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Valid Step", instruction = "Do valid", targetedOutcomeIds = listOf("out_1"))
        val step2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Orphan Step", instruction = "Do orphan", targetedOutcomeIds = emptyList())

        val plan = UserGoalPlan(
            goalText = "Orphan test",
            requiredOutcomes = listOf(outcome),
            steps = listOf(step1, step2)
        )

        val result = validator.validateCoverage(plan)
        assertTrue(result.orphanedStepIndices.contains(2), "Step 2 should be flagged as orphaned")
    }

    @Test
    fun `P12-12 Outcomes without a usable verification strategy are reported`() {
        val unsupportedOutcome = RequiredOutcome(
            id = "out_unsupported",
            description = "Unsupported render shader bake",
            verificationStrategy = VerificationStrategy.UNSUPPORTED,
            verificationLimitationNote = "Telemetry cannot monitor shader bake progress"
        )
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Bake Shader",
            instruction = "Click Bake.",
            targetedOutcomeIds = listOf("out_unsupported")
        )
        val plan = UserGoalPlan(
            goalText = "Bake shader",
            requiredOutcomes = listOf(unsupportedOutcome),
            steps = listOf(step)
        )

        val result = validator.validateCoverage(plan)
        assertTrue(result.outcomesLackingVerification.contains("out_unsupported"))
    }

    // ==========================================
    // DEPENDENCIES (P12-13 to P12-16)
    // ==========================================

    @Test
    fun `P12-13 A valid dependency order is accepted`() {
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Step 1", instruction = "Do 1", targetedOutcomeIds = listOf("out_1"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Step 2", instruction = "Do 2", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("s1"))
        val plan = UserGoalPlan(
            goalText = "Dependency test",
            requiredOutcomes = listOf(RequiredOutcome("out_1", "Outcome 1")),
            steps = listOf(s1, s2)
        )

        val result = validator.validateCoverage(plan)
        assertTrue(result.isValid)
        assertTrue(result.missingPrerequisiteErrors.isEmpty())
        assertTrue(result.cyclicDependencyErrors.isEmpty())
    }

    @Test
    fun `P12-14 Missing dependencies are detected`() {
        val s1 = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Dependent Step",
            instruction = "Do action",
            targetedOutcomeIds = listOf("out_1"),
            prerequisiteStepIds = listOf("non_existent_step")
        )
        val plan = UserGoalPlan(
            goalText = "Missing dep test",
            requiredOutcomes = listOf(RequiredOutcome("out_1", "Outcome 1")),
            steps = listOf(s1)
        )

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertTrue(result.missingPrerequisiteErrors.isNotEmpty())
        assertTrue(result.validationIssues.any { it.issueType == ValidationIssueType.MISSING_PREREQUISITE_STEP })
    }

    @Test
    fun `P12-15 Circular dependencies are detected`() {
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Step 1", instruction = "Do 1", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("s2"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Step 2", instruction = "Do 2", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("s1"))
        val plan = UserGoalPlan(
            goalText = "Circular test",
            requiredOutcomes = listOf(RequiredOutcome("out_1", "Outcome 1")),
            steps = listOf(s1, s2)
        )

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertTrue(result.cyclicDependencyErrors.isNotEmpty(), "Cycle should be detected in cyclicDependencyErrors")
        assertTrue(result.validationIssues.any { it.issueType == ValidationIssueType.CIRCULAR_DEPENDENCY })
    }

    @Test
    fun `P12-16 Dependent steps cannot execute before required prerequisites are satisfied`() {
        val tracker = GoalProgressTracker()
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Create Sphere", instruction = "Do 1", targetedOutcomeIds = listOf("out_1"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Scale Sphere", instruction = "Do 2", targetedOutcomeIds = listOf("out_2"), prerequisiteStepIds = listOf("s1"))
        val plan = UserGoalPlan(
            goalText = "Prereq execution test",
            requiredOutcomes = listOf(RequiredOutcome("out_1", "Sphere"), RequiredOutcome("out_2", "Scaled")),
            steps = listOf(s1, s2)
        )

        tracker.initializePlan(plan)
        // Step 2 depends on Step 1, which has not been verified yet
        assertFalse(tracker.canExecuteStep(s2), "Step 2 must not be executable before Step 1 is verified")
        assertTrue(tracker.canExecuteStep(s1), "Step 1 has no prerequisites and must be executable")

        // Verify Step 1
        tracker.recordStepVerification("s1", verified = true)
        assertTrue(tracker.canExecuteStep(s2), "Step 2 must now be executable after Step 1 is verified")
    }

    // ==========================================
    // PLAN REPAIR (P12-17 to P12-22)
    // ==========================================

    @Test
    fun `P12-17 A missing step is repaired when the correction is unambiguous`() {
        val outcome = RequiredOutcome(id = "out_sphere", description = "A UV Sphere exists in the scene", importance = OutcomeRequirementLevel.MANDATORY)
        val modeStep = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Ensure Mode", instruction = "Object Mode", targetedOutcomeIds = emptyList())
        val incompletePlan = UserGoalPlan(
            goalText = "Create UV sphere",
            requiredOutcomes = listOf(outcome),
            steps = listOf(modeStep)
        )

        val repaired = repairEngine.repairPlan(incompletePlan)
        assertTrue(repaired.validationResult?.isValid == true, "Repaired plan should be valid")
        assertTrue(repaired.steps.any { it.targetedOutcomeIds.contains("out_sphere") }, "Missing sphere step should be synthesized")
    }

    @Test
    fun `P12-18 Repair preserves the original goal`() {
        val originalText = "Create a sphere"
        val incompletePlan = UserGoalPlan(
            goalText = originalText,
            requiredOutcomes = listOf(RequiredOutcome(id = "out_sphere", description = "A UV Sphere exists in scene")),
            steps = emptyList()
        )

        val repaired = repairEngine.repairPlan(incompletePlan)
        assertEquals(originalText, repaired.goalText, "Repair must preserve original learner goal text")
    }

    @Test
    fun `P12-19 Repair does not remove mandatory outcomes`() {
        val mandatoryOutcome = RequiredOutcome(id = "out_mandatory", description = "Sphere created", importance = OutcomeRequirementLevel.MANDATORY)
        val incompletePlan = UserGoalPlan(
            goalText = "Create sphere",
            requiredOutcomes = listOf(mandatoryOutcome),
            steps = emptyList()
        )

        val repaired = repairEngine.repairPlan(incompletePlan)
        assertTrue(repaired.requiredOutcomes.any { it.id == "out_mandatory" }, "Mandatory outcome must not be removed by repair")
    }

    @Test
    fun `P12-20 Repair iterations are bounded`() {
        val policy = PlanRepairPolicy(maxRepairIterations = 2)
        // Cyclic dependency cannot be auto-repaired deterministically
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "1", instruction = "1", prerequisiteStepIds = listOf("s2"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "2", instruction = "2", prerequisiteStepIds = listOf("s1"))
        val unrepairablePlan = UserGoalPlan(
            goalText = "Unrepairable",
            requiredOutcomes = listOf(RequiredOutcome("out_1", "1")),
            steps = listOf(s1, s2)
        )

        val repaired = repairEngine.repairPlan(unrepairablePlan, policy)
        assertTrue(repaired.repairHistory.size <= 2, "Repair iterations must not exceed maxRepairIterations")
    }

    @Test
    fun `P12-21 An unsuccessful repair does not produce a false valid status`() {
        val unrepairablePlan = UserGoalPlan(
            goalText = "Unrepairable",
            requiredOutcomes = listOf(RequiredOutcome("out_1", "Unsupported bake", verificationStrategy = VerificationStrategy.UNSUPPORTED)),
            steps = emptyList()
        )

        val repaired = repairEngine.repairPlan(unrepairablePlan)
        assertFalse(repaired.validationResult?.isValid == true, "Unsuccessful repair must not produce a false valid status")
    }

    @Test
    fun `P12-22 Repaired plans are revalidated`() {
        val incompletePlan = UserGoalPlan(
            goalText = "Create cube",
            requiredOutcomes = listOf(RequiredOutcome(id = "out_cube", description = "Cube exists in scene")),
            steps = emptyList()
        )

        val repaired = repairEngine.repairPlan(incompletePlan)
        assertNotNull(repaired.validationResult, "Repaired plan must contain a fresh validation result")
        assertTrue(repaired.validationResult!!.totalMandatoryOutcomes > 0)
    }

    // ==========================================
    // PROGRESS AND CONTINUITY (P12-23 to P12-29)
    // ==========================================

    @Test
    fun `P12-23 Plan generation does not mark a goal complete`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planGoal("Teach me to create a UV Sphere in Blender")
        tracker.initializePlan(plan)

        assertFalse(tracker.isGoalCompleted(), "Newly generated plan must not be marked complete")
        assertEquals(GoalCompletionStatus.NOT_STARTED, tracker.getCurrentState().completionStatus)
    }

    @Test
    fun `P12-24 An unverified step does not satisfy its required outcome`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        val sphereOutcomeId = "out_sphere_exists"
        assertEquals(OutcomeSatisfactionStatus.NOT_STARTED, tracker.getCurrentState().outcomeProgress[sphereOutcomeId])
    }

    @Test
    fun `P12-25 A verified step updates the appropriate outcome progress`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        tracker.recordStepVerification("step_1", verified = true)
        val state = tracker.getCurrentState()
        assertEquals(OutcomeSatisfactionStatus.SATISFIED, state.outcomeProgress["out_mode_obj"])
    }

    @Test
    fun `P12-26 A goal remains incomplete while mandatory outcomes remain unsatisfied`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        // Verify only mode step, not sphere creation steps
        tracker.recordStepVerification("step_1", verified = true)

        assertFalse(tracker.isGoalCompleted(), "Goal must remain incomplete while mandatory sphere outcome is unsatisfied")
        assertEquals(GoalCompletionStatus.PARTIALLY_SATISFIED, tracker.getCurrentState().completionStatus)
    }

    @Test
    fun `P12-27 Re-planning preserves valid completed progress where appropriate`() {
        val tracker = GoalProgressTracker()
        val plan1 = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan1)

        tracker.recordStepVerification("step_1", verified = true)
        assertTrue(tracker.getCompletedStepIds().contains("step_1"))

        // Re-plan with revised compound goal
        val plan2 = planner.planCompoundModeling("Create a sphere, scale it, and move it above the ground plane.")
        tracker.replan(plan2)

        // Step 1 exists in both plans as ensure mode!
        assertTrue(tracker.getCompletedStepIds().contains("step_1"), "Valid completed step must be preserved after re-planning")
    }

    @Test
    fun `P12-28 Stale responses cannot overwrite a newer plan`() {
        val tracker = GoalProgressTracker()
        val plan1 = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan1)
        val v1 = tracker.planVersion

        // Learner updates goal
        val plan2 = planner.planCreateCube("Create cube")
        tracker.replan(plan2)
        val v2 = tracker.planVersion
        assertTrue(v2 > v1, "Plan version must increment on replan")

        // In-flight response arrives with old version v1
        tracker.recordStepVerification("step_2", verified = true, operationPlanVersion = v1)

        assertFalse(tracker.getCompletedStepIds().contains("step_2"), "Stale verification from previous plan version must be rejected")
    }

    @Test
    fun `P12-29 Plan changes invalidate inappropriate pending operations`() {
        val controller = TutorController()
        val plan1 = controller.startUserGoal("Teach me to create a UV Sphere in Blender")
        assertNotNull(plan1)
        val oldVersion = controller.goalProgressTracker.planVersion

        controller.replanGoal("Create a cube")
        val newVersion = controller.goalProgressTracker.planVersion

        assertTrue(newVersion > oldVersion, "Re-planning via controller must invalidate previous plan epoch")
    }
}
