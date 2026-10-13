package tutor

import chat.ApiKeyConfig

/**
 * Smart multimodal screenshot analyzer that automatically routes screenshot analysis requests to:
 * 1. Groq Cloud Vision (`qwen/qwen3.8-27b`) if `GROQ_API_KEY` is present.
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
            val result = groqAnalyzer.submitForAnalysis(context)
            if (result !is ScreenshotAnalysisSubmissionResult.Rejected) {
                return result
            }
        }

        val geminiKey = geminiKeyProvider().trim()
        if (geminiKey.isNotEmpty() && geminiKey != "MOCK_KEY") {
            val result = geminiAnalyzer.submitForAnalysis(context)
            if (result !is ScreenshotAnalysisSubmissionResult.Rejected) {
                return result
            }
        }

        // Neither key is configured or live provider rejected request: route to graceful offline staging
        return geminiAnalyzer.submitForAnalysis(context)
    }
}
