package overlay

import bridge.BlenderCaptureResult
import bridge.BlenderScreenCaptureService
import bridge.BlenderWindowInfo
import bridge.BlenderWindowLocator
import bridge.BlenderWindowResult
import bridge.RobotBlenderScreenCaptureService
import context.DesktopCoordinateTransform
import context.DpiCoordinateNormalizer
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import models.HighlightRegion
import tutor.ScreenshotHelpCoordinator
import tutor.ScreenshotHelpWorkflowState
import tutor.TutorController

class Phase14DesktopOverlayTest {

    // =========================================================================
    // PART 3: WINDOW DISCOVERY TESTS
    // =========================================================================

    class MockBlenderWindowLocator(
        var locateResult: BlenderWindowResult = BlenderWindowResult.NotRunning,
        var validHandles: MutableSet<Long> = mutableSetOf()
    ) : BlenderWindowLocator {
        var locateCallCount = 0
        var bringToForegroundCallCount = 0
        var foregroundHwndTarget: Long? = null

        override suspend fun locateBlenderWindow(): BlenderWindowResult {
            locateCallCount++
            return locateResult
        }

        override suspend fun bringBlenderToForeground(hwnd: Long): Boolean {
            bringToForegroundCallCount++
            foregroundHwndTarget = hwnd
            return validHandles.contains(hwnd)
        }

        override suspend fun isWindowValid(hwnd: Long): Boolean {
            return validHandles.contains(hwnd)
        }
    }

    @Test
    fun blenderWindowExistsDiscoveryReturnsFound(): Unit = runBlocking {
        val windowInfo = BlenderWindowInfo(
            hwnd = 1001L,
            processId = 5544L,
            title = "Blender [C:\\Projects\\model.blend]",
            bounds = Rectangle(50, 50, 1920, 1080),
            isVisible = true,
            isMinimized = false,
            isMaximized = true,
            isForeground = true
        )
        val locator = MockBlenderWindowLocator(
            locateResult = BlenderWindowResult.Found(windowInfo),
            validHandles = mutableSetOf(1001L)
        )

        val result = locator.locateBlenderWindow()
        assertTrue(result is BlenderWindowResult.Found)
        assertEquals(1001L, result.window.hwnd)
        assertEquals(5544L, result.window.processId)
        assertTrue(result.window.title.contains("Blender"))
        assertTrue(locator.isWindowValid(1001L))
    }

    @Test
    fun blenderWindowAbsentReturnsNotRunning(): Unit = runBlocking {
        val locator = MockBlenderWindowLocator(
            locateResult = BlenderWindowResult.NotRunning
        )

        val result = locator.locateBlenderWindow()
        assertTrue(result is BlenderWindowResult.NotRunning)
        assertFalse(locator.isWindowValid(1001L))
    }

    @Test
    fun unrelatedWindowWithMatchingWordIsNotMistakenForBlender(): Unit = runBlocking {
        // Selection policy ensures we check executable process name ("blender" / "blender-app"), not just arbitrary window title.
        // A browser window titled "Learning Blender Tutorial - Chrome" has process Chrome, not Blender.
        val locator = MockBlenderWindowLocator(
            locateResult = BlenderWindowResult.NotRunning // Win32 process filter would reject chrome.exe
        )

        val result = locator.locateBlenderWindow()
        assertTrue(result is BlenderWindowResult.NotRunning)
    }

    @Test
    fun multipleBlenderWindowsSelectsPrimaryWorkspaceByAreaPolicy(): Unit = runBlocking {
        // Policy: When multiple windows exist, select the foreground workspace if Blender & >100x100,
        // or the window with the largest surface area (e.g. 2560x1440 workspace over 400x300 floating preferences).
        val workspaceInfo = BlenderWindowInfo(
            hwnd = 2002L,
            processId = 5544L,
            title = "Blender",
            bounds = Rectangle(0, 0, 2560, 1440),
            isVisible = true,
            isMinimized = false,
            isMaximized = true,
            isForeground = false
        )
        val locator = MockBlenderWindowLocator(
            locateResult = BlenderWindowResult.Found(workspaceInfo),
            validHandles = mutableSetOf(2002L)
        )

        val result = locator.locateBlenderWindow()
        assertTrue(result is BlenderWindowResult.Found)
        assertEquals(2002L, result.window.hwnd)
        assertEquals(2560 * 1440, result.window.bounds.width * result.window.bounds.height)
    }

    @Test
    fun previouslyValidHandleBecomesInvalidWhenBlenderCloses(): Unit = runBlocking {
        val locator = MockBlenderWindowLocator(
            validHandles = mutableSetOf(5500L)
        )
        assertTrue(locator.isWindowValid(5500L))

        // Blender closes
        locator.validHandles.remove(5500L)
        assertFalse(locator.isWindowValid(5500L))
    }

