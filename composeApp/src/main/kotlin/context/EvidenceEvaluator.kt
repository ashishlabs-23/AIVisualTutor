package context

import java.awt.image.BufferedImage
import kotlinx.coroutines.CancellationException

enum class EvidenceStatus { AVAILABLE, INSUFFICIENT, UNAVAILABLE, FAILURE, NOT_RUN, CANCELLED }

enum class EvidenceDecision { ACCEPT, REFINE, ESCALATE, ABSTAIN }

enum class VisualGroundingInvocationStatus {
    NOT_NEEDED,
    PENDING,
    NOT_INVOKED_MISSING_TARGET,
    UNAVAILABLE,
    NOT_CONFIGURED,
    INVOKED,
    RETURNED_EVIDENCE,
    FAILED,
    CANCELLED
}

data class EvidenceEvaluationInput(
    val targetDescription: String?,
    val uiAutomationEvidence: PerceptionResult?,
    val uiAutomationStatus: EvidenceStatus,
    val ocrEvidence: OcrResult?,
    val ocrStatus: EvidenceStatus,
    val visualGroundingEvidence: VisualGroundingResult? = null,
    val visualGroundingAvailability: GroundingProviderAvailability = GroundingProviderAvailability.NOT_CONFIGURED,
    val visualGroundingDiagnostic: String? = null,
    val requiresVisualGrounding: Boolean = false,
    val screenshotWidth: Int,
    val screenshotHeight: Int
)

data class EvidenceEvaluationResult(
    val targetDescription: String?,
    val decision: EvidenceDecision,
    val reason: String,
    val uiAutomationStatus: EvidenceStatus,
    val ocrStatus: EvidenceStatus,
    val visualGroundingAvailability: GroundingProviderAvailability,
    val visualGroundingInvocation: VisualGroundingInvocationStatus,
    val visualGroundingProviderId: String?,
    val visualGroundingDiagnostic: String?,
    val uiAutomationEvidence: PerceptionResult?,
    val ocrEvidence: OcrResult?,
    val visualGroundingEvidence: VisualGroundingResult?
)

/** Deterministic policy. Source confidences remain separate and are never combined. */
class EvidenceEvaluator {
    fun evaluate(input: EvidenceEvaluationInput): EvidenceEvaluationResult {
        val target = input.targetDescription?.trim()?.takeIf(String::isNotEmpty)
        val visual = input.visualGroundingEvidence
        val visualAvailability = visual?.availability ?: input.visualGroundingAvailability

        if (target == null) {
            return result(input, EvidenceDecision.ABSTAIN, "target_description_missing", visualAvailability)
        }

        val uia = input.uiAutomationEvidence
        val ocr = input.ocrEvidence
        val uiText = uia?.let { it.selectedObject ?: it.visibleText }
        val uiMatches = input.uiAutomationStatus == EvidenceStatus.AVAILABLE &&
            uia != null &&
            uia.confidence.meets(UIA_MATCH_THRESHOLD) &&
            uia.boundingRectangle != null &&
            textMatchesTarget(target, uiText)
        val ocrMatches = input.ocrStatus == EvidenceStatus.AVAILABLE &&
            ocr != null &&
            ocr.confidence.meets(OCR_MATCH_THRESHOLD) &&
            ocr.words.isNotEmpty() &&
            textMatchesTarget(target, ocr.text)

        val uiConflictsWithOcr = uiMatches &&
            input.ocrStatus == EvidenceStatus.AVAILABLE &&
            ocr != null &&
            ocr.confidence.meets(OCR_MATCH_THRESHOLD) &&
            ocr.text.isNotBlank() &&
            isConciseCandidateText(ocr.text) &&
            !textMatchesTarget(target, ocr.text)
        val ocrConflictsWithUi = ocrMatches &&
            input.uiAutomationStatus == EvidenceStatus.AVAILABLE &&
            uia != null &&
            uia.confidence.meets(UIA_MATCH_THRESHOLD) &&
            !uiText.isNullOrBlank() &&
            isConciseCandidateText(uiText) &&
            !textMatchesTarget(target, uiText)
        if (uiConflictsWithOcr || ocrConflictsWithUi) {
            return result(input, EvidenceDecision.ESCALATE, "conflicting_high_confidence_source_text", visualAvailability)
        }

        if (visual != null && isStrongVisualMatch(target, visual, input.screenshotWidth, input.screenshotHeight)) {
            return result(input, EvidenceDecision.ACCEPT, "visual_semantic_and_geometry_match", visualAvailability)
        }

        if (visual != null) {
            return result(input, EvidenceDecision.ABSTAIN, "evidence_insufficient_after_visual_attempt", visualAvailability)
        }

        if (input.requiresVisualGrounding) {
            return result(input, EvidenceDecision.ESCALATE, "visual_grounding_required", visualAvailability)
        }
        if (uiMatches || ocrMatches) {
            return result(input, EvidenceDecision.ACCEPT, if (uiMatches) "structured_uia_match" else "ocr_text_match", visualAvailability)
        }

        return when (visualAvailability) {
            GroundingProviderAvailability.AVAILABLE ->
                result(input, EvidenceDecision.REFINE, "available_visual_provider_may_improve_evidence", visualAvailability)
            GroundingProviderAvailability.UNAVAILABLE ->
                result(input, EvidenceDecision.ABSTAIN, "visual_provider_unavailable", visualAvailability)
            GroundingProviderAvailability.NOT_CONFIGURED ->
                result(input, EvidenceDecision.ABSTAIN, "visual_provider_not_configured", visualAvailability)
            GroundingProviderAvailability.FAILURE ->
                result(input, EvidenceDecision.ABSTAIN, "visual_provider_failure", visualAvailability)
            GroundingProviderAvailability.CANCELLED ->
                result(input, EvidenceDecision.ABSTAIN, "visual_provider_cancelled", visualAvailability)
        }
    }

