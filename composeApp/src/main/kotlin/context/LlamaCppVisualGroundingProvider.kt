package context

import java.awt.image.BufferedImage
import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class LlamaCppGroundingConfiguration(
    val enabled: Boolean = false,
    val executablePath: Path? = null,
    val modelPath: Path? = null,
    val mmprojPath: Path? = null,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val runtimeVersion: String = "NOT_RECORDED"
) {
    init {
        require(timeoutMillis > 0) { "Visual-grounding timeout must be positive." }
    }

    companion object {
        const val ENABLE_PROPERTY = "aivt.visual.enabled"
        const val EXECUTABLE_PROPERTY = "aivt.visual.executable"
        const val MODEL_PROPERTY = "aivt.visual.model"
        const val MMPROJ_PROPERTY = "aivt.visual.mmproj"
        const val TIMEOUT_PROPERTY = "aivt.visual.timeoutMillis"
        const val RUNTIME_VERSION_PROPERTY = "aivt.visual.runtimeVersion"
        const val MODEL_SOURCE = "osunlp/UGround-V1-2B"
        const val QUANTIZATION_SOURCE = "mradermacher/UGround-V1-2B-GGUF"
        const val MODEL_ID = "UGround-V1-2B"
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L

        fun fromSystemProperties(): LlamaCppGroundingConfiguration =
            LlamaCppGroundingConfiguration(
                enabled = System.getProperty(ENABLE_PROPERTY)?.equals("true", ignoreCase = true) == true,
                executablePath = System.getProperty(EXECUTABLE_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of),
                modelPath = System.getProperty(MODEL_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of),
                mmprojPath = System.getProperty(MMPROJ_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of),
                timeoutMillis = System.getProperty(TIMEOUT_PROPERTY)?.let { value ->
                    value.toLongOrNull() ?: throw IllegalArgumentException("$TIMEOUT_PROPERTY must be an integer.")
                } ?: DEFAULT_TIMEOUT_MILLIS,
                runtimeVersion = System.getProperty(RUNTIME_VERSION_PROPERTY)?.takeIf(String::isNotBlank)
                    ?: "NOT_RECORDED"
            )
    }
}

fun interface AvailableMemoryProbe {
    fun availableBytes(): Long?
}

object SystemAvailableMemoryProbe : AvailableMemoryProbe {
    override fun availableBytes(): Long? =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.freeMemorySize
}

data class LlamaCppProcessResult(
    val exitCode: Int,
    val output: String,
    val modelLoadMillis: Long? = null,
    val imageEncodingMillis: Long? = null,
    val generationMillis: Long? = null
)

fun interface LlamaCppCommandRunner {
    val requiresHostMemoryGuard: Boolean
        get() = false

    suspend fun run(
        configuration: LlamaCppGroundingConfiguration,
        imagePath: Path,
        promptPath: Path
    ): LlamaCppProcessResult
}

