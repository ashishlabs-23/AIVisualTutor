package tutor

/**
 * Categorical type of tutor recommendation for Phase 10.
 */
enum class RecommendationType(val displayName: String) {
    /**
     * Active state is aligned or verified; proceed with the next learning micro-action.
     */
    CONTINUE("Proceed with Next Action"),

    /**
     * State mismatch detected (e.g. incorrect mode, missing selection); correct before proceeding.
     */
    CORRECT_STATE("Correct Workspace State"),

    /**
     * Conflict or ambiguity detected between visual evidence and telemetry; inspect or clarify.
     */
    CLARIFY("Clarify Workspace Ambiguity"),

    /**
     * Screenshot is obstructed, ambiguous, or lacks sufficient visual evidence; capture a clearer view.
     */
    REQUEST_MORE_EVIDENCE("Capture Clearer View"),

    /**
     * Operation failed or unfulfilled; retry with additional instructional scaffolding.
     */
    RETRY("Retry with Scaffolding"),

    /**
     * Unexpected state or error encountered; reset selection or revert to baseline.
     */
    RECOVER("Reset & Recover")
}

/**
 * Actionable, context-aware pedagogical recommendation produced by the Phase 10 engine.
 * Combines active lesson context, Phase 9 visual observations, Blender telemetry,
 * and adaptive learner history to suggest the single best next action.
 *
 * NOTE: This recommendation is strictly guidance for the human learner.
 * It never automatically executes actions or marks lesson steps complete.
 */
data class TutorRecommendation(
    val title: String,
    val explanation: String,
    val actionSteps: List<String>,
    val rationale: String,
    val expectedOutcome: String,
    val supportingEvidence: List<String> = emptyList(),
    val uncertaintyNotes: List<String> = emptyList(),
    val recommendationType: RecommendationType = RecommendationType.CONTINUE,
    val confidence: Double? = null,
    val requiresVerification: Boolean = true,
    val conflictWarning: String? = null,
    val basedSolelyOnVisualEvidence: Boolean = false,
    val targetControlName: String? = null,
    val suggestedShortcut: String? = null
) {
    init {
        require(title.isNotBlank()) { "Recommendation title must not be blank." }
        require(explanation.isNotBlank()) { "Recommendation explanation must not be blank." }
        require(actionSteps.isNotEmpty()) { "Recommendation must provide at least one concrete action step." }
        require(rationale.isNotBlank()) { "Recommendation rationale must not be blank." }
        if (confidence != null) {
            require(confidence in 0.0..1.0) { "Recommendation confidence must be in range 0.0..1.0, was $confidence" }
        }
    }

    /**
     * Indicates whether the recommendation is based on uncertain or conflicting observations.
     */
    val hasUncertaintyOrConflict: Boolean
        get() = !conflictWarning.isNullOrBlank() || uncertaintyNotes.isNotEmpty() || (confidence != null && confidence < 0.6)
}
