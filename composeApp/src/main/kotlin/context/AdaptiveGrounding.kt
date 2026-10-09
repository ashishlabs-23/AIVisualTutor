package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Explicit experiment configurations. A run never silently changes its requested mode. */
enum class GroundingMode { UIA_ONLY, OCR_ONLY, UIA_OCR, VISION_ONLY, ADAPTIVE }
enum class GroundingProviderId { UIA, OCR, VISION }
enum class GroundingProviderStatus {
    NOT_REQUESTED, SUCCESS, SUCCESS_NO_MATCH, EMPTY_RESULT, UNAVAILABLE, NOT_CONFIGURED,
    EXECUTION_FAILED, INVALID_OUTPUT, CANCELLED, TIMED_OUT, INCOMPATIBLE_COORDINATES
}
enum class GroundingDecisionReason {
    UNIQUE_SUPPORTED_CANDIDATE, MULTIPLE_PLAUSIBLE_CANDIDATES, CONFLICTING_EVIDENCE,
    NO_MATCH, PROVIDER_UNAVAILABLE, INCOMPATIBLE_COORDINATES, INVALID_TARGET, INVALID_SCREENSHOT,
    INSUFFICIENT_EVIDENCE
}
enum class GroundingBoxSpace { SCREENSHOT_PIXELS, SCREEN_COORDINATES, NORMALIZED_SCREENSHOT }
enum class GroundingGeometryKind { BOX, POINT }

data class GroundingBox(val x: Double, val y: Double, val width: Double, val height: Double) {
    val centerX get() = x + width / 2.0
    val centerY get() = y + height / 2.0
    fun validWithin(widthLimit: Double, heightLimit: Double): Boolean =
        x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
            x >= 0.0 && y >= 0.0 && width > 0.0 && height > 0.0 &&
            x + width <= widthLimit && y + height <= heightLimit
}

/** Explicit transform from physical desktop coordinates into this run's screenshot pixels. */
data class ScreenToScreenshotTransform(
    val screenOriginX: Double,
    val screenOriginY: Double,
    val scaleX: Double,
    val scaleY: Double
) {
    init { require(scaleX.isFinite() && scaleY.isFinite() && scaleX > 0 && scaleY > 0) }
    fun box(screen: Rectangle) = GroundingBox(
        (screen.x - screenOriginX) * scaleX,
        (screen.y - screenOriginY) * scaleY,
        screen.width * scaleX,
        screen.height * scaleY
    )
}

data class GroundingObservation(
    val source: GroundingProviderId,
    val candidateId: String,
    val observedText: String?,
    val targetDescription: String,
    val box: GroundingBox?,
    val originalBox: GroundingBox?,
    val originalCoordinateSpace: GroundingBoxSpace?,
    val coordinateSpace: GroundingBoxSpace?,
    /** Provider-supplied confidence only. Similarity and agreement are separate fields. */
    val providerConfidence: Double?,
    val textSimilarity: Double,
    val sourceMetadata: Map<String, String> = emptyMap(),
    val executionId: String
)

data class GroundingCandidate(
    val candidateId: String,
    val observations: List<GroundingObservation>,
    val box: GroundingBox?,
    val coordinateSpace: GroundingBoxSpace?,
    val targetSimilarity: Double,
    val crossProviderAgreement: Double?,
    val score: Double
)

