package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import models.HighlightRegion
import models.TutorStep

/**
 * Origin of the localized target highlight.
 */
enum class TargetLocalizationSource {
    /** Resolved deterministically via Windows UI Automation element tree. */
    UI_AUTOMATION,
    /** Resolved via optical character recognition match on rendered window/screen text. */
    OCR,
    /** Resolved via multimodal visual grounding (VLM / visual detector). */
    VISUAL_GROUNDING,
    /** Fallback default specified on the tutorial step definition. */
    STEP_DEFAULT,
    /** No confident candidate found; guidance should abstain or ask for user clarification. */
    ABSTAIN
}

/**
 * Outcome of dynamic target localization.
 */
data class TargetPositionResult(
    val region: HighlightRegion?,
    val source: TargetLocalizationSource,
    val confidence: Double,
    val isSemanticallyVerified: Boolean,
    val candidateName: String? = null,
    val diagnostic: String? = null,
    val boundsInScreenCoordinates: Rectangle? = null
)

/**
 * Service implementing layered target-location strategy:
 * Target description + Active application
 *   -> 1. UI Automation
 *   -> 2. OCR matching
 *   -> 3. Visual grounding via RegionFocus sub-cropping
 *   -> 4. Validation (DPI, window bounds, clickable dimensions)
 *   -> Highlight region
 */
