package context

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import selection.SelectionRect
import selection.clampSelectionRect
import selection.isSelectionLargeEnough
import selection.normalizeSelectionRect

class SelectionGeometryTest {
    @Test fun allDragDirectionsNormalize() {
        val expected = SelectionRect(10, 20, 30, 40)
        listOf(normalizeSelectionRect(10,20,40,60), normalizeSelectionRect(40,20,10,60), normalizeSelectionRect(10,60,40,20), normalizeSelectionRect(40,60,10,20)).forEach { assertEquals(expected,it) }
    }
    @Test fun clampAndMinimum() {
        assertEquals(SelectionRect(-5,0,15,20), clampSelectionRect(SelectionRect(-10,-5,20,25), Rectangle(-5,0,100,100)))
        assertFalse(isSelectionLargeEnough(SelectionRect(0,0,8,20)))
        assertFalse(isSelectionLargeEnough(SelectionRect(0,0,9,8)))
        assertTrue(isSelectionLargeEnough(SelectionRect(0,0,9,9)))
    }
}
