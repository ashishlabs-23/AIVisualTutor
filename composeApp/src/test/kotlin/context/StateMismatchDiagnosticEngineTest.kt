package context

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StateMismatchDiagnosticEngineTest {

    private val engine = StateMismatchDiagnosticEngine()

    @Test
    fun `detects mode mismatch and suggests Tab shortcut`() {
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube",
            targetMode = "EDIT_MESH"
        )
        val observed = ObservedState(
            mode = "OBJECT",
            objects = listOf(ObjectObservation(name = "Cube", selected = true)),
            selectedObjects = setOf("Cube")
        )

        val report = engine.diagnose(expected, observed)

        assertTrue(report.hasMismatch)
        val mismatch = report.primaryMismatch
        assertNotNull(mismatch)
        assertEquals(MismatchCategory.MODE_MISMATCH, mismatch.category)
        assertEquals("EDIT_MESH", mismatch.expected)
        assertEquals("OBJECT", mismatch.observed)
        assertTrue(mismatch.explanation.contains("Expected mode 'EDIT_MESH'"))
        assertEquals("Tab", mismatch.suggestedAction.suggestedShortcut)
    }

    @Test
    fun `detects selection mismatch when wrong object is selected`() {
        val expected = ExpectedState(
            operation = VerificationOperation.SELECT,
            targetName = "Cube",
            expectedSelection = true
        )
        val observed = ObservedState(
            objects = listOf(
                ObjectObservation(name = "Cube", selected = false),
                ObjectObservation(name = "Camera", selected = true)
            ),
            selectedObjects = setOf("Camera")
        )

        val report = engine.diagnose(expected, observed)

        assertTrue(report.hasMismatch)
        val mismatch = report.primaryMismatch
        assertNotNull(mismatch)
        assertEquals(MismatchCategory.SELECTION_MISMATCH, mismatch.category)
        assertTrue(mismatch.explanation.contains("Expected 'Cube' to be selected"))
        assertEquals("SELECT_OBJECT", mismatch.suggestedAction.actionType)
    }

    @Test
    fun `detects missing object when CREATE operation was not executed`() {
        val expected = ExpectedState(
            operation = VerificationOperation.CREATE,
            targetName = "Sphere"
        )
        val observed = ObservedState(
            objects = listOf(ObjectObservation(name = "Cube"))
        )

        val report = engine.diagnose(expected, observed)

        assertTrue(report.hasMismatch)
        val mismatch = report.primaryMismatch
        assertNotNull(mismatch)
        assertEquals(MismatchCategory.OBJECT_MISSING, mismatch.category)
        assertEquals("CREATE_OBJECT", mismatch.suggestedAction.actionType)
    }

    @Test
    fun `detects transform unmodified when location did not change from baseline`() {
        val expected = ExpectedState(
            operation = VerificationOperation.MODIFY,
            targetName = "Cube"
        )
        val baseline = BaselineState(
            objects = listOf(ObjectObservation(name = "Cube", metadata = mapOf("location" to "0.0,0.0,0.0")))
        )
        val observed = ObservedState(
            objects = listOf(ObjectObservation(name = "Cube", metadata = mapOf("location" to "0.0,0.0,0.0")))
        )

        val report = engine.diagnose(expected, observed, baseline = baseline)

        assertTrue(report.hasMismatch)
        val mismatch = report.primaryMismatch
        assertNotNull(mismatch)
        assertEquals(MismatchCategory.TRANSFORM_UNMODIFIED, mismatch.category)
        assertEquals("APPLY_TRANSFORM", mismatch.suggestedAction.actionType)
    }

    @Test
    fun `detects stale telemetry observation exceeding max age`() {
        val expected = ExpectedState(
            operation = VerificationOperation.SELECT,
            targetName = "Cube"
        )
        val staleObserved = ObservedState(
            capturedAt = Instant.now().minusSeconds(3600), // 1 hour ago
            objects = listOf(ObjectObservation(name = "Cube", selected = true))
        )

        val report = engine.diagnose(expected, staleObserved)

        assertTrue(report.hasMismatch)
        assertEquals(MismatchCategory.STALE_TELEMETRY, report.primaryMismatch?.category)
    }

    @Test
    fun `reports no mismatch when state aligns with expectations`() {
        val expected = ExpectedState(
            operation = VerificationOperation.SELECT,
            targetName = "Cube",
            expectedSelection = true,
            targetMode = "OBJECT"
        )
        val observed = ObservedState(
            mode = "OBJECT",
            objects = listOf(ObjectObservation(name = "Cube", selected = true)),
            selectedObjects = setOf("Cube")
        )

        val report = engine.diagnose(expected, observed)

        assertFalse(report.hasMismatch)
        assertTrue(report.mismatches.isEmpty())
        assertNotNull(report.summary)
    }
}
