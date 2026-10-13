package pedagogy

import context.ObservedState
import context.VisualContext
import models.HighlightRegion

/**
 * Request payload for adaptive instruction generation.
 */
data class InstructionGenerationRequest(
    val task: LearningTask,
    val stage: GuidanceStage,
    val learnerState: LearnerState,
    val visualContext: VisualContext? = null,
    val observedState: ObservedState? = null,
    val previousAttempts: List<PedagogicalAttempt> = emptyList(),
    val allowFallback: Boolean = true
)

/**
 * Interface for generating adaptive pedagogical instructions.
 */
interface AdaptiveInstructionGenerator {
    fun generate(request: InstructionGenerationRequest): AdaptiveInstruction
}

/**
 * Deterministic adaptive instruction generator.
 * Implements strict evidence-grounded instruction generation with visual context verification,
 * location grounding checks, and safe fallback generation.
 */
class DeterministicAdaptiveInstructionGenerator : AdaptiveInstructionGenerator {

    override fun generate(request: InstructionGenerationRequest): AdaptiveInstruction {
        val task = request.task
        val stage = request.stage
        val visualContext = request.visualContext
        val observedState = request.observedState

        // Validate basic prerequisites
        if (task.exactInstruction.isBlank()) {
            return generateSafeFallback(request, "Task instruction is blank.")
        }

        // 1. Evidence evaluation from VisualContext and UI Automation
        val evidenceList = mutableListOf<String>()
        var targetVisuallyConfirmed = false
        var targetLocationConfirmed = false
        var evidenceConfidence: Double? = null

        val targetName = task.targetControlName

        if (visualContext != null) {
            val ocrText = visualContext.extractedText ?: visualContext.ocrResult?.text
            val ocrWords = visualContext.ocrResult?.words.orEmpty()

            if (!targetName.isNullOrBlank() && !ocrText.isNullOrBlank()) {
                val containsTarget = ocrText.contains(targetName, ignoreCase = true)
                if (containsTarget) {
                    targetVisuallyConfirmed = true
                    val matchingWord = ocrWords.find { it.text.contains(targetName, ignoreCase = true) }
                    val wordConf = matchingWord?.confidence?.toDouble()
                        ?: visualContext.confidence?.toDouble()
                        ?: 0.8
                    evidenceConfidence = wordConf
                    evidenceList += "OCR confirmed control '$targetName' (confidence=${"%.2f".format(wordConf)})."
                    if (task.targetAreaDescription != null) {
                        targetLocationConfirmed = true
                        evidenceList += "Location grounded in area '${task.targetAreaDescription}'."
                    }
                } else {
                    evidenceList += "OCR text observed (${ocrText.length} chars) but target '$targetName' not explicitly matched."
                }
            }

            // UIA perception check
            visualContext.perceptionResult?.let { perception ->
                val uiaName = perception.selectedObject ?: perception.visibleText
                if (!targetName.isNullOrBlank() && uiaName?.contains(targetName, ignoreCase = true) == true) {
                    targetVisuallyConfirmed = true
                    targetLocationConfirmed = true
                    val uiaConf = perception.confidence?.toDouble() ?: 0.9
                    evidenceConfidence = maxOf(evidenceConfidence ?: 0.0, uiaConf)
                    evidenceList += "UI Automation confirmed element '$uiaName' (type=${perception.uiType})."
                }
            }
        }

        // 2. State verification check: Has the target goal already been met?
        if (observedState != null && task.expectedState != null) {
            val expected = task.expectedState
            val matchedObj = observedState.objects.find {
                expected.targetName != null && it.name?.equals(expected.targetName, ignoreCase = true) == true
            }
            if (matchedObj != null && expected.operation == context.VerificationOperation.CREATE) {
                evidenceList += "Observed state indicates object '${expected.targetName}' is already present in scene."
            }
        }

        val state = request.learnerState
        val hasDifficulty = state.hasDifficulty(task.skillId)
        val isConsistent = state.getSkillLevel(task.skillId) == SkillMasteryLevel.CONSISTENTLY_DEMONSTRATED
        val wantsConcise = state.preferences.detailPreference == DetailPreference.CONCISE
        val wantsDetailed = state.preferences.detailPreference == DetailPreference.DETAILED

        val (strategy, rationale) = when {
            hasDifficulty -> AdaptationStrategy.DETAILED_INSTRUCTION to "Detailed guidance provided due to repeated verified difficulty on '${task.skillId}'."
            wantsDetailed -> AdaptationStrategy.DETAILED_INSTRUCTION to "Detailed guidance provided based on explicit learner preference."
            wantsConcise || isConsistent -> AdaptationStrategy.CONCISE_INSTRUCTION to "Concise instruction provided based on demonstrated mastery or learner preference."
            else -> AdaptationStrategy.NORMAL_GUIDANCE to null
        }

        // 3. Stage-specific generation adhering to instruction quality rules
        return when (stage) {
            GuidanceStage.STAGE_1_EXACT_GUIDANCE -> generateStage1(
                task = task,
                visualContext = visualContext,
                targetVisuallyConfirmed = targetVisuallyConfirmed,
                targetLocationConfirmed = targetLocationConfirmed,
                evidenceList = evidenceList,
                evidenceConfidence = evidenceConfidence,
                strategy = strategy,
                adaptationRationale = rationale
            )
            GuidanceStage.STAGE_2_CONCEPTUAL_HINT -> generateStage2(
                task = task,
                evidenceList = evidenceList,
                evidenceConfidence = evidenceConfidence,
                strategy = strategy,
                adaptationRationale = rationale
            )
            GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION -> generateStage3(
                task = task,
                evidenceList = evidenceList,
                evidenceConfidence = evidenceConfidence,
                strategy = strategy,
                adaptationRationale = rationale
            )
        }
    }

