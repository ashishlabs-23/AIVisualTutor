package context

import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class LlamaCppVisualGroundingProviderTest {
    @Test
    fun successfulProviderReturnsNormalizedCropPointWithoutInventedConfidence() = runBlocking {
        val files = configuredFiles()
        try {
            val provider = LlamaCppVisualGroundingProvider(files.configuration) { _, image, prompt ->
                assertTrue(Files.isRegularFile(image))
                assertTrue(Files.readString(prompt).contains("Description:   Find the Save button  "))
                LlamaCppProcessResult(
                    exitCode = 0,
                    output = "(250, 800)",
                    modelLoadMillis = 14,
                    generationMillis = 8
                )
            }

            val result = provider.ground(request("  Find the Save button  "))

            assertEquals(GroundingProviderAvailability.AVAILABLE, result.availability)
            assertNull(result.semantic)
            assertEquals(VisualGroundingCoordinateSpace.NORMALIZED_CROP, result.geometry?.coordinateSpace)
            assertEquals(GroundingPoint(.25, .8), result.geometry?.point)
            assertNull(result.geometry?.confidence)
            assertEquals("14", result.metadata["modelLoadTimeMs"])
            assertEquals("8", result.metadata["inferenceTimeMs"])
            assertTrue(result.latencyMillis != null && result.latencyMillis >= 0)
        } finally {
            files.close()
        }
    }

    @Test
    fun missingModelConfigurationIsUnavailableWithoutCallingRunner() = runBlocking {
        val files = configuredFiles()
        try {
            Files.delete(files.model)
            var calls = 0
            val provider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, _ ->
                calls++
                error("missing model must prevent process execution")
            }

            val result = provider.ground(request("Find Save"))

            assertEquals(GroundingProviderAvailability.UNAVAILABLE, result.availability)
            assertEquals(0, calls)
        } finally {
            files.close()
        }
    }

    @Test
    fun timeoutBecomesExplicitFailure() = runBlocking {
        val files = configuredFiles(timeoutMillis = 20)
        try {
            val provider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, _ ->
                delay(1_000)
                LlamaCppProcessResult(0, "(10, 20)")
            }

            val result = provider.ground(request("Find Save"))

            assertEquals(GroundingProviderAvailability.FAILURE, result.availability)
            assertEquals("provider_timeout", result.metadata["diagnostic"])
        } finally {
            files.close()
        }
    }

    @Test
    fun callerCancellationIsRethrown() = runBlocking {
        val files = configuredFiles()
        try {
            val provider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, _ ->
                awaitCancellation()
            }
            val job = async { provider.ground(request("Find Save")) }
            delay(20)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            files.close()
        }
    }

    @Test
    fun malformedAndOutOfRangeCoordinatesAreRejected() {
        assertNull(LlamaCppVisualGroundingProvider.parseModelPoint("not a point"))
        assertNull(LlamaCppVisualGroundingProvider.parseModelPoint("(10,)"))
        assertNull(LlamaCppVisualGroundingProvider.parseModelPoint("(NaN, 2)"))
        assertNull(LlamaCppVisualGroundingProvider.parseModelPoint("(10, 1000)"))
        assertNull(LlamaCppVisualGroundingProvider.parseModelPoint("(-1, 10)"))
        assertNull(LlamaCppVisualGroundingProvider.parseModelPoint("(10, 20)\n(30, 40)"))
    }

    @Test
    fun validModelPointUsesDocumentedThousandScaleThenNormalizesToCrop() {
        val point = LlamaCppVisualGroundingProvider.parseModelPoint("(999.5, 0)")
        assertEquals(GroundingPoint(999.5, 0.0), point)
        assertEquals(
            GroundingPoint(.9995, 0.0),
            GroundingPoint(point!!.x / 1000.0, point.y / 1000.0)
        )
    }

    @Test
    fun coordinatorPassesTheUnmodifiedTargetDescriptionToConfiguredProvider() = runBlocking {
        val files = configuredFiles()
        try {
            var prompt = ""
            val provider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, promptPath ->
                prompt = Files.readString(promptPath)
                LlamaCppProcessResult(0, "(20, 30)")
            }

            EvidenceEvaluationCoordinator(provider).evaluate(
                screenshot = BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB),
                applicationContext = null,
                targetDescription = "  Find the Save button  ",
                uiAutomationEvidence = null,
                uiAutomationStatus = EvidenceStatus.INSUFFICIENT,
                ocrEvidence = null,
                ocrStatus = EvidenceStatus.INSUFFICIENT
            )

            assertTrue(prompt.contains("Description:   Find the Save button  \n"))
        } finally {
            files.close()
        }
    }

    @Test
    fun disabledProviderIsNotConfiguredAndDoesNotWriteOrInvoke() = runBlocking {
        var calls = 0
        val provider = LlamaCppVisualGroundingProvider(
            LlamaCppGroundingConfiguration(enabled = false),
            LlamaCppCommandRunner { _, _, _ -> calls++; LlamaCppProcessResult(0, "(1, 2)") }
        )
        val result = provider.ground(request("Find Save"))
        assertEquals(GroundingProviderAvailability.NOT_CONFIGURED, result.availability)
        assertEquals(0, calls)
    }

    @Test
    fun hostMemoryGuardBlocksExecutionWhenAvailableMemoryIsBelowThreshold() = runBlocking {
        val files = configuredFiles()
        try {
            val guardedRunner = object : LlamaCppCommandRunner {
                override val requiresHostMemoryGuard = true
                override suspend fun run(config: LlamaCppGroundingConfiguration, img: java.nio.file.Path, pmt: java.nio.file.Path) =
                    LlamaCppProcessResult(0, "(100, 200)")
            }
            // Memory probe reporting less than 3.63 GB (e.g. 1.8 GB)
            val lowMemoryProbe = AvailableMemoryProbe { 1_800_000_000L }
            val provider = LlamaCppVisualGroundingProvider(files.configuration, lowMemoryProbe, guardedRunner)

            val preflight = provider.preflight()
            assertEquals(GroundingProviderAvailability.HOST_BLOCKED, preflight.availability)
            assertEquals("BLOCKED", preflight.metadata["guardDecision"])

            val result = provider.ground(request("Find Save"))
            assertEquals(GroundingProviderAvailability.HOST_BLOCKED, result.availability)
        } finally {
            files.close()
        }
    }

    @Test
    fun hostMemoryGuardPassesWhenAvailableMemoryIsSufficient() = runBlocking {
        val files = configuredFiles()
        try {
            val guardedRunner = object : LlamaCppCommandRunner {
                override val requiresHostMemoryGuard = true
                override suspend fun run(config: LlamaCppGroundingConfiguration, img: java.nio.file.Path, pmt: java.nio.file.Path) =
                    LlamaCppProcessResult(0, "(100, 200)")
            }
            // Memory probe reporting 8 GB (greater than 3.63 GB threshold)
            val sufficientMemoryProbe = AvailableMemoryProbe { 8_000_000_000L }
            val provider = LlamaCppVisualGroundingProvider(files.configuration, sufficientMemoryProbe, guardedRunner)

            val preflight = provider.preflight()
            assertEquals(GroundingProviderAvailability.AVAILABLE, preflight.availability)
            assertEquals("PASS", preflight.metadata["guardDecision"])

            val result = provider.ground(request("Find Save"))
            assertEquals(GroundingProviderAvailability.AVAILABLE, result.availability)
            assertEquals(GroundingPoint(0.1, 0.2), result.geometry?.point)
        } finally {
            files.close()
        }
    }

    @Test
    fun processExitCodeFailureReturnsExplicitFailureResult() = runBlocking {
        val files = configuredFiles()
        try {
            val provider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, _ ->
                LlamaCppProcessResult(exitCode = 1, output = "CUDA out of memory error")
            }
            val result = provider.ground(request("Find Save"))
            assertEquals(GroundingProviderAvailability.FAILURE, result.availability)
            assertEquals("provider_process_failed", result.metadata["status"])
            assertEquals("1", result.metadata["exitCode"])
        } finally {
            files.close()
        }
    }

    @Test
    fun pixelPointConversionMatchesScreenshotDimensions() = runBlocking {
        val files = configuredFiles()
        try {
            val provider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, _ ->
                LlamaCppProcessResult(exitCode = 0, output = "(500, 250)")
            }
            // Request with 640x480 screenshot
            val req = VisualGroundingRequest(
                screenshot = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB),
                applicationContext = null,
                targetDescription = "Find Center",
                ocrEvidence = null,
                uiAutomationEvidence = null
            )
            val result = provider.ground(req)
            assertEquals(GroundingProviderAvailability.AVAILABLE, result.availability)
            assertEquals(GroundingPoint(0.5, 0.25), result.geometry?.point)
            assertEquals("320.0,120.0", result.metadata["pixelPoint"])
            assertEquals("640", result.metadata["cropWidthPx"])
            assertEquals("480", result.metadata["cropHeightPx"])
            assertEquals("SUCCESS", result.metadata["failureCategory"])
        } finally {
            files.close()
        }
    }

    @Test
    fun missingFilesReportedAsUnavailableWithStructuredDiagnostics() = runBlocking {
        val files = configuredFiles()
        try {
            Files.delete(files.model)
            val guardedRunner = object : LlamaCppCommandRunner {
                override val requiresHostMemoryGuard = true
                override suspend fun run(config: LlamaCppGroundingConfiguration, img: java.nio.file.Path, pmt: java.nio.file.Path) =
                    LlamaCppProcessResult(0, "(100, 200)")
            }
            val sufficientMemoryProbe = AvailableMemoryProbe { 8_000_000_000L } // 8 GB, passes memory guard
            val provider = LlamaCppVisualGroundingProvider(files.configuration, sufficientMemoryProbe, guardedRunner)

            val preflight = provider.preflight()
            assertEquals(GroundingProviderAvailability.UNAVAILABLE, preflight.availability)
            assertEquals("MODEL_NOT_FOUND", preflight.metadata["failureCategory"])
            assertTrue(preflight.diagnostic?.contains("model") == true)
        } finally {
            files.close()
        }
    }

    @Test
    fun parseModelPointSupportsPreambleTokensAndSpecialTokens() {
        val pointWithAnswer = LlamaCppVisualGroundingProvider.parseModelPoint("Answer: (300, 400)")
        assertEquals(GroundingPoint(300.0, 400.0), pointWithAnswer)

        val pointWithSpecialToken = LlamaCppVisualGroundingProvider.parseModelPoint("(150.5, 600.2) <|im_end|>")
        assertEquals(GroundingPoint(150.5, 600.2), pointWithSpecialToken)

        val pointWithEmbeddedLine = LlamaCppVisualGroundingProvider.parseModelPoint("The target coordinates are (500, 200) on screen.")
        assertEquals(GroundingPoint(500.0, 200.0), pointWithEmbeddedLine)
    }

    @Test
    fun structuredFailureCategoriesDistinguishedAcrossPipeline() = runBlocking {
        val files = configuredFiles()
        try {
            // Empty output
            val emptyProvider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, _ ->
                LlamaCppProcessResult(exitCode = 0, output = "   ")
            }
            val emptyResult = emptyProvider.ground(request("Find Box"))
            assertEquals(GroundingProviderAvailability.FAILURE, emptyResult.availability)
            assertEquals("EMPTY_MODEL_OUTPUT", emptyResult.metadata["failureCategory"])

            // Unparsable output
            val parseFailProvider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, _ ->
                LlamaCppProcessResult(exitCode = 0, output = "I cannot see the button")
            }
            val parseFailResult = parseFailProvider.ground(request("Find Box"))
            assertEquals(GroundingProviderAvailability.FAILURE, parseFailResult.availability)
            assertEquals("OUTPUT_PARSE_FAILED", parseFailResult.metadata["failureCategory"])

            // Process nonzero exit code
            val nonzeroProvider = LlamaCppVisualGroundingProvider(files.configuration) { _, _, _ ->
                LlamaCppProcessResult(exitCode = 137, output = "Killed")
            }
            val nonzeroResult = nonzeroProvider.ground(request("Find Box"))
            assertEquals(GroundingProviderAvailability.FAILURE, nonzeroResult.availability)
            assertEquals("PROCESS_EXIT_NONZERO", nonzeroResult.metadata["failureCategory"])
        } finally {
            files.close()
        }
    }

    private fun request(target: String?) = VisualGroundingRequest(
        screenshot = BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB),
        applicationContext = null,
        targetDescription = target,
        ocrEvidence = null,
        uiAutomationEvidence = null
    )

    private fun configuredFiles(timeoutMillis: Long = 1_000): TemporaryFiles {
        val directory = Files.createTempDirectory("aivt-llama-test-")
        val executable = Files.createFile(directory.resolve("llama-mtmd-cli.exe"))
        val model = Files.createFile(directory.resolve("model.gguf"))
        val mmproj = Files.createFile(directory.resolve("mmproj.gguf"))
        return TemporaryFiles(
            directory,
            executable,
            model,
            LlamaCppGroundingConfiguration(
                enabled = true,
                executablePath = executable,
                modelPath = model,
                mmprojPath = mmproj,
                timeoutMillis = timeoutMillis
            )
        )
    }

    private data class TemporaryFiles(
        val directory: java.nio.file.Path,
        val executable: java.nio.file.Path,
        val model: java.nio.file.Path,
        val configuration: LlamaCppGroundingConfiguration
    ) : AutoCloseable {
        val mmproj = directory.resolve("mmproj.gguf")

        override fun close() {
            Files.deleteIfExists(executable)
            Files.deleteIfExists(model)
            Files.deleteIfExists(mmproj)
            Files.deleteIfExists(directory)
        }
    }
}
