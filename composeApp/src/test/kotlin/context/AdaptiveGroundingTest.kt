package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

class AdaptiveGroundingTest {
    private val image = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)
    private val target = "Click Save button"
    private val box = GroundingBox(10.0, 10.0, 20.0, 12.0)

    @Test fun normalizedTextAndGeometryAreExplicitAndBounded() {
        assertEquals(1.0, textSimilarity("CLICK Save! button", "save"))
        val transform = ScreenToScreenshotTransform(100.0, 50.0, 2.0, 1.5)
        assertEquals(GroundingBox(20.0, 15.0, 40.0, 18.0), transform.box(Rectangle(110, 60, 20, 12)))
        assertFalse(GroundingBox(95.0, 0.0, 10.0, 1.0).validWithin(100.0, 80.0))
        assertNull(iou(box, GroundingBox(12.0, 12.0, 0.0, 0.0)))
    }

    @Test fun uiaAndOcrNormalizeWithoutInventingConfidenceOrSpace() {
        val request = request(GroundingMode.UIA_OCR, transform = ScreenToScreenshotTransform(100.0, 50.0, 1.0, 1.0))
        val normalizer = GroundingNormalizer()
        val uia = normalizer.uia(PerceptionResult("Demo", "Save", null, UiType.BUTTON,
            Rectangle(110, 60, 20, 12), null, PerceptionSource.UI_AUTOMATION), request).single()
        assertEquals(GroundingBoxSpace.SCREENSHOT_PIXELS, uia.coordinateSpace)
        assertNull(uia.providerConfidence)
        val ocr = normalizer.ocr(OcrResult("Save", null,
            listOf(OcrWord("Save", null, Rectangle(10, 10, 20, 12), lineIndex = 1)), "fixture"), request).single()
        assertEquals(box, ocr.box)
        assertNull(ocr.providerConfidence)
    }

    @Test fun uiaWithoutDeclaredCoordinateTransformIsNotGroundable() = runBlocking {
        val provider = ExistingUiaGroundingProvider(PerceptionEngine {
            PerceptionResult("Demo", "Save", null, UiType.BUTTON, Rectangle(10, 10, 20, 12), 1f,
                PerceptionSource.UI_AUTOMATION)
        })
        val run = provider.collect(request(GroundingMode.UIA_ONLY))
        assertEquals(GroundingProviderStatus.INCOMPATIBLE_COORDINATES, run.status)
        assertTrue(run.observations.single().box == null)
        assertEquals(GroundingBoxSpace.SCREEN_COORDINATES, run.observations.single().originalCoordinateSpace)
    }

    @Test fun missingUiaWindowContextIsUnavailableNotEvidenceOfNoTarget() = runBlocking {
        val engine = WindowsUiAutomationPerceptionEngine(
            UiAutomationClient { error("UIA must not query without a window handle") },
            { true },
            { true }
        )
        val request = request(GroundingMode.UIA_ONLY, transform = ScreenToScreenshotTransform(0.0, 0.0, 1.0, 1.0))
            .copy(applicationContext = null)

        val run = ExistingUiaGroundingProvider(engine).collect(request)

        assertEquals(GroundingProviderStatus.UNAVAILABLE, run.status)
        assertEquals("missing_window_handle", run.diagnostic)
        assertTrue(run.observations.isEmpty())
    }

    @Test fun requestedModesNeverCallDisabledProviders() = runBlocking {
        for (mode in GroundingMode.values()) {
            val calls = mutableListOf<GroundingProviderId>()
            val router = router(calls, agreementEvidence())
            val result = router.run(request(mode))
            when (mode) {
                GroundingMode.UIA_ONLY -> assertEquals(setOf(GroundingProviderId.UIA), calls.toSet())
                GroundingMode.OCR_ONLY -> assertEquals(setOf(GroundingProviderId.OCR), calls.toSet())
                GroundingMode.UIA_OCR -> assertEquals(setOf(GroundingProviderId.UIA, GroundingProviderId.OCR), calls.toSet())
                GroundingMode.VISION_ONLY -> assertEquals(setOf(GroundingProviderId.VISION), calls.toSet())
                GroundingMode.ADAPTIVE -> assertEquals(setOf(GroundingProviderId.UIA, GroundingProviderId.OCR), calls.toSet())
            }
            assertEquals(mode, result.mode)
            assertEquals(calls.toSet(), result.executedProviders)
        }
    }

    @Test fun visionOnlyIsolatedAndAdaptiveRecordsSkippedVision() = runBlocking {
        var evidenceAllowed: Boolean? = null
        val vision = GroundingVisionProvider { _, allowed ->
            evidenceAllowed = allowed
            GroundingProviderRun(GroundingProviderId.VISION, GroundingProviderStatus.SUCCESS, 3)
        }
        val isolated = EvidenceAdaptiveGroundingRouter(
            GroundingUiaProvider { error("UIA must not run") }, GroundingOcrProvider { error("OCR must not run") }, vision
        ).run(request(GroundingMode.VISION_ONLY))
        assertEquals(false, evidenceAllowed)
        assertEquals(setOf(GroundingProviderId.VISION), isolated.executedProviders)

        val calls = mutableListOf<GroundingProviderId>()
        val adaptive = router(calls, agreementEvidence()).run(request(GroundingMode.ADAPTIVE))
        assertEquals("skipped_unique_supported_candidate", adaptive.skippedReasons[GroundingProviderId.VISION])
        assertFalse(GroundingProviderId.VISION in adaptive.executedProviders)
    }

    @Test fun providerFailureDoesNotDiscardIndependentOcrCandidate() = runBlocking {
        val router = EvidenceAdaptiveGroundingRouter(
            GroundingUiaProvider { throw IllegalStateException("uia failure") },
            GroundingOcrProvider {
                GroundingProviderRun(GroundingProviderId.OCR, GroundingProviderStatus.SUCCESS, 2,
                    listOf(observation(GroundingProviderId.OCR, "ocr-save", "Save", box)))
            },
            GroundingVisionProvider { _, _ -> GroundingProviderRun(GroundingProviderId.VISION, GroundingProviderStatus.NOT_CONFIGURED, 0) }
        )
        val result = router.run(request(GroundingMode.ADAPTIVE))
        assertTrue(GroundingProviderId.UIA in result.failedProviders)
        assertTrue(result.providerRuns.any { it.provider == GroundingProviderId.OCR && it.status == GroundingProviderStatus.SUCCESS })
        assertEquals(EvidenceDecision.ACCEPT, result.decision)
        assertTrue(result.acceptedBox != null)
        Unit
    }

    @Test fun similarDistinctControlsRemainAmbiguous() {
        val observations = listOf(
            observation(GroundingProviderId.OCR, "a", "Save", GroundingBox(5.0, 5.0, 16.0, 8.0)),
            observation(GroundingProviderId.OCR, "b", "Save", GroundingBox(70.0, 5.0, 16.0, 8.0))
        )
        val candidates = GroundingCandidateGenerator().generate(observations)
        val (decision, reason) = GroundingConfidenceEvaluator().decide(candidates, request(GroundingMode.OCR_ONLY), emptyList())
        assertEquals(EvidenceDecision.REFINE, decision)
        assertEquals(GroundingDecisionReason.MULTIPLE_PLAUSIBLE_CANDIDATES, reason)
    }

    @Test fun duplicateProviderObservationsAreNotDoubleCounted() {
        val evidence = observation(GroundingProviderId.OCR, "ocr-save", "Save", box)

        val candidates = GroundingCandidateGenerator().generate(listOf(evidence, evidence.copy()))

        assertEquals(1, candidates.size)
        assertEquals(1, candidates.single().observations.size)
        assertNull(candidates.single().crossProviderAgreement)
    }

    @Test fun conflictingCoordinatesFromDifferentProvidersRemainSeparateAndRefine() {
        val candidates = GroundingCandidateGenerator().generate(listOf(
            observation(GroundingProviderId.UIA, "uia-save", "Save", box),
            observation(GroundingProviderId.OCR, "ocr-save", "Save", GroundingBox(70.0, 55.0, 20.0, 12.0))
        ))

        assertEquals(2, candidates.size)
        assertEquals(setOf(GroundingProviderId.UIA, GroundingProviderId.OCR),
            candidates.flatMap { it.observations }.map { it.source }.toSet())
        assertEquals(
            EvidenceDecision.REFINE to GroundingDecisionReason.MULTIPLE_PLAUSIBLE_CANDIDATES,
            GroundingConfidenceEvaluator().decide(candidates, request(GroundingMode.UIA_OCR), emptyList())
        )
    }

    @Test fun outOfBoundsCandidateCannotBeAccepted() {
        val invalid = observation(
            GroundingProviderId.OCR,
            "ocr-outside",
            "Save",
            GroundingBox(95.0, 70.0, 20.0, 12.0)
        )
        val candidates = GroundingCandidateGenerator().generate(listOf(invalid))

        assertEquals(
            EvidenceDecision.ABSTAIN to GroundingDecisionReason.NO_MATCH,
            GroundingConfidenceEvaluator().decide(candidates, request(GroundingMode.OCR_ONLY), emptyList())
        )
    }

    @Test fun containedOcrTextAssociatesWithUiaControlAndPreservesControlGeometry() {
        val control = GroundingBox(10.0, 10.0, 80.0, 60.0)
        val label = GroundingBox(35.0, 30.0, 24.0, 10.0)
        val observations = listOf(
            observation(GroundingProviderId.UIA, "uia-save", "Save", control),
            observation(GroundingProviderId.OCR, "ocr-save", "Save", label)
        )

        val candidates = GroundingCandidateGenerator().generate(observations)
        assertEquals(1, candidates.size)
        assertEquals(setOf(GroundingProviderId.UIA, GroundingProviderId.OCR), candidates.single().observations.map { it.source }.toSet())
        assertEquals(control, candidates.single().box)
        assertEquals(1.0, candidates.single().crossProviderAgreement)
        assertEquals(EvidenceDecision.ACCEPT, GroundingConfidenceEvaluator().decide(
            candidates, request(GroundingMode.UIA_OCR), emptyList()
        ).first)
    }

    @Test fun uiaAndVisionPointInsideControlFuseWithoutInventingPointConfidence() {
        val control = observation(GroundingProviderId.UIA, "uia-save", "Save", box)
        val point = GroundingObservation(
            GroundingProviderId.VISION, "vision-point", null, target, GroundingBox(15.0, 15.0, 0.0, 0.0),
            null, GroundingBoxSpace.NORMALIZED_SCREENSHOT, GroundingBoxSpace.SCREENSHOT_PIXELS,
            null, 0.0, mapOf("geometryKind" to "POINT"), "vision-run"
        )

        val candidates = GroundingCandidateGenerator().generate(listOf(control, point))
        assertEquals(1, candidates.size)
        assertEquals(box, candidates.single().box)
        assertEquals(setOf(GroundingProviderId.UIA, GroundingProviderId.VISION), candidates.single().observations.map { it.source }.toSet())
        assertEquals(EvidenceDecision.ACCEPT, GroundingConfidenceEvaluator().decide(
            candidates, request(GroundingMode.UIA_OCR), emptyList()
        ).first)
    }

    @Test fun aVisionPointOutsideTheSupportedControlIsAConflict() {
        val control = observation(GroundingProviderId.OCR, "ocr-save", "Save", box)
        val point = GroundingObservation(
            GroundingProviderId.VISION, "vision-point", null, target, GroundingBox(80.0, 60.0, 0.0, 0.0),
            null, GroundingBoxSpace.NORMALIZED_SCREENSHOT, GroundingBoxSpace.SCREENSHOT_PIXELS,
            null, 0.0, mapOf("geometryKind" to "POINT"), "vision-run"
        )
        val candidates = GroundingCandidateGenerator().generate(listOf(control, point))

        assertEquals(2, candidates.size)
        assertEquals(
            EvidenceDecision.ESCALATE to GroundingDecisionReason.CONFLICTING_EVIDENCE,
            GroundingConfidenceEvaluator().decide(candidates, request(GroundingMode.ADAPTIVE), emptyList())
        )
    }

    @Test fun disagreementAcrossAllThreeSourcesCannotBeAccepted() {
        val point = GroundingObservation(
            GroundingProviderId.VISION, "vision-point", null, target, GroundingBox(80.0, 60.0, 0.0, 0.0),
            null, GroundingBoxSpace.NORMALIZED_SCREENSHOT, GroundingBoxSpace.SCREENSHOT_PIXELS,
            null, 0.0, mapOf("geometryKind" to "POINT"), "vision-run"
        )
        val observations = listOf(
            observation(GroundingProviderId.UIA, "uia-save", "Save", box),
            observation(GroundingProviderId.OCR, "ocr-cancel", "Cancel", box),
            point
        )
        val candidates = GroundingCandidateGenerator().generate(observations)

        assertEquals(3, candidates.size)
        val (decision, reason) = GroundingConfidenceEvaluator().decide(
            candidates, request(GroundingMode.ADAPTIVE), emptyList()
        )
        assertEquals(EvidenceDecision.ESCALATE, decision)
        assertEquals(GroundingDecisionReason.CONFLICTING_EVIDENCE, reason)
    }

    @Test fun timeoutAndUnavailableVisionRemainExplicit() = runBlocking {
        val timed = EvidenceAdaptiveGroundingRouter(
            GroundingUiaProvider { GroundingProviderRun(GroundingProviderId.UIA, GroundingProviderStatus.EMPTY_RESULT, 0) },
            GroundingOcrProvider { delay(200); GroundingProviderRun(GroundingProviderId.OCR, GroundingProviderStatus.SUCCESS, 200) },
            GroundingVisionProvider { _, _ -> GroundingProviderRun(GroundingProviderId.VISION, GroundingProviderStatus.NOT_CONFIGURED, 0) }
        ).run(request(GroundingMode.OCR_ONLY).copy(providerTimeoutMillis = 5))
        assertEquals(GroundingProviderStatus.TIMED_OUT, timed.providerRuns.single().status)
        assertEquals(EvidenceDecision.ESCALATE, timed.decision)

        val calls = mutableListOf<GroundingProviderId>()
        val unavailable = EvidenceAdaptiveGroundingRouter(
            GroundingUiaProvider { calls += GroundingProviderId.UIA; error("must not run") },
            GroundingOcrProvider { calls += GroundingProviderId.OCR; error("must not run") },
            ExistingVisionGroundingProvider(NotConfiguredVisualGroundingProvider())
        ).run(request(GroundingMode.VISION_ONLY))
        assertTrue(calls.isEmpty())
        assertEquals(GroundingProviderStatus.NOT_CONFIGURED, unavailable.providerRuns.single().status)
        assertEquals(GroundingDecisionReason.PROVIDER_UNAVAILABLE, unavailable.reason)
    }

    @Test fun pointGroundTruthUsesPointDistanceWithoutInventingBox() {
        val pointObs = GroundingObservation(GroundingProviderId.VISION, "point", "Save", target,
            GroundingBox(11.0, 12.0, 0.0, 0.0), null, GroundingBoxSpace.NORMALIZED_SCREENSHOT,
            GroundingBoxSpace.SCREENSHOT_PIXELS, null, 1.0,
            mapOf("geometryKind" to "POINT"), "point-run")
        val candidate = GroundingCandidate("point-candidate", listOf(pointObs), pointObs.box,
            GroundingBoxSpace.SCREENSHOT_PIXELS, 1.0, null, 1.0)
        val run = GroundingResult("exp", "point-case", "point-run", "now", GroundingMode.VISION_ONLY, target,
            100, 80, setOf(GroundingProviderId.VISION), setOf(GroundingProviderId.VISION), setOf(GroundingProviderId.VISION),
            emptySet(), emptyMap(),
            listOf(GroundingProviderRun(GroundingProviderId.VISION, GroundingProviderStatus.SUCCESS, 1, listOf(pointObs))),
            listOf(candidate), candidate, EvidenceDecision.ACCEPT,
            GroundingDecisionReason.UNIQUE_SUPPORTED_CANDIDATE, "ok", 1, GroundingThresholds())
        assertNull(run.acceptedBox)
        assertEquals(11.0 to 12.0, run.acceptedPoint)
        val metrics = GroundingResearchMetrics.evaluate(listOf(run), mapOf("point-case" to GroundTruthAnnotation(
            "point-case", "fixture", target, expectedPoint = 12.0 to 12.0
        )), pointTolerancePixels = 2.0)
        assertEquals(1.0, metrics.selectiveAccuracy)
        assertNull(metrics.meanIou)
    }

    @Test fun incompatibleGroundTruthCoordinatesAreExcludedFromLocalizationMetrics() {
        val truth = GroundTruthAnnotation("C2", "desktop-frame", target,
            expectedBox = box, coordinateSpace = GroundingBoxSpace.SCREEN_COORDINATES)
        val metrics = GroundingResearchMetrics.evaluate(emptyList(), mapOf("C2" to truth))
        assertEquals(0, metrics.cases)
        assertNull(metrics.coverage)
    }

    @Test fun targetConditionedVisionPointRemainsProposalWithoutSemanticClaim() = runBlocking {
        val model = VisualGroundingProvider {
            VisualGroundingResult(null, GroundingGeometricEvidence(point = GroundingPoint(.5, .4),
                coordinateSpace = VisualGroundingCoordinateSpace.NORMALIZED_CROP), "fixture-model",
                GroundingProviderAvailability.AVAILABLE, metadata = mapOf("rawModelOutput" to "(500, 400)", "model" to "fixture-model"))
        }
        val result = EvidenceAdaptiveGroundingRouter(
            GroundingUiaProvider { error("UIA must not run") }, GroundingOcrProvider { error("OCR must not run") },
            ExistingVisionGroundingProvider(model)
        ).run(request(GroundingMode.VISION_ONLY))
        assertEquals(EvidenceDecision.REFINE, result.decision)
        assertEquals(GroundingDecisionReason.INSUFFICIENT_EVIDENCE, result.reason)
        assertEquals(GroundingBox(50.0, 32.0, 0.0, 0.0), result.proposedBox)
        assertNull(result.acceptedPoint)
        assertNull(result.acceptedBox)
    }

    @Test fun alignedDifferentSourceLabelsEscalateInsteadOfAveraging() {
        val observations = listOf(
            observation(GroundingProviderId.UIA, "uia", "Save", GroundingBox(10.0, 10.0, 80.0, 60.0)),
            observation(GroundingProviderId.OCR, "ocr", "Cancel", GroundingBox(35.0, 30.0, 24.0, 10.0))
        )
        val candidates = GroundingCandidateGenerator().generate(observations)
        assertEquals(2, candidates.size)
        val (decision, reason) = GroundingConfidenceEvaluator().decide(candidates, request(GroundingMode.UIA_OCR), emptyList())
        assertEquals(EvidenceDecision.ESCALATE, decision)
        assertEquals(GroundingDecisionReason.CONFLICTING_EVIDENCE, reason)
    }

    @Test fun punctuationOnlyOcrDoesNotConflictWithSupportedControlEvidence() {
        val observations = listOf(
            observation(GroundingProviderId.UIA, "uia-save", "Save", box),
            observation(GroundingProviderId.OCR, "ocr-border", "|", GroundingBox(20.0, 10.0, 1.0, 12.0))
        )
        val candidates = GroundingCandidateGenerator().generate(observations)

        assertEquals(EvidenceDecision.ACCEPT, GroundingConfidenceEvaluator().decide(
            candidates, request(GroundingMode.UIA_OCR), emptyList()
        ).first)
    }

    @Test fun unrelatedLowSimilarityCandidatesAreNotPresentedAsTargetProposals() {
        val candidate = GroundingCandidateGenerator().generate(listOf(
            observation(GroundingProviderId.OCR, "ocr-unrelated", "Settings", GroundingBox(4.0, 5.0, 20.0, 8.0))
        )).single()
        val result = GroundingResult(
            "TEST", null, "absent-run", "now", GroundingMode.OCR_ONLY, "Archive",
            image.width, image.height, setOf(GroundingProviderId.OCR), setOf(GroundingProviderId.OCR),
            setOf(GroundingProviderId.OCR), emptySet(), emptyMap(), emptyList(), listOf(candidate), null,
            EvidenceDecision.ABSTAIN, GroundingDecisionReason.NO_MATCH, "no_match", 1, GroundingThresholds()
        )

        assertNull(result.proposedCandidate)
        assertNull(result.proposedBox)
    }

    @Test fun missingTargetSkipsEveryProviderAndAllFailuresStayExplicit() = runBlocking {
        var calls = 0
        val skipped = EvidenceAdaptiveGroundingRouter(
            GroundingUiaProvider { calls++; error("unexpected") }, GroundingOcrProvider { calls++; error("unexpected") },
            GroundingVisionProvider { _, _ -> calls++; error("unexpected") }
        ).run(request(GroundingMode.ADAPTIVE).copy(targetDescription = "  "))
        assertEquals(0, calls)
        assertEquals(GroundingDecisionReason.INVALID_TARGET, skipped.reason)
        assertTrue(skipped.executedProviders.isEmpty())

        val failed = EvidenceAdaptiveGroundingRouter(
            GroundingUiaProvider { error("uia") }, GroundingOcrProvider { error("ocr") },
            GroundingVisionProvider { _, _ -> error("vision") }
        ).run(request(GroundingMode.ADAPTIVE))
        assertEquals(setOf(GroundingProviderId.UIA, GroundingProviderId.OCR, GroundingProviderId.VISION), failed.failedProviders)
        assertEquals(EvidenceDecision.ESCALATE, failed.decision)
        assertEquals(GroundingDecisionReason.PROVIDER_UNAVAILABLE, failed.reason)
    }

    @Test fun ocrEmptyResultIsDifferentFromExecutionFailure() = runBlocking {
        val empty = ExistingOcrGroundingProvider(OCRService {
            OcrResult("", null, engineName = "fixture", metadata = mapOf("status" to "empty"))
        }).collect(request(GroundingMode.OCR_ONLY))
        assertEquals(GroundingProviderStatus.EMPTY_RESULT, empty.status)
        val broken = ExistingOcrGroundingProvider(OCRService { error("native failure") }).collect(request(GroundingMode.OCR_ONLY))
        assertEquals(GroundingProviderStatus.EXECUTION_FAILED, broken.status)
    }

    @Test fun experimentStorePersistsIndependentRunAndGroundTruthMetricsCountAbstention() = runBlocking {
        val directory = Files.createTempDirectory("grounding-experiment-test")
        val store = GroundingExperimentStore(directory)
        val result = router(mutableListOf(), agreementEvidence()).run(request(GroundingMode.UIA_OCR).copy(caseId = "C1"))
        val persisted = result.copy(targetDescription = "Save \"button\"\nnext")
        store.persist(persisted, image)
        assertTrue(Files.isRegularFile(directory.resolve("phase5-experiments").resolve(result.runId).resolve("result.json")))
        assertTrue(Files.readString(directory.resolve("phase5-experiments/manifest.jsonl")).contains("UIA_OCR"))
        val record = store.readRecord(result.runId)!!
        assertTrue(record.contains("screenshots/"))
        assertTrue(store.loadScreenshot(result.runId) != null)
        val replay = assertNotNull(store.loadReplayRequest(result.runId, GroundingMode.OCR_ONLY))
        assertEquals(persisted.targetDescription, replay.targetDescription)
        assertEquals(GroundingMode.OCR_ONLY, replay.mode)
        val truth = GroundTruthAnnotation("C1", "fixture-reference", target, expectedBox = box)
        val metrics = GroundingResearchMetrics.evaluate(listOf(result), mapOf("C1" to truth))
        assertEquals(1, metrics.cases)
        assertEquals(1, metrics.accepted)
        assertEquals(1.0, metrics.coverage)
        assertEquals(1.0, metrics.meanIou)
    }

    @Test fun comparisonRunsAllModesAgainstSameScreenshotWithIsolatedRunIds() = runBlocking {
        val directory = Files.createTempDirectory("grounding-compare-test")
        val store = GroundingExperimentStore(directory)
        val runner = GroundingExperimentRunner(router(mutableListOf(), box), store)
        val base = request(GroundingMode.ADAPTIVE).copy(executionId = "ignored", caseId = "SAME_CASE", screenshotReference = "preview")
        val results = runner.compareAllModes(base)
        assertEquals(GroundingMode.values().toSet(), results.map { it.mode }.toSet())
        assertEquals(5, results.map { it.runId }.toSet().size)
        assertTrue(results.all { it.caseId == "SAME_CASE" && it.screenshotWidth == image.width && it.screenshotHeight == image.height })
        assertEquals(1L, Files.list(directory.resolve("phase5-experiments/screenshots")).use { it.count() })
        assertEquals(5L, Files.list(directory.resolve("phase5-experiments")).use { paths -> paths.filter { Files.isDirectory(it) && it.fileName.toString() != "screenshots" }.count() })
    }

    private fun router(
        calls: MutableList<GroundingProviderId>,
        evidence: GroundingBox,
        sink: GroundingResultSink? = null
    ) = EvidenceAdaptiveGroundingRouter(
        GroundingUiaProvider { calls += GroundingProviderId.UIA; GroundingProviderRun(GroundingProviderId.UIA, GroundingProviderStatus.SUCCESS, 1, listOf(observation(GroundingProviderId.UIA, "uia", "Save", evidence))) },
        GroundingOcrProvider { calls += GroundingProviderId.OCR; GroundingProviderRun(GroundingProviderId.OCR, GroundingProviderStatus.SUCCESS, 1, listOf(observation(GroundingProviderId.OCR, "ocr", "Save", evidence))) },
        GroundingVisionProvider { _, _ -> calls += GroundingProviderId.VISION; GroundingProviderRun(GroundingProviderId.VISION, GroundingProviderStatus.NOT_CONFIGURED, 0) },
        sink = sink
    )

    private fun agreementEvidence() = box
    private fun request(mode: GroundingMode, transform: ScreenToScreenshotTransform? = null) = GroundingRequest(
        image, target, mode, selectedRegionOnDesktop = Rectangle(0, 0, image.width, image.height),
        screenToScreenshot = transform, executionId = "test-${mode.name}", experimentId = "TEST", caseId = "C1"
    )
    private fun observation(source: GroundingProviderId, id: String, text: String, rect: GroundingBox) = GroundingObservation(
        source, id, text, target, rect, rect, GroundingBoxSpace.SCREENSHOT_PIXELS, GroundingBoxSpace.SCREENSHOT_PIXELS,
        null, textSimilarity(target, text), executionId = "test"
    )
}
