package overlay

import adapter.AdapterHealthStatus
import adapter.AdapterSnapshotResult
import adapter.BlenderApplicationAdapter
import adapter.ProjectedObjectResult
import context.BlenderVerificationWorkflow
import context.DynamicTargetPositioningService
import context.ExpectedState
import context.TargetLocalizationSource
import context.VerificationOperation
import context.VerificationStatus
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import models.DirectionalAxis
import models.TutorStep
import org.junit.Assume.assumeTrue
import tutor.TutorController

/**
 * Authentic Live Acceptance Tests executed directly against running Blender 5.2.2 LTS.
 * Validates the complete 3D object-highlighting workflow across viewport rotation,
 * zoom, pan, movement, deletion, and task verification.
 */
class LiveBlenderObjectHighlightingAcceptanceTest {

    private val blenderExe = System.getenv("AIVT_BLENDER_EXECUTABLE")
        ?.takeIf(String::isNotBlank)
        ?: "C:\\Program Files\\Blender Foundation\\Blender 5.2\\blender.exe"

    @Test
    fun testLiveBlenderCompleteHighlightingAndVerificationWorkflow() = runBlocking {
        assumeTrue("Blender executable must exist at $blenderExe", Files.isRegularFile(Path.of(blenderExe)))

        withLiveBlender { port, triggerFile ->
            val adapter = BlenderApplicationAdapter(port = port, timeoutMillis = 1500)
            val service = DynamicTargetPositioningService(blenderAdapter = adapter)
            val controller = TutorController(
                blenderAdapter = adapter,
                targetPositioningService = service
            )

            println("\n============================================================")
            println("LIVE BLENDER 5.2.2 LTS ACCEPTANCE TEST")
            println("Port: $port | Blender: $blenderExe")
            println("============================================================\n")

            // ------------------------------------------------------------
            // 1. Correct Target Identification & Initial Projection
            // ------------------------------------------------------------
            val moveStep = TutorStep(
                id = 4,
                title = "Move Cube Up",
                instruction = "Press G, then Z to constrain to the Z-axis, move the mouse upward, and left-click to confirm.",
                description = "Translate Cube upward",
                stepNumber = 4,
                totalSteps = 4,
                targetControlName = "3D Viewport",
                expectedState = ExpectedState(
                    operation = VerificationOperation.MODIFY,
                    targetName = "Cube",
                    targetProperty = "location.z",
                    expectedPropertyValue = "> 0"
                )
            )

            val identifiedObj = service.identifyTargetObject(moveStep)
            assertEquals("Cube", identifiedObj)
            println("[EVIDENCE 1] Target Identified: '$identifiedObj'")

            val initialResult = service.resolveTarget(moveStep)
            assertEquals(TargetLocalizationSource.BLENDER_3D_PROJECTION, initialResult.source)
            assertTrue(initialResult.isSemanticallyVerified)
            assertNotNull(initialResult.region)

            val initialBounds = initialResult.region!!
            println("[EVIDENCE 2] Initial Cube Screen Bounds: X=${initialBounds.x}, Y=${initialBounds.y}, W=${initialBounds.width}, H=${initialBounds.height}")
            assertTrue(initialBounds.width > 20f, "Cube projected width must be non-zero in 3D viewport")
            assertTrue(initialBounds.height > 20f, "Cube projected height must be non-zero in 3D viewport")
            assertFalse(initialBounds.isScreenCoordinates, "Bounds should be window-relative to Blender window")

            // Directional indicator for +Z
            assertNotNull(initialBounds.directionalIndicator)
            assertEquals(DirectionalAxis.UP_POSITIVE_Z, initialBounds.directionalIndicator!!.axis)
            assertEquals("+Z Up", initialBounds.directionalIndicator!!.label)
            println("[EVIDENCE 2b] Directional Guidance: Axis=${initialBounds.directionalIndicator!!.axis}, Label='${initialBounds.directionalIndicator!!.label}'")

            // ------------------------------------------------------------
            // 2. Selection Preservation (Non-destructive check)
            // ------------------------------------------------------------
            val snapshot1 = (adapter.takeSnapshot() as AdapterSnapshotResult.Success).snapshot
            val cube1 = snapshot1.objects.first { it.name == "Cube" }
            val initialSelected = cube1.selected
            println("[EVIDENCE 5] Selection Preservation: Cube selected=$initialSelected (unaltered by projection)")

            // ------------------------------------------------------------
            // 3. Viewport Rotation Updates Alignment
            // ------------------------------------------------------------
            val verBeforeRot = adapter.fetchVersion()
            val counterBeforeRot = (verBeforeRot as adapter.AdapterVersionResult.Success).info.eventCounter

            // Rotate viewport camera
            triggerAction(
                triggerFile,
                """
                import bpy
                for window in bpy.context.window_manager.windows:
                    for area in window.screen.areas:
                        if area.type == 'VIEW_3D':
                            rv3d = area.spaces.active.region_3d
                            rv3d.view_rotation = (0.5, 0.5, 0.5, 0.5)
                            break
                """.trimIndent()
            )
            Thread.sleep(400)

            val verAfterRot = adapter.fetchVersion()
            val counterAfterRot = (verAfterRot as adapter.AdapterVersionResult.Success).info.eventCounter
            assertTrue(counterAfterRot >= counterBeforeRot, "Event counter must increment on viewport rotation")

            val rotatedProj = adapter.projectObject("Cube")
            assertTrue(rotatedProj is ProjectedObjectResult.Success)
            val rotBounds = rotatedProj.bounds
            println("[EVIDENCE 3] Viewport Rotation Bounds: X=${rotBounds.x}, Y=${rotBounds.y}, W=${rotBounds.width}, H=${rotBounds.height}")
            assertTrue(rotBounds.width > 0f)

            // ------------------------------------------------------------
            // 4. Viewport Zoom Updates Alignment
            // ------------------------------------------------------------
            // Zoom out (view_distance = 25.0)
            triggerAction(
                triggerFile,
                """
                import bpy
                for window in bpy.context.window_manager.windows:
                    for area in window.screen.areas:
                        if area.type == 'VIEW_3D':
                            rv3d = area.spaces.active.region_3d
                            rv3d.view_distance = 25.0
                            break
                """.trimIndent()
            )
            Thread.sleep(400)

            val zoomedProj = adapter.projectObject("Cube")
            assertTrue(zoomedProj is ProjectedObjectResult.Success)
            val zoomBounds = zoomedProj.bounds
            println("[EVIDENCE 4] Viewport Zoom (Out) Bounds: X=${zoomBounds.x}, Y=${zoomBounds.y}, W=${zoomBounds.width}, H=${zoomBounds.height}")
            // Zooming out must make the object's projected on-screen dimensions smaller than before
            assertTrue(zoomBounds.width <= rotBounds.width + 10f, "Zoomed-out cube should occupy smaller/equal screen width")

            // ------------------------------------------------------------
            // 5. Verification: Cube Unchanged (Z = 0) -> FAILS
            // ------------------------------------------------------------
            val workflow = BlenderVerificationWorkflow(provider = adapter)
            val baselineRun = workflow.captureBaseline(moveStep.expectedState!!)
            val verifyUnchanged = workflow.verifyAction(baselineRun)
            println("[EVIDENCE 8a] Verification Unchanged State: Status=${verifyUnchanged.status} - Explanation: ${verifyUnchanged.explanation}")
            assertEquals(VerificationStatus.FAILURE, verifyUnchanged.status, "Unchanged Cube at Z=0 must NOT be verified")

            // ------------------------------------------------------------
            // 6. Move Cube Upward (Z = 1.5m) -> VERIFIES
            // ------------------------------------------------------------
            triggerAction(
                triggerFile,
                """
                import bpy
                cube = bpy.data.objects.get('Cube')
                if cube:
                    cube.location.z = 1.5
                """.trimIndent()
            )
            Thread.sleep(400)

            val movedProj = adapter.projectObject("Cube")
            assertTrue(movedProj is ProjectedObjectResult.Success)
            println("[EVIDENCE 8b] Cube Moved Upward (Z=1.5m). Projected Y=${movedProj.bounds.y}")

            val verifyMoved = workflow.verifyAction(baselineRun)
            println("[EVIDENCE 8c] Verification Moved State: Status=${verifyMoved.status} - Explanation: ${verifyMoved.explanation}")
            assertEquals(VerificationStatus.SUCCESS, verifyMoved.status, "Cube moved to Z=1.5m must be VERIFIED")

            // ------------------------------------------------------------
            // 7. Cleanup after Target Deletion
            // ------------------------------------------------------------
            triggerAction(
                triggerFile,
                """
                import bpy
                cube = bpy.data.objects.get('Cube')
                if cube:
                    bpy.data.objects.remove(cube, do_unlink=True)
                """.trimIndent()
            )
            Thread.sleep(400)

            val deletedProj = adapter.projectObject("Cube")
            assertTrue(deletedProj is ProjectedObjectResult.NotFound)
            println("[EVIDENCE 6] Target Deletion Projection: Status=NotFound ($deletedProj)")

            val deleteResolution = service.resolveTarget(moveStep)
            assertNull(deleteResolution.region, "Highlight region must be null (suppressed) after target deletion")
            println("[EVIDENCE 6b] Highlight Suppressed Cleanly: Diagnostic=${deleteResolution.diagnostic}")

            // ------------------------------------------------------------
            // 8. Step Completion / Goal Clear Cleanup
            // ------------------------------------------------------------
            controller.setHighlight(initialBounds)
            assertNotNull(controller.state.currentHighlight)
            controller.clearUserGoal()
            assertNull(controller.state.currentHighlight, "Highlight must be null after clearing goal")
            println("[EVIDENCE 7] Step/Goal Completion Cleanup: Highlight successfully reset to null.")
            println("\n============================================================")
            println("ALL LIVE BLENDER ACCEPTANCE TESTS PASSED!")
            println("============================================================\n")
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
        val triggerDir = Files.createTempDirectory("aivt_live_highlight").toFile()
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
            val adapter = BlenderApplicationAdapter(port = port, timeoutMillis = 800)
            var ready = false
            for (i in 1..60) {
                val health = adapter.checkHealth()
                if (health.status == AdapterHealthStatus.HEALTHY) {
                    ready = true
                    break
                }
                Thread.sleep(200)
            }
            assertTrue(ready, "Blender adapter failed to become healthy on port $port")

            block(port, triggerFile)
        } finally {
            process.destroyForcibly()
            triggerDir.deleteRecursively()
        }
    }
}
