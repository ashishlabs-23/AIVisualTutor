package context

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HttpVisualGroundingConfiguration(
    val enabled: Boolean = false,
    val endpoint: String? = null,
    val modelId: String = DEFAULT_MODEL_ID,
    val apiKey: String? = null,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val maxPayloadBytes: Long = DEFAULT_MAX_PAYLOAD_BYTES
) {
    init {
        require(timeoutMillis > 0) { "Visual-grounding timeout must be positive." }
        require(maxPayloadBytes > 0) { "Max payload bytes must be positive." }
    }

    companion object {
        const val ENABLE_PROPERTY = "aivt.visual.http.enabled"
        const val ENDPOINT_PROPERTY = "aivt.visual.endpoint"
        const val MODEL_PROPERTY = "aivt.visual.http.model"
        const val API_KEY_PROPERTY = "aivt.visual.apiKey"
        const val TIMEOUT_PROPERTY = "aivt.visual.http.timeoutMillis"
        const val MAX_PAYLOAD_PROPERTY = "aivt.visual.http.maxPayloadBytes"

        const val DEFAULT_MODEL_ID = "UGround-V1-2B"
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_MAX_PAYLOAD_BYTES = 15 * 1024 * 1024L // 15 MB

        fun fromSystemProperties(): HttpVisualGroundingConfiguration {
            fun resolve(prop: String, env: String): String? =
                System.getProperty(prop)?.takeIf(String::isNotBlank)
                    ?: System.getenv(env)?.takeIf(String::isNotBlank)

            val rawEndpoint = resolve(ENDPOINT_PROPERTY, "AIVT_VISUAL_ENDPOINT")?.trim()
            val endpoint = rawEndpoint?.let(::normalizeEndpoint)
            val explicitEnabled = resolve(ENABLE_PROPERTY, "AIVT_VISUAL_HTTP_ENABLED")
                ?.equals("true", ignoreCase = true)
            val fallbackEnabled = resolve("aivt.visual.enabled", "AIVT_VISUAL_ENABLED")
                ?.equals("true", ignoreCase = true) == true && !endpoint.isNullOrBlank()

            return HttpVisualGroundingConfiguration(
                enabled = explicitEnabled ?: fallbackEnabled,
                endpoint = endpoint,
                modelId = resolve(MODEL_PROPERTY, "AIVT_VISUAL_MODEL")?.trim() ?: DEFAULT_MODEL_ID,
                apiKey = resolve(API_KEY_PROPERTY, "AIVT_VISUAL_API_KEY")?.trim(),
                timeoutMillis = resolve(TIMEOUT_PROPERTY, "AIVT_VISUAL_TIMEOUT_MILLIS")?.let {
                    it.toLongOrNull() ?: throw IllegalArgumentException("$TIMEOUT_PROPERTY must be an integer.")
                } ?: DEFAULT_TIMEOUT_MILLIS,
                maxPayloadBytes = resolve(MAX_PAYLOAD_PROPERTY, "AIVT_VISUAL_MAX_PAYLOAD_BYTES")?.let {
                    it.toLongOrNull() ?: throw IllegalArgumentException("$MAX_PAYLOAD_PROPERTY must be an integer.")
                } ?: DEFAULT_MAX_PAYLOAD_BYTES
            )
        }

        fun normalizeEndpoint(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return trimmed
            val withoutSlash = trimmed.removeSuffix("/")
            val uri = try { URI.create(withoutSlash) } catch (_: Exception) { return trimmed }
            val path = uri.path.orEmpty()
            return when {
                path.endsWith("/chat/completions") -> withoutSlash
                path.endsWith("/v1") -> "$withoutSlash/chat/completions"
                path.isEmpty() || path == "/" -> "$withoutSlash/v1/chat/completions"
                else -> withoutSlash
            }
        }
    }
}

data class HttpResponsePayload(
    val statusCode: Int,
    val body: String
)

fun interface HttpTransport {
    suspend fun send(
        uri: URI,
        requestJson: String,
        timeoutMillis: Long,
        apiKey: String?
    ): HttpResponsePayload
}

class DefaultHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : HttpTransport {
    override suspend fun send(
        uri: URI,
        requestJson: String,
        timeoutMillis: Long,
        apiKey: String?
    ): HttpResponsePayload = withContext(Dispatchers.IO) {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMillis(timeoutMillis))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestJson))

        apiKey?.takeIf(String::isNotBlank)?.let { key ->
            requestBuilder.header("Authorization", "Bearer $key")
        }

        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
        HttpResponsePayload(response.statusCode(), response.body())
    }
}

