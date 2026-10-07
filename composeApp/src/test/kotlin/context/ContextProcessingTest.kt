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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ContextProcessingTest {
    private fun snapshot(): FrozenScreenSnapshot {
        val bounds=Rectangle(-20,0,30,30)
        val image=BufferedImage(30,30,BufferedImage.TYPE_INT_ARGB)
        return FrozenScreenSnapshot(image,bounds,1.0,1.0,listOf(FrozenMonitorSnapshot(bounds,image,1.0,1.0)))
    }

    @Test fun perceptionRequestUsesActualAcquisitionInputsAndAttachesUiaResult() = runBlocking {
        val logger = RecordingContextLogger()
        val app = ApplicationContext("Editor", "editor.exe", pid = 17, windowHandle = 12345L)
        val selected = Rectangle(-18, 2, 19, 18)
        val ocrEvidence = OcrResult("recognized text", .8f, listOf(OcrWord("recognized", .8f, Rectangle(2, 3, 30, 10))), "test-ocr")
        val requests = mutableListOf<PerceptionRequest>()
        val perception = PerceptionResult("Editor", "Save", null, UiType.BUTTON, Rectangle(-17, 3, 8, 6), 1f, PerceptionSource.UI_AUTOMATION)
        val processor = ContextProcessor(
            ocr = OCRService { ocrEvidence },
            classifier = ContentClassifier { _, _ -> ClassificationResult(ContentType.TEXT, .7f) },
            logger = logger,
            perceptionEngine = PerceptionEngine { request -> requests += request; perception }
        )
        val acquisition = VisualContextAcquisition(
            RegionSelectionController(logger), ApplicationContextProvider { app },
            WgcScreenCaptureService(logger), processor
        )
        val session = assertNotNull(acquisition.begin())
        val targetDescription = "Select Save button"
        val captured = acquisition.process(session, snapshot(), selected, targetDescription)
        val request = requests.single()

        assertSame(captured.image, request.screenshot)
        assertSame(selected, request.selectedRegion)
        assertSame(app, request.applicationContext)
        assertEquals(targetDescription, request.targetDescription)
        assertEquals(12345L, request.applicationContext?.windowHandle)
        assertSame(perception, captured.visualContext.perceptionResult)
        assertSame(ocrEvidence, captured.visualContext.ocrResult)
        assertEquals(targetDescription, captured.visualContext.evidenceEvaluation?.targetDescription)
        assertEquals(OcrCoordinateSpace.CROP_IMAGE_PIXELS, captured.visualContext.ocrResult?.coordinateSpace)
        assertEquals("recognized text", captured.visualContext.extractedText)
        assertEquals(ContentType.TEXT, captured.visualContext.contentType)
        assertTrue(captured.visualContext.metadata.containsKey("perceptionMillis"))
        assertTrue(acquisition.controller.state.value is SelectionState.Completed)
        acquisition.reset(session)
    }

    @Test fun ocrAndUiaEvidenceCoexistAndProviderMetadataIsRetained() = runBlocking {
        val ocr = OcrResult("Save", .95f, listOf(OcrWord("Save", .95f, Rectangle(8, 9, 40, 18))), "fixture-ocr", metadata = mapOf("providerId" to "fixture", "totalLatencyMillis" to "3"))
        val uia = PerceptionResult("Editor", "Save", null, UiType.BUTTON, Rectangle(10, 11, 80, 30), .9f, PerceptionSource.UI_AUTOMATION)
        val result = ContextProcessor(ocr = OCRService { ocr }, perceptionEngine = PerceptionEngine { uia }, logger = RecordingContextLogger())
            .process(BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB), 0, 0, ApplicationContext("Editor", "editor.exe"))

        assertSame(ocr, result.ocrResult)
        assertSame(uia, result.perceptionResult)
        assertEquals("Save", result.extractedText)
        assertEquals("fixture", result.metadata["ocrProviderId"])
        assertEquals("3", result.metadata["ocrTotalLatencyMillis"])
    }

    @Test fun explicitEmptyPerceptionResultStillCompletesVisualContext() = runBlocking {
        val logger = RecordingContextLogger()
        val empty = PerceptionResult("Unknown app", null, null, UiType.UNKNOWN, null, null, PerceptionSource.UI_AUTOMATION, mapOf("status" to "missing_window_handle"))
        val processor = ContextProcessor(logger = logger, perceptionEngine = PerceptionEngine { empty })
        val acquisition = VisualContextAcquisition(
            RegionSelectionController(logger), ApplicationContextProvider { null },
            WgcScreenCaptureService(logger), processor
        )
        val session = assertNotNull(acquisition.begin())
        val completed = acquisition.process(session, snapshot(), Rectangle(-20, 0, 20, 20))

        assertSame(empty, completed.visualContext.perceptionResult)
        assertTrue(acquisition.controller.state.value is SelectionState.Completed)
        acquisition.reset(session)
    }

    @Test fun unexpectedPerceptionFailureDegradesAndKeepsOcrAndClassification() = runBlocking {
        val logger = RecordingContextLogger()
        val processor = ContextProcessor(
            ocr = OCRService { OcrResult("kept OCR", .91f, engineName = "test-ocr") },
            classifier = ContentClassifier { _, _ -> ClassificationResult(ContentType.TABLE, .73f) },
            logger = logger,
            perceptionEngine = PerceptionEngine { error("uia adapter failed") }
        )
        val context = processor.process(BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB), 0, 0, null)

        assertEquals("kept OCR", context.extractedText)
        assertEquals(ContentType.TABLE, context.contentType)
        assertEquals(true, context.metadata["perceptionFailure"]?.toBoolean())
        assertEquals("provider_failure", context.perceptionResult?.metadata?.get("status"))
        assertEquals(UiType.UNKNOWN, context.perceptionResult?.uiType)
    }

    @Test fun perceptionCancellationPropagatesAndProcessingReturnsToIdle() = runBlocking {
        val logger = RecordingContextLogger()
        val processor = ContextProcessor(
            logger = logger,
            perceptionEngine = PerceptionEngine { throw kotlinx.coroutines.CancellationException("cancelled") }
        )
        val controller = RegionSelectionController(logger)
        val acquisition = VisualContextAcquisition(
            controller, ApplicationContextProvider { null }, WgcScreenCaptureService(logger), processor
        )
        val session = assertNotNull(acquisition.begin())

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            acquisition.process(session, snapshot(), Rectangle(-20, 0, 20, 20))
        }
        assertEquals(SelectionState.Idle, controller.state.value)
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
