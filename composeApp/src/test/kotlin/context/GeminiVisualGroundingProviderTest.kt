package context

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GeminiVisualGroundingProviderTest {

    @Test
    fun `reports NOT_CONFIGURED when API key is blank`() = runBlocking {
        val provider = GeminiVisualGroundingProvider(apiKey = "")
        val image = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)
        val request = VisualGroundingRequest(
            screenshot = image,
            applicationContext = null,
            targetDescription = "Add Menu",
            ocrEvidence = null,
            uiAutomationEvidence = null
        )

        val result = provider.ground(request)
        assertEquals(GroundingProviderAvailability.NOT_CONFIGURED, result.availability)
    }

    @Test
    fun `normalizes 0-1000 bounding box correctly to 0-1 crop bounds`() {
        val provider = GeminiVisualGroundingProvider(apiKey = "test_key")
        // Reflection test on internal parsing logic
        val parseMethod = provider.javaClass.getDeclaredMethod(
            "parseGeminiBoundingBox",
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType
        )
        parseMethod.isAccessible = true

        val json = """{"box_2d": [100, 200, 300, 600], "label": "Add"}"""
        val result = parseMethod.invoke(provider, json, "Add", 150L) as VisualGroundingResult

        assertNotNull(result.geometry)
        assertEquals(VisualGroundingCoordinateSpace.NORMALIZED_CROP, result.geometry!!.coordinateSpace)

        val bounds = result.geometry!!.bounds
        assertNotNull(bounds)
        // [ymin=100, xmin=200, ymax=300, xmax=600]
        // left = xmin/1000 = 0.2, top = ymin/1000 = 0.1, right = xmax/1000 = 0.6, bottom = ymax/1000 = 0.3
        assertEquals(0.2, bounds.left, 0.001)
        assertEquals(0.1, bounds.top, 0.001)
        assertEquals(0.6, bounds.right, 0.001)
        assertEquals(0.3, bounds.bottom, 0.001)
    }
}
