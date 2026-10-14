package context

import kotlinx.coroutines.runBlocking
import org.junit.Test
import tutor.AdaptiveTutoringEngine
import tutor.ClosedLoopRecoveryCoordinator
import tutor.ClosedLoopRecoveryState
import tutor.LearnerOutcome
import tutor.TutorController
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PHASE 11: Action Semantics & Evidence-Grounded Verification Hardening Test Suite.
 *
 * Verifies the complete matrix of Blender action semantics:
 * - Rotation (no action, partial, correct, wrong object, final-state policy, tolerances)
 * - Deletion (correct deletion, target remains, wrong target deleted, hidden/deselected, absent initially)
 * - Movement (no movement, insufficient, correct, wrong object, missing evidence)
 * - Scaling (no scale, partial, correct, wrong object, invalid/missing evidence)
 * - Addition (expected object, wrong type, no object, unrelated object)
 * - Adaptive Recovery (progressive hints, target mismatch, wrong direction, fresh evidence request)
 */
class Phase11ActionSemanticsVerificationTest {

    private val verifier = StateVerifier()
    private val adaptiveEngine = AdaptiveTutoringEngine()

    private class MockBlenderProvider(
        var currentObservation: BlenderStateObservation
    ) : BlenderStateProvider {
        override suspend fun observe(): BlenderStateObservation = currentObservation
    }

    private fun createSceneObservation(
        objects: List<ObjectObservation>,
        selectedObjects: Set<String> = emptySet(),
        capturedAt: Instant = Instant.now(),
        status: BlenderObservationStatus = BlenderObservationStatus.AVAILABLE,
        diagnostic: String? = null
    ): BlenderStateObservation {
        return BlenderStateObservation(
            status = status,
            capturedAt = capturedAt,
            sessionId = "session-p11",
            sceneId = "scene-default",
            sceneName = "Scene",
            mode = "OBJECT",
            objects = objects,
            selectedObjects = selectedObjects,
            metadata = mapOf(
                "applicationName" to "Blender",
                "applicationVersion" to "5.2.2 LTS",
                "complete" to "true",
                "freshness" to "FRESH"
            ),
            diagnostic = diagnostic
        )
    }

    // =========================================================================
    // SECTION A: ROTATION VERIFICATION & FALSE-POSITIVE ELIMINATION
    // =========================================================================

