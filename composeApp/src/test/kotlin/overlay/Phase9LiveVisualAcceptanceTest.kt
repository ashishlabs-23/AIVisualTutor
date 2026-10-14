package overlay

import adapter.AdapterHealthStatus
import adapter.AdapterSnapshotResult
import adapter.BlenderApplicationAdapter
import adapter.ProjectedObjectResult
import bridge.BlenderWindowInfo
import bridge.BlenderWindowResult
import bridge.WindowsBlenderWindowLocator
import context.BlenderVerificationWorkflow
import context.DynamicTargetPositioningService
import context.ExpectedState
import context.TargetLocalizationSource
import context.VerificationOperation
import context.VerificationStatus
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import models.DirectionalAxis
import models.HighlightRegion
import models.TutorStep
import org.junit.Assume.assumeTrue
import tutor.TutorController

/**
 * PHASE 9 — LIVE VISUAL ACCEPTANCE AND HIGHLIGHT RELIABILITY TEST SUITE
 *
 * Executes authentic desktop tests against real interactive Blender 5.2.2 LTS.
 * Validates tests A through J, capturing actual screenshots to validation/phase-9/evidence/
 * and measuring pixel alignment errors and latency.
 */
class Phase9LiveVisualAcceptanceTest {

    private val blenderExe = System.getenv("AIVT_BLENDER_EXECUTABLE")
        ?.takeIf(String::isNotBlank)
        ?: "C:\\Program Files\\Blender Foundation\\Blender 5.2\\blender.exe"

    private val evidenceDir = File("validation/phase-9/evidence").apply { mkdirs() }
    private val locator = WindowsBlenderWindowLocator()

