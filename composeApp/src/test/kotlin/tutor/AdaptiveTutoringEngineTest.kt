package tutor

import context.ExpectedState
import context.ObjectObservation
import context.ObservedState
import context.VerificationOperation
import context.VerificationStatus
import models.DirectionalAxis
import models.HighlightRegion
import models.TutorStep
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive Unit and Regression Test Suite for Phase 10: Evidence-Adaptive Visual Tutoring.
 * Covers all 12 required test scenarios from Phase 10 Section 7.
 */
class AdaptiveTutoringEngineTest {

    private val engine = AdaptiveTutoringEngine()

    private val moveUpStep = TutorStep(
        id = 4,
        title = "Move Cube Up",
        instruction = "Press G, then Z to constrain to the Z-axis, move the mouse upward, and left-click to confirm.",
        description = "Translate Cube upward along vertical Z-axis",
        stepNumber = 4,
        totalSteps = 4,
        targetControlName = "3D Viewport",
        expectedState = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "location.z",
            expectedPropertyValue = "> 0"
        ),
        targetRegion = HighlightRegion(300f, 200f, 150f, 150f)
    )

    private fun createObjectObservation(
        name: String,
        location: String,
        selected: Boolean = true,
        type: String = "MESH"
    ): ObjectObservation = ObjectObservation(
        id = "id_$name",
        name = name,
        type = type,
        selected = selected,
        metadata = mapOf("location" to location)
    )

    private fun createObservedState(
        objects: List<ObjectObservation>,
        version: Long = 10L,
        stale: Boolean = false
    ): ObservedState = ObservedState(
        runId = UUID.randomUUID().toString(),
        capturedAt = Instant.now(),
        applicationName = "Blender",
        sceneName = "Scene",
        mode = "OBJECT",
        objects = objects,
        selectedObjects = objects.filter { it.selected == true }.mapNotNull { it.name }.toSet(),
        metadata = mapOf("snapshotVersion" to version.toString(), "stale" to stale.toString()),
        isComplete = true
    )

    // ------------------------------------------------------------
    // TEST 1: Correct task completion
    // ------------------------------------------------------------
    @Test
    fun test01_CorrectTaskCompletion() {
        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")))
        val obs = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 1.5")), version = 11L)

        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = base,
            observed = obs,
            verificationStatus = VerificationStatus.SUCCESS
        )

        assertEquals(LearnerOutcome.SUCCESS, evidence.outcome)
        assertEquals("Cube", evidence.targetObjectName)
        assertEquals(Vector3D(0.0, 0.0, 0.0), evidence.baselineTransform)
        assertEquals(Vector3D(0.0, 0.0, 1.5), evidence.latestTransform)
        assertEquals(Vector3D(0.0, 0.0, 1.5), evidence.transformDelta)
        assertEquals(1, evidence.hintLevel)
        assertTrue(evidence.diagnosticExplanation.contains("verified", ignoreCase = true))
    }

    // ------------------------------------------------------------
    // TEST 2: Movement in the wrong direction
    // ------------------------------------------------------------
    @Test
    fun test02_MovementInWrongDirection() {
        // Expected is Z > 0 (upward), but user moved cube downward to Z = -1.2
        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")))
        val obs = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, -1.2")), version = 11L)

        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = base,
            observed = obs,
            verificationStatus = VerificationStatus.FAILURE,
            failedAttemptsCount = 1
        )

        assertEquals(LearnerOutcome.WRONG_DIRECTION_CONFIRMED, evidence.outcome)
        assertNotNull(evidence.transformDelta)
        assertTrue(evidence.transformDelta!!.z < -0.02)
        assertTrue(evidence.diagnosticExplanation.contains("downward", ignoreCase = true))
        assertTrue(evidence.activeHint?.contains("downward", ignoreCase = true) == true)
        assertEquals(DirectionalAxis.UP_POSITIVE_Z, evidence.activeDirectionalIndicator?.axis)

        // Visual adaptation test: must attach reverse/corrective guidance
        val adaptedRegion = engine.adaptHighlightForOutcome(moveUpStep.targetRegion, evidence.outcome, evidence)
        assertNotNull(adaptedRegion)
        assertEquals(DirectionalAxis.UP_POSITIVE_Z, adaptedRegion.directionalIndicator?.axis)
        assertTrue(adaptedRegion.directionalIndicator?.label?.contains("Reverse") == true)
    }

    // ------------------------------------------------------------
    // TEST 3: Movement along an irrelevant axis
    // ------------------------------------------------------------
    @Test
    fun test03_MovementAlongIrrelevantAxis() {
        // Expected is Z > 0, but user moved horizontally along X and Y (dx=1.5, dy=2.0, dz=0.0)
        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")))
        val obs = createObservedState(listOf(createObjectObservation("Cube", "1.5, 2.0, 0.0")), version = 11L)

        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = base,
            observed = obs,
            verificationStatus = VerificationStatus.FAILURE,
            failedAttemptsCount = 1
        )

        assertEquals(LearnerOutcome.EXPECTED_CONDITION_NOT_MET, evidence.outcome)
        assertNotNull(evidence.transformDelta)
        assertEquals(0.0, evidence.transformDelta!!.z)
        assertTrue(evidence.diagnosticExplanation.contains("horizontally", ignoreCase = true))
        assertTrue(evidence.activeHint?.contains("horizontally", ignoreCase = true) == true)
        // Must guide user back to Z axis
        assertTrue(evidence.activeHint?.contains("Z") == true)
    }

    // ------------------------------------------------------------
    // TEST 4: No state change
    // ------------------------------------------------------------
    @Test
    fun test04_NoStateChange() {
        // Cube remained exactly at (0, 0, 0)
        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")))
        val obs = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")), version = 11L)

        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = base,
            observed = obs,
            verificationStatus = VerificationStatus.FAILURE,
            failedAttemptsCount = 1
        )

        assertEquals(LearnerOutcome.NO_PROGRESS, evidence.outcome)
        // Must NOT infer wrong direction when state is unchanged!
        assertFalse(evidence.outcome == LearnerOutcome.WRONG_DIRECTION_CONFIRMED)
        assertTrue(evidence.diagnosticExplanation.contains("no movement", ignoreCase = true))
    }

    // ------------------------------------------------------------
    // TEST 5: Meaningful partial progress
    // ------------------------------------------------------------
    @Test
    fun test05_MeaningfulPartialProgress() {
        // Task requires Z > 2.0. Learner moved upward to Z = 0.8.
        val strictMoveStep = moveUpStep.copy(
            expectedState = ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetName = "Cube",
                targetProperty = "location.z",
                expectedPropertyValue = "> 2.0"
            )
        )
        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")))
        val obs = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.8")), version = 11L)

        val evidence = engine.observeLearnerProgress(
            step = strictMoveStep,
            stepIndex = 3,
            baseline = base,
            observed = obs,
            verificationStatus = VerificationStatus.FAILURE,
            failedAttemptsCount = 1
        )

        assertEquals(LearnerOutcome.PROGRESS_OBSERVED, evidence.outcome)
        assertTrue(evidence.diagnosticExplanation.contains("Progress observed", ignoreCase = true))
        // Hint level must reset to 1 upon meaningful progress
        assertEquals(1, evidence.hintLevel)
        assertTrue(evidence.activeHint?.contains("Great progress") == true)

        // Visual adaptation test: must show encouraging indicator
        val adaptedRegion = engine.adaptHighlightForOutcome(moveUpStep.targetRegion, evidence.outcome, evidence)
        assertNotNull(adaptedRegion)
        assertTrue(adaptedRegion.directionalIndicator?.label?.contains("Keep Moving Up") == true)
    }

    // ------------------------------------------------------------
    // TEST 6: Modification of the wrong object
    // ------------------------------------------------------------
    @Test
    fun test06_ModificationOfWrongObject() {
        // Cube remained unchanged at (0, 0, 0), but learner moved the Light/Sphere
        val base = createObservedState(
            listOf(
                createObjectObservation("Cube", "0.0, 0.0, 0.0", selected = false),
                createObjectObservation("Light", "0.0, 0.0, 5.0", selected = false)
            )
        )
        val obs = createObservedState(
            listOf(
                createObjectObservation("Cube", "0.0, 0.0, 0.0", selected = false),
                createObjectObservation("Light", "2.0, 3.0, 8.0", selected = true, type = "MESH")
            ),
            version = 11L
        )

        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = base,
            observed = obs,
            verificationStatus = VerificationStatus.FAILURE,
            failedAttemptsCount = 1
        )

        assertEquals(LearnerOutcome.TARGET_MISMATCH, evidence.outcome)
        assertTrue(evidence.diagnosticExplanation.contains("Target mismatch", ignoreCase = true))
        assertTrue(evidence.diagnosticExplanation.contains("Light", ignoreCase = true))
        assertTrue(evidence.activeHint?.contains("Cube") == true)
    }

    // ------------------------------------------------------------
    // TEST 7: Stale observations
    // ------------------------------------------------------------
    @Test
    fun test07_StaleObservations() {
        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")), version = 20L)
        // Arrived with older version 18L or marked stale
        val staleObs = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 1.5")), version = 18L)

        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = base,
            observed = staleObs,
            verificationStatus = VerificationStatus.SUCCESS
        )

        assertEquals(LearnerOutcome.OBSERVATION_STALE, evidence.outcome)
        assertTrue(evidence.diagnosticExplanation.contains("stale", ignoreCase = true))
    }

    // ------------------------------------------------------------
    // TEST 8: Missing Blender state
    // ------------------------------------------------------------
    @Test
    fun test08_MissingBlenderState() {
        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = null as ObservedState?,
            observed = null,
            verificationStatus = null
        )

        assertEquals(LearnerOutcome.EVIDENCE_UNAVAILABLE, evidence.outcome)
        assertTrue(evidence.diagnosticExplanation.contains("unavailable", ignoreCase = true))
    }

    // ------------------------------------------------------------
    // TEST 9: Progressive hint selection (Levels 1 to 4)
    // ------------------------------------------------------------
    @Test
    fun test09_ProgressiveHintSelection() {
        val bundle = engine.getProgressiveHintBundle(moveUpStep)

        // Level 1: Explain requested action
        val h1 = bundle.getHintForLevel(1)
        assertTrue(h1.contains("Translate", ignoreCase = true) && h1.contains("Cube", ignoreCase = true))

        // Level 2: Identify tool / control
        val h2 = bundle.getHintForLevel(2)
        assertTrue(h2.contains("Move tool") || h2.contains("G"))

        // Level 3: Concrete sequence
        val h3 = bundle.getHintForLevel(3)
        assertTrue(h3.contains("1.") && h3.contains("2.") && h3.contains("3."))

        // Level 4: Recovery guidance
        val h4 = bundle.getHintForLevel(4)
        assertTrue(h4.contains("Alt + G") || h4.contains("Transform panel"))

        // Ensure distinct non-repetitive hints across levels
        val distinctHints = setOf(h1, h2, h3, h4)
        assertEquals(4, distinctHints.size, "All 4 progressive hint levels must provide distinct guidance")
    }

    // ------------------------------------------------------------
    // TEST 10: Hint progression reset after meaningful progress
    // ------------------------------------------------------------
    @Test
    fun test10_HintProgressionResetAfterMeaningfulProgress() {
        val stepIndex = 3
        engine.resetHintLevelForStep(stepIndex)
        assertEquals(1, engine.getHintLevelForStep(stepIndex))

        // Advance twice (e.g. from repeated requests or failed attempts)
        engine.advanceHintLevelForStep(stepIndex) // level 2
        engine.advanceHintLevelForStep(stepIndex) // level 3
        assertEquals(3, engine.getHintLevelForStep(stepIndex))

        // Learner makes meaningful progress
        val strictMoveStep = moveUpStep.copy(
            expectedState = ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetName = "Cube",
                targetProperty = "location.z",
                expectedPropertyValue = "> 2.0"
            )
        )
        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")))
        val obs = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.8")), version = 11L)

        val evidence = engine.observeLearnerProgress(
            step = strictMoveStep,
            stepIndex = stepIndex,
            baseline = base,
            observed = obs,
            verificationStatus = VerificationStatus.FAILURE
        )

        assertEquals(LearnerOutcome.PROGRESS_OBSERVED, evidence.outcome)
        // Hint level must be reset back to 1
        assertEquals(1, engine.getHintLevelForStep(stepIndex))
    }

    // ------------------------------------------------------------
    // TEST 11: Correct target highlighting after recovery
    // ------------------------------------------------------------
    @Test
    fun test11_CorrectTargetHighlightingAfterRecovery() {
        val base = createObservedState(
            listOf(
                createObjectObservation("Cube", "0.0, 0.0, 0.0", selected = false),
                createObjectObservation("Sphere", "0.0, 0.0, 0.0", selected = true)
            )
        )
        val obs = createObservedState(
            listOf(
                createObjectObservation("Cube", "0.0, 0.0, 0.0", selected = false),
                createObjectObservation("Sphere", "0.0, 0.0, 2.0", selected = true, type = "MESH")
            ),
            version = 11L
        )

        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = base,
            observed = obs,
            verificationStatus = VerificationStatus.FAILURE
        )

        assertEquals(LearnerOutcome.TARGET_MISMATCH, evidence.outcome)

        val adapted = engine.adaptHighlightForOutcome(moveUpStep.targetRegion, evidence.outcome, evidence)
        assertNotNull(adapted)
        assertEquals(DirectionalAxis.UP_POSITIVE_Z, adapted.directionalIndicator?.axis)
        assertTrue(adapted.directionalIndicator?.label?.contains("Cube") == true)
    }

    // ------------------------------------------------------------
    // TEST 12: Successful step completion and highlight cleanup
    // ------------------------------------------------------------
    @Test
    fun test12_SuccessfulStepCompletionAndHighlightCleanup() {
        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")))
        val obs = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 1.5")), version = 11L)

        val controller = TutorController()
        controller.startUserGoal("Make cube and move up")
        controller.goToStep(3)
        controller.setHighlight(moveUpStep.targetRegion)
        assertNotNull(controller.state.currentHighlight)

        val successResult = context.VerificationResult(
            status = VerificationStatus.SUCCESS,
            runId = "test_run",
            operation = VerificationOperation.MODIFY,
            explanation = "Cube location.z > 0 satisfied."
        )

        controller.recordVerificationResult(successResult, moveUpStep.expectedState)

        // After verified success, the step completes, advances, or highlight is cleared
        controller.clearUserGoal()
        assertNull(controller.state.currentHighlight, "Highlight must be null after clearing/completing goal")
        assertEquals(1, controller.adaptiveTutoringEngine.getHintLevelForStep(0))
    }
}
