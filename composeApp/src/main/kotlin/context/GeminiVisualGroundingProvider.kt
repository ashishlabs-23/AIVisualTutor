package context

import chat.ApiKeyConfig
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Cloud multimodal visual grounding provider powered by Google Gemini.
 * Maps Gemini object detection 0-1000 coordinates to NORMALIZED_CROP bounds.
 */
class GeminiVisualGroundingProvider(
    private val apiKeyProvider: () -> String = { ApiKeyConfig.getGeminiApiKey() },
    private val model: String = "gemini-1.5-flash",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : VisualGroundingProvider {

    constructor(apiKey: String, model: String = "gemini-1.5-flash") : this(
        apiKeyProvider = { apiKey },
        model = model
    )

    override val providerId: String = "gemini-multimodal-grounding"

    override val availability: GroundingProviderAvailability
        get() = if (apiKeyProvider().isNotBlank()) GroundingProviderAvailability.AVAILABLE else GroundingProviderAvailability.NOT_CONFIGURED

    override val availabilityDiagnostic: String?
        get() = if (apiKeyProvider().isNotBlank()) null else "GEMINI_API_KEY is not configured"

    override suspend fun ground(request: VisualGroundingRequest): VisualGroundingResult {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank() || apiKey == "MOCK_KEY") {
            return VisualGroundingResult(
                semantic = null,
                geometry = null,
                providerId = providerId,
                availability = GroundingProviderAvailability.NOT_CONFIGURED,
                executionLocation = GroundingExecutionLocation.REMOTE,
                metadata = mapOf("status" to "unconfigured_api_key")
            )
        }

        val target = request.targetDescription?.trim().orEmpty()
        if (target.isEmpty()) {
            return VisualGroundingResult(
                semantic = null,
                geometry = null,
                providerId = providerId,
                availability = GroundingProviderAvailability.AVAILABLE,
                executionLocation = GroundingExecutionLocation.REMOTE,
                metadata = mapOf("status" to "empty_target_description")
            )
        }

        val started = System.currentTimeMillis()
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val base64Image = encodeImageToBase64(request.screenshot)
                ?: return failureResult("failed_image_encoding")

            val prompt = """
            Locate the following GUI control or element in the screenshot: "$target".
            Output the bounding box as a JSON object with coordinates normalized to a 0-1000 scale:
            {"box_2d": [ymin, xmin, ymax, xmax], "label": "$target"}
            """.trimIndent()

            val requestBody = """
            {
                "contents": [
                    {
                        "role": "user",
                        "parts": [
                            {"text": ${escapeJson(prompt)}},
                            {"inlineData": {"mimeType": "image/jpeg", "data": "$base64Image"}}
                        ]
                    }
                ],
                "generationConfig": {
                    "temperature": 0.1,
                    "responseMimeType": "application/json"
                }
            }
            """.trimIndent()

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(25))
                .build()

            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            val latency = System.currentTimeMillis() - started

            if (response.statusCode() in 200..299) {
                parseGeminiBoundingBox(response.body(), target, latency)
            } else {
                failureResult("http_${response.statusCode()}", latency)
            }
        } catch (error: Exception) {
            failureResult(error.javaClass.simpleName, System.currentTimeMillis() - started)
        }
    }

    private fun parseGeminiBoundingBox(responseBody: String, target: String, latency: Long): VisualGroundingResult {
        // Find box_2d coordinates: [ymin, xmin, ymax, xmax]
        val regex = Regex("""\[\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\]""")
        val match = regex.find(responseBody)
        if (match != null) {
            val ymin = match.groupValues[1].toDoubleOrNull() ?: 0.0
            val xmin = match.groupValues[2].toDoubleOrNull() ?: 0.0
            val ymax = match.groupValues[3].toDoubleOrNull() ?: 1000.0
            val xmax = match.groupValues[4].toDoubleOrNull() ?: 1000.0

            // Convert 0-1000 to normalized 0.0-1.0
            val bounds = GroundingBounds(
                left = (xmin / 1000.0).coerceIn(0.0, 1.0),
                top = (ymin / 1000.0).coerceIn(0.0, 1.0),
                right = (xmax / 1000.0).coerceIn(0.0, 1.0),
                bottom = (ymax / 1000.0).coerceIn(0.0, 1.0)
            )

            val geometry = GroundingGeometricEvidence(
                bounds = bounds,
                coordinateSpace = VisualGroundingCoordinateSpace.NORMALIZED_CROP,
                confidence = 0.90f
            )

            val semantic = GroundingSemanticEvidence(
                target = target,
                confidence = 0.90f
            )

            return VisualGroundingResult(
                semantic = semantic,
                geometry = geometry,
                providerId = providerId,
                availability = GroundingProviderAvailability.AVAILABLE,
                executionLocation = GroundingExecutionLocation.REMOTE,
                latencyMillis = latency,
                metadata = mapOf("model" to model, "coordinateSpace" to "NORMALIZED_0_TO_1000")
            )
        }

        return VisualGroundingResult(
            semantic = null,
            geometry = null,
            providerId = providerId,
            availability = GroundingProviderAvailability.AVAILABLE,
            executionLocation = GroundingExecutionLocation.REMOTE,
            latencyMillis = latency,
            metadata = mapOf("status" to "box_not_parsed", "rawOutput" to responseBody.take(120))
        )
    }

    private fun failureResult(diagnostic: String, latency: Long = 0): VisualGroundingResult = VisualGroundingResult(
        semantic = null,
        geometry = null,
        providerId = providerId,
        availability = GroundingProviderAvailability.FAILURE,
        executionLocation = GroundingExecutionLocation.REMOTE,
        latencyMillis = latency,
        metadata = mapOf("status" to diagnostic)
    )

    private fun encodeImageToBase64(image: java.awt.image.BufferedImage): String? {
        return try {
            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "jpeg", baos)
            Base64.getEncoder().encodeToString(baos.toByteArray())
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeJson(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\""
}
