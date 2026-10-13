package tutor

import context.ExpectedState
import context.MismatchCategory
import context.ObservedState
import context.StateMismatchDiagnosticEngine
import pedagogy.GuidanceStage
import pedagogy.LearnerState

/**
 * Contract for the Phase 10 Context-Aware Next-Step Recommendation Engine.
 */
interface NextStepRecommendationEngine {
    fun generateRecommendation(
        context: ScreenshotHelpContext,
        analysisResult: ScreenshotAnalysisResult?,
        observedState: ObservedState?,
        learnerState: LearnerState? = null,
        expectedState: ExpectedState? = null
    ): TutorRecommendation
}

/**
 * Core implementation of Phase 10 Next-Step Recommendation Engine.
 * Synthesizes:
 * 1. Active lesson context (goal, active step, instruction, expected outcome)
 * 2. Phase 9 visual observations and UI detections
 * 3. Authoritative Blender telemetry and deterministic state mismatch diagnostics
 * 4. Adaptive learner modeling (guidance stage, consecutive difficulties)
 *
 * Implements strict evidence hierarchy, transparent uncertainty disclosure,
 * conflict reconciliation, and non-completion safety invariants.
 */
class AdaptiveNextStepRecommendationEngine(
    private val diagnosticEngine: StateMismatchDiagnosticEngine = StateMismatchDiagnosticEngine()
) : NextStepRecommendationEngine {

    override fun generateRecommendation(
        context: ScreenshotHelpContext,
        analysisResult: ScreenshotAnalysisResult?,
        observedState: ObservedState?,
        learnerState: LearnerState?,
        expectedState: ExpectedState?
    ): TutorRecommendation {
        val guidanceStage = learnerState?.currentGuidanceStage ?: GuidanceStage.STAGE_1_EXACT_GUIDANCE
        val consecutiveFailures = learnerState?.consecutiveFailures ?: 0

        val teleMode = observedState?.mode?.uppercase()?.trim()
        val teleObjects = observedState?.objects?.mapNotNull { it.name } ?: emptyList()
        val teleSelected = observedState?.selectedObjects ?: emptySet()

        val visualMode = detectVisualMode(analysisResult)
        val visualEvidence = analysisResult?.observations?.map { it.description } ?: emptyList()
        val visualUncertainties = analysisResult?.uncertaintyNotes ?: emptyList()
        val visualConfidence = analysisResult?.confidence ?: 0.5

        val targetMode = expectedState?.targetMode?.uppercase()?.trim()
            ?: detectExpectedMode(context.expectedOutcome ?: context.stepInstruction)
        val targetName = expectedState?.targetName
            ?: detectExpectedTarget(context.expectedOutcome ?: context.stepTitle)

        // ---------------------------------------------------------------------
        // 1. CONFLICT DETECTION (Case B: Telemetry vs Visual Inference)
        // ---------------------------------------------------------------------
        val hasModeConflict = teleMode != null && visualMode != null && teleMode != visualMode
        val conflictWarning = if (hasModeConflict) {
            "Blender telemetry confirms $teleMode Mode, but visual analysis inferred $visualMode Mode. Prioritizing authoritative telemetry over visual inference."
        } else null

        // ---------------------------------------------------------------------
        // 2. INSUFFICIENT EVIDENCE (Case D & F)
        // ---------------------------------------------------------------------
        val isVisualObstructedOrEmpty = analysisResult == null ||
            (analysisResult.observations.isEmpty() && analysisResult.relevantUiElements.isEmpty()) ||
            (analysisResult.confidence != null && analysisResult.confidence < 0.4)

        if (isVisualObstructedOrEmpty && observedState == null) {
            return buildInsufficientEvidenceRecommendation(context, guidanceStage, visualUncertainties)
        }

        // ---------------------------------------------------------------------
        // 3. DETERMINISTIC TELEMETRY MISMATCH DIAGNOSIS (Case C - Authoritative)
        // ---------------------------------------------------------------------
        if (expectedState != null && observedState != null) {
            val report = diagnosticEngine.diagnose(expectedState, observedState)
            if (report.hasMismatch && report.primaryMismatch != null) {
                return buildDeterministicMismatchRecommendation(
                    context = context,
                    report = report,
                    guidanceStage = guidanceStage,
                    consecutiveFailures = consecutiveFailures,
                    visualEvidence = visualEvidence,
                    conflictWarning = conflictWarning
                )
            }
        }

        // ---------------------------------------------------------------------
        // 4. VISUAL MISMATCH DIAGNOSIS (When telemetry is missing or confirms mode mismatch)
        // ---------------------------------------------------------------------
        val effectiveMode = teleMode ?: visualMode
        if (targetMode != null && effectiveMode != null && effectiveMode != targetMode) {
            return buildModeMismatchRecommendation(
                context = context,
                currentMode = effectiveMode,
                expectedMode = targetMode,
                guidanceStage = guidanceStage,
                consecutiveFailures = consecutiveFailures,
                basedSolelyOnVisual = observedState == null,
                visualEvidence = visualEvidence,
                conflictWarning = conflictWarning
            )
        }

        // Selection / Target Object Mismatch
        if (targetName != null) {
            val isSelectedInTele = teleSelected.any { it.equals(targetName, ignoreCase = true) }
            val isVisibleInVision = analysisResult?.relevantUiElements?.any {
                it.name.contains(targetName, ignoreCase = true) && it.visible
            } ?: false

            if (observedState != null && !isSelectedInTele && teleObjects.any { it.equals(targetName, ignoreCase = true) }) {
                return buildSelectionMismatchRecommendation(
                    context = context,
                    targetName = targetName,
                    guidanceStage = guidanceStage,
                    visualEvidence = visualEvidence,
                    basedSolelyOnVisual = false
                )
            } else if (observedState == null && !isVisibleInVision && analysisResult != null && !isVisualObstructedOrEmpty) {
                // Visual indicates target object might be unselected or not visible
                val cubeVisible = analysisResult.relevantUiElements.any { it.name.contains("Cube", ignoreCase = true) }
                if (targetName.contains("Sphere", ignoreCase = true) && cubeVisible) {
                    return buildVisualObjectMismatchRecommendation(
                        context = context,
                        observedObject = "Cube",
                        expectedObject = targetName,
                        guidanceStage = guidanceStage,
                        visualEvidence = visualEvidence
                    )
                }
            }
        }

        // ---------------------------------------------------------------------
        // 5. STRONG AGREEMENT / READY TO PROCEED (Case A)
        // ---------------------------------------------------------------------
        return buildProceedRecommendation(
            context = context,
            analysisResult = analysisResult,
            observedState = observedState,
            guidanceStage = guidanceStage,
            consecutiveFailures = consecutiveFailures,
            conflictWarning = conflictWarning,
            visualUncertainties = visualUncertainties
        )
    }

    // =========================================================================
    // RECOMMENDATION BUILDERS
    // =========================================================================

    private fun buildProceedRecommendation(
        context: ScreenshotHelpContext,
        analysisResult: ScreenshotAnalysisResult?,
        observedState: ObservedState?,
        guidanceStage: GuidanceStage,
        consecutiveFailures: Int,
        conflictWarning: String?,
        visualUncertainties: List<String>
    ): TutorRecommendation {
        val title = "Proceed with Step ${context.stepIndex + 1}: ${context.stepTitle}"
        val evidenceList = mutableListOf<String>()

        if (observedState != null) {
            evidenceList.add("Blender telemetry confirms ${observedState.mode ?: "active"} workspace.")
        }
        if (analysisResult != null && analysisResult.observations.isNotEmpty()) {
            evidenceList.add("Visual analysis: ${analysisResult.summary}")
        } else {
            evidenceList.add("Active lesson instruction: ${context.stepInstruction}")
        }

        val actionSteps = when (guidanceStage) {
            GuidanceStage.STAGE_1_EXACT_GUIDANCE -> {
                if (consecutiveFailures >= 2) {
                    listOf(
                        "Review current workspace state in the 3D Viewport.",
                        "Execute instruction: ${context.stepInstruction}",
                        "Check the Status Bar or Outliner to verify your action."
                    )
                } else {
                    listOf(
                        "Execute instruction: ${context.stepInstruction}",
                        "Observe the change in the 3D Viewport."
                    )
                }
            }
            GuidanceStage.STAGE_2_CONCEPTUAL_HINT -> listOf(
                "Apply the concept for '${context.stepTitle}' using the relevant interface toolbar.",
                "Ensure ${context.expectedOutcome ?: "intended change"} is visible."
            )
            GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION -> listOf(
                "Reflect on how to '${context.stepTitle.lowercase()}'.",
                "Execute the operation manually in Blender."
            )
        }

        val explanation = if (observedState != null) {
            "Workspace state is properly prepared for '${context.stepTitle}'. No prior configuration errors detected."
        } else {
            "Visual analysis indicates workspace is ready for '${context.stepTitle}'."
        }

        val rationale = "Executing this action directly advances your learning goal: '${context.userGoal}'."
        val expected = context.expectedOutcome ?: "Visual confirmation of '${context.stepTitle}' in Blender."

        val rawConfidence = if (observedState != null) 0.95 else (analysisResult?.confidence ?: 0.75)
        val calibratedConf = if (conflictWarning != null) (rawConfidence * 0.7).coerceIn(0.1, 1.0) else rawConfidence.coerceIn(0.1, 1.0)

        return TutorRecommendation(
            title = title,
            explanation = explanation,
            actionSteps = actionSteps,
            rationale = rationale,
            expectedOutcome = expected,
            supportingEvidence = evidenceList,
            uncertaintyNotes = visualUncertainties,
            recommendationType = RecommendationType.CONTINUE,
            confidence = calibratedConf,
            requiresVerification = true,
            conflictWarning = conflictWarning,
            basedSolelyOnVisualEvidence = observedState == null
        )
    }

    private fun buildModeMismatchRecommendation(
        context: ScreenshotHelpContext,
        currentMode: String,
        expectedMode: String,
        guidanceStage: GuidanceStage,
        consecutiveFailures: Int,
        basedSolelyOnVisual: Boolean,
        visualEvidence: List<String>,
        conflictWarning: String?
    ): TutorRecommendation {
        val shortcut = if (expectedMode.equals("OBJECT", ignoreCase = true) || expectedMode.equals("EDIT", ignoreCase = true)) "Tab" else null
        val title = "Switch from $currentMode to $expectedMode Mode"

        val explanation = "The active step '${context.stepTitle}' requires $expectedMode Mode, but your workspace is currently in $currentMode Mode."
        val actionSteps = when (guidanceStage) {
            GuidanceStage.STAGE_1_EXACT_GUIDANCE -> {
                if (shortcut != null) {
                    listOf(
                        "Press the '$shortcut' key on your keyboard to toggle between Edit and Object mode.",
                        "Alternatively, click the Mode Selector dropdown in the top-left header of the 3D Viewport and select '$expectedMode Mode'."
                    )
                } else {
                    listOf(
                        "Click the Mode Selector in the top-left of the 3D Viewport header.",
                        "Select '$expectedMode Mode' from the list."
                    )
                }
            }
            GuidanceStage.STAGE_2_CONCEPTUAL_HINT -> listOf(
                "Use the Mode Selector located in the top-left 3D Viewport header to switch to $expectedMode Mode.",
                "Verify the mode indicator changes to '$expectedMode'."
            )
            GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION -> listOf(
                "What keyboard shortcut or header selector switches your active mode to $expectedMode?",
                "Switch to $expectedMode Mode to proceed."
            )
        }

        val rationale = "Blender operations and tools are mode-specific. Tool availability for '${context.stepTitle}' requires $expectedMode Mode."
        val expected = "3D Viewport top-left header displays '$expectedMode Mode'."

        val evidence = mutableListOf<String>()
        if (basedSolelyOnVisual) {
            evidence.add("Visual inspection observed $currentMode Mode.")
        } else {
            evidence.add("Telemetry confirmed $currentMode Mode.")
        }
        evidence.addAll(visualEvidence.take(2))

        return TutorRecommendation(
            title = title,
            explanation = explanation,
            actionSteps = actionSteps,
            rationale = rationale,
            expectedOutcome = expected,
            supportingEvidence = evidence,
            uncertaintyNotes = if (basedSolelyOnVisual) listOf("Mode observation derived from screenshot without live socket telemetry.") else emptyList(),
            recommendationType = RecommendationType.CORRECT_STATE,
            confidence = if (basedSolelyOnVisual) 0.8 else 0.95,
            requiresVerification = true,
            conflictWarning = conflictWarning,
            basedSolelyOnVisualEvidence = basedSolelyOnVisual,
            targetControlName = "Mode Selector",
            suggestedShortcut = shortcut
        )
    }

    private fun buildDeterministicMismatchRecommendation(
        context: ScreenshotHelpContext,
        report: context.StateMismatchReport,
        guidanceStage: GuidanceStage,
        consecutiveFailures: Int,
        visualEvidence: List<String>,
        conflictWarning: String?
    ): TutorRecommendation {
        val primary = report.primaryMismatch!!
        val action = primary.suggestedAction

        val isModeMismatch = primary.category == MismatchCategory.MODE_MISMATCH
        val title = if (isModeMismatch) {
            "Switch to ${primary.expected} Mode"
        } else if (primary.category == MismatchCategory.SELECTION_MISMATCH) {
            "Select ${primary.expected}"
        } else {
            "Resolve ${primary.category.title}: ${primary.expected}"
        }
        val explanation = primary.explanation

        val actionSteps = when (guidanceStage) {
            GuidanceStage.STAGE_1_EXACT_GUIDANCE -> listOf(
                action.description,
                if (!action.suggestedShortcut.isNullOrBlank()) "Shortcut: Press '${action.suggestedShortcut}' in Blender." else "Check the 3D Viewport to confirm the change."
            )
            GuidanceStage.STAGE_2_CONCEPTUAL_HINT -> {
                if (isModeMismatch) {
                    listOf(
                        "Use the Mode Selector located in the top-left 3D Viewport header to switch to ${primary.expected} Mode.",
                        "Verify the mode indicator changes to '${primary.expected}'."
                    )
                } else {
                    listOf(
                        "Adjust the workspace so ${primary.expected.lowercase()} is active instead of ${primary.observed.lowercase()}.",
                        "Locate the relevant control in the interface."
                    )
                }
            }
            GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION -> {
                if (isModeMismatch) {
                    listOf(
                        "What keyboard shortcut or header selector switches your active mode to ${primary.expected}?",
                        "Switch to ${primary.expected} Mode to proceed."
                    )
                } else {
                    listOf(
                        "How do you correct ${primary.category.title.lowercase()} from ${primary.observed} to ${primary.expected}?",
                        "Perform the correction in Blender."
                    )
                }
            }
        }

        val rationale = "The active step requires ${primary.expected} before subsequent actions can succeed."
        val expected = "Observed state reflects: ${primary.expected}."

        val evidence = mutableListOf<String>()
        evidence.add("Deterministic state check: ${primary.explanation}")
        evidence.addAll(visualEvidence.take(2))

        return TutorRecommendation(
            title = title,
            explanation = explanation,
            actionSteps = actionSteps,
            rationale = rationale,
            expectedOutcome = expected,
            supportingEvidence = evidence,
            recommendationType = RecommendationType.CORRECT_STATE,
            confidence = primary.confidence.coerceIn(0.1, 1.0),
            requiresVerification = true,
            conflictWarning = conflictWarning,
            basedSolelyOnVisualEvidence = false,
            targetControlName = action.targetName,
            suggestedShortcut = action.suggestedShortcut
        )
    }

    private fun buildSelectionMismatchRecommendation(
        context: ScreenshotHelpContext,
        targetName: String,
        guidanceStage: GuidanceStage,
        visualEvidence: List<String>,
        basedSolelyOnVisual: Boolean
    ): TutorRecommendation {
        val title = "Select Object '$targetName'"
        val explanation = "The active step requires '$targetName' to be selected, but it is currently unselected."
        val actionSteps = when (guidanceStage) {
            GuidanceStage.STAGE_1_EXACT_GUIDANCE -> listOf(
                "Click on '$targetName' in the top-right Outliner panel.",
                "Alternatively, Left-Click '$targetName' in the 3D Viewport."
            )
            GuidanceStage.STAGE_2_CONCEPTUAL_HINT -> listOf(
                "Locate '$targetName' in the Outliner or 3D Viewport and select it.",
                "Ensure the selection outline appears."
            )
            GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION -> listOf(
                "Which object needs to be active for '${context.stepTitle}'?",
                "Select '$targetName' in Blender."
            )
        }

        val rationale = "Blender modifiers and transforms only apply to active and selected objects."
        val expected = "'$targetName' is highlighted with an orange selection outline."

        val evidence = mutableListOf("Telemetry indicates '$targetName' is unselected.")
        evidence.addAll(visualEvidence.take(2))

        return TutorRecommendation(
            title = title,
            explanation = explanation,
            actionSteps = actionSteps,
            rationale = rationale,
            expectedOutcome = expected,
            supportingEvidence = evidence,
            recommendationType = RecommendationType.CORRECT_STATE,
            confidence = 0.9,
            requiresVerification = true,
            basedSolelyOnVisualEvidence = basedSolelyOnVisual,
            targetControlName = "Outliner"
        )
    }

    private fun buildVisualObjectMismatchRecommendation(
        context: ScreenshotHelpContext,
        observedObject: String,
        expectedObject: String,
        guidanceStage: GuidanceStage,
        visualEvidence: List<String>
    ): TutorRecommendation {
        val title = "Target Object Mismatch: Replace $observedObject with $expectedObject"
        val explanation = "Visual evidence shows a $observedObject in the workspace, while the active step requires a $expectedObject."
        val actionSteps = listOf(
            "Select the existing $observedObject in the 3D Viewport and press 'X' or 'Delete' to remove it.",
            "Press 'Shift+A' and select Mesh -> $expectedObject.",
            "Verify the new $expectedObject appears at the 3D Cursor."
        )

        return TutorRecommendation(
            title = title,
            explanation = explanation,
            actionSteps = actionSteps,
            rationale = "The active tutorial instructions are tailored for $expectedObject geometry.",
            expectedOutcome = "$expectedObject is visible and selected in the 3D Viewport.",
            supportingEvidence = visualEvidence.take(2),
            uncertaintyNotes = listOf("Object classification based on visual shape; verify object name in Outliner."),
            recommendationType = RecommendationType.CORRECT_STATE,
            confidence = 0.8,
            requiresVerification = true,
            basedSolelyOnVisualEvidence = true,
            suggestedShortcut = "Shift+A"
        )
    }

    private fun buildInsufficientEvidenceRecommendation(
        context: ScreenshotHelpContext,
        guidanceStage: GuidanceStage,
        uncertaintyNotes: List<String>
    ): TutorRecommendation {
        val title = "Capture Clearer View of 3D Viewport"
        val explanation = "The screenshot was ambiguous, obstructed, or lacking visual detail to confidently verify your workspace."
        val actionSteps = listOf(
            "Ensure Blender is the front-most active window and unobstructed.",
            "Adjust your 3D Viewport angle so your active geometry and header are clearly visible.",
            "Click '🔄 Retake Screenshot' in the tutor HUD."
        )

        val notes = if (uncertaintyNotes.isNotEmpty()) uncertaintyNotes else listOf("No unambiguous objects or headers detected in captured frame.")

        return TutorRecommendation(
            title = title,
            explanation = explanation,
            actionSteps = actionSteps,
            rationale = "Reliable visual recommendations require a clear view of the Blender 3D Viewport and mode header.",
            expectedOutcome = "Unobstructed view of active mesh and header controls.",
            supportingEvidence = listOf("Phase 9 reported high visual ambiguity or empty observation set."),
            uncertaintyNotes = notes,
            recommendationType = RecommendationType.REQUEST_MORE_EVIDENCE,
            confidence = 0.35,
            requiresVerification = false,
            basedSolelyOnVisualEvidence = true
        )
    }

    // =========================================================================
    // HELPER SCANNERS
    // =========================================================================

    private fun detectVisualMode(analysisResult: ScreenshotAnalysisResult?): String? {
        if (analysisResult == null) return null
        val fullText = buildString {
            append(analysisResult.summary).append(" ")
            analysisResult.observations.forEach { append(it.description).append(" ") }
            analysisResult.relevantUiElements.forEach { append(it.name).append(" ").append(it.evidence ?: "").append(" ") }
        }.lowercase()

        return when {
            fullText.contains("edit mode") -> "EDIT"
            fullText.contains("object mode") -> "OBJECT"
            fullText.contains("sculpt mode") -> "SCULPT"
            fullText.contains("pose mode") -> "POSE"
            else -> null
        }
    }

    private fun detectExpectedMode(text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("object mode") || lower.contains("object_mode") -> "OBJECT"
            lower.contains("edit mode") || lower.contains("edit_mode") -> "EDIT"
            lower.contains("sculpt mode") || lower.contains("sculpt_mode") -> "SCULPT"
            else -> null
        }
    }

    private fun detectExpectedTarget(text: String): String? {
        val lower = text.lowercase()
        return when {
            lower.contains("sphere") -> "Sphere"
            lower.contains("cube") -> "Cube"
            lower.contains("cylinder") -> "Cylinder"
            lower.contains("plane") -> "Plane"
            lower.contains("camera") -> "Camera"
            lower.contains("light") -> "Light"
            else -> null
        }
    }
}
