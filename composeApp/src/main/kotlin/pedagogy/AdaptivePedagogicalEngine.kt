package pedagogy

import context.ObservedState
import context.VerificationResult
import context.VisualContext

/**
 * Event emitted when the pedagogical engine performs an adaptation decision.
 */
data class PedagogicalDecisionEvent(
    val taskId: String,
    val previousStage: GuidanceStage,
    val newStage: GuidanceStage,
    val outcome: PedagogicalOutcome,
    val rationale: String,
    val competenceEstimate: Double,
    val stageChanged: Boolean,
    val instruction: AdaptiveInstruction
)

/**
 * Core Adaptive Pedagogical Engine for Phase 7.
 * Coordinates learner modeling, deterministic adaptation policy, Phase 6 verification outcomes,
 * and adaptive instruction generation with pedagogical fading.
 */
class AdaptivePedagogicalEngine(
    val policy: AdaptationPolicy = AdaptationPolicy(),
    val generator: AdaptiveInstructionGenerator = DeterministicAdaptiveInstructionGenerator(),
    val persistence: LearnerStatePersistence? = null,
    initialLearnerId: String = "default_learner"
) {
    var state: LearnerState = LearnerState(learnerId = initialLearnerId)
        private set

    /**
     * Obtains the appropriate adaptive instruction for the current task and state
     * according to the learner's active guidance stage and available interface evidence.
     */
    fun getInstruction(
        task: LearningTask,
        visualContext: VisualContext? = null,
        observedState: ObservedState? = null
    ): AdaptiveInstruction {
        // Ensure state is tuned to the current task
        if (state.currentTaskId != task.taskId) {
            val decision = policy.onTaskSwitched(state, task)
            state = decision.updatedLearnerState
        }

        val request = InstructionGenerationRequest(
            task = task,
            stage = state.currentGuidanceStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )

        return generator.generate(request)
    }

    /**
     * Ingests a real verification result from Phase 6, updates the learner model,
     * applies the deterministic adaptation policy, and returns the next adaptive instruction.
     */
    fun onVerificationResult(
        task: LearningTask,
        result: VerificationResult,
        visualContext: VisualContext? = null,
        observedState: ObservedState? = null
    ): PedagogicalDecisionEvent {
        val outcome = PedagogicalOutcome.fromVerificationResult(result)
        val prevStage = state.currentGuidanceStage

        // Guard against duplicate processing of the exact same verification run
        val isDuplicate = result.runId.isNotBlank() &&
            state.recentAttempts.any { it.verificationRunId == result.runId }
        if (isDuplicate) {
            val duplicateRationale = "Duplicate verification run '${result.runId}' already recorded; state preserved."
            val request = InstructionGenerationRequest(
                task = task,
                stage = state.currentGuidanceStage,
                learnerState = state,
                visualContext = visualContext,
                observedState = observedState,
                previousAttempts = state.recentAttempts
            )
            return PedagogicalDecisionEvent(
                taskId = task.taskId,
                previousStage = prevStage,
                newStage = state.currentGuidanceStage,
                outcome = outcome,
                rationale = duplicateRationale,
                competenceEstimate = state.competenceEstimate,
                stageChanged = false,
                instruction = generator.generate(request)
            )
        }

        // Calculate evidence confidence from Phase 6 observations
        val evidenceConf = if (result.evidence.isNotEmpty()) {
            val available = result.evidence.count { it.available }
            available.toDouble() / result.evidence.size.toDouble()
        } else null

        val decision = policy.evaluateOutcome(
            currentState = state,
            task = task,
            outcome = outcome,
            explanation = result.explanation,
            evidenceConfidence = evidenceConf,
            verificationRunId = result.runId
        )

        state = decision.updatedLearnerState

        // Generate the next instruction following the updated guidance stage
        val request = InstructionGenerationRequest(
            task = task,
            stage = decision.nextGuidanceStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )
        val nextInstruction = generator.generate(request)

        return PedagogicalDecisionEvent(
            taskId = task.taskId,
            previousStage = prevStage,
            newStage = decision.nextGuidanceStage,
            outcome = outcome,
            rationale = decision.rationale,
            competenceEstimate = state.competenceEstimate,
            stageChanged = decision.stageChanged,
            instruction = nextInstruction
        )
    }

    /**
     * Explicitly records a pedagogical outcome without requiring a full VerificationResult object.
     * Useful for simulations, tests, or non-Phase 6 verification sources.
     */
    fun recordOutcome(
        task: LearningTask,
        outcome: PedagogicalOutcome,
        explanation: String = "",
        evidenceConfidence: Double? = null,
        visualContext: VisualContext? = null,
        observedState: ObservedState? = null
    ): PedagogicalDecisionEvent {
        val prevStage = state.currentGuidanceStage
        val decision = policy.evaluateOutcome(
            currentState = state,
            task = task,
            outcome = outcome,
            explanation = explanation,
            evidenceConfidence = evidenceConfidence
        )
        state = decision.updatedLearnerState

        val request = InstructionGenerationRequest(
            task = task,
            stage = decision.nextGuidanceStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )
        val nextInstruction = generator.generate(request)

        return PedagogicalDecisionEvent(
            taskId = task.taskId,
            previousStage = prevStage,
            newStage = decision.nextGuidanceStage,
            outcome = outcome,
            rationale = decision.rationale,
            competenceEstimate = state.competenceEstimate,
            stageChanged = decision.stageChanged,
            instruction = nextInstruction
        )
    }

    /**
     * Adapts state when switching to a different learning task.
     */
    fun onTaskSwitched(
        newTask: LearningTask,
        visualContext: VisualContext? = null,
        observedState: ObservedState? = null
    ): AdaptiveInstruction {
        val decision = policy.onTaskSwitched(state, newTask)
        state = decision.updatedLearnerState

        val request = InstructionGenerationRequest(
            task = newTask,
            stage = state.currentGuidanceStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )
        return generator.generate(request)
    }

    /**
     * Evaluates a learner's conceptual answer to a Stage 3 self-correction reflection question.
     * Updates competence and adapts guidance stage based on demonstrated concept mastery.
     */
    fun evaluateConceptualAnswer(
        task: LearningTask,
        learnerAnswer: String,
        visualContext: VisualContext? = null,
        observedState: ObservedState? = null
    ): PedagogicalDecisionEvent {
        val trimmed = learnerAnswer.trim()
        val prevStage = state.currentGuidanceStage

        if (trimmed.isEmpty() || trimmed.equals("stuck", ignoreCase = true) || trimmed.equals("i'm stuck", ignoreCase = true)) {
            val decision = policy.evaluateOutcome(
                currentState = state,
                task = task,
                outcome = PedagogicalOutcome.VERIFIED_FAILURE,
                explanation = "Learner requested guidance on reflection question; stepping down to conceptual hint."
            )
            state = decision.updatedLearnerState.copy(currentGuidanceStage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT)
            val request = InstructionGenerationRequest(
                task = task,
                stage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT,
                learnerState = state,
                visualContext = visualContext,
                observedState = observedState,
                previousAttempts = state.recentAttempts
            )
            return PedagogicalDecisionEvent(
                taskId = task.taskId,
                previousStage = prevStage,
                newStage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT,
                outcome = PedagogicalOutcome.VERIFIED_FAILURE,
                rationale = "Help requested; guidance increased to conceptual hint.",
                competenceEstimate = state.competenceEstimate,
                stageChanged = true,
                instruction = generator.generate(request)
            )
        }

        val targetName = task.targetControlName?.lowercase()?.trim().orEmpty()
        val normalizedAnswer = trimmed.lowercase()

        val isMatch = (targetName.isNotEmpty() && (normalizedAnswer.contains(targetName) || targetName.contains(normalizedAnswer))) ||
            context.textSimilarity(trimmed, task.targetControlName.orEmpty()) >= 0.65 ||
            context.textSimilarity(trimmed, task.exactInstruction) >= 0.60

        val outcome = if (isMatch) PedagogicalOutcome.VERIFIED_SUCCESS else PedagogicalOutcome.VERIFIED_FAILURE
        val explanation = if (isMatch) {
            "Correct! You identified '$trimmed' for task '${task.title}'."
        } else {
            "Not quite. Expected '${task.targetControlName ?: task.exactInstruction}'. Review the hint below."
        }

        val decision = policy.evaluateOutcome(
            currentState = state,
            task = task,
            outcome = outcome,
            explanation = explanation,
            evidenceConfidence = 1.0
        )

        val nextStage = if (isMatch) decision.nextGuidanceStage else GuidanceStage.STAGE_2_CONCEPTUAL_HINT
        state = decision.updatedLearnerState.copy(currentGuidanceStage = nextStage)

        val request = InstructionGenerationRequest(
            task = task,
            stage = nextStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )

        return PedagogicalDecisionEvent(
            taskId = task.taskId,
            previousStage = prevStage,
            newStage = nextStage,
            outcome = outcome,
            rationale = explanation,
            competenceEstimate = state.competenceEstimate,
            stageChanged = prevStage != nextStage,
            instruction = generator.generate(request)
        )
    }

    /**
     * Resets the learner model to a clean beginner baseline.
     */
    fun reset(learnerId: String = state.learnerId) {
        state = LearnerState(learnerId = learnerId)
    }

    /**
     * Persists the current state if a persistence provider is configured.
     */
    suspend fun persistState() {
        persistence?.save(state)
    }

    /**
     * Restores learner state from persistence.
     */
    suspend fun restoreState(learnerId: String) {
        val loaded = persistence?.load(learnerId)
        if (loaded != null) {
            state = loaded
        }
    }
}
