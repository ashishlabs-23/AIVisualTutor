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
 * Multimodal Screenshot Analyzer boundary powered by Google Gemini (e.g. gemini-1.5-flash).
 * Sends the learner-confirmed screenshot bytes along with active tutoring context to Gemini,
 * and parses structured visual observations, detected UI elements, and uncertainty notes.
 */
class GeminiMultimodalScreenshotAnalyzer(
    private val apiKeyProvider: () -> String = { ApiKeyConfig.getGeminiApiKey() },
    val model: String = "gemini-1.5-flash",
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
                    statusMessage = "Screenshot verified for Step ${context.stepIndex + 1} ('${context.stepTitle}'). Set GEMINI_API_KEY to activate cloud multimodal analysis (currently staged for Phase 9)."
                )
            } else {
                return@withContext ScreenshotAnalysisSubmissionResult.Rejected(
                    reason = "Google Gemini API key is not configured. Set GEMINI_API_KEY in your environment, .env file, or Chatbot Settings to enable visual analysis.",
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

        // 5. Construct multimodal prompt
        val promptText = buildPrompt(context)

        // 6. Execute HTTP request
        val started = System.currentTimeMillis()
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
            val requestBody = buildRequestBody(promptText, mimeType, base64Data)

            val httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build()

            val httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            val latency = System.currentTimeMillis() - started

            val statusCode = httpResponse.statusCode()
            val responseBody = httpResponse.body() ?: ""

            when (statusCode) {
                in 200..299 -> {
                    val analysisResult = parseGeminiResponse(responseBody, model, latency)
                    ScreenshotAnalysisSubmissionResult.Success(
                        context = context,
                        analysisResult = analysisResult,
                        statusMessage = analysisResult.summary
                    )
                }
                400 -> ScreenshotAnalysisSubmissionResult.Rejected("Gemini API rejected request (HTTP 400): ${sanitizeError(responseBody)}")
                401, 403 -> ScreenshotAnalysisSubmissionResult.Rejected("Gemini API authentication failed (HTTP $statusCode). Verify your GEMINI_API_KEY.", isConfigurationError = true)
                429 -> ScreenshotAnalysisSubmissionResult.Rejected("Gemini API rate limit exceeded (HTTP 429). Please retry in a few moments.")
                in 500..599 -> ScreenshotAnalysisSubmissionResult.Rejected("Gemini API server error (HTTP $statusCode). Please retry shortly.")
                else -> ScreenshotAnalysisSubmissionResult.Rejected("Gemini API returned unexpected status HTTP $statusCode: ${sanitizeError(responseBody)}")
            }
        } catch (timeout: HttpTimeoutException) {
            ScreenshotAnalysisSubmissionResult.Rejected("Gemini API request timed out after 30 seconds.")
        } catch (ex: Exception) {
            val msg = ex.message ?: ex.javaClass.simpleName
            ScreenshotAnalysisSubmissionResult.Rejected("Failed to connect to Gemini API: $msg")
        }
    }

    private fun detectMimeType(file: File): String? {
        val ext = file.extension.lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> {
                // Header magic bytes check
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
        return """
        {
            "contents": [
                {
                    "role": "user",
                    "parts": [
                        {"text": $escapedPrompt},
                        {"inlineData": {"mimeType": "$mimeType", "data": "$base64Data"}}
                    ]
                }
            ],
            "generationConfig": {
                "temperature": 0.2,
                "responseMimeType": "application/json"
            }
        }
        """.trimIndent()
    }

    internal fun parseGeminiResponse(responseBody: String, modelId: String, latency: Long): ScreenshotAnalysisResult {
        val textContent = extractCandidatesText(responseBody)
        return parseJsonAnalysis(textContent, modelId, latency)
    }

    private fun extractCandidatesText(responseBody: String): String {
        val textMarker = "\"text\":"
        val idx = responseBody.indexOf(textMarker)
        if (idx != -1) {
            val start = responseBody.indexOf('"', idx + textMarker.length)
            if (start != -1) {
                val end = findClosingQuote(responseBody, start + 1)
                if (end != -1) {
                    val raw = responseBody.substring(start + 1, end)
                    return unescapeJsonString(raw)
                }
            }
        }
        return responseBody
    }

    private fun unescapeJsonString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c)
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    internal fun parseJsonAnalysis(jsonText: String, modelId: String, latency: Long): ScreenshotAnalysisResult {
        val summary = extractRootStringField(jsonText, "summary") ?: extractStringField(jsonText, "summary") ?: "Visual analysis of Blender workspace completed."
        val suggestedNextStep = extractRootStringField(jsonText, "suggestedNextStepEvidence") ?: extractStringField(jsonText, "suggestedNextStepEvidence")
        val confidence = extractRootDoubleField(jsonText, "confidence")

        val observations = parseObservations(jsonText)
        val uiElements = parseUiElements(jsonText)
        val uncertaintyNotes = parseStringArray(jsonText, "uncertaintyNotes")

        return ScreenshotAnalysisResult(
            summary = summary,
            observations = observations,
            relevantUiElements = uiElements,
            uncertaintyNotes = uncertaintyNotes,
            suggestedNextStepEvidence = suggestedNextStep,
            confidence = confidence,
            modelIdentifier = modelId,
            latencyMillis = latency,
            rawModelResponse = jsonText.take(1500)
        )
    }

    private fun parseObservations(json: String): List<VisualObservation> {
        val list = mutableListOf<VisualObservation>()
        val marker = "\"observations\""
        val startIdx = json.indexOf(marker)
        if (startIdx == -1) return list

        val arrayStart = json.indexOf('[', startIdx)
        if (arrayStart == -1) return list
        val arrayEnd = findClosingBracket(json, arrayStart)
        if (arrayEnd == -1) return list

        val arrayContent = json.substring(arrayStart + 1, arrayEnd)
        val objectRegex = Regex("""\{([^{}]+)\}""")
        for (match in objectRegex.findAll(arrayContent)) {
            val obj = match.groupValues[1]
            val desc = extractStringField("{$obj}", "description") ?: continue
            val evidence = extractStringField("{$obj}", "evidence")
            val conf = extractDoubleField("{$obj}", "confidence")
            list.add(VisualObservation(description = desc, evidence = evidence, confidence = conf))
        }
        return list
    }

    private fun parseUiElements(json: String): List<VisibleUiElement> {
        val list = mutableListOf<VisibleUiElement>()
        val marker = "\"relevantUiElements\""
        val startIdx = json.indexOf(marker)
        if (startIdx == -1) return list

        val arrayStart = json.indexOf('[', startIdx)
        if (arrayStart == -1) return list
        val arrayEnd = findClosingBracket(json, arrayStart)
        if (arrayEnd == -1) return list

        val arrayContent = json.substring(arrayStart + 1, arrayEnd)
        val objectRegex = Regex("""\{([^{}]+)\}""")
        for (match in objectRegex.findAll(arrayContent)) {
            val obj = match.groupValues[1]
            val name = extractStringField("{$obj}", "name") ?: continue
            val visible = extractBooleanField("{$obj}", "visible") ?: true
            val evidence = extractStringField("{$obj}", "evidence")
            list.add(VisibleUiElement(name = name, visible = visible, evidence = evidence))
        }
        return list
    }

    private fun parseStringArray(json: String, fieldName: String): List<String> {
        val list = mutableListOf<String>()
        val marker = "\"$fieldName\""
        val startIdx = json.indexOf(marker)
        if (startIdx == -1) return list

        val arrayStart = json.indexOf('[', startIdx)
        if (arrayStart == -1) return list
        val arrayEnd = findClosingBracket(json, arrayStart)
        if (arrayEnd == -1) return list

        val arrayContent = json.substring(arrayStart + 1, arrayEnd)
        val itemRegex = Regex(""""((?:[^"\\\\]|\\\\.)*)"""")
        for (match in itemRegex.findAll(arrayContent)) {
            val raw = match.groupValues[1]
            val clean = raw.replace("\\\"", "\"").replace("\\\\", "\\").trim()
            if (clean.isNotEmpty()) {
                list.add(clean)
            }
        }
        return list
    }

    private fun extractRootStringField(json: String, fieldName: String): String? {
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var escape = false

        for (i in 0 until json.length - fieldName.length - 2) {
            val c = json[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                if (inString && braceDepth == 1 && bracketDepth == 0) {
                    if (json.startsWith("\"$fieldName\"", i)) {
                        val colonIdx = json.indexOf(':', i + fieldName.length + 2)
                        if (colonIdx != -1) {
                            val sub = json.substring(colonIdx + 1).trimStart()
                            if (sub.startsWith("\"")) {
                                val endQuote = findClosingQuote(sub, 1)
                                if (endQuote != -1) {
                                    val raw = sub.substring(1, endQuote)
                                    return raw.replace("\\\"", "\"").replace("\\\\", "\\").trim()
                                }
                            }
                        }
                    }
                }
                continue
            }
            if (!inString) {
                when (c) {
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                    '[' -> bracketDepth++
                    ']' -> bracketDepth--
                }
            }
        }
        return null
    }

    private fun extractRootDoubleField(json: String, fieldName: String): Double? {
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var escape = false

        for (i in 0 until json.length - fieldName.length - 2) {
            val c = json[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                if (inString && braceDepth == 1 && bracketDepth == 0) {
                    if (json.startsWith("\"$fieldName\"", i)) {
                        val colonIdx = json.indexOf(':', i + fieldName.length + 2)
                        if (colonIdx != -1) {
                            val sub = json.substring(colonIdx + 1).trimStart()
                            val regex = Regex("""^([0-9]+(?:\.[0-9]+)?)""")
                            val match = regex.find(sub)
                            if (match != null) {
                                return match.groupValues[1].toDoubleOrNull()
                            }
                        }
                    }
                }
                continue
            }
            if (!inString) {
                when (c) {
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                    '[' -> bracketDepth++
                    ']' -> bracketDepth--
                }
            }
        }
        return null
    }

    private fun extractStringField(json: String, fieldName: String): String? {
        val regex = Regex(""""$fieldName"\s*:\s*"((?:[^"\\\\]|\\\\.)*)"""")
        val match = regex.find(json) ?: return null
        return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").trim()
    }

    private fun extractDoubleField(json: String, fieldName: String): Double? {
        val regex = Regex(""""$fieldName"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")
        val match = regex.find(json) ?: return null
        return match.groupValues[1].toDoubleOrNull()
    }

    private fun extractBooleanField(json: String, fieldName: String): Boolean? {
        val regex = Regex(""""$fieldName"\s*:\s*(true|false)""", RegexOption.IGNORE_CASE)
        val match = regex.find(json) ?: return null
        return match.groupValues[1].toBoolean()
    }

    private fun findClosingQuote(str: String, startIdx: Int): Int {
        var i = startIdx
        while (i < str.length) {
            if (str[i] == '"') {
                var backslashCount = 0
                var j = i - 1
                while (j >= 0 && str[j] == '\\') {
                    backslashCount++
                    j--
                }
                if (backslashCount % 2 == 0) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun findClosingBracket(str: String, startIdx: Int): Int {
        var depth = 0
        for (i in startIdx until str.length) {
            if (str[i] == '[') depth++
            else if (str[i] == ']') {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
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

    private fun sanitizeError(raw: String): String {
        return raw.take(200).replace("\n", " ").trim()
    }
}
