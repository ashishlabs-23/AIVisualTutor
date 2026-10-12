package pedagogy

import context.EvidenceObservation
import context.EvidenceSource
import context.ExpectedState
import context.ObjectObservation
import context.ObservedState
import context.OcrCoordinateSpace
import context.OcrResult
import context.OcrWord
import context.PerceptionResult
import context.UiType
import context.PerceptionSource
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import context.VisualContext
import models.HighlightRegion
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AdaptivePedagogicalEngineTest {

    private fun sampleTask(
        id: String = "cad_sketch",
        skillId: String = "sketching",
        title: String = "Create Sketch",
        controlName: String? = "Create Sketch",
        area: String? = "top toolbar",
        supportsStage3: Boolean = true
    ): LearningTask = LearningTask(
        taskId = id,
        skillId = skillId,
        title = title,
        objective = "Initialize a 2D sketch plane in the workspace",
        targetControlName = controlName,
        targetAreaDescription = area,
        exactInstruction = "Click Create Sketch in the top toolbar.",
        conceptualHint = "Start a new sketch from the toolbar.",
        reflectionQuestion = "Look at the upper toolbar. Which command would let you begin drawing?",
        expectedState = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sketch1"
        ),
        targetRegion = HighlightRegion(100f, 50f, 80f, 30f),
        supportsStage3 = supportsStage3
    )

    private fun fakeVisualContext(
        targetText: String = "Create Sketch",
        confidence: Float = 0.95f
    ): VisualContext {
        val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val word = OcrWord(
            text = targetText,
            confidence = confidence,
            bounds = Rectangle(100, 50, 80, 30)
        )
        val ocr = OcrResult(
            text = "Create Sketch Extrude Revolve",
            confidence = confidence,
            words = listOf(word),
            engineName = "test-ocr",
            coordinateSpace = OcrCoordinateSpace.CROP_IMAGE_PIXELS
        )
        val perception = PerceptionResult(
            applicationName = "CAD_App",
            selectedObject = targetText,
            visibleText = targetText,
            uiType = UiType.BUTTON,
            boundingRectangle = Rectangle(100, 50, 80, 30),
            confidence = confidence,
            source = PerceptionSource.UI_AUTOMATION
        )
        return VisualContext(
            regionId = UUID.randomUUID(),
            image = image,
            x = 0,
            y = 0,
            width = 400,
            height = 300,
            timestampMillis = System.currentTimeMillis(),
            extractedText = ocr.text,
            contentType = context.ContentType.UI_ELEMENT,
            applicationContext = null,
            confidence = confidence,
            metadata = emptyMap(),
            ocrResult = ocr,
            perceptionResult = perception
        )
    }

    private fun fakeVerificationResult(status: VerificationStatus, explanation: String = ""): VerificationResult =
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
     * Test A — Beginner Guidance
     * Given: A new task, no verified history, reliable evidence identifying the target.
     * Expected: Stage 1 is selected; instruction identifies the supported target; no unsupported location or action invented.
     */
    @Test
    fun testA_beginnerGuidance() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()
        val visualContext = fakeVisualContext("Create Sketch")

        val instruction = engine.getInstruction(task, visualContext)

        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, instruction.stage)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, engine.state.currentGuidanceStage)
        assertTrue(instruction.instructionText.contains("Create Sketch"))
        assertTrue(instruction.instructionText.contains("top toolbar"))
        assertEquals("Create Sketch", instruction.targetControlName)
        assertEquals("top toolbar", instruction.targetLocationDescription)
        assertEquals(HighlightRegion(100f, 50f, 80f, 30f), instruction.targetRegion)
        assertEquals(0, engine.state.consecutiveSuccesses)
        assertEquals(0.0, engine.state.competenceEstimate)
    }

    /**
     * Test B — Successful Fading
     * Given: Two consecutive verified successes on comparable steps; no intervening failure.
     * Expected: The policy progresses from Stage 1 to Stage 2 according to configured threshold.
     */
    @Test
    fun testB_successfulFading() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // Attempt 1: Verified success
        val event1 = engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, event1.newStage)
        assertEquals(1, engine.state.consecutiveSuccesses)
        assertFalse(event1.stageChanged)

        // Attempt 2: Second consecutive verified success
        val event2 = engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, event2.newStage)
        assertEquals(2, engine.state.consecutiveSuccesses)
        assertTrue(event2.stageChanged)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, engine.state.currentGuidanceStage)
        assertEquals("Start a new sketch from the toolbar.", event2.instruction.instructionText)
        assertTrue(engine.state.competenceEstimate > 0.0)
    }

    /**
     * Test C — Further Fading
     * Given: Sufficient additional verified successes; task suitable for independent problem-solving.
     * Expected: Stage 3 becomes eligible according to configured policy.
     */
    @Test
    fun testC_furtherFading() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask(supportsStage3 = true)

        // Reach Stage 2 (2 successes)
        engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, engine.state.currentGuidanceStage)

        // Further verified success at Stage 2 -> Fades to Stage 3
        val event3 = engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, event3.newStage)
        assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, engine.state.currentGuidanceStage)
        assertTrue(event3.instruction.instructionText.contains("Which command would let you begin drawing?"))
    }

    /**
     * Test D — Failure Recovery
     * Given: The learner is at Stage 3; two consecutive verified failures occur.
     * Expected: The system increases support according to defined recovery rules (Stage 3 -> Stage 2).
     */
    @Test
    fun testD_failureRecovery() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // Advance to Stage 3
        repeat(4) {
            engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        }
        assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, engine.state.currentGuidanceStage)

        // Failure 1: Remains at Stage 3 while evaluating instruction adequacy
        val fail1 = engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.FAILURE, "Target not created"))
        assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, fail1.newStage)
        assertEquals(1, engine.state.consecutiveFailures)
        assertEquals(0, engine.state.consecutiveSuccesses)

        // Failure 2: Drops support by 1 level to Stage 2
        val fail2 = engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.FAILURE, "Target not created"))
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, fail2.newStage)
        assertEquals(2, engine.state.consecutiveFailures)
        assertTrue(fail2.stageChanged)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, engine.state.currentGuidanceStage)
    }

    /**
     * Test E — Persistent Difficulty
     * Given: Three consecutive verified failures.
     * Expected: Stage 1 guidance is selected; the engine considers whether task, target, or instruction requires reassessment.
     */
    @Test
    fun testE_persistentDifficulty() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // Advance to Stage 2
        repeat(2) {
            engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        }
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, engine.state.currentGuidanceStage)

        // 3 consecutive failures
        engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.FAILURE, "Fail 1"))
        engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.FAILURE, "Fail 2"))
        val fail3 = engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.FAILURE, "Fail 3"))

        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, fail3.newStage)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, engine.state.currentGuidanceStage)
        assertTrue(engine.state.requiresReassessment)
        assertTrue(fail3.rationale.contains("reassessment recommended", ignoreCase = true))
    }

    /**
     * Test F — Ambiguous Verification
     * Given: Phase 6 returns an uncertain outcome.
     * Expected: No verified success recorded; no verified failure recorded; competence is not incorrectly increased or decreased.
     */
    @Test
    fun testF_ambiguousVerification() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // 1 success initially
        engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        val initialSuccesses = engine.state.consecutiveSuccesses
        val initialFailures = engine.state.consecutiveFailures
        val initialCompetence = engine.state.competenceEstimate

        // Uncertain verification
        val uncertainEvent = engine.onVerificationResult(
            task,
            fakeVerificationResult(VerificationStatus.UNCERTAIN, "Baseline and observed sessions differed")
        )

        assertEquals(PedagogicalOutcome.UNCERTAIN, uncertainEvent.outcome)
        assertEquals(initialSuccesses, engine.state.consecutiveSuccesses)
        assertEquals(initialFailures, engine.state.consecutiveFailures)
        assertEquals(initialCompetence, engine.state.competenceEstimate)
        assertEquals(1, engine.state.totalUncertainOutcomes)
        assertFalse(uncertainEvent.stageChanged)
    }

    /**
     * Test G — Unrelated Tasks
     * Given: The learner succeeds repeatedly at one task; a substantially different task begins.
     * Expected: The previous success streak does not automatically cause inappropriate fading.
     */
    @Test
    fun testG_unrelatedTasks() {
        val engine = AdaptivePedagogicalEngine()
        val taskA = sampleTask(id = "cad_sketch", skillId = "sketching")
        val taskB = sampleTask(id = "cad_render", skillId = "rendering", title = "Setup Lighting")

        // Learner demonstrates competence in Task A
        repeat(4) {
            engine.onVerificationResult(taskA, fakeVerificationResult(VerificationStatus.SUCCESS))
        }
        assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, engine.state.currentGuidanceStage)

        // Switch to Task B with completely different skillId
        val instructionB = engine.onTaskSwitched(taskB)

        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, instructionB.stage)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, engine.state.currentGuidanceStage)
        assertEquals(0, engine.state.consecutiveSuccesses)
        assertEquals(0.0, engine.state.competenceEstimate)
    }

    /**
     * Test H — Contradictory Visual Evidence
     * Given: Conflicting observations about the target control.
     * Expected: The engine avoids unsupported exact guidance; appropriate uncertainty or recovery strategy selected.
     */
    @Test
    fun testH_contradictoryVisualEvidence() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask(controlName = "NonExistentCommand", area = "special toolbar")

        // Visual context has OCR, but the control does not appear in it
        val visualContext = fakeVisualContext("Unrelated Text Only", confidence = 0.9f)
        val instruction = engine.getInstruction(task, visualContext)

        assertEquals(GenerationStatus.UNCERTAIN_EVIDENCE, instruction.status)
        assertTrue(instruction.instructionText.contains("verify location", ignoreCase = true))
    }

    /**
     * Test I — LLM / Generator Fallback
     * Given: The instruction-generation input is edge-case or blank.
     * Expected: The application remains operational with safe deterministic instruction; no unsupported completion claims.
     */
    @Test
    fun testI_generatorFallback() {
        val engine = AdaptivePedagogicalEngine()
        val emptyTask = LearningTask(
            taskId = "blank_task",
            skillId = "blank_skill",
            title = "Empty Step",
            objective = "Test fallback",
            exactInstruction = "Default action.",
            conceptualHint = "Default hint.",
            reflectionQuestion = "Default question."
        )

        val instruction = engine.getInstruction(emptyTask)
        assertNotNull(instruction)
        assertFalse(instruction.instructionText.isBlank())
        assertFalse(instruction.instructionText.contains("success", ignoreCase = true))
    }

    /**
     * Test J — State Changes After Instruction
     * Given: An instruction was generated, and observed state indicates the goal was already met.
     * Expected: System notes the presence of the object in supporting evidence.
     */
    @Test
    fun testJ_stateChangesAfterInstruction() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()
        val observedState = ObservedState(
            runId = UUID.randomUUID().toString(),
            objects = listOf(
                ObjectObservation(name = "Sketch1", type = "SKETCH")
            )
        )

        val instruction = engine.getInstruction(task, observedState = observedState)
        assertTrue(instruction.supportingEvidence.any { it.contains("already present", ignoreCase = true) })
    }

    /**
     * Test Deduplication: Verifies that duplicate verification runs with the same runId
     * are ignored idempotently and do not double-increment successes or streaks.
     */
    @Test
    fun testDuplicateVerificationRunIdIgnored() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()
        val fixedRunId = "run_fixed_uuid_1234"
        val result = VerificationResult(
            status = VerificationStatus.SUCCESS,
            runId = fixedRunId,
            operation = VerificationOperation.CREATE,
            explanation = "First execution"
        )

        // First execution -> success recorded (1/2)
        val event1 = engine.onVerificationResult(task, result)
        assertEquals(1, engine.state.consecutiveSuccesses)
        assertEquals(1, engine.state.totalVerifiedSuccesses)
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, event1.newStage)

        // Duplicate execution with exact same runId
        val event2 = engine.onVerificationResult(task, result)
        assertEquals(1, engine.state.consecutiveSuccesses) // NOT incremented to 2!
        assertEquals(1, engine.state.totalVerifiedSuccesses) // NOT incremented to 2!
        assertFalse(event2.stageChanged)
        assertTrue(event2.rationale.contains("Duplicate verification run", ignoreCase = true))
    }

    /**
     * Test Gradual Recovery: Verifies that after dropping back to Stage 1 from persistent failure,
     * the learner restores reduced guidance gradually (requires 2 consecutive successes) rather
     * than immediately jumping back to Stage 2 or 3.
     */
    @Test
    fun testGradualRecoveryAfterFailure() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask()

        // 1. Advance to Stage 2
        engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, engine.state.currentGuidanceStage)

        // 2. Persistent failure drops to Stage 1
        repeat(3) {
            engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.FAILURE))
        }
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, engine.state.currentGuidanceStage)
        assertEquals(3, engine.state.consecutiveFailures)

        // 3. First recovery success: resets failure streak, but remains at Stage 1 (1/2 successes)
        val rec1 = engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        assertEquals(GuidanceStage.STAGE_1_EXACT_GUIDANCE, rec1.newStage)
        assertEquals(1, engine.state.consecutiveSuccesses)
        assertEquals(0, engine.state.consecutiveFailures)

        // 4. Second recovery success: restores Stage 2 gradually
        val rec2 = engine.onVerificationResult(task, fakeVerificationResult(VerificationStatus.SUCCESS))
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, rec2.newStage)
        assertEquals(2, engine.state.consecutiveSuccesses)
    }

    /**
     * Test TutorController Task Routing: Verifies that TutorController properly resolves
     * matching steps for verification results and does not misattribute to unrelated steps.
     */
    @Test
    fun testTaskScopingForBlenderOperationInTutorController() {
        val controller = tutor.TutorController()
        // Controller starts on Step 1: "Add Menu" (id=1, no expectedState)
        assertEquals(1, controller.currentStep.id)

        // Verify CREATE Cube in Blender panel
        val expected = context.ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Cube",
            targetType = "MESH"
        )
        val result = VerificationResult(
            status = VerificationStatus.SUCCESS,
            runId = "blender_test_cube_run",
            operation = VerificationOperation.CREATE,
            explanation = "Created Cube in Blender"
        )

        controller.recordVerificationResult(result, expected)

        // The profile for Step 3 ("blender_cube") should be updated with success
        val cubeProfile = controller.pedagogicalEngine.state.taskProfiles["blender_cube"]
        assertNotNull(cubeProfile)
        assertEquals(1, cubeProfile.totalSuccesses)
    }

    @Test
    fun `evaluateConceptualAnswer handles correct response`() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask(controlName = "Extrude Face")

        val event = engine.evaluateConceptualAnswer(task, "Extrude Face")

        assertEquals(PedagogicalOutcome.VERIFIED_SUCCESS, event.outcome)
        assertTrue(event.rationale.contains("Correct", ignoreCase = true))
    }

    @Test
    fun `evaluateConceptualAnswer steps down to hint when learner is stuck`() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask(controlName = "Extrude Face")

        val event = engine.evaluateConceptualAnswer(task, "I'm stuck")

        assertEquals(PedagogicalOutcome.VERIFIED_FAILURE, event.outcome)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, event.newStage)
        assertTrue(event.stageChanged)
    }

    @Test
    fun `evaluateConceptualAnswer steps down to hint on wrong answer`() {
        val engine = AdaptivePedagogicalEngine()
        val task = sampleTask(controlName = "Extrude Face")

        val event = engine.evaluateConceptualAnswer(task, "Delete Cube")

        assertEquals(PedagogicalOutcome.VERIFIED_FAILURE, event.outcome)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, event.newStage)
        assertTrue(event.rationale.contains("Not quite", ignoreCase = true))
    }
}
