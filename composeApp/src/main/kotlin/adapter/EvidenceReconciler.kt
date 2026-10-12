package adapter

import context.BaselineState
import context.EvidenceObservation
import context.EvidenceSource
import context.ExpectedState
import context.ObjectObservation
import context.ObservedState
import context.StateVerifier
import context.VerificationOperation
import context.VerificationResult
import context.VerificationStatus
import java.time.Duration
import java.time.Instant

/**
 * Result of evidence reconciliation before passing to StateVerifier.
 */
data class ReconciliationOutcome(
    val status: VerificationStatus,
    val explanation: String,
    val reconciledEvidence: List<EvidenceObservation>,
    val conflictsDetected: List<EvidenceConflict> = emptyList(),
    val staleObservationsRejected: Int = 0
)

/**
 * Detailed description of a detected evidence conflict.
 */
data class EvidenceConflict(
    val entityOrProperty: String,
    val primarySource: EvidenceSource,
    val primaryValue: String,
    val conflictingSource: EvidenceSource,
    val conflictingValue: String,
    val resolution: ConflictResolution
)

enum class ConflictResolution {
    RESOLVED_TO_DIRECT_FACT,
    ABSTAIN_UNCERTAIN
}

/**
 * Reconciles multi-source evidence (Direct Application State, UI Automation, OCR, Visual Predictions)
 * according to strict provenance, freshness, and ground-truth priority rules.
 */
