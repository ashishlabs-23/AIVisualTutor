package tutor

import bridge.BlenderCaptureResult
import bridge.BlenderScreenCaptureService
import bridge.BlenderWindowInfo
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

class GroqMultimodalScreenshotAnalyzerTest {

    private fun createSamplePng(width: Int = 400, height: Int = 300): File {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = img.createGraphics()
        g.color = Color.DARK_GRAY
        g.fillRect(0, 0, width, height)
        g.color = Color.CYAN
        g.drawString("Blender 3D Viewport", 20, 40)
        g.dispose()

        val tempFile = File.createTempFile("test_groq_sample_", ".png")
        tempFile.deleteOnExit()
        ImageIO.write(img, "png", tempFile)
        return tempFile
    }

    private fun createSampleContext(file: File): ScreenshotHelpContext {
        return ScreenshotHelpContext(
            requestId = "req-groq-1",
            sessionId = "session-groq",
            userGoal = "Model a coffee cup",
            planId = "plan_groq",
            stepId = 1,
            stepIndex = 0,
            stepTitle = "Add Cylinder",
            stepInstruction = "Press Shift+A and select Mesh -> Cylinder",
            expectedOutcome = "Cylinder in 3D Viewport",
            capturedAt = Instant.now(),
            imagePath = file.absolutePath,
            imageWidth = 400,
            imageHeight = 300,
            blenderProcessId = 12345L,
            blenderWindowBounds = Rectangle(0, 0, 400, 300),
            latestObservedStateSummary = "Mode: OBJECT"
        )
    }

