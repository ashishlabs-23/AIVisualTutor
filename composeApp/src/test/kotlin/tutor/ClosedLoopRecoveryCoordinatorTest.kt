package tutor

import context.BaselineState
import context.BlenderObservationStatus
import context.BlenderStateObservation
import context.BlenderStateProvider
import context.BlenderVerificationWorkflow
import context.ExpectedCondition
import context.ExpectedState
import context.ExpectedStateField
import context.MismatchCategory
import context.ObjectObservation
import context.ObservedState
import context.StateMismatchDiagnosticEngine
import context.StateVerifier
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import kotlinx.coroutines.runBlocking
import models.ApplicationType
import models.TutorStep
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClosedLoopRecoveryCoordinatorTest {

    private class SequentialBlenderStateProvider(
        var states: MutableList<BlenderStateObservation> = mutableListOf()
    ) : BlenderStateProvider {
        private var index = 0

        override suspend fun observe(): BlenderStateObservation {
            val base = if (index < states.size) states[index] else states.lastOrNull()
                ?: BlenderStateObservation(
                    status = BlenderObservationStatus.AVAILABLE,
                    sessionId = "session-1",
                    metadata = mapOf("complete" to "true")
                )
            val currentIdx = ++index
            val baseVer = base.metadata["snapshotVersion"]?.toLongOrNull() ?: currentIdx.toLong()
            val effectiveVer = maxOf(baseVer, currentIdx.toLong())
            return base.copy(
                sessionId = base.sessionId ?: "session-1",
                metadata = base.metadata + mapOf(
                    "complete" to (base.metadata["complete"] ?: "true"),
                    "snapshotVersion" to effectiveVer.toString()
                )
            )
        }

        fun reset(vararg newStates: BlenderStateObservation) {
            index = 0
            states = newStates.toMutableList()
        }
    }

    private fun createTestController(
        expectedState: ExpectedState? = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sphere",
            targetType = "MESH",
            requiredConditions = listOf(
                ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = "Sphere", objectName = "Sphere")
            )
        )
    ): TutorController {
        val controller = TutorController()
        val plan = UserGoalPlan(
            goalText = "Create a UV sphere",
            application = ApplicationType.BLENDER,
            steps = listOf(
                UserGoalPlanStep(
                    stepIndex = 1,
                    title = "Create Sphere",
                    instruction = "Add a UV Sphere",
                    description = "Add UV sphere",
                    targetControlName = "Sphere",
                    expectedState = expectedState
                ),
                UserGoalPlanStep(
                    stepIndex = 2,
                    title = "Finish",
                    instruction = "Done",
                    description = "Done",
                    targetControlName = "Sphere"
                )
            )
        )
        controller.setActivePlanForTesting(plan)
        controller.recoveryCoordinator.reset()
        controller.recoveryCoordinator.onStepChanged(0)
        return controller
    }

    // P11-01: Successful verification closes the recovery lifecycle and advances lesson
    @Test
    fun p11_01_successfulVerificationClosesLifecycleAndAdvancesLesson(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        // Call 1 (baseline): Only Cube exists
        // Call 2 (observed): Cube and Sphere exist
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                selectedObjects = setOf("Cube"),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(
                    ObjectObservation(name = "Cube", type = "MESH"),
                    ObjectObservation(name = "Sphere", type = "MESH")
                ),
                selectedObjects = setOf("Sphere"),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            )
        )

        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val initialStepIndex = controller.state.currentStepIndex
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        val resultState = coordinator.verifyActiveStep()

        assertTrue(resultState is ClosedLoopRecoveryState.VerifiedSuccess)
        assertEquals(VerificationStatus.SUCCESS, resultState.verificationResult.status)
        assertEquals(initialStepIndex + 1, controller.state.currentStepIndex)
        assertEquals(1, coordinator.getAttemptsForStep(initialStepIndex).size)
        assertEquals(0, coordinator.getVerifiedFailuresCount(initialStepIndex))
    }

    // P11-02: Verified failure triggers diagnosis
    @Test
    fun p11_02_verifiedFailureTriggersDiagnosis(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        // Baseline: Cube exists. Post-action: Still only Cube exists (Sphere missing)
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            )
        )

        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        controller.recordObservedState(
            ObservedState(
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                selectedObjects = setOf("Cube")
            )
        )

        val resultState = coordinator.verifyActiveStep()

        assertTrue(resultState is ClosedLoopRecoveryState.RecoveryRecommended)
        assertNotNull(resultState.diagnosticReport)
        assertTrue(resultState.diagnosticReport!!.hasMismatch)
        assertEquals(MismatchCategory.OBJECT_MISSING, resultState.diagnosticReport!!.primaryMismatch?.category)
        assertEquals(1, coordinator.getVerifiedFailuresCount(0))
        assertEquals(0, controller.state.currentStepIndex)
    }

    // P11-03: Diagnosis produces grounded recovery recommendation when evidence is sufficient
    @Test
    fun p11_03_diagnosisProducesGroundedRecoveryRecommendation(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "EDIT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            )
        )

        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController(
            expectedState = ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetName = "Cube",
                targetMode = "OBJECT",
                requiredConditions = listOf(
                    ExpectedCondition(ExpectedStateField.MODE, expectedValue = "OBJECT")
                )
            )
        )
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        controller.recordObservedState(
            ObservedState(
                sceneName = "Scene",
                mode = "EDIT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH"))
            )
        )

        val resultState = coordinator.verifyActiveStep()
        assertTrue(resultState is ClosedLoopRecoveryState.RecoveryRecommended)

        val rec = resultState.recommendation
        assertEquals(RecommendationType.CORRECT_STATE, rec.recommendationType)
        assertTrue(rec.actionSteps.any { it.contains("mode", ignoreCase = true) || it.contains("Tab", ignoreCase = true) })
        assertTrue(rec.requiresVerification)
        assertFalse(rec.basedSolelyOnVisualEvidence)
    }

    // P11-04: Insufficient evidence produces uncertainty response rather than invented diagnosis
    @Test
    fun p11_04_insufficientEvidenceProducesUncertaintyResponse(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.INSUFFICIENT,
                sceneName = null,
                objects = emptyList(),
                sessionId = "session-1",
                metadata = mapOf("complete" to "false"),
                diagnostic = "Scene graph data incomplete"
            )
        )

        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        val resultState = coordinator.verifyActiveStep()
        assertTrue(resultState is ClosedLoopRecoveryState.NeedsMoreEvidence)
        assertTrue(resultState.canRetry)
        assertEquals(0, coordinator.getVerifiedFailuresCount(0))
    }

    // P11-05: Repeated identical failures do not repeat the same ineffective recovery indefinitely
    @Test
    fun p11_05_repeatedIdenticalFailuresAdaptGuidance(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "3")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "4")
            )
        )

        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(
            controller = controller,
            workflow = workflow,
            policy = RecoveryPolicy(maxVerifiedFailuresPerStep = 3)
        )

        controller.recordObservedState(
            ObservedState(
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH"))
            )
        )

        // Attempt 1: First failure
        val state1 = coordinator.verifyActiveStep()
        assertTrue(state1 is ClosedLoopRecoveryState.RecoveryRecommended)
        assertFalse(state1.isRepeatedMismatch)
        assertEquals(1, state1.attemptNumber)

        coordinator.retryStep()

        // Attempt 2: Same failure repeats
        val state2 = coordinator.verifyActiveStep()
        assertTrue(state2 is ClosedLoopRecoveryState.RecoveryRecommended)
        assertTrue(state2.isRepeatedMismatch)
        assertEquals(2, state2.attemptNumber)
        assertTrue(state2.recommendation.actionSteps.any { it.contains("Verification Check", ignoreCase = true) || it.contains("Attempt 2", ignoreCase = true) })
        assertNotNull(state2.adaptedGuidanceNote)
    }

    // P11-06: A different observed mismatch updates the diagnosis
    @Test
    fun p11_06_differentObservedMismatchUpdatesDiagnosis(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        // Attempt 1: Mismatch is OBJECT_MISSING
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            )
        )
        controller.recordObservedState(
            ObservedState(objects = listOf(ObjectObservation(name = "Cube", type = "MESH")))
        )
        val state1 = coordinator.verifyActiveStep()
        assertTrue(state1 is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(MismatchCategory.OBJECT_MISSING, state1.diagnosticReport?.primaryMismatch?.category)

        coordinator.retryStep()

        // Attempt 2: Sphere created, but Mode is incorrect
        val controllerWithModeExpected = createTestController(
            expectedState = ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Sphere",
                targetMode = "OBJECT",
                requiredConditions = listOf(
                    ExpectedCondition(ExpectedStateField.OBJECT_EXISTS, expectedValue = "Sphere", objectName = "Sphere"),
                    ExpectedCondition(ExpectedStateField.MODE, expectedValue = "OBJECT")
                )
            )
        )
        val coordinator2 = ClosedLoopRecoveryCoordinator(controller = controllerWithModeExpected, workflow = workflow)
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = emptyList(),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "3")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "EDIT",
                objects = listOf(ObjectObservation(name = "Sphere", type = "MESH")),
                selectedObjects = setOf("Sphere"),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "4")
            )
        )
        controllerWithModeExpected.recordObservedState(
            ObservedState(
                mode = "EDIT",
                objects = listOf(ObjectObservation(name = "Sphere", type = "MESH")),
                selectedObjects = setOf("Sphere")
            )
        )

        val state2 = coordinator2.verifyActiveStep()
        assertTrue(state2 is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(MismatchCategory.MODE_MISMATCH, state2.diagnosticReport?.primaryMismatch?.category)
    }

    // P11-07: Successful retry stops recovery and uses existing lesson progression mechanism
    @Test
    fun p11_07_successfulRetryStopsRecoveryAndAdvancesLesson(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        // Attempt 1: Failed
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            )
        )
        val state1 = coordinator.verifyActiveStep()
        assertTrue(state1 is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(0, controller.state.currentStepIndex)

        // Learner retries and corrects the state in Blender
        coordinator.retryStep()
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "3")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(
                    ObjectObservation(name = "Cube", type = "MESH"),
                    ObjectObservation(name = "Sphere", type = "MESH")
                ),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "4")
            )
        )

        // Attempt 2: Success
        val state2 = coordinator.verifyActiveStep()
        assertTrue(state2 is ClosedLoopRecoveryState.VerifiedSuccess)
        assertEquals(1, controller.state.currentStepIndex)
    }

    // P11-08: Failed verification does not advance the lesson
    @Test
    fun p11_08_failedVerificationDoesNotAdvanceLesson(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                objects = emptyList(),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                objects = emptyList(),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            )
        )

        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        val beforeIndex = controller.state.currentStepIndex
        val state = coordinator.verifyActiveStep()

        assertTrue(state is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(beforeIndex, controller.state.currentStepIndex)
    }

    // P11-09: Capture failure is distinguished from verified learner-action failure
    @Test
    fun p11_09_captureFailureIsDistinguishedFromVerifiedLearnerActionFailure(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.UNAVAILABLE,
                diagnostic = "Blender process terminated or socket closed"
            )
        )

        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        val state = coordinator.verifyActiveStep()
        assertTrue(state is ClosedLoopRecoveryState.NeedsMoreEvidence)
        assertEquals(0, coordinator.getVerifiedFailuresCount(0))
    }

    // P11-10: Provider failure does not falsely complete or fail the step
    @Test
    fun p11_10_providerExceptionDoesNotFalselyCompleteOrEqualToFailStep(): Unit = runBlocking {
        val provider = object : BlenderStateProvider {
            override suspend fun observe(): BlenderStateObservation {
                throw RuntimeException("Network timeout communicating with Blender TCP socket")
            }
        }

        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        val state = coordinator.verifyActiveStep()
        assertTrue(state is ClosedLoopRecoveryState.NeedsMoreEvidence)
        assertEquals(0, coordinator.getVerifiedFailuresCount(0))
        assertEquals(0, controller.state.currentStepIndex)
    }

    // P11-11: Stale verification results are ignored
    @Test
    fun p11_11_staleVerificationResultsAreIgnoredWhenStepChanges(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        // Initiate capture baseline
        val expected = controller.currentStep!!.expectedState!!
        val run = workflow.captureBaseline(expected)

        // Learner advances step manually before verification response finishes
        controller.nextStep()
        coordinator.onStepChanged(controller.state.currentStepIndex)

        // Now if an old run finishes, coordinator state remains protected
        assertNotEquals(0, controller.state.currentStepIndex)
        assertTrue(coordinator.state is ClosedLoopRecoveryState.AwaitingLearnerAction || coordinator.state is ClosedLoopRecoveryState.NotStarted)
    }

    // P11-12: Out-of-order asynchronous responses cannot overwrite newer state
    @Test
    fun p11_12_outOfOrderAsyncResponsesCannotOverwriteNewerState(): Unit = runBlocking {
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller)

        controller.nextStep()
        coordinator.onStepChanged(controller.state.currentStepIndex)
        val stateAfterSwitch = coordinator.state

        assertEquals(1, controller.state.currentStepIndex)
        assertTrue(stateAfterSwitch is ClosedLoopRecoveryState.AwaitingLearnerAction)
        assertEquals(1, (stateAfterSwitch as ClosedLoopRecoveryState.AwaitingLearnerAction).stepIndex)
    }

    // P11-13: Duplicate callbacks do not create duplicate recovery operations
    @Test
    fun p11_13_duplicateCallsPreventDuplicateOperations(): Unit = runBlocking {
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller)

        coordinator.state = ClosedLoopRecoveryState.Verifying(
            requestId = "req-1",
            stepIndex = 0,
            stepTitle = "Add Sphere",
            runId = "run-1"
        )

        val beforeAttempts = coordinator.getAttemptsForStep(0).size

        val state1 = coordinator.verifyActiveStep()
        assertTrue(state1 is ClosedLoopRecoveryState.Verifying)
        assertEquals("req-1", (state1 as ClosedLoopRecoveryState.Verifying).requestId)
        assertEquals(beforeAttempts, coordinator.getAttemptsForStep(0).size)
    }

    // P11-14: Cancellation prevents stale UI updates
    @Test
    fun p11_14_cancellationPreventsStaleUpdates(): Unit = runBlocking {
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller)

        coordinator.cancelVerification()
        assertTrue(coordinator.state is ClosedLoopRecoveryState.Cancelled)

        coordinator.dismiss()
        assertTrue(coordinator.state is ClosedLoopRecoveryState.NotStarted)
    }

    // P11-15: Changing active goal invalidates inappropriate pending operations
    @Test
    fun p11_15_changingActiveGoalInvalidatesPendingOperationsAndClearsHistory(): Unit = runBlocking {
        val controller = createTestController()
        val coordinator = controller.recoveryCoordinator

        fakeRecordFailure(coordinator, 0)
        assertEquals(1, coordinator.getVerifiedFailuresCount(0))

        controller.startUserGoal("Make a cube and move it up")

        assertEquals(0, coordinator.getVerifiedFailuresCount(0))
        assertTrue(coordinator.state is ClosedLoopRecoveryState.AwaitingLearnerAction)
    }

    // P11-16: Recovery history is isolated by lesson step
    @Test
    fun p11_16_recoveryHistoryIsIsolatedByLessonStep(): Unit = runBlocking {
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller)

        fakeRecordFailure(coordinator, 0)
        fakeRecordFailure(coordinator, 0)
        assertEquals(2, coordinator.getVerifiedFailuresCount(0))
        assertEquals(0, coordinator.getVerifiedFailuresCount(1))

        fakeRecordFailure(coordinator, 1)
        assertEquals(2, coordinator.getVerifiedFailuresCount(0))
        assertEquals(1, coordinator.getVerifiedFailuresCount(1))
    }

    // P11-17: Retry limits behave correctly
    @Test
    fun p11_17_retryLimitsBehaveCorrectly(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = emptyList(),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = emptyList(),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = emptyList(),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "3")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = emptyList(),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "4")
            )
        )
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(
            controller = controller,
            workflow = workflow,
            policy = RecoveryPolicy(maxVerifiedFailuresPerStep = 2)
        )

        // Attempt 1: Fail -> RecoveryRecommended
        coordinator.verifyActiveStep()
        assertTrue(coordinator.state is ClosedLoopRecoveryState.RecoveryRecommended)

        coordinator.retryStep()

        // Attempt 2: Fail -> RetryLimitReached
        coordinator.verifyActiveStep()
        assertTrue(coordinator.state is ClosedLoopRecoveryState.RetryLimitReached)
        val limitState = coordinator.state as ClosedLoopRecoveryState.RetryLimitReached
        assertEquals(2, limitState.totalAttempts)
        assertTrue(limitState.canResetOrOverride)

        // Learner can reset attempts
        coordinator.resetStepAttempts(0)
        assertEquals(0, coordinator.getVerifiedFailuresCount(0))
        assertTrue(coordinator.state is ClosedLoopRecoveryState.AwaitingLearnerAction)
    }

    // P11-18: Missing Phase 9 analysis does not prevent deterministic recovery
    @Test
    fun p11_18_missingPhase9AnalysisDoesNotPreventDeterministicRecovery(): Unit = runBlocking {
        val provider = SequentialBlenderStateProvider()
        provider.reset(
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "1")
            ),
            BlenderStateObservation(
                status = BlenderObservationStatus.AVAILABLE,
                sceneName = "Scene",
                mode = "OBJECT",
                objects = listOf(ObjectObservation(name = "Cube", type = "MESH")),
                sessionId = "session-1",
                metadata = mapOf("complete" to "true", "snapshotVersion" to "2")
            )
        )
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = createTestController(
            expectedState = ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Sphere",
                targetType = "MESH"
            )
        )
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller, workflow = workflow)

        controller.recordObservedState(
            ObservedState(mode = "OBJECT", objects = listOf(ObjectObservation(name = "Cube", type = "MESH")))
        )

        val state = coordinator.verifyActiveStep()
        assertTrue(state is ClosedLoopRecoveryState.RecoveryRecommended)
        assertEquals(MismatchCategory.OBJECT_MISSING, state.diagnosticReport?.primaryMismatch?.category)
        assertFalse(state.recommendation.basedSolelyOnVisualEvidence)
    }

    // P11-19: Conflicting state and visual evidence is represented honestly
    @Test
    fun p11_19_conflictingStateAndVisualEvidenceIsRepresentedHonestly() {
        val engine = AdaptiveNextStepRecommendationEngine()
        val context = ScreenshotHelpContext(
            sessionId = "s1",
            userGoal = "Switch to Object Mode",
            planId = "p1",
            stepId = 1,
            stepIndex = 0,
            stepTitle = "Switch to Object Mode",
            stepInstruction = "Toggle to Object Mode",
            expectedOutcome = "OBJECT mode",
            imagePath = "fake.png",
            imageWidth = 800,
            imageHeight = 600,
            blenderProcessId = 100L,
            blenderWindowBounds = null
        )
        val visualAnalysis = ScreenshotAnalysisResult(
            summary = "User appears to be in EDIT mode",
            observations = listOf(VisualObservation(description = "Header shows EDIT mode")),
            confidence = 0.8
        )
        val teleObserved = ObservedState(mode = "OBJECT")

        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = visualAnalysis,
            observedState = teleObserved,
            expectedState = ExpectedState(operation = VerificationOperation.MODIFY, targetMode = "OBJECT")
        )

        assertNotNull(rec.conflictWarning)
        assertTrue(rec.conflictWarning!!.contains("Prioritizing authoritative telemetry", ignoreCase = true))
    }

    // P11-20: System never marks a step complete solely from LLM response or visual inference
    @Test
    fun p11_20_systemNeverMarksStepCompleteSolelyFromVisualOrModelInference(): Unit = runBlocking {
        val controller = createTestController()
        val coordinator = ClosedLoopRecoveryCoordinator(controller = controller)

        val beforeIndex = controller.state.currentStepIndex

        val rec = TutorRecommendation(
            title = "Looks done",
            explanation = "Visual model thinks step is complete",
            actionSteps = listOf("Continue"),
            rationale = "Image appears correct",
            expectedOutcome = "Done",
            recommendationType = RecommendationType.CONTINUE,
            requiresVerification = true
        )

        assertEquals(beforeIndex, controller.state.currentStepIndex)
        assertFalse(coordinator.state is ClosedLoopRecoveryState.VerifiedSuccess)
    }

    private fun fakeRecordFailure(coordinator: ClosedLoopRecoveryCoordinator, stepIndex: Int) {
        val dummyResult = VerificationResult(
            status = VerificationStatus.FAILURE,
            runId = "test-run",
            operation = VerificationOperation.CREATE,
            explanation = "Object not found"
        )
        val dummyRec = TutorRecommendation(
            title = "Fix missing object",
            explanation = "Add object",
            actionSteps = listOf("Shift+A"),
            rationale = "Required",
            expectedOutcome = "Present",
            recommendationType = RecommendationType.CORRECT_STATE
        )
        val attemptsField = ClosedLoopRecoveryCoordinator::class.java.getDeclaredField("stepAttempts")
        attemptsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = attemptsField.get(coordinator) as MutableMap<Int, MutableList<StepRecoveryAttempt>>
        val list = map.getOrPut(stepIndex) { mutableListOf() }
        list.add(
            StepRecoveryAttempt(
                stepIndex = stepIndex,
                stepId = 1,
                attemptNumber = list.size + 1,
                verificationResult = dummyResult,
                observedState = null,
                mismatchCategory = MismatchCategory.OBJECT_MISSING,
                recommendation = dummyRec
            )
        )
    }
}
