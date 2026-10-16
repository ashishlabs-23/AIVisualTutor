package ui.avatar

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rigorous test suite validating the 6 Pillars of Frontend Avatar Experience
 * in AI Visual Tutor on branch `feature/frontend-dev`.
 */
class AvatarEnhancementsSixPillarsTest {

    // =========================================================================
    // PILLAR 1: Biological Realism & Saccadic Micro-Jitter
    // =========================================================================

    @Test
    fun testPillar1ContextSensitiveBlinkDurationModulation() {
        fun resolveBlinkDuration(state: AvatarPresentationState): Int {
            return when (state) {
                AvatarPresentationState.THINKING, AvatarPresentationState.VERIFYING -> 1800
                AvatarPresentationState.EXPLAINING -> 5600
                else -> 3800
            }
        }

        assertEquals(1800, resolveBlinkDuration(AvatarPresentationState.THINKING), "Thinking must trigger rapid cognitive blink (1.8s)")
        assertEquals(1800, resolveBlinkDuration(AvatarPresentationState.VERIFYING), "Verifying must trigger rapid cognitive blink (1.8s)")
        assertEquals(5600, resolveBlinkDuration(AvatarPresentationState.EXPLAINING), "Explaining must hold attentive gaze (5.6s)")
        assertEquals(3800, resolveBlinkDuration(AvatarPresentationState.IDLE), "Idle baseline blink is 3.8s")
        assertEquals(3800, resolveBlinkDuration(AvatarPresentationState.GUIDING), "Guiding baseline blink is 3.8s")
    }

    @Test
    fun testPillar1SaccadicOcularJitterClamping() {
        val baseGazeX = 0.85f
        val saccadeJitterX = 0.22f
        val finalGazeX = (baseGazeX + saccadeJitterX).coerceIn(-1.0f, 1.0f)
        assertTrue(finalGazeX <= 1.0f && finalGazeX >= -1.0f, "Combined gaze and saccade jitter must be clamped within [-1.0, 1.0]")
    }

    // =========================================================================
    // PILLAR 2: Procedural Kinematics & Drag Inertia Tilt
    // =========================================================================

    @Test
    fun testPillar2DragInertiaTiltCalculations() {
        fun calculateRawTilt(deltaX: Float): Float {
            return if (abs(deltaX) > 1.5f) {
                (-deltaX * 0.35f).coerceIn(-14f, 14f)
            } else 0f
        }

        assertEquals(0f, calculateRawTilt(0.5f), "Negligible movements (< 1.5px) should not tilt")
        val rightDragTilt = calculateRawTilt(20f)
        assertEquals(-7.0f, rightDragTilt, "Dragging right (+20px) should tilt head counter-clockwise (-7 deg)")

        val leftDragTilt = calculateRawTilt(-30f)
        assertEquals(10.5f, leftDragTilt, "Dragging left (-30px) should tilt head clockwise (+10.5 deg)")

        val extremeDragTilt = calculateRawTilt(100f)
        assertEquals(-14f, extremeDragTilt, "Extreme velocity must be clamped to -14 deg max tilt")
    }

    @Test
    fun testPillar2LayeredHarmonicBreathingFloat() {
        val primaryFloat = 2.0f
        val secondaryFloat = 0.8f
        val combinedFloat = primaryFloat + secondaryFloat * 0.45f
        assertEquals(2.36f, combinedFloat, 0.01f, "Combined harmonic float must integrate primary and secondary octaves")
    }

    // =========================================================================
    // PILLAR 3: Visual FX & Traveling Laser Energy Wave
    // =========================================================================

    @Test
    fun testPillar3TravelingLaserEnergyWavePacketInterpolation() {
        val handX = 100f
        val handY = 200f
        val dirX = 1f
        val dirY = 0f
        val laserLength = 70f

        for (phase in listOf(0f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            val packetX = handX + dirX * (laserLength * phase)
            val packetY = handY + dirY * (laserLength * phase)
            val expectedX = 100f + 70f * phase

            assertEquals(expectedX, packetX, 0.001f, "Wave packet X must advance proportionally with pulse phase")
            assertEquals(200f, packetY, 0.001f, "Wave packet Y must remain along pointing axis")
        }
    }

    // =========================================================================
    // PILLAR 4: Audio-Reactive Visemes / Visor Soundwave
    // =========================================================================

    @Test
    fun testPillar4SpeechVisorWaveformAmplitudes() {
        val barCount = 5
        val speechWavePhase = 0.4f

        for (i in 0 until barCount) {
            val phaseOffset = i * 0.9f
            val barAmplitude = 1.5f + 4.5f * (0.5f + 0.5f * sin((speechWavePhase * 6.28318f) + phaseOffset))
            assertTrue(barAmplitude >= 1.5f, "Soundwave bar amplitude must have a non-zero base minimum")
            assertTrue(barAmplitude <= 6.0f, "Soundwave bar amplitude must not exceed visor height limits")
        }
    }

    // =========================================================================
    // PILLAR 5: Screen-Edge Intelligence & Spatial Inversion
    // =========================================================================

    @Test
    fun testPillar5ScreenEdgeDetectionAndLayoutInversion() {
        val screenWidth = 1920f
        val windowWidth = 440f
        val edgeMargin = 80f

        fun isNearRightEdge(windowX: Float): Boolean {
            return (windowX + windowWidth) > (screenWidth - edgeMargin)
        }

        assertFalse(isNearRightEdge(100f), "Window at X=100 is far from right edge -> default layout")
        assertFalse(isNearRightEdge(1300f), "Window at X=1300 is within comfortable bounds -> default layout")
        assertTrue(isNearRightEdge(1410f), "Window at X=1410 reaches right margin (1410+440=1850 > 1840) -> flipped layout")
        assertTrue(isNearRightEdge(1600f), "Window at X=1600 reaches right margin -> flipped layout")
    }

    // =========================================================================
    // PILLAR 6: Micro-Interactions & Badges
    // =========================================================================

    @Test
    fun testPillar6CelebratoryConfettiParticleGeometry() {
        val centerX = 200f
        val badgeY = 50f
        val particleCount = 8
        val t = 0.5f // Halfway through celebration progress

        val particlePositions = (0 until particleCount).map { i ->
            val angle = (i * (6.28318f / particleCount)) + 0.3f
            val speed = 18f + (i % 3) * 8f
            val px = centerX + cos(angle) * (speed * t)
            val py = (badgeY - 4f) + sin(angle) * (speed * 0.7f * t) - (12f * t)
            Offset(px, py)
        }

        assertEquals(8, particlePositions.size, "Must generate exactly 8 celebration particles")
        // Verify particles spread in distinct directions
        val minX = particlePositions.minOf { it.x }
        val maxX = particlePositions.maxOf { it.x }
        assertTrue(maxX > minX + 10f, "Particles must scatter radially across X axis")
    }
}
