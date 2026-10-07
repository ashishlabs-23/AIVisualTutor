package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class PerceptionContractsTest {
    @Test
    fun requestAndResultCarryPhaseFourFields() = runBlocking {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val selected = Rectangle(-20, 10, 40, 30)
        val application = ApplicationContext("Editor", "editor.exe", pid = 42)
        val request = PerceptionRequest(image, selected, application)
        val result = PerceptionEngine {
            PerceptionResult(
                applicationName = it.applicationContext?.applicationName,
                selectedObject = "Save button",
                visibleText = "Save",
                uiType = UiType.BUTTON,
                boundingRectangle = it.selectedRegion,
                confidence = 0.9f,
                source = PerceptionSource.UI_AUTOMATION,
                metadata = mapOf("provider" to "future-provider")
            )
        }.perceive(request)

        assertEquals(image, request.screenshot)
        assertEquals(selected, request.selectedRegion)
        assertEquals(application, request.applicationContext)
        assertEquals("Editor", result.applicationName)
        assertEquals("Save button", result.selectedObject)
        assertEquals("Save", result.visibleText)
        assertEquals(UiType.BUTTON, result.uiType)
        assertEquals(selected, result.boundingRectangle)
        assertEquals(0.9f, result.confidence)
        assertEquals(PerceptionSource.UI_AUTOMATION, result.source)
        assertEquals("future-provider", result.metadata["provider"])
    }

    @Test
    fun unavailableObservationsCanBeRepresented() {
        val result = PerceptionResult(null, null, null, UiType.UNKNOWN, null, null, PerceptionSource.UNKNOWN)

        assertNull(result.applicationName)
        assertNull(result.boundingRectangle)
        assertEquals(emptyMap(), result.metadata)
    }
}
