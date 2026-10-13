package chat

/**
 * Intelligent chatbot provider that automatically routes queries to:
 * 1. Groq (ultra-fast Llama-3 / GPT-OSS / Qwen) if `GROQ_API_KEY` is present.
 * 2. Google Gemini if `GEMINI_API_KEY` is present.
 * 3. Graceful offline tutorial knowledge base if no keys are present.
 */
class SmartChatbotProvider(
    private val groqProvider: ChatbotProvider = GroqChatbotProvider(),
    private val geminiProvider: ChatbotProvider = GeminiChatbotProvider()
) : ChatbotProvider {

    override suspend fun askTutor(context: ChatContext): ChatResponse {
        val groqKey = ApiKeyConfig.getGroqApiKey()
        if (groqKey.isNotBlank()) {
            return groqProvider.askTutor(context)
        }

        val geminiKey = ApiKeyConfig.getGeminiApiKey()
        if (geminiKey.isNotBlank()) {
            return geminiProvider.askTutor(context)
        }

        // Default to Groq provider's offline knowledge engine
        return groqProvider.askTutor(context)
    }
}
