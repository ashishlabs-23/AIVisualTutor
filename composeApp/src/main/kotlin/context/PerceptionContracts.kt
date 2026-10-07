package context

import java.awt.Rectangle
import java.awt.image.BufferedImage

/** Inputs already collected by Phase 3 for a future perception engine. */
data class PerceptionRequest(
    val screenshot: BufferedImage,
    /** Selected bounds in Java AWT desktop user-space coordinates. */
    val selectedRegion: Rectangle,
    val applicationContext: ApplicationContext?,
    val targetDescription: String? = null
)

enum class UiType {
    UNKNOWN, TEXT, BUTTON, INPUT, MENU, TOOLBAR, DIALOG, TABLE, IMAGE, OTHER
}

enum class PerceptionSource {
    UNKNOWN, UI_AUTOMATION, OCR, COMPUTER_VISION, VLM
}

/** Structured observation produced from a screenshot, selection, and application context. */
data class PerceptionResult(
    val applicationName: String?,
    val selectedObject: String?,
    val visibleText: String?,
    val uiType: UiType,
    /** Bounds use the coordinate space documented by the producing engine. */
    val boundingRectangle: Rectangle?,
    val confidence: Float?,
    val source: PerceptionSource,
    val metadata: Map<String, String> = emptyMap()
)

/** Async seam for platform perception providers. */
fun interface PerceptionEngine {
    suspend fun perceive(request: PerceptionRequest): PerceptionResult
}

/** Input contract for optional task-conditioned visual grounding. */
data class VisualGroundingRequest(
    val screenshot: BufferedImage,
    val applicationContext: ApplicationContext?,
    /** Natural-language target instruction; null while the current selection flow supplies none. */
    val targetDescription: String?,
    val ocrEvidence: OcrResult?,
    val uiAutomationEvidence: PerceptionResult?
)

enum class VisualGroundingCoordinateSpace { CROP_IMAGE_PIXELS, NORMALIZED_CROP }
enum class GroundingProviderAvailability { AVAILABLE, UNAVAILABLE, NOT_CONFIGURED, FAILURE, CANCELLED }
enum class GroundingExecutionLocation { LOCAL, REMOTE, UNKNOWN }

/** Semantic identity and its confidence are independent from any geometric prediction. */
data class GroundingSemanticEvidence(
    val target: String,
    val targetType: String? = null,
    val confidence: Float? = null
)

/** Double precision preserves normalized model coordinates without rounding them to desktop ints. */
data class GroundingBounds(val left: Double, val top: Double, val right: Double, val bottom: Double)
data class GroundingPoint(val x: Double, val y: Double)

/** Model geometry describes the selected screenshot unless another space is declared explicitly. */
data class GroundingGeometricEvidence(
    val bounds: GroundingBounds? = null,
    val point: GroundingPoint? = null,
    val coordinateSpace: VisualGroundingCoordinateSpace,
    val confidence: Float? = null
) {
    init { require(bounds != null || point != null) { "Grounding geometry must contain bounds or a point." } }
}

data class VisualGroundingResult(
    val semantic: GroundingSemanticEvidence?,
    val geometry: GroundingGeometricEvidence?,
    val providerId: String,
    val availability: GroundingProviderAvailability,
    val executionLocation: GroundingExecutionLocation = GroundingExecutionLocation.UNKNOWN,
    val latencyMillis: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

/** Replaceable task-conditioned GUI-grounding boundary. */
fun interface VisualGroundingProvider {
    val providerId: String
        get() = "visual-grounding-provider"

    val availability: GroundingProviderAvailability
        get() = GroundingProviderAvailability.AVAILABLE

    val availabilityDiagnostic: String?
        get() = null

    suspend fun ground(request: VisualGroundingRequest): VisualGroundingResult
}

/** Default provider boundary when no model/runtime has been configured. */
class UnavailableVisualGroundingProvider(
    private val reason: String = "provider_unavailable"
) : VisualGroundingProvider {
    override val providerId = "visual-grounding-unavailable"
    override val availability = GroundingProviderAvailability.UNAVAILABLE
    override val availabilityDiagnostic = reason.take(80)

    override suspend fun ground(request: VisualGroundingRequest) = VisualGroundingResult(
        semantic = null,
        geometry = null,
        providerId = providerId,
        availability = availability,
        executionLocation = GroundingExecutionLocation.UNKNOWN,
        metadata = mapOf("status" to availabilityDiagnostic.orEmpty())
    )
}

class NotConfiguredVisualGroundingProvider(
    private val reason: String = "no_model_or_runtime_configured"
) : VisualGroundingProvider {
    override val providerId = "visual-grounding-not-configured"
    override val availability = GroundingProviderAvailability.NOT_CONFIGURED
    override val availabilityDiagnostic = reason.take(80)

    override suspend fun ground(request: VisualGroundingRequest) = VisualGroundingResult(
        semantic = null,
        geometry = null,
        providerId = providerId,
        availability = availability,
        executionLocation = GroundingExecutionLocation.UNKNOWN,
        metadata = mapOf("status" to availabilityDiagnostic.orEmpty())
    )
}