class EvidenceReconciler(
    val maxAcceptableStaleness: Duration = Duration.ofMinutes(30),
    val stateVerifier: StateVerifier = StateVerifier(),
    val visualDiffVerifier: context.VisualFrameDiffVerifier = context.VisualFrameDiffVerifier()
) {
    private var highestObservedSnapshotVersion: Long = 0L

    /**
     * Verifies whether a visual change occurred between two screenshot frames.
     */
    fun verifyVisualChange(
        baselineImage: java.awt.image.BufferedImage?,
        postActionImage: java.awt.image.BufferedImage?,
        regionOfInterest: java.awt.Rectangle? = null
    ): context.VisualDiffResult {
        return visualDiffVerifier.compare(baselineImage, postActionImage, regionOfInterest)
    }

    /**
     * Resets the version sequence tracking for a new task/session.
     */
    fun resetVersionSequence() {
        highestObservedSnapshotVersion = 0L
    }

    /**
     * Checks if a snapshot version is stale or out of order.
     */
    fun isStaleSnapshotVersion(version: Long): Boolean {
        return version > 0 && version < highestObservedSnapshotVersion
    }

    /**
     * Updates the highest observed snapshot version if newer.
     */
    fun recordObservedVersion(version: Long) {
        if (version > highestObservedSnapshotVersion) {
            highestObservedSnapshotVersion = version
        }
    }

    /**
     * Performs multi-source evidence reconciliation and executes verification.
     */
    fun reconcileAndVerify(
        expected: ExpectedState,
        baseline: BaselineState?,
        observed: ObservedState?,
        rawEvidence: List<ProvenanceEvidenceObservation>,
        taskCreatedAt: Instant = Instant.now()
    ): VerificationResult {
        val conflicts = mutableListOf<EvidenceConflict>()
        var staleRejected = 0

        // Filter and inspect freshness
        val validEvidence = mutableListOf<ProvenanceEvidenceObservation>()
        for (obs in rawEvidence) {
            val version = obs.provenance.snapshotVersion
            if (version != null && isStaleSnapshotVersion(version)) {
                staleRejected++
                continue // Reject stale out-of-order response
            }

            // Check temporal staleness relative to baseline / task reference time
            val referenceTime = baseline?.capturedAt ?: taskCreatedAt
            val age = Duration.between(referenceTime, obs.provenance.capturedAt).abs()
            if (age > maxAcceptableStaleness && obs.provenance.freshness == ObservationFreshness.FRESH) {
                // Mark as expired/stale
                val expiredProv = obs.provenance.copy(freshness = ObservationFreshness.EXPIRED)
                validEvidence.add(obs.copy(provenance = expiredProv, available = false))
            } else {
                validEvidence.add(obs)
            }
        }

        // Check for direct application state vs visual / UIA conflicts
        val directStateObs = validEvidence.find {
            it.provenance.category == EvidenceCategory.DIRECT_APPLICATION_STATE && it.available
        }

        val visualObs = validEvidence.find {
            it.provenance.category == EvidenceCategory.VISUAL_MODEL_PREDICTION && it.available
        }

        val uiaObs = validEvidence.find {
            it.provenance.category == EvidenceCategory.SEMANTIC_UI_AUTOMATION && it.available
        }

        // Scenario D: Conflict detection (e.g. mode conflict: Direct reports OBJECT mode, Visual reports EDIT mode)
        if (directStateObs != null && visualObs != null) {
            val directMode = directStateObs.details["mode"] ?: observed?.mode
            val visualMode = visualObs.details["mode"]
            if (!directMode.isNullOrBlank() && !visualMode.isNullOrBlank() && !directMode.equals(visualMode, ignoreCase = true)) {
                // Ground-truth principle: Direct application state is authoritative over visual prediction if fresh
                if (directStateObs.provenance.freshness == ObservationFreshness.FRESH) {
                    conflicts.add(
                        EvidenceConflict(
                            entityOrProperty = "mode",
                            primarySource = directStateObs.provenance.source,
                            primaryValue = directMode,
                            conflictingSource = visualObs.provenance.source,
                            conflictingValue = visualMode,
                            resolution = ConflictResolution.RESOLVED_TO_DIRECT_FACT
                        )
                    )
                } else {
                    conflicts.add(
                        EvidenceConflict(
                            entityOrProperty = "mode",
                            primarySource = directStateObs.provenance.source,
                            primaryValue = directMode,
                            conflictingSource = visualObs.provenance.source,
                            conflictingValue = visualMode,
                            resolution = ConflictResolution.ABSTAIN_UNCERTAIN
                        )
                    )
                }
            }
        }

        // Scenario C: Adapter timeout / unavailable state
        val hasDirectState = directStateObs != null
        if (!hasDirectState && observed == null) {
            val legacyEvidence = validEvidence.map { it.toLegacyEvidenceObservation() }
            return VerificationResult(
                status = VerificationStatus.UNCERTAIN,
                runId = baseline?.runId ?: java.util.UUID.randomUUID().toString(),
                operation = expected.operation,
                explanation = "Application adapter did not provide fresh state; cannot verify action without direct observation.",
                evidence = legacyEvidence,
                requiredConditions = emptyList()
            )
        }

        // Check unresolvable conflicts that require abstaining
        val unresolvableConflict = conflicts.any { it.resolution == ConflictResolution.ABSTAIN_UNCERTAIN }
        if (unresolvableConflict) {
            val legacyEvidence = validEvidence.map { it.toLegacyEvidenceObservation() }
            return VerificationResult(
                status = VerificationStatus.UNCERTAIN,
                runId = baseline?.runId ?: observed?.runId ?: java.util.UUID.randomUUID().toString(),
                operation = expected.operation,
                explanation = "Conflicting evidence detected across observation sources (${conflicts.joinToString { "${it.entityOrProperty}: ${it.primaryValue} vs ${it.conflictingValue}" }}); abstaining.",
                evidence = legacyEvidence,
                requiredConditions = emptyList()
            )
        }

        // Convert to legacy evidence for StateVerifier
        val legacyEvidence = validEvidence.map { it.toLegacyEvidenceObservation() }

        // Scenario B: Object selection when creation was expected
        // Check if user selected an existing object instead of creating a new one
        if (expected.operation == VerificationOperation.CREATE && baseline != null && observed != null) {
            val targetName = expected.targetName
            val existingInBaseline = baseline.objects.any { it.name.equals(targetName, ignoreCase = true) }
            if (existingInBaseline) {
                // The object was already in baseline! If user merely selected it, verification must fail
                return VerificationResult(
                    status = VerificationStatus.FAILURE,
                    runId = baseline.runId,
                    operation = expected.operation,
                    explanation = "Target object '$targetName' already existed in the baseline state; selecting an existing object is not a valid creation.",
                    evidence = legacyEvidence
                )
            }
        }

        // Scenario F: Object rename identity tracking
        if (expected.operation == VerificationOperation.RENAME && baseline != null && observed != null) {
            val targetName = expected.targetName
            val afterTarget = observed.objects.find { it.name.equals(targetName, ignoreCase = true) }
            if (afterTarget != null && afterTarget.id != null) {
                val beforeObjWithSameId = baseline.objects.find { it.id == afterTarget.id }
                if (beforeObjWithSameId == null) {
                    // Object with same identity was not in baseline - this might be a deletion + creation, not a rename!
                    return VerificationResult(
                        status = VerificationStatus.FAILURE,
                        runId = baseline.runId,
                        operation = expected.operation,
                        explanation = "Target object '$targetName' has identity '${afterTarget.id}' which did not exist in baseline; operation was a creation, not a rename.",
                        evidence = legacyEvidence
                    )
                }
            }
        }

        // Delegate to Phase 6 StateVerifier with reconciled evidence
        val result = stateVerifier.verify(expected, baseline, observed, legacyEvidence)

        // Enrich explanation if stale observations were rejected
        return if (staleRejected > 0) {
            result.copy(
                explanation = "${result.explanation} ($staleRejected stale out-of-order observation(s) rejected)"
            )
        } else {
            result
        }
    }
}
