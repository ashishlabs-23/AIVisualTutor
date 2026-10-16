package ui.avatar

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetGuidanceGeometryTest {

    private val anchor = ScreenPoint(500f, 500f)

    @Test
    fun `target directly above anchor produces upward angle and gaze`() {
        // Target center at (500, 200) -> dy = -300, dx = 0
        val target = ScreenRect(450f, 150f, 100f, 100f)
        val geometry = TargetGuidanceCalculator.calculateGuidance(anchor, target)

        assertTrue(geometry.isGuidanceUsable)
        assertEquals(GuidanceValidity.VALID, geometry.validity)
        assertEquals(0f, geometry.displacement!!.dx, 0.001f)
        assertEquals(-300f, geometry.displacement!!.dy, 0.001f)
        assertEquals(0f, geometry.displacement!!.normalizedX, 0.001f)
        assertEquals(-1f, geometry.displacement!!.normalizedY, 0.001f)
        assertEquals(-90f, geometry.displacement!!.angleDegrees, 0.01f)

        assertEquals(0f, geometry.gazeOffset.offsetX, 0.001f)
        assertTrue(geometry.gazeOffset.offsetY < 0f)
        assertFalse(geometry.pointingGesture!!.isPointingDown)
    }

    @Test
    fun `target directly below anchor produces downward angle and gaze`() {
        // Target center at (500, 800) -> dy = +300, dx = 0
        val target = ScreenRect(450f, 750f, 100f, 100f)
        val geometry = TargetGuidanceCalculator.calculateGuidance(anchor, target)

        assertTrue(geometry.isGuidanceUsable)
        assertEquals(GuidanceValidity.VALID, geometry.validity)
        assertEquals(0f, geometry.displacement!!.dx, 0.001f)
        assertEquals(300f, geometry.displacement!!.dy, 0.001f)
        assertEquals(0f, geometry.displacement!!.normalizedX, 0.001f)
        assertEquals(1f, geometry.displacement!!.normalizedY, 0.001f)
        assertEquals(90f, geometry.displacement!!.angleDegrees, 0.01f)

        assertEquals(0f, geometry.gazeOffset.offsetX, 0.001f)
        assertTrue(geometry.gazeOffset.offsetY > 0f)
        assertTrue(geometry.pointingGesture!!.isPointingDown)
    }

    @Test
    fun `target directly left of anchor produces leftward angle and gaze`() {
        // Target center at (200, 500) -> dx = -300, dy = 0
        val target = ScreenRect(150f, 450f, 100f, 100f)
        val geometry = TargetGuidanceCalculator.calculateGuidance(anchor, target)

        assertTrue(geometry.isGuidanceUsable)
        assertEquals(GuidanceValidity.VALID, geometry.validity)
        assertEquals(-300f, geometry.displacement!!.dx, 0.001f)
        assertEquals(0f, geometry.displacement!!.dy, 0.001f)
        assertEquals(-1f, geometry.displacement!!.normalizedX, 0.001f)
        assertEquals(0f, geometry.displacement!!.normalizedY, 0.001f)
        assertEquals(180f, abs(geometry.displacement!!.angleDegrees), 0.01f)

        assertTrue(geometry.gazeOffset.offsetX < 0f)
        assertEquals(0f, geometry.gazeOffset.offsetY, 0.001f)
        assertFalse(geometry.pointingGesture!!.isPointingRight)
    }

    @Test
    fun `target directly right of anchor produces rightward angle and gaze`() {
        // Target center at (800, 500) -> dx = +300, dy = 0
        val target = ScreenRect(750f, 450f, 100f, 100f)
        val geometry = TargetGuidanceCalculator.calculateGuidance(anchor, target)

        assertTrue(geometry.isGuidanceUsable)
        assertEquals(GuidanceValidity.VALID, geometry.validity)
        assertEquals(300f, geometry.displacement!!.dx, 0.001f)
        assertEquals(0f, geometry.displacement!!.dy, 0.001f)
        assertEquals(1f, geometry.displacement!!.normalizedX, 0.001f)
        assertEquals(0f, geometry.displacement!!.normalizedY, 0.001f)
        assertEquals(0f, geometry.displacement!!.angleDegrees, 0.01f)

        assertTrue(geometry.gazeOffset.offsetX > 0f)
        assertEquals(0f, geometry.gazeOffset.offsetY, 0.001f)
        assertTrue(geometry.pointingGesture!!.isPointingRight)
    }

    @Test
    fun `targets in all four diagonal quadrants produce expected angles`() {
        // 1. Top-Right: dx = 300, dy = -300 -> angle ~ -45°
        val tr = TargetGuidanceCalculator.calculateGuidance(anchor, ScreenRect(750f, 150f, 100f, 100f))
        assertEquals(-45f, tr.displacement!!.angleDegrees, 0.01f)
        assertTrue(tr.pointingGesture!!.isPointingRight)
        assertFalse(tr.pointingGesture!!.isPointingDown)

        // 2. Top-Left: dx = -300, dy = -300 -> angle ~ -135°
        val tl = TargetGuidanceCalculator.calculateGuidance(anchor, ScreenRect(150f, 150f, 100f, 100f))
        assertEquals(-135f, tl.displacement!!.angleDegrees, 0.01f)
        assertFalse(tl.pointingGesture!!.isPointingRight)
        assertFalse(tl.pointingGesture!!.isPointingDown)

        // 3. Bottom-Right: dx = 300, dy = 300 -> angle ~ +45°
        val br = TargetGuidanceCalculator.calculateGuidance(anchor, ScreenRect(750f, 750f, 100f, 100f))
        assertEquals(45f, br.displacement!!.angleDegrees, 0.01f)
        assertTrue(br.pointingGesture!!.isPointingRight)
        assertTrue(br.pointingGesture!!.isPointingDown)

        // 4. Bottom-Left: dx = -300, dy = 300 -> angle ~ +135°
        val bl = TargetGuidanceCalculator.calculateGuidance(anchor, ScreenRect(150f, 750f, 100f, 100f))
        assertEquals(135f, bl.displacement!!.angleDegrees, 0.01f)
        assertFalse(bl.pointingGesture!!.isPointingRight)
        assertTrue(bl.pointingGesture!!.isPointingDown)
    }

    @Test
    fun `target coinciding with anchor produces ZERO_DISTANCE and neutral gaze`() {
        // Target center exactly at (500, 500)
        val target = ScreenRect(450f, 450f, 100f, 100f)
        val geometry = TargetGuidanceCalculator.calculateGuidance(anchor, target)

        assertEquals(GuidanceValidity.ZERO_DISTANCE, geometry.validity)
        assertFalse(geometry.isGuidanceUsable)
        assertEquals(0f, geometry.distance, 0.001f)
        assertEquals(0f, geometry.gazeOffset.offsetX)
        assertEquals(0f, geometry.gazeOffset.offsetY)
        assertNull(geometry.pointingGesture)
    }

    @Test
    fun `empty and invalid target bounds produce INVALID_TARGET_BOUNDS`() {
        val zeroWidth = ScreenRect(100f, 100f, 0f, 50f)
        val negativeHeight = ScreenRect(100f, 100f, 50f, -10f)

        val g1 = TargetGuidanceCalculator.calculateGuidance(anchor, zeroWidth)
        assertEquals(GuidanceValidity.INVALID_TARGET_BOUNDS, g1.validity)
        assertFalse(g1.isGuidanceUsable)

        val g2 = TargetGuidanceCalculator.calculateGuidance(anchor, negativeHeight)
        assertEquals(GuidanceValidity.INVALID_TARGET_BOUNDS, g2.validity)
        assertFalse(g2.isGuidanceUsable)

        val gNull = TargetGuidanceCalculator.calculateGuidance(anchor, null)
        assertEquals(GuidanceValidity.INVALID_TARGET_BOUNDS, gNull.validity)
        assertFalse(gNull.isGuidanceUsable)
    }

    @Test
    fun `negative desktop coordinates on multi-monitor setups resolve correctly`() {
        // Secondary monitor placed to the left: anchor at (-1200, 300), target at (-800, 500)
        val leftMonitorAnchor = ScreenPoint(-1200f, 300f)
        val leftMonitorTarget = ScreenRect(-850f, 450f, 100f, 100f) // center at (-800, 500)

        val geometry = TargetGuidanceCalculator.calculateGuidance(leftMonitorAnchor, leftMonitorTarget)
        assertTrue(geometry.isGuidanceUsable)
        assertEquals(400f, geometry.displacement!!.dx, 0.001f)
        assertEquals(200f, geometry.displacement!!.dy, 0.001f)
        assertTrue(geometry.pointingGesture!!.isPointingRight)
        assertTrue(geometry.pointingGesture!!.isPointingDown)
    }

    @Test
    fun `non-finite coordinates fail safely with NON_FINITE_COORDINATES`() {
        val nanAnchor = ScreenPoint(Float.NaN, 500f)
        val g1 = TargetGuidanceCalculator.calculateGuidance(nanAnchor, ScreenRect(100f, 100f, 50f, 50f))
        assertEquals(GuidanceValidity.NON_FINITE_COORDINATES, g1.validity)
        assertFalse(g1.isGuidanceUsable)

        val infTarget = ScreenRect(100f, Float.POSITIVE_INFINITY, 50f, 50f)
        val g2 = TargetGuidanceCalculator.calculateGuidance(anchor, infTarget)
        assertEquals(GuidanceValidity.NON_FINITE_COORDINATES, g2.validity)
        assertFalse(g2.isGuidanceUsable)
    }

    @Test
    fun `normalized direction vector magnitude is always 1 for non-zero displacement`() {
        val targets = listOf(
            ScreenRect(10f, 20f, 30f, 40f),
            ScreenRect(999f, 1200f, 100f, 100f),
            ScreenRect(-500f, 800f, 80f, 80f),
            ScreenRect(300f, -600f, 50f, 50f)
        )

        for (target in targets) {
            val g = TargetGuidanceCalculator.calculateGuidance(anchor, target)
            assertTrue(g.isGuidanceUsable)
            val normMag = sqrt(
                g.displacement!!.normalizedX * g.displacement!!.normalizedX +
                g.displacement!!.normalizedY * g.displacement!!.normalizedY
            )
            assertEquals(1.0f, normMag, 0.0001f)
        }
    }

    @Test
    fun `gaze offset is strictly clamped within -1 to 1 range`() {
        // Target extremely far away (dx = 100,000)
        val distantTarget = ScreenRect(100_000f, 100_000f, 100f, 100f)
        val g = TargetGuidanceCalculator.calculateGuidance(anchor, distantTarget)

        assertTrue(g.isGuidanceUsable)
        assertTrue(g.gazeOffset.offsetX in -1.0f..1.0f)
        assertTrue(g.gazeOffset.offsetY in -1.0f..1.0f)
    }

    @Test
    fun `DPI scaling preserves normalized vector and angle`() {
        val target = ScreenRect(700f, 650f, 80f, 60f)
        val unscaled = TargetGuidanceCalculator.calculateGuidance(anchor, target)

        // Simulate 1.5x DPI scaling on both coordinates
        val scale = 1.5f
        val scaledAnchor = anchor.scale(scale)
        val scaledTarget = target.scale(scale)
        val scaled = TargetGuidanceCalculator.calculateGuidance(scaledAnchor, scaledTarget)

        assertEquals(unscaled.displacement!!.angleDegrees, scaled.displacement!!.angleDegrees, 0.001f)
        assertEquals(unscaled.displacement!!.normalizedX, scaled.displacement!!.normalizedX, 0.001f)
        assertEquals(unscaled.displacement!!.normalizedY, scaled.displacement!!.normalizedY, 0.001f)
    }

    @Test
    fun `target movement updates guidance deterministically`() {
        val targetPos1 = ScreenRect(600f, 475f, 50f, 50f) // center at (625, 500) -> right
        val targetPos2 = ScreenRect(475f, 600f, 50f, 50f) // center at (500, 625) -> down

        val g1 = TargetGuidanceCalculator.calculateGuidance(anchor, targetPos1)
        val g2 = TargetGuidanceCalculator.calculateGuidance(anchor, targetPos2)

        assertEquals(0f, g1.displacement!!.angleDegrees, 0.01f)
        assertEquals(90f, g2.displacement!!.angleDegrees, 0.01f)
    }

    @Test
    fun `stale target invalidation produces neutral safe output`() {
        val neutral = TargetGuidanceGeometry.neutral(anchor)
        assertFalse(neutral.isGuidanceUsable)
        assertNull(neutral.targetCenter)
        assertNull(neutral.displacement)
        assertNull(neutral.pointingGesture)
        assertEquals(0f, neutral.gazeOffset.offsetX)
        assertEquals(0f, neutral.gazeOffset.offsetY)
    }

    @Test
    fun `deterministic execution produces identical results on identical inputs`() {
        val target = ScreenRect(850f, 250f, 60f, 40f)
        val run1 = TargetGuidanceCalculator.calculateGuidance(anchor, target)
        val run2 = TargetGuidanceCalculator.calculateGuidance(anchor, target)

        assertEquals(run1, run2)
    }

    // =========================================================================
    // TARGET DOCKING POSITION CALCULATOR TESTS
    // =========================================================================

    @Test
    fun `target in center of viewport docks avatar to right with robot on left pointing left`() {
        // Target: 3D Viewport Sphere at (670, 360, 300, 260) -> center at (820, 490)
        val sphereTarget = ScreenRect(670f, 360f, 300f, 260f)
        val screen = ScreenPoint(1920f, 1080f)
        val window = ScreenPoint(440f, 260f)

        val docking = TargetDockPositionCalculator.calculateDockPosition(
            target = sphereTarget,
            screenSize = screen,
            windowSize = window,
            gap = 24f,
            margin = 20f
        )

        // Space on right: 1920 - (670 + 300 + 24 + 440 + 20) = 466px >= 0 -> Docks to RIGHT of target
        assertEquals(DockSide.RIGHT_OF_TARGET, docking.dockSide)
        assertFalse(docking.isAvatarOnRight, "Avatar should be on left edge of window pointing left into sphere")
        assertEquals(994f, docking.position.x, 0.001f, "Window X should be target.x + target.width + gap (670 + 300 + 24 = 994)")
        assertEquals(360f, docking.position.y, 0.001f, "Window Y should vertically center with target (490 - 130 = 360)")
    }

    @Test
    fun `target near right screen edge docks avatar to left with robot on right pointing right`() {
        // Target in Blender Outliner / Properties panel at X=1600, Y=200, W=220, H=40
        val outlinerTarget = ScreenRect(1600f, 200f, 220f, 40f)
        val screen = ScreenPoint(1920f, 1080f)
        val window = ScreenPoint(440f, 260f)

        val docking = TargetDockPositionCalculator.calculateDockPosition(
            target = outlinerTarget,
            screenSize = screen,
            windowSize = window,
            gap = 24f,
            margin = 20f
        )

        // Space on right: 1920 - (1600 + 220 + 24 + 440 + 20) < 0 -> Docks to LEFT of target
        assertEquals(DockSide.LEFT_OF_TARGET, docking.dockSide)
        assertTrue(docking.isAvatarOnRight, "Avatar should be on right edge of window pointing right into outliner")
        assertEquals(1136f, docking.position.x, 0.001f, "Window X should be target.x - window.x - gap (1600 - 440 - 24 = 1136)")
    }

    @Test
    fun `target near top edge clamps vertical coordinate to preserve margin`() {
        // Target in 3D Viewport header menu at Y=40
        val topTarget = ScreenRect(200f, 40f, 100f, 30f) // Center Y = 55
        val docking = TargetDockPositionCalculator.calculateDockPosition(
            target = topTarget,
            screenSize = ScreenPoint(1920f, 1080f),
            windowSize = ScreenPoint(440f, 260f),
            margin = 20f
        )

        // 55 - 130 = -75 -> Clamped to margin + 20 = 40
        assertEquals(40f, docking.position.y, 0.001f)
    }

    @Test
    fun `target near bottom edge clamps vertical coordinate to preserve taskbar margin`() {
        // Target near timeline at bottom Y=980
        val bottomTarget = ScreenRect(500f, 980f, 100f, 40f) // Center Y = 1000
        val docking = TargetDockPositionCalculator.calculateDockPosition(
            target = bottomTarget,
            screenSize = ScreenPoint(1920f, 1080f),
            windowSize = ScreenPoint(440f, 260f),
            margin = 20f
        )

        // 1000 - 130 = 870 -> Clamped to 1080 - 260 - 20 - 30 = 770
        assertEquals(770f, docking.position.y, 0.001f)
    }

    @Test
    fun `null or invalid target bounds return safe resting dock position`() {
        val nullResult = TargetDockPositionCalculator.calculateDockPosition(null)
        assertEquals(DockSide.LEFT_OF_TARGET, nullResult.dockSide)
        assertTrue(nullResult.isAvatarOnRight)
        assertTrue(nullResult.position.x > 0f)
        assertTrue(nullResult.position.y > 0f)

        val zeroWidthResult = TargetDockPositionCalculator.calculateDockPosition(ScreenRect(100f, 100f, 0f, 50f))
        assertEquals(DockSide.LEFT_OF_TARGET, zeroWidthResult.dockSide)
    }
}

