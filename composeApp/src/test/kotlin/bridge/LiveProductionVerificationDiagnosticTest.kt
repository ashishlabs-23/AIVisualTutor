package bridge

import context.BlenderObservationStatus
import context.BlenderTcpStateProvider
import context.VerificationStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import tutor.ClosedLoopRecoveryState
import tutor.TutorController
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveProductionVerificationDiagnosticTest {

    private fun triggerAction(triggerFile: File, pythonCode: String) {
        triggerFile.writeText(pythonCode)
    }

    @Test
    fun testFullProductionWorkflowWithLiveBlender() = runBlocking {
        val blenderExe = File("D:\\blender.exe")
        assumeTrue("D:\\blender.exe must exist", blenderExe.isFile)

        ProcessBuilder("taskkill", "/F", "/IM", "blender.exe").start().waitFor()
        delay(1000)

        val addon = listOf(
            File("blender/ai_visual_tutor_state/__init__.py"),
            File("../blender/ai_visual_tutor_state/__init__.py"),
            File("d:/AIVisualTutor/AIVisualTutor-main/blender/ai_visual_tutor_state/__init__.py")
        ).firstOrNull { it.isFile }
        assertNotNull(addon, "Addon file must exist")

        val triggerDir = Files.createTempDirectory("aivt_test_trigger").toFile()
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
                        print("TRIGGER_SUCCESS")
                    except Exception as e:
                        print("TRIGGER_ERROR:", e)
                return 0.1

            bpy.app.timers.register(poll_actions, first_interval=0.1, persistent=True)
            """.trimIndent()
        )

        // Launch Blender on port 47629
        println("Launching Blender from ${blenderExe.absolutePath}...")
        val proc = ProcessBuilder(
            blenderExe.absolutePath,
            "--factory-startup",
            "--python", addon.absolutePath,
            "--python", runnerScript.absolutePath
        )
            .redirectErrorStream(true)
            .apply { environment()["AIVT_BLENDER_STATE_PORT"] = "47629" }
            .start()

        try {
            // Wait for Blender to bind port 47629
            val provider = BlenderTcpStateProvider(port = 47629, timeoutMillis = 4000)
            var ready = false
            for (i in 1..30) {
                delay(500)
                val obs = provider.observe()
                if (obs.status == BlenderObservationStatus.AVAILABLE) {
                    println("Blender ready on port 47629! Objects: ${obs.objects.map { it.name }}")
                    ready = true
                    break
                }
            }
            assertTrue(ready, "Blender must be ready on port 47629")

            // Create production TutorController
            val controller = TutorController()
            val plan = controller.startUserGoal("Create a UV sphere")
            assertNotNull(plan, "Plan must be created")
            println("Active Plan: ${plan.goalText}")

            // 1. Verify Step 1: Ensure Object Mode
            println("\n--- STEP 1: ${controller.currentStep?.title} ---")
            val step1Outcome = controller.recoveryCoordinator.verifyActiveStep()
            println("Step 1 Outcome: $step1Outcome")
            println("Step 1 Completed in Tracker: ${controller.isStepIndexCompleted(0)}")
            println("Completed Step IDs: ${controller.goalProgressState.completedStepIds}")
            assertTrue(step1Outcome is ClosedLoopRecoveryState.VerifiedSuccess, "Step 1 verification must succeed in Object Mode")
            assertTrue(controller.isStepIndexCompleted(0), "Step 1 must be completed in tracker")

            // 2. Advance to Step 2: Add Mesh Sphere
            if (controller.state.currentStepIndex == 0) {
                controller.nextStep()
            }
            println("\n--- STEP 2: ${controller.currentStep?.title} ---")
            println("Can execute Step 2: ${controller.canExecuteCurrentStep()}")
            assertTrue(controller.canExecuteCurrentStep(), "Step 2 prerequisites must be satisfied")

            // Simulate user performing the action in Blender
            println("User performing action in Blender: creating UV Sphere...")
            triggerAction(triggerFile, "import bpy; bpy.ops.mesh.primitive_uv_sphere_add()")
            delay(1000)

            // Verify Blender state actually has the sphere now
            val checkObs = provider.observe()
            println("Blender objects now: ${checkObs.objects.map { it.name }}")
            assertTrue(checkObs.objects.any { it.name == "Sphere" }, "Sphere must exist in Blender")

            // User moves to Step 3: Verify a Sphere object exists
            controller.nextStep()
            println("\n--- STEP 3: ${controller.currentStep?.title} ---")
            println("Can execute Step 3: ${controller.canExecuteCurrentStep()}")
            assertTrue(controller.canExecuteCurrentStep(), "Step 3 prerequisites must be satisfied")

            // User clicks "Verify" on Step 3
            println("User clicks Verify on Step 3...")
            val step3Outcome = controller.recoveryCoordinator.verifyActiveStep()
            println("Step 3 Outcome: $step3Outcome")
            println("Step 3 Completed in Tracker: ${controller.isStepIndexCompleted(2)}")
            println("Final Completed Step IDs: ${controller.goalProgressState.completedStepIds}")
            println("Final TutorState currentStepIndex: ${controller.state.currentStepIndex}")
            println("Final TutorState lastPedagogicalOutcome: ${controller.state.lastPedagogicalOutcome}")
            assertTrue(step3Outcome is ClosedLoopRecoveryState.VerifiedSuccess, "Step 3 verification must succeed")
            assertTrue(controller.isStepIndexCompleted(2), "Step 3 must be completed in tracker")
            assertTrue(controller.goalProgressTracker.isGoalCompleted(), "Goal must be completed")

        } finally {
            proc.destroyForcibly()
            triggerDir.deleteRecursively()
            ProcessBuilder("taskkill", "/F", "/IM", "blender.exe").start().waitFor()
        }
    }

    @Test
    fun testLiveNavigationDirectlyToStep3AutoReconcilesPrerequisites() = runBlocking {
        val blenderExe = File("D:\\blender.exe")
        assumeTrue("D:\\blender.exe must exist", blenderExe.isFile)

        ProcessBuilder("taskkill", "/F", "/IM", "blender.exe").start().waitFor()
        delay(1000)

        val addon = listOf(
            File("blender/ai_visual_tutor_state/__init__.py"),
            File("../blender/ai_visual_tutor_state/__init__.py"),
            File("d:/AIVisualTutor/AIVisualTutor-main/blender/ai_visual_tutor_state/__init__.py")
        ).firstOrNull { it.isFile }
        assertNotNull(addon, "Addon file must exist")

        val triggerDir = Files.createTempDirectory("aivt_test_trigger_nav").toFile()
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
                        pass
                return 0.1

            bpy.app.timers.register(poll_actions, first_interval=0.1, persistent=True)
            """.trimIndent()
        )

        val proc = ProcessBuilder(
            blenderExe.absolutePath,
            "--factory-startup",
            "--python", addon.absolutePath,
            "--python", runnerScript.absolutePath
        )
            .redirectErrorStream(true)
            .apply { environment()["AIVT_BLENDER_STATE_PORT"] = "47629" }
            .start()

        try {
            val provider = BlenderTcpStateProvider(port = 47629, timeoutMillis = 5000)
            var ready = false
            for (i in 1..30) {
                delay(500)
                val obs = provider.observe()
                if (obs.status == BlenderObservationStatus.AVAILABLE) {
                    ready = true
                    break
                }
            }
            assertTrue(ready, "Blender must be ready on port 47629")

            val controller = TutorController()
            val plan = controller.startUserGoal("Create a UV sphere")
            assertNotNull(plan, "Plan must be created")

            // User navigates directly to Step 2 without verifying Step 1
            controller.nextStep()
            assertEquals(1, controller.state.currentStepIndex)

            // User creates sphere in Blender
            triggerAction(triggerFile, "import bpy; bpy.ops.mesh.primitive_uv_sphere_add()")
            delay(1000)

            // User advances to Step 3
            controller.nextStep()
            assertEquals(2, controller.state.currentStepIndex)

            // User clicks Verify on Step 3 - coordinator must auto-reconcile Step 1 (mode is OBJECT) and verify Step 3
            val step3Outcome = controller.recoveryCoordinator.verifyActiveStep()
            assertTrue(step3Outcome is ClosedLoopRecoveryState.VerifiedSuccess, "Step 3 verification must succeed and auto-reconcile prerequisites")
            assertTrue(controller.isStepIndexCompleted(0), "Step 1 must be auto-reconciled and completed")
            assertTrue(controller.isStepIndexCompleted(2), "Step 3 must be completed")
            assertTrue(controller.goalProgressTracker.isGoalCompleted(), "Full goal must be marked completed")

        } finally {
            proc.destroyForcibly()
            triggerDir.deleteRecursively()
            ProcessBuilder("taskkill", "/F", "/IM", "blender.exe").start().waitFor()
        }
    }
}
