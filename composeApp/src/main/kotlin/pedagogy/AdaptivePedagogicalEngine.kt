package pedagogy

import context.ObservedState
import context.VerificationResult
import context.VisualContext
import java.util.UUID

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
    val instruction: AdaptiveInstruction,
    val strategy: AdaptationStrategy = AdaptationStrategy.NORMAL_GUIDANCE
)

/**
 * Core Adaptive Pedagogical Engine for Phase 13.
 * Coordinates learner modeling, deterministic adaptation policy, trustworthy learning event boundary,
 * evidence-based skill mastery tracking, and persistent progress restoration.
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
     * according to the learner's active guidance stage, demonstrated skills, and preferences.
     */
    fun getInstruction(
        task: LearningTask,
        visualContext: VisualContext? = null,
        observedState: ObservedState? = null
    ): AdaptiveInstruction {
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
     * Ingests a trustworthy [LearningEvent] from the tutoring workflow.
     * Guarantees idempotency and protects learner progress against duplicate, stale, or conflicting events.
     */
    fun ingestLearningEvent(
        event: LearningEvent,
        task: LearningTask? = null,
        visualContext: VisualContext? = null,
        observedState: ObservedState? = null
    ): PedagogicalDecisionEvent {
        val prevStage = state.currentGuidanceStage

        // Guard against duplicate event delivery (Rule 3 idempotency requirement)
        val isDuplicateRun = event is LearningEvent.OutcomeVerified && event.result.runId.isNotBlank() &&
            state.recentAttempts.any { it.verificationRunId == event.result.runId }
        val isDuplicateEvent = event.eventId.isNotBlank() && state.processedEventIds.contains(event.eventId)

        if (isDuplicateRun || isDuplicateEvent) {
            val runIdInfo = if (event is LearningEvent.OutcomeVerified && event.result.runId.isNotBlank()) event.result.runId else event.eventId
            val duplicateRationale = "Duplicate verification run '$runIdInfo' already recorded; state preserved."
            val currentTask = task ?: fallbackTaskForId(state.currentTaskId, state.currentSkillId)
            val request = InstructionGenerationRequest(
                task = currentTask,
                stage = state.currentGuidanceStage,
                learnerState = state,
                visualContext = visualContext,
                observedState = observedState,
                previousAttempts = state.recentAttempts
            )
            val outcome = if (event is LearningEvent.OutcomeVerified) PedagogicalOutcome.fromVerificationResult(event.result) else PedagogicalOutcome.NOT_EVALUATED
            return PedagogicalDecisionEvent(
                taskId = currentTask.taskId,
                previousStage = prevStage,
                newStage = state.currentGuidanceStage,
                outcome = outcome,
                rationale = duplicateRationale,
                competenceEstimate = state.competenceEstimate,
                stageChanged = false,
                instruction = generator.generate(request)
            )
        }

        // Validate learner identity isolation
        if (!event.learnerId.equals(state.learnerId, ignoreCase = true)) {
            val mismatchRationale = "Ignored event targeting learner '${event.learnerId}'; current session is '${state.learnerId}'."
            val currentTask = task ?: fallbackTaskForId(state.currentTaskId, state.currentSkillId)
            val request = InstructionGenerationRequest(
                task = currentTask,
                stage = state.currentGuidanceStage,
                learnerState = state,
                visualContext = visualContext,
                observedState = observedState,
                previousAttempts = state.recentAttempts
            )
            return PedagogicalDecisionEvent(
                taskId = currentTask.taskId,
                previousStage = prevStage,
                newStage = state.currentGuidanceStage,
                outcome = PedagogicalOutcome.NOT_EVALUATED,
                rationale = mismatchRationale,
                competenceEstimate = state.competenceEstimate,
                stageChanged = false,
                instruction = generator.generate(request)
            )
        }

        return when (event) {
            is LearningEvent.OutcomeVerified -> handleOutcomeVerifiedEvent(event, task, visualContext, observedState)
            is LearningEvent.PreferenceUpdated -> handlePreferenceUpdatedEvent(event, task, visualContext, observedState)
            is LearningEvent.RecoveryAttemptEvaluated -> handleRecoveryAttemptEvent(event, task, visualContext, observedState)
            is LearningEvent.ResetRequested -> handleResetRequestedEvent(event, task, visualContext, observedState)
        }
    }

    private fun handleOutcomeVerifiedEvent(
        event: LearningEvent.OutcomeVerified,
        task: LearningTask?,
        visualContext: VisualContext?,
        observedState: ObservedState?
    ): PedagogicalDecisionEvent {
        val prevStage = state.currentGuidanceStage
        val taskToUse = task ?: fallbackTaskForId(event.taskId, event.skillId)

        // Ensure state is tuned to the target task
        if (state.currentTaskId != taskToUse.taskId) {
            val switchDecision = policy.onTaskSwitched(state, taskToUse)
            state = switchDecision.updatedLearnerState
        }

        val outcome = PedagogicalOutcome.fromVerificationResult(event.result)
        val evidenceConf = if (event.result.evidence.isNotEmpty()) {
            val available = event.result.evidence.count { it.available }
            available.toDouble() / event.result.evidence.size.toDouble()
        } else null

        val decision = policy.evaluateOutcome(
            currentState = state,
            task = taskToUse,
            outcome = outcome,
            explanation = event.result.explanation,
            evidenceConfidence = evidenceConf,
            verificationRunId = event.result.runId,
            mismatchCategory = event.mismatchCategory,
            failureCause = event.failureCause
        )

        // Mark event ID as processed for idempotency
        state = decision.updatedLearnerState.copy(
            processedEventIds = decision.updatedLearnerState.processedEventIds + event.eventId
        )

        val request = InstructionGenerationRequest(
            task = taskToUse,
            stage = decision.nextGuidanceStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )
        val nextInstruction = generator.generate(request)

        return PedagogicalDecisionEvent(
            taskId = taskToUse.taskId,
            previousStage = prevStage,
            newStage = decision.nextGuidanceStage,
            outcome = outcome,
            rationale = decision.rationale,
            competenceEstimate = state.competenceEstimate,
            stageChanged = decision.stageChanged,
            instruction = nextInstruction,
            strategy = decision.strategy
        )
    }

    private fun handlePreferenceUpdatedEvent(
        event: LearningEvent.PreferenceUpdated,
        task: LearningTask?,
        visualContext: VisualContext?,
        observedState: ObservedState?
    ): PedagogicalDecisionEvent {
        val prevStage = state.currentGuidanceStage
        val currentTask = task ?: fallbackTaskForId(state.currentTaskId, state.currentSkillId)
        val targetStage = event.preferences.preferredGuidanceStage ?: state.currentGuidanceStage

        state = state.copy(
            preferences = event.preferences,
            currentGuidanceStage = targetStage,
            processedEventIds = state.processedEventIds + event.eventId
        )

        val request = InstructionGenerationRequest(
            task = currentTask,
            stage = targetStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )
        val nextInstruction = generator.generate(request)

        return PedagogicalDecisionEvent(
            taskId = currentTask.taskId,
            previousStage = prevStage,
            newStage = targetStage,
            outcome = PedagogicalOutcome.NOT_EVALUATED,
            rationale = "Learner preferences updated: detail=${event.preferences.detailPreference.name}, stage=${targetStage.displayName}.",
            competenceEstimate = state.competenceEstimate,
            stageChanged = prevStage != targetStage,
            instruction = nextInstruction,
            strategy = if (event.preferences.detailPreference == DetailPreference.CONCISE) AdaptationStrategy.CONCISE_INSTRUCTION else AdaptationStrategy.NORMAL_GUIDANCE
        )
    }

    private fun handleRecoveryAttemptEvent(
        event: LearningEvent.RecoveryAttemptEvaluated,
        task: LearningTask?,
        visualContext: VisualContext?,
        observedState: ObservedState?
    ): PedagogicalDecisionEvent {
        val currentTask = task ?: fallbackTaskForId(state.currentTaskId, state.currentSkillId)
        state = state.copy(
            processedEventIds = state.processedEventIds + event.eventId
        )

        val request = InstructionGenerationRequest(
            task = currentTask,
            stage = state.currentGuidanceStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )
        return PedagogicalDecisionEvent(
            taskId = currentTask.taskId,
            previousStage = state.currentGuidanceStage,
            newStage = state.currentGuidanceStage,
            outcome = PedagogicalOutcome.NOT_EVALUATED,
            rationale = "Recovery attempt ${event.attemptNumber} logged (${event.status.name}): ${event.recoveryOutcome}.",
            competenceEstimate = state.competenceEstimate,
            stageChanged = false,
            instruction = generator.generate(request)
        )
    }

    private fun handleResetRequestedEvent(
        event: LearningEvent.ResetRequested,
        task: LearningTask?,
        visualContext: VisualContext?,
        observedState: ObservedState?
    ): PedagogicalDecisionEvent {
        val prevStage = state.currentGuidanceStage
        state = LearnerState(
            learnerId = event.learnerId,
            processedEventIds = setOf(event.eventId)
        )
        val currentTask = task ?: fallbackTaskForId(state.currentTaskId, state.currentSkillId)
        val request = InstructionGenerationRequest(
            task = currentTask,
            stage = state.currentGuidanceStage,
            learnerState = state,
            visualContext = visualContext,
            observedState = observedState,
            previousAttempts = state.recentAttempts
        )
        return PedagogicalDecisionEvent(
            taskId = currentTask.taskId,
            previousStage = prevStage,
            newStage = state.currentGuidanceStage,
            outcome = PedagogicalOutcome.NOT_EVALUATED,
            rationale = "Learner progress reset to clean baseline by explicit request.",
            competenceEstimate = 0.0,
            stageChanged = prevStage != state.currentGuidanceStage,
            instruction = generator.generate(request)
        )
    }

    /**
     * Backward-compatible entrypoint consuming Phase 6 VerificationResult.
     */
    fun onVerificationResult(
        task: LearningTask,
        result: VerificationResult,
        visualContext: VisualContext? = null,
        observedState: ObservedState? = null,
        mismatchCategory: String? = null,
        failureCause: FailureCause = FailureCause.LEARNER_ACTION
    ): PedagogicalDecisionEvent {
        val eventId = if (result.runId.isNotBlank()) "event_${result.runId}" else UUID.randomUUID().toString()
        val event = LearningEvent.OutcomeVerified(
            eventId = eventId,
            learnerId = state.learnerId,
            taskId = task.taskId,
            skillId = task.skillId,
            result = result,
            mismatchCategory = mismatchCategory,
            failureCause = failureCause
        )
        return ingestLearningEvent(event, task, visualContext, observedState)
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
            instruction = nextInstruction,
            strategy = decision.strategy
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
            instruction = generator.generate(request),
            strategy = decision.strategy
        )
    }

    /**
     * Resets the learner model to a clean beginner baseline.
     */
    fun reset(learnerId: String = state.learnerId) {
        state = LearnerState(learnerId = learnerId)
    }

    /**
     * Updates learner preferences idempotently.
     */
    fun updatePreferences(preferences: LearnerPreferences): PedagogicalDecisionEvent {
        val event = LearningEvent.PreferenceUpdated(
            learnerId = state.learnerId,
            preferences = preferences
        )
        return ingestLearningEvent(event)
    }

    /**
     * Persists the current state if a persistence provider is configured.
     */
    suspend fun persistState(): Boolean {
        return persistence?.save(state) ?: false
    }

    /**
     * Restores learner state from persistence.
     */
    suspend fun restoreState(learnerId: String): Boolean {
        val loaded = persistence?.load(learnerId)
        return if (loaded != null) {
            state = loaded
            true
        } else {
            false
        }
    }

    private fun fallbackTaskForId(taskId: String, skillId: String): LearningTask {
        return LearningTask(
            taskId = taskId,
            skillId = skillId,
            title = taskId,
            objective = "Perform action for $taskId",
            exactInstruction = "Perform action for $taskId.",
            conceptualHint = "Review the tools required for $taskId.",
            reflectionQuestion = "Which command accomplishes $taskId?"
        )
    }
}