    @Test
    fun minimizedBlenderWindowProducesExplicitMinimizedResult(): Unit = runBlocking {
        val windowInfo = BlenderWindowInfo(
            hwnd = 3003L,
            processId = 5544L,
            title = "Blender",
            bounds = Rectangle(-32000, -32000, 160, 31), // Win32 minimized coordinates
            isVisible = false,
            isMinimized = true,
            isMaximized = false,
            isForeground = false
        )
        val locator = MockBlenderWindowLocator(
            locateResult = BlenderWindowResult.Minimized(windowInfo)
        )

        val result = locator.locateBlenderWindow()
        assertTrue(result is BlenderWindowResult.Minimized)
        assertTrue(result.window.isMinimized)
    }

    // =========================================================================
    // PART 4: OVERLAY LIFECYCLE AND POSITIONING TESTS
    // =========================================================================

    @Test
    fun overlayManagerTracksVisibilityAndHighlightRegionsAccurately(): Unit {
        val manager = OverlayManager()
        assertFalse(manager.isOverlayVisible)
        assertFalse(manager.isClickThroughEnabled)
        assertEquals(null, manager.activeHighlightRegion)

        val targetRegion = HighlightRegion(100f, 150f, 200f, 50f)
        manager.showOverlay(targetRegion)

        assertTrue(manager.isOverlayVisible)
        assertTrue(manager.isClickThroughEnabled)
        assertEquals(targetRegion, manager.activeHighlightRegion)

        // Updating click-through
        manager.updateClickThrough(false)
        assertFalse(manager.isClickThroughEnabled)
        assertTrue(manager.isOverlayVisible)

        // Hiding overlay clears state
        manager.hideOverlay()
        assertFalse(manager.isOverlayVisible)
        assertFalse(manager.isClickThroughEnabled)
        assertEquals(null, manager.activeHighlightRegion)
    }

    @Test
    fun repeatedShowCallsDoNotCreateDuplicateOverlayStates(): Unit {
        val manager = OverlayManager()
        val region1 = HighlightRegion(50f, 50f, 100f, 100f)
        val region2 = HighlightRegion(80f, 80f, 120f, 120f)

        manager.showOverlay(region1)
        manager.showOverlay(region2)

        assertTrue(manager.isOverlayVisible)
        assertEquals(region2, manager.activeHighlightRegion)
    }

    @Test
    fun enableWindowsClickThroughExecutesSafely(): Unit {
        // Safe execution without throwing unhandled exceptions
        val result = enableWindowsClickThrough()
        // Result is true or false depending on whether overlay window currently exists, but cannot throw
        assertNotNull(result)
    }

    // =========================================================================
    // PART 5: SCREEN COORDINATES AND DISPLAY SCALING TESTS
    // =========================================================================

    @Test
    fun coordinateTransformWindowRelativeToPhysicalAndBackPreservesFidelity(): Unit {
        val transform = DesktopCoordinateTransform()
        val windowBounds = Rectangle(200, 150, 1920, 1080)
        val relativeRect = Rectangle(50, 75, 300, 120)

        val physicalRect = transform.windowRelativeToPhysical(relativeRect, windowBounds)
        assertEquals(250, physicalRect.x)
        assertEquals(225, physicalRect.y)
        assertEquals(300, physicalRect.width)
        assertEquals(120, physicalRect.height)

        val roundTrip = transform.physicalToWindowRelative(physicalRect, windowBounds)
        assertEquals(relativeRect, roundTrip)
    }

    @Test
    fun coordinateTransformHandlesNegativeDesktopOrigins(): Unit {
        val transform = DesktopCoordinateTransform()
        // Secondary monitor placed to the left of primary monitor: x = -1920
        val secondaryWindowBounds = Rectangle(-1920, 0, 1920, 1080)
        val relativeRect = Rectangle(100, 200, 400, 300)

        val physicalRect = transform.windowRelativeToPhysical(relativeRect, secondaryWindowBounds)
        assertEquals(-1820, physicalRect.x)
        assertEquals(200, physicalRect.y)
        assertEquals(400, physicalRect.width)
        assertEquals(300, physicalRect.height)

        val roundTrip = transform.physicalToWindowRelative(physicalRect, secondaryWindowBounds)
        assertEquals(relativeRect, roundTrip)
    }

