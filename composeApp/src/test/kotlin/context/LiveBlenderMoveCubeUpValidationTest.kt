package context

import org.junit.Assume.assumeTrue
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
import kotlinx.coroutines.runBlocking
import tutor.ClosedLoopRecoveryCoordinator
import tutor.ClosedLoopRecoveryState
import tutor.TutorController
import tutor.UserGoalPlanner

class LiveBlenderMoveCubeUpValidationTest {

    private val blenderExe = System.getenv("AIVT_BLENDER_EXECUTABLE")
        ?.takeIf(String::isNotBlank)
        ?: "C:\\Program Files\\Blender Foundation\\Blender 5.2\\blender.exe"

    private suspend fun withLiveBlender(block: suspend (port: Int, triggerAction: (String) -> Unit) -> Unit) {
        assumeTrue("Blender executable must exist at $blenderExe", Files.isRegularFile(Path.of(blenderExe)))
        val addonCandidates = listOf(
            Path.of(System.getProperty("user.dir"), "blender", "ai_visual_tutor_state", "__init__.py"),
            Path.of(System.getProperty("user.dir"), "..", "blender", "ai_visual_tutor_state", "__init__.py")
        ).map { it.normalize().toAbsolutePath() }
        val addon = addonCandidates.firstOrNull(Files::isRegularFile)
            ?: error("Blender adapter not found in ${addonCandidates.joinToString()}")

        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val triggerDir = Files.createTempDirectory("aivt_live_cube_test").toFile()
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
                return 0.05

            bpy.app.timers.register(poll_actions, first_interval=0.05, persistent=True)
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

        fun runScriptInBlender(code: String) {
            triggerFile.writeText(code)
            var waited = 0
            while (triggerFile.exists() && waited < 40) {
                Thread.sleep(50)
                waited++
            }
            Thread.sleep(150)
        }

        try {
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
            assertTrue(ready, "Live Blender 5.2.2 LTS failed to start and connect on port $port")

            block(port, ::runScriptInBlender)
        } finally {
            process.destroyForcibly()
            triggerDir.deleteRecursively()
        }
    }

