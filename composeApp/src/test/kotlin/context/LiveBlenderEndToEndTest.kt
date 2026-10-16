package context

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

import org.junit.Assume.assumeTrue

class LiveBlenderEndToEndTest {

    private val localAppData = System.getenv("LOCALAPPDATA")
        ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
    private val runStoreDir = Path.of(localAppData, "AIVisualTutor", "verification-runs")
    private val blenderExe = System.getenv("AIVT_BLENDER_EXECUTABLE")
        ?.takeIf(String::isNotBlank)
        ?: "C:\\Program Files\\Blender Foundation\\Blender 5.2\\blender.exe"

    @Test
    fun testLiveBlenderConnectionAndSnapshot() = runBlocking {
        withLiveInteractiveBlender { port, _ ->
            val provider = BlenderTcpStateProvider(port)
            val observation = provider.observe()
            println("=== LIVE BLENDER OBSERVATION ===")
            println("Status: ${observation.status}")
            println("SessionId: ${observation.sessionId}")
            println("SceneName: ${observation.sceneName}")
            println("Object Count: ${observation.objects.size}")
            observation.objects.forEach { obj ->
                println("  - Name: ${obj.name}, Type: ${obj.type}, Selected: ${obj.selected}, Location: ${obj.metadata["location"]}")
            }

            assertEquals(BlenderObservationStatus.AVAILABLE, observation.status, observation.diagnostic)
            assertNotNull(observation.sessionId)
            assertEquals("Scene", observation.sceneName)
            assertTrue(observation.objects.any { it.name == "Cube" && it.type == "MESH" })
        }
    }

