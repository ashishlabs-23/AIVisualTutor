package ui.avatar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import kotlin.math.PI
import kotlin.math.cos

/**
 * Shared clock and phase generator for synchronized guidance between
 * the floating robot avatar and the on-screen highlight overlay.
 *
 * Guarantees that [ui.avatar.AvatarView] and [ui.Highlight] share the exact
 * same temporal phase, pulse timing source, and breathing rhythm regardless
 * of when each window was mounted or composed.
 */
object GuidanceHarmonicClock {
    const val BREATHING_CYCLE_MS = 1500L
    const val PHOTON_CYCLE_MS = 1000L
    const val IMPACT_CYCLE_MS = 800L

    /**
     * Calculates the continuous sinusoidal breathing pulse in [0.35f .. 0.95f]
     * for the given system millisecond timestamp.
     */
    fun calculateBreathingPulse(timeMs: Long = System.currentTimeMillis()): Float {
        val phase = (timeMs % BREATHING_CYCLE_MS).toFloat() / BREATHING_CYCLE_MS.toFloat()
        val sine = (1f - cos(phase * 2f * PI.toFloat())) / 2f
        return 0.35f + 0.60f * sine
    }

    /**
     * Calculates the linear traveling photon phase in [0.0f .. 1.0f].
     */
    fun calculatePhotonPhase(timeMs: Long = System.currentTimeMillis()): Float {
        return (timeMs % PHOTON_CYCLE_MS).toFloat() / PHOTON_CYCLE_MS.toFloat()
    }

    /**
     * Calculates the impact ripple phase in [0.0f .. 1.0f].
     */
    fun calculateImpactPhase(timeMs: Long = System.currentTimeMillis()): Float {
        return (timeMs % IMPACT_CYCLE_MS).toFloat() / IMPACT_CYCLE_MS.toFloat()
    }
}

/**
 * Holds immutable synchronized guidance values computed from the shared clock.
 */
data class SynchronizedGuidanceValues(
    val breathingPulse: Float,
    val photonPhase: Float,
    val impactPhase: Float
)

/**
 * Reactive Compose hook that updates in sync with display frames, providing
 * the shared harmonic timing values for synchronized guidance rendering.
 */
@Composable
fun rememberSynchronizedGuidanceClock(): SynchronizedGuidanceValues {
    var frameTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { frameTimeNanos ->
                frameTimeMs = frameTimeNanos / 1_000_000L
            }
        }
    }

    return remember(frameTimeMs) {
        SynchronizedGuidanceValues(
            breathingPulse = GuidanceHarmonicClock.calculateBreathingPulse(frameTimeMs),
            photonPhase = GuidanceHarmonicClock.calculatePhotonPhase(frameTimeMs),
            impactPhase = GuidanceHarmonicClock.calculateImpactPhase(frameTimeMs)
        )
    }
}
