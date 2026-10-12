package adapter

import context.BaselineState
import context.BlenderObservationStatus
import context.BlenderStateObservation
import context.BlenderVerificationWorkflow
import context.EvidenceSource
import context.ExpectedState
import context.ObjectObservation
import context.ObservedState
import context.StateVerifier
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import models.ApplicationType
import pedagogy.AdaptivePedagogicalEngine
import pedagogy.GuidanceStage
import pedagogy.LearningTask
import pedagogy.PedagogicalOutcome
import tutor.TutorController
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Phase8AdaptiveTutoringIntegrationTest {

    private fun sampleTask(id: String = "task-blender-cube"): LearningTask = LearningTask(
        taskId = id,
        skillId = "blender.primitives",
        title = "Create Cube",
        objective = "Create a default 3D mesh cube in the scene",
        targetControlName = "Cube",
        exactInstruction = "Press Shift+A and select Mesh -> Cube.",
        conceptualHint = "Add a new box primitive from the add menu.",
        reflectionQuestion = "Which primitive tool creates a box shape?",
        expectedState = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Cube",
            targetType = "MESH"
        )
    )

    /**
     * Test 1: Successful verification advances pedagogical state correctly.
     * Stage 1 -> Success 1 -> Success 2 -> Fades to Stage 2 (Conceptual Hint).
     */
    @Test
    fun testSuccessfulVerificationAdvancesPedagogicalState() {
        val engine = AdaptivePedagogicalEngine()
        val controller = TutorController(pedagogicalEngine = engine)
        controller.selectApplication(ApplicationType.BLENDER)

        val task = sampleTask()
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, engine.state.currentGuidanceStage)

        // Result 1: Success
        val res1 = VerificationResult(
            status = VerificationStatus.SUCCESS,
            runId = "run-step-1",
            operation = VerificationOperation.CREATE,
            explanation = "Cube appeared in scene."
        )
        controller.recordVerificationResult(res1, task.expectedState)
        assertEquals(1, engine.state.consecutiveSuccesses)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, controller.state.guidanceStage)

        // Result 2: Success -> Should fade to Stage 2
        val res2 = VerificationResult(
            status = VerificationStatus.SUCCESS,
            runId = "run-step-2",
            operation = VerificationOperation.CREATE,
            explanation = "Second cube verified."
        )
        controller.recordVerificationResult(res2, task.expectedState)
        assertEquals(2, engine.state.consecutiveSuccesses)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, controller.state.guidanceStage)
        assertEquals(PedagogicalOutcome.VERIFIED_SUCCESS, controller.state.lastPedagogicalOutcome)
    }

    /**
     * Test 2: Verified failure triggers existing recovery path.
     * Learner at Stage 2 -> 2 consecutive failures -> Steps back to Stage 1.
     */
    @Test
    fun testVerifiedFailureTriggersRecoveryStepBack() {
        val engine = AdaptivePedagogicalEngine()
        val controller = TutorController(pedagogicalEngine = engine)
        controller.selectApplication(ApplicationType.BLENDER)

        val task = sampleTask()

        // Give 2 successes to reach Stage 2
        controller.recordVerificationResult(VerificationResult(VerificationStatus.SUCCESS, "r1", VerificationOperation.CREATE, "ok"), task.expectedState)
        controller.recordVerificationResult(VerificationResult(VerificationStatus.SUCCESS, "r2", VerificationOperation.CREATE, "ok"), task.expectedState)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, controller.state.guidanceStage)

        // Failure 1: Stage retained to assess instruction adequacy
        controller.recordVerificationResult(VerificationResult(VerificationStatus.FAILURE, "r3", VerificationOperation.CREATE, "no cube"), task.expectedState)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, controller.state.guidanceStage)

        // Failure 2: Support stepped back to Stage 1
        controller.recordVerificationResult(VerificationResult(VerificationStatus.FAILURE, "r4", VerificationOperation.CREATE, "no cube again"), task.expectedState)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, controller.state.guidanceStage)
        assertEquals(PedagogicalOutcome.VERIFIED_FAILURE, controller.state.lastPedagogicalOutcome)
    }

    /**
     * Test 3: Uncertain verification does NOT falsely advance or corrupt streaks.
     */
    @Test
    fun testUncertainVerificationPreservesStreaksAndStage() {
        val engine = AdaptivePedagogicalEngine()
        val controller = TutorController(pedagogicalEngine = engine)
        controller.selectApplication(ApplicationType.BLENDER)

        val task = sampleTask()
        controller.recordVerificationResult(VerificationResult(VerificationStatus.SUCCESS, "r1", VerificationOperation.CREATE, "ok"), task.expectedState)
        assertEquals(1, engine.state.consecutiveSuccesses)

        // Adapter timeout / uncertain outcome
        val uncertainRes = VerificationResult(
            status = VerificationStatus.UNCERTAIN,
            runId = "r-uncertain",
            operation = VerificationOperation.CREATE,
            explanation = "Blender adapter timed out."
        )
        controller.recordVerificationResult(uncertainRes, task.expectedState)

        // Streaks and guidance stage are strictly preserved!
        assertEquals(1, engine.state.consecutiveSuccesses)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, controller.state.guidanceStage)
        assertEquals(PedagogicalOutcome.UNCERTAIN, controller.state.lastPedagogicalOutcome)
    }

    /**
     * Test 4: Duplicate verification run ID does not double-increment progress.
     */
    @Test
    fun testDuplicateVerificationRunIdIdempotence() {
        val engine = AdaptivePedagogicalEngine()
        val controller = TutorController(pedagogicalEngine = engine)
        controller.selectApplication(ApplicationType.BLENDER)

        val task = sampleTask()
        val res = VerificationResult(
            status = VerificationStatus.SUCCESS,
            runId = "duplicate-run-123",
            operation = VerificationOperation.CREATE,
            explanation = "Cube verified."
        )

        controller.recordVerificationResult(res, task.expectedState)
        assertEquals(1, engine.state.consecutiveSuccesses)

        // Call again with the exact same runId!
        controller.recordVerificationResult(res, task.expectedState)
        // Success streak remains 1, does NOT jump to 2 or fade to Stage 2!
        assertEquals(1, engine.state.consecutiveSuccesses)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, controller.state.guidanceStage)
    }
}
