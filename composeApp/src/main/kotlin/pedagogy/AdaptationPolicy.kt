package pedagogy

/**
 * Configuration parameters for the deterministic adaptation policy.
 * All thresholds are explicit, positive integers and floating-point parameters.
 */
data class AdaptationPolicyConfig(
    val successesForFadingToStage2: Int = 2,
    val furtherSuccessesForFadingToStage3: Int = 1,
    val failuresForSupportStepBack: Int = 2,
    val failuresForStage1Fallback: Int = 3,
    val competenceGainOnSuccess: Double = 0.25,
    val competenceLossOnFailure: Double = 0.20
) {
    init {
        require(successesForFadingToStage2 > 0) { "successesForFadingToStage2 must be > 0" }
        require(furtherSuccessesForFadingToStage3 > 0) { "furtherSuccessesForFadingToStage3 must be > 0" }
        require(failuresForSupportStepBack > 0) { "failuresForSupportStepBack must be > 0" }
        require(failuresForStage1Fallback >= failuresForSupportStepBack) {
            "failuresForStage1Fallback must be >= failuresForSupportStepBack"
        }
        require(competenceGainOnSuccess in 0.0..1.0) { "competenceGainOnSuccess must be in 0.0..1.0" }
        require(competenceLossOnFailure in 0.0..1.0) { "competenceLossOnFailure must be in 0.0..1.0" }
    }
}

/**
 * Result of evaluating an adaptation step.
 */
data class PolicyDecision(
    val nextGuidanceStage: GuidanceStage,
    val updatedLearnerState: LearnerState,
    val rationale: String,
    val stageChanged: Boolean,
    val reassessmentRecommended: Boolean
)

/**
 * Deterministic adaptation policy implementing pedagogical fading and scaffolding recovery.
 * It governs guidance-level transitions based strictly on verified evidence without LLM dependency.
 */
