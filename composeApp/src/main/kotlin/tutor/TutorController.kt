package tutor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import context.ApplicationContext
import context.DynamicTargetPositioningService
import context.TargetPositionResult
import context.VerificationResult
import context.VisualContext
import java.awt.image.BufferedImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import models.ApplicationType
import models.HighlightRegion
import models.SessionContext
import models.TutorEvent
import models.TutorState
import models.TutorStep
import pedagogy.AdaptivePedagogicalEngine
import pedagogy.GuidanceStage
import pedagogy.LearningTask
import java.util.UUID

/**
 * Owns the single [TutorState] instance and coordinates UI state mutations
 * with the [AdaptivePedagogicalEngine].
 */
class TutorController(
    val pedagogicalEngine: AdaptivePedagogicalEngine = AdaptivePedagogicalEngine(),
    val targetPositioningService: DynamicTargetPositioningService = DynamicTargetPositioningService(),
    val goalPlanner: UserGoalPlanner = UserGoalPlanner(),
    loadMockTutorialByDefault: Boolean = false,
    screenshotHelpCoordinatorFactory: ((TutorController) -> ScreenshotHelpCoordinator)? = null,
    recoveryCoordinatorFactory: ((TutorController) -> ClosedLoopRecoveryCoordinator)? = null,
    conversationCoordinatorFactory: ((TutorController) -> TutorConversationCoordinator)? = null
) {
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val screenshotHelpCoordinator: ScreenshotHelpCoordinator =
        screenshotHelpCoordinatorFactory?.invoke(this) ?: ScreenshotHelpCoordinator(this)

    val recoveryCoordinator: ClosedLoopRecoveryCoordinator =
        recoveryCoordinatorFactory?.invoke(this) ?: ClosedLoopRecoveryCoordinator(this)

    val conversationCoordinator: TutorConversationCoordinator =
        conversationCoordinatorFactory?.invoke(this) ?: TutorConversationCoordinator(this)

    var sessionContext by mutableStateOf(SessionContext())
        private set
    private val mutableEvents = MutableSharedFlow<TutorEvent>(replay = 16, extraBufferCapacity = 16)
    val events = mutableEvents.asSharedFlow()

    var state by mutableStateOf(TutorState())
        private set

    val goalProgressTracker: GoalProgressTracker = GoalProgressTracker()

    var goalProgressState by mutableStateOf(goalProgressTracker.getCurrentState())
        private set

    var activePlan by mutableStateOf<UserGoalPlan?>(null)
        private set

    internal fun setActivePlanForTesting(plan: UserGoalPlan?) {
        activePlan = plan
        if (plan != null) {
            goalProgressState = goalProgressTracker.initializePlan(plan, preserveExistingProgress = false)
        }
    }

    var hasLoadedMockTutorial by mutableStateOf(loadMockTutorialByDefault)
        private set

    val currentGeneratedPlan: UserGoalPlan?
        get() = activePlan

    init {
        if (loadMockTutorialByDefault) {
            val firstStep = stepsForCurrentApp().firstOrNull()
            if (firstStep != null) {
                val firstTask = firstStep.toLearningTask()
                val initialInstruction = pedagogicalEngine.getInstruction(firstTask)

                state = state.copy(
                    guidanceStage = initialInstruction.stage,
                    adaptiveInstruction = initialInstruction,
                    currentHighlight = initialInstruction.targetRegion ?: firstStep.targetRegion,
                    adaptationReason = pedagogicalEngine.state.adaptationReason,
                    competenceEstimate = pedagogicalEngine.state.competenceEstimate
                )
            }
        } else {
            state = state.copy(
                guidanceStage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
                adaptiveInstruction = null,
                currentHighlight = null,
                adaptationReason = null
            )
        }
    }

    fun stepsForCurrentApp(): List<TutorStep> {
        val plan = activePlan
        return if (plan != null && state.selectedApplication == plan.application) {
            plan.toTutorSteps()
        } else if (hasLoadedMockTutorial) {
            MockTutorData.stepsFor(state.selectedApplication)
        } else {
            emptyList()
        }
    }

    /**
     * Accepts a user goal string, invokes [UserGoalPlanner] to generate a structured
     * plan, replaces current tutorial steps with generated steps, and activates the first step.
     * Returns null if [goalText] is blank without generating a plan.
     */
    fun startUserGoal(goalText: String): UserGoalPlan? {
        val trimmed = goalText.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val plan = goalPlanner.planGoal(trimmed, ApplicationType.BLENDER)
        activePlan = plan
        goalProgressState = goalProgressTracker.initializePlan(plan, preserveExistingProgress = false)

        if (state.selectedApplication != plan.application) {
            state = state.copy(selectedApplication = plan.application)
        }

        val steps = plan.toTutorSteps()
        if (steps.isNotEmpty()) {
            val firstStep = steps.first()
            val firstTask = firstStep.toLearningTask()
            val initialInstruction = pedagogicalEngine.onTaskSwitched(
                newTask = firstTask,
                visualContext = sessionContext.lastVisualContext
            )

            state = state.copy(
                currentStepIndex = 0,
                guidanceStage = initialInstruction.stage,
                adaptiveInstruction = initialInstruction,
                currentHighlight = initialInstruction.targetRegion ?: firstStep.targetRegion,
                adaptationReason = pedagogicalEngine.state.adaptationReason,
                competenceEstimate = pedagogicalEngine.state.competenceEstimate
            )
            recoveryCoordinator.reset()
            recoveryCoordinator.onStepChanged(0)
            conversationCoordinator.onGoalChanged()
            conversationCoordinator.onStepChanged(0)
        }

        return plan
    }

    /**
     * Safely updates to a revised user goal plan, preserving valid completed progress
     * and incrementing plan version to invalidate in-flight stale operations.
     */
    fun replanGoal(revisedGoalText: String): UserGoalPlan? {
        val trimmed = revisedGoalText.trim()
        if (trimmed.isBlank()) {
            return null
        }
        val plan = goalPlanner.planGoal(trimmed, ApplicationType.BLENDER)
        activePlan = plan
        goalProgressState = goalProgressTracker.replan(plan)
        recoveryCoordinator.reset()

        val steps = plan.toTutorSteps()
        if (steps.isNotEmpty()) {
            val completedIds = goalProgressTracker.getCompletedStepIds()
            val incompleteIndex = plan.steps.indexOfFirst { it.stepId !in completedIds }
            val targetIndex = (if (incompleteIndex >= 0) incompleteIndex else 0).coerceIn(0, steps.lastIndex)
            val targetStep = steps[targetIndex]
            val targetTask = targetStep.toLearningTask()
            val initialInstruction = pedagogicalEngine.onTaskSwitched(
                newTask = targetTask,
                visualContext = sessionContext.lastVisualContext
            )

            state = state.copy(
                currentStepIndex = targetIndex,
                guidanceStage = initialInstruction.stage,
                adaptiveInstruction = initialInstruction,
                currentHighlight = initialInstruction.targetRegion ?: targetStep.targetRegion,
                adaptationReason = pedagogicalEngine.state.adaptationReason,
                competenceEstimate = pedagogicalEngine.state.competenceEstimate
            )
            recoveryCoordinator.onStepChanged(targetIndex)
            conversationCoordinator.onGoalChanged()
            conversationCoordinator.onStepChanged(targetIndex)
        }

        return plan
    }

    /**
     * Clears any active generated goal plan and restores the empty goal-entry state
     * (or restores mock tutorial if demo mode was loaded).
     */
    fun clearUserGoal() {
        if (activePlan == null) return
        activePlan = null
        goalProgressTracker.initializePlan(UserGoalPlan("", steps = emptyList()))
        goalProgressState = goalProgressTracker.getCurrentState()
        screenshotHelpCoordinator.resetSync()
        recoveryCoordinator.reset()
        conversationCoordinator.onGoalChanged()

        if (hasLoadedMockTutorial) {
            val steps = MockTutorData.stepsFor(state.selectedApplication)
            val firstStep = steps.firstOrNull()
            if (firstStep != null) {
                val firstTask = firstStep.toLearningTask()
                val initialInstruction = pedagogicalEngine.onTaskSwitched(
                    newTask = firstTask,
                    visualContext = sessionContext.lastVisualContext
                )

                state = state.copy(
                    currentStepIndex = 0,
                    guidanceStage = initialInstruction.stage,
                    adaptiveInstruction = initialInstruction,
                    currentHighlight = initialInstruction.targetRegion ?: firstStep.targetRegion,
                    adaptationReason = pedagogicalEngine.state.adaptationReason,
                    competenceEstimate = pedagogicalEngine.state.competenceEstimate
                )
            }
        } else {
            state = state.copy(
                currentStepIndex = 0,
                guidanceStage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
                adaptiveInstruction = null,
                currentHighlight = null,
                adaptationReason = null
            )
        }
    }

    /**
     * Loads predefined mock tutorial steps for demo/test workflows.
     * Not used as the default production experience on app startup.
     */
    fun loadMockTutorial(application: ApplicationType = state.selectedApplication) {
        hasLoadedMockTutorial = true
        activePlan = null
        val steps = MockTutorData.stepsFor(application)
        val firstStep = steps.first()
        val firstTask = firstStep.toLearningTask()
        val initialInstruction = pedagogicalEngine.onTaskSwitched(
            newTask = firstTask,
            visualContext = sessionContext.lastVisualContext
        )

        state = state.copy(
            selectedApplication = application,
            currentStepIndex = 0,
            guidanceStage = initialInstruction.stage,
            adaptiveInstruction = initialInstruction,
            currentHighlight = initialInstruction.targetRegion ?: firstStep.targetRegion,
            adaptationReason = pedagogicalEngine.state.adaptationReason,
            competenceEstimate = pedagogicalEngine.state.competenceEstimate
        )
        recoveryCoordinator.reset()
        recoveryCoordinator.onStepChanged(0)
    }

    /** The step currently being shown to the user, or null if no tutorial is active. */
    val currentStep: TutorStep?
        get() = stepsForCurrentApp().getOrNull(state.currentStepIndex)

    /** Natural-language instruction for the active step, grounded in pedagogical guidance. */
    val currentTargetDescription: String?
        get() = state.adaptiveInstruction?.instructionText
            ?: currentStep?.let { targetDescriptionForStep(it) }

    fun targetDescriptionForStep(step: TutorStep): String? = step.instruction.takeIf(String::isNotBlank)

    fun recordVisualContext(visualContext: VisualContext) {
        sessionContext = sessionContext.copy(lastVisualContext = visualContext)
        mutableEvents.tryEmit(TutorEvent.VisualContextCreated(visualContext))

        val step = currentStep ?: return
        val currentTask = step.toLearningTask()
        val groundedInstruction = pedagogicalEngine.getInstruction(
            task = currentTask,
            visualContext = visualContext
        )

        state = state.copy(
            adaptiveInstruction = groundedInstruction,
            currentHighlight = groundedInstruction.targetRegion ?: state.currentHighlight
        )
    }

    val diagnosticEngine: context.StateMismatchDiagnosticEngine = context.StateMismatchDiagnosticEngine()

    fun recordObservedState(observedState: context.ObservedState) {
        sessionContext = sessionContext.copy(lastObservedState = observedState)
    }

    fun diagnoseCurrentStep(): context.StateMismatchReport {
        val step = currentStep ?: return context.StateMismatchReport(
            hasMismatch = false,
            mismatches = emptyList(),
            primaryMismatch = null,
            recoveryActions = emptyList(),
            summary = "No active tutorial step to diagnose."
        )
        return diagnosticEngine.diagnose(
            expected = step.expectedState,
            observed = sessionContext.lastObservedState,
            targetApplicationName = state.selectedApplication.displayName
        )
    }

    /**
     * Consumes a verified outcome from Phase 6 Closed-Loop Verification,
     * updates the learner model via the adaptation policy, and adjusts guidance.
     */
    fun recordVerificationResult(result: VerificationResult, expected: context.ExpectedState? = null) {
        val resolvedTask = resolveTaskForVerification(result, expected)

        // If the verified task corresponds to the current step, update UI adaptive instruction
        val step = currentStep
        val isCurrentStepTask = step != null && resolvedTask.taskId == step.taskId
        val isCurrentStepOperation = step != null && (
            (step.expectedState != null && step.expectedState?.operation == result.operation) ||
            (step.expectedState == null && expected == null)
        )
        val isVerifiedCurrentStep = isCurrentStepTask || isCurrentStepOperation

        val plan = activePlan
        val planStep = plan?.steps?.getOrNull(state.currentStepIndex)
        val stepId = planStep?.stepId ?: "step_${state.currentStepIndex + 1}"

        // Diagnose failure root cause if applicable (Rule 2: distinguish learner vs infrastructure)
        val diag = if (result.status == context.VerificationStatus.FAILURE && isVerifiedCurrentStep) diagnoseCurrentStep() else null
        val (mismatchCategory, failureCause) = when (result.status) {
            context.VerificationStatus.SUCCESS -> null to pedagogy.FailureCause.LEARNER_ACTION
            context.VerificationStatus.UNCERTAIN -> null to pedagogy.FailureCause.UNCERTAIN_EVIDENCE
            context.VerificationStatus.FAILURE -> {
                if (diag?.hasMismatch == true && diag.primaryMismatch != null) {
                    diag.primaryMismatch.category.name to pedagogy.FailureCause.LEARNER_ACTION
                } else if (result.explanation.contains("telemetry", ignoreCase = true) ||
                    result.explanation.contains("socket", ignoreCase = true) ||
                    result.explanation.contains("disconnect", ignoreCase = true)
                ) {
                    "InfrastructureFailure" to pedagogy.FailureCause.INFRASTRUCTURE_OR_PROVIDER
                } else {
                    (diag?.primaryMismatch?.category?.name ?: "Mismatch") to pedagogy.FailureCause.LEARNER_ACTION
                }
            }
        }

        val event = pedagogy.LearningEvent.OutcomeVerified(
            eventId = if (result.runId.isNotBlank()) "event_${result.runId}" else UUID.randomUUID().toString(),
            learnerId = pedagogicalEngine.state.learnerId,
            sessionId = sessionContext.hashCode().toString(),
            goalId = plan?.planId,
            planVersion = goalProgressTracker.planVersion,
            stepId = stepId,
            taskId = resolvedTask.taskId,
            skillId = resolvedTask.skillId,
            result = result,
            mismatchCategory = mismatchCategory,
            failureCause = failureCause
        )

        val decision = pedagogicalEngine.ingestLearningEvent(
            event = event,
            task = resolvedTask,
            visualContext = sessionContext.lastVisualContext
        )

        // Asynchronously persist verified progress off UI thread (Rule 4 / Part 5)
        controllerScope.launch(Dispatchers.IO) {
            pedagogicalEngine.persistState()
        }

        var customRationale = decision.rationale
        if (result.status == context.VerificationStatus.FAILURE && isVerifiedCurrentStep) {
            if (diag?.hasMismatch == true && diag.primaryMismatch != null) {
                customRationale = "Mismatch: ${diag.primaryMismatch.explanation}. Suggestion: ${diag.primaryMismatch.suggestedAction.description}"
            }
        } else if (result.status == context.VerificationStatus.UNCERTAIN && isVerifiedCurrentStep) {
            customRationale = "Verification uncertain: ${result.explanation}. Step not confirmed; please check Blender."
        }

        state = state.copy(
            guidanceStage = decision.newStage,
            adaptiveInstruction = if (isCurrentStepTask) decision.instruction else state.adaptiveInstruction,
            lastPedagogicalOutcome = decision.outcome,
            adaptationReason = customRationale,
            competenceEstimate = decision.competenceEstimate,
            currentHighlight = if (isCurrentStepTask) (decision.instruction.targetRegion ?: step.targetRegion) else state.currentHighlight
        )

        mutableEvents.tryEmit(TutorEvent.PedagogicalStateChanged(decision))
        if (decision.stageChanged) {
            mutableEvents.tryEmit(TutorEvent.GuidanceStageChanged(decision.newStage))
        }

        // Closed-loop step progression: advance only on SUCCESS of the active step
        if (result.status == context.VerificationStatus.SUCCESS && isVerifiedCurrentStep) {
            if (plan != null) {
                goalProgressState = goalProgressTracker.recordStepVerification(
                    stepId = stepId,
                    verified = true,
                    operationPlanVersion = goalProgressTracker.planVersion
                )
            }
            if (state.currentStepIndex < stepsForCurrentApp().lastIndex) {
                nextStep()
            }
        } else if (result.status == context.VerificationStatus.FAILURE && isVerifiedCurrentStep) {
            if (plan != null) {
                goalProgressState = goalProgressTracker.recordStepVerification(
                    stepId = stepId,
                    verified = false,
                    operationPlanVersion = goalProgressTracker.planVersion
                )
            }
        }
    }

    /**
     * Updates explicit learner preferences and adapts current guidance accordingly.
     */
    fun updateLearnerPreferences(preferences: pedagogy.LearnerPreferences) {
        val decision = pedagogicalEngine.updatePreferences(preferences)
        val step = currentStep
        state = state.copy(
            guidanceStage = decision.newStage,
            adaptiveInstruction = if (step != null) decision.instruction else state.adaptiveInstruction,
            adaptationReason = decision.rationale
        )
        controllerScope.launch(Dispatchers.IO) {
            pedagogicalEngine.persistState()
        }
    }

    /**
     * Requests a conceptual hint for the active step, transitioning guidance to STAGE_2_CONCEPTUAL_HINT
     * and updating active instruction and overlay markup with hint text.
     */
    fun requestHint(): String? {
        val step = currentStep ?: return null
        val task = step.toLearningTask()
        val hintText = step.conceptualHint?.takeIf { it.isNotBlank() } ?: step.instruction
        val targetRegion = step.targetRegion ?: state.currentHighlight

        val hintInstruction = pedagogy.AdaptiveInstruction(
            taskId = task.taskId,
            stage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT,
            instructionText = "💡 Hint: $hintText",
            targetControlName = step.targetControlName ?: step.title,
            targetRegion = targetRegion,
            conceptSummary = hintText,
            adaptationRationale = "Learner requested a hint for '${step.title}'."
        )

        state = state.copy(
            guidanceStage = GuidanceStage.STAGE_2_CONCEPTUAL_HINT,
            adaptiveInstruction = hintInstruction,
            currentHighlight = targetRegion,
            screenAssistanceEnabled = true,
            adaptationReason = "💡 Hint: $hintText"
        )
        mutableEvents.tryEmit(TutorEvent.GuidanceStageChanged(GuidanceStage.STAGE_2_CONCEPTUAL_HINT))
        return hintText
    }

    /**
     * Toggles learner instruction detail preference (Normal, Concise, Detailed).
     */
    fun setDetailPreference(detailPreference: pedagogy.DetailPreference) {
        updateLearnerPreferences(pedagogicalEngine.state.preferences.copy(detailPreference = detailPreference))
    }

    /**
     * Explicitly clears learner progress across session and disk persistence.
     */
    fun clearLearnerProgress() {
        pedagogicalEngine.reset()
        state = state.copy(
            guidanceStage = pedagogy.GuidanceStage.STAGE_1_EXACT_GUIDANCE,
            adaptationReason = pedagogicalEngine.state.adaptationReason,
            competenceEstimate = 0.0
        )
        controllerScope.launch(Dispatchers.IO) {
            pedagogicalEngine.persistence?.clear(pedagogicalEngine.state.learnerId)
        }
    }

    /**
     * Submits learner's answer to the reflection question for the active step.
     */
    fun submitConceptualAnswer(answer: String) {
        val step = currentStep ?: return
        val currentTask = step.toLearningTask()
        val decision = pedagogicalEngine.evaluateConceptualAnswer(
            task = currentTask,
            learnerAnswer = answer,
            visualContext = sessionContext.lastVisualContext
        )
        state = state.copy(
            guidanceStage = decision.newStage,
            adaptiveInstruction = decision.instruction,
            lastPedagogicalOutcome = decision.outcome,
            adaptationReason = decision.rationale,
            competenceEstimate = decision.competenceEstimate,
            currentHighlight = decision.instruction.targetRegion ?: step.targetRegion
        )
        mutableEvents.tryEmit(TutorEvent.PedagogicalStateChanged(decision))
        if (decision.stageChanged) {
            mutableEvents.tryEmit(TutorEvent.GuidanceStageChanged(decision.newStage))
        }
    }

    fun requestStuckHelp() {
        submitConceptualAnswer("I'm stuck")
    }

    suspend fun requestScreenshotHelp(
        excludeOverlayAction: (suspend () -> Unit)? = null,
        restoreOverlayAction: (suspend () -> Unit)? = null
    ): Boolean {
        return screenshotHelpCoordinator.startCapture(excludeOverlayAction, restoreOverlayAction)
    }

    private fun resolveTaskForVerification(result: VerificationResult, expected: context.ExpectedState?): LearningTask {
        val step = currentStep
        // 1. If current step matches the result operation or matching expected state, use it
        if (step != null) {
            val opMatches = step.expectedState != null && step.expectedState?.operation == result.operation
            val expectedMatches = expected != null && step.expectedState == expected
            val bothNull = expected == null && step.expectedState == null
            if (opMatches || expectedMatches || bothNull) {
                return step.toLearningTask()
            }
        }
        // 2. If any step in current application matches the operation and target name, use it
        val matchingStep = stepsForCurrentApp().find {
            it.expectedState != null && it.expectedState?.operation == result.operation &&
                (expected == null || it.expectedState?.targetName.equals(expected.targetName, ignoreCase = true))
        }
        if (matchingStep != null) {
            return matchingStep.toLearningTask()
        }
        // 3. Fallback to a dynamically scoped task for this verified operation
        val opName = result.operation.name.lowercase()
        val targetName = expected?.targetName ?: "object"
        return LearningTask(
            taskId = "blender_${opName}_${targetName.lowercase()}",
            skillId = "blender.operations",
            title = "${result.operation.name} $targetName",
            objective = "Perform ${result.operation.name} on $targetName in Blender",
            targetControlName = targetName,
            exactInstruction = "Perform ${result.operation.name} on $targetName.",
            conceptualHint = "Execute the ${result.operation.name} command for $targetName.",
            reflectionQuestion = "Which Blender operation achieves ${result.operation.name} on $targetName?",
            expectedState = expected
        )
    }

    fun selectApplication(application: ApplicationType) {
        if (application == state.selectedApplication) return
        if (activePlan != null && activePlan?.application != application) {
            activePlan = null
        }
        val steps = if (activePlan != null && activePlan?.application == application) {
            activePlan!!.toTutorSteps()
        } else if (hasLoadedMockTutorial) {
            MockTutorData.stepsFor(application)
        } else {
            emptyList()
        }

        if (steps.isNotEmpty()) {
            val firstStep = steps.first()
            val firstTask = firstStep.toLearningTask()

            val adaptiveInstruction = pedagogicalEngine.onTaskSwitched(
                newTask = firstTask,
                visualContext = sessionContext.lastVisualContext
            )

            state = state.copy(
                selectedApplication = application,
                currentStepIndex = 0,
                guidanceStage = adaptiveInstruction.stage,
                adaptiveInstruction = adaptiveInstruction,
                currentHighlight = adaptiveInstruction.targetRegion ?: firstStep.targetRegion,
                adaptationReason = pedagogicalEngine.state.adaptationReason,
                competenceEstimate = pedagogicalEngine.state.competenceEstimate
            )
        } else {
            state = state.copy(
                selectedApplication = application,
                currentStepIndex = 0,
                guidanceStage = GuidanceStage.STAGE_1_EXACT_GUIDANCE,
                adaptiveInstruction = null,
                currentHighlight = null,
                adaptationReason = null
            )
        }
        recoveryCoordinator.reset()
        recoveryCoordinator.onStepChanged(0)
        mutableEvents.tryEmit(TutorEvent.ApplicationChanged(application))
    }

    fun nextStep() {
        val steps = stepsForCurrentApp()
        if (steps.isEmpty()) return
        val fromIndex = state.currentStepIndex
        val nextIndex = (fromIndex + 1).coerceAtMost(steps.lastIndex)
        if (fromIndex == nextIndex) return

        val plan = activePlan
        if (plan != null) {
            val fromPlanStep = plan.steps.getOrNull(fromIndex)
            if (fromPlanStep != null) {
                if (fromPlanStep.expectedState == null) {
                    goalProgressState = goalProgressTracker.recordStepVerification(
                        stepId = fromPlanStep.stepId,
                        verified = true,
                        operationPlanVersion = goalProgressTracker.planVersion
                    )
                } else {
                    val lastObs = sessionContext.lastObservedState
                    if (lastObs != null && recoveryCoordinator.workflow.verifier.matchesExpectedState(fromPlanStep.expectedState!!, lastObs)) {
                        goalProgressState = goalProgressTracker.recordStepVerification(
                            stepId = fromPlanStep.stepId,
                            verified = true,
                            operationPlanVersion = goalProgressTracker.planVersion
                        )
                    }
                }
            }
        }

        val nextStep = steps[nextIndex]
        val nextTask = nextStep.toLearningTask()
        val adaptiveInstruction = pedagogicalEngine.onTaskSwitched(
            newTask = nextTask,
            visualContext = sessionContext.lastVisualContext
        )

        state = state.copy(
            currentStepIndex = nextIndex,
            guidanceStage = adaptiveInstruction.stage,
            adaptiveInstruction = adaptiveInstruction,
            currentHighlight = adaptiveInstruction.targetRegion ?: nextStep.targetRegion,
            adaptationReason = pedagogicalEngine.state.adaptationReason,
            competenceEstimate = pedagogicalEngine.state.competenceEstimate
        )
        recoveryCoordinator.onStepChanged(nextIndex)
        conversationCoordinator.onStepChanged(nextIndex)
        mutableEvents.tryEmit(TutorEvent.StepAdvanced(fromIndex, nextIndex))
    }

    fun previousStep() {
        val steps = stepsForCurrentApp()
        if (steps.isEmpty()) return
        val fromIndex = state.currentStepIndex
        val prevIndex = (fromIndex - 1).coerceAtLeast(0)
        if (fromIndex == prevIndex) return

        val prevStep = steps[prevIndex]
        val prevTask = prevStep.toLearningTask()
        val adaptiveInstruction = pedagogicalEngine.onTaskSwitched(
            newTask = prevTask,
            visualContext = sessionContext.lastVisualContext
        )

        state = state.copy(
            currentStepIndex = prevIndex,
            guidanceStage = adaptiveInstruction.stage,
            adaptiveInstruction = adaptiveInstruction,
            currentHighlight = adaptiveInstruction.targetRegion ?: prevStep.targetRegion,
            adaptationReason = pedagogicalEngine.state.adaptationReason,
            competenceEstimate = pedagogicalEngine.state.competenceEstimate
        )
        recoveryCoordinator.onStepChanged(prevIndex)
        conversationCoordinator.onStepChanged(prevIndex)
        mutableEvents.tryEmit(TutorEvent.StepRegressed(fromIndex, prevIndex))
    }

    /**
     * Navigates directly to the specified step index.
     */
    fun goToStep(targetIndex: Int) {
        val steps = stepsForCurrentApp()
        if (steps.isEmpty()) return
        val clamped = targetIndex.coerceIn(0, steps.lastIndex)
        if (clamped == state.currentStepIndex) return

        val fromIndex = state.currentStepIndex

        val plan = activePlan
        if (plan != null && clamped > fromIndex) {
            for (i in fromIndex until clamped) {
                val pastStep = plan.steps.getOrNull(i)
                if (pastStep != null) {
                    if (pastStep.expectedState == null) {
                        goalProgressState = goalProgressTracker.recordStepVerification(
                            stepId = pastStep.stepId,
                            verified = true,
                            operationPlanVersion = goalProgressTracker.planVersion
                        )
                    } else {
                        val lastObs = sessionContext.lastObservedState
                        if (lastObs != null && recoveryCoordinator.workflow.verifier.matchesExpectedState(pastStep.expectedState!!, lastObs)) {
                            goalProgressState = goalProgressTracker.recordStepVerification(
                                stepId = pastStep.stepId,
                                verified = true,
                                operationPlanVersion = goalProgressTracker.planVersion
                            )
                        }
                    }
                }
            }
        }

        val targetStep = steps[clamped]
        val task = targetStep.toLearningTask()
        val adaptiveInstruction = pedagogicalEngine.onTaskSwitched(
            newTask = task,
            visualContext = sessionContext.lastVisualContext
        )

        state = state.copy(
            currentStepIndex = clamped,
            guidanceStage = adaptiveInstruction.stage,
            adaptiveInstruction = adaptiveInstruction,
            currentHighlight = adaptiveInstruction.targetRegion ?: targetStep.targetRegion,
            adaptationReason = pedagogicalEngine.state.adaptationReason,
            competenceEstimate = pedagogicalEngine.state.competenceEstimate
        )
        recoveryCoordinator.onStepChanged(clamped)
        conversationCoordinator.onStepChanged(clamped)
        if (clamped > fromIndex) {
            mutableEvents.tryEmit(TutorEvent.StepAdvanced(fromIndex, clamped))
        } else {
            mutableEvents.tryEmit(TutorEvent.StepRegressed(fromIndex, clamped))
        }
    }

    /**
     * Checks if prerequisite steps for the current active step have been satisfied.
     */
    fun canExecuteCurrentStep(): Boolean {
        val plan = activePlan ?: return true
        val step = plan.steps.getOrNull(state.currentStepIndex) ?: return true
        return goalProgressTracker.canExecuteStep(step)
    }

    /**
     * Returns true if the step at [index] is authoritatively completed.
     */
    fun isStepIndexCompleted(index: Int): Boolean {
        val plan = activePlan
        if (plan != null) {
            val step = plan.steps.getOrNull(index) ?: return false
            return goalProgressState.completedStepIds.contains(step.stepId)
        }
        return index < state.currentStepIndex
    }

    /**
     * Records a prerequisite step as satisfied when verified via direct telemetry.
     */
    fun recordPrerequisiteSatisfied(stepId: String) {
        val plan = activePlan ?: return
        goalProgressState = goalProgressTracker.recordStepVerification(
            stepId = stepId,
            verified = true,
            operationPlanVersion = goalProgressTracker.planVersion
        )
    }

    fun togglePause() {
        val newPaused = !state.isPaused
        state = state.copy(isPaused = newPaused)
        mutableEvents.tryEmit(TutorEvent.PauseToggled(newPaused))
    }

    fun toggleScreenAssistance() {
        val newEnabled = !state.screenAssistanceEnabled
        state = state.copy(screenAssistanceEnabled = newEnabled)
        mutableEvents.tryEmit(TutorEvent.AssistanceToggled(newEnabled))
    }

    fun setHighlight(region: HighlightRegion?) {
        state = state.copy(currentHighlight = region)
        mutableEvents.tryEmit(TutorEvent.RegionHighlighted(region))
    }

    /**
     * Resolves the target highlight dynamically using layered UIA -> OCR -> Visual Grounding -> Validation
     * against the target application window, updating the active overlay highlight in real-time.
     */
    suspend fun resolveDynamicTarget(
        appContext: ApplicationContext? = null,
        screenshot: BufferedImage? = null
    ): TargetPositionResult {
        val step = currentStep ?: return TargetPositionResult(
            region = null,
            source = context.TargetLocalizationSource.ABSTAIN,
            confidence = 0.0,
            isSemanticallyVerified = false,
            diagnostic = "No active tutorial step"
        )
        val result = targetPositioningService.resolveTarget(
            step = step,
            appContext = appContext,
            screenshot = screenshot
        )
        if (result.region != null) {
            setHighlight(result.region)
        }
        return result
    }

    /**
     * Resolves and highlights a target element by its semantic name (e.g. from chatbot recommendation
     * or user request), searching tutorial steps or dynamically grounding on screen.
     */
    suspend fun resolveTargetForName(
        targetName: String,
        appContext: ApplicationContext? = null,
        screenshot: BufferedImage? = null
    ): TargetPositionResult {
        if (!state.screenAssistanceEnabled) {
            state = state.copy(screenAssistanceEnabled = true)
        }
        val matchingStep = stepsForCurrentApp().find {
            it.targetControlName.equals(targetName, ignoreCase = true) ||
            it.title.contains(targetName, ignoreCase = true) ||
            it.instruction.contains(targetName, ignoreCase = true)
        } ?: currentStep?.copy(targetControlName = targetName)
        ?: TutorStep(
            id = 999,
            title = targetName,
            instruction = "Locate $targetName",
            description = "Dynamically resolved target for $targetName",
            targetControlName = targetName,
            stepNumber = 1,
            totalSteps = 1,
            targetRegion = HighlightRegion(100f, 100f, 120f, 40f)
        )

        val result = targetPositioningService.resolveTarget(
            step = matchingStep,
            appContext = appContext,
            screenshot = screenshot
        )
        if (result.region != null) {
            setHighlight(result.region)
        }
        return result
    }
}
