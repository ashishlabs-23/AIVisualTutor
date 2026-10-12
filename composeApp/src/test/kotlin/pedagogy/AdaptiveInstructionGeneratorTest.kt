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
import context.PerceptionSource
import context.UiType
import context.VerificationOperation
import context.VisualContext
import models.HighlightRegion
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdaptiveInstructionGeneratorTest {

    private val generator = DeterministicAdaptiveInstructionGenerator()

    private fun sampleTask(
        id: String = "sketch_line",
        title: String = "Draw Line",
        controlName: String? = "Line",
        area: String? = "Draw toolbar"
    ): LearningTask = LearningTask(
        taskId = id,
        skillId = "sketching",
        title = title,
        objective = "Create a 2D line segment between two vertices",
        targetControlName = controlName,
        targetAreaDescription = area,
        exactInstruction = "Click Line in the Draw toolbar.",
        conceptualHint = "Select the linear segment tool from the drawing section.",
        reflectionQuestion = "Which command in the interface creates straight line segments?",
        expectedState = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Line1"
        ),
        targetRegion = HighlightRegion(120f, 60f, 40f, 40f)
    )

    private fun visualContextWithUia(targetName: String, confidence: Float = 0.95f): VisualContext {
        val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val perception = PerceptionResult(
            applicationName = "CAD_Suite",
            selectedObject = targetName,
            visibleText = targetName,
            uiType = UiType.BUTTON,
            boundingRectangle = Rectangle(120, 60, 40, 40),
            confidence = confidence,
            source = PerceptionSource.UI_AUTOMATION
        )
        return VisualContext(
            regionId = UUID.randomUUID(),
            image = image,
            x = 0, y = 0, width = 400, height = 300,
            timestampMillis = System.currentTimeMillis(),
            extractedText = targetName,
            contentType = context.ContentType.UI_ELEMENT,
            applicationContext = null,
            confidence = confidence,
            metadata = emptyMap(),
            perceptionResult = perception
        )
    }

    private fun visualContextWithOcr(targetName: String, confidence: Float = 0.90f): VisualContext {
        val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        val word = OcrWord(text = targetName, confidence = confidence, bounds = Rectangle(120, 60, 40, 40))
        val ocr = OcrResult(
            text = "Tools $targetName Arc Circle",
            confidence = confidence,
            words = listOf(word),
            engineName = "tesseract",
            coordinateSpace = OcrCoordinateSpace.CROP_IMAGE_PIXELS
        )
        return VisualContext(
            regionId = UUID.randomUUID(),
            image = image,
            x = 0, y = 0, width = 400, height = 300,
            timestampMillis = System.currentTimeMillis(),
            extractedText = ocr.text,
            contentType = context.ContentType.UI_ELEMENT,
            applicationContext = null,
            confidence = confidence,
            metadata = emptyMap(),
            ocrResult = ocr
        )
    }

    // 1. Target control identified reliably by UI Automation
    @Test
    fun testTargetIdentifiedReliablyByUiAutomation() {
        val task = sampleTask()
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            learnerState = LearnerState(),
            visualContext = visualContextWithUia("Line")
        )

        val instruction = generator.generate(request)
        assertEquals(GenerationStatus.GENERATED, instruction.status)
        assertTrue(instruction.instructionText.contains("Line"))
        assertTrue(instruction.instructionText.contains("Draw toolbar"))
        assertTrue(instruction.supportingEvidence.any { it.contains("UI Automation confirmed", ignoreCase = true) })
        assertNotNull(instruction.targetRegion)
    }

    // 2. Target control identified reliably by OCR
    @Test
    fun testTargetIdentifiedReliablyByOcr() {
        val task = sampleTask()
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            learnerState = LearnerState(),
            visualContext = visualContextWithOcr("Line")
        )

        val instruction = generator.generate(request)
        assertEquals(GenerationStatus.GENERATED, instruction.status)
        assertTrue(instruction.instructionText.contains("Line"))
        assertTrue(instruction.supportingEvidence.any { it.contains("OCR confirmed control", ignoreCase = true) })
    }

    // 3. Target is not visible in observed visual context
    @Test
    fun testTargetNotVisibleInVisualContext() {
        val task = sampleTask(controlName = "NonExistentCommand")
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            learnerState = LearnerState(),
            visualContext = visualContextWithOcr("Unrelated Words Extrude Loft")
        )

        val instruction = generator.generate(request)
        assertEquals(GenerationStatus.UNCERTAIN_EVIDENCE, instruction.status)
        assertTrue(instruction.instructionText.contains("verify location", ignoreCase = true))
    }

    // 4. Target has no reliable location description in task
    @Test
    fun testTargetHasNoReliableLocationDescription() {
        val task = sampleTask(area = null)
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            learnerState = LearnerState(),
            visualContext = null
        )

        val instruction = generator.generate(request)
        // Does not invent a false toolbar or coordinates
        assertNull(instruction.targetLocationDescription)
        assertEquals(task.exactInstruction, instruction.instructionText)
    }

    // 5. Application state changes after instruction generation (object already created)
    @Test
    fun testObservedStateIndicatesGoalAlreadySatisfied() {
        val task = sampleTask()
        val observedState = ObservedState(
            objects = listOf(ObjectObservation(name = "Line1", type = "LINE"))
        )
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            learnerState = LearnerState(),
            observedState = observedState
        )

        val instruction = generator.generate(request)
        assertTrue(instruction.supportingEvidence.any { it.contains("already present in scene", ignoreCase = true) })
    }

    // 6. Stage 2 provides useful conceptual hint without revealing clicking details
    @Test
    fun testStage2ConceptualHintWithholdsExactClicks() {
        val task = sampleTask()
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT,
            learnerState = LearnerState()
        )

        val instruction = generator.generate(request)
        assertEquals(GuidanceStage.STAGE_2_CONCEPTUAL_HINT, instruction.stage)
        assertEquals(task.conceptualHint, instruction.instructionText)
        assertNull(instruction.targetControlName) // Withheld in Stage 2
        assertNull(instruction.targetRegion) // Withheld in Stage 2
        assertEquals(task.targetAreaDescription, instruction.targetLocationDescription)
    }

    // 7. Stage 3 produces relevant self-correction question
    @Test
    fun testStage3ProducesSelfCorrectionQuestion() {
        val task = sampleTask()
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION,
            learnerState = LearnerState()
        )

        val instruction = generator.generate(request)
        assertEquals(GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION, instruction.stage)
        assertEquals(task.reflectionQuestion, instruction.instructionText)
        assertNotNull(instruction.questionText)
        assertNull(instruction.targetControlName) // Withheld in Stage 3
        assertNull(instruction.targetRegion) // Withheld in Stage 3
    }

    // 8. System cannot obtain fresh visual evidence (null visualContext)
    @Test
    fun testNullVisualContextOperatesSafely() {
        val task = sampleTask()
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            learnerState = LearnerState(),
            visualContext = null
        )

        val instruction = generator.generate(request)
        assertNotNull(instruction)
        assertEquals(GenerationStatus.GENERATED, instruction.status)
        assertEquals(task.exactInstruction, instruction.instructionText)
    }

    // 9. Fallback instructions do not make unsupported claims
    @Test
    fun testFallbackGenerationForBlankTask() {
        val malformedTask = LearningTask(
            taskId = "t_blank",
            skillId = "s_blank",
            title = "Empty",
            objective = "Test",
            exactInstruction = "Perform action.",
            conceptualHint = "Hint.",
            reflectionQuestion = "Question."
        )
        val request = InstructionGenerationRequest(
            task = malformedTask,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            learnerState = LearnerState()
        )

        val instruction = generator.generate(request)
        assertNotNull(instruction)
        assertFalse(instruction.instructionText.contains("verified", ignoreCase = true))
        assertFalse(instruction.instructionText.contains("success", ignoreCase = true))
    }

    // 10. Generator does not report an action as successful before verification
    @Test
    fun testGeneratorDoesNotPrematurelyDeclareSuccess() {
        val task = sampleTask()
        val request = InstructionGenerationRequest(
            task = task,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            learnerState = LearnerState(competenceEstimate = 0.0)
        )

        val instruction = generator.generate(request)
        assertFalse(instruction.instructionText.contains("successfully completed", ignoreCase = true))
        assertFalse(instruction.instructionText.contains("task verified", ignoreCase = true))
    }
}
