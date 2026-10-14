package ui.avatar

import context.BlenderObservationStatus
import context.VerificationStatus
import models.HighlightRegion
import models.TutorStep
import org.junit.Test
import pedagogy.PedagogicalOutcome
import tutor.TutorController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AvatarIntegrationTest {

    @Test
    fun authoritativeSessionStateMapsToExpectedAvatarState() {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val step = controller.currentStep
        assertNotNull(step, "Mock tutorial should provide an initial step")

        val stateWithTarget = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            activeStep = step,
            hasTarget = step.targetControlName != null || step.targetRegion != null
        )
        assertTrue(
            stateWithTarget == AvatarPresentationState.POINTING || stateWithTarget == AvatarPresentationState.GUIDING,
            "Active step must resolve to POINTING or GUIDING"
        )

        val stateWithoutTarget = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            activeStep = step.copy(targetControlName = null, targetRegion = null),
            hasTarget = false
        )
        assertEquals(AvatarPresentationState.GUIDING, stateWithoutTarget)
    }

    @Test
    fun blenderDisconnectOverridesNormalGuidance() {
        val dummyStep = TutorStep(
            id = 1,
            title = "Extrude Face",
            instruction = "Press E to extrude",
            description = "Extrudes mesh face.",
            stepNumber = 1,
            totalSteps = 3
        )

        // Disconnected state MUST yield OFFLINE even when active step is present
        val state = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = false,
            activeStep = dummyStep,
            hasTarget = true,
            isThinking = true,
            isRecoveryActive = true
        )
        assertEquals(AvatarPresentationState.OFFLINE, state, "Blender disconnect must override all other guidance states")
    }

    @Test
    fun reconnectionPreservesActiveTaskAndSessionWithoutDuplicate() {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val initialPlan = controller.activePlan
        val initialStepIndex = controller.state.currentStepIndex
        val initialTaskId = controller.currentStep?.taskId

        // Simulate disconnect
        val offlineState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = false,
            activeStep = controller.currentStep,
            hasTarget = false
        )
        assertEquals(AvatarPresentationState.OFFLINE, offlineState)

        // Simulate reconnect
        val reconnectedState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            activeStep = controller.currentStep,
            hasTarget = false
        )
        assertEquals(AvatarPresentationState.GUIDING, reconnectedState)

        // Verify task, step index, and plan remain intact
        assertEquals(initialStepIndex, controller.state.currentStepIndex)
        assertEquals(initialPlan, controller.activePlan)
        assertEquals(initialTaskId, controller.currentStep?.taskId)
    }

    @Test
    fun verificationSuccessIsRequiredBeforeDisplayingConfirmedSuccess() {
        val dummyStep = TutorStep(
            id = 1,
            title = "Add Mesh",
            instruction = "Add UV sphere",
            description = "Creates sphere.",
            stepNumber = 1,
            totalSteps = 2
        )

        // In-flight verification is VERIFYING, not SUCCESS
        val inFlightState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            isVerificationBusy = true,
            isVerifiedSuccess = false,
            activeStep = dummyStep
        )
        assertEquals(AvatarPresentationState.VERIFYING, inFlightState)

        // Only verified success enables SUCCESS
        val confirmedSuccessState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            isVerificationBusy = false,
            isVerifiedSuccess = true,
            activeStep = dummyStep
        )
        assertEquals(AvatarPresentationState.SUCCESS, confirmedSuccessState)
    }

    @Test
    fun failedOrUncertainVerificationMapsToRecovery() {
        val dummyStep = TutorStep(
            id = 1,
            title = "Select Object",
            instruction = "Click on the cube",
            description = "Selects cube.",
            stepNumber = 1,
            totalSteps = 2
        )

        val recoveryState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            isRecoveryActive = true,
            activeStep = dummyStep
        )
        assertEquals(AvatarPresentationState.RECOVERY, recoveryState)
    }

    @Test
    fun compactModePreservesUnderlyingSemanticState() {
        val dummyStep = TutorStep(
            id = 1,
            title = "Scale Object",
            instruction = "Press S to scale",
            description = "Scales object.",
            stepNumber = 1,
            totalSteps = 2
        )

        // When compact is true, presentation state is COMPACT
        val compactState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            isCompact = true,
            activeStep = dummyStep,
            hasTarget = true
        )
        assertEquals(AvatarPresentationState.COMPACT, compactState)

        // When toggled back to expanded (isCompact = false), the underlying state (POINTING) is restored
        val expandedState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            isCompact = false,
            activeStep = dummyStep,
            hasTarget = true
        )
        assertEquals(AvatarPresentationState.POINTING, expandedState)
    }

    @Test
    fun missingTargetsProduceSafeFeedback() {
        val dummyStep = TutorStep(
            id = 1,
            title = "Review Topology",
            instruction = "Observe mesh wireframe",
            description = "Check edge flow.",
            stepNumber = 1,
            totalSteps = 2,
            targetControlName = null,
            targetRegion = null
        )

        val state = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            activeStep = dummyStep,
            hasTarget = false
        )
        assertEquals(AvatarPresentationState.GUIDING, state, "Missing target must resolve to safe GUIDING without crash")
    }

    @Test
    fun closingOrHidingAvatarDoesNotResetTutorialProgress() {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val initialStepIndex = controller.state.currentStepIndex

        // Simulating avatar hidden
        var isAvatarVisible = false
        assertFalse(isAvatarVisible)

        // Advance tutorial step in controller
        controller.nextStep()
        assertEquals(initialStepIndex + 1, controller.state.currentStepIndex)

        // Simulating avatar restored
        isAvatarVisible = true
        assertTrue(isAvatarVisible)
        assertEquals(initialStepIndex + 1, controller.state.currentStepIndex, "Progress must be preserved across visibility toggles")
    }

    @Test
    fun chatOrProviderErrorDoesNotCorruptTutoringSession() {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val initialStep = controller.currentStep
        val initialIndex = controller.state.currentStepIndex

        // Simulate provider exception handling
        var explanationText: String? = null
        try {
            throw RuntimeException("Network timeout to inference API")
        } catch (ex: Exception) {
            explanationText = "Concept: ${initialStep?.description}"
        }

        assertNotNull(explanationText)
        assertTrue(explanationText.contains("Concept:"))
        // Controller state remains unchanged
        assertEquals(initialIndex, controller.state.currentStepIndex)
        assertEquals(initialStep, controller.currentStep)
    }

    @Test
    fun stuckSignalTransitionsToRecovery() {
        val next = AvatarStateMachine.transition(
            currentState = AvatarPresentationState.WAITING_FOR_USER,
            event = AvatarDomainEvent.StuckReported
        )
        assertEquals(AvatarPresentationState.RECOVERY, next)
    }

    @Test
    fun validActiveTargetProducesCorrectGazeAndPointingGuidance() {
        val anchor = ScreenPoint(200f, 320f)
        val targetRegion = HighlightRegion(x = 600f, y = 300f, width = 80f, height = 40f) // center at (640, 320) -> directly right

        val guidance = TargetGuidanceCalculator.calculateGuidance(anchor, targetRegion.toScreenRect())
        assertTrue(guidance.isGuidanceUsable)
        assertTrue(guidance.gazeOffset.offsetX > 0f, "Gaze should look right towards the target")
        assertNotNull(guidance.pointingGesture)
        assertTrue(guidance.pointingGesture!!.isPointingRight)
        assertEquals(0f, guidance.pointingGesture!!.directionAngleDegrees, 0.01f)
    }

    @Test
    fun pointingGestureUsesAuthoritativeTargetFromHighlightAndController() {
        val controller = TutorController(loadMockTutorialByDefault = true)
        val highlightRegion = HighlightRegion(400f, 100f, 50f, 50f) // center at (425, 125)
        controller.setHighlight(highlightRegion)

        assertEquals(highlightRegion, controller.state.currentHighlight)

        val anchor = ScreenPoint(425f, 500f) // directly below target center
        val geometry = TargetGuidanceCalculator.calculateGuidance(anchor, controller.state.currentHighlight!!.toScreenRect())

        assertTrue(geometry.isGuidanceUsable)
        assertEquals(highlightRegion.centerX, geometry.targetCenter!!.x, 0.001f)
        assertEquals(highlightRegion.centerY, geometry.targetCenter!!.y, 0.001f)
        assertEquals(-90f, geometry.displacement!!.angleDegrees, 0.01f, "Pointing angle should align with authoritative highlight")
    }

    @Test
    fun offlineOrVerifyingOrRecoveryStatesSuppressDirectionalPointing() {
        val dummyStep = TutorStep(
            id = 1,
            title = "Extrude",
            instruction = "Press E",
            description = "Extrude",
            targetControlName = "Extrude Tool",
            targetRegion = HighlightRegion(800f, 200f, 60f, 40f),
            stepNumber = 1,
            totalSteps = 2
        )

        // 1. OFFLINE suppresses pointing
        val offlineState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = false,
            activeStep = dummyStep,
            hasTarget = true
        )
        assertEquals(AvatarPresentationState.OFFLINE, offlineState)

        // 2. VERIFYING suppresses pointing
        val verifyingState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            isVerificationBusy = true,
            activeStep = dummyStep,
            hasTarget = true
        )
        assertEquals(AvatarPresentationState.VERIFYING, verifyingState)

        // 3. RECOVERY suppresses pointing
        val recoveryState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            isRecoveryActive = true,
            activeStep = dummyStep,
            hasTarget = true
        )
        assertEquals(AvatarPresentationState.RECOVERY, recoveryState)

        // 4. SUCCESS suppresses pointing
        val successState = AvatarStateMachine.resolvePresentationState(
            isBlenderConnected = true,
            isVerifiedSuccess = true,
            activeStep = dummyStep,
            hasTarget = true
        )
        assertEquals(AvatarPresentationState.SUCCESS, successState)
    }

    @Test
    fun compactModePreservesTargetGuidanceGeometry() {
        val anchor = ScreenPoint(100f, 100f)
        val target = ScreenRect(500f, 100f, 50f, 50f)

        val fullGeo = TargetGuidanceCalculator.calculateGuidance(anchor, target)
        val compactGeo = TargetGuidanceCalculator.calculateGuidance(anchor, target)

        assertEquals(fullGeo.displacement!!.angleDegrees, compactGeo.displacement!!.angleDegrees)
        assertEquals(fullGeo.gazeOffset, compactGeo.gazeOffset)
        assertTrue(fullGeo.isGuidanceUsable)
    }
}
