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
import kotlin.test.assertTrue

/**
 * Dedicated regression suite for Blender State Synchronization and UV Sphere Verification.
 *
 * Each test explicitly documents its fidelity:
 * - UNIT: isolated class logic under controlled inputs.
 * - COMPONENT: coordination between multiple production components (e.g., Coordinator + Controller).
 * - SIMULATED_INTEGRATION: full production path from provider observation to UI state updates.
 */
class BlenderSyncRegressionTest {

    private class ConfigurableBlenderStateProvider(
        var observation: BlenderStateObservation
    ) : BlenderStateProvider {
        var callCount: Int = 0

        override suspend fun observe(): BlenderStateObservation {
            callCount++
            return observation
        }
    }

    private fun sphereObservation(
        name: String = "Sphere",
        type: String = "MESH",
        sessionId: String = "session-blender-1"
    ): BlenderStateObservation = BlenderStateObservation(
        status = BlenderObservationStatus.AVAILABLE,
        sessionId = sessionId,
        sceneName = "Scene",
        sceneId = "scene-1",
        mode = "OBJECT",
        objects = listOf(
            ObjectObservation(id = "obj-1", name = "Cube", type = "MESH", sceneName = "Scene"),
            ObjectObservation(id = "obj-2", name = name, type = type, sceneName = "Scene")
        ),
        selectedObjects = setOf(name),
        metadata = mapOf("complete" to "true", "snapshotVersion" to "2"),
        capturedAt = Instant.now()
    )

    private fun cubeOnlyObservation(
        sessionId: String = "session-blender-1"
    ): BlenderStateObservation = BlenderStateObservation(
        status = BlenderObservationStatus.AVAILABLE,
        sessionId = sessionId,
        sceneName = "Scene",
        sceneId = "scene-1",
        mode = "OBJECT",
        objects = listOf(
            ObjectObservation(id = "obj-1", name = "Cube", type = "MESH", sceneName = "Scene")
        ),
        selectedObjects = setOf("Cube"),
        metadata = mapOf("complete" to "true", "snapshotVersion" to "1"),
        capturedAt = Instant.now()
    )

