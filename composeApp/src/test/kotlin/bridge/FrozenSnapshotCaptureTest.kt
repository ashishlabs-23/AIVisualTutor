package bridge

import context.RecordingContextLogger
import selection.FrozenMonitorSnapshot
import selection.FrozenScreenSnapshot
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FrozenSnapshotCaptureTest {
    private fun solid(width: Int, height: Int, color: Int) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { image ->
        val g = image.createGraphics(); try { g.color = java.awt.Color(color, true); g.fillRect(0,0,width,height) } finally { g.dispose() }
    }

    @Test fun scalesPreserveSelectedPhysicalPixelSize() {
        for (scale in listOf(1.0, 1.25, 1.5, 2.0)) {
            val logical = Rectangle(-40, 10, 100, 80)
            val tile = FrozenMonitorSnapshot(logical, solid((100*scale).toInt(), (80*scale).toInt(), 0xFF22AA44.toInt()), scale, scale)
            val snapshot = FrozenScreenSnapshot(solid(100,80,0), logical, 1.0,1.0,listOf(tile))
            val crop = FrozenSnapshotRegionCaptureService(RecordingContextLogger()).crop(snapshot, Rectangle(-20,20,20,16))
            assertEquals((20*scale).toInt(), crop.width)
            assertEquals((16*scale).toInt(), crop.height)
        }
    }

    @Test fun negativeOriginMixedDpiAndSpanningSelectionMapPerMonitor() {
        val leftBounds = Rectangle(-100, 0, 100, 100)
        val rightBounds = Rectangle(0, 0, 100, 100)
        val left = FrozenMonitorSnapshot(leftBounds, solid(125,125,0xFFFF0000.toInt()),1.25,1.25)
        val right = FrozenMonitorSnapshot(rightBounds, solid(150,150,0xFF0000FF.toInt()),1.5,1.5)
        val snapshot = FrozenScreenSnapshot(solid(200,100,0), Rectangle(-100,0,200,100),1.0,1.0,listOf(left,right))
        val crop = FrozenSnapshotRegionCaptureService(RecordingContextLogger()).crop(snapshot, Rectangle(-100,0,200,100))
        assertEquals(300, crop.width)
        assertEquals(150, crop.height)
        assertEquals(0xFFFF0000.toInt(), crop.getRGB(40,40))
        assertEquals(0xFF0000FF.toInt(), crop.getRGB(230,40))
        val clamped = FrozenSnapshotRegionCaptureService(RecordingContextLogger()).crop(snapshot, Rectangle(-130,-10,160,60))
        assertEquals(195, clamped.width) // 130 logical pixels at the highest intersecting scale (1.5)
        assertEquals(75, clamped.height)
    }

    @Test fun negativeXAndYOriginMapsAcrossVerticallyArrangedDisplays() {
        val upperBounds=Rectangle(-50,-30,50,30)
        val lowerBounds=Rectangle(0,0,50,50)
        val upper=FrozenMonitorSnapshot(upperBounds,solid(100,60,0xFF00AA00.toInt()),2.0,2.0)
        val lower=FrozenMonitorSnapshot(lowerBounds,solid(50,50,0xFFAA0000.toInt()),1.0,1.0)
        val snapshot=FrozenScreenSnapshot(solid(100,80,0),Rectangle(-50,-30,100,80),1.0,1.0,listOf(upper,lower))
        val result=FrozenSnapshotRegionCaptureService(RecordingContextLogger()).crop(snapshot,Rectangle(-20,-10,40,30))
        assertEquals(80,result.width)
        assertEquals(60,result.height)
        assertEquals(0xFF00AA00.toInt(),result.getRGB(10,10))
        assertEquals(0xFFAA0000.toInt(),result.getRGB(60,40))
    }

    @Test fun tinySelectionsAreRejectedAndPreviewFilesCanBeDeleted() {
        val bounds = Rectangle(0,0,20,20)
        val snap = FrozenScreenSnapshot(solid(20,20,0),bounds,1.0,1.0,listOf(FrozenMonitorSnapshot(bounds,solid(20,20,0),1.0,1.0)))
        val service = FrozenSnapshotRegionCaptureService(RecordingContextLogger())
        assertFailsWith<context.SelectionTooSmallException> { service.crop(snap, Rectangle(0,0,8,8)) }
        val image = service.crop(snap, Rectangle(0,0,12,10))
        val path = service.persistPreview(image)
        assertTrue(Files.isRegularFile(path))
        Files.deleteIfExists(path)
        assertTrue(!Files.exists(path))
    }
}
