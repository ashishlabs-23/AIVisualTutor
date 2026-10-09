package context

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class BlenderTcpStateProviderTest {
    @Test
    fun readsACompleteBlenderSnapshotOverLoopback() = runBlocking {
        val sessionId = "blender-session"
        val sceneId = "$sessionId:scene:55"
        val objectId = "$sessionId:session:17"
        val response = listOf(
            listOf(
                "AIVT_STATE_V1",
                encode(sessionId),
                "2026-10-09T10:00:00Z",
                encode("5.2.2 LTS"),
                encode(sceneId),
                encode("Scene"),
                encode("OBJECT"),
                "true",
                "1"
            ).joinToString("\t"),
            listOf("OBJECT", encode(objectId), encode("Cube"), encode("MESH"), encode("Scene"), "true", encode("1,2,3"))
                .joinToString("\t"),
            "END",
            ""
        ).joinToString("\n")

        withAdapter(response) { port ->
            val result = BlenderTcpStateProvider(port).observe()

            assertEquals(BlenderObservationStatus.AVAILABLE, result.status)
            assertEquals(sessionId, result.sessionId)
            assertEquals(sceneId, result.sceneId)
            assertEquals("Scene", result.sceneName)
            assertEquals("5.2.2 LTS", result.metadata["blenderVersion"])
            assertEquals(setOf("Cube"), result.selectedObjects)
            assertEquals("1,2,3", result.objects.single().metadata["location"])
            assertEquals(objectId, result.objects.single().id)
        }
    }

    @Test
    fun incompleteInventoryIsNotReturnedAsAnAvailableSnapshot() = runBlocking {
        val response = listOf(
            listOf("AIVT_STATE_V1", encode("session"), "2026-10-09T10:00:00Z", encode("5.2.2"),
                encode("scene-id"), encode("Scene"), encode("OBJECT"), "false", "0").joinToString("\t"),
            "END",
            ""
        ).joinToString("\n")

        withAdapter(response) { port ->
            val result = BlenderTcpStateProvider(port).observe()

            assertEquals(BlenderObservationStatus.INSUFFICIENT, result.status)
            assertTrue(result.diagnostic.orEmpty().contains("incomplete"))
        }
    }

    @Test
    fun malformedProtocolIsAnExplicitFailure() = runBlocking {
        withAdapter("unexpected\n") { port ->
            val result = BlenderTcpStateProvider(port).observe()
            assertEquals(BlenderObservationStatus.FAILED, result.status)
            assertTrue(result.diagnostic.orEmpty().contains("protocol"))
        }
    }

    @Test
    fun missingAdapterIsUnavailableNotAnEmptyScene() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val port = server.localPort
            server.close()

            val result = BlenderTcpStateProvider(port).observe()

            assertEquals(BlenderObservationStatus.UNAVAILABLE, result.status)
            assertNull(result.sceneName)
            assertTrue(result.objects.isEmpty())
        }
    }

    @Test
    fun verificationRunCorrelatesSnapshotsAndRejectsOtherBlenderSessions() {
        val expected = ExpectedState(VerificationOperation.CREATE, targetName = "Cube")
        val deadline = Instant.parse("2026-10-09T10:00:10Z")
        val run = VerificationRun(
            id = "run-1",
            expected = expected,
            startedAt = Instant.parse("2026-10-09T10:00:00Z"),
            deadline = deadline
        )
        val baseline = availableSnapshot("session-1", Instant.parse("2026-10-09T10:00:01Z"))
        val updated = availableSnapshot(
            "session-1",
            Instant.parse("2026-10-09T10:00:03Z"),
            objects = listOf(ObjectObservation("session-1:object-1", "Cube", "MESH", "Scene", false))
        )
        val otherSession = availableSnapshot(
            "session-2",
            Instant.parse("2026-10-09T10:00:03Z"),
            objects = updated.objects
        )

        val correlated = run.withBaseline(baseline)

        assertEquals("session-1", correlated.applicationSessionId)
        assertEquals("run-1", correlated.baseline?.runId)
        assertNotNull(correlated.observedState(updated))
        assertNull(correlated.observedState(otherSession))
    }

    @Test
    fun liveBlenderSnapshotIsAvailableWhenExplicitlyOptedIn() = runBlocking {
        val executable = System.getenv("AIVT_BLENDER_EXECUTABLE")
        assumeTrue(
            "Set AIVT_BLENDER_EXECUTABLE and AIVT_BLENDER_LIVE_TEST=true to launch an isolated interactive Blender instance.",
            !executable.isNullOrBlank() && System.getenv("AIVT_BLENDER_LIVE_TEST") == "true"
        )

        val addonCandidates = listOf(
            java.nio.file.Path.of(System.getProperty("user.dir"), "blender", "ai_visual_tutor_state", "__init__.py"),
            java.nio.file.Path.of(System.getProperty("user.dir"), "..", "blender", "ai_visual_tutor_state", "__init__.py")
        ).map { it.normalize().toAbsolutePath() }
        val addon = addonCandidates.firstOrNull(java.nio.file.Files::isRegularFile)
            ?: error("Blender adapter not found in ${addonCandidates.joinToString()}")

        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val process = ProcessBuilder(executable!!, "--factory-startup", "--python", addon.toString())
            .redirectErrorStream(true)
            .apply { environment()["AIVT_BLENDER_STATE_PORT"] = port.toString() }
            .start()
        try {
            var observation: BlenderStateObservation? = null
            repeat(30) {
                if (observation?.status != BlenderObservationStatus.AVAILABLE) {
                    observation = BlenderTcpStateProvider(port, timeoutMillis = 1_000).observe()
                    if (observation?.status != BlenderObservationStatus.AVAILABLE) Thread.sleep(250)
                }
            }

            val snapshot = assertNotNull(observation)
            assertEquals(BlenderObservationStatus.AVAILABLE, snapshot.status, snapshot.diagnostic)
            assertEquals("Blender", snapshot.metadata["applicationName"])
            assertTrue(snapshot.sessionId.orEmpty().isNotBlank())
            assertTrue(snapshot.objects.isNotEmpty())
            assertTrue(snapshot.objects.any { it.name == "Cube" && it.type == "MESH" })
        } finally {
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private suspend fun withAdapter(response: String, action: suspend (Int) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val request = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
                    check(request == "AIVT_STATE_V1")
                    socket.getOutputStream().write(response.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().flush()
                }
            }
            worker.start()
            action(server.localPort)
            worker.join(2_000)
            assertTrue(!worker.isAlive, "Test adapter did not finish its request.")
        }
    }

    private fun availableSnapshot(
        sessionId: String,
        capturedAt: Instant,
        objects: List<ObjectObservation> = emptyList()
    ) = BlenderStateObservation(
        sceneName = "Scene",
        sceneId = "$sessionId:scene",
        objects = objects,
        status = BlenderObservationStatus.AVAILABLE,
        capturedAt = capturedAt,
        sessionId = sessionId,
        metadata = mapOf("complete" to "true", "applicationName" to "Blender")
    )

    private fun encode(value: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
