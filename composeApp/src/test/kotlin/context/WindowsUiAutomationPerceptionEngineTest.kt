package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class WindowsUiAutomationPerceptionEngineTest {
    private val app = ApplicationContext("Demo", "demo.exe", pid = 7, windowHandle = 1234)

    @Test
    fun mapsRequiredControlTypesToExistingUiTypes() {
        assertEquals(UiType.BUTTON, mapControlType(50000)) // Button
        assertEquals(UiType.INPUT, mapControlType(50004)) // Edit
        assertEquals(UiType.BUTTON, mapControlType(50002)) // CheckBox
        assertEquals(UiType.BUTTON, mapControlType(50013)) // RadioButton
        assertEquals(UiType.INPUT, mapControlType(50003)) // ComboBox
        assertEquals(UiType.UNKNOWN, mapControlType(50008)) // List
        assertEquals(UiType.UNKNOWN, mapControlType(50007)) // ListItem
        assertEquals(UiType.MENU, mapControlType(50009)) // Menu
        assertEquals(UiType.MENU, mapControlType(50011)) // MenuItem
        assertEquals(UiType.UNKNOWN, mapControlType(50018)) // Tab
        assertEquals(UiType.UNKNOWN, mapControlType(50023)) // Tree
        assertEquals(UiType.UNKNOWN, mapControlType(50024)) // TreeItem
        assertEquals(UiType.INPUT, mapControlType(50015)) // Slider
        assertEquals(UiType.UNKNOWN, mapControlType(50014)) // ScrollBar
        assertEquals(UiType.TOOLBAR, mapControlType(50021)) // ToolBar
        assertEquals(UiType.TEXT, mapControlType(50020)) // Text
        assertEquals(UiType.IMAGE, mapControlType(50006)) // Image
        assertEquals(UiType.UNKNOWN, mapControlType(50032)) // Window
        assertEquals(UiType.UNKNOWN, mapControlType(50033)) // Pane
        assertEquals(UiType.UNKNOWN, mapControlType(50025)) // Custom
        assertEquals(UiType.UNKNOWN, mapControlType(-1))
    }

    @Test
    fun deterministicRankingHandlesContainmentAndPartialIntersection() {
        val selected = Rectangle(10, 10, 20, 20)
        val partial = candidate("partial", Rectangle(28, 10, 20, 20))
        val containing = candidate("container", Rectangle(0, 0, 100, 100))
        val insideLarge = candidate("inside-large", Rectangle(12, 12, 10, 10))
        val insideSmall = candidate("inside-small", Rectangle(15, 15, 4, 4))

        assertEquals(listOf(insideSmall, insideLarge, containing, partial), rankUiAutomationCandidates(
            listOf(partial, containing, insideLarge, insideSmall), selected
        ))
    }

    @Test
    fun emptyCandidatesAndMissingBoundsDoNotProduceAnElement() = runBlocking {
        val engine = WindowsUiAutomationPerceptionEngine(UiAutomationClient { listOf(candidate("no-bounds", null)) }, { true }, { true })
        val result = engine.perceive(request())

        assertEquals(UiType.UNKNOWN, result.uiType)
        assertNull(result.selectedObject)
        assertEquals("no_intersecting_element", result.metadata["status"])
        assertEquals("Demo", result.applicationName)

        val emptyResult = WindowsUiAutomationPerceptionEngine(UiAutomationClient { emptyList() }, { true }, { true }).perceive(request())
        assertEquals("no_intersecting_element", emptyResult.metadata["status"])
    }

    @Test
    fun resultUsesOnlyUiaEvidenceAndPropagatesApplication() = runBlocking {
        val engine = WindowsUiAutomationPerceptionEngine(UiAutomationClient { hwnd ->
            assertEquals(1234L, hwnd)
            listOf(UiAutomationCandidate("Accessible name", "save-id", 50000, Rectangle(5, 5, 30, 30), true, false))
        }, { true }, { true })
        val result = engine.perceive(request())

        assertEquals("Demo", result.applicationName)
        assertEquals("Accessible name", result.selectedObject)
        assertNull(result.visibleText)
        assertEquals(UiType.BUTTON, result.uiType)
        assertEquals(Rectangle(5, 5, 30, 30), result.boundingRectangle)
        assertEquals(1.0f, result.confidence)
        assertEquals(PerceptionSource.UI_AUTOMATION, result.source)
        assertEquals("save-id", result.metadata["automationId"])
    }

    @Test
    fun missingNameIsAllowedAndValuePatternIsTheOnlyVisibleTextSource() = runBlocking {
        val engine = WindowsUiAutomationPerceptionEngine(UiAutomationClient {
            listOf(UiAutomationCandidate(null, null, 50004, Rectangle(10, 10, 20, 20), true, false, "actual value"))
        }, { true }, { true })
        val result = engine.perceive(request())

        assertNull(result.selectedObject)
        assertEquals("actual value", result.visibleText)
    }

    @Test
    fun missingHwndReturnsExplicitEmptyResultWithoutCallingWindows() = runBlocking {
        val engine = WindowsUiAutomationPerceptionEngine(UiAutomationClient { error("must not call") }, { true }, { true })
        val result = engine.perceive(request(app.copy(windowHandle = null)))

        assertEquals("missing_window_handle", result.metadata["status"])
        assertEquals("Demo", result.applicationName)
    }

    @Test
    fun unsupportedDpiMappingDoesNotComparePhysicalAndUserCoordinates() = runBlocking {
        val engine = WindowsUiAutomationPerceptionEngine(UiAutomationClient { error("must not call") }, { true }, { false })
        val result = engine.perceive(request())

        assertEquals("coordinate_mapping_unsupported", result.metadata["status"])
        assertEquals("Demo", result.applicationName)
    }

    private fun request(application: ApplicationContext = app) = PerceptionRequest(
        BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB), Rectangle(10, 10, 20, 20), application
    )

    private fun candidate(name: String?, bounds: Rectangle?) = UiAutomationCandidate(name, null, 50000, bounds, true, false)
}