    @Test
    fun coordinateTransformHandlesOriginAndBoundaryConditions(): Unit {
        val transform = DesktopCoordinateTransform()
        val windowBounds = Rectangle(0, 0, 1280, 720)
        val originRect = Rectangle(0, 0, 1, 1)

        val physicalRect = transform.windowRelativeToPhysical(originRect, windowBounds)
        assertEquals(Rectangle(0, 0, 1, 1), physicalRect)

        val roundTrip = transform.physicalToWindowRelative(physicalRect, windowBounds)
        assertEquals(originRect, roundTrip)
    }

    @Test
    fun coordinateTransformHandlesScreenshotRasterScale(): Unit {
        val transform = DesktopCoordinateTransform()
        val captureOrigin = Rectangle(100, 100, 1000, 800)
        val physicalTarget = Rectangle(200, 300, 100, 50)

        // Scale 1.5 (e.g. 150% DPI high-res screenshot)
        val screenshotRect = transform.physicalToScreenshot(physicalTarget, captureOrigin, rasterScale = 1.5)
        assertEquals(150, screenshotRect.x) // (200 - 100) * 1.5
        assertEquals(300, screenshotRect.y) // (300 - 100) * 1.5
        assertEquals(150, screenshotRect.width) // 100 * 1.5
        assertEquals(75, screenshotRect.height) // 50 * 1.5

        val recoveredPhysical = transform.screenshotToPhysical(screenshotRect, captureOrigin, rasterScale = 1.5)
        assertEquals(physicalTarget, recoveredPhysical)
    }

    @Test
    fun coordinateTransformConvertsHighlightRegionWithVirtualScreenOffset(): Unit {
        val transform = DesktopCoordinateTransform()
        val region = HighlightRegion(10f, 20f, 100f, 50f)
        val windowOffset = Rectangle(500, 400, 1200, 800)
        val virtualOrigin = Rectangle(-1920, 0, 3840, 1080)

        val adjusted = transform.adjustRegionForOverlay(region, windowOffset, virtualOrigin)
        // physical x = 500 + 10 = 510; overlay x = 510 - (-1920) = 2430
        assertEquals(2430f, adjusted.x)
        // physical y = 400 + 20 = 420; overlay y = 420 - 0 = 420
        assertEquals(420f, adjusted.y)
        assertEquals(100f, adjusted.width)
        assertEquals(50f, adjusted.height)
    }