    @Test
    fun testLiveBlenderCasesA_B_C_D() = runBlocking {
        withLiveBlender { port, triggerAction ->
            println("=== LIVE BLENDER 5.2.2 LTS VALIDATION ===")
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            val controller = TutorController(
                recoveryCoordinatorFactory = { ctrl -> ClosedLoopRecoveryCoordinator(ctrl, workflow) }
            )
            controller.startUserGoal("Make a cube and move it")

            // Advance plan to Step 4 ("Move Cube Up")
            while (controller.state.currentStepIndex < 3) {
                controller.nextStep()
            }
            assertEquals("Move Cube Up", controller.currentStep?.title)
            val stepIndex = controller.state.currentStepIndex

            // Ensure cube is at (0, 0, 0)
            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, 0.0)
            """.trimIndent())

            val obs0 = provider.observe()
            val cubeObj0 = obs0.objects.find { it.name == "Cube" }
            assertNotNull(cubeObj0)
            println("[Live Initial] Cube Location: ${cubeObj0.metadata["location"]}")

            // ---------------------------------------------------------
            // CASE A — Unchanged cube at Z = 0
            // ---------------------------------------------------------
            println("\n--- RUNNING CASE A: Unchanged cube (Z = 0) ---")
            val caseAResult = controller.recoveryCoordinator.verifyActiveStep()
            println("CASE A Status: ${caseAResult::class.simpleName}")
            assertTrue(caseAResult is ClosedLoopRecoveryState.RecoveryRecommended, "Case A must fail verification")
            val caseARec = caseAResult as ClosedLoopRecoveryState.RecoveryRecommended
            println("CASE A Tutor Feedback: ${caseARec.recommendation.explanation}")
            println("CASE A Progress State: currentStepIndex = ${controller.state.currentStepIndex}, completed = ${controller.goalProgressState.completedStepIds}")
            assertFalse(controller.goalProgressState.completedStepIds.contains("step_4"))
            assertEquals(stepIndex, controller.state.currentStepIndex)
            assertTrue(caseARec.recommendation.explanation.contains("Not verified") || caseARec.recommendation.explanation.contains("Z location"))

            controller.recoveryCoordinator.resetStepAttempts(stepIndex)
            controller.recoveryCoordinator.retryStep()

            // ---------------------------------------------------------
            // CASE B — Move horizontally only (X = 2.0, Y = 1.0, Z = 0.0)
            // ---------------------------------------------------------
            println("\n--- RUNNING CASE B: Move horizontally only (X=2, Y=1, Z=0) ---")
            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, 0.0)
            """.trimIndent())
            controller.recoveryCoordinator.captureBaseline()

            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (2.0, 1.0, 0.0)
            """.trimIndent())
            val obsB = provider.observe()
            val cubeObjB = obsB.objects.find { it.name == "Cube" }
            println("[Live Case B] Blender Transform: ${cubeObjB?.metadata["location"]}")

            val caseBResult = controller.recoveryCoordinator.verifyActiveStep()
            println("CASE B Status: ${caseBResult::class.simpleName}")
            assertTrue(caseBResult is ClosedLoopRecoveryState.RecoveryRecommended, "Case B must fail verification")
            val caseBRec = caseBResult as ClosedLoopRecoveryState.RecoveryRecommended
            println("CASE B Tutor Feedback: ${caseBRec.recommendation.explanation}")
            println("CASE B Progress State: currentStepIndex = ${controller.state.currentStepIndex}, completed = ${controller.goalProgressState.completedStepIds}")
            assertFalse(controller.goalProgressState.completedStepIds.contains("step_4"))
            assertEquals(stepIndex, controller.state.currentStepIndex)

            controller.recoveryCoordinator.resetStepAttempts(stepIndex)
            controller.recoveryCoordinator.retryStep()

            // ---------------------------------------------------------
            // CASE C — Move downward (Z = -2.0)
            // ---------------------------------------------------------
            println("\n--- RUNNING CASE C: Move downward (Z = -2.0) ---")
            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, 0.0)
            """.trimIndent())
            controller.recoveryCoordinator.captureBaseline()

            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, -2.0)
            """.trimIndent())
            val obsC = provider.observe()
            val cubeObjC = obsC.objects.find { it.name == "Cube" }
            println("[Live Case C] Blender Transform: ${cubeObjC?.metadata["location"]}")

            val caseCResult = controller.recoveryCoordinator.verifyActiveStep()
            println("CASE C Status: ${caseCResult::class.simpleName}")
            assertTrue(caseCResult is ClosedLoopRecoveryState.RecoveryRecommended, "Case C must fail verification")
            val caseCRec = caseCResult as ClosedLoopRecoveryState.RecoveryRecommended
            println("CASE C Tutor Feedback: ${caseCRec.recommendation.explanation}")
            println("CASE C Progress State: currentStepIndex = ${controller.state.currentStepIndex}, completed = ${controller.goalProgressState.completedStepIds}")
            assertFalse(controller.goalProgressState.completedStepIds.contains("step_4"))
            assertEquals(stepIndex, controller.state.currentStepIndex)

            controller.recoveryCoordinator.resetStepAttempts(stepIndex)
            controller.recoveryCoordinator.retryStep()

            // ---------------------------------------------------------
            // CASE D — Move upward correctly (Z = 2.5)
            // ---------------------------------------------------------
            println("\n--- RUNNING CASE D: Move upward correctly (Z = 2.5) ---")
            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, 0.0)
            """.trimIndent())
            controller.recoveryCoordinator.captureBaseline()

            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, 2.5)
            """.trimIndent())
            val obsD = provider.observe()
            val cubeObjD = obsD.objects.find { it.name == "Cube" }
            println("[Live Case D] Blender Transform: ${cubeObjD?.metadata["location"]}")

            val caseDResult = controller.recoveryCoordinator.verifyActiveStep()
            println("CASE D Status: ${caseDResult::class.simpleName}")
            assertTrue(caseDResult is ClosedLoopRecoveryState.VerifiedSuccess, "Case D must succeed verification")
            val caseDSuccess = caseDResult as ClosedLoopRecoveryState.VerifiedSuccess
            println("CASE D Tutor Feedback: ${caseDSuccess.explanation}")
            println("CASE D Progress State: currentStepIndex = ${controller.state.currentStepIndex}, completed = ${controller.goalProgressState.completedStepIds}")
            assertTrue(controller.goalProgressState.completedStepIds.contains("step_4"))
            assertTrue(controller.isStepIndexCompleted(stepIndex))
            println("\n=== ALL LIVE BLENDER CASES A, B, C, D COMPLETED SUCCESSFULLY ===")
        }
    }

    @Test
    fun testLiveBlenderRegressionSequence_A_B_C() = runBlocking {
        withLiveBlender { port, triggerAction ->
            println("=== LIVE BLENDER REGRESSION TEST SEQUENCE (A, B, C) ===")
            val provider = BlenderTcpStateProvider(port)
            val workflow = BlenderVerificationWorkflow(provider)

            val controller = TutorController(
                recoveryCoordinatorFactory = { ctrl -> ClosedLoopRecoveryCoordinator(ctrl, workflow) }
            )
            controller.startUserGoal("Make a cube and move it")

            // Advance plan to Step 4 ("Move Cube Up")
            while (controller.state.currentStepIndex < 3) {
                controller.nextStep()
            }
            assertEquals("Move Cube Up", controller.currentStep?.title)
            val stepIndex = controller.state.currentStepIndex

            // TEST A — UNCHANGED CUBE: Blender reports Z = 0 m. User clicks Verify.
            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, 0.0)
            """.trimIndent())
            val obsA = provider.observe()
            val cubeA = obsA.objects.find { it.name == "Cube" }
            assertNotNull(cubeA)
            println("[Live Test A] Blender reports: location = ${cubeA.metadata["location"]}")

            val resultA = controller.recoveryCoordinator.verifyActiveStep()
            println("[Live Test A] Tutor Result: ${resultA::class.simpleName}")
            assertTrue(resultA is ClosedLoopRecoveryState.RecoveryRecommended, "Test A (Z = 0) must report Not Verified")
            val recA = resultA as ClosedLoopRecoveryState.RecoveryRecommended
            assertEquals(1, recA.attemptNumber)
            println("[Live Test A] Tutor Feedback: ${recA.recommendation.explanation}")
            assertTrue(recA.recommendation.explanation.contains("Not verified") && recA.recommendation.explanation.contains("0.0 m"))
            assertFalse(controller.goalProgressState.completedStepIds.contains("step_4"))

            // User prepares to retry (like clicking retry in the tutor UI)
            controller.recoveryCoordinator.retryStep()

            // TEST B — CUBE MOVED UPWARD: User moves the cube upward to Z ≈ 1 m. User clicks Verify again.
            // In real manual testing, NO baseline capture occurs before user moves the cube.
            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, 1.0)
            """.trimIndent())
            val obsB = provider.observe()
            val cubeB = obsB.objects.find { it.name == "Cube" }
            assertNotNull(cubeB)
            println("[Live Test B] Blender reports: location = ${cubeB.metadata["location"]}")

            val resultB = controller.recoveryCoordinator.verifyActiveStep()
            println("[Live Test B] Tutor Result: ${resultB::class.simpleName}")
            assertTrue(resultB is ClosedLoopRecoveryState.VerifiedSuccess, "Test B (Z = 1 m) must report Verified on attempt 2")
            val successB = resultB as ClosedLoopRecoveryState.VerifiedSuccess
            println("[Live Test B] Tutor Feedback: ${successB.explanation}")
            assertTrue(controller.goalProgressState.completedStepIds.contains("step_4"))
            println("[Live Test B] Goal progress updated: ${controller.goalProgressState.completedStepIds}")

            // TEST C — DOWNWARD MOVEMENT: violates the contract
            controller.recoveryCoordinator.resetStepAttempts(stepIndex)
            triggerAction("""
                import bpy
                cube = bpy.data.objects.get("Cube")
                if cube:
                    cube.location = (0.0, 0.0, -1.5)
            """.trimIndent())
            val obsC = provider.observe()
            val cubeC = obsC.objects.find { it.name == "Cube" }
            assertNotNull(cubeC)
            println("[Live Test C] Blender reports: location = ${cubeC.metadata["location"]}")

            val resultC = controller.recoveryCoordinator.verifyActiveStep()
            println("[Live Test C] Tutor Result: ${resultC::class.simpleName}")
            assertTrue(resultC is ClosedLoopRecoveryState.RecoveryRecommended, "Test C (Z = -1.5 m) must report Not Verified")
            val recC = resultC as ClosedLoopRecoveryState.RecoveryRecommended
            println("[Live Test C] Tutor Feedback: ${recC.recommendation.explanation}")
            assertTrue(recC.recommendation.explanation.contains("Not verified") && recC.recommendation.explanation.contains("-1.5 m"))
            println("=== LIVE BLENDER REGRESSION TEST SEQUENCE (A, B, C) PASSED ===")
        }
    }
}
