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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import models.ApplicationType
import models.HighlightRegion
import models.SessionContext
import models.TutorEvent
import models.TutorState
import models.TutorStep
import pedagogy.AdaptivePedagogicalEngine
import pedagogy.GuidanceStage
import pedagogy.LearningTask

/**
 * Owns the single [TutorState] instance and coordinates UI state mutations
 * with the [AdaptivePedagogicalEngine].
 */
class TutorController(
    val pedagogicalEngine: AdaptivePedagogicalEngine = AdaptivePedagogicalEngine(),
    val targetPositioningService: DynamicTargetPositioningService = DynamicTargetPositioningService()
) {

    var sessionContext by mutableStateOf(SessionContext())
        private set
    private val mutableEvents = MutableSharedFlow<TutorEvent>(replay = 16, extraBufferCapacity = 16)
    val events = mutableEvents.asSharedFlow()

    var state by mutableStateOf(TutorState())
        private set

    init {
        // Initialize adaptive instruction for the first step
        val firstStep = stepsForCurrentApp().first()
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

    fun stepsForCurrentApp(): List<TutorStep> =
        MockTutorData.stepsFor(state.selectedApplication)

    /** The step currently being shown to the user. */
    val currentStep: TutorStep
        get() = stepsForCurrentApp()[state.currentStepIndex]

    /** Natural-language instruction for the active step, grounded in pedagogical guidance. */
    val currentTargetDescription: String?
        get() = state.adaptiveInstruction?.instructionText
            ?: targetDescriptionForStep(currentStep)

    fun targetDescriptionForStep(step: TutorStep): String? = step.instruction.takeIf(String::isNotBlank)

    fun recordVisualContext(visualContext: VisualContext) {
        sessionContext = sessionContext.copy(lastVisualContext = visualContext)
        mutableEvents.tryEmit(TutorEvent.VisualContextCreated(visualContext))

        // Ground the current instruction in the newly observed visual context
        val currentTask = currentStep.toLearningTask()
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
        return diagnosticEngine.diagnose(
            expected = currentStep.expectedState,
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
        val decision = pedagogicalEngine.onVerificationResult(
            task = resolvedTask,
            result = result,
            visualContext = sessionContext.lastVisualContext
        )

        // If the verified task corresponds to the current step, update UI adaptive instruction
        val isCurrentStepTask = resolvedTask.taskId == currentStep.taskId
        state = state.copy(
            guidanceStage = decision.newStage,
            adaptiveInstruction = if (isCurrentStepTask) decision.instruction else state.adaptiveInstruction,
            lastPedagogicalOutcome = decision.outcome,
            adaptationReason = decision.rationale,
            competenceEstimate = decision.competenceEstimate,
            currentHighlight = if (isCurrentStepTask) (decision.instruction.targetRegion ?: currentStep.targetRegion) else state.currentHighlight
        )

        mutableEvents.tryEmit(TutorEvent.PedagogicalStateChanged(decision))
        if (decision.stageChanged) {
            mutableEvents.tryEmit(TutorEvent.GuidanceStageChanged(decision.newStage))
        }
    }

    /**
     * Submits learner's answer to the reflection question for the active step.
     */
    fun submitConceptualAnswer(answer: String) {
        val currentTask = currentStep.toLearningTask()
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
            currentHighlight = decision.instruction.targetRegion ?: currentStep.targetRegion
        )
        mutableEvents.tryEmit(TutorEvent.PedagogicalStateChanged(decision))
        if (decision.stageChanged) {
            mutableEvents.tryEmit(TutorEvent.GuidanceStageChanged(decision.newStage))
        }
    }

    fun requestStuckHelp() {
        submitConceptualAnswer("I'm stuck")
    }

    private fun resolveTaskForVerification(result: VerificationResult, expected: context.ExpectedState?): LearningTask {
        // 1. If current step's expected state matches the result operation, use it
        if (currentStep.expectedState != null && currentStep.expectedState?.operation == result.operation) {
            return currentStep.toLearningTask()
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
        val steps = MockTutorData.stepsFor(application)
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
        mutableEvents.tryEmit(TutorEvent.ApplicationChanged(application))
    }

    fun nextStep() {
        val steps = stepsForCurrentApp()
        val fromIndex = state.currentStepIndex
        val nextIndex = (fromIndex + 1).coerceAtMost(steps.lastIndex)
        if (fromIndex == nextIndex) return

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
        mutableEvents.tryEmit(TutorEvent.StepAdvanced(fromIndex, nextIndex))
    }

    fun previousStep() {
        val steps = stepsForCurrentApp()
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
        mutableEvents.tryEmit(TutorEvent.StepRegressed(fromIndex, prevIndex))
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
        val result = targetPositioningService.resolveTarget(
            step = currentStep,
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
        } ?: currentStep.copy(targetControlName = targetName)

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
