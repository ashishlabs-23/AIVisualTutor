package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ui.ScreenshotPreviewEvidence

class VisualGroundingPersistenceTest {
    private val image = BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB)

    @Test
    fun notConfiguredAndUnavailablePreflightsArePersistedWithoutCoordinates() = runBlocking {
        val notConfiguredRoot = Files.createTempDirectory("visual-not-configured-")
        val notConfigured = LlamaCppVisualGroundingProvider(
            LlamaCppGroundingConfiguration(enabled = false),
            AvailableMemoryProbe { 8_000_000_000L },
            LlamaCppCommandRunner { _, _, _ -> error("not-configured provider must not run") }
        )
        val notConfiguredContext = process(notConfiguredRoot, notConfigured)
        val notConfiguredRecord = record(notConfiguredRoot)
        assertEquals("NOT_CONFIGURED", visualStatus(notConfiguredRecord))
        assertTrue("\"coordinateSpace\":\"NONE\"" in notConfiguredRecord)
        assertTrue("\"normalizedPoint\":\"NONE\"" in notConfiguredRecord)
        assertTrue("\"pixelPoint\":\"NONE\"" in notConfiguredRecord)
        assertEquals("Find the Blender cube", ScreenshotPreviewEvidence.from("Find the Blender cube", notConfiguredContext).targetLabel)

        val unavailableRoot = Files.createTempDirectory("visual-unavailable-")
        val unavailable = configuredProvider(
            availableMemory = 8_000_000_000L,
            modelExists = false,
            mmprojExists = false,
            runner = LlamaCppCommandRunner { _, _, _ -> error("missing artifacts must prevent execution") }
        )
        process(unavailableRoot, unavailable)
        val unavailableRecord = record(unavailableRoot)
        assertEquals("UNAVAILABLE", visualStatus(unavailableRecord))
        assertTrue("\"invocationRequested\":true" in unavailableRecord)
        assertTrue("\"invocationAttempted\":false" in unavailableRecord)
        assertTrue("\"coordinateSpace\":\"NONE\"" in unavailableRecord)
    }

    @Test
    fun memoryGuardBlocksBeforeModelFileCheckAndPersistsHostBlockedPreviewState() = runBlocking {
        val root = Files.createTempDirectory("visual-host-blocked-")
        var modelCalls = 0
        val guardedRunner = object : LlamaCppCommandRunner {
            override val requiresHostMemoryGuard = true
            override suspend fun run(configuration: LlamaCppGroundingConfiguration, imagePath: java.nio.file.Path, promptPath: java.nio.file.Path): LlamaCppProcessResult {
                modelCalls++
                error("host-blocked provider must not launch inference")
            }
        }
        val provider = configuredProvider(
            availableMemory = 1_817_931_776L,
            modelExists = false,
            mmprojExists = false,
            runner = guardedRunner
        )

        val context = process(root, provider)
        val record = record(root)

        assertEquals(0, modelCalls)
        assertEquals("HOST_BLOCKED", visualStatus(record))
        assertTrue("\"availableMemoryBytes\":\"1817931776\"" in record)
        assertTrue("\"requiredSafetyMemoryBytes\":\"3629247837\"" in record)
        assertTrue("\"guardDecision\":\"BLOCKED\"" in record)
        assertTrue("\"model\":\"UGround-V1-2B\"" in record)
        assertTrue("\"provider\":\"llama.cpp-uground-v1-2b\"" in record)
        assertTrue("\"runtime\"" in record && "0.6.0-dev build 11433" in record)
        assertTrue("\"diagnostic\":\"insufficient available memory for safe local inference\"" in record)
        assertTrue("\"coordinateSpace\":\"NONE\"" in record)
        assertTrue("\"normalizedPoint\":\"NONE\"" in record)
        assertTrue("\"pixelPoint\":\"NONE\"" in record)
        assertTrue("\"groundTruthLabel\":\"PENDING\"" in record)
        val preview = ScreenshotPreviewEvidence.from("Find the Blender cube", context)
        assertEquals("HOST_BLOCKED", preview.visualGroundingStatus)
        assertEquals("insufficient available memory for safe local inference", preview.visualReason)

        val unknownMemoryRoot = Files.createTempDirectory("visual-memory-unverified-")
        var unknownMemoryModelCalls = 0
        val unknownMemoryRunner = object : LlamaCppCommandRunner {
            override val requiresHostMemoryGuard = true
            override suspend fun run(configuration: LlamaCppGroundingConfiguration, imagePath: java.nio.file.Path, promptPath: java.nio.file.Path): LlamaCppProcessResult {
                unknownMemoryModelCalls++
                error("inference must not run when available memory cannot be measured")
            }
        }
        process(unknownMemoryRoot, configuredProvider(
            availableMemory = null,
            modelExists = false,
            mmprojExists = false,
            runner = unknownMemoryRunner
        ))
        val unknownMemoryRecord = record(unknownMemoryRoot)
        assertEquals(0, unknownMemoryModelCalls)
        assertEquals("HOST_BLOCKED", visualStatus(unknownMemoryRecord))
        assertTrue("\"availableMemoryBytes\":\"NOT_RECORDED\"" in unknownMemoryRecord)
        assertTrue("\"guardDecision\":\"UNVERIFIED\"" in unknownMemoryRecord)
        assertTrue("available memory could not be verified for safe local inference" in unknownMemoryRecord)
        assertTrue("\"coordinateSpace\":\"NONE\"" in unknownMemoryRecord)
    }

    @Test
    fun malformedOutputTimeoutAndSuccessArePersistedWithOnlyMeasuredGeometry() = runBlocking {
        val malformedRoot = Files.createTempDirectory("visual-malformed-")
        process(malformedRoot, configuredProvider(
            8_000_000_000L, runner = LlamaCppCommandRunner { _, _, _ ->
                LlamaCppProcessResult(exitCode = 0, output = "not a point")
            }
        ))
        val malformed = record(malformedRoot)
        assertEquals("FAILURE", visualStatus(malformed))
        assertTrue("\"normalizedPoint\":\"NONE\"" in malformed)
        assertTrue("\"pixelPoint\":\"NONE\"" in malformed)
        assertTrue("\"coordinateSpace\":\"NONE\"" in malformed)
        assertTrue("\"confidence\":\"NOT_RECORDED\"" in malformed)
        assertTrue("\"rawOutput\":\"not a point\"" in malformed)

        val timeoutRoot = Files.createTempDirectory("visual-timeout-")
        process(timeoutRoot, configuredProvider(
            8_000_000_000L,
            timeoutMillis = 10,
            runner = LlamaCppCommandRunner { _, _, _ ->
                delay(1_000)
                LlamaCppProcessResult(exitCode = 0, output = "(2, 3)")
            }
        ))
        val timeout = record(timeoutRoot)
        assertEquals("FAILURE", visualStatus(timeout))
        assertTrue("provider_timeout" in timeout)
        assertTrue("\"coordinateSpace\":\"NONE\"" in timeout)

        val successRoot = Files.createTempDirectory("visual-success-")
        process(successRoot, configuredProvider(
            8_000_000_000L,
            runner = LlamaCppCommandRunner { _, _, _ ->
                LlamaCppProcessResult(exitCode = 0, output = "(250, 750)", modelLoadMillis = 31, generationMillis = 14)
            }
        ))
        val success = record(successRoot)
        assertEquals("SUCCESS", visualStatus(success))
        assertTrue("\"normalizedPoint\":{\"x\":0.25, \"y\":0.75}" in success)
        assertTrue("\"pixelPoint\":{\"x\":3.0, \"y\":6.0}" in success)
        assertTrue("\"coordinateSpace\":\"NORMALIZED_CROP\"" in success)
        assertTrue("\"latencyMs\":" in success)
        assertTrue("\"modelSource\":\"osunlp/UGround-V1-2B\"" in success)
        assertTrue("\"quantizationSource\":\"mradermacher/UGround-V1-2B-GGUF\"" in success)
        assertTrue("\"confidence\":\"NOT_RECORDED\"" in success)
    }

    @Test
    fun cancelledVisualAttemptPersistsProviderStateAndNoPrediction() = runBlocking {
        val root = Files.createTempDirectory("visual-cancelled-")
        val started = CompletableDeferred<Unit>()
        val provider = configuredProvider(
            8_000_000_000L,
            runner = LlamaCppCommandRunner { _, _, _ -> started.complete(Unit); awaitCancellation() }
        )
        val processor = processor(root, provider)
        val job = async {
            processor.process(
                image, 0, 0, null,
                targetDescription = "Find the Blender cube",
                calibrationAttempt = attempt()
            )
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        val record = record(root)
        assertEquals("CANCELLED", visualStatus(record))
        assertTrue("\"invocationRequested\":true" in record)
        assertTrue("\"invocationAttempted\":true" in record)
        assertTrue("\"normalizedPoint\":\"NONE\"" in record)
        assertTrue("\"pixelPoint\":\"NONE\"" in record)
        assertTrue("\"coordinateSpace\":\"NONE\"" in record)
    }

    @Test
    fun targetAndOptionalGroundTruthPersistAndTestFakeIsClearlyIdentified() = runBlocking {
        val pendingRoot = Files.createTempDirectory("visual-pending-label-")
        val fake = VisualGroundingProvider {
            VisualGroundingResult(
                semantic = null,
                geometry = null,
                providerId = "TEST_FAKE_VISUAL_PROVIDER",
                availability = GroundingProviderAvailability.FAILURE,
                metadata = mapOf("status" to "test_failure")
            )
        }
        process(pendingRoot, fake)
        val pending = record(pendingRoot)
        assertTrue("\"targetDescription\":\"Find the Blender cube\"" in pending)
        assertTrue("\"groundTruthLabel\":\"PENDING\"" in pending)
        assertTrue("\"provider\":\"TEST_FAKE_VISUAL_PROVIDER\"" in pending)
        assertFalse("\"provider\":\"llama.cpp-uground-v1-2b\"" in pending)

        val nullLabelRoot = Files.createTempDirectory("visual-null-label-")
        process(nullLabelRoot, fake, label = null)
        assertTrue("\"groundTruthLabel\":null" in record(nullLabelRoot))

        val humanLabelRoot = Files.createTempDirectory("visual-human-label-")
        process(humanLabelRoot, fake, label = "Human supplied label")
        assertTrue("\"groundTruthLabel\":\"Human supplied label\"" in record(humanLabelRoot))
    }

    @Test
    fun blankTargetIsPersistedWithoutInvokingTheProvider() = runBlocking {
        val root = Files.createTempDirectory("visual-blank-target-")
        var calls = 0
        val fake = VisualGroundingProvider {
            calls++
            VisualGroundingResult(null, null, "TEST_FAKE_VISUAL_PROVIDER", GroundingProviderAvailability.FAILURE)
        }
        val result = processor(root, fake).process(
            image, 0, 0, null, targetDescription = " ",
            calibrationAttempt = attempt().copy(targetDescription = " ")
        )
        val record = record(root)
        assertEquals(0, calls)
        assertTrue("\"targetDescription\":\" \"" in record)
        assertTrue("\"invocationRequested\":false" in record)
        assertEquals("NOT_INVOKED_MISSING_TARGET", result.evidenceEvaluation?.visualGroundingInvocation?.name)
    }

    @Test
    fun schemaThreeAddsOptionalVisualBlockWithoutRewritingVersionOneRecords() {
        val root = Files.createTempDirectory("visual-schema-compat-")
        val oldRecord = root.resolve("legacy-run").resolve("result.json")
        Files.createDirectories(oldRecord.parent)
        val oldContents = """{"schemaVersion":1,"caseId":"OLD_CASE","runId":"legacy-run","runKind":"CROP_REPLAY","sha256":"${"a".repeat(64)}","processingStatus":"AVAILABLE","processedAtUtc":"2026-10-01T00:00:00Z"}"""
        Files.writeString(oldRecord, oldContents)
        val store = CalibrationResultStore(root)
        store.persist(
            CalibrationRunResult(
                image = image,
                attempt = attempt(caseId = "NEW_CASE"),
                selectedRegion = Rectangle(0, 0, image.width, image.height),
                applicationContext = null,
                visualContext = null,
                processingStartedNanos = System.nanoTime()
            )
        )

        assertEquals(oldContents, Files.readString(oldRecord))
        assertTrue(Files.readString(root.resolve(CalibrationResultStore.MANIFEST)).contains("\"caseId\":\"OLD_CASE\""))
        val newRunDirectory = Files.list(root).use { dirs ->
            dirs.filter { Files.isDirectory(it) && it.fileName.toString() != "legacy-run" }.findFirst().orElseThrow()
        }
        val newRecord = Files.readString(newRunDirectory.resolve("result.json"))
        assertTrue("\"schemaVersion\":3" in newRecord)
        assertTrue("\"visualGrounding\":" in newRecord)
        assertTrue("\"coordinateSpace\":\"NONE\"" in newRecord)
    }

    private suspend fun process(
        root: java.nio.file.Path,
        provider: VisualGroundingProvider,
        label: String? = "PENDING"
    ): VisualContext =
        processor(root, provider).process(
            image, 0, 0, null,
            selectedRegion = Rectangle(0, 0, image.width, image.height),
            targetDescription = "Find the Blender cube",
            calibrationAttempt = attempt(label = label)
        )

    private fun processor(root: java.nio.file.Path, provider: VisualGroundingProvider) = ContextProcessor(
        ocr = OCRService { OcrResult("", null, engineName = "test-ocr") },
        perceptionEngine = PerceptionEngine {
            PerceptionResult(
                applicationName = null,
                selectedObject = null,
                visibleText = null,
                uiType = UiType.UNKNOWN,
                boundingRectangle = null,
                confidence = null,
                source = PerceptionSource.UNKNOWN
            )
        },
        evidenceEvaluationCoordinator = EvidenceEvaluationCoordinator(provider),
        calibrationResultSink = CalibrationResultStore(root)
    )

    private fun configuredProvider(
        availableMemory: Long?,
        modelExists: Boolean = true,
        mmprojExists: Boolean = true,
        timeoutMillis: Long = 1_000,
        runner: LlamaCppCommandRunner
    ): LlamaCppVisualGroundingProvider {
        val directory = Files.createTempDirectory("visual-provider-config-")
        val executable = Files.createFile(directory.resolve("llama-mtmd-cli.exe"))
        val model = directory.resolve("UGround-V1-2B.Q4_K_M.gguf")
        val mmproj = directory.resolve("UGround-V1-2B.mmproj-fp16.gguf")
        if (modelExists) Files.createFile(model)
        if (mmprojExists) Files.createFile(mmproj)
        return LlamaCppVisualGroundingProvider(
            configuration = LlamaCppGroundingConfiguration(
                enabled = true,
                executablePath = executable,
                modelPath = model,
                mmprojPath = mmproj,
                timeoutMillis = timeoutMillis,
                runtimeVersion = "0.6.0-dev build 11433"
            ),
            memoryProbe = AvailableMemoryProbe { availableMemory },
            runner = runner
        )
    }

    private fun attempt(caseId: String = "B03", label: String? = "PENDING") = CalibrationAttempt(
        caseId = caseId,
        runKind = CalibrationRunKind.CROP_REPLAY,
        capturedAt = null,
        targetDescription = "Find the Blender cube",
        targetDescriptionSource = "manifest.targetDescription",
        groundTruthLabel = label
    )

    private fun record(root: java.nio.file.Path): String {
        val directory = Files.list(root).use { paths ->
            paths.filter { Files.isDirectory(it) }.findFirst().orElseThrow()
        }
        return Files.readString(directory.resolve("result.json"))
    }

    private fun visualStatus(json: String): String =
        Regex("\"visualGrounding\":\\{\"status\":\"([A-Z_]+)\"")
            .findAll(json).lastOrNull()?.groupValues?.get(1) ?: error("visualGrounding status missing")
}
