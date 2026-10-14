package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import models.HighlightRegion
import models.TutorStep

class DynamicTargetPositioningServiceTest {

    @Test
    fun `resolves target via UI Automation when element exists`() = runBlocking {
        val mockClient = UiAutomationClient { hwnd ->
            listOf(
                UiAutomationCandidate(
                    name = "Add",
                    automationId = "btn_add_menu",
                    controlTypeId = 50000,
                    bounds = Rectangle(150, 80, 70, 26),
                    isEnabled = true,
                    isOffscreen = false
                ),
                UiAutomationCandidate(
                    name = "File",
                    automationId = "btn_file_menu",
                    controlTypeId = 50000,
                    bounds = Rectangle(50, 80, 60, 26),
                    isEnabled = true,
                    isOffscreen = false
                )
            )
        }

        val service = DynamicTargetPositioningService(uiaClient = mockClient)
        val step = TutorStep(
            id = 1,
            title = "Add Menu",
            instruction = "Open the Add menu.",
            description = "Click Add",
            stepNumber = 1,
            totalSteps = 5,
            targetControlName = "Add",
            targetRegion = HighlightRegion(100f, 100f, 50f, 50f)
        )

        val appContext = ApplicationContext(
            applicationName = "TestApp",
            processName = "testapp.exe",
            pid = 1234L,
            windowHandle = 5678L,
            windowBounds = Rectangle(0, 0, 1920, 1080)
        )

        val result = service.resolveTarget(step, appContext)

        assertEquals(TargetLocalizationSource.UI_AUTOMATION, result.source)
        assertTrue(result.isSemanticallyVerified)
        assertTrue(result.confidence >= 0.90)
        assertNotNull(result.region)
        assertEquals(150f, result.region!!.x)
        assertEquals(80f, result.region!!.y)
        assertEquals(70f, result.region!!.width)
        assertEquals(26f, result.region!!.height)
    }

    @Test
    fun `rejects UIA element when located outside application window bounds`() = runBlocking {
        val mockClient = UiAutomationClient { hwnd ->
            listOf(
                UiAutomationCandidate(
                    name = "Add",
                    automationId = "btn_add_menu",
                    controlTypeId = 50000,
                    bounds = Rectangle(2500, 80, 70, 26), // outside 1920x1080 window
                    isEnabled = true,
                    isOffscreen = false
                )
            )
        }

        val service = DynamicTargetPositioningService(uiaClient = mockClient)
        val step = TutorStep(
            id = 1,
            title = "Add Menu",
            instruction = "Open the Add menu.",
            description = "Click Add",
            stepNumber = 1,
            totalSteps = 5,
            targetControlName = "Add",
            targetRegion = HighlightRegion(220f, 90f, 90f, 30f)
        )

        val appContext = ApplicationContext(
            applicationName = "TestApp",
            processName = "testapp.exe",
            pid = 1234L,
            windowHandle = 5678L,
            windowBounds = Rectangle(0, 0, 1920, 1080)
        )

        val result = service.resolveTarget(step, appContext)

        // Rejected due to out-of-window bounds, falls back to step default
        assertEquals(TargetLocalizationSource.STEP_DEFAULT, result.source)
        assertEquals(220f, result.region!!.x)
    }

    @Test
    fun `falls back to OCR matching when UI Automation element not found`() = runBlocking {
        val mockClient = UiAutomationClient { emptyList() }
        val mockOcr = OCRService { _ ->
            OcrResult(
                text = "File Edit Add Render Window Help",
                confidence = 0.95f,
                words = listOf(
                    OcrWord(text = "File", confidence = 0.95f, bounds = Rectangle(10, 10, 40, 20), lineIndex = 0, wordIndex = 0),
                    OcrWord(text = "Edit", confidence = 0.95f, bounds = Rectangle(60, 10, 40, 20), lineIndex = 0, wordIndex = 1),
                    OcrWord(text = "Add", confidence = 0.92f, bounds = Rectangle(110, 10, 35, 20), lineIndex = 0, wordIndex = 2)
                ),
                coordinateSpace = OcrCoordinateSpace.CROP_IMAGE_PIXELS,
                engineName = "MockOCR"
            )
        }

        val service = DynamicTargetPositioningService(uiaClient = mockClient, ocrService = mockOcr)
        val step = TutorStep(
            id = 1,
            title = "Add Menu",
            instruction = "Open the Add menu.",
            description = "Click Add",
            stepNumber = 1,
            totalSteps = 5,
            targetControlName = "Add",
            targetRegion = HighlightRegion(220f, 90f, 90f, 30f)
        )

        val dummyImage = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        val appContext = ApplicationContext(
            applicationName = "TestApp",
            processName = "testapp.exe",
            pid = 1234L,
            windowHandle = 5678L,
            windowBounds = Rectangle(100, 50, 800, 600)
        )

        val result = service.resolveTarget(step, appContext, dummyImage)

        assertEquals(TargetLocalizationSource.OCR, result.source)
        assertTrue(result.isSemanticallyVerified)
        assertNotNull(result.region)
        // Offset is originX(100) + targetBox.x(110) = 210
        assertEquals(210f, result.region!!.x)
        // Offset is originY(50) + targetBox.y(10) = 60
        assertEquals(60f, result.region!!.y)
        assertEquals(35f, result.region!!.width)
        assertEquals(20f, result.region!!.height)
    }