data class GroundingProviderRun(
    val provider: GroundingProviderId,
    val status: GroundingProviderStatus,
    val latencyMillis: Long,
    val observations: List<GroundingObservation> = emptyList(),
    val diagnostic: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class GroundingThresholds(
    val minimumTextSimilarity: Double = 0.72,
    val minimumAcceptedScore: Double = 0.78,
    val minimumWinnerMargin: Double = 0.12,
    val minimumIouForAssociation: Double = 0.20
) {
    init {
        require(listOf(minimumTextSimilarity, minimumAcceptedScore, minimumWinnerMargin, minimumIouForAssociation)
            .all { it.isFinite() && it in 0.0..1.0 })
    }
}

data class GroundingRequest(
    val screenshot: BufferedImage,
    val targetDescription: String?,
    val mode: GroundingMode,
    val applicationContext: ApplicationContext? = null,
    val selectedRegionOnDesktop: Rectangle? = null,
    val screenToScreenshot: ScreenToScreenshotTransform? = null,
    val screenshotReference: String? = null,
    val executionId: String = UUID.randomUUID().toString(),
    val experimentId: String = "PHASE5",
    val caseId: String? = null,
    val groundTruthReference: String? = null,
    val thresholds: GroundingThresholds = GroundingThresholds(),
    val adaptiveVisionPolicy: AdaptiveVisionPolicy = AdaptiveVisionPolicy.ON_AMBIGUITY_OR_MISSING_EVIDENCE,
    val providerTimeoutMillis: Long = 30_000
)

enum class AdaptiveVisionPolicy { ALWAYS, ON_AMBIGUITY_OR_MISSING_EVIDENCE }

data class GroundingResult(
    val experimentId: String,
    val caseId: String?,
    val runId: String,
    val timestamp: String,
    val mode: GroundingMode,
    val targetDescription: String?,
    val screenshotWidth: Int,
    val screenshotHeight: Int,
    val requestedProviders: Set<GroundingProviderId>,
    val executedProviders: Set<GroundingProviderId>,
    val successfulProviders: Set<GroundingProviderId>,
    val failedProviders: Set<GroundingProviderId>,
    val skippedReasons: Map<GroundingProviderId, String>,
    val providerRuns: List<GroundingProviderRun>,
    val candidates: List<GroundingCandidate>,
    val selectedCandidate: GroundingCandidate?,
    val decision: EvidenceDecision,
    val reason: GroundingDecisionReason,
    val decisionReason: String,
    val totalLatencyMillis: Long,
    val configuration: GroundingThresholds,
    val modelId: String? = null,
    val screenshotReference: String? = null,
    val groundTruthReference: String? = null
) {
    val acceptedBox: GroundingBox? get() = if (decision == EvidenceDecision.ACCEPT) selectedCandidate?.box?.takeIf { it.width > 0 && it.height > 0 } else null
    val acceptedPoint: Pair<Double, Double>? get() = if (decision == EvidenceDecision.ACCEPT) selectedCandidate?.takeIf { it.observations.any { item -> item.sourceMetadata["geometryKind"] == "POINT" } }?.box?.let { it.x to it.y } else null
    val proposedCandidate: GroundingCandidate? get() = selectedCandidate ?: candidates
        .filter { candidate ->
            candidate.targetSimilarity >= configuration.minimumTextSimilarity ||
                candidate.observations.any { it.source == GroundingProviderId.VISION && it.sourceMetadata["geometryKind"] == "POINT" }
        }
        .maxByOrNull { it.score }
    val proposedBox: GroundingBox? get() = proposedCandidate?.box
}

fun interface GroundingUiaProvider { suspend fun collect(request: GroundingRequest): GroundingProviderRun }
fun interface GroundingOcrProvider { suspend fun collect(request: GroundingRequest): GroundingProviderRun }
fun interface GroundingVisionProvider { suspend fun collect(request: GroundingRequest, allowEvidence: Boolean): GroundingProviderRun }
fun interface GroundingResultSink {
    fun persist(result: GroundingResult)
    fun persist(result: GroundingResult, screenshot: BufferedImage) = persist(result)
}

/** Normalizes provider output. It deliberately does not infer screen/crop alignment. */
class GroundingNormalizer {
    fun uia(result: PerceptionResult, request: GroundingRequest): List<GroundingObservation> {
        if (result.metadata["isEnabled"] == "false" || result.metadata["isOffscreen"] == "true") return emptyList()
        val raw = result.boundingRectangle ?: return emptyList()
        val mapped = request.screenToScreenshot?.box(raw)
        val valid = mapped?.takeIf { it.validWithin(request.screenshot.width.toDouble(), request.screenshot.height.toDouble()) }
        val text = result.selectedObject ?: result.visibleText
        return listOf(GroundingObservation(
            GroundingProviderId.UIA, "uia:${result.metadata["automationId"] ?: "single"}", text,
            request.targetDescription.orEmpty(), valid, GroundingBox(raw.x.toDouble(), raw.y.toDouble(), raw.width.toDouble(), raw.height.toDouble()),
            GroundingBoxSpace.SCREEN_COORDINATES, if (valid == null) null else GroundingBoxSpace.SCREENSHOT_PIXELS,
            result.confidence?.toDouble(), textSimilarity(request.targetDescription.orEmpty(), text.orEmpty()),
            result.metadata + mapOf("controlType" to result.uiType.name, "coordinateStatus" to if (valid == null) "UNALIGNED" else "ALIGNED"), request.executionId
        ))
    }

    fun ocr(result: OcrResult, request: GroundingRequest): List<GroundingObservation> {
        val words = result.words
        // Group by OCR line when available; words without line metadata remain separate.
        val groups = words.groupBy { it.lineIndex?.let { line -> "line:$line" } ?: "word:${it.wordIndex ?: it.text}" }
        return groups.flatMap { (key, group) ->
            val lineText = group.joinToString(" ") { it.text }
            val regions = if (group.size > 1 && textSimilarity(request.targetDescription.orEmpty(), lineText) < request.thresholds.minimumTextSimilarity) {
                group.map { listOf(it) }
            } else listOf(group)
            regions.mapNotNull { region ->
            val text = region.joinToString(" ") { it.text }
            val rectangle = region.map { it.bounds }.reduceOrNull(Rectangle::union) ?: return@mapNotNull null
            val box = GroundingBox(rectangle.x.toDouble(), rectangle.y.toDouble(), rectangle.width.toDouble(), rectangle.height.toDouble())
            if (!box.validWithin(request.screenshot.width.toDouble(), request.screenshot.height.toDouble())) return@mapNotNull null
            GroundingObservation(
                GroundingProviderId.OCR, "ocr:$key", text, request.targetDescription.orEmpty(), box, box,
                GroundingBoxSpace.SCREENSHOT_PIXELS, GroundingBoxSpace.SCREENSHOT_PIXELS,
                region.mapNotNull { it.confidence?.takeIf(Float::isFinite)?.toDouble() }.takeIf { it.isNotEmpty() }?.average(),
                textSimilarity(request.targetDescription.orEmpty(), text),
                mapOf("engine" to result.engineName, "coordinateSpace" to result.coordinateSpace.name, "wordCount" to region.size.toString()), request.executionId
            )
            }
        }
    }

    fun vision(result: VisualGroundingResult, request: GroundingRequest): List<GroundingObservation> {
        val geometry = result.geometry ?: return emptyList()
        val original = geometry.bounds?.let { GroundingBox(it.left, it.top, it.right - it.left, it.bottom - it.top) }
            ?: geometry.point?.let { GroundingBox(it.x, it.y, 0.0, 0.0) }
        val (box, kind) = when (geometry.coordinateSpace) {
            VisualGroundingCoordinateSpace.NORMALIZED_CROP -> {
                val bounds = geometry.bounds?.let { GroundingBox(it.left * request.screenshot.width, it.top * request.screenshot.height,
                    (it.right - it.left) * request.screenshot.width, (it.bottom - it.top) * request.screenshot.height) }
                val point = geometry.point?.let { GroundingBox(it.x * request.screenshot.width, it.y * request.screenshot.height, 0.0, 0.0) }
                (bounds ?: point) to if (bounds != null) "BOX" else "POINT"
            }
            VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS -> {
                val bounds = geometry.bounds?.let { GroundingBox(it.left, it.top, it.right - it.left, it.bottom - it.top) }
                val point = geometry.point?.let { GroundingBox(it.x, it.y, 0.0, 0.0) }
                (bounds ?: point) to if (bounds != null) "BOX" else "POINT"
            }
        }
        if (box == null) return emptyList()
        val valid = if (kind == "POINT") box.x.isFinite() && box.y.isFinite() && box.x >= 0 && box.y >= 0 && box.x < request.screenshot.width && box.y < request.screenshot.height
            else box.validWithin(request.screenshot.width.toDouble(), request.screenshot.height.toDouble())
        if (!valid) return emptyList()
        return listOf(GroundingObservation(
            GroundingProviderId.VISION, "vision:${result.providerId}", result.semantic?.target, request.targetDescription.orEmpty(),
            box, original, geometry.coordinateSpace.toGroundingSpace(), GroundingBoxSpace.SCREENSHOT_PIXELS,
            geometry.confidence?.takeIf(Float::isFinite)?.toDouble(), textSimilarity(request.targetDescription.orEmpty(), result.semantic?.target.orEmpty()),
            result.metadata + mapOf("model" to (result.metadata["model"] ?: "NOT_RECORDED"), "geometryKind" to kind,
                "rawModelOutput" to (result.metadata["rawModelOutput"] ?: "NOT_RECORDED")), request.executionId
        ))
    }
}

private fun VisualGroundingCoordinateSpace.toGroundingSpace() = when (this) {
    VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS -> GroundingBoxSpace.SCREENSHOT_PIXELS
    VisualGroundingCoordinateSpace.NORMALIZED_CROP -> GroundingBoxSpace.NORMALIZED_SCREENSHOT
}

/** Text similarity is a normalized token Dice score, not a provider confidence. */
fun textSimilarity(target: String, observed: String): Double {
    val a = normalizeGroundingText(target).split(' ').filter(String::isNotBlank).toSet()
    val b = normalizeGroundingText(observed).split(' ').filter(String::isNotBlank).toSet()
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return 2.0 * a.intersect(b).size / (a.size + b.size)
}

fun normalizeGroundingText(value: String): String = value.lowercase()
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    .split(' ').filterNot { it in setOf("click", "select", "press", "tap", "choose", "the", "a", "an", "button", "control", "element", "icon") }
    .joinToString(" ")

/** Associates only compatible screenshot-pixel boxes with text support or meaningful overlap. */
class GroundingCandidateGenerator(private val thresholds: GroundingThresholds = GroundingThresholds()) {
    fun generate(observations: List<GroundingObservation>): List<GroundingCandidate> {
        val groups = mutableListOf<MutableList<GroundingObservation>>()
        observations.distinct().forEach { observation ->
            val compatible = observation.coordinateSpace == GroundingBoxSpace.SCREENSHOT_PIXELS && observation.box != null
            val matches = if (!compatible) emptyList() else groups.filter { group ->
                group.none { it.source == observation.source } && group.any { existing ->
                    canAssociate(existing, observation)
                }
            }
            val match = matches.singleOrNull()
            if (match == null) groups += mutableListOf(observation) else match += observation
        }
        return groups.mapIndexed { index, group ->
            val boxes = group.mapNotNull { it.box }
            val box = boxes.firstOrNull() // Preserve a source geometry; never average across modalities.
            val text = group.maxOfOrNull { it.textSimilarity } ?: 0.0
            val agreement = if (group.size < 2) null else group.flatMapIndexed { i, item ->
                group.drop(i + 1).map { other ->
                    val textAgree = textSimilarity(item.observedText.orEmpty(), other.observedText.orEmpty())
                    val spatialAgree = spatialAgreement(item, other)
                    if (item.sourceMetadata["geometryKind"] == "POINT" || other.sourceMetadata["geometryKind"] == "POINT") {
                        spatialAgree ?: 0.0
                    } else if (spatialAgree == null) textAgree else (textAgree + spatialAgree) / 2.0
                }
            }.average()
            // Equal source contribution avoids comparing uncalibrated provider confidence values.
            val textEvidence = group.filter { !it.observedText.isNullOrBlank() }.map { it.textSimilarity }
            val score = if (group.isEmpty()) 0.0 else (textEvidence.takeIf { it.isNotEmpty() }?.average() ?: 0.0) * 0.80 +
                (agreement ?: 0.0) * 0.20
            GroundingCandidate("candidate-${index + 1}", group.toList(), box,
                if (box == null) null else GroundingBoxSpace.SCREENSHOT_PIXELS, text, agreement, score.coerceIn(0.0, 1.0))
        }
    }

    private fun canAssociate(first: GroundingObservation, second: GroundingObservation): Boolean {
        val textAgrees = textSimilarity(first.observedText.orEmpty(), second.observedText.orEmpty()) >= thresholds.minimumTextSimilarity
        if (textAgrees) {
            return (spatialAgreement(first, second) ?: 0.0) >= thresholds.minimumIouForAssociation
        }
        val visualPoint = when {
            first.source == GroundingProviderId.VISION && first.sourceMetadata["geometryKind"] == "POINT" -> first
            second.source == GroundingProviderId.VISION && second.sourceMetadata["geometryKind"] == "POINT" -> second
            else -> return false
        }
        val labeled = if (visualPoint === first) second else first
        return labeled.textSimilarity >= thresholds.minimumTextSimilarity &&
            containsPoint(labeled.box, visualPoint.box)
    }
}

private fun spatialAgreement(a: GroundingObservation, b: GroundingObservation): Double? {
    val aIsPoint = a.source == GroundingProviderId.VISION && a.sourceMetadata["geometryKind"] == "POINT"
    val bIsPoint = b.source == GroundingProviderId.VISION && b.sourceMetadata["geometryKind"] == "POINT"
    return when {
        aIsPoint && bIsPoint -> null
        aIsPoint -> if (containsPoint(b.box, a.box)) 1.0 else 0.0
        bIsPoint -> if (containsPoint(a.box, b.box)) 1.0 else 0.0
        else -> listOfNotNull(iou(a.box, b.box), overlapRatio(a.box, b.box)).maxOrNull()
    }
}

private fun containsPoint(bounds: GroundingBox?, point: GroundingBox?): Boolean =
    bounds != null && point != null && point.width == 0.0 && point.height == 0.0 &&
        bounds.validWithin(Double.MAX_VALUE, Double.MAX_VALUE) &&
        point.x >= bounds.x && point.x <= bounds.x + bounds.width &&
        point.y >= bounds.y && point.y <= bounds.y + bounds.height

/** Measures overlap relative to the smaller region so contained text boxes can associate to controls. */
fun overlapRatio(a: GroundingBox?, b: GroundingBox?): Double? {
    if (a == null || b == null || a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return null
    val left = maxOf(a.x, b.x); val top = maxOf(a.y, b.y)
    val right = minOf(a.x + a.width, b.x + b.width); val bottom = minOf(a.y + a.height, b.y + b.height)
    val intersection = maxOf(0.0, right - left) * maxOf(0.0, bottom - top)
    val smallerArea = minOf(a.width * a.height, b.width * b.height)
    return if (smallerArea <= 0.0) null else intersection / smallerArea
}

fun iou(a: GroundingBox?, b: GroundingBox?): Double? {
    if (a == null || b == null || a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return null
    val left = maxOf(a.x, b.x); val top = maxOf(a.y, b.y)
    val right = minOf(a.x + a.width, b.x + b.width); val bottom = minOf(a.y + a.height, b.y + b.height)
    val intersection = maxOf(0.0, right - left) * maxOf(0.0, bottom - top)
    val union = a.width * a.height + b.width * b.height - intersection
    return if (union <= 0) null else intersection / union
}

class GroundingConfidenceEvaluator {
    fun decide(candidates: List<GroundingCandidate>, request: GroundingRequest, runs: List<GroundingProviderRun>): Pair<EvidenceDecision, GroundingDecisionReason> {
        if (request.targetDescription.isNullOrBlank()) return EvidenceDecision.ABSTAIN to GroundingDecisionReason.INVALID_TARGET
        val allObservations = candidates.flatMap { it.observations }
        val supportedTextObservations = allObservations.filter {
            it.textSimilarity >= request.thresholds.minimumTextSimilarity && it.box != null &&
                it.coordinateSpace == GroundingBoxSpace.SCREENSHOT_PIXELS
        }
        val unsupportedVisualPoint = allObservations.any { point ->
            point.source == GroundingProviderId.VISION && point.sourceMetadata["geometryKind"] == "POINT" &&
                supportedTextObservations.any { text ->
                    text.source != point.source && (spatialAgreement(text, point) ?: 0.0) < request.thresholds.minimumIouForAssociation
                }
        }
        if (unsupportedVisualPoint) return EvidenceDecision.ESCALATE to GroundingDecisionReason.CONFLICTING_EVIDENCE
        val conflicting = allObservations.any { first ->
            allObservations.any { second ->
                val firstText = normalizeGroundingText(first.observedText.orEmpty())
                val secondText = normalizeGroundingText(second.observedText.orEmpty())
                first.source != second.source && firstText.isNotEmpty() && secondText.isNotEmpty() &&
                textSimilarity(firstText, secondText) < 0.25 &&
                (spatialAgreement(first, second) ?: 0.0) >= request.thresholds.minimumIouForAssociation
            }
        }
        if (conflicting) return EvidenceDecision.ESCALATE to GroundingDecisionReason.CONFLICTING_EVIDENCE
        val unconfirmedVisualPoint = candidates.any { candidate ->
            candidate.observations.any { it.source == GroundingProviderId.VISION && it.sourceMetadata["geometryKind"] == "POINT" } &&
                candidate.box?.let { it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.y >= 0 && it.x < request.screenshot.width && it.y < request.screenshot.height } == true &&
                candidate.targetSimilarity < request.thresholds.minimumTextSimilarity
        }
        if (unconfirmedVisualPoint) return EvidenceDecision.REFINE to GroundingDecisionReason.INSUFFICIENT_EVIDENCE
        val valid = candidates.filter { candidate ->
            candidate.box != null && candidate.coordinateSpace == GroundingBoxSpace.SCREENSHOT_PIXELS &&
                (if (candidate.observations.any { it.sourceMetadata["geometryKind"] == "POINT" }) {
                    candidate.box.x.isFinite() && candidate.box.y.isFinite() && candidate.box.x >= 0 && candidate.box.y >= 0 &&
                        candidate.box.x < request.screenshot.width && candidate.box.y < request.screenshot.height
                } else candidate.box.validWithin(request.screenshot.width.toDouble(), request.screenshot.height.toDouble())) &&
                candidate.targetSimilarity >= request.thresholds.minimumTextSimilarity
        }.sortedWith(compareByDescending<GroundingCandidate> { it.score }.thenBy { it.candidateId })
        if (valid.isEmpty()) {
            if (runs.any { it.status == GroundingProviderStatus.INCOMPATIBLE_COORDINATES }) {
                return EvidenceDecision.ABSTAIN to GroundingDecisionReason.INCOMPATIBLE_COORDINATES
            }
            val failed = runs.any { it.status in setOf(GroundingProviderStatus.UNAVAILABLE, GroundingProviderStatus.NOT_CONFIGURED, GroundingProviderStatus.EXECUTION_FAILED, GroundingProviderStatus.INVALID_OUTPUT, GroundingProviderStatus.TIMED_OUT) }
            return (if (failed) EvidenceDecision.ESCALATE else EvidenceDecision.ABSTAIN) to
                (if (failed) GroundingDecisionReason.PROVIDER_UNAVAILABLE else GroundingDecisionReason.NO_MATCH)
        }
        if (valid.size > 1 && valid[0].score - valid[1].score < request.thresholds.minimumWinnerMargin) {
            return EvidenceDecision.REFINE to GroundingDecisionReason.MULTIPLE_PLAUSIBLE_CANDIDATES
        }
        val best = valid.first()
        if (best.score < request.thresholds.minimumAcceptedScore) return EvidenceDecision.REFINE to GroundingDecisionReason.INSUFFICIENT_EVIDENCE
        val hasVisualPoint = best.observations.any { it.source == GroundingProviderId.VISION && it.sourceMetadata["geometryKind"] == "POINT" }
        if (hasVisualPoint && best.observations.size == 1) return EvidenceDecision.REFINE to GroundingDecisionReason.INSUFFICIENT_EVIDENCE
        return EvidenceDecision.ACCEPT to GroundingDecisionReason.UNIQUE_SUPPORTED_CANDIDATE
    }
}

/** Mode router and adaptive policy. Providers are independent and mode isolation is enforced here. */
class EvidenceAdaptiveGroundingRouter(
    private val uia: GroundingUiaProvider,
    private val ocr: GroundingOcrProvider,
    private val vision: GroundingVisionProvider,
    private val candidateGeneratorFactory: (GroundingThresholds) -> GroundingCandidateGenerator = ::GroundingCandidateGenerator,
    private val confidenceEvaluator: GroundingConfidenceEvaluator = GroundingConfidenceEvaluator(),
    private val sink: GroundingResultSink? = null
) {
    suspend fun run(request: GroundingRequest): GroundingResult {
        val started = System.nanoTime()
        val requested = when (request.mode) {
            GroundingMode.UIA_ONLY -> setOf(GroundingProviderId.UIA)
            GroundingMode.OCR_ONLY -> setOf(GroundingProviderId.OCR)
            GroundingMode.UIA_OCR -> setOf(GroundingProviderId.UIA, GroundingProviderId.OCR)
            GroundingMode.VISION_ONLY -> setOf(GroundingProviderId.VISION)
            GroundingMode.ADAPTIVE -> setOf(GroundingProviderId.UIA, GroundingProviderId.OCR, GroundingProviderId.VISION)
        }
        if (request.screenshot.width <= 0 || request.screenshot.height <= 0 || request.targetDescription.isNullOrBlank()) {
            val invalidTarget = request.targetDescription.isNullOrBlank()
            val result = GroundingResult(
                request.experimentId, request.caseId, request.executionId, Instant.now().toString(), request.mode,
                request.targetDescription, request.screenshot.width, request.screenshot.height, requested, emptySet(), emptySet(), emptySet(),
                requested.associateWith { if (invalidTarget) "invalid_target" else "invalid_screenshot" }, emptyList(), emptyList(), null,
                EvidenceDecision.ABSTAIN, if (invalidTarget) GroundingDecisionReason.INVALID_TARGET else GroundingDecisionReason.INVALID_SCREENSHOT,
                if (invalidTarget) "invalid_target" else "invalid_screenshot", (System.nanoTime() - started) / 1_000_000,
                request.thresholds, screenshotReference = request.screenshotReference, groundTruthReference = request.groundTruthReference
            )
            sink?.persist(result)
            return result
        }
        val runs = mutableListOf<GroundingProviderRun>()
        suspend fun collect(id: GroundingProviderId, evidenceAllowed: Boolean = true) {
            val before = System.nanoTime()
            val run = try {
                withTimeout(request.providerTimeoutMillis) {
                    when (id) {
                        GroundingProviderId.UIA -> uia.collect(request)
                        GroundingProviderId.OCR -> ocr.collect(request)
                        GroundingProviderId.VISION -> vision.collect(request, evidenceAllowed)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                GroundingProviderRun(id, GroundingProviderStatus.TIMED_OUT, (System.nanoTime() - before) / 1_000_000, diagnostic = "provider_timeout")
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { GroundingProviderRun(id, GroundingProviderStatus.EXECUTION_FAILED,
                (System.nanoTime() - before) / 1_000_000, diagnostic = error.javaClass.simpleName) }
            runs += run.copy(provider = id, latencyMillis = run.latencyMillis.coerceAtLeast((System.nanoTime() - before) / 1_000_000))
        }
        val skipped = mutableMapOf<GroundingProviderId, String>()
        when (request.mode) {
            GroundingMode.UIA_ONLY -> collect(GroundingProviderId.UIA)
            GroundingMode.OCR_ONLY -> collect(GroundingProviderId.OCR)
            GroundingMode.UIA_OCR -> { collect(GroundingProviderId.UIA); collect(GroundingProviderId.OCR) }
            GroundingMode.VISION_ONLY -> collect(GroundingProviderId.VISION, evidenceAllowed = false)
            GroundingMode.ADAPTIVE -> {
                collect(GroundingProviderId.UIA); collect(GroundingProviderId.OCR)
                val current = candidateGeneratorFactory(request.thresholds).generate(runs.flatMap { it.observations })
                val (decision, _) = confidenceEvaluator.decide(current, request, runs)
                if (request.adaptiveVisionPolicy == AdaptiveVisionPolicy.ALWAYS || decision != EvidenceDecision.ACCEPT) collect(GroundingProviderId.VISION)
                else skipped[GroundingProviderId.VISION] = "skipped_unique_supported_candidate"
            }
        }
        val candidates = candidateGeneratorFactory(request.thresholds).generate(runs.flatMap { it.observations })
        val (decision, reason) = confidenceEvaluator.decide(candidates, request, runs)
        val selected = candidates.filter { it.box != null && it.targetSimilarity >= request.thresholds.minimumTextSimilarity }
            .sortedByDescending { it.score }.firstOrNull()?.takeIf { decision == EvidenceDecision.ACCEPT }
        val statuses = GroundingProviderId.values().associateWith { id -> runs.firstOrNull { it.provider == id }?.status ?: GroundingProviderStatus.NOT_REQUESTED }
        val result = GroundingResult(
            request.experimentId, request.caseId, request.executionId, Instant.now().toString(), request.mode,
            request.targetDescription, request.screenshot.width, request.screenshot.height, requested,
            runs.map { it.provider }.toSet(), runs.filter { it.status in setOf(GroundingProviderStatus.SUCCESS, GroundingProviderStatus.SUCCESS_NO_MATCH, GroundingProviderStatus.EMPTY_RESULT) }.map { it.provider }.toSet(),
            runs.filter { it.status in setOf(GroundingProviderStatus.UNAVAILABLE, GroundingProviderStatus.NOT_CONFIGURED, GroundingProviderStatus.EXECUTION_FAILED, GroundingProviderStatus.INVALID_OUTPUT, GroundingProviderStatus.TIMED_OUT, GroundingProviderStatus.INCOMPATIBLE_COORDINATES) }.map { it.provider }.toSet(),
            skipped + statuses.filterValues { it != GroundingProviderStatus.SUCCESS }.mapNotNull { (id, status) ->
                if (id !in runs.map { it.provider } && id in requested && id !in skipped) id to "${status.name.lowercase()}" else null
            }, runs.toList(), candidates, selected, decision, reason,
            reason.name.lowercase(), (System.nanoTime() - started) / 1_000_000, request.thresholds,
            runs.firstOrNull { it.provider == GroundingProviderId.VISION }?.metadata?.get("model"), request.screenshotReference, request.groundTruthReference
        )
        sink?.persist(result)
        return result
    }
}

/** Adapts the current one-candidate UIA contract; unavailable screen transforms stay explicit. */
class ExistingUiaGroundingProvider(private val engine: PerceptionEngine, private val normalizer: GroundingNormalizer = GroundingNormalizer()) : GroundingUiaProvider {
    override suspend fun collect(request: GroundingRequest): GroundingProviderRun {
        val start = System.nanoTime()
        val desktop = request.selectedRegionOnDesktop ?: return GroundingProviderRun(GroundingProviderId.UIA, GroundingProviderStatus.UNAVAILABLE, 0, diagnostic = "selected desktop bounds unavailable")
        val perceptionRequest = PerceptionRequest(request.screenshot, desktop, request.applicationContext, request.targetDescription)
        val evidence = if (engine is WindowsUiAutomationPerceptionEngine) engine.perceiveCandidates(perceptionRequest)
            else listOf(engine.perceive(perceptionRequest))
        val first = evidence.first()
        val status = first.metadata["status"]
        val items = evidence.flatMap { normalizer.uia(it, request) }
        val resultStatus = when {
            status in setOf("unsupported_platform", "missing_window_handle", "coordinate_mapping_unsupported") -> GroundingProviderStatus.UNAVAILABLE
            status in setOf("uia_query_failed", "provider_failure") -> GroundingProviderStatus.EXECUTION_FAILED
            items.any { it.originalBox != null } && items.all { it.box == null } -> GroundingProviderStatus.INCOMPATIBLE_COORDINATES
            items.isEmpty() && request.screenToScreenshot == null -> GroundingProviderStatus.INCOMPATIBLE_COORDINATES
            items.isEmpty() -> GroundingProviderStatus.EMPTY_RESULT
            else -> GroundingProviderStatus.SUCCESS
        }
        return GroundingProviderRun(GroundingProviderId.UIA, resultStatus, (System.nanoTime() - start) / 1_000_000,
            items, status, first.metadata + mapOf(
                "source" to first.source.name,
                "candidateCount" to (first.metadata["candidateCount"] ?: evidence.size.toString()),
                "screenshotDimensions" to "${request.screenshot.width},${request.screenshot.height}",
                "screenToScreenshot" to request.screenToScreenshot?.let {
                    "${it.screenOriginX},${it.screenOriginY},${it.scaleX},${it.scaleY}"
                }.orEmpty()
            ))
    }
}

class ExistingOcrGroundingProvider(private val service: OCRService, private val normalizer: GroundingNormalizer = GroundingNormalizer()) : GroundingOcrProvider {
    override suspend fun collect(request: GroundingRequest): GroundingProviderRun {
        val start = System.nanoTime()
        val result = try { service.recognize(request.screenshot) }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { return GroundingProviderRun(GroundingProviderId.OCR, GroundingProviderStatus.EXECUTION_FAILED, (System.nanoTime() - start) / 1_000_000, diagnostic = error.javaClass.simpleName) }
        val observations = normalizer.ocr(result, request)
        val status = when {
            result.metadata["availability"] == "unavailable" -> GroundingProviderStatus.UNAVAILABLE
            result.metadata["status"] == "recognition_failed" -> GroundingProviderStatus.EXECUTION_FAILED
            observations.isEmpty() -> GroundingProviderStatus.EMPTY_RESULT
            observations.any { it.textSimilarity >= request.thresholds.minimumTextSimilarity } -> GroundingProviderStatus.SUCCESS
            else -> GroundingProviderStatus.SUCCESS_NO_MATCH
        }
        return GroundingProviderRun(GroundingProviderId.OCR, status, (System.nanoTime() - start) / 1_000_000,
            observations, metadata = result.metadata + mapOf("ocrStatus" to status.name, "engine" to result.engineName))
    }
}

class ExistingVisionGroundingProvider(private val provider: VisualGroundingProvider, private val normalizer: GroundingNormalizer = GroundingNormalizer()) : GroundingVisionProvider {
    override suspend fun collect(request: GroundingRequest, allowEvidence: Boolean): GroundingProviderRun {
        val start = System.nanoTime()
        val preflight = provider.preflight()
        if (preflight.availability != GroundingProviderAvailability.AVAILABLE) {
            val status = when (preflight.availability) {
                GroundingProviderAvailability.NOT_CONFIGURED -> GroundingProviderStatus.NOT_CONFIGURED
                GroundingProviderAvailability.UNAVAILABLE, GroundingProviderAvailability.HOST_BLOCKED -> GroundingProviderStatus.UNAVAILABLE
                GroundingProviderAvailability.FAILURE, GroundingProviderAvailability.CANCELLED -> GroundingProviderStatus.EXECUTION_FAILED
                GroundingProviderAvailability.AVAILABLE -> error("unreachable")
            }
            return GroundingProviderRun(GroundingProviderId.VISION, status, 0, diagnostic = preflight.diagnostic, metadata = preflight.metadata)
        }
        val target = request.targetDescription?.takeIf(String::isNotBlank)
            ?: return GroundingProviderRun(GroundingProviderId.VISION, GroundingProviderStatus.INVALID_OUTPUT, 0, diagnostic = "target description missing")
        // Vision-only never receives UIA/OCR evidence, even if the provider supports those optional fields.
        val evidence = provider.ground(VisualGroundingRequest(request.screenshot, request.applicationContext, target,
            if (allowEvidence) null else null, if (allowEvidence) null else null))
        val observations = normalizer.vision(evidence, request)
        val status = when {
            evidence.availability != GroundingProviderAvailability.AVAILABLE -> GroundingProviderStatus.EXECUTION_FAILED
            observations.isEmpty() -> GroundingProviderStatus.INVALID_OUTPUT
            else -> GroundingProviderStatus.SUCCESS
        }
        return GroundingProviderRun(GroundingProviderId.VISION, status, evidence.latencyMillis ?: (System.nanoTime() - start) / 1_000_000,
            observations, evidence.metadata["diagnostic"] ?: evidence.metadata["status"], evidence.metadata)
    }
}
