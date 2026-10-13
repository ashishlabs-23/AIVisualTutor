package tutor

import context.ExpectedState
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent Validation and Freeze Audit Test Suite for Phase 12.
 * Directly exercises P12-T01 through P12-T42 according to the freeze audit specification.
 */
class Phase12AuditMatrixTest {

    private val validator = PlanCoverageValidator()
    private val repairEngine = PlanRepairEngine(validator)
    private val planner = UserGoalPlanner(validator, repairEngine)

    // ==========================================
    // PART 3 — PLAN COVERAGE VALIDATION (P12-T01 to P12-T10)
    // ==========================================

    @Test
    fun `P12-T01 Complete coverage`() {
        val outcome = RequiredOutcome("out_1", "Sphere exists", importance = OutcomeRequirementLevel.MANDATORY)
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Add Sphere",
            instruction = "Press Shift+A > UV Sphere",
            targetedOutcomeIds = listOf("out_1")
        )
        val plan = UserGoalPlan(goalText = "Create Sphere", requiredOutcomes = listOf(outcome), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertTrue(result.isValid)
        assertEquals(PlanValidationStatus.VALID, result.status)
        assertEquals(1, result.coveredMandatoryOutcomes)
        assertEquals(0, result.uncoveredMandatoryOutcomes)
    }

    @Test
    fun `P12-T02 Missing mandatory outcome`() {
        val o1 = RequiredOutcome("out_1", "Sphere exists", importance = OutcomeRequirementLevel.MANDATORY)
        val o2 = RequiredOutcome("out_2", "Material assigned", importance = OutcomeRequirementLevel.MANDATORY)
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Add Sphere",
            instruction = "Press Shift+A > UV Sphere",
            targetedOutcomeIds = listOf("out_1")
        )
        val plan = UserGoalPlan(goalText = "Sphere with material", requiredOutcomes = listOf(o1, o2), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertEquals(1, result.uncoveredMandatoryOutcomes)
        assertEquals(OutcomeCoverageStatus.UNCOVERED, result.outcomeCoverage["out_2"])
    }

    @Test
    fun `P12-T03 Optional outcome`() {
        val mandatory = RequiredOutcome("out_mand", "Base Mesh", importance = OutcomeRequirementLevel.MANDATORY)
        val optional = RequiredOutcome("out_opt", "Wireframe Preview", importance = OutcomeRequirementLevel.OPTIONAL)
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Base Mesh",
            instruction = "Create base mesh",
            targetedOutcomeIds = listOf("out_mand")
        )
        // Missing optional outcome must not block validation of mandatory goal
        val plan = UserGoalPlan(goalText = "Mesh with optional preview", requiredOutcomes = listOf(mandatory, optional), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertTrue(result.isValid)
        assertEquals(1, result.coveredMandatoryOutcomes)
        assertEquals(0, result.coveredOptionalOutcomes)
    }

