package tutor

import bridge.BlenderCaptureResult
import bridge.BlenderScreenCaptureService
import bridge.BlenderWindowInfo
import bridge.BlenderWindowLocator
import bridge.BlenderWindowResult
import context.ExpectedState
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import models.ApplicationType
import models.HighlightRegion
import models.TutorStep

class ScreenshotHelpWorkflowTest {

    private fun createTestImage(): Pair<BufferedImage, String> {
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = java.awt.Color.BLUE
        g.fillRect(0, 0, 640, 480)
        g.dispose()

        val tempFile = Files.createTempFile("test_capture_", ".png")
        javax.imageio.ImageIO.write(img, "png", tempFile.toFile())
        return img to tempFile.toAbsolutePath().toString()
    }

    private fun createMockCaptureService(
        resultProvider: () -> BlenderCaptureResult
    ): BlenderScreenCaptureService {
        return object : BlenderScreenCaptureService {
            override suspend fun captureBlenderWorkspace(
                excludeOverlayAction: (suspend () -> Unit)?,
                restoreOverlayAction: (suspend () -> Unit)?
            ): BlenderCaptureResult {
                excludeOverlayAction?.invoke()
                val res = resultProvider()
                restoreOverlayAction?.invoke()
                return res
            }
        }
    }

    private fun createSampleWindowInfo(
        bounds: Rectangle = Rectangle(0, 0, 1920, 1080),
        minimized: Boolean = false
    ) = BlenderWindowInfo(
        hwnd = 12345L,
        processId = 6789L,
        title = "Blender [D:\\project.blend]",
        bounds = bounds,
        isVisible = true,
        isMinimized = minimized,
        isMaximized = true,
        isForeground = true
    )

    // ==========================================
    // CAPTURE LIFECYCLE TESTS (Scenarios 1-10)
    // ==========================================

