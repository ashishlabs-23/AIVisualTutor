package bridge

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BlenderScreenCaptureServiceTest {

    @Test
    fun `capture returns BlenderNotRunning when locator reports NotRunning`() = runBlocking {
        val mockLocator = object : BlenderWindowLocator {
            override suspend fun locateBlenderWindow(): BlenderWindowResult = BlenderWindowResult.NotRunning
            override suspend fun bringBlenderToForeground(hwnd: Long): Boolean = false
        }

        val service = RobotBlenderScreenCaptureService(mockLocator)
        val result = service.captureBlenderWorkspace()

        assertTrue(result is BlenderCaptureResult.BlenderNotRunning)
        assertTrue((result as BlenderCaptureResult.BlenderNotRunning).message.contains("not running"))
    }

    @Test
    fun `capture returns BlenderMinimized when locator reports Minimized`() = runBlocking {
        val mockInfo = BlenderWindowInfo(
            hwnd = 100L,
            processId = 200L,
            title = "Blender",
            bounds = Rectangle(0, 0, 1920, 1080),
            isVisible = true,
            isMinimized = true,
            isMaximized = false,
            isForeground = false
        )
        val mockLocator = object : BlenderWindowLocator {
            override suspend fun locateBlenderWindow(): BlenderWindowResult = BlenderWindowResult.Minimized(mockInfo)
            override suspend fun bringBlenderToForeground(hwnd: Long): Boolean = false
        }

        val service = RobotBlenderScreenCaptureService(mockLocator)
        val result = service.captureBlenderWorkspace()

        assertTrue(result is BlenderCaptureResult.BlenderMinimized)
        assertTrue((result as BlenderCaptureResult.BlenderMinimized).message.contains("minimized"))
    }

    @Test
    fun `capture returns InvalidImage when window has tiny or negative dimensions`() = runBlocking {
        val mockInfo = BlenderWindowInfo(
            hwnd = 100L,
            processId = 200L,
            title = "Blender",
            bounds = Rectangle(0, 0, 5, 5),
            isVisible = true,
            isMinimized = false,
            isMaximized = false,
            isForeground = true
        )
        val mockLocator = object : BlenderWindowLocator {
            override suspend fun locateBlenderWindow(): BlenderWindowResult = BlenderWindowResult.Found(mockInfo)
            override suspend fun bringBlenderToForeground(hwnd: Long): Boolean = true
        }

        val service = RobotBlenderScreenCaptureService(mockLocator)
        val result = service.captureBlenderWorkspace()

        assertTrue(result is BlenderCaptureResult.InvalidImage)
        assertTrue((result as BlenderCaptureResult.InvalidImage).diagnostic.contains("too small or invalid"))
    }

    @Test
    fun `capture returns CaptureFailed when locator returns Error`() = runBlocking {
        val mockLocator = object : BlenderWindowLocator {
            override suspend fun locateBlenderWindow(): BlenderWindowResult = BlenderWindowResult.Error("Desktop window query timed out")
            override suspend fun bringBlenderToForeground(hwnd: Long): Boolean = false
        }

        val service = RobotBlenderScreenCaptureService(mockLocator)
        val result = service.captureBlenderWorkspace()

        assertTrue(result is BlenderCaptureResult.CaptureFailed)
        assertTrue((result as BlenderCaptureResult.CaptureFailed).diagnostic.contains("timed out"))
    }

    @Test
    fun `capture triggers overlay exclusion and restoration callbacks`() = runBlocking {
        val mockInfo = BlenderWindowInfo(
            hwnd = 100L,
            processId = 200L,
            title = "Blender",
            bounds = Rectangle(0, 0, 100, 100),
            isVisible = true,
            isMinimized = false,
            isMaximized = false,
            isForeground = true
        )
        val mockLocator = object : BlenderWindowLocator {
            override suspend fun locateBlenderWindow(): BlenderWindowResult = BlenderWindowResult.Found(mockInfo)
            override suspend fun bringBlenderToForeground(hwnd: Long): Boolean = true
        }

        var overlayExcluded = false
        var overlayRestored = false

        val service = RobotBlenderScreenCaptureService(mockLocator)
        service.captureBlenderWorkspace(
            excludeOverlayAction = { overlayExcluded = true },
            restoreOverlayAction = { overlayRestored = true }
        )

        assertTrue(overlayExcluded, "Overlay should be excluded before capture")
        assertTrue(overlayRestored, "Overlay should be restored after capture")
    }
}
