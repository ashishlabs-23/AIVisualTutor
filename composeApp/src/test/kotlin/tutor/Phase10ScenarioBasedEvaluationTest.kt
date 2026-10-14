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
import kotlin.test.assertTrue

/**
 * Phase 10 Section 8: Scenario-Based Evaluation Suite.
 *
 * Systematically simulates multi-attempt learner trajectories to measure and record:
 * 1. Outcome classification accuracy (assert 100%)
 * 2. Incorrect success rate (assert 0.0%)
 * 3. Hint relevance across progressive escalation levels (assert 100%)
 * 4. Attempt counts and progression to resolution
 * 5. Abstention frequency when telemetry is missing or stale (assert 100%)
 */
class Phase10ScenarioBasedEvaluationTest {

    private val engine = AdaptiveTutoringEngine()

    private val moveUpStep = TutorStep(
        id = 10,
        title = "Move Cube Up",
        instruction = "Press G, then Z to constrain to the Z-axis, move mouse upward, and left-click.",
        description = "Translate Cube upward along vertical Z-axis",
        stepNumber = 4,
        totalSteps = 5,
        targetControlName = "3D Viewport",
        expectedState = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "location.z",
            expectedPropertyValue = "> 0.5"
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
        stale: Boolean = false,
        capturedAt: Instant = Instant.now()
    ): ObservedState = ObservedState(
        runId = UUID.randomUUID().toString(),
        capturedAt = capturedAt,
        applicationName = "Blender",
        sceneName = "Scene",
        mode = "OBJECT",
        objects = objects,
        selectedObjects = objects.filter { it.selected == true }.mapNotNull { it.name }.toSet(),
        metadata = mapOf("snapshotVersion" to version.toString(), "stale" to stale.toString()),
        isComplete = true
    )

    data class EvaluationMetrics(
        var totalClassifications: Int = 0,
        var correctClassifications: Int = 0,
        var falsePositives: Int = 0,
        var relevantHints: Int = 0,
        var totalHintsGenerated: Int = 0,
        var abstentionsRequired: Int = 0,
        var abstentionsObserved: Int = 0
    ) {
        val classificationAccuracy: Double get() = if (totalClassifications == 0) 1.0 else correctClassifications.toDouble() / totalClassifications
        val incorrectSuccessRate: Double get() = if (totalClassifications == 0) 0.0 else falsePositives.toDouble() / totalClassifications
        val hintRelevance: Double get() = if (totalHintsGenerated == 0) 1.0 else relevantHints.toDouble() / totalHintsGenerated
        val abstentionFrequency: Double get() = if (abstentionsRequired == 0) 1.0 else abstentionsObserved.toDouble() / abstentionsRequired
    }

    // -------------------------------------------------------------------------
    // SCENARIO 1: Direct Successful Learner Trajectory
    // -------------------------------------------------------------------------
    @Test
    fun testScenario1_DirectSuccessfulLearnerTrajectory() {
        val metrics = EvaluationMetrics()

        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")), version = 1L)
        val post = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 1.8")), version = 2L)

        metrics.totalClassifications++
        val evidence = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = 3,
            baseline = base,
            observed = post,
            verificationStatus = VerificationStatus.SUCCESS,
            failedAttemptsCount = 0
        )

        if (evidence.outcome == LearnerOutcome.SUCCESS) metrics.correctClassifications++
        if (evidence.outcome == LearnerOutcome.SUCCESS && post.objects.find { it.name == "Cube" }?.metadata?.get("location") == "0.0, 0.0, 0.0") {
            metrics.falsePositives++
        }

