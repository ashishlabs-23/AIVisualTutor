package bridge

import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import tutor.ScreenshotAnalysisBoundary
import tutor.ScreenshotAnalysisSubmissionResult
import tutor.ScreenshotHelpContext
import tutor.ScreenshotHelpCoordinator
import tutor.ScreenshotHelpLifecycleStage
import tutor.ScreenshotHelpWorkflowState
import tutor.TutorController

/**
 * Live Windows Desktop Integration Tests validating real Blender discovery,
 * real Robot desktop capture, overlay exclusion, and session continuity.
 */
class LiveDesktopBlenderCaptureIntegrationTest {

    private val locator = WindowsBlenderWindowLocator()
    private val captureService = RobotBlenderScreenCaptureService(locator)

    @Test
    fun `Test E - Blender unavailable when not running`() = runBlocking {
        // Ensure no stray blender processes are running before this test
        killAnyRunningBlender()

        val result = locator.locateBlenderWindow()
        println("Live Test E Locator Result: $result")
        assertEquals(BlenderWindowResult.NotRunning, result)

        val controller = TutorController()
        controller.startUserGoal("Create a UV sphere")
        val coordinator = ScreenshotHelpCoordinator(controller, captureService)

        val success = coordinator.startCapture()
        assertFalse(success, "Capture must fail when Blender is not running")
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)
        val err = coordinator.state as ScreenshotHelpWorkflowState.Error
        println("Live Test E Error Message: ${err.message}")
        assertTrue(err.message.contains("not running", ignoreCase = true))

        // Session continuity check (Test G part 1)
        assertEquals(0, controller.state.currentStepIndex)
        assertEquals("Ensure Object Mode", controller.currentStep?.title)
        assertEquals("Create a UV sphere", controller.activePlan?.goalText)
    }

    @Test
    fun `Test A B C D F G - Live Blender Window Capture Workflow`() = runBlocking {
        val blenderExe = BlenderExecutableLocator.findBlenderExecutable()
        if (blenderExe == null || !blenderExe.isFile) {
            println("Skipping live test: Blender executable not found")
            return@runBlocking
        }

        killAnyRunningBlender()

        println("Launching live Blender GUI from ${blenderExe.absolutePath}...")
        val blenderProcess = ProcessBuilder(blenderExe.absolutePath).start()

        try {
            // Wait up to 30 seconds for the GUI window to be created and become visible
            var windowFound: BlenderWindowInfo? = null
            for (i in 1..60) {
                delay(500)
                when (val res = locator.locateBlenderWindow()) {
                    is BlenderWindowResult.Found -> {
                        windowFound = res.window
                        break
                    }
                    else -> {}
                }
            }

            assertNotNull(windowFound, "Live Blender window must be discovered by locator")
            println("Live Test A - Found Blender window:")
            println("  HWND: ${windowFound.hwnd}")
            println("  PID: ${windowFound.processId}")
            println("  Title: ${windowFound.title}")
            println("  Bounds: ${windowFound.bounds}")
            println("  Visible: ${windowFound.isVisible}")
            println("  Minimized: ${windowFound.isMinimized}")
            println("  Maximized: ${windowFound.isMaximized}")
            assertTrue(windowFound.bounds.width > 200)
            assertTrue(windowFound.bounds.height > 200)

            // Test A: Normal capture with active tutoring goal
            val controller = TutorController()
            val plan = controller.startUserGoal("Create a UV sphere")
            assertNotNull(plan)
            assertEquals(0, controller.state.currentStepIndex)

            var overlayExcluded = false
            var overlayRestored = false

            val coordinator = ScreenshotHelpCoordinator(controller, captureService)

            println("Capturing live Blender workspace...")
            val captureSuccess = coordinator.startCapture(
                excludeOverlayAction = {
                    overlayExcluded = true
                    println("Live Test C - Overlay exclusion callback invoked (hiding HUD)")
                },
                restoreOverlayAction = {
                    overlayRestored = true
                    println("Live Test C - Overlay restoration callback invoked (restoring HUD)")
                }
            )

            // Verify Test C: Overlay exclusion callbacks were executed
            assertTrue(overlayExcluded, "Overlay exclusion callback must be called")
            assertTrue(overlayRestored, "Overlay restoration callback must be called")

            // Verify Test A: Successful capture & preview
            assertTrue(captureSuccess, "Live Blender screen capture must succeed")
            assertTrue(coordinator.state is ScreenshotHelpWorkflowState.PreviewReady)

            val preview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
            println("Live Capture Details:")
            println("  Image dimensions: ${preview.image.width}x${preview.image.height}")
            println("  PNG path: ${preview.context.imagePath}")
            println("  File size: ${File(preview.context.imagePath).length()} bytes")

            assertTrue(preview.image.width > 200)
            assertTrue(preview.image.height > 200)
            val captureFile = File(preview.context.imagePath)
            assertTrue(captureFile.isFile)
            assertTrue(captureFile.length() > 1024, "Real PNG image must be non-empty")

            // Test F: Cancellation and retry
            println("Live Test F - Cancelling preview...")
            val firstCapturePath = preview.context.imagePath
            coordinator.cancelHelp()
            assertEquals(ScreenshotHelpLifecycleStage.CANCELLED, coordinator.state.stage)
            assertFalse(File(firstCapturePath).exists(), "Cancelled screenshot file must be cleaned up")

            println("Live Test F - Retaking screenshot...")
            val retakeSuccess = coordinator.retakeScreenshot(
                excludeOverlayAction = { println("Overlay excluded for retake") },
                restoreOverlayAction = { println("Overlay restored after retake") }
            )
            assertTrue(retakeSuccess)
            assertTrue(coordinator.state is ScreenshotHelpWorkflowState.PreviewReady)
            val secondPreview = coordinator.state as ScreenshotHelpWorkflowState.PreviewReady
            val secondCapturePath = secondPreview.context.imagePath
            assertTrue(File(secondCapturePath).isFile)
            println("Retake successful. New PNG: $secondCapturePath")

            // Test D: Bring another window / verify target
            println("Live Test D - Verifying target window association...")
            assertEquals(windowFound.processId, secondPreview.context.blenderProcessId)
            assertEquals(windowFound.bounds, secondPreview.context.blenderWindowBounds)

            // Confirm submission for Phase 9
            val confirmed = coordinator.confirmScreenshot()
            assertTrue(confirmed)
            assertEquals(ScreenshotHelpLifecycleStage.RESPONSE_READY, coordinator.state.stage)
            val response = coordinator.state as ScreenshotHelpWorkflowState.ResponseReady
            println("Phase 9/10 Response: ${response.message}")
            assertTrue(response.message.contains("Phase 9") || response.analysisResult != null || response.recommendation != null)

            // Test G: Session continuity verification
            assertEquals("Create a UV sphere", controller.activePlan?.goalText)
            assertEquals(0, controller.state.currentStepIndex)
            assertEquals("Ensure Object Mode", controller.currentStep?.title)
            assertEquals(3, controller.stepsForCurrentApp().size)
            println("Live Test G - Session progress completely preserved!")

        } finally {
            blenderProcess.destroyForcibly()
            killAnyRunningBlender()
        }
    }

    private fun killAnyRunningBlender() {
        runCatching {
            ProcessBuilder("taskkill", "/F", "/IM", "blender.exe").start().waitFor()
            Thread.sleep(1000)
        }
    }
}