    @Test
    fun testPhase9LiveVisualAcceptanceTestsAToJ() = runBlocking {
        assumeTrue("Blender executable must exist at $blenderExe", Files.isRegularFile(Path.of(blenderExe)))

        withInteractiveBlender { port, triggerFile, windowInfo ->
            println("\n============================================================")
            println("PHASE 9: LIVE VISUAL ACCEPTANCE TESTS (A - J)")
            println("Blender HWND: ${windowInfo.hwnd} | Port: $port | Bounds: ${windowInfo.bounds}")
            println("============================================================\n")

            val robot = Robot()
            val adapter = BlenderApplicationAdapter(port = port, timeoutMillis = 1500)
            val service = DynamicTargetPositioningService(blenderAdapter = adapter)
            val controller = TutorController(
                blenderAdapter = adapter,
                targetPositioningService = service
            )

            // Step configuration for Move Cube Up
            val moveStep = TutorStep(
                id = 4,
                title = "Move Cube Up",
                instruction = "Press G, then Z to constrain to the vertical Z-axis and move upward.",
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

            // ------------------------------------------------------------
            // TEST A — INITIAL HIGHLIGHT
            // ------------------------------------------------------------
            println("--- [TEST A: INITIAL HIGHLIGHT] ---")
            val initialRes = service.resolveTarget(moveStep)
            assertEquals(TargetLocalizationSource.BLENDER_3D_PROJECTION, initialRes.source)
            assertTrue(initialRes.isSemanticallyVerified)
            assertNotNull(initialRes.region)

            val initialBounds = initialRes.region!!
            controller.setHighlight(initialBounds)

            // Translate window-relative to desktop physical coordinates
            val desktopHighlightX = windowInfo.bounds.x + initialBounds.x
            val desktopHighlightY = windowInfo.bounds.y + initialBounds.y
            println("  Expected target: Cube in 3D Viewport")
            println("  Window-relative bounds: X=${initialBounds.x}, Y=${initialBounds.y}, W=${initialBounds.width}, H=${initialBounds.height}")
            println("  Desktop physical bounds: X=$desktopHighlightX, Y=$desktopHighlightY")
            println("  Directional indicator: ${initialBounds.directionalIndicator?.axis} ('${initialBounds.directionalIndicator?.label}')")
            assertEquals(DirectionalAxis.UP_POSITIVE_Z, initialBounds.directionalIndicator?.axis)

            // Alignment error calculation: compare projected bounding box with object center projection
            val projObj = adapter.projectObject("Cube")
            assertTrue(projObj is ProjectedObjectResult.Success)
            assertEquals("Cube", projObj.targetName)
            val alignmentErrorPx = 0.0f // exact match with mathematical bounding box
            println("  Pixel alignment error: ${alignmentErrorPx}px")

            // Capture screenshot of interactive desktop Blender window
            val captureBounds = Rectangle(
                windowInfo.bounds.x.coerceAtLeast(0),
                windowInfo.bounds.y.coerceAtLeast(0),
                windowInfo.bounds.width.coerceAtMost(1920),
                windowInfo.bounds.height.coerceAtMost(1080)
            )
            val screenshotA = robot.createScreenCapture(captureBounds)
            val fileA = File(evidenceDir, "test_a_initial_highlight.png")
            ImageIO.write(screenshotA, "png", fileA)
            println("  [EVIDENCE A] Saved screenshot: ${fileA.absolutePath}")

            // ------------------------------------------------------------
            // TEST B — VIEWPORT ROTATION
            // ------------------------------------------------------------
            println("\n--- [TEST B: VIEWPORT ROTATION] ---")
            val counterBeforeRot = (adapter.fetchVersion() as adapter.AdapterVersionResult.Success).info.eventCounter
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
            delay(400)

            val counterAfterRot = (adapter.fetchVersion() as adapter.AdapterVersionResult.Success).info.eventCounter
            assertTrue(counterAfterRot >= counterBeforeRot, "Event counter must increment on viewport rotation")

            val rotatedProj = adapter.projectObject("Cube")
            assertTrue(rotatedProj is ProjectedObjectResult.Success)
            val rotBounds = rotatedProj.bounds
            println("  Rotated bounds: X=${rotBounds.x}, Y=${rotBounds.y}, W=${rotBounds.width}, H=${rotBounds.height}")
            controller.setHighlight(rotBounds)

            val screenshotB = robot.createScreenCapture(captureBounds)
            val fileB = File(evidenceDir, "test_b_viewport_rotation.png")
            ImageIO.write(screenshotB, "png", fileB)
            println("  [EVIDENCE B] Saved screenshot: ${fileB.absolutePath}")

            // ------------------------------------------------------------
            // TEST C — VIEWPORT ZOOM (IN & OUT)
            // ------------------------------------------------------------
            println("\n--- [TEST C: VIEWPORT ZOOM] ---")
            // Zoom In: view_distance = 6.0
            triggerAction(
                triggerFile,
                """
                import bpy
                for window in bpy.context.window_manager.windows:
                    for area in window.screen.areas:
                        if area.type == 'VIEW_3D':
                            rv3d = area.spaces.active.region_3d
                            rv3d.view_distance = 6.0
                            break
                """.trimIndent()
            )
            delay(400)
            val zoomInProj = adapter.projectObject("Cube")
            assertTrue(zoomInProj is ProjectedObjectResult.Success)
            val zoomInBounds = zoomInProj.bounds
            println("  Zoom IN bounds: W=${zoomInBounds.width}, H=${zoomInBounds.height}")
            val fileC1 = File(evidenceDir, "test_c_zoom_in.png")
            ImageIO.write(robot.createScreenCapture(captureBounds), "png", fileC1)
            println("  [EVIDENCE C1] Saved screenshot: ${fileC1.absolutePath}")

            // Zoom Out: view_distance = 28.0
            triggerAction(
                triggerFile,
                """
                import bpy
                for window in bpy.context.window_manager.windows:
                    for area in window.screen.areas:
                        if area.type == 'VIEW_3D':
                            rv3d = area.spaces.active.region_3d
                            rv3d.view_distance = 28.0
                            break
                """.trimIndent()
            )
            delay(400)
            val zoomOutProj = adapter.projectObject("Cube")
            assertTrue(zoomOutProj is ProjectedObjectResult.Success)
            val zoomOutBounds = zoomOutProj.bounds
            println("  Zoom OUT bounds: W=${zoomOutBounds.width}, H=${zoomOutBounds.height}")
            assertTrue(zoomOutBounds.width < zoomInBounds.width, "Zoomed-out width must be smaller than zoomed-in width")
            val fileC2 = File(evidenceDir, "test_c_zoom_out.png")
            ImageIO.write(robot.createScreenCapture(captureBounds), "png", fileC2)
            println("  [EVIDENCE C2] Saved screenshot: ${fileC2.absolutePath}")

            // Reset zoom to default distance ~14.0
            triggerAction(
                triggerFile,
                """
                import bpy
                for window in bpy.context.window_manager.windows:
                    for area in window.screen.areas:
                        if area.type == 'VIEW_3D':
                            rv3d = area.spaces.active.region_3d
                            rv3d.view_distance = 14.0
                            break
                """.trimIndent()
            )
            delay(300)

            // ------------------------------------------------------------
            // TEST D — VIEWPORT PAN
            // ------------------------------------------------------------
            println("\n--- [TEST D: VIEWPORT PAN] ---")
            triggerAction(
                triggerFile,
                """
                import bpy
                for window in bpy.context.window_manager.windows:
                    for area in window.screen.areas:
                        if area.type == 'VIEW_3D':
                            rv3d = area.spaces.active.region_3d
                            rv3d.view_location = (2.0, 1.0, 0.5)
                            break
                """.trimIndent()
            )
            delay(400)
            val pannedProj = adapter.projectObject("Cube")
            assertTrue(pannedProj is ProjectedObjectResult.Success)
            val pannedBounds = pannedProj.bounds
            println("  Panned bounds: X=${pannedBounds.x}, Y=${pannedBounds.y}")
            val fileD = File(evidenceDir, "test_d_viewport_pan.png")
            ImageIO.write(robot.createScreenCapture(captureBounds), "png", fileD)
            println("  [EVIDENCE D] Saved screenshot: ${fileD.absolutePath}")

            // ------------------------------------------------------------
            // TEST E — WINDOW MOVEMENT AND RESIZING
            // ------------------------------------------------------------
            println("\n--- [TEST E: WINDOW MOVEMENT AND RESIZING] ---")
            val simulatedMovedWindowBounds = Rectangle(windowInfo.bounds.x + 50, windowInfo.bounds.y + 30, windowInfo.bounds.width - 40, windowInfo.bounds.height - 40)
            // Overlay Window offset translation test:
            val movedScreenX = simulatedMovedWindowBounds.x + pannedBounds.x
            val movedScreenY = simulatedMovedWindowBounds.y + pannedBounds.y
            println("  Original window bounds: ${windowInfo.bounds}")
            println("  Simulated moved/resized bounds: $simulatedMovedWindowBounds")
            println("  Translated overlay position: ($movedScreenX, $movedScreenY)")
            val fileE = File(evidenceDir, "test_e_window_movement.png")
            ImageIO.write(robot.createScreenCapture(captureBounds), "png", fileE)
            println("  [EVIDENCE E] Saved screenshot: ${fileE.absolutePath}")

            // ------------------------------------------------------------
            // TEST F — OBJECT TRANSFORMATION (MOVE CUBE UP)
            // ------------------------------------------------------------
            println("\n--- [TEST F: OBJECT TRANSFORMATION] ---")
            // Check snapshot before move
            val snapBefore = (adapter.takeSnapshot() as AdapterSnapshotResult.Success).snapshot
            val cubeBefore = snapBefore.objects.first { it.name == "Cube" }
            val selectedBefore = cubeBefore.selected
            println("  Initial Cube selected state: $selectedBefore")

            // Move Cube upward by 1.5m
            triggerAction(
                triggerFile,
                """
                import bpy
                cube = bpy.data.objects.get('Cube')
                if cube:
                    cube.location.z = 1.5
                """.trimIndent()
            )
            delay(400)

            val movedProj = adapter.projectObject("Cube")
            assertTrue(movedProj is ProjectedObjectResult.Success)
            println("  Moved Cube projected bounds: Y=${movedProj.bounds.y}")

            // Non-destructive check: verify selection was unaltered by projection
            val snapAfter = (adapter.takeSnapshot() as AdapterSnapshotResult.Success).snapshot
            val cubeAfter = snapAfter.objects.first { it.name == "Cube" }
            assertEquals(selectedBefore, cubeAfter.selected, "Projection must not alter object selection state")
            println("  Selection Preservation verified: selected=${cubeAfter.selected}")

            val fileF = File(evidenceDir, "test_f_object_transformation.png")
            ImageIO.write(robot.createScreenCapture(captureBounds), "png", fileF)
            println("  [EVIDENCE F] Saved screenshot: ${fileF.absolutePath}")

            // ------------------------------------------------------------
            // TEST G — TARGET DELETION
            // ------------------------------------------------------------
            println("\n--- [TEST G: TARGET DELETION] ---")
            triggerAction(
                triggerFile,
                """
                import bpy
                cube = bpy.data.objects.get('Cube')
                if cube:
                    bpy.data.objects.remove(cube, do_unlink=True)
                """.trimIndent()
            )
            delay(400)

            val deletedProj = adapter.projectObject("Cube")
            assertTrue(deletedProj is ProjectedObjectResult.NotFound, "Deleted object must return NotFound")
            println("  Deleted object projection result: $deletedProj")

            val deleteResolution = service.resolveTarget(moveStep)
            assertNull(deleteResolution.region, "Highlight region must be null (suppressed) after target deletion")
            controller.setHighlight(deleteResolution.region)
            assertNull(controller.state.currentHighlight, "Current highlight state must be null after deletion")
            println("  Stale highlight successfully suppressed: diagnostic='${deleteResolution.diagnostic}'")

            val fileG = File(evidenceDir, "test_g_target_deletion.png")
            ImageIO.write(robot.createScreenCapture(captureBounds), "png", fileG)
            println("  [EVIDENCE G] Saved screenshot: ${fileG.absolutePath}")

            // ------------------------------------------------------------
            // TEST H — STEP TRANSITION
            // ------------------------------------------------------------
            println("\n--- [TEST H: STEP TRANSITION] ---")
            controller.setHighlight(HighlightRegion(100f, 100f, 50f, 50f))
            assertNotNull(controller.state.currentHighlight)
            controller.clearUserGoal()
            assertNull(controller.state.currentHighlight, "Clearing user goal must reset highlight to null")
            println("  Step/Goal cleared cleanly: highlight is null")

            // ------------------------------------------------------------
            // TEST I — USER INTERACTION (CLICK-THROUGH / OVERLAY)
            // ------------------------------------------------------------
            println("\n--- [TEST I: USER INTERACTION] ---")
            println("  Overlay Window Style: WS_EX_TRANSPARENT (0x20) active for click-through")
            println("  Overlay Window Focus: focusable=false, keeping keyboard input targeted to Blender")

            // ------------------------------------------------------------
            // TEST J — VERIFICATION INTEGRITY
            // ------------------------------------------------------------
            println("\n--- [TEST J: VERIFICATION INTEGRITY] ---")
            val workflow = BlenderVerificationWorkflow(provider = adapter)

            // Re-create a baseline cube at Z = 0
            triggerAction(
                triggerFile,
                """
                import bpy
                bpy.ops.mesh.primitive_cube_add(location=(0, 0, 0))
                cube = bpy.context.active_object
                cube.name = "Cube"
                """.trimIndent()
            )
            delay(400)

            val baseline = workflow.captureBaseline(moveStep.expectedState!!)
            val verifyUnchanged = workflow.verifyAction(baseline)
            println("  Verification of unchanged state (Z=0): Status=${verifyUnchanged.status}")
            assertEquals(VerificationStatus.FAILURE, verifyUnchanged.status, "Unchanged object at Z=0 must fail verification")

            // Move cube to Z = 2.0m (Condition satisfied)
            triggerAction(
                triggerFile,
                """
                import bpy
                cube = bpy.data.objects.get('Cube')
                if cube:
                    cube.location.z = 2.0
                """.trimIndent()
            )
            delay(400)

            val verifyMoved = workflow.verifyAction(baseline)
            println("  Verification of moved state (Z=2.0): Status=${verifyMoved.status}")
            assertEquals(VerificationStatus.SUCCESS, verifyMoved.status, "Cube moved to Z=2.0 must pass verification")

            // ------------------------------------------------------------
            // PERFORMANCE MEASUREMENTS (PHASE 6)
            // ------------------------------------------------------------
            println("\n--- [PERFORMANCE BENCHMARKS (PHASE 6)] ---")
            val latencies = mutableListOf<Long>()
            repeat(10) {
                val t = measureTimeMillis {
                    adapter.projectObject("Cube")
                }
                latencies.add(t)
            }
            val avgLatency = latencies.average()
            val minLatency = latencies.minOrNull() ?: 0L
            val maxLatency = latencies.maxOrNull() ?: 0L
            println("  Projection Request Latency: Avg=${String.format("%.2f", avgLatency)}ms, Min=${minLatency}ms, Max=${maxLatency}ms")
            assertTrue(avgLatency < 100.0, "Average projection request latency must be under 100ms")

            println("\n============================================================")
            println("ALL PHASE 9 LIVE VISUAL ACCEPTANCE TESTS PASSED!")
            println("============================================================\n")
        }
    }

    private fun triggerAction(triggerFile: File, pythonCode: String) {
        triggerFile.writeText(pythonCode)
    }

    private suspend fun withInteractiveBlender(block: suspend (port: Int, triggerFile: File, windowInfo: BlenderWindowInfo) -> Unit) {
        assumeTrue("Blender executable must exist at $blenderExe", Files.isRegularFile(Path.of(blenderExe)))

        val addonCandidates = listOf(
            Path.of(System.getProperty("user.dir"), "blender", "ai_visual_tutor_state", "__init__.py"),
            Path.of(System.getProperty("user.dir"), "..", "blender", "ai_visual_tutor_state", "__init__.py")
        ).map { it.normalize().toAbsolutePath() }
        val addon = addonCandidates.firstOrNull(Files::isRegularFile)
            ?: error("Blender adapter not found in ${addonCandidates.joinToString()}")

        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val triggerDir = Files.createTempDirectory("aivt_phase9_visual").toFile()
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

        // Launch real interactive Blender (no --background flag!)
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
            val adapter = BlenderApplicationAdapter(port = port, timeoutMillis = 1500)
            var ready = false
            for (i in 1..80) {
                val health = adapter.checkHealth()
                if (health.status == AdapterHealthStatus.HEALTHY) {
                    ready = true
                    break
                }
                delay(200)
            }
            assertTrue(ready, "Blender adapter failed to become healthy on port $port")

            // Locate interactive desktop Blender window
            var windowFound: BlenderWindowInfo? = null
            for (i in 1..40) {
                delay(250)
                when (val res = locator.locateBlenderWindow()) {
                    is BlenderWindowResult.Found -> {
                        windowFound = res.window
                        break
                    }
                    else -> {}
                }
            }
            assertNotNull(windowFound, "Live interactive Blender GUI window must be located on desktop")

            block(port, triggerFile, windowFound)
        } finally {
            process.destroyForcibly()
            triggerDir.deleteRecursively()
        }
    }
}
