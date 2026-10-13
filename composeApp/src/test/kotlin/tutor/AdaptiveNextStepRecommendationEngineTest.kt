package tutor

import bridge.BlenderCaptureResult
import bridge.BlenderScreenCaptureService
import bridge.BlenderWindowInfo
import context.ExpectedState
import context.ObjectObservation
import context.ObservedState
import context.VerificationOperation
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import pedagogy.GuidanceStage
import pedagogy.LearnerState

class AdaptiveNextStepRecommendationEngineTest {

    private val engine = AdaptiveNextStepRecommendationEngine()

    private fun createSampleContext(
        goal: String = "Create a UV sphere and move it upward along Z",
        stepTitle: String = "Ensure Object Mode",
        stepInstruction: String = "Switch to Object Mode in 3D Viewport",
        expectedOutcome: String? = "OBJECT_MODE on 3D Viewport"
    ): ScreenshotHelpContext {
        return ScreenshotHelpContext(
            requestId = "req-rec-test",
            sessionId = "session-rec-test",
            userGoal = goal,
            planId = "plan_rec_test",
            stepId = 1,
            stepIndex = 0,
            stepTitle = stepTitle,
            stepInstruction = stepInstruction,
            expectedOutcome = expectedOutcome,
            capturedAt = Instant.now(),
            imagePath = "dummy.png",
            imageWidth = 400,
            imageHeight = 300,
            blenderProcessId = 1001L,
            blenderWindowBounds = Rectangle(0, 0, 400, 300)
        )
    }

    // =========================================================================
    // 1. BASIC GENERATION & CASE A: STRONG AGREEMENT
    // =========================================================================

    @Test
    fun `strong agreement between telemetry and vision produces CONTINUE recommendation`() {
        val context = createSampleContext(
            stepTitle = "Ensure Object Mode",
            stepInstruction = "Confirm workspace is in Object Mode",
            expectedOutcome = "Object Mode active"
        )
        val visual = ScreenshotAnalysisResult(
            summary = "Blender 3D Viewport is in Object Mode.",
            observations = listOf(VisualObservation("Header shows Object Mode", "Mode selector", 0.95)),
            relevantUiElements = listOf(VisibleUiElement("Mode Selector", true, "Object Mode")),
            confidence = 0.95
        )
        val tele = ObservedState(
            mode = "OBJECT",
            objects = listOf(ObjectObservation(name = "Cube", selected = true))
        )
        val expected = ExpectedState(operation = VerificationOperation.MODIFY, targetMode = "OBJECT")

        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = visual,
            observedState = tele,
            expectedState = expected
        )

