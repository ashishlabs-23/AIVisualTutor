package context

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaleEvidenceGuardTest {

    private val baseInstant = Instant.parse("2026-10-12T12:00:00Z")
    private val fixedClock = Clock.fixed(baseInstant, ZoneOffset.UTC)
    private val guard = StaleEvidenceGuard(clock = fixedClock)

    @Test
    fun `fresh telemetry within 3s threshold is accepted`() {
        val observedAt = baseInstant.minusSeconds(2) // 2s old
        val eval = guard.evaluate(
            category = EvidenceCategory.LIVE_TELEMETRY,
            observedAt = observedAt,
            observedApplication = "Blender",
            targetApplication = "Blender"
        )

        assertTrue(eval.isFresh)
        assertEquals(2000L, eval.ageMillis)
        assertEquals(3000L, eval.thresholdMillis)
    }

    @Test
    fun `stale telemetry exceeding 3s threshold is rejected`() {
        val observedAt = baseInstant.minusSeconds(4) // 4s old
        val eval = guard.evaluate(
            category = EvidenceCategory.LIVE_TELEMETRY,
            observedAt = observedAt,
            observedApplication = "Blender",
            targetApplication = "Blender"
        )

        assertFalse(eval.isFresh)
        assertEquals(4000L, eval.ageMillis)
        assertTrue(eval.reason.contains("exceeds LIVE_TELEMETRY threshold"))
    }

    @Test
    fun `screenshot freshness threshold permits up to 8s`() {
        val freshObs = baseInstant.minusSeconds(7)
        val freshEval = guard.evaluate(
            category = EvidenceCategory.SCREENSHOT,
            observedAt = freshObs,
            observedApplication = "Excel",
            targetApplication = "Excel"
        )
        assertTrue(freshEval.isFresh)

        val staleObs = baseInstant.minusSeconds(9)
        val staleEval = guard.evaluate(
            category = EvidenceCategory.SCREENSHOT,
            observedAt = staleObs,
            observedApplication = "Excel",
            targetApplication = "Excel"
        )
        assertFalse(staleEval.isFresh)
        assertTrue(staleEval.reason.contains("exceeds SCREENSHOT threshold"))
    }

    @Test
    fun `application mismatch is rejected even if temporally fresh`() {
        val observedAt = baseInstant.minusMillis(500) // 500ms old
        val eval = guard.evaluate(
            category = EvidenceCategory.LIVE_TELEMETRY,
            observedAt = observedAt,
            observedApplication = "Google Chrome",
            targetApplication = "Blender"
        )

        assertFalse(eval.isFresh)
        assertTrue(eval.reason.contains("Application mismatch"))
    }

    @Test
    fun `step mismatch is rejected when policy enforces matching step`() {
        val strictPolicy = mapOf(
            EvidenceCategory.LIVE_TELEMETRY to FreshnessPolicy(
                category = EvidenceCategory.LIVE_TELEMETRY,
                maxAge = Duration.ofSeconds(3),
                requireMatchingStep = true
            )
        )
        val strictGuard = StaleEvidenceGuard(clock = fixedClock, policies = strictPolicy)

        val eval = strictGuard.evaluate(
            category = EvidenceCategory.LIVE_TELEMETRY,
            observedAt = baseInstant.minusSeconds(1),
            observedStepId = "step-1",
            currentStepId = "step-2"
        )

        assertFalse(eval.isFresh)
        assertTrue(eval.reason.contains("Step mismatch"))
    }

    @Test
    fun `future timestamps are rejected as clock skew`() {
        val futureInstant = baseInstant.plusSeconds(10)
        val eval = guard.evaluate(
            category = EvidenceCategory.LIVE_TELEMETRY,
            observedAt = futureInstant
        )

        assertFalse(eval.isFresh)
        assertTrue(eval.reason.contains("Clock skew detected"))
    }
}
