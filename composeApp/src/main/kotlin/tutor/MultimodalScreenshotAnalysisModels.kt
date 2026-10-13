package tutor

/**
 * Visual observation directly grounded in the captured screenshot.
 */
data class VisualObservation(
    val description: String,
    val evidence: String? = null,
    val confidence: Double? = null
)

/**
 * Identified visible Blender user interface element or 3D object.
 */
data class VisibleUiElement(
    val name: String,
    val visible: Boolean = true,
    val evidence: String? = null
)

/**
 * Structured analysis result returned by vision-capable AI provider.
 * Separates visual evidence from assumptions, and explicitly highlights uncertainty.
 */
data class ScreenshotAnalysisResult(
    val summary: String,
    val observations: List<VisualObservation> = emptyList(),
    val relevantUiElements: List<VisibleUiElement> = emptyList(),
    val uncertaintyNotes: List<String> = emptyList(),
    val suggestedNextStepEvidence: String? = null,
    val confidence: Double? = null,
    val modelIdentifier: String = "gemini-1.5-flash",
    val latencyMillis: Long = 0L,
    val rawModelResponse: String? = null
) {
    /**
     * True if the model reported significant ambiguity or lack of visual evidence.
     */
    val hasUncertainty: Boolean
        get() = uncertaintyNotes.isNotEmpty() || (confidence != null && confidence < 0.6)
}
