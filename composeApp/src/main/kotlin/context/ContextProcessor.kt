package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

class ContextProcessor(
    private val ocr: OCRService = defaultOcrService(),
    private val classifier: ContentClassifier = PlaceholderContentClassifier(),
    private val logger: ContextLogger = StderrContextLogger(),
    private val perceptionEngine: PerceptionEngine = defaultPerceptionEngine(),
    private val evidenceEvaluationCoordinator: EvidenceEvaluationCoordinator = EvidenceEvaluationCoordinator(),
    private val calibrationResultSink: CalibrationResultSink = CalibrationResultStore.configuredSink()
) {
    suspend fun process(
        image: BufferedImage,
        x: Int,
        y: Int,
        app: ApplicationContext?,
        regionId: UUID = UUID.randomUUID(),
        additionalMetadata: Map<String, String> = emptyMap(),
        selectedRegion: Rectangle? = Rectangle(x, y, image.width, image.height),
        targetDescription: String? = null,
        requiresVisualGrounding: Boolean = false,
        calibrationAttempt: CalibrationAttempt? = null
    ): VisualContext {
        if (calibrationAttempt == null || calibrationResultSink === NoOpCalibrationResultSink) {
            return processCore(
                image, x, y, app, regionId, additionalMetadata, selectedRegion,
                targetDescription, requiresVisualGrounding, CalibrationProcessingTrace()
            )
        }
        val started = System.nanoTime()
        var completed: VisualContext? = null
        var failure: Throwable? = null
        val trace = CalibrationProcessingTrace()
        try {
            return processCore(
                image, x, y, app, regionId, additionalMetadata, selectedRegion,
                targetDescription, requiresVisualGrounding, trace
            ).also { completed = it }
        } catch (e: CancellationException) {
            failure = e
            throw e
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    withTimeout(CALIBRATION_PERSISTENCE_TIMEOUT_MILLIS) {
                        calibrationResultSink.persist(
                            CalibrationRunResult(
                                image = image,
                                attempt = calibrationAttempt,
                                selectedRegion = selectedRegion,
                                applicationContext = app,
                                visualContext = completed,
                                processingStartedNanos = started,
                                providerSnapshot = trace.snapshot(),
                                failureType = failure?.javaClass?.simpleName,
                                failureDiagnostic = failure?.message?.take(120)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                val safeCaseId = calibrationAttempt.caseId.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(48)
                System.err.println(
                    "CALIBRATION_PERSISTENCE_FAILURE caseId=$safeCaseId width=${image.width} height=${image.height} error=${e.javaClass.simpleName}"
                )
            }
        }
    }

    private suspend fun processCore(
        image: BufferedImage,
        x: Int,
        y: Int,
        app: ApplicationContext?,
        regionId: UUID,
        additionalMetadata: Map<String, String>,
        selectedRegion: Rectangle?,
        targetDescription: String?,
        requiresVisualGrounding: Boolean,
        trace: CalibrationProcessingTrace
    ): VisualContext = withContext(Dispatchers.Default) {
        val id = regionId
        logger.event(LifecycleEvent.PROCESSING_STARTED, mapOf("regionId" to id, "state" to "PROCESSING"))
        val stageTimes = java.util.concurrent.ConcurrentHashMap<String, String>()
        val started = System.nanoTime()
        val ocrJob = async {
            val t = System.nanoTime()
            val result = try { Result.success(ocr.recognize(image)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            stageTimes["ocrMillis"] = ((System.nanoTime() - t) / 1_000_000).toString()
            result
        }
        val classifierJob = async {
            val t = System.nanoTime()
            val result = try { Result.success(classifier.classify(image, null)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
            stageTimes["classificationMillis"] = ((System.nanoTime() - t) / 1_000_000).toString()
            result
        }
        val perceptionJob = async {
            val t = System.nanoTime()
            val attempt = if (selectedRegion == null) {
                PerceptionAttempt(missingWindowPerceptionResult(app), null)
            } else {
                try {
                    PerceptionAttempt(perceptionEngine.perceive(PerceptionRequest(image, selectedRegion, app, targetDescription)), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PerceptionAttempt(emptyPerceptionResult(app, e), e.javaClass.simpleName)
                }
            }
            stageTimes["perceptionMillis"] = ((System.nanoTime() - t) / 1_000_000).toString()
            attempt
        }
        val ocrAttempt = ocrJob.await()
        val ocrResult = ocrAttempt.getOrNull()
        trace.ocrEvidence = ocrResult
        trace.ocrStatus = ocrEvidenceStatus(ocrAttempt)
        trace.ocrLatencyMillis = stageTimes["ocrMillis"]?.toLongOrNull()
        val classification = classifierJob.await().getOrNull()
        val perceptionAttempt = perceptionJob.await()
        val uiAutomationStatus = uiAutomationEvidenceStatus(perceptionAttempt)
        val ocrStatus = ocrEvidenceStatus(ocrAttempt)
        trace.uiAutomationEvidence = perceptionAttempt.result
        trace.uiAutomationStatus = uiAutomationStatus
        trace.uiAutomationLatencyMillis = stageTimes["perceptionMillis"]?.toLongOrNull()
        val evidenceEvaluation = try {
            evidenceEvaluationCoordinator.evaluate(
                screenshot = image,
                applicationContext = app,
                targetDescription = targetDescription,
                uiAutomationEvidence = perceptionAttempt.result,
                uiAutomationStatus = uiAutomationStatus,
                ocrEvidence = ocrResult,
                ocrStatus = ocrStatus,
                requiresVisualGrounding = requiresVisualGrounding,
                onVisualInvocationState = { preflight, requested, attempted ->
                    trace.visualGroundingPreflight = preflight
                    trace.visualGroundingInvocationRequested = requested
                    trace.visualGroundingInvocationAttempted = attempted
                }
            )
        } catch (e: CancellationException) {
            trace.visualGroundingStatus = GroundingProviderAvailability.CANCELLED
            trace.visualGroundingInvocation = VisualGroundingInvocationStatus.CANCELLED
            throw e
        }
        trace.evaluation = evidenceEvaluation
        val totalMillis = (System.nanoTime() - started) / 1_000_000
        val ocrMetadata = ocrResult?.metadata.orEmpty().mapKeys { (key, _) -> "ocr${key.replaceFirstChar(Char::uppercase)}" }
        val metadata = additionalMetadata + stageTimes.toMap() + ocrMetadata + mapOf("processingMillis" to totalMillis.toString(), "ocrEngine" to (ocrResult?.engineName ?: "failed")) +
            listOfNotNull(
                if (ocrResult == null) "ocrFailure" to "true" else null,
                if (classification == null) "classificationFailure" to "true" else null,
                if (perceptionAttempt.failure != null) "perceptionFailure" to "true" else null,
                perceptionAttempt.failure?.let { "perceptionDiagnostic" to it }
            ).toMap()
        val visual = VisualContext(
            id, image, x, y, image.width, image.height, System.currentTimeMillis(),
            ocrResult?.text, classification?.contentType, app,
            ocrResult?.confidence ?: classification?.confidence, metadata, perceptionAttempt.result, ocrResult, evidenceEvaluation
        )
        logger.event(LifecycleEvent.CONTEXT_CREATED, mapOf("regionId" to id, "width" to image.width, "height" to image.height, "durationMillis" to totalMillis, "ocrCharacters" to (ocrResult?.text?.length ?: 0)))
        visual
    }

    companion object {
        const val CALIBRATION_PERSISTENCE_TIMEOUT_MILLIS = 2_000L
    }

    private class CalibrationProcessingTrace {
        var uiAutomationEvidence: PerceptionResult? = null
        var uiAutomationStatus: EvidenceStatus? = null
        var uiAutomationLatencyMillis: Long? = null
        var ocrEvidence: OcrResult? = null
        var ocrStatus: EvidenceStatus? = null
        var ocrLatencyMillis: Long? = null
        var evaluation: EvidenceEvaluationResult? = null
        var visualGroundingStatus: GroundingProviderAvailability? = null
        var visualGroundingInvocation: VisualGroundingInvocationStatus? = null
        var visualGroundingPreflight: VisualGroundingPreflight? = null
        var visualGroundingInvocationRequested = false
        var visualGroundingInvocationAttempted = false

        fun snapshot() = CalibrationProviderSnapshot(
            uiAutomationEvidence, uiAutomationStatus, uiAutomationLatencyMillis,
            ocrEvidence, ocrStatus, ocrLatencyMillis, evaluation,
            visualGroundingStatus, visualGroundingInvocation, visualGroundingPreflight,
            visualGroundingInvocationRequested, visualGroundingInvocationAttempted
        )
    }

    private data class PerceptionAttempt(val result: PerceptionResult, val failure: String?)

    private fun uiAutomationEvidenceStatus(attempt: PerceptionAttempt): EvidenceStatus {
        if (attempt.failure != null) return EvidenceStatus.FAILURE
        if (attempt.result.source == PerceptionSource.UI_AUTOMATION && attempt.result.boundingRectangle != null) {
            return EvidenceStatus.AVAILABLE
        }
        return when (attempt.result.metadata["status"]) {
            "unsupported_platform", "missing_window_handle", "coordinate_mapping_unsupported" -> EvidenceStatus.UNAVAILABLE
            "uia_query_failed", "provider_failure" -> EvidenceStatus.FAILURE
            else -> EvidenceStatus.INSUFFICIENT
        }
    }

    private fun ocrEvidenceStatus(attempt: Result<OcrResult>): EvidenceStatus {
        val result = attempt.getOrNull() ?: return EvidenceStatus.FAILURE
        return when {
            result.metadata["availability"] == "unavailable" -> EvidenceStatus.UNAVAILABLE
            result.metadata["status"] == "recognition_failed" -> EvidenceStatus.FAILURE
            result.text.isNotBlank() && result.words.isNotEmpty() -> EvidenceStatus.AVAILABLE
            else -> EvidenceStatus.INSUFFICIENT
        }
    }

    private fun emptyPerceptionResult(app: ApplicationContext?, error: Exception) = PerceptionResult(
        applicationName = app?.applicationName,
        selectedObject = null,
        visibleText = null,
        uiType = UiType.UNKNOWN,
        boundingRectangle = null,
        confidence = null,
        source = PerceptionSource.UNKNOWN,
        metadata = mapOf("status" to "provider_failure", "diagnostic" to error.javaClass.simpleName)
    )

    private fun missingWindowPerceptionResult(app: ApplicationContext?) = PerceptionResult(
        applicationName = app?.applicationName,
        selectedObject = null,
        visibleText = null,
        uiType = UiType.UNKNOWN,
        boundingRectangle = null,
        confidence = null,
        source = PerceptionSource.UI_AUTOMATION,
        metadata = mapOf("status" to "missing_window_handle")
    )
}

private fun defaultOcrService(): OCRService =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) TesseractOcrService()
    else PlaceholderOcrService()

private fun defaultPerceptionEngine(): PerceptionEngine =
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        WindowsUiAutomationPerceptionEngine()
    } else {
        PerceptionEngine { request ->
            PerceptionResult(
                applicationName = request.applicationContext?.applicationName,
                selectedObject = null,
                visibleText = null,
                uiType = UiType.UNKNOWN,
                boundingRectangle = null,
                confidence = null,
                source = PerceptionSource.UNKNOWN,
                metadata = mapOf("status" to "unsupported_platform")
            )
        }
    }
