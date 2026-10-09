package context

import bridge.CaptureBridge
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsUiAutomationLiveIntegrationTest {
    @Test
    fun previousWindowCaptureCarriesItsOwnHandleIntoProductionUia() = runBlocking {
        assumeTrue(
            "Set aivt.test.uia.capture-previous-window=true with a visible external Continue-button fixture.",
            System.getProperty("aivt.test.uia.capture-previous-window")?.equals("true", ignoreCase = true) == true
        )
        assumeTrue("This test requires Windows.", System.getProperty("os.name").startsWith("Windows", ignoreCase = true))

        val capture = CaptureBridge.capturePreviousWindow()
        assertTrue(capture is CaptureBridge.Result.Success, "Window capture failed: $capture")
        capture as CaptureBridge.Result.Success
        val target = assertNotNull(capture.capturedWindow, "Capture bridge omitted the actual capture HWND and bounds.")
        val screenshotFile = java.io.File(capture.pngPath)
        val screenshot = ImageIO.read(screenshotFile) ?: error("Captured PNG was invalid.")
        try {
            val bounds = target.bounds
            val request = GroundingRequest(
                screenshot = screenshot,
                targetDescription = "Continue",
                mode = GroundingMode.UIA_ONLY,
                applicationContext = ApplicationContext(
                    target.processName,
                    target.processName,
                    pid = target.processId,
                    windowBounds = bounds,
                    windowHandle = target.windowHandle
                ),
                selectedRegionOnDesktop = bounds,
                screenToScreenshot = ScreenToScreenshotTransform(
                    bounds.x.toDouble(),
                    bounds.y.toDouble(),
                    screenshot.width.toDouble() / bounds.width,
                    screenshot.height.toDouble() / bounds.height
                )
            )
            val result = ExistingUiaGroundingProvider(WindowsUiAutomationPerceptionEngine()).collect(request)
            val button = result.observations.firstOrNull { it.observedText.equals("Continue", ignoreCase = true) }

            assertEquals(GroundingProviderStatus.SUCCESS, result.status, "UIA diagnostics: ${result.metadata}")
            assertNotNull(button, "UIA missed the button in the exact window captured by WGC: ${result.metadata}")
            assertTrue(kotlin.math.abs(button.box!!.x - 278.0) <= 3.0, "Unexpected mapped button bounds: ${button.box}")
            assertTrue(kotlin.math.abs(button.box.y - 226.0) <= 3.0, "Unexpected mapped button bounds: ${button.box}")
            assertEquals(target.windowHandle.toString(), result.metadata["windowHandle"])
            assertEquals("Continue", button.observedText)
        } finally {
            screenshot.flush()
            Files.deleteIfExists(screenshotFile.toPath())
        }
    }

    @Test
    fun controlledVisibleButtonUsesProductionUiaQueryAndScreenTransform() = runBlocking {
        val hwnd = System.getProperty("aivt.test.uia.hwnd")?.toLongOrNull()
        val region = System.getProperty("aivt.test.uia.region")
            ?.split(',')
            ?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.size == 4 }
        assumeTrue("Set aivt.test.uia.hwnd and aivt.test.uia.region to run the live UIA integration test.", hwnd != null && region != null)

        val desktopBounds = Rectangle(region!![0], region[1], region[2], region[3])
        val screenshot = BufferedImage(desktopBounds.width, desktopBounds.height, BufferedImage.TYPE_INT_RGB)
        try {
            val request = GroundingRequest(
                screenshot = screenshot,
                targetDescription = "Continue",
                mode = GroundingMode.UIA_ONLY,
                applicationContext = ApplicationContext(
                    applicationName = "AIVT UIA Probe",
                    processName = "powershell.exe",
                    windowBounds = desktopBounds,
                    windowHandle = hwnd!!
                ),
                selectedRegionOnDesktop = desktopBounds,
                screenToScreenshot = ScreenToScreenshotTransform(
                    desktopBounds.x.toDouble(),
                    desktopBounds.y.toDouble(),
                    screenshot.width.toDouble() / desktopBounds.width,
                    screenshot.height.toDouble() / desktopBounds.height
                )
            )
            val result = ExistingUiaGroundingProvider(WindowsUiAutomationPerceptionEngine()).collect(request)

            assertEquals(GroundingProviderStatus.SUCCESS, result.status, "UIA diagnostics: ${result.metadata}")
            val button = result.observations.firstOrNull { it.observedText.equals("Continue", ignoreCase = true) }
            assertNotNull(button, "UIA did not return the controlled button: ${result.metadata}")
            assertEquals(GroundingBox(278.0, 226.0, 180.0, 64.0), button.box)
            assertTrue(result.metadata["candidateCount"].orEmpty().toInt() >= 1)
            assertFalse(result.metadata["candidateBounds"].orEmpty().contains("50032:"), "window root must not be treated as a target candidate")
            assertEquals("${desktopBounds.x},${desktopBounds.y},${desktopBounds.width},${desktopBounds.height}", result.metadata["selectedRegion"])
        } finally {
            screenshot.flush()
        }
    }
}
