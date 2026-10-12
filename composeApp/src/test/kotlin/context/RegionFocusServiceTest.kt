package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegionFocusServiceTest {

    private val service = RegionFocusService(
        contextualMarginPx = 100,
        minimumDimensionPx = 48
    )

    private fun createTestImage(w: Int = 1920, h: Int = 1080): BufferedImage {
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    }

    @Test
    fun `crops around target neighborhood with margin and calculates pixel reduction`() {
        val img = createTestImage(1920, 1080)
        // Target control at (500, 400) of size (60, 40)
        val target = Rectangle(500, 400, 60, 40)

        val crop = service.createFocusedCrop(
            screenshot = img,
            targetBoundsOnScreen = target
        )

        assertEquals(RegionFocusSource.TARGET_NEIGHBORHOOD, crop.source)
        // With 100px margin: expected width = 60 + 200 = 260, height = 40 + 200 = 240
        assertEquals(260, crop.cropDimensions.width)
        assertEquals(240, crop.cropDimensions.height)
        assertTrue(crop.estimatedPixelReductionRatio > 0.90, "Expected >90% pixel/token reduction over 1080p")
    }

    @Test
    fun `crop coordinate transform round-trips correctly`() {
        val img = createTestImage(1000, 1000)
        val target = Rectangle(400, 400, 100, 100)

        val crop = service.createFocusedCrop(
            screenshot = img,
            targetBoundsOnScreen = target
        )

        val transform = crop.transform
        // A point inside the crop at (50, 50)
        val (screenX, screenY) = transform.cropToScreen(50.0, 50.0)
        val (roundTripX, roundTripY) = transform.screenToCrop(screenX, screenY)

        assertEquals(50.0, roundTripX, 0.001)
        assertEquals(50.0, roundTripY, 0.001)
    }

    @Test
    fun `crops to active application window when target bounds are null`() {
        val img = createTestImage(1920, 1080)
        val appContext = ApplicationContext(
            applicationName = "Blender",
            processName = "blender.exe",
            windowBounds = Rectangle(100, 100, 1200, 800)
        )

        val crop = service.createFocusedCrop(
            screenshot = img,
            appContext = appContext
        )

        assertEquals(RegionFocusSource.APPLICATION_WINDOW, crop.source)
        assertEquals(1200, crop.cropDimensions.width)
        assertEquals(800, crop.cropDimensions.height)
        assertEquals(100, crop.cropRectOnScreen.x)
        assertEquals(100, crop.cropRectOnScreen.y)
    }

    @Test
    fun `crops to panel quadrant when area hint specifies toolbar`() {
        val img = createTestImage(1000, 1000)

        val crop = service.createFocusedCrop(
            screenshot = img,
            targetAreaDescription = "Left Toolbar"
        )

        assertEquals(RegionFocusSource.PANEL_QUADRANT, crop.source)
        assertEquals(350, crop.cropDimensions.width) // 35% of 1000
        assertEquals(1000, crop.cropDimensions.height)
    }

    @Test
    fun `safely clamps crop when target is adjacent to screen boundary`() {
        val img = createTestImage(500, 500)
        // Target at extreme top-left (10, 10)
        val target = Rectangle(10, 10, 30, 30)

        val crop = service.createFocusedCrop(
            screenshot = img,
            targetBoundsOnScreen = target
        )

        assertEquals(RegionFocusSource.TARGET_NEIGHBORHOOD, crop.source)
        assertEquals(0, crop.cropRectOnScreen.x)
        assertEquals(0, crop.cropRectOnScreen.y)
        assertTrue(crop.cropDimensions.width >= 48)
        assertTrue(crop.cropDimensions.height >= 48)
    }

    @Test
    fun `falls back to full screenshot when no localized bounds are provided`() {
        val img = createTestImage(800, 600)

        val crop = service.createFocusedCrop(
            screenshot = img
        )

        assertEquals(RegionFocusSource.FULL_SCREEN_FALLBACK, crop.source)
        assertEquals(800, crop.cropDimensions.width)
        assertEquals(600, crop.cropDimensions.height)
        assertEquals(0.0, crop.estimatedPixelReductionRatio)
    }
}
