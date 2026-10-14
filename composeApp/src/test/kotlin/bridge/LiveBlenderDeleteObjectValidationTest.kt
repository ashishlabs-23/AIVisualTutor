package bridge

import context.BaselineState
import context.BlenderObservationStatus
import context.BlenderStateObservation
import context.BlenderTcpStateProvider
import context.BlenderVerificationWorkflow
import context.ExpectedState
import context.VerificationOperation
import context.VerificationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import tutor.ClosedLoopRecoveryCoordinator
import tutor.ClosedLoopRecoveryState
import tutor.TutorController
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveBlenderDeleteObjectValidationTest {

    private val blenderExe = System.getenv("AIVT_BLENDER_EXECUTABLE")
        ?.takeIf(String::isNotBlank)
        ?: "C:\\Program Files\\Blender Foundation\\Blender 5.2\\blender.exe"

    private fun triggerAction(triggerFile: File, pythonCode: String) {
        triggerFile.writeText(pythonCode)
        Thread.sleep(600)
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
            val provider = BlenderTcpStateProvider(port)
            var ready = false
            for (i in 1..40) {
                val obs = provider.observe()
                if (obs.status == BlenderObservationStatus.AVAILABLE) {
                    ready = true
                    break
                }
                Thread.sleep(250)
            }
            assertTrue(ready, "Blender failed to start on port $port")
            block(port, triggerFile)
        } finally {
            process.destroyForcibly()
            triggerDir.deleteRecursively()
        }
    }

    // =========================================================================
    // SECTION 6: LIVE BLENDER VALIDATION SUITE (Tests A, B, C, D)
    // =========================================================================

    @Test
    fun testLiveBlenderDeleteObjectValidation() = runBlocking {
        withLiveInteractiveBlender { port, triggerFile ->
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)
            val controller = TutorController(
                recoveryCoordinatorFactory = { c -> ClosedLoopRecoveryCoordinator(c, workflow = workflow) }
            )

            // Setup scene: Remove default Cube, add Sphere, select Sphere
            // Scene Outliner contains: Camera, Light, Sphere (Sphere selected)
            triggerAction(triggerFile, """
                import bpy
                cube = bpy.data.objects.get('Cube')
                if cube:
                    bpy.data.objects.remove(cube, do_unlink=True)
                bpy.ops.mesh.primitive_uv_sphere_add(location=(0,0,0))
                sphere = bpy.context.active_object
                sphere.name = 'Sphere'
            """.trimIndent())

            // Seed initial observation so active object "Sphere" is recognized
            val initialObs = provider.observe()
            assertEquals(BlenderObservationStatus.AVAILABLE, initialObs.status)
            assertTrue(initialObs.objects.any { it.name == "Sphere" })

            val plan = controller.startUserGoal("Delete Object")
            assertNotNull(plan)
            println("Active Task ID: ${controller.currentStep?.taskId}")
            println("Target Object: ${controller.currentStep?.targetControlName}")

            // Complete Step 1: Select Object to Delete
            val step1Outcome = controller.recoveryCoordinator.verifyActiveStep()
            assertTrue(step1Outcome is ClosedLoopRecoveryState.VerifiedSuccess)
            controller.nextStep()

            // Current step is: Delete Object
            assertEquals("Delete Object", controller.currentStep?.title)
            val activeTaskId = controller.currentStep?.taskId
            val targetName = controller.currentStep?.expectedState?.targetName

            // -------------------------------------------------------------
            // TEST A — TARGET STILL EXISTS
            // Leave Sphere in the scene, click Verify.
            // Expected: NOT VERIFIED (RecoveryRecommended / FAILURE)
            // -------------------------------------------------------------
            println("=== TEST A: TARGET STILL EXISTS ===")
            val obsA = provider.observe()
            println("Active Task ID: $activeTaskId")
            println("Target Object Identity: $targetName")
            println("Fresh Blender Observation Objects: ${obsA.objects.map { it.name }}")
            println("Selected Objects: ${obsA.selectedObjects}")

            val outcomeA = controller.recoveryCoordinator.verifyActiveStep()
            println("Evaluator Decision: ${outcomeA::class.simpleName}")
            println("Tutor UI Result: ${if (outcomeA is ClosedLoopRecoveryState.VerifiedSuccess) "Verified" else "Not Verified"}")
            println("Progress State: Completed=${controller.isStepIndexCompleted(1)}")

            assertTrue(outcomeA is ClosedLoopRecoveryState.RecoveryRecommended, "Test A must FAIL because Sphere still exists in Blender")
            assertFalse(controller.isStepIndexCompleted(1))

            // -------------------------------------------------------------
            // TEST B — SELECTION CHANGES ONLY
            // Keep Sphere in the scene, deselect Sphere / select Camera.
            // Expected: NOT VERIFIED
            // -------------------------------------------------------------
            println("=== TEST B: SELECTION CHANGES ONLY ===")
            triggerAction(triggerFile, """
                import bpy
                bpy.ops.object.select_all(action='DESELECT')
                cam = bpy.data.objects.get('Camera')
                if cam:
                    cam.select_set(True)
                    bpy.context.view_layer.objects.active = cam
            """.trimIndent())

            val obsB = provider.observe()
            println("Fresh Blender Observation Objects: ${obsB.objects.map { it.name }}")
            println("Selected Objects: ${obsB.selectedObjects}")

            controller.recoveryCoordinator.retryStep()
            val outcomeB = controller.recoveryCoordinator.verifyActiveStep()
            println("Evaluator Decision: ${outcomeB::class.simpleName}")
            println("Tutor UI Result: ${if (outcomeB is ClosedLoopRecoveryState.VerifiedSuccess) "Verified" else "Not Verified"}")

            assertTrue(outcomeB is ClosedLoopRecoveryState.RecoveryRecommended, "Test B must FAIL because Sphere still exists in Blender")
            assertFalse(controller.isStepIndexCompleted(1))

            // -------------------------------------------------------------
            // TEST D — WRONG OBJECT DELETED
            // Delete Light, but keep Sphere in scene.
            // Expected: NOT VERIFIED
            // -------------------------------------------------------------
            println("=== TEST D: WRONG OBJECT DELETED ===")
            triggerAction(triggerFile, """
                import bpy
                light = bpy.data.objects.get('Light')
                if light:
                    bpy.data.objects.remove(light, do_unlink=True)
            """.trimIndent())

            val obsD = provider.observe()
            println("Fresh Blender Observation Objects: ${obsD.objects.map { it.name }}")
            println("Sphere Present: ${obsD.objects.any { it.name == "Sphere" }}")
            println("Light Present: ${obsD.objects.any { it.name == "Light" }}")

            controller.recoveryCoordinator.retryStep()
            val outcomeD = controller.recoveryCoordinator.verifyActiveStep()
            println("Evaluator Decision: ${outcomeD::class.simpleName}")
            println("Tutor UI Result: ${if (outcomeD is ClosedLoopRecoveryState.VerifiedSuccess) "Verified" else "Not Verified"}")

            assertTrue(outcomeD is ClosedLoopRecoveryState.RecoveryRecommended || outcomeD is ClosedLoopRecoveryState.RetryLimitReached, "Test D must FAIL because Sphere still exists in Blender")
            assertFalse(controller.isStepIndexCompleted(1))

            // -------------------------------------------------------------
            // TEST C — DELETE THE TARGET
            // Delete Sphere from Blender.
            // Expected: VERIFIED
            // -------------------------------------------------------------
            println("=== TEST C: DELETE THE TARGET ===")
            controller.recoveryCoordinator.resetStepAttempts(1)
            triggerAction(triggerFile, """
                import bpy
                sphere = bpy.data.objects.get('Sphere')
                if sphere:
                    bpy.data.objects.remove(sphere, do_unlink=True)
            """.trimIndent())

            val obsC = provider.observe()
            println("Fresh Blender Observation Objects: ${obsC.objects.map { it.name }}")
            assertFalse(obsC.objects.any { it.name == "Sphere" }, "Sphere must be absent from authoritative scene observation")

            controller.recoveryCoordinator.retryStep()
            val outcomeC = controller.recoveryCoordinator.verifyActiveStep()
            println("Evaluator Decision: ${outcomeC::class.simpleName}")
            println("Tutor UI Result: ${if (outcomeC is ClosedLoopRecoveryState.VerifiedSuccess) "Verified" else "Not Verified"}")
            println("Progress State: Completed=${controller.isStepIndexCompleted(1)}")

            assertTrue(outcomeC is ClosedLoopRecoveryState.VerifiedSuccess, "Test C must SUCCEED because Sphere is absent from Blender")
            assertTrue(controller.isStepIndexCompleted(1))
        }
    }
}
