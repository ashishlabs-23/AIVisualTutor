package context

/** Phase 4 research record; it is not consumed by the production processing path. */
data class EvidenceEvaluationCase(
    val caseId: String,
    val application: String,
    val targetDescriptionVariants: List<TargetDescriptionVariant>,
    val targetType: String,
    val expectedRegion: GroundingBounds?,
    val expectedCoordinateSpace: VisualGroundingCoordinateSpace?,
    val sourceAnnotations: List<String> = emptyList()
)

data class TargetDescriptionVariant(
    val description: String,
    val expectedSameTarget: Boolean = true
)

enum class EvidenceAvailability { AVAILABLE, UNAVAILABLE, UNVERIFIED }
enum class EvidenceAgreement { AGREE, DISAGREE, NOT_COMPUTABLE, UNVERIFIED }
enum class GeometryQuality { VALID, INVALID, UNAVAILABLE, UNVERIFIED }
enum class HumanGroundTruth { AVAILABLE, UNAVAILABLE, UNVERIFIED }
enum class EvidenceCoordinateSpace { CROP_IMAGE_PIXELS, NORMALIZED_CROP, PHYSICAL_DESKTOP_SCREEN }

/** Geometry tagged for evidence comparison; unlike model output, it can represent UIA screen space. */
data class EvidenceGeometry(
    val bounds: GroundingBounds?,
    val point: GroundingPoint? = null,
    val coordinateSpace: EvidenceCoordinateSpace,
    val confidence: Float? = null
) {
    init { require(bounds != null || point != null) { "Evidence geometry must contain bounds or a point." } }
}

/** Keeps source observations separate rather than deriving one cross-source confidence. */
data class EvidenceEvaluationRecord(
    val evaluationCase: EvidenceEvaluationCase,
    val uiAutomationEvidence: PerceptionResult?,
    val uiAutomationAvailability: EvidenceAvailability,
    val uiAutomationGeometry: EvidenceGeometry?,
    val ocrEvidence: OcrResult?,
    val ocrAvailability: EvidenceAvailability,
    val ocrGeometry: EvidenceGeometry?,
    val semanticAgreement: EvidenceAgreement,
    val geometryAgreement: EvidenceAgreement,
    val uiAutomationGeometryQuality: GeometryQuality,
    val ocrGeometryQuality: GeometryQuality,
    val humanGroundTruth: HumanGroundTruth,
    val insufficientEvidence: EvidenceAgreement,
    val notes: List<String> = emptyList()
)

/** IoU is only defined for rectangular regions in the same explicitly declared coordinate space. */
fun geometryIou(
    first: EvidenceGeometry?,
    second: EvidenceGeometry?
): Double? {
    if (first == null || second == null || first.coordinateSpace != second.coordinateSpace) return null
    val a = first.bounds ?: return null
    val b = second.bounds ?: return null
    return boundsIou(a, b)
}

/** Provider-model geometry cannot be compared with registry geometry without an explicit mapping. */
fun geometryIou(first: GroundingGeometricEvidence?, second: EvidenceGeometry?): Double? = null

/** Provider-model geometry cannot be compared with registry geometry without an explicit mapping. */
fun geometryIou(first: EvidenceGeometry?, second: GroundingGeometricEvidence?): Double? = null

/** Model geometry uses only crop-pixel or normalized-crop coordinates and must match exactly. */
fun geometryIou(
    first: GroundingGeometricEvidence?,
    second: GroundingGeometricEvidence?
): Double? {
    if (first == null || second == null || first.coordinateSpace != second.coordinateSpace) return null
    val a = first.bounds ?: return null
    val b = second.bounds ?: return null
    return boundsIou(a, b)
}

private fun boundsIou(a: GroundingBounds, b: GroundingBounds): Double? {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    val intersection = maxOf(0.0, right - left) * maxOf(0.0, bottom - top)
    val areaA = maxOf(0.0, a.right - a.left) * maxOf(0.0, a.bottom - a.top)
    val areaB = maxOf(0.0, b.right - b.left) * maxOf(0.0, b.bottom - b.top)
    val union = areaA + areaB - intersection
    return union.takeIf { it > 0.0 }?.let { intersection / it }
}
