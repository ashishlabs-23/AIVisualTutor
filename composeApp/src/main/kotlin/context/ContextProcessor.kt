package context

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

class ContextProcessor(
    private val ocr: OCRService = defaultOcrService(),
    private val classifier: ContentClassifier = PlaceholderContentClassifier(),
    private val logger: ContextLogger = StderrContextLogger(),
    private val perceptionEngine: PerceptionEngine = defaultPerceptionEngine(),
    private val evidenceEvaluationCoordinator: EvidenceEvaluationCoordinator = EvidenceEvaluationCoordinator()
) {
    suspend fun process(
        image: BufferedImage,
        x: Int,
        y: Int,
        app: ApplicationContext?,
        regionId: UUID = UUID.randomUUID(),
        additionalMetadata: Map<String, String> = emptyMap(),
        selectedRegion: Rectangle = Rectangle(x, y, image.width, image.height),
        targetDescription: String? = null,
        requiresVisualGrounding: Boolean = false
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
            val attempt = try {
                PerceptionAttempt(perceptionEngine.perceive(PerceptionRequest(image, selectedRegion, app, targetDescription)), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PerceptionAttempt(emptyPerceptionResult(app, e), e.javaClass.simpleName)
            }
            stageTimes["perceptionMillis"] = ((System.nanoTime() - t) / 1_000_000).toString()
            attempt
        }
        val ocrAttempt = ocrJob.await()
        val ocrResult = ocrAttempt.getOrNull()
        val classification = classifierJob.await().getOrNull()
        val perceptionAttempt = perceptionJob.await()
        val uiAutomationStatus = uiAutomationEvidenceStatus(perceptionAttempt)
        val ocrStatus = ocrEvidenceStatus(ocrAttempt)
        val evidenceEvaluation = evidenceEvaluationCoordinator.evaluate(
            screenshot = image,
            applicationContext = app,
            targetDescription = targetDescription,
            uiAutomationEvidence = perceptionAttempt.result,
            uiAutomationStatus = uiAutomationStatus,
            ocrEvidence = ocrResult,
            ocrStatus = ocrStatus,
            requiresVisualGrounding = requiresVisualGrounding
        )
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