    private fun generateStage1(
        task: LearningTask,
        visualContext: VisualContext?,
        targetVisuallyConfirmed: Boolean,
        targetLocationConfirmed: Boolean,
        evidenceList: List<String>,
        evidenceConfidence: Double?,
        strategy: AdaptationStrategy,
        adaptationRationale: String?
    ): AdaptiveInstruction {
        val controlName = task.targetControlName
        val area = task.targetAreaDescription

        val (instructionText, status) = when {
            // Target is visually/accessibility confirmed with reliable location
            targetVisuallyConfirmed && targetLocationConfirmed && !controlName.isNullOrBlank() && !area.isNullOrBlank() -> {
                "${task.exactInstruction.trimEnd('.')} (in the $area)." to GenerationStatus.GENERATED
            }
            // Control name known from trusted task, but location lacks visual confirmation
            !controlName.isNullOrBlank() && area != null && !targetLocationConfirmed && visualContext != null -> {
                // If visual evidence was acquired but could not confirm location, avoid asserting false exact coordinates
                "${task.exactInstruction} Note: verify location in $area." to GenerationStatus.UNCERTAIN_EVIDENCE
            }
            else -> {
                task.exactInstruction to GenerationStatus.GENERATED
            }
        }

        return AdaptiveInstruction(
            taskId = task.taskId,
            stage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            instructionText = instructionText,
            conceptSummary = task.objective,
            targetControlName = task.targetControlName,
            targetLocationDescription = task.targetAreaDescription,
            targetRegion = task.targetRegion,
            supportingEvidence = evidenceList,
            evidenceConfidence = evidenceConfidence,
            status = status,
            strategy = strategy,
            adaptationRationale = adaptationRationale
        )
    }

    private fun generateStage2(
        task: LearningTask,
        evidenceList: List<String>,
        evidenceConfidence: Double?,
        strategy: AdaptationStrategy,
        adaptationRationale: String?
    ): AdaptiveInstruction {
        // Conceptual hint: Explains action conceptually and indicates general area without giving every exact click detail
        val hintText = task.conceptualHint
        val supporting = evidenceList + "Conceptual hint generated for competent learner."

        return AdaptiveInstruction(
            taskId = task.taskId,
            stage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT,
            instructionText = hintText,
            conceptSummary = task.objective,
            targetControlName = null, // Withheld in Stage 2 to foster independent location
            targetLocationDescription = task.targetAreaDescription,
            targetRegion = null, // Withheld in Stage 2 to avoid spoiling location
            supportingEvidence = supporting,
            evidenceConfidence = evidenceConfidence,
            status = GenerationStatus.GENERATED,
            strategy = strategy,
            adaptationRationale = adaptationRationale
        )
    }

    private fun generateStage3(
        task: LearningTask,
        evidenceList: List<String>,
        evidenceConfidence: Double?,
        strategy: AdaptationStrategy,
        adaptationRationale: String?
    ): AdaptiveInstruction {
        // Self-correction question: Targeted question encouraging reflection and problem-solving
        val question = task.reflectionQuestion
        val supporting = evidenceList + "Self-correction reflection question generated for independent learner."

        return AdaptiveInstruction(
            taskId = task.taskId,
            stage = GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION,
            instructionText = question,
            questionText = question,
            conceptSummary = task.objective,
            targetControlName = null, // Withheld in Stage 3
            targetLocationDescription = null, // Withheld in Stage 3
            targetRegion = null, // Withheld in Stage 3
            supportingEvidence = supporting,
            evidenceConfidence = evidenceConfidence,
            status = GenerationStatus.GENERATED,
            strategy = strategy,
            adaptationRationale = adaptationRationale
        )
    }

    private fun generateSafeFallback(
        request: InstructionGenerationRequest,
        reason: String
    ): AdaptiveInstruction {
        val task = request.task
        val fallbackText = when (request.stage) {
            GuidanceStage.STAGE_1_EXACT_GUIDANCE -> task.exactInstruction.ifBlank { "Perform the requested action for '${task.title}'." }
            GuidanceStage.STAGE_2_CONCEPTUAL_HINT -> task.conceptualHint.ifBlank { "Consider how to achieve '${task.objective}' in the interface." }
            GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION -> task.reflectionQuestion.ifBlank { "What is the next step to achieve '${task.objective}'?" }
        }

        return AdaptiveInstruction(
            taskId = task.taskId,
            stage = request.stage,
            instructionText = fallbackText,
            conceptSummary = task.objective,
            isFallback = true,
            status = GenerationStatus.FALLBACK_GENERATED,
            supportingEvidence = listOf("Safe deterministic fallback activated: $reason")
        )
    }
}