/** Runs the configured llama.cpp multimodal CLI without a shell or secondary inference runtime. */
class LlamaCppVisualGroundingProvider(
    private val configuration: LlamaCppGroundingConfiguration = LlamaCppGroundingConfiguration.fromSystemProperties(),
    private val memoryProbe: AvailableMemoryProbe = SystemAvailableMemoryProbe,
    private val runner: LlamaCppCommandRunner = LlamaCppMtmdCommandRunner
) : VisualGroundingProvider {
    constructor(configuration: LlamaCppGroundingConfiguration, runner: LlamaCppCommandRunner) :
        this(configuration, SystemAvailableMemoryProbe, runner)

    override val providerId = "llama.cpp-uground-v1-2b"

    override val availability: GroundingProviderAvailability get() = preflight().availability
    override val availabilityDiagnostic: String? get() = preflight().diagnostic

    override fun preflight(): VisualGroundingPreflight {
        val availableMemory = memoryProbe.availableBytes()
        val modelBytes = configuration.modelPath?.let(::regularFileSize)
        val mmprojBytes = configuration.mmprojPath?.let(::regularFileSize)
        val jvmMemory = Runtime.getRuntime()
        val metadata = mapOf(
            "provider" to providerId,
            "model" to LlamaCppGroundingConfiguration.MODEL_ID,
            "modelFile" to (configuration.modelPath?.fileName?.toString() ?: "NOT_CONFIGURED"),
            "modelSource" to LlamaCppGroundingConfiguration.MODEL_SOURCE,
            "quantizationSource" to LlamaCppGroundingConfiguration.QUANTIZATION_SOURCE,
            "runtime" to runtimeIdentity(),
            "executable" to (configuration.executablePath?.toString() ?: "NOT_CONFIGURED"),
            "configured" to configuration.enabled.toString(),
            "availableMemoryBytes" to (availableMemory?.toString() ?: "NOT_RECORDED"),
            "requiredSafetyMemoryBytes" to MINIMUM_AVAILABLE_MEMORY_BYTES.toString(),
            "modelFileBytes" to (modelBytes?.toString() ?: "NOT_RECORDED"),
            "mmprojFileBytes" to (mmprojBytes?.toString() ?: "NOT_RECORDED"),
            "combinedArtifactBytes" to if (modelBytes != null && mmprojBytes != null) (modelBytes + mmprojBytes).toString() else "NOT_RECORDED",
            "availableMemoryProbe" to "OPERATING_SYSTEM_AVAILABLE_PHYSICAL_MEMORY",
            "gpuMemoryBytes" to "NOT_PROBED",
            "inferenceBackend" to if (runner.requiresHostMemoryGuard) "CPU_ONLY" else "CUSTOM_RUNNER",
            "jvmMaxMemoryBytes" to jvmMemory.maxMemory().toString(),
            "jvmCommittedMemoryBytes" to jvmMemory.totalMemory().toString(),
            "guardDecision" to when {
                !runner.requiresHostMemoryGuard -> "NOT_APPLICABLE_TO_TEST_RUNNER"
                availableMemory == null -> "UNVERIFIED"
                availableMemory < MINIMUM_AVAILABLE_MEMORY_BYTES -> "BLOCKED"
                else -> "PASS"
            }
        )
        if (!configuration.enabled) {
            return VisualGroundingPreflight(
                GroundingProviderAvailability.NOT_CONFIGURED,
                "visual_provider_not_configured",
                metadata
            )
        }
        val paths = listOf(configuration.executablePath, configuration.modelPath, configuration.mmprojPath)
        if (paths.any { it == null }) {
            return VisualGroundingPreflight(
                GroundingProviderAvailability.NOT_CONFIGURED,
                "visual_runtime_or_model_path_not_configured",
                metadata
            )
        }
        if (runner.requiresHostMemoryGuard &&
            (availableMemory == null || availableMemory < MINIMUM_AVAILABLE_MEMORY_BYTES)
        ) {
            return VisualGroundingPreflight(
                GroundingProviderAvailability.HOST_BLOCKED,
                if (availableMemory == null) {
                    "available memory could not be verified for safe local inference"
                } else {
                    "insufficient available memory for safe local inference"
                },
                metadata
            )
        }
        if (paths.filterNotNull().any { !Files.isRegularFile(it) }) {
            return VisualGroundingPreflight(
                GroundingProviderAvailability.UNAVAILABLE,
                "configured_runtime_or_model_file_missing",
                metadata
            )
        }
        return VisualGroundingPreflight(
            GroundingProviderAvailability.AVAILABLE,
            metadata = metadata + ("guardDecision" to if (runner.requiresHostMemoryGuard) "PASS" else "NOT_APPLICABLE_TO_TEST_RUNNER")
        )
    }

    override suspend fun ground(request: VisualGroundingRequest): VisualGroundingResult {
        val started = System.nanoTime()
        val preflight = preflight()
        if (preflight.availability != GroundingProviderAvailability.AVAILABLE) {
            return statusResult(preflight, started)
        }
        val target = request.targetDescription?.takeUnless(String::isBlank)
            ?: return statusResult(
                VisualGroundingPreflight(
                    GroundingProviderAvailability.NOT_CONFIGURED,
                    "target_description_missing"
                ),
                started
            )

        val temporaryDirectory = Files.createTempDirectory("aivt-visual-grounding-")
        try {
            val imagePath = temporaryDirectory.resolve("crop.png")
            val promptPath = temporaryDirectory.resolve("prompt.txt")
            val imageEncodingStarted = System.nanoTime()
            if (!ImageIO.write(request.screenshot, "png", imagePath.toFile())) {
                return failureResult("screenshot_png_encoding_failed", started)
            }
            val imageEncodingMillis = (System.nanoTime() - imageEncodingStarted) / 1_000_000
            Files.writeString(promptPath, groundingPrompt(target))

            val processResult = try {
                withTimeout(configuration.timeoutMillis) {
                    runner.run(configuration, imagePath, promptPath)
                }
            } catch (e: TimeoutCancellationException) {
                return failureResult("provider_timeout", started, imageEncodingMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failureResult(e.javaClass.simpleName, started, imageEncodingMillis)
            }
            val latencyMillis = (System.nanoTime() - started) / 1_000_000
            val commonMetadata = mapOf(
                "model" to LlamaCppGroundingConfiguration.MODEL_ID,
                "modelFile" to (configuration.modelPath?.fileName?.toString() ?: "NOT_CONFIGURED"),
                "modelSource" to LlamaCppGroundingConfiguration.MODEL_SOURCE,
                "quantizationSource" to LlamaCppGroundingConfiguration.QUANTIZATION_SOURCE,
                "runtime" to runtimeIdentity(),
                "cropPngEncodingTimeMs" to imageEncodingMillis.toString(),
                "modelImageEncodingTimeMs" to processResult.imageEncodingMillis?.toString().orStatus("NOT_RECORDED"),
                "modelLoadTimeMs" to processResult.modelLoadMillis?.toString().orStatus("NOT_RECORDED"),
                "inferenceTimeMs" to processResult.generationMillis?.toString().orStatus("NOT_RECORDED"),
                "rawModelOutput" to processResult.output.takeLast(MAX_RECORDED_OUTPUT_CHARS),
                "coordinateFormat" to "MODEL_0_TO_1000_NORMALIZED_TO_NORMALIZED_CROP"
            )
            if (processResult.exitCode != 0) {
                return VisualGroundingResult(
                    semantic = null,
                    geometry = null,
                    providerId = providerId,
                    availability = GroundingProviderAvailability.FAILURE,
                    executionLocation = GroundingExecutionLocation.LOCAL,
                    latencyMillis = latencyMillis,
                    metadata = commonMetadata + mapOf(
                        "status" to "provider_process_failed",
                        "exitCode" to processResult.exitCode.toString()
                    )
                )
            }

            val point = parseModelPoint(processResult.output)
                ?: return VisualGroundingResult(
                    semantic = null,
                    geometry = null,
                    providerId = providerId,
                    availability = GroundingProviderAvailability.FAILURE,
                    executionLocation = GroundingExecutionLocation.LOCAL,
                    latencyMillis = latencyMillis,
                    metadata = commonMetadata + ("status" to "invalid_coordinate_output")
                )
            val normalized = GroundingPoint(point.x / MODEL_COORDINATE_SCALE, point.y / MODEL_COORDINATE_SCALE)
            val pixel = GroundingPoint(
                normalized.x * request.screenshot.width,
                normalized.y * request.screenshot.height
            )
            return VisualGroundingResult(
                semantic = null,
                geometry = GroundingGeometricEvidence(
                    point = normalized,
                    coordinateSpace = VisualGroundingCoordinateSpace.NORMALIZED_CROP
                ),
                providerId = providerId,
                availability = GroundingProviderAvailability.AVAILABLE,
                executionLocation = GroundingExecutionLocation.LOCAL,
                latencyMillis = latencyMillis,
                metadata = commonMetadata + mapOf(
                    "status" to "point_returned",
                    "normalizedPoint" to "${normalized.x},${normalized.y}",
                    "pixelPoint" to "${pixel.x},${pixel.y}",
                    "cropWidthPx" to request.screenshot.width.toString(),
                    "cropHeightPx" to request.screenshot.height.toString()
                )
            )
        } catch (e: IOException) {
            return failureResult(e.javaClass.simpleName, started)
        } finally {
            Files.deleteIfExists(temporaryDirectory.resolve("crop.png"))
            Files.deleteIfExists(temporaryDirectory.resolve("prompt.txt"))
            Files.deleteIfExists(temporaryDirectory)
        }
    }

    private fun statusResult(preflight: VisualGroundingPreflight, started: Long) = VisualGroundingResult(
        semantic = null,
        geometry = null,
        providerId = providerId,
        availability = preflight.availability,
        executionLocation = GroundingExecutionLocation.LOCAL,
        latencyMillis = (System.nanoTime() - started) / 1_000_000,
        metadata = preflight.metadata + mapOf(
            "status" to preflight.availability.name,
            "diagnostic" to preflight.diagnostic.orStatus(preflight.availability.name)
        )
    )

    private fun runtimeIdentity() =
        "${configuration.executablePath?.toString() ?: "llama-mtmd-cli"} (${configuration.runtimeVersion})"

    private fun regularFileSize(path: Path): Long? =
        if (Files.isRegularFile(path)) Files.size(path) else null

    private fun failureResult(
        diagnostic: String,
        started: Long,
        imageEncodingMillis: Long? = null
    ) = VisualGroundingResult(
        semantic = null,
        geometry = null,
        providerId = providerId,
        availability = GroundingProviderAvailability.FAILURE,
        executionLocation = GroundingExecutionLocation.LOCAL,
        latencyMillis = (System.nanoTime() - started) / 1_000_000,
        metadata = buildMap {
            put("status", "provider_failure")
            put("diagnostic", diagnostic.take(80))
            put("cropPngEncodingTimeMs", imageEncodingMillis?.toString().orStatus("NOT_RECORDED"))
        }
    )

    private fun groundingPrompt(target: String) =
        """
        Your task is to identify the precise point (x, y) of the requested GUI target in the screenshot.
        Return exactly one point as (x, y), with each coordinate in the range [0, 1000).
        Do not return an explanation, confidence, or any other text.
        Description: $target
        Answer:
        """.trimIndent()

    companion object {
        const val MODEL_COORDINATE_SCALE = 1000.0
        const val MINIMUM_AVAILABLE_MEMORY_BYTES = 3_629_247_837L
        const val MAX_RECORDED_OUTPUT_CHARS = 4096
        private val POINT_LINE = Regex(
            """^\s*\(\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*,\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*\)\s*$"""
        )

        fun parseModelPoint(output: String): GroundingPoint? {
            val matchingLines = output.lineSequence().mapNotNull { line ->
                POINT_LINE.matchEntire(line)?.groupValues?.drop(1)?.let { values ->
                    val x = values[0].toDoubleOrNull()
                    val y = values[1].toDoubleOrNull()
                    if (x == null || y == null) null else GroundingPoint(x, y)
                }
            }.toList()
            if (matchingLines.size != 1) return null
            val point = matchingLines.single()
            if (!point.x.isFinite() || !point.y.isFinite()) return null
            if (point.x < 0.0 || point.y < 0.0 ||
                point.x >= MODEL_COORDINATE_SCALE || point.y >= MODEL_COORDINATE_SCALE
            ) return null
            return point
        }
    }
}

private object LlamaCppMtmdCommandRunner : LlamaCppCommandRunner {
    private const val MAX_OUTPUT_TAIL_BYTES = 16 * 1024L
    override val requiresHostMemoryGuard = true

    override suspend fun run(
        configuration: LlamaCppGroundingConfiguration,
        imagePath: Path,
        promptPath: Path
    ): LlamaCppProcessResult = withContext(Dispatchers.IO) {
        val outputPath = Files.createTempFile(imagePath.parent, "llama-output-", ".txt")
        val command = listOf(
            configuration.executablePath!!.toString(),
            "--model", configuration.modelPath!!.toString(),
            "--mmproj", configuration.mmprojPath!!.toString(),
            "--image", imagePath.toString(),
            "--image-min-tokens", "1024",
            "--prompt", Files.readString(promptPath),
            "--ctx-size", "2048",
            "--predict", "64",
            "--temp", "0",
            "--device", "none",
            "--mmproj-device", "none",
            "--no-warmup",
            "--perf"
        )
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(outputPath.toFile())
                .start()
        } catch (e: IOException) {
            Files.deleteIfExists(outputPath)
            throw e
        }
        try {
            process.outputStream.close()
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                currentCoroutineContext().ensureActive()
            }
            val output = readOutputTail(outputPath)
            LlamaCppProcessResult(
                exitCode = process.exitValue(),
                output = output,
                modelLoadMillis = parseMillis(output, "load time"),
                imageEncodingMillis = parseImageEncodingMillis(output),
                generationMillis = parseMillis(output, "eval time")
            )
        } catch (e: CancellationException) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            throw e
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            Files.deleteIfExists(outputPath)
        }
    }

    private fun parseMillis(output: String, timingName: String): Long? =
        Regex("""(?im)^.*\b${Regex.escape(timingName)}\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*ms.*$""")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?.toLong()

    private fun parseImageEncodingMillis(output: String): Long? =
        Regex("""(?im)^.*(?:image|encode)[^\r\n]*time\s*=\s*([0-9]+(?:\.[0-9]+)?)\s*ms.*$""")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?.toLong()

    private fun readOutputTail(path: Path): String {
        val size = Files.size(path)
        val start = (size - MAX_OUTPUT_TAIL_BYTES).coerceAtLeast(0)
        return java.nio.channels.FileChannel.open(path).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate((size - start).toInt())
            channel.position(start)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            buffer.flip()
            java.nio.charset.StandardCharsets.UTF_8.decode(buffer).toString()
        }
    }

}

private fun String?.orStatus(fallback: String): String = this ?: fallback

fun defaultVisualGroundingProvider(): VisualGroundingProvider {
    val configuration = LlamaCppGroundingConfiguration.fromSystemProperties()
    return if (configuration.enabled) {
        LlamaCppVisualGroundingProvider(configuration)
    } else {
        NotConfiguredVisualGroundingProvider()
    }
}