class DynamicTargetPositioningService(
    private val uiaClient: UiAutomationClient? = null,
    private val ocrService: OCRService? = null,
    private val groundingRouter: EvidenceAdaptiveGroundingRouter? = null,
    private val regionFocusService: RegionFocusService = RegionFocusService(),
    private val dpiNormalizer: DpiCoordinateNormalizer = DpiCoordinateNormalizer()
) {

    suspend fun resolveTarget(
        step: TutorStep,
        appContext: ApplicationContext? = null,
        screenshot: BufferedImage? = null
    ): TargetPositionResult {
        val targetName = step.targetControlName?.trim().orEmpty()
        val targetDesc = step.targetAreaDescription?.trim().orEmpty()
        val queryText = if (targetName.isNotEmpty()) targetName else step.instruction

        // 1. Try Windows UI Automation if window handle is available
        val windowHandle = appContext?.windowHandle?.takeIf { it != 0L }
        if (windowHandle != null && uiaClient != null && queryText.isNotBlank()) {
            val uiaResult = resolveViaUiAutomation(windowHandle, queryText, appContext)
            if (uiaResult != null) {
                return uiaResult
            }
        }

        // 2. Try OCR matching if screenshot is available
        if (screenshot != null && ocrService != null && queryText.isNotBlank()) {
            val ocrResult = resolveViaOcr(screenshot, queryText, appContext)
            if (ocrResult != null) {
                return ocrResult
            }
        }

        // 3. Try Visual Grounding if router is configured and screenshot is available
        if (screenshot != null && groundingRouter != null && (targetDesc.isNotBlank() || queryText.isNotBlank())) {
            val groundingDesc = if (targetDesc.isNotBlank()) "$queryText in $targetDesc" else queryText
            val groundingResult = resolveViaGrounding(screenshot, groundingDesc, appContext)
            if (groundingResult != null) {
                return groundingResult
            }
        }

        // 4. Fallback to step-defined targetRegion if present
        if (step.targetRegion != null) {
            val fallback = step.targetRegion
            return TargetPositionResult(
                region = fallback,
                source = TargetLocalizationSource.STEP_DEFAULT,
                confidence = 0.5,
                isSemanticallyVerified = false,
                candidateName = step.targetControlName,
                diagnostic = "Dynamic localization fell back to step default region."
            )
        }

        // 5. Abstain gracefully when no target can be localized
        return TargetPositionResult(
            region = null,
            source = TargetLocalizationSource.ABSTAIN,
            confidence = 0.0,
            isSemanticallyVerified = false,
            diagnostic = "Unable to localize target '${queryText}' across UIA, OCR, or visual grounding."
        )
    }

    private fun resolveViaUiAutomation(
        windowHandle: Long,
        queryText: String,
        appContext: ApplicationContext?
    ): TargetPositionResult? {
        val candidates = try {
            uiaClient?.descendants(windowHandle).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

        if (candidates.isEmpty()) return null

        val normalizedQuery = queryText.lowercase().trim()
        val matchingCandidates = candidates.filter { candidate ->
            val name = candidate.name?.lowercase()?.trim().orEmpty()
            val autoId = candidate.automationId?.lowercase()?.trim().orEmpty()
            val bounds = candidate.bounds
            val isValidBounds = bounds != null && bounds.width > 4 && bounds.height > 4
            val isNotOffscreen = candidate.isOffscreen != true

            isValidBounds && isNotOffscreen && (
                name == normalizedQuery ||
                name.contains(normalizedQuery) ||
                (autoId.isNotEmpty() && autoId.contains(normalizedQuery))
            )
        }

        val bestCandidate = matchingCandidates.maxByOrNull { candidate ->
            val name = candidate.name.orEmpty()
            textSimilarity(queryText, name)
        } ?: return null

        val bounds = bestCandidate.bounds ?: return null

        // Validate coordinates against application window if bounds are known
        val windowBounds = appContext?.windowBounds
        if (windowBounds != null && windowBounds.width > 0 && windowBounds.height > 0) {
            if (!windowBounds.intersects(bounds)) {
                return null // Rejected: element is outside the target application's window
            }
        }

        val similarity = textSimilarity(queryText, bestCandidate.name.orEmpty())
        val isExact = bestCandidate.name.equals(queryText, ignoreCase = true)
        val confidence = if (isExact) 0.98 else (0.80 + similarity * 0.18).coerceAtMost(0.95)

        val logicalBounds = dpiNormalizer.physicalToLogical(bounds)
        val highlight = HighlightRegion(
            x = logicalBounds.x.toFloat(),
            y = logicalBounds.y.toFloat(),
            width = logicalBounds.width.toFloat(),
            height = logicalBounds.height.toFloat()
        )

        return TargetPositionResult(
            region = highlight,
            source = TargetLocalizationSource.UI_AUTOMATION,
            confidence = confidence,
            isSemanticallyVerified = similarity >= 0.75 || isExact,
            candidateName = bestCandidate.name,
            diagnostic = "Matched UIA element '${bestCandidate.name}' [autoId=${bestCandidate.automationId ?: "none"}]",
            boundsInScreenCoordinates = bounds
        )
    }

    private suspend fun resolveViaOcr(
        screenshot: BufferedImage,
        queryText: String,
        appContext: ApplicationContext?
    ): TargetPositionResult? {
        val ocrResult = try {
            ocrService?.recognize(screenshot)
        } catch (_: Exception) {
            null
        } ?: return null

        val words = ocrResult.words.filter { it.bounds.width > 0 && it.bounds.height > 0 }
        if (words.isEmpty()) return null

        val normalizedQuery = queryText.lowercase().trim()

        // Match individual words or grouped lines
        val exactWord = words.firstOrNull { it.text.equals(normalizedQuery, ignoreCase = true) }
        val targetBox: Rectangle?
        val matchedText: String
        val score: Double

        if (exactWord != null) {
            targetBox = exactWord.bounds
            matchedText = exactWord.text
            score = 0.90
        } else {
            // Check groups/lines
            val lines = words.groupBy { it.lineIndex ?: 0 }
            val bestLine = lines.entries.map { entry ->
                val lineText = entry.value.joinToString(" ") { it.text }
                val sim = textSimilarity(queryText, lineText)
                val unionBounds = entry.value.map { it.bounds }.reduceOrNull { a, b -> a.union(b) }
                Triple(lineText, sim, unionBounds)
            }.filter { it.third != null }.maxByOrNull { it.second }

            if (bestLine != null && bestLine.second >= 0.72) {
                targetBox = bestLine.third
                matchedText = bestLine.first
                score = 0.75 + (bestLine.second * 0.15)
            } else {
                return null
            }
        }

        if (targetBox == null) return null

        // Convert screenshot pixel coords to desktop coords if window origin is known
        val originX = appContext?.windowBounds?.x ?: 0
        val originY = appContext?.windowBounds?.y ?: 0

        val screenBounds = Rectangle(
            originX + targetBox.x,
            originY + targetBox.y,
            targetBox.width,
            targetBox.height
        )

        val highlight = HighlightRegion(
            x = screenBounds.x.toFloat(),
            y = screenBounds.y.toFloat(),
            width = screenBounds.width.toFloat(),
            height = screenBounds.height.toFloat()
        )

        return TargetPositionResult(
            region = highlight,
            source = TargetLocalizationSource.OCR,
            confidence = score,
            isSemanticallyVerified = score >= 0.80,
            candidateName = matchedText,
            diagnostic = "Matched OCR text '${matchedText}' at offset (${targetBox.x}, ${targetBox.y})",
            boundsInScreenCoordinates = screenBounds
        )
    }

    private suspend fun resolveViaGrounding(
        screenshot: BufferedImage,
        targetDescription: String,
        appContext: ApplicationContext?
    ): TargetPositionResult? {
        val crop = regionFocusService.createFocusedCrop(
            screenshot = screenshot,
            targetBoundsOnScreen = null,
            appContext = appContext,
            targetAreaDescription = targetDescription
        )

        val request = GroundingRequest(
            screenshot = crop.croppedImage,
            targetDescription = targetDescription,
            mode = GroundingMode.ADAPTIVE,
            applicationContext = appContext
        )

        val result = try {
            groundingRouter?.run(request)
        } catch (_: Exception) {
            null
        } ?: return null

        val acceptedBox = result.acceptedBox ?: return null
        val (screenX, screenY) = crop.transform.cropToScreen(acceptedBox.x, acceptedBox.y)

        val screenBounds = Rectangle(
            screenX.roundToInt(),
            screenY.roundToInt(),
            acceptedBox.width.toInt(),
            acceptedBox.height.toInt()
        )

        val logicalBounds = dpiNormalizer.physicalToLogical(screenBounds)
        val highlight = HighlightRegion(
            x = logicalBounds.x.toFloat(),
            y = logicalBounds.y.toFloat(),
            width = logicalBounds.width.toFloat(),
            height = logicalBounds.height.toFloat()
        )

        val confidence = result.selectedCandidate?.score ?: 0.75

        return TargetPositionResult(
            region = highlight,
            source = TargetLocalizationSource.VISUAL_GROUNDING,
            confidence = confidence,
            isSemanticallyVerified = result.decision == EvidenceDecision.ACCEPT,
            candidateName = result.selectedCandidate?.candidateId,
            diagnostic = "Grounded via RegionFocus (${crop.source}, ${(crop.estimatedPixelReductionRatio * 100).toInt()}% pixel reduction): ${result.decisionReason}",
            boundsInScreenCoordinates = screenBounds
        )
    }
}