/** Executes visual grounding through an OpenAI-compatible multimodal endpoint (vLLM, llama-server, or remote service). */
class HttpVisualGroundingProvider(
    private val configuration: HttpVisualGroundingConfiguration = HttpVisualGroundingConfiguration.fromSystemProperties(),
    private val transport: HttpTransport = DefaultHttpTransport()
) : VisualGroundingProvider {

    override val providerId: String = "http-uground-v1-2b"

    override val availability: GroundingProviderAvailability get() = preflight().availability
    override val availabilityDiagnostic: String? get() = preflight().diagnostic

    override fun preflight(): VisualGroundingPreflight {
        val metadata = mapOf(
            "provider" to providerId,
            "model" to configuration.modelId,
            "endpoint" to (configuration.endpoint ?: "NOT_CONFIGURED"),
            "configured" to configuration.enabled.toString(),
            "timeoutMillis" to configuration.timeoutMillis.toString(),
            "hasApiKey" to (configuration.apiKey != null).toString(),
            "inferenceBackend" to "HTTP_SERVICE"
        )
        if (!configuration.enabled) {
            return VisualGroundingPreflight(
                GroundingProviderAvailability.NOT_CONFIGURED,
                "visual_http_provider_not_configured",
                metadata + ("failureCategory" to "NOT_CONFIGURED")
            )
        }
        val endpointStr = configuration.endpoint
        if (endpointStr.isNullOrBlank()) {
            return VisualGroundingPreflight(
                GroundingProviderAvailability.NOT_CONFIGURED,
                "visual_endpoint_not_configured",
                metadata + ("failureCategory" to "NOT_CONFIGURED")
            )
        }
        val uri = try {
            URI.create(endpointStr)
        } catch (_: Exception) {
            null
        }
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host == null) {
            return VisualGroundingPreflight(
                GroundingProviderAvailability.UNAVAILABLE,
                "invalid_endpoint_uri: $endpointStr",
                metadata + ("failureCategory" to "PROVIDER_UNAVAILABLE")
            )
        }
        return VisualGroundingPreflight(
            GroundingProviderAvailability.AVAILABLE,
            metadata = metadata + ("failureCategory" to "SUCCESS")
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
                    "target_description_missing",
                    preflight.metadata + ("failureCategory" to "NOT_CONFIGURED")
                ),
                started
            )

        val uri = URI.create(configuration.endpoint!!)
        val imageEncodingStarted = System.nanoTime()
        val pngBytes = try {
            val output = ByteArrayOutputStream()
            if (!ImageIO.write(request.screenshot, "png", output)) {
                return failureResult("screenshot_png_encoding_failed", started, failureCategory = "IMAGE_INPUT_FAILED")
            }
            output.toByteArray()
        } catch (e: Exception) {
            return failureResult(e.javaClass.simpleName, started, failureCategory = "IMAGE_INPUT_FAILED")
        }
        val imageEncodingMillis = (System.nanoTime() - imageEncodingStarted) / 1_000_000

        if (pngBytes.size > configuration.maxPayloadBytes) {
            return failureResult(
                "screenshot_payload_exceeds_maximum_bound: ${pngBytes.size} bytes",
                started,
                imageEncodingMillis,
                failureCategory = "IMAGE_INPUT_FAILED"
            )
        }

        val base64Image = Base64.getEncoder().encodeToString(pngBytes)
        val promptText = groundingPrompt(target)
        val requestJson = buildOpenAiVisionPayload(configuration.modelId, base64Image, promptText)

        val responsePayload = try {
            transport.send(uri, requestJson, configuration.timeoutMillis, configuration.apiKey)
        } catch (e: HttpTimeoutException) {
            return failureResult("request_timeout", started, imageEncodingMillis, failureCategory = "INFERENCE_TIMEOUT")
        } catch (e: IOException) {
            return failureResult("connection_failed: ${e.message}", started, imageEncodingMillis, failureCategory = "PROVIDER_UNAVAILABLE")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failureResult(e.javaClass.simpleName, started, imageEncodingMillis, failureCategory = "PROVIDER_UNAVAILABLE")
        }

        val latencyMillis = (System.nanoTime() - started) / 1_000_000
        val commonMetadata = mapOf(
            "model" to configuration.modelId,
            "endpoint" to configuration.endpoint!!,
            "cropPngEncodingTimeMs" to imageEncodingMillis.toString(),
            "httpStatusCode" to responsePayload.statusCode.toString(),
            "rawModelOutput" to responsePayload.body.takeLast(4096),
            "coordinateFormat" to "MODEL_0_TO_1000_NORMALIZED_TO_NORMALIZED_CROP"
        )

        if (responsePayload.statusCode !in 200..299) {
            return VisualGroundingResult(
                semantic = null,
                geometry = null,
                providerId = providerId,
                availability = GroundingProviderAvailability.FAILURE,
                executionLocation = GroundingExecutionLocation.REMOTE,
                latencyMillis = latencyMillis,
                metadata = commonMetadata + mapOf(
                    "status" to "http_error_${responsePayload.statusCode}",
                    "failureCategory" to "PROCESS_EXIT_NONZERO"
                )
            )
        }

        val content = extractContentFromResponse(responsePayload.body)
        if (content.isBlank()) {
            return VisualGroundingResult(
                semantic = null,
                geometry = null,
                providerId = providerId,
                availability = GroundingProviderAvailability.FAILURE,
                executionLocation = GroundingExecutionLocation.REMOTE,
                latencyMillis = latencyMillis,
                metadata = commonMetadata + mapOf(
                    "status" to "empty_model_output",
                    "failureCategory" to "EMPTY_MODEL_OUTPUT"
                )
            )
        }

        val point = LlamaCppVisualGroundingProvider.parseModelPoint(content)
            ?: return VisualGroundingResult(
                semantic = null,
                geometry = null,
                providerId = providerId,
                availability = GroundingProviderAvailability.FAILURE,
                executionLocation = GroundingExecutionLocation.REMOTE,
                latencyMillis = latencyMillis,
                metadata = commonMetadata + mapOf(
                    "status" to "invalid_coordinate_output",
                    "failureCategory" to "OUTPUT_PARSE_FAILED"
                )
            )

        val normalized = GroundingPoint(
            point.x / LlamaCppVisualGroundingProvider.MODEL_COORDINATE_SCALE,
            point.y / LlamaCppVisualGroundingProvider.MODEL_COORDINATE_SCALE
        )
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
            executionLocation = GroundingExecutionLocation.REMOTE,
            latencyMillis = latencyMillis,
            metadata = commonMetadata + mapOf(
                "status" to "point_returned",
                "failureCategory" to "SUCCESS",
                "normalizedPoint" to "${normalized.x},${normalized.y}",
                "pixelPoint" to "${pixel.x},${pixel.y}",
                "cropWidthPx" to request.screenshot.width.toString(),
                "cropHeightPx" to request.screenshot.height.toString()
            )
        )
    }

    private fun statusResult(preflight: VisualGroundingPreflight, started: Long) = VisualGroundingResult(
        semantic = null,
        geometry = null,
        providerId = providerId,
        availability = preflight.availability,
        executionLocation = GroundingExecutionLocation.REMOTE,
        latencyMillis = (System.nanoTime() - started) / 1_000_000,
        metadata = preflight.metadata + mapOf(
            "status" to preflight.availability.name,
            "diagnostic" to preflight.diagnostic.orStatus(preflight.availability.name),
            "failureCategory" to (preflight.metadata["failureCategory"] ?: preflight.availability.name)
        )
    )

    private fun failureResult(
        diagnostic: String,
        started: Long,
        imageEncodingMillis: Long? = null,
        failureCategory: String = "PROVIDER_FAILURE"
    ) = VisualGroundingResult(
        semantic = null,
        geometry = null,
        providerId = providerId,
        availability = GroundingProviderAvailability.FAILURE,
        executionLocation = GroundingExecutionLocation.REMOTE,
        latencyMillis = (System.nanoTime() - started) / 1_000_000,
        metadata = buildMap {
            put("status", "provider_failure")
            put("diagnostic", diagnostic.take(80))
            put("failureCategory", failureCategory)
            put("cropPngEncodingTimeMs", imageEncodingMillis?.toString().orStatus("NOT_RECORDED"))
        }
    )

    companion object {
        fun groundingPrompt(target: String): String =
            """
            Your task is to identify the precise point (x, y) of the requested GUI target in the screenshot.
            Return exactly one point as (x, y), with each coordinate in the range [0, 1000).
            Do not return an explanation, confidence, or any other text.
            Description: $target
            Answer:
            """.trimIndent()

        fun buildOpenAiVisionPayload(model: String, base64Png: String, prompt: String): String {
            val escapedPrompt = escapeJsonString(prompt)
            val escapedModel = escapeJsonString(model)
            return """{"model":"$escapedModel","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$base64Png"}},{"type":"text","text":"$escapedPrompt"}]}],"temperature":0.0,"max_tokens":64}"""
        }

        fun extractContentFromResponse(json: String): String {
            val contentRegex = Regex("""(?s)"(?:content|text|response)"\s*:\s*"((?:\\.|[^"\\])*)"""")
            val match = contentRegex.find(json)
            if (match != null) {
                return unescapeJsonString(match.groupValues[1]).trim()
            }
            return json.trim()
        }

        private fun escapeJsonString(value: String): String = buildString {
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }

        private fun unescapeJsonString(value: String): String = buildString {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c != '\\' || i >= value.length) {
                    append(c)
                } else {
                    when (val escaped = value[i++]) {
                        '"' -> append('"')
                        '\\' -> append('\\')
                        '/' -> append('/')
                        'b' -> append('\b')
                        'f' -> append('\u000c')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'u' -> {
                            if (i + 4 <= value.length) {
                                val hex = value.substring(i, i + 4)
                                append(hex.toIntOrNull(16)?.toChar() ?: '\uFFFD')
                                i += 4
                            } else {
                                append("\\u")
                            }
                        }
                        else -> {
                            append('\\')
                            append(escaped)
                        }
                    }
                }
            }
        }
    }
}

private fun String?.orStatus(fallback: String): String = this ?: fallback
