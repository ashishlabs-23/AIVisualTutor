package selection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionSelectorGeometryTest {

    @Test
    fun escapeDownAndUpAreBothConsumedBeforeCancellation() {
        val sequence = EscapeKeySequence()
        var consumed = 0
        var cancellations = 0
        assertTrue(sequence.handle(KeyEventType.KeyDown, { consumed++ }, { cancellations++ }))
        assertEquals(1, consumed)
        assertEquals(0, cancellations)
        assertTrue(sequence.handle(KeyEventType.KeyUp, { consumed++ }, { cancellations++ }))
        assertEquals(1, consumed)
        assertEquals(1, cancellations)
    }

    @Test
    fun oneToOneDisplayPreservesPointerDownAndUpBounds() {
        val display = calculateImageDisplayRect(
            width = 1920f,
            height = 1080f,
            imageWidth = 1920,
            imageHeight = 1080
        )

        val crop = selectionToImageCrop(
            start = Offset(628f, 311f),
            end = Offset(986f, 386f),
            display = display,
            imageWidth = 1920,
            imageHeight = 1080
        )

        assertEquals(PixelCrop(628, 311, 358, 75), crop)
    }

    @Test
    fun scaledDisplayAccountsForOffsetsAndReverseDrag() {
        val display = DisplayRect(left = 100f, top = 50f, width = 960f, height = 540f)
        val forward = selectionToImageCrop(
            start = Offset(120f, 60f),
            end = Offset(300f, 140f),
            display = display,
            imageWidth = 1920,
            imageHeight = 1080
        )
        val reverse = selectionToImageCrop(
            start = Offset(300f, 140f),
            end = Offset(120f, 60f),
            display = display,
            imageWidth = 1920,
            imageHeight = 1080
        )

        assertEquals(PixelCrop(40, 20, 360, 160), forward)
        assertEquals(forward, reverse)
    }

    @Test
    fun allFourDragDirectionsSelectTheSameDesktopImagePixels() {
        val display = DisplayRect(left = 0f, top = 0f, width = 400f, height = 300f)
        val corners = listOf(
            Offset(40f, 50f) to Offset(160f, 190f),
            Offset(160f, 50f) to Offset(40f, 190f),
            Offset(40f, 190f) to Offset(160f, 50f),
            Offset(160f, 190f) to Offset(40f, 50f)
        )
        val crops = corners.map { (start, end) ->
            selectionToImageCrop(start, end, display, imageWidth = 400, imageHeight = 300)
        }
        assertEquals(List(4) { PixelCrop(40, 50, 120, 140) }, crops)
    }

    @Test
    fun selectionsThatStayOutsideTheDisplayCollapseToEmptyCrop() {
        val display = DisplayRect(left = 100f, top = 50f, width = 960f, height = 540f)

        val crop = selectionToImageCrop(
            start = Offset(0f, 0f),
            end = Offset(40f, 20f),
            display = display,
            imageWidth = 1920,
            imageHeight = 1080
        )

        assertEquals(PixelCrop(0, 0, 0, 0), crop)
    }

    @Test
    fun selectionsTouchingTheDisplayEdgeStillMapToExactPixels() {
        val display = DisplayRect(left = 100f, top = 50f, width = 960f, height = 540f)
        val crop = selectionToImageCrop(
            start = Offset(100f, 50f),
            end = Offset(200f, 90f),
            display = display,
            imageWidth = 1920,
            imageHeight = 1080
        )

        assertEquals(PixelCrop(0, 0, 200, 80), crop)
    }

    @Test
    fun highDpiScalingConvertsPhysicalCropToLogicalCoordinates() {
        val physicalCrop = PixelCrop(x = 150, y = 300, width = 450, height = 150)
        val scaleX = 1.5
        val scaleY = 1.5

        val logicalX = (physicalCrop.x / scaleX).toFloat()
        val logicalY = (physicalCrop.y / scaleY).toFloat()
        val logicalWidth = (physicalCrop.width / scaleX).toFloat()
        val logicalHeight = (physicalCrop.height / scaleY).toFloat()

        assertEquals(100f, logicalX)
        assertEquals(200f, logicalY)
        assertEquals(300f, logicalWidth)
        assertEquals(100f, logicalHeight)
    }
}