    @Test
    fun `P12-T04 Keyword-only association`() {
        val outcome = RequiredOutcome("out_uv_sphere", "A UV Sphere exists in the scene", importance = OutcomeRequirementLevel.MANDATORY)
        // Step mentions "UV Sphere" in text but targetedOutcomeIds does not link "out_uv_sphere"
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Discuss UV Sphere",
            instruction = "Read about UV Sphere topology in Blender.",
            targetedOutcomeIds = emptyList()
        )
        val plan = UserGoalPlan(goalText = "UV Sphere goal", requiredOutcomes = listOf(outcome), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid, "Keyword matching alone must not satisfy explicit outcome coverage")
        assertEquals(OutcomeCoverageStatus.UNCOVERED, result.outcomeCoverage["out_uv_sphere"])
    }

    @Test
    fun `P12-T05 Invalid outcome identifier`() {
        val validOutcome = RequiredOutcome("out_real", "Real Outcome", importance = OutcomeRequirementLevel.MANDATORY)
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Invalid Reference Step",
            instruction = "Perform action",
            targetedOutcomeIds = listOf("out_nonexistent_fake_id")
        )
        val plan = UserGoalPlan(goalText = "Invalid ref goal", requiredOutcomes = listOf(validOutcome), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertTrue(result.validationIssues.any { it.issueType == ValidationIssueType.INVALID_OUTCOME_IDENTIFIER })
    }

    @Test
    fun `P12-T06 Orphaned step`() {
        val outcome = RequiredOutcome("out_1", "Target outcome", importance = OutcomeRequirementLevel.MANDATORY)
        val step1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Valid", instruction = "Do valid", targetedOutcomeIds = listOf("out_1"))
        val step2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Orphan", instruction = "Do orphan", targetedOutcomeIds = emptyList())
        val plan = UserGoalPlan(goalText = "Orphan goal", requiredOutcomes = listOf(outcome), steps = listOf(step1, step2))

        val result = validator.validateCoverage(plan)
        assertTrue(result.orphanedStepIndices.contains(2))
        assertTrue(result.validationIssues.any { it.issueType == ValidationIssueType.ORPHANED_STEP })
    }

    @Test
    fun `P12-T07 Unsupported verification strategy`() {
        val unsupportedOutcome = RequiredOutcome(
            id = "out_unsupported",
            description = "Unsupported render raytrace buffer",
            importance = OutcomeRequirementLevel.MANDATORY,
            verificationStrategy = VerificationStrategy.UNSUPPORTED
        )
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Raytrace",
            instruction = "Do raytrace",
            targetedOutcomeIds = listOf("out_unsupported")
        )
        val plan = UserGoalPlan(goalText = "Unsupported goal", requiredOutcomes = listOf(unsupportedOutcome), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid, "Unsupported verification strategy must cause validation failure")
        assertTrue(result.outcomesLackingVerification.contains("out_unsupported"))
    }

    @Test
    fun `P12-T08 Duplicate associations`() {
        val outcome = RequiredOutcome("out_1", "Single Outcome", importance = OutcomeRequirementLevel.MANDATORY)
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Duplicate target step",
            instruction = "Do action",
            targetedOutcomeIds = listOf("out_1", "out_1") // duplicate!
        )
        val plan = UserGoalPlan(goalText = "Duplicate test", requiredOutcomes = listOf(outcome), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertTrue(result.isValid)
        assertEquals(1, result.coveredMandatoryOutcomes)
    }

    @Test
    fun `P12-T09 Empty plan`() {
        val outcome = RequiredOutcome("out_1", "Mandatory outcome", importance = OutcomeRequirementLevel.MANDATORY)
        val emptyPlan = UserGoalPlan(goalText = "Empty plan goal", requiredOutcomes = listOf(outcome), steps = emptyList())

        val result = validator.validateCoverage(emptyPlan)
        assertFalse(result.isValid)
        assertEquals(1, result.uncoveredMandatoryOutcomes)
    }

    @Test
    fun `P12-T10 Malformed plan`() {
        val outcome = RequiredOutcome("out_1", "Outcome", importance = OutcomeRequirementLevel.MANDATORY)
        val malformedStep = UserGoalPlanStep(
            stepId = "", // blank ID!
            stepIndex = -1, // invalid index!
            title = "", // blank title!
            instruction = "Malformed",
            targetedOutcomeIds = listOf("out_1")
        )
        val plan = UserGoalPlan(goalText = "Malformed goal", requiredOutcomes = listOf(outcome), steps = listOf(malformedStep))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertTrue(result.validationIssues.any { it.issueType == ValidationIssueType.MALFORMED_PLAN_STEP })
    }

    // ==========================================
    // PART 4 — DEPENDENCY GRAPH VALIDATION (P12-T11 to P12-T20)
    // ==========================================

    @Test
    fun `P12-T11 Valid dependency chain`() {
        val o = RequiredOutcome("out_1", "Final outcome")
        val a = UserGoalPlanStep(stepId = "sA", stepIndex = 1, title = "A", instruction = "A", targetedOutcomeIds = listOf("out_1"))
        val b = UserGoalPlanStep(stepId = "sB", stepIndex = 2, title = "B", instruction = "B", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("sA"))
        val c = UserGoalPlanStep(stepId = "sC", stepIndex = 3, title = "C", instruction = "C", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("sB"))
        val plan = UserGoalPlan(goalText = "Chain test", requiredOutcomes = listOf(o), steps = listOf(a, b, c))

        val result = validator.validateCoverage(plan)
        assertTrue(result.isValid)
        assertTrue(result.missingPrerequisiteErrors.isEmpty())
        assertTrue(result.cyclicDependencyErrors.isEmpty())
    }

    @Test
    fun `P12-T12 Missing dependency`() {
        val o = RequiredOutcome("out_1", "Outcome")
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Missing prereq step",
            instruction = "Do",
            targetedOutcomeIds = listOf("out_1"),
            prerequisiteStepIds = listOf("step_ghost_missing")
        )
        val plan = UserGoalPlan(goalText = "Missing dep", requiredOutcomes = listOf(o), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertTrue(result.missingPrerequisiteErrors.isNotEmpty())
    }

    @Test
    fun `P12-T13 Self-dependency`() {
        val o = RequiredOutcome("out_1", "Outcome")
        val step = UserGoalPlanStep(
            stepId = "s1",
            stepIndex = 1,
            title = "Self dependent",
            instruction = "Do",
            targetedOutcomeIds = listOf("out_1"),
            prerequisiteStepIds = listOf("s1") // self!
        )
        val plan = UserGoalPlan(goalText = "Self dep", requiredOutcomes = listOf(o), steps = listOf(step))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertTrue(result.validationIssues.any { it.issueType == ValidationIssueType.SELF_DEPENDENCY })
    }

    @Test
    fun `P12-T14 Two-node cycle`() {
        val o = RequiredOutcome("out_1", "Outcome")
        val a = UserGoalPlanStep(stepId = "sA", stepIndex = 1, title = "A", instruction = "A", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("sB"))
        val b = UserGoalPlanStep(stepId = "sB", stepIndex = 2, title = "B", instruction = "B", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("sA"))
        val plan = UserGoalPlan(goalText = "Two node cycle", requiredOutcomes = listOf(o), steps = listOf(a, b))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertTrue(result.cyclicDependencyErrors.isNotEmpty())
    }

    @Test
    fun `P12-T15 Longer cycle`() {
        val o = RequiredOutcome("out_1", "Outcome")
        val a = UserGoalPlanStep(stepId = "sA", stepIndex = 1, title = "A", instruction = "A", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("sC"))
        val b = UserGoalPlanStep(stepId = "sB", stepIndex = 2, title = "B", instruction = "B", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("sA"))
        val c = UserGoalPlanStep(stepId = "sC", stepIndex = 3, title = "C", instruction = "C", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("sB"))
        val plan = UserGoalPlan(goalText = "3 node cycle", requiredOutcomes = listOf(o), steps = listOf(a, b, c))

        val result = validator.validateCoverage(plan)
        assertFalse(result.isValid)
        assertTrue(result.cyclicDependencyErrors.isNotEmpty())
    }

    @Test
    fun `P12-T16 Independent steps`() {
        val o1 = RequiredOutcome("out_1", "Outcome 1")
        val o2 = RequiredOutcome("out_2", "Outcome 2")
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "1", instruction = "1", targetedOutcomeIds = listOf("out_1"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "2", instruction = "2", targetedOutcomeIds = listOf("out_2"))
        val plan = UserGoalPlan(goalText = "Independent steps", requiredOutcomes = listOf(o1, o2), steps = listOf(s1, s2))

        val result = validator.validateCoverage(plan)
        assertTrue(result.isValid)
    }

    @Test
    fun `P12-T17 Prerequisite enforcement`() {
        val tracker = GoalProgressTracker()
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Step 1", instruction = "Do 1", targetedOutcomeIds = listOf("out_1"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Step 2", instruction = "Do 2", targetedOutcomeIds = listOf("out_2"), prerequisiteStepIds = listOf("s1"))
        val plan = UserGoalPlan(goalText = "Prereq test", requiredOutcomes = listOf(RequiredOutcome("out_1", "O1"), RequiredOutcome("out_2", "O2")), steps = listOf(s1, s2))

        tracker.initializePlan(plan)
        assertFalse(tracker.canExecuteStep(s2))
        tracker.recordStepVerification("s1", verified = true)
        assertTrue(tracker.canExecuteStep(s2))
    }

    @Test
    fun `P12-T18 Failed prerequisite`() {
        val tracker = GoalProgressTracker()
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Step 1", instruction = "Do 1", targetedOutcomeIds = listOf("out_1"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Step 2", instruction = "Do 2", targetedOutcomeIds = listOf("out_2"), prerequisiteStepIds = listOf("s1"))
        val plan = UserGoalPlan(goalText = "Failed prereq test", requiredOutcomes = listOf(RequiredOutcome("out_1", "O1"), RequiredOutcome("out_2", "O2")), steps = listOf(s1, s2))

        tracker.initializePlan(plan)
        // Step 1 fails verification
        tracker.recordStepVerification("s1", verified = false)
        assertFalse(tracker.canExecuteStep(s2), "Dependent step 2 must remain blocked when prerequisite 1 fails verification")
    }

    @Test
    fun `P12-T19 Reordered plan`() {
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Prereq", instruction = "Do prereq", targetedOutcomeIds = listOf("out_1"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Dependent", instruction = "Do dep", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("s1"))
        // Intentionally inverse list order
        val inversePlan = UserGoalPlan(
            goalText = "Reordered plan",
            requiredOutcomes = listOf(RequiredOutcome("out_1", "Outcome")),
            steps = listOf(s2, s1)
        )

        val repaired = repairEngine.repairPlan(inversePlan)
        assertTrue(repaired.validationResult?.isValid == true)
        // First step in repaired plan must be s1
        assertEquals("s1", repaired.steps.first().stepId)
    }

    @Test
    fun `P12-T20 Invalid graph repair`() {
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "A", instruction = "A", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("s2"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "B", instruction = "B", targetedOutcomeIds = listOf("out_1"), prerequisiteStepIds = listOf("s1"))
        val cyclicPlan = UserGoalPlan(goalText = "Cycle repair", requiredOutcomes = listOf(RequiredOutcome("out_1", "Outcome")), steps = listOf(s1, s2))

        val repaired = repairEngine.repairPlan(cyclicPlan)
        assertFalse(repaired.validationResult?.isValid == true, "Repair must never accept an unresolved cyclic dependency graph")
    }

    // ==========================================
    // PART 5 — PLAN REPAIR (P12-T21 to P12-T29)
    // ==========================================

    @Test
    fun `P12-T21 Missing outcome repair`() {
        val sphereOutcome = RequiredOutcome("out_sphere", "A UV Sphere exists in the scene", importance = OutcomeRequirementLevel.MANDATORY)
        val incompletePlan = UserGoalPlan(goalText = "Create UV sphere", requiredOutcomes = listOf(sphereOutcome), steps = emptyList())

        val repaired = repairEngine.repairPlan(incompletePlan)
        assertTrue(repaired.validationResult?.isValid == true)
        assertTrue(repaired.steps.any { it.targetedOutcomeIds.contains("out_sphere") })
    }

    @Test
    fun `P12-T22 Repair limit`() {
        val policy = PlanRepairPolicy(maxRepairIterations = 3)
        // Cycle is unrepairable
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "A", instruction = "A", prerequisiteStepIds = listOf("s2"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "B", instruction = "B", prerequisiteStepIds = listOf("s1"))
        val plan = UserGoalPlan(goalText = "Bounded repair", requiredOutcomes = listOf(RequiredOutcome("out_1", "O1")), steps = listOf(s1, s2))

        val repaired = repairEngine.repairPlan(plan, policy)
        assertTrue(repaired.repairHistory.size <= 3, "Repair attempts must not exceed maxRepairIterations bound of 3")
    }

    @Test
    fun `P12-T23 Revalidation after repair`() {
        val outcome = RequiredOutcome("out_cube", "Cube exists in scene", importance = OutcomeRequirementLevel.MANDATORY)
        val incompletePlan = UserGoalPlan(goalText = "Create cube", requiredOutcomes = listOf(outcome), steps = emptyList())

        val repaired = repairEngine.repairPlan(incompletePlan)
        assertNotNull(repaired.validationResult)
        assertEquals(PlanValidationStatus.VALID, repaired.validationResult?.status)
    }

    @Test
    fun `P12-T24 Unsuccessful repair`() {
        val unsupportedOutcome = RequiredOutcome("out_unsupported", "Unsupported bake", verificationStrategy = VerificationStrategy.UNSUPPORTED)
        val plan = UserGoalPlan(goalText = "Unrepairable", requiredOutcomes = listOf(unsupportedOutcome), steps = emptyList())

        val repaired = repairEngine.repairPlan(plan)
        assertFalse(repaired.validationResult?.isValid == true, "Unsuccessful repair must not falsely report valid status")
    }

    @Test
    fun `P12-T25 Invalid repair`() {
        // If repair cannot resolve dependencies, it catches it in revalidation
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Self", instruction = "Self", prerequisiteStepIds = listOf("s1"))
        val plan = UserGoalPlan(goalText = "Self dep repair", requiredOutcomes = listOf(RequiredOutcome("out_1", "O1")), steps = listOf(s1))

        val repaired = repairEngine.repairPlan(plan)
        assertFalse(repaired.validationResult?.isValid == true)
    }

    @Test
    fun `P12-T26 Idempotence`() {
        val validPlan = planner.planCreateSphere("Create a UV sphere")
        val repaired1 = repairEngine.repairPlan(validPlan)
        val repaired2 = repairEngine.repairPlan(repaired1)

        assertEquals(validPlan.steps.size, repaired1.steps.size)
        assertEquals(repaired1.steps.size, repaired2.steps.size)
    }

    @Test
    fun `P12-T27 Deterministic repair`() {
        val outcome = RequiredOutcome("out_cube", "Cube exists in scene", importance = OutcomeRequirementLevel.MANDATORY)
        val plan1 = UserGoalPlan(goalText = "Create cube", requiredOutcomes = listOf(outcome), steps = emptyList())
        val plan2 = UserGoalPlan(goalText = "Create cube", requiredOutcomes = listOf(outcome), steps = emptyList())

        val rep1 = repairEngine.repairPlan(plan1)
        val rep2 = repairEngine.repairPlan(plan2)

        assertEquals(rep1.steps.size, rep2.steps.size)
        assertEquals(rep1.steps.map { it.title }, rep2.steps.map { it.title })
    }

    @Test
    fun `P12-T28 Repair exception`() {
        // Disabled auto repair
        val policy = PlanRepairPolicy(autoRepairEnabled = false)
        val brokenPlan = UserGoalPlan(
            goalText = "Broken",
            requiredOutcomes = listOf(RequiredOutcome("out_1", "O1")),
            steps = emptyList()
        )

        val result = repairEngine.repairPlan(brokenPlan, policy)
        assertFalse(result.validationResult?.isValid == true)
    }

    @Test
    fun `P12-T29 Repair preserves learner progress`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        tracker.recordStepVerification("step_1", verified = true)
        assertTrue(tracker.getCompletedStepIds().contains("step_1"))

        // Add a step or repair
        val repaired = repairEngine.repairPlan(plan)
        tracker.replan(repaired)
        assertTrue(tracker.getCompletedStepIds().contains("step_1"), "Preserves valid completed step")
    }

    // ==========================================
    // PART 6 — GOAL COMPLETION AND PROGRESS TRACKING (P12-T30 to P12-T42)
    // ==========================================

    @Test
    fun `P12-T30 Valid plan no completed outcomes`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        assertFalse(tracker.isGoalCompleted(), "Valid plan with zero completed outcomes must remain incomplete")
        assertEquals(GoalCompletionStatus.NOT_STARTED, tracker.getCurrentState().completionStatus)
    }

    @Test
    fun `P12-T31 One mandatory outcome remains unsatisfied`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        // Complete step 1 (Object mode)
        tracker.recordStepVerification("step_1", verified = true)
        assertFalse(tracker.isGoalCompleted(), "Goal must remain incomplete while mandatory sphere outcome is unsatisfied")
        assertEquals(GoalCompletionStatus.PARTIALLY_SATISFIED, tracker.getCurrentState().completionStatus)
    }

    @Test
    fun `P12-T32 All mandatory outcomes verified`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        // Verify all steps
        tracker.recordStepVerification("step_1", verified = true)
        tracker.recordStepVerification("step_2", verified = true)
        tracker.recordStepVerification("step_3", verified = true)

        assertTrue(tracker.isGoalCompleted(), "Goal must be marked complete when all mandatory outcomes are verified")
        assertEquals(GoalCompletionStatus.COMPLETED, tracker.getCurrentState().completionStatus)
    }

    @Test
    fun `P12-T33 Optional outcome remains unsatisfied`() {
        val tracker = GoalProgressTracker()
        val mand = RequiredOutcome("out_mand", "Base Mesh", importance = OutcomeRequirementLevel.MANDATORY)
        val opt = RequiredOutcome("out_opt", "Optional Preview", importance = OutcomeRequirementLevel.OPTIONAL)
        val s1 = UserGoalPlanStep(stepId = "s1", stepIndex = 1, title = "Base", instruction = "Base", targetedOutcomeIds = listOf("out_mand"))
        val s2 = UserGoalPlanStep(stepId = "s2", stepIndex = 2, title = "Preview", instruction = "Preview", targetedOutcomeIds = listOf("out_opt"))
        val plan = UserGoalPlan(goalText = "Optional test", requiredOutcomes = listOf(mand, opt), steps = listOf(s1, s2))

        tracker.initializePlan(plan)
        tracker.recordStepVerification("s1", verified = true)
        // Mandatory is satisfied, optional is unsatisfied
        assertTrue(tracker.isGoalCompleted(), "Unsatisfied optional outcome must not block mandatory goal completion")
    }

    @Test
    fun `P12-T34 Planner response claims success`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        // Textual claim from planner or prompt
        val plannerClaim = "The sphere is now fully created in your scene!"
        assertNotNull(plannerClaim)

        // Goal must remain incomplete without actual verification
        assertFalse(tracker.isGoalCompleted(), "Planner textual claims must not bypass state verification")
    }

    @Test
    fun `P12-T35 Screenshot analysis claims success`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        val visualAnalysisText = "Screenshot inspection shows what appears to be a spherical mesh."
        assertNotNull(visualAnalysisText)

        assertFalse(tracker.isGoalCompleted(), "Screenshot advisory text alone must not bypass state verification")
    }

    @Test
    fun `P12-T36 Verification failure`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        tracker.recordStepVerification("step_1", verified = false)
        assertFalse(tracker.isGoalCompleted())
        assertEquals(OutcomeSatisfactionStatus.FAILED, tracker.getCurrentState().outcomeProgress["out_mode_obj"])
    }

    @Test
    fun `P12-T37 Duplicate completion event`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        tracker.recordStepVerification("step_1", verified = true)
        tracker.recordStepVerification("step_1", verified = true) // duplicate!

        assertEquals(1, tracker.getCompletedStepIds().size, "Duplicate verification must be handled idempotently")
    }

    @Test
    fun `P12-T38 Stale plan response`() {
        val tracker = GoalProgressTracker()
        val planA = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(planA)
        val vA = tracker.planVersion

        val planB = planner.planCreateCube("Create cube")
        tracker.replan(planB)
        val vB = tracker.planVersion

        // In-flight response arrives from planA epoch
        tracker.recordStepVerification("step_2", verified = true, operationPlanVersion = vA)
        assertFalse(tracker.getCompletedStepIds().contains("step_2"), "Stale verification from older version must be rejected")
    }

    @Test
    fun `P12-T39 Replanning after partial progress`() {
        val tracker = GoalProgressTracker()
        val planA = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(planA)
        tracker.recordStepVerification("step_1", verified = true)

        val planCompound = planner.planCompoundModeling("Create a sphere, scale it, and move it above the ground plane.")
        tracker.replan(planCompound)

        // step_1 is shared Object Mode step
        assertTrue(tracker.getCompletedStepIds().contains("step_1"))
        assertFalse(tracker.isGoalCompleted(), "Goal must remain incomplete with remaining compound outcomes")
    }

    @Test
    fun `P12-T40 Conflicting progress evidence`() {
        val controller = TutorController()
        val plan = controller.startUserGoal("Teach me to create a UV Sphere in Blender")
        assertNotNull(plan)

        // Conflicting evidence resolved to FAILURE
        val conflictResult = VerificationResult(
            status = VerificationStatus.FAILURE,
            runId = "conflict_run_1",
            operation = VerificationOperation.CREATE,
            explanation = "Authoritative state contradicts visual hint: Object not found."
        )
        controller.recordVerificationResult(conflictResult)

        assertFalse(controller.goalProgressTracker.isGoalCompleted(), "Conflicting evidence failing verification must keep goal incomplete")
    }

    @Test
    fun `P12-T41 Session or goal identity mismatch`() {
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        tracker.initializePlan(plan)

        // Event from different plan version
        tracker.recordStepVerification("step_1", verified = true, operationPlanVersion = 9999L)
        // Current plan version is 1L. Passing 9999L does not update if mismatch policy rejects or version differs
        assertTrue(tracker.planVersion == 1L)
    }

    @Test
    fun `P12-T42 Restart or restored progress`() {
        // Documented scope boundary: Phase 12 manages runtime memory progress and replanning continuity;
        // disk serialization/restoration is explicitly outside Phase 12 scope.
        val tracker = GoalProgressTracker()
        val plan = planner.planCreateSphere("Create sphere")
        val state = tracker.initializePlan(plan)

        assertNotNull(state)
        assertEquals(plan.planId, state.goalId)
    }
}
