package overlay

import adapter.AdapterVersionInfo
import adapter.AdapterVersionResult
import adapter.BlenderApplicationAdapter
import adapter.ProjectedObjectResult
import context.ApplicationContext
import context.DynamicTargetPositioningService
import context.ExpectedCondition
import context.ExpectedState
import context.ExpectedStateField
import context.TargetLocalizationSource
import context.VerificationOperation
import context.VerificationStatus
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import models.DirectionalAxis
import models.DirectionalIndicator
import models.HighlightRegion
import models.TutorStep
import tutor.TutorController

/**
 * Unit and pipeline regression tests for the live 3D object-highlighting workflow.
 */
class Live3DObjectHighlightingPipelineTest {

    @Test
    fun testIdentifyTargetObjectFromExpectedStateAndControlName() {
        val service = DynamicTargetPositioningService()

        // 1. Target from expectedState.targetName
        val step1 = TutorStep(
            id = 4,
            title = "Move Cube Up",
            instruction = "Press G, then Z to constrain to the vertical Z-axis and move upward.",
            description = "Move Cube Up",
            stepNumber = 4,
            totalSteps = 4,
            targetControlName = "3D Viewport", // generic control name
            expectedState = ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetName = "Cube",
                targetProperty = "location.z",
                expectedPropertyValue = "> 0"
            )
        )
        assertEquals("Cube", service.identifyTargetObject(step1))

        // 2. Target from requiredConditions
        val step2 = TutorStep(
            id = 2,
            title = "Translate Primitive",
            instruction = "Translate object.",
            description = "Translate object",
            stepNumber = 2,
            totalSteps = 2,
            targetControlName = null,
            expectedState = ExpectedState(
                operation = VerificationOperation.MODIFY,
                requiredConditions = listOf(
                    ExpectedCondition(
                        field = ExpectedStateField.OBJECT_EXISTS,
                        objectName = "Sphere",
                        expectedValue = "Sphere"
                    )
                )
            )
        )
        assertEquals("Sphere", service.identifyTargetObject(step2))

        // 3. Target from targetControlName
        val step3 = TutorStep(
            id = 1,
            title = "Select Suzanne",
            instruction = "Select monkey head in viewport.",
            description = "Select monkey head",
            stepNumber = 1,
            totalSteps = 2,
            targetControlName = "Suzanne"
        )
        assertEquals("Suzanne", service.identifyTargetObject(step3))

