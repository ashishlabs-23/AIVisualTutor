package chat

import java.awt.image.BufferedImage
import java.time.Instant
import java.util.UUID

enum class ChatSender { USER, TUTOR, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: ChatSender,
    val text: String,
    val timestamp: Instant = Instant.now(),
    val targetControl: String? = null,
    val suggestedAction: String? = null,
    val isError: Boolean = false
)

data class ChatContext(
    val question: String,
    val applicationName: String,
    val applicationMode: String? = null,
    val applicationStateSummary: String? = null,
    val currentTaskTitle: String? = null,
    val currentStepNumber: Int? = null,
    val totalSteps: Int? = null,
    val activeInstruction: String? = null,
    val recentChatHistory: List<ChatMessage> = emptyList(),
    val screenshotCrop: BufferedImage? = null,
    val includeVisualContext: Boolean = false
)

data class ChatResponse(
    val replyText: String,
    val explanation: String? = null,
    val targetControl: String? = null,
    val suggestedAction: String? = null,
    val confidence: Double = 1.0,
    val providerModel: String = "openai/gpt-oss-120b"
)

fun interface ChatbotProvider {
    suspend fun askTutor(context: ChatContext): ChatResponse
}
