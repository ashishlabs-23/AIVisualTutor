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
    /** Resolved via 3D object projection from Blender's active viewport. */
    BLENDER_3D_PROJECTION,
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
 *   -> 0. Blender 3D Object Projection (when targeting scene objects in Blender)
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
    private val dpiNormalizer: DpiCoordinateNormalizer = DpiCoordinateNormalizer(),
    private val blenderAdapter: adapter.BlenderApplicationAdapter? = null
) {

    /**
     * Identifies whether the active step targets an actual 3D scene object (e.g. Cube, Sphere).
     */
    fun identifyTargetObject(step: TutorStep): String? {
        val expTarget = step.expectedState?.targetName?.trim().orEmpty()
        if (expTarget.isNotEmpty() && !isUiControlName(expTarget)) {
            return expTarget
        }
        val reqObj = step.expectedState?.requiredConditions
            ?.mapNotNull { it.objectName?.trim() }
            ?.firstOrNull { it.isNotBlank() && !isUiControlName(it) }
        if (reqObj != null) {
            return reqObj
        }
        val ctrlName = step.targetControlName?.trim().orEmpty()
        if (ctrlName.isNotEmpty() && isLikelySceneObjectName(ctrlName)) {
            return ctrlName
        }
        val normTitle = step.title.lowercase()
        return when {
            normTitle.contains("cube") -> "Cube"
            normTitle.contains("sphere") -> "Sphere"
            normTitle.contains("camera") -> "Camera"
            normTitle.contains("light") -> "Light"
            else -> null
        }
    }

    private fun isUiControlName(name: String): Boolean {
        val n = name.lowercase().trim()
        return n in setOf(
            "mode", "object mode", "edit mode", "render", "render image",
            "material properties", "material", "transform", "location",
            "item", "add", "delete", "3d viewport", "outliner", "view"
        )
    }

    private fun isLikelySceneObjectName(name: String): Boolean {
        val n = name.lowercase().trim()
        if (isUiControlName(name)) return false
        val scenePrimitives = setOf(
            "cube", "sphere", "uv sphere", "ico sphere", "cylinder", "cone",
            "plane", "torus", "grid", "monkey", "suzanne", "camera", "light",
            "selected", "active", "active_object"
        )
        return n in scenePrimitives || n.contains(".") || Character.isUpperCase(name.firstOrNull() ?: 'a')
    }

    fun isUpwardMovementStep(step: TutorStep): Boolean {
        val titleNorm = step.title.lowercase()
        val instrNorm = step.instruction.lowercase()
        val prop = step.expectedState?.targetProperty?.lowercase()
        val propVal = step.expectedState?.expectedPropertyValue
        val hasZUpCond = step.expectedState?.requiredConditions?.any {
            it.propertyName?.equals("location.z", ignoreCase = true) == true &&
                it.expectedValue?.contains(">") == true
        } == true

        return (titleNorm.contains("move") && titleNorm.contains("up")) ||
            (prop == "location.z" && propVal?.contains(">") == true) ||
            hasZUpCond ||
            (instrNorm.contains("z-axis") && instrNorm.contains("up"))
    }

    /**
     * Validates that target bounding box meets plausibility requirements:
     * - Non-collapsed, non-empty dimensions (minimum 4x4 pixels).
     * - Intersects application window if window bounds are known.
     * - Does not engulf the entire application window (e.g. background root canvas).
     */
    fun isValidControlBounds(bounds: Rectangle?, windowBounds: Rectangle?): Boolean {
        if (bounds == null) return false
        if (bounds.width < 4 || bounds.height < 4) return false

        if (windowBounds != null && windowBounds.width > 0 && windowBounds.height > 0) {
            if (!windowBounds.intersects(bounds)) return false
            if (bounds.width >= windowBounds.width * 0.98 && bounds.height >= windowBounds.height * 0.98) return false
        }
        return true
    }

    suspend fun resolveTarget(
        step: TutorStep,
        appContext: ApplicationContext? = null,
        screenshot: BufferedImage? = null,
        allowStepDefaultFallback: Boolean = true
    ): TargetPositionResult {
        // 0. Try Blender 3D object projection if adapter is configured and target is an identifiable scene object
        val objectTarget = identifyTargetObject(step)
        if (blenderAdapter != null && objectTarget != null) {
            when (val proj = blenderAdapter.projectObject(objectTarget)) {
                is adapter.ProjectedObjectResult.Success -> {
                    val isMoveUp = isUpwardMovementStep(step)
                    val region = if (isMoveUp) {
                        proj.bounds.copy(
                            directionalIndicator = models.DirectionalIndicator(
                                axis = models.DirectionalAxis.UP_POSITIVE_Z,
                                label = "+Z Up",
                                hint = "Press G, then Z to constrain to the vertical Z-axis and move upward"
                            )
                        )
                    } else {
                        proj.bounds
                    }
                    val confidence = when (proj.visibility) {
                        "VISIBLE" -> 0.99
                        "PARTIALLY_CLIPPED" -> 0.85
                        "POTENTIALLY_OCCLUDED" -> 0.50
                        else -> 0.90
                    }
                    val occlusionNote = if (proj.occludedBy != null) " (occluded by ${proj.occludedBy})" else ""
                    return TargetPositionResult(
                        region = region,
                        source = TargetLocalizationSource.BLENDER_3D_PROJECTION,
                        confidence = confidence,
                        isSemanticallyVerified = true,
                        candidateName = proj.targetName,
                        diagnostic = "Projected 3D object '${proj.targetName}' (${proj.identity}) [${proj.visibility}$occlusionNote] from active 3D viewport."
                    )
                }
                is adapter.ProjectedObjectResult.AmbiguousViewport -> {
                    return TargetPositionResult(
                        region = null,
                        source = TargetLocalizationSource.BLENDER_3D_PROJECTION,
                        confidence = 0.0,
                        isSemanticallyVerified = false,
                        candidateName = objectTarget,
                        diagnostic = "Multiple 3D viewports detected (${proj.viewportCount}); viewport is ambiguous."
                    )
                }
                is adapter.ProjectedObjectResult.NotProjected -> {
                    return TargetPositionResult(
                        region = null,
                        source = TargetLocalizationSource.BLENDER_3D_PROJECTION,
                        confidence = 0.0,
                        isSemanticallyVerified = false,
                        candidateName = objectTarget,
                        diagnostic = "Object '$objectTarget' exists in Blender but is ${proj.reason}: ${proj.diagnostic}"
                    )
                }
                is adapter.ProjectedObjectResult.NotFound -> {
                    if (step.expectedState?.targetName != null && step.expectedState.targetName.equals(objectTarget, ignoreCase = true)) {
                        return TargetPositionResult(
                            region = null,
                            source = TargetLocalizationSource.BLENDER_3D_PROJECTION,
                            confidence = 0.0,
                            isSemanticallyVerified = false,
                            candidateName = objectTarget,
                            diagnostic = "Target object '$objectTarget' not found in active Blender scene."
                        )
                    }
                }
                is adapter.ProjectedObjectResult.Error -> {
                    // Blender adapter error or disconnected; fall through
                }
            }
        }

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

        // 4. Fallback to step-defined targetRegion if allowed and valid
        if (allowStepDefaultFallback && step.targetRegion != null) {
            val fallback = step.targetRegion
            val fallbackRect = Rectangle(fallback.x.roundToInt(), fallback.y.roundToInt(), fallback.width.roundToInt(), fallback.height.roundToInt())
            if (isValidControlBounds(fallbackRect, appContext?.windowBounds)) {
                return TargetPositionResult(
                    region = fallback,
                    source = TargetLocalizationSource.STEP_DEFAULT,
                    confidence = 0.5,
                    isSemanticallyVerified = false,
                    candidateName = step.targetControlName,
                    diagnostic = "Dynamic localization fell back to step default region."
                )
            }
        }

        // 5. Abstain gracefully when no target can be localized reliably
        return TargetPositionResult(
            region = null,
            source = TargetLocalizationSource.ABSTAIN,
            confidence = 0.0,
            isSemanticallyVerified = false,
            diagnostic = "Unable to localize target '${queryText}' reliably across UIA, OCR, or visual grounding."
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
        val windowBounds = appContext?.windowBounds
        val matchingCandidates = candidates.filter { candidate ->
            val name = candidate.name?.lowercase()?.trim().orEmpty()
            val autoId = candidate.automationId?.lowercase()?.trim().orEmpty()
            val bounds = candidate.bounds
            val isNotOffscreen = candidate.isOffscreen != true
            val isValidBounds = isValidControlBounds(bounds, windowBounds)

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

        val similarity = textSimilarity(queryText, bestCandidate.name.orEmpty())
        val isExact = bestCandidate.name.equals(queryText, ignoreCase = true)
        val confidence = if (isExact) 0.98 else (0.80 + similarity * 0.18).coerceAtMost(0.95)

        val logicalBounds = dpiNormalizer.physicalToLogical(bounds)
        val highlight = HighlightRegion(
            x = logicalBounds.x.toFloat(),
            y = logicalBounds.y.toFloat(),
            width = logicalBounds.width.toFloat(),
            height = logicalBounds.height.toFloat(),
            isScreenCoordinates = true
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

        // Validate bounds against application window and clickable dimensions
        if (!isValidControlBounds(screenBounds, appContext?.windowBounds)) {
            return null
        }

        // Apply DPI normalization from physical screen pixels to logical Compose DIPs
        val logicalBounds = dpiNormalizer.physicalToLogical(screenBounds)
        val highlight = HighlightRegion(
            x = logicalBounds.x.toFloat(),
            y = logicalBounds.y.toFloat(),
            width = logicalBounds.width.toFloat(),
            height = logicalBounds.height.toFloat(),
            isScreenCoordinates = true
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

        if (!isValidControlBounds(screenBounds, appContext?.windowBounds)) {
            return null
        }

        val logicalBounds = dpiNormalizer.physicalToLogical(screenBounds)
        val highlight = HighlightRegion(
            x = logicalBounds.x.toFloat(),
            y = logicalBounds.y.toFloat(),
            width = logicalBounds.width.toFloat(),
            height = logicalBounds.height.toFloat(),
            isScreenCoordinates = true
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

