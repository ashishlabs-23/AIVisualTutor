package pedagogy

import context.EvidenceObservation
import context.EvidenceSource
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PedagogicalTrajectoriesEvaluationTest {

    private fun createTask(id: String, skillId: String, title: String): LearningTask =
        LearningTask(
            taskId = id,
            skillId = skillId,
            title = title,
            objective = "Demonstrate competence on $title",
            targetControlName = title,
            targetAreaDescription = "main toolbar",
            exactInstruction = "Click $title in the toolbar.",
            conceptualHint = "Use the $title tool to proceed.",
            reflectionQuestion = "Which command in the interface accomplishes $title?"
        )

    private fun verificationResult(status: VerificationStatus, explanation: String): VerificationResult =
        VerificationResult(
            status = status,
            runId = UUID.randomUUID().toString(),
            operation = VerificationOperation.CREATE,
            explanation = explanation,
            evidence = listOf(
                EvidenceObservation(
                    source = EvidenceSource.BLENDER,
                    capturedAt = Instant.now(),
                    available = true
                )
            )
        )

    /**
     * Trajectory 1 — Competent Learner
     * New task -> Stage 1 -> Verified success -> Verified success -> Stage 2 -> Further verified success -> Stage 3
     */
    @Test
    fun evaluateTrajectory1_competentLearner() {
        val engine = AdaptivePedagogicalEngine()
        val task = createTask("task_create_sketch", "cad.modeling", "Create Sketch")
        val trajectoryTrace = mutableListOf<String>()

        fun logStep(action: String, stage: GuidanceStage, reason: String) {
            trajectoryTrace += "Action: $action | Stage: ${stage.displayName} | Reason: $reason"
        }

        // 1. Initial presentation
        val initial = engine.getInstruction(task)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, initial.stage)
        logStep("Initial Presentation", initial.stage, engine.state.adaptationReason)

        // 2. First verified success
        val event1 = engine.onVerificationResult(task, verificationResult(VerificationStatus.SUCCESS, "Sketch plane created successfully."))
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, event1.newStage)
        logStep("Attempt 1 Success", event1.newStage, event1.rationale)

        // 3. Second verified success -> Fades to Stage 2
        val event2 = engine.onVerificationResult(task, verificationResult(VerificationStatus.SUCCESS, "Sketch plane verified."))
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, event2.newStage)
        assertTrue(event2.stageChanged)
        logStep("Attempt 2 Success", event2.newStage, event2.rationale)

        // 4. Further verified success at Stage 2 -> Fades to Stage 3
        val event3 = engine.onVerificationResult(task, verificationResult(VerificationStatus.SUCCESS, "Dimension entered."))
        assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, event3.newStage)
        assertTrue(event3.stageChanged)
        logStep("Further Success (Stage 3)", event3.newStage, event3.rationale)

        println("=== REPRODUCIBLE TRAJECTORY 1: COMPETENT LEARNER ===")
        trajectoryTrace.forEach { println("  $it") }
        assertEquals(4, trajectoryTrace.size)
    }

    /**
     * Trajectory 2 — Struggling Learner
     * New task -> Stage 1 -> Verified success -> Stage 2 -> Verified failure -> Verified failure -> Increased support
     */
    @Test
    fun evaluateTrajectory2_strugglingLearner() {
        val engine = AdaptivePedagogicalEngine()
        val task = createTask("task_extrude", "cad.features", "Extrude Shape")
        val trajectoryTrace = mutableListOf<String>()

        fun logStep(action: String, stage: GuidanceStage, reason: String) {
            trajectoryTrace += "Action: $action | Stage: ${stage.displayName} | Reason: $reason"
        }

        // 1. Initial -> Stage 1
        val initial = engine.getInstruction(task)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, initial.stage)
        logStep("Initial Presentation", initial.stage, engine.state.adaptationReason)

        // 2. Success 1 & 2 -> Advances to Stage 2
        engine.onVerificationResult(task, verificationResult(VerificationStatus.SUCCESS, "Extrude preview established."))
        val event2 = engine.onVerificationResult(task, verificationResult(VerificationStatus.SUCCESS, "Extrude confirmed."))
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, event2.newStage)
        logStep("Success Streaks (2)", event2.newStage, event2.rationale)

        // 3. First verified failure: Retains Stage 2 while assessing adequacy
        val fail1 = engine.onVerificationResult(task, verificationResult(VerificationStatus.FAILURE, "Extrude height was zero."))
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, fail1.newStage)
        assertFalse(fail1.stageChanged)
        logStep("First Failure", fail1.newStage, fail1.rationale)

        // 4. Second consecutive failure: Drops back to Stage 1 (Increased support)
        val fail2 = engine.onVerificationResult(task, verificationResult(VerificationStatus.FAILURE, "Invalid profile selected."))
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, fail2.newStage)
        assertTrue(fail2.stageChanged)
        logStep("Second Failure (Scaffolding Recovery)", fail2.newStage, fail2.rationale)

        println("=== REPRODUCIBLE TRAJECTORY 2: STRUGGLING LEARNER ===")
        trajectoryTrace.forEach { println("  $it") }
        assertEquals(4, trajectoryTrace.size)
    }

    /**
     * Trajectory 3 — Ambiguous Environment
     * New task -> Stage 1 -> Uncertain verification -> No false competence update -> Re-observation
     */
    @Test
    fun evaluateTrajectory3_ambiguousEnvironment() {
        val engine = AdaptivePedagogicalEngine()
        val task = createTask("task_chamfer", "cad.features", "Apply Chamfer")
        val trajectoryTrace = mutableListOf<String>()

        fun logStep(action: String, stage: GuidanceStage, reason: String) {
            trajectoryTrace += "Action: $action | Stage: ${stage.displayName} | Reason: $reason"
        }

        val initial = engine.getInstruction(task)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, initial.stage)
        logStep("Initial Presentation", initial.stage, engine.state.adaptationReason)

        val initialCompetence = engine.state.competenceEstimate
        val initialSuccesses = engine.state.consecutiveSuccesses
        val initialFailures = engine.state.consecutiveFailures

        // Verification returns UNCERTAIN
        val uncertain = engine.onVerificationResult(
            task,
            verificationResult(VerificationStatus.UNCERTAIN, "Ambiguous state: UIA timeout during property query.")
        )

        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, uncertain.newStage)
        assertEquals(initialCompetence, engine.state.competenceEstimate)
        assertEquals(initialSuccesses, engine.state.consecutiveSuccesses)
        assertEquals(initialFailures, engine.state.consecutiveFailures)
        assertFalse(uncertain.stageChanged)
        logStep("Uncertain Verification", uncertain.newStage, uncertain.rationale)

        println("=== REPRODUCIBLE TRAJECTORY 3: AMBIGUOUS ENVIRONMENT ===")
        trajectoryTrace.forEach { println("  $it") }
        assertEquals(2, trajectoryTrace.size)
    }

    /**
     * Trajectory 4 — Context Switch
     * Task A -> Repeated verified successes -> Reduced guidance (Stage 3)
     * Task B -> Different skill or operation -> Reassess appropriate guidance (Stage 1)
     */
    @Test
    fun evaluateTrajectory4_contextSwitch() {
        val engine = AdaptivePedagogicalEngine()
        val taskA = createTask("task_sketch_lines", "cad.sketching", "Draw Line")
        val taskB = createTask("task_render_bvh", "cad.raytracing", "Build BVH")
        val trajectoryTrace = mutableListOf<String>()

        fun logStep(action: String, stage: GuidanceStage, reason: String) {
            trajectoryTrace += "Action: $action | Stage: ${stage.displayName} | Reason: $reason"
        }

        // Master Task A
        repeat(4) {
            engine.onVerificationResult(taskA, verificationResult(VerificationStatus.SUCCESS, "Line segment added."))
        }
        assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, engine.state.currentGuidanceStage)
        logStep("Task A Mastered", engine.state.currentGuidanceStage, engine.state.adaptationReason)

        // Switch to Task B (different domain: cad.raytracing)
        val instructionB = engine.onTaskSwitched(taskB)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, instructionB.stage)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, engine.state.currentGuidanceStage)
        assertEquals(0, engine.state.consecutiveSuccesses)
        assertEquals(0.0, engine.state.competenceEstimate)
        logStep("Switch to Unrelated Task B", instructionB.stage, engine.state.adaptationReason)

        println("=== REPRODUCIBLE TRAJECTORY 4: CONTEXT SWITCH ===")
        trajectoryTrace.forEach { println("  $it") }
        assertEquals(2, trajectoryTrace.size)
    }
}
