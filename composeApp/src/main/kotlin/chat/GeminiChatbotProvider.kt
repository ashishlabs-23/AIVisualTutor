package chat

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Replaceable Google Gemini Chatbot Provider.
 * Communicates directly with Google AI Gemini endpoint using standard Java HttpClient.
 * When API key is not configured, provides deterministic local guidance based on step and app context.
 */
class GeminiChatbotProvider(
    private val apiKeyProvider: () -> String = { ApiKeyConfig.getGeminiApiKey() },
    private val model: String = "gemini-1.5-flash",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : ChatbotProvider {

    constructor(apiKey: String, model: String = "gemini-1.5-flash") : this(
        apiKeyProvider = { apiKey },
        model = model
    )

    override suspend fun askTutor(context: ChatContext): ChatResponse {
        val key = apiKeyProvider().trim()
        if (key.isBlank() || key == "MOCK_KEY") {
            return generateOfflineGuidance(context)
        }

        return try {
            queryGeminiApi(context, key)
        } catch (error: Exception) {
            val fallback = generateOfflineGuidance(context)
            fallback.copy(
                explanation = "Cloud API notice: ${error.message ?: error.javaClass.simpleName}. Using offline tutor context."
            )
        }
    }

    private fun queryGeminiApi(context: ChatContext, key: String): ChatResponse {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"

        val promptBuilder = StringBuilder()
        promptBuilder.append("You are an expert interactive GUI tutor for ${context.applicationName}.\n")
        if (context.applicationMode != null) {
            promptBuilder.append("Current Application Mode: ${context.applicationMode}\n")
        }
        if (context.applicationStateSummary != null) {
            promptBuilder.append("Current Application State: ${context.applicationStateSummary}\n")
        }
        if (context.currentTaskTitle != null) {
            promptBuilder.append("Current Tutorial Step: Step ${context.currentStepNumber ?: 1} of ${context.totalSteps ?: 1} - '${context.currentTaskTitle}'\n")
        }
        if (context.activeInstruction != null) {
            promptBuilder.append("Current Instruction: '${context.activeInstruction}'\n")
        }
        promptBuilder.append("\nLearner's Question: ${context.question}\n")
        promptBuilder.append("Provide a clear, direct, and actionable answer. Highlight the exact menu, tool name, or shortcut to use.")

        val jsonParts = mutableListOf<String>()
        jsonParts.add("""{"text": ${escapeJson(promptBuilder.toString())}}""")

        if (context.includeVisualContext && context.screenshotCrop != null) {
            val base64Image = encodeImageToBase64(context.screenshotCrop)
            if (base64Image != null) {
                jsonParts.add("""{"inlineData": {"mimeType": "image/jpeg", "data": "$base64Image"}}""")
            }
        }

        val requestBody = """
        {
            "contents": [
                {
                    "role": "user",
                    "parts": [
                        ${jsonParts.joinToString(",\n")}
                    ]
                }
            ]
        }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(20))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() in 200..299) {
            val replyText = extractGeminiText(response.body())
            return ChatResponse(
                replyText = replyText,
                explanation = "Powered by Google Gemini ($model)",
                providerModel = model
            )
        } else {
            throw RuntimeException("Gemini API returned HTTP ${response.statusCode()}: ${response.body()}")
        }
    }

    private fun generateOfflineGuidance(context: ChatContext): ChatResponse {
        val q = context.question.lowercase()
        val app = context.applicationName
        val task = context.currentTaskTitle ?: "the current task"
        val state = context.applicationStateSummary ?: "standard state"

        val reply = when {
            q.contains("where") || q.contains("find") -> {
                "In $app for '$task', look at the highlighted interface region. Current instruction: ${context.activeInstruction ?: "Follow step directions"}."
            }
            q.contains("why") || q.contains("work") || q.contains("fail") -> {
                "In $app, ensure you are in the correct mode (${context.applicationMode ?: "Object/Edit mode"}). State: $state."
            }
            q.contains("difference") || q.contains("mode") -> {
                "In Blender, Object Mode is for transforming entire objects (position, scale). Edit Mode allows modifying individual vertices, edges, and faces."
            }
            q.contains("extrude") -> {
                "To extrude a face in Blender, enter Edit Mode (Tab), select a face, and press 'E' or select the Extrude tool from the left toolbar."
            }
            else -> {
                "For '$task' in $app: ${context.activeInstruction ?: "Check the highlighted control or menu"}."
            }
        }

        return ChatResponse(
            replyText = reply,
            explanation = "Offline GUI Knowledge Base (configure GEMINI_API_KEY for cloud model)",
            providerModel = "local-offline-tutor"
        )
    }

    private fun encodeImageToBase64(image: java.awt.image.BufferedImage): String? {
        return try {
            val baos = ByteArrayOutputStream()
            ImageIO.write(image, "jpeg", baos)
            Base64.getEncoder().encodeToString(baos.toByteArray())
        } catch (_: Exception) {
            null
        }
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

    private fun extractGeminiText(jsonBody: String): String {
        // Simple extraction of the first text candidate in response
        val textMarker = "\"text\":"
        val idx = jsonBody.indexOf(textMarker)
        if (idx != -1) {
            val start = jsonBody.indexOf('"', idx + textMarker.length)
            if (start != -1) {
                val end = findClosingQuote(jsonBody, start + 1)
                if (end != -1) {
                    val raw = jsonBody.substring(start + 1, end)
                    return raw
                        .replace("\\n", "\n")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                }
            }
        }
        return "Received response from Gemini."
    }

    private fun findClosingQuote(str: String, startIdx: Int): Int {
        var i = startIdx
        while (i < str.length) {
            if (str[i] == '"' && str[i - 1] != '\\') {
                return i
            }
            i++
        }
        return -1
    }
}