        assertEquals(RecommendationType.CONTINUE, rec.recommendationType)
        assertTrue(rec.title.contains("Proceed with Step 1"))
        assertFalse(rec.actionSteps.isEmpty())
        assertTrue(rec.confidence != null && rec.confidence!! >= 0.8)
        assertFalse(rec.basedSolelyOnVisualEvidence)
        assertEquals(null, rec.conflictWarning)
    }

    // =========================================================================
    // 2. CASE B: TELEMETRY CONTRADICTS VISUAL INFERENCE
    // =========================================================================

    @Test
    fun `telemetry contradicting visual inference emits conflict warning and trusts telemetry`() {
        val context = createSampleContext(
            stepTitle = "Ensure Object Mode",
            stepInstruction = "Verify Object Mode",
            expectedOutcome = "Object Mode"
        )
        // Visual erroneously guessed Edit Mode
        val visual = ScreenshotAnalysisResult(
            summary = "Workspace appears to be in Edit Mode with vertices.",
            observations = listOf(VisualObservation("Edit Mode mesh vertices visible", "Viewport", 0.7)),
            confidence = 0.7
        )
        // Authoritative telemetry confirms Object Mode
        val tele = ObservedState(
            mode = "OBJECT",
            objects = listOf(ObjectObservation(name = "Cube", selected = true))
        )
        val expected = ExpectedState(operation = VerificationOperation.MODIFY, targetMode = "OBJECT")

        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = visual,
            observedState = tele,
            expectedState = expected
        )

        assertNotNull(rec.conflictWarning)
        assertTrue(rec.conflictWarning!!.contains("telemetry confirms OBJECT Mode", ignoreCase = true))
        assertTrue(rec.conflictWarning!!.contains("Prioritizing authoritative telemetry", ignoreCase = true))
        // Should NOT tell learner to press Tab or exit Edit Mode because telemetry says it's already in Object Mode!
        assertEquals(RecommendationType.CONTINUE, rec.recommendationType)
    }

    // =========================================================================
    // 3. CASE C: DETERMINISTIC STATE MISMATCH
    // =========================================================================

    @Test
    fun `telemetry mode mismatch generates concrete CORRECT_STATE recommendation`() {
        val context = createSampleContext(
            stepTitle = "Ensure Object Mode",
            stepInstruction = "Switch to Object Mode",
            expectedOutcome = "OBJECT"
        )
        val visual = ScreenshotAnalysisResult(
            summary = "Workspace in Edit Mode.",
            observations = listOf(VisualObservation("Header shows Edit Mode", "Mode selector", 0.9)),
            confidence = 0.9
        )
        // Telemetry confirms learner is in Edit Mode
        val tele = ObservedState(
            mode = "EDIT",
            objects = listOf(ObjectObservation(name = "Cube", selected = true))
        )
        val expected = ExpectedState(operation = VerificationOperation.MODIFY, targetMode = "OBJECT")

        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = visual,
            observedState = tele,
            expectedState = expected
        )

        assertEquals(RecommendationType.CORRECT_STATE, rec.recommendationType)
        assertTrue(rec.title.contains("Object Mode", ignoreCase = true))
        assertTrue(rec.actionSteps.any { it.contains("Tab", ignoreCase = true) })
        assertEquals("Tab", rec.suggestedShortcut)
        assertTrue(rec.confidence != null && rec.confidence!! >= 0.8)
    }

    // =========================================================================
    // 4. CASE C2: VISUAL MISMATCH WITH MISSING TELEMETRY (CASE E)
    // =========================================================================

    @Test
    fun `visual mode mismatch without telemetry marks basedSolelyOnVisualEvidence`() {
        val context = createSampleContext(
            stepTitle = "Ensure Object Mode",
            stepInstruction = "Switch to Object Mode",
            expectedOutcome = "Object Mode"
        )
        val visual = ScreenshotAnalysisResult(
            summary = "Edit Mode visible.",
            observations = listOf(VisualObservation("Header displays Edit Mode", "Header", 0.85)),
            relevantUiElements = listOf(VisibleUiElement("Edit Mode", true)),
            confidence = 0.85
        )
        val expected = ExpectedState(operation = VerificationOperation.MODIFY, targetMode = "OBJECT")

        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = visual,
            observedState = null, // Missing telemetry!
            expectedState = expected
        )

        assertEquals(RecommendationType.CORRECT_STATE, rec.recommendationType)
        assertTrue(rec.basedSolelyOnVisualEvidence)
        assertTrue(rec.uncertaintyNotes.any { it.contains("without live socket telemetry", ignoreCase = true) })
        assertTrue(rec.actionSteps.any { it.contains("Tab", ignoreCase = true) })
    }

    // =========================================================================
    // 5. CASE C3: OBJECT SELECTION MISMATCH
    // =========================================================================

    @Test
    fun `unselected target object triggers selection recovery recommendation`() {
        val context = createSampleContext(
            stepTitle = "Select UV Sphere",
            stepInstruction = "Select the UV Sphere",
            expectedOutcome = "Sphere selected"
        )
        val tele = ObservedState(
            mode = "OBJECT",
            objects = listOf(ObjectObservation(name = "Sphere", selected = false)),
            selectedObjects = emptySet() // Unselected!
        )
        val expected = ExpectedState(operation = VerificationOperation.SELECT, targetName = "Sphere")

        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = null,
            observedState = tele,
            expectedState = expected
        )

        assertEquals(RecommendationType.CORRECT_STATE, rec.recommendationType)
        assertTrue(rec.title.contains("Select Object 'Sphere'"))
        assertTrue(rec.actionSteps.any { it.contains("Outliner", ignoreCase = true) })
    }

    // =========================================================================
    // 6. CASE D: INSUFFICIENT OR OBSTRUCTED VISUAL EVIDENCE
    // =========================================================================

    @Test
    fun `ambiguous or obstructed visual analysis produces REQUEST_MORE_EVIDENCE`() {
        val context = createSampleContext()
        val visual = ScreenshotAnalysisResult(
            summary = "Viewport view obstructed by menus.",
            observations = emptyList(),
            uncertaintyNotes = listOf("Cannot distinguish 3D Viewport from overlay"),
            confidence = 0.25 // Low confidence
        )

        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = visual,
            observedState = null
        )

        assertEquals(RecommendationType.REQUEST_MORE_EVIDENCE, rec.recommendationType)
        assertTrue(rec.title.contains("Clearer View", ignoreCase = true))
        assertTrue(rec.actionSteps.any { it.contains("Retake Screenshot", ignoreCase = true) })
        assertTrue(rec.hasUncertaintyOrConflict)
    }

    // =========================================================================
    // 7. CASE F: ADAPTIVE PEDAGOGICAL FADING
    // =========================================================================

    @Test
    fun `guidance stage adapts recommendation detail from direct to conceptual to reflective`() {
        val context = createSampleContext(
            stepTitle = "Ensure Object Mode",
            stepInstruction = "Switch to Object Mode",
            expectedOutcome = "OBJECT"
        )
        val tele = ObservedState(mode = "EDIT")
        val expected = ExpectedState(operation = VerificationOperation.MODIFY, targetMode = "OBJECT")

        // Stage 1: Direct Guidance
        val recStage1 = engine.generateRecommendation(
            context = context,
            analysisResult = null,
            observedState = tele,
            learnerState = LearnerState(currentGuidanceStage = GuidanceStage.STAGE_1_EXACT_GUIDANCE),
            expectedState = expected
        )
        assertTrue(recStage1.actionSteps.any { it.contains("Tab", ignoreCase = true) })

        // Stage 2: Conceptual Hint
        val recStage2 = engine.generateRecommendation(
            context = context,
            analysisResult = null,
            observedState = tele,
            learnerState = LearnerState(currentGuidanceStage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT),
            expectedState = expected
        )
        assertTrue(recStage2.actionSteps.any { it.contains("Use the Mode Selector located in the top-left", ignoreCase = true) })

        // Stage 3: Reflection Question
        val recStage3 = engine.generateRecommendation(
            context = context,
            analysisResult = null,
            observedState = tele,
            learnerState = LearnerState(currentGuidanceStage = GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION),
            expectedState = expected
        )
        assertTrue(recStage3.actionSteps.any { it.contains("What keyboard shortcut", ignoreCase = true) })
    }

    // =========================================================================
    // 8. COORDINATOR INTEGRATION & PRESERVATION INVARIANTS
    // =========================================================================

    @Test
    fun `coordinator populates TutorRecommendation without advancing step or completing lesson`() = runBlocking {
        val png = File.createTempFile("coord_rec_test_", ".png").apply {
            deleteOnExit()
            val img = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
            val g: Graphics2D = img.createGraphics()
            g.color = Color.DARK_GRAY
            g.fillRect(0, 0, 400, 300)
            g.dispose()
            ImageIO.write(img, "png", this)
        }

        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val captureService = object : BlenderScreenCaptureService {
            override suspend fun captureBlenderWorkspace(
                excludeOverlayAction: (suspend () -> Unit)?,
                restoreOverlayAction: (suspend () -> Unit)?
            ): BlenderCaptureResult {
                return BlenderCaptureResult.Success(
                    image = ImageIO.read(png),
                    pngPath = png.absolutePath,
                    windowInfo = BlenderWindowInfo(1L, 1L, "Blender", Rectangle(0, 0, 400, 300), true, false, false, true),
                    capturedAt = Instant.now()
                )
            }
        }

        val expectedAnalysis = ScreenshotAnalysisResult(
            summary = "Workspace is in Object Mode.",
            observations = listOf(VisualObservation("Object Mode confirmed", "Header", 0.9)),
            confidence = 0.9
        )
        val mockBoundary = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                return ScreenshotAnalysisSubmissionResult.Success(
                    context = context,
                    analysisResult = expectedAnalysis,
                    statusMessage = expectedAnalysis.summary
                )
            }
        }

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = captureService,
            analysisBoundary = mockBoundary,
            recommendationEngine = engine
        )

        coordinator.startCapture()
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.PreviewReady)

        coordinator.confirmScreenshot()
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.ResponseReady)

        val readyState = coordinator.state as ScreenshotHelpWorkflowState.ResponseReady
        assertNotNull(readyState.recommendation)
        val rec = readyState.recommendation!!
        assertEquals(RecommendationType.CONTINUE, rec.recommendationType)
        assertTrue(rec.title.contains("Proceed with Step 1"))

        // MANDATORY INVARIANTS:
        // 1. Active Goal preserved
        assertEquals("Create a UV sphere", controller.activePlan?.goalText)
        // 2. Active Step Index preserved at 0 (NOT marked complete or advanced!)
        assertEquals(0, controller.state.currentStepIndex)
        // 3. Current Step title intact
        assertEquals("Ensure Object Mode", controller.currentStep?.title)
    }

    // =========================================================================
    // 9. TEST P10-08: PREVIOUS RECOMMENDATION SUCCEEDED
    // =========================================================================

    @Test
    fun `Test P10-08 - previous recommendation succeeded does not continue recommending resolved correction`() {
        val context = createSampleContext(
            stepTitle = "Ensure Object Mode",
            stepInstruction = "Switch to Object Mode",
            expectedOutcome = "OBJECT"
        )
        // Telemetry confirms Object Mode has now been reached
        val tele = ObservedState(mode = "OBJECT")
        val expected = ExpectedState(operation = VerificationOperation.MODIFY, targetMode = "OBJECT")
        val visual = ScreenshotAnalysisResult(
            summary = "Workspace is in Object Mode.",
            observations = listOf(VisualObservation("Object Mode confirmed", "Header", 0.95)),
            confidence = 0.95
        )

        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = visual,
            observedState = tele,
            expectedState = expected
        )

        assertEquals(RecommendationType.CONTINUE, rec.recommendationType)
        assertFalse(rec.title.contains("Switch from", ignoreCase = true))
        assertFalse(rec.actionSteps.any { it.contains("Tab", ignoreCase = true) })
        assertTrue(rec.actionSteps.any { it.contains("instruction", ignoreCase = true) })
    }

    // =========================================================================
    // 10. TEST P10-09: MISSING PHASE 9 ANALYSIS RESULT
    // =========================================================================

    @Test
    fun `Test P10-09 - missing Phase 9 analysis result handles gracefully without pretending visual insight`() {
        val context = createSampleContext(
            stepTitle = "Ensure Object Mode",
            stepInstruction = "Switch to Object Mode",
            expectedOutcome = "OBJECT"
        )
        // No analysis result available (analysisResult = null) and no telemetry -> insufficient evidence
        val rec = engine.generateRecommendation(
            context = context,
            analysisResult = null,
            observedState = null,
            expectedState = ExpectedState(operation = VerificationOperation.MODIFY, targetMode = "OBJECT")
        )

        assertEquals(RecommendationType.REQUEST_MORE_EVIDENCE, rec.recommendationType)
        assertTrue(rec.title.contains("Clearer View", ignoreCase = true))
        assertTrue(rec.basedSolelyOnVisualEvidence)
    }

    // =========================================================================
    // 11. TEST P10-10: STALE ANALYSIS OVERWRITE PROTECTION
    // =========================================================================

    @Test
    fun `Test P10-10 - stale analysis request cannot overwrite newer tutoring context`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val captureFile = File.createTempFile("stale_test_", ".png").apply {
            deleteOnExit()
            val img = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
            val g: Graphics2D = img.createGraphics()
            g.color = Color.GRAY
            g.fillRect(0, 0, 100, 100)
            g.dispose()
            ImageIO.write(img, "png", this)
        }

        val captureService = object : BlenderScreenCaptureService {
            override suspend fun captureBlenderWorkspace(
                excludeOverlayAction: (suspend () -> Unit)?,
                restoreOverlayAction: (suspend () -> Unit)?
            ): BlenderCaptureResult {
                return BlenderCaptureResult.Success(
                    image = ImageIO.read(captureFile),
                    pngPath = captureFile.absolutePath,
                    windowInfo = BlenderWindowInfo(1L, 1L, "Blender", Rectangle(0, 0, 100, 100), true, false, false, true),
                    capturedAt = Instant.now()
                )
            }
        }

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = captureService
        )

        // Capture during Step 0
        coordinator.startCapture()
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.PreviewReady)

        // Advance controller to Step 1
        controller.nextStep()
        assertEquals(1, controller.state.currentStepIndex)

        // Confirming the old screenshot from Step 0 must fail due to stale context check!
        val confirmed = coordinator.confirmScreenshot()
        assertFalse(confirmed)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val err = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(err.message.contains("different or expired", ignoreCase = true))
    }
}