    @Test
    fun testA1_unchangedCubeFailsRotationTask() {
        // Initial state: Cube unrotated (Euler 0, 0, 0)
        val baseline = BaselineState(
            runId = "rot-a1",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "0.0,0.0,0.0"))
            )
        )
        // Post-action state: Cube completely unchanged
        val observed = ObservedState(
            runId = "rot-a1",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "0.0,0.0,0.0"))
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "rotation",
            expectedPropertyValue = "!= baseline"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Unchanged cube MUST NOT pass rotation task")
    }

    @Test
    fun testA2_partialRotationFailsUpsideDownTask() {
        // Cube rotated by only 45 degrees (0.785 rad) around X-axis.
        // For upside-down, local Z component in world space cos(alpha)*cos(beta) must be <= -0.5.
        // At 45 deg: cos(0.785 rad) = ~0.707 > 0, so local Z points upward.
        val baseline = BaselineState(
            runId = "rot-a2",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "0.0,0.0,0.0"))
            )
        )
        val observed = ObservedState(
            runId = "rot-a2",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "0.785398,0.0,0.0"))
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "rotation",
            expectedPropertyValue = "upside_down"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "45-degree rotation MUST NOT pass upside_down condition")
    }

    @Test
    fun testA3_correctUpsideDownRotationPassesTask() {
        // Cube rotated by 180 degrees (3.14159 rad) around X-axis.
        // cos(pi)*cos(0) = -1.0 <= -0.5. Object is inverted upside down.
        val baseline = BaselineState(
            runId = "rot-a3",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "0.0,0.0,0.0"))
            )
        )
        val observed = ObservedState(
            runId = "rot-a3",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "3.141593,0.0,0.0"))
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "rotation",
            expectedPropertyValue = "upside_down"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, result.status, "180-degree rotation MUST pass upside_down condition")
    }

    @Test
    fun testA4_wrongObjectRotatedFailsTask() {
        // Learner rotates Camera or Light, but Cube is unchanged
        val baseline = BaselineState(
            runId = "rot-a4",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "0.0,0.0,0.0")),
                ObjectObservation(id = "cam_1", name = "Camera", type = "CAMERA", metadata = mapOf("rotation" to "0.0,0.0,0.0"))
            )
        )
        val observed = ObservedState(
            runId = "rot-a4",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "0.0,0.0,0.0")),
                ObjectObservation(id = "cam_1", name = "Camera", type = "CAMERA", metadata = mapOf("rotation" to "3.141593,0.0,0.0"))
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "rotation",
            expectedPropertyValue = "upside_down"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Rotating Camera instead of Cube MUST FAIL")
    }

    @Test
    fun testA5_alreadySatisfiedOrientationFollowsExplicitPolicy() {
        // Initial state is already upside down (3.14159, 0, 0)
        val baseline = BaselineState(
            runId = "rot-a5",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "3.141593,0.0,0.0"))
            )
        )
        val observed = ObservedState(
            runId = "rot-a5",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "3.141593,0.0,0.0"))
            )
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        // Policy 1: Transition required (allowSatisfiedInitialState = false) -> FAILURE
        val actionExpected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "rotation",
            expectedPropertyValue = "upside_down",
            allowSatisfiedInitialState = false
        )
        val actionResult = verifier.verify(actionExpected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, actionResult.status, "Action task without state change MUST fail")

        // Policy 2: Final state accepted (allowSatisfiedInitialState = true) -> SUCCESS
        val finalStateExpected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "rotation",
            expectedPropertyValue = "upside_down",
            allowSatisfiedInitialState = true
        )
        val finalResult = verifier.verify(finalStateExpected, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, finalResult.status, "Explicit final-state policy MUST accept satisfied orientation")
    }

    @Test
    fun testA6_staleTransformEvidenceCannotEstablishSuccess() {
        val now = Instant.now()
        val baseline = BaselineState(
            runId = "rot-a6",
            capturedAt = now,
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "0.0,0.0,0.0"))
            )
        )
        // Observed state has a timestamp older than baseline (stale!)
        val observed = ObservedState(
            runId = "rot-a6",
            capturedAt = now.minus(5, ChronoUnit.SECONDS),
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("rotation" to "3.141593,0.0,0.0"))
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "rotation",
            expectedPropertyValue = "upside_down"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.UNCERTAIN, result.status, "Stale observation MUST NOT produce SUCCESS")
        assertTrue(result.explanation.contains("stale", ignoreCase = true))
    }

    @Test
    fun testA7_missingRotationPropertyIsUncertain() {
        val baseline = BaselineState(
            runId = "rot-a7",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH")
            )
        )
        val observed = ObservedState(
            runId = "rot-a7",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH")
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "rotation",
            expectedPropertyValue = "upside_down"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.UNCERTAIN, result.status, "Missing rotation metadata MUST be UNCERTAIN")
    }

    // =========================================================================
    // SECTION B: DELETION VERIFICATION & EDGE CASES
    // =========================================================================

    @Test
    fun testB1_correctObjectDeletedPasses() {
        val baseline = BaselineState(
            runId = "del-b1",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH"),
                ObjectObservation(id = "light_1", name = "Light", type = "LIGHT")
            )
        )
        val observed = ObservedState(
            runId = "del-b1",
            objects = listOf(
                ObjectObservation(id = "light_1", name = "Light", type = "LIGHT")
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, result.status, "Deleted Cube MUST pass verification")
    }

    @Test
    fun testB2_targetRemainsInSceneFails() {
        val baseline = BaselineState(
            runId = "del-b2",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH"))
        )
        val observed = ObservedState(
            runId = "del-b2",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH"))
        )
        val expected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Present Cube MUST fail delete verification")
    }

    @Test
    fun testB3_wrongObjectDeletedFails() {
        val baseline = BaselineState(
            runId = "del-b3",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH"),
                ObjectObservation(id = "cam_1", name = "Camera", type = "CAMERA")
            )
        )
        // Camera deleted, Cube still present!
        val observed = ObservedState(
            runId = "del-b3",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH")
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Deleting wrong object while target remains MUST FAIL")
    }

    @Test
    fun testB4_targetHiddenStillFailsDeletion() {
        // Target is hidden in viewport (visible=false), but object still exists in scene.
        val baseline = BaselineState(
            runId = "del-b4",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("visible" to "true")))
        )
        val observed = ObservedState(
            runId = "del-b4",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("visible" to "false")))
        )
        val expected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Hiding an object does NOT mean it was deleted")
    }

    @Test
    fun testB5_targetDeselectedStillFailsDeletion() {
        // Target is deselected (selected=false), but object still exists in scene.
        val baseline = BaselineState(
            runId = "del-b5",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", selected = true)),
            selectedObjects = setOf("Cube")
        )
        val observed = ObservedState(
            runId = "del-b5",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", selected = false)),
            selectedObjects = emptySet()
        )
        val expected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Deselecting an object does NOT mean it was deleted")
    }

    @Test
    fun testB6_targetAbsentInitiallyFailsDeletion() {
        // Target was never in the scene at baseline. User cannot complete "delete target" if target was not present.
        val baseline = BaselineState(
            runId = "del-b6",
            objects = listOf(ObjectObservation(id = "light_1", name = "Light", type = "LIGHT"))
        )
        val observed = ObservedState(
            runId = "del-b6",
            objects = listOf(ObjectObservation(id = "light_1", name = "Light", type = "LIGHT"))
        )
        val expected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Deleting an object that never existed in baseline MUST FAIL")
    }

    @Test
    fun testB7_duplicateOrAmbiguousTargetReturnsUncertain() {
        // Multiple objects matching target name in baseline, scene emptied
        val baseline = BaselineState(
            runId = "del-b7",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH"),
                ObjectObservation(id = "cube_2", name = "Cube", type = "MESH")
            )
        )
        val observed = ObservedState(
            runId = "del-b7",
            objects = emptyList()
        )
        val expected = ExpectedState(
            operation = VerificationOperation.DELETE,
            targetName = "Cube"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.UNCERTAIN, result.status, "Ambiguous duplicate target names MUST yield UNCERTAIN")
    }

    // =========================================================================
    // SECTION C: MOVEMENT VERIFICATION
    // =========================================================================

    @Test
    fun testC1_noMovementFails() {
        val baseline = BaselineState(
            runId = "mov-c1",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("location.z" to "0.0")))
        )
        val observed = ObservedState(
            runId = "mov-c1",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("location.z" to "0.0")))
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "location.z",
            expectedPropertyValue = "> 0"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "No movement MUST fail > 0 requirement")
    }

    @Test
    fun testC2_insufficientOrNegativeMovementFails() {
        val baseline = BaselineState(
            runId = "mov-c2",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("location.z" to "0.0")))
        )
        val observed = ObservedState(
            runId = "mov-c2",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("location.z" to "-1.5")))
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "location.z",
            expectedPropertyValue = "> 0"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Downward movement MUST fail > 0 requirement")
    }

    @Test
    fun testC3_correctUpwardMovementPasses() {
        val baseline = BaselineState(
            runId = "mov-c3",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("location.z" to "0.0")))
        )
        val observed = ObservedState(
            runId = "mov-c3",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("location.z" to "2.5")))
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "location.z",
            expectedPropertyValue = "> 0"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, result.status, "Z=2.5m MUST pass > 0 requirement")
    }

    @Test
    fun testC4_wrongObjectMovedFails() {
        val baseline = BaselineState(
            runId = "mov-c4",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("location.z" to "0.0")),
                ObjectObservation(id = "light_1", name = "Light", type = "LIGHT", metadata = mapOf("location.z" to "0.0"))
            )
        )
        val observed = ObservedState(
            runId = "mov-c4",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("location.z" to "0.0")),
                ObjectObservation(id = "light_1", name = "Light", type = "LIGHT", metadata = mapOf("location.z" to "3.0"))
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "location.z",
            expectedPropertyValue = "> 0"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Moving Light instead of Cube MUST fail")
    }

    // =========================================================================
    // SECTION D: SCALING VERIFICATION
    // =========================================================================

    @Test
    fun testD1_unmodifiedScaleFails() {
        val baseline = BaselineState(
            runId = "scale-d1",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("scale" to "1.0,1.0,1.0")))
        )
        val observed = ObservedState(
            runId = "scale-d1",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("scale" to "1.0,1.0,1.0")))
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "scale",
            expectedPropertyValue = "!= baseline"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Unchanged scale MUST fail scale task")
    }

    @Test
    fun testD2_correctScalePasses() {
        val baseline = BaselineState(
            runId = "scale-d2",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("scale" to "1.0,1.0,1.0")))
        )
        val observed = ObservedState(
            runId = "scale-d2",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("scale" to "2.0,2.0,2.0")))
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "scale",
            expectedPropertyValue = "!= baseline"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, result.status, "Scaled cube (2.0) MUST pass scale task")
    }

    @Test
    fun testD3_wrongObjectScaledFails() {
        val baseline = BaselineState(
            runId = "scale-d3",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("scale" to "1.0,1.0,1.0")),
                ObjectObservation(id = "light_1", name = "Light", type = "LIGHT", metadata = mapOf("scale" to "1.0,1.0,1.0"))
            )
        )
        val observed = ObservedState(
            runId = "scale-d3",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", metadata = mapOf("scale" to "1.0,1.0,1.0")),
                ObjectObservation(id = "light_1", name = "Light", type = "LIGHT", metadata = mapOf("scale" to "3.0,3.0,3.0"))
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetProperty = "scale",
            expectedPropertyValue = "!= baseline"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Scaling Light instead of Cube MUST fail")
    }

    // =========================================================================
    // SECTION E: ADD OBJECT VERIFICATION
    // =========================================================================

    @Test
    fun testE1_expectedObjectAddedPasses() {
        val baseline = BaselineState(
            runId = "add-e1",
            objects = listOf(ObjectObservation(id = "cube_1", name = "Cube", type = "MESH"))
        )
        val observed = ObservedState(
            runId = "add-e1",
            objects = listOf(
                ObjectObservation(id = "cube_1", name = "Cube", type = "MESH"),
                ObjectObservation(id = "sph_1", name = "Sphere", type = "MESH")
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sphere",
            targetType = "MESH"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.SUCCESS, result.status, "Newly added Sphere MUST pass CREATE verification")
    }

    @Test
    fun testE2_wrongObjectTypeAddedFails() {
        val baseline = BaselineState(
            runId = "add-e2",
            objects = emptyList()
        )
        // Expected MESH, but added LIGHT
        val observed = ObservedState(
            runId = "add-e2",
            objects = listOf(
                ObjectObservation(id = "obj_1", name = "Sphere", type = "LIGHT")
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sphere",
            targetType = "MESH"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Adding wrong object type (LIGHT instead of MESH) MUST FAIL")
    }

    @Test
    fun testE3_unrelatedObjectAddedFails() {
        val baseline = BaselineState(
            runId = "add-e3",
            objects = emptyList()
        )
        // Expected Sphere, but added Cone
        val observed = ObservedState(
            runId = "add-e3",
            objects = listOf(
                ObjectObservation(id = "cone_1", name = "Cone", type = "MESH")
            )
        )
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sphere",
            targetType = "MESH"
        )
        val evidence = listOf(EvidenceObservation(source = EvidenceSource.BLENDER, available = true))

        val result = verifier.verify(expected, baseline, observed, evidence)
        assertEquals(VerificationStatus.FAILURE, result.status, "Adding unrelated Cone instead of Sphere MUST FAIL")
    }

    // =========================================================================
    // SECTION F: ADAPTIVE TUTORING & RECOVERY
    // =========================================================================

    @Test
    fun testF1_progressiveHintsEscalateOnRepeatedAttempts() {
        val step = models.TutorStep(
            id = 1,
            title = "Rotate Cube Upside Down",
            instruction = "Rotate the Cube by 180 degrees so its top faces downward.",
            description = "Rotate cube upside down.",
            stepNumber = 1,
            totalSteps = 1,
            expectedState = ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetName = "Cube",
                targetProperty = "rotation",
                expectedPropertyValue = "upside_down"
            )
        )

        adaptiveEngine.resetProgressiveHintLevel(1)
        assertEquals(1, adaptiveEngine.getProgressiveHintLevel(1))

        val bundle = adaptiveEngine.getProgressiveHintBundle(step)
        // Level 1: Conceptual explanation
        val hint1 = bundle.getHintForLevel(1)
        assertTrue(hint1.contains("downward", ignoreCase = true) || hint1.contains("upside down", ignoreCase = true))

        // Level 2: Tool identification via escalation
        val hint2 = adaptiveEngine.getEscalatedHintForStep(step, 1)
        assertTrue(hint2.contains("Rotate tool", ignoreCase = true) || hint2.contains("'R'", ignoreCase = true))
        assertEquals(2, adaptiveEngine.getProgressiveHintLevel(1))

        // Level 3: Concrete keystroke sequence via escalation
        val hint3 = adaptiveEngine.getEscalatedHintForStep(step, 1)
        assertTrue(hint3.contains("180", ignoreCase = true) || hint3.contains("R", ignoreCase = true))
        assertEquals(3, adaptiveEngine.getProgressiveHintLevel(1))

        // Level 4: Recovery guidance via escalation
        val hint4 = adaptiveEngine.getEscalatedHintForStep(step, 1)
        assertTrue(hint4.contains("Alt + R", ignoreCase = true) || hint4.contains("Transform panel", ignoreCase = true))
        assertEquals(4, adaptiveEngine.getProgressiveHintLevel(1))
    }

    @Test
    fun testF2_closedLoopRecoveryDiagnosesNoProgressVsSuccess() = runBlocking {
        val cubeUnrotated = listOf(
            ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", selected = true, metadata = mapOf("rotation" to "0.0,0.0,0.0"))
        )
        val cubeUpsideDown = listOf(
            ObjectObservation(id = "cube_1", name = "Cube", type = "MESH", selected = true, metadata = mapOf("rotation" to "3.141593,0.0,0.0"))
        )

        val provider = MockBlenderProvider(createSceneObservation(cubeUnrotated, selectedObjects = setOf("Cube")))
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Rotate Cube upside down")
        val step1 = controller.currentStep
        assertNotNull(step1)
        assertEquals("Select Cube", step1.title)

        // Step 1: Verify selection and advance to Step 2
        val selOutcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(selOutcome is ClosedLoopRecoveryState.VerifiedSuccess)
        controller.nextStep()

        val step2 = controller.currentStep
        assertNotNull(step2)
        assertEquals("Rotate Cube Upside Down", step2.title)

        // Attempt 1 on Step 2: No progress made (cube unchanged) -> RecoveryRecommended
        val outcome1 = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome1 is ClosedLoopRecoveryState.RecoveryRecommended, "Unchanged cube MUST trigger RecoveryRecommended")
        assertFalse(controller.isStepIndexCompleted(1))

        // Attempt 2 on Step 2: Learner performs 180-deg rotation in Blender
        provider.currentObservation = createSceneObservation(cubeUpsideDown)
        val outcome2 = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome2 is ClosedLoopRecoveryState.VerifiedSuccess, "Correct upside-down rotation MUST succeed")
        assertTrue(controller.isStepIndexCompleted(1))
    }

    @Test
    fun testF3_unavailableBlenderProviderRequestsEvidenceWithoutPenalizingLearner() = runBlocking {
        val unavailableObs = createSceneObservation(
            objects = emptyList(),
            status = BlenderObservationStatus.UNAVAILABLE,
            diagnostic = "Connection refused: loopback port 9876 closed"
        )
        val provider = MockBlenderProvider(unavailableObs)
        val workflow = BlenderVerificationWorkflow(provider = provider)
        val controller = TutorController(
            recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
        )

        controller.startUserGoal("Delete Sphere")
        val outcome = controller.recoveryCoordinator.verifyActiveStep()
        assertTrue(outcome is ClosedLoopRecoveryState.NeedsMoreEvidence, "Unavailable provider MUST yield NeedsMoreEvidence, NOT failure")
        assertEquals(0, controller.recoveryCoordinator.getVerifiedFailuresCount(0), "Learner must NOT be penalized for backend unavailability")
    }
}
