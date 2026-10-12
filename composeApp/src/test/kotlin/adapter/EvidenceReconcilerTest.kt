package adapter

import context.BaselineState
import context.EvidenceSource
import context.ExpectedState
import context.ObjectObservation
import context.ObservedState
import context.VerificationOperation
import context.VerificationStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvidenceReconcilerTest {

    private val reconciler = EvidenceReconciler()

    private fun sampleBaseline(runId: String = "run-1", objects: List<ObjectObservation> = emptyList()) = BaselineState(
        runId = runId,
        capturedAt = Instant.now().minusSeconds(5),
        applicationName = "Blender",
        sceneName = "Scene",
        mode = "OBJECT",
        objects = objects,
        isComplete = true
    )

    private fun sampleObserved(runId: String = "run-1", objects: List<ObjectObservation> = emptyList(), mode: String = "OBJECT") = ObservedState(
        runId = runId,
        capturedAt = Instant.now(),
        applicationName = "Blender",
        sceneName = "Scene",
        mode = mode,
        objects = objects,
        isComplete = true
    )

    /**
     * Scenario A: Successful object creation
     */
    @Test
    fun testScenarioA_successfulObjectCreation() {
        val runId = UUID.randomUUID().toString()
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sphere",
            targetType = "MESH"
        )
        val baseline = sampleBaseline(runId, emptyList())
        val observed = sampleObserved(runId, listOf(ObjectObservation("id-1", "Sphere", "MESH", "Scene")))

        val prov = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            snapshotVersion = 2L,
            isDirectFact = true
        )
        val evidence = listOf(ProvenanceEvidenceObservation(provenance = prov, available = true))

        val result = reconciler.reconcileAndVerify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, result.status)
        assertTrue(result.explanation.contains("Required state conditions"))
    }

    /**
     * Scenario B: Existing object selected instead of created.
     * The learner selects an existing object; visual screenshot might look like an object,
     * but baseline state already contained it -> Must NOT report success!
     */
    @Test
    fun testScenarioB_existingObjectSelectedNotCreated() {
        val runId = UUID.randomUUID().toString()
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Cube",
            targetType = "MESH"
        )
        // Baseline already has Cube!
        val baseline = sampleBaseline(runId, listOf(ObjectObservation("id-cube", "Cube", "MESH", "Scene", selected = false)))
        // In observed state, Cube is still there (now selected)
        val observed = sampleObserved(runId, listOf(ObjectObservation("id-cube", "Cube", "MESH", "Scene", selected = true)))

        val prov = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            snapshotVersion = 3L
        )
        val evidence = listOf(ProvenanceEvidenceObservation(provenance = prov, available = true))

        val result = reconciler.reconcileAndVerify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status)
        assertTrue(result.explanation.contains("already existed in the baseline state"))
    }

    /**
     * Scenario C: Adapter timeout / unavailable state.
     * Missing state must NOT be interpreted as failure. Must return UNCERTAIN.
     */
    @Test
    fun testScenarioC_adapterTimeoutReturnsUncertainNotFailure() {
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Torus"
        )
        // No observed state available due to adapter timeout
        val baseline = sampleBaseline("run-timeout", emptyList())
        val prov = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            diagnostic = "Socket timeout waiting for Blender state."
        )
        val evidence = listOf(ProvenanceEvidenceObservation(provenance = prov, available = false))

        val result = reconciler.reconcileAndVerify(expected, baseline, null, evidence)
        assertEquals(VerificationStatus.UNCERTAIN, result.status)
        assertTrue(result.explanation.contains("did not provide fresh state"))
    }

    /**
     * Scenario D: Conflicting evidence.
     * Blender adapter reports OBJECT mode, while visual model predicted EDIT mode.
     * When direct state is fresh, ground-truth principle resolves to direct fact.
     */
    @Test
    fun testScenarioD_conflictingEvidenceResolvedToDirectFactWhenFresh() {
        val runId = UUID.randomUUID().toString()
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "loc",
            expectedPropertyValue = "1",
            targetMode = "OBJECT"
        )
        val baseline = sampleBaseline(runId, listOf(ObjectObservation("id-1", "Cube", "MESH", "Scene", metadata = mapOf("loc" to "0"))))
        val observed = sampleObserved(runId, listOf(ObjectObservation("id-1", "Cube", "MESH", "Scene", metadata = mapOf("loc" to "1"))), mode = "OBJECT")

        val directProv = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            snapshotVersion = 5L,
            freshness = ObservationFreshness.FRESH,
            isDirectFact = true
        )
        val directObs = ProvenanceEvidenceObservation(
            provenance = directProv,
            available = true,
            details = mapOf("mode" to "OBJECT")
        )

        val visualProv = EvidenceProvenance(
            category = EvidenceCategory.VISUAL_MODEL_PREDICTION,
            source = EvidenceSource.VISUAL,
            confidence = 0.85
        )
        val visualObs = ProvenanceEvidenceObservation(
            provenance = visualProv,
            available = true,
            details = mapOf("mode" to "EDIT")
        )

        // Even though visual claimed EDIT, fresh direct fact OBJECT prevails
        val result = reconciler.reconcileAndVerify(expected, baseline, observed, listOf(directObs, visualObs))
        assertEquals(VerificationStatus.SUCCESS, result.status)
    }

    /**
     * Scenario E: Stale response / out-of-order responses.
     * If newer snapshot version 10 was recorded, version 8 arriving later is rejected as stale.
     */
    @Test
    fun testScenarioE_staleOutOfOrderSnapshotRejected() {
        val rec = EvidenceReconciler()
        rec.recordObservedVersion(10L)

        assertTrue(rec.isStaleSnapshotVersion(8L))
        assertFalse(rec.isStaleSnapshotVersion(10L))
        assertFalse(rec.isStaleSnapshotVersion(11L))

        val runId = UUID.randomUUID().toString()
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Monkey"
        )
        val baseline = sampleBaseline(runId, emptyList())
        val observed = sampleObserved(runId, listOf(ObjectObservation("id-m", "Monkey", "MESH", "Scene")))

        val staleProv = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            snapshotVersion = 7L // older than 10L!
        )
        val staleObs = ProvenanceEvidenceObservation(provenance = staleProv, available = true)

        val result = rec.reconcileAndVerify(expected, baseline, observed, listOf(staleObs))
        assertTrue(result.explanation.contains("stale out-of-order observation(s) rejected"))
    }

    /**
     * Scenario F: Object rename identity tracking.
     * If an object with target name appears, but its identity does not match any baseline object,
     * it was created as a new object, not renamed from an existing object!
     */
    @Test
    fun testScenarioF_renameFailsIfIdentityWasNotPresentInBaseline() {
        val runId = UUID.randomUUID().toString()
        val expected = ExpectedState(
            operation = VerificationOperation.RENAME,
            targetName = "CubeRenamed",
            targetType = "MESH"
        )
        val baseline = sampleBaseline(runId, listOf(ObjectObservation("id-orig", "Cube", "MESH", "Scene")))
        // In observed state, CubeRenamed has identity 'id-different', which was NEVER in baseline!
        val observed = sampleObserved(runId, listOf(ObjectObservation("id-different", "CubeRenamed", "MESH", "Scene")))

        val prov = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            snapshotVersion = 12L
        )
        val evidence = listOf(ProvenanceEvidenceObservation(provenance = prov, available = true))

        val result = reconciler.reconcileAndVerify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status)
        assertTrue(result.explanation.contains("was a creation, not a rename"))
    }

    /**
     * Scenario F2: Successful rename when identity matches.
     */
    @Test
    fun testScenarioF2_renameSucceedsWhenStableIdentityMatches() {
        val runId = UUID.randomUUID().toString()
        val expected = ExpectedState(
            operation = VerificationOperation.RENAME,
            targetName = "CubeRenamed",
            targetType = "MESH"
        )
        val baseline = sampleBaseline(runId, listOf(ObjectObservation("id-stable", "Cube", "MESH", "Scene")))
        val observed = sampleObserved(runId, listOf(ObjectObservation("id-stable", "CubeRenamed", "MESH", "Scene")))

        val prov = EvidenceProvenance(
            category = EvidenceCategory.DIRECT_APPLICATION_STATE,
            source = EvidenceSource.BLENDER,
            snapshotVersion = 13L
        )
        val evidence = listOf(ProvenanceEvidenceObservation(provenance = prov, available = true))

        val result = reconciler.reconcileAndVerify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, result.status)
    }
}