    private fun result(
        input: EvidenceEvaluationInput,
        decision: EvidenceDecision,
        reason: String,
        availability: GroundingProviderAvailability
    ): EvidenceEvaluationResult {
        val visual = input.visualGroundingEvidence
        return EvidenceEvaluationResult(
            targetDescription = input.targetDescription,
            decision = decision,
            reason = reason,
            uiAutomationStatus = input.uiAutomationStatus,
            ocrStatus = input.ocrStatus,
            visualGroundingAvailability = availability,
            visualGroundingInvocation = if (decision == EvidenceDecision.REFINE || decision == EvidenceDecision.ESCALATE) {
                VisualGroundingInvocationStatus.PENDING
            } else {
                VisualGroundingInvocationStatus.NOT_NEEDED
            },
            visualGroundingProviderId = visual?.providerId,
            visualGroundingDiagnostic = visual?.metadata?.get("diagnostic")
                ?: visual?.metadata?.get("status")
                ?: input.visualGroundingDiagnostic,
            uiAutomationEvidence = input.uiAutomationEvidence,
            ocrEvidence = input.ocrEvidence,
            visualGroundingEvidence = input.visualGroundingEvidence
        )
    }

    private fun isStrongVisualMatch(
        target: String,
        evidence: VisualGroundingResult,
        screenshotWidth: Int,
        screenshotHeight: Int
    ): Boolean {
        val semantic = evidence.semantic ?: return false
        val geometry = evidence.geometry ?: return false
        if (evidence.availability != GroundingProviderAvailability.AVAILABLE ||
            semantic.confidence.meets(VISUAL_SEMANTIC_THRESHOLD).not() ||
            geometry.confidence.meets(VISUAL_GEOMETRY_THRESHOLD).not() ||
            !textMatchesTarget(target, semantic.target)
        ) return false
        return when (geometry.coordinateSpace) {
            VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS ->
                geometry.isWithin(screenshotWidth.toDouble(), screenshotHeight.toDouble())
            VisualGroundingCoordinateSpace.NORMALIZED_CROP ->
                geometry.isWithin(1.0, 1.0)
        }
    }

    private fun GroundingGeometricEvidence.isWithin(width: Double, height: Double): Boolean {
        val bounds = bounds
        if (bounds != null) {
            return bounds.left.isFinite() && bounds.top.isFinite() &&
                bounds.right.isFinite() && bounds.bottom.isFinite() &&
                bounds.left >= 0.0 && bounds.top >= 0.0 &&
                bounds.right > bounds.left && bounds.bottom > bounds.top &&
                bounds.right <= width && bounds.bottom <= height
        }
        val point = point ?: return false
        return point.x.isFinite() && point.y.isFinite() &&
            point.x >= 0.0 && point.y >= 0.0 &&
            point.x < width && point.y < height
    }

    private fun Float?.meets(threshold: Float): Boolean = this != null && isFinite() && this >= threshold

    private fun textMatchesTarget(target: String, evidence: String?): Boolean {
        if (evidence.isNullOrBlank()) return false
        val targetTokens = tokens(target).filterNot(IGNORED_TARGET_TOKENS::contains).toSet()
        if (targetTokens.isEmpty()) return false
        return tokens(evidence).containsAll(targetTokens)
    }

    private fun tokens(value: String) =
        value.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)

    private fun isConciseCandidateText(value: String) = tokens(value).size <= MAX_CONFLICT_LABEL_TOKENS

    companion object {
        const val UIA_MATCH_THRESHOLD = 0.8f
        const val OCR_MATCH_THRESHOLD = 0.8f
        const val VISUAL_SEMANTIC_THRESHOLD = 0.8f
        const val VISUAL_GEOMETRY_THRESHOLD = 0.65f
        const val MAX_CONFLICT_LABEL_TOKENS = 3

        private val IGNORED_TARGET_TOKENS = setOf(
            "select", "click", "press", "tap", "choose", "the", "a", "an",
            "button", "control", "element", "icon"
        )
    }
}

