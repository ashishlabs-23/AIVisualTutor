package context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EvidenceEvaluationTest {
    @Test fun iouIsComputedOnlyForSameExplicitCoordinateSpace() {
        val first = GroundingGeometricEvidence(
            bounds = GroundingBounds(0.0, 0.0, 10.0, 10.0),
            coordinateSpace = VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS
        )
        val second = GroundingGeometricEvidence(
            bounds = GroundingBounds(5.0, 0.0, 15.0, 10.0),
            coordinateSpace = VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS
        )
        val normalized = second.copy(coordinateSpace = VisualGroundingCoordinateSpace.NORMALIZED_CROP)
        val desktop = EvidenceGeometry(
            bounds = GroundingBounds(5.0, 0.0, 15.0, 10.0),
            coordinateSpace = EvidenceCoordinateSpace.PHYSICAL_DESKTOP_SCREEN
        )

        assertEquals(1.0 / 3.0, geometryIou(first, second))
        assertNull(geometryIou(first, normalized))
        assertNull(geometryIou(first, desktop))
        assertNull(geometryIou(first, first.copy(point = GroundingPoint(2.0, 2.0), bounds = null)))
    }

    @Test fun evaluationRecordKeepsAvailabilitySemanticsGeometryAndHumanTruthSeparate() {
        val evaluationCase = EvidenceEvaluationCase(
            caseId = "ocr-only-illustration",
            application = "SyntheticFixtureApp",
            targetDescriptionVariants = listOf(TargetDescriptionVariant("Save"), TargetDescriptionVariant("Save button")),
            targetType = "text",
            expectedRegion = GroundingBounds(1.0, 2.0, 3.0, 4.0),
            expectedCoordinateSpace = VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS
        )
        val record = EvidenceEvaluationRecord(
            evaluationCase, null, EvidenceAvailability.UNAVAILABLE, null,
            OcrResult("Save", .9f, engineName = "test"), EvidenceAvailability.AVAILABLE,
            null, EvidenceAgreement.UNVERIFIED, EvidenceAgreement.NOT_COMPUTABLE,
            GeometryQuality.UNAVAILABLE, GeometryQuality.UNVERIFIED,
            HumanGroundTruth.UNAVAILABLE, EvidenceAgreement.UNVERIFIED
        )

        assertEquals(EvidenceAvailability.UNAVAILABLE, record.uiAutomationAvailability)
        assertEquals(EvidenceAvailability.AVAILABLE, record.ocrAvailability)
        assertEquals(EvidenceAgreement.NOT_COMPUTABLE, record.geometryAgreement)
        assertEquals(HumanGroundTruth.UNAVAILABLE, record.humanGroundTruth)
        assertEquals(2, record.evaluationCase.targetDescriptionVariants.size)
    }
}
