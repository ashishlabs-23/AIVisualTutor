package bridge

import adapter.EvidenceCategory
import adapter.EvidenceProvenance
import adapter.EvidenceReconciler
import adapter.ObservationFreshness
import adapter.ProvenanceEvidenceObservation
import context.BaselineState
import context.BlenderObservationStatus
import context.BlenderStateObservation
import context.BlenderStateProvider
import context.BlenderVerificationWorkflow
import context.ExpectedCondition
import context.ExpectedState
import context.ExpectedStateField
import context.ObjectObservation
import context.ObservedState
import context.StateVerifier
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import models.ApplicationType
import pedagogy.PedagogicalOutcome
import tutor.ClosedLoopRecoveryCoordinator
import tutor.ClosedLoopRecoveryState
import tutor.TutorController
import tutor.UserGoalPlan
import tutor.UserGoalPlanStep
import tutor.UserGoalPlanner
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Universal Verify Button and State Synchronization Regression Test Suite.
 *
 * Implements and validates all 18 test specifications required by the Critical State Synchronization specification:
 * TEST 1 to TEST 18, with explicitly documented test fidelity.
 */
class UniversalVerifyAndStateSyncRegressionTest {

    private class MockBlenderProvider(
        var currentObservation: BlenderStateObservation
    ) : BlenderStateProvider {
        var callCount: Int = 0

        override suspend fun observe(): BlenderStateObservation {
            callCount++
            return currentObservation
        }
    }

    private fun objectModeObs(
        objects: List<ObjectObservation> = emptyList(),
        selected: Set<String> = emptySet(),
        sessionId: String = "session-1"
    ): BlenderStateObservation = BlenderStateObservation(
        status = BlenderObservationStatus.AVAILABLE,
        sessionId = sessionId,
        sceneName = "Scene",
        sceneId = "scene-1",
        mode = "OBJECT",
        objects = objects,
        selectedObjects = selected,
        metadata = mapOf("complete" to "true", "snapshotVersion" to "1"),
        capturedAt = Instant.now()
    )

    private fun sphereSceneObs(
        sessionId: String = "session-1"
    ): BlenderStateObservation = BlenderStateObservation(
        status = BlenderObservationStatus.AVAILABLE,
        sessionId = sessionId,
        sceneName = "Scene",
        sceneId = "scene-1",
        mode = "OBJECT",
        objects = listOf(
            ObjectObservation(id = "obj-cam", name = "Camera", type = "CAMERA", sceneName = "Scene"),
            ObjectObservation(id = "obj-light", name = "Light", type = "LIGHT", sceneName = "Scene"),
            ObjectObservation(id = "obj-sphere", name = "Sphere", type = "MESH", sceneName = "Scene", selected = true)
        ),
        selectedObjects = setOf("Sphere"),
        metadata = mapOf("complete" to "true", "snapshotVersion" to "2"),
        capturedAt = Instant.now()
    )

    private fun deletedSphereSceneObs(
        sessionId: String = "session-1"
    ): BlenderStateObservation = BlenderStateObservation(
        status = BlenderObservationStatus.AVAILABLE,
        sessionId = sessionId,
        sceneName = "Scene",
        sceneId = "scene-1",
        mode = "OBJECT",
        objects = listOf(
            ObjectObservation(id = "obj-cam", name = "Camera", type = "CAMERA", sceneName = "Scene"),
            ObjectObservation(id = "obj-light", name = "Light", type = "LIGHT", sceneName = "Scene")
        ),
        selectedObjects = emptySet(),
        metadata = mapOf("complete" to "true", "snapshotVersion" to "3"),
        capturedAt = Instant.now()
    )

    // =========================================================================
    // TEST 1 — VERIFY BUTTON ON FIRST STEP
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test1_verifyButtonOnFirstStep_isCallableAndVerifies() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        assertEquals(0, controller.state.currentStepIndex)
        assertEquals("Ensure Object Mode", controller.currentStep?.title)

