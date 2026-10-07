package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class VisualGroundingContractsTest {
    @Test fun requestPreservesScreenshotApplicationOcrAndUiaEvidence() = runBlocking {
        val screenshot = BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB)
        val app = ApplicationContext("Editor", "editor.exe")
        val ocr = OcrResult("Save", .9f, listOf(OcrWord("Save", .9f, Rectangle(1, 2, 6, 3))), "test-ocr")
        val uia = PerceptionResult("Editor", "Save", null, UiType.BUTTON, Rectangle(100, 200, 60, 25), 1f, PerceptionSource.UI_AUTOMATION)
        val request = VisualGroundingRequest(screenshot, app, "Save button", ocr, uia)
        var received: VisualGroundingRequest? = null
        val semantic = GroundingSemanticEvidence("Save button", "button", .8f)
        val geometry = GroundingGeometricEvidence(point = GroundingPoint(4.0, 4.0), coordinateSpace = VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS, confidence = .7f)
        val result = VisualGroundingProvider { received = it; VisualGroundingResult(semantic, geometry, "test-provider", GroundingProviderAvailability.AVAILABLE) }.ground(request)

        assertSame(screenshot, received?.screenshot)
        assertSame(app, received?.applicationContext)
        assertSame(ocr, received?.ocrEvidence)
        assertSame(uia, received?.uiAutomationEvidence)
        assertEquals("Save button", received?.targetDescription)
        assertSame(semantic, result.semantic)
        assertSame(geometry, result.geometry)
        assertSame(uia, request.uiAutomationEvidence)
        assertSame(ocr, request.ocrEvidence)
    }

    @Test fun semanticEvidenceDoesNotRequireOrDeclareGeometry() {
        val result = VisualGroundingResult(
            semantic = GroundingSemanticEvidence("toolbar tool", confidence = .6f),
            geometry = null,
            providerId = "semantic-only",
            availability = GroundingProviderAvailability.AVAILABLE
        )
        assertEquals("toolbar tool", result.semantic?.target)
        assertNull(result.geometry)
    }

    @Test fun geometryRequiresExplicitCoordinateSpaceAndBoundsOrPoint() {
        val geometry = GroundingGeometricEvidence(bounds = GroundingBounds(.1, .2, .4, .5), coordinateSpace = VisualGroundingCoordinateSpace.NORMALIZED_CROP)
        assertEquals(VisualGroundingCoordinateSpace.NORMALIZED_CROP, geometry.coordinateSpace)
        assertFailsWith<IllegalArgumentException> {
            GroundingGeometricEvidence(coordinateSpace = VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS)
        }
        Unit
    }

    @Test fun unavailableProviderReturnsNoFabricatedEvidence() = runBlocking {
        val result = UnavailableVisualGroundingProvider().ground(
            VisualGroundingRequest(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), null, null, null, null)
        )
        assertEquals(GroundingProviderAvailability.UNAVAILABLE, result.availability)
        assertNull(result.semantic)
        assertNull(result.geometry)
        assertEquals("provider_unavailable", result.metadata["status"])
    }

    @Test fun evaluationFixturesDeclareTargetAndCoordinateAnnotations() {
        assertEquals(setOf("text-only", "button-like", "dense-ui", "empty-non-text", "icon-only", "neighboring-text"), OcrFixtures.grounding.map { it.imageId }.toSet())
        assertTrue(OcrFixtures.grounding.all { it.application.isNotBlank() && it.expectedRegion.width > 0 && it.expectedRegion.height > 0 })
        assertTrue(OcrFixtures.grounding.all { it.sourceAnnotations.isNotEmpty() && it.coordinateSpace == VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS })
        assertTrue(OcrFixtures.grounding.filter { it.targetType != "non-target" }.all { it.targetDescriptionVariants.size >= 1 && it.targetDescriptionVariants.all { variant -> variant.expectedSameTarget } })
    }
}