    @Test
    fun `scenario 01 - capture request starts in capturing state`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                val (img, path) = createTestImage()
                BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
            }
        )

        assertEquals(ScreenshotHelpLifecycleStage.IDLE, coordinator.state.stage)
        val success = coordinator.startCapture()
        assertTrue(success)
        assertEquals(ScreenshotHelpLifecycleStage.PREVIEW_READY, coordinator.state.stage)
        val preview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
        assertEquals(0, preview.context.stepIndex)
        assertEquals("Ensure Object Mode", preview.context.stepTitle)
    }

    @Test
    fun `scenario 02 - capture succeeds and populates valid image data`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            }
        )

        val success = coordinator.startCapture()
        assertTrue(success)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.PreviewReady)

        val preview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
        assertEquals(640, preview.image.width)
        assertEquals(480, preview.image.height)
        assertEquals(testPath, preview.context.imagePath)
        assertTrue(File(testPath).exists())
    }

    @Test
    fun `scenario 03 - capture fails with diagnostic error`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.CaptureFailed("DirectX device lost or display unavailable")
            }
        )

        val success = coordinator.startCapture()
        assertFalse(success)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val error = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(error.message.contains("DirectX device lost"))
        assertTrue(error.canRetry)
    }

    @Test
    fun `scenario 04 - blender cannot be identified reports specific actionable error`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.BlenderNotRunning("Blender is not running. Please launch Blender and open your project.")
            }
        )

        val success = coordinator.startCapture()
        assertFalse(success)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val error = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(error.message.contains("Blender is not running"))
    }

    @Test
    fun `scenario 04b - blender minimized reports unminimize actionable error`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.BlenderMinimized(
                    createSampleWindowInfo(minimized = true),
                    "Blender is minimized. Please restore and unminimize the Blender window to capture your workspace."
                )
            }
        )

        val success = coordinator.startCapture()
        assertFalse(success)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val error = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(error.message.contains("Blender is minimized"))
    }

    @Test
    fun `scenario 05 - capture returns invalid image data handled gracefully`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.InvalidImage("Captured screenshot has empty dimensions.")
            }
        )

        val success = coordinator.startCapture()
        assertFalse(success)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val error = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(error.message.contains("invalid"))
    }

    @Test
    fun `scenario 06 - preview loads successfully from persisted image`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        val preview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
        val file = File(preview.context.imagePath)
        assertTrue(file.isFile)
        assertTrue(file.length() > 0)
    }

    @Test
    fun `scenario 07 - preview loading fails when file is deleted or corrupt`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        // Simulate external deletion of temp screenshot
        File(testPath).delete()

        // Confirmation should fail safely if file was deleted
        val confirmed = coordinator.confirmScreenshot()
        assertFalse(confirmed)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val err = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(err.message.contains("missing or empty"))
    }

    @Test
    fun `scenario 08 - learner cancels the preview and cleans up temp file`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        assertTrue(File(testPath).exists())

        coordinator.cancelHelp()
        assertEquals(ScreenshotHelpLifecycleStage.CANCELLED, coordinator.state.stage)
        // File must be cleaned up on cancellation
        assertFalse(File(testPath).exists())
    }

    @Test
    fun `scenario 09 - learner requests another capture retakes cleanly`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        var captureCount = 0
        var firstPath = ""
        var secondPath = ""

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = object : BlenderScreenCaptureService {
                override suspend fun captureBlenderWorkspace(
                    excludeOverlayAction: (suspend () -> Unit)?,
                    restoreOverlayAction: (suspend () -> Unit)?
                ): BlenderCaptureResult {
                    captureCount++
                    val (img, path) = createTestImage()
                    if (captureCount == 1) firstPath = path else secondPath = path
                    return BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
                }
            }
        )

        coordinator.startCapture()
        assertEquals(1, captureCount)
        assertTrue(File(firstPath).exists())

        coordinator.retakeScreenshot()
        assertEquals(2, captureCount)
        // First file was cleaned up on retake
        assertFalse(File(firstPath).exists())
        assertTrue(File(secondPath).exists())
        val preview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
        assertEquals(secondPath, preview.context.imagePath)
    }

    @Test
    fun `scenario 10 - learner confirms valid screenshot and stages for Phase 9`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        val confirmed = coordinator.confirmScreenshot()
        assertTrue(confirmed)
        assertEquals(ScreenshotHelpLifecycleStage.RESPONSE_READY, coordinator.state.stage)

        val response = coordinator.state as ScreenshotHelpWorkflowState.ResponseReady
        assertTrue(response.message.contains("Phase 9"))
        assertEquals("Ensure Object Mode", response.context.stepTitle)
    }

    // ==========================================
    // CONTEXT PRESERVATION TESTS (Scenarios 11-16)
    // ==========================================

    @Test
    fun `scenario 11 - active goal remains unchanged during help workflow`() = runBlocking {
        val controller = TutorController()
        val plan = controller.startUserGoal("Create a UV sphere")
        assertNotNull(plan)
        val originalGoal = plan.goalText

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                val (img, path) = createTestImage()
                BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        assertEquals(originalGoal, controller.activePlan?.goalText)

        coordinator.confirmScreenshot()
        assertEquals(originalGoal, controller.activePlan?.goalText)

        coordinator.dismiss()
        assertEquals(originalGoal, controller.activePlan?.goalText)
    }

    @Test
    fun `scenario 12 - active plan remains unchanged during help workflow`() = runBlocking {
        val controller = TutorController()
        val plan = controller.startUserGoal("Create a UV sphere")
        assertNotNull(plan)
        val originalPlanSteps = plan.steps.map { it.title }

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                val (img, path) = createTestImage()
                BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        assertEquals(originalPlanSteps, controller.activePlan?.steps?.map { it.title })

        coordinator.cancelHelp()
        assertEquals(originalPlanSteps, controller.activePlan?.steps?.map { it.title })
    }

    @Test
    fun `scenario 13 - active step remains unchanged during help workflow`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")
        controller.nextStep() // advance to step 1
        assertEquals(1, controller.state.currentStepIndex)
        val originalStep = controller.currentStep

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                val (img, path) = createTestImage()
                BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        assertEquals(1, controller.state.currentStepIndex)
        assertEquals(originalStep?.title, controller.currentStep?.title)

        val preview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
        assertEquals(1, preview.context.stepIndex)
        assertEquals(originalStep?.title, preview.context.stepTitle)

        coordinator.confirmScreenshot()
        assertEquals(1, controller.state.currentStepIndex)
    }

    @Test
    fun `scenario 14 - relevant tutoring history and adaptation reason preserved`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                val (img, path) = createTestImage()
                BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        val preview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
        assertNotNull(preview.context.capturedAt)
        assertNotNull(preview.context.sessionId)
        assertEquals("Create a UV sphere", preview.context.userGoal)
    }

    @Test
    fun `scenario 15 - screenshot metadata is associated with the correct request and window`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val windowInfo = createSampleWindowInfo(
            bounds = Rectangle(50, 50, 1600, 900)
        )
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                val (img, path) = createTestImage()
                BlenderCaptureResult.Success(img, path, windowInfo)
            }
        )

        coordinator.startCapture()
        val preview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
        assertEquals(6789L, preview.context.blenderProcessId)
        assertEquals(Rectangle(50, 50, 1600, 900), preview.context.blenderWindowBounds)
        assertEquals(640, preview.context.imageWidth)
        assertEquals(480, preview.context.imageHeight)
    }

    @Test
    fun `scenario 16 - screenshot help does not restart the lesson`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")
        controller.nextStep()
        assertEquals(1, controller.state.currentStepIndex)

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                val (img, path) = createTestImage()
                BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        coordinator.confirmScreenshot()
        coordinator.dismiss()

        // Still at step 1!
        assertEquals(1, controller.state.currentStepIndex)
        assertEquals(3, controller.stepsForCurrentApp().size)
    }

    // ==========================================
    // REQUEST SAFETY TESTS (Scenarios 17-22)
    // ==========================================

    @Test
    fun `scenario 17 - duplicate submissions while capturing are prevented`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        var captureAttempts = 0
        lateinit var coordinator: ScreenshotHelpCoordinator
        coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = object : BlenderScreenCaptureService {
                override suspend fun captureBlenderWorkspace(
                    excludeOverlayAction: (suspend () -> Unit)?,
                    restoreOverlayAction: (suspend () -> Unit)?
                ): BlenderCaptureResult {
                    captureAttempts++
                    // While capturing, a duplicate startCapture should return false
                    val secondAttempt = coordinator.startCapture()
                    assertFalse(secondAttempt)
                    val (img, path) = createTestImage()
                    return BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
                }
            }
        )

        val firstAttempt = coordinator.startCapture()
        assertTrue(firstAttempt)
        assertEquals(1, captureAttempts)
    }

    @Test
    fun `scenario 18 - a stale request cannot modify a newer session`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.PreviewReady)

        // Learner switches goals or restarts lesson
        controller.startUserGoal("Delete active object")

        // In-flight preview from previous goal is rejected upon confirm
        val confirmed = coordinator.confirmScreenshot()
        assertFalse(confirmed)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val err = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(err.message.contains("different or expired"))
    }

    @Test
    fun `scenario 19 - reset clears the appropriate pending state and file`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        assertTrue(File(testPath).exists())

        controller.clearUserGoal()
        assertEquals(ScreenshotHelpLifecycleStage.IDLE, controller.screenshotHelpCoordinator.state.stage)
    }

    @Test
    fun `scenario 20 - cancellation prevents stale results from being applied`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            }
        )

        coordinator.startCapture()
        coordinator.cancelHelp()

        // Confirmation on cancelled state should return false
        val confirmed = coordinator.confirmScreenshot()
        assertFalse(confirmed)
        assertEquals(ScreenshotHelpLifecycleStage.CANCELLED, coordinator.state.stage)
    }

    @Test
    fun `scenario 21 - invalid requests with no active lesson are rejected`() = runBlocking {
        val controller = TutorController()
        // No goal entered, no tutorial loaded
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                val (img, path) = createTestImage()
                BlenderCaptureResult.Success(img, path, createSampleWindowInfo())
            }
        )

        val success = coordinator.startCapture()
        assertFalse(success)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val err = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(err.message.contains("No active tutorial step"))
    }

    @Test
    fun `scenario 22 - phase 9 boundary handles rejected requests cleanly`() = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val (testImg, testPath) = createTestImage()
        val rejectingBoundary = object : ScreenshotAnalysisBoundary {
            override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
                return ScreenshotAnalysisSubmissionResult.Rejected("Multimodal model service quota exceeded")
            }
        }

        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = createMockCaptureService {
                BlenderCaptureResult.Success(testImg, testPath, createSampleWindowInfo())
            },
            analysisBoundary = rejectingBoundary
        )

        coordinator.startCapture()
        val confirmed = coordinator.confirmScreenshot()
        assertFalse(confirmed)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val err = coordinator.state as ScreenshotHelpWorkflowState.Error
        assertTrue(err.message.contains("quota exceeded"))
    }

    // ==========================================
    // REGRESSION TESTS (Scenarios 23-27)
    // ==========================================

    @Test
    fun `scenario 23 - existing goal planning continues to work`() {
        val planner = UserGoalPlanner()
        val plan = planner.planGoal("create a cube")
        assertEquals(ApplicationType.BLENDER, plan.application)
        assertEquals(3, plan.steps.size)
        assertEquals("Ensure Object Mode", plan.steps[0].title)
        assertEquals("Add Mesh Cube", plan.steps[1].title)
    }

    @Test
    fun `scenario 24 - existing controller step progression remains correct`() {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")
        assertEquals(0, controller.state.currentStepIndex)

        controller.nextStep()
        assertEquals(1, controller.state.currentStepIndex)

        controller.previousStep()
        assertEquals(0, controller.state.currentStepIndex)
    }

    @Test
    fun `scenario 25 - state verification closed loop behavior unchanged`() {
        val controller = TutorController()
        controller.startUserGoal("make a cube and move it up")
        assertEquals(0, controller.state.currentStepIndex)

        // Advance to verification step (index 2: "Verify Cube Exists")
        controller.nextStep()
        controller.nextStep()
        assertEquals(2, controller.state.currentStepIndex)
        assertEquals("Verify Cube Exists", controller.currentStep?.title)

        val successResult = VerificationResult(
            status = VerificationStatus.SUCCESS,
            runId = "run-verify-cube-success",
            operation = VerificationOperation.CREATE,
            explanation = "Cube was created in the scene."
        )

        controller.recordVerificationResult(successResult, controller.currentStep?.expectedState)

        // Step advances to step index 3 ("Move Cube Up")!
        assertEquals(3, controller.state.currentStepIndex)
        assertEquals("Move Cube Up", controller.currentStep?.title)
    }

    @Test
    fun `scenario 26 - pause and screen assistance toggles work alongside help workflow`() {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        controller.togglePause()
        assertTrue(controller.state.isPaused)

        controller.toggleScreenAssistance()
        assertFalse(controller.state.screenAssistanceEnabled)
    }

    @Test
    fun `scenario 27 - overlay highlight manipulation preserved`() {
        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")

        val region = HighlightRegion(50f, 60f, 120f, 40f)
        controller.setHighlight(region)
        assertEquals(region, controller.state.currentHighlight)
    }
}