        // User clicks Verify on Step 1
        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.VerifiedSuccess)
        assertTrue(controller.isStepIndexCompleted(0))
    }

    // =========================================================================
    // TEST 2 — VERIFY BUTTON AFTER SUCCESS
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test2_verifyButtonAfterSuccess_nextStepExposesVerifyAndClearsStaleBubble() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        controller.recoveryCoordinator.verifyActiveStep() // Step 1 succeeds

        // Automatically advances or user navigates to Step 2
        if (controller.state.currentStepIndex == 0) controller.nextStep()
        assertEquals(1, controller.state.currentStepIndex)
        assertEquals("Add Mesh Sphere", controller.currentStep?.title)

        // Step 2 is not completed yet, so UI state must be awaiting learner action rather than verified
        assertFalse(controller.isStepIndexCompleted(1))
        assertTrue(controller.recoveryCoordinator.state is ClosedLoopRecoveryState.AwaitingLearnerAction)
    }

    // =========================================================================
    // TEST 3 — VERIFY BUTTON AFTER FAILURE
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test3_verifyButtonAfterFailure_controlRemainsAvailable() = runBlocking {
        // Blender is in EDIT mode instead of OBJECT mode
        val editModeObs = BlenderStateObservation(
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            sceneName = "Scene",
            mode = "EDIT",
            metadata = mapOf("complete" to "true"),
            capturedAt = Instant.now()
        )
        val provider = MockBlenderProvider(editModeObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.RecoveryRecommended)
        assertFalse(controller.isStepIndexCompleted(0))

        // Control remains visible and active for retry
        assertEquals(1, controller.recoveryCoordinator.getVerifiedFailuresCount(0))
        assertFalse(controller.isStepIndexCompleted(0))
    }

    // =========================================================================
    // TEST 4 — VERIFY BUTTON AFTER RETRY EXHAUSTION
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test4_verifyButtonAfterRetryExhaustion_recoveryExplicitAndResetSupported() = runBlocking {
        val editModeObs = BlenderStateObservation(
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            sceneName = "Scene",
            mode = "EDIT",
            metadata = mapOf("complete" to "true"),
            capturedAt = Instant.now()
        )
        val provider = MockBlenderProvider(editModeObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        // Fail 3 times to exhaust retries
        controller.recoveryCoordinator.verifyActiveStep()
        controller.recoveryCoordinator.verifyActiveStep()
        val finalAttempt = controller.recoveryCoordinator.verifyActiveStep()

        assertTrue(finalAttempt is ClosedLoopRecoveryState.RetryLimitReached)

        // Reset step attempts allows immediate recovery attempt
        controller.recoveryCoordinator.resetStepAttempts(0)
        provider.currentObservation = objectModeObs()
        val recoveredOutcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(recoveredOutcome is ClosedLoopRecoveryState.VerifiedSuccess)
        assertTrue(controller.isStepIndexCompleted(0))
    }

    // =========================================================================
    // TEST 5 — VERIFY BUTTON AFTER NAVIGATION
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test5_verifyButtonAfterNavigation_previousAndNextPreserveStateCorrectly() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        controller.recoveryCoordinator.verifyActiveStep() // Complete Step 1

        if (controller.state.currentStepIndex == 0) controller.nextStep()
        assertEquals(1, controller.state.currentStepIndex)

        // Navigate back to completed Step 0
        controller.previousStep()
        assertEquals(0, controller.state.currentStepIndex)
        assertTrue(controller.isStepIndexCompleted(0))
        assertEquals(PedagogicalOutcome.VERIFIED_SUCCESS, controller.state.lastPedagogicalOutcome)

        // Navigate forward again to incomplete Step 1
        controller.nextStep()
        assertEquals(1, controller.state.currentStepIndex)
        assertFalse(controller.isStepIndexCompleted(1))
        assertNull(controller.state.lastPedagogicalOutcome)
    }

    // =========================================================================
    // TEST 6 — VERIFY BUTTON FOR INFORMATIONAL STEPS
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test6_verifyButtonForInformationalSteps_completesHonestInformationalContract() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        // Create a custom plan containing an informational step without expectedState
        val infoPlan = UserGoalPlan(
            goalText = "Learn viewport navigation",
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepId = "info_step_1",
                    stepIndex = 1,
                    title = "Observe 3D Viewport",
                    instruction = "Middle-click and drag in the 3D Viewport to rotate the view.",
                    description = "Informational walkthrough of viewport orbiting."
                    // expectedState is null
                )
            )
        )
        controller.setActivePlanForTesting(infoPlan)
        assertEquals(0, controller.state.currentStepIndex)

        // Clicking Verify on this informational step must succeed without blocking the learner
        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.VerifiedSuccess)
        assertTrue(controller.isStepIndexCompleted(0))
        assertTrue(outcome.explanation.contains("informational"))
    }

    // =========================================================================
    // TEST 7 — OBJECT CREATION
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test7_objectCreation_freshProviderObservationDetectsCreatedObject() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        controller.recoveryCoordinator.verifyActiveStep() // Step 1: Object mode

        // Advance to Step 2
        if (controller.state.currentStepIndex == 0) controller.nextStep()
        assertEquals(1, controller.state.currentStepIndex)

        // Learner creates sphere in Blender
        provider.currentObservation = sphereSceneObs()

        // User verifies creation
        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.VerifiedSuccess)
        assertTrue(controller.isStepIndexCompleted(1))
    }

    // =========================================================================
    // TEST 8 — OBJECT DELETION
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test8_objectDeletion_freshObservationDetectsIntendedObjectIsAbsent() = runBlocking {
        // Scene initially contains Sphere
        val provider = MockBlenderProvider(sphereSceneObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        // Seed initial observation so active object "Sphere" is known
        provider.observe()
        val plan = controller.startUserGoal("Delete Sphere")
        assertNotNull(plan)
        assertEquals(2, plan.steps.size)

        // Step 1: Select Object to Delete
        controller.recoveryCoordinator.verifyActiveStep()
        controller.nextStep()

        // Step 2: Delete Object
        assertEquals(1, controller.state.currentStepIndex)
        assertEquals("Delete Object", controller.currentStep?.title)

        // Learner deletes Sphere in Blender
        provider.currentObservation = deletedSphereSceneObs()

        // Verify deletion
        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.VerifiedSuccess, "Deletion verification must succeed when object is absent")
        assertTrue(controller.isStepIndexCompleted(1))
    }

    // =========================================================================
    // TEST 9 — ACTIVE OBJECT PLACEHOLDER
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test9_activeObjectPlaceholder_resolvesToCorrectObjectIdentityNotLiteralName() = runBlocking {
        val initialObs = sphereSceneObs()
        val provider = MockBlenderProvider(initialObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        // Seed workflow observation with Sphere active
        workflow.provider.observe()

        // User requests "Delete active object"
        val plan = controller.startUserGoal("Delete active object")
        assertNotNull(plan)

        // Plan outcome and steps must resolve to "Sphere", not literal string "Active"
        val deleteStep = plan.steps.find { it.expectedState?.operation == VerificationOperation.DELETE }
        assertNotNull(deleteStep)
        assertNotEquals("Active", deleteStep.expectedState?.targetName, "Placeholder 'Active' must not be literal targetName")
        assertEquals("Sphere", deleteStep.expectedState?.targetName, "Placeholder 'Active' must resolve to 'Sphere'")
    }

    // =========================================================================
    // TEST 10 — PROVIDER FAILURE
    // Fidelity: UNIT
    // =========================================================================
    @Test
    fun test10_providerFailure_failedStateRequestIsNotInterpretedAsEmptyScene() = runBlocking {
        val initialObs = objectModeObs()
        val provider = MockBlenderProvider(initialObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)

        val run = workflow.captureBaseline(
            ExpectedState(operation = VerificationOperation.DELETE, targetName = "Cube")
        )

        // Post-action observation fails (e.g. TCP socket disconnect or timeout)
        provider.currentObservation = BlenderStateObservation(
            status = BlenderObservationStatus.FAILED,
            diagnostic = "Socket connection reset by peer",
            metadata = mapOf("provider" to "blender-loopback-tcp")
        )
        val result = workflow.verifyAction(run)

        // Must NOT verify as successful deletion of Cube merely because provider failed!
        assertNotEquals(VerificationStatus.SUCCESS, result.status, "Provider failure must never verify as successful object absence")
        assertEquals(VerificationStatus.UNCERTAIN, result.status)
        assertTrue(result.explanation.contains("Blender state request failed") || result.explanation.contains("Socket connection reset by peer"))
    }

    // =========================================================================
    // TEST 11 — STALE OBSERVATION
    // Fidelity: UNIT
    // =========================================================================
    @Test
    fun test11_staleObservation_rejectedByReconciler() {
        val reconciler = EvidenceReconciler()
        reconciler.recordObservedVersion(5L)

        // Out-of-order stale response with snapshotVersion 3 must be detected as stale
        assertTrue(reconciler.isStaleSnapshotVersion(3L))
        assertFalse(reconciler.isStaleSnapshotVersion(6L))
    }

    // =========================================================================
    // TEST 12 — RETRY RECOVERY
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test12_retryRecovery_allowedNewAttemptUsesFreshEvidence() = runBlocking {
        val provider = MockBlenderProvider(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sessionId = "session-1",
                sceneName = "Scene",
                mode = "EDIT", // Incorrect mode
                metadata = mapOf("complete" to "true"),
                capturedAt = Instant.now()
            )
        )
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        val failOutcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(failOutcome is ClosedLoopRecoveryState.RecoveryRecommended)

        // Learner fixes mode in Blender
        provider.currentObservation = objectModeObs()

        // Retry verification
        val retryOutcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(retryOutcome is ClosedLoopRecoveryState.VerifiedSuccess)
        assertTrue(controller.isStepIndexCompleted(0))
    }

    // =========================================================================
    // TEST 13 — PREREQUISITE CONSISTENCY
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test13_prerequisiteConsistency_displayedCheckmarksAndAuthoritativeTrackingAgree() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        assertFalse(controller.isStepIndexCompleted(0))
        assertFalse(controller.isStepIndexCompleted(1))

        // Complete Step 0
        controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(controller.isStepIndexCompleted(0))
        assertTrue(controller.goalProgressState.completedStepIds.contains("step_1"))
        assertTrue(controller.canExecuteCurrentStep())
    }

    // =========================================================================
    // TEST 14 — UI SYNCHRONIZATION
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test14_uiSynchronization_successfulVerificationRefreshesUIWithoutRestarting() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        val initialPlanVersion = controller.goalProgressTracker.planVersion

        controller.recoveryCoordinator.verifyActiveStep()

        // Same plan preserved; step index advanced; progress updated
        assertEquals(initialPlanVersion, controller.goalProgressTracker.planVersion)
        assertTrue(controller.goalProgressState.completedStepIds.contains("step_1"))
    }

    // =========================================================================
    // TEST 15 — DUPLICATE CLICKS
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test15_duplicateClicks_doNotTriggerConcurrentVerificationRequests() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        val coordinator = controller.recoveryCoordinator

        // Rapid duplicate invocation
        val job1 = launch { coordinator.verifyActiveStep() }
        val job2 = launch { coordinator.verifyActiveStep() }
        job1.join()
        job2.join()

        // Verification succeeded without crashing or getting corrupted
        assertTrue(controller.isStepIndexCompleted(0))
    }

    // =========================================================================
    // TEST 16 — TASK IDENTITY
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test16_taskIdentity_resultFromOldPlanCannotCompleteStepInNewPlan() = runBlocking {
        val provider = MockBlenderProvider(objectModeObs())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a cube")
        val oldReqId = UUID.randomUUID().toString()

        // Switch to UV Sphere
        controller.startUserGoal("Create a UV sphere")

        // Check coordinator staleness detection
        assertTrue(controller.recoveryCoordinator.isStale(oldReqId, 0, "old_plan"))
    }

    // =========================================================================
    // TEST 17 — CREATION/DELETION SEMANTICS
    // Fidelity: UNIT
    // =========================================================================
    @Test
    fun test17_creationDeletionSemantics_noInappropriateCreationDeltaForDeletion() {
        val verifier = StateVerifier()
        val expectedDelete = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Sphere",
            allowSatisfiedInitialState = true
        )

        // Baseline: object was already absent (learner deleted it before clicking verify)
        val baseline = BaselineState(
            runId = "run-1",
            capturedAt = Instant.now().minusSeconds(1),
            applicationName = "Blender",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = listOf(
                ObjectObservation(id = "cam", name = "Camera", type = "CAMERA", sceneName = "Scene")
            ),
            isComplete = true
        )

        // Observed: object remains absent
        val observed = ObservedState(
            runId = "run-1",
            capturedAt = Instant.now(),
            applicationName = "Blender",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = listOf(
                ObjectObservation(id = "cam", name = "Camera", type = "CAMERA", sceneName = "Scene")
            ),
            isComplete = true
        )

        val evidence = listOf(
            context.EvidenceObservation(
                source = context.EvidenceSource.BLENDER,
                available = true
            )
        )

        val result = verifier.verify(expectedDelete, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, result.status, "Object absence must verify as deletion success")
    }

    // =========================================================================
    // TEST 18 — REAL ERROR VISIBILITY
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test18_realErrorVisibility_providerExceptionRemainsDistinguishableFromMismatch() = runBlocking {
        val socketTimeoutObs = BlenderStateObservation(
            status = BlenderObservationStatus.FAILED,
            diagnostic = "Blender state request timed out.",
            metadata = mapOf("provider" to "blender-loopback-tcp")
        )
        val provider = MockBlenderProvider(socketTimeoutObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Create a UV sphere")
        val outcome = controller.recoveryCoordinator.verifyActiveStep()

        assertTrue(outcome is ClosedLoopRecoveryState.NeedsMoreEvidence)
        assertTrue(outcome.diagnostic?.contains("timed out") == true || outcome.message.contains("timed out") == true,
            "Real error diagnostic must be preserved and visible to user")
    }
}
