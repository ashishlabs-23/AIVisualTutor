package tutor

import bridge.BlenderCaptureResult
import bridge.BlenderScreenCaptureService
import bridge.BlenderWindowInfo
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Targeted Automated Tests for Phase 9 Multimodal AI Screenshot Analysis.
 * Covers image validation, request construction, structured response parsing,
 * error handling, and workflow preservation.
 */
class GeminiMultimodalScreenshotAnalyzerTest {

    private fun createSamplePng(width: Int = 400, height: Int = 300): File {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = img.createGraphics()
        g.color = Color.DARK_GRAY
        g.fillRect(0, 0, width, height)
        g.color = Color.CYAN
        g.drawString("Blender 3D Viewport", 20, 40)
        g.dispose()

        val tempFile = File.createTempFile("test_sample_capture_", ".png")
        tempFile.deleteOnExit()
        ImageIO.write(img, "png", tempFile)
        return tempFile
    }

    private fun createSampleContext(file: File): ScreenshotHelpContext {
        return ScreenshotHelpContext(
            requestId = "req-123",
            sessionId = "session-test",
            userGoal = "Create a UV sphere and move it upward along the Z axis.",
            planId = "plan_test",
            stepId = 1,
            stepIndex = 0,
            stepTitle = "Ensure Object Mode",
            stepInstruction = "Switch to Object Mode in the 3D Viewport header.",
            expectedOutcome = "OBJECT_MODE on 3D Viewport",
            capturedAt = Instant.now(),
            imagePath = file.absolutePath,
            imageWidth = 400,
            imageHeight = 300,
            blenderProcessId = 12345L,
            blenderWindowBounds = Rectangle(0, 0, 400, 300),
            latestObservedStateSummary = "Mode: OBJECT, Objects: 1"
        )
    }

    // =========================================================================
    // 1. IMAGE VALIDATION TESTS
    // =========================================================================