    @Test
    fun `abstains gracefully when target cannot be found and no step default exists`() = runBlocking {
        val service = DynamicTargetPositioningService()
        val step = TutorStep(
            id = 1,
            title = "Unknown Step",
            instruction = "Perform something obscure.",
            description = "No target info",
            stepNumber = 1,
            totalSteps = 5,
            targetRegion = null
        )

        val result = service.resolveTarget(step)
        assertEquals(TargetLocalizationSource.ABSTAIN, result.source)
        assertNull(result.region)
    }

    @Test
    fun `resolves target via visual grounding using RegionFocus sub-crop`() = runBlocking {
        val mockRouter = EvidenceAdaptiveGroundingRouter(
            uia = { GroundingProviderRun(GroundingProviderId.UIA, GroundingProviderStatus.EMPTY_RESULT, 0) },
            ocr = { GroundingProviderRun(GroundingProviderId.OCR, GroundingProviderStatus.EMPTY_RESULT, 0) },
            vision = { req, _ ->
                val box = GroundingBox(50.0, 40.0, 100.0, 80.0)
                GroundingProviderRun(
                    GroundingProviderId.VISION,
                    GroundingProviderStatus.SUCCESS,
                    latencyMillis = 10,
                    observations = listOf(
                        GroundingObservation(
                            source = GroundingProviderId.VISION,
                            candidateId = "target-1",
                            observedText = "properties",
                            targetDescription = req.targetDescription.orEmpty(),
                            box = box,
                            originalBox = box,
                            originalCoordinateSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
                            coordinateSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
                            providerConfidence = 0.95,
                            textSimilarity = 0.95,
                            sourceMetadata = emptyMap(),
                            executionId = req.executionId
                        )
                    )
                )
            }
        )

        val service = DynamicTargetPositioningService(
            groundingRouter = mockRouter,
            regionFocusService = RegionFocusService(contextualMarginPx = 50)
        )

        val step = TutorStep(
            id = 2,
            title = "Locate Properties Panel",
            instruction = "Open properties in the right inspector.",
            description = "Click Properties",
            stepNumber = 2,
            totalSteps = 5,
            targetAreaDescription = "properties panel on the right",
            targetRegion = null
        )

        val dummyImage = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB)
        val appContext = ApplicationContext(
            applicationName = "TestApp",
            processName = "testapp.exe",
            pid = 100L,
            windowHandle = 200L,
            windowBounds = Rectangle(0, 0, 1920, 1080)
        )

        val result = service.resolveTarget(step, appContext, dummyImage)