    @Test
    fun testPhase6NoActionDetectionAgainstLiveBlender() = runBlocking {
        withLiveInteractiveBlender { port, _ ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            // Phase 6: Action not performed
            // Expected: CREATE Cylinder MESH
            val expected = ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Cylinder",
                targetType = "MESH"
            )

            // 1. Capture baseline from live Blender
            val run = workflow.captureBaseline(expected)
            assertNotNull(run.baseline)
            println("Baseline captured for run ${run.id}: ${run.baseline?.objects?.size} objects in scene '${run.baseline?.sceneName}'")
            assertTrue(run.baseline!!.objects.none { it.name == "Cylinder" }, "Cylinder should not exist in baseline")

            // 2. Do not create the cylinder in Blender (no-action).

            // 3. Verify action from live Blender
            val result = workflow.verifyAction(run)
            println("Verification decision for unperformed action: ${result.status} - ${result.explanation}")

            // Expected result: Must NOT be SUCCESS
            assertNotEquals(VerificationStatus.SUCCESS, result.status)
            assertEquals(VerificationStatus.FAILURE, result.status)
            assertTrue(result.explanation.contains("Cylinder") || result.unsatisfiedConditions.isNotEmpty())

            // 4. Verify persistence to %LOCALAPPDATA%\AIVisualTutor\verification-runs\
            val runRecordPath = runStoreDir.resolve("${run.id}.json")
            assertTrue(Files.exists(runRecordPath), "Verification record must exist at $runRecordPath")

            val recordJson = Files.readString(runRecordPath)
            assertTrue(recordJson.contains("\"runId\": \"${run.id}\""))
            assertTrue(recordJson.contains("\"status\": \"FAILURE\""))
            assertTrue(recordJson.contains("\"source\":\"BLENDER\""))
        }
    }

    @Test
    fun testPhase5SuccessObjectCreationInLiveBlender() = runBlocking {
        withLiveInteractiveBlender { port, triggerFile ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            // Step 1: Expected CREATE Sphere MESH
            val expected = ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Sphere",
                targetType = "MESH"
            )

            // Step 2: Capture baseline from live Blender
            val run = workflow.captureBaseline(expected)
            assertNotNull(run.baseline)
            println("Baseline object count: ${run.baseline?.objects?.size}")
            assertTrue(run.baseline!!.objects.none { it.name == "Sphere" })

            // Step 3: Trigger Blender to create UV Sphere
            triggerAction(triggerFile, "import bpy; bpy.ops.mesh.primitive_uv_sphere_add()")

            // Wait for Blender to process the operator
            Thread.sleep(600)

            // Step 4: Verify action from live Blender
            val result = workflow.verifyAction(run)
            println("Phase 5 Result: ${result.status} - ${result.explanation}")

            // Assert SUCCESS
            assertEquals(VerificationStatus.SUCCESS, result.status)
            assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_ADDED && it.subject == "Sphere" })
            assertTrue(result.satisfiedConditions.any { it.field == ExpectedStateField.OBJECT_EXISTS })
            assertTrue(result.satisfiedConditions.any { it.field == ExpectedStateField.OBJECT_TYPE })

            // Assert persistence
            val runRecordPath = runStoreDir.resolve("${run.id}.json")
            assertTrue(Files.exists(runRecordPath))
            val json = Files.readString(runRecordPath)
            assertTrue(json.contains("\"status\": \"SUCCESS\""))
            assertTrue(json.contains("\"type\":\"OBJECT_ADDED\""))
        }
    }

    @Test
    fun testPhase7IncorrectActionDetectionInLiveBlender() = runBlocking {
        withLiveInteractiveBlender { port, triggerFile ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            // Step 1: Expected CREATE Cone MESH
            val expected = ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Cone",
                targetType = "MESH"
            )

            // Step 2: Capture baseline
            val run = workflow.captureBaseline(expected)
            assertNotNull(run.baseline)

            // Step 3: Create UV Sphere INSTEAD of Cone
            triggerAction(triggerFile, "import bpy; bpy.ops.mesh.primitive_uv_sphere_add()")
            Thread.sleep(600)

            // Step 4: Verify action
            val result = workflow.verifyAction(run)
            println("Phase 7 Result: ${result.status} - ${result.explanation}")

            // Result must NOT be SUCCESS
            assertNotEquals(VerificationStatus.SUCCESS, result.status)
            assertEquals(VerificationStatus.FAILURE, result.status)
            assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_ADDED && it.subject == "Sphere" })
            assertTrue(result.unsatisfiedConditions.any { it.field == ExpectedStateField.OBJECT_EXISTS && it.objectName == "Cone" })
        }
    }

    @Test
    fun testPhase8ObjectMatchingAndRenameInLiveBlender() = runBlocking {
        withLiveInteractiveBlender { port, triggerFile ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            // Step 1: Expected RENAME Cube to CubeRenamed
            val expected = ExpectedState(
                operation = VerificationOperation.RENAME,
                targetName = "CubeRenamed",
                targetType = "MESH"
            )

            // Step 2: Capture baseline
            val run = workflow.captureBaseline(expected)
            assertNotNull(run.baseline)
            assertTrue(run.baseline!!.objects.any { it.name == "Cube" })

            // Step 3: Rename Cube in Blender
            triggerAction(triggerFile, "import bpy; obj = bpy.data.objects.get('Cube'); obj.name = 'CubeRenamed' if obj else None")
            Thread.sleep(600)

            // Step 4: Verify action
            val result = workflow.verifyAction(run)
            println("Phase 8 Rename Result: ${result.status} - ${result.explanation}")

            // Result must be SUCCESS with OBJECT_RENAMED
            assertEquals(VerificationStatus.SUCCESS, result.status)
            assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_RENAMED && it.subject == "Cube->CubeRenamed" })
        }
    }

    @Test
    fun testPhase9ObjectSelectionInLiveBlender() = runBlocking {
        withLiveInteractiveBlender { port, triggerFile ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            // Step 1: Expected SELECT Camera
            val expected = ExpectedState(
                operation = VerificationOperation.SELECT,
                targetName = "Camera",
                targetType = "CAMERA",
                expectedSelection = true
            )

            // Step 2: Capture baseline (Camera is not selected initially)
            val run = workflow.captureBaseline(expected)
            assertNotNull(run.baseline)

            // Step 3: Select Camera in Blender
            triggerAction(triggerFile, "import bpy; bpy.ops.object.select_all(action='DESELECT'); cam = bpy.data.objects.get('Camera'); cam.select_set(True) if cam else None; bpy.context.view_layer.objects.active = cam")
            Thread.sleep(600)

            // Step 4: Verify action
            val result = workflow.verifyAction(run)
            println("Phase 9 Selection Result: ${result.status} - ${result.explanation}")

            assertEquals(VerificationStatus.SUCCESS, result.status)
            assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_SELECTION_CHANGED })
            assertTrue(result.satisfiedConditions.any { it.field == ExpectedStateField.OBJECT_SELECTED })

            // Assert persistence
            val runRecordPath = runStoreDir.resolve("${run.id}.json")
            assertTrue(Files.exists(runRecordPath))
            val json = Files.readString(runRecordPath)
            assertTrue(json.contains("\"status\": \"SUCCESS\""))
            assertTrue(json.contains("\"type\":\"OBJECT_SELECTION_CHANGED\""))
        }
    }

    @Test
    fun testPhase10ObjectModificationInLiveBlender() = runBlocking {
        withLiveInteractiveBlender { port, triggerFile ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            // Step 1: Expected MODIFY Cube location to 1,2,3
            val expected = ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetName = "Cube",
                targetType = "MESH",
                targetProperty = "location",
                expectedPropertyValue = "1,2,3"
            )

            // Step 2: Capture baseline
            val run = workflow.captureBaseline(expected)
            assertNotNull(run.baseline)
            val cubeBaseline = run.baseline!!.objects.first { it.name == "Cube" }
            assertEquals("0,0,0", cubeBaseline.metadata["location"])

            // Step 3: Modify Cube location in Blender
            triggerAction(triggerFile, "import bpy; obj = bpy.data.objects.get('Cube'); obj.location = (1.0, 2.0, 3.0) if obj else None")
            Thread.sleep(600)

            // Step 4: Verify action
            val result = workflow.verifyAction(run)
            println("Phase 10 Modify Result: ${result.status} - ${result.explanation}")

            assertEquals(VerificationStatus.SUCCESS, result.status)
            assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_PROPERTY_CHANGED && it.subject == "Cube" })
            assertTrue(result.satisfiedConditions.any { it.field == ExpectedStateField.OBJECT_PROPERTY })

            // Assert persistence
            val runRecordPath = runStoreDir.resolve("${run.id}.json")
            assertTrue(Files.exists(runRecordPath))
            val json = Files.readString(runRecordPath)
            assertTrue(json.contains("\"status\": \"SUCCESS\""))
            assertTrue(json.contains("\"type\":\"OBJECT_PROPERTY_CHANGED\""))
        }
    }

    @Test
    fun testPhase11ObjectDeletionInLiveBlender() = runBlocking {
        withLiveInteractiveBlender { port, triggerFile ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            // Step 1: Expected DELETE Cube
            val expected = ExpectedState(
                operation = VerificationOperation.DELETE,
                targetName = "Cube",
                targetType = "MESH"
            )

            // Step 2: Capture baseline (Cube is present)
            val run = workflow.captureBaseline(expected)
            assertNotNull(run.baseline)
            assertTrue(run.baseline!!.objects.any { it.name == "Cube" })

            // Step 3: Delete Cube in Blender
            triggerAction(triggerFile, "import bpy; obj = bpy.data.objects.get('Cube'); bpy.data.objects.remove(obj, do_unlink=True) if obj else None")
            Thread.sleep(600)

            // Step 4: Verify action
            val result = workflow.verifyAction(run)
            println("Phase 11 Deletion Result: ${result.status} - ${result.explanation}")

            assertEquals(VerificationStatus.SUCCESS, result.status)
            assertTrue(result.diffs.any { it.type == StateChangeType.OBJECT_REMOVED && it.subject == "Cube" })
            assertTrue(result.satisfiedConditions.any { it.field == ExpectedStateField.OBJECT_EXISTS })

            // Assert persistence
            val runRecordPath = runStoreDir.resolve("${run.id}.json")
            assertTrue(Files.exists(runRecordPath))
            val json = Files.readString(runRecordPath)
            assertTrue(json.contains("\"status\": \"SUCCESS\""))
            assertTrue(json.contains("\"type\":\"OBJECT_REMOVED\""))
        }
    }

    @Test
    fun testPhase12ModeTransitionInLiveBlender() = runBlocking {
        withLiveInteractiveBlender { port, triggerFile ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            // Step 1: Expected OBJECT mode
            val expected = ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetMode = "OBJECT"
            )

            // Step 2: Capture baseline (initially in OBJECT mode)
            val run = workflow.captureBaseline(expected)
            assertNotNull(run.baseline)
            assertEquals("OBJECT", run.baseline!!.mode)

            // Step 3: Switch to EDIT mode in Blender
            triggerAction(triggerFile, "import bpy; bpy.ops.object.mode_set(mode='EDIT')")
            Thread.sleep(600)

            // Observe live mode directly
            val editObs = provider.observe()
            assertEquals("EDIT_MESH", editObs.mode)

            // Step 4: Verify action requiring OBJECT mode while in EDIT_MESH mode (negative test)
            val failResult = workflow.verifyAction(run)
            println("Phase 12 Negative Mode Result: ${failResult.status} - ${failResult.explanation}")
            assertEquals(VerificationStatus.FAILURE, failResult.status)
            assertTrue(failResult.unsatisfiedConditions.any { it.field == ExpectedStateField.MODE })

            // Step 5: Switch back to OBJECT mode in Blender
            triggerAction(triggerFile, "import bpy; bpy.ops.object.mode_set(mode='OBJECT')")
            Thread.sleep(600)

            val objObs = provider.observe()
            assertEquals("OBJECT", objObs.mode)

            // Step 6: Verify action requiring OBJECT mode (positive test)
            val successResult = workflow.verifyAction(run)
            println("Phase 12 Positive Mode Result: ${successResult.status} - ${successResult.explanation}")
            assertEquals(VerificationStatus.SUCCESS, successResult.status)
            assertTrue(successResult.satisfiedConditions.any { it.field == ExpectedStateField.MODE })
        }
    }

    private fun triggerAction(triggerFile: File, pythonCode: String) {
        triggerFile.writeText(pythonCode)
    }

    private suspend fun withLiveInteractiveBlender(block: suspend (port: Int, triggerFile: File) -> Unit) {
        assumeTrue("Blender executable must exist at $blenderExe", Files.isRegularFile(Path.of(blenderExe)))
        val addonCandidates = listOf(
            Path.of(System.getProperty("user.dir"), "blender", "ai_visual_tutor_state", "__init__.py"),
            Path.of(System.getProperty("user.dir"), "..", "blender", "ai_visual_tutor_state", "__init__.py")
        ).map { it.normalize().toAbsolutePath() }
        val addon = addonCandidates.firstOrNull(Files::isRegularFile)
            ?: error("Blender adapter not found in ${addonCandidates.joinToString()}")

        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val triggerDir = Files.createTempDirectory("aivt_blender_trigger").toFile()
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
            // Wait for port to become available
            val provider = BlenderTcpStateProvider(port)
            var ready = false
            for (i in 1..40) {
                val obs = provider.observe()
                if (obs.status == BlenderObservationStatus.AVAILABLE) {
                    ready = true
                    break
                }
                Thread.sleep(200)
            }
            assertTrue(ready, "Blender failed to become available on port $port")

            block(port, triggerFile)
        } finally {
            process.destroyForcibly()
            triggerDir.deleteRecursively()
        }
    }
}
