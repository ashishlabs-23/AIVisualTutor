package chat

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-speed Groq Chatbot Provider supporting OpenAI-compatible chat completions.
 * Uses `openai/gpt-oss-120b` for fast, detailed instruction reasoning and
 * `qwen/qwen3.8-27b` for multimodal vision requests with attached screen crops.
 */
class GroqChatbotProvider(
    private val apiKeyProvider: () -> String = { ApiKeyConfig.getGroqApiKey() },
    private val textModel: String = "openai/gpt-oss-120b",
    private val visionModel: String = "qwen/qwen3.8-27b",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
) : ChatbotProvider {

    constructor(apiKey: String) : this(
        apiKeyProvider = { apiKey }
    )

    override suspend fun askTutor(context: ChatContext): ChatResponse {
        val key = apiKeyProvider().trim()
        if (key.isBlank() || key == "MOCK_KEY") {
            return generateOfflineGuidance(context)
        }

        return try {
            withContext(Dispatchers.IO) {
                queryGroqApi(context, key)
            }
        } catch (error: Exception) {
            val fallback = generateOfflineGuidance(context)
            fallback.copy(
                explanation = "Groq API notice: ${error.message ?: error.javaClass.simpleName}. Using offline tutor context."
            )
        }
    }

    private fun queryGroqApi(context: ChatContext, key: String): ChatResponse {
        val endpoint = "https://api.groq.com/openai/v1/chat/completions"
        val hasVisualContext = context.includeVisualContext && context.screenshotCrop != null
        val selectedModel = if (hasVisualContext) visionModel else textModel

        val systemPrompt = buildSystemPrompt(context)
        val userContent = buildUserContent(context, hasVisualContext)

        val messagesJson = StringBuilder()
        messagesJson.append("[")
        messagesJson.append("""{"role": "system", "content": ${escapeJson(systemPrompt)}}""")
        messagesJson.append(",")
        messagesJson.append("""{"role": "user", "content": $userContent}""")
        messagesJson.append("]")

        val requestBody = """
        {
            "model": "$selectedModel",
            "messages": $messagesJson,
            "temperature": 0.2
        }
        """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("User-Agent", "AIVisualTutor/1.0")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(20))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() in 200..299) {
            val rawReply = extractAssistantContent(response.body())
            val directives = parseDirectives(rawReply)
            return ChatResponse(
                replyText = directives.cleanText,
                explanation = "Powered by Groq ($selectedModel)",
                targetControl = directives.targetControl,
                suggestedAction = directives.suggestedAction,
                providerModel = selectedModel
            )
        } else {
            throw RuntimeException("Groq API returned HTTP ${response.statusCode()}: ${response.body()}")
        }
    }

    private fun buildSystemPrompt(context: ChatContext): String {
        val sb = StringBuilder()
        sb.append("You are an expert AI Visual Tutor for desktop applications, currently teaching ${context.applicationName}.\n")
        sb.append("Answer questions concisely in 1 to 3 clear sentences.\n")
        sb.append("When explaining where a UI tool or button is located, or guiding a learner who is stuck:\n")
        sb.append("1. Clearly describe the location, shortcut, or mode.\n")
        sb.append("2. Crucial: specify the exact target control name using [TARGET: <ControlName>] (e.g. [TARGET: Extrude], [TARGET: Loop Cut], [TARGET: Inset Faces]).\n")
        sb.append("3. If the user needs their action in the app verified or tracked, append [ACTION: AUTO_VERIFY].\n")
        sb.append("This instructs the tutor overlay to illuminate the exact UI element on their physical screen.")
        return sb.toString()
    }

    private fun buildUserContent(context: ChatContext, hasVisualContext: Boolean): String {
        val userPrompt = StringBuilder()
        if (context.applicationMode != null) {
            userPrompt.append("Application Mode: ${context.applicationMode}\n")
        }
        if (context.applicationStateSummary != null) {
            userPrompt.append("Application State: ${context.applicationStateSummary}\n")
        }
        if (context.currentTaskTitle != null) {
            userPrompt.append("Current Tutorial Step: Step ${context.currentStepNumber ?: 1} of ${context.totalSteps ?: 1} - '${context.currentTaskTitle}'\n")
        }
        if (context.activeInstruction != null) {
            userPrompt.append("Step Instruction: '${context.activeInstruction}'\n")
        }
        userPrompt.append("Learner Question: ${context.question}")

        if (!hasVisualContext || context.screenshotCrop == null) {
            return escapeJson(userPrompt.toString())
        }

        // Multi-part content with image
        val base64Image = encodeImageToBase64(context.screenshotCrop)
        if (base64Image == null) {
            return escapeJson(userPrompt.toString())
        }

        return """
        [
            {"type": "text", "text": ${escapeJson(userPrompt.toString())}},
            {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,$base64Image"}}
        ]
        """.trimIndent()
    }

    private fun parseDirectives(rawText: String): ParsedDirectives {
        var text = rawText
        var target: String? = null
        var action: String? = null

        val targetRegex = Regex("""\[TARGET:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
        targetRegex.find(text)?.let {
            target = it.groupValues[1].trim()
            text = text.replace(it.value, "").trim()
        }

        val actionRegex = Regex("""\[ACTION:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
        actionRegex.find(text)?.let {
            action = it.groupValues[1].trim()
            text = text.replace(it.value, "").trim()
        }

        return ParsedDirectives(cleanText = text, targetControl = target, suggestedAction = action)
    }

    private data class ParsedDirectives(
        val cleanText: String,
        val targetControl: String?,
        val suggestedAction: String?
    )

    private fun generateOfflineGuidance(context: ChatContext): ChatResponse {
        val q = context.question.lowercase()
        val app = context.applicationName
        val task = context.currentTaskTitle ?: "the current task"

        val (reply, target) = when {
            q.contains("extrude") -> Pair(
                "In Edit Mode, press 'E' or select the Extrude Region tool from the left toolbar.",
                "Extrude"
            )
            q.contains("loop cut") || q.contains("cut") -> Pair(
                "Press Ctrl+R to add a Loop Cut, or select Loop Cut from the left toolbar.",
                "Loop Cut"
            )
            q.contains("modifier") || q.contains("bevel") -> Pair(
                "Go to the Modifier Properties tab on the right sidebar (wrench icon) and add the Bevel modifier.",
                "Modifier Properties"
            )
            q.contains("where") || q.contains("find") -> Pair(
                "Look at the highlighted area on your screen for '$task'. Instruction: ${context.activeInstruction ?: "Follow step instructions"}.",
                null
            )
            else -> Pair(
                "For '$task' in $app: ${context.activeInstruction ?: "Follow step instructions"}.",
                null
            )
        }

        val hasConfiguredKey = apiKeyProvider().isNotBlank()
        val notice = if (hasConfiguredKey) {
            "Offline GUI Knowledge Base (Cloud model unavailable or rate-limited)"
        } else {
            "Offline GUI Knowledge Base (configure GROQ_API_KEY in .env for cloud model)"
        }
        return ChatResponse(
            replyText = reply,
            explanation = notice,
            targetControl = target,
            suggestedAction = if (target != null) "AUTO_VERIFY" else null,
            providerModel = "offline-tutor"
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

    private fun extractAssistantContent(jsonBody: String): String {
        val messageIdx = jsonBody.indexOf("\"message\"")
        val searchStart = if (messageIdx != -1) messageIdx else 0
        val contentMarker = "\"content\":"
        val idx = jsonBody.indexOf(contentMarker, searchStart)
        if (idx != -1) {
            val start = jsonBody.indexOf('"', idx + contentMarker.length)
            if (start != -1) {
                val end = findClosingQuote(jsonBody, start + 1)
                if (end != -1) {
                    val raw = jsonBody.substring(start + 1, end)
                    return unescapeJson(raw)
                }
            }
        }
        return "Received response from Groq."
    }

    private fun findClosingQuote(str: String, startIdx: Int): Int {
        var i = startIdx
        while (i < str.length) {
            if (str[i] == '"') {
                var backslashes = 0
                var j = i - 1
                while (j >= 0 && str[j] == '\\') {
                    backslashes++
                    j--
                }
                if (backslashes % 2 == 0) {
                    return i
                }
            }
            i++
        }
        return -1
    }

    private fun unescapeJson(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    'b' -> { sb.append('\b'); i += 2; continue }
                    '"' -> { sb.append('"'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                    '/' -> { sb.append('/'); i += 2; continue }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            try {
                                val code = raw.substring(i + 2, i + 6).toInt(16)
                                sb.append(code.toChar())
                                i += 6
                                continue
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            sb.append(raw[i])
            i++
        }
        return sb.toString()
    }
}