        assertEquals(LearnerOutcome.SUCCESS, evidence.outcome)
        assertEquals(1, evidence.hintLevel)
        assertEquals(1.0, metrics.classificationAccuracy)
        assertEquals(0.0, metrics.incorrectSuccessRate)
    }

    // -------------------------------------------------------------------------
    // SCENARIO 2: Wrong-Direction Recovery Trajectory
    // User moves downward -> wrong direction detected -> progressive hints -> user corrects -> success
    // -------------------------------------------------------------------------
    @Test
    fun testScenario2_WrongDirectionRecoveryTrajectory() {
        val metrics = EvaluationMetrics()
        val stepIndex = 3
        engine.resetProgressiveHintLevel(stepIndex)

        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")), version = 1L)

        // Attempt 1: Learner moves down to z = -1.2
        val att1 = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, -1.2")), version = 2L)
        metrics.totalClassifications++
        val ev1 = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = stepIndex,
            baseline = base,
            observed = att1,
            verificationStatus = VerificationStatus.FAILURE,
            failedAttemptsCount = 1
        )
        if (ev1.outcome == LearnerOutcome.WRONG_DIRECTION_CONFIRMED) metrics.correctClassifications++
        if (ev1.outcome == LearnerOutcome.SUCCESS) metrics.falsePositives++

        // Check hint relevance for wrong direction
        metrics.totalHintsGenerated++
        if (ev1.activeHint?.contains("downward", ignoreCase = true) == true) metrics.relevantHints++

        // Verify highlight adaptation reverses guidance
        val adapted1 = engine.adaptHighlightForOutcome(moveUpStep.targetRegion, ev1.outcome, ev1)
        assertNotNull(adapted1)
        assertEquals(DirectionalAxis.UP_POSITIVE_Z, adapted1.directionalIndicator?.axis)
        assertTrue(adapted1.directionalIndicator?.label?.contains("Reverse") == true)

        // Attempt 2: Learner requests higher hint (advances to Level 2)
        val hint2 = engine.getEscalatedHintForStep(moveUpStep, stepIndex)
        metrics.totalHintsGenerated++
        if (hint2.contains("G") && hint2.contains("Z")) metrics.relevantHints++
        assertEquals(2, engine.getProgressiveHintLevel(stepIndex))

        // Attempt 3: Learner makes partial positive progress (z = 0.3, but threshold is > 0.5)
        val att3 = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.3")), version = 3L)
        metrics.totalClassifications++
        val ev3 = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = stepIndex,
            baseline = base,
            observed = att3,
            verificationStatus = VerificationStatus.FAILURE,
            failedAttemptsCount = 2
        )
        if (ev3.outcome == LearnerOutcome.PROGRESS_OBSERVED) metrics.correctClassifications++
        if (ev3.outcome == LearnerOutcome.SUCCESS) metrics.falsePositives++

        metrics.totalHintsGenerated++
        if (ev3.activeHint?.contains("progress", ignoreCase = true) == true ||
            ev3.activeHint?.contains("upward", ignoreCase = true) == true) {
            metrics.relevantHints++
        }

        // Attempt 4: Learner completes movement to z = 1.5
        val att4 = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 1.5")), version = 4L)
        metrics.totalClassifications++
        val ev4 = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = stepIndex,
            baseline = base,
            observed = att4,
            verificationStatus = VerificationStatus.SUCCESS,
            failedAttemptsCount = 2
        )
        if (ev4.outcome == LearnerOutcome.SUCCESS) metrics.correctClassifications++
        if (ev4.outcome == LearnerOutcome.SUCCESS && att4.objects.find { it.name == "Cube" }?.metadata?.get("location") == "0.0, 0.0, 0.0") {
            metrics.falsePositives++
        }

        assertEquals(1.0, metrics.classificationAccuracy)
        assertEquals(0.0, metrics.incorrectSuccessRate)
        assertEquals(1.0, metrics.hintRelevance)
    }

    // -------------------------------------------------------------------------
    // SCENARIO 3: Target Mismatch (Wrong Object Manipulated) Trajectory
    // -------------------------------------------------------------------------
    @Test
    fun testScenario3_TargetMismatchLearnerTrajectory() {
        val metrics = EvaluationMetrics()
        val stepIndex = 3
        engine.resetProgressiveHintLevel(stepIndex)

        val base = createObservedState(
            listOf(
                createObjectObservation("Cube", "0.0, 0.0, 0.0", selected = true),
                createObjectObservation("Camera", "0.0, -10.0, 1.0", selected = false, type = "CAMERA")
            ),
            version = 1L
        )

        // Learner selected and moved Camera instead of Cube
        val att1 = createObservedState(
            listOf(
                createObjectObservation("Cube", "0.0, 0.0, 0.0", selected = false),
                createObjectObservation("Camera", "0.0, -10.0, 5.0", selected = true, type = "CAMERA")
            ),
            version = 2L
        )

        metrics.totalClassifications++
        val ev1 = engine.observeLearnerProgress(
            step = moveUpStep,
            stepIndex = stepIndex,
            baseline = base,
            observed = att1,
            verificationStatus = VerificationStatus.FAILURE,
            failedAttemptsCount = 1
        )

        if (ev1.outcome == LearnerOutcome.TARGET_MISMATCH) metrics.correctClassifications++
        if (ev1.outcome == LearnerOutcome.SUCCESS) metrics.falsePositives++

        metrics.totalHintsGenerated++
        if (ev1.activeHint?.contains("Camera", ignoreCase = true) == true &&
            ev1.activeHint?.contains("Cube", ignoreCase = true) == true) {
            metrics.relevantHints++
        }

        // Highlight adaptation should redirect learner back to target Cube
        val adapted = engine.adaptHighlightForOutcome(moveUpStep.targetRegion, ev1.outcome, ev1)
        assertNotNull(adapted)
        assertTrue(adapted.directionalIndicator?.label?.contains("Cube") == true)

        assertEquals(1.0, metrics.classificationAccuracy)
        assertEquals(0.0, metrics.incorrectSuccessRate)
        assertEquals(1.0, metrics.hintRelevance)
    }

    // -------------------------------------------------------------------------
    // SCENARIO 4: Repeated Inaction & Progressive Escalation
    // -------------------------------------------------------------------------
    @Test
    fun testScenario4_ProgressiveEscalationOnRepeatedInaction() {
        val metrics = EvaluationMetrics()
        val stepIndex = 3
        engine.resetProgressiveHintLevel(stepIndex)

        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")), version = 1L)

        // Attempt 1: No change
        val att1 = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")), version = 2L)
        val ev1 = engine.observeLearnerProgress(moveUpStep, stepIndex, base, att1, VerificationStatus.FAILURE, 1)
        assertEquals(LearnerOutcome.NO_PROGRESS, ev1.outcome)
        assertEquals(1, ev1.hintLevel)

        // Attempt 2: Still no change, hint escalated
        val hint2 = engine.getEscalatedHintForStep(moveUpStep, stepIndex)
        assertEquals(2, engine.getProgressiveHintLevel(stepIndex))
        assertTrue(hint2.contains("G") && hint2.contains("Z"))

        // Attempt 3: Still no change, hint escalated
        val hint3 = engine.getEscalatedHintForStep(moveUpStep, stepIndex)
        assertEquals(3, engine.getProgressiveHintLevel(stepIndex))
        assertTrue(hint3.contains("1.") && hint3.contains("2."))

        // Attempt 4: Still no change, maximum recovery hint
        val hint4 = engine.getEscalatedHintForStep(moveUpStep, stepIndex)
        assertEquals(4, engine.getProgressiveHintLevel(stepIndex))
        assertTrue(hint4.contains("Alt + G") || hint4.contains("Transform panel"))

        // Attempt 5: Does not exceed Level 4
        val hint5 = engine.getEscalatedHintForStep(moveUpStep, stepIndex)
        assertEquals(4, engine.getProgressiveHintLevel(stepIndex))
        assertEquals(hint4, hint5)
    }

    // -------------------------------------------------------------------------
    // SCENARIO 5: Abstention Frequency on Telemetry Failure & Stale State
    // -------------------------------------------------------------------------
    @Test
    fun testScenario5_AbstentionFrequencyOnUnavailableAndStaleTelemetry() {
        val metrics = EvaluationMetrics()
        val stepIndex = 3
        engine.resetProgressiveHintLevel(stepIndex)

        val base = createObservedState(listOf(createObjectObservation("Cube", "0.0, 0.0, 0.0")), version = 10L)

        // Case 5A: Telemetry disconnected / observed is null
        metrics.totalClassifications++
        metrics.abstentionsRequired++
        val evNull = engine.observeLearnerProgress(moveUpStep, stepIndex, base, null, VerificationStatus.FAILURE, 1)
        if (evNull.outcome == LearnerOutcome.EVIDENCE_UNAVAILABLE) {
            metrics.correctClassifications++
            metrics.abstentionsObserved++
        }
        if (evNull.outcome == LearnerOutcome.SUCCESS) metrics.falsePositives++

        // Case 5B: Target object missing from observed state
        val missingTargetObs = createObservedState(listOf(createObjectObservation("Camera", "0.0, 0.0, 0.0")), version = 11L)
        metrics.totalClassifications++
        metrics.abstentionsRequired++
        val evMissing = engine.observeLearnerProgress(moveUpStep, stepIndex, base, missingTargetObs, VerificationStatus.FAILURE, 1)
        if (evMissing.outcome == LearnerOutcome.EVIDENCE_UNAVAILABLE) {
            metrics.correctClassifications++
            metrics.abstentionsObserved++
        }
        if (evMissing.outcome == LearnerOutcome.SUCCESS) metrics.falsePositives++

        // Case 5C: Stale timestamp / out-of-order version
        val staleObs = createObservedState(
            listOf(createObjectObservation("Cube", "0.0, 0.0, 1.5")),
            version = 5L // Baseline is version 10L!
        )
        metrics.totalClassifications++
        metrics.abstentionsRequired++
        val evStale = engine.observeLearnerProgress(moveUpStep, stepIndex, base, staleObs, VerificationStatus.FAILURE, 1)
        if (evStale.outcome == LearnerOutcome.OBSERVATION_STALE) {
            metrics.correctClassifications++
            metrics.abstentionsObserved++
        }
        if (evStale.outcome == LearnerOutcome.SUCCESS) metrics.falsePositives++

        // Case 5D: Marked stale explicitly in metadata
        val explicitlyStaleObs = createObservedState(
            listOf(createObjectObservation("Cube", "0.0, 0.0, 1.5")),
            version = 12L,
            stale = true
        )
        metrics.totalClassifications++
        metrics.abstentionsRequired++
        val evExplicitStale = engine.observeLearnerProgress(moveUpStep, stepIndex, base, explicitlyStaleObs, VerificationStatus.FAILURE, 1)
        if (evExplicitStale.outcome == LearnerOutcome.OBSERVATION_STALE) {
            metrics.correctClassifications++
            metrics.abstentionsObserved++
        }
        if (evExplicitStale.outcome == LearnerOutcome.SUCCESS) metrics.falsePositives++

        // Quantitative Assertions
        assertEquals(1.0, metrics.classificationAccuracy, "Outcome classification accuracy must be 100%")
        assertEquals(0.0, metrics.incorrectSuccessRate, "Incorrect success rate (false positives) must be exactly 0.0%")
        assertEquals(1.0, metrics.abstentionFrequency, "Abstention frequency on missing/stale evidence must be 100%")
    }

    // -------------------------------------------------------------------------
    // SCENARIO 6: Comprehensive Benchmark Over Multi-Behavior Population
    // -------------------------------------------------------------------------
    @Test
    fun testScenario6_MultiLearnerPopulationEvaluation() {
        val metrics = EvaluationMetrics()

        data class LearnerSimulation(
            val description: String,
            val basePos: String,
            val observedPos: String,
            val verificationStatus: VerificationStatus,
            val versionDelta: Long,
            val isStale: Boolean,
            val observedObjectName: String,
            val expectedOutcome: LearnerOutcome
        )

        val testCases = listOf(
            LearnerSimulation("Clean correct upward move", "0,0,0", "0,0,2.0", VerificationStatus.SUCCESS, 1, false, "Cube", LearnerOutcome.SUCCESS),
            LearnerSimulation("Direct downward error", "0,0,0", "0,0,-1.5", VerificationStatus.FAILURE, 1, false, "Cube", LearnerOutcome.WRONG_DIRECTION_CONFIRMED),
            LearnerSimulation("Minor partial upward progress", "0,0,0", "0,0,0.3", VerificationStatus.FAILURE, 1, false, "Cube", LearnerOutcome.PROGRESS_OBSERVED),
            LearnerSimulation("Horizontal movement only", "0,0,0", "2.0,1.0,0", VerificationStatus.FAILURE, 1, false, "Cube", LearnerOutcome.EXPECTED_CONDITION_NOT_MET),
            LearnerSimulation("Idling at baseline", "0,0,0", "0,0,0", VerificationStatus.FAILURE, 1, false, "Cube", LearnerOutcome.NO_PROGRESS),
            LearnerSimulation("Wrong object manipulated", "0,0,0", "0,0,0", VerificationStatus.FAILURE, 1, false, "Sphere", LearnerOutcome.TARGET_MISMATCH),
            LearnerSimulation("Stale snapshot version", "0,0,0", "0,0,2.0", VerificationStatus.FAILURE, -5, false, "Cube", LearnerOutcome.OBSERVATION_STALE),
            LearnerSimulation("Explicit stale metadata flag", "0,0,0", "0,0,2.0", VerificationStatus.FAILURE, 1, true, "Cube", LearnerOutcome.OBSERVATION_STALE)
        )

        val base = createObservedState(
            listOf(
                createObjectObservation("Cube", "0.0, 0.0, 0.0", selected = true),
                createObjectObservation("Sphere", "5.0, 0.0, 0.0", selected = false)
            ),
            version = 100L
        )

        for (tc in testCases) {
            val obsObjects = if (tc.observedObjectName == "Sphere") {
                listOf(
                    createObjectObservation("Cube", "0.0, 0.0, 0.0", selected = false),
                    createObjectObservation("Sphere", "5.0, 0.0, 2.0", selected = true)
                )
            } else {
                listOf(
                    createObjectObservation("Cube", tc.observedPos, selected = true),
                    createObjectObservation("Sphere", "5.0, 0.0, 0.0", selected = false)
                )
            }

            val obs = createObservedState(
                obsObjects,
                version = 100L + tc.versionDelta,
                stale = tc.isStale
            )

            metrics.totalClassifications++
            val evidence = engine.observeLearnerProgress(
                step = moveUpStep,
                stepIndex = 1,
                baseline = base,
                observed = obs,
                verificationStatus = tc.verificationStatus,
                failedAttemptsCount = 1
            )

            if (evidence.outcome == tc.expectedOutcome) {
                metrics.correctClassifications++
            }
            if (evidence.outcome == LearnerOutcome.SUCCESS && tc.expectedOutcome != LearnerOutcome.SUCCESS) {
                metrics.falsePositives++
            }
        }

        assertEquals(1.0, metrics.classificationAccuracy, "Classification accuracy across population must be 100%")
        assertEquals(0.0, metrics.incorrectSuccessRate, "Incorrect success rate must be 0.0%")
        assertEquals(testCases.size, metrics.totalClassifications)
        assertEquals(0, metrics.falsePositives)
    }
}
