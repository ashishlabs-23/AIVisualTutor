package ui.avatar

import context.BlenderObservationStatus
import context.VerificationStatus
import models.ApplicationType
import models.HighlightRegion
import models.TutorStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AvatarStateContractTest {

    @Test
    fun allTwelveSemanticStatesExistWithNonEmptyStatusLabels() {
        val states = AvatarPresentationState.values()
        assertEquals(12, states.size, "Must define exactly 12 semantic states per Phase 1 spec")

        val expectedNames = setOf(
            "STARTING", "IDLE", "THINKING", "GUIDING", "POINTING",
            "EXPLAINING", "WAITING_FOR_USER", "VERIFYING", "SUCCESS",
            "RECOVERY", "OFFLINE", "COMPACT"
        )
        assertEquals(expectedNames, states.map { it.name }.toSet())

        states.forEach { state ->
            assertTrue(state.defaultStatusLabel.isNotBlank(), "State $state must have non-blank status label")
        }
    }

    @Test
    fun transientVsPersistentStatesAreCorrectlyClassified() {
        val transientStates = AvatarPresentationState.values().filter { it.isTransient }.map { it.name }.toSet()
        val persistentStates = AvatarPresentationState.values().filter { !it.isTransient }.map { it.name }.toSet()

        val expectedTransient = setOf("STARTING", "THINKING", "VERIFYING", "SUCCESS")
        val expectedPersistent = setOf("IDLE", "GUIDING", "POINTING", "EXPLAINING", "WAITING_FOR_USER", "RECOVERY", "OFFLINE", "COMPACT")

        assertEquals(expectedTransient, transientStates)
        assertEquals(expectedPersistent, persistentStates)
    }

    @Test
    fun stepActivationWithTargetTransitionsToPointing() {
        val dummyStep = TutorStep(
            id = 1,
            title = "Add UV Sphere",
            instruction = "Press Shift+A and select UV Sphere",
            description = "Creates a 3D UV sphere mesh.",
            stepNumber = 1,
            totalSteps = 3,
            targetControlName = "3D Viewport -> Add"
        )

        val next = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.IDLE,
            event = AvatarDomainEvent.StepActivated(dummyStep, hasTarget = true)
        )
        assertEquals(AvatarPresentationState.POINTING, next)
    }

    @Test
    fun stepActivationWithoutTargetTransitionsToGuiding() {
        val dummyStep = TutorStep(
            id = 2,
            title = "Inspect Object",
            instruction = "Observe the sphere in the viewport",
            description = "View the newly added object.",
            stepNumber = 2,
            totalSteps = 3
        )

        val next = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.IDLE,
            event = AvatarDomainEvent.StepActivated(dummyStep, hasTarget = false)
        )
        assertEquals(AvatarPresentationState.GUIDING, next)
    }

    @Test
    fun goalOrQuerySubmissionTransitionsToThinking() {
        val nextGoal = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.IDLE,
            event = AvatarDomainEvent.GoalStarted("Build a low-poly tree")
        )
        assertEquals(AvatarPresentationState.THINKING, nextGoal)

        val nextQuery = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.GUIDING,
            event = AvatarDomainEvent.QuerySubmitted
        )
        assertEquals(AvatarPresentationState.THINKING, nextQuery)
    }

    @Test
    fun verificationOutcomesTransitionToSuccessOrRecovery() {
        val nextVerifying = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.WAITING_FOR_USER,
            event = AvatarDomainEvent.VerificationStarted
        )
        assertEquals(AvatarPresentationState.VERIFYING, nextVerifying)

        val nextSuccess = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.VERIFYING,
            event = AvatarDomainEvent.VerificationFinished(VerificationStatus.SUCCESS)
        )
        assertEquals(AvatarPresentationState.SUCCESS, nextSuccess)

        val nextFailure = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.VERIFYING,
            event = AvatarDomainEvent.VerificationFinished(VerificationStatus.FAILURE)
        )
        assertEquals(AvatarPresentationState.RECOVERY, nextFailure)

        val nextUncertain = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.VERIFYING,
            event = AvatarDomainEvent.VerificationFinished(VerificationStatus.UNCERTAIN)
        )
        assertEquals(AvatarPresentationState.RECOVERY, nextUncertain)
    }

    @Test
    fun stuckReportTransitionsToRecovery() {
        val next = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.WAITING_FOR_USER,
            event = AvatarDomainEvent.StuckReported
        )
        assertEquals(AvatarPresentationState.RECOVERY, next)
    }

    @Test
    fun blenderDisconnectOverridesToOffline() {
        val states = listOf(
            AvatarPresentationState.IDLE,
            AvatarPresentationState.GUIDING,
            AvatarPresentationState.POINTING,
            AvatarPresentationState.THINKING,
            AvatarPresentationState.WAITING_FOR_USER
        )

        states.forEach { current ->
            val next = AvatarStateMachine.transition(
                currentState = current,
                event = AvatarDomainEvent.BlenderConnectionChanged(BlenderObservationStatus.UNAVAILABLE),
                isBlenderConnected = false
            )
            assertEquals(AvatarPresentationState.OFFLINE, next, "Disconnect from $current must yield OFFLINE")
        }
    }

    @Test
    fun blenderReconnectReturnsFromOfflineToIdle() {
        val next = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.OFFLINE,
            event = AvatarDomainEvent.BlenderConnectionChanged(BlenderObservationStatus.AVAILABLE),
            isBlenderConnected = true
        )
        assertEquals(AvatarPresentationState.IDLE, next)
    }

    @Test
    fun dockCollapseAndExpandTransitions() {
        val collapsed = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.GUIDING,
            event = AvatarDomainEvent.CollapseToDock
        )
        assertEquals(AvatarPresentationState.COMPACT, collapsed)

        val expanded = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.COMPACT,
            event = AvatarDomainEvent.ExpandFromDock,
            isBlenderConnected = true
        )
        assertEquals(AvatarPresentationState.IDLE, expanded)
    }

    @Test
    fun transientTimeoutSettlesStates() {
        assertEquals(
            AvatarPresentationState.IDLE,
            AvatarStateMachine.transition(AvatarPresentationState.STARTING, AvatarDomainEvent.TransientTimeout)
        )
        assertEquals(
            AvatarPresentationState.GUIDING,
            AvatarStateMachine.transition(AvatarPresentationState.THINKING, AvatarDomainEvent.TransientTimeout)
        )
        assertEquals(
            AvatarPresentationState.WAITING_FOR_USER,
            AvatarStateMachine.transition(AvatarPresentationState.VERIFYING, AvatarDomainEvent.TransientTimeout)
        )
        assertEquals(
            AvatarPresentationState.GUIDING,
            AvatarStateMachine.transition(AvatarPresentationState.SUCCESS, AvatarDomainEvent.TransientTimeout)
        )
    }

    @Test
    fun criticalInvariantAvatarTransitionsNeverMutateStepProgress() {
        val initialSession = AvatarSessionContract(
            currentTaskId = "task-42",
            currentPlanId = "plan-99",
            currentStepIndex = 1,
            totalSteps = 5,
            currentInstruction = "Scale the sphere by 2.0",
            presentationState = AvatarPresentationState.GUIDING,
            isBlenderConnected = true
        )

        // Simulating multiple rapid presentation transitions
        var session = initialSession
        val events = listOf(
            AvatarDomainEvent.ExplanationRequested,
            AvatarDomainEvent.UserActionPending,
            AvatarDomainEvent.VerificationStarted,
            AvatarDomainEvent.VerificationFinished(VerificationStatus.SUCCESS)
        )

        events.forEach { event ->
            val nextState = AvatarStateMachine.transition(session.presentationState, event, session.isBlenderConnected)
            session = session.copy(presentationState = nextState)
        }

        // The step index, plan ID, and task ID MUST remain untouched by presentation transitions
        assertEquals(initialSession.currentStepIndex, session.currentStepIndex, "Avatar state changes must not advance step index")
        assertEquals(initialSession.currentTaskId, session.currentTaskId, "Avatar state changes must not alter current task ID")
        assertEquals(initialSession.currentPlanId, session.currentPlanId, "Avatar state changes must not alter plan ID")
        assertEquals(initialSession.totalSteps, session.totalSteps)
        assertEquals(AvatarPresentationState.SUCCESS, session.presentationState)
    }

    @Test
    fun sessionContractPreservesReducedMotionFlag() {
        val contract = AvatarSessionContract(
            isReducedMotion = true,
            presentationState = AvatarPresentationState.GUIDING
        )
        assertTrue(contract.isReducedMotion)
    }
}