class AdaptationPolicy(
    val config: AdaptationPolicyConfig = AdaptationPolicyConfig()
) {

    /**
     * Transition table summary:
     *
     * | Current Stage | Outcome | Condition | Next Stage | Rationale |
     * |---|---|---|---|---|
     * | Stage 1 | VERIFIED_SUCCESS | stageConsecutiveSuccesses >= 2 | Stage 2 | Demonstrated initial competence: faded to Conceptual Hint |
     * | Stage 1 | VERIFIED_SUCCESS | stageConsecutiveSuccesses < 2 | Stage 1 | Consecutive success 1/2: remaining at Direct Guidance |
     * | Stage 2 | VERIFIED_SUCCESS | stageConsecutiveSuccesses >= 1 && task.supportsStage3 | Stage 3 | Further verified competence: faded to Reflection Question |
     * | Stage 2 | VERIFIED_SUCCESS | stageConsecutiveSuccesses >= 1 && !task.supportsStage3 | Stage 2 | Demonstrated competence, but task not suitable for Stage 3 |
     * | Stage 2 | VERIFIED_SUCCESS | stageConsecutiveSuccesses < 1 | Stage 2 | Consecutive success at Stage 2 |
     * | Stage 3 | VERIFIED_SUCCESS | any | Stage 3 | Maintained independent reflection level |
     * | Any | VERIFIED_FAILURE | consecutiveFailures == 1 | Current Stage | First failure: retaining stage to reassess instruction |
     * | Stage 3 | VERIFIED_FAILURE | consecutiveFailures == 2 | Stage 2 | 2 consecutive failures: stepping back to Conceptual Hint |
     * | Stage 2 | VERIFIED_FAILURE | consecutiveFailures == 2 | Stage 1 | 2 consecutive failures: stepping back to Direct Guidance |
     * | Any | VERIFIED_FAILURE | consecutiveFailures >= 3 | Stage 1 | 3 consecutive failures: full scaffolding + reassessment flagged |
     * | Any | UNCERTAIN | any | Current Stage | Inconclusive verification: streaks and competence preserved |
     * | Any | NOT_EVALUATED | any | Current Stage | Missing evaluation: streaks and competence preserved |
     * | Any | Context Switch | different skill | Stage 1 | New skill context: beginner baseline without ungrounded transfer |
     */
    fun evaluateOutcome(
        currentState: LearnerState,
        task: LearningTask,
        outcome: PedagogicalOutcome,
        explanation: String = "",
        evidenceConfidence: Double? = null,
        verificationRunId: String? = null
    ): PolicyDecision {
        val currentProfile = currentState.profileForTask(task.taskId, task.skillId)

        return when (outcome) {
            PedagogicalOutcome.VERIFIED_SUCCESS -> handleSuccess(currentState, currentProfile, task, explanation, evidenceConfidence, verificationRunId)
            PedagogicalOutcome.VERIFIED_FAILURE -> handleFailure(currentState, currentProfile, task, explanation, evidenceConfidence, verificationRunId)
            PedagogicalOutcome.UNCERTAIN -> handleUncertain(currentState, currentProfile, task, explanation, evidenceConfidence, verificationRunId)
            PedagogicalOutcome.NOT_EVALUATED -> handleNotEvaluated(currentState, task)
        }
    }

    private fun handleSuccess(
        state: LearnerState,
        profile: TaskLearnerProfile,
        task: LearningTask,
        explanation: String,
        evidenceConfidence: Double?,
        verificationRunId: String? = null
    ): PolicyDecision {
        val newSuccesses = profile.consecutiveSuccesses + 1
        val newStageSuccesses = profile.stageConsecutiveSuccesses + 1
        val newCompetence = (profile.competenceEstimate + config.competenceGainOnSuccess).coerceAtMost(1.0)
        val currentStage = profile.guidanceStage

        var nextStageSuccesses = newStageSuccesses
        val (nextStage, rationale) = when (currentStage) {
            GuidanceStage.STAGE_1_EXACT_GUIDANCE -> {
                if (newStageSuccesses >= config.successesForFadingToStage2) {
                    nextStageSuccesses = 0
                    GuidanceStage.STAGE_2_CONCEPTUAL_HINT to
                        "Progressed to Stage 2 (Conceptual Hint) after $newStageSuccesses consecutive verified successes on '${task.title}'."
                } else {
                    GuidanceStage.STAGE_1_EXACT_GUIDANCE to
                        "Recorded verified success ($newStageSuccesses/${config.successesForFadingToStage2}) on '${task.title}'; continuing Stage 1."
                }
            }
            GuidanceStage.STAGE_2_CONCEPTUAL_HINT -> {
                if (newStageSuccesses >= config.furtherSuccessesForFadingToStage3) {
                    if (task.supportsStage3) {
                        nextStageSuccesses = 0
                        GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION to
                            "Progressed to Stage 3 (Reflection Question) after demonstrated competence on '${task.title}'."
                    } else {
                        GuidanceStage.STAGE_2_CONCEPTUAL_HINT to
                            "Verified success on '${task.title}'; task is flagged as unsuitable for Stage 3 reflection, retaining Stage 2."
                    }
                } else {
                    GuidanceStage.STAGE_2_CONCEPTUAL_HINT to
                        "Recorded verified success ($newStageSuccesses/${config.furtherSuccessesForFadingToStage3}) at Stage 2 on '${task.title}'."
                }
            }
            GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION -> {
                GuidanceStage.STAGE_3_SELF_CORRECTION_QUESTION to
                    "Maintained Stage 3 (Reflection Question) with continued verified success on '${task.title}'."
            }
        }

        val updatedProfile = profile.copy(
            guidanceStage = nextStage,
            consecutiveSuccesses = newSuccesses,
            stageConsecutiveSuccesses = nextStageSuccesses,
            consecutiveFailures = 0,
            totalSuccesses = profile.totalSuccesses + 1,
            competenceEstimate = newCompetence,
            lastOutcome = PedagogicalOutcome.VERIFIED_SUCCESS,
            lastAttemptAt = java.time.Instant.now()
        )

        val updatedProfiles = state.taskProfiles + (task.taskId to updatedProfile)
        val attempt = PedagogicalAttempt(
            taskId = task.taskId,
            skillId = task.skillId,
            guidanceStage = currentStage,
            outcome = PedagogicalOutcome.VERIFIED_SUCCESS,
            verificationRunId = verificationRunId,
            explanation = explanation,
            evidenceConfidence = evidenceConfidence
        )

        val updatedState = state.copy(
            currentTaskId = task.taskId,
            currentSkillId = task.skillId,
            currentGuidanceStage = nextStage,
            consecutiveSuccesses = newSuccesses,
            stageConsecutiveSuccesses = nextStageSuccesses,
            consecutiveFailures = 0,
            totalVerifiedSuccesses = state.totalVerifiedSuccesses + 1,
            competenceEstimate = newCompetence,
            evidenceConfidence = evidenceConfidence ?: state.evidenceConfidence,
            adaptationReason = rationale,
            requiresReassessment = false,
            recentAttempts = (state.recentAttempts + attempt).takeLast(50),
            taskProfiles = updatedProfiles
        )

        return PolicyDecision(
            nextGuidanceStage = nextStage,
            updatedLearnerState = updatedState,
            rationale = rationale,
            stageChanged = nextStage != currentStage,
            reassessmentRecommended = false
        )
    }

    private fun handleFailure(
        state: LearnerState,
        profile: TaskLearnerProfile,
        task: LearningTask,
        explanation: String,
        evidenceConfidence: Double?,
        verificationRunId: String? = null
    ): PolicyDecision {
        val newFailures = profile.consecutiveFailures + 1
        val newCompetence = (profile.competenceEstimate - config.competenceLossOnFailure).coerceAtLeast(0.0)
        val currentStage = profile.guidanceStage

        var reassess = false
        val (nextStage, rationale) = when {
            newFailures >= config.failuresForStage1Fallback -> {
                reassess = true
                GuidanceStage.STAGE_1_EXACT_GUIDANCE to
                    "Reverted to Stage 1 (Direct Guidance) after $newFailures consecutive verified failures on '${task.title}'. Target availability and task interpretation reassessment recommended."
            }
            newFailures >= config.failuresForSupportStepBack -> {
                val stepped = currentStage.nextMoreSupport
                stepped to
                    "Increased support from ${currentStage.displayName} to ${stepped.displayName} after $newFailures consecutive verified failures on '${task.title}'."
            }
            else -> {
                currentStage to
                    "Recorded verified failure (attempt $newFailures) on '${task.title}'; maintaining ${currentStage.displayName} to assess instruction adequacy."
            }
        }

        val updatedProfile = profile.copy(
            guidanceStage = nextStage,
            consecutiveSuccesses = 0,
            stageConsecutiveSuccesses = 0,
            consecutiveFailures = newFailures,
            totalFailures = profile.totalFailures + 1,
            competenceEstimate = newCompetence,
            lastOutcome = PedagogicalOutcome.VERIFIED_FAILURE,
            lastAttemptAt = java.time.Instant.now()
        )

        val updatedProfiles = state.taskProfiles + (task.taskId to updatedProfile)
        val attempt = PedagogicalAttempt(
            taskId = task.taskId,
            skillId = task.skillId,
            guidanceStage = currentStage,
            outcome = PedagogicalOutcome.VERIFIED_FAILURE,
            verificationRunId = verificationRunId,
            explanation = explanation,
            evidenceConfidence = evidenceConfidence
        )

        val updatedState = state.copy(
            currentTaskId = task.taskId,
            currentSkillId = task.skillId,
            currentGuidanceStage = nextStage,
            consecutiveSuccesses = 0,
            stageConsecutiveSuccesses = 0,
            consecutiveFailures = newFailures,
            totalVerifiedFailures = state.totalVerifiedFailures + 1,
            competenceEstimate = newCompetence,
            evidenceConfidence = evidenceConfidence ?: state.evidenceConfidence,
            adaptationReason = rationale,
            requiresReassessment = reassess,
            recentAttempts = (state.recentAttempts + attempt).takeLast(50),
            taskProfiles = updatedProfiles
        )

        return PolicyDecision(
            nextGuidanceStage = nextStage,
            updatedLearnerState = updatedState,
            rationale = rationale,
            stageChanged = nextStage != currentStage,
            reassessmentRecommended = reassess
        )
    }

    private fun handleUncertain(
        state: LearnerState,
        profile: TaskLearnerProfile,
        task: LearningTask,
        explanation: String,
        evidenceConfidence: Double?,
        verificationRunId: String? = null
    ): PolicyDecision {
        val rationale = "Verification outcome was uncertain ($explanation). Guidance stage (${profile.guidanceStage.displayName}) and competence streaks preserved pending clearer evidence."

        val updatedProfile = profile.copy(
            totalUncertain = profile.totalUncertain + 1,
            lastOutcome = PedagogicalOutcome.UNCERTAIN,
            lastAttemptAt = java.time.Instant.now()
        )

        val attempt = PedagogicalAttempt(
            taskId = task.taskId,
            skillId = task.skillId,
            guidanceStage = profile.guidanceStage,
            outcome = PedagogicalOutcome.UNCERTAIN,
            verificationRunId = verificationRunId,
            explanation = explanation,
            evidenceConfidence = evidenceConfidence
        )

        val updatedState = state.copy(
            currentTaskId = task.taskId,
            currentSkillId = task.skillId,
            totalUncertainOutcomes = state.totalUncertainOutcomes + 1,
            evidenceConfidence = evidenceConfidence ?: state.evidenceConfidence,
            adaptationReason = rationale,
            recentAttempts = (state.recentAttempts + attempt).takeLast(50),
            taskProfiles = state.taskProfiles + (task.taskId to updatedProfile)
        )

        return PolicyDecision(
            nextGuidanceStage = profile.guidanceStage,
            updatedLearnerState = updatedState,
            rationale = rationale,
            stageChanged = false,
            reassessmentRecommended = false
        )
    }

    private fun handleNotEvaluated(
        state: LearnerState,
        task: LearningTask
    ): PolicyDecision {
        val rationale = "Action on '${task.title}' was not evaluated; guidance stage and competence preserved."
        return PolicyDecision(
            nextGuidanceStage = state.currentGuidanceStage,
            updatedLearnerState = state.copy(adaptationReason = rationale),
            rationale = rationale,
            stageChanged = false,
            reassessmentRecommended = false
        )
    }

    /**
     * Re-evaluates guidance stage when switching to a new learning task.
     * Prevents improper transfer across unrelated skill domains.
     */
    fun onTaskSwitched(
        currentState: LearnerState,
        newTask: LearningTask
    ): PolicyDecision {
        val existingProfile = currentState.taskProfiles[newTask.taskId]

        if (existingProfile != null) {
            val rationale = "Resumed existing profile for task '${newTask.title}' at ${existingProfile.guidanceStage.displayName}."
            val updatedState = currentState.copy(
                currentTaskId = newTask.taskId,
                currentSkillId = newTask.skillId,
                currentGuidanceStage = existingProfile.guidanceStage,
                consecutiveSuccesses = existingProfile.consecutiveSuccesses,
                consecutiveFailures = existingProfile.consecutiveFailures,
                competenceEstimate = existingProfile.competenceEstimate,
                adaptationReason = rationale
            )
            return PolicyDecision(
                nextGuidanceStage = existingProfile.guidanceStage,
                updatedLearnerState = updatedState,
                rationale = rationale,
                stageChanged = existingProfile.guidanceStage != currentState.currentGuidanceStage,
                reassessmentRecommended = false
            )
        }

        // Check if there is transferable competence within the same skill domain
        val domainProfiles = currentState.taskProfiles.values.filter { it.skillId == newTask.skillId }
        val (initialStage, initialCompetence, rationale) = if (domainProfiles.isNotEmpty()) {
            val avgCompetence = domainProfiles.map { it.competenceEstimate }.average()
            val candidateStage = if (avgCompetence >= 0.7 && newTask.supportsStage3) {
                GuidanceStage.STAGE_2_CONCEPTUAL_HINT
            } else if (avgCompetence >= 0.4) {
                GuidanceStage.STAGE_2_CONCEPTUAL_HINT
            } else {
                GuidanceStage.STAGE_1_EXACT_GUIDANCE
            }
            Triple(
                candidateStage,
                avgCompetence * 0.7, // conservative domain transfer
                "Task '${newTask.title}' shares skill domain '${newTask.skillId}'; initialized at ${candidateStage.displayName} based on domain history."
            )
        } else {
            Triple(
                GuidanceStage.STAGE_1_EXACT_GUIDANCE,
                0.0,
                "New task '${newTask.title}' in skill domain '${newTask.skillId}'; defaulting to Stage 1 (Direct Guidance) without cross-domain transfer."
            )
        }

        val newProfile = TaskLearnerProfile(
            taskId = newTask.taskId,
            skillId = newTask.skillId,
            guidanceStage = initialStage,
            competenceEstimate = initialCompetence
        )

        val updatedState = currentState.copy(
            currentTaskId = newTask.taskId,
            currentSkillId = newTask.skillId,
            currentGuidanceStage = initialStage,
            consecutiveSuccesses = 0,
            consecutiveFailures = 0,
            competenceEstimate = initialCompetence,
            adaptationReason = rationale,
            requiresReassessment = false,
            taskProfiles = currentState.taskProfiles + (newTask.taskId to newProfile)
        )

        return PolicyDecision(
            nextGuidanceStage = initialStage,
            updatedLearnerState = updatedState,
            rationale = rationale,
            stageChanged = initialStage != currentState.currentGuidanceStage,
            reassessmentRecommended = false
        )
    }
}