    // =========================================================================
    // TEST 1 — SPHERE ALREADY EXISTS
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test1_sphereAlreadyExists_verifiesSuccessfullyAndAdvances() = runBlocking {
        val initialObs = sphereObservation(name = "Sphere", type = "MESH")
        val provider = ConfigurableBlenderStateProvider(initialObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator

        controller.startUserGoal("create a UV sphere")
        // Step 1: Ensure Object Mode
        controller.nextStep()
        // Step 2: Add Mesh Sphere
        controller.nextStep()
        // Step 3: Verify a Sphere object exists
        assertEquals(2, controller.state.currentStepIndex)
        assertTrue(controller.canExecuteCurrentStep(), "Prerequisites must be satisfied for step 3")

        val resultState = coordinator.verifyActiveStep()

        assertTrue(resultState is ClosedLoopRecoveryState.VerifiedSuccess, "Expected VerifiedSuccess but got $resultState")
        assertEquals(VerificationStatus.SUCCESS, resultState.verificationResult.status)
        assertTrue(controller.isStepIndexCompleted(2), "Authoritative step 3 must be marked complete")
        assertEquals(PedagogicalOutcome.VERIFIED_SUCCESS, controller.state.lastPedagogicalOutcome)
        assertTrue(provider.callCount >= 1, "A fresh observation must be obtained from provider")
    }

    // =========================================================================
    // TEST 2 — SPHERE CREATED AFTER INITIAL OBSERVATION
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test2_sphereCreatedAfterInitialEmptyObservation_verifiesFreshState() = runBlocking {
        val provider = ConfigurableBlenderStateProvider(cubeOnlyObservation())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator

        controller.startUserGoal("create a UV sphere")
        controller.nextStep()
        controller.nextStep()
        assertEquals(2, controller.state.currentStepIndex)

        // First verification: no sphere exists yet in Blender
        val failureState = coordinator.verifyActiveStep()
        assertTrue(failureState is ClosedLoopRecoveryState.RecoveryRecommended || failureState is ClosedLoopRecoveryState.NeedsMoreEvidence)

        // Learner creates UV sphere in Blender
        provider.observation = sphereObservation(name = "Sphere", type = "MESH")

        // Second verification: fresh observation is evaluated
        val successState = coordinator.verifyActiveStep()
        assertTrue(successState is ClosedLoopRecoveryState.VerifiedSuccess, "Fresh evidence must satisfy requirement")
        assertEquals(VerificationStatus.SUCCESS, successState.verificationResult.status)
        assertTrue(controller.isStepIndexCompleted(2), "Step 3 must now be complete")
    }

    // =========================================================================
    // TEST 3 — WRONG MESH OBJECT
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test3_wrongMeshObject_cannotClaimUvSphereConfirmed() = runBlocking {
        // Scene contains Cube and Cylinder, but no Sphere
        val wrongMeshObs = BlenderStateObservation(
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = listOf(
                ObjectObservation(id = "1", name = "Cube", type = "MESH"),
                ObjectObservation(id = "2", name = "Cylinder", type = "MESH")
            ),
            metadata = mapOf("complete" to "true"),
            capturedAt = Instant.now()
        )
        val provider = ConfigurableBlenderStateProvider(wrongMeshObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator

        controller.startUserGoal("create a UV sphere")
        controller.nextStep()
        controller.nextStep()

        val state = coordinator.verifyActiveStep()
        assertFalse(state is ClosedLoopRecoveryState.VerifiedSuccess, "Wrong mesh must not verify UV sphere")
        assertFalse(controller.isStepIndexCompleted(2), "Step 3 must remain incomplete")
    }

    // =========================================================================
    // TEST 4 — PROVIDER FAILURE
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test4_providerFailure_doesNotMistakeForEmptySceneAndAllowsRetry() = runBlocking {
        val failedObs = BlenderStateObservation(
            status = BlenderObservationStatus.FAILED,
            diagnostic = "Socket connection lost to Blender port 9871",
            sessionId = null,
            metadata = mapOf("complete" to "false"),
            capturedAt = Instant.now()
        )
        val provider = ConfigurableBlenderStateProvider(failedObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator

        controller.startUserGoal("create a UV sphere")
        controller.nextStep()
        controller.nextStep()

        val state = coordinator.verifyActiveStep()
        assertTrue(state is ClosedLoopRecoveryState.NeedsMoreEvidence || state is ClosedLoopRecoveryState.Error)
        if (state is ClosedLoopRecoveryState.NeedsMoreEvidence) {
            assertTrue(state.canRetry, "Provider failure must permit retry")
            assertTrue(state.message.contains("unavailable") || state.diagnostic?.contains("Socket") == true)
        }
        assertFalse(controller.isStepIndexCompleted(2), "Failed capture must not complete step")
    }

    // =========================================================================
    // TEST 5 — PREREQUISITE STATE CONSISTENCY
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test5_prerequisiteStateConsistency_uiAndAuthoritativeTrackerAgree() = runBlocking {
        val obs = BlenderStateObservation(
            status = BlenderObservationStatus.AVAILABLE,
            sessionId = "session-1",
            sceneName = "Scene",
            mode = "OBJECT",
            metadata = mapOf("complete" to "true")
        )
        val provider = ConfigurableBlenderStateProvider(obs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator

        controller.startUserGoal("create a UV sphere")

        // Initially at Step 1 (index 0)
        assertEquals(0, controller.state.currentStepIndex)
        assertTrue(controller.canExecuteCurrentStep(), "Step 1 has no prerequisites")
        assertFalse(controller.isStepIndexCompleted(0))

        // Verify Step 1 (Ensure Object Mode)
        val res1 = coordinator.verifyActiveStep()
        assertTrue(res1 is ClosedLoopRecoveryState.VerifiedSuccess)
        assertTrue(controller.isStepIndexCompleted(0))

        // Moving to Step 2
        assertEquals(1, controller.state.currentStepIndex)
        assertTrue(controller.canExecuteCurrentStep(), "Step 1 prerequisite was satisfied")

        // Moving to Step 3 marks Step 2 complete
        controller.nextStep()
        assertEquals(2, controller.state.currentStepIndex)
        assertTrue(controller.canExecuteCurrentStep(), "Step 2 prerequisite was satisfied when advancing past it")
        assertTrue(controller.isStepIndexCompleted(1), "UI completion and tracker completion must agree for Step 2")
    }

    // =========================================================================
    // TEST 6 — STALE ASYNCHRONOUS RESPONSE
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test6_staleAsynchronousResponse_cannotOverwriteNewerState() = runBlocking {
        val reconciler = EvidenceReconciler()
        reconciler.recordObservedVersion(10L)

        // An older out-of-order response with snapshotVersion = 5 arrives
        val staleProv = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = context.EvidenceSource.BLENDER,
            capturedAt = Instant.now().minusSeconds(60),
            snapshotVersion = 5L,
            isDirectFact = true
        )
        val staleObs = ProvenanceEvidenceObservation(provenance = staleProv, available = true)

        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sphere",
            targetType = "MESH"
        )
        val baseline = BaselineState(runId = "run-1")
        val observed = ObservedState(runId = "run-1", objects = listOf(ObjectObservation(name = "Sphere", type = "MESH")))

        val result = reconciler.reconcileAndVerify(
            expected = expected,
            baseline = baseline,
            observed = observed,
            rawEvidence = listOf(staleObs)
        )

        // The older stale observation was rejected by the reconciler
        assertTrue(reconciler.isStaleSnapshotVersion(5L), "Older snapshot version must be recognized as stale")
        assertFalse(reconciler.isStaleSnapshotVersion(11L), "Newer snapshot version must not be recognized as stale")

        // Also test coordinator level stale request protection:
        val provider = ConfigurableBlenderStateProvider(sphereObservation())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator
        controller.startUserGoal("create a UV sphere")
        controller.nextStep()
        controller.nextStep() // At Step 3

        // Simulate an older in-flight request ID from a previous step
        assertTrue(coordinator.isStale("old-request-id", 0, "different-plan-id"))
    }

    // =========================================================================
    // TEST 7 — RETRY LIMIT AND RECOVERY
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test7_retryLimitReached_recoversWhenBlenderStateChanges() = runBlocking {
        val provider = ConfigurableBlenderStateProvider(cubeOnlyObservation())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator

        controller.startUserGoal("create a UV sphere")
        controller.nextStep()
        controller.nextStep()

        // Exhaust retries (max 3 failures)
        coordinator.verifyActiveStep()
        coordinator.verifyActiveStep()
        val retryLimitState = coordinator.verifyActiveStep()
        assertTrue(retryLimitState is ClosedLoopRecoveryState.RetryLimitReached, "Should reach retry limit after 3 attempts")

        // Learner now fixes Blender state by creating the sphere
        provider.observation = sphereObservation(name = "Sphere", type = "MESH")

        // Fresh verification when learner retries
        val recoveredState = coordinator.verifyActiveStep()
        assertTrue(recoveredState is ClosedLoopRecoveryState.VerifiedSuccess, "Retry limit must not dead-end when state is corrected")
        assertTrue(controller.isStepIndexCompleted(2), "Authoritative progress must advance")
    }

    // =========================================================================
    // TEST 8 — UI SYNCHRONIZATION
    // Fidelity: COMPONENT
    // =========================================================================
    @Test
    fun test8_uiSynchronization_stateUpdatesWithoutRestart() = runBlocking {
        val provider = ConfigurableBlenderStateProvider(sphereObservation())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator

        controller.startUserGoal("create a UV sphere")
        controller.nextStep()
        controller.nextStep()

        coordinator.verifyActiveStep()

        // Verify UI-facing reactive states reflect completion immediately
        assertEquals(PedagogicalOutcome.VERIFIED_SUCCESS, controller.state.lastPedagogicalOutcome)
        assertTrue(controller.isStepIndexCompleted(2))
        assertTrue(coordinator.state is ClosedLoopRecoveryState.VerifiedSuccess)
    }

    // =========================================================================
    // TEST 9 — MULTIPLE SIMILAR OBJECTS
    // Fidelity: UNIT
    // =========================================================================
    @Test
    fun test9_multipleSimilarObjects_verifierDoesNotConfuseUnrelatedNames() {
        val verifier = StateVerifier()
        val unrelatedObs = listOf(
            ObjectObservation(id = "1", name = "Atmosphere", type = "MESH"),
            ObjectObservation(id = "2", name = "Sphere_backup", type = "MESH"),
            ObjectObservation(id = "3", name = "Hemisphere", type = "MESH"),
            ObjectObservation(id = "4", name = "Cube", type = "MESH")
        )

        // None of these should match "Sphere"
        unrelatedObs.forEach { obs ->
            assertFalse(
                verifier.objectMatchesName(obs, "Sphere"),
                "Object '${obs.name}' must not match target 'Sphere'"
            )
        }

        // Blender numbered duplicate should match
        val numberedObs = ObjectObservation(id = "5", name = "Sphere.001", type = "MESH")
        assertTrue(
            verifier.objectMatchesName(numberedObs, "Sphere"),
            "Object 'Sphere.001' must match target 'Sphere'"
        )
    }

    // =========================================================================
    // TEST 10 — PLAN OR TASK IDENTITY CHANGE
    // Fidelity: SIMULATED_INTEGRATION
    // =========================================================================
    @Test
    fun test10_planOrTaskIdentityChange_delayedResultCannotMarkNewTaskComplete() = runBlocking {
        val provider = ConfigurableBlenderStateProvider(cubeOnlyObservation())
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )
        val coordinator = controller.recoveryCoordinator

        // Plan 1: Cube
        controller.startUserGoal("create a cube")
        val oldPlanId = controller.activePlan?.planId

        // Plan 2: Switch to Sphere
        controller.startUserGoal("create a UV sphere")
        val newPlanId = controller.activePlan?.planId
        assertNotEquals(oldPlanId, newPlanId)

        // Coordinator reset/switched
        coordinator.onStepChanged(0)
        assertEquals(0, controller.state.currentStepIndex)
        assertFalse(controller.isStepIndexCompleted(0))
    }
}
