package bridge

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
import context.VerificationStatus
import kotlinx.coroutines.runBlocking
import tutor.ClosedLoopRecoveryCoordinator
import tutor.ClosedLoopRecoveryState
import tutor.TutorController
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeleteObjectVerificationRegressionTest {

    private class MockBlenderProvider(
        var currentObservation: BlenderStateObservation
    ) : BlenderStateProvider {
        var observeCallCount = 0
        override suspend fun observe(): BlenderStateObservation {
            observeCallCount++
            return currentObservation
        }
    }

    private val verifier = StateVerifier()

    private fun createSceneObs(
        objects: List<ObjectObservation>,
        selectedObjects: Set<String> = emptySet(),
        status: BlenderObservationStatus = BlenderObservationStatus.AVAILABLE,
        isComplete: Boolean = true,
        freshness: String = "FRESH"
    ): BlenderStateObservation {
        return BlenderStateObservation(
            status = status,
            capturedAt = Instant.now(),
            sessionId = "session-1",
            sceneId = "scene-1",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = objects,
            selectedObjects = selectedObjects,
            metadata = mapOf(
                "applicationName" to "Blender",
                "applicationVersion" to "5.2.2 LTS",
                "complete" to isComplete.toString(),
                "freshness" to freshness
            )
        )
    }

    private fun standardObjects(includeSphere: Boolean = true, sphereSelected: Boolean = true, sphereName: String = "Sphere"): List<ObjectObservation> {
        val list = mutableListOf(
            ObjectObservation(id = "cam_1", name = "Camera", type = "CAMERA", sceneName = "Scene", selected = false),
            ObjectObservation(id = "light_1", name = "Light", type = "LIGHT", sceneName = "Scene", selected = false)
        )
        if (includeSphere) {
            list.add(
                ObjectObservation(
                    id = "sphere_1",
                    name = sphereName,
                    type = "MESH",
                    sceneName = "Scene",
                    selected = sphereSelected,
                    metadata = mapOf("location" to "0.0,0.0,0.0")
                )
            )
        }
        return list
    }

    private fun sphereScene(sphereSelected: Boolean = true, sphereName: String = "Sphere"): BlenderStateObservation {
        val objs = standardObjects(includeSphere = true, sphereSelected = sphereSelected, sphereName = sphereName)
        val selected = if (sphereSelected) setOf(sphereName) else emptySet()
        return createSceneObs(objs, selected)
    }

    private fun deletedSphereScene(): BlenderStateObservation {
        val objs = standardObjects(includeSphere = false)
        return createSceneObs(objs, emptySet())
    }

    // =========================================================================
    // 1. Target Sphere exists and is selected -> DELETE FAILS
    // =========================================================================
    @Test
    fun test1_targetSphereExistsAndIsSelected_deleteFails() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep() // Move to Step 2: Delete Object

        assertEquals("Delete Object", controller.currentStep?.title)
        val outcome = controller.recoveryCoordinator.verifyActiveStep()

        assertTrue(outcome is ClosedLoopRecoveryState.RecoveryRecommended, "Expected RecoveryRecommended when target Sphere still exists and is selected")
        val lastAttempt = controller.recoveryCoordinator.getAttemptsForStep(1).lastOrNull()
        assertEquals(VerificationStatus.FAILURE, lastAttempt?.verificationResult?.status)
        assertFalse(controller.isStepIndexCompleted(1))
    }

    // =========================================================================
    // 2. Target Sphere exists but is not selected -> DELETE FAILS
    // =========================================================================
    @Test
    fun test2_targetSphereExistsButNotSelected_deleteFails() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        // Step 1: Select Sphere
        controller.recoveryCoordinator.verifyActiveStep()
        controller.nextStep() // Step 2: Delete Object

        // In Step 2, user deselects Sphere, but Sphere still exists in Blender
        provider.currentObservation = sphereScene(sphereSelected = false)
        val outcome = controller.recoveryCoordinator.verifyActiveStep()

        assertTrue(outcome is ClosedLoopRecoveryState.RecoveryRecommended, "Expected RecoveryRecommended when target Sphere still exists even if unselected")
        val lastAttempt = controller.recoveryCoordinator.getAttemptsForStep(1).lastOrNull()
        assertEquals(VerificationStatus.FAILURE, lastAttempt?.verificationResult?.status)
        assertFalse(controller.isStepIndexCompleted(1))
    }

    // =========================================================================
    // 3. Target Sphere is absent from a fresh authoritative scene observation -> DELETE PASSES
    // =========================================================================
    @Test
    fun test3_targetSphereAbsentFromFreshAuthoritativeObservation_deletePasses() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        // Step 1: Select
        controller.recoveryCoordinator.verifyActiveStep()
        controller.nextStep()

        // Learner deletes Sphere
        provider.currentObservation = deletedSphereScene()

        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.VerifiedSuccess, "Expected VerifiedSuccess when Sphere is absent")
        val lastAttempt = controller.recoveryCoordinator.getAttemptsForStep(1).lastOrNull()
        assertEquals(VerificationStatus.SUCCESS, lastAttempt?.verificationResult?.status)
        assertTrue(controller.isStepIndexCompleted(1))
    }

    // =========================================================================
    // 4. Sphere still exists but Camera is absent -> DELETE FAILS
    // =========================================================================
    @Test
    fun test4_sphereExistsButCameraAbsent_deleteFails() = runBlocking {
        // Camera deleted, Sphere kept
        val objs = listOf(
            ObjectObservation(id = "light_1", name = "Light", type = "LIGHT", sceneName = "Scene", selected = false),
            ObjectObservation(id = "sphere_1", name = "Sphere", type = "MESH", sceneName = "Scene", selected = true)
        )
        val obs = createSceneObs(objs, setOf("Sphere"))
        val provider = MockBlenderProvider(obs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep() // Step 2

        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.RecoveryRecommended, "Deleting Camera while keeping Sphere must FAIL")
        val lastAttempt = controller.recoveryCoordinator.getAttemptsForStep(1).lastOrNull()
        assertEquals(VerificationStatus.FAILURE, lastAttempt?.verificationResult?.status)
    }

    // =========================================================================
    // 5. Sphere still exists but Light is absent -> DELETE FAILS
    // =========================================================================
    @Test
    fun test5_sphereExistsButLightAbsent_deleteFails() = runBlocking {
        // Light deleted, Sphere kept
        val objs = listOf(
            ObjectObservation(id = "cam_1", name = "Camera", type = "CAMERA", sceneName = "Scene", selected = false),
            ObjectObservation(id = "sphere_1", name = "Sphere", type = "MESH", sceneName = "Scene", selected = true)
        )
        val obs = createSceneObs(objs, setOf("Sphere"))
        val provider = MockBlenderProvider(obs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep() // Step 2

        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.RecoveryRecommended, "Deleting Light while keeping Sphere must FAIL")
        val lastAttempt = controller.recoveryCoordinator.getAttemptsForStep(1).lastOrNull()
        assertEquals(VerificationStatus.FAILURE, lastAttempt?.verificationResult?.status)
    }

    // =========================================================================
    // 6. Target object is missing from observation payload -> INCONCLUSIVE
    // =========================================================================
    @Test
    fun test6_targetObjectMissingFromObservationPayload_inconclusive() = runBlocking {
        // Incomplete observation
        val incompleteObs = createSceneObs(
            objects = standardObjects(includeSphere = false),
            isComplete = false
        )
        val provider = MockBlenderProvider(incompleteObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep()

        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.NeedsMoreEvidence,
            "Incomplete observation payload must be INCONCLUSIVE / NeedsMoreEvidence")
    }

    // =========================================================================
    // 7. Observation is stale -> INCONCLUSIVE
    // =========================================================================
    @Test
    fun test7_observationIsStale_inconclusive() {
        val expected = ExpectedState(operation = VerificationOperation.DELETE, targetName = "Sphere")
        val baseline = BaselineState(
            runId = "run-1",
            capturedAt = Instant.now().minusSeconds(5),
            applicationName = "Blender",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = standardObjects(includeSphere = true),
            isComplete = true
        )
        val staleObserved = ObservedState(
            runId = "run-1",
            capturedAt = Instant.now().minusSeconds(10), // Captured before baseline!
            applicationName = "Blender",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = standardObjects(includeSphere = false),
            metadata = mapOf("freshness" to "STALE"),
            isComplete = true
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, staleObserved, evidence)
        assertEquals(VerificationStatus.UNCERTAIN, result.status, "Stale observation must produce UNCERTAIN status")
    }

    // =========================================================================
    // 8. Observation is malformed -> INCONCLUSIVE
    // =========================================================================
    @Test
    fun test8_observationIsMalformed_inconclusive() = runBlocking {
        val failedObs = BlenderStateObservation(
            status = BlenderObservationStatus.FAILED,
            diagnostic = "Blender adapter returned an unsupported protocol header.",
            metadata = mapOf("complete" to "false")
        )
        val provider = MockBlenderProvider(failedObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep()

        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.NeedsMoreEvidence,
            "Malformed/failed observation must result in NeedsMoreEvidence")
    }

    // =========================================================================
    // 9. Target identity is ambiguous -> INCONCLUSIVE
    // =========================================================================
    @Test
    fun test9_targetIdentityIsAmbiguous_inconclusive() {
        val expected = ExpectedState(operation = VerificationOperation.DELETE, targetName = "Sphere")
        // Baseline contains two objects that match "Sphere": "Sphere" and "Sphere.001"
        val ambiguousObjects = listOf(
            ObjectObservation(id = "s1", name = "Sphere", type = "MESH", sceneName = "Scene"),
            ObjectObservation(id = "s2", name = "Sphere.001", type = "MESH", sceneName = "Scene")
        )
        val baseline = BaselineState(
            runId = "run-ambiguous",
            capturedAt = Instant.now().minusSeconds(1),
            applicationName = "Blender",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = ambiguousObjects,
            isComplete = true
        )
        val observed = ObservedState(
            runId = "run-ambiguous",
            capturedAt = Instant.now(),
            applicationName = "Blender",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = emptyList(),
            isComplete = true
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.UNCERTAIN, result.status, "Ambiguous target identity must yield UNCERTAIN")
    }

    // =========================================================================
    // 10. Object name has a Blender suffix such as Sphere.001 and identity is handled correctly
    // =========================================================================
    @Test
    fun test10_blenderSuffixSphere001_handledCorrectly() = runBlocking {
        // Scene has Sphere.001
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true, sphereName = "Sphere.001"))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep() // Delete step

        // Target Sphere.001 still in scene -> FAIL
        val outcome1 = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome1 is ClosedLoopRecoveryState.RecoveryRecommended, "Sphere.001 still in scene must FAIL deletion")

        // Target Sphere.001 deleted from scene -> PASS
        provider.currentObservation = deletedSphereScene()
        controller.recoveryCoordinator.retryStep()
        val outcome2 = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome2 is ClosedLoopRecoveryState.VerifiedSuccess, "Absence of Sphere.001 must PASS deletion")
    }

    // =========================================================================
    // 11. A failed deletion retry cannot reuse previous success
    // =========================================================================
    @Test
    fun test11_failedDeletionRetryCannotReusePreviousSuccess() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep()

        // Attempt 1: Sphere still exists -> FAIL
        val failOutcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(failOutcome is ClosedLoopRecoveryState.RecoveryRecommended)

        // Retry without deleting: Sphere still exists -> must FAIL again, cannot synthesize success
        controller.recoveryCoordinator.retryStep()
        val retryOutcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(retryOutcome is ClosedLoopRecoveryState.RecoveryRecommended, "Retry must fail if object is still present")
        assertFalse(controller.isStepIndexCompleted(1))
    }

    // =========================================================================
    // 12. A successful prior task cannot make a failed deletion pass
    // =========================================================================
    @Test
    fun test12_successfulPriorTaskCannotMakeFailedDeletionPass() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        // Step 1: Select Object to Delete
        val step1Outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(step1Outcome is ClosedLoopRecoveryState.VerifiedSuccess, "Step 1 (selection) succeeds")
        assertTrue(controller.isStepIndexCompleted(0))

        // Move to Step 2: Delete Object
        // But Sphere is NOT deleted in Blender!
        val step2Outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(step2Outcome is ClosedLoopRecoveryState.RecoveryRecommended, "Step 2 MUST fail despite Step 1 success")
        assertFalse(controller.isStepIndexCompleted(1))
    }

    // =========================================================================
    // 13. Failed deletion does not advance task progress
    // =========================================================================
    @Test
    fun test13_failedDeletionDoesNotAdvanceTaskProgress() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep()

        assertEquals(1, controller.state.currentStepIndex)
        controller.recoveryCoordinator.verifyActiveStep()

        // Index remains at 1 (Delete Object)
        assertEquals(1, controller.state.currentStepIndex)
        assertFalse(controller.goalProgressState.completedStepIds.contains("step_2"))
    }

    // =========================================================================
    // 14. Successful deletion updates progress exactly once
    // =========================================================================
    @Test
    fun test14_successfulDeletionUpdatesProgressExactlyOnce() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.recoveryCoordinator.verifyActiveStep() // Step 1 verified
        controller.nextStep()

        provider.currentObservation = deletedSphereScene()
        controller.recoveryCoordinator.verifyActiveStep()

        assertTrue(controller.goalProgressState.completedStepIds.contains("step_2"))
        val completedCount = controller.goalProgressState.completedStepIds.count { it == "step_2" }
        assertEquals(1, completedCount, "step_2 must be recorded as completed exactly once")
    }

    // =========================================================================
    // 15. Missing deletion conditions cannot default to success
    // =========================================================================
    @Test
    fun test15_missingDeletionConditionsCannotDefaultToSuccess() {
        val emptyExpected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Sphere",
            requiredConditions = emptyList<ExpectedCondition>() // No explicit required conditions supplied
        )
        val baseline = BaselineState(
            runId = "run-1",
            capturedAt = Instant.now().minusSeconds(1),
            applicationName = "Blender",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = standardObjects(includeSphere = true),
            isComplete = true
        )
        val observed = ObservedState(
            runId = "run-1",
            capturedAt = Instant.now(),
            applicationName = "Blender",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = standardObjects(includeSphere = true), // Sphere still present!
            isComplete = true
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(emptyExpected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status,
            "Verification must FAIL even if requiredConditions list was omitted on ExpectedState")
    }

    // =========================================================================
    // 18. Active task ID and displayed task must agree
    // =========================================================================
    @Test
    fun test18_activeTaskIdAndDisplayedTaskMustAgree() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        val plan = controller.startUserGoal("Delete Sphere")
        assertNotNull(plan)
        controller.nextStep()

        val activeStep = controller.currentStep
        assertNotNull(activeStep)
        assertEquals("Delete Object", activeStep.title)
        assertEquals(VerificationOperation.DELETE, activeStep.expectedState?.operation)
        assertEquals(2, activeStep.id)
        assertEquals("step_2", plan.steps[1].stepId)
    }

    // =========================================================================
    // 19. A selection-only change never satisfies deletion
    // =========================================================================
    @Test
    fun test19_selectionOnlyChangeNeverSatisfiesDeletion() = runBlocking {
        // Start with Sphere selected
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.recoveryCoordinator.verifyActiveStep() // Step 1 verified
        controller.nextStep()

        // User deselects Sphere in Blender, but Sphere remains in scene
        provider.currentObservation = sphereScene(sphereSelected = false)

        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.RecoveryRecommended,
            "Changing selection while keeping target object in scene must FAIL deletion")
        val lastAttempt = controller.recoveryCoordinator.getAttemptsForStep(1).lastOrNull()
        assertEquals(VerificationStatus.FAILURE, lastAttempt?.verificationResult?.status)
    }

    // =========================================================================
    // 20. Fresh post-action observations are used for deletion verification
    // =========================================================================
    @Test
    fun test20_freshPostActionObservationsAreUsedForDeletionVerification() = runBlocking {
        val provider = MockBlenderProvider(sphereScene(sphereSelected = true))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        controller.nextStep()

        val initialObserveCount = provider.observeCallCount
        controller.recoveryCoordinator.verifyActiveStep()

        // Verification must have called provider.observe() freshly
        assertTrue(provider.observeCallCount > initialObserveCount,
            "Verification must request fresh observations from provider")
    }
}
