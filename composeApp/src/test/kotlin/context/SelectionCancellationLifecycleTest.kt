package context

import bridge.CaptureResult
import bridge.ScreenCaptureService
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import selection.FrozenScreenSnapshot
import models.HighlightRegion

class SelectionCancellationLifecycleTest {
    private class FakeFocusManager : WindowFocusManager {
        val calls = mutableListOf<String>()
        val previous = PreviousForegroundWindow(1234, 77, true, false, true, -100, 10, 900, 700, false)
        override suspend fun capture(): PreviousForegroundWindow { calls += "capture"; return previous }
        override suspend fun restore(previous: PreviousForegroundWindow?): Boolean { calls += "restore"; return previous == this.previous }
    }

    private class CountingCapture : ScreenCaptureService {
        var regionCalls = 0
        override suspend fun capturePreviousWindow() = CaptureResult.Failure("unused")
        override suspend fun captureRegion(region: HighlightRegion) = CaptureResult.Failure("unused")
        override suspend fun captureFrozenRegion(snapshot: FrozenScreenSnapshot, desktopBounds: Rectangle, regionId: UUID): BufferedImage {
            regionCalls++
            return BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        }
    }

    @Test fun doubleAndRepeatedCancellationRestoreCapturedForegroundWithoutProcessing() = runBlocking {
        val focus = FakeFocusManager()
        val capture = CountingCapture()
        var ocrCalls = 0
        val processor = ContextProcessor(OCRService { ocrCalls++; OcrResult("unexpected", .9f, engineName = "test") })
        val controller = RegionSelectionController(RecordingContextLogger())
        val acquisition = VisualContextAcquisition(controller, ApplicationContextProvider { null }, capture, processor, focus)
        val snapshot = FrozenScreenSnapshot(
            BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB), Rectangle(-10, 0, 20, 20), 1.0, 1.0
        )

        repeat(3) {
            val session = assertNotNull(acquisition.begin())
            assertEquals(SelectionState.Selecting(session.regionId), controller.state.value)
            acquisition.cancel(session)
            acquisition.cancel(session) // a second ESC/close callback is harmless
            assertEquals(SelectionState.Idle, controller.state.value)
            assertTrue(acquisition.restorePreviousFocus(session))
        }

        val last = assertNotNull(acquisition.begin())
        acquisition.cancel(last)
        assertFailsWith<IllegalStateException> { acquisition.process(last, snapshot, Rectangle(-10, 0, 12, 12)) }
        assertEquals(0, capture.regionCalls)
        assertEquals(0, ocrCalls)
        assertEquals(listOf("capture", "restore", "capture", "restore", "capture", "restore", "capture"), focus.calls)
        snapshot.flush()
    }

    @Test fun foregroundStateIsCapturedBeforeApplicationContextAndCanBeRestored() = runBlocking {
        val order = mutableListOf<String>()
        val focus = object : WindowFocusManager {
            override suspend fun capture(): PreviousForegroundWindow? { order += "foreground"; return null }
            override suspend fun restore(previous: PreviousForegroundWindow?) = false
        }
        val acquisition = VisualContextAcquisition(
            RegionSelectionController(RecordingContextLogger()),
            ApplicationContextProvider { order += "application"; null },
            CountingCapture(), ContextProcessor(), focus
        )
        val session = assertNotNull(acquisition.begin())
        assertEquals(listOf("foreground", "application"), order)
        acquisition.cancel(session)
    }
}
