package tutor

import chat.ApiKeyConfig
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Multimodal Screenshot Analyzer boundary powered by Groq Cloud Vision (e.g. qwen/qwen3.8-27b).
 * Sends the learner-confirmed screenshot bytes along with active tutorial context to Groq,
 * and parses structured visual observations, detected UI elements, and uncertainty notes
 * using Groq's high-speed inference engine.
 */
class GroqMultimodalScreenshotAnalyzer(
    private val apiKeyProvider: () -> String = { ApiKeyConfig.getGroqApiKey() },
    val model: String = "qwen/qwen3.8-27b",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
    val allowOfflineStagingWhenUnconfigured: Boolean = true
) : ScreenshotAnalysisBoundary {

    override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult = withContext(Dispatchers.IO) {
        val imageFile = File(context.imagePath)

        // 1. Validate screenshot file existence and size
        if (!imageFile.exists() || !imageFile.isFile) {
            return@withContext ScreenshotAnalysisSubmissionResult.Rejected("Screenshot file not found at path: ${imageFile.name}")
        }
        val fileLength = imageFile.length()
        if (fileLength <= 0) {
            return@withContext ScreenshotAnalysisSubmissionResult.Rejected("Screenshot file is empty (0 bytes)")
        }
        if (fileLength > 20 * 1024 * 1024) {
            return@withContext ScreenshotAnalysisSubmissionResult.Rejected("Screenshot file exceeds 20MB limit ($fileLength bytes)")
        }

        // 2. Validate image format and dimensions
        val mimeType = detectMimeType(imageFile)
        if (mimeType == null) {
            return@withContext ScreenshotAnalysisSubmissionResult.Rejected("Unsupported image format. Expected PNG or JPEG.")
        }

        try {
            val bufferedImage = ImageIO.read(imageFile)
            if (bufferedImage == null || bufferedImage.width <= 0 || bufferedImage.height <= 0) {
                return@withContext ScreenshotAnalysisSubmissionResult.Rejected("Screenshot image could not be decoded or has invalid dimensions.")
            }
        } catch (ex: Exception) {
            return@withContext ScreenshotAnalysisSubmissionResult.Rejected("Screenshot image decoding failed: ${ex.message}")
        }

        // 3. Validate API configuration
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank() || apiKey == "MOCK_KEY") {
            if (allowOfflineStagingWhenUnconfigured) {
                return@withContext ScreenshotAnalysisSubmissionResult.StagedForPhase9(
                    context = context,
                    statusMessage = "Screenshot verified for Step ${context.stepIndex + 1} ('${context.stepTitle}'). Set GROQ_API_KEY to activate ultra-fast Groq Cloud visual analysis."
                )
            } else {
                return@withContext ScreenshotAnalysisSubmissionResult.Rejected(
                    reason = "Groq API key is not configured. Set GROQ_API_KEY in your environment, .env file, or Chatbot Settings to enable visual analysis.",
                    isConfigurationError = true
                )
            }
        }

        // 4. Read raw image bytes and encode to Base64
        val imageBytes = try {
            imageFile.readBytes()
        } catch (ex: Exception) {
            return@withContext ScreenshotAnalysisSubmissionResult.Rejected("Could not read screenshot image bytes: ${ex.message}")
        }
        val base64Data = Base64.getEncoder().encodeToString(imageBytes)

        // 5. Construct prompt
        val promptText = buildPrompt(context)

        // 6. Execute Groq HTTP request
        val started = System.currentTimeMillis()
        try {
            val endpoint = "https://api.groq.com/openai/v1/chat/completions"
            val requestBody = buildRequestBody(promptText, mimeType, base64Data)

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("User-Agent", "AIVisualTutor/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build()

            val httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            val latency = System.currentTimeMillis() - started

            val statusCode = httpResponse.statusCode()
            val responseBody = httpResponse.body() ?: ""

            when (statusCode) {
                in 200..299 -> {
                    val messageContent = MultimodalAnalysisJsonParser.extractGroqMessageContent(responseBody)
                    val analysisResult = MultimodalAnalysisJsonParser.parseJsonAnalysis(messageContent, model, latency)
                    ScreenshotAnalysisSubmissionResult.Success(
                        context = context,
                        analysisResult = analysisResult,
                        statusMessage = analysisResult.summary
                    )
                }
                400 -> ScreenshotAnalysisSubmissionResult.Rejected("Groq API rejected request (HTTP 400): ${sanitizeError(responseBody)}")
                401, 403 -> ScreenshotAnalysisSubmissionResult.Rejected("Groq API authentication failed (HTTP $statusCode). Verify your GROQ_API_KEY.", isConfigurationError = true)
                429 -> ScreenshotAnalysisSubmissionResult.Rejected("Groq API rate limit exceeded (HTTP 429). Please retry in a few moments.")
                in 500..599 -> ScreenshotAnalysisSubmissionResult.Rejected("Groq API server error (HTTP $statusCode). Please retry shortly.")
                else -> ScreenshotAnalysisSubmissionResult.Rejected("Groq API returned unexpected status HTTP $statusCode: ${sanitizeError(responseBody)}")
            }
        } catch (timeout: HttpTimeoutException) {
            ScreenshotAnalysisSubmissionResult.Rejected("Groq API request timed out after 30 seconds.")
        } catch (ex: Exception) {
            val msg = ex.message ?: ex.javaClass.simpleName
            ScreenshotAnalysisSubmissionResult.Rejected("Failed to connect to Groq API: $msg")
        }
    }

    private fun detectMimeType(file: File): String? {
        val ext = file.extension.lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> {
                val header = runCatching { file.inputStream().use { it.readNBytes(8) } }.getOrNull() ?: return null
                if (header.size >= 8 && header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) {
                    "image/png"
                } else if (header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                    "image/jpeg"
                } else {
                    null
                }
            }
        }
    }

    private fun buildPrompt(context: ScreenshotHelpContext): String {
        return buildString {
            appendLine("You are an expert interactive GUI visual tutor analyzing a screenshot of Blender for an active tutorial.")
            appendLine("Learner Goal: \"${context.userGoal}\"")
            appendLine("Active Tutorial Step: Step ${context.stepIndex + 1} - \"${context.stepTitle}\"")
            appendLine("Step Instruction: \"${context.stepInstruction}\"")
            if (!context.expectedOutcome.isNullOrBlank()) {
                appendLine("Expected Outcome: \"${context.expectedOutcome}\"")
            }
            if (!context.latestObservedStateSummary.isNullOrBlank()) {
                appendLine("Telemetry Context: \"${context.latestObservedStateSummary}\"")
            }
            appendLine()
            appendLine("Instructions:")
            appendLine("Analyze the supplied screenshot of the Blender workspace in the context of the learner's current goal and active lesson step.")
            appendLine("Describe only what is visually supported by the screenshot. Identify relevant visible Blender interface elements (modes, panels, buttons) and 3D objects.")
            appendLine("Separate direct observations from inferences. State what cannot be determined or is uncertain.")
            appendLine("If the image is ambiguous, incomplete, or insufficient to assess the step, state so explicitly in uncertaintyNotes.")
            appendLine("Do not claim that an operation succeeded unless the screenshot provides adequate visual evidence.")
            appendLine("Do not mark steps complete. Do not invent UI elements or objects not visible in the screenshot.")
            appendLine()
            appendLine("Output your analysis as a single JSON object strictly matching this schema:")
            appendLine("{")
            appendLine("  \"summary\": \"Concise summary of what is visible on screen relevant to the task\",")
            appendLine("  \"observations\": [")
            appendLine("    {\"description\": \"Observation text\", \"evidence\": \"Visual detail supporting this\", \"confidence\": 0.9}")
            appendLine("  ],")
            appendLine("  \"relevantUiElements\": [")
            appendLine("    {\"name\": \"Element name (e.g. Mode Selector, 3D Viewport, Transform Header, Object)\", \"visible\": true, \"evidence\": \"Where/how it appears\"}")
            appendLine("  ],")
            appendLine("  \"uncertaintyNotes\": [")
            appendLine("    \"Notes on what cannot be verified visually or remains ambiguous\"")
            appendLine("  ],")
            appendLine("  \"suggestedNextStepEvidence\": \"Visible indication of what is needed or whether next step is ready\",")
            appendLine("  \"confidence\": 0.85")
            appendLine("}")
        }
    }

    private fun buildRequestBody(promptText: String, mimeType: String, base64Data: String): String {
        val escapedPrompt = escapeJson(promptText)
        val dataUri = "data:$mimeType;base64,$base64Data"
        return """
        {
            "model": "$model",
            "messages": [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": $escapedPrompt
                        },
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": "$dataUri"
                            }
                        }
                    ]
                }
            ],
            "response_format": { "type": "json_object" },
            "temperature": 0.2
        }
        """.trimIndent()
    }

    private fun escapeJson(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\u000C", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun sanitizeError(body: String): String {
        val msgMarker = "\"message\":"
        val idx = body.indexOf(msgMarker)
        if (idx != -1) {
            val start = body.indexOf('"', idx + msgMarker.length)
            if (start != -1) {
                val end = body.indexOf('"', start + 1)
                if (end != -1) {
                    return body.substring(start + 1, end).take(200)
                }
            }
        }
        return body.take(200)
    }
}
