package ui

import context.EvidenceDecision
import context.EvidenceEvaluationResult
import context.EvidenceStatus
import context.GroundingProviderAvailability
import context.VisualContext
import context.VisualGroundingInvocationStatus
import java.awt.image.BufferedImage
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenshotPreviewEvidenceTest {
    @Test
    fun targetIsShownExactlyOrMarkedNotSpecified() {
        assertEquals(" Find Save exactly ", ScreenshotPreviewEvidence.pending(" Find Save exactly ").targetLabel)
        assertEquals("Not specified", ScreenshotPreviewEvidence.pending("  ").targetLabel)
        assertEquals("Not specified", ScreenshotPreviewEvidence.pending(null).targetLabel)
    }

    @Test
    fun previewShowsProviderStatusesAndDecisionWithoutGroundTruth() {
        val evaluation = EvidenceEvaluationResult(
            targetDescription = "Find Save",
            decision = EvidenceDecision.ABSTAIN,
            reason = "visual_provider_not_configured",
            uiAutomationStatus = EvidenceStatus.INSUFFICIENT,
            ocrStatus = EvidenceStatus.AVAILABLE,
            visualGroundingAvailability = GroundingProviderAvailability.NOT_CONFIGURED,
            visualGroundingInvocation = VisualGroundingInvocationStatus.NOT_CONFIGURED,
            visualGroundingProviderId = "visual-grounding-not-configured",
            visualGroundingDiagnostic = null,
            uiAutomationEvidence = null,
            ocrEvidence = null,
            visualGroundingEvidence = null
        )
        val context = VisualContext(
            regionId = UUID.randomUUID(),
            image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB),
            x = 0,
            y = 0,
            width = 4,
            height = 4,
            timestampMillis = 0,
            extractedText = null,
            contentType = null,
            applicationContext = null,
            confidence = null,
            metadata = emptyMap(),
            evidenceEvaluation = evaluation
        )

        val summary = ScreenshotPreviewEvidence.from("Find Save", context)

        assertEquals("Find Save", summary.targetLabel)
        assertEquals("INSUFFICIENT", summary.uiAutomationStatus)
        assertEquals("AVAILABLE", summary.ocrStatus)
        assertEquals("NOT_CONFIGURED", summary.visualGroundingStatus)
        assertEquals("ABSTAIN", summary.decision)
    }

    @Test
    fun rawCaptureShowsNotRunAndPending() {
        val summary = ScreenshotPreviewEvidence.pending(null)
        assertEquals("NOT_RUN", summary.uiAutomationStatus)
        assertEquals("NOT_RUN", summary.ocrStatus)
        assertEquals("NOT_RUN", summary.visualGroundingStatus)
        assertEquals("PENDING", summary.decision)
    }

    @Test
    fun processingFailureDoesNotPretendProvidersWereNotRun() {
        val summary = ScreenshotPreviewEvidence.processingFailure("Find Save")
        assertEquals("NOT_RECORDED", summary.uiAutomationStatus)
        assertEquals("NOT_RECORDED", summary.ocrStatus)
        assertEquals("NOT_RECORDED", summary.visualGroundingStatus)
        assertEquals("PENDING", summary.decision)
    }
}
