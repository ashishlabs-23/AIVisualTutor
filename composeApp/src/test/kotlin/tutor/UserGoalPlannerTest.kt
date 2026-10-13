package tutor

import context.BaselineState
import context.EvidenceObservation
import context.EvidenceSource
import context.ExpectedState
import context.ObjectObservation
import context.ObservedState
import context.StateVerifier
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import models.ApplicationType
import pedagogy.GuidanceStage
import pedagogy.PedagogicalOutcome

class UserGoalPlannerTest {

    private val planner = UserGoalPlanner()

    @Test
    fun `planner parses create a UV sphere`() {
        val plan = planner.planGoal("create a UV sphere")

        assertEquals(ApplicationType.BLENDER, plan.application)
        assertEquals("create a UV sphere", plan.goalText)
        assertEquals(3, plan.steps.size)

        assertEquals("Ensure Object Mode", plan.steps[0].title)
        assertEquals("Add Mesh Sphere", plan.steps[1].title)
        assertEquals("Verify a Sphere object exists", plan.steps[2].title)

        val verificationStep = plan.steps[2]
        assertNotNull(verificationStep.expectedState)
        assertEquals(VerificationOperation.CREATE, verificationStep.expectedState?.operation)
        assertEquals("Sphere", verificationStep.expectedState?.targetName)
        assertEquals("MESH", verificationStep.expectedState?.targetType)
    }

    @Test
    fun `planner parses natural language I want to create a UV sphere`() {
        val plan = planner.planGoal("I want to create a UV sphere")

        assertEquals(3, plan.steps.size)
        assertEquals("Verify a Sphere object exists", plan.steps[2].title)
        assertEquals(VerificationOperation.CREATE, plan.steps[2].expectedState?.operation)
        assertEquals("Sphere", plan.steps[2].expectedState?.targetName)
    }

    @Test
    fun `planner parses create cube`() {
        val plan = planner.planGoal("create cube")

        assertEquals(3, plan.steps.size)
        assertEquals("Ensure Object Mode", plan.steps[0].title)
        assertEquals("Add Mesh Cube", plan.steps[1].title)
        assertEquals("Verify Cube Exists", plan.steps[2].title)

        val verificationStep = plan.steps[2]
        assertNotNull(verificationStep.expectedState)
        assertEquals(VerificationOperation.CREATE, verificationStep.expectedState?.operation)
        assertEquals("Cube", verificationStep.expectedState?.targetName)
        assertEquals("MESH", verificationStep.expectedState?.targetType)
    }

    @Test
    fun `planner parses compound goal make a cube and move it up`() {
        val plan = planner.planGoal("make a cube and move it up")

        assertEquals(4, plan.steps.size)
        assertEquals("Ensure Object Mode", plan.steps[0].title)
        assertEquals("Add Mesh Cube", plan.steps[1].title)
        assertEquals("Verify Cube Exists", plan.steps[2].title)
        assertEquals("Move Cube Up", plan.steps[3].title)

        assertEquals(VerificationOperation.CREATE, plan.steps[2].expectedState?.operation)
        assertEquals("Cube", plan.steps[2].expectedState?.targetName)
    }

    @Test
    fun `planner parses delete object`() {
        val plan = planner.planGoal("delete the Cube")

        assertEquals(2, plan.steps.size)
        assertEquals("Select Object to Delete", plan.steps[0].title)
        assertEquals("Delete Object", plan.steps[1].title)

        val expected = plan.steps[1].expectedState
        assertNotNull(expected)
        assertEquals(VerificationOperation.DELETE, expected.operation)
        assertEquals("Cube", expected.targetName)
    }

    @Test
    fun `planner parses rename object`() {
        val plan = planner.planGoal("rename Cube to PlayerMesh")

        assertEquals(2, plan.steps.size)
        assertEquals("Select Object to Rename", plan.steps[0].title)
        assertEquals("Rename to PlayerMesh", plan.steps[1].title)

        val expected = plan.steps[1].expectedState
        assertNotNull(expected)
        assertEquals(VerificationOperation.RENAME, expected.operation)
        assertEquals("PlayerMesh", expected.targetName)
    }

