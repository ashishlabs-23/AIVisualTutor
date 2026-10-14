package tutor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import context.BlenderObservationStatus
import context.BlenderVerificationWorkflow
import context.ExpectedState
import context.MismatchCategory
import context.ObservedState
import context.StateMismatchDiagnosticEngine
import context.StateMismatchReport
import context.VerificationOperation
import context.VerificationResult
import context.VerificationRun
import context.VerificationStatus
import context.toObservedState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pedagogy.GuidanceStage
import java.util.UUID

/**
 * Closed-Loop Recovery Coordinator for Phase 11.
 * Coordinates:
 * 1. Post-action state capture and authoritative verification via [BlenderVerificationWorkflow].
 * 2. Deterministic failure diagnosis via [StateMismatchDiagnosticEngine].
 * 3. Contextual, adaptive recovery recommendations avoiding repetitive loops.
 * 4. Bounded recovery policy preventing infinite loops.
 * 5. Strict lesson continuity and non-autonomous learner agency.
 */
class ClosedLoopRecoveryCoordinator(
    private val controller: TutorController,
    val workflow: BlenderVerificationWorkflow = BlenderVerificationWorkflow(),
    val diagnosticEngine: StateMismatchDiagnosticEngine = StateMismatchDiagnosticEngine(),
    val recommendationEngine: NextStepRecommendationEngine = AdaptiveNextStepRecommendationEngine(),
    val policy: RecoveryPolicy = RecoveryPolicy()
) {
    var state: ClosedLoopRecoveryState by mutableStateOf(ClosedLoopRecoveryState.NotStarted)
        internal set

    private val actionMutex = Mutex()
    private var inFlightRequestId: String? = null
    private var inFlightStepIndex: Int = -1
    private var inFlightPlanId: String? = null

    // Track attempts isolated per lesson step
    private val stepAttempts = mutableMapOf<Int, MutableList<StepRecoveryAttempt>>()

    var activeBaselineRun: VerificationRun? = null
        private set

    /**
     * Returns the recorded attempt history for [stepIndex].
     */
    fun getAttemptsForStep(stepIndex: Int): List<StepRecoveryAttempt> =
        stepAttempts[stepIndex]?.toList() ?: emptyList()

    /**
     * Returns the count of verified action failures for [stepIndex].
     */
    fun getVerifiedFailuresCount(stepIndex: Int): Int =
        stepAttempts[stepIndex]?.count { it.verificationResult.status == VerificationStatus.FAILURE } ?: 0

    /**
     * Initiates authoritative verification of the active tutorial step.
     */
    suspend fun verifyActiveStep(): ClosedLoopRecoveryState {
        val activeStep = controller.currentStep
        val stepIndex = controller.state.currentStepIndex
        val planId = controller.activePlan?.let { "plan_${it.goalText.hashCode()}" } ?: "session_${controller.sessionContext.hashCode()}"

        if (activeStep == null) {
            val errorState = ClosedLoopRecoveryState.Error("No active lesson step to verify.", canRetry = false)
            state = errorState
            return errorState
        }

        val reqId: String
        actionMutex.withLock {
            if (state is ClosedLoopRecoveryState.Verifying || state is ClosedLoopRecoveryState.AwaitingStateCapture) {
                return state // Duplicate click protection
            }

            reqId = UUID.randomUUID().toString()
            inFlightRequestId = reqId
            inFlightStepIndex = stepIndex
            inFlightPlanId = planId
        }

        var expected = activeStep.expectedState ?: inferExpectedStateFromStep(activeStep)
        if (expected != null && (expected.targetName.equals("Active", ignoreCase = true) || expected.targetName.equals("Selected", ignoreCase = true))) {
            if (workflow.lastObservedState == null && workflow.lastObservation == null) {
                val liveObs = try {
                    workflow.provider.observe()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    null
                }
                if (liveObs != null && liveObs.status == context.BlenderObservationStatus.AVAILABLE) {
                    workflow.lastObservation = liveObs
                    val converted = liveObs.toObservedState(UUID.randomUUID().toString())
                    workflow.lastObservedState = converted
                    controller.recordObservedState(converted)
                }
            }
            val resolvedName = workflow.lastObservedState?.selectedObjects?.firstOrNull()
                ?: workflow.lastObservedState?.objects?.find { it.selected == true }?.name
                ?: workflow.lastObservedState?.objects?.find { it.type == "MESH" }?.name
                ?: workflow.lastObservation?.selectedObjects?.firstOrNull()
                ?: workflow.lastObservation?.objects?.find { it.type == "MESH" }?.name
            if (resolvedName != null) {
                expected = expected.copy(targetName = resolvedName)
            }
        }

        if (expected == null) {
            val prereqFailure = resolvePrerequisites(stepIndex, activeStep)
            if (prereqFailure != null) {
                return prereqFailure
            }

            val obs = try {
                workflow.provider.observe()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                null
            }

            if (obs != null && obs.status != BlenderObservationStatus.AVAILABLE) {
                val diagnostic = obs.diagnostic ?: "Blender state telemetry unavailable."
                val isUnavailable = obs.status == BlenderObservationStatus.UNAVAILABLE
                val message = if (isUnavailable) {
                    "Blender is not running or the state adapter is not reachable."
                } else {
                    "Blender state telemetry unavailable: $diagnostic"
                }
                val evidenceState = ClosedLoopRecoveryState.NeedsMoreEvidence(
                    requestId = reqId,
                    stepIndex = stepIndex,
                    message = message,
                    diagnostic = diagnostic,
                    canRetry = true
                )
                state = evidenceState
                return evidenceState
            }

            val infoExpected = ExpectedState(operation = VerificationOperation.UNKNOWN)
            val infoResult = VerificationResult(
                status = VerificationStatus.SUCCESS,
                runId = reqId,
                operation = VerificationOperation.UNKNOWN,
                explanation = "Step '${activeStep.title}' acknowledged and verified (informational step)."
            )
            return actionMutex.withLock {
                handleSuccess(reqId, stepIndex, activeStep.id, activeStep.title, infoResult, infoExpected)
            }
        }

        val prereqFailure = resolvePrerequisites(stepIndex, activeStep)
        if (prereqFailure != null) {
            return prereqFailure
        }

        actionMutex.withLock {
            state = ClosedLoopRecoveryState.AwaitingStateCapture(
                requestId = reqId,
                stepIndex = stepIndex,
                stepTitle = activeStep.title
            )
        }

        try {
            // 1. Establish or reuse baseline for this expected state
            var run = activeBaselineRun
            if (run == null || run.expected != expected) {
                run = try {
                    workflow.captureBaseline(expected)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    return handleCaptureFailure(reqId, stepIndex, "Could not capture Blender baseline: ${e.message ?: "Unknown error"}")
                }
                activeBaselineRun = run
            }

            // 2. Perform verification
            actionMutex.withLock {
                if (isStale(reqId, stepIndex, planId)) {
                    state = ClosedLoopRecoveryState.Cancelled("Session changed during baseline capture.")
                    return state
                }
                state = ClosedLoopRecoveryState.Verifying(
                    requestId = reqId,
                    stepIndex = stepIndex,
                    stepTitle = activeStep.title,
                    runId = run.id
                )
            }

            val priorObservedState = controller.sessionContext.lastObservedState
            var result: VerificationResult = try {
                workflow.verifyAction(run)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                return handleCaptureFailure(reqId, stepIndex, "Blender state observation failed: ${e.message ?: "Connection error"}")
            }
            workflow.lastObservedState?.let { controller.recordObservedState(it) }

            if (result.status == VerificationStatus.FAILURE &&
                expected.operation == VerificationOperation.CREATE &&
                run.baseline?.objects?.any { workflow.verifier.objectMatchesName(it, expected.targetName) } == true
            ) {
                val preCreationResult = workflow.verifyWithPreCreationBaseline(run)
                if (preCreationResult?.status == VerificationStatus.SUCCESS) {
                    result = preCreationResult
                }
            } else if (result.status == VerificationStatus.FAILURE &&
                expected.operation == VerificationOperation.DELETE &&
                run.baseline?.objects?.none { workflow.verifier.objectMatchesName(it, expected.targetName) } == true
            ) {
                val preDeletionResult = workflow.verifyWithPreDeletionBaseline(run, priorObservedState)
                if (preDeletionResult?.status == VerificationStatus.SUCCESS) {
                    result = preDeletionResult
                }
            }

            // Record diagnostic log for this attempt
            recordDiagnostic(reqId, activeStep, expected, result)

            // 3. Process outcome with stale-protection lock
            return actionMutex.withLock {
                if (isStale(reqId, stepIndex, planId)) {
                    state = ClosedLoopRecoveryState.Cancelled("Step advanced or goal changed during verification.")
                    return state
                }

                when (result.status) {
                    VerificationStatus.SUCCESS -> handleSuccess(reqId, stepIndex, activeStep.id, activeStep.title, result, expected)
                    VerificationStatus.FAILURE -> handleFailure(reqId, stepIndex, activeStep.id, activeStep.title, result, expected, run)
                    VerificationStatus.UNCERTAIN -> handleUncertain(reqId, stepIndex, result, expected)
                }
            }
        } catch (ce: CancellationException) {
            actionMutex.withLock {
                state = ClosedLoopRecoveryState.Cancelled("Verification was cancelled.")
            }
            throw ce
        } catch (e: Exception) {
            return actionMutex.withLock {
                state = ClosedLoopRecoveryState.Error("Unexpected verification error: ${e.message}", cause = e, canRetry = true)
                state
            }
        }
    }

    /**
     * Automatic monitoring verification that waits for state changes before verifying.
     */
    suspend fun autoVerifyActiveStep(): ClosedLoopRecoveryState {
        val activeStep = controller.currentStep
        val stepIndex = controller.state.currentStepIndex
        val planId = controller.activePlan?.let { "plan_${it.goalText.hashCode()}" } ?: "session_${controller.sessionContext.hashCode()}"

        val expected = activeStep?.let { it.expectedState ?: inferExpectedStateFromStep(it) }
        if (activeStep == null || expected == null) {
            val evidenceState = ClosedLoopRecoveryState.NeedsMoreEvidence(
                requestId = UUID.randomUUID().toString(),
                stepIndex = stepIndex,
                message = "No expected state configured for step.",
                canRetry = false
            )
            state = evidenceState
            return evidenceState
        }

        val prereqFailure = resolvePrerequisites(stepIndex, activeStep)
        if (prereqFailure != null) {
            return prereqFailure
        }

        val reqId: String

        actionMutex.withLock {
            if (state is ClosedLoopRecoveryState.Verifying || state is ClosedLoopRecoveryState.AwaitingStateCapture) {
                return state
            }

            reqId = UUID.randomUUID().toString()
            inFlightRequestId = reqId
            inFlightStepIndex = stepIndex
            inFlightPlanId = planId

            state = ClosedLoopRecoveryState.AwaitingStateCapture(reqId, stepIndex, activeStep.title)
        }

        try {
            val run = try {
                workflow.captureBaseline(expected)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                return handleCaptureFailure(reqId, stepIndex, "Could not capture baseline: ${e.message}")
            }
            activeBaselineRun = run

            actionMutex.withLock {
                if (isStale(reqId, stepIndex, planId)) {
                    state = ClosedLoopRecoveryState.Cancelled("Session changed during capture.")
                    return state
                }
                state = ClosedLoopRecoveryState.Verifying(reqId, stepIndex, activeStep.title, run.id)
            }

            val result = workflow.autoVerifyAction(
                run = run,
                pollIntervalMillis = policy.autoVerifyPollIntervalMillis,
                maxWaitMillis = policy.autoVerifyMaxWaitMillis
            )

            recordDiagnostic(reqId, activeStep, expected, result)

            return actionMutex.withLock {
                if (isStale(reqId, stepIndex, planId)) {
                    state = ClosedLoopRecoveryState.Cancelled("Step changed during auto-verification.")
                    return state
                }

                when (result.status) {
                    VerificationStatus.SUCCESS -> handleSuccess(reqId, stepIndex, activeStep.id, activeStep.title, result, expected)
                    VerificationStatus.FAILURE -> handleFailure(reqId, stepIndex, activeStep.id, activeStep.title, result, expected, run)
                    VerificationStatus.UNCERTAIN -> handleUncertain(reqId, stepIndex, result, expected)
                }
            }
        } catch (ce: CancellationException) {
            actionMutex.withLock {
                state = ClosedLoopRecoveryState.Cancelled("Auto-verification cancelled.")
            }
            throw ce
        } catch (e: Exception) {
            return actionMutex.withLock {
                state = ClosedLoopRecoveryState.Error("Auto-verification error: ${e.message}", cause = e, canRetry = true)
                state
            }
        }
    }

    private fun recordDiagnostic(
        reqId: String,
        activeStep: models.TutorStep,
        expected: ExpectedState,
        result: VerificationResult
    ) {
        val lastObs = workflow.lastObservedState ?: controller.sessionContext.lastObservedState
        val targetName = expected.targetName ?: ""
        val targetObj = lastObs?.objects?.find { workflow.verifier.objectMatchesName(it, targetName) }
        val coords = targetObj?.metadata?.filterKeys { it in setOf("location", "location.x", "location.y", "location.z", "x", "y", "z") }
        val expectedZ = expected.requiredConditions.find { it.propertyName?.equals("location.z", ignoreCase = true) == true }?.expectedValue
            ?: if (expected.targetProperty?.equals("location.z", ignoreCase = true) == true) expected.expectedPropertyValue else null
        val rawSnippet = lastObs?.metadata?.get("rawResponseSnippet")
            ?: lastObs?.let { obs -> "Scene=${obs.sceneName}, Mode=${obs.mode}, Objects=${obs.objects.map { it.name }}" }
        val planStep = controller.activePlan?.steps?.getOrNull(controller.state.currentStepIndex)
        val taskId = planStep?.stepId ?: "step_${activeStep.id}"
        val condDiagnostics = (result.satisfiedConditions.map { context.ConditionEvaluationDiagnostic(it.field.name, it.objectName, it.propertyName, it.expectedValue, true) } +
            result.unsatisfiedConditions.map { context.ConditionEvaluationDiagnostic(it.field.name, it.objectName, it.propertyName, it.expectedValue, false) } +
            result.indeterminateConditions.map { context.ConditionEvaluationDiagnostic(it.field.name, it.objectName, it.propertyName, it.expectedValue, null) })

        context.VerificationDiagnosticLogger.record(
            context.VerificationAttemptDiagnostic(
                attemptId = reqId,
                taskId = taskId,
                taskDescription = activeStep.description.ifBlank { activeStep.title },
                expectedZCondition = expectedZ,
                expectedTarget = expected.targetName,
                rawBlenderResponseSnippet = rawSnippet,
                parsedCoordinates = coords,
                objectIdentity = targetObj?.id,
                observationTimestamp = lastObs?.capturedAt?.toString(),
                freshness = lastObs?.metadata?.get("freshness") ?: if (lastObs != null) "FRESH" else "NONE",
                conditionResults = condDiagnostics,
                finalDecision = result.status,
                failureReason = if (result.status != VerificationStatus.SUCCESS) result.explanation else null
            )
        )
    }

    private fun handleSuccess(
        reqId: String,
        stepIndex: Int,
        stepId: Int,
        stepTitle: String,
        result: VerificationResult,
        expected: ExpectedState
    ): ClosedLoopRecoveryState {
        val attempts = stepAttempts.getOrPut(stepIndex) { mutableListOf() }
        val attemptNumber = attempts.size + 1

        val record = StepRecoveryAttempt(
            stepIndex = stepIndex,
            stepId = stepId,
            attemptNumber = attemptNumber,
            verificationResult = result,
            observedState = controller.sessionContext.lastObservedState,
            mismatchCategory = null,
            recommendation = null,
            isRetried = true,
            retryOutcome = VerificationStatus.SUCCESS
        )
        attempts.add(record)

        activeBaselineRun = null
        val successState = ClosedLoopRecoveryState.VerifiedSuccess(
            requestId = reqId,
            stepIndex = stepIndex,
            stepTitle = stepTitle,
            verificationResult = result,
            explanation = result.explanation
        )
        state = successState

        val activeStep = controller.currentStep
        if (activeStep != null) {
            val evidence = controller.adaptiveTutoringEngine.observeLearnerProgress(
                step = activeStep,
                stepIndex = stepIndex,
                baseline = activeBaselineRun?.baseline,
                observed = controller.sessionContext.lastObservedState,
                verificationStatus = VerificationStatus.SUCCESS,
                failedAttemptsCount = 0
            )
            controller.latestLearnerEvidence = evidence
        }

        // Notify controller to advance lesson progression
        controller.recordVerificationResult(result, expected)
        return successState
    }

    private fun handleFailure(
        reqId: String,
        stepIndex: Int,
        stepId: Int,
        stepTitle: String,
        result: VerificationResult,
        expected: ExpectedState,
        run: VerificationRun
    ): ClosedLoopRecoveryState {
        val attempts = stepAttempts.getOrPut(stepIndex) { mutableListOf() }
        val previousFailures = attempts.filter { it.verificationResult.status == VerificationStatus.FAILURE }
        val attemptNumber = previousFailures.size + 1

        val observed = workflow.lastObservedState ?: controller.sessionContext.lastObservedState
        if (observed != null) {
            controller.recordObservedState(observed)
        }
        val report = diagnosticEngine.diagnose(
            expected = expected,
            observed = observed,
            baseline = run.baseline
        )
        val mismatchCat = report.primaryMismatch?.category

        val isRepeated = previousFailures.any { it.mismatchCategory == mismatchCat }

        // Observe learner progress and classify outcome
        val activeStep = controller.currentStep
        if (activeStep != null) {
            val evidence = controller.adaptiveTutoringEngine.observeLearnerProgress(
                step = activeStep,
                stepIndex = stepIndex,
                baseline = run.baseline,
                observed = observed,
                verificationStatus = result.status,
                failedAttemptsCount = attemptNumber
            )
            controller.latestLearnerEvidence = evidence

            // Adapt visual highlight according to outcome
            val adaptedHighlight = controller.adaptiveTutoringEngine.adaptHighlightForOutcome(
                baseRegion = controller.state.currentHighlight,
                outcome = evidence.outcome,
                evidence = evidence
            )
            if (adaptedHighlight != null) {
                controller.setHighlight(adaptedHighlight)
            }
        }

        // Build contextual, bounded recovery recommendation
        val recommendation = buildRecoveryRecommendation(
            stepIndex = stepIndex,
            stepTitle = stepTitle,
            expected = expected,
            observed = observed,
            report = report,
            attemptNumber = attemptNumber,
            isRepeated = isRepeated
        )

        val record = StepRecoveryAttempt(
            stepIndex = stepIndex,
            stepId = stepId,
            attemptNumber = attemptNumber,
            verificationResult = result,
            observedState = observed,
            mismatchCategory = mismatchCat,
            recommendation = recommendation,
            isRetried = attemptNumber > 1,
            retryOutcome = VerificationStatus.FAILURE
        )
        attempts.add(record)
        // Keep activeBaselineRun intact so subsequent verification attempts compare against
        // the original pre-action baseline for this step.

        val remaining = (policy.maxVerifiedFailuresPerStep - attemptNumber).coerceAtLeast(0)

        val nextState = if (attemptNumber >= policy.maxVerifiedFailuresPerStep) {
            ClosedLoopRecoveryState.RetryLimitReached(
                stepIndex = stepIndex,
                stepTitle = stepTitle,
                totalAttempts = attemptNumber,
                lastRecommendation = recommendation,
                message = "Step '$stepTitle' reached the retry limit ($attemptNumber attempts). Review the instruction or verify Blender manually."
            )
        } else {
            ClosedLoopRecoveryState.RecoveryRecommended(
                requestId = reqId,
                stepIndex = stepIndex,
                stepTitle = stepTitle,
                recommendation = recommendation,
                diagnosticReport = report,
                attemptNumber = attemptNumber,
                remainingAttempts = remaining,
                isRepeatedMismatch = isRepeated,
                adaptedGuidanceNote = if (isRepeated) "Previous correction did not resolve mismatch ($mismatchCat). Adapting instructions." else null
            )
        }

        state = nextState
        // Update pedagogical engine without advancing step
        controller.recordVerificationResult(result, expected)
        return nextState
    }

    private fun handleUncertain(
        reqId: String,
        stepIndex: Int,
        result: VerificationResult,
        expected: ExpectedState
    ): ClosedLoopRecoveryState {
        // Uncertain outcome does NOT consume verified failure budget
        val uncertainState = ClosedLoopRecoveryState.NeedsMoreEvidence(
            requestId = reqId,
            stepIndex = stepIndex,
            message = "Verification uncertain: ${result.explanation}",
            diagnostic = "Evidence insufficient or conflicting. Please check Blender workspace.",
            canRetry = true
        )
        state = uncertainState
        controller.recordVerificationResult(result, expected)
        return uncertainState
    }

    private suspend fun handleCaptureFailure(
        reqId: String,
        stepIndex: Int,
        diagnostic: String
    ): ClosedLoopRecoveryState = actionMutex.withLock {
        val evidenceState = ClosedLoopRecoveryState.NeedsMoreEvidence(
            requestId = reqId,
            stepIndex = stepIndex,
            message = "Blender state telemetry unavailable: $diagnostic",
            diagnostic = diagnostic,
            canRetry = true
        )
        state = evidenceState
        evidenceState
    }

    private fun buildRecoveryRecommendation(
        stepIndex: Int,
        stepTitle: String,
        expected: ExpectedState,
        observed: ObservedState?,
        report: StateMismatchReport,
        attemptNumber: Int,
        isRepeated: Boolean
    ): TutorRecommendation {
        val evidence = controller.latestLearnerEvidence
        val targetName = expected.targetName ?: "object"

        if (evidence != null && evidence.outcome == LearnerOutcome.WRONG_DIRECTION_CONFIRMED) {
            val title = "Reverse Direction: Move Upward"
            val explanation = evidence.diagnosticExplanation
            val actionSteps = listOf(
                "Wrong direction detected: $explanation",
                evidence.activeHint ?: "Press 'G' then 'Z' and move mouse UPWARD to increase height.",
                "Verify that Z location becomes positive before clicking Verify."
            )
            return TutorRecommendation(
                title = title,
                explanation = explanation,
                actionSteps = actionSteps,
                rationale = "Object moved in the reverse direction of the task requirement.",
                expectedOutcome = "Z location > 0 m (moved upward)",
                supportingEvidence = listOf("Observed: moved downward", "Expected: move upward (+Z)"),
                recommendationType = RecommendationType.CORRECT_STATE,
                confidence = 0.98,
                requiresVerification = true,
                suggestedShortcut = "G then Z",
                targetControlName = targetName
            )
        }

        if (evidence != null && evidence.outcome == LearnerOutcome.PROGRESS_OBSERVED) {
            val title = "Continue Upward (+Z)"
            val explanation = evidence.diagnosticExplanation
            val actionSteps = listOf(
                "Great start! Object moved in the correct direction.",
                evidence.activeHint ?: "Continue translating upward until target is reached.",
                "Click 'Verify Action' once the target condition is satisfied."
            )
            return TutorRecommendation(
                title = title,
                explanation = explanation,
                actionSteps = actionSteps,
                rationale = "Meaningful upward progress observed, but threshold not yet met.",
                expectedOutcome = "State matches ${expected.operation.name} on $targetName.",
                supportingEvidence = listOf("Observed: upward progress", "Expected: complete threshold"),
                recommendationType = RecommendationType.RETRY,
                confidence = 0.95,
                requiresVerification = true,
                suggestedShortcut = "G then Z",
                targetControlName = targetName
            )
        }

        if (evidence != null && evidence.outcome == LearnerOutcome.TARGET_MISMATCH) {
            val title = "Select Target: $targetName"
            val explanation = evidence.diagnosticExplanation
            val actionSteps = listOf(
                explanation,
                evidence.activeHint ?: "Left-click '$targetName' to select it before performing action.",
                "Ensure '$targetName' is outlined in orange before verifying."
            )
            return TutorRecommendation(
                title = title,
                explanation = explanation,
                actionSteps = actionSteps,
                rationale = "A different object was interacted with instead of the assigned target.",
                expectedOutcome = "Target $targetName is active and transformed.",
                supportingEvidence = listOf("Expected: $targetName", "Observed: wrong target interacted with"),
                recommendationType = RecommendationType.CORRECT_STATE,
                confidence = 0.95,
                requiresVerification = true,
                targetControlName = targetName
            )
        }

        val primary = report.primaryMismatch

        if (primary != null) {
            val title = if (isRepeated) {
                "Retry (Attempt $attemptNumber): ${primary.suggestedAction.actionType.replace('_', ' ')}"
            } else {
                "Correct: ${primary.suggestedAction.actionType.replace('_', ' ')}"
            }

            val actionSteps = mutableListOf<String>()
            if (isRepeated) {
                actionSteps.add("Notice: Attempt $attemptNumber - ${primary.explanation}")
                actionSteps.add(primary.suggestedAction.description)
                actionSteps.add("Verification Check: Confirm in Blender's Outliner or Header that '$targetName' reflects the expected state before verifying.")
            } else {
                actionSteps.add(primary.suggestedAction.description)
            }

            val rationale = if (isRepeated) {
                "The previous attempt did not satisfy '${primary.expected}'. Carefully perform the correction and verify visually before retrying."
            } else {
                primary.explanation
            }

            return TutorRecommendation(
                title = title,
                explanation = primary.explanation,
                actionSteps = actionSteps,
                rationale = rationale,
                expectedOutcome = "State matches ${expected.operation.name} on $targetName.",
                supportingEvidence = listOf("Observed: ${primary.observed}", "Expected: ${primary.expected}"),
                recommendationType = RecommendationType.CORRECT_STATE,
                confidence = 0.95,
                requiresVerification = true,
                suggestedShortcut = primary.suggestedAction.suggestedShortcut,
                targetControlName = primary.suggestedAction.targetName ?: targetName
            )
        }

        return TutorRecommendation(
            title = "Retry Step: $stepTitle",
            explanation = "Expected state for $stepTitle has not been observed.",
            actionSteps = listOf("Follow step instructions carefully in Blender.", "Click 'Verify Action' when complete."),
            rationale = "Blender scene state did not reflect the intended outcome.",
            expectedOutcome = "State aligns with $stepTitle.",
            recommendationType = RecommendationType.RETRY,
            confidence = 0.8,
            requiresVerification = true
        )
    }

    /**
     * Explicitly captures a baseline for the current step before the learner acts.
     */
    suspend fun captureBaseline(): context.VerificationRun? {
        val activeStep = controller.currentStep ?: return null
        val expected = activeStep.expectedState ?: inferExpectedStateFromStep(activeStep) ?: return null
        return try {
            val run = workflow.captureBaseline(expected)
            activeBaselineRun = run
            run
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Prepares the tutor for the learner's retry of the current step.
     */
    fun retryStep(): Boolean {
        val step = controller.currentStep ?: return false
        val stepIndex = controller.state.currentStepIndex
        val failures = getVerifiedFailuresCount(stepIndex)

        activeBaselineRun = null
        state = ClosedLoopRecoveryState.AwaitingRetry(
            stepIndex = stepIndex,
            stepTitle = step.title,
            attemptNumber = failures + 1
        )
        return true
    }

    /**
     * Resets verified failure counts for [stepIndex], allowing fresh retry budget.
     */
    fun resetStepAttempts(stepIndex: Int) {
        stepAttempts[stepIndex]?.clear()
        val step = controller.currentStep
        state = if (step != null && controller.state.currentStepIndex == stepIndex) {
            ClosedLoopRecoveryState.AwaitingLearnerAction(stepIndex, step.title, step.expectedState)
        } else {
            ClosedLoopRecoveryState.NotStarted
        }
    }

    /**
     * Cancels any in-flight verification and updates state to Cancelled.
     */
    fun cancelVerification() {
        inFlightRequestId = null
        activeBaselineRun = null
        state = ClosedLoopRecoveryState.Cancelled()
    }

    /**
     * Dismisses cancelled or error state and returns to NotStarted.
     */
    fun dismiss() {
        inFlightRequestId = null
        state = ClosedLoopRecoveryState.NotStarted
    }

    /**
     * Completely resets recovery state and clears all historical attempts (e.g. when goal changes).
     */
    fun reset() {
        inFlightRequestId = null
        inFlightStepIndex = -1
        inFlightPlanId = null
        activeBaselineRun = null
        stepAttempts.clear()
        state = ClosedLoopRecoveryState.NotStarted
    }

    /**
     * Called when the active step changes, protecting against stale overwrites.
     */
    fun onStepChanged(newStepIndex: Int) {
        if (inFlightStepIndex != newStepIndex) {
            inFlightRequestId = null
        }
        activeBaselineRun = null
        val step = controller.currentStep
        state = if (step != null) {
            ClosedLoopRecoveryState.AwaitingLearnerAction(newStepIndex, step.title, step.expectedState)
        } else {
            ClosedLoopRecoveryState.NotStarted
        }
    }

    internal fun isStale(reqId: String, stepIndex: Int, planId: String?): Boolean {
        val currentPlanId = controller.activePlan?.let { "plan_${it.goalText.hashCode()}" } ?: "session_${controller.sessionContext.hashCode()}"
        return reqId != inFlightRequestId ||
            stepIndex != controller.state.currentStepIndex ||
            planId != currentPlanId
    }

    private fun inferExpectedStateFromStep(step: models.TutorStep): ExpectedState? {
        val titleAndInstr = "${step.title} ${step.instruction}".lowercase()
        return when {
            titleAndInstr.contains("object mode") -> ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetMode = "OBJECT",
                requiredConditions = listOf(
                    context.ExpectedCondition(context.ExpectedStateField.MODE, expectedValue = "OBJECT")
                )
            )
            titleAndInstr.contains("edit mode") -> ExpectedState(
                operation = VerificationOperation.MODIFY,
                targetMode = "EDIT",
                requiredConditions = listOf(
                    context.ExpectedCondition(context.ExpectedStateField.MODE, expectedValue = "EDIT")
                )
            )
            titleAndInstr.contains("move") || titleAndInstr.contains("translate") || titleAndInstr.contains("location") -> {
                val targetName = when {
                    titleAndInstr.contains("sphere") -> "Sphere"
                    titleAndInstr.contains("cube") -> "Cube"
                    titleAndInstr.contains("camera") -> "Camera"
                    titleAndInstr.contains("light") -> "Light"
                    else -> "Cube"
                }
                val isUpward = titleAndInstr.contains("up") || titleAndInstr.contains("above") || titleAndInstr.contains("z-axis") || titleAndInstr.contains("vertical")
                if (isUpward) {
                    ExpectedState(
                        operation = VerificationOperation.MODIFY,
                        targetName = targetName,
                        targetType = "MESH",
                        targetProperty = "location.z",
                        expectedPropertyValue = "> 0",
                        requiredConditions = listOf(
                            context.ExpectedCondition(
                                field = context.ExpectedStateField.OBJECT_EXISTS,
                                objectName = targetName,
                                expectedValue = targetName,
                                required = true
                            ),
                            context.ExpectedCondition(
                                field = context.ExpectedStateField.OBJECT_PROPERTY,
                                objectName = targetName,
                                propertyName = "location.z",
                                expectedValue = "> 0",
                                required = true
                            )
                        )
                    )
                } else {
                    ExpectedState(
                        operation = VerificationOperation.MODIFY,
                        targetName = targetName,
                        targetProperty = "location",
                        expectedPropertyValue = "!= baseline",
                        requiredConditions = listOf(
                            context.ExpectedCondition(
                                field = context.ExpectedStateField.OBJECT_EXISTS,
                                objectName = targetName,
                                expectedValue = targetName,
                                required = true
                            ),
                            context.ExpectedCondition(
                                field = context.ExpectedStateField.OBJECT_PROPERTY,
                                objectName = targetName,
                                propertyName = "location",
                                expectedValue = "!= baseline",
                                required = true
                            )
                        )
                    )
                }
            }
            titleAndInstr.contains("delete") || titleAndInstr.contains("remove") -> {
                val targetName = when {
                    titleAndInstr.contains("sphere") -> "Sphere"
                    titleAndInstr.contains("cube") -> "Cube"
                    titleAndInstr.contains("camera") -> "Camera"
                    titleAndInstr.contains("light") -> "Light"
                    else -> "Selected"
                }
                ExpectedState(
                    operation = VerificationOperation.DELETE,
                    targetName = targetName,
                    requiredConditions = listOf(
                        context.ExpectedCondition(context.ExpectedStateField.OBJECT_EXISTS, expectedValue = targetName, objectName = targetName, expectedBoolean = false)
                    )
                )
            }
            titleAndInstr.contains("sphere") -> ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Sphere",
                targetType = "MESH",
                requiredConditions = listOf(
                    context.ExpectedCondition(context.ExpectedStateField.OBJECT_EXISTS, expectedValue = "Sphere", objectName = "Sphere")
                )
            )
            titleAndInstr.contains("cube") -> ExpectedState(
                operation = VerificationOperation.CREATE,
                targetName = "Cube",
                targetType = "MESH",
                requiredConditions = listOf(
                    context.ExpectedCondition(context.ExpectedStateField.OBJECT_EXISTS, expectedValue = "Cube", objectName = "Cube")
                )
            )
            else -> null
        }
    }

    private suspend fun resolvePrerequisites(stepIndex: Int, activeStep: models.TutorStep): ClosedLoopRecoveryState? {
        val plan = controller.activePlan ?: return null
        val currentPlanStep = plan.steps.getOrNull(stepIndex) ?: return null
        if (currentPlanStep.prerequisiteStepIds.isEmpty()) return null

        val completedStepIds = controller.goalProgressState.completedStepIds
        val allPrereqIds = LinkedHashSet<String>()
        fun collectPrereqs(stepId: String) {
            val step = plan.steps.find { it.stepId == stepId } ?: return
            for (pId in step.prerequisiteStepIds) {
                if (!allPrereqIds.contains(pId)) {
                    collectPrereqs(pId)
                    allPrereqIds.add(pId)
                }
            }
        }
        collectPrereqs(currentPlanStep.stepId)

        val missingPrereqIds = allPrereqIds.filterNot { it in completedStepIds }
        if (missingPrereqIds.isEmpty()) return null

        var currentObs = workflow.lastObservedState
        if (currentObs == null) {
            val obs = try {
                workflow.provider.observe()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                null
            }
            if (obs != null && obs.status != context.BlenderObservationStatus.AVAILABLE) {
                val diagnostic = obs.diagnostic ?: "Blender state telemetry unavailable."
                val isUnavailable = obs.status == context.BlenderObservationStatus.UNAVAILABLE
                val message = if (isUnavailable) {
                    "Blender is not running or the state adapter is not reachable."
                } else {
                    "Blender state telemetry unavailable. Ensure Blender is running and connected."
                }
                val evidenceState = ClosedLoopRecoveryState.NeedsMoreEvidence(
                    requestId = UUID.randomUUID().toString(),
                    stepIndex = stepIndex,
                    message = message,
                    diagnostic = diagnostic,
                    canRetry = true
                )
                state = evidenceState
                return evidenceState
            } else if (obs != null && obs.status == context.BlenderObservationStatus.AVAILABLE) {
                val converted = obs.toObservedState(UUID.randomUUID().toString())
                controller.recordObservedState(converted)
                currentObs = converted
            }
        }

        val unresolvablePrereqs = mutableListOf<UserGoalPlanStep>()
        for (prereqId in missingPrereqIds) {
            val prereqStep = plan.steps.find { it.stepId == prereqId }
            if (prereqStep != null) {
                val prereqExpected = prereqStep.expectedState
                if (prereqExpected != null) {
                    val isSatisfied = if (currentObs != null) {
                        workflow.verifier.matchesExpectedState(prereqExpected, currentObs)
                    } else false

                    if (isSatisfied) {
                        controller.recordPrerequisiteSatisfied(prereqStep.stepId)
                    } else {
                        unresolvablePrereqs.add(prereqStep)
                    }
                } else {
                    controller.recordPrerequisiteSatisfied(prereqStep.stepId)
                }
            }
        }

        if (unresolvablePrereqs.isNotEmpty()) {
            val firstUnmet = unresolvablePrereqs.first()
            val evidenceState = ClosedLoopRecoveryState.NeedsMoreEvidence(
                requestId = UUID.randomUUID().toString(),
                stepIndex = stepIndex,
                message = "Prerequisite step '${firstUnmet.title}' must be satisfied before verifying '${activeStep.title}'. (${firstUnmet.instruction})",
                diagnostic = "Prerequisite step '${firstUnmet.stepId}' is not satisfied.",
                canRetry = true
            )
            state = evidenceState
            return evidenceState
        }
        return null
    }
}
