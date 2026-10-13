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

        val expected = activeStep.expectedState ?: inferExpectedStateFromStep(activeStep)
        if (expected == null) {
            val evidenceState = ClosedLoopRecoveryState.NeedsMoreEvidence(
                requestId = UUID.randomUUID().toString(),
                stepIndex = stepIndex,
                message = "No expected state configured for step '${activeStep.title}'.",
                canRetry = false
            )
            state = evidenceState
            return evidenceState
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

            var result: VerificationResult = try {
                workflow.verifyAction(run)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                return handleCaptureFailure(reqId, stepIndex, "Blender state observation failed: ${e.message ?: "Connection error"}")
            }

            if (result.status == VerificationStatus.FAILURE &&
                expected.operation == VerificationOperation.CREATE &&
                run.baseline?.objects?.any { workflow.verifier.objectMatchesName(it, expected.targetName) } == true
            ) {
                val preCreationResult = workflow.verifyWithPreCreationBaseline(run)
                if (preCreationResult?.status == VerificationStatus.SUCCESS) {
                    result = preCreationResult
                }
            }

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
        activeBaselineRun = null

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
            message = "Blender state telemetry unavailable. Ensure Blender is running and connected.",
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
        val primary = report.primaryMismatch
        val targetName = expected.targetName ?: "object"

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
}