    @Test
    fun `planner parses select object`() {
        val plan = planner.planGoal("select object Sphere")

        assertEquals(2, plan.steps.size)
        assertEquals("Locate Sphere", plan.steps[0].title)
        assertEquals("Select Sphere", plan.steps[1].title)

        val expected = plan.steps[1].expectedState
        assertNotNull(expected)
        assertEquals(VerificationOperation.SELECT, expected.operation)
        assertEquals("Sphere", expected.targetName)
        assertEquals(true, expected.expectedSelection)
    }

    @Test
    fun `planner parses move object with explicit location`() {
        val plan = planner.planGoal("move Cube to 0.0, 0.0, 2.0")

        assertEquals(2, plan.steps.size)
        val expected = plan.steps[1].expectedState
        assertNotNull(expected)
        assertEquals(VerificationOperation.MODIFY, expected.operation)
        assertEquals("Cube", expected.targetName)
        assertEquals("location", expected.targetProperty)
        assertEquals("0.0, 0.0, 2.0", expected.expectedPropertyValue)
    }

    @Test
    fun `TutorController starts in empty goal-entry state by default`() {
        val controller = TutorController()
        assertNull(controller.activePlan)
        assertNull(controller.currentGeneratedPlan)
        assertFalse(controller.hasLoadedMockTutorial)
        assertEquals(0, controller.stepsForCurrentApp().size)
        assertNull(controller.currentStep)
        assertNull(controller.state.currentHighlight)
    }

    @Test
    fun `TutorController startUserGoal loads generated steps and clearUserGoal restores empty state`() {
        val controller = TutorController()
        assertEquals(0, controller.stepsForCurrentApp().size)

        val plan = controller.startUserGoal("create a UV sphere")
        assertNotNull(plan)
        assertEquals(plan, controller.activePlan)
        assertEquals(plan, controller.currentGeneratedPlan)
        assertEquals(3, controller.stepsForCurrentApp().size)
        assertEquals(0, controller.state.currentStepIndex)
        assertEquals("Ensure Object Mode", controller.currentStep?.title)
        assertEquals("Switch to Object Mode in the 3D Viewport header.", controller.currentStep?.instruction)

        controller.clearUserGoal()

        assertNull(controller.activePlan)
        assertNull(controller.currentGeneratedPlan)
        assertEquals(0, controller.stepsForCurrentApp().size)
        assertNull(controller.currentStep)
    }

    @Test
    fun `empty or blank goal does not generate plan or show mock tutorial steps`() {
        val controller = TutorController()

        val emptyPlan = controller.startUserGoal("")
        assertNull(emptyPlan)
        assertNull(controller.activePlan)
        assertEquals(0, controller.stepsForCurrentApp().size)
        assertNull(controller.currentStep)

        val blankPlan = controller.startUserGoal("    ")
        assertNull(blankPlan)
        assertNull(controller.activePlan)
        assertEquals(0, controller.stepsForCurrentApp().size)
        assertNull(controller.currentStep)
    }

    @Test
    fun `loadMockTutorial loads demo tutorial when explicitly requested`() {
        val controller = TutorController()
        assertEquals(0, controller.stepsForCurrentApp().size)

        controller.loadMockTutorial(ApplicationType.BLENDER)
        assertTrue(controller.hasLoadedMockTutorial)
        assertTrue(controller.stepsForCurrentApp().isNotEmpty())
        assertNotNull(controller.currentStep)
        assertEquals("Open the Add menu.", controller.currentStep?.instruction)
    }

