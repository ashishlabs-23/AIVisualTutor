package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CalibrationResultPersistenceTest {
    private val image = BufferedImage(24, 18, BufferedImage.TYPE_INT_ARGB)
    private val fixedClock = Clock.fixed(Instant.parse("2026-10-08T08:00:00Z"), ZoneOffset.UTC)

    @Test
    fun configuredCalibrationCapturePersistsByDefaultWithPendingResearchLabel() = runBlocking {
        val root = Files.createTempDirectory("calibration-default-persistence")
        val previousEnabled = System.getProperty(CalibrationResultStore.ENABLE_PROPERTY)
        val previousRoot = System.getProperty(CalibrationResultStore.OUTPUT_PROPERTY)
        try {
            System.clearProperty(CalibrationResultStore.ENABLE_PROPERTY)
            System.setProperty(CalibrationResultStore.OUTPUT_PROPERTY, root.toString())
            val processor = ContextProcessor(
                ocr = OCRService { OcrResult("", null, engineName = "test-ocr") },
                perceptionEngine = PerceptionEngine {
                    PerceptionResult(
                        applicationName = null,
                        selectedObject = null,
                        visibleText = null,
                        uiType = UiType.UNKNOWN,
                        boundingRectangle = null,
                        confidence = null,
                        source = PerceptionSource.UI_AUTOMATION,
                        metadata = mapOf("status" to "missing_window_handle")
                    )
                },
                logger = RecordingContextLogger()
            )

            processor.process(
                image = image,
                x = 0,
                y = 0,
                app = null,
                targetDescription = "Find the target",
                calibrationAttempt = attempt(target = "Find the target")
            )

            val record = Files.readString(runDirectories(root).single().resolve("result.json"))
            assertTrue("\"caseId\":\"TEST_CASE\"" in record)
            assertTrue("\"timestamp\":\"2026-10-08T08:00:00Z\"" in record)
            assertTrue("\"application\":\"NOT_RECORDED\"" in record)
            assertTrue("\"targetDescription\":\"Find the target\"" in record)
            assertTrue("\"groundTruthLabel\":\"PENDING\"" in record)
            assertTrue("\"controlType\":\"UNKNOWN\"" in record)
        } finally {
            if (previousEnabled == null) System.clearProperty(CalibrationResultStore.ENABLE_PROPERTY)
            else System.setProperty(CalibrationResultStore.ENABLE_PROPERTY, previousEnabled)
            if (previousRoot == null) System.clearProperty(CalibrationResultStore.OUTPUT_PROPERTY)
            else System.setProperty(CalibrationResultStore.OUTPUT_PROPERTY, previousRoot)
        }
    }

    @Test
    fun completedProcessingWritesUniqueBoundCropRecordAndIncreasingManifestSequence() = runBlocking {
        val root = Files.createTempDirectory("calibration-records")
        val store = CalibrationResultStore(root, fixedClock)
        val processor = processor(store)
        val attempt = attempt(target = "  Select Save button  ")

        processor.process(image, 2, 3, null, selectedRegion = Rectangle(2, 3, 24, 18), targetDescription = attempt.targetDescription, calibrationAttempt = attempt)
        processor.process(image, 2, 3, null, selectedRegion = Rectangle(2, 3, 24, 18), targetDescription = attempt.targetDescription, calibrationAttempt = attempt)

        val runs = runDirectories(root)
        assertEquals(2, runs.size)
        assertTrue(runs.all { Files.isRegularFile(it.resolve("crop.png")) && Files.isRegularFile(it.resolve("result.json")) })
        val records = runs.map { Files.readString(it.resolve("result.json")) }
        assertTrue(records.all { "\"targetDescription\":\"  Select Save button  \"" in it })
        assertTrue(records.all { "\"groundTruthLabel\":\"PENDING\"" in it })
        val manifest = Files.readAllLines(root.resolve(CalibrationResultStore.MANIFEST))
        assertEquals(2, manifest.size)
        assertTrue(manifest[0].contains("\"sequence\":1"))
        assertTrue(manifest[1].contains("\"sequence\":2"))
        assertTrue(manifest[0].contains("\"runId\"") && manifest[1].contains("\"runId\""))
        val hash = Regex("\"sha256\":\"([a-f0-9]{64})\"").find(records.first())!!.groupValues[1]
        assertEquals(hash, sha256(Files.readAllBytes(runs.first().resolve("crop.png"))))
        assertTrue(CalibrationResultStore.isoUtc("2026-10-08T08:00:00Z"))
        assertTrue("\"processedAtUtc\":\"2026-10-08T08:00:00Z\"" in records.first())

        Files.delete(root.resolve(CalibrationResultStore.MANIFEST))
        processor.process(image, 2, 3, null, selectedRegion = Rectangle(2, 3, 24, 18), targetDescription = attempt.targetDescription, calibrationAttempt = attempt)
        val rebuilt = Files.readAllLines(root.resolve(CalibrationResultStore.MANIFEST))
        assertEquals(3, rebuilt.size)
        assertTrue(rebuilt.mapIndexed { index, row -> row.contains("\"sequence\":${index + 1}") }.all { it })
    }

    @Test
    fun providerFailuresAbstentionCoordinatesAndLatencyArePersistedWithoutGroundTruth() = runBlocking {
        val root = Files.createTempDirectory("calibration-failure-records")
        val store = CalibrationResultStore(root, fixedClock)
        val failingUia = processor(store, uia = PerceptionEngine { error("uia test failure") })
        val result = failingUia.process(
            image, 0, 0, null, selectedRegion = Rectangle(0, 0, image.width, image.height),
            targetDescription = "Target", calibrationAttempt = attempt(target = "Target")
        )
        assertEquals(EvidenceDecision.ABSTAIN, result.evidenceEvaluation?.decision)
        val record = Files.readString(runDirectories(root).single().resolve("result.json"))
        assertTrue("\"decision\":\"ABSTAIN\"" in record)
        assertTrue("\"status\":\"FAILURE\"" in record)
        assertTrue("\"coordinateSpace\":\"CROP_IMAGE_PIXELS\"" in record)
        assertTrue("\"latencyMs\":" in record)
        assertTrue("\"geometryComparisonStatus\":\"NOT_COMPUTABLE\"" in record)
        assertTrue("\"providerInvocationStatus\":\"NOT_CONFIGURED\"" in record)
        assertTrue("\"visualGrounding\":{\"status\":\"NOT_CONFIGURED\"" in record)
        assertTrue("\"visualCoordinateSpace\":\"NOT_RECORDED\"" in record)
        assertTrue("\"schemaVersion\":3" in record)
        assertTrue("\"groundTruthLabel\":\"PENDING\"" in record)

        val unavailableRoot = Files.createTempDirectory("calibration-unavailable-records")
        val unavailable = ContextProcessor(
            ocr = OCRService { OcrResult("", null, engineName = "test") },
            perceptionEngine = uiaWithStatus("not_run"),
            evidenceEvaluationCoordinator = EvidenceEvaluationCoordinator(UnavailableVisualGroundingProvider()),
            calibrationResultSink = CalibrationResultStore(unavailableRoot, fixedClock)
        )
        unavailable.process(
            image, 0, 0, null, targetDescription = "Target",
            calibrationAttempt = attempt(target = "Target")
        )
        assertTrue(Files.readString(runDirectories(unavailableRoot).single().resolve("result.json"))
            .contains("\"providerInvocationStatus\":\"UNAVAILABLE\""))
    }

    @Test
    fun visualGroundingGeometryAndLatencyArePersistedSeparately() = runBlocking {
        val root = Files.createTempDirectory("calibration-visual-records")
        val visualProvider = VisualGroundingProvider {
            VisualGroundingResult(
                semantic = null,
                geometry = GroundingGeometricEvidence(
                    point = GroundingPoint(.25, .75),
                    coordinateSpace = VisualGroundingCoordinateSpace.NORMALIZED_CROP
                ),
                providerId = "test-uground",
                availability = GroundingProviderAvailability.AVAILABLE,
                executionLocation = GroundingExecutionLocation.LOCAL,
                latencyMillis = 17,
                metadata = mapOf("rawModelOutput" to "(250, 750)")
            )
        }
        val processor = ContextProcessor(
            ocr = OCRService { OcrResult("", null, engineName = "test-ocr") },
            perceptionEngine = uiaWithStatus("missing_window_handle"),
            evidenceEvaluationCoordinator = EvidenceEvaluationCoordinator(visualProvider),
            calibrationResultSink = CalibrationResultStore(root, fixedClock)
        )

        processor.process(
            image, 0, 0, null, targetDescription = "Find the target",
            calibrationAttempt = attempt(target = "Find the target")
        )

        val record = Files.readString(runDirectories(root).single().resolve("result.json"))
        assertTrue("\"visualGrounding\":{\"status\":\"AVAILABLE\"" in record)
        assertTrue("\"coordinateSpace\":\"NORMALIZED_CROP\"" in record)
        assertTrue("\"point\":{\"x\":0.25, \"y\":0.75}" in record)
        assertTrue("\"latencyMs\":17" in record)
        assertTrue("\"rawModelOutput\":\"(250, 750)\"" in record)
        assertTrue("\"groundTruthLabel\":\"PENDING\"" in record)
    }

    @Test
    fun cancelledVisualProviderIsPersistedAsCancelledAndRethrown() {
        val root = Files.createTempDirectory("calibration-visual-cancel")
        val provider = VisualGroundingProvider {
            throw CancellationException("visual provider cancelled")
        }
        val processor = ContextProcessor(
            ocr = OCRService { OcrResult("", null, engineName = "test-ocr") },
            perceptionEngine = uiaWithStatus("missing_window_handle"),
            evidenceEvaluationCoordinator = EvidenceEvaluationCoordinator(provider),
            calibrationResultSink = CalibrationResultStore(root, fixedClock)
        )

        assertFailsWith<CancellationException> {
            runBlocking {
                processor.process(
                    image, 0, 0, null, targetDescription = "Find the target",
                    calibrationAttempt = attempt(target = "Find the target")
                )
            }
        }

        val record = Files.readString(runDirectories(root).single().resolve("result.json"))
        assertTrue("\"visualGrounding\":{\"status\":\"CANCELLED\"" in record)
        assertTrue("\"providerInvocationStatus\":\"CANCELLED\"" in record)
        assertTrue("\"processingStatus\":\"CANCELLED\"" in record)
    }

    @Test
    fun ocrFailureAndUnexpectedProcessingFailureStillProduceRecords() = runBlocking {
        val root = Files.createTempDirectory("calibration-exception-records")
        val store = CalibrationResultStore(root, fixedClock)
        val processor = processor(store, ocr = OCRService { error("ocr test failure") })
        processor.process(image, 0, 0, null, calibrationAttempt = attempt())
        val ocrRecord = Files.readString(runDirectories(root).single().resolve("result.json"))
        assertTrue("\"status\":\"FAILURE\"" in ocrRecord)

        val unexpectedRoot = Files.createTempDirectory("calibration-unexpected-records")
        val logger = ContextLogger { _, _ -> error("unexpected processor failure") }
        val unexpected = ContextProcessor(logger = logger, calibrationResultSink = CalibrationResultStore(unexpectedRoot, fixedClock))
        assertFailsWith<IllegalStateException> {
            runBlocking { unexpected.process(image, 0, 0, null, calibrationAttempt = attempt()) }
        }
        val failed = Files.readString(runDirectories(unexpectedRoot).single().resolve("result.json"))
        assertTrue("\"processingStatus\":\"FAILURE\"" in failed)
        assertTrue("\"failureType\":\"IllegalStateException\"" in failed)
    }

    @Test
    fun evaluatorFailureRecordRetainsProvidersAlreadyExecuted() = runBlocking {
        val root = Files.createTempDirectory("calibration-evaluator-failure")
        val provider = object : VisualGroundingProvider {
            override val availability: GroundingProviderAvailability
                get() = error("provider state failure")
            override suspend fun ground(request: VisualGroundingRequest) = error("not reached")
        }
        val processor = ContextProcessor(
            ocr = OCRService {
                OcrResult("Save", .91f, listOf(OcrWord("Save", .91f, Rectangle(1, 1, 8, 5))), "test-ocr")
            },
            perceptionEngine = PerceptionEngine {
                PerceptionResult(
                    "Test", "Save", null, UiType.BUTTON, Rectangle(1, 1, 8, 5),
                    1f, PerceptionSource.UI_AUTOMATION
                )
            },
            logger = RecordingContextLogger(),
            evidenceEvaluationCoordinator = EvidenceEvaluationCoordinator(provider),
            calibrationResultSink = CalibrationResultStore(root, fixedClock)
        )
        assertFailsWith<IllegalStateException> {
            runBlocking { processor.process(image, 0, 0, null, targetDescription = "Select Save button", calibrationAttempt = attempt(target = "Select Save button")) }
        }
        val record = Files.readString(runDirectories(root).single().resolve("result.json"))
        assertTrue("\"processingStatus\":\"FAILURE\"" in record)
        assertTrue("\"providers\":{\"UIA\":{\"status\":\"AVAILABLE\"" in record)
        assertTrue("\"OCR\":{\"status\":\"AVAILABLE\"" in record)
        assertTrue("\"decision\":\"NOT_RECORDED\"" in record)
    }

    @Test
    fun cancellationWritesCancelledRecordThenRethrows() {
        val root = Files.createTempDirectory("calibration-cancel-records")
        val processor = processor(
            CalibrationResultStore(root, fixedClock),
            ocr = OCRService { throw CancellationException("test cancellation") }
        )
        assertFailsWith<CancellationException> {
            runBlocking { processor.process(image, 0, 0, null, calibrationAttempt = attempt()) }
        }
        val record = Files.readString(runDirectories(root).single().resolve("result.json"))
        assertTrue("\"processingStatus\":\"CANCELLED\"" in record)
    }

    @Test
    fun failedAtomicResultWriteLeavesNoPartialResultAndDoesNotFailProcessing() = runBlocking {
        val root = Files.createTempDirectory("calibration-atomic-failure")
        val store = CalibrationResultStore(root, fixedClock) { error("injected write failure") }
        val result = processor(store).process(image, 0, 0, null, calibrationAttempt = attempt())
        assertNotNull(result)
        val run = runDirectories(root).single()
        assertTrue(Files.isRegularFile(run.resolve("crop.png")))
        assertFalse(Files.exists(run.resolve("result.json")))
        assertFalse(Files.list(run).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test
    fun cropReplayPreservesAndHashesTheExactSourcePngBytes() {
        val root = Files.createTempDirectory("calibration-source-crop")
        val source = root.resolve("source.png")
        assertTrue(javax.imageio.ImageIO.write(image, "png", source.toFile()))
        val originalBytes = Files.readAllBytes(source)
        val storeRoot = Files.createTempDirectory("calibration-source-crop-output")
        val attempt = attempt(kind = CalibrationRunKind.CROP_REPLAY, capturedAt = null).copy(sourceCropPath = source)
        CalibrationResultStore(storeRoot, fixedClock).persist(runResult().copy(attempt = attempt))

        val run = runDirectories(storeRoot).single()
        assertTrue(originalBytes.contentEquals(Files.readAllBytes(run.resolve("crop.png"))))
        assertEquals(sha256(originalBytes), Regex("\"sha256\":\"([a-f0-9]{64})\"")
            .find(Files.readString(run.resolve("result.json")))!!.groupValues[1])
    }

    @Test
    fun disabledSinkAndLegacyImportDoNotInventCaptureFields() {
        val root = Files.createTempDirectory("calibration-disabled")
        NoOpCalibrationResultSink.persist(runResult())
        assertEquals(0L, Files.list(root).use { it.count() })

        val legacyRoot = Files.createTempDirectory("calibration-legacy")
        val crop = legacyRoot.resolve("C01_crop.png")
        val bytes = javax.imageio.ImageIO.write(image, "png", crop.toFile())
        assertTrue(bytes)
        val storeRoot = Files.createTempDirectory("calibration-legacy-store")
        val legacy = runResult().copy(
            attempt = attempt(kind = CalibrationRunKind.LEGACY_IMPORT, target = "Legacy target", capturedAt = null),
            visualContext = null,
            legacyFields = mapOf("targetDescription" to "Legacy target", "uiaStatus" to "FAILURE"),
            legacyCropPath = crop
        )
        CalibrationResultStore(storeRoot, fixedClock).persist(legacy)
        val record = Files.readString(runDirectories(storeRoot).single().resolve("result.json"))
        assertTrue("\"capturedAtUtc\":\"NOT_RECORDED\"" in record)
        assertTrue("\"fileModifiedAtUtc\":\"" in record)
        assertTrue("\"selectedObject\":\"NOT_RECORDED\"" in record)
        assertTrue("\"latencyMs\":\"NOT_RECORDED\"" in record)
        assertTrue("\"totalLatencyMs\":\"NOT_RECORDED\"" in record)
        assertTrue("\"groundTruthLabel\":\"PENDING\"" in record)
    }

    private fun processor(
        sink: CalibrationResultSink,
        ocr: OCRService = OCRService {
            OcrResult("Save", .91f, listOf(OcrWord("Save", .91f, Rectangle(1, 1, 8, 5))), "test-ocr")
        },
        uia: PerceptionEngine = PerceptionEngine {
            PerceptionResult(
                "Test", "Save", null, UiType.BUTTON, Rectangle(1, 1, 8, 5),
                1f, PerceptionSource.UI_AUTOMATION,
                mapOf("coordinateSpace" to "PHYSICAL_DESKTOP_SCREEN", "candidateCount" to "1")
            )
        }
    ) = ContextProcessor(
        ocr = ocr,
        perceptionEngine = uia,
        logger = RecordingContextLogger(),
        calibrationResultSink = sink
    )

    private fun uiaWithStatus(status: String) = PerceptionEngine {
        PerceptionResult(null, null, null, UiType.UNKNOWN, null, null, PerceptionSource.UNKNOWN, mapOf("status" to status))
    }

    private fun attempt(
        target: String? = "Select Save button",
        kind: CalibrationRunKind = CalibrationRunKind.LIVE_FLOW,
        capturedAt: Instant? = fixedClock.instant()
    ) = CalibrationAttempt("TEST_CASE", kind, capturedAt, target, "test-source")

    private fun runResult() = CalibrationRunResult(
        image, attempt(), Rectangle(0, 0, image.width, image.height), null, null, System.nanoTime()
    )

    private fun runDirectories(root: java.nio.file.Path) = Files.list(root).use { paths ->
        paths.filter { Files.isDirectory(it) }.toList()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
