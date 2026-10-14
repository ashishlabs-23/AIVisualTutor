package bridge

import adapter.EvidenceProvenance
import adapter.ObservationFreshness
import adapter.ProvenanceEvidenceObservation
import context.BaselineState
import context.BlenderObservationStatus
import context.BlenderStateObservation
import context.BlenderStateProvider
import context.BlenderVerificationWorkflow
import context.EvidenceObservation
import context.EvidenceSource
import context.ExpectedCondition
import context.ExpectedState
import context.ExpectedStateField
import context.ObjectObservation
import context.ObservedState
import context.StateVerifier
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import kotlinx.coroutines.runBlocking
import models.ApplicationType
import tutor.ClosedLoopRecoveryCoordinator
import tutor.ClosedLoopRecoveryState
import tutor.TutorController
import tutor.UserGoalPlanner
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MoveCubeUpVerificationRegressionTest {

    private class MockBlenderProvider(
        var currentObservation: BlenderStateObservation
    ) : BlenderStateProvider {
        override suspend fun observe(): BlenderStateObservation = currentObservation
    }

    private val verifier = StateVerifier()

    private val moveCubeUpExpected = ExpectedState(
        operation = VerificationOperation.MODIFY,
        targetName = "Cube",
        targetType = "MESH",
        targetProperty = "location.z",
        expectedPropertyValue = "> 0",
        requiredConditions = listOf(
            ExpectedCondition(
                field = ExpectedStateField.OBJECT_EXISTS,
                objectName = "Cube",
                expectedValue = "Cube",
                required = true
            ),
            ExpectedCondition(
                field = ExpectedStateField.OBJECT_PROPERTY,
                objectName = "Cube",
                propertyName = "location.z",
                expectedValue = "> 0",
                required = true
            )
        )
    )

    private fun createBaseline(z: Double = 0.0, id: String = "cube_1", name: String = "Cube"): BaselineState {
        return BaselineState(
            runId = "run-1",
            sceneName = "Scene",
            mode = "OBJECT",
            capturedAt = Instant.now(),
            objects = listOf(
                ObjectObservation(
                    id = id,
                    name = name,
                    type = "MESH",
                    sceneName = "Scene",
                    selected = true,
                    metadata = mapOf(
                        "location" to "0.0,0.0,$z",
                        "location.x" to "0.0",
                        "location.y" to "0.0",
                        "location.z" to z.toString(),
                        "z" to z.toString()
                    )
                )
            ),
            selectedObjects = setOf(name),
            isComplete = true
        )
    }

    private fun createObserved(
        x: Double = 0.0,
        y: Double = 0.0,
        z: Double = 0.0,
        id: String = "cube_1",
        name: String = "Cube",
        type: String = "MESH",
        capturedAt: Instant = Instant.now().plusMillis(100),
        metadata: Map<String, String>? = null,
        stateMetadata: Map<String, String> = emptyMap(),
        runId: String = "run-1"
    ): ObservedState {
        val meta = metadata ?: mapOf(
            "location" to "$x,$y,$z",
            "location.x" to x.toString(),
            "location.y" to y.toString(),
            "location.z" to z.toString(),
            "z" to z.toString()
        )
        return ObservedState(
            runId = runId,
            sceneName = "Scene",
            mode = "OBJECT",
            capturedAt = capturedAt,
            objects = listOf(
                ObjectObservation(
                    id = id,
                    name = name,
                    type = type,
                    sceneName = "Scene",
                    selected = true,
                    metadata = meta
                )
            ),
            selectedObjects = setOf(name),
            metadata = stateMetadata,
            isComplete = true
        )
    }

    private val defaultEvidence = listOf(
        EvidenceObservation(source = EvidenceSource.BLENDER, available = true)
    )

    // TASK 4 — REGRESSION TEST 1: Z = 0 against Z > 0 -> FAIL.
    @Test
    fun test1_z0_against_zGreaterThan0_fails() {
        val baseline = createBaseline(z = 0.0)
        val observed = createObserved(x = 0.0, y = 0.0, z = 0.0)

        val result = verifier.verify(moveCubeUpExpected, baseline, observed, defaultEvidence)

        assertEquals(VerificationStatus.FAILURE, result.status)
        assertTrue(result.explanation.contains("Not verified: the cube's Z location has not reached the required target"))
        assertTrue(result.explanation.contains("observed: 0.0 m"))
    }

    // TASK 4 — REGRESSION TEST 2: Z = 1 against Z > 0 -> PASS.
    @Test
    fun test2_z1_against_zGreaterThan0_passes() {
        // Standard upward movement from initial Z = 0.0 to Z = 1.0
        val baseline0 = createBaseline(z = 0.0)
        val observed1 = createObserved(x = 0.0, y = 0.0, z = 1.0)

        val result1 = verifier.verify(moveCubeUpExpected, baseline0, observed1, defaultEvidence)
        assertEquals(VerificationStatus.SUCCESS, result1.status)
        assertTrue(result1.explanation.contains("Required state conditions were observed"))

        // Critical regression case: baseline captured during verify attempt when cube is ALREADY at Z = 1.0
        val baseline1 = createBaseline(z = 1.0)
        val result2 = verifier.verify(moveCubeUpExpected, baseline1, observed1, defaultEvidence)
        assertEquals(VerificationStatus.SUCCESS, result2.status, "Cube at Z = 1.0 must pass explicit Z > 0 contract even if baseline was sampled at Z = 1.0")
        assertTrue(result2.explanation.contains("Required state conditions were observed"))
    }

    // TASK 4 — REGRESSION TEST 3: Z = -1 against Z > 0 -> FAIL.
    @Test
    fun test3_zMinus1_against_zGreaterThan0_fails() {
        val baseline = createBaseline(z = 0.0)
        val observed = createObserved(x = 0.0, y = 0.0, z = -1.0)

        val result = verifier.verify(moveCubeUpExpected, baseline, observed, defaultEvidence)

        assertEquals(VerificationStatus.FAILURE, result.status)
        assertTrue(result.explanation.contains("Not verified: the cube's Z location has not reached the required target"))
        assertTrue(result.explanation.contains("observed: -1.0 m"))
    }

    // TASK 4 — REGRESSION TEST 4: A fresh observation for the wrong object -> FAIL.
    @Test
    fun test4_freshObservationForWrongObject_fails() {
        val baseline = createBaseline(z = 0.0, name = "Camera", id = "cam_1")
        val observed = createObserved(z = 1.0, name = "Camera", id = "cam_1")

        val result = verifier.verify(moveCubeUpExpected, baseline, observed, defaultEvidence)

        assertEquals(VerificationStatus.FAILURE, result.status)
    }

    // TASK 4 — REGRESSION TEST 5: Missing Z coordinate -> INCONCLUSIVE/ERROR.
    @Test
    fun test5_missingZCoordinate_inconclusiveOrError() {
        val baseline = createBaseline(z = 0.0)
        val observedMissingLoc = createObserved(metadata = emptyMap())

        val result = verifier.verify(moveCubeUpExpected, baseline, observedMissingLoc, defaultEvidence)

        assertEquals(VerificationStatus.UNCERTAIN, result.status)
        assertTrue(result.explanation.contains("Insufficient evidence") || result.explanation.contains("location"))
    }

    // TASK 4 — REGRESSION TEST 6: Stale observation -> INCONCLUSIVE/ERROR.
    @Test
    fun test6_staleObservation_inconclusiveOrError() {
        val baselineTime = Instant.now()
        val baseline = createBaseline(z = 0.0).copy(capturedAt = baselineTime)
        // Stale timestamp before baseline:
        val observedStaleTime = createObserved(z = 1.0).copy(capturedAt = baselineTime.minusSeconds(10))

        val resultStaleTime = verifier.verify(moveCubeUpExpected, baseline, observedStaleTime, defaultEvidence)
        assertEquals(VerificationStatus.UNCERTAIN, resultStaleTime.status)
        assertTrue(resultStaleTime.explanation.contains("stale"))

        // Explicit STALE freshness metadata:
        val observedStaleMeta = createObserved(z = 1.0, stateMetadata = mapOf("freshness" to "STALE"))
        val resultStaleMeta = verifier.verify(moveCubeUpExpected, baseline, observedStaleMeta, defaultEvidence)
        assertEquals(VerificationStatus.UNCERTAIN, resultStaleMeta.status)
    }

    // TASK 4 — REGRESSION TEST 7: Wrong task ID or mismatched expected-state definition -> FAIL.
    @Test
    fun test7_wrongTaskIdOrMismatchedExpectedState_fails() {
        // Step 1's condition is ensuring Object Mode, but evaluating a Mode condition for a different expected state
        val step1Expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetMode = "EDIT",
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.MODE, expectedValue = "EDIT")
            )
        )
        val baseline = createBaseline(z = 0.0)
        val observedInObjectMode = createObserved(z = 1.0).copy(mode = "OBJECT")

        val result = verifier.verify(step1Expected, baseline, observedInObjectMode, defaultEvidence)

        assertEquals(VerificationStatus.FAILURE, result.status)
    }

    // TASK 4 — REGRESSION TEST 8: A required condition fails while another passes -> FAIL.
    @Test
    fun test8_requiredConditionFailsWhileAnotherPasses_fails() {
        // Cube exists (PASS), but location.z > 0 (FAILS when z = 0.0)
        val baseline = createBaseline(z = 0.0)
        val observed = createObserved(z = 0.0)

        val result = verifier.verify(moveCubeUpExpected, baseline, observed, defaultEvidence)

        assertEquals(VerificationStatus.FAILURE, result.status)
        assertTrue(result.satisfiedConditions.any { it.field == ExpectedStateField.OBJECT_EXISTS })
        assertTrue(result.unsatisfiedConditions.any { it.field == ExpectedStateField.OBJECT_PROPERTY })
    }

    // TASK 4 — REGRESSION TEST 9: Failure messages match the condition that actually failed.
    @Test
    fun test9_failureMessagesMatchConditionThatActuallyFailed() {
        val baseline = createBaseline(z = 0.0)

        // 1. Z = 0.0 failure message
        val observed0 = createObserved(z = 0.0)
        val result0 = verifier.verify(moveCubeUpExpected, baseline, observed0, defaultEvidence)
        assertEquals(VerificationStatus.FAILURE, result0.status)
        assertEquals(
            "Not verified: the cube's Z location has not reached the required target (observed: 0.0 m, required: > 0 m).",
            result0.explanation
        )

        // 2. Z = -1.0 failure message
        val observedMinus1 = createObserved(z = -1.0)
        val resultMinus1 = verifier.verify(moveCubeUpExpected, baseline, observedMinus1, defaultEvidence)
        assertEquals(VerificationStatus.FAILURE, resultMinus1.status)
        assertEquals(
            "Not verified: the cube's Z location has not reached the required target (observed: -1.0 m, required: > 0 m).",
            resultMinus1.explanation
        )

        // 3. A value satisfying Z > 0 (e.g. 1.0) must never be reported as failing
        val observed1 = createObserved(z = 1.0)
        val result1 = verifier.verify(moveCubeUpExpected, baseline, observed1, defaultEvidence)
        assertEquals(VerificationStatus.SUCCESS, result1.status)
        assertFalse(result1.explanation.contains("Not verified"))
    }

    // TASK 4 — REGRESSION TEST 10: A failed retry cannot reuse a previous success.
    @Test
    fun test10_failedRetryCannotReusePreviousSuccess() = runBlocking {
        val baselineObs = BlenderStateObservation(
            sceneName = "Scene",
            mode = "OBJECT",
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            objects = listOf(
                ObjectObservation(id = "cube1", name = "Cube", type = "MESH", sceneName = "Scene", selected = true,
                    metadata = mapOf("location" to "0.0,0.0,0.0", "location.z" to "0.0"))
            ),
            selectedObjects = setOf("Cube"),
            metadata = mapOf("complete" to "true")
        )

        val movedObs = BlenderStateObservation(
            sceneName = "Scene",
            mode = "OBJECT",
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            objects = listOf(
                ObjectObservation(id = "cube1", name = "Cube", type = "MESH", sceneName = "Scene", selected = true,
                    metadata = mapOf("location" to "0.0,0.0,1.0", "location.z" to "1.0"))
            ),
            selectedObjects = setOf("Cube"),
            metadata = mapOf("complete" to "true")
        )

        val unmovedObs = BlenderStateObservation(
            sceneName = "Scene",
            mode = "OBJECT",
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            objects = listOf(
                ObjectObservation(id = "cube1", name = "Cube", type = "MESH", sceneName = "Scene", selected = true,
                    metadata = mapOf("location" to "0.0,0.0,0.0", "location.z" to "0.0"))
            ),
            selectedObjects = setOf("Cube"),
            metadata = mapOf("complete" to "true")
        )

        val provider = MockBlenderProvider(baselineObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { ctrl -> ClosedLoopRecoveryCoordinator(ctrl, workflow) }
        )
        controller.startUserGoal("Make a cube and move it")

        // Advance to step 4 ("Move Cube Up")
        while (controller.state.currentStepIndex < 3) {
            controller.nextStep()
        }
        assertEquals("Move Cube Up", controller.currentStep?.title)

        val activeStep = controller.currentStep!!
        assertNotNull(activeStep.expectedState)

        // 1. Initial verification attempt with moved cube succeeds
        provider.currentObservation = baselineObs
        val run = workflow.captureBaseline(activeStep.expectedState!!)
        provider.currentObservation = movedObs
        val resultSuccess = workflow.verifyAction(run)
        assertEquals(VerificationStatus.SUCCESS, resultSuccess.status)

        // 2. Subsequent retry attempt with unmoved cube MUST FAIL and not reuse success
        provider.currentObservation = baselineObs
        val run2 = workflow.captureBaseline(activeStep.expectedState!!)
        provider.currentObservation = unmovedObs
        val resultFail = workflow.verifyAction(run2)

        assertEquals(VerificationStatus.FAILURE, resultFail.status)
        assertTrue(resultFail.explanation.contains("Not verified: the cube's Z location has not reached the required target"))
    }

    // TASK 4 — REGRESSION TEST 11: A successful retry updates progress exactly once.
    @Test
    fun test11_successfulRetryUpdatesProgressExactlyOnce() = runBlocking {
        val unmovedObs = BlenderStateObservation(
            sceneName = "Scene",
            mode = "OBJECT",
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            objects = listOf(
                ObjectObservation(id = "cube1", name = "Cube", type = "MESH", sceneName = "Scene", selected = true,
                    metadata = mapOf("location" to "0.0,0.0,0.0", "location.z" to "0.0"))
            ),
            selectedObjects = setOf("Cube"),
            metadata = mapOf("complete" to "true")
        )

        val movedObs = BlenderStateObservation(
            sceneName = "Scene",
            mode = "OBJECT",
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            objects = listOf(
                ObjectObservation(id = "cube1", name = "Cube", type = "MESH", sceneName = "Scene", selected = true,
                    metadata = mapOf("location" to "0.0,0.0,1.0", "location.z" to "1.0"))
            ),
            selectedObjects = setOf("Cube"),
            metadata = mapOf("complete" to "true")
        )

        val provider = MockBlenderProvider(unmovedObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { ctrl -> ClosedLoopRecoveryCoordinator(ctrl, workflow) }
        )
        controller.startUserGoal("Make a cube and move it")

        while (controller.state.currentStepIndex < 3) {
            controller.nextStep()
        }
        assertEquals("Move Cube Up", controller.currentStep?.title)
        val initialStepIndex = controller.state.currentStepIndex
        val completedBefore = controller.goalProgressState.completedStepIds.size

        // Attempt 1: Unchanged cube fails
        val state1 = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(state1 is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(initialStepIndex, controller.state.currentStepIndex)
        assertFalse(controller.goalProgressState.completedStepIds.contains("step_4"))
        val completedAfterFail = controller.goalProgressState.completedStepIds.size

        // Retry step
        controller.recoveryCoordinator.retryStep()

        // Attempt 2: Cube moved to Z = 1.0 succeeds
        provider.currentObservation = movedObs
        val state2 = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(state2 is ClosedLoopRecoveryState.VerifiedSuccess)

        // Progress updated exactly once
        assertEquals(completedAfterFail + 1, controller.goalProgressState.completedStepIds.size)
        assertTrue(controller.goalProgressState.completedStepIds.contains("step_4"))
        // Step 4 is marked completed
        assertTrue(controller.isStepIndexCompleted(initialStepIndex))
    }

    // TASK 4 — REGRESSION TEST 12: Existing cube creation and object deletion verification remain correct.
    @Test
    fun test12_existingCubeCreationAndObjectDeletionVerificationRemainCorrect() {
        val baseTime = Instant.now()
        // CREATE verification:
        val createExpected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Cube",
            targetType = "MESH"
        )
        val baselineNoCube = BaselineState(
            runId = "run-c",
            sceneName = "Scene",
            capturedAt = baseTime,
            objects = emptyList(),
            isComplete = true
        )
        val baselineWithCube = BaselineState(
            runId = "run-c",
            sceneName = "Scene",
            capturedAt = baseTime,
            objects = listOf(
                ObjectObservation(id = "c1", name = "Cube", type = "MESH", sceneName = "Scene")
            ),
            isComplete = true
        )
        val observedWithCube = ObservedState(
            runId = "run-c",
            sceneName = "Scene",
            capturedAt = baseTime.plusMillis(50),
            objects = listOf(
                ObjectObservation(id = "c1", name = "Cube", type = "MESH", sceneName = "Scene")
            ),
            isComplete = true
        )
        val createPass = verifier.verify(createExpected, baselineNoCube, observedWithCube, defaultEvidence)
        assertEquals(VerificationStatus.SUCCESS, createPass.status, "Cube creation must succeed when cube added")

        // Already existed at baseline:
        val createFail = verifier.verify(createExpected, baselineWithCube, observedWithCube, defaultEvidence)
        assertEquals(VerificationStatus.FAILURE, createFail.status, "Cube creation must fail when cube already existed at baseline")

        // DELETE verification:
        val deleteExpected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube"
        )
        val observedNoCube = ObservedState(
            runId = "run-d",
            sceneName = "Scene",
            capturedAt = baseTime.plusMillis(50),
            objects = emptyList(),
            isComplete = true
        )
        val baselineDelete = BaselineState(
            runId = "run-d",
            sceneName = "Scene",
            capturedAt = baseTime,
            objects = listOf(
                ObjectObservation(id = "c1", name = "Cube", type = "MESH", sceneName = "Scene")
            ),
            isComplete = true
        )
        val deletePass = verifier.verify(deleteExpected, baselineDelete, observedNoCube, defaultEvidence)
        assertEquals(VerificationStatus.SUCCESS, deletePass.status, "Cube deletion must succeed when cube removed")

        val deleteFail = verifier.verify(deleteExpected, baselineDelete, observedWithCube.copy(runId = "run-d", capturedAt = baseTime.plusMillis(50)), defaultEvidence)
        assertEquals(VerificationStatus.FAILURE, deleteFail.status, "Cube deletion must fail when cube still present")
    }

    // 13. Horizontal movement only fails Z > 0 requirement.
    @Test
    fun test13_cubeMovedHorizontallyOnly_fails() {
        val baseline = createBaseline(z = 0.0)
        val observed = createObserved(x = 3.0, y = 2.0, z = 0.0)

        val result = verifier.verify(moveCubeUpExpected, baseline, observed, defaultEvidence)

        assertEquals(VerificationStatus.FAILURE, result.status)
        assertTrue(result.explanation.contains("Not verified: the cube's Z location has not reached the required target"))
        assertTrue(result.explanation.contains("observed: 0.0 m"))
    }

    // 14. Blender object naming suffixes do not cause incorrect object matching.
    @Test
    fun test14_objectNamingSuffixes_matchCorrectlyWithoutMatchingUnrelated() {
        val baseline = createBaseline(z = 0.0, name = "Cube.001", id = "c_001")
        val observed = createObserved(z = 2.0, name = "Cube.001", id = "c_001")

        // Should match "Cube.001" as a valid instance of expected "Cube"
        val result = verifier.verify(moveCubeUpExpected, baseline, observed, defaultEvidence)
        assertEquals(VerificationStatus.SUCCESS, result.status)

        // Unrelated objects such as "CubeCollider" or "CubeRig" must NOT match
        val unrelatedObserved = createObserved(z = 2.0, name = "CubeCollider", id = "c_col")
        val resultUnrelated = verifier.verify(moveCubeUpExpected, baseline, unrelatedObserved, defaultEvidence)
        assertEquals(VerificationStatus.FAILURE, resultUnrelated.status)
    }

    // 15. Verification retries preserve the correct active task identity.
    @Test
    fun test15_verificationRetriesPreserveActiveTaskIdentity() = runBlocking {
        val unmovedObs = BlenderStateObservation(
            sceneName = "Scene",
            mode = "OBJECT",
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            objects = listOf(
                ObjectObservation(id = "c1", name = "Cube", type = "MESH", sceneName = "Scene", selected = true,
                    metadata = mapOf("location" to "0.0,0.0,0.0", "location.z" to "0.0"))
            ),
            selectedObjects = setOf("Cube"),
            metadata = mapOf("complete" to "true")
        )

        val provider = MockBlenderProvider(unmovedObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { ctrl -> ClosedLoopRecoveryCoordinator(ctrl, workflow) }
        )
        controller.startUserGoal("Make a cube and move it")

        while (controller.state.currentStepIndex < 3) {
            controller.nextStep()
        }
        assertEquals("Move Cube Up", controller.currentStep?.title)

        val coordinator = controller.recoveryCoordinator

        // First verification attempt: fails
        val state1 = coordinator.verifyActiveStep()
        assertTrue(state1 is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(1, (state1 as ClosedLoopRecoveryState.RecoveryRecommended).attemptNumber)
        assertEquals("Move Cube Up", state1.stepTitle)
        assertEquals(3, state1.stepIndex)

        // Retry step
        coordinator.retryStep()
        assertEquals(3, controller.state.currentStepIndex)
        assertEquals("Move Cube Up", controller.currentStep?.title)

        // Second verification attempt: preserves task identity and advances attempt number
        val state2 = coordinator.verifyActiveStep()
        assertTrue(state2 is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(2, (state2 as ClosedLoopRecoveryState.RecoveryRecommended).attemptNumber)
        assertEquals("Move Cube Up", state2.stepTitle)
        assertEquals(3, state2.stepIndex)
    }

    // 16. UI progress cannot advance following failed verification.
    @Test
    fun test16_uiProgressCannotAdvanceFollowingFailedVerification() = runBlocking {
        val unmovedObs = BlenderStateObservation(
            sceneName = "Scene",
            mode = "OBJECT",
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            objects = listOf(
                ObjectObservation(id = "c1", name = "Cube", type = "MESH", sceneName = "Scene", selected = true,
                    metadata = mapOf("location" to "0.0,0.0,0.0", "location.z" to "0.0"))
            ),
            selectedObjects = setOf("Cube"),
            metadata = mapOf("complete" to "true")
        )

        val provider = MockBlenderProvider(unmovedObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { ctrl -> ClosedLoopRecoveryCoordinator(ctrl, workflow) }
        )
        controller.startUserGoal("Make a cube and move it")

        while (controller.state.currentStepIndex < 3) {
            controller.nextStep()
        }
        val initialStepIndex = controller.state.currentStepIndex

        val coordinator = controller.recoveryCoordinator

        // Execute verification when cube is NOT moved (Z = 0)
        val resultState = coordinator.verifyActiveStep()

        assertTrue(resultState is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(initialStepIndex, controller.state.currentStepIndex)
        assertFalse(controller.goalProgressState.completedStepIds.contains("step_4"))
        assertFalse(controller.isStepIndexCompleted(initialStepIndex))
    }

    // 17. Explicit relative baseline condition (e.g. "> baseline") is evaluated relative to baseline.
    @Test
    fun test17_explicitRelativeBaselineCondition_evaluatedRelative() {
        val relativeExpected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetType = "MESH",
            targetProperty = "location.z",
            expectedPropertyValue = "> baseline",
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, objectName = "Cube", expectedValue = "Cube")
            )
        )

        val baseline2 = createBaseline(z = 2.0)
        val observedUnmoved = createObserved(z = 2.0)
        val resultUnmoved = verifier.verify(relativeExpected, baseline2, observedUnmoved, defaultEvidence)
        assertEquals(VerificationStatus.FAILURE, resultUnmoved.status, "Unmoved cube must fail explicit > baseline condition")

        val observedMoved = createObserved(z = 3.0)
        val resultMoved = verifier.verify(relativeExpected, baseline2, observedMoved, defaultEvidence)
        assertEquals(VerificationStatus.SUCCESS, resultMoved.status, "Cube moved to Z=3 must pass explicit > baseline condition from Z=2")
    }
}
