package context

import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisualFrameDiffVerifierTest {

    private val verifier = VisualFrameDiffVerifier(
        colorDistanceThreshold = 25.0,
        minimumChangeRatio = 0.0001,
        minPixelNoiseCluster = 16
    )

    private fun createSolidImage(width: Int, height: Int, color: Color): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }

    @Test
    fun `identical images produce NO_CHANGE with zero ratio`() {
        val base = createSolidImage(200, 200, Color.LIGHT_GRAY)
        val post = createSolidImage(200, 200, Color.LIGHT_GRAY)

        val result = verifier.compare(base, post)

        assertEquals(VisualDiffStatus.NO_CHANGE, result.status)
        assertEquals(0.0, result.changedRatio)
        assertEquals(0, result.changedPixelCount)
        assertNull(result.changedBoundingBox)
        assertFalse(result.hasVisualChange)
    }

    @Test
    fun `localized rectangle change produces MEASURABLE_CHANGE with accurate bounding box`() {
        val base = createSolidImage(300, 300, Color.WHITE)
        val post = createSolidImage(300, 300, Color.WHITE)

        // Draw a 50x50 black square from (100, 100) to (149, 149)
        val g = post.createGraphics()
        g.color = Color.BLACK
        g.fillRect(100, 100, 50, 50)
        g.dispose()

        val result = verifier.compare(base, post)

        assertEquals(VisualDiffStatus.MEASURABLE_CHANGE, result.status)
        assertTrue(result.hasVisualChange)
        assertEquals(2500, result.changedPixelCount)
        val expectedRatio = 2500.0 / (300.0 * 300.0)
        assertEquals(expectedRatio, result.changedRatio, 0.001)

        val bbox = result.changedBoundingBox
        assertNotNull(bbox)
        assertEquals(100, bbox.x)
        assertEquals(100, bbox.y)
        assertEquals(50, bbox.width)
        assertEquals(50, bbox.height)
    }

    @Test
    fun `changes outside region of interest are ignored`() {
        val base = createSolidImage(400, 400, Color.WHITE)
        val post = createSolidImage(400, 400, Color.WHITE)

        // Draw a change at top-left (10, 10) to (40, 40)
        val g = post.createGraphics()
        g.color = Color.RED
        g.fillRect(10, 10, 30, 30)
        g.dispose()

        // Restrict ROI to bottom-right quadrant (200, 200, 200, 200)
        val roi = Rectangle(200, 200, 200, 200)
        val result = verifier.compare(base, post, regionOfInterest = roi)

        assertEquals(VisualDiffStatus.NO_CHANGE, result.status)
        assertEquals(0, result.changedPixelCount)
        assertNull(result.changedBoundingBox)
    }

    @Test
    fun `dimension mismatch reports DIMENSIONS_CHANGED`() {
        val base = createSolidImage(200, 200, Color.WHITE)
        val post = createSolidImage(250, 200, Color.WHITE)

        val result = verifier.compare(base, post)

        assertEquals(VisualDiffStatus.DIMENSIONS_CHANGED, result.status)
        assertTrue(result.warnings.any { it.contains("Dimension mismatch") })
    }

    @Test
    fun `null images report INVALID_IMAGES gracefully`() {
        val result = verifier.compare(null, null)

        assertEquals(VisualDiffStatus.INVALID_IMAGES, result.status)
        assertFalse(result.hasVisualChange)
    }

    @Test
    fun `sub-threshold noise below minPixelNoiseCluster is filtered as NO_CHANGE`() {
        val base = createSolidImage(200, 200, Color.WHITE)
        val post = createSolidImage(200, 200, Color.WHITE)

        // Change only 4 pixels (below minPixelNoiseCluster of 16)
        post.setRGB(10, 10, Color.BLUE.rgb)
        post.setRGB(11, 10, Color.BLUE.rgb)
        post.setRGB(10, 11, Color.BLUE.rgb)
        post.setRGB(11, 11, Color.BLUE.rgb)

        val result = verifier.compare(base, post)

        assertEquals(VisualDiffStatus.NO_CHANGE, result.status)
        assertNull(result.changedBoundingBox)
    }
}
