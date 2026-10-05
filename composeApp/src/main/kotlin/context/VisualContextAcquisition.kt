package context

import bridge.ScreenCaptureService
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import selection.FrozenScreenSnapshot
import java.util.UUID

data class SelectionSession(
    val regionId: UUID,
    val applicationContext: ApplicationContext?,
    val applicationContextMillis: Long,
    val previousForegroundWindow: PreviousForegroundWindow? = null
)
data class CompletedVisualCapture(val image: BufferedImage, val visualContext: VisualContext)
class SelectionTooSmallException(message: String) : IllegalArgumentException(message)

/** Orchestrates domain/capture services; the Compose window only supplies screen bounds and selection. */
class VisualContextAcquisition(
    val controller: RegionSelectionController,
    private val appContextProvider: ApplicationContextProvider,
    private val capture: ScreenCaptureService,
    private val processor: ContextProcessor,
    private val windowFocusManager: WindowFocusManager = NoopWindowFocusManager
) {
    suspend fun begin(): SelectionSession? {
        val id = controller.begin() ?: return null
        val previousWindow = try { windowFocusManager.capture() } catch (_: Exception) { null }
        controller.log(LifecycleEvent.PREVIOUS_FOREGROUND_WINDOW_CAPTURED, mapOf("regionId" to id, "state" to if (previousWindow == null) "unavailable" else "captured"))
        val appStarted = System.nanoTime()
        val app = try { appContextProvider.current() } catch (e: kotlinx.coroutines.CancellationException) { controller.fail(id); throw e } catch (_: Exception) { null }
        return SelectionSession(id, app, (System.nanoTime() - appStarted) / 1_000_000, previousWindow)
    }

    suspend fun process(session: SelectionSession, snapshot: FrozenScreenSnapshot, bounds: Rectangle): CompletedVisualCapture {
        check(controller.processing(session.regionId)) { "Selection session is no longer active." }
        try {
            val captureStarted = System.nanoTime()
            controller.log(LifecycleEvent.CAPTURE_STARTED, mapOf("regionId" to session.regionId, "state" to "CAPTURING"))
            val image = withContext(Dispatchers.IO) { capture.captureFrozenRegion(snapshot, bounds, session.regionId) }
            val metadata = mapOf(
                "captureSourceMode" to "FROZEN_SNAPSHOT",
                "captureMillis" to ((System.nanoTime() - captureStarted) / 1_000_000).toString(),
                "applicationContextMillis" to session.applicationContextMillis.toString(),
                "monitorCount" to snapshot.monitors.count { !it.desktopBounds.intersection(bounds).isEmpty }.toString(),
                "scaleFactor" to snapshot.monitors.filter { !it.desktopBounds.intersection(bounds).isEmpty }.maxOf { maxOf(it.scaleX, it.scaleY) }.toString()
            )
            val visual = processor.process(image, bounds.x, bounds.y, session.applicationContext, session.regionId, metadata)
            check(controller.complete(session.regionId, visual)) { "Selection session ended while processing." }
            return CompletedVisualCapture(image, visual)
        } catch (e: SelectionTooSmallException) {
            controller.cancel(session.regionId)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            controller.fail(session.regionId)
            throw e
        } catch (e: Exception) {
            controller.fail(session.regionId)
            throw e
        }
    }

    fun cancel(session: SelectionSession) {
        controller.cancel(session.regionId)
        controller.reset(session.regionId)
    }

    fun reset(session: SelectionSession) = controller.reset(session.regionId)
    fun fail(session: SelectionSession) = controller.fail(session.regionId)

    suspend fun restorePreviousFocus(session: SelectionSession): Boolean = windowFocusManager.restore(session.previousForegroundWindow)
}