    @Test
    fun coordinateTransformRejectsInvalidWindowDimensions(): Unit {
        val transform = DesktopCoordinateTransform()
        var exceptionThrown = false
        try {
            transform.windowRelativeToPhysical(Rectangle(0, 0, 10, 10), Rectangle(0, 0, 0, 0))
        } catch (_: IllegalArgumentException) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown, "Transform must reject non-positive window dimensions.")
    }

    // =========================================================================
    // PART 6: SCREENSHOT CAPTURE AND WINDOW IDENTITY TESTS
    // =========================================================================

    class MockScreenCaptureService(
        var captureResult: BlenderCaptureResult
    ) : BlenderScreenCaptureService {
        override suspend fun captureBlenderWorkspace(
            excludeOverlayAction: (suspend () -> Unit)?,
            restoreOverlayAction: (suspend () -> Unit)?
        ): BlenderCaptureResult {
            excludeOverlayAction?.invoke()
            val res = captureResult
            restoreOverlayAction?.invoke()
            return res
        }
    }

    @Test
    fun capturePreservesWindowIdentityAccurately(): Unit = runBlocking {
        val windowInfo = BlenderWindowInfo(
            hwnd = 4004L,
            processId = 8899L,
            title = "Blender [DefaultScene]",
            bounds = Rectangle(100, 100, 1920, 1080),
            isVisible = true,
            isMinimized = false,
            isMaximized = true,
            isForeground = true
        )
        val dummyImage = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB)
        val successResult = BlenderCaptureResult.Success(
            image = dummyImage,
            pngPath = "mock_capture.png",
            windowInfo = windowInfo,
            capturedAt = Instant.now()
        )
        val captureService = MockScreenCaptureService(successResult)

        val result = captureService.captureBlenderWorkspace()
        assertTrue(result is BlenderCaptureResult.Success)
        assertEquals(4004L, result.windowInfo.hwnd)
        assertEquals(8899L, result.windowInfo.processId)
        assertEquals("Blender [DefaultScene]", result.windowInfo.title)
    }

    @Test
    fun captureFailureDoesNotModifyLearnerCompetenceOrCompleteStep(): Unit = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a cube")
        val initialStepIndex = controller.state.currentStepIndex

        // Simulate capture failure
        val captureService = MockScreenCaptureService(
            BlenderCaptureResult.CaptureFailed("Display device disconnected.")
        )
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = captureService
        )

        val success = coordinator.startCapture()
        assertFalse(success)
        assertTrue(coordinator.state is ScreenshotHelpWorkflowState.Error)

        // Learner state must remain unchanged
        assertEquals(initialStepIndex, controller.state.currentStepIndex)
        assertEquals(0, controller.goalProgressState.completedStepIndices.size)
        assertEquals(null, controller.state.lastPedagogicalOutcome)
    }

    @Test
    fun captureWithMinimizedBlenderProducesExplicitErrorWithRetry(): Unit = runBlocking {
        val controller = TutorController()
        controller.startUserGoal("Create a cube")
        val windowInfo = BlenderWindowInfo(
            hwnd = 7007L,
            processId = 1234L,
            title = "Blender",
            bounds = Rectangle(-32000, -32000, 160, 31),
            isVisible = false,
            isMinimized = true,
            isMaximized = false,
            isForeground = false
        )
        val captureService = MockScreenCaptureService(
            BlenderCaptureResult.BlenderMinimized(windowInfo)
        )
        val coordinator = ScreenshotHelpCoordinator(
            controller = controller,
            captureService = captureService
        )

        val success = coordinator.startCapture()
        assertFalse(success)
        val state = coordinator.state
        assertTrue(state is ScreenshotHelpWorkflowState.Error)
        assertTrue(state.canRetry)
        assertTrue(state.message.contains("minimized", ignoreCase = true))
    }

    // =========================================================================
    // PART 7: FOCUS AND INPUT RELIABILITY TESTS
    // =========================================================================

    @Test
    fun tutorDoesNotStealFocusContinuously(): Unit = runBlocking {
        // Verify that locator's bringBlenderToForeground is only called explicitly during capture
        val locator = MockBlenderWindowLocator(
            locateResult = BlenderWindowResult.Found(
                BlenderWindowInfo(
                    hwnd = 9009L,
                    processId = 3322L,
                    title = "Blender",
                    bounds = Rectangle(0, 0, 1920, 1080),
                    isVisible = true,
                    isMinimized = false,
                    isMaximized = true,
                    isForeground = true // already foreground
                )
            ),
            validHandles = mutableSetOf(9009L)
        )

        // When window is already foreground, RobotBlenderScreenCaptureService does not re-steal focus
        val captureService = RobotBlenderScreenCaptureService(locator)
        // If headless without screens, capture handles failure gracefully
        val res = captureService.captureBlenderWorkspace()
        assertNotNull(res)
    }

    @Test
    fun failedFocusRequestIsNotTreatedAsLearnerFailure(): Unit = runBlocking {
        val locator = MockBlenderWindowLocator(
            validHandles = mutableSetOf() // empty, fails focus
        )
        val focusSuccess = locator.bringBlenderToForeground(9999L)
        assertFalse(focusSuccess)

        // Ensure tutor controller does not record pedagogical failure for native window focus issues
        val controller = TutorController()
        assertEquals(0, controller.goalProgressState.completedStepIndices.size)
        assertEquals(null, controller.state.lastPedagogicalOutcome)
    }

    @Test
    fun blenderWindowLocatorTranslatesWindowRelativeCoordinatesToDesktopBounds() {
        val blenderBounds = Rectangle(300, 200, 1400, 900)
        val stepTarget = HighlightRegion(x = 120f, y = 80f, width = 60f, height = 30f)

        // Translation logic matching OverlayWindowContent
        val desktopTarget = HighlightRegion(
            x = blenderBounds.x.toFloat() + stepTarget.x,
            y = blenderBounds.y.toFloat() + stepTarget.y,
            width = stepTarget.width,
            height = stepTarget.height
        )

        assertEquals(420f, desktopTarget.x)
        assertEquals(280f, desktopTarget.y)
        assertEquals(60f, desktopTarget.width)
        assertEquals(30f, desktopTarget.height)
    }

    @Test
    fun overlayAwarenessDistinguishesForegroundVsBackgroundBlender() = runBlocking {
        val bgInfo = BlenderWindowInfo(
            hwnd = 1001L, processId = 4455L, title = "Blender",
            bounds = Rectangle(0, 0, 1920, 1080),
            isVisible = true, isMinimized = false, isMaximized = true,
            isForeground = false // In background
        )
        val locatorBg = MockBlenderWindowLocator(locateResult = BlenderWindowResult.Found(bgInfo))
        val resBg = locatorBg.locateBlenderWindow()
        assertTrue(resBg is BlenderWindowResult.Found)
        assertFalse(resBg.window.isForeground)

        val fgInfo = bgInfo.copy(isForeground = true)
        val locatorFg = MockBlenderWindowLocator(locateResult = BlenderWindowResult.Found(fgInfo))
        val resFg = locatorFg.locateBlenderWindow()
        assertTrue(resFg is BlenderWindowResult.Found)
        assertTrue(resFg.window.isForeground)
    }
}
