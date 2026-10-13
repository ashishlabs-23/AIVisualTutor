package tutor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import bridge.BlenderCaptureResult
import bridge.BlenderScreenCaptureService
import bridge.RobotBlenderScreenCaptureService
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates the screenshot-assisted help workflow for active tutoring lessons.
 * Ensures the tutoring session, goal, plan, and active step remain preserved throughout.
 */
class ScreenshotHelpCoordinator(
    private val controller: TutorController,
    private val captureService: BlenderScreenCaptureService = RobotBlenderScreenCaptureService(),
    private val analysisBoundary: ScreenshotAnalysisBoundary = SmartMultimodalScreenshotAnalyzer(),
    private val recommendationEngine: NextStepRecommendationEngine = AdaptiveNextStepRecommendationEngine()
) {
    var state: ScreenshotHelpWorkflowState by mutableStateOf(ScreenshotHelpWorkflowState.Idle)
        private set

    private val actionMutex = Mutex()
    private var inFlightSessionId: String? = null
    private var inFlightStepIndex: Int = -1

    /**
     * Initiates the screenshot-help workflow for the currently active tutoring step.
     */
    suspend fun startCapture(
        excludeOverlayAction: (suspend () -> Unit)? = null,
        restoreOverlayAction: (suspend () -> Unit)? = null
    ): Boolean {
        actionMutex.withLock {
            if (state is ScreenshotHelpWorkflowState.Capturing || state is ScreenshotHelpWorkflowState.Analyzing) {
                return false // Duplicate click prevention
            }

            val step = controller.currentStep
            val steps = controller.stepsForCurrentApp()
            if (step == null || steps.isEmpty()) {
                state = ScreenshotHelpWorkflowState.Error(
                    message = "No active tutorial step to capture help for. Please enter a goal first.",
                    canRetry = false
                )
                return false
            }

            val currentStepIdx = controller.state.currentStepIndex
            val planId = controller.activePlan?.let { "plan_${it.goalText.hashCode()}" }
            val sessionKey = planId ?: "session_${controller.sessionContext.hashCode()}"
            inFlightSessionId = sessionKey
            inFlightStepIndex = currentStepIdx

            state = ScreenshotHelpWorkflowState.Capturing(
                stepIndex = currentStepIdx,
                stepTitle = step.title
            )
        }

        try {
            val captureResult = captureService.captureBlenderWorkspace(
                excludeOverlayAction = excludeOverlayAction,
                restoreOverlayAction = restoreOverlayAction
            )

            actionMutex.withLock {
                // Stale check: verify user hasn't changed steps or cleared goal during capture
                val currentStep = controller.currentStep
                val currentStepIdx = controller.state.currentStepIndex
                val planId = controller.activePlan?.let { "plan_${it.goalText.hashCode()}" }
                val currentSessionKey = planId ?: "session_${controller.sessionContext.hashCode()}"

                if (currentStep == null || currentStepIdx != inFlightStepIndex || currentSessionKey != inFlightSessionId) {
                    if (captureResult is BlenderCaptureResult.Success) {
                        cleanUpFile(captureResult.pngPath)
                    }
                    state = ScreenshotHelpWorkflowState.Cancelled("Session or step changed during capture.")
                    return false
                }

                when (captureResult) {
                    is BlenderCaptureResult.Success -> {
                        val helpContext = ScreenshotHelpContext(
                            requestId = UUID.randomUUID().toString(),
                            sessionId = currentSessionKey,
                            userGoal = controller.activePlan?.goalText ?: currentStep.title,
                            planId = planId,
                            stepId = currentStep.id,
                            stepIndex = currentStepIdx,
                            stepTitle = currentStep.title,
                            stepInstruction = currentStep.instruction,
                            expectedOutcome = currentStep.expectedState?.let { "${it.operation.name} on ${it.targetName ?: "target"}" },
                            capturedAt = captureResult.capturedAt,
                            imagePath = captureResult.pngPath,
                            imageWidth = captureResult.image.width,
                            imageHeight = captureResult.image.height,
                            blenderProcessId = captureResult.windowInfo.processId,
                            blenderWindowBounds = captureResult.windowInfo.bounds,
                            recentTutoringMessages = listOfNotNull(controller.state.adaptationReason),
                            latestObservedStateSummary = controller.sessionContext.lastObservedState?.let {
                                "Scene: ${it.sceneName ?: "Default"}, Mode: ${it.mode ?: "OBJECT"}, Objects: ${it.objects.size}"
                            },
                            latestVerificationResult = controller.state.lastPedagogicalOutcome?.name
                        )
                        state = ScreenshotHelpWorkflowState.PreviewReady(
                            context = helpContext,
                            image = captureResult.image
                        )
                        return true
                    }
                    is BlenderCaptureResult.BlenderNotRunning -> {
                        state = ScreenshotHelpWorkflowState.Error(
                            message = captureResult.message,
                            canRetry = true
                        )
                        return false
                    }
                    is BlenderCaptureResult.BlenderMinimized -> {
                        state = ScreenshotHelpWorkflowState.Error(
                            message = captureResult.message,
                            canRetry = true
                        )
                        return false
                    }
                    is BlenderCaptureResult.WindowNotFound -> {
                        state = ScreenshotHelpWorkflowState.Error(
                            message = captureResult.message,
                            canRetry = true
                        )
                        return false
                    }
                    is BlenderCaptureResult.InvalidImage -> {
                        state = ScreenshotHelpWorkflowState.Error(
                            message = "Captured screenshot is invalid: ${captureResult.diagnostic}",
                            canRetry = true
                        )
                        return false
                    }
                    is BlenderCaptureResult.CaptureFailed -> {
                        state = ScreenshotHelpWorkflowState.Error(
                            message = "Screenshot capture failed: ${captureResult.diagnostic}",
                            diagnostic = captureResult.cause?.message,
                            canRetry = true
                        )
                        return false
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            actionMutex.withLock {
                state = ScreenshotHelpWorkflowState.Cancelled("Screenshot capture was cancelled.")
            }
            throw cancellation
        } catch (exception: Exception) {
            actionMutex.withLock {
                state = ScreenshotHelpWorkflowState.Error(
                    message = "Unexpected error during screenshot capture: ${exception.message ?: "Unknown error"}",
                    canRetry = true
                )
            }
            return false
        }
    }

    /**
     * Confirms the captured screenshot and submits it to the Phase 9 analysis boundary.
     */
    suspend fun confirmScreenshot(): Boolean {
        val currentContext: ScreenshotHelpContext
        actionMutex.withLock {
            val currentState = state
            if (currentState !is ScreenshotHelpWorkflowState.PreviewReady) {
                return false
            }

            // Stale check
            val planId = controller.activePlan?.let { "plan_${it.goalText.hashCode()}" }
            val currentSessionKey = planId ?: "session_${controller.sessionContext.hashCode()}"
            if (currentState.context.sessionId != currentSessionKey || currentState.context.stepIndex != controller.state.currentStepIndex) {
                cleanUpFile(currentState.context.imagePath)
                state = ScreenshotHelpWorkflowState.Error("Screenshot is associated with a different or expired tutoring step.", canRetry = true)
                return false
            }

            // Validate image file existence
            val file = File(currentState.context.imagePath)
            if (!file.isFile || file.length() <= 0) {
                state = ScreenshotHelpWorkflowState.Error("Captured screenshot file is missing or empty.", canRetry = true)
                return false
            }

            currentContext = currentState.context
            state = ScreenshotHelpWorkflowState.Analyzing(currentContext)
        }

        return try {
            val submissionResult = analysisBoundary.submitForAnalysis(currentContext)
            actionMutex.withLock {
                when (submissionResult) {
                    is ScreenshotAnalysisSubmissionResult.Success -> {
                        val recommendation = recommendationEngine.generateRecommendation(
                            context = currentContext,
                            analysisResult = submissionResult.analysisResult,
                            observedState = controller.sessionContext.lastObservedState,
                            learnerState = controller.pedagogicalEngine.state,
                            expectedState = controller.currentStep?.expectedState
                        )
                        state = ScreenshotHelpWorkflowState.ResponseReady(
                            context = submissionResult.context,
                            message = submissionResult.statusMessage,
                            analysisResult = submissionResult.analysisResult,
                            recommendation = recommendation
                        )
                        true
                    }
                    is ScreenshotAnalysisSubmissionResult.StagedForPhase9 -> {
                        val recommendation = recommendationEngine.generateRecommendation(
                            context = currentContext,
                            analysisResult = null,
                            observedState = controller.sessionContext.lastObservedState,
                            learnerState = controller.pedagogicalEngine.state,
                            expectedState = controller.currentStep?.expectedState
                        )
                        state = ScreenshotHelpWorkflowState.ResponseReady(
                            context = submissionResult.context,
                            message = submissionResult.statusMessage,
                            analysisResult = null,
                            recommendation = recommendation
                        )
                        true
                    }
                    is ScreenshotAnalysisSubmissionResult.Rejected -> {
                        state = ScreenshotHelpWorkflowState.Error(
                            message = "Analysis submission rejected: ${submissionResult.reason}",
                            canRetry = !submissionResult.isConfigurationError
                        )
                        false
                    }
                }
            }
        } catch (exception: Exception) {
            actionMutex.withLock {
                state = ScreenshotHelpWorkflowState.Error(
                    message = "Could not submit screenshot for analysis: ${exception.message ?: "Unknown error"}",
                    canRetry = true
                )
            }
            false
        }
    }

    /**
     * Retakes the screenshot for the active step without modifying tutoring progress.
     */
    suspend fun retakeScreenshot(
        excludeOverlayAction: (suspend () -> Unit)? = null,
        restoreOverlayAction: (suspend () -> Unit)? = null
    ): Boolean {
        cleanUpCurrentPreviewFile()
        return startCapture(excludeOverlayAction, restoreOverlayAction)
    }

    /**
     * Cancels the help request, cleans up any pending screenshot files, and preserves the lesson.
     */
    suspend fun cancelHelp() {
        actionMutex.withLock {
            cleanUpCurrentPreviewFile()
            state = ScreenshotHelpWorkflowState.Cancelled()
        }
    }

    /**
     * Dismisses the help workflow dialog/error and returns to Idle.
     */
    suspend fun dismiss() {
        actionMutex.withLock {
            cleanUpCurrentPreviewFile()
            state = ScreenshotHelpWorkflowState.Idle
            inFlightSessionId = null
            inFlightStepIndex = -1
        }
    }

    /**
     * Synchronously resets the coordinator to Idle and cleans up files without suspending.
     */
    fun resetSync() {
        cleanUpCurrentPreviewFile()
        state = ScreenshotHelpWorkflowState.Idle
        inFlightSessionId = null
        inFlightStepIndex = -1
    }

    /**
     * Resets the coordinator if the tutoring goal is cleared.
     */
    suspend fun reset() {
        dismiss()
    }

    private fun cleanUpCurrentPreviewFile() {
        val currentState = state
        when (currentState) {
            is ScreenshotHelpWorkflowState.PreviewReady -> cleanUpFile(currentState.context.imagePath)
            is ScreenshotHelpWorkflowState.Analyzing -> cleanUpFile(currentState.context.imagePath)
            is ScreenshotHelpWorkflowState.ResponseReady -> cleanUpFile(currentState.context.imagePath)
            else -> {}
        }
    }

    private fun cleanUpFile(path: String) {
        if (path.isNotBlank()) {
            runCatching {
                Files.deleteIfExists(Paths.get(path))
            }
        }
    }
}