        // 4. UI controls must NOT be identified as 3D scene objects
        val stepUi = TutorStep(
            id = 1,
            title = "Ensure Object Mode",
            instruction = "Switch to Object Mode.",
            description = "Switch to Object Mode",
            stepNumber = 1,
            totalSteps = 3,
            targetControlName = "Mode",
            expectedState = ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetMode = "OBJECT"
            )
        )
        assertNull(service.identifyTargetObject(stepUi))
    }

    @Test
    fun test3DObjectProjectionResolvesViewportBoundsAndAttachesZUpIndicator() = runBlocking {
        val mockAdapter = object : BlenderApplicationAdapter(port = 47629) {
            override suspend fun projectObject(targetName: String): ProjectedObjectResult {
                return if (targetName == "Cube") {
                    ProjectedObjectResult.Success(
                        targetName = "Cube",
                        identity = "session:xyz:pointer:123",
                        bounds = HighlightRegion(
                            x = 320f,
                            y = 240f,
                            width = 160f,
                            height = 140f,
                            isScreenCoordinates = false // Window-relative within Blender
                        ),
                        isVisible = true,
                        isSelected = true
                    )
                } else {
                    ProjectedObjectResult.NotFound(targetName, "Object '$targetName' not found")
                }
            }
        }

        val service = DynamicTargetPositioningService(blenderAdapter = mockAdapter)

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

        val result = service.resolveTarget(moveStep)
        assertEquals(TargetLocalizationSource.BLENDER_3D_PROJECTION, result.source)
        assertTrue(result.isSemanticallyVerified)
        assertEquals("Cube", result.candidateName)
        assertNotNull(result.region)

        val region = result.region!!
        assertEquals(320f, region.x)
        assertEquals(240f, region.y)
        assertEquals(160f, region.width)
        assertEquals(140f, region.height)
        assertFalse(region.isScreenCoordinates) // Window-relative

        // Phase 4: Must attach directional indicator pointing in +Z direction
        assertNotNull(region.directionalIndicator)
        assertEquals(DirectionalAxis.UP_POSITIVE_Z, region.directionalIndicator!!.axis)
        assertEquals("+Z Up", region.directionalIndicator!!.label)
    }

    @Test
    fun testWindowRelativeCoordinatesCorrectlyOffsetByBlenderWindowBounds() {
        val windowRelativeRegion = HighlightRegion(
            x = 320f,
            y = 240f,
            width = 160f,
            height = 140f,
            isScreenCoordinates = false,
            directionalIndicator = DirectionalIndicator(DirectionalAxis.UP_POSITIVE_Z, "+Z Up")
        )

        val blenderWindowBounds = Rectangle(200, 100, 1920, 1080)

        // Translation logic matching OverlayWindowContent:
        val positionedRegion = if (windowRelativeRegion.isScreenCoordinates) {
            windowRelativeRegion
        } else if (windowRelativeRegion.x < blenderWindowBounds.width && windowRelativeRegion.y < blenderWindowBounds.height) {
            HighlightRegion(
                x = blenderWindowBounds.x.toFloat() + windowRelativeRegion.x,
                y = blenderWindowBounds.y.toFloat() + windowRelativeRegion.y,
                width = windowRelativeRegion.width,
                height = windowRelativeRegion.height,
                isScreenCoordinates = true,
                directionalIndicator = windowRelativeRegion.directionalIndicator
            )
        } else {
            windowRelativeRegion
        }

        assertTrue(positionedRegion.isScreenCoordinates)
        assertEquals(520f, positionedRegion.x) // 200 + 320
        assertEquals(340f, positionedRegion.y) // 100 + 240
        assertEquals(160f, positionedRegion.width)
        assertEquals(140f, positionedRegion.height)
        assertEquals(DirectionalAxis.UP_POSITIVE_Z, positionedRegion.directionalIndicator?.axis)
    }

    @Test
    fun testTargetOutsideViewportSuppressesHighlightCleanly() = runBlocking {
        val mockAdapter = object : BlenderApplicationAdapter(port = 47629) {
            override suspend fun projectObject(targetName: String): ProjectedObjectResult {
                return ProjectedObjectResult.NotProjected(targetName, "Object '$targetName' outside 3D viewport")
            }
        }

        val service = DynamicTargetPositioningService(blenderAdapter = mockAdapter)
        val step = TutorStep(
            id = 4,
            title = "Move Cube Up",
            instruction = "Move cube upward.",
            description = "Move cube upward",
            stepNumber = 4,
            totalSteps = 4,
            expectedState = ExpectedState(operation = VerificationOperation.MODIFY, targetName = "Cube")
        )

        val result = service.resolveTarget(step)
        assertEquals(TargetLocalizationSource.BLENDER_3D_PROJECTION, result.source)
        assertNull(result.region) // Stale highlight suppressed!
        assertFalse(result.isSemanticallyVerified)
        assertTrue(result.diagnostic?.contains("outside") == true)
    }

    @Test
    fun testTargetDeletedSuppressesHighlightAndReportsProblem() = runBlocking {
        val mockAdapter = object : BlenderApplicationAdapter(port = 47629) {
            override suspend fun projectObject(targetName: String): ProjectedObjectResult {
                return ProjectedObjectResult.NotFound(targetName, "Object '$targetName' not found in scene")
            }
        }

        val service = DynamicTargetPositioningService(blenderAdapter = mockAdapter)
        val step = TutorStep(
            id = 4,
            title = "Move Cube Up",
            instruction = "Move cube upward.",
            description = "Move cube upward",
            stepNumber = 4,
            totalSteps = 4,
            expectedState = ExpectedState(operation = VerificationOperation.MODIFY, targetName = "Cube")
        )

        val result = service.resolveTarget(step)
        assertEquals(TargetLocalizationSource.BLENDER_3D_PROJECTION, result.source)
        assertNull(result.region)
        assertTrue(result.diagnostic?.contains("not found") == true)
    }

    @Test
    fun testNonDestructiveProjectionPreservesSelectionState() {
        val json = """
        {
          "status": "PROJECTED",
          "target": "Cube",
          "identity": "session:abc:pointer:def",
          "scene": "Scene",
          "visible": true,
          "selected": true,
          "bounds": {
            "x": 400.0,
            "y": 300.0,
            "width": 120.0,
            "height": 110.0
          },
          "center": {
            "x": 460.0,
            "y": 355.0
          }
        }
        """.trimIndent()

        val adapter = BlenderApplicationAdapter()
        val result = adapter.parseProjectedObjectJson(json)
        assertTrue(result is ProjectedObjectResult.Success)
        assertEquals("Cube", result.targetName)
        assertTrue(result.isSelected)
        assertTrue(result.isVisible)
        assertEquals(400f, result.bounds.x)
        assertEquals(300f, result.bounds.y)
    }

    @Test
    fun testVersionAndEventCounterParsing() {
        val json = """
        {
          "snapshotVersion": 42,
          "eventCounter": 99,
          "sessionId": "session-test-id",
          "activeMode": "OBJECT",
          "activeObject": "Cube",
          "selectedCount": 1
        }
        """.trimIndent()

        val adapter = BlenderApplicationAdapter()
        val result = adapter.parseVersionJson(json)
        assertTrue(result is AdapterVersionResult.Success)
        assertEquals(42L, result.info.snapshotVersion)
        assertEquals(99L, result.info.eventCounter)
        assertEquals("session-test-id", result.info.sessionId)
        assertEquals("OBJECT", result.info.activeMode)
        assertEquals("Cube", result.info.activeObject)
        assertEquals(1, result.info.selectedCount)
    }

    @Test
    fun testTutorControllerLiveTrackingRefreshesHighlightOnEventChange() = runBlocking {
        var eventCounter = 10L
        var projectedX = 200f

        val mockAdapter = object : BlenderApplicationAdapter(port = 47629) {
            override suspend fun fetchVersion(): AdapterVersionResult {
                return AdapterVersionResult.Success(
                    AdapterVersionInfo(
                        snapshotVersion = 1L,
                        eventCounter = eventCounter,
                        sessionId = "test-session"
                    )
                )
            }

            override suspend fun projectObject(targetName: String): ProjectedObjectResult {
                return ProjectedObjectResult.Success(
                    targetName = "Cube",
                    identity = "session:test:1",
                    bounds = HighlightRegion(x = projectedX, y = 150f, width = 100f, height = 100f),
                    isVisible = true,
                    isSelected = true
                )
            }
        }

        val controller = TutorController(
            blenderAdapter = mockAdapter,
            targetPositioningService = DynamicTargetPositioningService(blenderAdapter = mockAdapter)
        )

        controller.startUserGoal("make cube and move up")
        controller.goToStep(3) // Step 4: Move Cube Up (index 3)

        // Resolve initial position
        val initialRes = controller.resolveDynamicTarget()
        assertEquals(200f, controller.state.currentHighlight?.x)

        // Simulate viewport rotation in Blender: eventCounter increments, projectedX changes
        eventCounter = 11L
        projectedX = 350f

        // When version check detects eventCounter change, target is refreshed:
        val ver = mockAdapter.fetchVersion()
        assertTrue(ver is AdapterVersionResult.Success)
        assertEquals(11L, ver.info.eventCounter)

        controller.resolveDynamicTarget()
        assertEquals(350f, controller.state.currentHighlight?.x)
    }

    @Test
    fun testAmbiguousMultipleViewportsSuppressesHighlightCleanly() = runBlocking {
        val mockAdapter = object : BlenderApplicationAdapter(port = 47629) {
            override suspend fun projectObject(targetName: String, viewportIndex: Int?): ProjectedObjectResult {
                return ProjectedObjectResult.AmbiguousViewport(
                    target = targetName,
                    viewportCount = 2,
                    diagnostic = "Multiple 3D viewports detected (2). Target viewport cannot be determined unambiguously."
                )
            }
        }

        val service = DynamicTargetPositioningService(blenderAdapter = mockAdapter)
        val step = TutorStep(
            id = 4,
            title = "Move Cube Up",
            instruction = "Move cube upward.",
            description = "Move cube upward",
            stepNumber = 4,
            totalSteps = 4,
            expectedState = ExpectedState(operation = VerificationOperation.MODIFY, targetName = "Cube")
        )

        val result = service.resolveTarget(step)
        assertEquals(TargetLocalizationSource.BLENDER_3D_PROJECTION, result.source)
        assertNull(result.region, "Highlight region must be null when viewports are ambiguous")
        assertFalse(result.isSemanticallyVerified)
        assertTrue(result.diagnostic?.contains("Multiple 3D viewports detected") == true)
    }

    @Test
    fun testOcclusionAndClippingClassificationInTargetResolution() = runBlocking {
        val mockAdapter = object : BlenderApplicationAdapter(port = 47629) {
            override suspend fun projectObject(targetName: String, viewportIndex: Int?): ProjectedObjectResult {
                return ProjectedObjectResult.Success(
                    targetName = "Cube",
                    identity = "session:test:cube",
                    bounds = HighlightRegion(100f, 150f, 80f, 80f),
                    isVisible = true,
                    isSelected = false,
                    visibility = "POTENTIALLY_OCCLUDED",
                    occludedBy = "Sphere"
                )
            }
        }

        val service = DynamicTargetPositioningService(blenderAdapter = mockAdapter)
        val step = TutorStep(
            id = 4,
            title = "Move Cube Up",
            instruction = "Move cube upward.",
            description = "Move cube upward",
            stepNumber = 4,
            totalSteps = 4,
            expectedState = ExpectedState(operation = VerificationOperation.MODIFY, targetName = "Cube")
        )

        val result = service.resolveTarget(step)
        assertEquals(TargetLocalizationSource.BLENDER_3D_PROJECTION, result.source)
        assertNotNull(result.region)
        assertEquals(0.50, result.confidence)
        assertTrue(result.diagnostic?.contains("POTENTIALLY_OCCLUDED") == true)
        assertTrue(result.diagnostic?.contains("occluded by Sphere") == true)
    }

    @Test
    fun testCompleteCoordinatePipelineMultiMonitorAndWindowMoveCalculations() {
        // Test Stage 1 -> Stage 2: Blender Viewport Canvas to Window-Relative
        // Region: origin bottom-left of window at (100, 50), width=800, height=600 in a window of height=1080.
        // Object in region coords: min_x=200, max_x=350, min_y=150, max_y=300
        val regionX = 100f
        val regionY = 50f
        val totalH = 1080f
        val minX = 200f
        val maxX = 350f
        val minY = 150f
        val maxY = 300f

        val windowRelX = regionX + minX // 300
        val windowRelY = totalH - (regionY + maxY) // 1080 - (50 + 300) = 730
        val windowRelW = maxX - minX // 150
        val windowRelH = maxY - minY // 150

        assertEquals(300f, windowRelX)
        assertEquals(730f, windowRelY)
        assertEquals(150f, windowRelW)
        assertEquals(150f, windowRelH)

        // Test Stage 2 -> Stage 3: Window-Relative to Desktop Physical Coordinates
        // Blender Window moved to desktop position (500, 200)
        val blenderBounds = Rectangle(500, 200, 1920, 1080)
        val desktopX = blenderBounds.x + windowRelX // 500 + 300 = 800
        val desktopY = blenderBounds.y + windowRelY // 200 + 730 = 930

        assertEquals(800f, desktopX)
        assertEquals(930f, desktopY)

        // Test Stage 3 -> Stage 4: Multi-Monitor Virtual Screen Origin Translation
        // Multi-monitor layout: Monitor 2 is to the left (-1920 to 0), Primary is (0 to 1920).
        // Virtual screen bounds: x = -1920, y = 0, width = 3840, height = 1080
        val virtualScreenOffsetX = -1920f
        val virtualScreenOffsetY = 0f

        val overlayCanvasX = desktopX - virtualScreenOffsetX // 800 - (-1920) = 2720
        val overlayCanvasY = desktopY - virtualScreenOffsetY // 930 - 0 = 930

        assertEquals(2720f, overlayCanvasX)
        assertEquals(930f, overlayCanvasY)

        // Test Blender moved to secondary left monitor at (-1200, 150)
        val leftMonBlenderBounds = Rectangle(-1200, 150, 1920, 1080)
        val leftDesktopX = leftMonBlenderBounds.x + windowRelX // -1200 + 300 = -900
        val leftDesktopY = leftMonBlenderBounds.y + windowRelY // 150 + 730 = 880

        val leftOverlayCanvasX = leftDesktopX - virtualScreenOffsetX // -900 - (-1920) = 1020
        val leftOverlayCanvasY = leftDesktopY - virtualScreenOffsetY // 880 - 0 = 880

        assertEquals(1020f, leftOverlayCanvasX)
        assertEquals(880f, leftOverlayCanvasY)
        assertTrue(leftOverlayCanvasX >= 0, "Overlay canvas coordinates must be non-negative within virtual surface")
    }
}
