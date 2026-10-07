package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class EvidenceEvaluatorTest {
    private val image = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)
    private val evaluator = EvidenceEvaluator()
    private val uiaSave = PerceptionResult(
        "Editor", "Save", null, UiType.BUTTON, Rectangle(200, 120, 40, 20),
        1.0f, PerceptionSource.UI_AUTOMATION
    )
    private val ocrSave = OcrResult(
        "Save", .9f, listOf(OcrWord("Save", .9f, Rectangle(10, 12, 28, 14))), "test-ocr"
    )

    @Test
    fun strongUiaOnlyEvidenceAcceptsWithoutInvokingVisualProvider() = runBlocking {
        var calls = 0
        val result = coordinator(provider { calls++; visualSave() }).evaluate(
            image, null, "Select Save button", uiaSave, EvidenceStatus.AVAILABLE,
            null, EvidenceStatus.NOT_RUN
        )

        assertEquals(EvidenceDecision.ACCEPT, result.decision)
        assertEquals(VisualGroundingInvocationStatus.NOT_NEEDED, result.visualGroundingInvocation)
        assertEquals(0, calls)
        assertSame(uiaSave, result.uiAutomationEvidence)
    }

    @Test
    fun strongOcrOnlyEvidenceAccepts() {
        val result = evaluate(ocr = ocrSave, ocrStatus = EvidenceStatus.AVAILABLE)

        assertEquals(EvidenceDecision.ACCEPT, result.decision)
        assertEquals("ocr_text_match", result.reason)
        assertSame(ocrSave, result.ocrEvidence)
    }

    @Test
    fun strongOcrCanResolveInsufficientUiaEvidence() {
        val result = evaluate(
            uia = uiaSave.copy(selectedObject = "Toolbar"),
            uiaStatus = EvidenceStatus.INSUFFICIENT,
            ocr = ocrSave,
            ocrStatus = EvidenceStatus.AVAILABLE
        )

        assertEquals(EvidenceDecision.ACCEPT, result.decision)
        assertEquals("ocr_text_match", result.reason)
    }

    @Test
    fun conflictingConfidentTextEscalates() {
        val result = evaluate(
            uia = uiaSave,
            uiaStatus = EvidenceStatus.AVAILABLE,
            ocr = OcrResult("Cancel", .92f, listOf(OcrWord("Cancel", .92f, Rectangle(1, 1, 20, 10))), "test-ocr"),
            ocrStatus = EvidenceStatus.AVAILABLE
        )

        assertEquals(EvidenceDecision.ESCALATE, result.decision)
        assertEquals("conflicting_high_confidence_source_text", result.reason)
    }

    @Test
    fun unrelatedDenseOcrTextDoesNotOverrideStrongUiaMatch() {
        val result = evaluate(
            uia = uiaSave,
            uiaStatus = EvidenceStatus.AVAILABLE,
            ocr = OcrResult(
                "File Edit View Project Settings Tools Open Cancel",
                .91f,
                listOf(OcrWord("File", .91f, Rectangle(1, 1, 10, 8))),
                "test-ocr"
            ),
            ocrStatus = EvidenceStatus.AVAILABLE
        )

        assertEquals(EvidenceDecision.ACCEPT, result.decision)
        assertEquals("structured_uia_match", result.reason)
    }

    @Test
    fun visualOnlyTargetEscalatesWhenOtherSourcesAreInsufficient() {
        val result = evaluator.evaluate(input(
            target = "Select star icon",
            uiaStatus = EvidenceStatus.INSUFFICIENT,
            ocrStatus = EvidenceStatus.INSUFFICIENT,
            providerAvailability = GroundingProviderAvailability.AVAILABLE,
            requiresVisual = true
        ))

        assertEquals(EvidenceDecision.ESCALATE, result.decision)
        assertEquals("visual_grounding_required", result.reason)
    }

    @Test
    fun incompleteEvidenceRoutesToRefineWhenProviderIsConfigured() {
        val result = evaluator.evaluate(input(
            target = "Select Save button",
            uiaStatus = EvidenceStatus.INSUFFICIENT,
            ocrStatus = EvidenceStatus.INSUFFICIENT,
            providerAvailability = GroundingProviderAvailability.AVAILABLE
        ))

        assertEquals(EvidenceDecision.REFINE, result.decision)
        assertEquals(VisualGroundingInvocationStatus.PENDING, result.visualGroundingInvocation)
    }

    @Test
    fun unconfiguredProviderIsExplicitAndDoesNotFabricateEvidence() = runBlocking {
        val result = coordinator(NotConfiguredVisualGroundingProvider()).evaluate(
            image, null, "Select star icon", null, EvidenceStatus.INSUFFICIENT,
            null, EvidenceStatus.INSUFFICIENT, requiresVisualGrounding = true
        )

        assertEquals(EvidenceDecision.ABSTAIN, result.decision)
        assertEquals(GroundingProviderAvailability.NOT_CONFIGURED, result.visualGroundingAvailability)
        assertEquals(VisualGroundingInvocationStatus.NOT_CONFIGURED, result.visualGroundingInvocation)
        assertEquals("no_model_or_runtime_configured", result.visualGroundingDiagnostic)
        assertNull(result.visualGroundingEvidence)
    }

    @Test
    fun unavailableProviderIsNotCalledAndIsRecorded() = runBlocking {
        var calls = 0
        val unavailable = object : VisualGroundingProvider {
            override val availability = GroundingProviderAvailability.UNAVAILABLE
            override val availabilityDiagnostic = "runtime_unavailable"
            override suspend fun ground(request: VisualGroundingRequest): VisualGroundingResult {
                calls++
                error("unavailable provider must not be called")
            }
        }
        val result = coordinator(unavailable).evaluate(
            image, null, "Select star icon", null, EvidenceStatus.INSUFFICIENT,
            null, EvidenceStatus.INSUFFICIENT, requiresVisualGrounding = true
        )

        assertEquals(0, calls)
        assertEquals(EvidenceDecision.ABSTAIN, result.decision)
        assertEquals(GroundingProviderAvailability.UNAVAILABLE, result.visualGroundingAvailability)
        assertEquals(VisualGroundingInvocationStatus.UNAVAILABLE, result.visualGroundingInvocation)
        assertEquals("runtime_unavailable", result.visualGroundingDiagnostic)
    }

    @Test
    fun strongVisualSemanticAndCropGeometryCanAccept() = runBlocking {
        val evidence = visualSave()
        val result = coordinator(provider { evidence }).evaluate(
            image, null, "Select Save button", null, EvidenceStatus.INSUFFICIENT,
            null, EvidenceStatus.INSUFFICIENT, requiresVisualGrounding = true
        )

        assertEquals(EvidenceDecision.ACCEPT, result.decision)
        assertEquals(VisualGroundingInvocationStatus.RETURNED_EVIDENCE, result.visualGroundingInvocation)
        assertSame(evidence, result.visualGroundingEvidence)
    }

    @Test
    fun insufficientEvidenceInvokesConfiguredProviderOnlyOnce() = runBlocking {
        var calls = 0
        val result = coordinator(provider { calls++; visualSave() }).evaluate(
            image, null, "Select Save button", null, EvidenceStatus.INSUFFICIENT,
            null, EvidenceStatus.INSUFFICIENT
        )

        assertEquals(1, calls)
        assertEquals(EvidenceDecision.ACCEPT, result.decision)
        assertEquals(VisualGroundingInvocationStatus.RETURNED_EVIDENCE, result.visualGroundingInvocation)
    }

    @Test
    fun lowConfidenceVisualEvidenceAbstains() = runBlocking {
        val weak = visualSave().copy(
            semantic = GroundingSemanticEvidence("Save", confidence = .5f),
            geometry = visualSave().geometry?.copy(confidence = .4f)
        )
        val result = coordinator(provider { weak }).evaluate(
            image, null, "Select Save button", null, EvidenceStatus.INSUFFICIENT,
            null, EvidenceStatus.INSUFFICIENT, requiresVisualGrounding = true
        )

        assertEquals(EvidenceDecision.ABSTAIN, result.decision)
        assertEquals("evidence_insufficient_after_visual_attempt", result.reason)
    }

    @Test
    fun missingTargetDoesNotCallVisualProvider() = runBlocking {
        var calls = 0
        val result = coordinator(provider { calls++; visualSave() }).evaluate(
            image, null, "  ", null, EvidenceStatus.INSUFFICIENT,
            null, EvidenceStatus.INSUFFICIENT, requiresVisualGrounding = true
        )

        assertEquals(0, calls)
        assertEquals(EvidenceDecision.ABSTAIN, result.decision)
        assertEquals(VisualGroundingInvocationStatus.NOT_INVOKED_MISSING_TARGET, result.visualGroundingInvocation)
        assertEquals("target_description_missing", result.reason)
    }

    @Test
    fun incompatibleCoordinateSpacesHaveNoGeometryComparison() {
        val crop = GroundingGeometricEvidence(
            bounds = GroundingBounds(0.0, 0.0, 10.0, 10.0),
            coordinateSpace = VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS
        )
        val physical = EvidenceGeometry(
            bounds = GroundingBounds(0.0, 0.0, 10.0, 10.0),
            coordinateSpace = EvidenceCoordinateSpace.PHYSICAL_DESKTOP_SCREEN
        )

        assertNull(geometryIou(crop, physical))
    }

    @Test
    fun cancellationPropagatesFromProvider() {
        val coordinator = coordinator(provider { throw CancellationException("test cancellation") })
        assertFailsWith<CancellationException> {
            runBlocking {
                coordinator.evaluate(
                    image, null, "Select star icon", null, EvidenceStatus.INSUFFICIENT,
                    null, EvidenceStatus.INSUFFICIENT, requiresVisualGrounding = true
                )
            }
        }
    }

    @Test
    fun explicitCancelledProviderOutcomeIsRecorded() = runBlocking {
        val cancelled = VisualGroundingResult(
            semantic = null,
            geometry = null,
            providerId = "TEST_FAKE_PROVIDER",
            availability = GroundingProviderAvailability.CANCELLED
        )
        val result = coordinator(provider { cancelled }).evaluate(
            image, null, "Select star icon", null, EvidenceStatus.INSUFFICIENT,
            null, EvidenceStatus.INSUFFICIENT, requiresVisualGrounding = true
        )

        assertEquals(GroundingProviderAvailability.CANCELLED, result.visualGroundingAvailability)
        assertEquals(VisualGroundingInvocationStatus.CANCELLED, result.visualGroundingInvocation)
        assertEquals(EvidenceDecision.ABSTAIN, result.decision)
    }

    @Test
    fun providerFailureIsRecordedAndDoesNotAbortContextProcessing() = runBlocking {
        val failing = coordinator(provider { error("provider broke") })
        val emptyOcr = OcrResult("", null, engineName = "test-ocr")
        val context = ContextProcessor(
            ocr = OCRService { emptyOcr },
            perceptionEngine = PerceptionEngine {
                PerceptionResult(null, null, null, UiType.UNKNOWN, null, null, PerceptionSource.UNKNOWN)
            },
            evidenceEvaluationCoordinator = failing
        ).process(
            image, 0, 0, null,
            targetDescription = "Select star icon",
            requiresVisualGrounding = true
        )

        assertSame(emptyOcr, context.ocrResult)
        assertEquals(EvidenceDecision.ABSTAIN, context.evidenceEvaluation?.decision)
        assertEquals(GroundingProviderAvailability.FAILURE, context.evidenceEvaluation?.visualGroundingAvailability)
        assertEquals(VisualGroundingInvocationStatus.FAILED, context.evidenceEvaluation?.visualGroundingInvocation)
        assertEquals("IllegalStateException", context.evidenceEvaluation?.visualGroundingDiagnostic)
    }

    @Test
    fun testFakeProviderHasExplicitTestOnlyIdentity() = runBlocking {
        val fake = provider { visualSave() }
        val result = coordinator(fake).evaluate(
            image, null, "Select Save button", null, EvidenceStatus.INSUFFICIENT,
            null, EvidenceStatus.INSUFFICIENT, requiresVisualGrounding = true
        )

        assertEquals("TEST_FAKE_PROVIDER", result.visualGroundingProviderId)
        assertTrue(result.visualGroundingProviderId != "REAL_VISUAL_GROUNDING_PROVIDER")
    }

    @Test
    fun contextProcessorPassesSuppliedTargetToPerceptionAndVisualProvider() = runBlocking {
        var perceptionTarget: String? = null
        var groundingRequest: VisualGroundingRequest? = null
        val context = ContextProcessor(
            ocr = OCRService { OcrResult("", null, engineName = "test-ocr") },
            perceptionEngine = PerceptionEngine { request ->
                perceptionTarget = request.targetDescription
                PerceptionResult(null, null, null, UiType.UNKNOWN, null, null, PerceptionSource.UNKNOWN)
            },
            evidenceEvaluationCoordinator = coordinator(provider {
                groundingRequest = it
                visualSave()
            })
        ).process(
            image, 0, 0, ApplicationContext("Editor", "editor.exe"),
            targetDescription = "Select Save button",
            requiresVisualGrounding = true
        )

        assertEquals("Select Save button", perceptionTarget)
        assertEquals("Select Save button", groundingRequest?.targetDescription)
        assertEquals(EvidenceDecision.ACCEPT, context.evidenceEvaluation?.decision)
        assertSame(context.ocrResult, groundingRequest?.ocrEvidence)
        assertSame(context.perceptionResult, groundingRequest?.uiAutomationEvidence)
    }

    private fun evaluate(
        uia: PerceptionResult? = null,
        uiaStatus: EvidenceStatus = EvidenceStatus.NOT_RUN,
        ocr: OcrResult? = null,
        ocrStatus: EvidenceStatus = EvidenceStatus.NOT_RUN
    ) = evaluator.evaluate(input(
        target = "Select Save button",
        uia = uia,
        uiaStatus = uiaStatus,
        ocr = ocr,
        ocrStatus = ocrStatus
    ))

    private fun input(
        target: String?,
        uia: PerceptionResult? = null,
        uiaStatus: EvidenceStatus = EvidenceStatus.NOT_RUN,
        ocr: OcrResult? = null,
        ocrStatus: EvidenceStatus = EvidenceStatus.NOT_RUN,
        providerAvailability: GroundingProviderAvailability = GroundingProviderAvailability.NOT_CONFIGURED,
        requiresVisual: Boolean = false
    ) = EvidenceEvaluationInput(
        target, uia, uiaStatus, ocr, ocrStatus,
        visualGroundingAvailability = providerAvailability,
        requiresVisualGrounding = requiresVisual,
        screenshotWidth = image.width,
        screenshotHeight = image.height
    )

    private fun visualSave() = VisualGroundingResult(
        semantic = GroundingSemanticEvidence("Save button", "button", .91f),
        geometry = GroundingGeometricEvidence(
            bounds = GroundingBounds(10.0, 12.0, 38.0, 26.0),
            coordinateSpace = VisualGroundingCoordinateSpace.CROP_IMAGE_PIXELS,
            confidence = .88f
        ),
        providerId = "TEST_FAKE_PROVIDER",
        availability = GroundingProviderAvailability.AVAILABLE,
        executionLocation = GroundingExecutionLocation.UNKNOWN
    )

    private fun coordinator(provider: VisualGroundingProvider) = EvidenceEvaluationCoordinator(provider)

    private fun provider(block: suspend (VisualGroundingRequest) -> VisualGroundingResult): VisualGroundingProvider =
        object : VisualGroundingProvider {
            override val providerId = "TEST_FAKE_PROVIDER"
            override suspend fun ground(request: VisualGroundingRequest) = block(request)
        }
}