    @Test
    fun `successful verification advances generated plan step`() {
        val controller = TutorController()
        controller.startUserGoal("make a cube and move it up")

        assertEquals(0, controller.state.currentStepIndex)
        assertEquals("Ensure Object Mode", controller.currentStep?.title)

        // Advance to step 2 (index 2: "Verify Cube Exists")
        controller.nextStep()
        controller.nextStep()
        assertEquals(2, controller.state.currentStepIndex)
        assertEquals("Verify Cube Exists", controller.currentStep?.title)

        val successResult = VerificationResult(
            status = VerificationStatus.SUCCESS,
            runId = "run-verify-cube-success",
            operation = VerificationOperation.CREATE,
            explanation = "Cube was created in the scene."
        )

        controller.recordVerificationResult(successResult, controller.currentStep?.expectedState)

        // Step should have advanced to step index 3 ("Move Cube Up")!
        assertEquals(3, controller.state.currentStepIndex)
        assertEquals("Move Cube Up", controller.currentStep?.title)
        assertEquals(PedagogicalOutcome.VERIFIED_SUCCESS, controller.state.lastPedagogicalOutcome)
    }

    @Test
    fun `failed or uncertain verification does not falsely complete or advance the step`() {
        val controller = TutorController()
        controller.startUserGoal("create a UV sphere")

        // Go to verification step (index 2: "Verify a Sphere object exists")
        controller.nextStep()
        controller.nextStep()
        assertEquals(2, controller.state.currentStepIndex)
        assertEquals("Verify a Sphere object exists", controller.currentStep?.title)

        // 1. Uncertain verification: must NOT advance
        val uncertainResult = VerificationResult(
            status = VerificationStatus.UNCERTAIN,
            runId = "run-sphere-uncertain",
            operation = VerificationOperation.CREATE,
            explanation = "Blender state timed out."
        )
        controller.recordVerificationResult(uncertainResult, controller.currentStep?.expectedState)

        assertEquals(2, controller.state.currentStepIndex)
        assertEquals("Verify a Sphere object exists", controller.currentStep?.title)
        assertEquals(PedagogicalOutcome.UNCERTAIN, controller.state.lastPedagogicalOutcome)
        assertTrue(controller.state.adaptationReason?.contains("Verification uncertain") == true)

        // 2. Failed verification: must NOT advance
        val failureResult = VerificationResult(
            status = VerificationStatus.FAILURE,
            runId = "run-sphere-failure",
            operation = VerificationOperation.CREATE,
            explanation = "No Sphere found in Blender state."
        )
        controller.recordVerificationResult(failureResult, controller.currentStep?.expectedState)

        assertEquals(2, controller.state.currentStepIndex)
        assertEquals("Verify a Sphere object exists", controller.currentStep?.title)
        assertEquals(PedagogicalOutcome.VERIFIED_FAILURE, controller.state.lastPedagogicalOutcome)
    }

    @Test
    fun `closed-loop StateVerifier rejects creation when sphere already existed in baseline`() {
        val verifier = StateVerifier()
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sphere",
            targetType = "MESH"
        )

        // Baseline: Sphere already exists!
        val existingSphere = ObjectObservation(id = "sph1", name = "Sphere", type = "MESH")
        val baseline = BaselineState(
            runId = "run-duplicate",
            applicationName = "Blender",
            objects = listOf(existingSphere),
            isComplete = true
        )
        val observed = ObservedState(
            runId = "run-duplicate",
            applicationName = "Blender",
            objects = listOf(existingSphere),
            isComplete = true
        )
        val evidence = listOf(
            EvidenceObservation(
                source = EvidenceSource.BLENDER,
                available = true,
                objectObservation = existingSphere
            )
        )

        val result = verifier.verify(expected, baseline, observed, evidence)

        // StateVerifier returns FAILURE because before.isNotEmpty()
        assertEquals(VerificationStatus.FAILURE, result.status)

        // Ensure TutorController does not advance on this failure
        val controller = TutorController()
        controller.startUserGoal("create a UV sphere")
        controller.nextStep()
        controller.nextStep()
        assertEquals(2, controller.state.currentStepIndex)

        controller.recordVerificationResult(result, expected)

        assertEquals(2, controller.state.currentStepIndex)
        assertFalse(controller.state.lastPedagogicalOutcome == PedagogicalOutcome.VERIFIED_SUCCESS)
    }
}