        assertEquals(TargetLocalizationSource.VISUAL_GROUNDING, result.source)
        assertTrue(result.isSemanticallyVerified)
        assertNotNull(result.region)
        assertNotNull(result.boundsInScreenCoordinates)
        assertTrue(result.diagnostic?.contains("RegionFocus") == true)
        // Sub-crop was right quadrant (0.65 * 1920 = 1248 offset)
        assertTrue(result.boundsInScreenCoordinates!!.x >= 1248)
        assertTrue(result.region!!.isScreenCoordinates)
    }

    @Test
    fun `rejects degenerate collapsed bounding boxes and abstains when fallback disabled`() = runBlocking {
        val mockClient = UiAutomationClient { hwnd ->
            listOf(
                UiAutomationCandidate(
                    name = "Add",
                    automationId = "btn_add",
                    controlTypeId = 50000,
                    bounds = Rectangle(100, 100, 2, 2), // Degenerate < 4x4
                    isEnabled = true,
                    isOffscreen = false
                )
            )
        }

        val service = DynamicTargetPositioningService(uiaClient = mockClient)
        val step = TutorStep(
            id = 1,
            title = "Add",
            instruction = "Click Add",
            description = "Click Add",
            stepNumber = 1,
            totalSteps = 1,
            targetControlName = "Add",
            targetRegion = null
        )

        val appContext = ApplicationContext(
            applicationName = "TestApp",
            processName = "test.exe",
            pid = 1L,
            windowHandle = 2L,
            windowBounds = Rectangle(0, 0, 1920, 1080)
        )

        val result = service.resolveTarget(step, appContext, allowStepDefaultFallback = false)
        assertEquals(TargetLocalizationSource.ABSTAIN, result.source)
        assertNull(result.region)
    }

    @Test
    fun `rejects oversized bounding boxes covering entire window`() = runBlocking {
        val mockClient = UiAutomationClient { hwnd ->
            listOf(
                UiAutomationCandidate(
                    name = "Canvas",
                    automationId = "root_canvas",
                    controlTypeId = 50000,
                    bounds = Rectangle(0, 0, 1920, 1080), // Covers 100% of window
                    isEnabled = true,
                    isOffscreen = false
                )
            )
        }

        val service = DynamicTargetPositioningService(uiaClient = mockClient)
        val step = TutorStep(
            id = 1,
            title = "Canvas",
            instruction = "Click Canvas",
            description = "Click Canvas",
            stepNumber = 1,
            totalSteps = 1,
            targetControlName = "Canvas",
            targetRegion = null
        )

        val appContext = ApplicationContext(
            applicationName = "TestApp",
            processName = "test.exe",
            pid = 1L,
            windowHandle = 2L,
            windowBounds = Rectangle(0, 0, 1920, 1080)
        )

        val result = service.resolveTarget(step, appContext, allowStepDefaultFallback = false)
        assertEquals(TargetLocalizationSource.ABSTAIN, result.source)
    }

    @Test
    fun `normalizes OCR screen coordinates using DpiCoordinateNormalizer and tags screen coordinates`() = runBlocking {
        val mockDpiNormalizer = DpiCoordinateNormalizer(
            monitorsProvider = {
                listOf(
                    MonitorGeometry(
                        id = "PRIMARY",
                        logicalBounds = Rectangle(0, 0, 1280, 720),
                        scaleX = 1.5,
                        scaleY = 1.5,
                        physicalBounds = Rectangle(0, 0, 1920, 1080)
                    )
                )
            }
        )
        val mockOcr = OCRService { _ ->
            OcrResult(
                text = "Object Mode",
                confidence = 0.95f,
                words = listOf(
                    OcrWord(text = "Object", confidence = 0.95f, bounds = Rectangle(30, 30, 60, 30), lineIndex = 0, wordIndex = 0),
                    OcrWord(text = "Mode", confidence = 0.95f, bounds = Rectangle(95, 30, 50, 30), lineIndex = 0, wordIndex = 1)
                ),
                coordinateSpace = OcrCoordinateSpace.CROP_IMAGE_PIXELS,
                engineName = "MockOCR"
            )
        }

        val service = DynamicTargetPositioningService(
            ocrService = mockOcr,
            dpiNormalizer = mockDpiNormalizer
        )

        val step = TutorStep(
            id = 1,
            title = "Mode",
            instruction = "Select Object Mode",
            description = "Click Object",
            stepNumber = 1,
            totalSteps = 1,
            targetControlName = "Object",
            targetRegion = null
        )

        val dummyImage = BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
        val appContext = ApplicationContext(
            applicationName = "TestApp",
            processName = "test.exe",
            pid = 1L,
            windowHandle = 2L,
            windowBounds = Rectangle(0, 0, 800, 600)
        )

        val result = service.resolveTarget(step, appContext, dummyImage)
        assertEquals(TargetLocalizationSource.OCR, result.source)
        assertNotNull(result.region)
        assertTrue(result.region!!.isScreenCoordinates)
        // Physical bounds: (30, 30, 60, 30). At 150% DPI scale, logical = (30 / 1.5, 30 / 1.5, 60 / 1.5, 30 / 1.5) = (20, 20, 40, 20)
        assertEquals(20f, result.region!!.x)
        assertEquals(20f, result.region!!.y)
        assertEquals(40f, result.region!!.width)
        assertEquals(20f, result.region!!.height)
    }
}