    @Test
    fun rejectsMissingScreenshotFile() = runBlocking {
        val analyzer = GroqMultimodalScreenshotAnalyzer(apiKeyProvider = { "gsk_test" })
        val context = createSampleContext(File("non_existent_groq_file.png"))

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.reason.contains("not found"))
    }

    @Test
    fun rejectsEmptyScreenshotFile() = runBlocking {
        val emptyFile = File.createTempFile("empty_groq_", ".png")
        emptyFile.deleteOnExit()

        val analyzer = GroqMultimodalScreenshotAnalyzer(apiKeyProvider = { "gsk_test" })
        val context = createSampleContext(emptyFile)

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.reason.contains("empty"))
    }

    @Test
    fun unconfiguredGroqKeyTriggersOfflineStaging() = runBlocking {
        val file = createSamplePng()
        val analyzer = GroqMultimodalScreenshotAnalyzer(apiKeyProvider = { "" }, allowOfflineStagingWhenUnconfigured = true)
        val context = createSampleContext(file)

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.StagedForPhase9)
        assertTrue(result.statusMessage.contains("GROQ_API_KEY"))
    }

    @Test
    fun unconfiguredGroqKeyRejectsWhenStagingDisabled() = runBlocking {
        val file = createSamplePng()
        val analyzer = GroqMultimodalScreenshotAnalyzer(apiKeyProvider = { "" }, allowOfflineStagingWhenUnconfigured = false)
        val context = createSampleContext(file)

        val result = analyzer.submitForAnalysis(context)
        assertTrue(result is ScreenshotAnalysisSubmissionResult.Rejected)
        assertTrue(result.isConfigurationError)
    }

    @Test
    fun parsesGroqJsonAnalysisResponse() {
        val groqResponse = """
        {
          "id": "chatcmpl-12345",
          "object": "chat.completion",
          "created": 1728777000,
          "model": "llama-3.2-11b-vision-preview",
          "choices": [
            {
              "index": 0,
              "message": {
                "role": "assistant",
                "content": "{\n  \"summary\": \"3D Viewport with default cylinder mesh.\",\n  \"observations\": [\n    {\"description\": \"Active object is Cylinder in Object Mode\", \"evidence\": \"Orange selection outline visible in 3D Viewport\", \"confidence\": 0.95}\n  ],\n  \"relevantUiElements\": [\n    {\"name\": \"Mode Selector\", \"visible\": true, \"evidence\": \"Header shows Object Mode\"}\n  ],\n  \"uncertaintyNotes\": [\n    \"Vertex count cannot be confirmed without switching to Edit Mode\"\n  ],\n  \"suggestedNextStepEvidence\": \"Cylinder is ready for scaling\",\n  \"confidence\": 0.92\n}"
              },
              "finish_reason": "stop"
            }
          ]
        }
        """.trimIndent()

        val content = MultimodalAnalysisJsonParser.extractGroqMessageContent(groqResponse)
        val result = MultimodalAnalysisJsonParser.parseJsonAnalysis(content, "llama-3.2-11b-vision-preview", 120L)

        assertEquals("3D Viewport with default cylinder mesh.", result.summary)
        assertEquals(1, result.observations.size)
        assertEquals("Active object is Cylinder in Object Mode", result.observations[0].description)
        assertEquals("Orange selection outline visible in 3D Viewport", result.observations[0].evidence)
        assertEquals(0.95, result.observations[0].confidence)

        assertEquals(1, result.relevantUiElements.size)
        assertEquals("Mode Selector", result.relevantUiElements[0].name)
        assertTrue(result.relevantUiElements[0].visible)

        assertEquals(1, result.uncertaintyNotes.size)
        assertEquals(0.92, result.confidence)
        assertEquals("llama-3.2-11b-vision-preview", result.modelIdentifier)
        assertEquals(120L, result.latencyMillis)
    }

    @Test
    fun smartMultimodalAnalyzerRoutesToGroqWhenGroqKeyIsPresent() = runBlocking {
        val file = createSamplePng()
        val context = createSampleContext(file)

        var groqCalled = false
        var geminiCalled = false

        val mockGroq = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                groqCalled = true
                return ScreenshotAnalysisSubmissionResult.StagedForPhase9(context, "Routed to Groq")
            }
        }
        val mockGemini = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                geminiCalled = true
                return ScreenshotAnalysisSubmissionResult.StagedForPhase9(context, "Routed to Gemini")
            }
        }

        val smartAnalyzer = SmartMultimodalScreenshotAnalyzer(
            groqAnalyzer = mockGroq,
            geminiAnalyzer = mockGemini,
            groqKeyProvider = { "gsk_live_key" },
            geminiKeyProvider = { "gemini_key" }
        )

        val result = smartAnalyzer.submitForAnalysis(context)
        assertTrue(groqCalled)
        assertFalse(geminiCalled)
        assertEquals("Routed to Groq", (result as ScreenshotAnalysisSubmissionResult.StagedForPhase9).statusMessage)
    }

    @Test
    fun smartMultimodalAnalyzerRoutesToGeminiWhenGroqKeyIsMissing() = runBlocking {
        val file = createSamplePng()
        val context = createSampleContext(file)

        var groqCalled = false
        var geminiCalled = false

        val mockGroq = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                groqCalled = true
                return ScreenshotAnalysisSubmissionResult.StagedForPhase9(context, "Routed to Groq")
            }
        }
        val mockGemini = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                geminiCalled = true
                return ScreenshotAnalysisSubmissionResult.StagedForPhase9(context, "Routed to Gemini")
            }
        }

        val smartAnalyzer = SmartMultimodalScreenshotAnalyzer(
            groqAnalyzer = mockGroq,
            geminiAnalyzer = mockGemini,
            groqKeyProvider = { "" },
            geminiKeyProvider = { "gemini_live_key" }
        )

        val result = smartAnalyzer.submitForAnalysis(context)
        assertFalse(groqCalled)
        assertTrue(geminiCalled)
        assertEquals("Routed to Gemini", (result as ScreenshotAnalysisSubmissionResult.StagedForPhase9).statusMessage)
    }

    @Test
    fun coordinatorUsesSmartAnalyzerByDefault() = runBlocking {
        val png = createSamplePng()
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

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

        // Instantiated without explicit boundary argument -> defaults to SmartMultimodalScreenshotAnalyzer()
        val coordinator = ScreenshotHelpCoordinator(controller, captureService)
        val captureSuccess = coordinator.startCapture()
        assertTrue(captureSuccess)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.PreviewReady)

        val confirmSuccess = coordinator.confirmScreenshot()
        assertTrue(confirmSuccess)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.ResponseReady)
        val response = coordinator.state as ScreenshotHelpWorkflowState.ResponseReady
        // Without keys, safely degraded to staging without crash
        assertTrue(response.message.contains("verified") || response.message.contains("Step 1"))
    }
}
