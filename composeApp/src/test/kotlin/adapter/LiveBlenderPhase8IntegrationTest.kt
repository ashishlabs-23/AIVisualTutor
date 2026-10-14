package adapter

import context.BlenderObservationStatus
import context.BlenderVerificationWorkflow
import context.ExpectedState
import context.VerificationOperation
import context.VerificationStatus
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

class LiveBlenderPhase8IntegrationTest {

    private val blenderExe = System.getenv("AIVT_BLENDER_EXECUTABLE")
        ?.takeIf(String::isNotBlank)
        ?: "C:\\Program Files\\Blender Foundation\\Blender 5.2\\blender.exe"

    @Test
    fun testPhase8HttpHealthAndVersionedSnapshot() = runBlocking {
        withLiveBlender { port, _ ->
            val adapter = BlenderApplicationAdapter(port = port)

            // Health check
            val health = adapter.checkHealth()
            println("Phase 8 Live Health: ${health.status} on port $port")
            assertEquals(AdapterHealthStatus.HEALTHY, health.status)
            assertEquals("Blender", health.applicationName)
            assertNotNull(health.sessionId)

            // Take versioned snapshot
            val result = adapter.takeSnapshot()
            assertTrue(result is AdapterSnapshotResult.Success)

            val snapshot = result.snapshot
            println("Phase 8 Live Snapshot version: ${snapshot.snapshotVersion}, objects: ${snapshot.objects.size}")
            assertEquals(1, snapshot.schemaVersion)
            assertEquals("Blender", snapshot.applicationName)
            assertEquals(1L, snapshot.snapshotVersion)
            assertEquals("Scene", snapshot.scene.name)
            assertEquals("OBJECT", snapshot.activeMode)
            assertTrue(snapshot.objects.any { it.name == "Cube" && it.type == "MESH" })

            val cube = snapshot.objects.first { it.name == "Cube" }
            assertNotNull(cube.location)
            assertTrue(cube.identity.startsWith("session:${snapshot.sessionId}"))
        }
    }

    @Test
    fun testPhase8ObjectCreationThroughHttpAdapter() = runBlocking {
        withLiveBlender { port, triggerFile ->
            val adapter = BlenderApplicationAdapter(port = port)
            val workflow = BlenderVerificationWorkflow(applicationAdapter = adapter)

            val expected = ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Suzanne",
                targetType = "MESH"
            )

            // 1. Capture baseline
            val run = workflow.captureBaseline(expected)
            println("Phase 8 Baseline captured: scene=${run.baseline?.sceneName}, objects=${run.baseline?.objects?.size}")
            assertTrue(run.baseline?.objects?.none { it.name == "Suzanne" } == true)

            // 2. Trigger creation
            triggerAction(triggerFile, "import bpy\nbpy.ops.mesh.primitive_monkey_add()")
            Thread.sleep(600)

            // 3. Verify action
            val result = workflow.verifyAction(run)
            println("Phase 8 Live Creation Result: ${result.status} - ${result.explanation}")
            assertEquals(VerificationStatus.SUCCESS, result.status)
        }
    }

    @Test
    fun testPhase8ObjectRenamingWithStableIdentity() = runBlocking {
        withLiveBlender { port, triggerFile ->
            val adapter = BlenderApplicationAdapter(port = port)
            val workflow = BlenderVerificationWorkflow(applicationAdapter = adapter)

            val expected = ExpectedState(
                operation = VerificationOperation.RENAME,
                targetName = "CubeHero",
                targetType = "MESH"
            )

            val run = workflow.captureBaseline(expected)

            // Trigger rename in Blender
            triggerAction(triggerFile, "import bpy\nobj = bpy.data.objects.get('Cube')\nif obj: obj.name = 'CubeHero'")
            Thread.sleep(600)

            val result = workflow.verifyAction(run)
            println("Phase 8 Live Rename Result: ${result.status} - ${result.explanation}")
            assertEquals(VerificationStatus.SUCCESS, result.status)
        }
    }

    @Test
    fun testPhase8ObjectDeletion() = runBlocking {
        withLiveBlender { port, triggerFile ->
            val adapter = BlenderApplicationAdapter(port = port)
            val workflow = BlenderVerificationWorkflow(applicationAdapter = adapter)

            val expected = ExpectedState(
                operation = VerificationOperation.DELETE,
                targetName = "Cube"
            )

            val run = workflow.captureBaseline(expected)

            // Trigger deletion in Blender
            triggerAction(triggerFile, "import bpy\nobj = bpy.data.objects.get('Cube')\nif obj:\n    bpy.data.objects.remove(obj, do_unlink=True)")
            Thread.sleep(600)

            val result = workflow.verifyAction(run)
            println("Phase 8 Live Deletion Result: ${result.status} - ${result.explanation}")
            assertEquals(VerificationStatus.SUCCESS, result.status)
        }
    }

    private fun triggerAction(triggerFile: File, pythonCode: String) {
        triggerFile.writeText(pythonCode)
    }

    private suspend fun withLiveBlender(block: suspend (port: Int, triggerFile: File) -> Unit) {
        assumeTrue("Blender executable must exist at $blenderExe", Files.isRegularFile(Path.of(blenderExe)))
        val addonCandidates = listOf(
            Path.of(System.getProperty("user.dir"), "blender", "ai_visual_tutor_state", "__init__.py"),
            Path.of(System.getProperty("user.dir"), "..", "blender", "ai_visual_tutor_state", "__init__.py")
        ).map { it.normalize().toAbsolutePath() }
        val addon = addonCandidates.firstOrNull(Files::isRegularFile)
            ?: error("Blender adapter not found in ${addonCandidates.joinToString()}")

        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val triggerDir = Files.createTempDirectory("aivt_phase8_trigger").toFile()
        val triggerFile = File(triggerDir, "action.py")
        val runnerScript = File(triggerDir, "runner.py")
        runnerScript.writeText(
            """
            import os, bpy
            def poll_actions():
                action_path = r"${triggerFile.absolutePath}"
                if os.path.exists(action_path):
                    try:
                        with open(action_path, 'r', encoding='utf-8') as f:
                            code = f.read()
                        os.remove(action_path)
                        exec(code, globals())
                    except Exception as e:
                        print("TRIGGER_ERROR:", e)
                return 0.1

            bpy.app.timers.register(poll_actions, first_interval=0.1, persistent=True)
            """.trimIndent()
        )

        val process = ProcessBuilder(
            blenderExe,
            "--factory-startup",
            "--python", addon.toString(),
            "--python", runnerScript.absolutePath
        )
            .redirectErrorStream(true)
            .apply { environment()["AIVT_BLENDER_STATE_PORT"] = port.toString() }
            .start()

        try {
            val adapter = BlenderApplicationAdapter(port = port, timeoutMillis = 500)
            var ready = false
            for (i in 1..60) {
                val health = adapter.checkHealth()
                if (health.status == AdapterHealthStatus.HEALTHY) {
                    ready = true
                    break
                }
                Thread.sleep(200)
            }
            assertTrue(ready, "Blender Phase 8 adapter failed to become healthy on port $port")

            block(port, triggerFile)
        } finally {
            process.destroyForcibly()
            triggerDir.deleteRecursively()
        }
    }
}
