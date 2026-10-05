package context

import java.awt.image.BufferedImage
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

class ContextProcessor(
    private val ocr: OCRService = PlaceholderOcrService(),
    private val classifier: ContentClassifier = PlaceholderContentClassifier(),
    private val logger: ContextLogger = StderrContextLogger()
) {
    suspend fun process(image: BufferedImage, x: Int, y: Int, app: ApplicationContext?, regionId: UUID = UUID.randomUUID(), additionalMetadata: Map<String, String> = emptyMap()): VisualContext = withContext(Dispatchers.Default) {
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
        val ocrResult = ocrJob.await().getOrNull()
        val classification = classifierJob.await().getOrNull()
        val totalMillis = (System.nanoTime() - started) / 1_000_000
        val metadata = additionalMetadata + stageTimes.toMap() + mapOf("processingMillis" to totalMillis.toString(), "ocrEngine" to (ocrResult?.engineName ?: "failed")) +
            listOfNotNull(if (ocrResult == null) "ocrFailure" to "true" else null, if (classification == null) "classificationFailure" to "true" else null).toMap()
        val visual = VisualContext(id, image, x, y, image.width, image.height, System.currentTimeMillis(), ocrResult?.text, classification?.contentType, app, ocrResult?.confidence ?: classification?.confidence, metadata)
        logger.event(LifecycleEvent.CONTEXT_CREATED, mapOf("regionId" to id, "width" to image.width, "height" to image.height, "durationMillis" to totalMillis, "ocrCharacters" to (ocrResult?.text?.length ?: 0)))
        visual
    }
}