    @Test
    fun `rejects missing screenshot file`() = runBlocking {
        val nonExistent = File("missing_file_${System.currentTimeMillis()}.png")
        val context = createSampleContext(nonExistent)
        val analyzer = GeminiMultimodalScreenshotAnalyzer(apiKeyProvider = { "fake-key" })

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.reason.contains("not found", ignoreCase = true))
    }

    @Test
    fun `rejects empty screenshot file`() = runBlocking {
        val emptyFile = File.createTempFile("empty_capture_", ".png").apply { deleteOnExit() }
        val context = createSampleContext(emptyFile)
        val analyzer = GeminiMultimodalScreenshotAnalyzer(apiKeyProvider = { "fake-key" })

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.reason.contains("empty", ignoreCase = true))
    }

    @Test
    fun `rejects corrupted screenshot file`() = runBlocking {
        val corruptFile = File.createTempFile("corrupt_capture_", ".png").apply { deleteOnExit() }
        corruptFile.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)) // not a valid PNG
        val context = createSampleContext(corruptFile)
        val analyzer = GeminiMultimodalScreenshotAnalyzer(apiKeyProvider = { "fake-key" })

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.reason.contains("could not be decoded", ignoreCase = true) || result.reason.contains("format", ignoreCase = true))
    }

    @Test
    fun `rejects unsupported file format`() = runBlocking {
        val txtFile = File.createTempFile("text_file_", ".txt").apply { deleteOnExit() }
        txtFile.writeText("This is plain text, not an image.")
        val context = createSampleContext(txtFile)
        val analyzer = GeminiMultimodalScreenshotAnalyzer(apiKeyProvider = { "fake-key" })

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.reason.contains("unsupported", ignoreCase = true))
    }

    // =========================================================================
    // 2. CONFIGURATION & CREDENTIAL TESTS
    // =========================================================================

    @Test
    fun `rejects when API key is unconfigured and staging is disabled`() = runBlocking {
        val png = createSamplePng()
        val context = createSampleContext(png)
        val analyzer = GeminiMultimodalScreenshotAnalyzer(apiKeyProvider = { "" }, allowOfflineStagingWhenUnconfigured = false)

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.isConfigurationError)
        assertTrue(result.reason.contains("not configured", ignoreCase = true))
        assertTrue(result.reason.contains("GEMINI_API_KEY"))
    }

    @Test
    fun `rejects when API key is MOCK_KEY and staging is disabled`() = runBlocking {
        val png = createSamplePng()
        val context = createSampleContext(png)
        val analyzer = GeminiMultimodalScreenshotAnalyzer(apiKeyProvider = { "MOCK_KEY" }, allowOfflineStagingWhenUnconfigured = false)

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.isConfigurationError)
    }

    @Test
    fun `stages for Phase 9 when API key is unconfigured and staging is allowed`() = runBlocking {
        val png = createSamplePng()
        val context = createSampleContext(png)
        val analyzer = GeminiMultimodalScreenshotAnalyzer(apiKeyProvider = { "" }, allowOfflineStagingWhenUnconfigured = true)

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.StagedForPhase9)
        assertTrue(result.statusMessage.contains("Phase 9"))
    }

    // =========================================================================
    // 3. STRUCTURED RESPONSE PARSING TESTS
    // =========================================================================

    @Test
    fun `parses valid structured JSON response accurately`() {
        val jsonPayload = """
        {
          "summary": "Blender is open in Object Mode with a Default Cube centered in the 3D Viewport.",
          "observations": [
            {"description": "Top-left mode indicator clearly shows 'Object Mode'", "evidence": "Header text shows Object Mode", "confidence": 0.95},
            {"description": "A 3D cube object is selected in the viewport", "evidence": "Orange selection outline on cube", "confidence": 0.90}
          ],
          "relevantUiElements": [
            {"name": "Mode Selector", "visible": true, "evidence": "Top header"},
            {"name": "3D Viewport", "visible": true, "evidence": "Main workspace"},
            {"name": "Default Cube", "visible": true, "evidence": "Centered at (0,0,0)"}
          ],
          "uncertaintyNotes": [
            "Cannot verify exact transform Z coordinates without viewing the Transform N-panel"
          ],
          "suggestedNextStepEvidence": "Object Mode is established. Ready to add a UV sphere.",
          "confidence": 0.92
        }
        """.trimIndent()

        val analyzer = GeminiMultimodalScreenshotAnalyzer()
        val result = analyzer.parseJsonAnalysis(jsonPayload, "gemini-1.5-flash", 120L)

        assertEquals("Blender is open in Object Mode with a Default Cube centered in the 3D Viewport.", result.summary)
        assertEquals(2, result.observations.size)
        assertEquals("Top-left mode indicator clearly shows 'Object Mode'", result.observations[0].description)
        assertEquals("Header text shows Object Mode", result.observations[0].evidence)
        assertEquals(0.95, result.observations[0].confidence)

        assertEquals(3, result.relevantUiElements.size)
        assertEquals("Mode Selector", result.relevantUiElements[0].name)
        assertTrue(result.relevantUiElements[0].visible)

        assertEquals(1, result.uncertaintyNotes.size)
        assertTrue(result.hasUncertainty)
        assertEquals(0.92, result.confidence)
        assertEquals("gemini-1.5-flash", result.modelIdentifier)
        assertEquals(120L, result.latencyMillis)
    }

    @Test
    fun `handles response with missing optional fields`() {
        val jsonPayload = """
        {
          "summary": "Workspace shows Blender 3D Viewport."
        }
        """.trimIndent()

        val analyzer = GeminiMultimodalScreenshotAnalyzer()
        val result = analyzer.parseJsonAnalysis(jsonPayload, "gemini-1.5-flash", 80L)

        assertEquals("Workspace shows Blender 3D Viewport.", result.summary)
        assertTrue(result.observations.isEmpty())
        assertTrue(result.relevantUiElements.isEmpty())
        assertTrue(result.uncertaintyNotes.isEmpty())
        assertNull(result.confidence)
        assertFalse(result.hasUncertainty)
    }

    @Test
    fun `handles malformed JSON with fallback text extraction`() {
        val messyText = "I see Blender in Object Mode with a mesh cube visible."
        val analyzer = GeminiMultimodalScreenshotAnalyzer()
        val result = analyzer.parseJsonAnalysis(messyText, "gemini-1.5-flash", 95L)

        // Does not crash; provides graceful fallback summary
        assertTrue(result.summary.isNotBlank())
    }

    // =========================================================================
    // 4. COORDINATOR INTEGRATION & LIFECYCLE TESTS
    // =========================================================================

    @Test
    fun `coordinator transitions to ResponseReady with visual analysis result on success`() = runBlocking {
        val png = createSamplePng()
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val expectedResult = ScreenshotAnalysisResult(
            summary = "Blender is in Object Mode with active cube selected.",
            observations = listOf(VisualObservation("Object mode visible", "Header", 0.95)),
            relevantUiElements = listOf(VisibleUiElement("3D Viewport", true)),
            uncertaintyNotes = emptyList(),
            confidence = 0.90
        )

        val mockBoundary = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                return ScreenshotAnalysisSubmissionResult.Success(
                    context = context,
                    analysisResult = expectedResult,
                    statusMessage = expectedResult.summary
                )
            }
        }

        val captureService = object : BlenderScreenCaptureService {
            override suspend fun captureBlenderWorkspace(excludeOverlayAction: (suspend () -> Unit)?, restoreOverlayAction: (suspend () -> Unit)?): BlenderCaptureResult {
                return BlenderCaptureResult.Success(
                    image = ImageIO.read(png),
                    pngPath = png.absolutePath,
                    windowInfo = BlenderWindowInfo(1L, 1L, "Blender", Rectangle(0, 0, 400, 300), true, false, false, true),
                    capturedAt = Instant.now()
                )
            }
        }

        val coordinator = ScreenshotHelpCoordinator(controller, captureService, mockBoundary)

        // Capture
        val captureSuccess = coordinator.startCapture()
        assertTrue(captureSuccess)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.PreviewReady)

        // Confirm
        val confirmSuccess = coordinator.confirmScreenshot()
        assertTrue(confirmSuccess)
        assertEquals(ScreenshotHelpLifecycleStage.RESPONSE_READY, coordinator.state.stage)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.ResponseReady)

        val readyState = coordinator.state as ScreenshotHelpWorkflowState.ResponseReady
        assertNotNull(readyState.analysisResult)
        assertEquals("Blender is in Object Mode with active cube selected.", readyState.analysisResult?.summary)
        assertEquals(1, readyState.analysisResult?.observations?.size)

        // Verify lesson preservation: Active goal, step index 0, total steps intact
        assertEquals("Create a UV sphere", controller.activePlan?.goalText)
        assertEquals(0, controller.state.currentStepIndex)
        assertEquals("Ensure Object Mode", controller.currentStep?.title)
    }

    @Test
    fun `coordinator transitions to Error when analysis boundary rejects request`() = runBlocking {
        val png = createSamplePng()
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val mockBoundary = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                return ScreenshotAnalysisSubmissionResult.Rejected(
                    reason = "API rate limit exceeded. Please retry.",
                    isConfigurationError = false
                )
            }
        }

        val captureService = object : BlenderScreenCaptureService {
            override suspend fun captureBlenderWorkspace(excludeOverlayAction: (suspend () -> Unit)?, restoreOverlayAction: (suspend () -> Unit)?): BlenderCaptureResult {
                return BlenderCaptureResult.Success(
                    image = ImageIO.read(png),
                    pngPath = png.absolutePath,
                    windowInfo = BlenderWindowInfo(1L, 1L, "Blender", Rectangle(0, 0, 400, 300), true, false, false, true),
                    capturedAt = Instant.now()
                )
            }
        }

        val coordinator = ScreenshotHelpCoordinator(controller, captureService, mockBoundary)
        coordinator.startCapture()

        val confirmSuccess = coordinator.confirmScreenshot()
        assertFalse(confirmSuccess)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val err = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(err.message.contains("rate limit", ignoreCase = true))
        assertTrue(err.canRetry)

        // Lesson is not lost on error
        assertEquals(0, controller.state.currentStepIndex)
        assertEquals("Create a UV sphere", controller.activePlan?.goalText)
    }

    @Test
    fun `visual analysis never marks lesson steps complete`() = runBlocking {
        val png = createSamplePng()
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")
        assertEquals(0, controller.state.currentStepIndex)

        val analysisResult = ScreenshotAnalysisResult(
            summary = "A UV Sphere is visible and moved 2 units along Z.",
            observations = listOf(VisualObservation("UV sphere detected", "Sphere geometry", 0.99))
        )

        val mockBoundary = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                return ScreenshotAnalysisSubmissionResult.Success(context, analysisResult)
            }
        }

        val captureService = object : BlenderScreenCaptureService {
            override suspend fun captureBlenderWorkspace(excludeOverlayAction: (suspend () -> Unit)?, restoreOverlayAction: (suspend () -> Unit)?): BlenderCaptureResult {
                return BlenderCaptureResult.Success(
                    image = ImageIO.read(png),
                    pngPath = png.absolutePath,
                    windowInfo = BlenderWindowInfo(1L, 1L, "Blender", Rectangle(0, 0, 400, 300), true, false, false, true),
                    capturedAt = Instant.now()
                )
            }
        }

        val coordinator = ScreenshotHelpCoordinator(controller, captureService, mockBoundary)
        coordinator.startCapture()
        coordinator.confirmScreenshot()

        // Critical requirement: Visual analysis must NOT mark steps complete in Phase 9!
        assertEquals(0, controller.state.currentStepIndex, "Step index must remain at 0; visual analysis does not verify state")
    }
}
