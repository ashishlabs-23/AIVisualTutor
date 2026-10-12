package context

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DpiCoordinateNormalizerTest {

    @Test
    fun `identity transform preserves coordinates when scale is 1_0`() {
        val monitor = MonitorGeometry(
            id = "PRIMARY",
            logicalBounds = Rectangle(0, 0, 1920, 1080),
            scaleX = 1.0,
            scaleY = 1.0
        )
        val normalizer = DpiCoordinateNormalizer { listOf(monitor) }

        val phys = Rectangle(100, 200, 300, 150)
        val log = normalizer.physicalToLogical(phys)
        assertEquals(phys, log)

        val roundTrip = normalizer.logicalToPhysical(log)
        assertEquals(phys, roundTrip)
    }

    @Test
    fun `converts physical to logical and back at 1_25 scale`() {
        // At 125% DPI: 1920x1080 physical is 1536x864 logical
        val monitor = MonitorGeometry(
            id = "SCALE_125",
            logicalBounds = Rectangle(0, 0, 1536, 864),
            scaleX = 1.25,
            scaleY = 1.25
        )
        val normalizer = DpiCoordinateNormalizer { listOf(monitor) }

        // A physical widget of 250x125 at physical (500, 250)
        val physical = Rectangle(500, 250, 250, 125)
        val logical = normalizer.physicalToLogical(physical)

        assertEquals(400, logical.x)
        assertEquals(200, logical.y)
        assertEquals(200, logical.width)
        assertEquals(100, logical.height)

        val roundTrip = normalizer.logicalToPhysical(logical)
        assertEquals(500, roundTrip.x)
        assertEquals(250, roundTrip.y)
        assertEquals(250, roundTrip.width)
        assertEquals(125, roundTrip.height)
    }

    @Test
    fun `converts physical to logical at 1_5 and 2_0 scale`() {
        val monitor150 = MonitorGeometry(
            id = "SCALE_150",
            logicalBounds = Rectangle(0, 0, 1280, 720),
            scaleX = 1.5,
            scaleY = 1.5
        )
        val normalizer150 = DpiCoordinateNormalizer { listOf(monitor150) }
        val physical150 = Rectangle(300, 150, 150, 90)
        val logical150 = normalizer150.physicalToLogical(physical150)
        assertEquals(200, logical150.x)
        assertEquals(100, logical150.y)
        assertEquals(100, logical150.width)
        assertEquals(60, logical150.height)

        val monitor200 = MonitorGeometry(
            id = "SCALE_200",
            logicalBounds = Rectangle(0, 0, 1920, 1080),
            scaleX = 2.0,
            scaleY = 2.0
        )
        val normalizer200 = DpiCoordinateNormalizer { listOf(monitor200) }
        val physical200 = Rectangle(400, 200, 200, 100)
        val logical200 = normalizer200.physicalToLogical(physical200)
        assertEquals(200, logical200.x)
        assertEquals(100, logical200.y)
        assertEquals(100, logical200.width)
        assertEquals(50, logical200.height)
    }

    @Test
    fun `handles secondary monitor with negative origin correctly`() {
        // Setup dual-monitor: Secondary on the left (-1920, 0, 1920, 1080) at 1.0x, Primary at (0, 0, 1536, 864) at 1.25x
        val secondary = MonitorGeometry(
            id = "LEFT_SECONDARY",
            logicalBounds = Rectangle(-1920, 0, 1920, 1080),
            scaleX = 1.0,
            scaleY = 1.0
        )
        val primary = MonitorGeometry(
            id = "PRIMARY_125",
            logicalBounds = Rectangle(0, 0, 1536, 864),
            scaleX = 1.25,
            scaleY = 1.25
        )
        val normalizer = DpiCoordinateNormalizer { listOf(secondary, primary) }

        // A target on the secondary monitor
        val targetOnSecondary = Rectangle(-1000, 300, 200, 100)
        val mappedSecondary = normalizer.physicalToLogical(targetOnSecondary)
        assertEquals(-1000, mappedSecondary.x)
        assertEquals(300, mappedSecondary.y)

        // A target on the primary monitor
        val targetOnPrimary = Rectangle(250, 125, 250, 125)
        val mappedPrimary = normalizer.physicalToLogical(targetOnPrimary)
        assertEquals(200, mappedPrimary.x)
        assertEquals(100, mappedPrimary.y)
    }

    @Test
    fun `clamps out of bounds rectangle to virtual desktop bounds`() {
        val monitor = MonitorGeometry(
            id = "PRIMARY",
            logicalBounds = Rectangle(0, 0, 1920, 1080),
            scaleX = 1.0,
            scaleY = 1.0
        )
        val normalizer = DpiCoordinateNormalizer { listOf(monitor) }

        // Rectangle partially hanging off right and bottom edge
        val hangingRect = Rectangle(1800, 1000, 300, 200)
        val clamped = normalizer.clampToVirtualDesktop(hangingRect, isPhysical = false)

        assertEquals(1800, clamped.x)
        assertEquals(1000, clamped.y)
        assertEquals(120, clamped.width)
        assertEquals(80, clamped.height)
        assertTrue(clamped.x + clamped.width <= 1920)
        assertTrue(clamped.y + clamped.height <= 1080)
    }
}
