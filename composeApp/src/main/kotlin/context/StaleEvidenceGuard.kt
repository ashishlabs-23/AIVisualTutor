package context

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Source-specific evidence categories with distinct temporal validity lifecycles.
 */
enum class EvidenceCategory {
    /** Live application-specific socket/IPC state (e.g. Blender TCP, Excel COM). Tight validity window. */
    LIVE_TELEMETRY,
    /** Windows UI Automation element tree and properties. Moderate validity window. */
    UI_AUTOMATION,
    /** Desktop screenshot raster or cropped region. Snapshot of pixel state. */
    SCREENSHOT,
    /** Remote or local VLM visual grounding prediction. Longest inference window. */
    VLM_INFERENCE,
    /** Optical character recognition tokens. */
    OCR_READING
}

/**
 * Freshness policy governing a single evidence category.
 */
data class FreshnessPolicy(
    val category: EvidenceCategory,
    val maxAge: Duration,
    val requireMatchingApplication: Boolean = true,
    val requireMatchingStep: Boolean = false
) {
    companion object {
        val DEFAULT_POLICIES = mapOf(
            EvidenceCategory.LIVE_TELEMETRY to FreshnessPolicy(
                category = EvidenceCategory.LIVE_TELEMETRY,
                maxAge = Duration.ofSeconds(3),
                requireMatchingApplication = true
            ),
            EvidenceCategory.UI_AUTOMATION to FreshnessPolicy(
                category = EvidenceCategory.UI_AUTOMATION,
                maxAge = Duration.ofSeconds(5),
                requireMatchingApplication = true
            ),
            EvidenceCategory.SCREENSHOT to FreshnessPolicy(
                category = EvidenceCategory.SCREENSHOT,
                maxAge = Duration.ofSeconds(8),
                requireMatchingApplication = true
            ),
            EvidenceCategory.VLM_INFERENCE to FreshnessPolicy(
                category = EvidenceCategory.VLM_INFERENCE,
                maxAge = Duration.ofSeconds(15),
                requireMatchingApplication = true
            ),
            EvidenceCategory.OCR_READING to FreshnessPolicy(
                category = EvidenceCategory.OCR_READING,
                maxAge = Duration.ofSeconds(8),
                requireMatchingApplication = true
            )
        )
    }
}

/**
 * Outcome of evaluating evidence freshness.
 */
data class FreshnessEvaluation(
    val isFresh: Boolean,
    val ageMillis: Long,
    val thresholdMillis: Long,
    val category: EvidenceCategory,
    val reason: String,
    val observationTimestamp: Instant
)

/**
 * Reusable evidence freshness guard that prevents stale, mismatched, or out-of-order
 * observations from silently leading to erroneous verification success.
 *
 * Supports deterministic testing via an injectable [Clock].
 */
class StaleEvidenceGuard(
    private val clock: Clock = Clock.systemUTC(),
    private val policies: Map<EvidenceCategory, FreshnessPolicy> = FreshnessPolicy.DEFAULT_POLICIES
) {

    /**
     * Evaluates whether an observation taken at [observedAt] remains fresh and valid
     * for the given [category], [targetApplication], and [currentStepId].
     */
    fun evaluate(
        category: EvidenceCategory,
        observedAt: Instant,
        observedApplication: String? = null,
        targetApplication: String? = null,
        observedStepId: String? = null,
        currentStepId: String? = null
    ): FreshnessEvaluation {
        val policy = policies[category] ?: FreshnessPolicy(category, Duration.ofSeconds(5))
        val now = clock.instant()
        val age = Duration.between(observedAt, now)
        val ageMs = age.toMillis().coerceAtLeast(0)
        val maxAgeMs = policy.maxAge.toMillis()

        // 1. Future timestamp sanity check
        if (observedAt.isAfter(now.plusMillis(500))) {
            return FreshnessEvaluation(
                isFresh = false,
                ageMillis = ageMs,
                thresholdMillis = maxAgeMs,
                category = category,
                reason = "Observation timestamp is in the future ($observedAt > $now). Clock skew detected.",
                observationTimestamp = observedAt
            )
        }

        // 2. Application Identity Check
        if (policy.requireMatchingApplication && targetApplication != null && observedApplication != null) {
            val target = targetApplication.trim().lowercase()
            val observed = observedApplication.trim().lowercase()
            if (target != observed && !observed.contains(target) && !target.contains(observed)) {
                return FreshnessEvaluation(
                    isFresh = false,
                    ageMillis = ageMs,
                    thresholdMillis = maxAgeMs,
                    category = category,
                    reason = "Application mismatch: observation is for '$observedApplication' but expected '$targetApplication'.",
                    observationTimestamp = observedAt
                )
            }
        }

        // 3. Step Context Check
        if (policy.requireMatchingStep && currentStepId != null && observedStepId != null) {
            if (currentStepId != observedStepId) {
                return FreshnessEvaluation(
                    isFresh = false,
                    ageMillis = ageMs,
                    thresholdMillis = maxAgeMs,
                    category = category,
                    reason = "Step mismatch: observation belongs to step '$observedStepId' but current step is '$currentStepId'.",
                    observationTimestamp = observedAt
                )
            }
        }

        // 4. Temporal Staleness Check
        if (ageMs > maxAgeMs) {
            return FreshnessEvaluation(
                isFresh = false,
                ageMillis = ageMs,
                thresholdMillis = maxAgeMs,
                category = category,
                reason = "Evidence is stale: age ${ageMs}ms exceeds ${category.name} threshold of ${maxAgeMs}ms.",
                observationTimestamp = observedAt
            )
        }

        return FreshnessEvaluation(
            isFresh = true,
            ageMillis = ageMs,
            thresholdMillis = maxAgeMs,
            category = category,
            reason = "Evidence is fresh and valid (age ${ageMs}ms <= ${maxAgeMs}ms threshold).",
            observationTimestamp = observedAt
        )
    }
}
