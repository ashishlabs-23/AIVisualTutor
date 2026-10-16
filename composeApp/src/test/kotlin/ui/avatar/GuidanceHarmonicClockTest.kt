package ui.avatar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuidanceHarmonicClockTest {

    @Test
    fun breathingPulseReturnsMinAndMaxAtQuarterAndThreeQuarterCycles() {
        // At t = 0: phase = 0 -> sine = 0 -> pulse = 0.35
        val pulse0 = GuidanceHarmonicClock.calculateBreathingPulse(0L)
        assertEquals(0.35f, pulse0, 0.001f)

        // At t = 750ms (half of 1500ms): phase = 0.5 -> cos(pi) = -1 -> sine = 1 -> pulse = 0.95
        val pulseHalf = GuidanceHarmonicClock.calculateBreathingPulse(750L)
        assertEquals(0.95f, pulseHalf, 0.001f)

        // At t = 1500ms: full cycle -> returns to 0.35
        val pulseFull = GuidanceHarmonicClock.calculateBreathingPulse(1500L)
        assertEquals(0.35f, pulseFull, 0.001f)
    }

    @Test
    fun photonPhaseProgressesLinearlyOver1000MsCycle() {
        val p0 = GuidanceHarmonicClock.calculatePhotonPhase(0L)
        assertEquals(0f, p0, 0.001f)

        val pHalf = GuidanceHarmonicClock.calculatePhotonPhase(500L)
        assertEquals(0.5f, pHalf, 0.001f)

        val pQuarter = GuidanceHarmonicClock.calculatePhotonPhase(250L)
        assertEquals(0.25f, pQuarter, 0.001f)

        val pFull = GuidanceHarmonicClock.calculatePhotonPhase(1000L)
        assertEquals(0f, pFull, 0.001f)
    }

    @Test
    fun impactPhaseProgressesLinearlyOver800MsCycle() {
        val i0 = GuidanceHarmonicClock.calculateImpactPhase(0L)
        assertEquals(0f, i0, 0.001f)

        val iHalf = GuidanceHarmonicClock.calculateImpactPhase(400L)
        assertEquals(0.5f, iHalf, 0.001f)

        val iFull = GuidanceHarmonicClock.calculateImpactPhase(800L)
        assertEquals(0f, iFull, 0.001f)
    }

    @Test
    fun pulseValuesRemainStrictlyBoundedAcrossArbitraryTime() {
        val times = listOf(137L, 592L, 1201L, 458921849L, 999999999L)
        for (t in times) {
            val pulse = GuidanceHarmonicClock.calculateBreathingPulse(t)
            assertTrue(pulse in 0.35f..0.95f, "Pulse out of bounds at t=$t: $pulse")

            val photon = GuidanceHarmonicClock.calculatePhotonPhase(t)
            assertTrue(photon in 0.0f..1.0f, "Photon phase out of bounds at t=$t: $photon")

            val impact = GuidanceHarmonicClock.calculateImpactPhase(t)
            assertTrue(impact in 0.0f..1.0f, "Impact phase out of bounds at t=$t: $impact")
        }
    }
}
