package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionSelectionControllerTest {
    @Test fun rejectsDuplicateStartAndWalksTheLegalStates() {
        val log = RecordingContextLogger()
        val controller = RegionSelectionController(log)
        val id = assertNotNull(controller.begin())
        assertNull(controller.begin())
        assertEquals(SelectionState.Selecting(id), controller.state.value)
        assertTrue(controller.processing(id))
        assertEquals(SelectionState.Processing(id), controller.state.value)
        val image = BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB)
        val context = VisualContext(id, image, -12, 0, 12, 12, 1L, null, ContentType.UNKNOWN, null, null, emptyMap())
        assertTrue(controller.complete(id, context))
        assertEquals(SelectionState.Completed(context), controller.state.value)
        controller.reset(id)
        assertEquals(SelectionState.Idle, controller.state.value)
        assertEquals(listOf(LifecycleEvent.SELECTION_STARTED, LifecycleEvent.SELECTION_COMPLETED), log.entries.map { it.event })
    }

    @Test fun cancelAndFailureReturnToIdleAndRejectStaleCallbacks() {
        val log = RecordingContextLogger()
        val controller = RegionSelectionController(log)
        val id = assertNotNull(controller.begin())
        assertTrue(controller.cancel(id))
        assertEquals(SelectionState.Cancelled(id), controller.state.value)
        controller.reset(id)
        val next = assertNotNull(controller.begin())
        assertTrue(controller.processing(next))
        controller.fail(next)
        assertEquals(SelectionState.Idle, controller.state.value)
        assertFalse(controller.complete(id, VisualContext(UUID.randomUUID(), BufferedImage(1,1,2),0,0,1,1,0,null,null,null,null, emptyMap())))
        assertEquals(listOf(LifecycleEvent.SELECTION_STARTED, LifecycleEvent.SELECTION_CANCELLED, LifecycleEvent.SELECTION_STARTED, LifecycleEvent.SELECTION_COMPLETED, LifecycleEvent.PROCESSING_FAILED), log.entries.map { it.event })
    }
}
