package ui

import context.EvidenceEvaluationResult
import context.EvidenceDecision
import context.VisualContext
import context.VisualGroundingInvocationStatus

data class ScreenshotPreviewEvidence(
    val targetDescription: String?,
    val uiAutomationStatus: String,
    val ocrStatus: String,
    val visualGroundingStatus: String,
    val decision: String,
    val visualReason: String? = null
) {
    val targetLabel: String
        get() = targetDescription?.takeUnless(String::isBlank) ?: "Not specified"

    companion object {
        fun pending(targetDescription: String?) = ScreenshotPreviewEvidence(
            targetDescription = targetDescription,
            uiAutomationStatus = "NOT_RUN",
            ocrStatus = "NOT_RUN",
            visualGroundingStatus = "NOT_RUN",
            decision = "PENDING",
            visualReason = null
        )

        fun processingFailure(targetDescription: String?) = ScreenshotPreviewEvidence(
            targetDescription = targetDescription,
            uiAutomationStatus = "NOT_RECORDED",
            ocrStatus = "NOT_RECORDED",
            visualGroundingStatus = "NOT_RECORDED",
            decision = "PENDING",
            visualReason = null
        )

        fun from(targetDescription: String?, visualContext: VisualContext): ScreenshotPreviewEvidence {
            val evaluation = visualContext.evidenceEvaluation
            return ScreenshotPreviewEvidence(
                targetDescription = targetDescription,
                uiAutomationStatus = evaluation?.uiAutomationStatus?.name ?: "NOT_RECORDED",
                ocrStatus = evaluation?.ocrStatus?.name ?: "NOT_RECORDED",
                visualGroundingStatus = evaluation.visualStatus(),
                decision = evaluation?.decision?.name ?: "PENDING",
                visualReason = if (evaluation?.visualGroundingInvocation == VisualGroundingInvocationStatus.HOST_BLOCKED) {
                    evaluation.visualGroundingPreflight?.diagnostic
                        ?: "insufficient available memory for safe local inference"
                } else {
                    null
                }
            )
        }

        private fun EvidenceEvaluationResult?.visualStatus(): String {
            if (this == null) return "NOT_RECORDED"
            return when (visualGroundingInvocation) {
                VisualGroundingInvocationStatus.NOT_NEEDED -> "NOT_INVOKED"
                VisualGroundingInvocationStatus.PENDING -> "PENDING"
                VisualGroundingInvocationStatus.NOT_INVOKED_MISSING_TARGET -> "NOT_INVOKED_MISSING_TARGET"
                VisualGroundingInvocationStatus.UNAVAILABLE -> "UNAVAILABLE"
                VisualGroundingInvocationStatus.NOT_CONFIGURED -> "NOT_CONFIGURED"
                VisualGroundingInvocationStatus.HOST_BLOCKED -> "HOST_BLOCKED"
                VisualGroundingInvocationStatus.INVOKED -> "INVOKED"
                VisualGroundingInvocationStatus.RETURNED_EVIDENCE ->
                    visualGroundingEvidence?.availability?.name ?: "AVAILABLE"
                VisualGroundingInvocationStatus.FAILED -> "FAILURE"
                VisualGroundingInvocationStatus.CANCELLED -> "CANCELLED"
            }
        }
    }
}
