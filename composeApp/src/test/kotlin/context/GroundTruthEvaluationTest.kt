package context

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GroundTruthEvaluationTest {

    private val datasetCsv = listOf(
        Path.of("docs", "audit_evidence", "phase5", "ground-truth-v2", "cases.csv"),
        Path.of("..", "docs", "audit_evidence", "phase5", "ground-truth-v2", "cases.csv")
    ).first { Files.isRegularFile(it) }

    @Test
    fun groundTruthV2DatasetLoadsAndValidatesCleanly() {
        assertTrue(Files.isRegularFile(datasetCsv), "Dataset CSV must exist at $datasetCsv")
        val cases = GroundTruthCsv.read(datasetCsv)
        assertEquals(10, cases.size, "Dataset must contain 10 annotated cases")

        // Assert positive and absent distribution
        val positive = cases.filterNot { it.annotation.expectedAbsent }
        val absent = cases.filter { it.annotation.expectedAbsent }
        assertEquals(6, positive.size, "Should have 6 positive detection cases")
        assertEquals(4, absent.size, "Should have 4 negative/absent cases")

        // Check required applications
        val apps = cases.mapNotNull { it.applicationContext?.applicationName }.toSet()
        assertTrue(apps.containsAll(setOf("Blender", "Calculator", "Notepad", "Paint", "Excel")))
    }

    @Test
    fun allReferencedScreenshotsExistAndHaveValidDimensions() {
        val cases = GroundTruthCsv.read(datasetCsv)
        for (case in cases) {
            assertTrue(Files.isRegularFile(case.screenshotPath), "Screenshot must exist: ${case.screenshotPath}")
            val img = ImageIO.read(case.screenshotPath.toFile())
            assertNotNull(img, "Screenshot must be decodable image: ${case.screenshotPath}")
            assertTrue(img.width > 0 && img.height > 0)

            if (!case.annotation.expectedAbsent) {
                val box = case.annotation.expectedBox
                assertNotNull(box, "Positive case must have expectedBox: ${case.annotation.caseId}")
                assertTrue(
                    box.x >= 0 && box.y >= 0 && box.width > 0 && box.height > 0 &&
                        box.x + box.width <= img.width && box.y + box.height <= img.height,
                    "Expected box for ${case.annotation.caseId} must fit within image ${img.width}x${img.height}"
                )
            }
        }
    }

    @Test
    fun evaluatesMetricsAccuratelyOnControlledResults() {
        val cases = GroundTruthCsv.read(datasetCsv)
        val annotations = cases.associate { it.annotation.caseId to it.annotation }

        // Construct mock grounding results for testing evaluation metrics logic
        val results = cases.map { case ->
            val isAbsent = case.annotation.expectedAbsent
            val box = if (isAbsent) null else case.annotation.expectedBox
            GroundingResult(
                experimentId = "TEST_EVAL",
                caseId = case.annotation.caseId,
                runId = "run-${case.annotation.caseId}",
                timestamp = "2026-10-12T10:00:00Z",
                mode = GroundingMode.ADAPTIVE,
                targetDescription = case.annotation.targetDescription,
                screenshotWidth = 524,
                screenshotHeight = 324,
                requestedProviders = setOf(GroundingProviderId.UIA, GroundingProviderId.OCR),
                executedProviders = setOf(GroundingProviderId.OCR),
                successfulProviders = setOf(GroundingProviderId.OCR),
                failedProviders = emptySet(),
                skippedReasons = emptyMap(),
                providerRuns = listOf(
                    GroundingProviderRun(
                        provider = GroundingProviderId.OCR,
                        status = GroundingProviderStatus.SUCCESS,
                        latencyMillis = 15
                    )
                ),
                candidates = listOfNotNull(
                    box?.let {
                        GroundingCandidate(
                            candidateId = "cand-1",
                            observations = listOf(
                                GroundingObservation(
                                    source = GroundingProviderId.OCR,
                                    candidateId = "ocr-1",
                                    observedText = case.annotation.expectedTargetIdentity ?: case.annotation.targetDescription,
                                    targetDescription = case.annotation.targetDescription,
                                    box = it,
                                    originalBox = it,
                                    originalCoordinateSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
                                    coordinateSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
                                    providerConfidence = 0.95,
                                    textSimilarity = 1.0,
                                    executionId = "exec-1"
                                )
                            ),
                            box = it,
                            coordinateSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
                            targetSimilarity = 1.0,
                            crossProviderAgreement = null,
                            score = 0.85
                        )
                    }
                ),
                selectedCandidate = if (isAbsent) null else box?.let {
                    GroundingCandidate(
                        candidateId = "cand-1",
                        observations = listOf(
                            GroundingObservation(
                                source = GroundingProviderId.OCR,
                                candidateId = "ocr-1",
                                observedText = case.annotation.expectedTargetIdentity ?: case.annotation.targetDescription,
                                targetDescription = case.annotation.targetDescription,
                                box = it,
                                originalBox = it,
                                originalCoordinateSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
                                coordinateSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
                                providerConfidence = 0.95,
                                textSimilarity = 1.0,
                                executionId = "exec-1"
                            )
                        ),
                        box = box,
                        coordinateSpace = GroundingBoxSpace.SCREENSHOT_PIXELS,
                        targetSimilarity = 1.0,
                        crossProviderAgreement = null,
                        score = 0.85
                    )
                },
                decision = if (isAbsent) EvidenceDecision.ABSTAIN else EvidenceDecision.ACCEPT,
                reason = if (isAbsent) GroundingDecisionReason.NO_MATCH else GroundingDecisionReason.UNIQUE_SUPPORTED_CANDIDATE,
                decisionReason = if (isAbsent) "no_match" else "unique_supported_candidate",
                totalLatencyMillis = 20,
                configuration = GroundingThresholds()
            )
        }

        val metrics = GroundingResearchMetrics.evaluate(results, annotations)
        assertEquals(10, metrics.cases)
        assertEquals(10, metrics.evaluatedCases)
        assertEquals(6, metrics.accepted)
        assertEquals(1.0, metrics.selectiveAccuracy) // All accepted were true positives
        assertEquals(0.0, metrics.falseAcceptanceRate) // 0 false acceptances
        assertEquals(0.4, metrics.abstentionRate) // 4 absent cases abstained (4/10)
        assertEquals(1.0, metrics.targetIdentificationAccuracy) // 10/10 correct
    }
}
