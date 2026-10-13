package pedagogy

import java.time.Instant

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
    val reassessmentRecommended: Boolean,
    val strategy: AdaptationStrategy = AdaptationStrategy.NORMAL_GUIDANCE
)

/**
 * Deterministic adaptation policy implementing pedagogical fading, scaffolding recovery,
 * evidence-based skill mastery tracking, and explainable instruction adaptation.
 *
 * Governs guidance transitions based strictly on verified evidence without LLM dependency.
 */
class AdaptationPolicy(
    val config: AdaptationPolicyConfig = AdaptationPolicyConfig()
) {

    /**
     * Evaluates a verified pedagogical outcome against the current learner model.
     */
    fun evaluateOutcome(
        currentState: LearnerState,
        task: LearningTask,
        outcome: PedagogicalOutcome,
        explanation: String = "",
        evidenceConfidence: Double? = null,
        verificationRunId: String? = null,
        mismatchCategory: String? = null,
        failureCause: FailureCause = FailureCause.LEARNER_ACTION
    ): PolicyDecision {
        val currentProfile = currentState.profileForTask(task.taskId, task.skillId)

        return when (outcome) {
            PedagogicalOutcome.VERIFIED_SUCCESS -> handleSuccess(
                currentState, currentProfile, task, explanation, evidenceConfidence, verificationRunId
            )
            PedagogicalOutcome.VERIFIED_FAILURE -> handleFailure(
                currentState, currentProfile, task, explanation, evidenceConfidence, verificationRunId, mismatchCategory, failureCause
            )
            PedagogicalOutcome.UNCERTAIN -> handleUncertain(
                currentState, currentProfile, task, explanation, evidenceConfidence, verificationRunId
            )
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
        var (nextStage, rationale) = when (currentStage) {
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

        // Respect explicit learner preference for guidance stage if specified
        if (state.preferences.preferredGuidanceStage != null) {
            nextStage = state.preferences.preferredGuidanceStage
            rationale += " Explicit learner preference applied (${nextStage.displayName})."
        }

        val updatedProfile = profile.copy(
            guidanceStage = nextStage,
            consecutiveSuccesses = newSuccesses,
            stageConsecutiveSuccesses = nextStageSuccesses,
            consecutiveFailures = 0,
            totalSuccesses = profile.totalSuccesses + 1,
            competenceEstimate = newCompetence,
            lastOutcome = PedagogicalOutcome.VERIFIED_SUCCESS,
            lastAttemptAt = Instant.now()
        )

        // Update demonstrated skill evidence (Rule 3: tied strictly to verified evidence)
        val currentSkill = state.demonstratedSkills[task.skillId] ?: DemonstratedSkill(
            skillId = task.skillId,
            displayName = formatSkillName(task.skillId)
        )
        val skillSuccesses = currentSkill.verifiedSuccessCount + 1
        val skillConsecutive = currentSkill.consecutiveSuccessCount + 1
        val skillMastery = SkillMasteryLevel.fromSuccessCounts(skillSuccesses, skillConsecutive)
        val updatedSkill = currentSkill.copy(
            masteryLevel = skillMastery,
            verifiedSuccessCount = skillSuccesses,
            consecutiveSuccessCount = skillConsecutive,
            totalAttempts = currentSkill.totalAttempts + 1,
            lastVerifiedAt = Instant.now()
        )
        val updatedSkills = state.demonstratedSkills + (task.skillId to updatedSkill)

        // Resolve recurring difficulties on this skill if learner consistently succeeds
        val updatedDifficulties = if (skillConsecutive >= 2) {
            state.recordedDifficulties.filterKeys { !it.startsWith(task.skillId) }
        } else {
            state.recordedDifficulties
        }

        // Determine adaptation strategy
        val strategy = when {
            state.preferences.detailPreference == DetailPreference.CONCISE ||
                skillMastery == SkillMasteryLevel.CONSISTENTLY_DEMONSTRATED -> AdaptationStrategy.CONCISE_INSTRUCTION
            state.preferences.detailPreference == DetailPreference.DETAILED -> AdaptationStrategy.DETAILED_INSTRUCTION
            else -> AdaptationStrategy.NORMAL_GUIDANCE
        }

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

        val decisionRecord = AdaptationDecisionRecord(
            taskId = task.taskId,
            skillId = task.skillId,
            strategy = strategy,
            evidenceReason = rationale
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
            taskProfiles = updatedProfiles,
            demonstratedSkills = updatedSkills,
            verifiedOutcomes = state.verifiedOutcomes + task.taskId,
            recordedDifficulties = updatedDifficulties,
            adaptationDecisions = (state.adaptationDecisions + decisionRecord).takeLast(50),
            updatedAt = Instant.now()
        )

        return PolicyDecision(
            nextGuidanceStage = nextStage,
            updatedLearnerState = updatedState,
            rationale = rationale,
            stageChanged = nextStage != currentStage,
            reassessmentRecommended = false,
            strategy = strategy
        )
    }

    private fun handleFailure(
        state: LearnerState,
        profile: TaskLearnerProfile,
        task: LearningTask,
        explanation: String,
        evidenceConfidence: Double?,
        verificationRunId: String?,
        mismatchCategory: String?,
        failureCause: FailureCause
    ): PolicyDecision {
        // Rule 2: Failed attempts are not automatically learner weaknesses
        if (failureCause == FailureCause.INFRASTRUCTURE_OR_PROVIDER || failureCause == FailureCause.UNCERTAIN_EVIDENCE) {
            val infraRationale = "Verification outcome unconfirmed due to telemetry/provider condition ($explanation). Learner competence and skill mastery preserved."
            val attempt = PedagogicalAttempt(
                taskId = task.taskId,
                skillId = task.skillId,
                guidanceStage = profile.guidanceStage,
                outcome = PedagogicalOutcome.UNCERTAIN,
                verificationRunId = verificationRunId,
                explanation = infraRationale,
                evidenceConfidence = evidenceConfidence
            )
            val updatedState = state.copy(
                adaptationReason = infraRationale,
                totalUncertainOutcomes = state.totalUncertainOutcomes + 1,
                recentAttempts = (state.recentAttempts + attempt).takeLast(50),
                updatedAt = Instant.now()
            )
            return PolicyDecision(
                nextGuidanceStage = profile.guidanceStage,
                updatedLearnerState = updatedState,
                rationale = infraRationale,
                stageChanged = false,
                reassessmentRecommended = false,
                strategy = AdaptationStrategy.NORMAL_GUIDANCE
            )
        }

        val newFailures = profile.consecutiveFailures + 1
        val newCompetence = (profile.competenceEstimate - config.competenceLossOnFailure).coerceAtLeast(0.0)
        val currentStage = profile.guidanceStage

        var reassess = false
        var (nextStage, rationale) = when {
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

        // Respect explicit learner preference for guidance stage if specified
        if (state.preferences.preferredGuidanceStage != null) {
            nextStage = state.preferences.preferredGuidanceStage
            rationale += " Explicit learner preference applied (${nextStage.displayName})."
        }

        // Record learner difficulty only on repeated (2+) verified errors (Rule 2 & 3)
        var updatedDifficulties = state.recordedDifficulties
        var practiceRecs = state.practiceRecommendations
        var strategy = when {
            state.preferences.detailPreference == DetailPreference.DETAILED -> AdaptationStrategy.DETAILED_INSTRUCTION
            state.preferences.detailPreference == DetailPreference.CONCISE -> AdaptationStrategy.CONCISE_INSTRUCTION
            else -> AdaptationStrategy.NORMAL_GUIDANCE
        }

        if (newFailures >= 2) {
            val diffKey = "${task.skillId}_${mismatchCategory ?: "general"}"
            val existingDiff = state.recordedDifficulties[diffKey]
            val occurrence = (existingDiff?.occurrenceCount ?: 1) + 1
            val updatedDiff = LearnerDifficulty(
                difficultyId = existingDiff?.difficultyId ?: java.util.UUID.randomUUID().toString(),
                skillId = task.skillId,
                errorCategory = mismatchCategory ?: "State Mismatch",
                occurrenceCount = occurrence,
                explanation = explanation.ifBlank { "Repeated state mismatch observed on '${task.title}'" },
                firstOccurredAt = existingDiff?.firstOccurredAt ?: Instant.now(),
                lastOccurredAt = Instant.now()
            )
            updatedDifficulties = state.recordedDifficulties + (diffKey to updatedDiff)

            strategy = if (state.preferences.wantsSmallerSteps || newFailures >= config.failuresForStage1Fallback) {
                AdaptationStrategy.SMALLER_STEP
            } else {
                AdaptationStrategy.DETAILED_INSTRUCTION
            }
            rationale += " Repeated verified difficulty ($mismatchCategory) detected; adapting instruction strategy to ${strategy.name}."

            // Generate targeted practice recommendation if enabled
            if (state.preferences.practiceRecommendationsEnabled && practiceRecs.none { it.skillId == task.skillId && !it.isCompleted }) {
                val rec = PracticeRecommendation(
                    skillId = task.skillId,
                    title = "Targeted Practice: ${task.title}",
                    description = "Take a moment to practice '${task.title}' in isolation to build confidence.",
                    suggestedGoalText = "Practice ${task.title.lowercase()}",
                    rationale = "Repeated verified difficulty ($mismatchCategory) on ${formatSkillName(task.skillId)}."
                )
                practiceRecs = practiceRecs + rec
            }
        }

        // Reset consecutive successes for demonstrated skill without permanently wiping historical practiced count
        val currentSkill = state.demonstratedSkills[task.skillId]
        val updatedSkills = if (currentSkill != null) {
            val resetSkill = currentSkill.copy(
                consecutiveSuccessCount = 0,
                totalAttempts = currentSkill.totalAttempts + 1
            )
            state.demonstratedSkills + (task.skillId to resetSkill)
        } else {
            state.demonstratedSkills
        }

        val updatedProfile = profile.copy(
            guidanceStage = nextStage,
            consecutiveSuccesses = 0,
            stageConsecutiveSuccesses = 0,
            consecutiveFailures = newFailures,
            totalFailures = profile.totalFailures + 1,
            competenceEstimate = newCompetence,
            lastOutcome = PedagogicalOutcome.VERIFIED_FAILURE,
            lastAttemptAt = Instant.now()
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

        val decisionRecord = AdaptationDecisionRecord(
            taskId = task.taskId,
            skillId = task.skillId,
            strategy = strategy,
            evidenceReason = rationale
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
            taskProfiles = updatedProfiles,
            demonstratedSkills = updatedSkills,
            recordedDifficulties = updatedDifficulties,
            practiceRecommendations = practiceRecs,
            adaptationDecisions = (state.adaptationDecisions + decisionRecord).takeLast(50),
            updatedAt = Instant.now()
        )

        return PolicyDecision(
            nextGuidanceStage = nextStage,
            updatedLearnerState = updatedState,
            rationale = rationale,
            stageChanged = nextStage != currentStage,
            reassessmentRecommended = reassess,
            strategy = strategy
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
            lastAttemptAt = Instant.now()
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
            taskProfiles = state.taskProfiles + (task.taskId to updatedProfile),
            updatedAt = Instant.now()
        )

        return PolicyDecision(
            nextGuidanceStage = profile.guidanceStage,
            updatedLearnerState = updatedState,
            rationale = rationale,
            stageChanged = false,
            reassessmentRecommended = false,
            strategy = AdaptationStrategy.NORMAL_GUIDANCE
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
            reassessmentRecommended = false,
            strategy = AdaptationStrategy.NORMAL_GUIDANCE
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
            val stageToUse = currentState.preferences.preferredGuidanceStage ?: existingProfile.guidanceStage
            val rationale = "Resumed existing profile for task '${newTask.title}' at ${stageToUse.displayName}."
            val updatedState = currentState.copy(
                currentTaskId = newTask.taskId,
                currentSkillId = newTask.skillId,
                currentGuidanceStage = stageToUse,
                consecutiveSuccesses = existingProfile.consecutiveSuccesses,
                consecutiveFailures = existingProfile.consecutiveFailures,
                competenceEstimate = existingProfile.competenceEstimate,
                adaptationReason = rationale,
                updatedAt = Instant.now()
            )
            return PolicyDecision(
                nextGuidanceStage = stageToUse,
                updatedLearnerState = updatedState,
                rationale = rationale,
                stageChanged = stageToUse != currentState.currentGuidanceStage,
                reassessmentRecommended = false,
                strategy = if (currentState.preferences.detailPreference == DetailPreference.CONCISE) AdaptationStrategy.CONCISE_INSTRUCTION else AdaptationStrategy.NORMAL_GUIDANCE
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
                avgCompetence * 0.7,
                "Task '${newTask.title}' shares skill domain '${newTask.skillId}'; initialized at ${candidateStage.displayName} based on domain history."
            )
        } else {
            Triple(
                GuidanceStage.STAGE_1_EXACT_GUIDANCE,
                0.0,
                "New task '${newTask.title}' in skill domain '${newTask.skillId}'; defaulting to Stage 1 (Direct Guidance) without cross-domain transfer."
            )
        }

        val stageToUse = currentState.preferences.preferredGuidanceStage ?: initialStage
        val newProfile = TaskLearnerProfile(
            taskId = newTask.taskId,
            skillId = newTask.skillId,
            guidanceStage = stageToUse,
            competenceEstimate = initialCompetence
        )

        val updatedState = currentState.copy(
            currentTaskId = newTask.taskId,
            currentSkillId = newTask.skillId,
            currentGuidanceStage = stageToUse,
            consecutiveSuccesses = 0,
            consecutiveFailures = 0,
            competenceEstimate = initialCompetence,
            adaptationReason = rationale,
            requiresReassessment = false,
            taskProfiles = currentState.taskProfiles + (newTask.taskId to newProfile),
            updatedAt = Instant.now()
        )

        return PolicyDecision(
            nextGuidanceStage = stageToUse,
            updatedLearnerState = updatedState,
            rationale = rationale,
            stageChanged = stageToUse != currentState.currentGuidanceStage,
            reassessmentRecommended = false,
            strategy = if (currentState.preferences.detailPreference == DetailPreference.CONCISE) AdaptationStrategy.CONCISE_INSTRUCTION else AdaptationStrategy.NORMAL_GUIDANCE
        )
    }

    private fun formatSkillName(skillId: String): String {
        return skillId.substringAfterLast('.')
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }
}
