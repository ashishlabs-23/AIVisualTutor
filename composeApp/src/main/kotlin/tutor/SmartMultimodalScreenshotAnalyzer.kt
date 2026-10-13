package tutor

import chat.ApiKeyConfig

/**
 * Smart multimodal screenshot analyzer that automatically routes screenshot analysis requests to:
 * 1. Groq Cloud Vision (`llama-3.2-11b-vision-preview`) if `GROQ_API_KEY` is present.
 * 2. Google Gemini (`gemini-1.5-flash`) if `GEMINI_API_KEY` is present.
 * 3. Graceful offline staging diagnostics if neither key is configured.
 */
class SmartMultimodalScreenshotAnalyzer(
    private val groqAnalyzer: ScreenshotAnalysisBoundary = GroqMultimodalScreenshotAnalyzer(),
    private val geminiAnalyzer: ScreenshotAnalysisBoundary = GeminiMultimodalScreenshotAnalyzer(),
    private val groqKeyProvider: () -> String = { ApiKeyConfig.getGroqApiKey() },
    private val geminiKeyProvider: () -> String = { ApiKeyConfig.getGeminiApiKey() }
) : ScreenshotAnalysisBoundary {

    override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
        val groqKey = groqKeyProvider().trim()
        if (groqKey.isNotEmpty() && groqKey != "MOCK_KEY") {
            return groqAnalyzer.submitForAnalysis(context)
        }

        val geminiKey = geminiKeyProvider().trim()
        if (geminiKey.isNotEmpty() && geminiKey != "MOCK_KEY") {
            return geminiAnalyzer.submitForAnalysis(context)
        }

        // Neither key is configured: route to offline staging
        return if (groqKey.isNotEmpty()) {
            groqAnalyzer.submitForAnalysis(context)
        } else {
            geminiAnalyzer.submitForAnalysis(context)
        }
    }
}
