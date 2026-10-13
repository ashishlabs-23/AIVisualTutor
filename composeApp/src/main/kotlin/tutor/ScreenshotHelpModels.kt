package tutor

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.time.Instant
import java.util.UUID

/**
 * Conceptual lifecycle stages for screenshot-assisted help workflow.
 */
enum class ScreenshotHelpLifecycleStage {
    IDLE,
    CAPTURING,
    PREVIEW_READY,
    ANALYZING,
    RESPONSE_READY,
    ERROR,
    CANCELLED
}

/**
 * Immutable context bundle associating the captured screenshot with the active lesson.
 */
data class ScreenshotHelpContext(
    val requestId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val userGoal: String,
    val planId: String?,
    val stepId: Int,
    val stepIndex: Int,
    val stepTitle: String,
    val stepInstruction: String,
    val expectedOutcome: String?,
    val capturedAt: Instant = Instant.now(),
    val imagePath: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val blenderProcessId: Long?,
    val blenderWindowBounds: Rectangle?,
    val recentTutoringMessages: List<String> = emptyList(),
    val latestObservedStateSummary: String? = null,
    val latestVerificationResult: String? = null
)

/**
 * Sealed hierarchy representing the observable UI state of the screenshot help workflow.
 */
sealed interface ScreenshotHelpWorkflowState {
    val stage: ScreenshotHelpLifecycleStage

    object Idle : ScreenshotHelpWorkflowState {
        override val stage: ScreenshotHelpLifecycleStage get() = ScreenshotHelpLifecycleStage.IDLE
    }

    data class Capturing(
        val stepIndex: Int,
        val stepTitle: String
    ) : ScreenshotHelpWorkflowState {
        override val stage: ScreenshotHelpLifecycleStage get() = ScreenshotHelpLifecycleStage.CAPTURING
    }

    data class PreviewReady(
        val context: ScreenshotHelpContext,
        val image: BufferedImage
    ) : ScreenshotHelpWorkflowState {
        override val stage: ScreenshotHelpLifecycleStage get() = ScreenshotHelpLifecycleStage.PREVIEW_READY
    }

    data class Analyzing(
        val context: ScreenshotHelpContext
    ) : ScreenshotHelpWorkflowState {
        override val stage: ScreenshotHelpLifecycleStage get() = ScreenshotHelpLifecycleStage.ANALYZING
    }

    data class ResponseReady(
        val context: ScreenshotHelpContext,
        val message: String,
        val analysisResult: ScreenshotAnalysisResult? = null,
        val recommendation: TutorRecommendation? = null
    ) : ScreenshotHelpWorkflowState {
        override val stage: ScreenshotHelpLifecycleStage get() = ScreenshotHelpLifecycleStage.RESPONSE_READY
    }

    data class Error(
        val message: String,
        val diagnostic: String? = null,
        val canRetry: Boolean = true
    ) : ScreenshotHelpWorkflowState {
        override val stage: ScreenshotHelpLifecycleStage get() = ScreenshotHelpLifecycleStage.ERROR
    }

    data class Cancelled(
        val reason: String = "Help request cancelled by learner"
    ) : ScreenshotHelpWorkflowState {
        override val stage: ScreenshotHelpLifecycleStage get() = ScreenshotHelpLifecycleStage.CANCELLED
    }
}

/**
 * Explicit integration boundary for Phase 9 Multimodal Vision Analysis.
 */
interface ScreenshotAnalysisBoundary {
    suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult
}

/**
 * Result returned when submitting a captured screenshot and context to the analysis boundary.
 */
sealed interface ScreenshotAnalysisSubmissionResult {
    data class Success(
        val context: ScreenshotHelpContext,
        val analysisResult: ScreenshotAnalysisResult,
        val statusMessage: String = analysisResult.summary
    ) : ScreenshotAnalysisSubmissionResult

    data class StagedForPhase9(
        val context: ScreenshotHelpContext,
        val statusMessage: String = "Screenshot and lesson context captured successfully. Staged for Phase 9 multimodal AI analysis."
    ) : ScreenshotAnalysisSubmissionResult

    data class Rejected(
        val reason: String,
        val isConfigurationError: Boolean = false
    ) : ScreenshotAnalysisSubmissionResult
}

/**
 * Phase 8 baseline implementation: stages the verified request without fabricating AI vision responses.
 */
class Phase8PendingAnalysisBoundary : ScreenshotAnalysisBoundary {
    override suspend fun submitForAnalysis(context: ScreenshotHelpContext): ScreenshotAnalysisSubmissionResult {
        if (context.imagePath.isBlank() || context.imageWidth <= 0 || context.imageHeight <= 0) {
            return ScreenshotAnalysisSubmissionResult.Rejected("Invalid screenshot image data.")
        }
        return ScreenshotAnalysisSubmissionResult.StagedForPhase9(
            context = context,
            statusMessage = "Screenshot captured and verified for Step ${context.stepIndex + 1} ('${context.stepTitle}'). Analysis will be connected in Phase 9."
        )
    }
}
