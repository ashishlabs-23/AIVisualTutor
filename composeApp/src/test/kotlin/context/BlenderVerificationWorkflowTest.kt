package context

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BlenderVerificationWorkflowTest {
    @Test
    fun baselineActionVerificationPersistsEvidenceAndDecision() = runBlocking {
        withTempDirectory { root ->
            val snapshots = listOf(
                snapshot(
                    capturedAt = Instant.parse("2026-10-09T10:00:00Z"),
                    objects = listOf(ObjectObservation("camera", "Camera", "CAMERA", "Scene", false))
                ),
                snapshot(
                    capturedAt = Instant.parse("2026-10-09T10:00:02Z"),
                    objects = listOf(
                        ObjectObservation("camera", "Camera", "CAMERA", "Scene", false),
                        ObjectObservation("cube", "Cube", "MESH", "Scene", true)
                    )
                )
            )
            val workflow = BlenderVerificationWorkflow(
                provider = SequenceBlenderProvider(snapshots),
                resultStore = VerificationResultStore(root),
                runWindow = Duration.ofMinutes(1)
            )

            val run = workflow.captureBaseline(ExpectedState(VerificationOperation.CREATE, "Cube", "MESH"))
            val result = workflow.verifyAction(run)
            val persisted = Files.readString(root.resolve("${run.id}.json"))

            assertEquals(VerificationStatus.SUCCESS, result.status)
            assertEquals(run.id, result.runId)
            assertTrue(persisted.contains("\"status\": \"SUCCESS\""))
            assertTrue(persisted.contains("\"source\":\"BLENDER\""))
            assertTrue(persisted.contains("\"type\":\"OBJECT_ADDED\""))
        }
    }

    @Test
    fun providerBecomingUnavailableAfterBaselineProducesPersistedUncertainty() = runBlocking {
        withTempDirectory { root ->
            val workflow = BlenderVerificationWorkflow(
                provider = SequenceBlenderProvider(
                    listOf(
                        snapshot(capturedAt = Instant.parse("2026-10-09T10:00:00Z")),
                        BlenderStateObservation(
                            status = BlenderObservationStatus.UNAVAILABLE,
                            capturedAt = Instant.parse("2026-10-09T10:00:01Z"),
                            diagnostic = "adapter disconnected",
                            metadata = mapOf("complete" to "false")
                        )
                    )
                ),
                resultStore = VerificationResultStore(root)
            )

            val run = workflow.captureBaseline(ExpectedState(VerificationOperation.CREATE, "Cube", "MESH"))
            val result = workflow.verifyAction(run)

            assertEquals(VerificationStatus.UNCERTAIN, result.status)
            assertEquals("adapter disconnected", result.evidence.single().diagnostic)
            assertTrue(Files.exists(root.resolve("${run.id}.json")))
        }
    }

    @Test
    fun incompleteBaselineIsRejectedInsteadOfInventingAnEmptyScene() = runBlocking {
        withTempDirectory { root ->
            val workflow = BlenderVerificationWorkflow(
                provider = SequenceBlenderProvider(
                    listOf(snapshot(complete = false, capturedAt = Instant.parse("2026-10-09T10:00:00Z")))
                ),
                resultStore = VerificationResultStore(root)
            )
            assertFailsWith<IllegalArgumentException> {
                workflow.captureBaseline(ExpectedState(VerificationOperation.CREATE, "Cube", "MESH"))
            }
        }
    }

    private fun snapshot(
        capturedAt: Instant,
        objects: List<ObjectObservation> = emptyList(),
        complete: Boolean = true
    ) = BlenderStateObservation(
        sceneName = "Scene",
        sceneId = "test-session:scene",
        mode = "OBJECT",
        objects = objects,
        selectedObjects = objects.filter { it.selected == true }.mapNotNull { it.name }.toSet(),
        status = if (complete) BlenderObservationStatus.AVAILABLE else BlenderObservationStatus.INSUFFICIENT,
        capturedAt = capturedAt,
        sessionId = "test-session",
        metadata = mapOf("complete" to complete.toString(), "applicationName" to "Blender"),
        diagnostic = if (complete) null else "partial inventory"
    )

    private suspend fun withTempDirectory(action: suspend (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("aivt-verification-test")
        try {
            action(root)
        } finally {
            Files.list(root).use { children -> children.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(root)
        }
    }

    private class SequenceBlenderProvider(
        private val observations: List<BlenderStateObservation>
    ) : BlenderStateProvider {
        private var nextIndex = 0

        override suspend fun observe(): BlenderStateObservation =
            observations.getOrNull(nextIndex++) ?: error("Unexpected extra Blender observation request.")
    }
}
