package context

import bridge.WgcScreenCaptureService
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import selection.FrozenMonitorSnapshot
import selection.FrozenScreenSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextProcessingTest {
    private fun snapshot(): FrozenScreenSnapshot {
        val bounds=Rectangle(-20,0,30,30)
        val image=BufferedImage(30,30,BufferedImage.TYPE_INT_ARGB)
        return FrozenScreenSnapshot(image,bounds,1.0,1.0,listOf(FrozenMonitorSnapshot(bounds,image,1.0,1.0)))
    }

    @Test fun slowOcrRunsOffCallerAndStateStaysProcessing() = runBlocking {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<OcrResult>()
        val ocrThread=AtomicLong(-1)
        val logger=RecordingContextLogger()
        val processor=ContextProcessor(OCRService { ocrThread.set(Thread.currentThread().id); entered.complete(Unit); release.await() }, ContentClassifier { _,_->ClassificationResult(ContentType.UNKNOWN,.5f) }, logger)
        val controller=RegionSelectionController(logger)
        val flow=VisualContextAcquisition(controller,ApplicationContextProvider { null },WgcScreenCaptureService(logger),processor)
        val session=assertNotNull(flow.begin()); val callerThread=Thread.currentThread().id
        val job=launch { flow.process(session,snapshot(),Rectangle(-20,0,20,20)) }
        entered.await()
        assertTrue(job.isActive)
        assertTrue(controller.state.value is SelectionState.Processing)
        release.complete(OcrResult("visible words",.9f,engineName="test"))
        job.join()
        assertTrue(ocrThread.get()!=callerThread)
        assertTrue(controller.state.value is SelectionState.Completed)
        assertEquals(listOf(LifecycleEvent.SELECTION_STARTED,LifecycleEvent.PREVIOUS_FOREGROUND_WINDOW_CAPTURED,LifecycleEvent.SELECTION_COMPLETED,LifecycleEvent.CAPTURE_STARTED,LifecycleEvent.CAPTURE_COMPLETED,LifecycleEvent.PROCESSING_STARTED,LifecycleEvent.CONTEXT_CREATED),logger.entries.map { it.event })
        assertEquals(callerThread, Thread.currentThread().id)
        flow.reset(session)
    }

    @Test fun ocrClassifierAndApplicationContextFailuresDegrade() = runBlocking {
        val processor=ContextProcessor(OCRService { error("ocr unavailable") },ContentClassifier { _,_->error("classifier unavailable") },RecordingContextLogger())
        val controller=RegionSelectionController(RecordingContextLogger())
        val acquisition=VisualContextAcquisition(controller,ApplicationContextProvider { error("foreground unavailable") },WgcScreenCaptureService(RecordingContextLogger()),processor)
        val session=assertNotNull(acquisition.begin())
        assertNull(session.applicationContext)
        val result=acquisition.process(session,snapshot(),Rectangle(-20,0,20,20))
        assertNull(result.visualContext.extractedText)
        assertNull(result.visualContext.contentType)
        assertEquals("true",result.visualContext.metadata["ocrFailure"])
        assertEquals("true",result.visualContext.metadata["classificationFailure"])
        assertNotNull(result.visualContext.metadata["processingMillis"])
        acquisition.reset(session)
    }

    @Test fun contextIdsAreUniqueAndDefaultLoggerDropsSensitiveValues() = runBlocking {
        val processor=ContextProcessor(logger=RecordingContextLogger())
        val image=BufferedImage(10,10,BufferedImage.TYPE_INT_ARGB)
        val one=processor.process(image,-1,0,null)
        val two=processor.process(image,-1,0,null)
        assertFalse(one.regionId==two.regionId)
        val previous=System.err
        val bytes=ByteArrayOutputStream()
        try {
            System.setErr(PrintStream(bytes))
            StderrContextLogger().event(LifecycleEvent.CONTEXT_CREATED,mapOf("ocrText" to "PRIVATE OCR", "windowTitle" to "Private title", "image" to image, "ocrCharacters" to 42))
        } finally { System.setErr(previous) }
        val log=bytes.toString()
        assertTrue("42" in log)
        assertFalse("PRIVATE OCR" in log)
        assertFalse("Private title" in log)
    }
}