/** Executes at most one configured visual provider call for one target evaluation. */
class EvidenceEvaluationCoordinator(
    private val provider: VisualGroundingProvider = NotConfiguredVisualGroundingProvider(),
    private val evaluator: EvidenceEvaluator = EvidenceEvaluator()
) {
    suspend fun evaluate(
        screenshot: BufferedImage,
        applicationContext: ApplicationContext?,
        targetDescription: String?,
        uiAutomationEvidence: PerceptionResult?,
        uiAutomationStatus: EvidenceStatus,
        ocrEvidence: OcrResult?,
        ocrStatus: EvidenceStatus,
        requiresVisualGrounding: Boolean = false
    ): EvidenceEvaluationResult {
        val providerAvailability = provider.availability
        val providerDiagnostic = provider.availabilityDiagnostic
        val providerId = provider.providerId
        val initialInput = EvidenceEvaluationInput(
            targetDescription = targetDescription,
            uiAutomationEvidence = uiAutomationEvidence,
            uiAutomationStatus = uiAutomationStatus,
            ocrEvidence = ocrEvidence,
            ocrStatus = ocrStatus,
            visualGroundingAvailability = providerAvailability,
            visualGroundingDiagnostic = providerDiagnostic,
            requiresVisualGrounding = requiresVisualGrounding,
            screenshotWidth = screenshot.width,
            screenshotHeight = screenshot.height
        )
        val initial = evaluator.evaluate(initialInput)
        val target = targetDescription?.trim()?.takeIf(String::isNotEmpty)
            ?: return initial.copy(
                visualGroundingInvocation = VisualGroundingInvocationStatus.NOT_INVOKED_MISSING_TARGET
            )

        val shouldInvoke = initial.decision != EvidenceDecision.ACCEPT
        if (!shouldInvoke) return initial

        if (providerAvailability != GroundingProviderAvailability.AVAILABLE) {
            val invocation = when (providerAvailability) {
                GroundingProviderAvailability.UNAVAILABLE -> VisualGroundingInvocationStatus.UNAVAILABLE
                GroundingProviderAvailability.NOT_CONFIGURED -> VisualGroundingInvocationStatus.NOT_CONFIGURED
                GroundingProviderAvailability.FAILURE -> VisualGroundingInvocationStatus.FAILED
                GroundingProviderAvailability.CANCELLED -> VisualGroundingInvocationStatus.CANCELLED
                GroundingProviderAvailability.AVAILABLE -> error("unreachable")
            }
            val final = evaluator.evaluate(initialInput.copy(
                visualGroundingAvailability = providerAvailability,
                visualGroundingDiagnostic = providerDiagnostic,
                requiresVisualGrounding = false
            ))
            return final.copy(
                visualGroundingInvocation = invocation,
                visualGroundingProviderId = providerId
            )
        }

        val visualResult = try {
            provider.ground(VisualGroundingRequest(
                screenshot = screenshot,
                applicationContext = applicationContext,
                targetDescription = target,
                ocrEvidence = ocrEvidence,
                uiAutomationEvidence = uiAutomationEvidence
            ))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VisualGroundingResult(
                semantic = null,
                geometry = null,
                providerId = providerId,
                availability = GroundingProviderAvailability.FAILURE,
                metadata = mapOf("status" to "provider_failure", "diagnostic" to e.javaClass.simpleName)
            )
        }
        val final = evaluator.evaluate(initialInput.copy(
            visualGroundingEvidence = visualResult,
            visualGroundingAvailability = visualResult.availability,
            visualGroundingDiagnostic = visualResult.metadata["diagnostic"] ?: visualResult.metadata["status"],
            requiresVisualGrounding = requiresVisualGrounding
        ))
        val invocation = when (visualResult.availability) {
            GroundingProviderAvailability.AVAILABLE ->
                if (visualResult.semantic != null || visualResult.geometry != null) VisualGroundingInvocationStatus.RETURNED_EVIDENCE
                else VisualGroundingInvocationStatus.INVOKED
            GroundingProviderAvailability.UNAVAILABLE -> VisualGroundingInvocationStatus.UNAVAILABLE
            GroundingProviderAvailability.NOT_CONFIGURED -> VisualGroundingInvocationStatus.NOT_CONFIGURED
            GroundingProviderAvailability.FAILURE -> VisualGroundingInvocationStatus.FAILED
            GroundingProviderAvailability.CANCELLED -> VisualGroundingInvocationStatus.CANCELLED
        }
        return final.copy(visualGroundingInvocation = invocation, visualGroundingProviderId = visualResult.providerId)
    }
}
